package com.sky.decisioncompanion.advisor;

import com.sky.decisioncompanion.service.memory.MemoryContext;
import com.sky.decisioncompanion.service.memory.MemoryRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    void buildProfilePromptSplitsStableCoreIntoSystemAndVolatileRecallIntoUserContext() {
        when(memoryRetrievalService.retrieve(1L, "我在纠结外地 offer")).thenReturn(memoryContext("""
                以下是系统检索到的用户长期记忆，仅作为参考，不代表用户当前最终意愿。

                【稳定价值观】
                - 城市偏好：更看重离家近
                """));
        when(memoryRetrievalService.renderCoreSection(any())).thenReturn("【核心稳定画像（始终参考）】\n- 稳定：更看重家庭（置信度 0.9000）");

        ProfileAdvisorService.ProfilePrompt prompt = service.buildProfilePrompt(1L, "我在纠结外地 offer");

        // 稳定块进 system prompt：persona + 核心稳定画像 + 对话原则
        assertThat(prompt.systemPrompt()).contains("人生决策伙伴");
        assertThat(prompt.systemPrompt()).contains("核心稳定画像");
        assertThat(prompt.systemPrompt()).contains("更看重家庭");
        assertThat(prompt.systemPrompt()).contains("建议要结合他的价值观");
        assertThat(prompt.systemPrompt()).doesNotContain("更看重离家近");
        // 易变块（动态召回）单独暴露，供前置到 user message
        assertThat(prompt.volatileContext()).contains("仅作为参考");
        assertThat(prompt.volatileContext()).contains("城市偏好：更看重离家近");
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
                List.of(),
                new MemoryContext.RetrievalMetrics(0, 0, 0, 0, 0, 0, null, true, false),
                promptContext);
    }
}
