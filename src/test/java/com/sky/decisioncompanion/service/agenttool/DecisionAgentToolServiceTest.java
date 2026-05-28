package com.sky.decisioncompanion.service.agenttool;

import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.model.ProfileValues;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import com.sky.decisioncompanion.service.memory.MemoryContext;
import com.sky.decisioncompanion.service.memory.MemoryRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionAgentToolServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long CONVERSATION_ID = 9L;

    @Mock
    private ProfileDecisionRepository decisionRepository;

    @Mock
    private ProfileValuesRepository valuesRepository;

    @Mock
    private ProfileEmotionRepository emotionRepository;

    @Mock
    private ProfileRelationshipRepository relationshipRepository;

    @Mock
    private ProfileFearRepository fearRepository;

    @Mock
    private MemoryRetrievalService memoryRetrievalService;

    @Mock
    private AgentToolCallLogService logService;

    @Mock
    private AgentToolInvocationTracker toolInvocationTracker;

    private DecisionAgentToolService service;

    @BeforeEach
    void setUp() {
        service = new DecisionAgentToolService(
                decisionRepository,
                valuesRepository,
                emotionRepository,
                relationshipRepository,
                fearRepository,
                memoryRetrievalService,
                logService,
                toolInvocationTracker);
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
                argThat(summary -> summary.contains("query=offer 城市 成长") && summary.contains("limit=5")),
                contains("外地 offer"), anyLong());
    }

    @Test
    void searchSemanticMemoryReturnsUnavailableWhenVectorStoreIsMissing() {
        when(memoryRetrievalService.searchSemanticMemories(USER_ID, "我怕离家太远", 5))
                .thenReturn(new MemoryRetrievalService.SemanticSearchResult(List.of(), null, false, true));

        DecisionAgentToolService.SemanticMemoryToolResult result = service.searchSemanticMemory(
                "我怕离家太远",
                5,
                toolContext());

        assertThat(result.available()).isFalse();
        assertThat(result.memories()).isEmpty();
        assertThat(result.message()).contains("向量存储");
        verify(logService).recordSkipped(eq(USER_ID), eq(CONVERSATION_ID), eq("searchSemanticMemory"),
                argThat(summary -> summary.contains("query=我怕离家太远") && summary.contains("topK=5")),
                contains("向量存储"), anyLong());
    }

    @Test
    void searchSemanticMemoryLogsFailureAndReturnsUnavailableWhenVectorStoreFails() {
        when(memoryRetrievalService.searchSemanticMemories(USER_ID, "我怕离家太远", 5))
                .thenReturn(new MemoryRetrievalService.SemanticSearchResult(List.of(), null, true, true));

        DecisionAgentToolService.SemanticMemoryToolResult result = service.searchSemanticMemory(
                "我怕离家太远",
                5,
                toolContext());

        assertThat(result.available()).isFalse();
        assertThat(result.memories()).isEmpty();
        assertThat(result.message()).contains("暂时无法");
        verify(logService).recordFailure(eq(USER_ID), eq(CONVERSATION_ID), eq("searchSemanticMemory"),
                argThat(summary -> summary.contains("query=我怕离家太远") && summary.contains("topK=5")),
                contains("查询长期语义记忆失败"),
                anyLong());
    }

    @Test
    void searchSemanticMemoryReturnsCurrentUsersVectorMemories() {
        when(memoryRetrievalService.searchSemanticMemories(USER_ID, "我怕离家太远", 5))
                .thenReturn(new MemoryRetrievalService.SemanticSearchResult(List.of(
                        new MemoryContext.SemanticMemory(
                                "用户多次提到不想离父母太远",
                                "conversation_scene",
                                1,
                                0.78)), 0.78, true, false));

        DecisionAgentToolService.SemanticMemoryToolResult result = service.searchSemanticMemory(
                "我怕离家太远",
                5,
                toolContext());

        assertThat(result.available()).isTrue();
        assertThat(result.memories()).hasSize(1);
        assertThat(result.memories().get(0).content()).contains("父母");
        assertThat(result.memories().get(0).type()).isEqualTo("conversation_scene");
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
                argThat(summary -> summary.contains("topic=是否接受外地 offer")
                        && summary.contains("options=[接受外地 offer, 留在本地继续找]")
                        && summary.contains("dimensions=[成长空间, 家庭距离, 稳定性]")),
                contains("接受外地 offer"), anyLong());
    }

    @Test
    void updateUserProfileUpdatesCurrentUsersValueCorrection() {
        ProfileValues currentUserValue = new ProfileValues();
        currentUserValue.setId(10L);
        currentUserValue.setUserId(USER_ID);
        currentUserValue.setItem("城市偏好");
        currentUserValue.setPreference("更向往大城市机会");
        currentUserValue.setConfidence(new BigDecimal("0.70"));

        ProfileValues anotherUserValue = new ProfileValues();
        anotherUserValue.setId(20L);
        anotherUserValue.setUserId(2L);
        anotherUserValue.setItem("城市偏好");
        anotherUserValue.setPreference("留在本地");
        anotherUserValue.setConfidence(new BigDecimal("0.90"));

        when(valuesRepository.selectList(any())).thenReturn(List.of(anotherUserValue, currentUserValue));

        DecisionAgentToolService.UpdateUserProfileToolResult result = service.updateUserProfile(
                "value",
                "城市偏好",
                "其实我更偏向离家近的城市",
                null,
                0.95,
                List.of("其实我更偏向离家近的城市"),
                correctedToolContext());

        assertThat(result.updated()).isTrue();
        assertThat(result.profileType()).isEqualTo("value");
        assertThat(result.subject()).isEqualTo("城市偏好");
        assertThat(currentUserValue.getPreference()).isEqualTo("其实我更偏向离家近的城市");
        assertThat(currentUserValue.getConfidence()).isEqualByComparingTo("0.95");
        assertThat(currentUserValue.getEvidence()).contains("离家近");
        verify(valuesRepository).updateById(currentUserValue);
        verify(logService).recordSuccess(eq(USER_ID), eq(CONVERSATION_ID), eq("updateUserProfile"),
                argThat(summary -> summary.contains("profileType=value")
                        && summary.contains("subject=城市偏好")),
                contains("written value:城市偏好"), anyLong());
    }

    @Test
    void updateUserProfileWritesHighConfidenceProfileWhenAgentTriggers() {
        when(valuesRepository.selectList(any())).thenReturn(List.of());

        DecisionAgentToolService.UpdateUserProfileToolResult result = service.updateUserProfile(
                "value",
                "城市偏好",
                "更偏向离家近的城市",
                null,
                0.95,
                List.of("我怕离家太远"),
                toolContext());

        assertThat(result.updated()).isTrue();
        assertThat(result.action()).isEqualTo("written");

        var valueCaptor = org.mockito.ArgumentCaptor.forClass(ProfileValues.class);
        verify(valuesRepository).insert(valueCaptor.capture());
        ProfileValues saved = valueCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getItem()).isEqualTo("城市偏好");
        assertThat(saved.getPreference()).isEqualTo("更偏向离家近的城市");
        assertThat(saved.getEvidence()).contains("离家太远");
        verify(logService).recordSuccess(eq(USER_ID), eq(CONVERSATION_ID), eq("updateUserProfile"),
                contains("profileType=value"),
                contains("written value:城市偏好"), anyLong());
        verify(toolInvocationTracker).markCalled("req-1", "updateUserProfile");
    }

    @Test
    void updateUserProfileAsksConfirmationForMediumConfidenceProfile() {
        DecisionAgentToolService.UpdateUserProfileToolResult result = service.updateUserProfile(
                "value",
                "城市偏好",
                "更偏向离家近的城市",
                null,
                0.70,
                List.of("我怕离家太远"),
                toolContext());

        assertThat(result.updated()).isFalse();
        assertThat(result.action()).isEqualTo("needs_confirmation");
        assertThat(result.message()).contains("确认");
        verify(valuesRepository, never()).insert(any(ProfileValues.class));
        verify(valuesRepository, never()).updateById(any(ProfileValues.class));
        verify(logService).recordSkipped(eq(USER_ID), eq(CONVERSATION_ID), eq("updateUserProfile"),
                contains("profileType=value"),
                contains("needs_confirmation"), anyLong());
    }

    @Test
    void updateUserProfileSkipsLowConfidenceProfile() {
        DecisionAgentToolService.UpdateUserProfileToolResult result = service.updateUserProfile(
                "value",
                "城市偏好",
                "更偏向离家近的城市",
                null,
                0.40,
                List.of("我怕离家太远"),
                toolContext());

        assertThat(result.updated()).isFalse();
        assertThat(result.action()).isEqualTo("skipped");
        assertThat(result.message()).contains("置信度");
        verify(valuesRepository, never()).insert(any(ProfileValues.class));
        verify(valuesRepository, never()).updateById(any(ProfileValues.class));
        verify(logService).recordSkipped(eq(USER_ID), eq(CONVERSATION_ID), eq("updateUserProfile"),
                contains("profileType=value"),
                contains("confidence too low"), anyLong());
    }

    private ToolContext toolContext() {
        return new ToolContext(Map.of(
                AgentToolContext.USER_ID, USER_ID,
                AgentToolContext.CONVERSATION_ID, CONVERSATION_ID,
                AgentToolContext.MESSAGE, "我正在考虑是否接受外地 offer",
                AgentToolContext.REQUEST_ID, "req-1"));
    }

    private ToolContext correctedToolContext() {
        return new ToolContext(Map.of(
                AgentToolContext.USER_ID, USER_ID,
                AgentToolContext.CONVERSATION_ID, CONVERSATION_ID,
                AgentToolContext.MESSAGE, "你刚才理解不对，城市偏好请更新成其实我更偏向离家近的城市",
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
