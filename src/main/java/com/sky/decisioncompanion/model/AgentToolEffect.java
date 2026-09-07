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
@Schema(description = "Agent 工具副作用幂等账本")
@TableName("agent_tool_effect")
public class AgentToolEffect {

    @Schema(description = "记录ID")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "会话ID")
    private Long conversationId;

    @Schema(description = "发起请求ID，用于调用链路溯源")
    private String requestId;

    @Schema(description = "工具名称")
    private String toolName;

    @Schema(description = "幂等键")
    private String idempotencyKey;

    @Schema(description = "终态动作：written/needs_confirmation/skipped")
    private String action;

    @Schema(description = "画像类型")
    private String profileType;

    @Schema(description = "画像主体")
    private String subject;

    @Schema(description = "结果消息")
    private String message;

    @Schema(description = "已写入画像记录ID")
    private Long profileRecordId;

    @Schema(description = "已生成候选ID")
    private Long candidateId;

    @Schema(description = "状态：processing=占位执行中，committed=已提交终态")
    private String status;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @Schema(description = "提交终态时间")
    private LocalDateTime committedAt;
}