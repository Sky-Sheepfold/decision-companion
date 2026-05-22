package com.sky.decisioncompanion.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.sky.decisioncompanion.common.ChatRequest;
import com.sky.decisioncompanion.common.ChatResponse;
import com.sky.decisioncompanion.common.Result;
import com.sky.decisioncompanion.model.ChatConversation;
import com.sky.decisioncompanion.model.ChatMessage;
import com.sky.decisioncompanion.service.ConversationHistoryService;
import com.sky.decisioncompanion.service.DecisionAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent 对话服务", description = "提供与通义千问的对话功能")
public class AgentController {

    private final DecisionAgentService agentService;
    private final ConversationHistoryService conversationHistoryService;

    public AgentController(
            DecisionAgentService agentService,
            ConversationHistoryService conversationHistoryService) {
        this.agentService = agentService;
        this.conversationHistoryService = conversationHistoryService;
    }

    @PostMapping("/chat")
    @Operation(summary = "普通对话", description = "与通义千问进行普通对话，返回完整的回复内容")
    @ApiResponse(responseCode = "200", description = "对话成功", content = @Content(schema = @Schema(implementation = Result.class)))
    public ResponseEntity<Result<ChatResponse>> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse data = agentService.chat(
                StpUtil.getLoginIdAsLong(),
                request.getConversationId(),
                request.getMessage());
        return ResponseEntity.ok(Result.success(data));
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话", description = "与通义千问进行流式对话，使用 Server-Sent Events（SSE）")
    public Flux<ServerSentEvent<String>> chatStream(
            @Parameter(description = "用户发送的消息内容") @RequestParam String message,
            @Parameter(description = "会话ID，不传则创建新会话") @RequestParam(required = false) Long conversationId) {
        DecisionAgentService.ChatStreamResult result = agentService.chatStream(
                StpUtil.getLoginIdAsLong(),
                conversationId,
                message);

        ServerSentEvent<String> conversationEvent = ServerSentEvent.<String>builder(
                        "{\"conversationId\":" + result.conversationId() + "}")
                .event("conversation")
                .build();

        return Flux.concat(
                Flux.just(conversationEvent),
                result.content().map(chunk -> ServerSentEvent.<String>builder(chunk)
                        .event("message")
                        .build()));
    }

    @GetMapping("/conversations")
    @Operation(summary = "获取历史会话列表", description = "按最近更新时间倒序返回当前登录用户未删除的历史会话")
    public ResponseEntity<Result<List<ChatConversation>>> listConversations(
            @Parameter(description = "返回数量，默认50，最大100") @RequestParam(defaultValue = "50") int limit) {
        List<ChatConversation> data = conversationHistoryService.listConversations(
                StpUtil.getLoginIdAsLong(),
                limit);
        return ResponseEntity.ok(Result.success(data));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "获取会话消息", description = "返回当前登录用户指定会话下的消息列表")
    public ResponseEntity<Result<List<ChatMessage>>> listMessages(
            @Parameter(description = "会话ID") @PathVariable Long conversationId) {
        List<ChatMessage> data = conversationHistoryService.listMessages(
                StpUtil.getLoginIdAsLong(),
                conversationId);
        return ResponseEntity.ok(Result.success(data));
    }

    @DeleteMapping("/conversations/{conversationId}")
    @Operation(summary = "删除历史会话", description = "软删除当前登录用户指定会话")
    public ResponseEntity<Result<Void>> deleteConversation(
            @Parameter(description = "会话ID") @PathVariable Long conversationId) {
        conversationHistoryService.deleteConversation(StpUtil.getLoginIdAsLong(), conversationId);
        return ResponseEntity.ok(Result.<Void>success(null));
    }
}
