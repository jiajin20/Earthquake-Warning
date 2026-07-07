package com.earthquake.warning.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bark 推送设备
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BarkDevice {

    /** 设备唯一ID */
    private String id;

    /** 设备名称（如"我的iPhone"、"家人的iPhone"） */
    private String name;

    /** Bark Device Key */
    private String deviceKey;

    /** 是否启用 */
    private boolean enabled;
}
