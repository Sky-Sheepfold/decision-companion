package com.sky.decisioncompanion.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sky.decisioncompanion.model.ChatConversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatConversationRepository extends BaseMapper<ChatConversation> {
}
