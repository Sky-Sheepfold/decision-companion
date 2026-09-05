package com.sky.decisioncompanion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 深层画像门控（PostureGate）配置。
 *
 * <p>mode 三档：
 * <ul>
 *   <li>off：不启用，保持原有写入行为（默认）</li>
 *   <li>shadow：不阻塞，接受写入并异步记录 LLM 裁判结果，用于评估</li>
 *   <li>enforce：阻塞等待 LLM 裁判，异常时保守降级（拒绝直接写入）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "decision-companion.memory.posture-gate")
public class PostureGateProperties {

    private String mode = "off";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode == null ? "off" : mode.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean isEnabled() {
        return !"off".equals(mode);
    }
}
