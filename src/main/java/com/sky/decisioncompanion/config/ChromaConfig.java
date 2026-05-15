package com.sky.decisioncompanion.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChromaConfig {

    @Value("${spring.ai.vectorstore.chroma.client.host:http://localhost:8000}")
    private String chromaHost;

    @Value("${spring.ai.vectorstore.chroma.collection-name:companion_profiles}")
    private String collectionName;

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        ChromaApi chromaApi = ChromaApi.builder()
                .baseUrl(chromaHost)
                .build();

        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(true)
                .build();
    }
}
