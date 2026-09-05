package com.sky.decisioncompanion.service;

import com.sky.decisioncompanion.model.OnboardingProgress;
import com.sky.decisioncompanion.model.User;
import com.sky.decisioncompanion.repository.OnboardingProgressRepository;
import com.sky.decisioncompanion.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserService userService;

    @Mock
    private ProfileExtractJobService profileExtractJobService;

    @Mock
    private OnboardingProgressRepository progressRepository;

    private final List<OnboardingProgress> progresses = new ArrayList<>();
    private User user;
    private OnboardingService onboardingService;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(USER_ID);
        user.setUsername("tester");
        user.setPassword("encoded-password");
        user.setOnboarded(false);

        lenient().when(userService.getUserById(USER_ID)).thenReturn(user);
        lenient().when(progressRepository.selectList(any())).thenAnswer(invocation -> new ArrayList<>(progresses));
        lenient().when(progressRepository.insert(any(OnboardingProgress.class))).thenAnswer(invocation -> {
            OnboardingProgress progress = invocation.getArgument(0);
            progress.setId((long) progresses.size() + 1);
            progresses.add(progress);
            return 1;
        });
        lenient().when(progressRepository.updateById(any(OnboardingProgress.class))).thenAnswer(invocation -> {
            OnboardingProgress updated = invocation.getArgument(0);
            progresses.replaceAll(existing -> Objects.equals(existing.getId(), updated.getId()) ? updated : existing);
            return 1;
        });
        lenient().doAnswer(invocation -> {
            user.setOnboarded(true);
            return null;
        }).when(userService).markOnboarded(USER_ID);

        onboardingService = new OnboardingService(userService, profileExtractJobService, progressRepository);
    }

    @Test
    void statusForNewUserStartsAtFirstStep() {
        OnboardingService.OnboardingStatus status = onboardingService.getStatus(USER_ID);

        assertThat(status.onboarded()).isFalse();
        assertThat(status.currentStep()).isEqualTo(1);
        assertThat(status.totalSteps()).isEqualTo(5);
        assertThat(status.completedSteps()).isZero();
        assertThat(status.answeredSteps()).isZero();
        assertThat(status.skippedSteps()).isZero();
    }

    @Test
    void answeringStepReadsProgressOnlyOnce() {
        onboardingService.answerStep(USER_ID, 1, "我叫小杨");

        verify(userService, times(1)).getUserById(USER_ID);
        verify(progressRepository, times(1)).selectList(any());
    }

    @Test
    void answeringFirstStepMovesCurrentStepToSecondStep() {
        OnboardingService.OnboardingStepResult result = onboardingService.answerStep(USER_ID, 1, "我叫小杨");
        OnboardingService.OnboardingStatus status = onboardingService.getStatus(USER_ID);

        assertThat(result.isCompleted()).isFalse();
        assertThat(result.currentStep()).isEqualTo(1);
        assertThat(result.nextStep()).isEqualTo(2);
        assertThat(result.reply()).isNull();
        assertThat(status.currentStep()).isEqualTo(2);
        assertThat(status.completedSteps()).isEqualTo(1);
        assertThat(status.answeredSteps()).isEqualTo(1);
        assertThat(status.skippedSteps()).isZero();
        assertThat(progresses).singleElement().satisfies(progress -> {
                assertThat(progress.getStatus()).isEqualTo("answered");
                assertThat(progress.getAnswer()).isEqualTo("我叫小杨");
                assertThat(progress.getReply()).isNull();
            });
        verifyNoInteractions(profileExtractJobService);
    }

    @Test
    void finishingOnboardingExtractsProfileOnceFromAnsweredSteps() {
        onboardingService.answerStep(USER_ID, 1, "我叫小杨");
        onboardingService.answerStep(USER_ID, 2, "自由和稳定都很重要");
        onboardingService.skipStep(USER_ID, 3);
        onboardingService.skipStep(USER_ID, 4);
        OnboardingService.OnboardingStepResult finalResult = onboardingService.answerStep(USER_ID, 5, "妈妈对我影响很大");

        assertThat(finalResult.isCompleted()).isTrue();
        assertThat(finalResult.currentStep()).isEqualTo(5);
        assertThat(finalResult.nextStep()).isEqualTo(5);
        assertThat(finalResult.reply()).isNull();
        assertThat(user.getOnboarded()).isTrue();
        verify(userService).markOnboarded(USER_ID);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(profileExtractJobService).submit(eq(USER_ID), isNull(), messageCaptor.capture(), eq(""), eq("onboarding"));
        assertThat(messageCaptor.getValue())
                .contains("先自我介绍一下吧", "我叫小杨")
                .contains("什么对你来说最重要", "自由和稳定都很重要")
                .contains("有没有对你影响很大的人", "妈妈对我影响很大")
                .doesNotContain("面对压力或者焦虑");
    }

    @Test
    void outOfOrderStepIsRejected() {
        assertThatThrownBy(() -> onboardingService.skipStep(USER_ID, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请按当前步骤提交");

        verify(progressRepository, never()).insert(any(OnboardingProgress.class));
    }
}
