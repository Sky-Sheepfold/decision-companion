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
@Schema(description = "画像记录与场景记忆向量关联")
@TableName("profile_scene_memory_link")
public class ProfileSceneMemoryLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String profileType;
    private Long profileRecordId;
    private String documentId;
    private String source;
    private Boolean active;
    private String deleteStatus;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
