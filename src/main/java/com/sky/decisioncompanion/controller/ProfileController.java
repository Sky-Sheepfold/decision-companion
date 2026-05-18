package com.sky.decisioncompanion.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.common.Result;
import com.sky.decisioncompanion.model.*;
import com.sky.decisioncompanion.repository.*;
import com.sky.decisioncompanion.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profile")
@Tag(name = "用户档案", description = "当前登录用户的档案查询和管理")
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

    @GetMapping
    @Operation(summary = "获取当前用户档案", description = "返回当前登录用户完整档案信息")
    public ResponseEntity<Result<Map<String, Object>>> getProfile() {
        Long userId = currentUserId();
        return ResponseEntity.ok(Result.success(Map.of(
                "user", userService.getUserById(userId),
                "values", getValuesByUserId(userId),
                "decisions", getDecisionsByUserId(userId),
                "emotions", getEmotionsByUserId(userId),
                "relationships", getRelationshipsByUserId(userId),
                "fears", getFearsByUserId(userId)
        )));
    }

    @GetMapping("/status")
    @Operation(summary = "获取当前用户状态", description = "返回当前用户基本信息和冷启动状态")
    public ResponseEntity<Result<User>> getStatus() {
        return ResponseEntity.ok(Result.success(userService.getUserById(currentUserId())));
    }

    @GetMapping("/values")
    @Operation(summary = "获取价值观档案", description = "返回当前用户的价值观信息")
    public ResponseEntity<Result<List<ProfileValues>>> getValues() {
        return ResponseEntity.ok(Result.success(getValuesByUserId(currentUserId())));
    }

    @GetMapping("/decisions")
    @Operation(summary = "获取决策历史", description = "返回当前用户的决策历史")
    public ResponseEntity<Result<List<ProfileDecision>>> getDecisions() {
        return ResponseEntity.ok(Result.success(getDecisionsByUserId(currentUserId())));
    }

    @GetMapping("/emotions")
    @Operation(summary = "获取情绪模式", description = "返回当前用户的情绪模式信息")
    public ResponseEntity<Result<List<ProfileEmotion>>> getEmotions() {
        return ResponseEntity.ok(Result.success(getEmotionsByUserId(currentUserId())));
    }

    @GetMapping("/relationships")
    @Operation(summary = "获取关系图谱", description = "返回当前用户的关系图谱")
    public ResponseEntity<Result<List<ProfileRelationship>>> getRelationships() {
        return ResponseEntity.ok(Result.success(getRelationshipsByUserId(currentUserId())));
    }

    @GetMapping("/fears")
    @Operation(summary = "获取恐惧与边界", description = "返回当前用户的恐惧与边界信息")
    public ResponseEntity<Result<List<ProfileFear>>> getFears() {
        return ResponseEntity.ok(Result.success(getFearsByUserId(currentUserId())));
    }

    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    private List<ProfileValues> getValuesByUserId(Long userId) {
        return valuesRepository.selectList(new LambdaQueryWrapper<ProfileValues>().eq(ProfileValues::getUserId, userId));
    }

    private List<ProfileDecision> getDecisionsByUserId(Long userId) {
        return decisionRepository.selectList(new LambdaQueryWrapper<ProfileDecision>().eq(ProfileDecision::getUserId, userId));
    }

    private List<ProfileEmotion> getEmotionsByUserId(Long userId) {
        return emotionRepository.selectList(new LambdaQueryWrapper<ProfileEmotion>().eq(ProfileEmotion::getUserId, userId));
    }

    private List<ProfileRelationship> getRelationshipsByUserId(Long userId) {
        return relationshipRepository.selectList(new LambdaQueryWrapper<ProfileRelationship>().eq(ProfileRelationship::getUserId, userId));
    }

    private List<ProfileFear> getFearsByUserId(Long userId) {
        return fearRepository.selectList(new LambdaQueryWrapper<ProfileFear>().eq(ProfileFear::getUserId, userId));
    }
}
