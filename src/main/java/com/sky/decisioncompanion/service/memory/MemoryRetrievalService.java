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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final MemoryRetrievalIntentService intentService;
    private final MemoryAwarenessService awarenessService;

    public MemoryRetrievalService(
            ProfileValuesRepository valuesRepository,
            ProfileEmotionRepository emotionRepository,
            ProfileRelationshipRepository relationshipRepository,
            ProfileFearRepository fearRepository,
            ProfileSceneMemoryLinkRepository sceneMemoryLinkRepository,
            @Autowired(required = false) @Nullable VectorStore vectorStore,
            MemoryRetrievalProperties properties,
            DecisionRecallService decisionRecallService,
            MemoryRetrievalLogService retrievalLogService,
            MemoryRetrievalIntentService intentService,
            MemoryAwarenessService awarenessService) {
        this.valuesRepository = valuesRepository;
        this.emotionRepository = emotionRepository;
        this.relationshipRepository = relationshipRepository;
        this.fearRepository = fearRepository;
        this.sceneMemoryLinkRepository = sceneMemoryLinkRepository;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.decisionRecallService = decisionRecallService;
        this.retrievalLogService = retrievalLogService;
        this.intentService = intentService;
        this.awarenessService = awarenessService;
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
        MemoryRetrievalPlan plan = intentService.plan(query);

        try {
            values = getValues(userId, plan.valuesLimit());
            emotions = getEmotions(userId, plan.emotionsLimit());
            decisions = getDecisions(userId, query, plan.decisionsLimit());
            relationships = getRelationships(userId, plan.relationshipsLimit());
            fears = getFears(userId, plan.fearsLimit());
        } catch (Exception e) {
            degraded = true;
            logger.warn("长期记忆结构化画像召回失败, userId: {}", userId, e);
        }

        List<MemoryContext.ProfileMemory> coreProfiles = List.of();
        try {
            coreProfiles = getCoreProfiles(userId);
        } catch (Exception e) {
            degraded = true;
            logger.warn("核心稳定画像召回失败, userId: {}", userId, e);
        }

        List<MemoryContext.AwarenessMemory> awareness = List.of();
        try {
            awareness = awarenessService.findRecent(userId, properties.core().totalLimit())
                    .stream()
                    .map(note -> new MemoryContext.AwarenessMemory(
                            note.getAwareDate() == null ? "" : note.getAwareDate().toString(),
                            truncate(note.getObservation()),
                            truncate(note.getTrend()),
                            truncate(note.getEmotionGuess())))
                    .filter(memory -> StringUtils.hasText(memory.observation()))
                    .toList();
        } catch (Exception e) {
            degraded = true;
            logger.warn("近期觉察召回失败, userId: {}", userId, e);
        }

        SemanticSearchResult semanticResult = searchSemanticMemories(userId, query, plan, plan.semanticTopK());
        degraded = degraded || semanticResult.degraded();

        String promptContext = buildPromptContext(
                values,
                emotions,
                decisions,
                relationships,
                fears,
                coreProfiles,
                semanticResult.memories(),
                awareness,
                plan);
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

        logger.info("Memory RAG 召回完成, userId: {}, intent: {}, valueCount: {}, emotionCount: {}, decisionCount: {}, "
                + "relationshipCount: {}, fearCount: {}, coreCount: {}, awarenessCount: {}, semanticHitCount: {}, "
                + "maxSemanticScore: {}, degraded: {}",
                userId, plan.intent().code(), values.size(), emotions.size(), decisions.size(), relationships.size(),
                fears.size(), coreProfiles.size(), awareness.size(), semanticResult.memories().size(),
                semanticResult.maxScore(), degraded);

        MemoryContext context = new MemoryContext(
                values,
                emotions,
                decisions,
                relationships,
                fears,
                coreProfiles,
                semanticResult.memories(),
                awareness,
                metrics,
                promptContext);
        recordAutoRecall(userId, query, context, plan);
        return context;
    }

    public SemanticSearchResult searchSemanticMemories(Long userId, String query, Integer topK) {
        MemoryRetrievalPlan plan = intentService.plan(query);
        return searchSemanticMemories(userId, query, plan, properties.normalizeSemanticTopK(topK));
    }

    private SemanticSearchResult searchSemanticMemories(
            Long userId,
            String query,
            MemoryRetrievalPlan plan,
            int resultTopK) {
        if (userId == null || !StringUtils.hasText(query)) {
            return new SemanticSearchResult(List.of(), null, vectorStore != null, false);
        }
        if (vectorStore == null) {
            return new SemanticSearchResult(List.of(), null, false, true);
        }

        int safeResultTopK = Math.max(1, resultTopK);
        int candidateTopK = Math.max(safeResultTopK, plan.semanticCandidateTopK());
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(StringUtils.hasText(plan.semanticQuery()) ? plan.semanticQuery() : query)
                    .topK(candidateTopK)
                    .similarityThreshold(properties.semanticSimilarityThreshold())
                    .filterExpression("userId == '" + userId + "'")
                    .build();

            List<SemanticCandidate> candidates = semanticCandidates(vectorStore.similaritySearch(searchRequest), plan);
            Double maxScore = candidates.stream()
                    .map(candidate -> candidate.document().getScore())
                    .filter(Objects::nonNull)
                    .max(Double::compareTo)
                    .orElse(null);
            List<SemanticCandidate> ranked = chooseRerankPool(candidates, plan, safeResultTopK)
                    .stream()
                    .sorted(Comparator
                            .<SemanticCandidate>comparingDouble(candidate -> candidate.rerankScore().score())
                            .reversed()
                            .thenComparingInt(SemanticCandidate::originalIndex))
                    .toList();
            List<MemoryContext.SemanticMemory> memories = applyTypeQuota(ranked, safeResultTopK)
                    .stream()
                    .map(this::toSemanticMemory)
                    .filter(memory -> StringUtils.hasText(memory.content()))
                    .toList();
            return new SemanticSearchResult(memories, maxScore, true, false);
        } catch (Exception e) {
            logger.warn("长期语义记忆召回失败, userId: {}", userId, e);
            return new SemanticSearchResult(List.of(), null, true, true);
        }
    }

    private List<SemanticCandidate> semanticCandidates(List<Document> documents, MemoryRetrievalPlan plan) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<SemanticCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            if (document == null) {
                continue;
            }
            ProfileSceneMemoryLink link = findSceneMemoryLink(document);
            if (!isActiveSceneMemory(link)) {
                continue;
            }
            candidates.add(new SemanticCandidate(document, link, i, rerankScore(document, link, plan)));
        }
        return candidates;
    }

    private List<SemanticCandidate> chooseRerankPool(
            List<SemanticCandidate> candidates,
            MemoryRetrievalPlan plan,
            int resultTopK) {
        if (plan.preferredMemoryTypes().isEmpty()) {
            return candidates;
        }
        List<SemanticCandidate> preferred = candidates.stream()
                .filter(candidate -> isPreferredMemoryType(candidate.document(), plan))
                .toList();
        return preferred.size() >= resultTopK ? preferred : candidates;
    }

    /**
     * 召回多样性：按 memoryType 每类最多保留 {@code semanticTypeQuota} 条，避免同一类记忆刷屏。
     * 类型缺失（无 metadata）不参与配额；配额 0 表示不限。
     */
    private List<SemanticCandidate> applyTypeQuota(List<SemanticCandidate> ranked, int resultTopK) {
        int quota = properties.semanticTypeQuota();
        if (quota <= 0 || ranked.size() <= resultTopK) {
            return ranked;
        }
        Map<String, Integer> typeCounts = new HashMap<>();
        List<SemanticCandidate> result = new ArrayList<>();
        for (SemanticCandidate candidate : ranked) {
            String type = memoryType(candidate.document());
            boolean countable = StringUtils.hasText(type);
            int count = typeCounts.getOrDefault(type, 0);
            if (!countable || count < quota) {
                result.add(candidate);
                if (countable) {
                    typeCounts.put(type, count + 1);
                }
            }
            if (result.size() >= resultTopK) {
                break;
            }
        }
        return result;
    }

    private List<MemoryContext.ProfileMemory> getValues(Long userId, int limit) {
        return valuesRepository.selectList(new LambdaQueryWrapper<ProfileValues>()
                .eq(ProfileValues::getUserId, userId)
                .eq(ProfileValues::getActive, true)
                .orderByDesc(ProfileValues::getConfidence)
                .orderByDesc(ProfileValues::getUpdatedAt)
                .last("LIMIT " + limit))
                .stream()
                .filter(value -> Objects.equals(userId, value.getUserId()))
                .filter(value -> Boolean.TRUE.equals(value.getActive()))
                .limit(limit)
                .map(value -> new MemoryContext.ProfileMemory(
                        clean(value.getItem()),
                        truncate(value.getPreference()),
                        "",
                        toDouble(value.getConfidence())))
                .filter(memory -> StringUtils.hasText(memory.subject()) || StringUtils.hasText(memory.content()))
                .toList();
    }

    private List<MemoryContext.ProfileMemory> getEmotions(Long userId, int limit) {
        return emotionRepository.selectList(new LambdaQueryWrapper<ProfileEmotion>()
                .eq(ProfileEmotion::getUserId, userId)
                .eq(ProfileEmotion::getActive, true)
                .orderByDesc(ProfileEmotion::getUpdatedAt)
                .last("LIMIT " + limit))
                .stream()
                .filter(emotion -> Objects.equals(userId, emotion.getUserId()))
                .filter(emotion -> Boolean.TRUE.equals(emotion.getActive()))
                .limit(limit)
                .map(emotion -> new MemoryContext.ProfileMemory(
                        clean(emotion.getEmotion()),
                        truncate(emotion.getBehavior()),
                        truncate(emotion.getTriggerDesc()),
                        null))
                .filter(memory -> StringUtils.hasText(memory.subject()) || StringUtils.hasText(memory.content()))
                .toList();
    }

    private List<MemoryContext.DecisionMemory> getDecisions(Long userId, String query, int limit) {
        return decisionRecallService.recall(userId, query, limit)
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

    private List<MemoryContext.RelationshipMemory> getRelationships(Long userId, int limit) {
        return relationshipRepository.selectList(new LambdaQueryWrapper<ProfileRelationship>()
                .eq(ProfileRelationship::getUserId, userId)
                .eq(ProfileRelationship::getActive, true)
                .orderByDesc(ProfileRelationship::getUpdatedAt)
                .last("LIMIT " + limit))
                .stream()
                .filter(relationship -> Objects.equals(userId, relationship.getUserId()))
                .filter(relationship -> Boolean.TRUE.equals(relationship.getActive()))
                .limit(limit)
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

    private List<MemoryContext.ProfileMemory> getFears(Long userId, int limit) {
        return fearRepository.selectList(new LambdaQueryWrapper<ProfileFear>()
                .eq(ProfileFear::getUserId, userId)
                .eq(ProfileFear::getActive, true)
                .orderByDesc(ProfileFear::getConfidence)
                .orderByDesc(ProfileFear::getUpdatedAt)
                .last("LIMIT " + limit))
                .stream()
                .filter(fear -> Objects.equals(userId, fear.getUserId()))
                .filter(fear -> Boolean.TRUE.equals(fear.getActive()))
                .limit(limit)
                .map(fear -> new MemoryContext.ProfileMemory(
                        clean(fear.getType()),
                        truncate(fear.getDescription()),
                        truncate(fear.getManifestation()),
                        toDouble(fear.getConfidence())))
                .filter(memory -> StringUtils.hasText(memory.subject()) || StringUtils.hasText(memory.content()))
                .toList();
    }

    private MemoryContext.SemanticMemory toSemanticMemory(SemanticCandidate candidate) {
        Document document = candidate.document();
        String content = document.getText();
        if (!StringUtils.hasText(content)) {
            content = document.getFormattedContent();
        }
        Object recordCount = document.getMetadata().get("profileRecordCount");
        return new MemoryContext.SemanticMemory(
                truncate(content),
                StringUtils.hasText(memoryType(document)) ? memoryType(document) : "memory",
                toInteger(recordCount),
                document.getScore(),
                candidate.rerankScore().score(),
                candidate.rerankScore().reason());
    }

    private boolean isActiveSceneMemory(Document document) {
        return isActiveSceneMemory(findSceneMemoryLink(document));
    }

    private ProfileSceneMemoryLink findSceneMemoryLink(Document document) {
        if (document == null || !StringUtils.hasText(document.getId())) {
            return null;
        }
        return sceneMemoryLinkRepository
                .selectOne(new LambdaQueryWrapper<ProfileSceneMemoryLink>()
                        .eq(ProfileSceneMemoryLink::getDocumentId, document.getId()));
    }

    private boolean isActiveSceneMemory(@Nullable ProfileSceneMemoryLink link) {
        if (link == null) {
            return true;
        }
        return Boolean.TRUE.equals(link.getActive()) && "active".equals(link.getDeleteStatus());
    }

    private RerankScore rerankScore(Document document, @Nullable ProfileSceneMemoryLink link, MemoryRetrievalPlan plan) {
        double score = document.getScore() == null ? 0.0 : document.getScore();
        List<String> reasons = new ArrayList<>();
        reasons.add("vectorScore=" + formatScore(score));

        String memoryType = memoryType(document);
        double typeBoost = memoryTypeBoost(memoryType, plan);
        if (typeBoost > 0.0) {
            reasons.add("preferredType=" + memoryType + "+" + formatScore(typeBoost));
            score += typeBoost;
        }

        double confidenceBoost = confidenceBoost(document.getMetadata().get("confidence"));
        if (confidenceBoost > 0.0) {
            reasons.add("confidence+" + formatScore(confidenceBoost));
            score += confidenceBoost;
        }

        double recencyBoost = recencyBoost(document.getMetadata().get("createdAt"));
        if (recencyBoost > 0.0) {
            reasons.add("recency+" + formatScore(recencyBoost));
            score += recencyBoost;
        }

        if (link != null && Boolean.TRUE.equals(link.getActive()) && "active".equals(link.getDeleteStatus())) {
            double linkBoost = 0.08;
            reasons.add("linkedProfile+" + formatScore(linkBoost));
            score += linkBoost;
        }
        return new RerankScore(score, String.join(", ", reasons));
    }

    private double memoryTypeBoost(String memoryType, MemoryRetrievalPlan plan) {
        if (!StringUtils.hasText(memoryType) || plan.preferredMemoryTypes().isEmpty()) {
            return 0.0;
        }
        if (plan.preferredMemoryTypes().contains(memoryType)) {
            return 0.25;
        }
        if ("mixed".equals(memoryType)) {
            return 0.06;
        }
        return 0.0;
    }

    private double confidenceBoost(Object confidence) {
        Double value = toDouble(confidence);
        if (value == null) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(value, 1.0)) * 0.10;
    }

    private double recencyBoost(Object createdAt) {
        if (createdAt == null) {
            return 0.0;
        }
        try {
            LocalDateTime time = LocalDateTime.parse(createdAt.toString());
            long days = Duration.between(time, LocalDateTime.now()).toDays();
            if (days <= 30) {
                return 0.04;
            }
            if (days <= 180) {
                return 0.02;
            }
        } catch (DateTimeParseException e) {
            return 0.0;
        }
        return 0.0;
    }

    private boolean isPreferredMemoryType(Document document, MemoryRetrievalPlan plan) {
        String memoryType = memoryType(document);
        return StringUtils.hasText(memoryType) && plan.preferredMemoryTypes().contains(memoryType);
    }

    private String memoryType(Document document) {
        Object memoryType = document.getMetadata().get("memoryType");
        if (memoryType == null) {
            memoryType = document.getMetadata().get("type");
        }
        return memoryType == null ? "" : memoryType.toString();
    }

    /**
     * 生成易变（volatile）召回块：近期动态（Awareness）+ 动态意图区块（已与核心画像去重），
     * 供组装 user message 前置使用。核心稳定画像由 {@link #renderCoreSection} 单独渲染进 system prompt。
     */
    private String buildPromptContext(
            List<MemoryContext.ProfileMemory> values,
            List<MemoryContext.ProfileMemory> emotions,
            List<MemoryContext.DecisionMemory> decisions,
            List<MemoryContext.RelationshipMemory> relationships,
            List<MemoryContext.ProfileMemory> fears,
            List<MemoryContext.ProfileMemory> coreProfiles,
            List<MemoryContext.SemanticMemory> semanticMemories,
            List<MemoryContext.AwarenessMemory> awareness,
            MemoryRetrievalPlan plan) {
        Set<String> coreSubjects = coreSubjects(coreProfiles);
        String awarenessSection = formatAwarenessSection(awareness);
        String sections = plan.promptOrder()
                .stream()
                .map(section -> formatSection(
                        section, values, emotions, decisions, relationships, fears, semanticMemories, coreSubjects))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        String body = StringUtils.hasText(awarenessSection)
                ? awarenessSection + "\n\n" + sections
                : sections;
        return """
                以下是系统检索到的用户长期记忆，仅作为参考，不代表用户当前最终意愿。
                结构化画像表示较稳定的长期结论；相关场景记忆表示相似经历和证据补充，不要把场景记忆当作新的画像结论。

                %s
                """.formatted(body);
    }

    /** 渲染【近期动态】近因层：最近一段时间的觉察观察。 */
    private String formatAwarenessSection(List<MemoryContext.AwarenessMemory> awareness) {
        if (awareness.isEmpty()) {
            return "";
        }
        String lines = awareness.stream()
                .map(memory -> {
                    String prefix = StringUtils.hasText(memory.date()) ? "[" + memory.date() + "] " : "";
                    String line = prefix + clean(memory.observation());
                    if (StringUtils.hasText(memory.trend())) {
                        line += "（趋势：" + clean(memory.trend()) + "）";
                    }
                    if (StringUtils.hasText(memory.emotionGuess())) {
                        line += "（情绪：" + clean(memory.emotionGuess()) + "）";
                    }
                    return "- " + line;
                })
                .toList()
                .stream()
                .collect(java.util.stream.Collectors.joining("\n"));
        return "【近期动态】\n" + lines;
    }

    /**
     * 渲染【核心稳定画像（始终参考）】稳定块：高置信价值观/恐惧边界，与意图无关恒定注入 system prompt，
     * 作为稳定前缀利于 provider prompt cache 命中。
     */
    public String renderCoreSection(List<MemoryContext.ProfileMemory> coreProfiles) {
        if (coreProfiles == null || coreProfiles.isEmpty()) {
            return "【核心稳定画像（始终参考）】\n（暂无稳定画像结论）";
        }
        String lines = coreProfiles.stream()
                .limit(properties.core().totalLimit())
                .map(memory -> {
                    String subject = clean(memory.subject());
                    String content = clean(memory.content());
                    String line = subject.isBlank() ? content : subject + "：" + content;
                    if (memory.confidence() != null) {
                        line += "（置信度 " + formatScore(memory.confidence()) + "）";
                    }
                    return "- " + line;
                })
                .toList()
                .stream()
                .collect(java.util.stream.Collectors.joining("\n"));
        return "【核心稳定画像（始终参考）】\n" + lines;
    }

    private String formatSection(
            MemoryRetrievalPlan.Section section,
            List<MemoryContext.ProfileMemory> values,
            List<MemoryContext.ProfileMemory> emotions,
            List<MemoryContext.DecisionMemory> decisions,
            List<MemoryContext.RelationshipMemory> relationships,
            List<MemoryContext.ProfileMemory> fears,
            List<MemoryContext.SemanticMemory> semanticMemories,
            Set<String> coreSubjects) {
        return switch (section) {
            case VALUES -> "【稳定价值观】\n" + formatProfileMemories(filterOutCore(values, coreSubjects));
            case EMOTIONS -> "【情绪模式】\n" + formatProfileMemories(emotions);
            case DECISIONS -> "【相似历史决策】\n" + formatDecisionMemories(decisions);
            case RELATIONSHIPS -> "【关系影响】\n" + formatRelationshipMemories(relationships);
            case SEMANTIC_MEMORIES -> "【相关场景记忆】\n" + formatSemanticMemories(semanticMemories);
            case FEARS -> "【恐惧与边界】\n" + formatProfileMemories(filterOutCore(fears, coreSubjects));
        };
    }

    /**
     * 恒定注入的高置信稳定画像：价值观 + 恐惧/边界（仅这两类画像存有 confidence）。
     */
    private List<MemoryContext.ProfileMemory> getCoreProfiles(Long userId) {
        double threshold = properties.core().confidenceThreshold();
        int valueLimit = properties.core().valueLimit();
        int fearLimit = properties.core().fearLimit();
        int totalLimit = properties.core().totalLimit();
        List<MemoryContext.ProfileMemory> core = new ArrayList<>();

        if (valueLimit > 0) {
            List<MemoryContext.ProfileMemory> coreValues = valuesRepository.selectList(
                            new LambdaQueryWrapper<ProfileValues>()
                                    .eq(ProfileValues::getUserId, userId)
                                    .eq(ProfileValues::getActive, true)
                                    .ge(ProfileValues::getConfidence, threshold)
                                    .orderByDesc(ProfileValues::getConfidence)
                                    .orderByDesc(ProfileValues::getUpdatedAt)
                                    .last("LIMIT " + valueLimit))
                    .stream()
                    .filter(value -> Objects.equals(userId, value.getUserId()))
                    .filter(value -> Boolean.TRUE.equals(value.getActive()))
                    .filter(value -> value.getConfidence() != null
                            && value.getConfidence().doubleValue() >= threshold)
                    .limit(valueLimit)
                    .map(value -> new MemoryContext.ProfileMemory(
                            clean(value.getItem()),
                            truncate(value.getPreference()),
                            "",
                            toDouble(value.getConfidence())))
                    .filter(memory -> StringUtils.hasText(memory.subject()) || StringUtils.hasText(memory.content()))
                    .toList();
            core.addAll(coreValues);
        }

        if (fearLimit > 0 && core.size() < totalLimit) {
            int remainingFearLimit = Math.min(fearLimit, totalLimit - core.size());
            List<MemoryContext.ProfileMemory> coreFears = fearRepository.selectList(
                            new LambdaQueryWrapper<ProfileFear>()
                                    .eq(ProfileFear::getUserId, userId)
                                    .eq(ProfileFear::getActive, true)
                                    .ge(ProfileFear::getConfidence, threshold)
                                    .orderByDesc(ProfileFear::getConfidence)
                                    .orderByDesc(ProfileFear::getUpdatedAt)
                                    .last("LIMIT " + remainingFearLimit))
                    .stream()
                    .filter(fear -> Objects.equals(userId, fear.getUserId()))
                    .filter(fear -> Boolean.TRUE.equals(fear.getActive()))
                    .filter(fear -> fear.getConfidence() != null
                            && fear.getConfidence().doubleValue() >= threshold)
                    .limit(remainingFearLimit)
                    .map(fear -> new MemoryContext.ProfileMemory(
                            clean(fear.getType()),
                            truncate(fear.getDescription()),
                            truncate(fear.getManifestation()),
                            toDouble(fear.getConfidence())))
                    .filter(memory -> StringUtils.hasText(memory.subject()) || StringUtils.hasText(memory.content()))
                    .toList();
            core.addAll(coreFears);
        }

        return core.stream().limit(totalLimit).toList();
    }

    /** 取核心画像的规范化 subject 集合，用于动态区块去重。 */
    private Set<String> coreSubjects(List<MemoryContext.ProfileMemory> coreProfiles) {
        if (coreProfiles.isEmpty()) {
            return Set.of();
        }
        Set<String> subjects = new HashSet<>();
        for (MemoryContext.ProfileMemory memory : coreProfiles) {
            subjects.add(normalizeKey(memory.subject()));
        }
        return subjects;
    }

    private List<MemoryContext.ProfileMemory> filterOutCore(
            List<MemoryContext.ProfileMemory> memories,
            Set<String> coreSubjects) {
        if (coreSubjects.isEmpty() || memories.isEmpty()) {
            return memories;
        }
        return memories.stream()
                .filter(memory -> !coreSubjects.contains(normalizeKey(memory.subject())))
                .toList();
    }

    private String normalizeKey(String value) {
        return clean(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
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
        List<MemoryContext.ProfileMemory> coreProfiles = List.of();
        List<MemoryContext.SemanticMemory> semanticMemories = List.of();
        List<MemoryContext.AwarenessMemory> awareness = List.of();
        return new MemoryContext(
                values,
                emotions,
                decisions,
                relationships,
                fears,
                coreProfiles,
                semanticMemories,
                awareness,
                new MemoryContext.RetrievalMetrics(0, 0, 0, 0, 0, 0, null, vectorAvailable, degraded),
                buildPromptContext(
                        values,
                        emotions,
                        decisions,
                        relationships,
                        fears,
                        coreProfiles,
                        semanticMemories,
                        awareness,
                        intentService.plan("")));
    }

    private void recordAutoRecall(Long userId, String query, MemoryContext context, MemoryRetrievalPlan plan) {
        try {
            retrievalLogService.recordAutoRecall(
                    userId,
                    query,
                    context,
                    plan,
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

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
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

    private String formatScore(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
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

    private record SemanticCandidate(
            Document document,
            @Nullable ProfileSceneMemoryLink link,
            int originalIndex,
            RerankScore rerankScore) {
    }

    private record RerankScore(double score, String reason) {
    }
}
