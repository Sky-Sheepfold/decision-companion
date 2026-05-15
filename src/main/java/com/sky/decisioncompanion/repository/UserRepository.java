package com.sky.decisioncompanion.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sky.decisioncompanion.model.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserRepository extends BaseMapper<User> {
}
