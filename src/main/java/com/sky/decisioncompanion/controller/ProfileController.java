package com.sky.decisioncompanion.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.decisioncompanion.common.Result;
import com.sky.decisioncompanion.model.*;
import com.sky.decisioncompanion.repository.*;
import com.sky.decisioncompanion.service.UserService;
import com.sky.decisioncompanion.service.memory.MemoryInsightService;
import com.sky.decisioncompanion.service.profile.ProfileMemoryGovernanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
    private final ProfileMemoryGovernanceService profileMemoryGovernanceService;
    private final MemoryInsightService memoryInsightService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProfileController(
            UserService userService,
            ProfileValuesRepository valuesRepository,
            ProfileDecisionRepository decisionRepository,
            ProfileEmotionRepository emotionRepository,
            ProfileRelationshipRepository relationshipRepository,
            ProfileFearRepository fearRepository,
            ProfileMemoryGovernanceService profileMemoryGovernanceService,
            MemoryInsightService memoryInsightService) {
        this.userService = userService;
        this.valuesRepository = valuesRepository;
        this.decisionRepository = decisionRepository;
        this.emotionRepository = emotionRepository;
        this.relationshipRepository = relationshipRepository;
        this.fearRepository = fearRepository;
        this.profileMemoryGovernanceService = profileMemoryGovernanceService;
        this.memoryInsightService = memoryInsightService;
    }

    @GetMapping
    @Operation(summary = "获取当前用户档案", description = "返回当前登录用户完整档案信息")
    public ResponseEntity<Result<Map<String, Object>>> getProfile() {
        Long userId = currentUserId();
        return ResponseEntity.ok(Result.success(Map.of(
                "user", userService.getUserById(userId),
                "values", getValuesByUserId(userId),
                "decisions", getDecisionResponsesByUserId(userId),
                "emotions", getEmotionsByUserId(userId),
                "relationships", getRelationshipsByUserId(userId),
                "fears", getFearsByUserId(userId),
                "pendingMemoryCount", profileMemoryGovernanceService.countPendingCandidates(userId)
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
    public ResponseEntity<Result<List<ProfileDecisionResponse>>> getDecisions() {
        return ResponseEntity.ok(Result.success(getDecisionResponsesByUserId(currentUserId())));
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

    @GetMapping("/pending-memories")
    @Operation(summary = "获取待确认画像记忆", description = "返回当前用户待确认的画像记忆候选")
    public ResponseEntity<Result<List<ProfileMemoryCandidate>>> listPendingMemories() {
        return ResponseEntity.ok(Result.success(profileMemoryGovernanceService.listPendingCandidates(currentUserId())));
    }

    @GetMapping("/memory-audits")
    @Operation(summary = "获取画像记忆治理审计日志", description = "返回当前用户画像记忆确认、拒绝、修正和删除日志")
    public ResponseEntity<Result<List<ProfileMemoryAuditLog>>> listMemoryAudits(@RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(Result.success(profileMemoryGovernanceService.listAuditLogs(currentUserId(), limit)));
    }

    @GetMapping("/insights")
    @Operation(summary = "获取行为动机洞察", description = "返回当前用户的行为动机洞察（含判定状态）")
    public ResponseEntity<Result<Map<String, Object>>> listInsights() {
        Long userId = currentUserId();
        return ResponseEntity.ok(Result.success(Map.of(
                "insights", memoryInsightService.listInsights(userId),
                "unjudgedCount", memoryInsightService.countUnjudged(userId)
        )));
    }

    @PostMapping("/insights/{id}/judge")
    @Operation(summary = "判定行为动机洞察", description = "确认或否定一条行为动机洞察，确认后作为较可信理解沉淀")
    public ResponseEntity<Result<MemoryInsight>> judgeInsight(
            @PathVariable Long id,
            @RequestBody InsightJudgeRequest request) {
        return ResponseEntity.ok(Result.success(memoryInsightService.judge(
                currentUserId(), id, request == null ? null : request.verdict())));
    }

    @PostMapping("/pending-memories/{id}/confirm")
    @Operation(summary = "确认待确认画像记忆", description = "确认候选记忆并写入正式画像")
    public ResponseEntity<Result<ProfileMemoryGovernanceService.GovernanceResult>> confirmPendingMemory(
            @PathVariable Long id) {
        return ResponseEntity.ok(Result.success(profileMemoryGovernanceService.confirmCandidate(currentUserId(), id)));
    }

    @PostMapping("/pending-memories/{id}/reject")
    @Operation(summary = "拒绝待确认画像记忆", description = "拒绝候选记忆并记录原因")
    public ResponseEntity<Result<ProfileMemoryGovernanceService.GovernanceResult>> rejectPendingMemory(
            @PathVariable Long id,
            @RequestBody(required = false) ProfileMemoryReasonRequest request) {
        return ResponseEntity.ok(Result.success(profileMemoryGovernanceService.rejectCandidate(
                currentUserId(), id, reason(request))));
    }

    @PostMapping("/pending-memories/{id}/correct")
    @Operation(summary = "修正待确认画像记忆", description = "修正候选记忆后写入正式画像")
    public ResponseEntity<Result<ProfileMemoryGovernanceService.GovernanceResult>> correctPendingMemory(
            @PathVariable Long id,
            @RequestBody ProfileMemoryCorrectionRequest request) {
        return ResponseEntity.ok(Result.success(profileMemoryGovernanceService.correctCandidate(
                currentUserId(), id, toCorrectionCommand(request))));
    }

    @PatchMapping("/{profileType}/{id}")
    @Operation(summary = "修正正式画像记忆", description = "软失效原记录并写入修正后的正式画像")
    public ResponseEntity<Result<ProfileMemoryGovernanceService.GovernanceResult>> correctProfile(
            @PathVariable String profileType,
            @PathVariable Long id,
            @RequestBody ProfileMemoryCorrectionRequest request) {
        return ResponseEntity.ok(Result.success(profileMemoryGovernanceService.correctProfile(
                currentUserId(), profileType, id, toCorrectionCommand(request))));
    }

    @DeleteMapping("/{profileType}/{id}")
    @Operation(summary = "删除正式画像记忆", description = "软删除正式画像记忆并记录原因")
    public ResponseEntity<Result<ProfileMemoryGovernanceService.GovernanceResult>> deleteProfile(
            @PathVariable String profileType,
            @PathVariable Long id,
            @RequestBody(required = false) ProfileMemoryReasonRequest request) {
        return ResponseEntity.ok(Result.success(profileMemoryGovernanceService.deleteProfile(
                currentUserId(), profileType, id, reason(request))));
    }

    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    private List<ProfileValues> getValuesByUserId(Long userId) {
        return valuesRepository.selectList(new LambdaQueryWrapper<ProfileValues>()
                        .eq(ProfileValues::getUserId, userId)
                        .eq(ProfileValues::getActive, true))
                .stream()
                .filter(value -> Boolean.TRUE.equals(value.getActive()))
                .toList();
    }

    private List<ProfileDecision> getDecisionsByUserId(Long userId) {
        return decisionRepository.selectList(new LambdaQueryWrapper<ProfileDecision>().eq(ProfileDecision::getUserId, userId));
    }

    private List<ProfileDecisionResponse> getDecisionResponsesByUserId(Long userId) {
        return getDecisionsByUserId(userId).stream()
                .map(this::toDecisionResponse)
                .toList();
    }

    private ProfileDecisionResponse toDecisionResponse(ProfileDecision decision) {
        return new ProfileDecisionResponse(
                decision.getId(),
                decision.getUserId(),
                decision.getTopic(),
                decision.getChoice(),
                decision.getReason(),
                decision.getOutcome(),
                decision.getSatisfaction(),
                parseTags(decision.getTags()),
                decision.getDecisionDate(),
                decision.getCreatedAt()
        );
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }

        try {
            JsonNode node = objectMapper.readTree(tags);
            if (node.isArray()) {
                List<String> parsedTags = new ArrayList<>();
                node.forEach(item -> {
                    if (item.isTextual()) {
                        parsedTags.add(item.asText());
                    } else if (!item.isNull()) {
                        parsedTags.add(item.toString());
                    }
                });
                return parsedTags;
            }
            if (node.isTextual()) {
                return splitTags(node.asText());
            }
        } catch (JsonProcessingException ignored) {
            return splitTags(tags);
        }

        return List.of();
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split("[,，、]+"))
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .toList();
    }

    private List<ProfileEmotion> getEmotionsByUserId(Long userId) {
        return emotionRepository.selectList(new LambdaQueryWrapper<ProfileEmotion>()
                        .eq(ProfileEmotion::getUserId, userId)
                        .eq(ProfileEmotion::getActive, true))
                .stream()
                .filter(emotion -> Boolean.TRUE.equals(emotion.getActive()))
                .toList();
    }

    private List<ProfileRelationship> getRelationshipsByUserId(Long userId) {
        return relationshipRepository.selectList(new LambdaQueryWrapper<ProfileRelationship>()
                        .eq(ProfileRelationship::getUserId, userId)
                        .eq(ProfileRelationship::getActive, true))
                .stream()
                .filter(relationship -> Boolean.TRUE.equals(relationship.getActive()))
                .toList();
    }

    private List<ProfileFear> getFearsByUserId(Long userId) {
        return fearRepository.selectList(new LambdaQueryWrapper<ProfileFear>()
                        .eq(ProfileFear::getUserId, userId)
                        .eq(ProfileFear::getActive, true))
                .stream()
                .filter(fear -> Boolean.TRUE.equals(fear.getActive()))
                .toList();
    }

    private String reason(ProfileMemoryReasonRequest request) {
        return request == null ? null : request.reason();
    }

    private ProfileMemoryGovernanceService.MemoryCorrectionCommand toCorrectionCommand(
            ProfileMemoryCorrectionRequest request) {
        return new ProfileMemoryGovernanceService.MemoryCorrectionCommand(
                request == null ? null : request.subject(),
                request == null ? null : request.content(),
                request == null ? null : request.detail(),
                request == null ? null : request.reason());
    }

    public record ProfileDecisionResponse(
            Long id,
            Long userId,
            String topic,
            String choice,
            String reason,
            String outcome,
            Integer satisfaction,
            List<String> tags,
            String decisionDate,
            LocalDateTime createdAt
    ) {
    }

    public record ProfileMemoryReasonRequest(String reason) {
    }

    public record ProfileMemoryCorrectionRequest(String subject, String content, String detail, String reason) {
    }

    public record InsightJudgeRequest(String verdict) {
    }
}
