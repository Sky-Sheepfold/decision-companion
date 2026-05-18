package com.sky.decisioncompanion.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.SaTokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("参数验证失败");
        return errorResponse(HttpStatus.BAD_REQUEST, 400, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        return errorResponse(HttpStatus.BAD_REQUEST, 400, e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLoginException(NotLoginException e) {
        return errorResponse(HttpStatus.UNAUTHORIZED, 401, "请先登录");
    }

    @ExceptionHandler(SaTokenException.class)
    public ResponseEntity<Result<Void>> handleSaTokenException(SaTokenException e) {
        return errorResponse(HttpStatus.UNAUTHORIZED, 401, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 500, "服务器内部错误: " + e.getMessage());
    }

    private ResponseEntity<Result<Void>> errorResponse(HttpStatus status, int code, String message) {
        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(code, message));
    }
}
