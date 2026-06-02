package com.sky.decisioncompanion.service.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProfileSceneMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ProfileSceneMemoryService.class);
    private static final String VECTOR_MEMORY_TYPE = "conversation_scene";
    private static final String VECTOR_MEMORY_ROLE = "scene_evidence";

    private final VectorStore vectorStore;

    public ProfileSceneMemoryService(@Autowired(required = false) @Nullable VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void saveSceneMemory(SceneMemoryWrite memory) {
        if (memory == null || memory.userId() == null || memory.profileRecordCount() <= 0) {
            return;
        }
        if (this.vectorStore == null) {
            logger.warn("向量存储服务暂不可用，跳过存储, userId: {}", memory.userId());
            return;
        }

        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", String.valueOf(memory.userId()));
            metadata.put("type", VECTOR_MEMORY_TYPE);
            metadata.put("memoryRole", VECTOR_MEMORY_ROLE);
            metadata.put("source", defaultIfBlank(memory.source(), "profile_extract"));
            metadata.put("profileRecordCount", memory.profileRecordCount());
            if (StringUtils.hasText(memory.memoryType())) {
                metadata.put("memoryType", memory.memoryType());
            }
            if (!memory.profileTypes().isEmpty()) {
                metadata.put("profileTypes", String.join(",", memory.profileTypes()));
            }
            if (memory.confidence() != null) {
                metadata.put("confidence", memory.confidence().doubleValue());
            }
            if (memory.sourceConversationId() != null) {
                metadata.put("sourceConversationId", String.valueOf(memory.sourceConversationId()));
            }
            metadata.put("createdAt", LocalDateTime.now().toString());

            Document document = new Document(buildSceneMemorySummary(memory), metadata);
            vectorStore.add(List.of(document));
        } catch (Exception e) {
            logger.error("向量存储失败, userId: {}", memory.userId(), e);
        }
    }

    private String buildSceneMemorySummary(SceneMemoryWrite memory) {
        return """
                场景记忆（用于相似情境召回，不作为结构化画像结论）
                用户表达: %s
                关键证据: %s
                关联画像类型: %s
                场景线索: %s
                有效档案更新数: %d
                """.formatted(
                truncate(memory.userMessage(), 500),
                formatList(memory.evidence(), 8),
                formatProfileTypes(memory.profileTypes()),
                formatList(memory.sceneSignals(), 8),
                memory.profileRecordCount());
    }

    private String formatProfileTypes(List<String> profileTypes) {
        if (profileTypes.isEmpty()) {
            return "[]";
        }
        return String.join(", ", profileTypes);
    }

    private String formatList(List<String> values, int limit) {
        if (values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .limit(limit)
                .toList()
                .toString();
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String truncate(String value, int maxLength) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isBlank()) {
            return "";
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    public record SceneMemoryWrite(
            Long userId,
            String userMessage,
            String source,
            int profileRecordCount,
            List<String> profileTypes,
            List<String> evidence,
            List<String> sceneSignals,
            String memoryType,
            BigDecimal confidence,
            Long sourceConversationId) {

        public SceneMemoryWrite {
            userMessage = userMessage == null ? "" : userMessage.trim();
            source = source == null ? "" : source.trim();
            profileTypes = cleanList(profileTypes);
            evidence = cleanList(evidence);
            sceneSignals = cleanList(sceneSignals);
            memoryType = memoryType == null ? "" : memoryType.trim();
        }
    }
}
