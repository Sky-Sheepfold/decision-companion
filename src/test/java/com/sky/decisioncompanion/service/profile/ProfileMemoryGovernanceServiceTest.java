package com.sky.decisioncompanion.service.profile;

import com.baomidou.mybatisplus.core.conditions.SharedString;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.decisioncompanion.common.BusinessException;
import com.sky.decisioncompanion.common.ResultCode;
import com.sky.decisioncompanion.model.ProfileMemoryAuditLog;
import com.sky.decisioncompanion.model.ProfileMemoryCandidate;
import com.sky.decisioncompanion.model.ProfileSceneMemoryLink;
import com.sky.decisioncompanion.model.ProfileValues;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileMemoryAuditLogRepository;
import com.sky.decisioncompanion.repository.ProfileMemoryCandidateRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileSceneMemoryLinkRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import com.sky.decisioncompanion.service.memory.ProfileSceneMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileMemoryGovernanceServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private ProfileMemoryCandidateRepository candidateRepository;

    @Mock
    private ProfileMemoryAuditLogRepository auditLogRepository;

    @Mock
    private ProfileValuesRepository valuesRepository;

    @Mock
    private ProfileEmotionRepository emotionRepository;

    @Mock
    private ProfileRelationshipRepository relationshipRepository;

    @Mock
    private ProfileFearRepository fearRepository;

    @Mock
    private ProfileSceneMemoryLinkRepository linkRepository;

    @Mock
    private ProfileSceneMemoryService sceneMemoryService;

    private ProfileMemoryGovernanceService service;

    @BeforeEach
    void setUp() {
        service = new ProfileMemoryGovernanceService(
                candidateRepository,
                auditLogRepository,
                valuesRepository,
                emotionRepository,
                relationshipRepository,
                fearRepository,
                linkRepository,
                sceneMemoryService,
                new ObjectMapper());
    }

    @Test
    void createCandidateStoresPendingMemoryForSevenDays() {
        LocalDateTime before = LocalDateTime.now();
        doAnswer(invocation -> {
            ProfileMemoryCandidate candidate = invocation.getArgument(0);
            candidate.setId(11L);
            return 1;
        }).when(candidateRepository).insert(any(ProfileMemoryCandidate.class));

        ProfileMemoryCandidate candidate = service.createCandidate(new ProfileMemoryGovernanceService.MemoryCandidateCommand(
                USER_ID,
                "values",
                "职业节奏",
                "更偏好可预期的成长路径",
                "长期规划",
                new BigDecimal("0.82"),
                List.of("我想慢慢成长", "不太想被推着跑"),
                "profile_extract",
                99L));

        LocalDateTime after = LocalDateTime.now();
        assertThat(candidate.getId()).isEqualTo(11L);
        assertThat(candidate.getUserId()).isEqualTo(USER_ID);
        assertThat(candidate.getProfileType()).isEqualTo("value");
        assertThat(candidate.getSubject()).isEqualTo("职业节奏");
        assertThat(candidate.getContent()).isEqualTo("更偏好可预期的成长路径");
        assertThat(candidate.getStatus()).isEqualTo("pending");
        assertThat(candidate.getEvidence()).contains("我想慢慢成长", "不太想被推着跑");
        assertThat(candidate.getExpiresAt()).isBetween(before.plusDays(7), after.plusDays(7));
    }

    @Test
    void createCandidateReusesEquivalentPendingCandidate() {
        ProfileMemoryCandidate existing = pendingValueCandidate();
        existing.setId(12L);
        existing.setSubject(" 职业 节奏 ");
        existing.setContent(" 更偏好可预期的成长路径 ");
        existing.setDetail(" 长期规划 ");
        existing.setConfidence(new BigDecimal("0.72"));
        existing.setEvidence("[\"旧证据\",\" 重复证据 \"]");
        when(candidateRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

        ProfileMemoryCandidate candidate = service.createCandidate(
                new ProfileMemoryGovernanceService.MemoryCandidateCommand(
                        USER_ID,
                        "values",
                        "职业节奏",
                        "更偏好可预期的成长路径",
                        "长期规划",
                        new BigDecimal("0.85"),
                        List.of("重复证据", " 新证据 "),
                        "agent_tool_update",
                        100L));

        assertThat(candidate).isSameAs(existing);
        assertThat(candidate.getConfidence()).isEqualByComparingTo("0.85");
        assertThat(candidate.getEvidence()).isEqualTo("[\"旧证据\",\"重复证据\",\"新证据\"]");
        assertThat(candidate.getSource()).isEqualTo("agent_tool_update");
        assertThat(candidate.getSourceConversationId()).isEqualTo(100L);
        assertThat(candidate.getStatus()).isEqualTo("pending");
        assertThat(candidate.getExpiresAt()).isNotNull();
        assertThat(candidate.getUpdatedAt()).isNotNull();
        verify(candidateRepository).updateById(existing);
        verify(candidateRepository, never()).insert(any(ProfileMemoryCandidate.class));
    }

    @Test
    void confirmCandidateWritesFormalProfileSceneLinkAndAudit() {
        ProfileMemoryCandidate candidate = pendingValueCandidate();
        candidate.setId(31L);
        when(candidateRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(candidate);
        doAnswer(invocation -> {
            ProfileValues profile = invocation.getArgument(0);
            profile.setId(101L);
            return 1;
        }).when(valuesRepository).insert(any(ProfileValues.class));
        when(sceneMemoryService.saveSceneMemory(any(ProfileSceneMemoryService.SceneMemoryWrite.class)))
                .thenReturn(new ProfileSceneMemoryService.SceneMemoryWriteResult(List.of("doc-101")));

        ProfileMemoryGovernanceService.GovernanceResult result = service.confirmCandidate(USER_ID, 31L);

        assertThat(result.success()).isTrue();
        assertThat(result.action()).isEqualTo("confirm");
        assertThat(result.profileType()).isEqualTo("value");
        assertThat(result.profileRecordId()).isEqualTo(101L);
        assertThat(result.candidateId()).isEqualTo(31L);

        ArgumentCaptor<ProfileValues> valueCaptor = ArgumentCaptor.forClass(ProfileValues.class);
        verify(valuesRepository).insert(valueCaptor.capture());
        assertThat(valueCaptor.getValue().getItem()).isEqualTo("职业节奏");
        assertThat(valueCaptor.getValue().getPreference()).isEqualTo("更偏好可预期的成长路径");
        assertThat(valueCaptor.getValue().getActive()).isTrue();

        ArgumentCaptor<ProfileSceneMemoryService.SceneMemoryWrite> sceneCaptor =
                ArgumentCaptor.forClass(ProfileSceneMemoryService.SceneMemoryWrite.class);
        verify(sceneMemoryService).saveSceneMemory(sceneCaptor.capture());
        assertThat(sceneCaptor.getValue().sourceProfileRecordId()).isEqualTo(101L);
        assertThat(sceneCaptor.getValue().sourceConversationId()).isEqualTo(99L);

        ArgumentCaptor<ProfileSceneMemoryLink> linkCaptor = ArgumentCaptor.forClass(ProfileSceneMemoryLink.class);
        verify(linkRepository).insert(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getDocumentId()).isEqualTo("doc-101");
        assertThat(linkCaptor.getValue().getProfileRecordId()).isEqualTo(101L);
        assertThat(linkCaptor.getValue().getActive()).isTrue();
        assertThat(linkCaptor.getValue().getDeleteStatus()).isEqualTo("active");

        ArgumentCaptor<ProfileMemoryAuditLog> auditCaptor = ArgumentCaptor.forClass(ProfileMemoryAuditLog.class);
        verify(auditLogRepository).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("confirm");
        assertThat(auditCaptor.getValue().getCandidateId()).isEqualTo(31L);
        assertThat(auditCaptor.getValue().getProfileRecordId()).isEqualTo(101L);

        ArgumentCaptor<LambdaQueryWrapper<ProfileMemoryCandidate>> candidateQueryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(candidateRepository).selectOne(candidateQueryCaptor.capture());
        assertThat(lastSql(candidateQueryCaptor.getValue()).trim()).isEqualTo("FOR UPDATE");
    }

    @Test
    void rejectCandidateDoesNotWriteFormalProfileOrSceneMemory() {
        ProfileMemoryCandidate candidate = pendingValueCandidate();
        candidate.setId(41L);
        when(candidateRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(candidate);

        ProfileMemoryGovernanceService.GovernanceResult result =
                service.rejectCandidate(USER_ID, 41L, "用户否认这个偏好");

        assertThat(result.success()).isTrue();
        assertThat(result.action()).isEqualTo("reject");
        assertThat(result.candidateId()).isEqualTo(41L);

        ArgumentCaptor<ProfileMemoryCandidate> candidateCaptor = ArgumentCaptor.forClass(ProfileMemoryCandidate.class);
        verify(candidateRepository).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().getStatus()).isEqualTo("rejected");
        assertThat(candidateCaptor.getValue().getHandledAt()).isNotNull();

        ArgumentCaptor<ProfileMemoryAuditLog> auditCaptor = ArgumentCaptor.forClass(ProfileMemoryAuditLog.class);
        verify(auditLogRepository).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("reject");
        assertThat(auditCaptor.getValue().getReason()).isEqualTo("用户否认这个偏好");

        verify(valuesRepository, never()).insert(any(ProfileValues.class));
        verifyNoInteractions(sceneMemoryService);
    }

    @Test
    void expiredCandidateCannotBeConfirmed() {
        ProfileMemoryCandidate candidate = pendingValueCandidate();
        candidate.setId(51L);
        candidate.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(candidateRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(candidate);

        assertThatThrownBy(() -> service.confirmCandidate(USER_ID, 51L))
                .isInstanceOf(BusinessException.class)
                .extracting("resultCode")
                .isEqualTo(ResultCode.PROFILE_MEMORY_EXPIRED);

        ArgumentCaptor<ProfileMemoryCandidate> candidateCaptor = ArgumentCaptor.forClass(ProfileMemoryCandidate.class);
        verify(candidateRepository).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().getStatus()).isEqualTo("expired");
        assertThat(candidateCaptor.getValue().getHandledAt()).isNotNull();
        verify(valuesRepository, never()).insert(any(ProfileValues.class));
        verifyNoInteractions(sceneMemoryService);
    }

    @Test
    void writeConfirmedMemoryReturnsProfileRecordIdAndLinksSceneMemoryWithSourceProfileRecordId() {
        doAnswer(invocation -> {
            ProfileValues profile = invocation.getArgument(0);
            profile.setId(222L);
            return 1;
        }).when(valuesRepository).insert(any(ProfileValues.class));
        when(sceneMemoryService.saveSceneMemory(any(ProfileSceneMemoryService.SceneMemoryWrite.class)))
                .thenReturn(new ProfileSceneMemoryService.SceneMemoryWriteResult(List.of("doc-222")));

        ProfileMemoryGovernanceService.GovernanceResult result = service.writeConfirmedMemory(
                new ProfileMemoryGovernanceService.ConfirmedMemoryCommand(
                        USER_ID,
                        "value",
                        "生活节奏",
                        "希望保留自主安排时间",
                        "软边界",
                        new BigDecimal("0.91"),
                        List.of("我不想每天被排满"),
                        "agent_tool_update",
                        88L,
                        "我不想每天被排满"));

        assertThat(result.success()).isTrue();
        assertThat(result.profileRecordId()).isEqualTo(222L);
        assertThat(result.candidateId()).isNull();

        ArgumentCaptor<ProfileSceneMemoryService.SceneMemoryWrite> sceneCaptor =
                ArgumentCaptor.forClass(ProfileSceneMemoryService.SceneMemoryWrite.class);
        verify(sceneMemoryService).saveSceneMemory(sceneCaptor.capture());
        assertThat(sceneCaptor.getValue().sourceProfileRecordId()).isEqualTo(222L);

        ArgumentCaptor<ProfileSceneMemoryLink> linkCaptor = ArgumentCaptor.forClass(ProfileSceneMemoryLink.class);
        verify(linkRepository).insert(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getProfileRecordId()).isEqualTo(222L);
        assertThat(linkCaptor.getValue().getDocumentId()).isEqualTo("doc-222");
        assertThat(linkCaptor.getValue().getSource()).isEqualTo("agent_tool_update");
    }

    @Test
    void writeConfirmedMemoryReactivatesExistingSceneLinkForDuplicateDocumentId() {
        doAnswer(invocation -> {
            ProfileValues profile = invocation.getArgument(0);
            profile.setId(223L);
            return 1;
        }).when(valuesRepository).insert(any(ProfileValues.class));
        when(sceneMemoryService.saveSceneMemory(any(ProfileSceneMemoryService.SceneMemoryWrite.class)))
                .thenReturn(new ProfileSceneMemoryService.SceneMemoryWriteResult(List.of("doc-reused")));
        ProfileSceneMemoryLink existingLink = new ProfileSceneMemoryLink();
        existingLink.setId(77L);
        existingLink.setUserId(USER_ID);
        existingLink.setProfileType("value");
        existingLink.setProfileRecordId(111L);
        existingLink.setDocumentId("doc-reused");
        existingLink.setSource("profile_extract");
        existingLink.setActive(false);
        existingLink.setDeleteStatus("deleted");
        when(linkRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingLink);

        ProfileMemoryGovernanceService.GovernanceResult result = service.writeConfirmedMemory(
                new ProfileMemoryGovernanceService.ConfirmedMemoryCommand(
                        USER_ID,
                        "value",
                        "生活节奏",
                        "希望保留自主安排时间",
                        "软边界",
                        new BigDecimal("0.91"),
                        List.of("我不想每天被排满"),
                        "agent_tool_update",
                        88L,
                        "我不想每天被排满"));

        assertThat(result.profileRecordId()).isEqualTo(223L);
        verify(linkRepository, never()).insert(any(ProfileSceneMemoryLink.class));
        verify(linkRepository).updateById(existingLink);
        assertThat(existingLink.getUserId()).isEqualTo(USER_ID);
        assertThat(existingLink.getProfileType()).isEqualTo("value");
        assertThat(existingLink.getProfileRecordId()).isEqualTo(223L);
        assertThat(existingLink.getDocumentId()).isEqualTo("doc-reused");
        assertThat(existingLink.getSource()).isEqualTo("agent_tool_update");
        assertThat(existingLink.getActive()).isTrue();
        assertThat(existingLink.getDeleteStatus()).isEqualTo("active");
        assertThat(existingLink.getUpdatedAt()).isNotNull();
    }

    @Test
    void writeConfirmedMemoryUpdatesExistingActiveValueInsteadOfInsertingDuplicate() {
        ProfileValues existingValue = new ProfileValues();
        existingValue.setId(224L);
        existingValue.setUserId(USER_ID);
        existingValue.setActive(true);
        existingValue.setItem(" 生活 节奏 ");
        existingValue.setPreference("旧描述");
        existingValue.setConfidence(new BigDecimal("0.60"));
        existingValue.setEvidence("[\"旧证据\"]");
        when(valuesRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existingValue));
        when(sceneMemoryService.saveSceneMemory(any(ProfileSceneMemoryService.SceneMemoryWrite.class)))
                .thenReturn(new ProfileSceneMemoryService.SceneMemoryWriteResult(List.of()));

        ProfileMemoryGovernanceService.GovernanceResult result = service.writeConfirmedMemory(
                new ProfileMemoryGovernanceService.ConfirmedMemoryCommand(
                        USER_ID,
                        "value",
                        "生活节奏",
                        "希望保留自主安排时间",
                        "软边界",
                        new BigDecimal("0.91"),
                        List.of("我不想每天被排满"),
                        "agent_tool_update",
                        88L,
                        "我不想每天被排满"));

        assertThat(result.profileRecordId()).isEqualTo(224L);
        verify(valuesRepository).updateById(existingValue);
        verify(valuesRepository, never()).insert(any(ProfileValues.class));
        assertThat(existingValue.getItem()).isEqualTo("生活节奏");
        assertThat(existingValue.getPreference()).isEqualTo("希望保留自主安排时间");
        assertThat(existingValue.getConfidence()).isEqualByComparingTo("0.91");
        assertThat(existingValue.getEvidence()).contains("我不想每天被排满");
        assertThat(existingValue.getUpdatedAt()).isNotNull();
    }

    @Test
    void writeConfirmedMemoryRetriesLinkUpdateWhenDuplicateDocumentIdInsertedConcurrently() {
        doAnswer(invocation -> {
            ProfileValues profile = invocation.getArgument(0);
            profile.setId(225L);
            return 1;
        }).when(valuesRepository).insert(any(ProfileValues.class));
        when(sceneMemoryService.saveSceneMemory(any(ProfileSceneMemoryService.SceneMemoryWrite.class)))
                .thenReturn(new ProfileSceneMemoryService.SceneMemoryWriteResult(List.of("doc-concurrent")));
        ProfileSceneMemoryLink concurrentLink = new ProfileSceneMemoryLink();
        concurrentLink.setId(78L);
        concurrentLink.setDocumentId("doc-concurrent");
        when(linkRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null, concurrentLink);
        when(linkRepository.insert(any(ProfileSceneMemoryLink.class)))
                .thenThrow(new DuplicateKeyException("duplicate document id"));

        ProfileMemoryGovernanceService.GovernanceResult result = service.writeConfirmedMemory(
                new ProfileMemoryGovernanceService.ConfirmedMemoryCommand(
                        USER_ID,
                        "value",
                        "生活节奏",
                        "希望保留自主安排时间",
                        "软边界",
                        new BigDecimal("0.91"),
                        List.of("我不想每天被排满"),
                        "agent_tool_update",
                        88L,
                        "我不想每天被排满"));

        assertThat(result.profileRecordId()).isEqualTo(225L);
        verify(linkRepository).insert(any(ProfileSceneMemoryLink.class));
        verify(linkRepository).updateById(concurrentLink);
        assertThat(concurrentLink.getUserId()).isEqualTo(USER_ID);
        assertThat(concurrentLink.getProfileType()).isEqualTo("value");
        assertThat(concurrentLink.getProfileRecordId()).isEqualTo(225L);
        assertThat(concurrentLink.getSource()).isEqualTo("agent_tool_update");
        assertThat(concurrentLink.getActive()).isTrue();
        assertThat(concurrentLink.getDeleteStatus()).isEqualTo("active");
        assertThat(concurrentLink.getUpdatedAt()).isNotNull();
    }

    @Test
    void deleteProfileSoftInvalidatesValueAndMarksLinkedDocsDeletedOrDeleteFailed() {
        ProfileValues value = new ProfileValues();
        value.setId(301L);
        value.setUserId(USER_ID);
        value.setActive(true);
        value.setItem("生活节奏");
        value.setPreference("希望保留自主安排时间");
        when(valuesRepository.selectById(301L)).thenReturn(value);

        ProfileSceneMemoryLink deletableLink = activeLink("doc-ok");
        ProfileSceneMemoryLink failedLink = activeLink("doc-fail");
        when(linkRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(deletableLink, failedLink));
        when(sceneMemoryService.deleteSceneMemory("doc-ok")).thenReturn(true);
        when(sceneMemoryService.deleteSceneMemory("doc-fail")).thenReturn(false);

        ProfileMemoryGovernanceService.GovernanceResult result =
                service.deleteProfile(USER_ID, "value", 301L, "用户要求删除");

        assertThat(result.success()).isTrue();
        assertThat(result.action()).isEqualTo("delete");
        assertThat(result.profileRecordId()).isEqualTo(301L);
        assertThat(value.getActive()).isFalse();
        verify(valuesRepository).updateById(value);

        ArgumentCaptor<ProfileSceneMemoryLink> linkCaptor = ArgumentCaptor.forClass(ProfileSceneMemoryLink.class);
        verify(linkRepository, org.mockito.Mockito.times(2)).updateById(linkCaptor.capture());
        assertThat(linkCaptor.getAllValues())
                .extracting(ProfileSceneMemoryLink::getDeleteStatus)
                .containsExactly("deleted", "delete_failed");
        assertThat(linkCaptor.getAllValues())
                .extracting(ProfileSceneMemoryLink::getActive)
                .containsExactly(false, false);

        ArgumentCaptor<ProfileMemoryAuditLog> auditCaptor = ArgumentCaptor.forClass(ProfileMemoryAuditLog.class);
        verify(auditLogRepository).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("delete");
        assertThat(auditCaptor.getValue().getBeforeSnapshot()).contains("生活节奏");
    }

    @Test
    void correctProfileAllowsNullReasonWithoutLosingReplacement() {
        ProfileValues oldValue = new ProfileValues();
        oldValue.setId(401L);
        oldValue.setUserId(USER_ID);
        oldValue.setActive(true);
        oldValue.setItem("旧节奏");
        oldValue.setPreference("旧描述");
        when(valuesRepository.selectById(401L)).thenReturn(oldValue);
        when(linkRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        doAnswer(invocation -> {
            ProfileValues profile = invocation.getArgument(0);
            profile.setId(402L);
            return 1;
        }).when(valuesRepository).insert(any(ProfileValues.class));
        when(sceneMemoryService.saveSceneMemory(any(ProfileSceneMemoryService.SceneMemoryWrite.class)))
                .thenReturn(new ProfileSceneMemoryService.SceneMemoryWriteResult(List.of()));

        ProfileMemoryGovernanceService.GovernanceResult result = service.correctProfile(
                USER_ID,
                "value",
                401L,
                new ProfileMemoryGovernanceService.MemoryCorrectionCommand(
                        "新节奏",
                        "希望保留自主安排时间",
                        "软边界",
                        null));

        assertThat(result.success()).isTrue();
        assertThat(result.action()).isEqualTo("correct");
        assertThat(result.profileRecordId()).isEqualTo(402L);
        assertThat(oldValue.getActive()).isFalse();

        ArgumentCaptor<ProfileValues> valueCaptor = ArgumentCaptor.forClass(ProfileValues.class);
        verify(valuesRepository).insert(valueCaptor.capture());
        assertThat(valueCaptor.getValue().getItem()).isEqualTo("新节奏");

        ArgumentCaptor<ProfileSceneMemoryService.SceneMemoryWrite> sceneCaptor =
                ArgumentCaptor.forClass(ProfileSceneMemoryService.SceneMemoryWrite.class);
        verify(sceneMemoryService).saveSceneMemory(sceneCaptor.capture());
        assertThat(sceneCaptor.getValue().evidence()).isEmpty();
    }

    @Test
    void correctCandidateMarksCandidateConfirmedAndAuditsCorrect() {
        ProfileMemoryCandidate candidate = pendingValueCandidate();
        candidate.setId(501L);
        when(candidateRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(candidate);
        doAnswer(invocation -> {
            ProfileValues profile = invocation.getArgument(0);
            profile.setId(502L);
            return 1;
        }).when(valuesRepository).insert(any(ProfileValues.class));
        when(sceneMemoryService.saveSceneMemory(any(ProfileSceneMemoryService.SceneMemoryWrite.class)))
                .thenReturn(new ProfileSceneMemoryService.SceneMemoryWriteResult(List.of()));

        ProfileMemoryGovernanceService.GovernanceResult result = service.correctCandidate(
                USER_ID,
                501L,
                new ProfileMemoryGovernanceService.MemoryCorrectionCommand(
                        "修正节奏",
                        "更偏好可预期但不排斥挑战",
                        "长期规划",
                        "用户补充语义"));

        assertThat(result.success()).isTrue();
        assertThat(result.action()).isEqualTo("correct");
        assertThat(result.profileRecordId()).isEqualTo(502L);

        ArgumentCaptor<ProfileMemoryCandidate> candidateCaptor = ArgumentCaptor.forClass(ProfileMemoryCandidate.class);
        verify(candidateRepository).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().getStatus()).isEqualTo("confirmed");

        ArgumentCaptor<ProfileMemoryAuditLog> auditCaptor = ArgumentCaptor.forClass(ProfileMemoryAuditLog.class);
        verify(auditLogRepository).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("correct");
        assertThat(auditCaptor.getValue().getReason()).isEqualTo("用户补充语义");
    }

    @Test
    void listAndCountPendingCandidatesIgnoreExpiredRows() {
        when(candidateRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(candidateRepository.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        service.listPendingCandidates(USER_ID);
        service.countPendingCandidates(USER_ID);

        ArgumentCaptor<LambdaQueryWrapper<ProfileMemoryCandidate>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(candidateRepository).selectList(wrapperCaptor.capture());
        verify(candidateRepository).selectCount(wrapperCaptor.capture());

        assertThat(wrapperCaptor.getAllValues())
                .allSatisfy(wrapper -> assertThat(wrapper.getExpression().getNormal().size()).isGreaterThan(7));
    }

    @Test
    void stateChangingMethodsAreTransactional() throws NoSuchMethodException {
        assertTransactional("createCandidate", ProfileMemoryGovernanceService.MemoryCandidateCommand.class);
        assertTransactional("writeConfirmedMemory", ProfileMemoryGovernanceService.ConfirmedMemoryCommand.class);
        assertTransactional("confirmCandidate", Long.class, Long.class);
        assertTransactional("rejectCandidate", Long.class, Long.class, String.class);
        assertTransactional("correctCandidate", Long.class, Long.class,
                ProfileMemoryGovernanceService.MemoryCorrectionCommand.class);
        assertTransactional("correctProfile", Long.class, String.class, Long.class,
                ProfileMemoryGovernanceService.MemoryCorrectionCommand.class);
        assertTransactional("deleteProfile", Long.class, String.class, Long.class, String.class);
    }

    @Test
    void candidateHandlingMethodsDoNotRollBackExpiredStatusOnBusinessException() throws NoSuchMethodException {
        assertNoRollbackForBusinessException("confirmCandidate", Long.class, Long.class);
        assertNoRollbackForBusinessException("rejectCandidate", Long.class, Long.class, String.class);
        assertNoRollbackForBusinessException("correctCandidate", Long.class, Long.class,
                ProfileMemoryGovernanceService.MemoryCorrectionCommand.class);
    }

    private ProfileMemoryCandidate pendingValueCandidate() {
        ProfileMemoryCandidate candidate = new ProfileMemoryCandidate();
        candidate.setUserId(USER_ID);
        candidate.setProfileType("value");
        candidate.setSubject("职业节奏");
        candidate.setContent("更偏好可预期的成长路径");
        candidate.setDetail("长期规划");
        candidate.setConfidence(new BigDecimal("0.92"));
        candidate.setEvidence("[\"我想慢慢成长\"]");
        candidate.setSource("profile_extract");
        candidate.setSourceConversationId(99L);
        candidate.setStatus("pending");
        candidate.setExpiresAt(LocalDateTime.now().plusDays(1));
        return candidate;
    }

    private ProfileSceneMemoryLink activeLink(String documentId) {
        ProfileSceneMemoryLink link = new ProfileSceneMemoryLink();
        link.setUserId(USER_ID);
        link.setProfileType("value");
        link.setProfileRecordId(301L);
        link.setDocumentId(documentId);
        link.setActive(true);
        link.setDeleteStatus("active");
        return link;
    }

    private void assertTransactional(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ProfileMemoryGovernanceService.class.getMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    private void assertNoRollbackForBusinessException(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = ProfileMemoryGovernanceService.class.getMethod(methodName, parameterTypes);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(BusinessException.class);
    }

    private String lastSql(LambdaQueryWrapper<?> wrapper) {
        SharedString lastSql = (SharedString) ReflectionTestUtils.getField(wrapper, "lastSql");
        return lastSql == null ? "" : lastSql.getStringValue();
    }
}
