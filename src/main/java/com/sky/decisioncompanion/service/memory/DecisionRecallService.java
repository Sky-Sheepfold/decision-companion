package com.sky.decisioncompanion.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.config.MemoryRetrievalProperties;
import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class DecisionRecallService {

    private static final int TOPIC_WEIGHT = 5;
    private static final int CHOICE_WEIGHT = 4;
    private static final int REASON_WEIGHT = 3;
    private static final int TAGS_WEIGHT = 3;
    private static final int OUTCOME_WEIGHT = 2;
    private static final int DECISION_DATE_WEIGHT = 1;

    private final ProfileDecisionRepository decisionRepository;
    private final MemoryRetrievalProperties properties;

    public DecisionRecallService(
            ProfileDecisionRepository decisionRepository,
            MemoryRetrievalProperties properties) {
        this.decisionRepository = decisionRepository;
        this.properties = properties;
    }

    public DecisionRecallResult recall(Long userId, String query, Integer limit) {
        if (userId == null || !StringUtils.hasText(query)) {
            return new DecisionRecallResult(List.of());
        }

        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return new DecisionRecallResult(List.of());
        }

        int safeLimit = properties.decision().normalizeLimit(limit);
        int candidateLimit = safeLimit * properties.decision().candidateMultiplier();
        List<DecisionRecallItem> items = decisionRepository.selectList(new LambdaQueryWrapper<ProfileDecision>()
                        .eq(ProfileDecision::getUserId, userId)
                        .orderByDesc(ProfileDecision::getCreatedAt)
                        .orderByDesc(ProfileDecision::getId)
                        .last("LIMIT " + candidateLimit))
                .stream()
                .filter(decision -> Objects.equals(userId, decision.getUserId()))
                .map(decision -> scoreDecision(decision, tokens))
                .filter(scored -> scored.item().score() > 0)
                .sorted()
                .limit(safeLimit)
                .map(ScoredDecision::item)
                .toList();
        return new DecisionRecallResult(items);
    }

    private ScoredDecision scoreDecision(ProfileDecision decision, List<String> tokens) {
        int score = 0;
        Set<String> matchedFields = new LinkedHashSet<>();

        score += scoreField("topic", decision.getTopic(), tokens, TOPIC_WEIGHT, matchedFields);
        score += scoreField("choice", decision.getChoice(), tokens, CHOICE_WEIGHT, matchedFields);
        score += scoreField("reason", decision.getReason(), tokens, REASON_WEIGHT, matchedFields);
        score += scoreField("tags", decision.getTags(), tokens, TAGS_WEIGHT, matchedFields);
        score += scoreField("outcome", decision.getOutcome(), tokens, OUTCOME_WEIGHT, matchedFields);
        score += scoreField("decisionDate", decision.getDecisionDate(), tokens, DECISION_DATE_WEIGHT, matchedFields);

        DecisionRecallItem item = new DecisionRecallItem(
                clean(decision.getTopic()),
                clean(decision.getChoice()),
                clean(decision.getReason()),
                clean(decision.getOutcome()),
                decision.getSatisfaction(),
                score,
                List.copyOf(matchedFields));
        return new ScoredDecision(item, decision.getCreatedAt(), decision.getId());
    }

    private int scoreField(
            String fieldName,
            String fieldValue,
            List<String> tokens,
            int weight,
            Set<String> matchedFields) {
        String normalized = normalize(fieldValue);
        if (normalized.isBlank()) {
            return 0;
        }

        int matches = 0;
        for (String token : tokens) {
            if (normalized.contains(token)) {
                matches++;
            }
        }
        if (matches > 0) {
            matchedFields.add(fieldName);
        }
        return matches * weight;
    }

    private List<String> tokens(String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return List.of();
        }

        Set<String> result = new LinkedHashSet<>();
        for (String token : normalized.split("[\\s,，。！？、;；:：.!?\"“”'‘’()（）\\[\\]【】]+")) {
            if (token.length() >= properties.decision().minTokenLength()) {
                result.add(token);
            }
        }

        for (int i = 0; i < normalized.length() - 1; i++) {
            char first = normalized.charAt(i);
            char second = normalized.charAt(i + 1);
            if (Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN
                    && Character.UnicodeScript.of(second) == Character.UnicodeScript.HAN) {
                String token = normalized.substring(i, i + 2);
                if (token.length() >= properties.decision().minTokenLength()) {
                    result.add(token);
                }
            }
        }

        return result.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    private String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public record DecisionRecallResult(List<DecisionRecallItem> items) {

        public DecisionRecallResult {
            items = List.copyOf(items);
        }
    }

    public record DecisionRecallItem(
            String topic,
            String choice,
            String reason,
            String outcome,
            Integer satisfaction,
            int score,
            List<String> matchedFields) {

        public DecisionRecallItem {
            matchedFields = List.copyOf(matchedFields);
        }
    }

    private record ScoredDecision(DecisionRecallItem item, LocalDateTime createdAt, Long id)
            implements Comparable<ScoredDecision> {

        @Override
        public int compareTo(ScoredDecision other) {
            int scoreComparison = Integer.compare(other.item.score(), item.score());
            if (scoreComparison != 0) {
                return scoreComparison;
            }
            int createdAtComparison = compareNullableDesc(createdAt, other.createdAt);
            if (createdAtComparison != 0) {
                return createdAtComparison;
            }
            return compareNullableDesc(id, other.id);
        }

        private static <T extends Comparable<T>> int compareNullableDesc(T left, T right) {
            if (left == null && right == null) {
                return 0;
            }
            if (left == null) {
                return 1;
            }
            if (right == null) {
                return -1;
            }
            return right.compareTo(left);
        }
    }
}
