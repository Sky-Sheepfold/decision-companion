package com.sky.decisioncompanion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import com.sky.decisioncompanion.service.profile.ProfileAnalysisParser;
import com.sky.decisioncompanion.service.profile.ProfileAnalysisParser.Analysis;
import com.sky.decisioncompanion.service.profile.ProfileMemoryGovernanceService;
import com.sky.decisioncompanion.service.profile.ProfileWritePolicy;
import com.sky.decisioncompanion.service.memory.ProfileSceneMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ProfileExtractService {

    private static final Logger logger = LoggerFactory.getLogger(ProfileExtractService.class);
    private static final List<String> DECISION_KEYWORDS = List.of(
            "决定", "决策", "选择", "纠结", "要不要", "offer", "离职", "转行", "工作", "城市", "学校");

    private final ChatClient chatClient;
    private final ProfileDecisionRepository decisionRepository;
    private final ProfileMemoryGovernanceService profileMemoryGovernanceService;
    private final ProfileAnalysisParser analysisParser;

    public ProfileExtractService(
            ChatClient.Builder chatClientBuilder,
            ProfileValuesRepository valuesRepository,
            ProfileDecisionRepository decisionRepository,
            ProfileEmotionRepository emotionRepository,
            ProfileRelationshipRepository relationshipRepository,
            ProfileFearRepository fearRepository,
            ProfileSceneMemoryService profileSceneMemoryService,
            ProfileMemoryGovernanceService profileMemoryGovernanceService) {
        this.chatClient = chatClientBuilder.build();
        this.decisionRepository = decisionRepository;
        this.profileMemoryGovernanceService = profileMemoryGovernanceService;
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

            int savedCount = saveAnalysis(userId, userMessage, analysis);
            logger.info("档案提炼完成, userId: {}, savedCount: {}", userId, savedCount);
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
                    "emotions": [{"emotion": "情绪类型", "behavior": "行为表现", "trigger": "触发场景", "confidence": 0.8, "evidence": ["证据短句"]}],
                    "decisions": [{"topic": "决策主题", "choice": "选择", "reason": "原因", "evidence": ["证据短句"]}],
                    "relationships": [{"name": "关系人", "role": "角色", "influenceLevel": "高/中/低", "influenceStyle": "影响方式", "note": "备注", "confidence": 0.8, "evidence": ["证据短句"]}],
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
        savedCount += saveValues(userId, userMessage, analysis.values());
        savedCount += saveEmotions(userId, userMessage, analysis.emotions());
        savedCount += saveDecisions(userId, userMessage, analysis.decisions());
        savedCount += saveRelationships(userId, userMessage, analysis.relationships());
        savedCount += saveFears(userId, userMessage, analysis.fears());

        return savedCount;
    }

    private int saveValues(Long userId, String userMessage, List<JsonNode> values) {
        int savedCount = 0;
        try {
            for (JsonNode value : values) {
                String item = truncate(field(value, "item"), 100);
                String preference = truncate(field(value, "preference"), 200);
                BigDecimal confidence = decimal(value, "confidence", "0");
                if (item.isBlank() || preference.isBlank()) {
                    continue;
                }

                savedCount += routeGovernedProfile(
                        userId, userMessage, "value", item, preference, "", confidence, evidenceValues(value));
            }
        } catch (Exception e) {
            logger.error("价值观提取失败, userId: {}", userId, e);
        }
        return savedCount;
    }

    private int saveEmotions(Long userId, String userMessage, List<JsonNode> emotions) {
        int savedCount = 0;
        try {
            for (JsonNode emotion : emotions) {
                String emotionName = truncate(field(emotion, "emotion"), 100);
                String behavior = truncate(field(emotion, "behavior"), 500);
                String trigger = truncate(field(emotion, "trigger", "triggerDesc", "trigger_desc"), 200);
                BigDecimal confidence = decimal(emotion, "confidence", "0");
                if (emotionName.isBlank() || behavior.isBlank()) {
                    continue;
                }

                savedCount += routeGovernedProfile(
                        userId, userMessage, "emotion", emotionName, behavior, trigger, confidence, evidenceValues(emotion));
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

    private int saveRelationships(Long userId, String userMessage, List<JsonNode> relationships) {
        int savedCount = 0;
        try {
            for (JsonNode relationship : relationships) {
                String name = truncate(field(relationship, "name"), 50);
                if (name.isBlank()) {
                    continue;
                }

                String rawInfluence = field(relationship, "influence");
                String influenceStyle = field(relationship, "influenceStyle", "influence_style");
                if (influenceStyle.isBlank()) {
                    influenceStyle = rawInfluence;
                }
                BigDecimal confidence = decimal(relationship, "confidence", "0");
                String role = truncate(field(relationship, "role"), 50);
                String note = truncate(defaultIfBlank(
                        field(relationship, "note"),
                        defaultIfBlank(influenceStyle, defaultIfBlank(role, evidenceSummary(relationship)))), 500);
                if (note.isBlank()) {
                    continue;
                }

                savedCount += routeGovernedProfile(
                        userId, userMessage, "relationship", name, note, role, confidence, evidenceValues(relationship));
            }
        } catch (Exception e) {
            logger.error("关系提取失败, userId: {}", userId, e);
        }
        return savedCount;
    }

    private int saveFears(Long userId, String userMessage, List<JsonNode> fears) {
        int savedCount = 0;
        try {
            for (JsonNode fear : fears) {
                String description = truncate(field(fear, "description"), 500);
                BigDecimal confidence = decimal(fear, "confidence", "0");
                List<String> evidence = evidenceValues(fear);
                String type = normalizeFearType(field(fear, "type"));
                if (description.isBlank() || evidence.isEmpty()) {
                    continue;
                }

                String manifestation = truncate(defaultIfBlank(field(fear, "manifestation"), evidenceSummary(fear)), 500);
                savedCount += routeGovernedProfile(
                        userId,
                        userMessage,
                        type,
                        description,
                        manifestation,
                        normalizeBoundaryType(field(fear, "boundaryType", "boundary_type")),
                        confidence,
                        evidence);
            }
        } catch (Exception e) {
            logger.error("恐惧提取失败, userId: {}", userId, e);
        }
        return savedCount;
    }

    private int routeGovernedProfile(
            Long userId,
            String userMessage,
            String profileType,
            String subject,
            String content,
            String detail,
            BigDecimal confidence,
            List<String> evidence) {
        ProfileWritePolicy.Decision decision = ProfileWritePolicy.decide(profileType, confidence);
        if ("skipped".equals(decision.action())) {
            logger.info("自动提炼跳过画像写入, userId: {}, profileType: {}, subject: {}, action: {}, reason: {}",
                    userId, profileType, subject, decision.action(), decision.logSummary());
            return 0;
        }
        if ("needs_confirmation".equals(decision.action())) {
            profileMemoryGovernanceService.createCandidate(
                    new ProfileMemoryGovernanceService.MemoryCandidateCommand(
                            userId,
                            profileType,
                            subject,
                            content,
                            detail,
                            confidence,
                            evidence,
                            "profile_extract",
                            null));
            return 0;
        }
        if ("written".equals(decision.action())) {
            ProfileMemoryGovernanceService.GovernanceResult result =
                    profileMemoryGovernanceService.writeConfirmedMemory(
                            new ProfileMemoryGovernanceService.ConfirmedMemoryCommand(
                                    userId,
                                    profileType,
                                    subject,
                                    content,
                                    detail,
                                    confidence,
                                    evidence,
                                    "profile_extract",
                                    null,
                                    userMessage));
            return result != null && result.success() ? 1 : 0;
        }
        return 0;
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

    private String normalizeKey(String value) {
        return clean(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private boolean hasDecisionContext(String userMessage) {
        String normalized = clean(userMessage).toLowerCase(Locale.ROOT);
        return DECISION_KEYWORDS.stream().anyMatch(normalized::contains);
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

}
