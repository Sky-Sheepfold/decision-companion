package com.sky.decisioncompanion.model;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

    @Schema(description = "用户名，唯一，支持中文")
    private String username;

    @JsonIgnore
    @Schema(description = "BCrypt 加密后的密码")
    private String password;

    @Schema(description = "是否完成冷启动")
    private Boolean onboarded;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public User(String username, String password) {
        this.username = username;
        this.password = password;
        this.onboarded = false;
    }
}
