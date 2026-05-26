package com.sky.decisioncompanion.service.profile;

import java.math.BigDecimal;

public final class ProfileWritePolicy {

    private static final BigDecimal DEFAULT_CONFIDENCE = BigDecimal.ZERO;
    private static final BigDecimal PROFILE_WRITE_THRESHOLD = new BigDecimal("0.85");
    private static final BigDecimal PROFILE_CONFIRM_THRESHOLD = new BigDecimal("0.60");
    private static final BigDecimal SENSITIVE_PROFILE_WRITE_THRESHOLD = new BigDecimal("0.90");
    private static final BigDecimal SENSITIVE_PROFILE_CONFIRM_THRESHOLD = new BigDecimal("0.70");

    private ProfileWritePolicy() {
    }

    public static BigDecimal normalizeConfidence(Double confidence) {
        if (confidence == null || Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return DEFAULT_CONFIDENCE;
        }
        double value = Math.max(0.0, Math.min(1.0, confidence));
        return BigDecimal.valueOf(value);
    }

    public static BigDecimal normalizeConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return DEFAULT_CONFIDENCE;
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (confidence.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return confidence;
    }

    public static Decision decide(String profileType, BigDecimal confidence) {
        BigDecimal value = normalizeConfidence(confidence);
        boolean sensitive = isSensitive(profileType);
        BigDecimal writeThreshold = sensitive ? SENSITIVE_PROFILE_WRITE_THRESHOLD : PROFILE_WRITE_THRESHOLD;
        BigDecimal confirmThreshold = sensitive ? SENSITIVE_PROFILE_CONFIRM_THRESHOLD : PROFILE_CONFIRM_THRESHOLD;

        if (value.compareTo(writeThreshold) >= 0) {
            return new Decision(true, "written", "画像置信度较高，已自动写入",
                    "confidence write: " + value);
        }
        if (value.compareTo(confirmThreshold) >= 0) {
            return new Decision(false, "needs_confirmation", "画像置信度中等，需要先询问用户确认",
                    "needs_confirmation: confidence=" + value);
        }
        return new Decision(false, "skipped", "画像置信度较低，已跳过写入",
                "confidence too low: " + value);
    }

    public static boolean isSensitive(String profileType) {
        return "fear".equals(profileType) || "boundary".equals(profileType);
    }

    public record Decision(boolean writable, String action, String message, String logSummary) {
    }
}
