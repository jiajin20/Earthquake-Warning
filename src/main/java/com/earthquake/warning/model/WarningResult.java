package com.earthquake.warning.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 地震预警结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "地震预警结果")
public class WarningResult {

    @Schema(description = "是否成功")
    private boolean success;

    @Schema(description = "消息")
    private String message;

    @Schema(description = "处理的地震记录数")
    private int processedCount;

    @Schema(description = "触发预警的记录数")
    private int warningCount;

    @Schema(description = "我的位置信息")
    private MyLocation myLocation;

    @Schema(description = "预警详情列表")
    private List<EarthquakeRecord> warnings;

    @Schema(description = "扩展字段（如模拟测试的逐城计算结果）")
    private Map<String, Object> extra;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "监测位置信息")
    public static class MyLocation {
        @Schema(description = "纬度", example = "30.5728")
        private double latitude;

        @Schema(description = "经度", example = "104.0668")
        private double longitude;

        @Schema(description = "位置名称", example = "成都市")
        private String name;
    }
}
