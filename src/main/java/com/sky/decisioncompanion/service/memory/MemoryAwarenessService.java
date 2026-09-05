package com.sky.decisioncompanion.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.decisioncompanion.config.MemoryAwarenessProperties;
import com.sky.decisioncompanion.model.ChatMessage;
import com.sky.decisioncompanion.model.MemoryAwareness;
import com.sky.decisioncompanion.repository.ChatMessageRepository;
import com.sky.decisioncompanion.repository.MemoryAwarenessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 近期觉察（Awareness）记忆服务 —— 近因层。
 *
 * <p>对齐 OpenBiliClaw 的 Awareness 层：定时从近期对话中让 LLM 提炼结构化观察
 * （date / observation / trend / emotion_guess），同日去重、带来源消息证据链，
 * 作为易变块注入 prompt，与稳定核心画像（Core）和场景记忆（Episodic）互补。
 */
@Service
public class MemoryAwarenessService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryAwarenessService.class);

    private final MemoryAwarenessRepository awarenessRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MemoryAwarenessProperties properties;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public MemoryAwarenessService(
            MemoryAwarenessRepository awarenessRepository,
            ChatMessageRepository chatMessageRepository,
            MemoryAwarenessProperties properties,
            ChatClient.Builder chatClientBuilder) {
        this.awarenessRepository = awarenessRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.properties = properties;
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 为指定用户生成近期觉察（幂等：同一天相同观察去重）。返回新增条数。
     */
    public int generateForUser(Long userId) {
        if (userId == null || !properties.isEnabled()) {
            return 0;
        }
        List<ChatMessage> messages = recentMessages(userId);
        if (messages.isEmpty()) {
            return 0;
        }

        String analysis = callAwareness(messages);
        List<MemoryAwareness> notes = parseNotes(analysis);
        if (notes.isEmpty()) {
            logger.warn("近期觉察提炼输出为空或不可解析, userId: {}", userId);
            return 0;
        }

        Set<String> existingKeys = existingNoteKeys(userId);
        List<Long> sourceIds = messages.stream().map(ChatMessage::getId).toList();
        String sourceIdsText = sourceIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        int added = 0;
        LocalDateTime now = LocalDateTime.now();
        for (MemoryAwareness note : notes) {
            String observation = clean(note.getObservation());
            if (observation.isBlank()) {
                continue;
            }
            LocalDate date = note.getAwareDate() == null ? LocalDate.now() : note.getAwareDate();
            String key = date + ":" + normalize(observation);
            if (existingKeys.contains(key)) {
                continue;
            }
            note.setUserId(userId);
            note.setAwareDate(date);
            note.setSourceMessageIds(sourceIdsText);
            note.setSourceApproximate(true);
            note.setActive(true);
            note.setCreatedAt(now);
            note.setUpdatedAt(now);
            try {
                awarenessRepository.insert(note);
                added++;
                existingKeys.add(key);
            } catch (Exception e) {
                logger.warn("近期觉察写入失败, userId: {}", userId, e);
            }
        }
        if (added > 0) {
            logger.info("近期觉察生成完成, userId: {}, newNotes: {}", userId, added);
        }
        return added;
    }

    /**
     * 定时任务：扫描近期有对话的用户，为其生成近期觉察（有界，避免一次打爆 LLM）。
     */
    @Scheduled(fixedDelayString = "${decision-companion.memory.awareness.scheduled-interval-ms:7200000}")
    public void runScheduled() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(properties.windowDays());
            List<Long> userIds = awarenessRepository.selectRecentActiveUserIds(
                    since, properties.maxUsersPerRun());
            for (Long userId : userIds) {
                try {
                    generateForUser(userId);
                } catch (Exception e) {
                    logger.warn("近期觉察生成异常, userId: {}", userId, e);
                }
            }
        } catch (Exception e) {
            logger.warn("近期觉察定时任务异常", e);
        }
    }

    /**
     * 读取最近的觉察记录（供召回注入易变块）。
     */
    public List<MemoryAwareness> findRecent(Long userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        return awarenessRepository.selectList(new LambdaQueryWrapper<MemoryAwareness>()
                .eq(MemoryAwareness::getUserId, userId)
                .eq(MemoryAwareness::getActive, true)
                .orderByDesc(MemoryAwareness::getAwareDate)
                .orderByDesc(MemoryAwareness::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    private List<ChatMessage> recentMessages(Long userId) {
        LocalDateTime since = LocalDateTime.now().minusDays(properties.windowDays());
        return chatMessageRepository.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getUserId, userId)
                .ge(ChatMessage::getCreatedAt, since)
                .orderByAsc(ChatMessage::getCreatedAt)
                .last("LIMIT " + properties.messageCap()));
    }

    private String callAwareness(List<ChatMessage> messages) {
        String transcript = messages.stream()
                .map(message -> ("user".equals(message.getRole()) ? "用户" : "助手") + "：" + message.getContent())
                .collect(Collectors.joining("\n"));
        String prompt = """
                请分析用户最近一段时间的对话，提炼 1-3 条"近期觉察"：用户最近在经历什么、状态如何、情绪如何、有哪些趋势。

                对话记录：
                %s

                只返回一个 JSON 数组，不要返回 Markdown 或解释文字：
                [
                  {"date": "2026-09-05", "observation": "观察结论（一句话）", "trend": "趋势（如持续纠结/情绪低落/逐步坚定）", "emotion_guess": "情绪猜测（如焦虑/平静/矛盾）"}
                ]
                观察必须来自对话内容，不要臆测；证据不足返回空数组。
                """.formatted(transcript);
        return chatClient.prompt().messages(new UserMessage(prompt)).call().content();
    }

    private List<MemoryAwareness> parseNotes(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String json = extractJson(raw);
        if (json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<MemoryAwareness> notes = new ArrayList<>();
            for (JsonNode item : root) {
                MemoryAwareness note = new MemoryAwareness();
                note.setObservation(clean(text(item, "observation", "observe")));
                note.setTrend(truncate(clean(text(item, "trend")), 200));
                note.setEmotionGuess(truncate(clean(text(item, "emotion_guess", "emotion")), 100));
                String dateText = clean(text(item, "date"));
                if (!dateText.isBlank()) {
                    try {
                        note.setAwareDate(LocalDate.parse(dateText));
                    } catch (Exception e) {
                        note.setAwareDate(LocalDate.now());
                    }
                } else {
                    note.setAwareDate(LocalDate.now());
                }
                notes.add(note);
            }
            return notes;
        } catch (Exception e) {
            logger.warn("近期觉察 JSON 解析失败", e);
            return List.of();
        }
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('[');
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private Set<String> existingNoteKeys(Long userId) {
        List<MemoryAwareness> existing = awarenessRepository.selectList(new LambdaQueryWrapper<MemoryAwareness>()
                .eq(MemoryAwareness::getUserId, userId)
                .eq(MemoryAwareness::getActive, true));
        Set<String> keys = new HashSet<>();
        for (MemoryAwareness note : existing) {
            LocalDate date = note.getAwareDate() == null ? LocalDate.now() : note.getAwareDate();
            keys.add(date + ":" + normalize(note.getObservation()));
        }
        return keys;
    }

    private String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                return value.asText("");
            }
        }
        return "";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String normalize(String value) {
        return clean(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
