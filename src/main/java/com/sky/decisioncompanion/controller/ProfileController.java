package com.sky.decisioncompanion.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.common.Result;
import com.sky.decisioncompanion.model.*;
import com.sky.decisioncompanion.repository.*;
import com.sky.decisioncompanion.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profile")
@CrossOrigin(origins = "http://localhost:5173")
@Tag(name = "用户档案", description = "用户档案查询和管理")
public class ProfileController {

    private final UserService userService;
    private final ProfileValuesRepository valuesRepository;
    private final ProfileDecisionRepository decisionRepository;
    private final ProfileEmotionRepository emotionRepository;
    private final ProfileRelationshipRepository relationshipRepository;
    private final ProfileFearRepository fearRepository;

    public ProfileController(
            UserService userService,
            ProfileValuesRepository valuesRepository,
            ProfileDecisionRepository decisionRepository,
            ProfileEmotionRepository emotionRepository,
            ProfileRelationshipRepository relationshipRepository,
            ProfileFearRepository fearRepository) {
        this.userService = userService;
        this.valuesRepository = valuesRepository;
        this.decisionRepository = decisionRepository;
        this.emotionRepository = emotionRepository;
        this.relationshipRepository = relationshipRepository;
        this.fearRepository = fearRepository;
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "获取用户档案", description = "返回用户完整档案信息")
    public ResponseEntity<Result<Map<String, Object>>> getProfile(
            @Parameter(description = "会话ID") @PathVariable @NotBlank String sessionId) {
        User user = userService.getUserBySessionId(sessionId);
        if (user == null) {
            return ResponseEntity.ok(Result.error("用户不存在"));
        }

        Long userId = user.getId();

        return ResponseEntity.ok(Result.success(Map.of(
                "user", user,
                "values", valuesRepository.selectList(new LambdaQueryWrapper<ProfileValues>().eq(ProfileValues::getUserId, userId)),
                "decisions", decisionRepository.selectList(new LambdaQueryWrapper<ProfileDecision>().eq(ProfileDecision::getUserId, userId)),
                "emotions", emotionRepository.selectList(new LambdaQueryWrapper<ProfileEmotion>().eq(ProfileEmotion::getUserId, userId)),
                "relationships", relationshipRepository.selectList(new LambdaQueryWrapper<ProfileRelationship>().eq(ProfileRelationship::getUserId, userId)),
                "fears", fearRepository.selectList(new LambdaQueryWrapper<ProfileFear>().eq(ProfileFear::getUserId, userId))
        )));
    }

    @GetMapping("/{sessionId}/status")
    @Operation(summary = "获取用户状态", description = "返回用户基本信息和冷启动状态")
    public ResponseEntity<Result<Map<String, Object>>> getStatus(
            @Parameter(description = "会话ID") @PathVariable @NotBlank String sessionId) {
        User user = userService.getUserBySessionId(sessionId);
        if (user == null) {
            return ResponseEntity.ok(Result.error("用户不存在"));
        }

        return ResponseEntity.ok(Result.success(Map.of(
                "id", user.getId(),
                "sessionId", user.getSessionId(),
                "nickname", user.getNickname(),
                "onboarded", user.getOnboarded(),
                "createdAt", user.getCreatedAt()
        )));
    }

    @GetMapping("/{sessionId}/values")
    @Operation(summary = "获取价值观档案", description = "返回用户的价值观信息")
    public ResponseEntity<Result<List<ProfileValues>>> getValues(
            @Parameter(description = "会话ID") @PathVariable @NotBlank String sessionId) {
        User user = userService.getUserBySessionId(sessionId);
        if (user == null) {
            return ResponseEntity.ok(Result.error("用户不存在"));
        }

        List<ProfileValues> values = valuesRepository.selectList(
                new LambdaQueryWrapper<ProfileValues>().eq(ProfileValues::getUserId, user.getId())
        );
        return ResponseEntity.ok(Result.success(values));
    }

    @GetMapping("/{sessionId}/decisions")
    @Operation(summary = "获取决策历史", description = "返回用户的决策历史")
    public ResponseEntity<Result<List<ProfileDecision>>> getDecisions(
            @Parameter(description = "会话ID") @PathVariable @NotBlank String sessionId) {
        User user = userService.getUserBySessionId(sessionId);
        if (user == null) {
            return ResponseEntity.ok(Result.error("用户不存在"));
        }

        List<ProfileDecision> decisions = decisionRepository.selectList(
                new LambdaQueryWrapper<ProfileDecision>().eq(ProfileDecision::getUserId, user.getId())
        );
        return ResponseEntity.ok(Result.success(decisions));
    }

    @GetMapping("/{sessionId}/emotions")
    @Operation(summary = "获取情绪模式", description = "返回用户的情绪模式信息")
    public ResponseEntity<Result<List<ProfileEmotion>>> getEmotions(
            @Parameter(description = "会话ID") @PathVariable @NotBlank String sessionId) {
        User user = userService.getUserBySessionId(sessionId);
        if (user == null) {
            return ResponseEntity.ok(Result.error("用户不存在"));
        }

        List<ProfileEmotion> emotions = emotionRepository.selectList(
                new LambdaQueryWrapper<ProfileEmotion>().eq(ProfileEmotion::getUserId, user.getId())
        );
        return ResponseEntity.ok(Result.success(emotions));
    }

    @GetMapping("/{sessionId}/relationships")
    @Operation(summary = "获取关系图谱", description = "返回用户的关系图谱")
    public ResponseEntity<Result<List<ProfileRelationship>>> getRelationships(
            @Parameter(description = "会话ID") @PathVariable @NotBlank String sessionId) {
        User user = userService.getUserBySessionId(sessionId);
        if (user == null) {
            return ResponseEntity.ok(Result.error("用户不存在"));
        }

        List<ProfileRelationship> relationships = relationshipRepository.selectList(
                new LambdaQueryWrapper<ProfileRelationship>().eq(ProfileRelationship::getUserId, user.getId())
        );
        return ResponseEntity.ok(Result.success(relationships));
    }

    @GetMapping("/{sessionId}/fears")
    @Operation(summary = "获取恐惧与边界", description = "返回用户的恐惧与边界信息")
    public ResponseEntity<Result<List<ProfileFear>>> getFears(
            @Parameter(description = "会话ID") @PathVariable @NotBlank String sessionId) {
        User user = userService.getUserBySessionId(sessionId);
        if (user == null) {
            return ResponseEntity.ok(Result.error("用户不存在"));
        }

        List<ProfileFear> fears = fearRepository.selectList(
                new LambdaQueryWrapper<ProfileFear>().eq(ProfileFear::getUserId, user.getId())
        );
        return ResponseEntity.ok(Result.success(fears));
    }
}
