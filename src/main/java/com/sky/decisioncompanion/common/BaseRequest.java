package com.sky.decisioncompanion.common;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "基础请求参数")
public class BaseRequest {

    @Schema(description = "会话 ID，用于区分不同的对话会话")
    @NotBlank(message = "会话 ID 不能为空")
    private String sessionId;

    public BaseRequest() {
    }

    public BaseRequest(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
