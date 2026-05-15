package com.sky.decisioncompanion.common;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "聊天请求参数")
public class ChatRequest extends BaseRequest {

    @Schema(description = "消息内容")
    @NotBlank(message = "消息内容不能为空")
    private String message;

    public ChatRequest() {
        super();
    }

    public ChatRequest(String sessionId, String message) {
        super(sessionId);
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
