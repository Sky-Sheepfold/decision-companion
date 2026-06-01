package com.sky.decisioncompanion.advisor;

import com.sky.decisioncompanion.service.memory.MemoryContext;
import com.sky.decisioncompanion.service.memory.MemoryRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileAdvisorServiceTest {

    private final MemoryRetrievalService memoryRetrievalService = mock(MemoryRetrievalService.class);
    private ProfileAdvisorService service;

    @BeforeEach
    void setUp() {
        service = new ProfileAdvisorService(memoryRetrievalService);
    }

    @Test
    void buildSystemPromptUsesMemoryRagContextAndConversationPrinciples() {
        when(memoryRetrievalService.retrieve(1L, "我在纠结外地 offer")).thenReturn(memoryContext("""
                以下是系统检索到的用户长期记忆，仅作为参考，不代表用户当前最终意愿。

                【稳定价值观】
                - 城市偏好：更看重离家近
                """));

        String prompt = service.buildSystemPrompt(1L, "我在纠结外地 offer");

        assertThat(prompt).contains("人生决策伙伴");
        assertThat(prompt).contains("仅作为参考");
        assertThat(prompt).contains("城市偏好：更看重离家近");
        assertThat(prompt).contains("建议要结合他的价值观");
        verify(memoryRetrievalService).retrieve(1L, "我在纠结外地 offer");
    }

    private MemoryContext memoryContext(String promptContext) {
        return new MemoryContext(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new MemoryContext.RetrievalMetrics(0, 0, 0, 0, 0, 0, null, true, false),
                promptContext);
    }
}
