package com.sky.decisioncompanion.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "聊天响应数据")
public class ChatResponse {

    @Schema(description = "AI 回复内容")
    private String reply;

    public ChatResponse() {
    }

    public ChatResponse(String reply) {
        this.reply = reply;
    }
}
