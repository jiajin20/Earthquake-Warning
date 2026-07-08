package com.earthquake.warning.service;

import com.earthquake.warning.model.EarthquakeRecord;
import com.earthquake.warning.model.MonitoredCity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 地震影响计算服务（v4.0 — 多城市支持）
 */
@Slf4j
@Service
public class EarthquakeCalculateService {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double ATTEN_A = 2.0;
    private static final double ATTEN_B = 1.5;
    private static final double ATTEN_C = 1.3;
    private static final double ATTEN_D = 15.0;

    private final TravelTimeService travelTimeService;
    private final SiteAmplificationService siteService;
    private final RuntimeConfigService configService;

    public EarthquakeCalculateService(TravelTimeService travelTimeService,
                                       SiteAmplificationService siteService,
                                       RuntimeConfigService configService) {
        this.travelTimeService = travelTimeService;
        this.siteService = siteService;
        this.configService = configService;
    }

    /**
     * 针对特定城市计算地震影响（填充 record 的计算字段）
     */
    public void calculate(EarthquakeRecord record, MonitoredCity city) {
        if (record == null || city == null) return;

        try {
            double eqLat = Double.parseDouble(record.getLatitude());
            double eqLon = Double.parseDouble(record.getLongitude());
            double mag = Double.parseDouble(record.getMagnitude());
            double depthKm = Double.parseDouble(record.getDepth());

            // 震中距离
            double epicenterDist = haversineDistance(city.getLatitude(), city.getLongitude(), eqLat, eqLon);
            record.setEpicenterDistance(Math.round(epicenterDist * 100.0) / 100.0);

            // 基岩烈度
            double bedrockIntensity = estimateBedrockIntensity(mag, epicenterDist);

            // 场地修正
            double siteIntensity = siteService.applySiteEffectForCity(bedrockIntensity, city);
            record.setEstimatedLocalIntensity(Math.round(siteIntensity * 10.0) / 10.0);

            // 影响等级
            int impactLevel = determineImpactLevel(siteIntensity);
            record.setImpactLevel(impactLevel);
            record.setImpactLevelDesc(getImpactLevelDesc(impactLevel));

            // 波到达时间
            double pTime = travelTimeService.computePTime(depthKm, epicenterDist);
            double sTime = travelTimeService.computeSTime(depthKm, epicenterDist);
            record.setPWaveArrivalSeconds(Math.round(pTime * 10.0) / 10.0);
            record.setSWaveArrivalSeconds(Math.round(sTime * 10.0) / 10.0);

            // 防护建议
            record.setActionAdvice(generateAdvice(impactLevel, sTime));

            // 标记归属城市
            record.setCityName(city.getName());

        } catch (NumberFormatException e) {
            log.error("解析地震数据失败: {}", e.getMessage());
            record.setEpicenterDistance(null);
            record.setImpactLevel(0);
            record.setImpactLevelDesc("无法计算");
            record.setActionAdvice("数据异常");
        }
    }

    /**
     * 对单个城市调用（兼容旧接口，取第一个启用城市）
     */
    public void calculate(EarthquakeRecord record) {
        configService.getEnabledCities().stream().findFirst()
                .ifPresentOrElse(
                        city -> calculate(record, city),
                        () -> {
                            record.setEpicenterDistance(0.0);
                            record.setImpactLevel(0);
                            record.setImpactLevelDesc("未配置监测城市");
                            record.setActionAdvice("请先配置监测城市");
                        });
    }

    public boolean shouldWarn(EarthquakeRecord record) {
        if (record == null || record.getImpactLevel() == null) return false;
        try {
            double mag = Double.parseDouble(record.getMagnitude());
            return mag >= configService.getMinMagnitude()
                    && record.getImpactLevel() >= configService.getMinImpactLevel();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 公式（不变） ====================

    public double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public double estimateBedrockIntensity(double magnitude, double distanceKm) {
        double R = Math.max(distanceKm, 0.5);
        return Math.max(0, ATTEN_A + ATTEN_B * magnitude - ATTEN_C * Math.log(R + ATTEN_D));
    }

    public int determineImpactLevel(double intensity) {
        if (intensity < 3.0) return 0;
        if (intensity < 4.0) return 1;
        if (intensity < 5.0) return 2;
        if (intensity < 6.0) return 3;
        if (intensity < 7.0) return 4;
        return 5;
    }

    public String getImpactLevelDesc(int level) {
        return switch (level) {
            case 0 -> "无感";
            case 1 -> "轻微有感";
            case 2 -> "明显有感";
            case 3 -> "强烈有感";
            case 4 -> "破坏性";
            case 5 -> "毁灭性";
            default -> "未知";
        };
    }

    public String generateAdvice(int impactLevel, double sWaveArrivalSec) {
        if (impactLevel == 0) return "无需采取特殊措施，保持关注";
        if (impactLevel <= 1) {
            return sWaveArrivalSec > 60 ? "震感轻微，保持警惕" : "震感轻微，请保持冷静，远离易倒物品";
        }
        if (impactLevel == 2) {
            return sWaveArrivalSec > 30 ? "有明显震感，请就近蹲下躲避，远离窗户" : "有明显震感，立即蹲下、掩护头部";
        }
        if (impactLevel == 3) {
            return sWaveArrivalSec > 15 ? "将有强烈震感！立即躲到坚固桌子下！" : "强烈震动即将到达！立即趴下、找个坚固掩体！";
        }
        if (impactLevel == 4) {
            return sWaveArrivalSec > 10 ? "⚠ 破坏性地震！蹲下、掩护、抓牢！" : "⚠⚠ 破坏性地震波即将到达！马上就地避险！";
        }
        return sWaveArrivalSec > 5 ? "🚨 毁灭性地震预警！立即趴下掩护抓牢！！" : "🚨🚨 毁灭性地震波即将到达！！卧倒、掩护、坚持住！！！";
    }

    /** 动态获取（兼容旧接口） */
    public double getMyLatitude() { return configService.getMyLatitude(); }
    public double getMyLongitude() { return configService.getMyLongitude(); }
    public String getMyLocationName() { return configService.getMyLocationName(); }
    public SiteAmplificationService getSiteService() { return siteService; }
    public RuntimeConfigService getConfigService() { return configService; }
}
