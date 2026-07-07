package com.earthquake.warning.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模拟地震请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "模拟地震测试请求")
public class SimulateRequest {

    @Schema(description = "震级", example = "6.5", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "震级不能为空")
    private String magnitude;

    @Schema(description = "震中纬度（-90 到 90）", example = "31.58", minimum = "-90", maximum = "90")
    @DecimalMin(value = "-90", message = "纬度必须在 -90 到 90 之间")
    @DecimalMax(value = "90", message = "纬度必须在 -90 到 90 之间")
    private double latitude;

    @Schema(description = "震中经度（-180 到 180）", example = "104.00", minimum = "-180", maximum = "180")
    @DecimalMin(value = "-180", message = "经度必须在 -180 到 180 之间")
    @DecimalMax(value = "180", message = "经度必须在 -180 到 180 之间")
    private double longitude;

    @Schema(description = "震源深度（km）", example = "15", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "震源深度不能为空")
    private String depth;

    @Schema(description = "震中位置描述", example = "四川德阳市绵竹市")
    private String location;

    @Schema(description = "是否发送 Bark 推送（默认 true）", example = "true")
    @Builder.Default
    private boolean sendBark = true;
}
