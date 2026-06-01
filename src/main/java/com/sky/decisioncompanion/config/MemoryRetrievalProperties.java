package com.sky.decisioncompanion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "decision-companion.memory.retrieval")
public class MemoryRetrievalProperties {

    private int sectionLimit = 5;
    private int textMaxLength = 200;
    private int semanticTopK = 5;
    private double semanticSimilarityThreshold = 0.6;
    private Decision decision = new Decision();

    public int getSectionLimit() {
        return sectionLimit;
    }

    public void setSectionLimit(int sectionLimit) {
        this.sectionLimit = sectionLimit;
    }

    public int getTextMaxLength() {
        return textMaxLength;
    }

    public void setTextMaxLength(int textMaxLength) {
        this.textMaxLength = textMaxLength;
    }

    public int getSemanticTopK() {
        return semanticTopK;
    }

    public void setSemanticTopK(int semanticTopK) {
        this.semanticTopK = semanticTopK;
    }

    public double getSemanticSimilarityThreshold() {
        return semanticSimilarityThreshold;
    }

    public void setSemanticSimilarityThreshold(double semanticSimilarityThreshold) {
        this.semanticSimilarityThreshold = semanticSimilarityThreshold;
    }

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision == null ? new Decision() : decision;
    }

    public int sectionLimit() {
        return Math.max(1, sectionLimit);
    }

    public int textMaxLength() {
        return Math.max(20, textMaxLength);
    }

    public int semanticTopK() {
        return Math.max(1, semanticTopK);
    }

    public double semanticSimilarityThreshold() {
        return Math.max(0.0, Math.min(1.0, semanticSimilarityThreshold));
    }

    public Decision decision() {
        return decision == null ? new Decision() : decision;
    }

    public int normalizeSemanticTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return decision().defaultLimit();
        }
        return Math.min(topK, semanticTopK());
    }

    public static class Decision {

        private int defaultLimit = 3;
        private int maxLimit = 5;
        private int candidateMultiplier = 3;
        private int minTokenLength = 2;

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public int getMaxLimit() {
            return maxLimit;
        }

        public void setMaxLimit(int maxLimit) {
            this.maxLimit = maxLimit;
        }

        public int getCandidateMultiplier() {
            return candidateMultiplier;
        }

        public void setCandidateMultiplier(int candidateMultiplier) {
            this.candidateMultiplier = candidateMultiplier;
        }

        public int getMinTokenLength() {
            return minTokenLength;
        }

        public void setMinTokenLength(int minTokenLength) {
            this.minTokenLength = minTokenLength;
        }

        public int defaultLimit() {
            return Math.min(Math.max(1, defaultLimit), maxLimit());
        }

        public int maxLimit() {
            return Math.max(1, maxLimit);
        }

        public int candidateMultiplier() {
            return Math.max(1, candidateMultiplier);
        }

        public int minTokenLength() {
            return Math.max(1, minTokenLength);
        }

        public int normalizeLimit(Integer limit) {
            if (limit == null || limit <= 0) {
                return defaultLimit();
            }
            return Math.min(limit, maxLimit());
        }
    }
}
