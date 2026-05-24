package com.sky.decisioncompanion.service.agenttool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.util.Assert;

import java.util.Map;

public final class AgentToolContext {

    public static final String USER_ID = "userId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String MESSAGE = "message";
    public static final String REQUEST_ID = "requestId";

    private AgentToolContext() {
    }

    public static Execution from(ToolContext toolContext) {
        Assert.notNull(toolContext, "toolContext cannot be null");
        Map<String, Object> context = toolContext.getContext();
        Long userId = longValue(context.get(USER_ID));
        Assert.notNull(userId, "toolContext userId cannot be null");
        return new Execution(
                userId,
                longValue(context.get(CONVERSATION_ID)),
                stringValue(context.get(MESSAGE)),
                stringValue(context.get(REQUEST_ID)));
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    public record Execution(Long userId, Long conversationId, String message, String requestId) {
    }
}
