package com.sky.decisioncompanion.service.profile;

import com.sky.decisioncompanion.config.PostureGateProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostureGateServiceTest {

    private static final Long USER_ID = 7L;

    private final ChatClient.Builder builder = mock(ChatClient.Builder.class);
    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

    private PostureGateProperties properties;
    private PostureGateService service;

    @BeforeEach
    void setUp() {
        lenient().when(builder.build()).thenReturn(chatClient);
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.messages(any(org.springframework.ai.chat.messages.Message[].class))).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponseSpec);
        properties = new PostureGateProperties();
        service = new PostureGateService(properties, builder);
    }

    @Test
    void offModeAlwaysAcceptsWithoutJudge() {
        properties.setMode("off");
        PostureGateService.GateVerdict verdict = service.evaluate(
                USER_ID, "value", "稳定", "更想要稳定", List.of("证据"), new BigDecimal("0.9"));

        assertThat(verdict.accepted()).isTrue();
        verify(chatClient, never()).prompt();
    }

    @Test
    void onlyDeepTypesAreGated() {
        properties.setMode("enforce");
        assertThat(service.shouldGate("value")).isTrue();
        assertThat(service.shouldGate("fear")).isTrue();
        assertThat(service.shouldGate("boundary")).isTrue();
        assertThat(service.shouldGate("emotion")).isFalse();
        assertThat(service.shouldGate("relationship")).isFalse();
    }

    @Test
    void enforceModeRejectsWhenJudgeSaysReject() {
        properties.setMode("enforce");
        when(callResponseSpec.content()).thenReturn("{\"verdict\": \"reject\"}");

        PostureGateService.GateVerdict verdict = service.evaluate(
                USER_ID, "value", "稳定", "更想要稳定", List.of("证据"), new BigDecimal("0.9"));

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.action()).isEqualTo("reject");
    }

    @Test
    void enforceModeAcceptsWhenJudgeSaysAccept() {
        properties.setMode("enforce");
        when(callResponseSpec.content()).thenReturn("{\"verdict\": \"accept\"}");

        PostureGateService.GateVerdict verdict = service.evaluate(
                USER_ID, "value", "稳定", "更想要稳定", List.of("证据"), new BigDecimal("0.9"));

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void enforceModeConservativelyDowngradesOnJudgeError() {
        properties.setMode("enforce");
        when(callResponseSpec.content()).thenThrow(new RuntimeException("LLM down"));

        PostureGateService.GateVerdict verdict = service.evaluate(
                USER_ID, "value", "稳定", "更想要稳定", List.of("证据"), new BigDecimal("0.9"));

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.action()).isEqualTo("error_downgrade");
    }
}
