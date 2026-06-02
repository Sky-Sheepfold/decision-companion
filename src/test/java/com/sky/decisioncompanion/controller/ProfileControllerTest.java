package com.sky.decisioncompanion.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.decisioncompanion.common.Result;
import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.model.ProfileEmotion;
import com.sky.decisioncompanion.model.ProfileFear;
import com.sky.decisioncompanion.model.ProfileMemoryAuditLog;
import com.sky.decisioncompanion.model.ProfileMemoryCandidate;
import com.sky.decisioncompanion.model.ProfileRelationship;
import com.sky.decisioncompanion.model.ProfileValues;
import com.sky.decisioncompanion.model.User;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import com.sky.decisioncompanion.service.UserService;
import com.sky.decisioncompanion.service.profile.ProfileMemoryGovernanceService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ProfileControllerTest {

    private static final Long USER_ID = 7L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserService userService = mock(UserService.class);
    private final ProfileValuesRepository valuesRepository = mock(ProfileValuesRepository.class);
    private final ProfileDecisionRepository decisionRepository = mock(ProfileDecisionRepository.class);
    private final ProfileEmotionRepository emotionRepository = mock(ProfileEmotionRepository.class);
    private final ProfileRelationshipRepository relationshipRepository = mock(ProfileRelationshipRepository.class);
    private final ProfileFearRepository fearRepository = mock(ProfileFearRepository.class);
    private final ProfileMemoryGovernanceService governanceService = mock(ProfileMemoryGovernanceService.class);
    private final ProfileController controller = new ProfileController(
            userService,
            valuesRepository,
            decisionRepository,
            emotionRepository,
            relationshipRepository,
            fearRepository,
            governanceService
    );

    @Test
    void getDecisionsSerializesJsonTagsAsArray() {
        ProfileDecision decision = decisionWithTags("[\"专业\",\"就业\"]");

        when(decisionRepository.selectList(any())).thenReturn(List.of(decision));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            Result<?> result = controller.getDecisions().getBody();
            JsonNode data = objectMapper.valueToTree(result.getData());
            JsonNode tags = data.get(0).get("tags");

            assertThat(tags.isArray()).isTrue();
            assertThat(tags.get(0).asText()).isEqualTo("专业");
            assertThat(tags.get(1).asText()).isEqualTo("就业");
        }
    }

    @Test
    void getProfileSerializesDecisionTagsAsArrayAndIncludesPendingMemoryCount() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("小杨");
        user.setOnboarded(true);

        when(userService.getUserById(USER_ID)).thenReturn(user);
        when(valuesRepository.selectList(any())).thenReturn(List.of());
        when(decisionRepository.selectList(any())).thenReturn(List.of(decisionWithTags("[\"实习\",\"城市\"]")));
        when(emotionRepository.selectList(any())).thenReturn(List.of());
        when(relationshipRepository.selectList(any())).thenReturn(List.of());
        when(fearRepository.selectList(any())).thenReturn(List.of());
        when(governanceService.countPendingCandidates(USER_ID)).thenReturn(3);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            Result<?> result = controller.getProfile().getBody();
            JsonNode profile = objectMapper.valueToTree(result.getData());
            JsonNode tags = profile.get("decisions").get(0).get("tags");

            assertThat(tags.isArray()).isTrue();
            assertThat(tags.get(0).asText()).isEqualTo("实习");
            assertThat(tags.get(1).asText()).isEqualTo("城市");
            assertThat(profile.get("pendingMemoryCount").asInt()).isEqualTo(3);
            verify(governanceService).countPendingCandidates(USER_ID);
        }
    }

    @Test
    void getProfileReturnsOnlyActiveFormalProfileRecordsButKeepsDecisionsUnchanged() {
        when(userService.getUserById(USER_ID)).thenReturn(new User());
        when(valuesRepository.selectList(any())).thenReturn(List.of(
                value("城市偏好", "更看重离家近", true),
                value("过期偏好", "曾经想远离家庭", false)));
        when(decisionRepository.selectList(any())).thenReturn(List.of(decisionWithTags("历史")));
        when(emotionRepository.selectList(any())).thenReturn(List.of(
                emotion("焦虑", "被催促时容易压力变大", true),
                emotion("兴奋", "已失效的冲动模式", false)));
        when(relationshipRepository.selectList(any())).thenReturn(List.of(
                relationship("妈妈", "从安全和稳定角度影响选择", true),
                relationship("前同事", "已失效的建议影响", false)));
        when(fearRepository.selectList(any())).thenReturn(List.of(
                fear("fear", "害怕离家太远", true),
                fear("boundary", "已撤销的边界", false)));
        when(governanceService.countPendingCandidates(USER_ID)).thenReturn(0);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            Result<?> result = controller.getProfile().getBody();
            JsonNode profile = objectMapper.valueToTree(result.getData());

            assertThat(profile.get("values")).hasSize(1);
            assertThat(profile.get("values").get(0).get("preference").asText()).isEqualTo("更看重离家近");
            assertThat(profile.get("emotions")).hasSize(1);
            assertThat(profile.get("emotions").get(0).get("behavior").asText()).isEqualTo("被催促时容易压力变大");
            assertThat(profile.get("relationships")).hasSize(1);
            assertThat(profile.get("relationships").get(0).get("influenceStyle").asText()).isEqualTo("从安全和稳定角度影响选择");
            assertThat(profile.get("fears")).hasSize(1);
            assertThat(profile.get("fears").get(0).get("description").asText()).isEqualTo("害怕离家太远");
            assertThat(profile.get("decisions")).hasSize(1);
            assertThat(profile.toString()).doesNotContain("曾经想远离家庭");
            assertThat(profile.toString()).doesNotContain("已失效的冲动模式");
            assertThat(profile.toString()).doesNotContain("已失效的建议影响");
            assertThat(profile.toString()).doesNotContain("已撤销的边界");
        }
    }

    @Test
    void listPendingMemoriesUsesCurrentUser() {
        ProfileMemoryCandidate candidate = new ProfileMemoryCandidate();
        candidate.setId(11L);
        when(governanceService.listPendingCandidates(USER_ID)).thenReturn(List.of(candidate));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            Result<?> result = controller.listPendingMemories().getBody();

            assertThat((List<?>) result.getData()).hasSize(1);
            verify(governanceService).listPendingCandidates(USER_ID);
        }
    }

    @Test
    void confirmPendingMemoryUsesCurrentUserAndCandidateId() {
        ProfileMemoryGovernanceService.GovernanceResult governanceResult =
                new ProfileMemoryGovernanceService.GovernanceResult(true, "confirm", "value", 21L, 11L, "ok");
        when(governanceService.confirmCandidate(USER_ID, 11L)).thenReturn(governanceResult);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            Result<?> result = controller.confirmPendingMemory(11L).getBody();

            assertThat(result.getData()).isEqualTo(governanceResult);
            verify(governanceService).confirmCandidate(USER_ID, 11L);
        }
    }

    @Test
    void rejectPendingMemoryPassesOptionalReason() {
        ProfileController.ProfileMemoryReasonRequest request =
                new ProfileController.ProfileMemoryReasonRequest("用户否认");
        ProfileMemoryGovernanceService.GovernanceResult governanceResult =
                new ProfileMemoryGovernanceService.GovernanceResult(true, "reject", "value", null, 11L, "ok");
        when(governanceService.rejectCandidate(USER_ID, 11L, "用户否认")).thenReturn(governanceResult);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            Result<?> result = controller.rejectPendingMemory(11L, request).getBody();

            assertThat(result.getData()).isEqualTo(governanceResult);
            verify(governanceService).rejectCandidate(USER_ID, 11L, "用户否认");
        }
    }

    @Test
    void correctPendingMemoryBuildsCorrectionCommand() {
        ProfileController.ProfileMemoryCorrectionRequest request =
                new ProfileController.ProfileMemoryCorrectionRequest("城市偏好", "更看重离家近", "家庭距离", "用户修正");
        ProfileMemoryGovernanceService.GovernanceResult governanceResult =
                new ProfileMemoryGovernanceService.GovernanceResult(true, "correct", "value", 22L, 11L, "ok");
        when(governanceService.correctCandidate(eq(USER_ID), eq(11L), any())).thenReturn(governanceResult);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            Result<?> result = controller.correctPendingMemory(11L, request).getBody();

            assertThat(result.getData()).isEqualTo(governanceResult);
            verify(governanceService).correctCandidate(
                    USER_ID,
                    11L,
                    new ProfileMemoryGovernanceService.MemoryCorrectionCommand(
                            "城市偏好", "更看重离家近", "家庭距离", "用户修正"));
        }
    }

    @Test
    void patchActiveProfileBuildsCorrectionCommand() {
        ProfileController.ProfileMemoryCorrectionRequest request =
                new ProfileController.ProfileMemoryCorrectionRequest("城市偏好", "更看重离家近", null, "用户修正");
        ProfileMemoryGovernanceService.GovernanceResult governanceResult =
                new ProfileMemoryGovernanceService.GovernanceResult(true, "correct", "value", 23L, null, "ok");
        when(governanceService.correctProfile(eq(USER_ID), eq("value"), eq(23L), any())).thenReturn(governanceResult);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            Result<?> result = controller.correctProfile("value", 23L, request).getBody();

            assertThat(result.getData()).isEqualTo(governanceResult);
            verify(governanceService).correctProfile(
                    USER_ID,
                    "value",
                    23L,
                    new ProfileMemoryGovernanceService.MemoryCorrectionCommand(
                            "城市偏好", "更看重离家近", null, "用户修正"));
        }
    }

    @Test
    void deleteActiveProfilePassesOptionalReason() {
        ProfileController.ProfileMemoryReasonRequest request =
                new ProfileController.ProfileMemoryReasonRequest("用户要求删除");
        ProfileMemoryGovernanceService.GovernanceResult governanceResult =
                new ProfileMemoryGovernanceService.GovernanceResult(true, "delete", "value", 23L, null, "ok");
        when(governanceService.deleteProfile(USER_ID, "value", 23L, "用户要求删除")).thenReturn(governanceResult);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            Result<?> result = controller.deleteProfile("value", 23L, request).getBody();

            assertThat(result.getData()).isEqualTo(governanceResult);
            verify(governanceService).deleteProfile(USER_ID, "value", 23L, "用户要求删除");
        }
    }

    @Test
    void listMemoryAuditsUsesCurrentUserAndLimit() {
        ProfileMemoryAuditLog auditLog = new ProfileMemoryAuditLog();
        auditLog.setId(31L);
        when(governanceService.listAuditLogs(USER_ID, 20)).thenReturn(List.of(auditLog));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            Result<?> result = controller.listMemoryAudits(20).getBody();

            assertThat((List<?>) result.getData()).hasSize(1);
            verify(governanceService).listAuditLogs(USER_ID, 20);
        }
    }

    private ProfileDecision decisionWithTags(String tags) {
        ProfileDecision decision = new ProfileDecision();
        decision.setId(1L);
        decision.setUserId(USER_ID);
        decision.setTopic("大学专业选择");
        decision.setChoice("选择计算机专业");
        decision.setTags(tags);
        return decision;
    }

    private ProfileValues value(String item, String preference, boolean active) {
        ProfileValues value = new ProfileValues();
        value.setUserId(USER_ID);
        value.setActive(active);
        value.setItem(item);
        value.setPreference(preference);
        value.setConfidence(new BigDecimal("0.90"));
        return value;
    }

    private ProfileEmotion emotion(String name, String behavior, boolean active) {
        ProfileEmotion emotion = new ProfileEmotion();
        emotion.setUserId(USER_ID);
        emotion.setActive(active);
        emotion.setEmotion(name);
        emotion.setBehavior(behavior);
        return emotion;
    }

    private ProfileRelationship relationship(String name, String influenceStyle, boolean active) {
        ProfileRelationship relationship = new ProfileRelationship();
        relationship.setUserId(USER_ID);
        relationship.setActive(active);
        relationship.setName(name);
        relationship.setInfluenceStyle(influenceStyle);
        return relationship;
    }

    private ProfileFear fear(String type, String description, boolean active) {
        ProfileFear fear = new ProfileFear();
        fear.setUserId(USER_ID);
        fear.setActive(active);
        fear.setType(type);
        fear.setDescription(description);
        fear.setConfidence(new BigDecimal("0.80"));
        return fear;
    }
}
