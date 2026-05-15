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

    public String chat(String sessionId, String userMessage) {
        String reply = chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        profileExtractService.extractAndSave(null, sessionId, userMessage, reply);

        return reply;
    }

    public String chatWithProfile(Long userId, String sessionId, String userMessage) {
        String systemPrompt = profileAdvisorService.buildSystemPrompt(userId, userMessage);
        String fullMessage = systemPrompt + "\n\n用户消息：" + userMessage;

        String reply = chatClient.prompt()
                .user(fullMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        profileExtractService.extractAndSave(userId, sessionId, userMessage, reply);

        return reply;
    }

    public Flux<String> chatStream(String sessionId, String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content();
    }
}
