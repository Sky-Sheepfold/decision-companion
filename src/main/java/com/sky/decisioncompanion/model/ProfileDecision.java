package com.sky.decisioncompanion.model;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Schema(description = "决策历史实体")
@TableName("profile_decision")
public class ProfileDecision {

    @Schema(description = "记录ID")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "决策主题")
    private String topic;

    @Schema(description = "做出的选择")
    private String choice;

    @Schema(description = "决策原因")
    private String reason;

    @Schema(description = "决策结果")
    private String outcome;

    @Schema(description = "满意度")
    private Integer satisfaction;

    @Schema(description = "标签(JSON)")
    private String tags;

    @Schema(description = "决策日期")
    private String decisionDate;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
