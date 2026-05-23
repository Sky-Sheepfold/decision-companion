package com.sky.decisioncompanion.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.SaTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        return errorResponse(e.getHttpStatus(), e.getResultCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse(ResultCode.VALIDATION_FAILED.getMessage());
        return errorResponse(ResultCode.VALIDATION_FAILED.getHttpStatus(), ResultCode.VALIDATION_FAILED, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        return errorResponse(ResultCode.BAD_REQUEST.getHttpStatus(), ResultCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLoginException(NotLoginException e) {
        return errorResponse(ResultCode.UNAUTHORIZED);
    }

    @ExceptionHandler(SaTokenException.class)
    public ResponseEntity<Result<Void>> handleSaTokenException(SaTokenException e) {
        return errorResponse(ResultCode.TOKEN_INVALID);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        logger.error("Unhandled exception", e);
        return errorResponse(ResultCode.INTERNAL_ERROR);
    }

    private ResponseEntity<Result<Void>> errorResponse(ResultCode resultCode) {
        return errorResponse(resultCode.getHttpStatus(), resultCode, resultCode.getMessage());
    }

    private ResponseEntity<Result<Void>> errorResponse(HttpStatus status, ResultCode resultCode, String message) {
        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(resultCode, message));
    }
}
