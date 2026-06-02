package com.sky.decisioncompanion.common;

import org.springframework.http.HttpStatus;

public enum ResultCode {

    SUCCESS(200, "操作成功", HttpStatus.OK),
    REGISTER_SUCCESS(200, "注册成功", HttpStatus.OK),
    LOGIN_SUCCESS(200, "登录成功", HttpStatus.OK),
    LOGOUT_SUCCESS(200, "退出成功", HttpStatus.OK),

    VALIDATION_FAILED(40000, "参数验证失败", HttpStatus.BAD_REQUEST),
    BAD_REQUEST(40001, "请求参数错误", HttpStatus.BAD_REQUEST),
    USERNAME_EMPTY(40010, "用户名不能为空", HttpStatus.BAD_REQUEST),
    USERNAME_TOO_LONG(40011, "用户名不能超过 50 个字符", HttpStatus.BAD_REQUEST),
    USERNAME_UNSUPPORTED_CHARS(40012, "用户名不能包含空白字符、斜杠或反斜杠", HttpStatus.BAD_REQUEST),
    PASSWORD_TOO_SHORT(40013, "密码至少需要 8 位", HttpStatus.BAD_REQUEST),
    PASSWORD_TOO_LONG(40014, "密码不能超过 72 位", HttpStatus.BAD_REQUEST),
    UNSUPPORTED_PROFILE_TYPE(40030, "不支持的画像类型", HttpStatus.BAD_REQUEST),

    UNAUTHORIZED(40100, "请先登录", HttpStatus.UNAUTHORIZED),
    AUTH_FAILED(40101, "用户名或密码错误", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID(40102, "登录状态异常，请重新登录", HttpStatus.UNAUTHORIZED),

    USER_NOT_FOUND(40401, "用户不存在", HttpStatus.NOT_FOUND),
    ONBOARDING_STEP_NOT_FOUND(40410, "步骤不存在", HttpStatus.NOT_FOUND),
    CONVERSATION_NOT_FOUND(40420, "会话不存在", HttpStatus.NOT_FOUND),
    PROFILE_MEMORY_NOT_FOUND(40430, "待确认记忆不存在", HttpStatus.NOT_FOUND),
    PROFILE_RECORD_NOT_FOUND(40431, "画像记录不存在", HttpStatus.NOT_FOUND),

    USERNAME_EXISTS(40901, "用户名已存在", HttpStatus.CONFLICT),
    ONBOARDING_COMPLETED(40910, "冷启动已完成", HttpStatus.CONFLICT),
    ONBOARDING_STEP_MISMATCH(40911, "请按当前步骤提交", HttpStatus.CONFLICT),
    PROFILE_MEMORY_ALREADY_HANDLED(40930, "待确认记忆已处理", HttpStatus.CONFLICT),
    PROFILE_MEMORY_EXPIRED(40931, "待确认记忆已过期", HttpStatus.CONFLICT),

    INTERNAL_ERROR(50000, "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ResultCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
