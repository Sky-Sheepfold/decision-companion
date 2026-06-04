package com.sky.decisioncompanion.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRagEvaluationSamplesTest {

    @Test
    void p0SamplesCoverRecallGuardrails() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/memory-rag-eval-samples.json")) {
            assertThat(input).isNotNull();
            JsonNode root = new ObjectMapper().readTree(input);
            assertThat(root.path("version").asText()).isEqualTo("p0");
            JsonNode samples = root.path("samples");
            assertThat(samples.isArray()).isTrue();
            assertThat(samples).hasSizeGreaterThanOrEqualTo(5);

            List<String> ids = new ArrayList<>();
            samples.forEach(sample -> {
                ids.add(sample.path("id").asText());
                assertThat(sample.path("query").asText()).isNotBlank();
                assertThat(sample.path("expectedSignals").isArray()).isTrue();
                assertThat(sample.path("expectedSignals")).isNotEmpty();
                assertThat(sample.path("guardrail").asText()).isNotBlank();
            });
            assertThat(ids).contains(
                    "user-isolation",
                    "active-scene-filter",
                    "similarity-threshold",
                    "semantic-scene-hit",
                    "vector-degradation");
        }
    }
}
