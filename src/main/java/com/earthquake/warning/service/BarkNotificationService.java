package com.earthquake.warning.service;

import com.earthquake.warning.model.BarkDevice;
import com.earthquake.warning.model.EarthquakeRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.Base64;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Bark 通知推送服务（v4.0 — 多设备 + 多城市）
 */
@Slf4j
@Service
public class BarkNotificationService {

    private final RuntimeConfigService configService;
    private final RestTemplate restTemplate = new RestTemplate();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 保护 RestTemplate 并发调用的锁 */
    private final Object restLock = new Object();

    /** 专用于推送的线程池，避免阻塞 WebSocket 消息处理 */
    private final ExecutorService pushExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "bark-push");
        t.setDaemon(true);
        return t;
    });

    public BarkNotificationService(RuntimeConfigService configService) {
        this.configService = configService;
    }

    @PreDestroy
    public void destroy() {
        pushExecutor.shutdownNow();
        log.info("Bark 推送线程池已关闭");
    }

    /**
     * 异步推送地震预警（非阻塞），保证 WebSocket 消息处理延迟 ≤ 1s
     */
    public void sendWarningAsync(List<EarthquakeRecord> warningRecords) {
        if (warningRecords == null || warningRecords.isEmpty()) return;
        // 防御性拷贝，避免外部修改
        List<EarthquakeRecord> copy = new ArrayList<>(warningRecords);
        long startMs = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            int count = sendWarning(copy);
            long elapsed = System.currentTimeMillis() - startMs;
            log.info("📤 Bark 异步推送完成: {} 台设备, 耗时 {}ms", count, elapsed);
        }, pushExecutor).exceptionally(ex -> {
            log.error("❌ Bark 异步推送失败: {}", ex.getMessage());
            return null;
        });
    }

    /**
     * 发送地震预警通知（推送到所有启用的 Bark 设备）
     * @param warningRecords 触发预警的地震记录列表（可能包含多个城市）
     * @return 成功推送的设备数
     */
    public int sendWarning(List<EarthquakeRecord> warningRecords) {
        if (warningRecords == null || warningRecords.isEmpty()) return 0;

        List<BarkDevice> devices = configService.getEnabledBarkDevices();
        log.info("📤 sendWarning: {} 条预警记录, {} 台设备", warningRecords.size(), devices.size());
        if (devices.isEmpty()) {
            log.debug("无启用的 Bark 设备");
            return 0;
        }

        // 按城市分组
        Map<String, List<EarthquakeRecord>> byCity = warningRecords.stream()
                .filter(r -> r.getCityName() != null)
                .collect(Collectors.groupingBy(EarthquakeRecord::getCityName, LinkedHashMap::new, Collectors.toList()));

        int successCount = 0;
        for (BarkDevice device : devices) {
            try {
                Map<String, Object> payload = buildMultiCityPayload(byCity, warningRecords);
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                String url = configService.getBarkApiUrl() + "/" + device.getDeviceKey();
                HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
                ResponseEntity<String> response;
                synchronized (restLock) {
                    response = restTemplate.postForEntity(url, request, String.class);
                }

                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("✅ Bark推送成功 → {} [{}个城市触发]", device.getName(), byCity.size());
                    successCount++;
                } else {
                    log.warn("Bark推送失败 → {} status:{}", device.getName(), response.getStatusCode());
                }
            } catch (Exception e) {
                log.error("Bark推送异常 → {}: {}", device.getName(), e.getMessage());
            }
        }
        return successCount;
    }

    /**
     * 构建多城市预警推送载荷
     */
    private Map<String, Object> buildMultiCityPayload(Map<String, List<EarthquakeRecord>> byCity,
                                                        List<EarthquakeRecord> allRecords) {
        EarthquakeRecord first = allRecords.get(0);
        int maxLevel = allRecords.stream().mapToInt(r -> r.getImpactLevel() != null ? r.getImpactLevel() : 0).max().orElse(0);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", buildTitle(first, byCity.size()));
        payload.put("body", buildMultiCityBody(byCity, first));
        payload.put("group", "地震预警");
        payload.put("badge", maxLevel);
        payload.put("isArchive", 1);
        payload.put("ttl", 86400);
        payload.put("autoCopy", 1);

        applyLevelStrategy(payload, maxLevel);

        // 优先使用详情页跳转，兜底使用中国地震台网速报链接
        String detailUrl = buildDetailUrl(allRecords);
        if (detailUrl != null) {
            payload.put("url", detailUrl);
        } else {
            String cencUrl = buildCencUrl(first);
            if (cencUrl != null) {
                payload.put("url", cencUrl);
            }
        }

        // 推送图标（可替换为你的图标 URL）
        payload.put("icon", "https://img.icons8.com/emoji/96/earth-globe.png");

        log.info("📱 Bark payload: url='{}'", payload.get("url"));
        return payload;
    }

    /**
     * 将地震 time 字段（2026-07-06 07:10:27）转换为紧凑格式（20260706071027），
     * 构建中国地震台网速报链接。
     */
    private String buildCencUrl(EarthquakeRecord record) {
        if (record == null || record.getTime() == null || record.getTime().isBlank()) return null;
        String compact = record.getTime().replaceAll("[-:\\s]", "");
        if (compact.length() < 12) return null;
        return "https://www.cenc.ac.cn/earthquake-manage-publish-web/product-list/"
                + compact + "/summarize";
    }

    private String buildTitle(EarthquakeRecord record, int cityCount) {
        int level = record.getImpactLevel() != null ? record.getImpactLevel() : 0;
        String emoji = switch (level) {
            case 5 -> "🔴";
            case 4 -> "🟠";
            case 3 -> "🟡";
            default -> "🔔";
        };
        String suffix = cityCount > 1 ? " · " + cityCount + "城触发" : "";
        return String.format("%s M%s 地震预警 %s%s",
                emoji, record.getMagnitude(), record.getLocation(), suffix);
    }

    private String buildMultiCityBody(Map<String, List<EarthquakeRecord>> byCity, EarthquakeRecord eq) {
        StringBuilder sb = new StringBuilder();

        // ===== 地震概要（精简）=====
        sb.append("⏰ ").append(eq.getTime()).append("\n");
        sb.append("📍 ").append(eq.getLocation());
        sb.append("  M").append(eq.getMagnitude());
        sb.append("  深").append(eq.getDepth()).append("km");
        if (eq.getReportType() != null) {
            sb.append("  ").append("automatic".equals(eq.getReportType()) ? "📡自动测定" : "✅正式测定");
        }
        sb.append("\n");

        // ===== 各地影响（每城一行）=====
        if (!byCity.isEmpty()) {
            sb.append("\n━━ 各地影响预估 ━━\n");
            for (Map.Entry<String, List<EarthquakeRecord>> entry : byCity.entrySet()) {
                EarthquakeRecord r = entry.getValue().get(0);
                int level = r.getImpactLevel() != null ? r.getImpactLevel() : 0;
                String dot = switch (level) {
                    case 5, 4 -> "🔴";
                    case 3 -> "🟠";
                    case 2 -> "🟡";
                    default -> "⚪";
                };
                sb.append(dot).append(" ").append(entry.getKey());
                if (r.getEpicenterDistance() != null) {
                    sb.append(String.format(" · 距%.0fkm · 烈度%.1f", r.getEpicenterDistance(),
                            r.getEstimatedLocalIntensity() != null ? r.getEstimatedLocalIntensity() : 0));
                }
                sb.append("（").append(r.getImpactLevelDesc()).append("）");
                if (r.getSWaveArrivalSeconds() != null && r.getSWaveArrivalSeconds() > 0) {
                    sb.append(String.format(" · ⏱S波%.0fs", r.getSWaveArrivalSeconds()));
                }
                sb.append("\n");
            }
        }

        // ===== 建议 + 引导点击 =====
        if (eq.getActionAdvice() != null && !eq.getActionAdvice().isBlank()) {
            sb.append("\n💡 ").append(eq.getActionAdvice());
        }
        sb.append("\n🔗 点击查看完整详情与震情速报");

        return sb.toString();
    }

    /**
     * 构建地震详情页跳转 URL。
     * 将地震 + 各城市信息编码为 Base64 JSON 放入 URL hash，详情页直接从 hash 读取渲染。
     * 若未配置 detailPageBaseUrl 则返回 null，降级到 CENC 链接。
     */
    private String buildDetailUrl(List<EarthquakeRecord> allRecords) {
        String baseUrl = configService.getDetailPageBaseUrl();
        log.info("🔗 buildDetailUrl: baseUrl='{}'", baseUrl);
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("🔗 buildDetailUrl: baseUrl 为空，降级到 CENC 链接");
            return null;
        }

        try {
            Map<String, Object> data = new LinkedHashMap<>();
            EarthquakeRecord first = allRecords.get(0);
            data.put("time", first.getTime());
            data.put("location", first.getLocation());
            data.put("magnitude", first.getMagnitude());
            data.put("depth", first.getDepth());
            data.put("reportType", first.getReportType());
            data.put("advice", first.getActionAdvice() != null ? first.getActionAdvice() : "");
            String cencUrl = buildCencUrl(first);
            data.put("cencUrl", cencUrl != null ? cencUrl : "");

            // 各城市影响
            List<Map<String, Object>> cityList = new ArrayList<>();
            for (EarthquakeRecord r : allRecords) {
                if (r.getCityName() == null) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", r.getCityName());
                c.put("intensity", r.getEstimatedLocalIntensity() != null ? r.getEstimatedLocalIntensity() : 0);
                c.put("distance", r.getEpicenterDistance() != null ? r.getEpicenterDistance() : 0);
                c.put("sWave", r.getSWaveArrivalSeconds() != null ? r.getSWaveArrivalSeconds() : 0);
                c.put("pWave", r.getPWaveArrivalSeconds() != null ? r.getPWaveArrivalSeconds() : 0);
                c.put("level", r.getImpactLevel() != null ? r.getImpactLevel() : 0);
                c.put("levelDesc", r.getImpactLevelDesc() != null ? r.getImpactLevelDesc() : "");
                cityList.add(c);
            }
            data.put("cities", cityList);

            String json = MAPPER.writeValueAsString(data);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String detailUrl = baseUrl + "/earthquake-detail.html#" + encoded;
            log.info("🔗 buildDetailUrl 成功: {}...", detailUrl.substring(0, Math.min(120, detailUrl.length())));
            return detailUrl;
        } catch (Exception e) {
            log.warn("构建详情页链接失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private void applyLevelStrategy(Map<String, Object> payload, int impactLevel) {
        switch (impactLevel) {
            case 5, 4:
                payload.put("level", "critical");
                payload.put("sound", configService.getBarkSound());
                payload.put("volume", 10);
                payload.put("call", String.valueOf(configService.getBarkCall()));
                break;
            case 3:
                payload.put("level", "critical");
                payload.put("sound", configService.getBarkSound());
                payload.put("volume", Math.min(configService.getBarkVolume(), 8));
                break;
            case 2:
                payload.put("level", "timeSensitive");
                payload.put("sound", "bell");
                payload.put("volume", 6);
                break;
            default:
                payload.put("level", "active");
                payload.put("sound", "bell");
                payload.put("volume", 5);
                break;
        }
    }

    /**
     * 发送测试通知到所有启用的设备
     */
    public int sendTestToAll(String title, String body) {
        List<BarkDevice> devices = configService.getEnabledBarkDevices();
        if (devices.isEmpty()) {
            log.warn("无启用的 Bark 设备");
            return 0;
        }
        int count = 0;
        for (BarkDevice d : devices) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("title", title);
                payload.put("body", body + "\n→ " + d.getName());
                payload.put("sound", "bell");
                payload.put("group", "地震预警");
                payload.put("level", "active");
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                String url = configService.getBarkApiUrl() + "/" + d.getDeviceKey();
                synchronized (restLock) {
                    ResponseEntity<String> resp = restTemplate.postForEntity(url, new HttpEntity<>(payload, headers), String.class);
                    if (resp.getStatusCode().is2xxSuccessful()) {
                        count++;
                    } else {
                        log.warn("Bark测试推送失败 → {} status:{}", d.getName(), resp.getStatusCode());
                    }
                }
            } catch (Exception e) {
                log.error("Bark测试推送失败 → {}: {}", d.getName(), e.getMessage());
            }
        }
        log.info("Bark 测试推送完成: {}/{} 个设备", count, devices.size());
        return count;
    }

    /** 兼容旧接口 */
    public boolean sendWarning(EarthquakeRecord record) {
        if (record == null) return false;
        return sendWarning(List.of(record)) > 0;
    }

    /** 兼容旧接口 */
    public boolean sendTestNotification(String title, String body) {
        return sendTestToAll(title, body) > 0;
    }

    public boolean isEnabled() { return configService.isBarkEnabled(); }
    public String getBarkApiUrl() { return configService.getBarkApiUrl(); }
}
