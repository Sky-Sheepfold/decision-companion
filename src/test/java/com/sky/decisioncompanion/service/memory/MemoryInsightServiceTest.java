package com.sky.decisioncompanion.service.memory;

import com.sky.decisioncompanion.common.BusinessException;
import com.sky.decisioncompanion.config.MemoryInsightProperties;
import com.sky.decisioncompanion.model.MemoryAwareness;
import com.sky.decisioncompanion.model.MemoryInsight;
import com.sky.decisioncompanion.repository.MemoryAwarenessRepository;
import com.sky.decisioncompanion.repository.MemoryInsightRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryInsightServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private MemoryInsightRepository insightRepository;

    @Mock
    private MemoryAwarenessRepository awarenessRepository;

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @Mock
    private EmbeddingModel embeddingModel;

    private MemoryInsightProperties properties;
    private MemoryInsightService service;

    @BeforeEach
    void setUp() {
        lenient().when(chatClientBuilder.build()).thenReturn(chatClient);
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.messages(any(org.springframework.ai.chat.messages.Message[].class))).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponseSpec);
        properties = new MemoryInsightProperties();
        service = new MemoryInsightService(
                insightRepository, awarenessRepository, properties, chatClientBuilder, embeddingModel);
    }

    @Test
    void generatesInsightsFromRecentAwarenessWithEvidenceChain() {
        when(awarenessRepository.selectList(any())).thenReturn(List.of(
                awareness(11L, "用户近期因决策压力睡眠不佳"),
                awareness(12L, "用户反复权衡是否接受外地 offer")));
        when(callResponseSpec.content()).thenReturn("""
                [
                  {"hypothesis": "用户反复纠结可能源于害怕做错决定", "evidence": ["用户近期因决策压力睡眠不佳"], "confidence": 0.72},
                  {"hypothesis": "用户在选择时更倾向稳定性与确定性", "evidence": ["用户反复权衡是否接受外地 offer"], "confidence": 0.65}
                ]
                """);
        when(insightRepository.selectList(any())).thenReturn(List.of());

        int added = service.generateForUser(USER_ID);

        assertThat(added).isEqualTo(2);
        ArgumentCaptor<MemoryInsight> captor = ArgumentCaptor.forClass(MemoryInsight.class);
        verify(insightRepository, org.mockito.Mockito.times(2)).insert(captor.capture());
        MemoryInsight first = captor.getAllValues().get(0);
        assertThat(first.getUserId()).isEqualTo(USER_ID);
        assertThat(first.getHypothesis()).contains("害怕做错决定");
        assertThat(first.getConfidence()).isEqualByComparingTo("0.72");
        assertThat(first.getVerdict()).isEqualTo("");
        assertThat(first.getActive()).isTrue();
        // 证据链：来源觉察 ID 与输入批次一致，近似归属
        assertThat(first.getSourceAwarenessIds()).isEqualTo("11,12");
    }

    @Test
    void mergesUnjudgedDuplicateWithLatestConfidence() {
        when(awarenessRepository.selectList(any())).thenReturn(List.of(awareness(11L, "用户反复纠结")));
        when(callResponseSpec.content()).thenReturn("""
                [{"hypothesis": "用户反复纠结可能源于害怕做错决定", "evidence": [], "confidence": 0.66}]
                """);
        MemoryInsight existing = insight("用户反复纠结可能源于害怕做错决定", "0.60", "", true);
        when(insightRepository.selectList(any())).thenReturn(List.of(existing));

        int added = service.generateForUser(USER_ID);

        assertThat(added).isZero();
        // 未判定：跟随最新证据（0.66），仍有效
        assertThat(existing.getConfidence()).isEqualByComparingTo("0.66");
        assertThat(existing.getActive()).isTrue();
        verify(insightRepository).updateById(existing);
    }

    @Test
    void rejectedInsightOnlyFallsAndNeverResurrects() {
        when(awarenessRepository.selectList(any())).thenReturn(List.of(awareness(11L, "用户反复纠结")));
        when(callResponseSpec.content()).thenReturn("""
                [{"hypothesis": "用户反复纠结可能源于害怕做错决定", "evidence": [], "confidence": 0.90}]
                """);
        MemoryInsight existing = insight("用户反复纠结可能源于害怕做错决定", "0.72", "rejected", false);
        when(insightRepository.selectList(any())).thenReturn(List.of(existing));

        int added = service.generateForUser(USER_ID);

        assertThat(added).isZero();
        // 用户否定过：置信度只降不升（0.72→0.72），且不复活（active 保持 false）
        assertThat(existing.getConfidence()).isEqualByComparingTo("0.72");
        assertThat(existing.getVerdict()).isEqualTo("rejected");
        assertThat(existing.getActive()).isFalse();
    }

    @Test
    void confirmedInsightOnlyRisesAndStaysActive() {
        when(awarenessRepository.selectList(any())).thenReturn(List.of(awareness(11L, "用户反复纠结")));
        when(callResponseSpec.content()).thenReturn("""
                [{"hypothesis": "用户反复纠结可能源于害怕做错决定", "evidence": [], "confidence": 0.55}]
                """);
        MemoryInsight existing = insight("用户反复纠结可能源于害怕做错决定", "0.80", "confirmed", true);
        when(insightRepository.selectList(any())).thenReturn(List.of(existing));

        int added = service.generateForUser(USER_ID);

        assertThat(added).isZero();
        // 用户确认过：置信度只升不降（0.80 保持）
        assertThat(existing.getConfidence()).isEqualByComparingTo("0.80");
        assertThat(existing.getVerdict()).isEqualTo("confirmed");
        assertThat(existing.getActive()).isTrue();
    }

    @Test
    void semanticNearDuplicateMergesInsteadOfInserting() {
        when(awarenessRepository.selectList(any())).thenReturn(List.of(awareness(11L, "用户反复纠结")));
        when(callResponseSpec.content()).thenReturn("""
                [{"hypothesis": "用户反复纠结可能是怕做错", "evidence": [], "confidence": 0.70}]
                """);
        MemoryInsight existing = insight("用户反复纠结源于害怕做错决定", "0.60", "", true);
        when(insightRepository.selectList(any())).thenReturn(List.of(existing));
        // 语义相似：cos([1,0],[0.95,0.31]) ≈ 0.95 > 阈值 0.85
        when(embeddingModel.embed(anyList()))
                .thenReturn(List.of(new float[]{1f, 0f}))
                .thenReturn(List.of(new float[]{0.95f, 0.31f}));

        int added = service.generateForUser(USER_ID);

        assertThat(added).isZero();
        // 字面不同但语义近重复 → 合并（未判定跟随最新证据 0.70），不新增
        assertThat(existing.getConfidence()).isEqualByComparingTo("0.70");
        assertThat(existing.getActive()).isTrue();
        verify(insightRepository, never()).insert(any(MemoryInsight.class));
        verify(insightRepository).updateById(existing);
    }

    @Test
    void distinctHypothesisInsertsWhenBelowSemanticThreshold() {
        when(awarenessRepository.selectList(any())).thenReturn(List.of(awareness(11L, "用户反复纠结")));
        when(callResponseSpec.content()).thenReturn("""
                [{"hypothesis": "用户在工作选择上更倾向求稳", "evidence": [], "confidence": 0.68}]
                """);
        when(insightRepository.selectList(any()))
                .thenReturn(List.of(insight("用户反复纠结源于害怕做错决定", "0.60", "", true)));
        // 语义不相似：cos([1,0],[0,1]) = 0 < 阈值
        when(embeddingModel.embed(anyList()))
                .thenReturn(List.of(new float[]{1f, 0f}))
                .thenReturn(List.of(new float[]{0f, 1f}));

        int added = service.generateForUser(USER_ID);

        assertThat(added).isEqualTo(1);
        verify(insightRepository).insert(any(MemoryInsight.class));
    }

    @Test
    void embeddingFailureFallsBackToTextDedup() {
        when(awarenessRepository.selectList(any())).thenReturn(List.of(awareness(11L, "用户反复纠结")));
        when(callResponseSpec.content()).thenReturn("""
                [{"hypothesis": "用户在工作选择上更倾向求稳", "evidence": [], "confidence": 0.68}]
                """);
        when(insightRepository.selectList(any()))
                .thenReturn(List.of(insight("用户反复纠结源于害怕做错决定", "0.60", "", true)));
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("embedding down"));

        int added = service.generateForUser(USER_ID);

        // 语义去重失败不阻塞生成，降级为纯文本去重（插入新假设）
        assertThat(added).isEqualTo(1);
        verify(insightRepository).insert(any(MemoryInsight.class));
    }

    @Test
    void semanticMatchToRejectedKeepsItInactive() {
        when(awarenessRepository.selectList(any())).thenReturn(List.of(awareness(11L, "用户反复纠结")));
        when(callResponseSpec.content()).thenReturn("""
                [{"hypothesis": "用户反复纠结可能是怕做错", "evidence": [], "confidence": 0.90}]
                """);
        MemoryInsight rejected = insight("用户反复纠结源于害怕做错决定", "0.72", "rejected", false);
        when(insightRepository.selectList(any())).thenReturn(List.of(rejected));
        when(embeddingModel.embed(anyList()))
                .thenReturn(List.of(new float[]{1f, 0f}))
                .thenReturn(List.of(new float[]{0.95f, 0.31f}));

        int added = service.generateForUser(USER_ID);

        assertThat(added).isZero();
        // 语义命中被否定假设 → 保持 inactive 不复活，置信度只降不升
        assertThat(rejected.getVerdict()).isEqualTo("rejected");
        assertThat(rejected.getActive()).isFalse();
        assertThat(rejected.getConfidence()).isEqualByComparingTo("0.72");
        verify(insightRepository, never()).insert(any(MemoryInsight.class));
    }

    @Test
    void returnsEmptyWhenNoRecentAwareness() {
        when(awarenessRepository.selectList(any())).thenReturn(List.of());

        int added = service.generateForUser(USER_ID);

        assertThat(added).isZero();
        verify(insightRepository, never()).insert(any(MemoryInsight.class));
    }

    @Test
    void findActiveReturnsBoundedActiveInsights() {
        when(insightRepository.selectList(any())).thenReturn(List.of(insight("假设A", "0.8", "", true)));

        List<MemoryInsight> active = service.findActive(USER_ID, 3);

        assertThat(active).hasSize(1);
        assertThat(active.get(0).getHypothesis()).isEqualTo("假设A");
    }

    @Test
    void judgeConfirmSetsVerdictAndConfidenceFloor() {
        MemoryInsight insight = insight("假设A", "0.5", "", true);
        when(insightRepository.selectById(1L)).thenReturn(insight);

        MemoryInsight judged = service.judge(USER_ID, 1L, "confirm");

        assertThat(judged.getVerdict()).isEqualTo("confirmed");
        assertThat(judged.getActive()).isTrue();
        // 确认设置信度下限 0.75
        assertThat(judged.getConfidence()).isEqualByComparingTo("0.75");
        verify(insightRepository).updateById(insight);
    }

    @Test
    void judgeRejectSetsInactiveAndKeepsAudit() {
        MemoryInsight insight = insight("假设A", "0.8", "", true);
        when(insightRepository.selectById(1L)).thenReturn(insight);

        MemoryInsight judged = service.judge(USER_ID, 1L, "reject");

        assertThat(judged.getVerdict()).isEqualTo("rejected");
        assertThat(judged.getActive()).isFalse();
        verify(insightRepository).updateById(insight);
    }

    @Test
    void judgeRejectsInsightNotOwnedByUser() {
        MemoryInsight insight = insight("假设A", "0.8", "", true);
        insight.setUserId(999L);
        when(insightRepository.selectById(1L)).thenReturn(insight);

        assertThatThrownBy(() -> service.judge(USER_ID, 1L, "confirm"))
                .isInstanceOf(BusinessException.class);
        verify(insightRepository, never()).updateById(any(MemoryInsight.class));
    }

    @Test
    void judgeWithUnknownVerdictIsRejected() {
        MemoryInsight insight = insight("假设A", "0.8", "", true);
        when(insightRepository.selectById(1L)).thenReturn(insight);

        assertThatThrownBy(() -> service.judge(USER_ID, 1L, "maybe"))
                .isInstanceOf(BusinessException.class);
        verify(insightRepository, never()).updateById(any(MemoryInsight.class));
    }

    private MemoryAwareness awareness(Long id, String observation) {
        MemoryAwareness awareness = new MemoryAwareness();
        awareness.setId(id);
        awareness.setUserId(USER_ID);
        awareness.setObservation(observation);
        awareness.setAwareDate(LocalDate.now());
        awareness.setActive(true);
        return awareness;
    }

    private MemoryInsight insight(String hypothesis, String confidence, String verdict, boolean active) {
        MemoryInsight insight = new MemoryInsight();
        insight.setId(1L);
        insight.setUserId(USER_ID);
        insight.setHypothesis(hypothesis);
        insight.setConfidence(new BigDecimal(confidence));
        insight.setVerdict(verdict);
        insight.setActive(active);
        return insight;
    }
}
