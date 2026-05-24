package com.sky.decisioncompanion.service.agenttool;

import com.sky.decisioncompanion.model.AgentToolCallLog;
import com.sky.decisioncompanion.repository.AgentToolCallLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AgentToolCallLogService {

    private static final Logger logger = LoggerFactory.getLogger(AgentToolCallLogService.class);
    private static final int SUMMARY_MAX_LENGTH = 1000;
    private static final int ERROR_MAX_LENGTH = 500;

    private final AgentToolCallLogRepository repository;

    public AgentToolCallLogService(AgentToolCallLogRepository repository) {
        this.repository = repository;
    }

    public void recordSuccess(
            Long userId,
            Long conversationId,
            String toolName,
            String inputSummary,
            String outputSummary,
            long latencyMs) {
        insert(userId, conversationId, toolName, inputSummary, outputSummary, "success", null, latencyMs);
    }

    public void recordFailure(
            Long userId,
            Long conversationId,
            String toolName,
            String inputSummary,
            String errorMessage,
            long latencyMs) {
        insert(userId, conversationId, toolName, inputSummary, null, "failed", errorMessage, latencyMs);
    }

    public void recordSkipped(
            Long userId,
            Long conversationId,
            String toolName,
            String inputSummary,
            String outputSummary,
            long latencyMs) {
        insert(userId, conversationId, toolName, inputSummary, outputSummary, "skipped", null, latencyMs);
    }

    private void insert(
            Long userId,
            Long conversationId,
            String toolName,
            String inputSummary,
            String outputSummary,
            String status,
            String errorMessage,
            long latencyMs) {
        try {
            AgentToolCallLog log = new AgentToolCallLog();
            log.setUserId(userId);
            log.setConversationId(conversationId);
            log.setToolName(truncate(toolName, 100));
            log.setInputSummary(truncate(inputSummary, SUMMARY_MAX_LENGTH));
            log.setOutputSummary(truncate(outputSummary, SUMMARY_MAX_LENGTH));
            log.setStatus(status);
            log.setLatencyMs((int) Math.min(Math.max(latencyMs, 0), Integer.MAX_VALUE));
            log.setErrorMessage(truncate(errorMessage, ERROR_MAX_LENGTH));
            repository.insert(log);
        } catch (Exception e) {
            logger.warn("Agent 工具调用日志写入失败, toolName: {}", toolName, e);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
