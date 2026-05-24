package com.sky.decisioncompanion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.model.*;
import com.sky.decisioncompanion.repository.*;
import com.sky.decisioncompanion.service.profile.ProfileAnalysisParser;
import com.sky.decisioncompanion.service.profile.ProfileAnalysisParser.Analysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ProfileExtractService {

    private static final Logger logger = LoggerFactory.getLogger(ProfileExtractService.class);
    private static final BigDecimal MIN_VALUE_CONFIDENCE = new BigDecimal("0.60");
    private static final BigDecimal MIN_FEAR_CONFIDENCE = new BigDecimal("0.70");
    private static final List<String> DECISION_KEYWORDS = List.of(
            "决定", "决策", "选择", "纠结", "要不要", "offer", "离职", "转行", "工作", "城市", "学校");

    private final ChatClient chatClient;
    private final ProfileValuesRepository valuesRepository;
    private final ProfileDecisionRepository decisionRepository;
    private final ProfileEmotionRepository emotionRepository;
    private final ProfileRelationshipRepository relationshipRepository;
    private final ProfileFearRepository fearRepository;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final ProfileAnalysisParser analysisParser;

    public ProfileExtractService(
            ChatClient.Builder chatClientBuilder,
            ProfileValuesRepository valuesRepository,
            ProfileDecisionRepository decisionRepository,
            ProfileEmotionRepository emotionRepository,
            ProfileRelationshipRepository relationshipRepository,
            ProfileFearRepository fearRepository,
            @Autowired(required = false) @Nullable VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.valuesRepository = valuesRepository;
        this.decisionRepository = decisionRepository;
        this.emotionRepository = emotionRepository;
        this.relationshipRepository = relationshipRepository;
        this.fearRepository = fearRepository;
        this.vectorStore = vectorStore;
        this.objectMapper = new ObjectMapper();
        this.analysisParser = new ProfileAnalysisParser();
    }

    @Async
    public void extractAndSave(Long userId, String userMessage, String aiResponse) {
        if (userId == null) {
            logger.warn("跳过档案提炼：userId 为空");
            return;
        }

        try {
            String analysisPrompt = buildAnalysisPrompt(userMessage, aiResponse);
            String analysis = chatClient.prompt()
                    .messages(new UserMessage(analysisPrompt))
                    .call()
                    .content();

            saveAnalysis(userId, userMessage, analysis);
        } catch (Exception e) {
            logger.error("档案提炼失败, userId: {}", userId, e);
        }
    }

    private String buildAnalysisPrompt(String userMessage, String aiResponse) {
        return """
                请分析以下对话，提取用户明确透露的价值观、情绪模式、决策、关系和恐惧/边界信息。

                用户消息：%s

                AI回复：%s

                只返回一个 JSON 对象，不要返回 Markdown、代码块或解释文字。没有足够证据的信息必须返回空数组，不要猜测。
                evidence 必须是来自本轮用户消息或 AI 回复的短句数组。

                JSON 结构如下：
                {
                    "values": [{"item": "价值观维度", "preference": "倾向描述", "confidence": 0.8, "evidence": ["证据短句"]}],
                    "emotions": [{"emotion": "情绪类型", "behavior": "行为表现", "trigger": "触发场景", "evidence": ["证据短句"]}],
                    "decisions": [{"topic": "决策主题", "choice": "选择", "reason": "原因", "evidence": ["证据短句"]}],
                    "relationships": [{"name": "关系人", "role": "角色", "influenceLevel": "高/中/低", "influenceStyle": "影响方式", "note": "备注", "evidence": ["证据短句"]}],
                    "fears": [{"type": "fear/boundary", "description": "描述", "manifestation": "表现", "boundaryType": "hard/soft", "confidence": 0.7, "evidence": ["证据短句"]}]
                }
                """.formatted(userMessage, aiResponse);
    }

    int saveAnalysis(Long userId, String userMessage, String analysisOutput) {
        if (userId == null) {
            logger.warn("跳过档案提炼：userId 为空");
            return 0;
        }

        Analysis analysis = analysisParser.parse(analysisOutput);
        if (!analysis.parsed()) {
            logger.warn("跳过档案提炼：模型输出不是可解析 JSON, userId: {}, output: {}",
                    userId,
                    truncate(defaultIfBlank(analysisOutput, ""), 300));
            return 0;
        }

        int savedCount = 0;
        savedCount += saveValues(userId, analysis.values());
        savedCount += saveEmotions(userId, analysis.emotions());
        savedCount += saveDecisions(userId, userMessage, analysis.decisions());
        savedCount += saveRelationships(userId, analysis.relationships());
        savedCount += saveFears(userId, analysis.fears());

        if (savedCount > 0) {
            saveToVectorStore(userId, userMessage, analysis, savedCount);
        }

        return savedCount;
    }

    private int saveValues(Long userId, List<JsonNode> values) {
        int savedCount = 0;
        try {
            for (JsonNode value : values) {
                String item = truncate(field(value, "item"), 100);
                String preference = truncate(field(value, "preference"), 200);
                BigDecimal confidence = decimal(value, "confidence", "0");
                if (item.isBlank() || preference.isBlank() || confidence.compareTo(MIN_VALUE_CONFIDENCE) < 0) {
                    continue;
                }

                String evidence = evidenceJson(value);
                ProfileValues existing = findValue(userId, item);
                if (existing == null) {
                    ProfileValues profile = new ProfileValues();
                    profile.setUserId(userId);
                    profile.setItem(item);
                    profile.setPreference(preference);
                    profile.setConfidence(confidence);
                    profile.setEvidence(evidence);
                    profile.setUpdatedAt(LocalDateTime.now());
                    valuesRepository.insert(profile);
                } else {
                    existing.setPreference(preference);
                    existing.setConfidence(max(existing.getConfidence(), confidence));
                    existing.setEvidence(evidence);
                    existing.setUpdatedAt(LocalDateTime.now());
                    valuesRepository.updateById(existing);
                }
                savedCount++;
            }
        } catch (Exception e) {
            logger.error("价值观提取失败, userId: {}", userId, e);
        }
        return savedCount;
    }

    private int saveEmotions(Long userId, List<JsonNode> emotions) {
        int savedCount = 0;
        try {
            for (JsonNode emotion : emotions) {
                String emotionName = truncate(field(emotion, "emotion"), 100);
                String behavior = truncate(field(emotion, "behavior"), 500);
                String trigger = truncate(field(emotion, "trigger", "triggerDesc", "trigger_desc"), 200);
                if (emotionName.isBlank() || behavior.isBlank()) {
                    continue;
                }

                String evidenceSummary = truncate(evidenceSummary(emotion), 500);
                ProfileEmotion existing = findEmotion(userId, emotionName, trigger);
                if (existing == null) {
                    ProfileEmotion profile = new ProfileEmotion();
                    profile.setUserId(userId);
                    profile.setEmotion(emotionName);
                    profile.setBehavior(behavior);
                    profile.setTriggerDesc(trigger);
                    profile.setAgentNote(evidenceSummary);
                    profile.setUpdatedAt(LocalDateTime.now());
                    emotionRepository.insert(profile);
                } else {
                    existing.setBehavior(behavior);
                    existing.setTriggerDesc(trigger);
                    existing.setAgentNote(evidenceSummary);
                    existing.setUpdatedAt(LocalDateTime.now());
                    emotionRepository.updateById(existing);
                }
                savedCount++;
            }
        } catch (Exception e) {
            logger.error("情绪模式提取失败, userId: {}", userId, e);
        }
        return savedCount;
    }

    private int saveDecisions(Long userId, String userMessage, List<JsonNode> decisions) {
        if (!hasDecisionContext(userMessage)) {
            return 0;
        }

        int savedCount = 0;
        try {
            for (JsonNode decision : decisions) {
                String topic = truncate(field(decision, "topic"), 200);
                String choice = truncate(field(decision, "choice"), 500);
                String reason = field(decision, "reason");
                if (topic.isBlank() || (choice.isBlank() && reason.isBlank())) {
                    continue;
                }
                if (!choice.isBlank() && findDecision(userId, topic, choice) != null) {
                    continue;
                }

                String evidenceSummary = evidenceSummary(decision);
                String reasonWithEvidence = withEvidence(reason, evidenceSummary);
                {
                    ProfileDecision profile = new ProfileDecision();
                    profile.setUserId(userId);
                    profile.setTopic(topic);
                    profile.setChoice(choice);
                    profile.setReason(reasonWithEvidence);
                    profile.setCreatedAt(LocalDateTime.now());
                    decisionRepository.insert(profile);
                }
                savedCount++;
            }
        } catch (Exception e) {
            logger.error("决策提取失败, userId: {}", userId, e);
        }
        return savedCount;
    }

    private int saveRelationships(Long userId, List<JsonNode> relationships) {
        int savedCount = 0;
        try {
            for (JsonNode relationship : relationships) {
                String name = truncate(field(relationship, "name"), 50);
                if (name.isBlank()) {
                    continue;
                }

                String rawInfluence = field(relationship, "influence");
                String rawInfluenceLevel = field(relationship, "influenceLevel", "influence_level", "level");
                String influenceStyle = field(relationship, "influenceStyle", "influence_style");
                if (influenceStyle.isBlank()) {
                    influenceStyle = rawInfluence;
                }
                String note = truncate(defaultIfBlank(field(relationship, "note"), evidenceSummary(relationship)), 500);

                ProfileRelationship existing = findRelationship(userId, name);
                if (existing == null) {
                    ProfileRelationship profile = new ProfileRelationship();
                    profile.setUserId(userId);
                    profile.setName(name);
                    profile.setRole(truncate(field(relationship, "role"), 50));
                    profile.setInfluenceLevel(normalizeInfluenceLevel(rawInfluenceLevel));
                    profile.setInfluenceStyle(truncate(influenceStyle, 200));
                    profile.setNote(note);
                    profile.setUpdatedAt(LocalDateTime.now());
                    relationshipRepository.insert(profile);
                } else {
                    existing.setRole(truncate(field(relationship, "role"), 50));
                    existing.setInfluenceLevel(normalizeInfluenceLevel(rawInfluenceLevel));
                    existing.setInfluenceStyle(truncate(influenceStyle, 200));
                    existing.setNote(note);
                    existing.setUpdatedAt(LocalDateTime.now());
                    relationshipRepository.updateById(existing);
                }
                savedCount++;
            }
        } catch (Exception e) {
            logger.error("关系提取失败, userId: {}", userId, e);
        }
        return savedCount;
    }

    private int saveFears(Long userId, List<JsonNode> fears) {
        int savedCount = 0;
        try {
            for (JsonNode fear : fears) {
                String description = truncate(field(fear, "description"), 500);
                BigDecimal confidence = decimal(fear, "confidence", "0");
                String evidence = evidenceJson(fear);
                if (description.isBlank()
                        || confidence.compareTo(MIN_FEAR_CONFIDENCE) < 0
                        || "[]".equals(evidence)) {
                    continue;
                }

                String type = normalizeFearType(field(fear, "type"));
                ProfileFear existing = findFear(userId, type, description);
                if (existing == null) {
                    ProfileFear profile = new ProfileFear();
                    profile.setUserId(userId);
                    profile.setType(type);
                    profile.setDescription(description);
                    profile.setManifestation(truncate(field(fear, "manifestation"), 500));
                    profile.setConfidence(confidence);
                    profile.setEvidence(evidence);
                    profile.setBoundaryType(normalizeBoundaryType(field(fear, "boundaryType", "boundary_type")));
                    profile.setUpdatedAt(LocalDateTime.now());
                    fearRepository.insert(profile);
                } else {
                    existing.setManifestation(truncate(field(fear, "manifestation"), 500));
                    existing.setConfidence(max(existing.getConfidence(), confidence));
                    existing.setEvidence(evidence);
                    existing.setBoundaryType(normalizeBoundaryType(field(fear, "boundaryType", "boundary_type")));
                    existing.setUpdatedAt(LocalDateTime.now());
                    fearRepository.updateById(existing);
                }
                savedCount++;
            }
        } catch (Exception e) {
            logger.error("恐惧提取失败, userId: {}", userId, e);
        }
        return savedCount;
    }

    private void saveToVectorStore(Long userId, String userMessage, Analysis analysis, int savedCount) {
        if (this.vectorStore == null) {
            logger.warn("向量存储服务暂不可用，跳过存储, userId: {}", userId);
            return;
        }

        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", String.valueOf(userId));
            metadata.put("type", "conversation_analysis");
            metadata.put("profileRecordCount", savedCount);

            Document document = new Document(
                    buildVectorSummary(userId, userMessage, analysis, savedCount),
                    metadata);
            vectorStore.add(java.util.List.of(document));
        } catch (Exception e) {
            logger.error("向量存储失败, userId: {}", userId, e);
        }
    }

    private ProfileValues findValue(Long userId, String item) {
        return valuesRepository.selectList(new LambdaQueryWrapper<ProfileValues>()
                        .eq(ProfileValues::getUserId, userId))
                .stream()
                .filter(value -> normalizeKey(value.getItem()).equals(normalizeKey(item)))
                .findFirst()
                .orElse(null);
    }

    private ProfileEmotion findEmotion(Long userId, String emotion, String triggerDesc) {
        return emotionRepository.selectList(new LambdaQueryWrapper<ProfileEmotion>()
                        .eq(ProfileEmotion::getUserId, userId))
                .stream()
                .filter(value -> normalizeKey(value.getEmotion()).equals(normalizeKey(emotion)))
                .filter(value -> normalizeKey(value.getTriggerDesc()).equals(normalizeKey(triggerDesc)))
                .findFirst()
                .orElse(null);
    }

    private ProfileDecision findDecision(Long userId, String topic, String choice) {
        return decisionRepository.selectList(new LambdaQueryWrapper<ProfileDecision>()
                        .eq(ProfileDecision::getUserId, userId))
                .stream()
                .filter(value -> normalizeKey(value.getTopic()).equals(normalizeKey(topic)))
                .filter(value -> normalizeKey(value.getChoice()).equals(normalizeKey(choice)))
                .findFirst()
                .orElse(null);
    }

    private ProfileRelationship findRelationship(Long userId, String name) {
        return relationshipRepository.selectList(new LambdaQueryWrapper<ProfileRelationship>()
                        .eq(ProfileRelationship::getUserId, userId))
                .stream()
                .filter(value -> normalizeKey(value.getName()).equals(normalizeKey(name)))
                .findFirst()
                .orElse(null);
    }

    private ProfileFear findFear(Long userId, String type, String description) {
        return fearRepository.selectList(new LambdaQueryWrapper<ProfileFear>()
                        .eq(ProfileFear::getUserId, userId))
                .stream()
                .filter(value -> normalizeKey(value.getType()).equals(normalizeKey(type)))
                .filter(value -> normalizeKey(value.getDescription()).equals(normalizeKey(description)))
                .findFirst()
                .orElse(null);
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

    private String field(JsonNode node, String... keys) {
        return clean(text(node, keys));
    }

    private BigDecimal decimal(JsonNode node, String key, String defaultValue) {
        String value = defaultIfBlank(text(node, key), defaultValue);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return new BigDecimal(defaultValue);
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
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

    private String normalizeInfluenceLevel(String value) {
        if (value == null || value.isBlank()) {
            return "中";
        }
        String normalized = value.trim();
        if (normalized.contains("高") || normalized.equalsIgnoreCase("high")) {
            return "高";
        }
        if (normalized.contains("低") || normalized.equalsIgnoreCase("low")) {
            return "低";
        }
        return "中";
    }

    private String normalizeFearType(String value) {
        String normalized = clean(value).toLowerCase(Locale.ROOT);
        if (normalized.contains("boundary") || normalized.contains("边界")) {
            return "boundary";
        }
        return "fear";
    }

    private String normalizeBoundaryType(String value) {
        String normalized = clean(value).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.contains("hard") || normalized.contains("硬")) {
            return "hard";
        }
        if (normalized.contains("soft") || normalized.contains("软")) {
            return "soft";
        }
        return truncate(normalized, 10);
    }

    private BigDecimal max(BigDecimal first, BigDecimal second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.compareTo(second) >= 0 ? first : second;
    }

    private String normalizeKey(String value) {
        return clean(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private boolean hasDecisionContext(String userMessage) {
        String normalized = clean(userMessage).toLowerCase(Locale.ROOT);
        return DECISION_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private String evidenceJson(JsonNode node) {
        List<String> values = evidenceValues(node);
        if (values.isEmpty()) {
            return "[]";
        }

        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String evidenceSummary(JsonNode node) {
        return String.join("；", evidenceValues(node));
    }

    private List<String> evidenceValues(JsonNode node) {
        JsonNode evidence = firstNode(node, "evidence", "evidences");
        if (evidence == null || evidence.isNull()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        if (evidence.isArray()) {
            for (JsonNode item : evidence) {
                String value = clean(item.asText(""));
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        } else {
            String value = clean(evidence.asText(""));
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private JsonNode firstNode(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String withEvidence(String reason, String evidenceSummary) {
        String cleanedReason = clean(reason);
        String cleanedEvidence = clean(evidenceSummary);
        if (cleanedEvidence.isBlank()) {
            return cleanedReason;
        }
        if (cleanedReason.isBlank()) {
            return "证据：" + cleanedEvidence;
        }
        return cleanedReason + "\n证据：" + cleanedEvidence;
    }

    private String buildVectorSummary(Long userId, String userMessage, Analysis analysis, int savedCount) {
        return """
                用户ID: %s
                本轮用户消息: %s
                有效档案更新数: %d
                价值观: %s
                情绪模式: %s
                决策: %s
                关系: %s
                恐惧与边界: %s
                """.formatted(
                userId,
                userMessage,
                savedCount,
                summarizeNodes(analysis.values(), "item", "preference"),
                summarizeNodes(analysis.emotions(), "emotion", "behavior"),
                summarizeNodes(analysis.decisions(), "topic", "choice"),
                summarizeNodes(analysis.relationships(), "name", "role"),
                summarizeNodes(analysis.fears(), "type", "description"));
    }

    private String summarizeNodes(List<JsonNode> nodes, String firstKey, String secondKey) {
        if (nodes.isEmpty()) {
            return "[]";
        }
        return nodes.stream()
                .map(node -> field(node, firstKey) + ":" + field(node, secondKey))
                .toList()
                .toString();
    }
}
