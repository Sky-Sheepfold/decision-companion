package com.sky.decisioncompanion.service.memory;

import com.sky.decisioncompanion.config.MemoryAwarenessProperties;
import com.sky.decisioncompanion.model.ChatMessage;
import com.sky.decisioncompanion.model.MemoryAwareness;
import com.sky.decisioncompanion.repository.ChatMessageRepository;
import com.sky.decisioncompanion.repository.MemoryAwarenessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryAwarenessServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private MemoryAwarenessRepository awarenessRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private MemoryAwarenessProperties properties;
    private MemoryAwarenessService service;

    @BeforeEach
    void setUp() {
        lenient().when(chatClientBuilder.build()).thenReturn(chatClient);
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.messages(any(org.springframework.ai.chat.messages.Message[].class))).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponseSpec);
        properties = new MemoryAwarenessProperties();
        service = new MemoryAwarenessService(
                awarenessRepository, chatMessageRepository, properties, chatClientBuilder);
    }

    @Test
    void generatesAwarenessNotesFromRecentMessagesWithEvidenceChain() {
        when(chatMessageRepository.selectList(any())).thenReturn(List.of(
                message(1L, "user", "我最近很焦虑，睡不好"),
                message(2L, "assistant", "我能理解这种压力")));
        when(callResponseSpec.content()).thenReturn("""
                [
                  {"date": "2026-09-05", "observation": "用户近期因决策压力睡眠不佳", "trend": "持续焦虑", "emotion_guess": "焦虑"},
                  {"date": "2026-09-05", "observation": "用户正在纠结外地工作机会", "trend": "反复权衡", "emotion_guess": "矛盾"}
                ]
                """);
        when(awarenessRepository.selectList(any())).thenReturn(List.of());

        int added = service.generateForUser(USER_ID);

        assertThat(added).isEqualTo(2);
        ArgumentCaptor<MemoryAwareness> captor = ArgumentCaptor.forClass(MemoryAwareness.class);
        verify(awarenessRepository, org.mockito.Mockito.times(2)).insert(captor.capture());
        MemoryAwareness first = captor.getAllValues().get(0);
        assertThat(first.getUserId()).isEqualTo(USER_ID);
        assertThat(first.getObservation()).contains("睡眠不佳");
        assertThat(first.getTrend()).isEqualTo("持续焦虑");
        assertThat(first.getEmotionGuess()).isEqualTo("焦虑");
        assertThat(first.getAwareDate().toString()).isEqualTo("2026-09-05");
        // 证据链：来源消息 ID 与 consumed batch 一致，近似归属
        assertThat(first.getSourceMessageIds()).isEqualTo("1,2");
        assertThat(first.getSourceApproximate()).isTrue();
        assertThat(first.getActive()).isTrue();
    }

    @Test
    void deduplicatesSameDaySameObservation() {
        when(chatMessageRepository.selectList(any())).thenReturn(List.of(message(1L, "user", "我最近很焦虑")));
        when(callResponseSpec.content()).thenReturn("""
                [{"date": "2026-09-05", "observation": "用户近期很焦虑", "trend": "焦虑", "emotion_guess": "焦虑"}]
                """);
        MemoryAwareness existing = new MemoryAwareness();
        existing.setUserId(USER_ID);
        existing.setObservation("用户近期很焦虑");
        existing.setAwareDate(java.time.LocalDate.parse("2026-09-05"));
        existing.setActive(true);
        when(awarenessRepository.selectList(any())).thenReturn(List.of(existing));

        int added = service.generateForUser(USER_ID);

        assertThat(added).isZero();
        verify(awarenessRepository, never()).insert(any(MemoryAwareness.class));
    }

    @Test
    void returnsEmptyWhenNoRecentMessages() {
        when(chatMessageRepository.selectList(any())).thenReturn(List.of());

        int added = service.generateForUser(USER_ID);

        assertThat(added).isZero();
        verify(awarenessRepository, never()).insert(any(MemoryAwareness.class));
    }

    @Test
    void findRecentReturnsActiveAwarenessBoundedByLimit() {
        when(awarenessRepository.selectList(any())).thenReturn(List.of(awareness("近期很焦虑")));

        List<MemoryAwareness> recent = service.findRecent(USER_ID, 5);

        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).getObservation()).isEqualTo("近期很焦虑");
    }

    private ChatMessage message(Long id, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setUserId(USER_ID);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private MemoryAwareness awareness(String observation) {
        MemoryAwareness awareness = new MemoryAwareness();
        awareness.setId(1L);
        awareness.setUserId(USER_ID);
        awareness.setObservation(observation);
        awareness.setActive(true);
        awareness.setAwareDate(java.time.LocalDate.now());
        return awareness;
    }
}
