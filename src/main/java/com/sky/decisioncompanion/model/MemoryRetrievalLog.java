package com.sky.decisioncompanion.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Schema(description = "Memory RAG 召回日志")
@TableName("memory_retrieval_log")
public class MemoryRetrievalLog {

    @Schema(description = "记录ID")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "召回来源，当前自动注入召回写入 auto_prompt")
    private String retrievalSource;

    @Schema(description = "召回查询文本")
    private String queryText;

    private Integer valueCount;
    private Integer emotionCount;
    private Integer decisionCount;
    private Integer relationshipCount;
    private Integer fearCount;
    private Integer semanticHitCount;
    private BigDecimal maxSemanticScore;
    private Boolean vectorAvailable;
    private Boolean degraded;
    private Integer semanticTopK;

    @Schema(description = "召回意图")
    private String intent;

    @Schema(description = "语义检索改写后的查询文本")
    private String semanticQuery;

    @Schema(description = "语义召回候选TopK")
    private Integer semanticCandidateTopK;

    @Schema(description = "本次意图优先召回的记忆类型(JSON)")
    private String preferredMemoryTypes;

    private BigDecimal semanticSimilarityThreshold;

    @Schema(description = "Top 语义命中摘要(JSON)")
    private String semanticHitSummary;

    @Schema(description = "注入 Prompt 的长期记忆背景长度")
    private Integer promptContextLength;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
