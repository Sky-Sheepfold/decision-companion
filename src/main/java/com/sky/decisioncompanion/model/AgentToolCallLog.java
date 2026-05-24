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
@Schema(description = "Agent 工具调用日志")
@TableName("agent_tool_call_log")
public class AgentToolCallLog {

    @Schema(description = "记录ID")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "会话ID")
    private Long conversationId;

    @Schema(description = "工具名称")
    private String toolName;

    @Schema(description = "输入摘要")
    private String inputSummary;

    @Schema(description = "输出摘要")
    private String outputSummary;

    @Schema(description = "状态：成功/失败/跳过，存储值为 success/failed/skipped")
    private String status;

    @Schema(description = "耗时毫秒")
    private Integer latencyMs;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
