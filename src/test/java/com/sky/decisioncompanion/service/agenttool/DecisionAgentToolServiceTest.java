package com.sky.decisioncompanion.service.agenttool;

import com.sky.decisioncompanion.config.MemoryRetrievalProperties;
import com.sky.decisioncompanion.model.ProfileMemoryCandidate;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import com.sky.decisioncompanion.service.memory.DecisionRecallService;
import com.sky.decisioncompanion.service.memory.MemoryContext;
import com.sky.decisioncompanion.service.memory.MemoryRetrievalService;
import com.sky.decisioncompanion.service.memory.ProfileSceneMemoryService;
import com.sky.decisioncompanion.service.profile.ProfileMemoryGovernanceService;
import com.sky.decisioncompanion.service.profile.ProfileMemoryGovernanceService.ConfirmedMemoryCommand;
import com.sky.decisioncompanion.service.profile.ProfileMemoryGovernanceService.MemoryCandidateCommand;
import com.sky.decisioncompanion.service.profile.PostureGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class DecisionAgentToolServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long CONVERSATION_ID = 9L;

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
    private ProfileSceneMemoryService profileSceneMemoryService;

    @Mock
    private ProfileMemoryGovernanceService profileMemoryGovernanceService;

    @Mock
    private DecisionRecallService decisionRecallService;

    @Mock
    private AgentToolCallLogService logService;

    @Mock
    private AgentToolInvocationTracker toolInvocationTracker;

    @Mock
    private AgentToolEffectLedger effectLedger;

    @Mock
    private PostureGateService postureGateService;

    @Mock
    private com.sky.decisioncompanion.service.ConversationHistoryService conversationHistoryService;

    private MemoryRetrievalProperties properties;
    private DecisionAgentToolService service;

    @BeforeEach
    void setUp() {
        properties = new MemoryRetrievalProperties();
        service = new DecisionAgentToolService(
                valuesRepository,
                emotionRepository,
                relationshipRepository,
                fearRepository,
                memoryRetrievalService,
                profileSceneMemoryService,
                profileMemoryGovernanceService,
                decisionRecallService,
                properties,
                logService,
                toolInvocationTracker,
                postureGateService,
                conversationHistoryService,
                new AgentToolRegistry(),
                new AgentToolEffectExecutor(effectLedger));
        // 两段式审计：started 阶段固定返回 auditId=1
        when(logService.recordStarted(any(), any(), anyString(), anyString()))
                .thenReturn(new AgentToolCallLogService.StartedAudit(1L));
    }

    @Test
    void searchDecisionHistoryOnlyReturnsCurrentUsersMatchingDecisions() {
        when(decisionRecallService.recall(USER_ID, "offer 城市 成长", 5))
                .thenReturn(decisionResult(decision("外地 offer", "接受杭州 offer", "成长空间更大")));

        DecisionAgentToolService.DecisionHistoryToolResult result = service.searchDecisionHistory(
                "offer 城市 成长",
                5,
                toolContext());

        assertThat(result.available()).isTrue();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).topic()).isEqualTo("外地 offer");
        assertThat(result.items().get(0).choice()).isEqualTo("接受杭州 offer");
        verify(decisionRecallService).recall(USER_ID, "offer 城市 成长", 5);
        verify(logService).recordSuccess(eq(USER_ID), eq(CONVERSATION_ID), eq("searchDecisionHistory"),
                argThat(summary -> summary.contains("query=offer 城市 成长") && summary.contains("limit=5")),
                contains("外地 offer"), anyLong());
    }

    @Test
    void searchDecisionHistoryClipsLimitUsingSharedConfiguration() {
        when(decisionRecallService.recall(USER_ID, "offer", 5)).thenReturn(decisionResult());

        service.searchDecisionHistory("offer", 99, toolContext());

        verify(decisionRecallService).recall(USER_ID, "offer", 5);
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
    void searchSemanticMemorySkipsDuplicateLookupWhenAutomaticRecallAlreadyHitSameQuery() {
        DecisionAgentToolService.SemanticMemoryToolResult result = service.searchSemanticMemory(
                "我正在考虑是否接受外地 offer",
                5,
                toolContextWithSemanticRecall(2, "我正在考虑是否接受外地 offer"));

        assertThat(result.available()).isFalse();
        assertThat(result.message()).contains("本轮已召回");
        assertThat(result.memories()).isEmpty();
        verify(memoryRetrievalService, never()).searchSemanticMemories(anyLong(), anyString(), anyInt());
        verify(logService).recordSkipped(eq(USER_ID), eq(CONVERSATION_ID), eq("searchSemanticMemory"),
                contains("query=我正在考虑是否接受外地 offer"),
                contains("duplicate semantic recall"), anyLong());
    }

    @Test
    void searchSemanticMemorySkipsHighConfidenceSemanticEquivalentQueryAfterAutomaticRecall() {
        DecisionAgentToolService.SemanticMemoryToolResult result = service.searchSemanticMemory(
                "外地 offer 要不要接受",
                5,
                toolContextWithSemanticRecall(2, "我正在考虑是否接受外地 offer"));

        assertThat(result.available()).isFalse();
        assertThat(result.message()).contains("本轮已召回");
        assertThat(result.memories()).isEmpty();
        verify(memoryRetrievalService, never()).searchSemanticMemories(anyLong(), anyString(), anyInt());
        verify(logService).recordSkipped(eq(USER_ID), eq(CONVERSATION_ID), eq("searchSemanticMemory"),
                contains("query=外地 offer 要不要接受"),
                contains("semantic-equivalent duplicate recall"), anyLong());
    }

    @Test
    void searchSemanticMemoryAllowsRefinedQueryAfterAutomaticRecall() {
        when(memoryRetrievalService.searchSemanticMemories(USER_ID, "过往因为离家距离拒绝 offer 的经历", 5))
                .thenReturn(new MemoryRetrievalService.SemanticSearchResult(List.of(), null, true, false));

        DecisionAgentToolService.SemanticMemoryToolResult result = service.searchSemanticMemory(
                "过往因为离家距离拒绝 offer 的经历",
                5,
                toolContextWithSemanticRecall(2, "我正在考虑是否接受外地 offer"));

        assertThat(result.available()).isTrue();
        verify(memoryRetrievalService).searchSemanticMemories(USER_ID, "过往因为离家距离拒绝 offer 的经历", 5);
    }

    @Test
    void searchSemanticMemoryAllowsNegationDirectionChangeAfterAutomaticRecall() {
        when(memoryRetrievalService.searchSemanticMemories(USER_ID, "不接受外地 offer", 5))
                .thenReturn(new MemoryRetrievalService.SemanticSearchResult(List.of(), null, true, false));

        DecisionAgentToolService.SemanticMemoryToolResult result = service.searchSemanticMemory(
                "不接受外地 offer",
                5,
                toolContextWithSemanticRecall(2, "接受外地 offer"));

        assertThat(result.available()).isTrue();
        verify(memoryRetrievalService).searchSemanticMemories(USER_ID, "不接受外地 offer", 5);
    }

    @Test
    void searchSemanticMemoryAllowsEquivalentQueryWhenAutomaticRecallHadNoHits() {
        when(memoryRetrievalService.searchSemanticMemories(USER_ID, "外地 offer 要不要接受", 5))
                .thenReturn(new MemoryRetrievalService.SemanticSearchResult(List.of(), null, true, false));

        DecisionAgentToolService.SemanticMemoryToolResult result = service.searchSemanticMemory(
                "外地 offer 要不要接受",
                5,
                toolContextWithSemanticRecall(0, "我正在考虑是否接受外地 offer"));

        assertThat(result.available()).isTrue();
        verify(memoryRetrievalService).searchSemanticMemories(USER_ID, "外地 offer 要不要接受", 5);
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
    void updateUserProfileWritesConfirmedMemoryWhenConfidenceHigh() {
        when(conversationHistoryService.isOwnedConversation(USER_ID, CONVERSATION_ID)).thenReturn(true);
        when(effectLedger.acquire(anyLong(), any(), anyString(), eq("updateUserProfile"), anyString()))
                .thenReturn(AgentToolEffectLedger.Acquisition.reserved());
        when(profileMemoryGovernanceService.writeConfirmedMemory(any(ConfirmedMemoryCommand.class)))
                .thenReturn(new ProfileMemoryGovernanceService.GovernanceResult(
                        true,
                        "confirm",
                        "value",
                        10L,
                        null,
                        "画像记忆已写入"));

        DecisionAgentToolService.UpdateUserProfileToolResult result = service.updateUserProfile(
                "value",
                " 城市偏好 ",
                " 其实我更偏向离家近的城市 ",
                "  长期规划 ",
                0.95,
                List.of("其实我更偏向离家近的城市", "我怕离家太远", " ", "多余1", "多余2", "多余3"),
                correctedToolContext());

        assertThat(result.updated()).isTrue();
        assertThat(result.profileType()).isEqualTo("value");
        assertThat(result.subject()).isEqualTo("城市偏好");
        assertThat(result.action()).isEqualTo("written");
        assertThat(result.message()).isEqualTo("画像记忆已写入");

        ArgumentCaptor<ConfirmedMemoryCommand> commandCaptor = ArgumentCaptor.forClass(ConfirmedMemoryCommand.class);
        verify(profileMemoryGovernanceService).writeConfirmedMemory(commandCaptor.capture());
        ConfirmedMemoryCommand command = commandCaptor.getValue();
        assertThat(command.userId()).isEqualTo(USER_ID);
        assertThat(command.profileType()).isEqualTo("value");
        assertThat(command.subject()).isEqualTo("城市偏好");
        assertThat(command.content()).isEqualTo("其实我更偏向离家近的城市");
        assertThat(command.detail()).isEqualTo("长期规划");
        assertThat(command.confidence()).isEqualByComparingTo(new BigDecimal("0.95"));
        assertThat(command.evidence())
                .containsExactly("其实我更偏向离家近的城市", "我怕离家太远", "多余1", "多余2", "多余3");
        assertThat(command.source()).isEqualTo("agent_tool_update");
        assertThat(command.sourceConversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(command.userMessage()).isEqualTo("你刚才理解不对，城市偏好请更新成其实我更偏向离家近的城市");
        verify(profileMemoryGovernanceService, never()).createCandidate(any(MemoryCandidateCommand.class));
        verifyNoInteractions(valuesRepository);
        verify(profileSceneMemoryService, never()).saveSceneMemory(any());
        verify(logService).completeSuccess(eq(1L),
                contains("written value:城市偏好"), anyLong());
    }

    @Test
    void updateUserProfileCreatesCandidateWhenNeedsConfirmation() {
        when(conversationHistoryService.isOwnedConversation(USER_ID, CONVERSATION_ID)).thenReturn(true);
        when(effectLedger.acquire(anyLong(), any(), anyString(), eq("updateUserProfile"), anyString()))
                .thenReturn(AgentToolEffectLedger.Acquisition.reserved());
        DecisionAgentToolService.UpdateUserProfileToolResult result = service.updateUserProfile(
                "values",
                " 城市偏好 ",
                " 更偏向离家近的城市 ",
                " 长期规划 ",
                0.70,
                List.of("我怕离家太远", " ", "想离家近一点"),
                toolContext());

        assertThat(result.updated()).isFalse();
        assertThat(result.action()).isEqualTo("needs_confirmation");
        assertThat(result.message()).contains("确认");

        ArgumentCaptor<MemoryCandidateCommand> commandCaptor = ArgumentCaptor.forClass(MemoryCandidateCommand.class);
        verify(profileMemoryGovernanceService).createCandidate(commandCaptor.capture());
        MemoryCandidateCommand command = commandCaptor.getValue();
        assertThat(command.userId()).isEqualTo(USER_ID);
        assertThat(command.profileType()).isEqualTo("value");
        assertThat(command.subject()).isEqualTo("城市偏好");
        assertThat(command.content()).isEqualTo("更偏向离家近的城市");
        assertThat(command.detail()).isEqualTo("长期规划");
        assertThat(command.confidence()).isEqualByComparingTo(new BigDecimal("0.70"));
        assertThat(command.evidence()).containsExactly("我怕离家太远", "想离家近一点");
        assertThat(command.source()).isEqualTo("agent_tool_update");
        assertThat(command.sourceConversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(command.inputHash()).isNotBlank();
        verify(profileMemoryGovernanceService, never()).writeConfirmedMemory(any(ConfirmedMemoryCommand.class));
        verify(logService).completeSkipped(eq(1L),
                contains("needs_confirmation"), anyLong());
        verifyNoInteractions(valuesRepository);
        verify(profileSceneMemoryService, never()).saveSceneMemory(any());
    }

    @Test
    void updateUserProfileSkipsLowConfidenceProfile() {
        when(conversationHistoryService.isOwnedConversation(USER_ID, CONVERSATION_ID)).thenReturn(true);
        when(effectLedger.acquire(anyLong(), any(), anyString(), eq("updateUserProfile"), anyString()))
                .thenReturn(AgentToolEffectLedger.Acquisition.reserved());
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
        verifyNoInteractions(profileMemoryGovernanceService);
        verifyNoInteractions(valuesRepository);
        verify(logService).completeSkipped(eq(1L),
                contains("confidence too low"), anyLong());
        verify(profileSceneMemoryService, never()).saveSceneMemory(any());
    }

    @Test
    void updateUserProfileReplaysCommittedWriteWithoutReExecuting() {
        when(conversationHistoryService.isOwnedConversation(USER_ID, CONVERSATION_ID)).thenReturn(true);
        when(effectLedger.acquire(anyLong(), any(), anyString(), eq("updateUserProfile"), anyString()))
                .thenReturn(AgentToolEffectLedger.Acquisition.alreadyCommitted(
                        new AgentToolEffectLedger.CommittedWrite(
                                "written", "value", "城市偏好", "画像记忆已写入")));

        DecisionAgentToolService.UpdateUserProfileToolResult result = service.updateUserProfile(
                "value", "城市偏好", "更偏向离家近的城市", "长期规划", 0.95,
                List.of("更偏向离家近的城市"), toolContext());

        assertThat(result.updated()).isTrue();
        assertThat(result.message()).isEqualTo("画像记忆已写入");
        assertThat(result.profileType()).isEqualTo("value");
        assertThat(result.subject()).isEqualTo("城市偏好");
        assertThat(result.action()).isEqualTo("written");
        verify(profileMemoryGovernanceService, never()).writeConfirmedMemory(any(ConfirmedMemoryCommand.class));
        verify(profileMemoryGovernanceService, never()).createCandidate(any(MemoryCandidateCommand.class));
        verify(profileSceneMemoryService, never()).saveSceneMemory(any());
        verify(effectLedger, never()).finalizeWrite(anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any(), any());
        verify(logService).completeSkipped(eq(1L),
                contains("idempotent replay"), anyLong());
    }

    @Test
    void updateUserProfileIgnoresDuplicateWhenIdempotencyKeyInFlight() {
        when(conversationHistoryService.isOwnedConversation(USER_ID, CONVERSATION_ID)).thenReturn(true);
        when(effectLedger.acquire(anyLong(), any(), anyString(), eq("updateUserProfile"), anyString()))
                .thenReturn(AgentToolEffectLedger.Acquisition.inFlight());

        DecisionAgentToolService.UpdateUserProfileToolResult result = service.updateUserProfile(
                "value", "城市偏好", "更偏向离家近的城市", "长期规划", 0.95,
                List.of("更偏向离家近的城市"), toolContext());

        assertThat(result.updated()).isFalse();
        assertThat(result.action()).isEqualTo("processing");
        verify(profileMemoryGovernanceService, never()).writeConfirmedMemory(any(ConfirmedMemoryCommand.class));
        verify(profileMemoryGovernanceService, never()).createCandidate(any(MemoryCandidateCommand.class));
        verify(profileSceneMemoryService, never()).saveSceneMemory(any());
        verify(effectLedger, never()).finalizeWrite(anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void updateUserProfileCommitsLedgerAfterWrite() {
        when(conversationHistoryService.isOwnedConversation(USER_ID, CONVERSATION_ID)).thenReturn(true);
        when(effectLedger.acquire(anyLong(), any(), anyString(), eq("updateUserProfile"), anyString()))
                .thenReturn(AgentToolEffectLedger.Acquisition.reserved());
        when(profileMemoryGovernanceService.writeConfirmedMemory(any(ConfirmedMemoryCommand.class)))
                .thenReturn(new ProfileMemoryGovernanceService.GovernanceResult(
                        true, "confirm", "value", 10L, null, "画像记忆已写入"));

        service.updateUserProfile(
                "value", " 城市偏好 ", " 更偏向离家近的城市 ", " 长期规划 ", 0.95,
                List.of("更偏向离家近的城市"), correctedToolContext());

        verify(effectLedger).finalizeWrite(eq(USER_ID), eq("updateUserProfile"), anyString(),
                eq("written"), eq("value"), eq("城市偏好"), eq("画像记忆已写入"),
                eq(10L), isNull());
    }

    @Test
    void updateUserProfileDowngradesDeepProfileToCandidateWhenPostureGateRejects() {
        when(conversationHistoryService.isOwnedConversation(USER_ID, CONVERSATION_ID)).thenReturn(true);
        when(effectLedger.acquire(anyLong(), any(), anyString(), eq("updateUserProfile"), anyString()))
                .thenReturn(AgentToolEffectLedger.Acquisition.reserved());
        when(postureGateService.shouldGate("value")).thenReturn(true);
        when(postureGateService.evaluate(anyLong(), eq("value"), anyString(), anyString(),
                any(), any()))
                .thenReturn(new PostureGateService.GateVerdict(false, "reject"));
        ProfileMemoryCandidate candidate = new ProfileMemoryCandidate();
        candidate.setId(77L);
        when(profileMemoryGovernanceService.createCandidate(any(MemoryCandidateCommand.class)))
                .thenReturn(candidate);

        DecisionAgentToolService.UpdateUserProfileToolResult result = service.updateUserProfile(
                "value", "城市偏好", "更偏向离家近的城市", "长期规划", 0.95,
                List.of("更偏向离家近的城市"), toolContext());

        assertThat(result.updated()).isFalse();
        assertThat(result.action()).isEqualTo("needs_confirmation");
        assertThat(result.message()).contains("确认");
        verify(profileMemoryGovernanceService, never()).writeConfirmedMemory(any(ConfirmedMemoryCommand.class));
        verify(profileMemoryGovernanceService).recordGateRejection(eq(USER_ID), eq("value"), eq("城市偏好"), eq("reject"));

        ArgumentCaptor<MemoryCandidateCommand> cap = ArgumentCaptor.forClass(MemoryCandidateCommand.class);
        verify(profileMemoryGovernanceService).createCandidate(cap.capture());
        assertThat(cap.getValue().inputHash()).isNotBlank();
        assertThat(cap.getValue().subject()).isEqualTo("城市偏好");

        verify(effectLedger).finalizeWrite(eq(USER_ID), eq("updateUserProfile"), anyString(),
                eq("needs_confirmation"), eq("value"), eq("城市偏好"), eq("深层画像更新需先确认"),
                isNull(), eq(77L));
        verify(logService).completeSkipped(eq(1L),
                contains("gated→candidate"), anyLong());
    }

    @Test
    void updateUserProfileBlockedWhenToolNotVisibleInScenario() {
        DecisionAgentToolService.UpdateUserProfileToolResult result = service.updateUserProfile(
                "value", "城市偏好", "更偏向离家近的城市", "长期规划", 0.95,
                List.of("更偏向离家近的城市"), toolContextWithoutWrite());

        assertThat(result.updated()).isFalse();
        assertThat(result.action()).isEqualTo("skipped");
        assertThat(result.message()).contains("未开放");
        verify(effectLedger, never()).acquire(anyLong(), any(), anyString(), anyString(), anyString());
        verify(profileMemoryGovernanceService, never()).writeConfirmedMemory(any(ConfirmedMemoryCommand.class));
        verify(profileMemoryGovernanceService, never()).createCandidate(any(MemoryCandidateCommand.class));
    }

    @Test
    void updateUserProfileBlockedWhenConversationNotOwned() {
        when(conversationHistoryService.isOwnedConversation(USER_ID, CONVERSATION_ID)).thenReturn(false);

        DecisionAgentToolService.UpdateUserProfileToolResult result = service.updateUserProfile(
                "value", "城市偏好", "更偏向离家近的城市", "长期规划", 0.95,
                List.of("更偏向离家近的城市"), toolContext());

        assertThat(result.updated()).isFalse();
        assertThat(result.action()).isEqualTo("skipped");
        assertThat(result.message()).contains("会话归属");
        verify(effectLedger, never()).acquire(anyLong(), any(), anyString(), anyString(), anyString());
        verify(profileMemoryGovernanceService, never()).writeConfirmedMemory(any(ConfirmedMemoryCommand.class));
        verify(profileMemoryGovernanceService, never()).createCandidate(any(MemoryCandidateCommand.class));
    }

    private ToolContext toolContext() {
        return new ToolContext(Map.of(
                AgentToolContext.USER_ID, USER_ID,
                AgentToolContext.CONVERSATION_ID, CONVERSATION_ID,
                AgentToolContext.MESSAGE, "我正在考虑是否接受外地 offer",
                AgentToolContext.REQUEST_ID, "req-1"));
    }

    private ToolContext toolContextWithoutWrite() {
        return new ToolContext(Map.of(
                AgentToolContext.USER_ID, USER_ID,
                AgentToolContext.CONVERSATION_ID, CONVERSATION_ID,
                AgentToolContext.MESSAGE, "我正在考虑是否接受外地 offer",
                AgentToolContext.REQUEST_ID, "req-1",
                AgentToolContext.VISIBLE_TOOLS, Set.of(
                        "searchDecisionHistory", "searchSemanticMemory", "generateDecisionMatrix")));
    }

    private ToolContext toolContextWithSemanticRecall(int semanticHitCount, String semanticQuery) {
        return new ToolContext(Map.of(
                AgentToolContext.USER_ID, USER_ID,
                AgentToolContext.CONVERSATION_ID, CONVERSATION_ID,
                AgentToolContext.MESSAGE, "我正在考虑是否接受外地 offer",
                AgentToolContext.REQUEST_ID, "req-1",
                AgentToolContext.SEMANTIC_MEMORY_RETRIEVED, true,
                AgentToolContext.SEMANTIC_HIT_COUNT, semanticHitCount,
                AgentToolContext.MAX_SEMANTIC_SCORE, 0.82,
                AgentToolContext.SEMANTIC_QUERY, semanticQuery));
    }

    private ToolContext correctedToolContext() {
        return new ToolContext(Map.of(
                AgentToolContext.USER_ID, USER_ID,
                AgentToolContext.CONVERSATION_ID, CONVERSATION_ID,
                AgentToolContext.MESSAGE, "你刚才理解不对，城市偏好请更新成其实我更偏向离家近的城市",
                AgentToolContext.REQUEST_ID, "req-1"));
    }

    private DecisionRecallService.DecisionRecallResult decisionResult(
            DecisionRecallService.DecisionRecallItem... items) {
        return new DecisionRecallService.DecisionRecallResult(List.of(items));
    }

    private DecisionRecallService.DecisionRecallItem decision(String topic, String choice, String reason) {
        return new DecisionRecallService.DecisionRecallItem(
                topic,
                choice,
                reason,
                "",
                3,
                10,
                List.of("topic"));
    }
}
