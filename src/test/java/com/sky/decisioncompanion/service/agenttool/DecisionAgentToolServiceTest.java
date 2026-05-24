package com.sky.decisioncompanion.service.agenttool;

import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionAgentToolServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long CONVERSATION_ID = 9L;

    @Mock
    private ProfileDecisionRepository decisionRepository;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private AgentToolCallLogService logService;

    private DecisionAgentToolService service;

    @BeforeEach
    void setUp() {
        service = new DecisionAgentToolService(decisionRepository, vectorStore, logService);
    }

    @Test
    void searchDecisionHistoryOnlyReturnsCurrentUsersMatchingDecisions() {
        ProfileDecision matching = decision(USER_ID, "外地 offer", "接受杭州 offer", "成长空间更大");
        ProfileDecision unrelated = decision(USER_ID, "租房选择", "住公司附近", "通勤更短");
        ProfileDecision anotherUser = decision(2L, "外地 offer", "留在本地", "陪家人");
        when(decisionRepository.selectList(any())).thenReturn(List.of(matching, unrelated, anotherUser));

        DecisionAgentToolService.DecisionHistoryToolResult result = service.searchDecisionHistory(
                "offer 城市 成长",
                5,
                toolContext());

        assertThat(result.available()).isTrue();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).topic()).isEqualTo("外地 offer");
        assertThat(result.items().get(0).choice()).isEqualTo("接受杭州 offer");
        verify(logService).recordSuccess(eq(USER_ID), eq(CONVERSATION_ID), eq("searchDecisionHistory"),
                contains("offer 城市 成长"), contains("外地 offer"), anyLong());
    }

    @Test
    void searchSemanticMemoryReturnsUnavailableWhenVectorStoreIsMissing() {
        DecisionAgentToolService serviceWithoutVectorStore =
                new DecisionAgentToolService(decisionRepository, null, logService);

        DecisionAgentToolService.SemanticMemoryToolResult result = serviceWithoutVectorStore.searchSemanticMemory(
                "我怕离家太远",
                5,
                toolContext());

        assertThat(result.available()).isFalse();
        assertThat(result.memories()).isEmpty();
        assertThat(result.message()).contains("向量存储");
        verify(logService).recordSkipped(eq(USER_ID), eq(CONVERSATION_ID), eq("searchSemanticMemory"),
                contains("我怕离家太远"), contains("向量存储"), anyLong());
    }

    @Test
    void searchSemanticMemoryLogsFailureAndReturnsUnavailableWhenVectorStoreFails() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("chroma down"));

        DecisionAgentToolService.SemanticMemoryToolResult result = service.searchSemanticMemory(
                "我怕离家太远",
                5,
                toolContext());

        assertThat(result.available()).isFalse();
        assertThat(result.memories()).isEmpty();
        assertThat(result.message()).contains("暂时无法");
        verify(logService).recordFailure(eq(USER_ID), eq(CONVERSATION_ID), eq("searchSemanticMemory"),
                contains("我怕离家太远"), contains("chroma down"), anyLong());
    }

    @Test
    void searchSemanticMemoryReturnsCurrentUsersVectorMemories() {
        Document document = Document.builder()
                .text("用户多次提到不想离父母太远")
                .metadata("type", "conversation_analysis")
                .score(0.78)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        DecisionAgentToolService.SemanticMemoryToolResult result = service.searchSemanticMemory(
                "我怕离家太远",
                5,
                toolContext());

        assertThat(result.available()).isTrue();
        assertThat(result.memories()).hasSize(1);
        assertThat(result.memories().get(0).content()).contains("父母");
        assertThat(result.memories().get(0).type()).isEqualTo("conversation_analysis");
        assertThat(result.memories().get(0).score()).isEqualTo(0.78);
    }

    @Test
    void generateDecisionMatrixBuildsDeterministicReadOnlyAnalysis() {
        DecisionAgentToolService.DecisionMatrixToolResult result = service.generateDecisionMatrix(
                "是否接受外地 offer",
                List.of("接受外地 offer", "留在本地继续找"),
                List.of("成长空间", "家庭距离", "稳定性"),
                toolContext());

        assertThat(result.available()).isTrue();
        assertThat(result.matrix()).hasSize(2);
        assertThat(result.matrix().get(0).scores())
                .containsKeys("成长空间", "家庭距离", "稳定性");
        assertThat(result.matrix().get(0).summary()).contains("接受外地 offer");
        verify(logService).recordSuccess(eq(USER_ID), eq(CONVERSATION_ID), eq("generateDecisionMatrix"),
                contains("是否接受外地 offer"), contains("接受外地 offer"), anyLong());
    }

    private ToolContext toolContext() {
        return new ToolContext(Map.of(
                AgentToolContext.USER_ID, USER_ID,
                AgentToolContext.CONVERSATION_ID, CONVERSATION_ID,
                AgentToolContext.MESSAGE, "我正在考虑是否接受外地 offer",
                AgentToolContext.REQUEST_ID, "req-1"));
    }

    private ProfileDecision decision(Long userId, String topic, String choice, String reason) {
        ProfileDecision decision = new ProfileDecision();
        decision.setUserId(userId);
        decision.setTopic(topic);
        decision.setChoice(choice);
        decision.setReason(reason);
        decision.setSatisfaction(3);
        return decision;
    }
}
