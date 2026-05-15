package com.sky.decisioncompanion.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "统一响应格式")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    @Schema(description = "状态码，200表示成功")
    private int code;

    @Schema(description = "响应消息")
    private String message;

    @Schema(description = "响应数据")
    private T data;

    @Schema(description = "响应时间")
    private LocalDateTime timestamp;

    private Result() {
        this.timestamp = LocalDateTime.now();
    }

    public static <T> Result<T> success(T data) {
        Result<T> response = new Result<>();
        response.code = 200;
        response.message = "Success";
        response.data = data;
        return response;
    }

    public static <T> Result<T> success(String message, T data) {
        Result<T> response = new Result<>();
        response.code = 200;
        response.message = message;
        response.data = data;
        return response;
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> response = new Result<>();
        response.code = code;
        response.message = message;
        return response;
    }

    public static <T> Result<T> error(String message) {
        return error(500, message);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
