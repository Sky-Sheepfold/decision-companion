package com.sky.decisioncompanion.service;

import com.sky.decisioncompanion.model.ChatConversation;
import com.sky.decisioncompanion.model.ChatMessage;
import com.sky.decisioncompanion.repository.ChatConversationRepository;
import com.sky.decisioncompanion.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ConversationHistoryServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ChatConversationRepository conversationRepository;

    @Mock
    private ChatMessageRepository messageRepository;

    private final List<ChatConversation> conversations = new ArrayList<>();
    private final List<ChatMessage> messages = new ArrayList<>();
    private ConversationHistoryService service;

    @BeforeEach
    void setUp() {
        lenient().when(conversationRepository.selectById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return conversations.stream()
                    .filter(conversation -> Objects.equals(conversation.getId(), id))
                    .findFirst()
                    .orElse(null);
        });
        lenient().when(conversationRepository.selectList(any())).thenAnswer(invocation -> conversations.stream()
                .filter(conversation -> Objects.equals(conversation.getUserId(), USER_ID))
                .filter(conversation -> !Boolean.TRUE.equals(conversation.getDeleted()))
                .sorted(Comparator.comparing(ChatConversation::getUpdatedAt).reversed())
                .toList());
        lenient().when(conversationRepository.insert(any(ChatConversation.class))).thenAnswer(invocation -> {
            ChatConversation conversation = invocation.getArgument(0);
            conversation.setId((long) conversations.size() + 1);
            conversations.add(conversation);
            return 1;
        });
        lenient().when(conversationRepository.updateById(any(ChatConversation.class))).thenAnswer(invocation -> {
            ChatConversation updated = invocation.getArgument(0);
            conversations.replaceAll(existing -> Objects.equals(existing.getId(), updated.getId()) ? updated : existing);
            return 1;
        });

        lenient().when(messageRepository.selectList(any())).thenAnswer(invocation -> messages.stream()
                .filter(message -> Objects.equals(message.getUserId(), USER_ID))
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .toList());
        lenient().when(messageRepository.insert(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId((long) messages.size() + 1);
            messages.add(message);
            return 1;
        });

        service = new ConversationHistoryService(conversationRepository, messageRepository);
    }

    @Test
    void startingConversationCreatesTitleFromFirstUserMessage() {
        ChatConversation conversation = service.resolveConversation(USER_ID, null, "  我最近在纠结要不要考研，要不要先工作  ");

        assertThat(conversation.getId()).isEqualTo(1L);
        assertThat(conversation.getUserId()).isEqualTo(USER_ID);
        assertThat(conversation.getTitle()).isEqualTo("我最近在纠结要不要考研，要不要先工作");
        assertThat(conversation.getMessageCount()).isZero();
        assertThat(conversation.getDeleted()).isFalse();
        assertThat(conversation.getCreatedAt()).isNotNull();
        assertThat(conversation.getUpdatedAt()).isNotNull();
    }

    @Test
    void longFirstMessageTitleIsTruncated() {
        String message = "这是一条很长很长很长很长很长很长很长很长很长的第一条用户消息";

        ChatConversation conversation = service.resolveConversation(
                USER_ID,
                null,
                message);

        assertThat(conversation.getTitle()).hasSizeLessThanOrEqualTo(30);
        assertThat(conversation.getTitle()).isEqualTo(message.substring(0, 30));
    }

    @Test
    void resolvingOwnedConversationReturnsIt() {
        ChatConversation existing = existingConversation(USER_ID, "是否要换城市");

        ChatConversation resolved = service.resolveConversation(USER_ID, existing.getId(), "继续聊");

        assertThat(resolved).isSameAs(existing);
    }

    @Test
    void resolvingAnotherUsersConversationIsRejected() {
        ChatConversation other = existingConversation(2L, "别人的会话");

        assertThatThrownBy(() -> service.resolveConversation(USER_ID, other.getId(), "继续聊"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话不存在");
    }

    @Test
    void savingMessagesUpdatesCountAndTimestamp() {
        ChatConversation conversation = service.resolveConversation(USER_ID, null, "我想换城市");

        ChatMessage userMessage = service.saveMessage(USER_ID, conversation.getId(), "user", "我想换城市");
        ChatMessage assistantMessage = service.saveMessage(USER_ID, conversation.getId(), "assistant", "我们先看你在意什么。");

        assertThat(messages).containsExactly(userMessage, assistantMessage);
        assertThat(userMessage.getRole()).isEqualTo("user");
        assertThat(userMessage.getContent()).isEqualTo("我想换城市");
        assertThat(assistantMessage.getRole()).isEqualTo("assistant");
        assertThat(assistantMessage.getContent()).isEqualTo("我们先看你在意什么。");
        assertThat(conversation.getMessageCount()).isEqualTo(2);
        assertThat(conversation.getUpdatedAt()).isNotNull();
    }

    @Test
    void listConversationsReturnsNewestFirstWithLimit() {
        ChatConversation first = service.resolveConversation(USER_ID, null, "第一段会话");
        ChatConversation second = service.resolveConversation(USER_ID, null, "第二段会话");
        service.saveMessage(USER_ID, first.getId(), "user", "更新第一段");

        List<ChatConversation> listed = service.listConversations(USER_ID, 1);

        assertThat(listed).containsExactly(first);
        assertThat(second.getUpdatedAt()).isBefore(first.getUpdatedAt());
    }

    @Test
    void deletedConversationIsHiddenFromListAndDetail() {
        ChatConversation conversation = service.resolveConversation(USER_ID, null, "我想换城市");
        service.saveMessage(USER_ID, conversation.getId(), "user", "我想换城市");

        service.deleteConversation(USER_ID, conversation.getId());

        assertThat(service.listConversations(USER_ID, 50)).isEmpty();
        assertThatThrownBy(() -> service.listMessages(USER_ID, conversation.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话不存在");
    }

    private ChatConversation existingConversation(Long userId, String title) {
        ChatConversation conversation = service.resolveConversation(userId, null, title);
        conversations.remove(conversation);
        conversations.add(conversation);
        return conversation;
    }
}
