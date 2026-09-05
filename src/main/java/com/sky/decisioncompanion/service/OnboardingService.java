package com.sky.decisioncompanion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sky.decisioncompanion.common.BusinessException;
import com.sky.decisioncompanion.common.ResultCode;
import com.sky.decisioncompanion.model.OnboardingProgress;
import com.sky.decisioncompanion.model.User;
import com.sky.decisioncompanion.repository.OnboardingProgressRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class OnboardingService {

    private static final Logger logger = LoggerFactory.getLogger(OnboardingService.class);
    private static final String STATUS_ANSWERED = "answered";
    private static final String STATUS_SKIPPED = "skipped";

    private final UserService userService;
    private final ProfileExtractJobService profileExtractJobService;
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
            ProfileExtractJobService profileExtractJobService,
            OnboardingProgressRepository progressRepository) {
        this.userService = userService;
        this.profileExtractJobService = profileExtractJobService;
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
        User user = requireUser(userId);
        List<OnboardingProgress> progresses = getProgresses(userId);
        OnboardingStatus currentStatus = refreshCompletionStatus(user, progresses);

        validateCurrentStep(currentStatus, step);
        requireQuestion(step);

        OnboardingProgress progress = saveProgress(userId, step, STATUS_ANSWERED, answer, null, progresses);
        List<OnboardingProgress> updatedProgresses = withSavedProgress(progresses, progress);
        OnboardingStatus status = refreshCompletionStatus(user, updatedProgresses);
        extractInitialProfileIfCompleted(userId, status, updatedProgresses);

        return new OnboardingStepResult(
                null,
                status.onboarded(),
                step,
                nextStepFrom(status),
                getTotalSteps()
        );
    }

    public OnboardingStepResult skipStep(Long userId, int step) {
        User user = requireUser(userId);
        List<OnboardingProgress> progresses = getProgresses(userId);
        OnboardingStatus currentStatus = refreshCompletionStatus(user, progresses);

        validateCurrentStep(currentStatus, step);
        requireQuestion(step);

        OnboardingProgress progress = saveProgress(userId, step, STATUS_SKIPPED, null, null, progresses);
        List<OnboardingProgress> updatedProgresses = withSavedProgress(progresses, progress);
        OnboardingStatus status = refreshCompletionStatus(user, updatedProgresses);
        extractInitialProfileIfCompleted(userId, status, updatedProgresses);

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

    private void validateCurrentStep(OnboardingStatus status, int step) {
        if (status.onboarded()) {
            throw new BusinessException(ResultCode.ONBOARDING_COMPLETED);
        }
        if (step != status.currentStep()) {
            throw new BusinessException(ResultCode.ONBOARDING_STEP_MISMATCH);
        }
    }

    private void extractInitialProfileIfCompleted(
            Long userId,
            OnboardingStatus status,
            List<OnboardingProgress> progresses) {
        if (!status.onboarded()) {
            return;
        }

        String material = buildInitialProfileMaterial(progresses);
        if (material.isBlank()) {
            return;
        }

        try {
            logger.info("冷启动完成，提交初始画像提炼任务, userId: {}, materialLength: {}", userId, material.length());
            profileExtractJobService.submit(userId, null, material, "", "onboarding");
        } catch (Exception e) {
            logger.warn("冷启动初始画像提炼任务提交失败, userId: {}", userId, e);
        }
    }

    private String buildInitialProfileMaterial(List<OnboardingProgress> progresses) {
        StringBuilder material = new StringBuilder("用户完成冷启动问卷，以下是用户明确填写的回答：\n");
        int headerLength = material.length();
        progresses.stream()
                .filter(progress -> STATUS_ANSWERED.equals(progress.getStatus()))
                .filter(progress -> progress.getStep() != null)
                .filter(progress -> progress.getAnswer() != null && !progress.getAnswer().isBlank())
                .sorted(Comparator.comparing(OnboardingProgress::getStep))
                .forEach(progress -> {
                    OnboardingQuestion question = getQuestion(progress.getStep());
                    if (question == null) {
                        return;
                    }
                    material.append("\n第")
                            .append(progress.getStep())
                            .append("步问题：")
                            .append(question.question())
                            .append("\n用户回答：")
                            .append(progress.getAnswer().trim())
                            .append("\n");
                });

        if (material.length() == headerLength) {
            return "";
        }
        return material.toString();
    }

    private OnboardingQuestion requireQuestion(int step) {
        OnboardingQuestion question = getQuestion(step);
        if (question == null) {
            throw new BusinessException(ResultCode.ONBOARDING_STEP_NOT_FOUND);
        }
        return question;
    }

    private OnboardingStatus refreshCompletionStatus(Long userId) {
        User user = requireUser(userId);
        return refreshCompletionStatus(user, getProgresses(userId));
    }

    private User requireUser(Long userId) {
        User user = userService.getUserById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return user;
    }

    private OnboardingStatus refreshCompletionStatus(User user, List<OnboardingProgress> progresses) {
        ProgressSummary summary = summarizeProgress(progresses);
        boolean onboarded = Boolean.TRUE.equals(user.getOnboarded());
        if (!onboarded && summary.completedSteps() >= getTotalSteps()) {
            userService.markOnboarded(user.getId());
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

    private ProgressSummary summarizeProgress(List<OnboardingProgress> progresses) {
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

    private OnboardingProgress saveProgress(
            Long userId,
            int step,
            String status,
            String answer,
            String reply,
            List<OnboardingProgress> progresses) {
        OnboardingProgress progress = progresses.stream()
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
        return progress;
    }

    private List<OnboardingProgress> withSavedProgress(
            List<OnboardingProgress> progresses,
            OnboardingProgress savedProgress) {
        List<OnboardingProgress> updatedProgresses = new ArrayList<>();
        boolean replaced = false;

        for (OnboardingProgress progress : progresses) {
            if (progress.getStep() != null && progress.getStep().equals(savedProgress.getStep())) {
                updatedProgresses.add(savedProgress);
                replaced = true;
            } else {
                updatedProgresses.add(progress);
            }
        }

        if (!replaced) {
            updatedProgresses.add(savedProgress);
        }

        return updatedProgresses;
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
