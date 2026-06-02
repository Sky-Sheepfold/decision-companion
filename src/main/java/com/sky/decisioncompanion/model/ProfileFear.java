package com.sky.decisioncompanion.model;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Schema(description = "恐惧与边界实体")
@TableName("profile_fear")
public class ProfileFear {

    @Schema(description = "记录ID")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "是否有效")
    private Boolean active;

    @Schema(description = "类型 fear/boundary")
    private String type;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "表现形式")
    private String manifestation;

    @Schema(description = "置信度")
    private BigDecimal confidence;

    @Schema(description = "证据(JSON)")
    private String evidence;

    @Schema(description = "边界类型 hard/soft")
    private String boundaryType;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
