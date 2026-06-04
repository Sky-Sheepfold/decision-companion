package com.sky.decisioncompanion.service.memory;

import com.sky.decisioncompanion.config.MemoryRetrievalProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Service
public class MemoryRetrievalIntentService {

    private static final List<String> STRONG_REVIEW_KEYWORDS = List.of(
            "复盘", "回顾", "总结", "几次", "多次", "模式", "规律", "历史");
    private static final List<String> WEAK_REVIEW_KEYWORDS = List.of(
            "之前", "以前", "一直");
    private static final List<String> DECISION_KEYWORDS = List.of(
            "纠结", "取舍", "要不要", "该不该", "去不去", "留不留", "接受", "放弃", "选择", "决策", "offer", "机会", "城市");
    private static final List<String> RELATIONSHIP_KEYWORDS = List.of(
            "父母", "妈妈", "母亲", "爸爸", "父亲", "家人", "伴侣", "对象", "朋友", "同事", "领导", "关系");
    private static final List<String> EMOTION_KEYWORDS = List.of(
            "焦虑", "难受", "压力", "崩溃", "害怕", "恐惧", "烦", "累", "撑不住", "委屈", "低落", "内耗");
    private static final List<String> GOAL_KEYWORDS = List.of(
            "目标", "计划", "规划", "路径", "方向", "长期", "未来", "成长", "提升", "准备");

    private final MemoryRetrievalProperties properties;

    public MemoryRetrievalIntentService(MemoryRetrievalProperties properties) {
        this.properties = properties;
    }

    public MemoryRetrievalPlan plan(String query) {
        MemoryRetrievalIntent intent = resolve(query);
        int sectionLimit = properties.sectionLimit();
        int semanticTopK = properties.semanticTopK();
        return switch (intent) {
            case MAJOR_DECISION -> new MemoryRetrievalPlan(
                    intent,
                    sectionLimit,
                    cap(2, sectionLimit),
                    properties.decision().normalizeLimit(4),
                    cap(3, sectionLimit),
                    cap(3, sectionLimit),
                    semanticTopK,
                    candidateTopK(semanticTopK),
                    rewriteQuery(query, intent),
                    List.of("value", "fear", "relationship", "decision"),
                    List.of(
                            MemoryRetrievalPlan.Section.VALUES,
                            MemoryRetrievalPlan.Section.DECISIONS,
                            MemoryRetrievalPlan.Section.RELATIONSHIPS,
                            MemoryRetrievalPlan.Section.FEARS,
                            MemoryRetrievalPlan.Section.SEMANTIC_MEMORIES,
                            MemoryRetrievalPlan.Section.EMOTIONS));
            case VENTING -> new MemoryRetrievalPlan(
                    intent,
                    cap(2, sectionLimit),
                    sectionLimit,
                    properties.decision().normalizeLimit(1),
                    cap(3, sectionLimit),
                    cap(4, sectionLimit),
                    cap(4, semanticTopK),
                    candidateTopK(cap(4, semanticTopK)),
                    rewriteQuery(query, intent),
                    List.of("emotion", "fear", "relationship"),
                    List.of(
                            MemoryRetrievalPlan.Section.EMOTIONS,
                            MemoryRetrievalPlan.Section.FEARS,
                            MemoryRetrievalPlan.Section.RELATIONSHIPS,
                            MemoryRetrievalPlan.Section.SEMANTIC_MEMORIES,
                            MemoryRetrievalPlan.Section.VALUES,
                            MemoryRetrievalPlan.Section.DECISIONS));
            case REVIEW -> new MemoryRetrievalPlan(
                    intent,
                    cap(3, sectionLimit),
                    cap(3, sectionLimit),
                    properties.decision().normalizeLimit(5),
                    cap(2, sectionLimit),
                    cap(2, sectionLimit),
                    semanticTopK,
                    candidateTopK(semanticTopK),
                    rewriteQuery(query, intent),
                    List.of("decision", "value", "emotion", "mixed"),
                    List.of(
                            MemoryRetrievalPlan.Section.DECISIONS,
                            MemoryRetrievalPlan.Section.SEMANTIC_MEMORIES,
                            MemoryRetrievalPlan.Section.VALUES,
                            MemoryRetrievalPlan.Section.EMOTIONS,
                            MemoryRetrievalPlan.Section.RELATIONSHIPS,
                            MemoryRetrievalPlan.Section.FEARS));
            case RELATIONSHIP_PRESSURE -> new MemoryRetrievalPlan(
                    intent,
                    cap(4, sectionLimit),
                    cap(2, sectionLimit),
                    properties.decision().normalizeLimit(2),
                    sectionLimit,
                    cap(3, sectionLimit),
                    cap(4, semanticTopK),
                    candidateTopK(cap(4, semanticTopK)),
                    rewriteQuery(query, intent),
                    List.of("relationship", "value", "fear", "emotion"),
                    List.of(
                            MemoryRetrievalPlan.Section.RELATIONSHIPS,
                            MemoryRetrievalPlan.Section.VALUES,
                            MemoryRetrievalPlan.Section.FEARS,
                            MemoryRetrievalPlan.Section.SEMANTIC_MEMORIES,
                            MemoryRetrievalPlan.Section.EMOTIONS,
                            MemoryRetrievalPlan.Section.DECISIONS));
            case GOAL_PLANNING -> new MemoryRetrievalPlan(
                    intent,
                    sectionLimit,
                    cap(2, sectionLimit),
                    properties.decision().normalizeLimit(3),
                    cap(2, sectionLimit),
                    cap(3, sectionLimit),
                    semanticTopK,
                    candidateTopK(semanticTopK),
                    rewriteQuery(query, intent),
                    List.of("value", "decision", "fear", "mixed"),
                    List.of(
                            MemoryRetrievalPlan.Section.VALUES,
                            MemoryRetrievalPlan.Section.DECISIONS,
                            MemoryRetrievalPlan.Section.FEARS,
                            MemoryRetrievalPlan.Section.SEMANTIC_MEMORIES,
                            MemoryRetrievalPlan.Section.EMOTIONS,
                            MemoryRetrievalPlan.Section.RELATIONSHIPS));
            case GENERAL -> new MemoryRetrievalPlan(
                    intent,
                    sectionLimit,
                    sectionLimit,
                    properties.decision().defaultLimit(),
                    sectionLimit,
                    sectionLimit,
                    semanticTopK,
                    candidateTopK(semanticTopK),
                    rewriteQuery(query, intent),
                    List.of(),
                    List.of(
                            MemoryRetrievalPlan.Section.VALUES,
                            MemoryRetrievalPlan.Section.EMOTIONS,
                            MemoryRetrievalPlan.Section.DECISIONS,
                            MemoryRetrievalPlan.Section.RELATIONSHIPS,
                            MemoryRetrievalPlan.Section.SEMANTIC_MEMORIES,
                            MemoryRetrievalPlan.Section.FEARS));
        };
    }

    public MemoryRetrievalIntent resolve(String query) {
        String normalized = normalize(query);
        if (!StringUtils.hasText(normalized)) {
            return MemoryRetrievalIntent.GENERAL;
        }
        if (containsAny(normalized, STRONG_REVIEW_KEYWORDS)) {
            return MemoryRetrievalIntent.REVIEW;
        }
        if (containsAny(normalized, DECISION_KEYWORDS)) {
            return MemoryRetrievalIntent.MAJOR_DECISION;
        }
        if (containsAny(normalized, RELATIONSHIP_KEYWORDS)) {
            return MemoryRetrievalIntent.RELATIONSHIP_PRESSURE;
        }
        if (containsAny(normalized, EMOTION_KEYWORDS)) {
            return MemoryRetrievalIntent.VENTING;
        }
        if (containsAny(normalized, GOAL_KEYWORDS)) {
            return MemoryRetrievalIntent.GOAL_PLANNING;
        }
        if (containsAny(normalized, WEAK_REVIEW_KEYWORDS)) {
            return MemoryRetrievalIntent.REVIEW;
        }
        return MemoryRetrievalIntent.GENERAL;
    }

    private int candidateTopK(int resultTopK) {
        return Math.max(resultTopK, resultTopK * 2);
    }

    private String rewriteQuery(String query, MemoryRetrievalIntent intent) {
        String rawQuery = query == null ? "" : query.trim();
        return switch (intent) {
            case MAJOR_DECISION -> "重大决策取舍场景，检索用户价值观、恐惧边界、关系影响、历史决策和相似经历。原问题：" + rawQuery;
            case VENTING -> "情绪倾诉场景，检索用户情绪模式、恐惧边界、关系压力和相似情绪经历。原问题：" + rawQuery;
            case REVIEW -> "复盘总结场景，检索用户历史决策、相似场景、稳定价值观和反复出现的情绪模式。原问题：" + rawQuery;
            case GOAL_PLANNING -> "目标规划场景，检索用户长期价值观、历史选择、恐惧边界和未来规划相关经历。原问题：" + rawQuery;
            case RELATIONSHIP_PRESSURE -> "关系压力场景，检索用户关系影响、价值观、恐惧边界和相似沟通经历。原问题：" + rawQuery;
            case GENERAL -> rawQuery;
        };
    }

    private int cap(int desired, int max) {
        return Math.max(1, Math.min(desired, max));
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
