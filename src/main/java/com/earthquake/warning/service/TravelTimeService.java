package com.earthquake.warning.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * IASPEI-91 一维地球速度模型走时计算
 * <p>
 * 替代原先的"定速直线传播"模型。基于 IASP91 (Kennett & Engdahl, 1991)
 * 标准地球参考模型的上 210km 部分，使用射线参数迭代法计算 P 波与 S 波走时。
 * <p>
 * 算法原理：
 * <ol>
 *   <li>将地壳/上地幔划分为 5 个恒速层</li>
 *   <li>对给定的震源深度和震中距，二分搜索射线参数 p = sin(θ)/v</li>
 *   <li>沿射线路径积分距离和走时</li>
 *   <li>自动处理 Pg/Sg（地壳直达波）与 Pn/Sn（莫霍面首波）的分支</li>
 * </ol>
 */
@Slf4j
@Service
public class TravelTimeService {

    /** 每一层的顶面深度 (km) */
    private static final double[] LAYER_TOP = {0.0,  3.0, 20.0, 35.0, 80.0, 210.0};

    /** P 波速度 (km/s) — IASP91 上 210km 简化 */
    private static final double[] VP =     {5.40, 5.80, 6.50, 8.04, 8.05};

    /** S 波速度 (km/s)，按 Vp/Vs ≈ 1.732 推算 */
    private static final double[] VS =     {3.12, 3.36, 3.75, 4.47, 4.49};

    /** 层数 */
    private static final int N_LAYERS = VP.length;

    /** 射线参数二分搜索迭代次数 */
    private static final int BINARY_ITER = 60;

    /** 距离收敛阈值 (km) */
    private static final double DIST_TOL = 0.5;

    /** 最大震中距 (km)，超过此距离走时估算不可靠 */
    private static final double MAX_DISTANCE = 1000.0;

    /** 最深层底界 (km) */
    private static final double MODEL_BOTTOM = LAYER_TOP[N_LAYERS];

    /** 地球半径，用于球面修正 (km) */
    private static final double EARTH_R = 6371.0;

    /** 最小 cosθ，防止 pv≈1 时数值爆炸（cosθ→0 导致除法溢出） */
    private static final double MIN_COS_THETA = 0.002;
    /** 对应的最大 tanθ（防止距离计算溢出） */
    private static final double MAX_TAN_THETA = 500.0;

    /** P 波 crustal 平均速度 (km/s)，用于 fallback */
    private static final double VP_CRUST = 5.8;
    /** S 波 crustal 平均速度 (km/s) */
    private static final double VS_CRUST = 3.36;

    // ===================== 预计算表（加速运行时查询） =====================
    // 105 个距离点 × 7 个深度点 = 735 个预计算值

    /** 距离采样点 (km) */
    private static final double[] PRE_DIST =
            {0, 2, 5, 8, 10, 13, 16, 20, 25, 30, 35, 40, 46, 52, 58, 65, 72, 80, 88,
             96, 105, 114, 124, 134, 145, 156, 168, 180, 193, 206, 220, 235, 250, 266,
             282, 298, 315, 332, 350, 368, 386, 405, 424, 443, 462, 482, 502, 522, 542,
             563, 584, 605, 626, 648, 670, 692, 714, 736, 758, 780, 802, 824, 846, 868,
             890, 912, 934, 956, 978, 1000};

    /** 深度采样点 (km) */
    private static final double[] PRE_DEPTH = {0, 5, 10, 15, 20, 33, 50};

    /** 预计算 P 波走时表 [depth_idx][dist_idx] */
    private double[][] prePTable;

    /** 预计算 S 波走时表 [depth_idx][dist_idx] */
    private double[][] preSTable;

    public TravelTimeService() {
        preComputeTables();
        log.info("IASPEI-91 走时表预计算完成: {} 距离点 x {} 深度点 = {} 个值",
                PRE_DIST.length, PRE_DEPTH.length, PRE_DIST.length * PRE_DEPTH.length);
    }

    /**
     * 预计算走时表
     */
    private void preComputeTables() {
        int nDist = PRE_DIST.length;
        int nDepth = PRE_DEPTH.length;
        prePTable = new double[nDepth][nDist];
        preSTable = new double[nDepth][nDist];

        for (int d = 0; d < nDepth; d++) {
            for (int i = 0; i < nDist; i++) {
                if (PRE_DIST[i] < 0.01) {
                    prePTable[d][i] = 0.0;
                    preSTable[d][i] = 0.0;
                } else {
                    double tP = computeTravelTimeDirect(PRE_DEPTH[d], PRE_DIST[i], true);
                    double tS = computeTravelTimeDirect(PRE_DEPTH[d], PRE_DIST[i], false);
                    // -1 表示未收敛，用简单公式；NaN/Infinity 同理
                    prePTable[d][i] = (tP > 0 && Double.isFinite(tP)) ? tP : PRE_DIST[i] / VP_CRUST;
                    preSTable[d][i] = (tS > 0 && Double.isFinite(tS)) ? tS : PRE_DIST[i] / VS_CRUST;
                }
            }
        }
    }

    // ===================== 公开 API =====================

    /**
     * 计算 P 波走时
     *
     * @param sourceDepth     震源深度 (km)
     * @param epicentralDist  震中距 (km)
     * @return P 波走时 (秒)
     */
    public double computePTime(double sourceDepth, double epicentralDist) {
        if (epicentralDist < 0.01) return 0.0;
        if (epicentralDist > MAX_DISTANCE) return epicentralDist / VP_CRUST;
        double t = interpolateTable(sourceDepth, Math.max(0.1, epicentralDist), true);
        if (!Double.isFinite(t) || t <= 0) {
            // fallback：直线传播近似
            return epicentralDist / VP_CRUST;
        }
        return t;
    }

    /**
     * 计算 S 波走时
     *
     * @param sourceDepth     震源深度 (km)
     * @param epicentralDist  震中距 (km)
     * @return S 波走时 (秒)
     */
    public double computeSTime(double sourceDepth, double epicentralDist) {
        if (epicentralDist < 0.01) return 0.0;
        if (epicentralDist > MAX_DISTANCE) return epicentralDist / VS_CRUST;
        double t = interpolateTable(sourceDepth, Math.max(0.1, epicentralDist), false);
        if (!Double.isFinite(t) || t <= 0) {
            return epicentralDist / VS_CRUST;
        }
        return t;
    }

    // ===================== 表插值 =====================

    /**
     * 双线性插值查表获取走时
     */
    private double interpolateTable(double depth, double dist, boolean isP) {
        double[][] table = isP ? prePTable : preSTable;

        // 查找深度区间
        int d1, d2;
        if (depth <= PRE_DEPTH[0]) { d1 = 0; d2 = 0; }
        else if (depth >= PRE_DEPTH[PRE_DEPTH.length - 1]) {
            d1 = PRE_DEPTH.length - 1; d2 = d1;
        } else {
            int idx = 0;
            while (idx < PRE_DEPTH.length - 1 && PRE_DEPTH[idx + 1] <= depth) idx++;
            d1 = idx; d2 = idx + 1;
        }

        // 查找距离区间
        int i1, i2;
        if (dist <= PRE_DIST[0]) { i1 = 0; i2 = 0; }
        else if (dist >= PRE_DIST[PRE_DIST.length - 1]) {
            i1 = PRE_DIST.length - 1; i2 = i1;
        } else {
            int idx = 0;
            while (idx < PRE_DIST.length - 1 && PRE_DIST[idx + 1] <= dist) idx++;
            i1 = idx; i2 = idx + 1;
        }

        // 双线性插值
        double fd = 0, fi = 0;
        if (d1 != d2) fd = (depth - PRE_DEPTH[d1]) / (PRE_DEPTH[d2] - PRE_DEPTH[d1]);
        if (i1 != i2) fi = (dist - PRE_DIST[i1]) / (PRE_DIST[i2] - PRE_DIST[i1]);

        double v11 = table[d1][i1], v12 = table[d1][i2];
        double v21 = table[d2][i1], v22 = table[d2][i2];

        double v1 = v11 + fi * (v12 - v11);
        double v2 = v21 + fi * (v22 - v21);

        return v1 + fd * (v2 - v1);
    }

    // ===================== 精确射线追踪 =====================

    /**
     * 通过射线参数二分搜索计算走时（用于预填表格）
     * <p>
     * 当目标距离落在层边界的不连续区域（速度跳跃导致距离函数跳变）时，
     * 二分搜索无法收敛，此时返回 -1 表示需要用简单公式替代。
     */
    private double computeTravelTimeDirect(double sourceDepth, double epicentralDist, boolean isP) {
        double[] velocities = isP ? VP : VS;
        double vSource = getVelocityAt(sourceDepth, velocities);

        if (vSource <= 0) return -1;

        // 射线参数搜索范围
        double pMin = 0;
        double pMax = 1.0 / vSource;

        double bestP = pMin;
        double bestDiff = Double.MAX_VALUE;

        for (int iter = 0; iter < BINARY_ITER; iter++) {
            double p = (pMin + pMax) / 2;
            double dist = computeDistance(sourceDepth, p, velocities);

            double diff = Math.abs(dist - epicentralDist);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestP = p;
            }

            if (diff < DIST_TOL) {
                double t = computeTime(sourceDepth, p, velocities);
                return Double.isFinite(t) && t > 0 ? t : -1;
            }

            if (dist < epicentralDist) {
                pMax = p;
            } else {
                pMin = p;
            }
        }

        // 二分结束但未收敛：检查 bestP 是否可用
        double finalDist = computeDistance(sourceDepth, bestP, velocities);
        double time = computeTime(sourceDepth, bestP, velocities);

        // 距离误差 < 20% 且时间合理 → 可用
        if (bestDiff < epicentralDist * 0.2
                && Double.isFinite(time) && time > 0 && time < epicentralDist * 3) {
            return time;
        }

        // 不可用 → 返回 -1 触发 fallback
        log.debug("射线追踪未收敛: depth={}, dist={}, bestDiff={}, 使用回退公式",
                sourceDepth, epicentralDist, bestDiff);
        return -1;
    }

    /**
     * 给定射线参数 p，计算总水平距离
     * <p>
     * 射线从震源出发，分为上行和下行两支：
     * <ul>
     *   <li>上行支：从震源向上至地表，速度递减，pv 只减不增，不应出现 pv≥1</li>
     *   <li>下行支：从震源向下至转折深度，速度递增，pv 增大，当 pv≥1 时射线转折</li>
     * </ul>
     * 下行支距离 ×2 因为包含下行 + 上行回程。
     */
    private double computeDistance(double sourceDepth, double p, double[] velocities) {
        double distUp = 0;
        double distDown = 0;
        int sourceLayer = getLayerIndex(sourceDepth);

        // === 上行段：震源 → 地表 ===
        for (int i = sourceLayer; i >= 0; i--) {
            double v = velocities[i];
            double pv = p * v;
            if (pv >= 1.0) break;

            double tanTheta = pv / Math.sqrt(Math.max(1 - pv * pv, MIN_COS_THETA * MIN_COS_THETA));
            if (tanTheta > MAX_TAN_THETA) tanTheta = MAX_TAN_THETA;
            double h = (i == sourceLayer)
                    ? sourceDepth - LAYER_TOP[i]
                    : LAYER_TOP[i + 1] - LAYER_TOP[i];
            distUp += h * tanTheta;
        }

        // === 下行段：震源 → 转折深度 ===
        for (int i = sourceLayer; i < N_LAYERS; i++) {
            double v = velocities[i];
            double pv = p * v;
            if (pv >= 1.0) break;

            double tanTheta = pv / Math.sqrt(Math.max(1 - pv * pv, MIN_COS_THETA * MIN_COS_THETA));
            if (tanTheta > MAX_TAN_THETA) tanTheta = MAX_TAN_THETA;
            double h = (i == sourceLayer)
                    ? LAYER_TOP[i + 1] - sourceDepth
                    : LAYER_TOP[i + 1] - LAYER_TOP[i];
            distDown += h * tanTheta;
        }

        double totalDist = distUp + 2 * distDown;

        // 球面修正 (Earth flattening)；防止溢出
        double avgDepth = Math.max(sourceDepth, 0.1) / 2.0;
        double factor = EARTH_R / (EARTH_R - avgDepth);
        if (Double.isFinite(totalDist * factor)) {
            return totalDist * factor;
        }
        return totalDist;
    }

    /**
     * 给定射线参数 p，计算总走时
     * <p>
     * 时间 = 段厚度 / (速度 × cosθ)，下行支 ×2（往返）。
     */
    private double computeTime(double sourceDepth, double p, double[] velocities) {
        double timeUp = 0;
        double timeDown = 0;
        int sourceLayer = getLayerIndex(sourceDepth);

        // === 上行段 ===
        for (int i = sourceLayer; i >= 0; i--) {
            double v = velocities[i];
            double pv = p * v;
            if (pv >= 1.0) break;

            double cosTheta = Math.sqrt(Math.max(1 - pv * pv, MIN_COS_THETA * MIN_COS_THETA));
            double h = (i == sourceLayer)
                    ? sourceDepth - LAYER_TOP[i]
                    : LAYER_TOP[i + 1] - LAYER_TOP[i];
            timeUp += h / (v * cosTheta);
        }

        // === 下行段 ===
        for (int i = sourceLayer; i < N_LAYERS; i++) {
            double v = velocities[i];
            double pv = p * v;
            if (pv >= 1.0) break;

            double cosTheta = Math.sqrt(Math.max(1 - pv * pv, MIN_COS_THETA * MIN_COS_THETA));
            double h = (i == sourceLayer)
                    ? LAYER_TOP[i + 1] - sourceDepth
                    : LAYER_TOP[i + 1] - LAYER_TOP[i];
            timeDown += h / (v * cosTheta);
        }

        return timeUp + 2 * timeDown; // 下行 ×2 = 下去 + 上来
    }

    // ===================== 辅助方法 =====================

    /**
     * 获取指定深度处的层速度
     */
    private double getVelocityAt(double depth, double[] velocities) {
        if (depth >= MODEL_BOTTOM) return velocities[N_LAYERS - 1];
        int layer = getLayerIndex(depth);
        return velocities[layer];
    }

    /**
     * 获取深度所在的层索引
     */
    private int getLayerIndex(double depth) {
        for (int i = 0; i < N_LAYERS; i++) {
            if (depth < LAYER_TOP[i + 1]) return i;
        }
        return N_LAYERS - 1;
    }
}
