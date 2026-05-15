package com.sky.decisioncompanion.model;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Schema(description = "情绪模式实体")
@TableName("profile_emotion")
public class ProfileEmotion {

    @Schema(description = "记录ID")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "触发描述")
    private String triggerDesc;

    @Schema(description = "情绪类型")
    private String emotion;

    @Schema(description = "行为表现")
    private String behavior;

    @Schema(description = "AI记录")
    private String agentNote;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
