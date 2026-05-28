package com.sky.decisioncompanion.service.memory;

import java.util.List;

public record MemoryContext(
        List<ProfileMemory> values,
        List<ProfileMemory> emotions,
        List<DecisionMemory> decisions,
        List<ProfileMemory> fears,
        List<SemanticMemory> semanticMemories,
        RetrievalMetrics metrics,
        String promptContext) {

    public MemoryContext {
        values = List.copyOf(values);
        emotions = List.copyOf(emotions);
        decisions = List.copyOf(decisions);
        fears = List.copyOf(fears);
        semanticMemories = List.copyOf(semanticMemories);
    }

    public record ProfileMemory(String subject, String content, String detail, Double confidence) {
    }

    public record DecisionMemory(String topic, String choice, String reason, String outcome, Integer satisfaction) {
    }

    public record SemanticMemory(String content, String type, Integer profileRecordCount, Double score) {
    }

    public record RetrievalMetrics(
            int valueCount,
            int emotionCount,
            int decisionCount,
            int fearCount,
            int semanticHitCount,
            Double maxSemanticScore,
            boolean vectorAvailable,
            boolean degraded) {
    }
}
