package com.sky.decisioncompanion.service.memory;

import com.sky.decisioncompanion.config.MemoryRetrievalProperties;
import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionRecallServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ProfileDecisionRepository decisionRepository;

    private MemoryRetrievalProperties properties;
    private DecisionRecallService service;

    @BeforeEach
    void setUp() {
        properties = new MemoryRetrievalProperties();
        service = new DecisionRecallService(decisionRepository, properties);
    }

    @Test
    void recallScoresAndSortsCurrentUsersMatchingDecisions() {
        ProfileDecision topicMatch = decision(
                USER_ID,
                10L,
                "外地 offer",
                "接受杭州 offer",
                "成长空间更大",
                "",
                "[\"城市\",\"职业\"]",
                "2025",
                LocalDateTime.of(2025, 5, 1, 10, 0));
        ProfileDecision reasonOnlyMatch = decision(
                USER_ID,
                9L,
                "职业选择",
                "暂缓决定",
                "也考虑过 offer 机会",
                "",
                "[\"职业\"]",
                "2024",
                LocalDateTime.of(2025, 5, 2, 10, 0));
        ProfileDecision anotherUser = decision(
                2L,
                11L,
                "外地 offer",
                "留在本地",
                "陪家人",
                "",
                "[\"城市\"]",
                "2025",
                LocalDateTime.of(2025, 5, 3, 10, 0));
        when(decisionRepository.selectList(any())).thenReturn(List.of(reasonOnlyMatch, anotherUser, topicMatch));

        DecisionRecallService.DecisionRecallResult result = service.recall(USER_ID, "offer", 5);

        assertThat(result.items()).extracting(DecisionRecallService.DecisionRecallItem::topic)
                .containsExactly("外地 offer", "职业选择");
        assertThat(result.items().get(0).score()).isGreaterThan(result.items().get(1).score());
        assertThat(result.items()).allMatch(item -> item.matchedFields().contains("reason")
                || item.matchedFields().contains("topic")
                || item.matchedFields().contains("choice"));
    }

    @Test
    void recallMatchesTagsOutcomeAndDecisionDate() {
        ProfileDecision tagsOutcomeDateMatch = decision(
                USER_ID,
                12L,
                "租房选择",
                "住公司附近",
                "通勤更短",
                "后来因为通勤压力小，满意度更高",
                "[\"城市\",\"生活\"]",
                "2025",
                LocalDateTime.of(2025, 4, 1, 10, 0));
        when(decisionRepository.selectList(any())).thenReturn(List.of(tagsOutcomeDateMatch));

        DecisionRecallService.DecisionRecallResult result = service.recall(USER_ID, "城市 满意度 2025", 5);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).matchedFields())
                .contains("tags", "outcome", "decisionDate");
    }

    @Test
    void recallReturnsEmptyForBlankQuery() {
        DecisionRecallService.DecisionRecallResult result = service.recall(USER_ID, "   ", 5);

        assertThat(result.items()).isEmpty();
        verify(decisionRepository, never()).selectList(any());
    }

    @Test
    void recallUsesConfiguredDefaultAndMaxLimit() {
        properties.getDecision().setDefaultLimit(2);
        properties.getDecision().setMaxLimit(3);
        when(decisionRepository.selectList(any())).thenReturn(List.of(
                decision(USER_ID, 1L, "offer 1"),
                decision(USER_ID, 2L, "offer 2"),
                decision(USER_ID, 3L, "offer 3"),
                decision(USER_ID, 4L, "offer 4")));

        DecisionRecallService.DecisionRecallResult defaultResult = service.recall(USER_ID, "offer", null);
        DecisionRecallService.DecisionRecallResult clippedResult = service.recall(USER_ID, "offer", 99);

        assertThat(defaultResult.items()).hasSize(2);
        assertThat(clippedResult.items()).hasSize(3);
    }

    @Test
    void recallBreaksScoreTiesByCreatedAtThenId() {
        ProfileDecision older = decision(
                USER_ID,
                1L,
                "offer A",
                "接受",
                "",
                "",
                "[]",
                "2025",
                LocalDateTime.of(2025, 1, 1, 10, 0));
        ProfileDecision newerLowId = decision(
                USER_ID,
                2L,
                "offer B",
                "接受",
                "",
                "",
                "[]",
                "2025",
                LocalDateTime.of(2025, 1, 2, 10, 0));
        ProfileDecision newerHighId = decision(
                USER_ID,
                3L,
                "offer C",
                "接受",
                "",
                "",
                "[]",
                "2025",
                LocalDateTime.of(2025, 1, 2, 10, 0));
        when(decisionRepository.selectList(any())).thenReturn(List.of(older, newerLowId, newerHighId));

        DecisionRecallService.DecisionRecallResult result = service.recall(USER_ID, "offer", 5);

        assertThat(result.items()).extracting(DecisionRecallService.DecisionRecallItem::topic)
                .containsExactly("offer C", "offer B", "offer A");
    }

    private ProfileDecision decision(Long userId, Long id, String topic) {
        return decision(
                userId,
                id,
                topic,
                "选择",
                "原因",
                "",
                "[]",
                "2025",
                LocalDateTime.of(2025, 1, 1, 10, 0).plusDays(id));
    }

    private ProfileDecision decision(
            Long userId,
            Long id,
            String topic,
            String choice,
            String reason,
            String outcome,
            String tags,
            String decisionDate,
            LocalDateTime createdAt) {
        ProfileDecision decision = new ProfileDecision();
        decision.setUserId(userId);
        decision.setId(id);
        decision.setTopic(topic);
        decision.setChoice(choice);
        decision.setReason(reason);
        decision.setOutcome(outcome);
        decision.setTags(tags);
        decision.setDecisionDate(decisionDate);
        decision.setCreatedAt(createdAt);
        decision.setSatisfaction(4);
        return decision;
    }
}
