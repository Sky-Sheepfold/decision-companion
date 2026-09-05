package com.sky.decisioncompanion.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.decisioncompanion.common.BusinessException;
import com.sky.decisioncompanion.common.ResultCode;
import com.sky.decisioncompanion.config.MemoryInsightProperties;
import com.sky.decisioncompanion.model.MemoryAwareness;
import com.sky.decisioncompanion.model.MemoryInsight;
import com.sky.decisioncompanion.repository.MemoryAwarenessRepository;
import com.sky.decisioncompanion.repository.MemoryInsightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 行为动机洞察（Insight）记忆服务 —— 动机解释层。
 *
 * <p>对齐 OpenBiliClaw 的 Insight 层：从近期觉察（Awareness）观察之上让 LLM 提炼"行为背后的动机假设"
 * （回答"用户是在追求 A，还是在逃避 B"），带用户判定闭环（confirm/reject）。
 * 合并规则借鉴 OpenBiliClaw：按假设规范化文本去重；被否定的假设只降不升且不复活；
 * 已确认的假设设置信度下限；未判定的跟随最新证据。
 */
@Service
public class MemoryInsightService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryInsightService.class);

    public static final String VERDICT_CONFIRMED = "confirmed";
    public static final String VERDICT_REJECTED = "rejected";
    public static final String VERDICT_UNJUDGED = "";

    private static final int HYPOTHESIS_MAX = 500;

    private final MemoryInsightRepository insightRepository;
    private final MemoryAwarenessRepository awarenessRepository;
    private final MemoryInsightProperties properties;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final EmbeddingModel embeddingModel;

    public MemoryInsightService(
            MemoryInsightRepository insightRepository,
            MemoryAwarenessRepository awarenessRepository,
            MemoryInsightProperties properties,
            ChatClient.Builder chatClientBuilder,
            EmbeddingModel embeddingModel) {
        this.insightRepository = insightRepository;
        this.awarenessRepository = awarenessRepository;
        this.properties = properties;
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = new ObjectMapper();
        this.embeddingModel = embeddingModel;
    }

    /**
     * 为指定用户生成行为动机洞察（幂等：与已有假设去重，尊重用户判定）。返回新增条数。
     */
    public int generateForUser(Long userId) {
        if (userId == null || !properties.isEnabled()) {
            return 0;
        }
        List<MemoryAwareness> notes = recentAwareness(userId);
        if (notes.isEmpty()) {
            return 0;
        }

        List<MemoryInsight> existing = existingInsights(userId);
        String analysis = callInsight(notes, existing);
        List<InsightDraft> drafts = parseDrafts(analysis);
        if (drafts.isEmpty()) {
            logger.warn("行为动机洞察提炼输出为空或不可解析, userId: {}", userId);
            return 0;
        }

        Map<String, MemoryInsight> byKey = indexByHypothesis(existing);
        String sourceIdsText = notes.stream()
                .map(MemoryAwareness::getId)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        int added = 0;
        LocalDateTime now = LocalDateTime.now();
        // 第一遍：规范化文本精确匹配（快路径，零额外成本）
        List<InsightDraft> unmatched = new ArrayList<>();
        for (InsightDraft draft : drafts) {
            String hypothesis = clean(draft.hypothesis());
            if (hypothesis.isBlank()) {
                continue;
            }
            MemoryInsight existingInsight = byKey.get(normalize(hypothesis));
            if (existingInsight == null) {
                unmatched.add(draft);
                continue;
            }
            mergeExisting(existingInsight, draft, now);
            try {
                insightRepository.updateById(existingInsight);
            } catch (Exception e) {
                logger.warn("行为动机洞察更新失败, userId: {}", userId, e);
            }
        }
        // 第二遍：语义近重复匹配（embedding 余弦相似度，LLM 措辞差异去重；失败降级为纯文本去重）
        Map<InsightDraft, MemoryInsight> semanticMatches = semanticMatch(unmatched, existing);
        for (InsightDraft draft : unmatched) {
            String hypothesis = clean(draft.hypothesis());
            if (hypothesis.isBlank()) {
                continue;
            }
            MemoryInsight existingInsight = semanticMatches.get(draft);
            if (existingInsight == null) {
                // 语义匹配期间本批次已插入的同文本假设（避免同批重复插入）
                existingInsight = byKey.get(normalize(hypothesis));
            }
            if (existingInsight != null) {
                mergeExisting(existingInsight, draft, now);
                try {
                    insightRepository.updateById(existingInsight);
                } catch (Exception e) {
                    logger.warn("行为动机洞察更新失败, userId: {}", userId, e);
                }
                continue;
            }
            MemoryInsight insight = new MemoryInsight();
            insight.setUserId(userId);
            insight.setHypothesis(truncate(hypothesis, HYPOTHESIS_MAX));
            insight.setEvidence(toJson(draft.evidence()));
            insight.setConfidence(normalizeConfidence(draft.confidence()));
            insight.setVerdict(VERDICT_UNJUDGED);
            insight.setSourceAwarenessIds(sourceIdsText);
            insight.setActive(true);
            insight.setCreatedAt(now);
            insight.setUpdatedAt(now);
            try {
                insightRepository.insert(insight);
                added++;
                byKey.put(normalize(hypothesis), insight);
            } catch (Exception e) {
                logger.warn("行为动机洞察写入失败, userId: {}", userId, e);
            }
        }
        if (added > 0) {
            logger.info("行为动机洞察生成完成, userId: {}, newInsights: {}", userId, added);
        }
        return added;
    }

    /**
     * 定时任务：扫描近期有对话的用户，为其生成行为动机洞察（有界，避免一次打爆 LLM）。
     */
    @Scheduled(
            initialDelayString = "${decision-companion.memory.insight.initial-delay-ms:300000}",
            fixedDelayString = "${decision-companion.memory.insight.scheduled-interval-ms:21600000}")
    public void runScheduled() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(properties.windowDays());
            List<Long> userIds = insightRepository.selectRecentActiveUserIds(
                    since, properties.maxUsersPerRun());
            for (Long userId : userIds) {
                try {
                    generateForUser(userId);
                } catch (Exception e) {
                    logger.warn("行为动机洞察生成异常, userId: {}", userId, e);
                }
            }
        } catch (Exception e) {
            logger.warn("行为动机洞察定时任务异常", e);
        }
    }

    /**
     * 召回当前有效的洞察（供注入易变块），按置信度降序。已否定的洞察不参与召回。
     */
    public List<MemoryInsight> findActive(Long userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        return insightRepository.selectList(new LambdaQueryWrapper<MemoryInsight>()
                .eq(MemoryInsight::getUserId, userId)
                .eq(MemoryInsight::getActive, true)
                .orderByDesc(MemoryInsight::getConfidence)
                .orderByDesc(MemoryInsight::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * 画像页展示：当前有效洞察（含判定状态）。
     */
    public List<MemoryInsight> listInsights(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return insightRepository.selectList(new LambdaQueryWrapper<MemoryInsight>()
                .eq(MemoryInsight::getUserId, userId)
                .eq(MemoryInsight::getActive, true)
                .orderByDesc(MemoryInsight::getConfidence)
                .orderByDesc(MemoryInsight::getId));
    }

    /**
     * 待用户判定的洞察数量（画像页徽标）。
     */
    public int countUnjudged(Long userId) {
        if (userId == null) {
            return 0;
        }
        Long count = insightRepository.selectCount(new LambdaQueryWrapper<MemoryInsight>()
                .eq(MemoryInsight::getUserId, userId)
                .eq(MemoryInsight::getActive, true)
                .eq(MemoryInsight::getVerdict, VERDICT_UNJUDGED));
        return Math.toIntExact(count == null ? 0L : count);
    }

    /**
     * 用户判定行为动机洞察：confirm（确认）或 reject（否定）。
     * 确认后置为有效并按配置设置信度下限；否定后置为无效（保留审计，避免下次重新生成）。
     */
    public MemoryInsight judge(Long userId, Long insightId, String verdict) {
        if (userId == null || insightId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
        MemoryInsight insight = insightRepository.selectById(insightId);
        if (insight == null || !userId.equals(insight.getUserId())) {
            throw new BusinessException(ResultCode.MEMORY_INSIGHT_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        if (VERDICT_CONFIRMED.equals(normalizeVerdict(verdict))) {
            insight.setVerdict(VERDICT_CONFIRMED);
            insight.setActive(true);
            double floor = properties.confirmConfidenceFloor();
            double current = toDouble(insight.getConfidence());
            if (current < floor) {
                insight.setConfidence(BigDecimal.valueOf(floor));
            }
        } else if (VERDICT_REJECTED.equals(normalizeVerdict(verdict))) {
            insight.setVerdict(VERDICT_REJECTED);
            insight.setActive(false);
        } else {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
        insight.setUpdatedAt(now);
        insightRepository.updateById(insight);
        return insight;
    }

    // ---- 内部：提炼输入与 LLM 调用 ----

    private List<MemoryAwareness> recentAwareness(Long userId) {
        LocalDate since = LocalDate.now().minusDays(properties.windowDays());
        return awarenessRepository.selectList(new LambdaQueryWrapper<MemoryAwareness>()
                .eq(MemoryAwareness::getUserId, userId)
                .eq(MemoryAwareness::getActive, true)
                .ge(MemoryAwareness::getAwareDate, since)
                .orderByAsc(MemoryAwareness::getAwareDate)
                .orderByAsc(MemoryAwareness::getId)
                .last("LIMIT " + properties.awarenessCap()));
    }

    private List<MemoryInsight> existingInsights(Long userId) {
        return insightRepository.selectList(new LambdaQueryWrapper<MemoryInsight>()
                .eq(MemoryInsight::getUserId, userId));
    }

    private String callInsight(List<MemoryAwareness> notes, List<MemoryInsight> existing) {
        String notesText = notes.stream()
                .map(note -> "- [" + (note.getAwareDate() == null ? "" : note.getAwareDate())
                        + "] " + clean(note.getObservation())
                        + (StringUtils.hasText(note.getTrend()) ? "（趋势：" + clean(note.getTrend()) + "）" : "")
                        + (StringUtils.hasText(note.getEmotionGuess()) ? "（情绪：" + clean(note.getEmotionGuess()) + "）" : ""))
                .collect(Collectors.joining("\n"));
        String existingText = existing.stream()
                .filter(insight -> Boolean.TRUE.equals(insight.getActive()))
                .limit(20)
                .map(insight -> "- " + clean(insight.getHypothesis())
                        + (StringUtils.hasText(insight.getVerdict()) ? "（" + insight.getVerdict() + "）" : ""))
                .collect(Collectors.joining("\n"));
        String prompt = """
                请基于用户近期观察（Awareness）记录，提炼 1-3 条关于"用户行为背后的动机"的解释假设，
                用于理解"用户是在追求 A，还是在逃避 B"。每条假设必须能由观察直接支撑。

                近期观察：
                %s

                已有假设（避免重复；可对其细化或确认）：
                %s

                只返回一个 JSON 数组，不要返回 Markdown 或解释文字：
                [
                  {"hypothesis": "动机假设（一句话）", "evidence": ["支撑该假设的观察1", "支撑的观察2"], "confidence": 0.7}
                ]
                假设必须来自观察内容，不要臆测；证据不足返回空数组。
                """.formatted(notesText, existingText);
        return chatClient.prompt().messages(new UserMessage(prompt)).call().content();
    }

    private List<InsightDraft> parseDrafts(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String json = extractJson(raw);
        if (json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<InsightDraft> drafts = new ArrayList<>();
            for (JsonNode item : root) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String hypothesis = clean(text(item, "hypothesis", "insight"));
                if (hypothesis.isBlank()) {
                    continue;
                }
                JsonNode evidenceNode = item.get("evidence");
                List<String> evidence = new ArrayList<>();
                if (evidenceNode != null && evidenceNode.isArray()) {
                    for (JsonNode evidenceItem : evidenceNode) {
                        String value = clean(evidenceItem.isTextual() ? evidenceItem.asText("") : "");
                        if (!value.isBlank()) {
                            evidence.add(value);
                        }
                    }
                }
                drafts.add(new InsightDraft(
                        hypothesis,
                        evidence,
                        toBigDecimal(item.get("confidence"))));
            }
            return drafts;
        } catch (Exception e) {
            logger.warn("行为动机洞察 JSON 解析失败", e);
            return List.of();
        }
    }

    /** 合并已有假设与新一轮提炼结果，尊重用户判定（借鉴 OpenBiliClaw verdict 语义）。 */
    private void mergeExisting(MemoryInsight existing, InsightDraft draft, LocalDateTime now) {
        double current = toDouble(existing.getConfidence());
        double incoming = toDouble(draft.confidence());
        BigDecimal merged;
        if (VERDICT_REJECTED.equals(existing.getVerdict())) {
            // 用户否定过：置信度只降不升，且不复活（active 保持 false）
            merged = BigDecimal.valueOf(Math.min(current, incoming));
        } else if (VERDICT_CONFIRMED.equals(existing.getVerdict())) {
            // 用户确认过：置信度只升不降（设下限）
            merged = BigDecimal.valueOf(Math.max(current, incoming));
        } else {
            // 未判定：跟随最新证据
            merged = BigDecimal.valueOf(incoming);
        }
        existing.setConfidence(merged);
        existing.setEvidence(mergeEvidence(existing.getEvidence(), draft.evidence()));
        existing.setUpdatedAt(now);
    }

    private Map<String, MemoryInsight> indexByHypothesis(List<MemoryInsight> insights) {
        Map<String, MemoryInsight> index = new HashMap<>();
        for (MemoryInsight insight : insights) {
            index.put(normalize(insight.getHypothesis()), insight);
        }
        return index;
    }

    /**
     * 语义近重复匹配：对未精确命中的假设，与全部已有假设做 embedding 余弦相似度比对，
     * 找到 >= {@code nearDuplicateThreshold} 的最相似行（每行最多被匹配一次）。
     * 嵌入计算失败时降级返回空（回退为纯文本去重），不阻塞生成。
     */
    private Map<InsightDraft, MemoryInsight> semanticMatch(
            List<InsightDraft> drafts,
            List<MemoryInsight> candidates) {
        Map<InsightDraft, MemoryInsight> matches = new HashMap<>();
        double threshold = properties.nearDuplicateThreshold();
        if (drafts.isEmpty() || candidates.isEmpty() || threshold <= 0.0 || embeddingModel == null) {
            return matches;
        }
        List<String> draftTexts = drafts.stream()
                .map(draft -> clean(draft.hypothesis()))
                .filter(StringUtils::hasText)
                .toList();
        if (draftTexts.size() != drafts.size()) {
            return matches;
        }
        List<String> candidateTexts = candidates.stream()
                .map(MemoryInsight::getHypothesis)
                .filter(StringUtils::hasText)
                .toList();
        if (candidateTexts.size() != candidates.size()) {
            return matches;
        }
        try {
            List<float[]> draftVectors = embeddingModel.embed(draftTexts);
            List<float[]> candidateVectors = embeddingModel.embed(candidateTexts);
            boolean[] consumed = new boolean[candidates.size()];
            for (int i = 0; i < drafts.size(); i++) {
                float[] draftVector = draftVectors.get(i);
                if (draftVector == null) {
                    continue;
                }
                int bestIndex = -1;
                double bestSimilarity = threshold;
                for (int j = 0; j < candidates.size(); j++) {
                    if (consumed[j]) {
                        continue;
                    }
                    double similarity = cosineSimilarity(draftVector, candidateVectors.get(j));
                    if (similarity >= bestSimilarity) {
                        bestSimilarity = similarity;
                        bestIndex = j;
                    }
                }
                if (bestIndex >= 0) {
                    matches.put(drafts.get(i), candidates.get(bestIndex));
                    consumed[bestIndex] = true;
                }
            }
        } catch (Exception e) {
            logger.warn("行为动机洞察语义去重嵌入计算失败，已降级为纯文本去重", e);
        }
        return matches;
    }

    private double cosineSimilarity(float[] first, float[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) {
            return 0.0;
        }
        double dot = 0.0;
        double firstNorm = 0.0;
        double secondNorm = 0.0;
        for (int i = 0; i < first.length; i++) {
            dot += (double) first[i] * second[i];
            firstNorm += (double) first[i] * first[i];
            secondNorm += (double) second[i] * second[i];
        }
        if (firstNorm == 0.0 || secondNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(firstNorm) * Math.sqrt(secondNorm));
    }

    // ---- 工具方法 ----

    private String normalizeVerdict(String verdict) {
        if (verdict == null) {
            return "";
        }
        return switch (verdict.trim().toLowerCase(Locale.ROOT)) {
            case "confirm", "confirmed" -> VERDICT_CONFIRMED;
            case "reject", "rejected" -> VERDICT_REJECTED;
            default -> "";
        };
    }

    private String toJson(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String mergeEvidence(String existingJson, List<String> incoming) {
        List<String> existing = parseEvidence(existingJson);
        return toJson(Stream.concat(existing.stream(), incoming.stream())
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList());
    }

    private List<String> parseEvidence(String evidence) {
        if (!StringUtils.hasText(evidence)) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(String.class).readValue(evidence);
        } catch (Exception e) {
            return List.of(evidence);
        }
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('[');
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private BigDecimal normalizeConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return BigDecimal.ZERO;
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (confidence.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return confidence;
    }

    private BigDecimal toBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return normalizeConfidence(new BigDecimal(node.asText("0")));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private double toDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                return value.asText("");
            }
        }
        return "";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String normalize(String value) {
        return clean(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public record InsightDraft(String hypothesis, List<String> evidence, BigDecimal confidence) {
    }
}
