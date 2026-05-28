package com.sky.decisioncompanion.service.agenttool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.model.ProfileEmotion;
import com.sky.decisioncompanion.model.ProfileFear;
import com.sky.decisioncompanion.model.ProfileRelationship;
import com.sky.decisioncompanion.model.ProfileValues;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import com.sky.decisioncompanion.service.memory.MemoryContext;
import com.sky.decisioncompanion.service.memory.MemoryRetrievalService;
import com.sky.decisioncompanion.service.profile.ProfileWritePolicy;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class DecisionAgentToolService {

    private static final Logger logger = LoggerFactory.getLogger(DecisionAgentToolService.class);
    private static final int DEFAULT_LIMIT = 3;
    private static final int MAX_LIMIT = 5;
    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 5;
    private final ProfileDecisionRepository decisionRepository;
    private final ProfileValuesRepository valuesRepository;
    private final ProfileEmotionRepository emotionRepository;
    private final ProfileRelationshipRepository relationshipRepository;
    private final ProfileFearRepository fearRepository;
    private final MemoryRetrievalService memoryRetrievalService;
    private final AgentToolCallLogService logService;
    private final AgentToolInvocationTracker invocationTracker;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DecisionAgentToolService(
            ProfileDecisionRepository decisionRepository,
            ProfileValuesRepository valuesRepository,
            ProfileEmotionRepository emotionRepository,
            ProfileRelationshipRepository relationshipRepository,
            ProfileFearRepository fearRepository,
            MemoryRetrievalService memoryRetrievalService,
            AgentToolCallLogService logService,
            AgentToolInvocationTracker invocationTracker) {
        this.decisionRepository = decisionRepository;
        this.valuesRepository = valuesRepository;
        this.emotionRepository = emotionRepository;
        this.relationshipRepository = relationshipRepository;
        this.fearRepository = fearRepository;
        this.memoryRetrievalService = memoryRetrievalService;
        this.logService = logService;
        this.invocationTracker = invocationTracker;
    }

    @Tool(name = "searchDecisionHistory", description = "查询当前用户的历史决策记录，用于识别相似选择和决策模式。该工具只读，不会写入或修改任何档案。")
    public DecisionHistoryToolResult searchDecisionHistory(
            @ToolParam(description = "与当前决策相关的查询文本") String query,
            @ToolParam(required = false, description = "最多返回多少条结果，默认 3 条，最多 5 条") Integer limit,
            ToolContext toolContext) {
        long start = System.nanoTime();
        AgentToolContext.Execution context = AgentToolContext.from(toolContext);
        markToolCalled(context, "searchDecisionHistory");
        String inputSummary = "query=" + query + ", limit=" + limit;

        try {
            int safeLimit = normalizeLimit(limit);
            List<String> queryTokens = tokens(query);
            List<DecisionHistoryItem> items = decisionRepository.selectList(
                            new LambdaQueryWrapper<ProfileDecision>()
                                    .eq(ProfileDecision::getUserId, context.userId())
                                    .last("LIMIT " + (safeLimit * 3)))
                    .stream()
                    .filter(decision -> Objects.equals(context.userId(), decision.getUserId()))
                    .filter(decision -> matchesDecision(decision, queryTokens))
                    .limit(safeLimit)
                    .map(this::toDecisionHistoryItem)
                    .toList();

            DecisionHistoryToolResult result = new DecisionHistoryToolResult(
                    true,
                    items.isEmpty() ? "未找到相似历史决策" : "找到相似历史决策",
                    items);
            logService.recordSuccess(context.userId(), context.conversationId(), "searchDecisionHistory",
                    inputSummary, summarizeDecisions(items), elapsedMillis(start));
            return result;
        } catch (Exception e) {
            logService.recordFailure(context.userId(), context.conversationId(), "searchDecisionHistory",
                    inputSummary, failureMessage("查询历史决策", e), elapsedMillis(start));
            return new DecisionHistoryToolResult(false, "暂时无法查询历史决策", List.of());
        }
    }

    @Tool(name = "searchSemanticMemory", description = "从向量存储中检索当前用户的长期场景记忆，用于补充结构化画像之外的相似经历和证据。该工具只读，不会写入或修改任何档案。")
    public SemanticMemoryToolResult searchSemanticMemory(
            @ToolParam(description = "当前决策、复盘或困惑的查询文本") String query,
            @ToolParam(required = false, description = "最多返回多少条语义记忆，默认 3 条，最多 5 条") Integer topK,
            ToolContext toolContext) {
        long start = System.nanoTime();
        AgentToolContext.Execution context = AgentToolContext.from(toolContext);
        markToolCalled(context, "searchSemanticMemory");
        String inputSummary = "query=" + query + ", topK=" + topK;

        try {
            int safeTopK = normalizeLimit(topK);
            MemoryRetrievalService.SemanticSearchResult semanticResult =
                    memoryRetrievalService.searchSemanticMemories(context.userId(), query, safeTopK);
            if (!semanticResult.vectorAvailable()) {
                logService.recordSkipped(context.userId(), context.conversationId(), "searchSemanticMemory",
                        inputSummary, "向量存储服务暂不可用", elapsedMillis(start));
                return new SemanticMemoryToolResult(false, "向量存储服务暂不可用", List.of());
            }
            if (semanticResult.degraded()) {
                logService.recordFailure(context.userId(), context.conversationId(), "searchSemanticMemory",
                        inputSummary, "查询长期语义记忆失败", elapsedMillis(start));
                return new SemanticMemoryToolResult(false, "暂时无法查询长期语义记忆", List.of());
            }
            List<SemanticMemoryItem> memories = semanticResult.memories()
                    .stream()
                    .limit(safeTopK)
                    .map(this::toSemanticMemoryItem)
                    .toList();
            SemanticMemoryToolResult result = new SemanticMemoryToolResult(
                    true,
                    memories.isEmpty() ? "未找到相关长期场景记忆" : "找到相关长期场景记忆",
                    memories);
            logService.recordSuccess(context.userId(), context.conversationId(), "searchSemanticMemory",
                    inputSummary, summarizeMemories(memories), elapsedMillis(start));
            return result;
        } catch (Exception e) {
            logService.recordFailure(context.userId(), context.conversationId(), "searchSemanticMemory",
                    inputSummary, failureMessage("查询长期语义记忆", e), elapsedMillis(start));
            return new SemanticMemoryToolResult(false, "暂时无法查询长期语义记忆", List.of());
        }
    }

    @Tool(name = "generateDecisionMatrix", description = "根据用户给出的选项和比较维度生成决策矩阵，帮助把纠结拆成可讨论的结构化维度。该工具只做分析，不会写入档案或保存决策记录。")
    public DecisionMatrixToolResult generateDecisionMatrix(
            @ToolParam(description = "决策主题") String topic,
            @ToolParam(description = "用户正在比较的选项列表") List<String> options,
            @ToolParam(description = "用于比较这些选项的决策维度列表") List<String> dimensions,
            ToolContext toolContext) {
        long start = System.nanoTime();
        AgentToolContext.Execution context = AgentToolContext.from(toolContext);
        markToolCalled(context, "generateDecisionMatrix");
        String inputSummary = "topic=" + topic + ", options=" + options + ", dimensions=" + dimensions;

        try {
            List<String> safeOptions = safeList(options, 5);
            List<String> safeDimensions = safeList(dimensions, 8);
            List<DecisionMatrixOption> matrix = safeOptions.stream()
                    .map(option -> toDecisionMatrixOption(topic, option, safeDimensions))
                    .toList();
            DecisionMatrixToolResult result = new DecisionMatrixToolResult(true, "已生成决策矩阵", matrix);
            logService.recordSuccess(context.userId(), context.conversationId(), "generateDecisionMatrix",
                    inputSummary, summarizeMatrix(matrix), elapsedMillis(start));
            return result;
        } catch (Exception e) {
            logService.recordFailure(context.userId(), context.conversationId(), "generateDecisionMatrix",
                    inputSummary, failureMessage("生成决策矩阵", e), elapsedMillis(start));
            return new DecisionMatrixToolResult(false, "暂时无法生成决策矩阵", List.of());
        }
    }

    @Tool(name = "updateUserProfile", description = "根据 Agent 判断更新当前用户画像。支持 value、emotion、relationship、fear、boundary 类型。高置信度自动写入，中置信度返回 needs_confirmation，低置信度跳过。")
    public UpdateUserProfileToolResult updateUserProfile(
            @ToolParam(description = "画像类型：value/emotion/relationship/fear/boundary") String profileType,
            @ToolParam(description = "画像主体。value 为价值维度，emotion 为情绪名，relationship 为关系人，fear/boundary 为描述") String subject,
            @ToolParam(description = "画像内容。value 为倾向描述，emotion 为行为表现，relationship 为备注，fear/boundary 为表现形式") String content,
            @ToolParam(required = false, description = "补充字段。emotion 可传触发场景，relationship 可传角色，boundary 可传 hard/soft") String detail,
            @ToolParam(required = false, description = "置信度，0 到 1。高置信度才自动写入；不传按低置信度处理") Double confidence,
            @ToolParam(required = false, description = "来自用户原话的证据短句") List<String> evidence,
            ToolContext toolContext) {
        long start = System.nanoTime();
        AgentToolContext.Execution context = AgentToolContext.from(toolContext);
        markToolCalled(context, "updateUserProfile");
        String inputSummary = "profileType=" + profileType + ", subject=" + subject + ", detail=" + detail;
        logger.info("Agent Tool 触发: updateUserProfile, userId: {}, conversationId: {}, requestId: {}, inputSummary: {}",
                context.userId(), context.conversationId(), context.requestId(), inputSummary);

        try {
            String normalizedType = normalizeProfileType(profileType);
            if (!StringUtils.hasText(normalizedType)) {
                String message = "暂不支持该画像类型";
                logService.recordSkipped(context.userId(), context.conversationId(), "updateUserProfile",
                        inputSummary, message, elapsedMillis(start));
                logger.info("Agent Tool 跳过: updateUserProfile, userId: {}, conversationId: {}, reason: {}",
                        context.userId(), context.conversationId(), message);
                return new UpdateUserProfileToolResult(false, message, "", clean(subject), "skipped");
            }
            if (!StringUtils.hasText(subject) || !StringUtils.hasText(content)) {
                String message = "画像主体和内容不能为空";
                logService.recordSkipped(context.userId(), context.conversationId(), "updateUserProfile",
                        inputSummary, message, elapsedMillis(start));
                logger.info("Agent Tool 跳过: updateUserProfile, userId: {}, conversationId: {}, profileType: {}, reason: {}",
                        context.userId(), context.conversationId(), normalizedType, message);
                return new UpdateUserProfileToolResult(false, message, normalizedType, clean(subject), "skipped");
            }

            ProfileWriteDecision writeDecision = decideProfileWrite(normalizedType, confidence);
            if (!writeDecision.writable()) {
                logService.recordSkipped(context.userId(), context.conversationId(), "updateUserProfile",
                        inputSummary, writeDecision.logSummary(), elapsedMillis(start));
                logger.info("Agent Tool 未写入画像: updateUserProfile, userId: {}, conversationId: {}, profileType: {}, subject: {}, action: {}, reason: {}",
                        context.userId(), context.conversationId(), normalizedType, clean(subject),
                        writeDecision.action(), writeDecision.logSummary());
                return new UpdateUserProfileToolResult(
                        false, writeDecision.message(), normalizedType, clean(subject), writeDecision.action());
            }

            UpdateOutcome outcome = switch (normalizedType) {
                case "value" -> upsertValueProfile(context.userId(), subject, content, confidence, evidence);
                case "emotion" -> upsertEmotionProfile(context.userId(), subject, content, detail, evidence);
                case "relationship" -> upsertRelationshipProfile(context.userId(), subject, content, detail);
                case "fear", "boundary" -> upsertFearProfile(
                        context.userId(), normalizedType, subject, content, detail, confidence, evidence);
                default -> new UpdateOutcome(false, "暂不支持该画像类型", "skipped", clean(subject));
            };

            if (outcome.updated()) {
                logService.recordSuccess(context.userId(), context.conversationId(), "updateUserProfile",
                        inputSummary, "written " + normalizedType + ":" + outcome.subject(),
                        elapsedMillis(start));
                logger.info("Agent Tool 已写入画像: updateUserProfile, userId: {}, conversationId: {}, profileType: {}, subject: {}, action: {}",
                        context.userId(), context.conversationId(), normalizedType, outcome.subject(), outcome.action());
            } else {
                logService.recordSkipped(context.userId(), context.conversationId(), "updateUserProfile",
                        inputSummary, outcome.message(), elapsedMillis(start));
                logger.info("Agent Tool 未写入画像: updateUserProfile, userId: {}, conversationId: {}, profileType: {}, subject: {}, action: {}, reason: {}",
                        context.userId(), context.conversationId(), normalizedType, outcome.subject(), outcome.action(), outcome.message());
            }
            return new UpdateUserProfileToolResult(
                    outcome.updated(), outcome.message(), normalizedType, outcome.subject(), outcome.action());
        } catch (Exception e) {
            logService.recordFailure(context.userId(), context.conversationId(), "updateUserProfile",
                    inputSummary, failureMessage("更新用户画像", e), elapsedMillis(start));
            logger.warn("Agent Tool 调用失败: updateUserProfile, userId: {}, conversationId: {}, inputSummary: {}",
                    context.userId(), context.conversationId(), inputSummary, e);
            return new UpdateUserProfileToolResult(false, "暂时无法更新用户画像", normalizeProfileType(profileType), clean(subject), "failed");
        }
    }

    private DecisionHistoryItem toDecisionHistoryItem(ProfileDecision decision) {
        return new DecisionHistoryItem(
                decision.getTopic(),
                decision.getChoice(),
                decision.getReason(),
                decision.getOutcome(),
                decision.getSatisfaction());
    }

    private void markToolCalled(AgentToolContext.Execution context, String toolName) {
        invocationTracker.markCalled(context.requestId(), toolName);
    }

    private SemanticMemoryItem toSemanticMemoryItem(MemoryContext.SemanticMemory memory) {
        return new SemanticMemoryItem(
                memory.content(),
                memory.type(),
                memory.score());
    }

    private DecisionMatrixOption toDecisionMatrixOption(String topic, String option, List<String> dimensions) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String dimension : dimensions) {
            scores.put(dimension, deterministicScore(topic, option, dimension));
        }
        return new DecisionMatrixOption(option, scores, option + " 在这些维度上可继续逐项讨论。");
    }

    private int deterministicScore(String topic, String option, String dimension) {
        int hash = Math.abs(Objects.hash(topic, option, dimension));
        return MIN_SCORE + (hash % (MAX_SCORE - MIN_SCORE + 1));
    }

    private UpdateOutcome upsertValueProfile(
            Long userId,
            String subject,
            String content,
            Double confidence,
            List<String> evidence) {
        String item = truncate(subject, 100);
        String preference = truncate(content, 200);
        if (!StringUtils.hasText(item) || !StringUtils.hasText(preference)) {
            return new UpdateOutcome(false, "画像主体和内容不能为空", "skipped", item);
        }

        ProfileValues existing = findValue(userId, item);
        if (existing == null) {
            ProfileValues profile = new ProfileValues();
            profile.setUserId(userId);
            profile.setItem(item);
            profile.setPreference(preference);
            profile.setConfidence(normalizeConfidence(confidence));
            profile.setEvidence(evidenceJson(evidence));
            profile.setUpdatedAt(LocalDateTime.now());
            valuesRepository.insert(profile);
            return new UpdateOutcome(true, "已新增价值观画像", "written", item);
        }

        existing.setPreference(preference);
        existing.setConfidence(normalizeConfidence(confidence));
        existing.setEvidence(evidenceJson(evidence));
        existing.setUpdatedAt(LocalDateTime.now());
        valuesRepository.updateById(existing);
        return new UpdateOutcome(true, "已更新价值观画像", "written", item);
    }

    private UpdateOutcome upsertEmotionProfile(
            Long userId,
            String subject,
            String content,
            String detail,
            List<String> evidence) {
        String emotion = truncate(subject, 100);
        String behavior = truncate(content, 500);
        String triggerDesc = truncate(detail, 200);
        if (!StringUtils.hasText(emotion) || !StringUtils.hasText(behavior)) {
            return new UpdateOutcome(false, "画像主体和内容不能为空", "skipped", emotion);
        }

        ProfileEmotion existing = findEmotion(userId, emotion, triggerDesc);
        if (existing == null) {
            ProfileEmotion profile = new ProfileEmotion();
            profile.setUserId(userId);
            profile.setEmotion(emotion);
            profile.setBehavior(behavior);
            profile.setTriggerDesc(triggerDesc);
            profile.setAgentNote(evidenceSummary(evidence));
            profile.setUpdatedAt(LocalDateTime.now());
            emotionRepository.insert(profile);
            return new UpdateOutcome(true, "已新增情绪模式画像", "written", emotion);
        }

        existing.setBehavior(behavior);
        existing.setTriggerDesc(triggerDesc);
        existing.setAgentNote(evidenceSummary(evidence));
        existing.setUpdatedAt(LocalDateTime.now());
        emotionRepository.updateById(existing);
        return new UpdateOutcome(true, "已更新情绪模式画像", "written", emotion);
    }

    private UpdateOutcome upsertRelationshipProfile(Long userId, String subject, String content, String detail) {
        String name = truncate(subject, 50);
        String note = truncate(content, 500);
        String role = truncate(detail, 50);
        if (!StringUtils.hasText(name) || !StringUtils.hasText(note)) {
            return new UpdateOutcome(false, "画像主体和内容不能为空", "skipped", name);
        }

        ProfileRelationship existing = findRelationship(userId, name);
        if (existing == null) {
            ProfileRelationship profile = new ProfileRelationship();
            profile.setUserId(userId);
            profile.setName(name);
            profile.setRole(role);
            profile.setNote(note);
            profile.setUpdatedAt(LocalDateTime.now());
            relationshipRepository.insert(profile);
            return new UpdateOutcome(true, "已新增关系画像", "written", name);
        }

        if (StringUtils.hasText(role)) {
            existing.setRole(role);
        }
        existing.setNote(note);
        existing.setUpdatedAt(LocalDateTime.now());
        relationshipRepository.updateById(existing);
        return new UpdateOutcome(true, "已更新关系画像", "written", name);
    }

    private UpdateOutcome upsertFearProfile(
            Long userId,
            String profileType,
            String subject,
            String content,
            String detail,
            Double confidence,
            List<String> evidence) {
        String type = "boundary".equals(profileType) ? "boundary" : "fear";
        String description = truncate(subject, 500);
        String manifestation = truncate(content, 500);
        if (!StringUtils.hasText(description) || !StringUtils.hasText(manifestation)) {
            return new UpdateOutcome(false, "画像主体和内容不能为空", "skipped", description);
        }

        ProfileFear existing = findFear(userId, type, description);
        if (existing == null) {
            ProfileFear profile = new ProfileFear();
            profile.setUserId(userId);
            profile.setType(type);
            profile.setDescription(description);
            profile.setManifestation(manifestation);
            profile.setConfidence(normalizeConfidence(confidence));
            profile.setEvidence(evidenceJson(evidence));
            profile.setBoundaryType("boundary".equals(type) ? normalizeBoundaryType(detail) : "");
            profile.setUpdatedAt(LocalDateTime.now());
            fearRepository.insert(profile);
            return new UpdateOutcome(true, "已新增恐惧与边界画像", "written", description);
        }

        existing.setManifestation(manifestation);
        existing.setConfidence(normalizeConfidence(confidence));
        existing.setEvidence(evidenceJson(evidence));
        if ("boundary".equals(type)) {
            existing.setBoundaryType(normalizeBoundaryType(detail));
        }
        existing.setUpdatedAt(LocalDateTime.now());
        fearRepository.updateById(existing);
        return new UpdateOutcome(true, "已更新恐惧与边界画像", "written", description);
    }

    private ProfileValues findValue(Long userId, String item) {
        return safeRepositoryList(valuesRepository.selectList(new LambdaQueryWrapper<ProfileValues>()
                        .eq(ProfileValues::getUserId, userId)))
                .stream()
                .filter(value -> Objects.equals(userId, value.getUserId()))
                .filter(value -> normalizeKey(value.getItem()).equals(normalizeKey(item)))
                .findFirst()
                .orElse(null);
    }

    private ProfileEmotion findEmotion(Long userId, String emotion, String triggerDesc) {
        return safeRepositoryList(emotionRepository.selectList(new LambdaQueryWrapper<ProfileEmotion>()
                        .eq(ProfileEmotion::getUserId, userId)))
                .stream()
                .filter(value -> Objects.equals(userId, value.getUserId()))
                .filter(value -> normalizeKey(value.getEmotion()).equals(normalizeKey(emotion)))
                .filter(value -> normalizeKey(value.getTriggerDesc()).equals(normalizeKey(triggerDesc)))
                .findFirst()
                .orElse(null);
    }

    private ProfileRelationship findRelationship(Long userId, String name) {
        return safeRepositoryList(relationshipRepository.selectList(new LambdaQueryWrapper<ProfileRelationship>()
                        .eq(ProfileRelationship::getUserId, userId)))
                .stream()
                .filter(value -> Objects.equals(userId, value.getUserId()))
                .filter(value -> normalizeKey(value.getName()).equals(normalizeKey(name)))
                .findFirst()
                .orElse(null);
    }

    private ProfileFear findFear(Long userId, String type, String description) {
        return safeRepositoryList(fearRepository.selectList(new LambdaQueryWrapper<ProfileFear>()
                        .eq(ProfileFear::getUserId, userId)))
                .stream()
                .filter(value -> Objects.equals(userId, value.getUserId()))
                .filter(value -> normalizeKey(value.getType()).equals(normalizeKey(type)))
                .filter(value -> normalizeKey(value.getDescription()).equals(normalizeKey(description)))
                .findFirst()
                .orElse(null);
    }

    private boolean matchesDecision(ProfileDecision decision, List<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return true;
        }
        String haystack = String.join(" ",
                safe(decision.getTopic()),
                safe(decision.getChoice()),
                safe(decision.getReason()),
                safe(decision.getOutcome()),
                safe(decision.getTags())).toLowerCase(Locale.ROOT);
        return queryTokens.stream().anyMatch(haystack::contains);
    }

    private List<String> tokens(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        String[] rawTokens = normalized.split("[\\s,，。；;、]+");
        List<String> result = new ArrayList<>();
        for (String token : rawTokens) {
            if (StringUtils.hasText(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private List<String> safeList(List<String> values, int maxSize) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .limit(maxSize)
                .toList();
    }

    private String normalizeProfileType(String profileType) {
        String normalized = clean(profileType).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.contains("value") || normalized.contains("价值")) {
            return "value";
        }
        if (normalized.contains("emotion") || normalized.contains("情绪")) {
            return "emotion";
        }
        if (normalized.contains("relationship") || normalized.contains("关系")) {
            return "relationship";
        }
        if (normalized.contains("boundary") || normalized.contains("边界")) {
            return "boundary";
        }
        if (normalized.contains("fear") || normalized.contains("恐惧")) {
            return "fear";
        }
        return "";
    }

    private BigDecimal normalizeConfidence(Double confidence) {
        return ProfileWritePolicy.normalizeConfidence(confidence);
    }

    private ProfileWriteDecision decideProfileWrite(String profileType, Double confidence) {
        ProfileWritePolicy.Decision decision = ProfileWritePolicy.decide(
                profileType,
                ProfileWritePolicy.normalizeConfidence(confidence));
        return new ProfileWriteDecision(
                decision.writable(),
                decision.action(),
                decision.message(),
                decision.logSummary());
    }

    private String normalizeBoundaryType(String value) {
        String normalized = clean(value).toLowerCase(Locale.ROOT);
        if (normalized.contains("hard") || normalized.contains("硬")) {
            return "hard";
        }
        if (normalized.contains("soft") || normalized.contains("软")) {
            return "soft";
        }
        return "";
    }

    private String evidenceJson(List<String> evidence) {
        List<String> values = safeList(evidence, 5);
        if (values.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String evidenceSummary(List<String> evidence) {
        return String.join("；", safeList(evidence, 5));
    }

    private String summarizeDecisions(List<DecisionHistoryItem> items) {
        return items.stream()
                .map(item -> item.topic() + ":" + item.choice())
                .collect(Collectors.joining("; "));
    }

    private String summarizeMemories(List<SemanticMemoryItem> memories) {
        return memories.stream()
                .map(memory -> safe(memory.content()))
                .collect(Collectors.joining("; "));
    }

    private String summarizeMatrix(List<DecisionMatrixOption> matrix) {
        return matrix.stream()
                .map(DecisionMatrixOption::option)
                .collect(Collectors.joining("; "));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String value, int maxLength) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) {
            return "";
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private String normalizeKey(String value) {
        return clean(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private <T> List<T> safeRepositoryList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private String failureMessage(String action, Exception e) {
        String detail = e.getMessage();
        if (!StringUtils.hasText(detail)) {
            return action + "失败";
        }
        return action + "失败：" + detail;
    }

    public record DecisionHistoryToolResult(boolean available, String message, List<DecisionHistoryItem> items) {
    }

    public record DecisionHistoryItem(
            String topic,
            String choice,
            String reason,
            String outcome,
            Integer satisfaction) {
    }

    public record SemanticMemoryToolResult(boolean available, String message, List<SemanticMemoryItem> memories) {
    }

    public record SemanticMemoryItem(String content, String type, Double score) {
    }

    public record DecisionMatrixToolResult(boolean available, String message, List<DecisionMatrixOption> matrix) {
    }

    public record DecisionMatrixOption(String option, Map<String, Integer> scores, String summary) {
    }

    public record UpdateUserProfileToolResult(
            boolean updated,
            String message,
            String profileType,
            String subject,
            String action) {
    }

    private record UpdateOutcome(boolean updated, String message, String action, String subject) {
    }

    private record ProfileWriteDecision(boolean writable, String action, String message, String logSummary) {
    }
}
