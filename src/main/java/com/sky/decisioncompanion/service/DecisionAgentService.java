package com.sky.decisioncompanion.service;

import com.sky.decisioncompanion.advisor.ProfileAdvisorService;
import com.sky.decisioncompanion.common.ChatResponse;
import com.sky.decisioncompanion.model.ChatConversation;
import com.sky.decisioncompanion.service.agenttool.AgentToolContext;
import com.sky.decisioncompanion.service.agenttool.DecisionAgentToolService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

@Service
public class DecisionAgentService {

    private final ChatClient chatClient;
    private final ProfileAdvisorService profileAdvisorService;
    private final ProfileExtractService profileExtractService;
    private final ConversationHistoryService conversationHistoryService;
    private final DecisionAgentToolService agentToolService;

    public DecisionAgentService(
            ChatClient.Builder builder,
            ChatMemory chatMemory,
            ProfileAdvisorService profileAdvisorService,
            ProfileExtractService profileExtractService,
            ConversationHistoryService conversationHistoryService,
            DecisionAgentToolService agentToolService) {
        this.chatClient = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        this.profileAdvisorService = profileAdvisorService;
        this.profileExtractService = profileExtractService;
        this.conversationHistoryService = conversationHistoryService;
        this.agentToolService = agentToolService;
    }

    public String chat(Long userId, String userMessage) {
        return chat(userId, null, userMessage).getReply();
    }

    public ChatResponse chat(Long userId, Long conversationId, String userMessage) {
        ChatConversation conversation = conversationHistoryService.resolveConversation(userId, conversationId, userMessage);
        conversationHistoryService.saveMessage(userId, conversation.getId(), "user", userMessage);

        String memoryId = conversationMemoryId(userId, conversation.getId());
        String fullMessage = withProfileContext(userId, userMessage);

        String reply = chatClient.prompt()
                .user(fullMessage)
                .tools(agentToolService)
                .toolContext(toolContext(userId, conversation.getId(), userMessage))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryId))
                .call()
                .content();

        conversationHistoryService.saveMessage(userId, conversation.getId(), "assistant", reply);
        profileExtractService.extractAndSave(userId, userMessage, reply);

        return new ChatResponse(conversation.getId(), reply);
    }

    public Flux<String> chatStream(Long userId, String userMessage) {
        return chatStream(userId, null, userMessage).content();
    }

    public ChatStreamResult chatStream(Long userId, Long conversationId, String userMessage) {
        ChatConversation conversation = conversationHistoryService.resolveConversation(userId, conversationId, userMessage);
        conversationHistoryService.saveMessage(userId, conversation.getId(), "user", userMessage);

        String memoryId = conversationMemoryId(userId, conversation.getId());
        String fullMessage = withProfileContext(userId, userMessage);
        StringBuilder replyBuilder = new StringBuilder();

        Flux<String> content = chatClient.prompt()
                .user(fullMessage)
                .tools(agentToolService)
                .toolContext(toolContext(userId, conversation.getId(), userMessage))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryId))
                .stream()
                .content()
                .doOnNext(replyBuilder::append)
                .doOnComplete(() -> {
                    String reply = replyBuilder.toString();
                    conversationHistoryService.saveMessage(userId, conversation.getId(), "assistant", reply);
                    profileExtractService.extractAndSave(userId, userMessage, reply);
                });

        return new ChatStreamResult(conversation.getId(), content);
    }

    private String withProfileContext(Long userId, String userMessage) {
        String systemPrompt = profileAdvisorService.buildSystemPrompt(userId, userMessage);
        return systemPrompt + """

                【可用工具使用原则：】
                当用户处于重大决策、复盘或多选项比较场景时，可以调用受控工具查询历史决策、长期语义记忆或生成决策矩阵。
                不要为了普通倾诉强行调用工具。
                工具结果只是辅助，不代表用户最终意愿。
                不要基于工具结果直接写入档案或决策记录。
                单轮对话尽量只调用最必要的工具。

                用户消息：""" + userMessage;
    }

    private String conversationMemoryId(Long userId, Long conversationId) {
        return "user:" + userId + ":conversation:" + conversationId;
    }

    private Map<String, Object> toolContext(Long userId, Long conversationId, String userMessage) {
        return Map.of(
                AgentToolContext.USER_ID, userId,
                AgentToolContext.CONVERSATION_ID, conversationId,
                AgentToolContext.MESSAGE, userMessage,
                AgentToolContext.REQUEST_ID, UUID.randomUUID().toString());
    }

    public record ChatStreamResult(Long conversationId, Flux<String> content) {
    }
}
