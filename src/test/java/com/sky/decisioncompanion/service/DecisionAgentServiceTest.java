package com.sky.decisioncompanion.service;

import com.sky.decisioncompanion.advisor.ProfileAdvisorService;
import com.sky.decisioncompanion.common.ChatResponse;
import com.sky.decisioncompanion.model.ChatConversation;
import com.sky.decisioncompanion.service.agenttool.AgentToolContext;
import com.sky.decisioncompanion.service.agenttool.DecisionAgentToolService;
import com.sky.decisioncompanion.service.memory.MemoryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DecisionAgentServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long CONVERSATION_ID = 7L;

    private final ChatClient.Builder builder = mock(ChatClient.Builder.class);
    private final ChatMemory chatMemory = mock(ChatMemory.class);
    private final ProfileAdvisorService profileAdvisorService = mock(ProfileAdvisorService.class);
    private final ProfileExtractJobService profileExtractJobService = mock(ProfileExtractJobService.class);
    private final ConversationHistoryService conversationHistoryService = mock(ConversationHistoryService.class);
    private final DecisionAgentToolService toolService = mock(DecisionAgentToolService.class);
    private final com.sky.decisioncompanion.service.agenttool.AgentToolInvocationTracker toolInvocationTracker =
            mock(com.sky.decisioncompanion.service.agenttool.AgentToolInvocationTracker.class);
    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
    private final ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);

    private DecisionAgentService service;

    @BeforeEach
    void setUp() {
        when(builder.defaultAdvisors(any(Advisor.class))).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("我们先一起拆开看。");
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("我们", "看看"));

        ChatConversation conversation = new ChatConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        when(conversationHistoryService.resolveConversation(USER_ID, null, "我在纠结 offer"))
                .thenReturn(conversation);
        when(profileAdvisorService.buildProfilePrompt(USER_ID, "我在纠结 offer"))
                .thenReturn(new ProfileAdvisorService.ProfilePrompt(
                        "稳定画像系统上下文",
                        memoryContext(2, 0.82),
                        "易变召回块"));

        service = new DecisionAgentService(
                builder,
                chatMemory,
                profileAdvisorService,
                profileExtractJobService,
                conversationHistoryService,
                toolService,
                toolInvocationTracker,
                new com.sky.decisioncompanion.service.agenttool.AgentToolRegistry());
    }

    @Test
    void chatRegistersControlledToolsWithBackendContext() {
        ChatResponse response = service.chat(USER_ID, null, "我在纠结 offer");

        assertThat(response.getConversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(response.getReply()).isEqualTo("我们先一起拆开看。");
        verify(requestSpec).tools(toolService);
        assertToolContextWasPassed();
    }

    @Test
    void chatPlacesProfileToolRulesInSystemRoleAndPrependsVolatileContextToUserMessage() {
        service.chat(USER_ID, null, "我在纠结 offer");

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        String systemPrompt = systemCaptor.getValue();

        // 稳定块（system）与易变块（user）分层组装
        assertThat(systemPrompt).contains("稳定画像系统上下文");
        assertThat(systemPrompt).contains("必须先调用 updateUserProfile");
        assertThat(systemPrompt).contains("长期稳定偏好");
        assertThat(systemPrompt).doesNotContain("易变召回块");
        // 易变块前置到 user message，用户原始消息保持其后
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userCaptor.capture());
        assertThat(userCaptor.getValue()).contains("易变召回块").contains("我在纠结 offer");
        assertThat(systemPrompt).doesNotContain("用户消息：我在纠结 offer");
    }

    @Test
    void chatRunsPostChatProfileExtractionAsComplement() {
        service.chat(USER_ID, null, "我在纠结 offer");

        verify(profileExtractJobService).submit(USER_ID, CONVERSATION_ID, "我在纠结 offer", "我们先一起拆开看。", "chat");
    }

    @Test
    void chatTracksWhetherUpdateUserProfileWasCalledForRequestId() {
        service.chat(USER_ID, null, "我在纠结 offer");

        String requestId = assertToolContextWasPassed();
        verify(toolInvocationTracker).wasCalled(requestId, "updateUserProfile");
        verify(toolInvocationTracker).clear(requestId);
    }

    @Test
    void chatPassesSemanticRecallStateToTools() {
        service.chat(USER_ID, null, "我在纠结 offer");

        Map<String, Object> context = assertToolContextMapWasPassed();
        assertThat(context)
                .containsEntry(AgentToolContext.SEMANTIC_MEMORY_RETRIEVED, true)
                .containsEntry(AgentToolContext.SEMANTIC_HIT_COUNT, 2)
                .containsEntry(AgentToolContext.SEMANTIC_QUERY, "我在纠结 offer");
        assertThat(context.get(AgentToolContext.MAX_SEMANTIC_SCORE)).isEqualTo(0.82);
    }

    @Test
    void chatStreamRegistersControlledToolsWithBackendContext() {
        DecisionAgentService.ChatStreamResult result = service.chatStream(USER_ID, null, "我在纠结 offer");

        assertThat(result.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(result.content().collectList().block()).containsExactly("我们", "看看");
        verify(requestSpec).tools(toolService);
        assertToolContextWasPassed();
    }

    @Test
    void chatStreamRunsPostChatProfileExtractionAsComplement() {
        DecisionAgentService.ChatStreamResult result = service.chatStream(USER_ID, null, "我在纠结 offer");

        result.content().collectList().block();

        verify(profileExtractJobService).submit(USER_ID, CONVERSATION_ID, "我在纠结 offer", "我们看看", "chat");
    }

    @SuppressWarnings("unchecked")
    private String assertToolContextWasPassed() {
        Map<String, Object> context = assertToolContextMapWasPassed();
        return (String) context.get(AgentToolContext.REQUEST_ID);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> assertToolContextMapWasPassed() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(requestSpec).toolContext(captor.capture());
        Map<String, Object> context = captor.getValue();
        assertThat(context)
                .containsEntry(AgentToolContext.USER_ID, USER_ID)
                .containsEntry(AgentToolContext.CONVERSATION_ID, CONVERSATION_ID)
                .containsEntry(AgentToolContext.MESSAGE, "我在纠结 offer");
        assertThat(context.get(AgentToolContext.REQUEST_ID)).isInstanceOf(String.class);
        assertThat((String) context.get(AgentToolContext.REQUEST_ID)).isNotBlank();
        return context;
    }

    private MemoryContext memoryContext(int semanticHitCount, Double maxSemanticScore) {
        return new MemoryContext(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new MemoryContext.RetrievalMetrics(0, 0, 0, 0, 0,
                        semanticHitCount, maxSemanticScore, true, false),
                "用户价值观上下文");
    }
}
