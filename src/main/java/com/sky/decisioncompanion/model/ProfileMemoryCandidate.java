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

@Data
@NoArgsConstructor
@Schema(description = "待确认画像记忆")
@TableName("profile_memory_candidate")
public class ProfileMemoryCandidate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String profileType;
    private String subject;
    private String content;
    private String detail;
    private BigDecimal confidence;
    private String evidence;
    private String source;
    private Long sourceConversationId;
    @Schema(description = "规范化输入哈希，审批绑定标识")
    private String inputHash;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime handledAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
