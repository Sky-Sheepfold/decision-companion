package com.sky.decisioncompanion.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("人生决策伙伴 Agent API")
                        .description("提供对话、冷启动引导、用户档案管理等核心功能")
                        .version("1.0.0"));
    }
}
