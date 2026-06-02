package com.sky.decisioncompanion.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sky.decisioncompanion.model.ProfileMemoryAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProfileMemoryAuditLogRepository extends BaseMapper<ProfileMemoryAuditLog> {
}
