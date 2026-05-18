package com.sky.decisioncompanion.service;

import com.sky.decisioncompanion.advisor.ProfileAdvisorService;
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

    public DecisionAgentService(
            ChatClient.Builder builder,
            ChatMemory chatMemory,
            ProfileAdvisorService profileAdvisorService,
            ProfileExtractService profileExtractService) {
        this.chatClient = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        this.profileAdvisorService = profileAdvisorService;
        this.profileExtractService = profileExtractService;
    }

    public String chat(Long userId, String userMessage) {
        String conversationId = conversationId(userId);
        String fullMessage = withProfileContext(userId, userMessage);

        String reply = chatClient.prompt()
                .user(fullMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        profileExtractService.extractAndSave(userId, userMessage, reply);

        return reply;
    }

    public Flux<String> chatStream(Long userId, String userMessage) {
        String conversationId = conversationId(userId);
        String fullMessage = withProfileContext(userId, userMessage);
        StringBuilder replyBuilder = new StringBuilder();

        return chatClient.prompt()
                .user(fullMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .doOnNext(replyBuilder::append)
                .doOnComplete(() -> profileExtractService.extractAndSave(
                        userId,
                        userMessage,
                        replyBuilder.toString()));
    }

    private String withProfileContext(Long userId, String userMessage) {
        String systemPrompt = profileAdvisorService.buildSystemPrompt(userId, userMessage);
        return systemPrompt + "\n\n用户消息：" + userMessage;
    }

    private String conversationId(Long userId) {
        return "user:" + userId;
    }
}
