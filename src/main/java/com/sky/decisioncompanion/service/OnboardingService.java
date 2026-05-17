package com.sky.decisioncompanion.service;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OnboardingService {

    private final UserService userService;
    private final DecisionAgentService agentService;

    private static final List<OnboardingQuestion> QUESTIONS = List.of(
            new OnboardingQuestion(1, "identity", "先自我介绍一下吧，你叫什么名字？现在在什么人生阶段？"),
            new OnboardingQuestion(2, "values", "在生活中，什么对你来说最重要？你最看重的是什么？"),
            new OnboardingQuestion(3, "decisions", "最近有没有什么让你纠结的决策？或者回顾一下，你做过的最重要的决定是什么？"),
            new OnboardingQuestion(4, "emotions", "当你面对压力或者焦虑时，你通常是怎么应对的？"),
            new OnboardingQuestion(5, "relationships", "在你的生活中，有没有对你影响很大的人？你们的关系是怎样的？")
    );

    public OnboardingService(UserService userService, DecisionAgentService agentService) {
        this.userService = userService;
        this.agentService = agentService;
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

    public String processAnswer(String sessionId, int step, String answer) {
        String prompt = String.format(
                "用户正在完成冷启动问卷。第%d步的问题是：%s\n用户的回答是：%s\n请用温暖、理解的方式回应用户，可以适当追问或总结。",
                step,
                getQuestion(step).question(),
                answer
        );

        return agentService.chat(sessionId, prompt);
    }

    public void completeOnboarding(String sessionId) {
        userService.markOnboarded(sessionId);
    }

    public record OnboardingQuestion(int step, String type, String question) {}
}
