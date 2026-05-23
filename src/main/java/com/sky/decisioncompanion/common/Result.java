package com.sky.decisioncompanion.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "统一响应格式")
public class Result<T> {

    @Schema(description = "状态码，200表示成功")
    private int code;

    @Schema(description = "响应消息")
    private String message;

    @Schema(description = "响应数据")
    private T data;

    @Schema(description = "响应时间")
    private LocalDateTime timestamp;

    public static <T> Result<T> success(T data) {
        return success(ResultCode.SUCCESS, data);
    }

    public static <T> Result<T> success(ResultCode resultCode, T data) {
        Result<T> response = create(resultCode.getCode(), resultCode.getMessage());
        response.data = data;
        return response;
    }

    public static <T> Result<T> success(String message, T data) {
        Result<T> response = create(ResultCode.SUCCESS.getCode(), message);
        response.data = data;
        return response;
    }

    public static <T> Result<T> error(ResultCode resultCode) {
        return error(resultCode, resultCode.getMessage());
    }

    public static <T> Result<T> error(ResultCode resultCode, String message) {
        return create(resultCode.getCode(), message);
    }

    public static <T> Result<T> error(int code, String message) {
        return create(code, message);
    }

    public static <T> Result<T> error(String message) {
        return error(ResultCode.INTERNAL_ERROR, message);
    }

    private static <T> Result<T> create(int code, String message) {
        Result<T> response = new Result<>();
        response.code = code;
        response.message = message;
        response.timestamp = LocalDateTime.now();
        return response;
    }
}
