package com.sky.decisioncompanion.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.decisioncompanion.config.MemoryRetrievalProperties;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRagEvaluationSamplesTest {

    private final MemoryRetrievalIntentService intentService =
            new MemoryRetrievalIntentService(new MemoryRetrievalProperties());

    @Test
    void samplesCoverRecallGuardrailsAndIntentRouting() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/memory-rag-eval-samples.json")) {
            assertThat(input).isNotNull();
            JsonNode root = new ObjectMapper().readTree(input);
            assertThat(root.path("version").asText()).isEqualTo("p1");
            JsonNode samples = root.path("samples");
            assertThat(samples.isArray()).isTrue();
            assertThat(samples).hasSizeGreaterThanOrEqualTo(5);

            List<String> ids = new ArrayList<>();
            samples.forEach(sample -> {
                ids.add(sample.path("id").asText());
                assertThat(sample.path("query").asText()).isNotBlank();
                assertThat(sample.path("expectedIntent").asText()).isNotBlank();
                assertThat(sample.path("expectedSignals").isArray()).isTrue();
                assertThat(sample.path("expectedSignals")).isNotEmpty();
                assertThat(sample.path("guardrail").asText()).isNotBlank();
                assertThat(intentService.resolve(sample.path("query").asText()).code())
                        .isEqualTo(sample.path("expectedIntent").asText());
            });
            assertThat(ids).contains(
                    "user-isolation",
                    "active-scene-filter",
                    "similarity-threshold",
                    "semantic-scene-hit",
                    "vector-degradation",
                    "goal-planning-intent");
        }
    }
}
