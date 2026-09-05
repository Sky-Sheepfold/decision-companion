package com.sky.decisioncompanion.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 近期觉察（Awareness）记忆。
 *
 * <p>由 LLM 从近期对话中提炼的结构化观察（对齐 OpenBiliClaw 的 Awareness 层），
 * 作为"近因层"注入易变块（user message），与稳定核心画像（Core）和场景记忆（Episodic）互补。
 */
@Data
@NoArgsConstructor
@Schema(description = "近期觉察记忆")
@TableName("memory_awareness")
public class MemoryAwareness {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 观察结论（核心内容）。 */
    private String observation;

    /** 这暗示的趋势。 */
    private String trend;

    /** 猜测的情绪状态。 */
    private String emotionGuess;

    /** 观察日期，用于同日去重与时效。 */
    private LocalDate awareDate;

    /** 来源消息 ID（证据链，逗号分隔）。 */
    private String sourceMessageIds;

    /** 是否为整批近似归属（未做到逐条精确溯源）。 */
    private Boolean sourceApproximate;

    private Boolean active;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
