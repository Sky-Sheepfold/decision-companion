package com.sky.decisioncompanion.service.agenttool;

import com.sky.decisioncompanion.config.MemoryRetrievalProperties;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import com.sky.decisioncompanion.service.memory.DecisionRecallService;
import com.sky.decisioncompanion.service.memory.MemoryContext;
import com.sky.decisioncompanion.service.memory.MemoryRetrievalService;
import com.sky.decisioncompanion.service.memory.ProfileSceneMemoryService;
import com.sky.decisioncompanion.service.ConversationHistoryService;
import com.sky.decisioncompanion.service.profile.ProfileMemoryGovernanceService;
import com.sky.decisioncompanion.service.profile.ProfileMemoryGovernanceService.ConfirmedMemoryCommand;
import com.sky.decisioncompanion.service.profile.ProfileMemoryGovernanceService.MemoryCandidateCommand;
import com.sky.decisioncompanion.service.profile.PostureGateService;
import com.sky.decisioncompanion.service.profile.ProfileWritePolicy;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class DecisionAgentToolService {

    private static final Logger logger = LoggerFactory.getLogger(DecisionAgentToolService.class);
    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 5;
    private static final int MIN_SEMANTIC_CORE_LENGTH = 3;
    private static final int MAX_SEMANTIC_CORE_LENGTH_DELTA = 1;
    private static final double SEMANTIC_EQUIVALENCE_THRESHOLD = 0.82;
    private static final List<String> LOW_INFORMATION_PHRASES = List.of(
            "我正在考虑", "我在考虑", "我正在纠结", "我在纠结", "我纠结", "帮我看看", "请帮我看看",
            "是否要", "是否", "要不要", "该不该", "能不能", "可以吗", "吗", "呢");
    private static final List<String> NEGATION_MARKERS = List.of("不", "没", "未", "拒绝", "放弃", "取消");
    private final MemoryRetrievalService memoryRetrievalService;
    private final ProfileMemoryGovernanceService profileMemoryGovernanceService;
    private final DecisionRecallService decisionRecallService;
    private final MemoryRetrievalProperties properties;
    private final AgentToolCallLogService logService;
    private final AgentToolInvocationTracker invocationTracker;
    private final PostureGateService postureGateService;
    private final ConversationHistoryService conversationHistoryService;
    private final AgentToolRegistry toolRegistry;
    private final AgentToolEffectExecutor effectExecutor;

    public DecisionAgentToolService(
            ProfileValuesRepository valuesRepository,
            ProfileEmotionRepository emotionRepository,
            ProfileRelationshipRepository relationshipRepository,
            ProfileFearRepository fearRepository,
            MemoryRetrievalService memoryRetrievalService,
            ProfileSceneMemoryService profileSceneMemoryService,
            ProfileMemoryGovernanceService profileMemoryGovernanceService,
            DecisionRecallService decisionRecallService,
            MemoryRetrievalProperties properties,
            AgentToolCallLogService logService,
            AgentToolInvocationTracker invocationTracker,
            PostureGateService postureGateService,
            ConversationHistoryService conversationHistoryService,
            AgentToolRegistry toolRegistry,
            AgentToolEffectExecutor effectExecutor) {
        this.memoryRetrievalService = memoryRetrievalService;
        this.profileMemoryGovernanceService = profileMemoryGovernanceService;
        this.decisionRecallService = decisionRecallService;
        this.properties = properties;
        this.logService = logService;
        this.invocationTracker = invocationTracker;
        this.postureGateService = postureGateService;
        this.conversationHistoryService = conversationHistoryService;
        this.toolRegistry = toolRegistry;
        this.effectExecutor = effectExecutor;
    }

    @Tool(name = "searchDecisionHistory", description = "查询当前用户的历史决策记录，用于识别相似选择和决策模式。该工具只读，不会写入或修改任何档案。")
    public DecisionHistoryToolResult searchDecisionHistory(
            @ToolParam(description = "与当前决策相关的查询文本") String query,
            @ToolParam(required = false, description = "最多返回多少条结果，默认和上限受后端记忆召回配置控制") Integer limit,
            ToolContext toolContext) {
        long start = System.nanoTime();
        AgentToolContext.Execution context = AgentToolContext.from(toolContext);
        markToolCalled(context, "searchDecisionHistory");
        String inputSummary = "query=" + query + ", limit=" + limit;

        try {
            int safeLimit = properties.decision().normalizeLimit(limit);
            List<DecisionHistoryItem> items = decisionRecallService.recall(context.userId(), query, safeLimit)
                    .items()
                    .stream()
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
            @ToolParam(required = false, description = "最多返回多少条语义记忆，默认和上限受后端记忆召回配置控制") Integer topK,
            ToolContext toolContext) {
        long start = System.nanoTime();
        AgentToolContext.Execution context = AgentToolContext.from(toolContext);
        markToolCalled(context, "searchSemanticMemory");
        String inputSummary = "query=" + query + ", topK=" + topK;

        try {
            int safeTopK = properties.normalizeSemanticTopK(topK);
            String duplicateReason = duplicateSemanticRecallReason(context, query);
            if (duplicateReason != null) {
                String message = "本轮已召回相关场景记忆，已跳过重复查询";
                logService.recordSkipped(context.userId(), context.conversationId(), "searchSemanticMemory",
                        inputSummary, duplicateReason + ": semanticHitCount="
                                + context.semanticHitCount() + ", maxSemanticScore=" + context.maxSemanticScore(),
                        elapsedMillis(start));
                return new SemanticMemoryToolResult(false, message, List.of());
            }

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

        // 两段式审计：先记 started，各终态用 complete* 回填
        AgentToolCallLogService.StartedAudit audit = logService.recordStarted(
                context.userId(), context.conversationId(), "updateUserProfile", inputSummary);
        Long auditId = audit == null ? null : audit.id();

        try {
            String normalizedType = normalizeProfileType(profileType);
            if (!StringUtils.hasText(normalizedType)) {
                String message = "暂不支持该画像类型";
                logService.completeSkipped(auditId, message, elapsedMillis(start));
                logger.info("Agent Tool 跳过: updateUserProfile, userId: {}, conversationId: {}, reason: {}",
                        context.userId(), context.conversationId(), message);
                return new UpdateUserProfileToolResult(false, message, "", clean(subject), "skipped");
            }
            if (!StringUtils.hasText(subject) || !StringUtils.hasText(content)) {
                String message = "画像主体和内容不能为空";
                logService.completeSkipped(auditId, message, elapsedMillis(start));
                logger.info("Agent Tool 跳过: updateUserProfile, userId: {}, conversationId: {}, profileType: {}, reason: {}",
                        context.userId(), context.conversationId(), normalizedType, message);
                return new UpdateUserProfileToolResult(false, message, normalizedType, clean(subject), "skipped");
            }

            // 可见性分层：本轮场景未授权该写工具则直接拦下（模型可见 schema 但不可执行）
            if (!context.isToolVisible("updateUserProfile")) {
                String message = "当前场景未开放画像写入";
                logService.completeSkipped(auditId, message + ": " + clean(subject), elapsedMillis(start));
                logger.info("Agent Tool 场景未授权写画像: updateUserProfile, userId: {}, conversationId: {}, subject: {}",
                        context.userId(), context.conversationId(), clean(subject));
                return new UpdateUserProfileToolResult(false, message, normalizedType, clean(subject), "skipped");
            }
            // 执行处 owner 复核：会话归属校验（纵深防御，防上游传入不匹配的 userId/会话）
            if (context.conversationId() != null
                    && !conversationHistoryService.isOwnedConversation(context.userId(), context.conversationId())) {
                String message = "会话归属校验失败，未执行画像写入";
                logService.completeSkipped(auditId, message, elapsedMillis(start));
                logger.warn("Agent Tool 会话归属校验失败: updateUserProfile, userId: {}, conversationId: {}",
                        context.userId(), context.conversationId());
                return new UpdateUserProfileToolResult(false, message, normalizedType, clean(subject), "skipped");
            }

            String cleanedSubject = clean(subject);
            String cleanedContent = clean(content);
            String cleanedDetail = clean(detail);
            String idempotencyKey = buildIdempotencyKey(context, normalizedType, cleanedSubject, cleanedContent, cleanedDetail);
            String canonicalInputHash = canonicalInputHash(normalizedType, cleanedSubject, cleanedContent, cleanedDetail);

            // 账本与业务写同事务：抢占→业务写→回填 由执行器在单一事务内完成；命中已提交终态重放、占用中短路
            AgentToolEffectExecutor.Outcome outcome = effectExecutor.execute(
                    context.userId(),
                    context.conversationId(),
                    context.requestId(),
                    AgentToolRegistry.UPDATE_USER_PROFILE,
                    idempotencyKey,
                    () -> executeProfileWrite(context, auditId, start, inputSummary, normalizedType,
                            cleanedSubject, cleanedContent, cleanedDetail, confidence, evidence, canonicalInputHash));

            if (outcome.status() == AgentToolEffectLedger.AcquisitionStatus.ALREADY_COMMITTED) {
                AgentToolEffectLedger.CommittedWrite previous = outcome.committed();
                logService.completeSkipped(auditId, "idempotent replay: " + previous.action(), elapsedMillis(start));
                logger.info("Agent Tool 幂等重放: updateUserProfile, userId: {}, conversationId: {}, action: {}",
                        context.userId(), context.conversationId(), previous.action());
                return replay(previous);
            }
            if (outcome.status() == AgentToolEffectLedger.AcquisitionStatus.IN_FLIGHT) {
                String message = "同一画像更新正在处理中，已忽略本次重复调用";
                logService.completeSkipped(auditId, "in-flight duplicate: " + cleanedSubject, elapsedMillis(start));
                logger.info("Agent Tool 幂等忽略（占用中）: updateUserProfile, userId: {}, conversationId: {}, subject: {}",
                        context.userId(), context.conversationId(), cleanedSubject);
                return new UpdateUserProfileToolResult(false, message, normalizedType, cleanedSubject, "processing");
            }

            AgentToolEffectExecutor.Result effectResult = outcome.result();
            return new UpdateUserProfileToolResult(
                    effectResult.updated(), effectResult.message(), effectResult.profileType(),
                    effectResult.subject(), effectResult.action());
        } catch (Exception e) {
            logService.completeFailure(auditId, failureMessage("更新用户画像", e), elapsedMillis(start));
            logger.warn("Agent Tool 调用失败: updateUserProfile, userId: {}, conversationId: {}, inputSummary: {}",
                    context.userId(), context.conversationId(), inputSummary, e);
            return new UpdateUserProfileToolResult(false, "暂时无法更新用户画像", normalizeProfileType(profileType), clean(subject), "failed");
        }
    }

    /**
     * 业务写副作用（在 {@link AgentToolEffectExecutor} 的事务内执行）：按置信度走候选/门控降级/直写，
     * 返回用于回填账本的结果。
     */
    private AgentToolEffectExecutor.Result executeProfileWrite(
            AgentToolContext.Execution context,
            Long auditId,
            long start,
            String inputSummary,
            String normalizedType,
            String subject,
            String content,
            String detail,
            Double confidence,
            List<String> evidence,
            String inputHash) {
        ProfileWriteDecision writeDecision = decideProfileWrite(normalizedType, confidence);
        if (!writeDecision.writable()) {
            Long candidateId = null;
            if ("needs_confirmation".equals(writeDecision.action())) {
                var candidate = profileMemoryGovernanceService.createCandidate(new MemoryCandidateCommand(
                        context.userId(),
                        normalizedType,
                        subject,
                        content,
                        detail,
                        normalizeConfidence(confidence),
                        safeList(evidence, 5),
                        "agent_tool_update",
                        context.conversationId(),
                        inputHash));
                candidateId = candidate == null ? null : candidate.getId();
            }
            logService.completeSkipped(auditId, writeDecision.logSummary(), elapsedMillis(start));
            logger.info("Agent Tool 未写入画像: updateUserProfile, userId: {}, conversationId: {}, profileType: {}, subject: {}, action: {}, reason: {}",
                    context.userId(), context.conversationId(), normalizedType, subject,
                    writeDecision.action(), writeDecision.logSummary());
            return new AgentToolEffectExecutor.Result(
                    writeDecision.action(), normalizedType, subject, writeDecision.message(), false, null, candidateId);
        }

        // 深层画像门控（与异步提炼一致）：enforce 下由 LLM 裁判决定是否直接写入，拦截则降级为候选
        String gateVerdict = null;
        if (postureGateService.shouldGate(normalizedType)) {
            PostureGateService.GateVerdict verdict = postureGateService.evaluate(
                    context.userId(), normalizedType, subject, content,
                    safeList(evidence, 5), normalizeConfidence(confidence));
            gateVerdict = verdict.action();
            if (!verdict.accepted()) {
                try {
                    profileMemoryGovernanceService.recordGateRejection(
                            context.userId(), normalizedType, subject, gateVerdict);
                } catch (Exception e) {
                    logger.warn("Agent Tool 画像门控拒绝审计失败, userId: {}, profileType: {}, subject: {}",
                            context.userId(), normalizedType, subject, e);
                }
                var candidate = profileMemoryGovernanceService.createCandidate(new MemoryCandidateCommand(
                        context.userId(),
                        normalizedType,
                        subject,
                        content,
                        detail,
                        normalizeConfidence(confidence),
                        safeList(evidence, 5),
                        "agent_tool_update",
                        context.conversationId(),
                        inputHash));
                Long gatedCandidateId = candidate == null ? null : candidate.getId();
                logService.completeSkipped(auditId, "gated→candidate: " + gateVerdict + " " + normalizedType + ":" + subject, elapsedMillis(start));
                logger.info("Agent Tool 深层画像被门控降级为候选: updateUserProfile, userId: {}, conversationId: {}, profileType: {}, subject: {}, action: {}",
                        context.userId(), context.conversationId(), normalizedType, subject, gateVerdict);
                return new AgentToolEffectExecutor.Result(
                        "needs_confirmation", normalizedType, subject, "深层画像更新需先确认", false, null, gatedCandidateId);
            }
        }

        ProfileMemoryGovernanceService.GovernanceResult governanceResult =
                profileMemoryGovernanceService.writeConfirmedMemory(new ConfirmedMemoryCommand(
                        context.userId(),
                        normalizedType,
                        subject,
                        content,
                        detail,
                        normalizeConfidence(confidence),
                        safeList(evidence, 5),
                        "agent_tool_update",
                        context.conversationId(),
                        context.message(),
                        gateVerdict));
        String resultAction = governanceResult.success() ? "written" : governanceResult.action();
        if (governanceResult.success()) {
            logService.completeSuccess(auditId, "written " + normalizedType + ":" + subject, elapsedMillis(start));
            logger.info("Agent Tool 已写入画像: updateUserProfile, userId: {}, conversationId: {}, profileType: {}, subject: {}, action: {}",
                    context.userId(), context.conversationId(), normalizedType, subject, resultAction);
        } else {
            logService.completeSkipped(auditId, governanceResult.message(), elapsedMillis(start));
            logger.info("Agent Tool 未写入画像: updateUserProfile, userId: {}, conversationId: {}, profileType: {}, subject: {}, action: {}, reason: {}",
                    context.userId(), context.conversationId(), normalizedType, subject, resultAction, governanceResult.message());
        }
        return new AgentToolEffectExecutor.Result(
                resultAction, normalizedType, subject, governanceResult.message(),
                governanceResult.success(), governanceResult.profileRecordId(), null);
    }

    private DecisionHistoryItem toDecisionHistoryItem(DecisionRecallService.DecisionRecallItem decision) {
        return new DecisionHistoryItem(
                decision.topic(),
                decision.choice(),
                decision.reason(),
                decision.outcome(),
                decision.satisfaction());
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

    private String duplicateSemanticRecallReason(AgentToolContext.Execution context, String query) {
        if (!context.semanticMemoryRetrieved() || context.semanticHitCount() <= 0) {
            return null;
        }
        String normalizedQuery = normalizeSemanticQuery(query);
        String normalizedOriginal = normalizeSemanticQuery(context.semanticQuery());
        if (normalizedQuery.equals(normalizedOriginal)) {
            return "exact duplicate semantic recall";
        }
        if (isSemanticEquivalentDuplicate(normalizedQuery, normalizedOriginal)) {
            return "semantic-equivalent duplicate recall";
        }
        return null;
    }

    private String normalizeSemanticQuery(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s,，。；;、.!！?？:：\"“”'‘’()（）\\[\\]【】]+", "");
    }

    private boolean isSemanticEquivalentDuplicate(String query, String originalQuery) {
        String queryCore = semanticCore(query);
        String originalCore = semanticCore(originalQuery);
        if (queryCore.length() < MIN_SEMANTIC_CORE_LENGTH
                || originalCore.length() < MIN_SEMANTIC_CORE_LENGTH) {
            return false;
        }
        if (Math.abs(queryCore.length() - originalCore.length()) > MAX_SEMANTIC_CORE_LENGTH_DELTA) {
            return false;
        }
        if (hasNegationMismatch(queryCore, originalCore)) {
            return false;
        }
        if (queryCore.equals(originalCore)) {
            return true;
        }
        return diceSimilarity(bigrams(queryCore), bigrams(originalCore)) >= SEMANTIC_EQUIVALENCE_THRESHOLD
                || diceSimilarity(characters(queryCore), characters(originalCore)) >= SEMANTIC_EQUIVALENCE_THRESHOLD;
    }

    private String semanticCore(String value) {
        String result = safe(value);
        for (String phrase : LOW_INFORMATION_PHRASES) {
            result = result.replace(phrase, "");
        }
        return result;
    }

    private boolean hasNegationMismatch(String left, String right) {
        return containsNegation(left) != containsNegation(right);
    }

    private boolean containsNegation(String value) {
        return NEGATION_MARKERS.stream().anyMatch(value::contains);
    }

    private List<String> bigrams(String value) {
        if (value.length() < 2) {
            return List.of(value);
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < value.length() - 1; i++) {
            result.add(value.substring(i, i + 2));
        }
        return result;
    }

    private List<String> characters(String value) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < value.length(); i++) {
            result.add(value.substring(i, i + 1));
        }
        return result;
    }

    private double diceSimilarity(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String item : left) {
            counts.merge(item, 1, Integer::sum);
        }
        int overlap = 0;
        for (String item : right) {
            Integer count = counts.get(item);
            if (count != null && count > 0) {
                overlap++;
                counts.put(item, count - 1);
            }
        }
        return (2.0 * overlap) / (left.size() + right.size());
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

    private UpdateUserProfileToolResult replay(AgentToolEffectLedger.CommittedWrite previous) {
        boolean updated = "written".equals(previous.action());
        return new UpdateUserProfileToolResult(
                updated,
                safe(previous.message()),
                safe(previous.profileType()),
                safe(previous.subject()),
                safe(previous.action()));
    }

    private String buildIdempotencyKey(
            AgentToolContext.Execution context, String profileType, String subject, String content, String detail) {
        String paramsHash = canonicalInputHash(profileType, subject, content, detail);
        String contract = toolRegistry.contractRevision(AgentToolRegistry.UPDATE_USER_PROFILE);
        String raw = context.userId() + ":" + nullToEmpty(context.conversationId()) + ":"
                + contract + ":" + paramsHash;
        return sha256(raw);
    }

    /**
     * 规范化输入哈希：仅由规范化后的画像参数决定，用于把候选/审批绑定到具体输入，
     * 避免一次审批被不同参数的调用复用（借鉴 waoowaoo 的 inputHash）。
     */
    private String canonicalInputHash(String profileType, String subject, String content, String detail) {
        String params = String.join("|", safe(profileType), safe(subject), safe(content), safe(detail));
        return sha256(params);
    }

    private String nullToEmpty(Long value) {
        return value == null ? "" : value.toString();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
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

    private record ProfileWriteDecision(boolean writable, String action, String message, String logSummary) {
    }
}
