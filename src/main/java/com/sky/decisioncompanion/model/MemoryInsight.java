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

/**
 * 行为动机洞察（Insight）记忆。
 *
 * <p>对齐 OpenBiliClaw 的 Insight 层：从近期觉察（Awareness）观察之上提炼的"行为动机解释假设"，
 * 回答"他为什么处于这个状态"。假设必须配用户判定闭环（verdict：confirmed / rejected / 未判），
 * 已确认的洞察才作为较可信的理解沉淀，被否定的洞察保留但不再参与召回。
 */
@Data
@NoArgsConstructor
@Schema(description = "行为动机洞察")
@TableName("memory_insight")
public class MemoryInsight {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 行为动机假设（核心内容）。 */
    private String hypothesis;

    /** 支撑证据（Awareness 观察，JSON 数组）。 */
    private String evidence;

    /** 置信度 0.00~1.00。 */
    private BigDecimal confidence;

    /** 用户判定：""（未判）/ confirmed（已确认）/ rejected（已否定）。 */
    private String verdict;

    /** 来源觉察 ID（证据链，逗号分隔）。 */
    private String sourceAwarenessIds;

    /** 是否有效（rejected 后置为 false，保留审计与防重生）。 */
    private Boolean active;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
