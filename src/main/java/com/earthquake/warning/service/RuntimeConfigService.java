package com.earthquake.warning.service;

import com.earthquake.warning.model.BarkDevice;
import com.earthquake.warning.model.MonitoredCity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 运行时可变配置服务（v5.0 — 全量 yml 配置运行时可改）
 */
@Slf4j
@Service
public class RuntimeConfigService {

    private static final String CONFIG_FILE = "config/runtime-config.json";

    /** 生成短 ID（用 UUID 前 12 字符，碰撞概率极低） */
    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private final ObjectMapper objectMapper;

    // ====== 监测城市 ======
    private final CopyOnWriteArrayList<MonitoredCity> cities = new CopyOnWriteArrayList<>();

    // ====== Bark 设备 ======
    private final CopyOnWriteArrayList<BarkDevice> barkDevices = new CopyOnWriteArrayList<>();

    // ====== 数据源选择（v5.7） ======
    private volatile String dataSource;

    // 数据源预设：名称 -> {wsUrl, restUrl, displayName}
    private static final Map<String, Map<String, String>> DATA_SOURCE_PRESETS = Map.of(
            "cenc_eqlist", Map.of(
                    "wsUrl", "wss://ws-api.wolfx.jp/cenc_eqlist",
                    "restUrl", "https://api.wolfx.jp/cenc_eqlist.json",
                    "displayName", "中国地震台网发布最新地震信息"
            ),
            "cenc_eew", Map.of(
                    "wsUrl", "wss://ws-api.wolfx.jp/cenc_eew",
                    "restUrl", "https://api.wolfx.jp/cenc_eew.json",
                    "displayName", "中国地震台网实时地震预警"
            )
    );

    // ====== WebSocket / 连接配置 ======
    private volatile String websocketUrl;
    private volatile String restApiUrl;
    private volatile int pollingIntervalMs;
    private volatile int reconnectInterval;
    private volatile boolean autoConnect;

    // ====== Bark 全局参数 ======
    private volatile String barkApiUrl;
    private volatile String barkSound;
    private volatile String barkLevel;
    private volatile int barkVolume;
    private volatile int barkCall;

    // ====== 主通道选择（wss/http）======
    private volatile String primaryChannel;

    // ====== 代理配置（wolfx.jp 被部分云厂商 IP 段屏蔽时使用）======
    private volatile boolean proxyEnabled;
    private volatile String proxyHost;
    private volatile int proxyPort;

    // ====== 详情页地址 ======
    private volatile String detailPageBaseUrl;

    // ====== 预警阈值 ======
    private volatile double minMagnitude;
    private volatile int minImpactLevel;
    private volatile int maxWarningAgeMinutes;

    // ---- yml 默认值（由 @Value 注入） ----
    @Value("${earthquake.websocket-url:wss://ws-api.wolfx.jp/cenc_eqlist}")
    private String defaultWebsocketUrl;

    @Value("${earthquake.rest-api-url:https://api.wolfx.jp/cenc_eqlist.json}")
    private String defaultRestApiUrl;

    @Value("${earthquake.polling-interval-ms:1000}")
    private int defaultPollingIntervalMs;

    @Value("${earthquake.reconnect-interval:3}")
    private int defaultReconnectInterval;

    @Value("${earthquake.auto-connect:true}")
    private boolean defaultAutoConnect;

    @Value("${earthquake.bark.api-url:https://api.day.app}")
    private String defaultBarkApiUrl;

    @Value("${earthquake.bark.sound:alarm}")
    private String defaultBarkSound;

    @Value("${earthquake.bark.level:critical}")
    private String defaultBarkLevel;

    @Value("${earthquake.bark.volume:10}")
    private int defaultBarkVolume;

    @Value("${earthquake.bark.call:1}")
    private int defaultBarkCall;

    @Value("${earthquake.warning.min-magnitude:3.0}")
    private double defaultMinMagnitude;

    @Value("${earthquake.warning.min-impact-level:1}")
    private int defaultMinImpactLevel;

    @Value("${earthquake.warning.max-warning-age-minutes:10}")
    private int defaultMaxWarningAgeMinutes;

    @Value("${earthquake.detail-page-base-url:}")
    private String defaultDetailPageBaseUrl;

    @Value("${earthquake.proxy.enabled:false}")
    private boolean defaultProxyEnabled;

    @Value("${earthquake.proxy.host:}")
    private String defaultProxyHost;

    @Value("${earthquake.proxy.port:1080}")
    private int defaultProxyPort;

    @Value("${earthquake.primary-channel:wss}")
    private String defaultPrimaryChannel;

    @Value("${earthquake.data-source:cenc_eqlist}")
    private String defaultDataSource;

    public RuntimeConfigService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        // 先用 yml 默认值
        websocketUrl = defaultWebsocketUrl;
        restApiUrl = defaultRestApiUrl;
        pollingIntervalMs = defaultPollingIntervalMs;
        reconnectInterval = defaultReconnectInterval;
        autoConnect = defaultAutoConnect;
        barkApiUrl = defaultBarkApiUrl;
        barkSound = defaultBarkSound;
        barkLevel = defaultBarkLevel;
        barkVolume = defaultBarkVolume;
        barkCall = defaultBarkCall;
        minMagnitude = defaultMinMagnitude;
        minImpactLevel = defaultMinImpactLevel;
        maxWarningAgeMinutes = defaultMaxWarningAgeMinutes;
        detailPageBaseUrl = defaultDetailPageBaseUrl;
        proxyEnabled = defaultProxyEnabled;
        proxyHost = defaultProxyHost;
        proxyPort = defaultProxyPort;
        primaryChannel = defaultPrimaryChannel;
        dataSource = defaultDataSource;

        // 从磁盘加载覆盖
        loadFromDisk();
        // application.yml 中的 primary-channel 在启动时始终生效，覆盖磁盘旧值
        primaryChannel = defaultPrimaryChannel;

        // 如果磁盘没有存储 dataSource 或存储的值无效，重置为 yml 默认值
        if (!DATA_SOURCE_PRESETS.containsKey(dataSource)) {
            dataSource = defaultDataSource;
        }

        if (cities.isEmpty()) {
            cities.add(MonitoredCity.builder()
                    .id(shortId())
                    .name("成都市").latitude(30.5728).longitude(104.0668)
                    .siteClass(3).customAmplification(1.6).enabled(true).build());
        }
        if (barkDevices.isEmpty()) {
            barkDevices.add(BarkDevice.builder()
                    .id(shortId())
                    .name("我的iPhone").deviceKey("DWPHJh4yHuUUrwXC5CNEPR").enabled(true).build());
        }
        saveToDisk();
        log.info("运行时配置加载完成: {}个城市, {}个Bark设备, WS={}", cities.size(), barkDevices.size(), websocketUrl);
    }

    // ==================== 数据源选择（v5.7） ====================

    /** 获取当前数据源标识 */
    public String getDataSource() { return dataSource; }

    /** 获取数据源显示名称 */
    public String getDataSourceDisplayName() {
        Map<String, String> preset = DATA_SOURCE_PRESETS.get(dataSource);
        return preset != null ? preset.get("displayName") : dataSource;
    }

    /** 获取所有可选数据源列表 */
    public List<Map<String, String>> getAvailableDataSources() {
        List<Map<String, String>> list = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : DATA_SOURCE_PRESETS.entrySet()) {
            Map<String, String> ds = new LinkedHashMap<>(entry.getValue());
            ds.put("key", entry.getKey());
            list.add(ds);
        }
        return list;
    }

    /** 切换数据源：自动更新 WS/REST URL 并持久化 */
    public void setDataSource(String v) {
        if (!DATA_SOURCE_PRESETS.containsKey(v)) {
            log.warn("未知数据源 '{}'，保持当前数据源 '{}'", v, dataSource);
            return;
        }
        this.dataSource = v;
        Map<String, String> preset = DATA_SOURCE_PRESETS.get(v);
        this.websocketUrl = preset.get("wsUrl");
        this.restApiUrl = preset.get("restUrl");
        log.info("数据源已切换为: {} ({}) — WS={} REST={}",
                v, preset.get("displayName"), websocketUrl, restApiUrl);
        saveToDisk();
    }

    // ==================== 连接配置 Getters / Setters ====================

    public String getWebsocketUrl() { return websocketUrl; }
    public void setWebsocketUrl(String v) { this.websocketUrl = v; saveToDisk(); }

    public String getRestApiUrl() { return restApiUrl; }
    public void setRestApiUrl(String v) { this.restApiUrl = v; saveToDisk(); }

    public int getPollingIntervalMs() { return pollingIntervalMs; }
    public void setPollingIntervalMs(int v) { this.pollingIntervalMs = Math.max(v, 200); saveToDisk(); }

    public int getReconnectInterval() { return reconnectInterval; }
    public void setReconnectInterval(int v) { this.reconnectInterval = Math.max(v, 1); saveToDisk(); }

    public boolean isAutoConnect() { return autoConnect; }
    public void setAutoConnect(boolean v) { this.autoConnect = v; saveToDisk(); }

    // ==================== Bark 全局参数 Getters / Setters ====================

    public String getBarkApiUrl() { return barkApiUrl; }
    public void setBarkApiUrl(String v) { this.barkApiUrl = v; saveToDisk(); }

    public String getBarkSound() { return barkSound; }
    public void setBarkSound(String v) { this.barkSound = v; saveToDisk(); }

    public String getBarkLevel() { return barkLevel; }
    public void setBarkLevel(String v) { this.barkLevel = v; saveToDisk(); }

    public int getBarkVolume() { return barkVolume; }
    public void setBarkVolume(int v) { this.barkVolume = Math.min(10, Math.max(0, v)); saveToDisk(); }

    public int getBarkCall() { return barkCall; }
    public void setBarkCall(int v) { this.barkCall = Math.max(0, v); saveToDisk(); }

    // ==================== 主通道选择 ====================

    /** 获取主通道：wss 或 http */
    public String getPrimaryChannel() { return primaryChannel; }

    /** 设置主通道，非法值自动修正为 wss */
    public void setPrimaryChannel(String v) {
        this.primaryChannel = ("http".equals(v)) ? "http" : "wss";
        saveToDisk();
    }

    // ==================== 代理配置 ====================

    public boolean isProxyEnabled() { return proxyEnabled; }
    public void setProxyEnabled(boolean v) { this.proxyEnabled = v; saveToDisk(); }

    public String getProxyHost() { return proxyHost; }
    public void setProxyHost(String v) { this.proxyHost = v; saveToDisk(); }

    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int v) { this.proxyPort = Math.max(1, Math.min(v, 65535)); saveToDisk(); }

    // ==================== 详情页地址 ====================

    public String getDetailPageBaseUrl() { return detailPageBaseUrl; }
    public void setDetailPageBaseUrl(String v) { this.detailPageBaseUrl = v != null ? v : ""; saveToDisk(); }

    // ==================== 预警阈值 ====================

    public double getMinMagnitude() { return minMagnitude; }
    public void setMinMagnitude(double v) { this.minMagnitude = v; saveToDisk(); }

    public int getMinImpactLevel() { return minImpactLevel; }
    public void setMinImpactLevel(int v) { this.minImpactLevel = v; saveToDisk(); }

    public int getMaxWarningAgeMinutes() { return maxWarningAgeMinutes; }
    public void setMaxWarningAgeMinutes(int v) { this.maxWarningAgeMinutes = Math.max(1, v); saveToDisk(); }

    // ==================== 城市管理 ====================

    public List<MonitoredCity> getAllCities() { return Collections.unmodifiableList(cities); }
    public List<MonitoredCity> getEnabledCities() {
        return cities.stream().filter(MonitoredCity::isEnabled).collect(Collectors.toList());
    }

    public MonitoredCity addCity(MonitoredCity city) {
        if (city.getId() == null || city.getId().isBlank())
            city.setId(shortId());
        cities.add(city);
        saveToDisk();
        return city;
    }

    public boolean updateCity(String id, MonitoredCity updated) {
        for (int i = 0; i < cities.size(); i++) {
            if (cities.get(i).getId().equals(id)) { updated.setId(id); cities.set(i, updated); saveToDisk(); return true; }
        }
        return false;
    }

    public boolean deleteCity(String id) {
        boolean r = cities.removeIf(c -> c.getId().equals(id));
        if (r) saveToDisk();
        return r;
    }

    // ==================== Bark 设备管理 ====================

    public List<BarkDevice> getAllBarkDevices() { return Collections.unmodifiableList(barkDevices); }
    public List<BarkDevice> getEnabledBarkDevices() {
        return barkDevices.stream().filter(BarkDevice::isEnabled).collect(Collectors.toList());
    }

    public BarkDevice addBarkDevice(BarkDevice device) {
        if (device.getId() == null || device.getId().isBlank())
            device.setId(shortId());
        barkDevices.add(device);
        saveToDisk();
        return device;
    }

    public boolean updateBarkDevice(String id, BarkDevice updated) {
        for (int i = 0; i < barkDevices.size(); i++) {
            if (barkDevices.get(i).getId().equals(id)) { updated.setId(id); barkDevices.set(i, updated); saveToDisk(); return true; }
        }
        return false;
    }

    public boolean deleteBarkDevice(String id) {
        boolean r = barkDevices.removeIf(d -> d.getId().equals(id));
        if (r) saveToDisk();
        return r;
    }

    // ==================== 旧接口兼容 ====================

    public double getMyLatitude() {
        return cities.stream().filter(MonitoredCity::isEnabled).findFirst().map(MonitoredCity::getLatitude).orElse(30.5728);
    }
    public double getMyLongitude() {
        return cities.stream().filter(MonitoredCity::isEnabled).findFirst().map(MonitoredCity::getLongitude).orElse(104.0668);
    }
    public String getMyLocationName() {
        return cities.stream().filter(MonitoredCity::isEnabled).findFirst().map(MonitoredCity::getName).orElse("成都市");
    }
    public boolean isBarkEnabled() { return barkDevices.stream().anyMatch(BarkDevice::isEnabled); }

    // ==================== 全量配置导出 ====================

    public Map<String, Object> getAllConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 连接配置
        Map<String, Object> conn = new LinkedHashMap<>();
        conn.put("dataSource", dataSource);
        conn.put("dataSourceDisplayName", getDataSourceDisplayName());
        conn.put("availableDataSources", getAvailableDataSources());
        conn.put("websocketUrl", websocketUrl);
        conn.put("restApiUrl", restApiUrl);
        conn.put("pollingIntervalMs", pollingIntervalMs);
        conn.put("reconnectInterval", reconnectInterval);
        conn.put("autoConnect", autoConnect);
        conn.put("primaryChannel", primaryChannel);
        conn.put("proxyEnabled", proxyEnabled);
        conn.put("proxyHost", proxyHost != null ? proxyHost : "");
        conn.put("proxyPort", proxyPort);
        result.put("connection", conn);

        // Bark 全局
        result.put("barkGlobal", Map.of(
                "apiUrl", barkApiUrl,
                "sound", barkSound,
                "level", barkLevel,
                "volume", barkVolume,
                "call", barkCall,
                "detailPageBaseUrl", detailPageBaseUrl
        ));

        // 预警阈值
        result.put("warning", Map.of("minMagnitude", minMagnitude, "minImpactLevel", minImpactLevel, "maxWarningAgeMinutes", maxWarningAgeMinutes));

        // 城市与设备
        result.put("cities", new ArrayList<>(cities));
        result.put("barkDevices", barkDevices.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId()); m.put("name", d.getName());
            m.put("deviceKey", maskKey(d.getDeviceKey())); m.put("enabled", d.isEnabled());
            return m;
        }).collect(Collectors.toList()));

        return result;
    }

    // ==================== 持久化 ====================

    private synchronized void saveToDisk() {
        try {
            File file = new File(CONFIG_FILE);
            file.getParentFile().mkdirs();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("dataSource", dataSource);
            data.put("websocketUrl", websocketUrl);
            data.put("restApiUrl", restApiUrl);
            data.put("pollingIntervalMs", String.valueOf(pollingIntervalMs));
            data.put("reconnectInterval", String.valueOf(reconnectInterval));
            data.put("autoConnect", String.valueOf(autoConnect));
            data.put("barkApiUrl", barkApiUrl);
            data.put("barkSound", barkSound);
            data.put("barkLevel", barkLevel);
            data.put("barkVolume", String.valueOf(barkVolume));
            data.put("barkCall", String.valueOf(barkCall));
            data.put("minMagnitude", String.valueOf(minMagnitude));
            data.put("minImpactLevel", String.valueOf(minImpactLevel));
            data.put("maxWarningAgeMinutes", String.valueOf(maxWarningAgeMinutes));
            data.put("detailPageBaseUrl", detailPageBaseUrl);
            data.put("primaryChannel", primaryChannel);
            data.put("proxyEnabled", String.valueOf(proxyEnabled));
            data.put("proxyHost", proxyHost != null ? proxyHost : "");
            data.put("proxyPort", String.valueOf(proxyPort));
            data.put("cities", new ArrayList<>(cities));
            data.put("barkDevices", new ArrayList<>(barkDevices));
            objectMapper.writeValue(file, data);
        } catch (IOException e) {
            log.error("保存运行时配置失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) return;
        try {
            Map<String, Object> data = objectMapper.readValue(file, new TypeReference<LinkedHashMap<String, Object>>() {});

            websocketUrl = getString(data, "websocketUrl", defaultWebsocketUrl);
            restApiUrl = getString(data, "restApiUrl", defaultRestApiUrl);
            // 加载 dataSource，若磁盘存储的无效则使用 yml 默认值
            String ds = getString(data, "dataSource", defaultDataSource);
            dataSource = DATA_SOURCE_PRESETS.containsKey(ds) ? ds : defaultDataSource;
            pollingIntervalMs = getInt(data, "pollingIntervalMs", defaultPollingIntervalMs);
            reconnectInterval = getInt(data, "reconnectInterval", defaultReconnectInterval);
            autoConnect = getBool(data, "autoConnect", defaultAutoConnect);
            barkApiUrl = getString(data, "barkApiUrl", defaultBarkApiUrl);
            barkSound = getString(data, "barkSound", defaultBarkSound);
            barkLevel = getString(data, "barkLevel", defaultBarkLevel);
            barkVolume = getInt(data, "barkVolume", defaultBarkVolume);
            barkCall = getInt(data, "barkCall", defaultBarkCall);
            detailPageBaseUrl = getString(data, "detailPageBaseUrl", defaultDetailPageBaseUrl);
            proxyEnabled = getBool(data, "proxyEnabled", defaultProxyEnabled);
            proxyHost = getString(data, "proxyHost", defaultProxyHost);
            proxyPort = getInt(data, "proxyPort", defaultProxyPort);
            primaryChannel = getString(data, "primaryChannel", defaultPrimaryChannel);
            minMagnitude = getDouble(data, "minMagnitude", defaultMinMagnitude);
            minImpactLevel = getInt(data, "minImpactLevel", defaultMinImpactLevel);
            maxWarningAgeMinutes = getInt(data, "maxWarningAgeMinutes", defaultMaxWarningAgeMinutes);

            if (data.containsKey("cities") && data.get("cities") instanceof List) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("cities");
                for (Map<String, Object> m : list) {
                    cities.add(MonitoredCity.builder()
                            .id((String) m.get("id")).name((String) m.getOrDefault("name", "未命名"))
                            .latitude(getDouble(m, "latitude", 30.0)).longitude(getDouble(m, "longitude", 104.0))
                            .siteClass(getInt(m, "siteClass", 3))
                            .customAmplification(getDouble(m, "customAmplification", 1.6))
                            .enabled(m.get("enabled") == null || (boolean) m.get("enabled")).build());
                }
            }
            if (data.containsKey("barkDevices") && data.get("barkDevices") instanceof List) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("barkDevices");
                for (Map<String, Object> m : list) {
                    barkDevices.add(BarkDevice.builder()
                            .id((String) m.get("id")).name((String) m.getOrDefault("name", "未命名"))
                            .deviceKey((String) m.getOrDefault("deviceKey", ""))
                            .enabled(m.get("enabled") == null || (boolean) m.get("enabled")).build());
                }
            }
            log.info("已加载运行时配置: {}个城市, {}个Bark设备", cities.size(), barkDevices.size());
        } catch (Exception e) {
            log.error("加载运行时配置失败: {}", e.getMessage());
        }
    }

    private String getString(Map<String, Object> m, String k, String def) {
        Object v = m.get(k); return v == null ? def : v.toString();
    }
    private int getInt(Map<String, Object> m, String k, int def) {
        Object v = m.get(k); if (v == null) return def;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return def; }
    }
    private double getDouble(Map<String, Object> m, String k, double def) {
        Object v = m.get(k); if (v == null) return def;
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return def; }
    }
    private boolean getBool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k); if (v == null) return def;
        return Boolean.parseBoolean(v.toString());
    }

    public static String maskKey(String key) {
        if (key == null || key.length() <= 6) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
