package com.sky.decisioncompanion.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sky.decisioncompanion.model.MemoryInsight;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MemoryInsightRepository extends BaseMapper<MemoryInsight> {

    /**
     * 查找最近产生过对话的用户 ID（用于定时提炼行为动机洞察）。
     */
    @Select("SELECT DISTINCT user_id FROM chat_message WHERE created_at >= #{since} ORDER BY user_id LIMIT #{limit}")
    List<Long> selectRecentActiveUserIds(@Param("since") LocalDateTime since, @Param("limit") int limit);
}
