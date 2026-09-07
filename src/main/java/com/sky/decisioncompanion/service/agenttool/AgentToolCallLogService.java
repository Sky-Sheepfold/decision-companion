package com.sky.decisioncompanion.service.agenttool;

import com.sky.decisioncompanion.model.AgentToolCallLog;
import com.sky.decisioncompanion.repository.AgentToolCallLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AgentToolCallLogService {

    private static final Logger logger = LoggerFactory.getLogger(AgentToolCallLogService.class);
    private static final int SUMMARY_MAX_LENGTH = 1000;
    private static final int ERROR_MAX_LENGTH = 500;
    private static final String STATUS_STARTED = "started";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_SKIPPED = "skipped";

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
            int rows = repository.insert(log);
            logger.info("Agent 工具调用日志已写入, toolName: {}, status: {}, userId: {}, conversationId: {}, latencyMs: {}, rows: {}",
                    log.getToolName(), log.getStatus(), log.getUserId(), log.getConversationId(),
                    log.getLatencyMs(), rows);
        } catch (Exception e) {
            logger.warn("Agent 工具调用日志写入失败，工具名：{}", toolName, e);
        }
    }

    /**
     * 记录一次工具调用的"开始"（两段式审计的 started 阶段）。返回日志ID，供 {@link #complete} 回填终态。
     */
    public StartedAudit recordStarted(Long userId, Long conversationId, String toolName, String inputSummary) {
        try {
            AgentToolCallLog log = new AgentToolCallLog();
            log.setUserId(userId);
            log.setConversationId(conversationId);
            log.setToolName(truncate(toolName, 100));
            log.setInputSummary(truncate(inputSummary, SUMMARY_MAX_LENGTH));
            log.setStatus(STATUS_STARTED);
            log.setLatencyMs(0);
            repository.insert(log);
            return new StartedAudit(log.getId());
        } catch (Exception e) {
            logger.warn("Agent 工具 started 审计写入失败，工具名：{}", toolName, e);
            return new StartedAudit(null);
        }
    }

    /** 回填 started 记录为 success。 */
    public void completeSuccess(Long id, String outputSummary, long latencyMs) {
        complete(id, STATUS_SUCCESS, outputSummary, null, latencyMs);
    }

    /** 回填 started 记录为 failed。 */
    public void completeFailure(Long id, String errorMessage, long latencyMs) {
        complete(id, STATUS_FAILED, null, errorMessage, latencyMs);
    }

    /** 回填 started 记录为 skipped。 */
    public void completeSkipped(Long id, String outputSummary, long latencyMs) {
        complete(id, STATUS_SKIPPED, outputSummary, null, latencyMs);
    }

    /** 两段式审计的 finished 阶段：按日志ID更新终态与完成时间。 */
    public void complete(Long id, String status, String outputSummary, String errorMessage, long latencyMs) {
        if (id == null) {
            return;
        }
        try {
            AgentToolCallLog log = new AgentToolCallLog();
            log.setId(id);
            log.setStatus(status);
            log.setOutputSummary(truncate(outputSummary, SUMMARY_MAX_LENGTH));
            log.setErrorMessage(truncate(errorMessage, ERROR_MAX_LENGTH));
            log.setLatencyMs((int) Math.min(Math.max(latencyMs, 0), Integer.MAX_VALUE));
            log.setFinishedAt(LocalDateTime.now());
            int rows = repository.updateById(log);
            logger.info("Agent 工具调用日志已回填终态, id: {}, status: {}, finishedAt: {}, rows: {}",
                    id, status, log.getFinishedAt(), rows);
        } catch (Exception e) {
            logger.warn("Agent 工具审计完成写入失败，id：{}", id, e);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** started 阶段返回的日志身份。 */
    public record StartedAudit(Long id) {
    }
}
