package com.earthquake.warning.controller;

import com.earthquake.warning.model.*;
import com.earthquake.warning.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/earthquake")
@Tag(name = "地震预警", description = "地震数据、影响计算、多城市/多设备管理、全量配置")
public class EarthquakeController {

    private final WebSocketClientService wsClientService;
    private final EarthquakeCalculateService calculateService;
    private final BarkNotificationService barkService;
    private final RuntimeConfigService configService;

    public EarthquakeController(WebSocketClientService wsClientService,
                                EarthquakeCalculateService calculateService,
                                BarkNotificationService barkService,
                                RuntimeConfigService configService) {
        this.wsClientService = wsClientService;
        this.calculateService = calculateService;
        this.barkService = barkService;
        this.configService = configService;
    }

    // ==================== 状态 ====================

    @GetMapping("/status")
    @Operation(summary = "获取系统状态（含 WS 连接统计）")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = wsClientService.getStats();
        status.put("barkDeviceCount", configService.getEnabledBarkDevices().size());
        status.put("recordCount", wsClientService.getLatestRecords().size());
        status.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return ResponseEntity.ok(status);
    }

    // ==================== 实时原始消息 ====================

    @GetMapping("/raw-messages")
    @Operation(summary = "获取 WebSocket 实时消息流（含 JSON 预览）")
    public ResponseEntity<Map<String, Object>> getRawMessages(
            @Parameter(description = "返回条数") @RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> msgs = wsClientService.getRawMessages(Math.min(limit, 300));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", msgs.size());
        result.put("messages", msgs);
        result.put("connected", wsClientService.isConnected());
        result.put("initialDataReceived", wsClientService.isInitialDataReceived());
        result.put("recordCount", wsClientService.getLatestRecords().size());
        result.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return ResponseEntity.ok(result);
    }

    // ==================== 连接管理 ====================

    @PostMapping("/connect")
    @Operation(summary = "连接 WebSocket")
    public ResponseEntity<Map<String, String>> connect() {
        wsClientService.connect();
        return ResponseEntity.ok(Map.of("status", "connecting", "message", "正在连接..."));
    }

    @PostMapping("/disconnect")
    @Operation(summary = "断开 WebSocket")
    public ResponseEntity<Map<String, String>> disconnect() {
        wsClientService.disconnect();
        return ResponseEntity.ok(Map.of("status", "disconnected", "message", "已断开"));
    }

    // ==================== 地震数据 ====================

    @GetMapping("/latest")
    @Operation(summary = "获取最新地震列表")
    public ResponseEntity<Map<String, Object>> getLatest() {
        List<EarthquakeRecord> records = wsClientService.getLatestRecords();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", records.size());
        result.put("records", records);
        result.put("cities", configService.getEnabledCities().stream()
                .map(c -> Map.of("name", c.getName(), "lat", c.getLatitude(), "lon", c.getLongitude()))
                .collect(Collectors.toList()));
        result.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/warnings")
    @Operation(summary = "获取预警列表")
    public ResponseEntity<Map<String, Object>> getWarnings() {
        List<EarthquakeRecord> warnings = wsClientService.getLatestRecords().stream()
                .filter(calculateService::shouldWarn).toList();
        return ResponseEntity.ok(Map.of("count", warnings.size(), "warnings", warnings));
    }

    @PostMapping("/query")
    @Operation(summary = "手动查询最新地震")
    public ResponseEntity<Map<String, String>> queryLatest() {
        wsClientService.queryLatest();
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    // ==================== 模拟地震 ====================

    @PostMapping("/simulate")
    @Operation(summary = "模拟地震测试")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"message":"模拟地震计算完成","cityResults":[{"city":"成都市","impactLevel":3,"impactLevelDesc":"强烈有感","epicenterDistance":112.45}]}"""))))
    public ResponseEntity<WarningResult> simulateEarthquake(@Valid @RequestBody SimulateRequest request) {

        log.info("模拟地震: M{} 震中({},{})", request.getMagnitude(), request.getLatitude(), request.getLongitude());

        List<EarthquakeRecord> allRecords = new ArrayList<>();
        List<MonitoredCity> cities = configService.getEnabledCities().isEmpty()
                ? configService.getAllCities()
                : configService.getEnabledCities();

        if (cities.isEmpty()) {
            return ResponseEntity.ok(WarningResult.builder()
                    .success(false)
                    .message("没有监测城市，请先添加监测城市")
                    .processedCount(0).warningCount(0)
                    .warnings(Collections.emptyList()).build());
        }

        for (MonitoredCity city : cities) {
            EarthquakeRecord record = EarthquakeRecord.builder()
                    .reportType("simulated")
                    .eventId("SIM." + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
                    .time(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    .reportTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    .location(request.getLocation() != null ? request.getLocation() : "模拟震中")
                    .placeName(request.getLocation() != null ? request.getLocation() : "模拟震中")
                    .magnitude(request.getMagnitude())
                    .depth(request.getDepth())
                    .latitude(String.valueOf(request.getLatitude()))
                    .longitude(String.valueOf(request.getLongitude()))
                    .intensity(String.valueOf(estimateMaxIntensity(Double.parseDouble(request.getMagnitude()))))
                    .build();
            calculateService.calculate(record, city);
            allRecords.add(record);
        }

        List<EarthquakeRecord> warnings = allRecords.stream().filter(calculateService::shouldWarn).toList();
        boolean barkSent = false;
        if (request.isSendBark() && !warnings.isEmpty()) {
            barkSent = barkService.sendWarning(warnings) > 0;
        }

        List<Map<String, Object>> cityResults = allRecords.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("city", r.getCityName());
            m.put("impactLevel", r.getImpactLevel());
            m.put("impactLevelDesc", r.getImpactLevelDesc());
            m.put("epicenterDistance", r.getEpicenterDistance());
            m.put("intensity", r.getEstimatedLocalIntensity());
            m.put("sWaveArrival", r.getSWaveArrivalSeconds());
            m.put("advice", r.getActionAdvice());
            return m;
        }).collect(Collectors.toList());

        // 将 cityResults 放在 message 的扩展字段中返回
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("cityResults", cityResults);

        return ResponseEntity.ok(WarningResult.builder()
                .success(true)
                .message("模拟完成，涉及 " + cities.size() + " 个城市" + (barkSent ? "，已推送" : ""))
                .processedCount(allRecords.size())
                .warningCount(warnings.size())
                .myLocation(WarningResult.MyLocation.builder()
                        .latitude(cities.get(0).getLatitude()).longitude(cities.get(0).getLongitude())
                        .name(cities.size() + "个城市").build())
                .extra(extra)
                .warnings(warnings).build());
    }

    // ==================== Bark 测试 ====================

    @PostMapping("/bark/test")
    @Operation(summary = "测试 Bark 推送")
    public ResponseEntity<Map<String, Object>> testBark(
            @Parameter(description = "测试消息") @RequestParam(defaultValue = "地震预警系统测试") String message) {
        int count = barkService.sendTestToAll("🧪 测试推送", message);
        return ResponseEntity.ok(Map.of("success", count > 0, "deviceCount", count));
    }

    // ==================== 真实数据推送测试 ====================

    @PostMapping("/test-push")
    @Operation(summary = "用当前内存中最新的国内地震记录推送到手机（真数据测试）")
    public ResponseEntity<Map<String, Object>> testPushRealData() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 获取所有最新地震记录
        List<EarthquakeRecord> allRecords = wsClientService.getLatestRecords();

        if (allRecords.isEmpty()) {
            result.put("success", false);
            result.put("message", "当前无地震数据，请先确认 WebSocket 已连接且收到过数据。可先调用 POST /api/earthquake/query 手动查询。");
            result.put("connected", wsClientService.isConnected());
            result.put("initialDataReceived", wsClientService.isInitialDataReceived());
            return ResponseEntity.ok(result);
        }

        // 2. 过滤国内地震（location 含中文）
        List<EarthquakeRecord> domesticRecords = allRecords.stream()
                .filter(r -> isChineseLocation(r.getLocation()))
                .collect(Collectors.toList());

        if (domesticRecords.isEmpty()) {
            result.put("success", false);
            result.put("message", "当前无国内地震记录，共 " + allRecords.size() + " 条，均为境外地震。");
            result.put("totalRecords", allRecords.size());
            // 打印境外地震摘要
            List<Map<String, String>> samples = allRecords.stream()
                    .map(r -> Map.of("time", r.getTime(), "location", r.getLocation(), "magnitude", r.getMagnitude()))
                    .collect(Collectors.toList());
            result.put("samples", samples);
            return ResponseEntity.ok(result);
        }

        // 3. 只取时间最新的一条地震
        EarthquakeRecord latest = domesticRecords.get(0); // 已按时间降序

        log.info("📋 真数据推送测试 — 选中最新国内地震: {} M{} {}", latest.getTime(), latest.getMagnitude(), latest.getLocation());

        // 4. 对每个启用城市重新计算（确保数据完整）
        List<MonitoredCity> cities = configService.getEnabledCities();
        if (cities.isEmpty()) cities = configService.getAllCities();

        List<EarthquakeRecord> toPush = new ArrayList<>();
        for (MonitoredCity city : cities) {
            // 浅拷贝记录，避免污染内存中的原始数据
            EarthquakeRecord rec = latest.copy();

            calculateService.calculate(rec, city);
            toPush.add(rec);
        }

        // 5. 异步推送
        List<Map<String, Object>> cityDetails = toPush.stream().map(r -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("city", r.getCityName());
            d.put("impactLevel", r.getImpactLevel());
            d.put("impactLevelDesc", r.getImpactLevelDesc());
            d.put("epicenterDistance", r.getEpicenterDistance());
            d.put("estimatedIntensity", r.getEstimatedLocalIntensity());
            d.put("sWaveArrival", r.getSWaveArrivalSeconds());
            d.put("advice", r.getActionAdvice());
            return d;
        }).collect(Collectors.toList());

        barkService.sendWarningAsync(toPush);

        // 6. 组装返回
        result.put("success", true);
        result.put("message", "已触发推送 — 将最新国内地震推送到 " +
                configService.getEnabledBarkDevices().size() + " 台设备，覆盖 " + cities.size() + " 个城市");
        result.put("earthquake", Map.of(
                "time", latest.getTime(),
                "location", latest.getLocation(),
                "magnitude", latest.getMagnitude(),
                "depth", latest.getDepth(),
                "latitude", latest.getLatitude(),
                "longitude", latest.getLongitude(),
                "eventId", latest.getEventId() != null ? latest.getEventId() : "",
                "reportType", latest.getReportType() != null ? latest.getReportType() : ""
        ));
        result.put("cityDetails", cityDetails);
        result.put("totalDomesticRecords", domesticRecords.size());
        result.put("totalAllRecords", allRecords.size());

        return ResponseEntity.ok(result);
    }

    /** 判断 location 是否为国内地名（含中文字符） */
    private boolean isChineseLocation(String location) {
        if (location == null || location.isBlank()) return false;
        for (char c : location.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    // ==================== 监测城市 CRUD ====================

    @GetMapping("/cities")
    @Operation(summary = "获取所有监测城市")
    public ResponseEntity<List<MonitoredCity>> getCities() {
        return ResponseEntity.ok(configService.getAllCities());
    }

    @PostMapping("/cities")
    @Operation(summary = "添加监测城市")
    public ResponseEntity<MonitoredCity> addCity(@RequestBody MonitoredCity city) {
        return ResponseEntity.ok(configService.addCity(city));
    }

    @PutMapping("/cities/{id}")
    @Operation(summary = "修改监测城市")
    public ResponseEntity<?> updateCity(@PathVariable String id, @RequestBody MonitoredCity city) {
        return configService.updateCity(id, city) ? ResponseEntity.ok(city) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/cities/{id}")
    @Operation(summary = "删除监测城市")
    public ResponseEntity<Map<String, Object>> deleteCity(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("success", configService.deleteCity(id)));
    }

    // ==================== Bark 设备 CRUD ====================

    @GetMapping("/bark-devices")
    @Operation(summary = "获取所有 Bark 设备")
    public ResponseEntity<List<Map<String, Object>>> getBarkDevices() {
        return ResponseEntity.ok(configService.getAllBarkDevices().stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId()); m.put("name", d.getName());
            m.put("deviceKey", RuntimeConfigService.maskKey(d.getDeviceKey()));
            m.put("enabled", d.isEnabled());
            return m;
        }).collect(Collectors.toList()));
    }

    @PostMapping("/bark-devices")
    @Operation(summary = "添加 Bark 设备")
    public ResponseEntity<?> addBarkDevice(@RequestBody BarkDevice device) {
        BarkDevice saved = configService.addBarkDevice(device);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId()); resp.put("name", saved.getName());
        resp.put("enabled", saved.isEnabled()); resp.put("deviceKey", "****");
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/bark-devices/{id}")
    @Operation(summary = "修改 Bark 设备")
    public ResponseEntity<?> updateBarkDevice(@PathVariable String id, @RequestBody BarkDevice device) {
        return configService.updateBarkDevice(id, device)
                ? ResponseEntity.ok(Map.of("success", true, "id", id))
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/bark-devices/{id}")
    @Operation(summary = "删除 Bark 设备")
    public ResponseEntity<Map<String, Object>> deleteBarkDevice(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("success", configService.deleteBarkDevice(id)));
    }

    // ==================== 全量配置 ====================

    @GetMapping("/config")
    @Operation(summary = "获取全部运行时配置")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = configService.getAllConfig();
        config.put("model", Map.of(
                "version", "5.7.0",
                "attenuation", "GB 18306-2015 中国东部衰减模型",
                "travelTime", "IASPEI-91 一维地球速度模型",
                "distance", "Haversine 大圆公式",
                "features", "多城市监测 + 多Bark设备 + 全量运行时可配 + 双数据源切换"
        ));
        config.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return ResponseEntity.ok(config);
    }

    @PutMapping("/config")
    @Operation(summary = "批量修改配置（JSON Body，key=value 对）")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> updates) {
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            try {
                String v = e.getValue() != null ? e.getValue().toString() : "";
                switch (e.getKey()) {
                    case "dataSource" -> {
                        configService.setDataSource(v);
                        // 数据源切换后立即重连，用新 URL 获取数据
                        wsClientService.reconnect();
                    }
                    case "websocketUrl" -> configService.setWebsocketUrl(v);
                    case "restApiUrl" -> configService.setRestApiUrl(v);
                    case "pollingIntervalMs" -> configService.setPollingIntervalMs(Integer.parseInt(v));
                    case "reconnectInterval" -> configService.setReconnectInterval(Integer.parseInt(v));
                    case "autoConnect" -> configService.setAutoConnect(Boolean.parseBoolean(v));
                    case "barkApiUrl" -> configService.setBarkApiUrl(v);
                    case "barkSound" -> configService.setBarkSound(v);
                    case "barkLevel" -> configService.setBarkLevel(v);
                    case "barkVolume" -> configService.setBarkVolume(Integer.parseInt(v));
                    case "barkCall" -> configService.setBarkCall(Integer.parseInt(v));
                    case "detailPageBaseUrl" -> configService.setDetailPageBaseUrl(v);
                    case "proxyEnabled" -> { configService.setProxyEnabled(Boolean.parseBoolean(v)); wsClientService.resetProxyHttpClient(); }
                    case "proxyHost" -> { configService.setProxyHost(v); wsClientService.resetProxyHttpClient(); }
                    case "proxyPort" -> { configService.setProxyPort(Integer.parseInt(v)); wsClientService.resetProxyHttpClient(); }
                    case "primaryChannel" -> {
                        configService.setPrimaryChannel(v);
                        wsClientService.switchPrimaryChannel();
                    }
                    case "minMagnitude" -> configService.setMinMagnitude(Double.parseDouble(v));
                    case "minImpactLevel" -> configService.setMinImpactLevel(Integer.parseInt(v));
                    default -> log.warn("未知配置项: {}", e.getKey());
                }
            } catch (Exception ex) {
                log.warn("配置更新失败 {} = {}: {}", e.getKey(), e.getValue(), ex.getMessage());
            }
        }
        return ResponseEntity.ok(getConfig().getBody());
    }

    @GetMapping("/config/{key}")
    @Operation(summary = "获取单个配置项")
    public ResponseEntity<Map<String, String>> getConfigValue(@PathVariable String key) {
        String val = switch (key) {
            case "dataSource" -> configService.getDataSource();
            case "websocketUrl" -> configService.getWebsocketUrl();
            case "restApiUrl" -> configService.getRestApiUrl();
            case "pollingIntervalMs" -> String.valueOf(configService.getPollingIntervalMs());
            case "reconnectInterval" -> String.valueOf(configService.getReconnectInterval());
            case "autoConnect" -> String.valueOf(configService.isAutoConnect());
            case "barkApiUrl" -> configService.getBarkApiUrl();
            case "barkSound" -> configService.getBarkSound();
            case "barkLevel" -> configService.getBarkLevel();
            case "barkVolume" -> String.valueOf(configService.getBarkVolume());
            case "barkCall" -> String.valueOf(configService.getBarkCall());
            case "detailPageBaseUrl" -> configService.getDetailPageBaseUrl();
            case "proxyEnabled" -> String.valueOf(configService.isProxyEnabled());
            case "proxyHost" -> configService.getProxyHost();
            case "proxyPort" -> String.valueOf(configService.getProxyPort());
            case "primaryChannel" -> configService.getPrimaryChannel();
            case "minMagnitude" -> String.valueOf(configService.getMinMagnitude());
            case "minImpactLevel" -> String.valueOf(configService.getMinImpactLevel());
            default -> null;
        };
        return val != null ? ResponseEntity.ok(Map.of("key", key, "value", val))
                : ResponseEntity.notFound().build();
    }

    @PutMapping("/config/{key}")
    @Operation(summary = "修改单个配置项（请求体为纯文本值）")
    public ResponseEntity<Map<String, String>> setConfigValue(@PathVariable String key, @RequestBody String value) {
        try {
            switch (key) {
                case "dataSource" -> {
                    configService.setDataSource(value);
                    wsClientService.reconnect();
                }
                case "websocketUrl" -> configService.setWebsocketUrl(value);
                case "restApiUrl" -> configService.setRestApiUrl(value);
                case "pollingIntervalMs" -> configService.setPollingIntervalMs(Integer.parseInt(value));
                case "reconnectInterval" -> configService.setReconnectInterval(Integer.parseInt(value));
                case "autoConnect" -> configService.setAutoConnect(Boolean.parseBoolean(value));
                case "barkApiUrl" -> configService.setBarkApiUrl(value);
                case "barkSound" -> configService.setBarkSound(value);
                case "barkLevel" -> configService.setBarkLevel(value);
                case "barkVolume" -> configService.setBarkVolume(Integer.parseInt(value));
                case "barkCall" -> configService.setBarkCall(Integer.parseInt(value));
                case "detailPageBaseUrl" -> configService.setDetailPageBaseUrl(value);
                case "proxyEnabled" -> { configService.setProxyEnabled(Boolean.parseBoolean(value)); wsClientService.resetProxyHttpClient(); }
                case "proxyHost" -> { configService.setProxyHost(value); wsClientService.resetProxyHttpClient(); }
                case "proxyPort" -> { configService.setProxyPort(Integer.parseInt(value)); wsClientService.resetProxyHttpClient(); }
                case "primaryChannel" -> {
                    configService.setPrimaryChannel(value);
                    wsClientService.switchPrimaryChannel();
                }
                case "minMagnitude" -> configService.setMinMagnitude(Double.parseDouble(value));
                case "minImpactLevel" -> configService.setMinImpactLevel(Integer.parseInt(value));
                default -> { return ResponseEntity.notFound().build(); }
            }
            return ResponseEntity.ok(Map.of("key", key, "value", value, "status", "saved"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private int estimateMaxIntensity(double magnitude) {
        if (magnitude >= 8.0) return 11;
        if (magnitude >= 7.0) return 10;
        if (magnitude >= 6.0) return 8;
        if (magnitude >= 5.0) return 7;
        if (magnitude >= 4.0) return 6;
        return 3;
    }
}
