package com.sky.decisioncompanion.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.sky.decisioncompanion.common.ChatRequest;
import com.sky.decisioncompanion.common.ChatResponse;
import com.sky.decisioncompanion.model.ChatConversation;
import com.sky.decisioncompanion.service.ConversationHistoryService;
import com.sky.decisioncompanion.service.DecisionAgentService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentControllerTest {

    private static final Long USER_ID = 1L;

    private final DecisionAgentService agentService = mock(DecisionAgentService.class);
    private final ConversationHistoryService historyService = mock(ConversationHistoryService.class);
    private final AgentController controller = new AgentController(agentService, historyService);

    @Test
    void chatPassesConversationIdAndReturnsIt() {
        ChatRequest request = new ChatRequest();
        request.setConversationId(7L);
        request.setMessage("继续聊考研");
        ChatResponse response = new ChatResponse(7L, "我们接着看你的顾虑。");

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
            when(agentService.chat(USER_ID, 7L, "继续聊考研")).thenReturn(response);

            ChatResponse data = controller.chat(request).getBody().getData();

            assertThat(data.getConversationId()).isEqualTo(7L);
            assertThat(data.getReply()).isEqualTo("我们接着看你的顾虑。");
        }
    }

    @Test
    void listConversationsUsesCurrentUserAndLimit() {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(3L);
        conversation.setUserId(USER_ID);
        conversation.setTitle("是否要换城市");
        conversation.setMessageCount(2);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
            when(historyService.listConversations(USER_ID, 10)).thenReturn(List.of(conversation));

            List<ChatConversation> data = controller.listConversations(10).getBody().getData();

            assertThat(data).containsExactly(conversation);
            verify(historyService).listConversations(USER_ID, 10);
        }
    }

    @Test
    void chatStreamStartsWithConversationEvent() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
            when(agentService.chatStream(USER_ID, null, "我想换城市"))
                    .thenReturn(new DecisionAgentService.ChatStreamResult(11L, Flux.just("听", "起来")));

            List<ServerSentEvent<String>> events = controller.chatStream("我想换城市", null)
                    .collectList()
                    .block();

            assertThat(events).hasSize(3);
            assertThat(events.get(0).event()).isEqualTo("conversation");
            assertThat(events.get(0).data()).isEqualTo("{\"conversationId\":11}");
            assertThat(events.get(1).event()).isEqualTo("message");
            assertThat(events.get(1).data()).isEqualTo("听");
        }
    }
}
