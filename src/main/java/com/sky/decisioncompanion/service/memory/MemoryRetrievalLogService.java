package com.sky.decisioncompanion.service.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.decisioncompanion.model.MemoryRetrievalLog;
import com.sky.decisioncompanion.repository.MemoryRetrievalLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class MemoryRetrievalLogService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryRetrievalLogService.class);
    private static final int QUERY_MAX_LENGTH = 1000;
    private static final int SUMMARY_CONTENT_MAX_LENGTH = 200;
    private static final int SUMMARY_MAX_HITS = 3;

    private final MemoryRetrievalLogRepository repository;
    private final ObjectMapper objectMapper;

    public MemoryRetrievalLogService(MemoryRetrievalLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void recordAutoRecall(
            Long userId,
            String query,
            MemoryContext context,
            int semanticTopK,
            double semanticSimilarityThreshold) {
        if (userId == null || context == null) {
            return;
        }

        try {
            MemoryContext.RetrievalMetrics metrics = context.metrics();
            MemoryRetrievalLog log = new MemoryRetrievalLog();
            log.setUserId(userId);
            log.setRetrievalSource("auto_prompt");
            log.setQueryText(truncate(query, QUERY_MAX_LENGTH));
            log.setValueCount(metrics.valueCount());
            log.setEmotionCount(metrics.emotionCount());
            log.setDecisionCount(metrics.decisionCount());
            log.setRelationshipCount(metrics.relationshipCount());
            log.setFearCount(metrics.fearCount());
            log.setSemanticHitCount(metrics.semanticHitCount());
            log.setMaxSemanticScore(toScore(metrics.maxSemanticScore()));
            log.setVectorAvailable(metrics.vectorAvailable());
            log.setDegraded(metrics.degraded());
            log.setSemanticTopK(semanticTopK);
            log.setSemanticSimilarityThreshold(toThreshold(semanticSimilarityThreshold));
            log.setSemanticHitSummary(buildSemanticHitSummary(context.semanticMemories()));
            log.setPromptContextLength(context.promptContext() == null ? 0 : context.promptContext().length());
            int rows = repository.insert(log);
            logger.info("Memory RAG 召回日志已写入, userId: {}, semanticHitCount: {}, maxSemanticScore: {}, degraded: {}, rows: {}",
                    userId, log.getSemanticHitCount(), log.getMaxSemanticScore(), log.getDegraded(), rows);
        } catch (Exception e) {
            logger.warn("Memory RAG 召回日志写入失败, userId: {}", userId, e);
        }
    }

    private String buildSemanticHitSummary(List<MemoryContext.SemanticMemory> semanticMemories) {
        if (semanticMemories == null || semanticMemories.isEmpty()) {
            return "[]";
        }
        List<SemanticHitSummary> summaries = semanticMemories.stream()
                .limit(SUMMARY_MAX_HITS)
                .map(memory -> new SemanticHitSummary(
                        truncate(memory.content(), SUMMARY_CONTENT_MAX_LENGTH),
                        truncate(memory.type(), 80),
                        memory.profileRecordCount(),
                        memory.score()))
                .toList();
        try {
            return objectMapper.writeValueAsString(summaries);
        } catch (JsonProcessingException e) {
            logger.warn("Memory RAG 语义命中摘要序列化失败", e);
            return "[]";
        }
    }

    private BigDecimal toScore(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal toThreshold(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record SemanticHitSummary(
            String content,
            String type,
            Integer profileRecordCount,
            Double score) {
    }
}
