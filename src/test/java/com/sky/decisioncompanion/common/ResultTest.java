package com.sky.decisioncompanion.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {

    @Test
    void successCanUseManagedMessage() {
        Result<String> result = Result.success(ResultCode.LOGIN_SUCCESS, "token");

        assertThat(result.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
        assertThat(result.getMessage()).isEqualTo(ResultCode.LOGIN_SUCCESS.getMessage());
        assertThat(result.getData()).isEqualTo("token");
        assertThat(result.getTimestamp()).isNotNull();
    }

    @Test
    void errorCanUseManagedCodeAndMessage() {
        Result<Void> result = Result.error(ResultCode.ONBOARDING_STEP_NOT_FOUND);

        assertThat(result.getCode()).isEqualTo(ResultCode.ONBOARDING_STEP_NOT_FOUND.getCode());
        assertThat(result.getMessage()).isEqualTo(ResultCode.ONBOARDING_STEP_NOT_FOUND.getMessage());
        assertThat(result.getTimestamp()).isNotNull();
    }
}
