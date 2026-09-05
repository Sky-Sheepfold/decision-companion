package com.sky.decisioncompanion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 近期觉察（Awareness）记忆配置。
 */
@ConfigurationProperties(prefix = "decision-companion.memory.awareness")
public class MemoryAwarenessProperties {

    private boolean enabled = true;

    /** 取多近的对话作为提炼窗口（天）。 */
    private int windowDays = 7;

    /** 单次最多取多少条消息作为提炼输入。 */
    private int messageCap = 40;

    /** 定时提炼的间隔（毫秒）。 */
    private long scheduledIntervalMs = 2 * 60 * 60 * 1000L;

    /** 每轮最多处理多少用户。 */
    private int maxUsersPerRun = 50;

    /** 注入 prompt 的最多觉察条数。 */
    private int topLimit = 5;

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

    public int getMessageCap() {
        return messageCap;
    }

    public void setMessageCap(int messageCap) {
        this.messageCap = messageCap;
    }

    public long getScheduledIntervalMs() {
        return scheduledIntervalMs;
    }

    public void setScheduledIntervalMs(long scheduledIntervalMs) {
        this.scheduledIntervalMs = scheduledIntervalMs;
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

    public int windowDays() {
        return Math.max(1, windowDays);
    }

    public int messageCap() {
        return Math.max(1, messageCap);
    }

    public int maxUsersPerRun() {
        return Math.max(1, maxUsersPerRun);
    }

    public int topLimit() {
        return Math.max(1, topLimit);
    }
}
