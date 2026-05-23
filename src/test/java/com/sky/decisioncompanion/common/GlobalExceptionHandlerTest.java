package com.sky.decisioncompanion.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionUsesManagedStatusCodeAndMessage() {
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(
                new BusinessException(ResultCode.USERNAME_EXISTS));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.USERNAME_EXISTS.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo(ResultCode.USERNAME_EXISTS.getMessage());
        assertThat(response.getBody().getTimestamp()).isNotNull();
    }

    @Test
    void unexpectedExceptionReturnsManagedInternalErrorWithoutLeakingDetails() {
        ResponseEntity<Result<Void>> response = handler.handleException(new RuntimeException("database password leaked"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.INTERNAL_ERROR.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo(ResultCode.INTERNAL_ERROR.getMessage());
    }
}
