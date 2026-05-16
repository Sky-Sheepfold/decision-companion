package com.sky.decisioncompanion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.decisioncompanion.model.*;
import com.sky.decisioncompanion.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class ProfileExtractService {

    private static final Logger logger = LoggerFactory.getLogger(ProfileExtractService.class);

    private final ChatClient chatClient;
    private final ProfileValuesRepository valuesRepository;
    private final ProfileDecisionRepository decisionRepository;
    private final ProfileEmotionRepository emotionRepository;
    private final ProfileRelationshipRepository relationshipRepository;
    private final ProfileFearRepository fearRepository;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

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
    }

    @Async
    public void extractAndSave(Long userId, String sessionId, String userMessage, String aiResponse) {
        try {
            String analysisPrompt = buildAnalysisPrompt(userMessage, aiResponse);
            String analysis = chatClient.prompt()
                    .messages(new UserMessage(analysisPrompt))
                    .call()
                    .content();

            extractValues(userId, analysis);
            extractEmotions(userId, analysis);
            extractDecisions(userId, userMessage, analysis);
            extractRelationships(userId, analysis);
            extractFears(userId, analysis);

            saveToVectorStore(userId, userMessage, analysis);
        } catch (Exception e) {
            logger.error("档案提炼失败, userId: {}, sessionId: {}", userId, sessionId, e);
        }
    }

    private String buildAnalysisPrompt(String userMessage, String aiResponse) {
        return """
                请分析以下对话，提取用户的价值观、情绪模式、决策、关系和恐惧等信息。

                用户消息：%s

                AI回复：%s

                请以JSON格式返回分析结果，包含以下字段（如没有相关信息则为空数组）：
                {
                    "values": [{"item": "价值观维度", "preference": "倾向描述", "confidence": 0.8}],
                    "emotions": [{"emotion": "情绪类型", "behavior": "行为表现", "trigger": "触发场景"}],
                    "decisions": [{"topic": "决策主题", "choice": "选择", "reason": "原因"}],
                    "relationships": [{"name": "关系人", "role": "角色", "influence": "影响"}],
                    "fears": [{"type": "fear/boundary", "description": "描述", "confidence": 0.7}]
                }
                """.formatted(userMessage, aiResponse);
    }

    private void extractValues(Long userId, String analysis) {
        try {
            if (analysis.contains("\"values\":")) {
                String valuesJson = extractJsonArray(analysis, "values");
                var values = objectMapper.readTree(valuesJson);
                for (var value : values) {
                    ProfileValues profile = new ProfileValues();
                    profile.setUserId(userId);
                    profile.setItem(value.get("item").asText());
                    profile.setPreference(value.get("preference").asText());
                    profile.setConfidence(new BigDecimal(value.get("confidence").asText()));
                    profile.setEvidence("{}");
                    profile.setUpdatedAt(LocalDateTime.now());
                    valuesRepository.insert(profile);
                }
            }
        } catch (Exception e) {
            logger.error("价值观提取失败, userId: {}", userId, e);
        }
    }

    private void extractEmotions(Long userId, String analysis) {
        try {
            if (analysis.contains("\"emotions\":")) {
                String emotionsJson = extractJsonArray(analysis, "emotions");
                var emotions = objectMapper.readTree(emotionsJson);
                for (var emotion : emotions) {
                    ProfileEmotion profile = new ProfileEmotion();
                    profile.setUserId(userId);
                    profile.setEmotion(emotion.get("emotion").asText());
                    profile.setBehavior(emotion.get("behavior").asText());
                    profile.setTriggerDesc(emotion.has("trigger") ? emotion.get("trigger").asText() : "");
                    profile.setUpdatedAt(LocalDateTime.now());
                    emotionRepository.insert(profile);
                }
            }
        } catch (Exception e) {
            logger.error("情绪模式提取失败, userId: {}", userId, e);
        }
    }

    private void extractDecisions(Long userId, String userMessage, String analysis) {
        try {
            if (analysis.contains("\"decisions\":") && (userMessage.contains("决定") || userMessage.contains("选择"))) {
                String decisionsJson = extractJsonArray(analysis, "decisions");
                var decisions = objectMapper.readTree(decisionsJson);
                for (var decision : decisions) {
                    ProfileDecision profile = new ProfileDecision();
                    profile.setUserId(userId);
                    profile.setTopic(decision.get("topic").asText());
                    profile.setChoice(decision.get("choice").asText());
                    profile.setReason(decision.has("reason") ? decision.get("reason").asText() : "");
                    profile.setCreatedAt(LocalDateTime.now());
                    decisionRepository.insert(profile);
                }
            }
        } catch (Exception e) {
            logger.error("决策提取失败, userId: {}", userId, e);
        }
    }

    private void extractRelationships(Long userId, String analysis) {
        try {
            if (analysis.contains("\"relationships\":")) {
                String relationshipsJson = extractJsonArray(analysis, "relationships");
                var relationships = objectMapper.readTree(relationshipsJson);
                for (var relationship : relationships) {
                    ProfileRelationship profile = new ProfileRelationship();
                    profile.setUserId(userId);
                    profile.setName(relationship.get("name").asText());
                    profile.setRole(relationship.get("role").asText());
                    profile.setInfluenceLevel(
                            relationship.has("influence") ? relationship.get("influence").asText() : "中");
                    profile.setUpdatedAt(LocalDateTime.now());
                    relationshipRepository.insert(profile);
                }
            }
        } catch (Exception e) {
            logger.error("关系提取失败, userId: {}", userId, e);
        }
    }

    private void extractFears(Long userId, String analysis) {
        try {
            if (analysis.contains("\"fears\":")) {
                String fearsJson = extractJsonArray(analysis, "fears");
                var fears = objectMapper.readTree(fearsJson);
                for (var fear : fears) {
                    ProfileFear profile = new ProfileFear();
                    profile.setUserId(userId);
                    profile.setType(fear.get("type").asText());
                    profile.setDescription(fear.get("description").asText());
                    profile.setConfidence(
                            new BigDecimal(fear.has("confidence") ? fear.get("confidence").asText() : "0.5"));
                    profile.setEvidence("{}");
                    profile.setUpdatedAt(LocalDateTime.now());
                    fearRepository.insert(profile);
                }
            }
        } catch (Exception e) {
            logger.error("恐惧提取失败, userId: {}", userId, e);
        }
    }

    private void saveToVectorStore(Long userId, String userMessage, String analysis) {
        if (this.vectorStore == null) {
            logger.warn("向量存储服务暂不可用，跳过存储, userId: {}", userId);
            return;
        }

        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", String.valueOf(userId));
            metadata.put("type", "conversation_analysis");

            org.springframework.ai.document.Document document = new org.springframework.ai.document.Document(
                    "用户ID: " + userId + "\n用户消息: " + userMessage + "\n分析: " + analysis,
                    metadata);
            vectorStore.add(java.util.List.of(document));
        } catch (Exception e) {
            logger.error("向量存储失败, userId: {}", userId, e);
        }
    }

    private String extractJsonArray(String text, String key) {
        int start = text.indexOf("\"" + key + "\":");
        if (start == -1)
            return "[]";
        start = text.indexOf("[", start);
        if (start == -1)
            return "[]";
        int end = text.indexOf("]", start);
        if (end == -1)
            return "[]";
        return text.substring(start, end + 1);
    }
}
