package com.sky.decisioncompanion.service;

import com.sky.decisioncompanion.model.OnboardingProgress;
import com.sky.decisioncompanion.model.User;
import com.sky.decisioncompanion.repository.OnboardingProgressRepository;
import com.sky.decisioncompanion.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserService userService;

    @Mock
    private DecisionAgentService agentService;

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

        onboardingService = new OnboardingService(userService, agentService, progressRepository);
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
    void answeringFirstStepMovesCurrentStepToSecondStep() {
        lenient().when(agentService.chat(eq(USER_ID), anyString())).thenReturn("收到，我记下了。");

        OnboardingService.OnboardingStepResult result = onboardingService.answerStep(USER_ID, 1, "我叫小杨");
        OnboardingService.OnboardingStatus status = onboardingService.getStatus(USER_ID);

        assertThat(result.isCompleted()).isFalse();
        assertThat(result.currentStep()).isEqualTo(1);
        assertThat(result.nextStep()).isEqualTo(2);
        assertThat(result.reply()).isEqualTo("收到，我记下了。");
        assertThat(status.currentStep()).isEqualTo(2);
        assertThat(status.completedSteps()).isEqualTo(1);
        assertThat(status.answeredSteps()).isEqualTo(1);
        assertThat(status.skippedSteps()).isZero();
        assertThat(progresses).singleElement().satisfies(progress -> {
            assertThat(progress.getStatus()).isEqualTo("answered");
            assertThat(progress.getAnswer()).isEqualTo("我叫小杨");
        });
    }

    @Test
    void answeringThenSkippingRemainingStepsMarksUserOnboarded() {
        lenient().when(agentService.chat(eq(USER_ID), anyString())).thenReturn("收到，我记下了。");

        onboardingService.answerStep(USER_ID, 1, "我叫小杨");
        onboardingService.skipStep(USER_ID, 2);
        onboardingService.skipStep(USER_ID, 3);
        onboardingService.skipStep(USER_ID, 4);
        OnboardingService.OnboardingStepResult finalResult = onboardingService.skipStep(USER_ID, 5);

        assertThat(finalResult.isCompleted()).isTrue();
        assertThat(finalResult.currentStep()).isEqualTo(5);
        assertThat(finalResult.nextStep()).isEqualTo(5);
        assertThat(user.getOnboarded()).isTrue();
        verify(userService).markOnboarded(USER_ID);
    }

    @Test
    void outOfOrderStepIsRejected() {
        assertThatThrownBy(() -> onboardingService.skipStep(USER_ID, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请按当前步骤提交");

        verify(progressRepository, never()).insert(any(OnboardingProgress.class));
    }
}
