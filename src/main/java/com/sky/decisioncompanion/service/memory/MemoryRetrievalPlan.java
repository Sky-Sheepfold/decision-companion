package com.sky.decisioncompanion.service.memory;

import java.util.List;

public record MemoryRetrievalPlan(
        MemoryRetrievalIntent intent,
        int valuesLimit,
        int emotionsLimit,
        int decisionsLimit,
        int relationshipsLimit,
        int fearsLimit,
        int semanticTopK,
        int semanticCandidateTopK,
        String semanticQuery,
        List<String> preferredMemoryTypes,
        List<Section> promptOrder) {

    public MemoryRetrievalPlan {
        semanticQuery = semanticQuery == null ? "" : semanticQuery.trim();
        preferredMemoryTypes = List.copyOf(preferredMemoryTypes);
        promptOrder = List.copyOf(promptOrder);
    }

    public enum Section {
        VALUES,
        EMOTIONS,
        DECISIONS,
        RELATIONSHIPS,
        SEMANTIC_MEMORIES,
        FEARS
    }
}
