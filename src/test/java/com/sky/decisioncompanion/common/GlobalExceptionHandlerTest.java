package com.sky.decisioncompanion.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.lang.reflect.Method;

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

    @Test
    void clientDisconnectUsesVoidHandlerWithoutWritingErrorResponse() {
        ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        Method method = resolver.resolveMethod(new AsyncRequestNotUsableException("ServletOutputStream failed to write"));

        assertThat(method).isNotNull();
        assertThat(method.getName()).isEqualTo("handleAsyncRequestNotUsableException");
        assertThat(method.getReturnType()).isEqualTo(Void.TYPE);
        assertThat(method.getAnnotation(ExceptionHandler.class).value())
                .containsExactly(AsyncRequestNotUsableException.class);
    }

    @Test
    void clientDisconnectLogsChineseDebugMessage() {
        LogCapture capture = attachLogAppender(Level.DEBUG);
        try {
            handler.handleAsyncRequestNotUsableException(
                    new AsyncRequestNotUsableException("ServletOutputStream failed to write"));

            assertThat(capture.appender().list)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .isEqualTo("客户端在响应完成前已断开连接"));
        } finally {
            detachLogAppender(capture);
        }
    }

    @Test
    void unexpectedExceptionLogsChineseErrorMessage() {
        LogCapture capture = attachLogAppender(Level.ERROR);
        try {
            handler.handleException(new RuntimeException("database password leaked"));

            assertThat(capture.appender().list)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .isEqualTo("未处理的服务端异常"));
        } finally {
            detachLogAppender(capture);
        }
    }

    private LogCapture attachLogAppender(Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(level);
        return new LogCapture(logger, appender, originalLevel);
    }

    private void detachLogAppender(LogCapture capture) {
        capture.logger().detachAppender(capture.appender());
        capture.logger().setLevel(capture.originalLevel());
    }

    private record LogCapture(Logger logger, ListAppender<ILoggingEvent> appender, Level originalLevel) {
    }
}
