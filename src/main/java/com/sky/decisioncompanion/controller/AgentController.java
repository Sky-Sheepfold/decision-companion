package com.sky.decisioncompanion.controller;

import com.sky.decisioncompanion.common.ChatRequest;
import com.sky.decisioncompanion.common.ChatResponse;
import com.sky.decisioncompanion.common.Result;
import com.sky.decisioncompanion.service.DecisionAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent 对话服务", description = "提供与通义千问的对话功能")
public class AgentController {

    private final DecisionAgentService agentService;

    public AgentController(DecisionAgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/chat")
    @Operation(summary = "普通对话", description = "与通义千问进行普通对话，返回完整的回复内容")
    @ApiResponse(responseCode = "200", description = "对话成功", content = @Content(schema = @Schema(implementation = Result.class)))
    public ResponseEntity<Result<ChatResponse>> chat(@Valid @RequestBody ChatRequest request) {
        String reply = agentService.chat(request.getSessionId(), request.getMessage());
        ChatResponse data = new ChatResponse(reply);
        return ResponseEntity.ok(Result.success(data));
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话", description = "与通义千问进行流式对话，使用 Server-Sent Events（SSE）")
    public Flux<String> chatStream(
            @Parameter(description = "会话 ID，用于区分不同的对话会话") @RequestParam String sessionId,
            @Parameter(description = "用户发送的消息内容") @RequestParam String message) {
        return agentService.chatStream(sessionId, message);
    }
}
