package com.sky.decisioncompanion.common;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "聊天响应数据")
public class ChatResponse {

    @Schema(description = "AI 回复内容")
    private String reply;

    public ChatResponse() {
    }

    public ChatResponse(String reply) {
        this.reply = reply;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }
}
