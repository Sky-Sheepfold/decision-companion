package com.sky.decisioncompanion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.common.BusinessException;
import com.sky.decisioncompanion.common.ResultCode;
import com.sky.decisioncompanion.model.ChatConversation;
import com.sky.decisioncompanion.model.ChatMessage;
import com.sky.decisioncompanion.repository.ChatConversationRepository;
import com.sky.decisioncompanion.repository.ChatMessageRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class ConversationHistoryService {

    private static final int TITLE_MAX_LENGTH = 30;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;

    public ConversationHistoryService(
            ChatConversationRepository conversationRepository,
            ChatMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    public ChatConversation resolveConversation(Long userId, Long conversationId, String firstMessage) {
        if (conversationId == null) {
            return createConversation(userId, firstMessage);
        }
        return requireConversation(userId, conversationId);
    }

    public ChatMessage saveMessage(Long userId, Long conversationId, String role, String content) {
        ChatConversation conversation = requireConversation(userId, conversationId);
        LocalDateTime now = LocalDateTime.now();

        ChatMessage message = new ChatMessage();
        message.setConversationId(conversation.getId());
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(now);
        messageRepository.insert(message);

        int nextCount = conversation.getMessageCount() == null ? 1 : conversation.getMessageCount() + 1;
        conversation.setMessageCount(nextCount);
        conversation.setUpdatedAt(now);
        conversationRepository.updateById(conversation);

        return message;
    }

    public List<ChatConversation> listConversations(Long userId, int limit) {
        int safeLimit = normalizeLimit(limit);
        List<ChatConversation> conversations = conversationRepository.selectList(
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getUserId, userId)
                        .eq(ChatConversation::getDeleted, false)
                        .orderByDesc(ChatConversation::getUpdatedAt)
                        .last("LIMIT " + safeLimit));

        return conversations.stream()
                .filter(conversation -> userId.equals(conversation.getUserId()))
                .filter(conversation -> !Boolean.TRUE.equals(conversation.getDeleted()))
                .sorted(Comparator.comparing(ChatConversation::getUpdatedAt).reversed())
                .limit(safeLimit)
                .toList();
    }

    public List<ChatMessage> listMessages(Long userId, Long conversationId) {
        ChatConversation conversation = requireConversation(userId, conversationId);
        return messageRepository.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getUserId, userId)
                        .eq(ChatMessage::getConversationId, conversation.getId())
                        .orderByAsc(ChatMessage::getCreatedAt))
                .stream()
                .filter(message -> userId.equals(message.getUserId()))
                .filter(message -> conversation.getId().equals(message.getConversationId()))
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .toList();
    }

    public void deleteConversation(Long userId, Long conversationId) {
        ChatConversation conversation = requireConversation(userId, conversationId);
        conversation.setDeleted(true);
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.updateById(conversation);
    }

    private ChatConversation createConversation(Long userId, String firstMessage) {
        LocalDateTime now = LocalDateTime.now();
        ChatConversation conversation = new ChatConversation();
        conversation.setUserId(userId);
        conversation.setTitle(titleFrom(firstMessage));
        conversation.setMessageCount(0);
        conversation.setDeleted(false);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationRepository.insert(conversation);
        return conversation;
    }

    private ChatConversation requireConversation(Long userId, Long conversationId) {
        ChatConversation conversation = conversationRepository.selectById(conversationId);
        if (conversation == null
                || !userId.equals(conversation.getUserId())
                || Boolean.TRUE.equals(conversation.getDeleted())) {
            throw new BusinessException(ResultCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    /**
     * 会话归属校验（不抛异常），供工具执行处作 owner 复核。会话不存在/非本人/已删除均视为不属主。
     */
    public boolean isOwnedConversation(Long userId, Long conversationId) {
        if (userId == null || conversationId == null) {
            return false;
        }
        ChatConversation conversation = conversationRepository.selectById(conversationId);
        return conversation != null
                && userId.equals(conversation.getUserId())
                && !Boolean.TRUE.equals(conversation.getDeleted());
    }

    private String titleFrom(String message) {
        String title = message == null ? "" : message.trim();
        if (title.length() <= TITLE_MAX_LENGTH) {
            return title;
        }
        return title.substring(0, TITLE_MAX_LENGTH);
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
