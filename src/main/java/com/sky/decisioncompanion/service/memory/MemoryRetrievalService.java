package com.sky.decisioncompanion.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.model.ProfileEmotion;
import com.sky.decisioncompanion.model.ProfileFear;
import com.sky.decisioncompanion.model.ProfileValues;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class MemoryRetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryRetrievalService.class);
    private static final int MAX_SECTION_ITEMS = 5;
    private static final int DEFAULT_DECISION_LIMIT = 3;
    private static final int MAX_TEXT_LENGTH = 200;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.6;

    private final ProfileValuesRepository valuesRepository;
    private final ProfileDecisionRepository decisionRepository;
    private final ProfileEmotionRepository emotionRepository;
    private final ProfileFearRepository fearRepository;
    private final VectorStore vectorStore;

    public MemoryRetrievalService(
            ProfileValuesRepository valuesRepository,
            ProfileDecisionRepository decisionRepository,
            ProfileEmotionRepository emotionRepository,
            ProfileFearRepository fearRepository,
            @Autowired(required = false) @Nullable VectorStore vectorStore) {
        this.valuesRepository = valuesRepository;
        this.decisionRepository = decisionRepository;
        this.emotionRepository = emotionRepository;
        this.fearRepository = fearRepository;
        this.vectorStore = vectorStore;
    }

    public MemoryContext retrieve(Long userId, String query) {
        if (userId == null) {
            return emptyContext(false, true);
        }

        List<MemoryContext.ProfileMemory> values = new ArrayList<>();
        List<MemoryContext.ProfileMemory> emotions = new ArrayList<>();
        List<MemoryContext.DecisionMemory> decisions = new ArrayList<>();
        List<MemoryContext.ProfileMemory> fears = new ArrayList<>();
        boolean degraded = false;

        try {
            values = getValues(userId);
            emotions = getEmotions(userId);
            decisions = getDecisions(userId, query, DEFAULT_DECISION_LIMIT);
            fears = getFears(userId);
        } catch (Exception e) {
            degraded = true;
            logger.warn("长期记忆结构化画像召回失败, userId: {}", userId, e);
        }

        SemanticSearchResult semanticResult = searchSemanticMemories(userId, query, MAX_SECTION_ITEMS);
        degraded = degraded || semanticResult.degraded();

        String promptContext = buildPromptContext(values, emotions, decisions, fears, semanticResult.memories());
        MemoryContext.RetrievalMetrics metrics = new MemoryContext.RetrievalMetrics(
                values.size(),
                emotions.size(),
                decisions.size(),
                fears.size(),
                semanticResult.memories().size(),
                semanticResult.maxScore(),
                semanticResult.vectorAvailable(),
                degraded);

        logger.info("Memory RAG 召回完成, userId: {}, valueCount: {}, emotionCount: {}, decisionCount: {}, "
                        + "fearCount: {}, semanticHitCount: {}, maxSemanticScore: {}, degraded: {}",
                userId, values.size(), emotions.size(), decisions.size(), fears.size(),
                semanticResult.memories().size(), semanticResult.maxScore(), degraded);

        return new MemoryContext(values, emotions, decisions, fears, semanticResult.memories(), metrics, promptContext);
    }

    public SemanticSearchResult searchSemanticMemories(Long userId, String query, Integer topK) {
        if (userId == null || !StringUtils.hasText(query)) {
            return new SemanticSearchResult(List.of(), null, vectorStore != null, false);
        }
        if (vectorStore == null) {
            return new SemanticSearchResult(List.of(), null, false, true);
        }

        int safeTopK = normalizeTopK(topK);
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(safeTopK)
                    .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD)
                    .filterExpression("userId == '" + userId + "'")
                    .build();

            List<MemoryContext.SemanticMemory> memories = vectorStore.similaritySearch(searchRequest)
                    .stream()
                    .filter(Objects::nonNull)
                    .limit(safeTopK)
                    .map(this::toSemanticMemory)
                    .filter(memory -> StringUtils.hasText(memory.content()))
                    .toList();
            Double maxScore = memories.stream()
                    .map(MemoryContext.SemanticMemory::score)
                    .filter(Objects::nonNull)
                    .max(Double::compareTo)
                    .orElse(null);
            return new SemanticSearchResult(memories, maxScore, true, false);
        } catch (Exception e) {
            logger.warn("长期语义记忆召回失败, userId: {}", userId, e);
            return new SemanticSearchResult(List.of(), null, true, true);
        }
    }

    private List<MemoryContext.ProfileMemory> getValues(Long userId) {
        return valuesRepository.selectList(new LambdaQueryWrapper<ProfileValues>()
                        .eq(ProfileValues::getUserId, userId)
                        .orderByDesc(ProfileValues::getConfidence)
                        .orderByDesc(ProfileValues::getUpdatedAt)
                        .last("LIMIT " + MAX_SECTION_ITEMS))
                .stream()
                .filter(value -> Objects.equals(userId, value.getUserId()))
                .limit(MAX_SECTION_ITEMS)
                .map(value -> new MemoryContext.ProfileMemory(
                        clean(value.getItem()),
                        truncate(value.getPreference()),
                        "",
                        toDouble(value.getConfidence())))
                .filter(memory -> StringUtils.hasText(memory.subject()) || StringUtils.hasText(memory.content()))
                .toList();
    }

    private List<MemoryContext.ProfileMemory> getEmotions(Long userId) {
        return emotionRepository.selectList(new LambdaQueryWrapper<ProfileEmotion>()
                        .eq(ProfileEmotion::getUserId, userId)
                        .orderByDesc(ProfileEmotion::getUpdatedAt)
                        .last("LIMIT " + MAX_SECTION_ITEMS))
                .stream()
                .filter(emotion -> Objects.equals(userId, emotion.getUserId()))
                .limit(MAX_SECTION_ITEMS)
                .map(emotion -> new MemoryContext.ProfileMemory(
                        clean(emotion.getEmotion()),
                        truncate(emotion.getBehavior()),
                        truncate(emotion.getTriggerDesc()),
                        null))
                .filter(memory -> StringUtils.hasText(memory.subject()) || StringUtils.hasText(memory.content()))
                .toList();
    }

    private List<MemoryContext.DecisionMemory> getDecisions(Long userId, String query, int limit) {
        List<String> queryTokens = tokens(query);
        return decisionRepository.selectList(new LambdaQueryWrapper<ProfileDecision>()
                        .eq(ProfileDecision::getUserId, userId)
                        .last("LIMIT " + (limit * 3)))
                .stream()
                .filter(decision -> Objects.equals(userId, decision.getUserId()))
                .filter(decision -> queryTokens.isEmpty() || matchesDecision(decision, queryTokens))
                .limit(limit)
                .map(decision -> new MemoryContext.DecisionMemory(
                        clean(decision.getTopic()),
                        truncate(decision.getChoice()),
                        truncate(decision.getReason()),
                        truncate(decision.getOutcome()),
                        decision.getSatisfaction()))
                .filter(memory -> StringUtils.hasText(memory.topic()) || StringUtils.hasText(memory.choice()))
                .toList();
    }

    private List<MemoryContext.ProfileMemory> getFears(Long userId) {
        return fearRepository.selectList(new LambdaQueryWrapper<ProfileFear>()
                        .eq(ProfileFear::getUserId, userId)
                        .orderByDesc(ProfileFear::getConfidence)
                        .orderByDesc(ProfileFear::getUpdatedAt)
                        .last("LIMIT " + MAX_SECTION_ITEMS))
                .stream()
                .filter(fear -> Objects.equals(userId, fear.getUserId()))
                .limit(MAX_SECTION_ITEMS)
                .map(fear -> new MemoryContext.ProfileMemory(
                        clean(fear.getType()),
                        truncate(fear.getDescription()),
                        truncate(fear.getManifestation()),
                        toDouble(fear.getConfidence())))
                .filter(memory -> StringUtils.hasText(memory.subject()) || StringUtils.hasText(memory.content()))
                .toList();
    }

    private MemoryContext.SemanticMemory toSemanticMemory(Document document) {
        String content = document.getText();
        if (!StringUtils.hasText(content)) {
            content = document.getFormattedContent();
        }
        Object type = document.getMetadata().getOrDefault("type", "memory");
        Object recordCount = document.getMetadata().get("profileRecordCount");
        return new MemoryContext.SemanticMemory(
                truncate(content),
                type == null ? "memory" : type.toString(),
                toInteger(recordCount),
                document.getScore());
    }

    private String buildPromptContext(
            List<MemoryContext.ProfileMemory> values,
            List<MemoryContext.ProfileMemory> emotions,
            List<MemoryContext.DecisionMemory> decisions,
            List<MemoryContext.ProfileMemory> fears,
            List<MemoryContext.SemanticMemory> semanticMemories) {
        return """
                以下是系统检索到的用户长期记忆，仅作为参考，不代表用户当前最终意愿。
                结构化画像表示较稳定的长期结论；相关场景记忆表示相似经历和证据补充，不要把场景记忆当作新的画像结论。

                【稳定价值观】
                %s

                【情绪模式】
                %s

                【相似历史决策】
                %s

                【相关场景记忆】
                %s

                【恐惧与边界】
                %s
                """.formatted(
                formatProfileMemories(values),
                formatProfileMemories(emotions),
                formatDecisionMemories(decisions),
                formatSemanticMemories(semanticMemories),
                formatProfileMemories(fears));
    }

    private String formatProfileMemories(List<MemoryContext.ProfileMemory> memories) {
        if (memories.isEmpty()) {
            return "（暂无高相关记录）";
        }
        return memories.stream()
                .limit(MAX_SECTION_ITEMS)
                .map(memory -> {
                    String subject = clean(memory.subject());
                    String content = clean(memory.content());
                    String detail = clean(memory.detail());
                    String line = subject.isBlank() ? content : subject + "：" + content;
                    if (StringUtils.hasText(detail)) {
                        line += "（" + detail + "）";
                    }
                    return "- " + line;
                })
                .toList()
                .stream()
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String formatDecisionMemories(List<MemoryContext.DecisionMemory> memories) {
        if (memories.isEmpty()) {
            return "（暂无高相关记录）";
        }
        return memories.stream()
                .limit(MAX_SECTION_ITEMS)
                .map(memory -> {
                    String line = clean(memory.topic()) + "：" + clean(memory.choice());
                    if (StringUtils.hasText(memory.reason())) {
                        line += "，原因：" + clean(memory.reason());
                    }
                    return "- " + line;
                })
                .toList()
                .stream()
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String formatSemanticMemories(List<MemoryContext.SemanticMemory> memories) {
        if (memories.isEmpty()) {
            return "（暂无高相关记录）";
        }
        return memories.stream()
                .limit(MAX_SECTION_ITEMS)
                .map(memory -> "- " + clean(memory.content()))
                .toList()
                .stream()
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private MemoryContext emptyContext(boolean vectorAvailable, boolean degraded) {
        List<MemoryContext.ProfileMemory> values = List.of();
        List<MemoryContext.ProfileMemory> emotions = List.of();
        List<MemoryContext.DecisionMemory> decisions = List.of();
        List<MemoryContext.ProfileMemory> fears = List.of();
        List<MemoryContext.SemanticMemory> semanticMemories = List.of();
        return new MemoryContext(
                values,
                emotions,
                decisions,
                fears,
                semanticMemories,
                new MemoryContext.RetrievalMetrics(0, 0, 0, 0, 0, null, vectorAvailable, degraded),
                buildPromptContext(values, emotions, decisions, fears, semanticMemories));
    }

    private boolean matchesDecision(ProfileDecision decision, List<String> queryTokens) {
        String haystack = (clean(decision.getTopic()) + " "
                + clean(decision.getChoice()) + " "
                + clean(decision.getReason())).toLowerCase(Locale.ROOT);
        return queryTokens.stream().anyMatch(haystack::contains);
    }

    private List<String> tokens(String query) {
        String cleaned = clean(query).toLowerCase(Locale.ROOT);
        if (cleaned.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : cleaned.split("[\\s,，。！？、;；:：]+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        for (int i = 0; i < cleaned.length() - 1; i++) {
            char first = cleaned.charAt(i);
            char second = cleaned.charAt(i + 1);
            if (Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN
                    && Character.UnicodeScript.of(second) == Character.UnicodeScript.HAN) {
                tokens.add(cleaned.substring(i, i + 2));
            }
        }
        return tokens.stream()
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_DECISION_LIMIT;
        }
        return Math.min(topK, MAX_SECTION_ITEMS);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String value) {
        String cleaned = clean(value);
        if (cleaned.length() <= MAX_TEXT_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, MAX_TEXT_LENGTH);
    }

    private Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record SemanticSearchResult(
            List<MemoryContext.SemanticMemory> memories,
            Double maxScore,
            boolean vectorAvailable,
            boolean degraded) {

        public SemanticSearchResult {
            memories = List.copyOf(memories);
        }
    }
}
