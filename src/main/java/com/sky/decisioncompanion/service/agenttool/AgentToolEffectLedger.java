package com.sky.decisioncompanion.service.agenttool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sky.decisioncompanion.model.AgentToolEffect;
import com.sky.decisioncompanion.repository.AgentToolEffectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Agent 工具写入副作用幂等账本。
 *
 * <p>
 * 借鉴 waoowaoo 的 Effect Ledger，采用“先占位、后回填”以保证同一幂等调用只执行一次：
 * <ol>
 * <li>{@link #acquire}：写入 {@code status=processing} 的占位行，以唯一键
 * {@code idempotencyKey}
 * 作为**执行闸门**——插入成功者获得执行权，插入撞唯一键者不复用、直接短路；</li>
 * <li>占位者执行业务写，拿到产出 ID（正式档案/候选 ID）；</li>
 * <li>{@link #finalizeWrite}：把该行从 processing 回填为 {@code committed}，同时记录终态与产出，用于
 * 对账与故障定位。</li>
 * </ol>
 *
 * <p>
 * 键粒度：{@code SHA256(userId : conversationId : updateUserProfile : paramsHash)}，按规范化参数幂等，
 * 同一会话参数一致的写调用（含模型重试/请求重放）都会命中同键。若后续工具契约演进，应在键中纳入
 * 契约版本，避免旧条目短路新格式调用。
 * </p>
 *
 * <p>
 * 占位行可能因进程在业务写入完成前崩溃而滞留（{@code processing}）。{@link #recoverOrphans}
 * 定期删除超时占位行，释放键供重试。账本写失败一律降级放行业务，不阻塞正事。
 * </p>
 */
@Service
public class AgentToolEffectLedger {

    private static final Logger logger = LoggerFactory.getLogger(AgentToolEffectLedger.class);
    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_COMMITTED = "committed";

    private final AgentToolEffectRepository repository;

    /** 占位执行中的最大允许时长，超过视为孤儿并回收，防止键被永久占用。 */
    @Value("${decision-companion.agenttool.effect-processing-orphan-ms:300000}")
    private Long processingOrphanMs;

    /** 已提交账本的最大保留时长，超过即批量清理，抑制无限膨胀。 */
    @Value("${decision-companion.agenttool.effect-retention-ms:2592000000}")
    private Long effectRetentionMs;

    public AgentToolEffectLedger(AgentToolEffectRepository repository) {
        this.repository = repository;
    }

    /**
     * 抢占一次写调用的执行权。
     *
     * @return {@link AcquisitionStatus#RESERVED} 表示本调用获得执行权；
     *         {@link AcquisitionStatus#ALREADY_COMMITTED} 表示该键已提交终态，可直接重放返回结果；
     *         {@link AcquisitionStatus#IN_FLIGHT} 表示存在并发同键调用正在执行，应短路避免重复副作用。
     */
    public Acquisition acquire(
            Long userId, Long conversationId, String requestId, String toolName, String idempotencyKey) {
        if (userId == null || !StringUtils.hasText(toolName) || !StringUtils.hasText(idempotencyKey)) {
            logger.warn("Agent 工具幂等占位输入不完整，跳过幂等、放行业务写入, toolName: {}", toolName);
            return Acquisition.reserved();
        }
        AgentToolEffect row = new AgentToolEffect();
        row.setUserId(userId);
        row.setConversationId(conversationId);
        row.setRequestId(requestId);
        row.setToolName(toolName);
        row.setIdempotencyKey(idempotencyKey);
        row.setStatus(STATUS_PROCESSING);
        try {
            repository.insert(row);
            logger.info("Agent 工具幂等占位成功, toolName: {}, userId: {}, conversationId: {}",
                    toolName, userId, conversationId);
            return Acquisition.reserved();
        } catch (DuplicateKeyException e) {
            AgentToolEffect existing = findExisting(userId, toolName, idempotencyKey);
            if (existing != null && STATUS_COMMITTED.equals(existing.getStatus())
                    && StringUtils.hasText(existing.getAction())) {
                logger.info("Agent 工具幂等命中已提交终态, toolName: {}, action: {}, userId: {}",
                        toolName, existing.getAction(), userId);
                return Acquisition.alreadyCommitted(new CommittedWrite(
                        existing.getAction(),
                        existing.getProfileType(),
                        existing.getSubject(),
                        existing.getMessage()));
            }
            logger.info("Agent 工具幂等占位冲突，存在并发同键调用, toolName: {}, userId: {}", toolName, userId);
            return Acquisition.inFlight();
        } catch (Exception e) {
            logger.warn("Agent 工具幂等占位异常，降级放行业务写入, toolName: {}", toolName, e);
            return Acquisition.reserved();
        }
    }

    /**
     * 将占位行回填为已提交终态，并记录画像/候选产出 ID 用于对账。
     */
    public void finalizeWrite(
            Long userId,
            String toolName,
            String idempotencyKey,
            String action,
            String profileType,
            String subject,
            String message,
            Long profileRecordId,
            Long candidateId) {
        if (userId == null || !StringUtils.hasText(idempotencyKey) || !StringUtils.hasText(action)) {
            return;
        }
        try {
            int rows = repository.update(null, new LambdaUpdateWrapper<AgentToolEffect>()
                    .eq(AgentToolEffect::getUserId, userId)
                    .eq(AgentToolEffect::getToolName, toolName)
                    .eq(AgentToolEffect::getIdempotencyKey, idempotencyKey)
                    .eq(AgentToolEffect::getStatus, STATUS_PROCESSING)
                    .set(AgentToolEffect::getStatus, STATUS_COMMITTED)
                    .set(AgentToolEffect::getAction, action)
                    .set(AgentToolEffect::getProfileType, profileType)
                    .set(AgentToolEffect::getSubject, truncate(subject, 200))
                    .set(AgentToolEffect::getMessage, truncate(message, 500))
                    .set(AgentToolEffect::getProfileRecordId, profileRecordId)
                    .set(AgentToolEffect::getCandidateId, candidateId)
                    .set(AgentToolEffect::getCommittedAt, LocalDateTime.now()));
            logger.info("Agent 工具副作用已回填, toolName: {}, action: {}, userId: {}, rows: {}",
                    toolName, action, userId, rows);
        } catch (Exception e) {
            logger.warn("Agent 工具副作用回填失败，不影响已完成的业务写入, toolName: {}", toolName, e);
        }
    }

    /**
     * 定期回收超时的 processing 占位行（进程在业务写入完成前崩溃所致），释放幂等键供重试。
     */
    @Scheduled(fixedDelayString = "${decision-companion.agenttool.effect-orphan-scan-ms:60000}")
    public int recoverOrphans() {
        LocalDateTime cutoff = LocalDateTime.now().minus(orphanTimeoutMs(), ChronoUnit.MILLIS);
        int deleted = repository.delete(new LambdaQueryWrapper<AgentToolEffect>()
                .eq(AgentToolEffect::getStatus, STATUS_PROCESSING)
                .lt(AgentToolEffect::getCreatedAt, cutoff));
        if (deleted > 0) {
            logger.info("回收 Agent 工具幂等占位孤儿: {}", deleted);
        }
        return deleted;
    }

    private AgentToolEffect findExisting(Long userId, String toolName, String idempotencyKey) {
        return repository.selectOne(new LambdaQueryWrapper<AgentToolEffect>()
                .eq(AgentToolEffect::getUserId, userId)
                .eq(AgentToolEffect::getToolName, toolName)
                .eq(AgentToolEffect::getIdempotencyKey, idempotencyKey));
    }

    private long orphanTimeoutMs() {
        return processingOrphanMs == null ? 300_000L : processingOrphanMs;
    }

    /**
     * 定期清理超过保留期的已提交账本，防止幂等表无限膨胀。幂等只在保留窗口内有意义，过期条目即可安全删除。
     */
    @Scheduled(fixedDelayString = "${decision-companion.agenttool.effect-retention-scan-ms:3600000}")
    public long purgeExpiredCommitted() {
        long retentionMs = effectRetentionMs == null ? 2_592_000_000L : effectRetentionMs;
        LocalDateTime cutoff = LocalDateTime.now().minus(retentionMs, ChronoUnit.MILLIS);
        int deleted = repository.delete(new LambdaQueryWrapper<AgentToolEffect>()
                .eq(AgentToolEffect::getStatus, STATUS_COMMITTED)
                .and(wrapper -> wrapper.lt(AgentToolEffect::getCommittedAt, cutoff)
                        .or()
                        .isNull(AgentToolEffect::getCommittedAt)
                        .and(inner -> inner.lt(AgentToolEffect::getCreatedAt, cutoff))));
        if (deleted > 0) {
            logger.info("清理 Agent 工具幂等账本历史提交: {}", deleted);
        }
        return deleted;
    }

    private String truncate(String value, int maxLength) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    public enum AcquisitionStatus {
        RESERVED, ALREADY_COMMITTED, IN_FLIGHT
    }

    public record Acquisition(AcquisitionStatus status, CommittedWrite committed) {

        static Acquisition reserved() {
            return new Acquisition(AcquisitionStatus.RESERVED, null);
        }

        static Acquisition alreadyCommitted(CommittedWrite committed) {
            return new Acquisition(AcquisitionStatus.ALREADY_COMMITTED, committed);
        }

        static Acquisition inFlight() {
            return new Acquisition(AcquisitionStatus.IN_FLIGHT, null);
        }
    }

    public record CommittedWrite(String action, String profileType, String subject, String message) {
    }
}