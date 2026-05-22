package com.sky.decisioncompanion.service;

import com.sky.decisioncompanion.advisor.ProfileAdvisorService;
import com.sky.decisioncompanion.common.ChatResponse;
import com.sky.decisioncompanion.model.ChatConversation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class DecisionAgentService {

    private final ChatClient chatClient;
    private final ProfileAdvisorService profileAdvisorService;
    private final ProfileExtractService profileExtractService;
    private final ConversationHistoryService conversationHistoryService;

    public DecisionAgentService(
            ChatClient.Builder builder,
            ChatMemory chatMemory,
            ProfileAdvisorService profileAdvisorService,
            ProfileExtractService profileExtractService,
            ConversationHistoryService conversationHistoryService) {
        this.chatClient = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        this.profileAdvisorService = profileAdvisorService;
        this.profileExtractService = profileExtractService;
        this.conversationHistoryService = conversationHistoryService;
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
        return systemPrompt + "\n\n用户消息：" + userMessage;
    }

    private String conversationMemoryId(Long userId, Long conversationId) {
        return "user:" + userId + ":conversation:" + conversationId;
    }

    public record ChatStreamResult(Long conversationId, Flux<String> content) {
    }
}
