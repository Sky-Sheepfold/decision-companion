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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ProfileSceneMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ProfileSceneMemoryService.class);
    private static final String VECTOR_MEMORY_TYPE = "conversation_scene";
    private static final String VECTOR_MEMORY_ROLE = "scene_evidence";
    private static final String VECTOR_CHUNK_TYPE = "typed_memory";

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
            List<Document> documents = buildChunks(memory).stream()
                    .map(chunk -> buildDocument(memory, chunk))
                    .toList();
            vectorStore.add(documents);
        } catch (Exception e) {
            logger.error("向量存储失败, userId: {}", memory.userId(), e);
        }
    }

    private List<SceneMemoryChunk> buildChunks(SceneMemoryWrite memory) {
        if (!memory.sceneSignals().isEmpty()) {
            return indexedChunks(memory.sceneSignals(), memory, true);
        }
        if (!memory.evidence().isEmpty()) {
            return indexedChunks(memory.evidence(), memory, false);
        }
        return List.of(new SceneMemoryChunk(
                fallbackMemoryType(memory),
                memory.userMessage(),
                List.of(),
                0));
    }

    private List<SceneMemoryChunk> indexedChunks(
            List<String> payloads,
            SceneMemoryWrite memory,
            boolean sceneSignal) {
        Map<String, SceneMemoryChunk> chunksByNormalizedPayload = new LinkedHashMap<>();
        for (String payload : payloads) {
            String normalizedPayload = normalizePayload(payload);
            if (!StringUtils.hasText(normalizedPayload) || chunksByNormalizedPayload.containsKey(normalizedPayload)) {
                continue;
            }
            String memoryType = sceneSignal
                    ? signalMemoryType(payload, memory)
                    : fallbackMemoryType(memory);
            List<String> evidence = sceneSignal ? memory.evidence() : List.of(payload);
            chunksByNormalizedPayload.put(
                    normalizedPayload,
                    new SceneMemoryChunk(memoryType, payload, evidence, chunksByNormalizedPayload.size()));
        }
        if (!chunksByNormalizedPayload.isEmpty()) {
            return new ArrayList<>(chunksByNormalizedPayload.values());
        }
        return List.of(new SceneMemoryChunk(
                fallbackMemoryType(memory),
                memory.userMessage(),
                List.of(),
                0));
    }

    private Document buildDocument(SceneMemoryWrite memory, SceneMemoryChunk chunk) {
        String evidenceHash = sha256(normalizePayload(chunk.payload()));
        String dedupeKey = "profile-scene:%s:%s:%s".formatted(memory.userId(), chunk.memoryType(), evidenceHash);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", String.valueOf(memory.userId()));
        metadata.put("type", VECTOR_MEMORY_TYPE);
        metadata.put("memoryRole", VECTOR_MEMORY_ROLE);
        metadata.put("source", defaultIfBlank(memory.source(), "profile_extract"));
        metadata.put("profileRecordCount", memory.profileRecordCount());
        metadata.put("memoryType", chunk.memoryType());
        metadata.put("chunkType", VECTOR_CHUNK_TYPE);
        metadata.put("chunkIndex", chunk.index());
        metadata.put("evidenceHash", evidenceHash);
        metadata.put("dedupeKey", dedupeKey);
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

        return Document.builder()
                .id(dedupeKey)
                .text(buildChunkSummary(memory, chunk))
                .metadata(metadata)
                .build();
    }

    private String buildChunkSummary(SceneMemoryWrite memory, SceneMemoryChunk chunk) {
        return """
                场景记忆片段（用于相似情境召回，不作为结构化画像结论）
                记忆类型: %s
                用户表达: %s
                关键证据: %s
                场景线索: %s
                """.formatted(
                chunk.memoryType(),
                truncate(memory.userMessage(), 500),
                formatList(chunk.evidence(), 8),
                truncate(chunk.payload(), 500));
    }

    private String signalMemoryType(String signal, SceneMemoryWrite memory) {
        int delimiterIndex = signal.indexOf(':');
        if (delimiterIndex > 0) {
            String memoryType = normalizeMemoryType(signal.substring(0, delimiterIndex));
            if (StringUtils.hasText(memoryType)) {
                return memoryType;
            }
        }
        return fallbackMemoryType(memory);
    }

    private String fallbackMemoryType(SceneMemoryWrite memory) {
        String explicitType = normalizeMemoryType(memory.memoryType());
        if (StringUtils.hasText(explicitType)) {
            return explicitType;
        }
        List<String> profileTypes = memory.profileTypes().stream()
                .map(this::normalizeMemoryType)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return profileTypes.size() == 1 ? profileTypes.get(0) : "mixed";
    }

    private String normalizeMemoryType(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "value", "values" -> "value";
            case "emotion", "emotions" -> "emotion";
            case "decision", "decisions" -> "decision";
            case "relationship", "relationships" -> "relationship";
            case "fear", "fears", "boundary", "boundaries" -> "fear";
            case "mixed" -> "mixed";
            default -> "";
        };
    }

    private String normalizePayload(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
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

    private record SceneMemoryChunk(
            String memoryType,
            String payload,
            List<String> evidence,
            int index) {
    }
}
