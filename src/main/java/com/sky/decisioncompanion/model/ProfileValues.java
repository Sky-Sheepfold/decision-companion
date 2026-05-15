package com.sky.decisioncompanion.model;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Schema(description = "价值观档案实体")
@TableName("profile_values")
public class ProfileValues {

    @Schema(description = "记录ID")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "价值维度")
    private String item;

    @Schema(description = "倾向描述")
    private String preference;

    @Schema(description = "置信度")
    private BigDecimal confidence;

    @Schema(description = "支撑证据(JSON)")
    private String evidence;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public ProfileValues(Long userId, String item, String preference, BigDecimal confidence) {
        this.userId = userId;
        this.item = item;
        this.preference = preference;
        this.confidence = confidence;
    }
}
