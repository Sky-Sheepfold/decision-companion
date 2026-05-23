package com.sky.decisioncompanion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sky.decisioncompanion.common.BusinessException;
import com.sky.decisioncompanion.common.ResultCode;
import com.sky.decisioncompanion.model.OnboardingProgress;
import com.sky.decisioncompanion.model.User;
import com.sky.decisioncompanion.repository.OnboardingProgressRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class OnboardingService {

    private static final String STATUS_ANSWERED = "answered";
    private static final String STATUS_SKIPPED = "skipped";

    private final UserService userService;
    private final DecisionAgentService agentService;
    private final OnboardingProgressRepository progressRepository;

    private static final List<OnboardingQuestion> QUESTIONS = List.of(
            new OnboardingQuestion(1, "identity", "先自我介绍一下吧，你叫什么名字？现在在什么人生阶段？"),
            new OnboardingQuestion(2, "values", "在生活中，什么对你来说最重要？你最看重的是什么？"),
            new OnboardingQuestion(3, "decisions", "最近有没有什么让你纠结的决策？或者回顾一下，你做过的最重要的决定是什么？"),
            new OnboardingQuestion(4, "emotions", "当你面对压力或者焦虑时，你通常是怎么应对的？"),
            new OnboardingQuestion(5, "relationships", "在你的生活中，有没有对你影响很大的人？你们的关系是怎样的？")
    );

    public OnboardingService(
            UserService userService,
            DecisionAgentService agentService,
            OnboardingProgressRepository progressRepository) {
        this.userService = userService;
        this.agentService = agentService;
        this.progressRepository = progressRepository;
    }

    public List<OnboardingQuestion> getOnboardingQuestions() {
        return QUESTIONS;
    }

    public OnboardingQuestion getQuestion(int step) {
        return QUESTIONS.stream()
                .filter(q -> q.step() == step)
                .findFirst()
                .orElse(null);
    }

    public int getTotalSteps() {
        return QUESTIONS.size();
    }

    public OnboardingStepResult answerStep(Long userId, int step, String answer) {
        validateCurrentStep(userId, step);
        OnboardingQuestion question = requireQuestion(step);
        String prompt = String.format(
                "用户正在完成冷启动问卷。第%d步的问题是：%s\n用户的回答是：%s\n请用温暖、理解的方式回应用户，可以适当追问或总结。",
                step,
                question.question(),
                answer
        );

        String reply = agentService.chat(userId, prompt);
        saveProgress(userId, step, STATUS_ANSWERED, answer, reply);
        OnboardingStatus status = refreshCompletionStatus(userId);

        return new OnboardingStepResult(
                reply,
                status.onboarded(),
                step,
                nextStepFrom(status),
                getTotalSteps()
        );
    }

    public OnboardingStepResult skipStep(Long userId, int step) {
        validateCurrentStep(userId, step);
        requireQuestion(step);

        saveProgress(userId, step, STATUS_SKIPPED, null, null);
        OnboardingStatus status = refreshCompletionStatus(userId);

        return new OnboardingStepResult(
                null,
                status.onboarded(),
                step,
                nextStepFrom(status),
                getTotalSteps()
        );
    }

    public OnboardingStatus getStatus(Long userId) {
        return refreshCompletionStatus(userId);
    }

    private void validateCurrentStep(Long userId, int step) {
        OnboardingStatus status = refreshCompletionStatus(userId);
        if (status.onboarded()) {
            throw new BusinessException(ResultCode.ONBOARDING_COMPLETED);
        }
        if (step != status.currentStep()) {
            throw new BusinessException(ResultCode.ONBOARDING_STEP_MISMATCH);
        }
    }

    private OnboardingQuestion requireQuestion(int step) {
        OnboardingQuestion question = getQuestion(step);
        if (question == null) {
            throw new BusinessException(ResultCode.ONBOARDING_STEP_NOT_FOUND);
        }
        return question;
    }

    private OnboardingStatus refreshCompletionStatus(Long userId) {
        User user = userService.getUserById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        ProgressSummary summary = summarizeProgress(userId);
        boolean onboarded = Boolean.TRUE.equals(user.getOnboarded());
        if (!onboarded && summary.completedSteps() >= getTotalSteps()) {
            userService.markOnboarded(userId);
            onboarded = true;
        }

        int currentStep = onboarded ? getTotalSteps() : firstIncompleteStep(summary.completedStepNumbers());
        return new OnboardingStatus(
                onboarded,
                currentStep,
                getTotalSteps(),
                summary.completedSteps(),
                summary.answeredSteps(),
                summary.skippedSteps()
        );
    }

    private ProgressSummary summarizeProgress(Long userId) {
        List<OnboardingProgress> progresses = getProgresses(userId);
        Set<Integer> completedStepNumbers = new HashSet<>();
        int answeredSteps = 0;
        int skippedSteps = 0;

        for (OnboardingProgress progress : progresses) {
            if (!isValidCompletedStatus(progress.getStatus())) {
                continue;
            }

            Integer step = progress.getStep();
            if (step == null || step < 1 || step > getTotalSteps() || !completedStepNumbers.add(step)) {
                continue;
            }

            if (STATUS_ANSWERED.equals(progress.getStatus())) {
                answeredSteps++;
            } else if (STATUS_SKIPPED.equals(progress.getStatus())) {
                skippedSteps++;
            }
        }

        return new ProgressSummary(completedStepNumbers, completedStepNumbers.size(), answeredSteps, skippedSteps);
    }

    private List<OnboardingProgress> getProgresses(Long userId) {
        return progressRepository.selectList(new LambdaQueryWrapper<OnboardingProgress>()
                .eq(OnboardingProgress::getUserId, userId));
    }

    private boolean isValidCompletedStatus(String status) {
        return STATUS_ANSWERED.equals(status) || STATUS_SKIPPED.equals(status);
    }

    private int firstIncompleteStep(Set<Integer> completedStepNumbers) {
        for (int step = 1; step <= getTotalSteps(); step++) {
            if (!completedStepNumbers.contains(step)) {
                return step;
            }
        }
        return getTotalSteps();
    }

    private int nextStepFrom(OnboardingStatus status) {
        return status.onboarded() ? getTotalSteps() : status.currentStep();
    }

    private void saveProgress(Long userId, int step, String status, String answer, String reply) {
        OnboardingProgress progress = getProgresses(userId).stream()
                .filter(item -> item.getStep() != null && item.getStep() == step)
                .findFirst()
                .orElseGet(OnboardingProgress::new);

        progress.setUserId(userId);
        progress.setStep(step);
        progress.setStatus(status);
        progress.setAnswer(answer);
        progress.setReply(reply);

        if (progress.getId() == null) {
            progressRepository.insert(progress);
        } else {
            progressRepository.updateById(progress);
        }
    }

    public record OnboardingQuestion(int step, String type, String question) {}

    public record OnboardingStepResult(
            String reply,
            @JsonProperty("isCompleted")
            boolean isCompleted,
            int currentStep,
            int nextStep,
            int totalSteps) {}

    public record OnboardingStatus(
            boolean onboarded,
            int currentStep,
            int totalSteps,
            int completedSteps,
            int answeredSteps,
            int skippedSteps) {}

    private record ProgressSummary(
            Set<Integer> completedStepNumbers,
            int completedSteps,
            int answeredSteps,
            int skippedSteps) {}
}
