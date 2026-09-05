package com.sky.decisioncompanion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 档案提炼任务队列配置。
 */
@ConfigurationProperties(prefix = "decision-companion.memory.extract")
public class ProfileExtractProperties {

    private boolean enabled = true;
    private int concurrency = 3;
    private long pollIntervalMs = 5000;
    private long orphanRecoverMs = 60000;
    private int batchSize = 10;
    private int maxAttempts = 5;
    private int orphanTimeoutMinutes = 30;
    private long retryDelayMs = 15000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getOrphanRecoverMs() {
        return orphanRecoverMs;
    }

    public void setOrphanRecoverMs(long orphanRecoverMs) {
        this.orphanRecoverMs = orphanRecoverMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getOrphanTimeoutMinutes() {
        return orphanTimeoutMinutes;
    }

    public void setOrphanTimeoutMinutes(int orphanTimeoutMinutes) {
        this.orphanTimeoutMinutes = orphanTimeoutMinutes;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }
}
