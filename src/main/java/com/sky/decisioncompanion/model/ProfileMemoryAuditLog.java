package com.sky.decisioncompanion.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Schema(description = "画像记忆治理审计日志")
@TableName("profile_memory_audit_log")
public class ProfileMemoryAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String profileType;
    private Long profileRecordId;
    private Long candidateId;
    private String action;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
