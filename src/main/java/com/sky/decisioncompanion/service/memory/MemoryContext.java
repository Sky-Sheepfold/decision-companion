package com.sky.decisioncompanion.service.memory;

import java.util.List;

public record MemoryContext(
        List<ProfileMemory> values,
        List<ProfileMemory> emotions,
        List<DecisionMemory> decisions,
        List<RelationshipMemory> relationships,
        List<ProfileMemory> fears,
        List<ProfileMemory> coreProfiles,
        List<SemanticMemory> semanticMemories,
        List<AwarenessMemory> awareness,
        RetrievalMetrics metrics,
        String promptContext) {

    public MemoryContext {
        values = List.copyOf(values);
        emotions = List.copyOf(emotions);
        decisions = List.copyOf(decisions);
        relationships = List.copyOf(relationships);
        fears = List.copyOf(fears);
        coreProfiles = List.copyOf(coreProfiles);
        semanticMemories = List.copyOf(semanticMemories);
        awareness = List.copyOf(awareness);
    }

    public record ProfileMemory(String subject, String content, String detail, Double confidence) {
    }

    public record DecisionMemory(String topic, String choice, String reason, String outcome, Integer satisfaction) {
    }

    public record RelationshipMemory(
            String name,
            String role,
            String influenceLevel,
            String influenceStyle,
            String note) {
    }

    public record SemanticMemory(
            String content,
            String type,
            Integer profileRecordCount,
            Double score,
            Double rerankScore,
            String rerankReason) {

        public SemanticMemory(String content, String type, Integer profileRecordCount, Double score) {
            this(content, type, profileRecordCount, score, null, "");
        }

        public SemanticMemory {
            rerankReason = rerankReason == null ? "" : rerankReason;
        }
    }

    /** 近期觉察（Awareness）记忆：近因层，由 LLM 从近期对话提炼。 */
    public record AwarenessMemory(
            String date,
            String observation,
            String trend,
            String emotionGuess) {
    }

    public record RetrievalMetrics(
            int valueCount,
            int emotionCount,
            int decisionCount,
            int relationshipCount,
            int fearCount,
            int semanticHitCount,
            Double maxSemanticScore,
            boolean vectorAvailable,
            boolean degraded) {
    }
}
