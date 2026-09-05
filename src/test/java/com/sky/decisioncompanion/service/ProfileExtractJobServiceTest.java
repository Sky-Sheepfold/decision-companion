package com.sky.decisioncompanion.service;

import com.sky.decisioncompanion.config.ProfileExtractProperties;
import com.sky.decisioncompanion.model.ProfileExtractJob;
import com.sky.decisioncompanion.repository.ProfileExtractJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileExtractJobServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private ProfileExtractJobRepository jobRepository;

    @Mock
    private ProfileExtractService profileExtractService;

    private ProfileExtractProperties properties;
    private ProfileExtractJobService service;

    @BeforeEach
    void setUp() {
        properties = new ProfileExtractProperties();
        properties.setEnabled(true);
        properties.setConcurrency(2);
        properties.setMaxAttempts(3);
        properties.setRetryDelayMs(15_000L);
        service = new ProfileExtractJobService(jobRepository, profileExtractService, properties);
    }

    @Test
    void submitInsertsPendingJob() {
        service.submit(USER_ID, 5L, "消息", "回复", "chat");

        ArgumentCaptor<ProfileExtractJob> captor = ArgumentCaptor.forClass(ProfileExtractJob.class);
        verify(jobRepository).insert(captor.capture());
        ProfileExtractJob job = captor.getValue();
        assertThat(job.getUserId()).isEqualTo(USER_ID);
        assertThat(job.getConversationId()).isEqualTo(5L);
        assertThat(job.getSource()).isEqualTo("chat");
        assertThat(job.getStatus()).isEqualTo("pending");
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getUserMessage()).isEqualTo("消息");
        assertThat(job.getAiResponse()).isEqualTo("回复");
    }

    @Test
    void submitSkipsWhenExtractionDisabled() {
        properties.setEnabled(false);
        service.submit(USER_ID, null, "消息", "回复", "chat");
        verify(jobRepository, never()).insert(any(ProfileExtractJob.class));
    }

    @Test
    void transientFailureSchedulesRetryWithBackoff() {
        ProfileExtractJob job = job(1L, 1);
        lenient().when(jobRepository.completeRetryLater(any(), any(), any(), any(), anyInt())).thenReturn(1);

        service.handleFailure(job, "token", new RuntimeException("read timed out"));

        ArgumentCaptor<LocalDateTime> retryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(jobRepository).completeRetryLater(any(), any(), retryCaptor.capture(), any(), anyInt());
        LocalDateTime retryAt = retryCaptor.getValue();
        assertThat(retryAt).isAfter(LocalDateTime.now().plusSeconds(20));
        assertThat(retryAt).isBefore(LocalDateTime.now().plusSeconds(45));
    }

    @Test
    void permanentFailureMarksJobFailedImmediately() {
        ProfileExtractJob job = job(1L, 0);

        service.handleFailure(job, "token", new RuntimeException("401 invalid api key"));

        verify(jobRepository).completeFailed(any(), any(), any());
        verify(jobRepository, never()).completeRetryLater(any(), any(), any(), any(), anyInt());
    }

    @Test
    void failureAfterMaxAttemptsMarksJobFailed() {
        ProfileExtractJob job = job(1L, properties.getMaxAttempts() - 1);

        service.handleFailure(job, "token", new RuntimeException("read timed out"));

        verify(jobRepository).completeFailed(any(), any(), any());
        verify(jobRepository, never()).completeRetryLater(any(), any(), any(), any(), anyInt());
    }

    @Test
    void pollClaimsAndDispatchesPendingJobs() {
        ProfileExtractJob pending = job(1L, 0);
        when(jobRepository.selectList(any())).thenReturn(List.of(pending));
        lenient().when(jobRepository.tryClaim(any(), any(), anyInt())).thenReturn(1);
        lenient().when(jobRepository.completeSuccess(any(), any(), anyInt())).thenReturn(1);
        lenient().when(profileExtractService.extractAndSave(USER_ID, "消息", "回复")).thenReturn(2);

        service.poll();

        verify(jobRepository).tryClaim(any(), any(), anyInt());
    }

    @Test
    void pollSkipsClaimedByAnotherWorker() {
        ProfileExtractJob pending = job(1L, 0);
        when(jobRepository.selectList(any())).thenReturn(List.of(pending));
        // 竞争失败：tryClaim 返回 0
        when(jobRepository.tryClaim(any(), any(), anyInt())).thenReturn(0);

        service.poll();

        verify(jobRepository).tryClaim(any(), any(), anyInt());
        verify(profileExtractService, never()).extractAndSave(any(), any(), any());
    }

    private ProfileExtractJob job(Long id, int attempts) {
        ProfileExtractJob job = new ProfileExtractJob();
        job.setId(id);
        job.setUserId(USER_ID);
        job.setStatus("pending");
        job.setAttempts(attempts);
        job.setUserMessage("消息");
        job.setAiResponse("回复");
        return job;
    }
}
