package com.sky.decisioncompanion.service.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileAnalysisParserTest {

    private final ProfileAnalysisParser parser = new ProfileAnalysisParser();

    @Test
    void parsesJsonObjectWrappedInMarkdownAndExplanation() {
        String raw = """
                下面是分析结果：
                ```json
                {
                  "values": [
                    {
                      "item": "稳定",
                      "preference": "更看重长期确定性",
                      "confidence": 0.82,
                      "evidence": ["我还是更想要稳定一点"]
                    }
                  ],
                  "emotions": [],
                  "decisions": [],
                  "relationships": [],
                  "fears": []
                }
                ```
                已完成。
                """;

        ProfileAnalysisParser.Analysis analysis = parser.parse(raw);

        assertThat(analysis.parsed()).isTrue();
        assertThat(analysis.values()).hasSize(1);
        assertThat(analysis.values().get(0).get("item").asText()).isEqualTo("稳定");
        assertThat(analysis.emotions()).isEmpty();
        assertThat(analysis.decisions()).isEmpty();
        assertThat(analysis.relationships()).isEmpty();
        assertThat(analysis.fears()).isEmpty();
    }

    @Test
    void invalidModelOutputReturnsEmptyAnalysis() {
        ProfileAnalysisParser.Analysis analysis = parser.parse("这次没有结构化 JSON");

        assertThat(analysis.parsed()).isFalse();
        assertThat(analysis.values()).isEmpty();
        assertThat(analysis.emotions()).isEmpty();
        assertThat(analysis.decisions()).isEmpty();
        assertThat(analysis.relationships()).isEmpty();
        assertThat(analysis.fears()).isEmpty();
    }
}
