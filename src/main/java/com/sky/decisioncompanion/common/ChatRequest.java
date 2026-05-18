package com.sky.decisioncompanion.common;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "聊天请求参数")
public class ChatRequest {

    @Schema(description = "消息内容")
    @NotBlank(message = "消息内容不能为空")
    private String message;
}
