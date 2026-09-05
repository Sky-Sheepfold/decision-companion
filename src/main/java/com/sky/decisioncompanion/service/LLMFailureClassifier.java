package com.sky.decisioncompanion.service;

import java.util.List;
import java.util.Locale;

/**
 * LLM 调用失败分类器。
 *
 * <p>沿异常链收集消息，按标记识别限流 / 永久错误 / 瞬时错误，并给出分级退避延迟。
 * 参考 OpenBiliClaw 的"异常分类 → 类型化异常 → 按类定策略"管道设计。
 */
public final class LLMFailureClassifier {

    public enum FailureKind {
        RATE_LIMIT, TRANSIENT, PERMANENT
    }

    private static final long MAX_BACKOFF_MS = 300_000L;

    private static final List<String> RATE_LIMIT_MARKERS = List.of(
            "429", "rate limit", "rate_limit", "throttl", "quota", "too many request",
            "qps", "frequency", "限流", "频控", "访问过于频繁");

    private static final List<String> PERMANENT_MARKERS = List.of(
            "401", "403", "400", "404", "405", "422",
            "authentication", "invalid api key", "invalid_key", "api key",
            "model not found", "model_not_found", "不存在", "认证", "鉴权", "非法");

    private static final List<String> TRANSIENT_MARKERS = List.of(
            "timeout", "timed out", "connect", "read timed", "socket", "reset",
            "502", "503", "504", "server error", "internal server", "5xx",
            "unavailable", "network", "超时", "连接", "暂时", "服务不可用");

    private LLMFailureClassifier() {
    }

    public static FailureKind classify(Throwable error) {
        String message = collectMessages(error).toLowerCase(Locale.ROOT);
        if (matches(message, RATE_LIMIT_MARKERS)) {
            return FailureKind.RATE_LIMIT;
        }
        if (matches(message, PERMANENT_MARKERS)) {
            return FailureKind.PERMANENT;
        }
        if (matches(message, TRANSIENT_MARKERS)) {
            return FailureKind.TRANSIENT;
        }
        return FailureKind.TRANSIENT;
    }

    /**
     * 计算分级退避延迟：base * 2^(attempts-1)，上限 5 分钟。永久错误不重试（返回 0）。
     */
    public static long retryDelayMs(FailureKind kind, int attempts, long baseMs) {
        if (kind == FailureKind.PERMANENT) {
            return 0;
        }
        long base = Math.max(1_000L, baseMs);
        long delay = base * (1L << Math.min(Math.max(attempts - 1, 0), 5));
        return Math.min(delay, MAX_BACKOFF_MS);
    }

    private static boolean matches(String message, List<String> markers) {
        return markers.stream().anyMatch(message::contains);
    }

    private static String collectMessages(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }
}
