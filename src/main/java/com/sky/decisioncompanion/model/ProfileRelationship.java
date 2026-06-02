package com.sky.decisioncompanion.model;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Schema(description = "关系图谱实体")
@TableName("profile_relationship")
public class ProfileRelationship {

    @Schema(description = "记录ID")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "是否有效")
    private Boolean active;

    @Schema(description = "关系人姓名")
    private String name;

    @Schema(description = "关系角色")
    private String role;

    @Schema(description = "影响力等级")
    private String influenceLevel;

    @Schema(description = "影响方式")
    private String influenceStyle;

    @Schema(description = "备注")
    private String note;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
