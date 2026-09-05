package com.sky.decisioncompanion.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.decisioncompanion.model.MemoryRetrievalLog;
import com.sky.decisioncompanion.repository.MemoryRetrievalLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryRetrievalLogServiceTest {

    @Mock
    private MemoryRetrievalLogRepository repository;

    private MemoryRetrievalLogService service;

    @BeforeEach
    void setUp() {
        service = new MemoryRetrievalLogService(repository, new ObjectMapper());
    }

    @Test
    void recordAutoRecallPersistsMetricsAndTopSemanticHitSummary() {
        MemoryContext context = context(List.of(
                new MemoryContext.SemanticMemory(
                        "用户多次提到希望离父母近一点",
                        "relationship",
                        2,
                        0.82,
                        1.25,
                        "vectorScore=0.8200, preferredType=relationship+0.2500"),
                new MemoryContext.SemanticMemory("用户在毕业选择中倾向稳定性", "value", 1, 0.71)));
        MemoryRetrievalPlan plan = new MemoryRetrievalIntentService(new com.sky.decisioncompanion.config.MemoryRetrievalProperties())
                .plan("外地 offer 要不要接受？");

        service.recordAutoRecall(1L, "外地 offer 要不要接受？".repeat(80), context, plan, 0.6);

        ArgumentCaptor<MemoryRetrievalLog> captor = ArgumentCaptor.forClass(MemoryRetrievalLog.class);
        verify(repository).insert(captor.capture());
        MemoryRetrievalLog log = captor.getValue();
        assertThat(log.getUserId()).isEqualTo(1L);
        assertThat(log.getRetrievalSource()).isEqualTo("auto_prompt");
        assertThat(log.getQueryText()).hasSizeLessThanOrEqualTo(1000);
        assertThat(log.getValueCount()).isEqualTo(1);
        assertThat(log.getEmotionCount()).isEqualTo(1);
        assertThat(log.getDecisionCount()).isEqualTo(1);
        assertThat(log.getRelationshipCount()).isEqualTo(1);
        assertThat(log.getFearCount()).isEqualTo(1);
        assertThat(log.getSemanticHitCount()).isEqualTo(2);
        assertThat(log.getMaxSemanticScore()).isEqualByComparingTo("0.82");
        assertThat(log.getVectorAvailable()).isTrue();
        assertThat(log.getDegraded()).isFalse();
        assertThat(log.getSemanticTopK()).isEqualTo(5);
        assertThat(log.getIntent()).isEqualTo("major_decision");
        assertThat(log.getSemanticQuery()).contains("重大决策");
        assertThat(log.getSemanticCandidateTopK()).isEqualTo(10);
        assertThat(log.getPreferredMemoryTypes()).contains("value");
        assertThat(log.getPreferredMemoryTypes()).contains("fear");
        assertThat(log.getSemanticSimilarityThreshold()).isEqualByComparingTo("0.60");
        assertThat(log.getPromptContextLength()).isEqualTo(context.promptContext().length());
        assertThat(log.getSemanticHitSummary()).contains("离父母近一点");
        assertThat(log.getSemanticHitSummary()).contains("relationship");
        assertThat(log.getSemanticHitSummary()).contains("rerankScore");
        assertThat(log.getSemanticHitSummary()).contains("preferredType=relationship");
    }

    @Test
    void recordAutoRecallDoesNotThrowWhenInsertFails() {
        when(repository.insert(any(MemoryRetrievalLog.class))).thenThrow(new RuntimeException("db down"));

        MemoryRetrievalPlan plan = new MemoryRetrievalIntentService(new com.sky.decisioncompanion.config.MemoryRetrievalProperties())
                .plan("query");

        assertThatCode(() -> service.recordAutoRecall(1L, "query", context(List.of()), plan, 0.6))
                .doesNotThrowAnyException();
    }

    private MemoryContext context(List<MemoryContext.SemanticMemory> semanticMemories) {
        return new MemoryContext(
                List.of(new MemoryContext.ProfileMemory("城市偏好", "偏好离家近", "", 0.9)),
                List.of(new MemoryContext.ProfileMemory("焦虑", "被催促时压力变大", "", null)),
                List.of(new MemoryContext.DecisionMemory("毕业 offer", "暂缓接受", "距离太远", "", null)),
                List.of(new MemoryContext.RelationshipMemory("妈妈", "母亲", "高", "安全稳定", "")),
                List.of(new MemoryContext.ProfileMemory("fear", "害怕离家太远", "", 0.8)),
                List.of(),
                semanticMemories,
                List.of(),
                new MemoryContext.RetrievalMetrics(1, 1, 1, 1, 1,
                        semanticMemories.size(), 0.82, true, false),
                "长期记忆背景包");
    }
}
