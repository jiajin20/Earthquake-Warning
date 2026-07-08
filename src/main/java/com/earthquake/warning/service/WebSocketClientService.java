package com.earthquake.warning.service;

import com.earthquake.warning.model.EarthquakeRecord;
import com.earthquake.warning.model.MonitoredCity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Wolfx CENC 地震信息 WebSocket 客户端（v5.6 — 线程安全 + 内存泄漏修复）
 */
@Slf4j
@Service
public class WebSocketClientService {

    private static final int RAW_MSG_BUFFER_SIZE = 300;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int JSON_PREVIEW_MAX = 200;
    /** latestRecords 清理周期：超过此时间的记录从内存中移除 */
    private static final long RECORD_TTL_HOURS = 48;

    private final EarthquakeCalculateService calculateService;
    private final BarkNotificationService barkService;
    private final RuntimeConfigService configService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    /** 保护 RestTemplate 并发调用的锁（默认非线程安全） */
    private final Object restLock = new Object();

    /** 代理模式 OkHttpClient — 仅在 proxyEnabled=true 时构造，wolfx.jp 走 SOCKS5 代理（DNS 远程解析） */
    private volatile OkHttpClient proxyHttpClient;

    private WebSocketClient client;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 正在建立 WebSocket 连接中（防止重连与主动连接并发） */
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile boolean wsConnected = false;
    private volatile boolean initialDataReceived = false;

    private final Map<String, EarthquakeRecord> latestRecords = new ConcurrentHashMap<>();
    private final Map<String, Long> processedEvents = new ConcurrentHashMap<>();

    private ScheduledExecutorService reconnectExecutor;
    private ScheduledExecutorService pingExecutor;
    private ScheduledExecutorService pollExecutor;
    private ScheduledFuture<?> pollFuture;
    private final AtomicLong lastDataTimestamp = new AtomicLong(0);
    private final List<EarthquakeListener> listeners = new CopyOnWriteArrayList<>();

    /** 原始消息环形缓冲区 — 供 Web 页面实时展示 */
    private final List<Map<String, Object>> rawMessageBuffer = new CopyOnWriteArrayList<>();

    public WebSocketClientService(EarthquakeCalculateService calculateService,
                                  BarkNotificationService barkService,
                                  RuntimeConfigService configService,
                                  ObjectMapper objectMapper) {
        this.calculateService = calculateService;
        this.barkService = barkService;
        this.configService = configService;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(8000);
        this.restTemplate = new RestTemplate(factory);
    }

    @PostConstruct
    public void init() {
        if (!configService.isAutoConnect()) {
            log.info("autoConnect=false，不启动连接");
            return;
        }
        if ("http".equals(configService.getPrimaryChannel())) {
            log.info("主通道=HTTP轮询，直接启动HTTP轮询");
            startHttpPolling();
            return;
        }
        connect();
    }

    @PreDestroy
    public void destroy() { disconnect(); }

    // ==================== 代理分流 ====================

    /**
     * 判断是否需要走代理。当前仅当路由目标为 wolfx.jp 且配置了代理时启用。
     * Bark 推送和其他目标直连不受影响。
     */
    private boolean shouldUseProxy() {
        if (!configService.isProxyEnabled()) return false;
        String host = configService.getProxyHost();
        return host != null && !host.isBlank();
    }

    /** 获取或创建代理 OkHttpClient（SOCKS5 代理，OkHttp 将 DNS 交给代理解析 = socks5h） */
    private OkHttpClient getOrCreateProxyHttpClient() {
        if (proxyHttpClient != null) return proxyHttpClient;
        synchronized (restLock) {
            if (proxyHttpClient != null) return proxyHttpClient;
            proxyHttpClient = new OkHttpClient.Builder()
                    .proxy(new Proxy(Proxy.Type.SOCKS,
                            new InetSocketAddress(configService.getProxyHost(), configService.getProxyPort())))
                    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            log.info("代理 OkHttpClient (SOCKS5) 已创建: {}:{}", configService.getProxyHost(), configService.getProxyPort());
            return proxyHttpClient;
        }
    }

    /** 重置代理客户端缓存，代理配置变更时调用 */
    public void resetProxyHttpClient() {
        proxyHttpClient = null;
        log.info("代理客户端缓存已重置");
    }

    /**
     * 通过 HTTP(S) 获取 wolfx.jp 数据。
     * 代理启用时走 OkHttp-SOCKS5（DNS 远程解析），否则走 RestTemplate 直连。
     */
    private String httpFetchWolfxData() throws Exception {
        String url = configService.getRestApiUrl();
        if (shouldUseProxy()) {
            OkHttpClient client = getOrCreateProxyHttpClient();
            Request request = new Request.Builder().url(url).header("User-Agent", "earthquake-warning/1.0").build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                }
            }
            return null;
        } else {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            return null;
        }
    }

    public void addListener(EarthquakeListener listener) { listeners.add(listener); }
    public void removeListener(EarthquakeListener listener) { listeners.remove(listener); }

    public synchronized void connect() {
        if ("http".equals(configService.getPrimaryChannel())) {
            log.info("主通道=HTTP轮询，跳过WebSocket连接");
            startHttpPolling();
            return;
        }
        if (running.get() || connecting.get()) {
            log.info("WebSocket 正在运行或正在连接中，跳过");
            return;
        }
        connecting.set(true);
        initialDataReceived = false;
        try {
            String url = configService.getWebsocketUrl();
            URI uri = new URI(url);
            client = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    // 连接成功后才标记 running=true（修复 P0-2）
                    running.set(true);
                    connecting.set(false);
                    log.info("✓ WebSocket 已连接 — HTTP {}", handshake.getHttpStatus());
                    wsConnected = true;
                    lastDataTimestamp.set(System.currentTimeMillis());
                    stopHttpPolling();
                    appendRawMessage("connected", "✓ WebSocket 连接成功 (HTTP " + handshake.getHttpStatus() + ")");

                    // 主动请求一次初始数据
                    try {
                        send("query_cenceqlist");
                        log.info("已发送 query_cenceqlist 请求初始数据");
                    } catch (Exception e) {
                        log.warn("发送 query_cenceqlist 失败: {}", e.getMessage());
                    }
                }
                @Override
                public void onMessage(String message) {
                    lastDataTimestamp.set(System.currentTimeMillis());
                    handleMessage(message);
                }
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.warn("✗ WebSocket 关闭 — code:{} reason:{}", code, reason);
                    running.set(false);
                    connecting.set(false);
                    wsConnected = false;
                    initialDataReceived = false;
                    appendRawMessage("closed", "✗ WebSocket 断开 (code=" + code + ", reason=" + reason + ")");
                    startHttpPolling();
                    scheduleReconnect();
                }
                @Override
                public void onError(Exception ex) {
                    log.error("✗ WebSocket 错误: {}", ex.getMessage());
                    running.set(false);
                    connecting.set(false);
                    wsConnected = false;
                    appendRawMessage("error", "✗ WebSocket 错误: " + ex.getMessage());
                    startHttpPolling();
                }
            };
            // 条件代理：wolfx.jp 走代理，其他直连
            if (shouldUseProxy()) {
                client.setProxy(new Proxy(Proxy.Type.SOCKS,
                        new InetSocketAddress(configService.getProxyHost(), configService.getProxyPort())));
                log.info("WebSocket 已设置代理 (SOCKS5): {}:{}", configService.getProxyHost(), configService.getProxyPort());
            }
            client.setConnectionLostTimeout(60);
            client.connect();
            log.info("正在连接 Wolfx WebSocket: {}", url);
            startPingKeepalive();
        } catch (Exception e) {
            connecting.set(false);
            log.error("创建 WebSocket 连接失败: {}", e.getMessage());
            appendRawMessage("error", "✗ 创建连接失败: " + e.getMessage());
            startHttpPolling();
            scheduleReconnect();
        }
    }

    public synchronized void disconnect() {
        running.set(false);
        connecting.set(false);
        stopPingKeepalive();
        stopReconnect();
        stopHttpPolling();
        if (client != null && client.isOpen()) client.close();
        wsConnected = false;
        initialDataReceived = false;
    }

    public boolean isConnected() { return wsConnected; }
    public boolean isInitialDataReceived() { return initialDataReceived; }
    public long getMillisSinceLastData() {
        long last = lastDataTimestamp.get();
        return last == 0 ? -1 : System.currentTimeMillis() - last;
    }

    /** 主通道切换后调用：断开旧通道、启动新通道 */
    public synchronized void switchPrimaryChannel() {
        String channel = configService.getPrimaryChannel();
        log.info("主通道切换为: {}", channel);
        if ("http".equals(channel)) {
            // 切换到 HTTP 主通道：断开 WSS，启动轮询
            disconnect();
            startHttpPolling();
        } else {
            // 切换到 WSS 主通道：启动 WSS 连接
            connect();
        }
    }

    /** 数据源切换后调用：断开旧连接、用新 URL 重连（v5.7） */
    public synchronized void reconnect() {
        log.info("数据源切换 — 断开旧连接，准备用新 URL 重连...");
        disconnect();
        // 重置去重表，避免新旧数据源的 eventId 格式不同导致冲突
        processedEvents.clear();
        // 清空旧记录，新数据源数据完全覆盖
        latestRecords.clear();
        // 短暂延迟确保旧连接完全释放
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (configService.isAutoConnect()) {
            connect();
        } else {
            log.info("autoConnect=false，数据源 URL 已更新，但不自动连接。手动调用 POST /connect 启动。");
        }
    }

    /** 获取最新地震记录，按发震时间降序排列（最新的在最前） */
    public List<EarthquakeRecord> getLatestRecords() {
        return latestRecords.values().stream()
                .sorted(EarthquakeRecord.byTimeDesc())
                .collect(Collectors.toList());
    }

    /** 获取原始消息缓冲区（最近 N 条） */
    public List<Map<String, Object>> getRawMessages(int limit) {
        int size = rawMessageBuffer.size();
        int start = Math.max(0, size - limit);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = start; i < size; i++) result.add(rawMessageBuffer.get(i));
        return result;
    }

    public void queryLatest() {
        if (client != null && client.isOpen()) {
            try {
                client.send("query_cenceqlist");
                appendRawMessage("query", "手动查询 → 发送 query_cenceqlist");
                log.info("已发送 query_cenceqlist");
            } catch (Exception e) {
                appendRawMessage("error", "查询发送失败: " + e.getMessage());
            }
        } else {
            log.warn("WebSocket 未连接，通过 HTTP 轮询获取");
            try {
                String body = httpFetchWolfxData();
                if (body != null) {
                    lastDataTimestamp.set(System.currentTimeMillis());
                    handleMessage(body);
                    appendRawMessage("http-query", "HTTP 查询获取数据 (" + body.length() + " bytes)");
                }
            } catch (Exception ex) {
                appendRawMessage("error", "HTTP 查询失败: " + ex.getMessage());
            }
        }
    }

    // ==================== 保活 Ping ====================

    private void startPingKeepalive() {
        stopPingKeepalive();
        pingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-ping"); t.setDaemon(true); return t;
        });
        pingExecutor.scheduleAtFixedRate(() -> {
            if (client != null && client.isOpen()) {
                try { client.send("ping"); } catch (Exception e) { /* ignore */ }
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    private void stopPingKeepalive() {
        if (pingExecutor != null && !pingExecutor.isShutdown()) pingExecutor.shutdownNow();
    }

    // ==================== HTTP 轮询兜底 ====================

    private synchronized void startHttpPolling() {
        if (pollFuture != null && !pollFuture.isDone()) return;
        stopHttpPolling();
        pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "http-poll"); t.setDaemon(true); return t;
        });
        int interval = configService.getPollingIntervalMs();
        pollFuture = pollExecutor.scheduleAtFixedRate(() -> {
            boolean isHttpPrimary = "http".equals(configService.getPrimaryChannel());
            if (!isHttpPrimary && wsConnected && running.get()) {
                stopHttpPolling(); return;
            }
            try {
                String body = httpFetchWolfxData();
                if (body != null) {
                    lastDataTimestamp.set(System.currentTimeMillis());
                    handleMessage(body);
                    appendRawMessage("poll", "HTTP 轮询获取数据 (" + body.length() + " bytes)");
                }
            } catch (Exception e) {
                log.warn("HTTP 轮询失败: {} — {}", e.getClass().getSimpleName(), e.getMessage());
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
        log.info("HTTP 轮询已启动（间隔 {}ms）", interval);
    }

    private synchronized void stopHttpPolling() {
        if (pollFuture != null) { pollFuture.cancel(false); pollFuture = null; }
        if (pollExecutor != null && !pollExecutor.isShutdown()) pollExecutor.shutdownNow();
    }

    // ==================== 重连 ====================

    private void scheduleReconnect() {
        if ("http".equals(configService.getPrimaryChannel())) {
            log.debug("主通道=HTTP轮询，跳过WebSocket重连");
            return;
        }
        if (running.get() || connecting.get()) return;
        synchronized (this) {
            if (running.get() || connecting.get()) return; // double-check
            stopReconnect();
            reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect"); t.setDaemon(true); return t;
            });
            reconnectExecutor.schedule(() -> {
                log.info("尝试重连 WebSocket...");
                if (client != null && client.isOpen()) {
                    try { client.close(); } catch (Exception ignored) {}
                }
                // 确保状态已重置再调用 connect
                running.set(false);
                connecting.set(false);
                connect();
            }, configService.getReconnectInterval(), TimeUnit.SECONDS);
        }
    }

    private void stopReconnect() {
        if (reconnectExecutor != null && !reconnectExecutor.isShutdown()) reconnectExecutor.shutdownNow();
    }

    // ==================== 原始消息缓冲区 ====================

    private void appendRawMessage(String type, String summary) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", LocalDateTime.now().format(TIME_FMT));
        entry.put("type", type);
        entry.put("summary", summary);
        rawMessageBuffer.add(entry);
        while (rawMessageBuffer.size() > RAW_MSG_BUFFER_SIZE) {
            rawMessageBuffer.remove(0);
        }
    }

    private void appendRawMessageWithPreview(String type, String summary, String jsonPreview) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", LocalDateTime.now().format(TIME_FMT));
        entry.put("type", type);
        entry.put("summary", summary);
        if (jsonPreview != null && !jsonPreview.isEmpty()) {
            entry.put("preview", jsonPreview);
        }
        rawMessageBuffer.add(entry);
        while (rawMessageBuffer.size() > RAW_MSG_BUFFER_SIZE) {
            rawMessageBuffer.remove(0);
        }
    }

    // ==================== 消息处理 ====================

    private void handleMessage(String message) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty()) return;

        // 尝试 JSON 解析
        try {
            JsonNode root = objectMapper.readTree(trimmed);

            // --- 检测顶层 type 字段 ---
            String msgType = root.has("type") ? root.get("type").asText() : "";

            if ("heartbeat".equals(msgType)) {
                appendRawMessage("heartbeat", "收到服务端心跳 → 回复 ping");
                if (client != null && client.isOpen()) {
                    try { client.send("ping"); } catch (Exception e) { /* ignore */ }
                }
                return;
            }
            if ("pong".equals(msgType)) {
                appendRawMessage("pong", "收到 pong 响应");
                return;
            }

            // --- 不是控制消息，当作地震数据解析 ---
            // 先尝试 No1-No50 格式（对象 key）
            int recordCount = parseEarthquakeRecords(root);

            if (recordCount > 0) {
                initialDataReceived = true;
                appendRawMessage("data", "收到 " + recordCount + " 条地震数据 (No1-No" + recordCount + ")");
            } else {
                // 尝试扁平单对象格式（新数据源）：直接反序列化为单条 EarthquakeRecord
                int flatCount = parseSingleEarthquakeRecord(root);
                if (flatCount > 0) {
                    initialDataReceived = true;
                    appendRawMessage("data", "收到 1 条地震数据 (扁平格式)");
                } else {
                    // 0 条记录 — 可能是未知格式，展示预览帮助排查
                    String preview = truncateJson(trimmed);
                    appendRawMessage("unknown", "收到非标准格式消息 (" + trimmed.length() + " bytes) — 预览: " + preview);
                }
            }

        } catch (Exception e) {
            // 非 JSON 消息 — 可能是纯文本
            log.debug("非 JSON 消息 ({} chars): {}", trimmed.length(), truncateJson(trimmed));
            String lower = trimmed.toLowerCase();
            if (lower.contains("ping") || lower.contains("pong")) {
                appendRawMessage("text", "文本消息: " + trimmed);
            } else {
                appendRawMessage("text-unknown", "非 JSON 消息 (" + trimmed.length() + " chars): " + truncateJson(trimmed));
            }
        }
    }

    private String truncateJson(String json) {
        if (json == null || json.isEmpty()) return "(empty)";
        // 压缩换行和多余空白
        String compact = json.replaceAll("\\s+", " ");
        if (compact.length() <= JSON_PREVIEW_MAX) return compact;
        return compact.substring(0, JSON_PREVIEW_MAX) + "...";
    }

    private int parseEarthquakeRecords(JsonNode root) {
        List<MonitoredCity> enabledCities = configService.getEnabledCities();
        if (enabledCities.isEmpty()) {
            log.warn("没有启用的监测城市，跳过计算");
            return 0;
        }

        // ===== 第 1 步：解析所有记录 + 缓存原始 JsonNode =====
        Map<String, EarthquakeRecord> rawRecords = new LinkedHashMap<>(); // eventId → record
        Map<String, JsonNode> nodeCache = new LinkedHashMap<>();          // NoX → JsonNode（避免重复 treeToValue）

        for (int i = 1; i <= 100; i++) {
            String key = "No" + i;
            JsonNode node = root.get(key);
            if (node == null) continue;

            try {
                EarthquakeRecord record = objectMapper.treeToValue(node, EarthquakeRecord.class);
                record.setNo(key);

                // 去重：用 EventID，保留时间最新的那条
                String eventId = record.getEventId();
                if (eventId != null) {
                    EarthquakeRecord existing = rawRecords.get(eventId);
                    if (existing != null) {
                        if (record.getTimeAsDateTime().isAfter(existing.getTimeAsDateTime())) {
                            rawRecords.put(eventId, record);
                            nodeCache.put(eventId, node); // 更新缓存
                        }
                        continue;
                    }
                    rawRecords.put(eventId, record);
                    nodeCache.put(eventId, node);
                } else {
                    rawRecords.put(key, record);
                    nodeCache.put(key, node);
                }
            } catch (Exception e) {
                log.debug("跳过记录 {}: {}", key, e.getMessage());
            }
        }

        // ===== 第 2 步：按发震时间降序排列（最新在前） =====
        List<EarthquakeRecord> sortedRecords = rawRecords.values().stream()
                .sorted(EarthquakeRecord.byTimeDesc())
                .collect(Collectors.toList());

        if (!sortedRecords.isEmpty()) {
            log.info("地震记录已按时间排序，最新: {} M{} {} ，最旧: {} M{} {}",
                    sortedRecords.get(0).getTime(),
                    sortedRecords.get(0).getMagnitude(),
                    sortedRecords.get(0).getLocation(),
                    sortedRecords.get(sortedRecords.size() - 1).getTime(),
                    sortedRecords.get(sortedRecords.size() - 1).getMagnitude(),
                    sortedRecords.get(sortedRecords.size() - 1).getLocation());
        }

        // ===== 第 3 步：按时间顺序处理（最新 → 最旧），从缓存取出 JSON node 重建 =====
        List<EarthquakeRecord> newWarnings = new ArrayList<>();
        int count = 0;

        for (EarthquakeRecord baseRecord : sortedRecords) {
            String eventId = baseRecord.getEventId();
            String key = baseRecord.getNo();
            String cacheKey = (eventId != null) ? eventId : key;
            JsonNode cachedNode = nodeCache.get(cacheKey);

            // 跳过已处理过的事件
            if (eventId != null && processedEvents.containsKey(eventId)) {
                continue;
            }

            // 时效性检查：超过预警窗口的地震不再推送（S 波早已到达所有人）
            long ageMinutes = java.time.Duration.between(baseRecord.getTimeAsDateTime(), LocalDateTime.now()).toMinutes();
            if (ageMinutes > configService.getMaxWarningAgeMinutes()) {
                log.debug("⏰ 跳过过期地震 [{} {} M{}] — 已发生 {} 分钟 > {} 分钟预警窗口",
                        baseRecord.getTime(), baseRecord.getLocation(), baseRecord.getMagnitude(),
                        ageMinutes, configService.getMaxWarningAgeMinutes());
                continue;
            }

            // 对每个启用城市计算
            boolean anyCalculated = false;
            for (MonitoredCity city : enabledCities) {
                EarthquakeRecord record;
                try {
                    if (cachedNode != null) {
                        record = objectMapper.treeToValue(cachedNode, EarthquakeRecord.class);
                        record.setNo(key);
                    } else {
                        // fallback：从 root 重建
                        JsonNode node = root.get(key);
                        if (node == null) continue;
                        record = objectMapper.treeToValue(node, EarthquakeRecord.class);
                        record.setNo(key);
                    }
                } catch (Exception e) {
                    log.debug("重建记录失败 {}: {}", key, e.getMessage());
                    continue;
                }
                calculateService.calculate(record, city);
                String uniqueKey = (eventId != null ? eventId : key) + ":" + city.getId();
                latestRecords.put(uniqueKey, record);
                anyCalculated = true;
                count++;

                if (calculateService.shouldWarn(record)) {
                    newWarnings.add(record);
                    log.info("⚠ 地震预警 [{}] - 发震:{}, 震中:{}, M{}, 距离:{}km, 影响:{}",
                            city.getName(), record.getTime(), record.getLocation(), record.getMagnitude(),
                            record.getEpicenterDistance() != null ? String.format("%.0f", record.getEpicenterDistance()) : "?",
                            record.getImpactLevelDesc());
                }
            }

            if (eventId != null && anyCalculated) {
                processedEvents.put(eventId, System.currentTimeMillis());
            }
        }

        // 清理 processedEvents（48 小时前，与 RECORD_TTL_HOURS 一致，防止历史地震被"遗忘"后重新推送）
        long eventCutoff = System.currentTimeMillis() - RECORD_TTL_HOURS * 3600_000;
        processedEvents.entrySet().removeIf(e -> e.getValue() < eventCutoff);

        LocalDateTime recordCutoff = LocalDateTime.now().minusHours(RECORD_TTL_HOURS);
        latestRecords.entrySet().removeIf(e -> {
            EarthquakeRecord r = e.getValue();
            return r.getTimeAsDateTime().isBefore(recordCutoff);
        });

        // ===== 第 4 步：只推送最新一条地震预警（异步，不阻塞 WS 消息处理） =====
        if (!newWarnings.isEmpty()) {
            // 按时间降序排列，最新地震在最前
            List<EarthquakeRecord> sortedWarnings = newWarnings.stream()
                    .sorted(EarthquakeRecord.byTimeDesc())
                    .collect(Collectors.toList());

            // 只保留最新一条地震（相同 eventId）的预警
            String latestEventId = sortedWarnings.get(0).getEventId();
            List<EarthquakeRecord> latestOnly = sortedWarnings.stream()
                    .filter(r -> java.util.Objects.equals(r.getEventId(), latestEventId))
                    .collect(Collectors.toList());

            // 同一 eventId + 城市去重
            Map<String, EarthquakeRecord> deduped = new LinkedHashMap<>();
            for (EarthquakeRecord r : latestOnly) {
                String dedupKey = (r.getEventId() != null ? r.getEventId() : "") + ":" + r.getCityName();
                deduped.putIfAbsent(dedupKey, r);
            }
            List<EarthquakeRecord> uniqueWarnings = new ArrayList<>(deduped.values());

            // 异步推送，不阻塞 WebSocket 消息处理线程
            log.info("📤 推送最新 1 条地震 → {} 个城市触发", deduped.size());
            barkService.sendWarningAsync(uniqueWarnings);
            for (EarthquakeListener listener : listeners) {
                try { listener.onNewEarthquake(uniqueWarnings); } catch (Exception e) {
                    log.error("监听器回调异常: {}", e.getMessage());
                }
            }
        }

        return count;
    }

    /**
     * 解析扁平单对象格式的地震记录（新数据源格式）。
     * 适用格式：{"ID":"...", "EventID":"...", "OriginTime":"...", "HypoCenter":"...", ...}
     * 返回 1 表示成功解析并处理，返回 0 表示不是此格式。
     */
    private int parseSingleEarthquakeRecord(JsonNode root) {
        // 快速检测：扁平格式没有 No1-No100，但有 OriginTime/HypoCenter/ID 等关键字段
        boolean hasKeyField = root.has("OriginTime") || root.has("HypoCenter") || root.has("Magnitude");
        if (!hasKeyField) return 0;

        EarthquakeRecord record;
        try {
            record = objectMapper.treeToValue(root, EarthquakeRecord.class);
        } catch (Exception e) {
            log.debug("扁平格式解析失败: {}", e.getMessage());
            return 0;
        }

        // 验证必要字段
        if (record.getTime() == null || record.getLocation() == null || record.getMagnitude() == null) {
            log.debug("扁平格式记录缺少必要字段 (time={}, location={}, magnitude={})",
                    record.getTime(), record.getLocation(), record.getMagnitude());
            return 0;
        }

        // 去重：使用 eventId
        String eventId = record.getEventId();
        if (eventId != null && processedEvents.containsKey(eventId)) {
            log.debug("扁平格式 — 已处理过的事件 [{}] {} M{}，跳过", eventId, record.getLocation(), record.getMagnitude());
            return 0;
        }

        // 时效性检查
        long ageMinutes = java.time.Duration.between(record.getTimeAsDateTime(), LocalDateTime.now()).toMinutes();
        if (ageMinutes > configService.getMaxWarningAgeMinutes()) {
            log.debug("⏰ 扁平格式 — 跳过过期地震 [{} {} M{}] — 已发生 {} 分钟 > {} 分钟预警窗口",
                    record.getTime(), record.getLocation(), record.getMagnitude(),
                    ageMinutes, configService.getMaxWarningAgeMinutes());
            return 0;
        }

        // 对每个启用城市计算
        List<MonitoredCity> enabledCities = configService.getEnabledCities();
        if (enabledCities.isEmpty()) {
            log.warn("没有启用的监测城市，跳过扁平格式记录");
            return 0;
        }

        log.info("📥 扁平格式 — 收到地震: {} M{} {}", record.getTime(), record.getMagnitude(), record.getLocation());

        List<EarthquakeRecord> newWarnings = new ArrayList<>();
        int count = 0;
        boolean anyCalculated = false;

        for (MonitoredCity city : enabledCities) {
            EarthquakeRecord cityRecord = record.copy();
            calculateService.calculate(cityRecord, city);
            String uniqueKey = (eventId != null ? eventId : record.getTime()) + ":" + city.getId();
            latestRecords.put(uniqueKey, cityRecord);
            anyCalculated = true;
            count++;

            if (calculateService.shouldWarn(cityRecord)) {
                newWarnings.add(cityRecord);
                log.info("⚠ 地震预警 [{}] - 发震:{}, 震中:{}, M{}, 距离:{}km, 影响:{}",
                        city.getName(), cityRecord.getTime(), cityRecord.getLocation(), cityRecord.getMagnitude(),
                        cityRecord.getEpicenterDistance() != null ? String.format("%.0f", cityRecord.getEpicenterDistance()) : "?",
                        cityRecord.getImpactLevelDesc());
            }
        }

        if (eventId != null && anyCalculated) {
            processedEvents.put(eventId, System.currentTimeMillis());
        }

        // 推送
        if (!newWarnings.isEmpty()) {
            log.info("📤 扁平格式 — 推送 {} 条预警 → {} 个城市触发", newWarnings.size(),
                    newWarnings.stream().map(EarthquakeRecord::getCityName).distinct().count());
            barkService.sendWarningAsync(newWarnings);
            for (EarthquakeListener listener : listeners) {
                try { listener.onNewEarthquake(newWarnings); } catch (Exception e) {
                    log.error("监听器回调异常: {}", e.getMessage());
                }
            }
        }

        return count;
    }

    // ==================== 统计 ====================

    /** 获取连接统计信息 */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("wsConnected", wsConnected);
        stats.put("primaryChannel", configService.getPrimaryChannel());
        stats.put("pollingActive", pollFuture != null && !pollFuture.isDone());
        stats.put("initialDataReceived", initialDataReceived);
        stats.put("latestRecordCount", latestRecords.size());
        stats.put("processedEventCount", processedEvents.size());
        stats.put("rawMessageBufferSize", rawMessageBuffer.size());
        long ms = getMillisSinceLastData();
        stats.put("lastDataAgeMs", ms);
        stats.put("lastDataAgeHuman", ms < 0 ? "从未收到" : ms < 1000 ? ms + "ms" : String.format("%.1fs", ms / 1000.0));
        stats.put("enabledCityCount", configService.getEnabledCities().size());
        return stats;
    }

    public interface EarthquakeListener {
        void onNewEarthquake(List<EarthquakeRecord> records);
    }
}
