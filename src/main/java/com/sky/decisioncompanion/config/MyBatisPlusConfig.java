package com.sky.decisioncompanion.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.sky.decisioncompanion.repository")
public class MyBatisPlusConfig {
}
