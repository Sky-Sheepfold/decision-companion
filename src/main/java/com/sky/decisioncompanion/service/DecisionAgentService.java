package com.sky.decisioncompanion.service;

import com.sky.decisioncompanion.advisor.ProfileAdvisorService;
import com.sky.decisioncompanion.common.ChatResponse;
import com.sky.decisioncompanion.model.ChatConversation;
import com.sky.decisioncompanion.service.agenttool.AgentToolContext;
import com.sky.decisioncompanion.service.agenttool.AgentToolRegistry;
import com.sky.decisioncompanion.service.agenttool.DecisionAgentToolService;
import com.sky.decisioncompanion.service.agenttool.AgentToolInvocationTracker;
import com.sky.decisioncompanion.service.memory.MemoryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DecisionAgentService {

    private static final Logger logger = LoggerFactory.getLogger(DecisionAgentService.class);

    private final ChatClient chatClient;
    private final ProfileAdvisorService profileAdvisorService;
    private final ProfileExtractJobService profileExtractJobService;
    private final ConversationHistoryService conversationHistoryService;
    private final DecisionAgentToolService agentToolService;
    private final AgentToolInvocationTracker toolInvocationTracker;
    private final AgentToolRegistry toolRegistry;

    public DecisionAgentService(
            ChatClient.Builder builder,
            ChatMemory chatMemory,
            ProfileAdvisorService profileAdvisorService,
            ProfileExtractJobService profileExtractJobService,
            ConversationHistoryService conversationHistoryService,
            DecisionAgentToolService agentToolService,
            AgentToolInvocationTracker toolInvocationTracker,
            AgentToolRegistry toolRegistry) {
        this.chatClient = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        this.profileAdvisorService = profileAdvisorService;
        this.profileExtractJobService = profileExtractJobService;
        this.conversationHistoryService = conversationHistoryService;
        this.agentToolService = agentToolService;
        this.toolInvocationTracker = toolInvocationTracker;
        this.toolRegistry = toolRegistry;
    }

    public String chat(Long userId, String userMessage) {
        return chat(userId, null, userMessage).getReply();
    }

    public ChatResponse chat(Long userId, Long conversationId, String userMessage) {
        ChatConversation conversation = conversationHistoryService.resolveConversation(userId, conversationId, userMessage);
        conversationHistoryService.saveMessage(userId, conversation.getId(), "user", userMessage);

        String memoryId = conversationMemoryId(userId, conversation.getId());
        ProfileAdvisorService.ProfilePrompt profilePrompt = withProfileContext(userId, userMessage);
        String requestId = UUID.randomUUID().toString();

        try {
            String reply = chatClient.prompt()
                    .system(profilePrompt.systemPrompt())
                    .user(buildUserMessage(profilePrompt.volatileContext(), userMessage))
                    .tools(agentToolService)
                    .toolContext(toolContext(userId, conversation.getId(), userMessage, requestId,
                            profilePrompt.memoryContext()))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryId))
                    .call()
                    .content();

            logProfileToolInvocationState(userId, conversation.getId(), requestId, userMessage);
            conversationHistoryService.saveMessage(userId, conversation.getId(), "assistant", reply);
            profileExtractJobService.submit(userId, conversation.getId(), userMessage, reply, "chat");

            return new ChatResponse(conversation.getId(), reply);
        } finally {
            toolInvocationTracker.clear(requestId);
        }
    }

    public Flux<String> chatStream(Long userId, String userMessage) {
        return chatStream(userId, null, userMessage).content();
    }

    public ChatStreamResult chatStream(Long userId, Long conversationId, String userMessage) {
        ChatConversation conversation = conversationHistoryService.resolveConversation(userId, conversationId, userMessage);
        conversationHistoryService.saveMessage(userId, conversation.getId(), "user", userMessage);

        String memoryId = conversationMemoryId(userId, conversation.getId());
        ProfileAdvisorService.ProfilePrompt profilePrompt = withProfileContext(userId, userMessage);
        String requestId = UUID.randomUUID().toString();
        StringBuilder replyBuilder = new StringBuilder();

        Flux<String> content = chatClient.prompt()
                .system(profilePrompt.systemPrompt())
                .user(buildUserMessage(profilePrompt.volatileContext(), userMessage))
                .tools(agentToolService)
                .toolContext(toolContext(userId, conversation.getId(), userMessage, requestId,
                        profilePrompt.memoryContext()))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryId))
                .stream()
                .content()
                .doOnNext(replyBuilder::append)
                .doOnComplete(() -> {
                    String reply = replyBuilder.toString();
                    logProfileToolInvocationState(userId, conversation.getId(), requestId, userMessage);
                    conversationHistoryService.saveMessage(userId, conversation.getId(), "assistant", reply);
                    profileExtractJobService.submit(userId, conversation.getId(), userMessage, reply, "chat");
                })
                .doFinally(signalType -> toolInvocationTracker.clear(requestId));

        return new ChatStreamResult(conversation.getId(), content);
    }

    private ProfileAdvisorService.ProfilePrompt withProfileContext(Long userId, String userMessage) {
        ProfileAdvisorService.ProfilePrompt profilePrompt = profileAdvisorService.buildProfilePrompt(userId, userMessage);
        String systemPrompt = profilePrompt.systemPrompt() + """
                【可用工具使用原则：】
                当用户处于重大决策、复盘或多选项比较场景时，可以调用受控工具查询历史决策、长期语义记忆或生成决策矩阵。
                如果系统提示词中已经有【相关场景记忆】，不要为了普通对话重复调用 searchSemanticMemory；只有需要更具体历史证据时才做二次精查。
                当用户明确表达可长期复用的长期稳定偏好、价值观、情绪模式、关系影响、恐惧或边界时，必须先调用 updateUserProfile，再组织回复。
                updateUserProfile 会按置信度处理：高置信度自动写入，中置信度返回 needs_confirmation 并应在回复中询问用户确认，低置信度跳过。
                如果用户只是模糊倾诉、事实证据不足或你无法给出明确置信度，不要调用 updateUserProfile。
                不要为了普通倾诉强行调用工具。
                工具结果只是辅助，不代表用户最终意愿。
                不要基于模型猜测直接写入档案或决策记录。
                单轮对话尽量只调用最必要的工具。
                """;
        return new ProfileAdvisorService.ProfilePrompt(systemPrompt, profilePrompt.memoryContext(),
                profilePrompt.volatileContext());
    }

    private String conversationMemoryId(Long userId, Long conversationId) {
        return "user:" + userId + ":conversation:" + conversationId;
    }

    /**
     * 组装 user message：将易变（volatile）召回块前置到用户消息，稳定块已在 system prompt 中。
     */
    private String buildUserMessage(String volatileContext, String userMessage) {
        if (StringUtils.hasText(volatileContext)) {
            return volatileContext + "\n\n" + userMessage;
        }
        return userMessage;
    }

    private Map<String, Object> toolContext(
            Long userId,
            Long conversationId,
            String userMessage,
            String requestId,
            MemoryContext memoryContext) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(AgentToolContext.USER_ID, userId);
        context.put(AgentToolContext.CONVERSATION_ID, conversationId);
        context.put(AgentToolContext.MESSAGE, userMessage);
        context.put(AgentToolContext.REQUEST_ID, requestId);

        MemoryContext.RetrievalMetrics metrics = memoryContext.metrics();
        context.put(AgentToolContext.SEMANTIC_MEMORY_RETRIEVED, metrics.semanticHitCount() > 0);
        context.put(AgentToolContext.SEMANTIC_HIT_COUNT, metrics.semanticHitCount());
        if (metrics.maxSemanticScore() != null) {
            context.put(AgentToolContext.MAX_SEMANTIC_SCORE, metrics.maxSemanticScore());
        }
        context.put(AgentToolContext.SEMANTIC_QUERY, userMessage);
        context.put(AgentToolContext.VISIBLE_TOOLS, visibleTools(userMessage));
        return context;
    }

    /**
     * 按场景声明本轮允许执行的工具（可见性分层：注册≠对模型可见≠有权执行）。
     * 默认暴露读/分析类工具（来自注册表）；写画像工具仅当消息带明确长期偏好信号时才放行，
     * 减少普通倾诉场景下模型的误写。
     */
    private Set<String> visibleTools(String userMessage) {
        Set<String> tools = new LinkedHashSet<>(toolRegistry.defaultExposedNames());
        if (looksLikeProfileUpdateCandidate(userMessage)) {
            tools.add(toolRegistry.primaryWriteName());
        }
        return tools;
    }

    private void logProfileToolInvocationState(Long userId, Long conversationId, String requestId, String userMessage) {
        boolean called = toolInvocationTracker.wasCalled(requestId, "updateUserProfile");
        logger.info("Agent Tool 本轮调用状态: updateUserProfile, called: {}, userId: {}, conversationId: {}, requestId: {}, profileCandidate: {}",
                called, userId, conversationId, requestId, looksLikeProfileUpdateCandidate(userMessage));
    }

    private boolean looksLikeProfileUpdateCandidate(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return userMessage.contains("长期")
                || userMessage.contains("一直")
                || userMessage.contains("总是")
                || userMessage.contains("最看重")
                || userMessage.contains("更重要")
                || userMessage.contains("安全感")
                || userMessage.contains("离家近")
                || userMessage.contains("价值观")
                || userMessage.contains("偏好")
                || userMessage.contains("边界")
                || userMessage.contains("底线")
                || userMessage.contains("害怕")
                || userMessage.contains("恐惧");
    }

    public record ChatStreamResult(Long conversationId, Flux<String> content) {
    }
}
