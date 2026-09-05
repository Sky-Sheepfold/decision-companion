package com.sky.decisioncompanion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "decision-companion.memory.retrieval")
public class MemoryRetrievalProperties {

    private int sectionLimit = 5;
    private int textMaxLength = 200;
    private int semanticTopK = 5;
    private double semanticSimilarityThreshold = 0.3;
    private int semanticTypeQuota = 2;
    private int insightLimit = 3;
    private Decision decision = new Decision();
    private Core core = new Core();

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

    public int getSemanticTypeQuota() {
        return semanticTypeQuota;
    }

    public void setSemanticTypeQuota(int semanticTypeQuota) {
        this.semanticTypeQuota = semanticTypeQuota;
    }

    public int getInsightLimit() {
        return insightLimit;
    }

    public void setInsightLimit(int insightLimit) {
        this.insightLimit = insightLimit;
    }

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision == null ? new Decision() : decision;
    }

    public Core getCore() {
        return core;
    }

    public void setCore(Core core) {
        this.core = core == null ? new Core() : core;
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

    /** 每类语义记忆最多返回多少条（0 表示不限），用于召回多样性。 */
    public int semanticTypeQuota() {
        return Math.max(0, semanticTypeQuota);
    }

    /** 注入 prompt 的最多行为动机洞察条数。 */
    public int insightLimit() {
        return Math.max(0, insightLimit);
    }

    public Decision decision() {
        return decision == null ? new Decision() : decision;
    }

    /** 核心稳定画像（Core Memory）配置。 */
    public Core core() {
        return core == null ? new Core() : core;
    }

    public static class Core {

        /** 进入核心稳定画像的置信度门槛。 */
        private double confidenceThreshold = 0.85;

        /** 核心价值观最多恒定注入条数。 */
        private int valueLimit = 3;

        /** 核心恐惧/边界最多恒定注入条数。 */
        private int fearLimit = 2;

        /** 核心稳定画像总条数上限。 */
        private int totalLimit = 5;

        public double getConfidenceThreshold() {
            return confidenceThreshold;
        }

        public void setConfidenceThreshold(double confidenceThreshold) {
            this.confidenceThreshold = confidenceThreshold;
        }

        public int getValueLimit() {
            return valueLimit;
        }

        public void setValueLimit(int valueLimit) {
            this.valueLimit = valueLimit;
        }

        public int getFearLimit() {
            return fearLimit;
        }

        public void setFearLimit(int fearLimit) {
            this.fearLimit = fearLimit;
        }

        public int getTotalLimit() {
            return totalLimit;
        }

        public void setTotalLimit(int totalLimit) {
            this.totalLimit = totalLimit;
        }

        public double confidenceThreshold() {
            return Math.max(0.0, Math.min(1.0, confidenceThreshold));
        }

        public int valueLimit() {
            return Math.max(0, valueLimit);
        }

        public int fearLimit() {
            return Math.max(0, fearLimit);
        }

        public int totalLimit() {
            return Math.max(1, totalLimit);
        }
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
