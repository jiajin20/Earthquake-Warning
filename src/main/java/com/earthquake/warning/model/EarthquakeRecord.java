package com.earthquake.warning.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * CENC 地震记录
 * <p>
 * 来自 Wolfx wss://ws-api.wolfx.jp/cenc_eqlist 的数据格式：
 * {
 *   "No1": {
 *     "type": "automatic" | "reviewed",
 *     "EventID": "CD.20260706071531.566",
 *     "time": "2026-07-06 07:10:27",
 *     "ReportTime": "2026-07-06 07:15:35",
 *     "location": "云南大理州宾川县",
 *     "placeName": "云南大理州宾川县",
 *     "magnitude": "3.2",
 *     "depth": "10",
 *     "latitude": "25.94",
 *     "longitude": "100.57",
 *     "intensity": "5"
 *   },
 *   ...
 *   "md5": "41c8737f87d5cac68d60796a69e3f23e"
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EarthquakeRecord {

    /** 报告类型：automatic（自动测定）或 reviewed（正式测定） */
    @JsonProperty("type")
    private String reportType;

    /** 地震事件 ID */
    @JsonProperty("EventID")
    private String eventId;

    /** 发震时间（UTC+8） */
    private String time;

    /** 报告时间（UTC+8） */
    @JsonProperty("ReportTime")
    private String reportTime;

    /** 震中位置描述 */
    private String location;

    /** 震中地名 */
    private String placeName;

    /** 震级 */
    private String magnitude;

    /** 震源深度（km） */
    private String depth;

    /** 震中纬度 */
    private String latitude;

    /** 震中经度 */
    private String longitude;

    /** 最大烈度 */
    private String intensity;

    // ==== 计算字段 ====

    /** 记录编号（No1-No50） */
    private String no;

    /** 震中距离（km） */
    private Double epicenterDistance;

    /** 估算的本地影响烈度 */
    private Double estimatedLocalIntensity;

    /** 影响等级（0-5） */
    private Integer impactLevel;

    /** 影响等级描述 */
    private String impactLevelDesc;

    /** P波到达倒计时（秒） */
    private Double pWaveArrivalSeconds;

    /** S波到达倒计时（秒） */
    private Double sWaveArrivalSeconds;

    /** 建议措施 */
    private String actionAdvice;

    /** 对应监测城市名称（多地市模式时区分来源） */
    private String cityName;

    // ===== 排序工具 =====

    private static final DateTimeFormatter TIME_PARSER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 将 time 字符串转换为 LocalDateTime（用于排序比较）。
     * 格式不可解析时返回 1970-01-01（排在最后）。
     */
    public LocalDateTime getTimeAsDateTime() {
        if (time == null || time.isBlank()) return LocalDateTime.of(1970, 1, 1, 0, 0);
        try {
            return LocalDateTime.parse(time, TIME_PARSER);
        } catch (Exception e) {
            return LocalDateTime.of(1970, 1, 1, 0, 0);
        }
    }

    /** 按发震时间降序（最新在前）的比较器 */
    public static java.util.Comparator<EarthquakeRecord> byTimeDesc() {
        return java.util.Comparator.comparing(
                EarthquakeRecord::getTimeAsDateTime,
                java.util.Comparator.reverseOrder());
    }

    /** 浅拷贝一份记录（计算字段不拷贝，留给 calculateService 重新计算） */
    public EarthquakeRecord copy() {
        EarthquakeRecord r = new EarthquakeRecord();
        r.setNo(this.no);
        r.setReportType(this.reportType);
        r.setEventId(this.eventId);
        r.setTime(this.time);
        r.setReportTime(this.reportTime);
        r.setLocation(this.location);
        r.setPlaceName(this.placeName);
        r.setMagnitude(this.magnitude);
        r.setDepth(this.depth);
        r.setLatitude(this.latitude);
        r.setLongitude(this.longitude);
        r.setIntensity(this.intensity);
        return r;
    }
}
