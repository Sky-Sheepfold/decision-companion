package com.sky.decisioncompanion.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LLMFailureClassifierTest {

    @Test
    void classifiesRateLimitByMarker() {
        LLMFailureClassifier.FailureKind kind = LLMFailureClassifier.classify(
                new RuntimeException("HTTP 429 Too Many Requests, QPS limit exceeded"));
        assertThat(kind).isEqualTo(LLMFailureClassifier.FailureKind.RATE_LIMIT);
    }

    @Test
    void classifiesRateLimitByChineseMarker() {
        LLMFailureClassifier.FailureKind kind = LLMFailureClassifier.classify(
                new RuntimeException("触发限流，请稍后再试"));
        assertThat(kind).isEqualTo(LLMFailureClassifier.FailureKind.RATE_LIMIT);
    }

    @Test
    void classifiesTransientTimeoutAsTransient() {
        LLMFailureClassifier.FailureKind kind = LLMFailureClassifier.classify(
                new RuntimeException("Read timed out after 60000ms"));
        assertThat(kind).isEqualTo(LLMFailureClassifier.FailureKind.TRANSIENT);
    }

    @Test
    void classifiesAuthFailureAsPermanent() {
        LLMFailureClassifier.FailureKind kind = LLMFailureClassifier.classify(
                new RuntimeException("401 Authentication failed: invalid api key"));
        assertThat(kind).isEqualTo(LLMFailureClassifier.FailureKind.PERMANENT);
    }

    @Test
    void classifiesUnknownFailureAsTransient() {
        LLMFailureClassifier.FailureKind kind = LLMFailureClassifier.classify(
                new RuntimeException("some unknown error"));
        assertThat(kind).isEqualTo(LLMFailureClassifier.FailureKind.TRANSIENT);
    }

    @Test
    void walksExceptionCauseChainForClassification() {
        Exception root = new RuntimeException("connection reset by peer");
        Exception wrapper = new RuntimeException("LLM call failed", root);
        assertThat(LLMFailureClassifier.classify(wrapper))
                .isEqualTo(LLMFailureClassifier.FailureKind.TRANSIENT);
    }

    @Test
    void backoffGrowsExponentiallyFromBaseAndCapsAtFiveMinutes() {
        assertThat(LLMFailureClassifier.retryDelayMs(LLMFailureClassifier.FailureKind.TRANSIENT, 1, 15_000L))
                .isEqualTo(15_000L);
        assertThat(LLMFailureClassifier.retryDelayMs(LLMFailureClassifier.FailureKind.TRANSIENT, 2, 15_000L))
                .isEqualTo(30_000L);
        assertThat(LLMFailureClassifier.retryDelayMs(LLMFailureClassifier.FailureKind.TRANSIENT, 3, 15_000L))
                .isEqualTo(60_000L);
        // 超过 5 分钟上限
        assertThat(LLMFailureClassifier.retryDelayMs(LLMFailureClassifier.FailureKind.TRANSIENT, 10, 15_000L))
                .isEqualTo(300_000L);
    }

    @Test
    void permanentFailureNeverRetries() {
        assertThat(LLMFailureClassifier.retryDelayMs(LLMFailureClassifier.FailureKind.PERMANENT, 1, 15_000L))
                .isZero();
    }
}
