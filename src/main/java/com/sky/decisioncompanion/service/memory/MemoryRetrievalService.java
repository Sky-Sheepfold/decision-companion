package com.sky.decisioncompanion.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.config.MemoryRetrievalProperties;
import com.sky.decisioncompanion.model.ProfileEmotion;
import com.sky.decisioncompanion.model.ProfileFear;
import com.sky.decisioncompanion.model.ProfileRelationship;
import com.sky.decisioncompanion.model.ProfileSceneMemoryLink;
import com.sky.decisioncompanion.model.ProfileValues;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileSceneMemoryLinkRepository;
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
import java.util.List;
import java.util.Objects;

@Service
public class MemoryRetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryRetrievalService.class);

    private final ProfileValuesRepository valuesRepository;
    private final ProfileEmotionRepository emotionRepository;
    private final ProfileRelationshipRepository relationshipRepository;
    private final ProfileFearRepository fearRepository;
    private final ProfileSceneMemoryLinkRepository sceneMemoryLinkRepository;
    private final VectorStore vectorStore;
    private final MemoryRetrievalProperties properties;
    private final DecisionRecallService decisionRecallService;
    private final MemoryRetrievalLogService retrievalLogService;

    public MemoryRetrievalService(
            ProfileValuesRepository valuesRepository,
            ProfileEmotionRepository emotionRepository,
            ProfileRelationshipRepository relationshipRepository,
            ProfileFearRepository fearRepository,
            ProfileSceneMemoryLinkRepository sceneMemoryLinkRepository,
            @Autowired(required = false) @Nullable VectorStore vectorStore,
            MemoryRetrievalProperties properties,
            DecisionRecallService decisionRecallService,
            MemoryRetrievalLogService retrievalLogService) {
        this.valuesRepository = valuesRepository;
        this.emotionRepository = emotionRepository;
        this.relationshipRepository = relationshipRepository;
        this.fearRepository = fearRepository;
        this.sceneMemoryLinkRepository = sceneMemoryLinkRepository;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.decisionRecallService = decisionRecallService;
        this.retrievalLogService = retrievalLogService;
    }

    public MemoryContext retrieve(Long userId, String query) {
        if (userId == null) {
            return emptyContext(false, true);
        }

        List<MemoryContext.ProfileMemory> values = new ArrayList<>();
        List<MemoryContext.ProfileMemory> emotions = new ArrayList<>();
        List<MemoryContext.DecisionMemory> decisions = new ArrayList<>();
        List<MemoryContext.RelationshipMemory> relationships = new ArrayList<>();
        List<MemoryContext.ProfileMemory> fears = new ArrayList<>();
        boolean degraded = false;

        try {
            values = getValues(userId);
            emotions = getEmotions(userId);
            decisions = getDecisions(userId, query);
            relationships = getRelationships(userId);
            fears = getFears(userId);
        } catch (Exception e) {
            degraded = true;
            logger.warn("长期记忆结构化画像召回失败, userId: {}", userId, e);
        }

        SemanticSearchResult semanticResult = searchSemanticMemories(userId, query, properties.semanticTopK());
        degraded = degraded || semanticResult.degraded();

        String promptContext = buildPromptContext(
                values,
                emotions,
                decisions,
                relationships,
                fears,
                semanticResult.memories());
        MemoryContext.RetrievalMetrics metrics = new MemoryContext.RetrievalMetrics(
                values.size(),
                emotions.size(),
                decisions.size(),
                relationships.size(),
                fears.size(),
                semanticResult.memories().size(),
                semanticResult.maxScore(),
                semanticResult.vectorAvailable(),
                degraded);

        logger.info("Memory RAG 召回完成, userId: {}, valueCount: {}, emotionCount: {}, decisionCount: {}, "
                + "relationshipCount: {}, fearCount: {}, semanticHitCount: {}, maxSemanticScore: {}, degraded: {}",
                userId, values.size(), emotions.size(), decisions.size(), relationships.size(), fears.size(),
                semanticResult.memories().size(), semanticResult.maxScore(), degraded);

        MemoryContext context = new MemoryContext(
                values,
                emotions,
                decisions,
                relationships,
                fears,
                semanticResult.memories(),
                metrics,
                promptContext);
        recordAutoRecall(userId, query, context);
        return context;
    }

    public SemanticSearchResult searchSemanticMemories(Long userId, String query, Integer topK) {
        if (userId == null || !StringUtils.hasText(query)) {
            return new SemanticSearchResult(List.of(), null, vectorStore != null, false);
        }
        if (vectorStore == null) {
            return new SemanticSearchResult(List.of(), null, false, true);
        }

        int safeTopK = properties.normalizeSemanticTopK(topK);
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(safeTopK)
                    .similarityThreshold(properties.semanticSimilarityThreshold())
                    .filterExpression("userId == '" + userId + "'")
                    .build();

            List<MemoryContext.SemanticMemory> memories = vectorStore.similaritySearch(searchRequest)
                    .stream()
                    .filter(Objects::nonNull)
                    .filter(this::isActiveSceneMemory)
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
                .eq(ProfileValues::getActive, true)
                .orderByDesc(ProfileValues::getConfidence)
                .orderByDesc(ProfileValues::getUpdatedAt)
                .last("LIMIT " + properties.sectionLimit()))
                .stream()
                .filter(value -> Objects.equals(userId, value.getUserId()))
                .filter(value -> Boolean.TRUE.equals(value.getActive()))
                .limit(properties.sectionLimit())
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
                .eq(ProfileEmotion::getActive, true)
                .orderByDesc(ProfileEmotion::getUpdatedAt)
                .last("LIMIT " + properties.sectionLimit()))
                .stream()
                .filter(emotion -> Objects.equals(userId, emotion.getUserId()))
                .filter(emotion -> Boolean.TRUE.equals(emotion.getActive()))
                .limit(properties.sectionLimit())
                .map(emotion -> new MemoryContext.ProfileMemory(
                        clean(emotion.getEmotion()),
                        truncate(emotion.getBehavior()),
                        truncate(emotion.getTriggerDesc()),
                        null))
                .filter(memory -> StringUtils.hasText(memory.subject()) || StringUtils.hasText(memory.content()))
                .toList();
    }

    private List<MemoryContext.DecisionMemory> getDecisions(Long userId, String query) {
        return decisionRecallService.recall(userId, query, properties.decision().defaultLimit())
                .items()
                .stream()
                .map(this::toDecisionMemory)
                .toList();
    }

    private MemoryContext.DecisionMemory toDecisionMemory(DecisionRecallService.DecisionRecallItem decision) {
        return new MemoryContext.DecisionMemory(
                clean(decision.topic()),
                truncate(decision.choice()),
                truncate(decision.reason()),
                truncate(decision.outcome()),
                decision.satisfaction());
    }

    private List<MemoryContext.RelationshipMemory> getRelationships(Long userId) {
        return relationshipRepository.selectList(new LambdaQueryWrapper<ProfileRelationship>()
                .eq(ProfileRelationship::getUserId, userId)
                .eq(ProfileRelationship::getActive, true)
                .orderByDesc(ProfileRelationship::getUpdatedAt)
                .last("LIMIT " + properties.sectionLimit()))
                .stream()
                .filter(relationship -> Objects.equals(userId, relationship.getUserId()))
                .filter(relationship -> Boolean.TRUE.equals(relationship.getActive()))
                .limit(properties.sectionLimit())
                .map(relationship -> new MemoryContext.RelationshipMemory(
                        clean(relationship.getName()),
                        truncate(relationship.getRole()),
                        truncate(relationship.getInfluenceLevel()),
                        truncate(relationship.getInfluenceStyle()),
                        truncate(relationship.getNote())))
                .filter(memory -> StringUtils.hasText(memory.name())
                        || StringUtils.hasText(memory.note())
                        || StringUtils.hasText(memory.influenceStyle()))
                .toList();
    }

    private List<MemoryContext.ProfileMemory> getFears(Long userId) {
        return fearRepository.selectList(new LambdaQueryWrapper<ProfileFear>()
                .eq(ProfileFear::getUserId, userId)
                .eq(ProfileFear::getActive, true)
                .orderByDesc(ProfileFear::getConfidence)
                .orderByDesc(ProfileFear::getUpdatedAt)
                .last("LIMIT " + properties.sectionLimit()))
                .stream()
                .filter(fear -> Objects.equals(userId, fear.getUserId()))
                .filter(fear -> Boolean.TRUE.equals(fear.getActive()))
                .limit(properties.sectionLimit())
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

    private boolean isActiveSceneMemory(Document document) {
        if (!StringUtils.hasText(document.getId())) {
            return true;
        }
        ProfileSceneMemoryLink link = sceneMemoryLinkRepository
                .selectOne(new LambdaQueryWrapper<ProfileSceneMemoryLink>()
                        .eq(ProfileSceneMemoryLink::getDocumentId, document.getId()));
        if (link == null) {
            return true;
        }
        return Boolean.TRUE.equals(link.getActive()) && "active".equals(link.getDeleteStatus());
    }

    private String buildPromptContext(
            List<MemoryContext.ProfileMemory> values,
            List<MemoryContext.ProfileMemory> emotions,
            List<MemoryContext.DecisionMemory> decisions,
            List<MemoryContext.RelationshipMemory> relationships,
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

                【关系影响】
                %s

                【相关场景记忆】
                %s

                【恐惧与边界】
                %s
                """.formatted(
                formatProfileMemories(values),
                formatProfileMemories(emotions),
                formatDecisionMemories(decisions),
                formatRelationshipMemories(relationships),
                formatSemanticMemories(semanticMemories),
                formatProfileMemories(fears));
    }

    private String formatProfileMemories(List<MemoryContext.ProfileMemory> memories) {
        if (memories.isEmpty()) {
            return "（暂无高相关记录）";
        }
        return memories.stream()
                .limit(properties.sectionLimit())
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
                .limit(properties.sectionLimit())
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

    private String formatRelationshipMemories(List<MemoryContext.RelationshipMemory> memories) {
        if (memories.isEmpty()) {
            return "（暂无高相关记录）";
        }
        return memories.stream()
                .limit(properties.sectionLimit())
                .map(memory -> {
                    String line = clean(memory.name());
                    if (StringUtils.hasText(memory.role())) {
                        line += "（" + clean(memory.role()) + "）";
                    }
                    List<String> details = new ArrayList<>();
                    if (StringUtils.hasText(memory.influenceLevel())) {
                        details.add("影响力" + clean(memory.influenceLevel()));
                    }
                    if (StringUtils.hasText(memory.influenceStyle())) {
                        details.add("影响方式：" + clean(memory.influenceStyle()));
                    }
                    if (StringUtils.hasText(memory.note())) {
                        details.add("备注：" + clean(memory.note()));
                    }
                    if (!details.isEmpty()) {
                        line += "：" + String.join("，", details);
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
                .limit(properties.sectionLimit())
                .map(memory -> "- " + clean(memory.content()))
                .toList()
                .stream()
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private MemoryContext emptyContext(boolean vectorAvailable, boolean degraded) {
        List<MemoryContext.ProfileMemory> values = List.of();
        List<MemoryContext.ProfileMemory> emotions = List.of();
        List<MemoryContext.DecisionMemory> decisions = List.of();
        List<MemoryContext.RelationshipMemory> relationships = List.of();
        List<MemoryContext.ProfileMemory> fears = List.of();
        List<MemoryContext.SemanticMemory> semanticMemories = List.of();
        return new MemoryContext(
                values,
                emotions,
                decisions,
                relationships,
                fears,
                semanticMemories,
                new MemoryContext.RetrievalMetrics(0, 0, 0, 0, 0, 0, null, vectorAvailable, degraded),
                buildPromptContext(values, emotions, decisions, relationships, fears, semanticMemories));
    }

    private void recordAutoRecall(Long userId, String query, MemoryContext context) {
        try {
            retrievalLogService.recordAutoRecall(
                    userId,
                    query,
                    context,
                    properties.semanticTopK(),
                    properties.semanticSimilarityThreshold());
        } catch (Exception e) {
            logger.warn("Memory RAG 召回日志服务异常, userId: {}", userId, e);
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String value) {
        String cleaned = clean(value);
        if (cleaned.length() <= properties.textMaxLength()) {
            return cleaned;
        }
        return cleaned.substring(0, properties.textMaxLength());
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
