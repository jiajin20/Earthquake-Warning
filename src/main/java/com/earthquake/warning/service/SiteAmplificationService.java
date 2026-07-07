package com.earthquake.warning.service;

import com.earthquake.warning.model.MonitoredCity;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 场地放大系数服务（v4.0 — 多城市支持）
 */
@Slf4j
@Service
public class SiteAmplificationService {

    private final RuntimeConfigService configService;

    @Getter
    private String siteDescription;

    public SiteAmplificationService(RuntimeConfigService configService) {
        this.configService = configService;
        // 动态生成场地描述，不再硬编码"成都平原"
        this.siteDescription = buildSiteDescription();
    }

    /**
     * 获取场地类别（取第一个启用的城市，兼容旧接口）
     */
    public int getSiteClass() {
        return configService.getEnabledCities().stream().findFirst()
                .map(MonitoredCity::getSiteClass).orElse(3);
    }

    /**
     * 获取场地放大系数（取第一个启用的城市，兼容旧接口）
     */
    public double getAmplificationFactor() {
        return configService.getEnabledCities().stream().findFirst()
                .map(c -> c.getCustomAmplification() > 0.01 ? c.getCustomAmplification()
                        : switch (c.getSiteClass()) {
                            case 0 -> 0.9; case 1 -> 1.0; case 2 -> 1.3;
                            case 3 -> 1.6; case 4 -> 2.1; default -> 1.6;
                        })
                .orElse(1.6);
    }

    public String getSiteClassDescription() {
        int sc = getSiteClass();
        return switch (sc) {
            case 0 -> "I₀类 — 坚硬岩石";
            case 1 -> "I₁类 — 岩石";
            case 2 -> "II类 — 中硬土";
            case 3 -> "III类 — 中软土";
            case 4 -> "IV类 — 软弱土";
            default -> "未知类别";
        };
    }

    /** 针对特定城市应用场地效应 */
    public double applySiteEffectForCity(double bedrockIntensity, MonitoredCity city) {
        double amp = getAmplificationForCity(city);
        double deltaI = Math.log(amp) / Math.log(2.0);
        return bedrockIntensity + deltaI;
    }

    private double getAmplificationForCity(MonitoredCity city) {
        if (city.getCustomAmplification() > 0.01) return city.getCustomAmplification();
        return switch (city.getSiteClass()) {
            case 0 -> 0.9; case 1 -> 1.0; case 2 -> 1.3;
            case 3 -> 1.6; case 4 -> 2.1; default -> 1.6;
        };
    }

    public String getSiteClassDescription(MonitoredCity city) {
        return switch (city.getSiteClass()) {
            case 0 -> "I₀类 — 坚硬岩石";
            case 1 -> "I₁类 — 岩石";
            case 2 -> "II类 — 中硬土";
            case 3 -> "III类 — 中软土";
            case 4 -> "IV类 — 软弱土";
            default -> "未知类别";
        };
    }

    /** 兼容旧接口 */
    public double applySiteEffect(double bedrockIntensity) {
        double amp = getAmplificationFactor();
        double deltaI = Math.log(amp) / Math.log(2.0);
        return bedrockIntensity + deltaI;
    }

    /** 根据当前启用的城市动态生成场地描述 */
    private String buildSiteDescription() {
        var cities = configService.getEnabledCities();
        if (cities.isEmpty()) return "未配置城市";
        var city = cities.get(0);
        String siteDesc = getSiteClassDescription(city);
        return city.getName() + " — " + siteDesc;
    }
}
