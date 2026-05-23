package com.sky.decisioncompanion.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.sky.decisioncompanion.common.BusinessException;
import com.sky.decisioncompanion.common.Result;
import com.sky.decisioncompanion.common.ResultCode;
import com.sky.decisioncompanion.service.OnboardingService;
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

@RestController
@RequestMapping("/api/onboarding")
@Tag(name = "冷启动服务", description = "新用户引导和档案初始化")
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
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
            throw new BusinessException(ResultCode.ONBOARDING_STEP_NOT_FOUND);
        }
        return ResponseEntity.ok(Result.success(question));
    }

    @PostMapping("/answer")
    @Operation(summary = "提交问题答案", description = "处理用户的回答，返回AI的响应")
    public ResponseEntity<Result<OnboardingService.OnboardingStepResult>> submitAnswer(
            @Valid @RequestBody OnboardingAnswerRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        OnboardingService.OnboardingStepResult result = onboardingService.answerStep(
                userId,
                request.step(),
                request.answer()
        );

        return ResponseEntity.ok(Result.success(result));
    }

    @PostMapping("/skip")
    @Operation(summary = "跳过当前问题", description = "记录当前问题已跳过，并推进冷启动进度")
    public ResponseEntity<Result<OnboardingService.OnboardingStepResult>> skipQuestion(
            @Valid @RequestBody OnboardingSkipRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        OnboardingService.OnboardingStepResult result = onboardingService.skipStep(userId, request.step());
        return ResponseEntity.ok(Result.success(result));
    }

    @GetMapping("/status")
    @Operation(summary = "获取冷启动状态", description = "检查用户是否已完成冷启动")
    public ResponseEntity<Result<OnboardingService.OnboardingStatus>> getStatus() {
        return ResponseEntity.ok(Result.success(onboardingService.getStatus(StpUtil.getLoginIdAsLong())));
    }

    public record OnboardingAnswerRequest(
            @Parameter(description = "问题步骤号") @Min(1) @Max(5) int step,
            @Parameter(description = "用户答案") @NotBlank String answer) {}

    public record OnboardingSkipRequest(
            @Parameter(description = "问题步骤号") @Min(1) @Max(5) int step) {}
}
