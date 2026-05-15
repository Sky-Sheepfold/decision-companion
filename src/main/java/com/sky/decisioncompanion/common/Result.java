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
}
