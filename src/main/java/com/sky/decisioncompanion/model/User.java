package com.sky.decisioncompanion.model;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Schema(description = "用户实体")
@TableName("user")
public class User {

    @Schema(description = "用户ID")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "会话ID")
    private String sessionId;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "是否完成冷启动")
    private Boolean onboarded;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public User(String sessionId) {
        this.sessionId = sessionId;
        this.onboarded = false;
    }
}
