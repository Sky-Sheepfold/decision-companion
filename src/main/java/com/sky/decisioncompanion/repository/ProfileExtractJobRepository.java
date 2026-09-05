package com.sky.decisioncompanion.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sky.decisioncompanion.model.ProfileExtractJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ProfileExtractJobRepository extends BaseMapper<ProfileExtractJob> {

    /**
     * 原子认领一个待处理任务（CAS）。
     * 条件中的 {@code status='pending'} + {@code retry_at} 到期 + {@code attempts} 未耗尽，保证多 Worker 不重复认领。
     */
    @Update("""
            UPDATE profile_extract_job
            SET status = 'processing', claim_token = #{token}, claimed_at = NOW(), updated_at = NOW()
            WHERE id = #{id}
              AND status = 'pending'
              AND (retry_at IS NULL OR retry_at <= NOW())
              AND attempts < #{maxAttempts}
            """)
    int tryClaim(@Param("id") Long id, @Param("token") String token, @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE profile_extract_job
            SET status = 'success', saved_count = #{savedCount}, last_error = NULL,
                claim_token = NULL, claimed_at = NULL, updated_at = NOW()
            WHERE id = #{id} AND claim_token = #{token} AND status = 'processing'
            """)
    int completeSuccess(@Param("id") Long id, @Param("token") String token, @Param("savedCount") int savedCount);

    /**
     * 失败但未耗尽尝试次数：回到 pending，并设置下次重试时间（分级退避）。
     */
    @Update("""
            UPDATE profile_extract_job
            SET status = 'pending', attempts = attempts + 1, retry_at = #{retryAt},
                last_error = #{error}, claim_token = NULL, claimed_at = NULL, updated_at = NOW()
            WHERE id = #{id} AND claim_token = #{token} AND status = 'processing'
              AND attempts < #{maxAttempts}
            """)
    int completeRetryLater(@Param("id") Long id, @Param("token") String token,
                           @Param("retryAt") LocalDateTime retryAt,
                           @Param("error") String error, @Param("maxAttempts") int maxAttempts);

    /**
     * 永久失败或尝试次数耗尽：标记 failed，保留原行便于审计。
     */
    @Update("""
            UPDATE profile_extract_job
            SET status = 'failed', attempts = attempts + 1,
                last_error = #{error}, claim_token = NULL, claimed_at = NULL, updated_at = NOW()
            WHERE id = #{id} AND claim_token = #{token} AND status = 'processing'
            """)
    int completeFailed(@Param("id") Long id, @Param("token") String token, @Param("error") String error);

    /**
     * 回收孤儿任务：processing 超时（进程重启或 Worker 崩溃）后重置回 pending。
     */
    @Update("""
            UPDATE profile_extract_job
            SET status = 'pending', claim_token = NULL, claimed_at = NULL, updated_at = NOW()
            WHERE status = 'processing' AND claimed_at < #{threshold}
            """)
    int recoverOrphans(@Param("threshold") LocalDateTime threshold);
}
