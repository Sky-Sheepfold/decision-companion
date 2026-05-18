package com.sky.decisioncompanion.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.sky.decisioncompanion.common.Result;
import com.sky.decisioncompanion.model.User;
import com.sky.decisioncompanion.service.OnboardingService;
import com.sky.decisioncompanion.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/onboarding")
@Tag(name = "冷启动服务", description = "新用户引导和档案初始化")
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final UserService userService;

    public OnboardingController(OnboardingService onboardingService, UserService userService) {
        this.onboardingService = onboardingService;
        this.userService = userService;
    }

    @GetMapping("/questions")
    @Operation(summary = "获取冷启动问题列表", description = "返回所有冷启动引导问题")
    public ResponseEntity<Result<List<OnboardingService.OnboardingQuestion>>> getQuestions() {
        return ResponseEntity.ok(Result.success(onboardingService.getOnboardingQuestions()));
    }

    @GetMapping("/questions/{step}")
    @Operation(summary = "获取指定步骤的问题", description = "根据步骤号返回对应的问题")
    public ResponseEntity<Result<OnboardingService.OnboardingQuestion>> getQuestion(
            @Parameter(description = "问题步骤号") @PathVariable @Min(1) @Max(5) int step) {
        OnboardingService.OnboardingQuestion question = onboardingService.getQuestion(step);
        if (question == null) {
            return ResponseEntity.badRequest()
                    .body(Result.error("步骤不存在"));
        }
        return ResponseEntity.ok(Result.success(question));
    }

    @PostMapping("/answer")
    @Operation(summary = "提交问题答案", description = "处理用户的回答，返回AI的响应")
    public ResponseEntity<Result<Map<String, Object>>> submitAnswer(
            @Valid @RequestBody OnboardingAnswerRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        String reply = onboardingService.processAnswer(
                userId,
                request.step(),
                request.answer()
        );

        boolean isLastStep = request.step() >= onboardingService.getTotalSteps();

        if (isLastStep) {
            onboardingService.completeOnboarding(userId);
        }

        return ResponseEntity.ok(Result.success(Map.of(
                "reply", reply,
                "isCompleted", isLastStep,
                "currentStep", request.step(),
                "totalSteps", onboardingService.getTotalSteps()
        )));
    }

    @GetMapping("/status")
    @Operation(summary = "获取冷启动状态", description = "检查用户是否已完成冷启动")
    public ResponseEntity<Result<Map<String, Object>>> getStatus() {
        User user = userService.getUserById(StpUtil.getLoginIdAsLong());
        boolean completed = user != null && Boolean.TRUE.equals(user.getOnboarded());
        int currentStep = completed ? onboardingService.getTotalSteps() : 1;

        return ResponseEntity.ok(Result.success(Map.of(
                "onboarded", completed,
                "currentStep", currentStep,
                "totalSteps", onboardingService.getTotalSteps()
        )));
    }

    public record OnboardingAnswerRequest(
            @Parameter(description = "问题步骤号") @Min(1) @Max(5) int step,
            @Parameter(description = "用户答案") @NotBlank String answer) {}
}
