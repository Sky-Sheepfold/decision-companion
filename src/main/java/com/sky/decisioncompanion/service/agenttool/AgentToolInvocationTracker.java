package com.sky.decisioncompanion.service.agenttool;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AgentToolInvocationTracker {

    private final ConcurrentMap<String, Set<String>> callsByRequestId = new ConcurrentHashMap<>();

    public void markCalled(String requestId, String toolName) {
        if (!StringUtils.hasText(requestId) || !StringUtils.hasText(toolName)) {
            return;
        }
        callsByRequestId
                .computeIfAbsent(requestId, ignored -> ConcurrentHashMap.newKeySet())
                .add(toolName);
    }

    public boolean wasCalled(String requestId, String toolName) {
        if (!StringUtils.hasText(requestId) || !StringUtils.hasText(toolName)) {
            return false;
        }
        return callsByRequestId.getOrDefault(requestId, Set.of()).contains(toolName);
    }

    public void clear(String requestId) {
        if (StringUtils.hasText(requestId)) {
            callsByRequestId.remove(requestId);
        }
    }
}
