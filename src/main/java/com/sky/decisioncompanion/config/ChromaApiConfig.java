package com.sky.decisioncompanion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.ai.chroma.vectorstore.ChromaApi;

@Configuration
public class ChromaApiConfig {

    @Value("${spring.ai.vectorstore.chroma.client.host}")
    private String host;

    @Value("${spring.ai.vectorstore.chroma.client.port}")
    private int port;

    @Bean
    public ChromaApi customChromaApi() {
        String baseUrl = host + ":" + port;
        return ChromaApi.builder()
                .baseUrl(baseUrl)
                .build();
    }
}
