package com.sky.decisioncompanion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.config.ProfileExtractProperties;
import com.sky.decisioncompanion.model.ProfileExtractJob;
import com.sky.decisioncompanion.repository.ProfileExtractJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * 档案提炼任务队列。
 *
 * <p>替代原来无界的 {@code @Async} 提炼：任务先落库，Worker 用租约 CAS 认领后执行，
 * 失败按分级退避重试，处理超时的孤儿任务会被定期回收。并发由信号量约束，给 SSE 对话留出模型槽位。
 */
@Service
public class ProfileExtractJobService {

    private static final Logger logger = LoggerFactory.getLogger(ProfileExtractJobService.class);
    private static final String STATUS_PENDING = "pending";

    private final ProfileExtractJobRepository jobRepository;
    private final ProfileExtractService profileExtractService;
    private final ProfileExtractProperties properties;
    private final Semaphore concurrencyGate;
    private final ThreadPoolTaskExecutor executor;

    public ProfileExtractJobService(
            ProfileExtractJobRepository jobRepository,
            ProfileExtractService profileExtractService,
            ProfileExtractProperties properties) {
        this.jobRepository = jobRepository;
        this.profileExtractService = profileExtractService;
        this.properties = properties;
        this.concurrencyGate = new Semaphore(Math.max(1, properties.getConcurrency()));
        this.executor = buildExecutor();
    }

    /**
     * 提交一个提炼任务（同步、快速，不调用 LLM）。由对话完成回调 / 冷启动完成时调用。
     */
    public void submit(Long userId, Long conversationId, String userMessage, String aiResponse, String source) {
        if (userId == null || !properties.isEnabled()) {
            return;
        }
        ProfileExtractJob job = new ProfileExtractJob();
        job.setUserId(userId);
        job.setConversationId(conversationId);
        job.setSource(source);
        job.setStatus(STATUS_PENDING);
        job.setAttempts(0);
        job.setUserMessage(userMessage);
        job.setAiResponse(aiResponse);
        try {
            jobRepository.insert(job);
        } catch (Exception e) {
            logger.warn("档案提炼任务提交失败, userId: {}, source: {}", userId, source, e);
        }
    }

    @Scheduled(fixedDelayString = "${decision-companion.memory.extract.poll-interval-ms:5000}")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        List<ProfileExtractJob> pending = selectPending(properties.getBatchSize());
        for (ProfileExtractJob job : pending) {
            if (!concurrencyGate.tryAcquire()) {
                break;
            }
            String token = UUID.randomUUID().toString();
            try {
                int claimed = jobRepository.tryClaim(job.getId(), token, properties.getMaxAttempts());
                if (claimed == 0) {
                    concurrencyGate.release();
                    continue;
                }
            } catch (Exception e) {
                concurrencyGate.release();
                logger.warn("档案提炼任务认领异常, jobId: {}", job.getId(), e);
                continue;
            }
            Long jobId = job.getId();
            executor.execute(() -> {
                try {
                    process(jobId, token);
                } finally {
                    concurrencyGate.release();
                }
            });
        }
    }

    @Scheduled(fixedDelayString = "${decision-companion.memory.extract.orphan-recover-ms:60000}")
    public void recoverOrphans() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusMinutes(properties.getOrphanTimeoutMinutes());
            int recovered = jobRepository.recoverOrphans(threshold);
            if (recovered > 0) {
                logger.info("回收档案提炼孤儿任务: {}", recovered);
            }
        } catch (Exception e) {
            logger.warn("档案提炼孤儿任务回收异常", e);
        }
    }

    private void process(Long jobId, String token) {
        ProfileExtractJob job = loadClaimed(jobId, token);
        if (job == null) {
            return;
        }
        try {
            int saved = profileExtractService.extractAndSave(job.getUserId(), job.getUserMessage(), job.getAiResponse());
            jobRepository.completeSuccess(jobId, token, saved);
        } catch (Exception e) {
            handleFailure(job, token, e);
        }
    }

    void handleFailure(ProfileExtractJob job, String token, Exception error) {
        LLMFailureClassifier.FailureKind kind = LLMFailureClassifier.classify(error);
        String errorText = truncate(error.getMessage(), 500);
        logger.warn("档案提炼任务失败, jobId: {}, attempts: {}, kind: {}, error: {}",
                job.getId(), job.getAttempts(), kind, errorText);

        int attemptsAfter = job.getAttempts() + 1;
        if (kind == LLMFailureClassifier.FailureKind.PERMANENT || attemptsAfter >= properties.getMaxAttempts()) {
            jobRepository.completeFailed(job.getId(), token, errorText);
            return;
        }
        long delay = LLMFailureClassifier.retryDelayMs(kind, attemptsAfter, properties.getRetryDelayMs());
        LocalDateTime retryAt = LocalDateTime.now().plus(Duration.ofMillis(delay));
        jobRepository.completeRetryLater(job.getId(), token, retryAt, errorText, properties.getMaxAttempts());
    }

    private ProfileExtractJob loadClaimed(Long jobId, String token) {
        return jobRepository.selectOne(new LambdaQueryWrapper<ProfileExtractJob>()
                .eq(ProfileExtractJob::getId, jobId)
                .eq(ProfileExtractJob::getClaimToken, token));
    }

    private List<ProfileExtractJob> selectPending(int limit) {
        LocalDateTime now = LocalDateTime.now();
        return jobRepository.selectList(new LambdaQueryWrapper<ProfileExtractJob>()
                .eq(ProfileExtractJob::getStatus, STATUS_PENDING)
                .and(wrapper -> wrapper.isNull(ProfileExtractJob::getRetryAt)
                        .or()
                        .le(ProfileExtractJob::getRetryAt, now))
                .orderByAsc(ProfileExtractJob::getCreatedAt)
                .last("LIMIT " + Math.max(1, limit)));
    }

    private ThreadPoolTaskExecutor buildExecutor() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        int size = Math.max(1, properties.getConcurrency());
        pool.setCorePoolSize(size);
        pool.setMaxPoolSize(size);
        pool.setQueueCapacity(Math.max(1, properties.getBatchSize()));
        pool.setThreadNamePrefix("profile-extract-");
        pool.initialize();
        return pool;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
