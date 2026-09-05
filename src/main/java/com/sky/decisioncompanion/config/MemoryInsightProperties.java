package com.sky.decisioncompanion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 行为动机洞察（Insight）记忆配置。
 */
@ConfigurationProperties(prefix = "decision-companion.memory.insight")
public class MemoryInsightProperties {

    private boolean enabled = true;

    /** 取多近的觉察作为提炼输入（天）。 */
    private int windowDays = 14;

    /** 单次最多取多少条觉察作为提炼输入。 */
    private int awarenessCap = 30;

    /** 定时提炼的间隔（毫秒）。 */
    private long scheduledIntervalMs = 6 * 60 * 60 * 1000L;

    /** 应用启动后多久开始首次定时提炼（毫秒），避免启动瞬间与 Awareness 并发打一波 LLM。 */
    private long initialDelayMs = 5 * 60 * 1000L;

    /** 每轮最多处理多少用户。 */
    private int maxUsersPerRun = 50;

    /** 注入 prompt 的最多洞察条数。 */
    private int topLimit = 3;

    /** 用户确认时置信度的下限（确认视为较可信理解）。 */
    private double confirmConfidenceFloor = 0.75;

    /** 语义近重复阈值：与已有假设的向量余弦相似度 >= 该值视为同一假设（合并而非新增）。<=0 关闭语义去重。 */
    private double nearDuplicateThreshold = 0.85;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWindowDays() {
        return windowDays;
    }

    public void setWindowDays(int windowDays) {
        this.windowDays = windowDays;
    }

    public int getAwarenessCap() {
        return awarenessCap;
    }

    public void setAwarenessCap(int awarenessCap) {
        this.awarenessCap = awarenessCap;
    }

    public long getScheduledIntervalMs() {
        return scheduledIntervalMs;
    }

    public void setScheduledIntervalMs(long scheduledIntervalMs) {
        this.scheduledIntervalMs = scheduledIntervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public int getMaxUsersPerRun() {
        return maxUsersPerRun;
    }

    public void setMaxUsersPerRun(int maxUsersPerRun) {
        this.maxUsersPerRun = maxUsersPerRun;
    }

    public int getTopLimit() {
        return topLimit;
    }

    public void setTopLimit(int topLimit) {
        this.topLimit = topLimit;
    }

    public double getConfirmConfidenceFloor() {
        return confirmConfidenceFloor;
    }

    public void setConfirmConfidenceFloor(double confirmConfidenceFloor) {
        this.confirmConfidenceFloor = confirmConfidenceFloor;
    }

    public double getNearDuplicateThreshold() {
        return nearDuplicateThreshold;
    }

    public void setNearDuplicateThreshold(double nearDuplicateThreshold) {
        this.nearDuplicateThreshold = nearDuplicateThreshold;
    }

    public int windowDays() {
        return Math.max(1, windowDays);
    }

    public int awarenessCap() {
        return Math.max(1, awarenessCap);
    }

    public int maxUsersPerRun() {
        return Math.max(1, maxUsersPerRun);
    }

    public int topLimit() {
        return Math.max(1, topLimit);
    }

    public double confirmConfidenceFloor() {
        return Math.max(0.0, Math.min(1.0, confirmConfidenceFloor));
    }

    /** 语义近重复阈值（<=0 表示关闭语义去重）。 */
    public double nearDuplicateThreshold() {
        return Math.max(0.0, Math.min(1.0, nearDuplicateThreshold));
    }

    public long initialDelayMs() {
        return Math.max(0, initialDelayMs);
    }
}
