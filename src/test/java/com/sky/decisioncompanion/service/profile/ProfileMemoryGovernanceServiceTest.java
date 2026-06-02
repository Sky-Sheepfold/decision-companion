package com.sky.decisioncompanion.service.profile;

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
    void confirmCandidateWritesFormalProfileSceneLinkAndAudit() {
        ProfileMemoryCandidate candidate = pendingValueCandidate();
        candidate.setId(31L);
        when(candidateRepository.selectById(31L)).thenReturn(candidate);
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
    }

    @Test
    void rejectCandidateDoesNotWriteFormalProfileOrSceneMemory() {
        ProfileMemoryCandidate candidate = pendingValueCandidate();
        candidate.setId(41L);
        when(candidateRepository.selectById(41L)).thenReturn(candidate);

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
        when(candidateRepository.selectById(51L)).thenReturn(candidate);

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
}
