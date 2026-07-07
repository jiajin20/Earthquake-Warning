package com.earthquake.warning.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 监测城市
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MonitoredCity {

    /** 城市唯一ID */
    private String id;

    /** 城市名称 */
    private String name;

    /** 纬度 */
    private double latitude;

    /** 经度 */
    private double longitude;

    /** 场地类别 0-4 */
    private int siteClass;

    /** 自定义放大系数（0=使用默认值） */
    private double customAmplification;

    /** 是否启用 */
    private boolean enabled;
}
