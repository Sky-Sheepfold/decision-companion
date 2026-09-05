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

/**
 * 档案提炼任务。
 *
 * <p>将对话后画像提炼从"无状态的 @Async 调用"升级为"有状态、有界、可恢复的任务队列"。
 * 状态机：pending → processing → success / failed；失败按分级退避回到 pending 重试，
 * processing 超时未完成会被孤儿回收任务重置为 pending。
 */
@Data
@NoArgsConstructor
@Schema(description = "档案提炼任务")
@TableName("profile_extract_job")
public class ProfileExtractJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long conversationId;
    private String source;
    private String status;
    private String claimToken;
    private LocalDateTime claimedAt;
    private Integer attempts;
    private LocalDateTime retryAt;
    private String lastError;
    private String userMessage;
    private String aiResponse;
    private Integer savedCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
