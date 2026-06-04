package com.sky.decisioncompanion.service.memory;

import com.sky.decisioncompanion.config.MemoryRetrievalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRetrievalIntentServiceTest {

    private MemoryRetrievalIntentService service;

    @BeforeEach
    void setUp() {
        service = new MemoryRetrievalIntentService(new MemoryRetrievalProperties());
    }

    @Test
    void resolvesDecisionChoiceAndRaisesDecisionRecallLimit() {
        MemoryRetrievalPlan plan = service.plan("我现在还是在纠结外地高薪机会和离父母近之间怎么取舍");

        assertThat(plan.intent()).isEqualTo(MemoryRetrievalIntent.MAJOR_DECISION);
        assertThat(plan.decisionsLimit()).isEqualTo(4);
        assertThat(plan.semanticQuery()).contains("重大决策");
        assertThat(plan.preferredMemoryTypes()).containsExactly("value", "fear", "relationship", "decision");
        assertThat(plan.promptOrder()).containsSubsequence(
                MemoryRetrievalPlan.Section.VALUES,
                MemoryRetrievalPlan.Section.DECISIONS,
                MemoryRetrievalPlan.Section.EMOTIONS);
    }

    @Test
    void resolvesEmotionSupportAndKeepsDecisionRecallSmall() {
        MemoryRetrievalPlan plan = service.plan("我最近压力很大很焦虑，有点撑不住");

        assertThat(plan.intent()).isEqualTo(MemoryRetrievalIntent.VENTING);
        assertThat(plan.decisionsLimit()).isEqualTo(1);
        assertThat(plan.semanticQuery()).contains("情绪倾诉");
        assertThat(plan.preferredMemoryTypes()).containsExactly("emotion", "fear", "relationship");
        assertThat(plan.promptOrder()).containsSubsequence(
                MemoryRetrievalPlan.Section.EMOTIONS,
                MemoryRetrievalPlan.Section.FEARS,
                MemoryRetrievalPlan.Section.VALUES);
    }

    @Test
    void resolvesRelationshipPressureBeforeWeakReviewSignals() {
        MemoryRetrievalPlan plan = service.plan("我是不是一直很在意父母的意见");

        assertThat(plan.intent()).isEqualTo(MemoryRetrievalIntent.RELATIONSHIP_PRESSURE);
        assertThat(plan.promptOrder().get(0)).isEqualTo(MemoryRetrievalPlan.Section.RELATIONSHIPS);
    }

    @Test
    void resolvesReviewSummaryForExplicitReviewRequests() {
        MemoryRetrievalPlan plan = service.plan("我想复盘最近几次工作选择");

        assertThat(plan.intent()).isEqualTo(MemoryRetrievalIntent.REVIEW);
        assertThat(plan.decisionsLimit()).isEqualTo(5);
        assertThat(plan.promptOrder().get(0)).isEqualTo(MemoryRetrievalPlan.Section.DECISIONS);
    }

    @Test
    void resolvesGoalPlanningForFutureDirectionRequests() {
        MemoryRetrievalPlan plan = service.plan("我想规划未来三年的职业目标和成长路径");

        assertThat(plan.intent()).isEqualTo(MemoryRetrievalIntent.GOAL_PLANNING);
        assertThat(plan.semanticQuery()).contains("目标规划");
        assertThat(plan.preferredMemoryTypes()).containsExactly("value", "decision", "fear", "mixed");
        assertThat(plan.promptOrder().get(0)).isEqualTo(MemoryRetrievalPlan.Section.VALUES);
    }
}
