package com.sky.decisioncompanion.service.agenttool;

import com.sky.decisioncompanion.service.agenttool.AgentToolEffectLedger.Acquisition;
import com.sky.decisioncompanion.service.agenttool.AgentToolEffectLedger.AcquisitionStatus;
import com.sky.decisioncompanion.service.agenttool.AgentToolEffectLedger.CommittedWrite;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账本与业务写同事务执行器。
 *
 * <p>借鉴 waoowaoo 的 Effect Ledger：把「占位(processing) → 业务写 → 回填(committed)」放进
 * **同一个数据库事务**，任何一环失败都会整体回滚，从而：
 * <ul>
 *   <li>业务写成功 ⇔ 账本一定已提交（不留"业务已写、账本未回填"的崩溃窗口）；</li>
 *   <li>业务写失败 ⇒ 占位行一并回滚，重试可重新抢占，不会被残留的 processing 行短路；</li>
 *   <li>并发同键下唯一索引仍作为执行闸门，重复执行被短路。</li>
 * </ul>
 */
@Component
public class AgentToolEffectExecutor {

    private final AgentToolEffectLedger ledger;

    public AgentToolEffectExecutor(AgentToolEffectLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * 在同一事务内：抢占 → 执行业务副作用 → 回填账本终态。
     *
     * @return 抢占结果（{@code ALREADY_COMMITTED} 可重放 / {@code IN_FLIGHT} 应短路 / {@code RESERVED}
     *         携带已执行的业务结果）。
     */
    @Transactional
    public Outcome execute(
            Long userId,
            Long conversationId,
            String requestId,
            String toolName,
            String idempotencyKey,
            WriteEffect writeEffect) {
        Acquisition acquisition = ledger.acquire(userId, conversationId, requestId, toolName, idempotencyKey);
        if (acquisition.status() == AcquisitionStatus.ALREADY_COMMITTED) {
            return new Outcome(AcquisitionStatus.ALREADY_COMMITTED, acquisition.committed(), null);
        }
        if (acquisition.status() != AcquisitionStatus.RESERVED) {
            return new Outcome(AcquisitionStatus.IN_FLIGHT, null, null);
        }
        // 已获得执行权，执行业务副作用（占用+写+回填同事务）
        Result result = writeEffect.run();
        ledger.finalizeWrite(
                userId,
                toolName,
                idempotencyKey,
                result.action(),
                result.profileType(),
                result.subject(),
                result.message(),
                result.profileRecordId(),
                result.candidateId());
        return new Outcome(AcquisitionStatus.RESERVED, null, result);
    }

    @FunctionalInterface
    public interface WriteEffect {
        Result run();
    }

    /** 业务写副作用的结果，用于回填账本与生成工具返回。 */
    public record Result(
            String action,
            String profileType,
            String subject,
            String message,
            boolean updated,
            Long profileRecordId,
            Long candidateId) {
    }

    /** 一次执行的整体结果。 */
    public record Outcome(AcquisitionStatus status, CommittedWrite committed, Result result) {
    }
}