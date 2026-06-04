package com.sky.decisioncompanion.service.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "decision-companion.memory.retrieval.semantic-similarity-threshold=0.0"
})
@EnabledIfSystemProperty(named = "memory.rag.live", matches = "true")
class MemoryRagLiveVectorSearchTest {

    private static final String DEFAULT_QUERY = "我现在还是在纠结外地高薪机会和离父母近之间怎么取舍，你结合我之前的偏好帮我判断一下。";

    @Autowired
    private VectorStore vectorStore;

    @Test
    void queryLiveChromaAtDifferentThresholds() {
        String userId = System.getProperty("memory.rag.user-id", "11");
        String query = System.getProperty("memory.rag.query", DEFAULT_QUERY);

        for (double threshold : List.of(0.6, 0.3, 0.2, 0.0)) {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(5)
                    .similarityThreshold(threshold)
                    .filterExpression("userId == '" + userId + "'")
                    .build();

            List<Document> hits = vectorStore.similaritySearch(request);
            System.out.printf("LIVE_RAG threshold=%.1f userId=%s hitCount=%d query=%s%n",
                    threshold, userId, hits.size(), query);
            for (Document hit : hits) {
                System.out.printf("LIVE_RAG_HIT threshold=%.1f id=%s score=%s type=%s text=%s%n",
                        threshold,
                        hit.getId(),
                        hit.getScore(),
                        hit.getMetadata().get("memoryType"),
                        abbreviate(hit.getText(), 180));
            }
        }

        assertThat(vectorStore).isNotNull();
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
