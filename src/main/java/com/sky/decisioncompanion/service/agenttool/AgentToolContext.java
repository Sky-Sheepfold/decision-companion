package com.sky.decisioncompanion.service.agenttool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.util.Assert;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AgentToolContext {

    public static final String USER_ID = "userId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String MESSAGE = "message";
    public static final String REQUEST_ID = "requestId";
    public static final String SEMANTIC_MEMORY_RETRIEVED = "semanticMemoryRetrieved";
    public static final String SEMANTIC_HIT_COUNT = "semanticHitCount";
    public static final String MAX_SEMANTIC_SCORE = "maxSemanticScore";
    public static final String SEMANTIC_QUERY = "semanticQuery";
    /** 本轮允许执行的工具名集合；缺省表示全部允许（权限由上层按场景配置）。 */
    public static final String VISIBLE_TOOLS = "visibleTools";

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
                stringValue(context.get(REQUEST_ID)),
                booleanValue(context.get(SEMANTIC_MEMORY_RETRIEVED)),
                intValue(context.get(SEMANTIC_HIT_COUNT)),
                doubleValue(context.get(MAX_SEMANTIC_SCORE)),
                stringValue(context.get(SEMANTIC_QUERY)),
                stringSet(context.get(VISIBLE_TOOLS)));
    }

    private static Set<String> stringSet(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> collection) {
            Set<String> result = new HashSet<>();
            for (Object item : collection) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        return null;
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

    private static boolean booleanValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private static Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.valueOf(value.toString());
    }

    public record Execution(
            Long userId,
            Long conversationId,
            String message,
            String requestId,
            boolean semanticMemoryRetrieved,
            int semanticHitCount,
            Double maxSemanticScore,
            String semanticQuery,
            Set<String> visibleTools) {

        /**
         * 该工具本轮是否允许执行。{@code visibleTools} 缺省（null）表示未限制、放行；
         * 否则要求工具名在允许集合内。
         */
        public boolean isToolVisible(String toolName) {
            return visibleTools == null || visibleTools.contains(toolName);
        }
    }
}
