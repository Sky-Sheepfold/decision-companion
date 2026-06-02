package com.sky.decisioncompanion.service;

import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import com.sky.decisioncompanion.service.memory.ProfileSceneMemoryService;
import com.sky.decisioncompanion.service.profile.ProfileMemoryGovernanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileExtractServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ProfileValuesRepository valuesRepository;

    @Mock
    private ProfileDecisionRepository decisionRepository;

    @Mock
    private ProfileEmotionRepository emotionRepository;

    @Mock
    private ProfileRelationshipRepository relationshipRepository;

    @Mock
    private ProfileFearRepository fearRepository;

    @Mock
    private ProfileSceneMemoryService profileSceneMemoryService;

    @Mock
    private ProfileMemoryGovernanceService profileMemoryGovernanceService;

    private ProfileExtractService service;

    @BeforeEach
    void setUp() {
        lenient().when(chatClientBuilder.build()).thenReturn(chatClient);
        service = serviceWithSceneMemory(profileSceneMemoryService);
    }

    @Test
    void skipsProfilesBelowAutoWriteThresholdWithoutWritingVectorMemory() {
        String analysis = """
                {
                  "values": [
                    {
                      "item": "稳定",
                      "preference": "更想要稳定",
                      "confidence": 0.59,
                      "evidence": ["我更想要稳定"]
                    }
                  ],
                  "fears": [
                    {
                      "type": "fear",
                      "description": "害怕失败",
                      "confidence": 0.69,
                      "evidence": ["我怕失败"]
                    }
                  ]
                }
                """;

        int saved = service.saveAnalysis(USER_ID, "我更想要稳定，但也怕失败", analysis);

        assertThat(saved).isZero();
        verify(profileMemoryGovernanceService, never()).createCandidate(any());
        verify(profileMemoryGovernanceService, never()).writeConfirmedMemory(any());
        verifyNoInteractions(profileSceneMemoryService);
    }

    @Test
    void mediumConfidenceValueCreatesPendingCandidate() {
        String analysis = """
                {
                  "values": [
                    {
                      "item": "稳定",
                      "preference": "更看重长期确定性",
                      "confidence": 0.84,
                      "evidence": ["我还是想稳定一点"]
                    }
                  ]
                }
                """;

        int saved = service.saveAnalysis(USER_ID, "我还是想稳定一点", analysis);

        assertThat(saved).isZero();
        ArgumentCaptor<ProfileMemoryGovernanceService.MemoryCandidateCommand> captor =
                ArgumentCaptor.forClass(ProfileMemoryGovernanceService.MemoryCandidateCommand.class);
        verify(profileMemoryGovernanceService).createCandidate(captor.capture());
        ProfileMemoryGovernanceService.MemoryCandidateCommand command = captor.getValue();
        assertThat(command.userId()).isEqualTo(USER_ID);
        assertThat(command.profileType()).isEqualTo("value");
        assertThat(command.subject()).isEqualTo("稳定");
        assertThat(command.content()).isEqualTo("更看重长期确定性");
        assertThat(command.confidence()).isEqualByComparingTo("0.84");
        assertThat(command.evidence()).containsExactly("我还是想稳定一点");
        assertThat(command.source()).isEqualTo("profile_extract");
        verify(profileMemoryGovernanceService, never()).writeConfirmedMemory(any());
        verifyNoInteractions(profileSceneMemoryService);
    }

    @Test
    void highConfidenceValueWritesConfirmedMemoryThroughGovernance() {
        when(profileMemoryGovernanceService.writeConfirmedMemory(any()))
                .thenReturn(new ProfileMemoryGovernanceService.GovernanceResult(
                        true, "confirm", "value", 101L, null, "画像记忆已写入"));

        String analysis = """
                {
                  "values": [
                    {
                      "item": " 稳定 ",
                      "preference": "更看重长期确定性",
                      "confidence": 0.90,
                      "evidence": ["我还是想稳定一点"]
                    }
                  ]
                }
                """;

        int saved = service.saveAnalysis(USER_ID, "我还是想稳定一点", analysis);

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<ProfileMemoryGovernanceService.ConfirmedMemoryCommand> captor =
                ArgumentCaptor.forClass(ProfileMemoryGovernanceService.ConfirmedMemoryCommand.class);
        verify(profileMemoryGovernanceService).writeConfirmedMemory(captor.capture());
        ProfileMemoryGovernanceService.ConfirmedMemoryCommand command = captor.getValue();
        assertThat(command.userId()).isEqualTo(USER_ID);
        assertThat(command.profileType()).isEqualTo("value");
        assertThat(command.subject()).isEqualTo("稳定");
        assertThat(command.content()).isEqualTo("更看重长期确定性");
        assertThat(command.confidence()).isEqualByComparingTo("0.90");
        assertThat(command.evidence()).containsExactly("我还是想稳定一点");
        assertThat(command.source()).isEqualTo("profile_extract");
        assertThat(command.userMessage()).isEqualTo("我还是想稳定一点");
        verify(profileMemoryGovernanceService, never()).createCandidate(any());
        verifyNoInteractions(profileSceneMemoryService);
    }

    @Test
    void highConfidenceValueAndMediumConfidenceFearUseGovernanceWithoutDirectSceneMemory() {
        when(profileMemoryGovernanceService.writeConfirmedMemory(any()))
                .thenReturn(new ProfileMemoryGovernanceService.GovernanceResult(
                        true, "confirm", "value", 101L, null, "画像记忆已写入"));

        String analysis = """
                {
                  "values": [
                    {
                      "item": "城市偏好",
                      "preference": "希望离父母近一点",
                      "confidence": 0.92,
                      "evidence": ["我怕离家太远以后没时间陪父母"]
                    }
                  ],
                  "fears": [
                    {
                      "type": "fear",
                      "description": "担心无法陪伴父母",
                      "manifestation": "害怕离家太远",
                      "confidence": 0.85,
                      "evidence": ["我怕离家太远以后没时间陪父母"]
                    }
                  ]
                }
                """;

        int saved = service.saveAnalysis(USER_ID, "我怕离家太远以后没时间陪父母", analysis);

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<ProfileMemoryGovernanceService.ConfirmedMemoryCommand> confirmedCaptor =
                ArgumentCaptor.forClass(ProfileMemoryGovernanceService.ConfirmedMemoryCommand.class);
        ArgumentCaptor<ProfileMemoryGovernanceService.MemoryCandidateCommand> candidateCaptor =
                ArgumentCaptor.forClass(ProfileMemoryGovernanceService.MemoryCandidateCommand.class);
        verify(profileMemoryGovernanceService).writeConfirmedMemory(confirmedCaptor.capture());
        verify(profileMemoryGovernanceService).createCandidate(candidateCaptor.capture());
        assertThat(confirmedCaptor.getValue().profileType()).isEqualTo("value");
        assertThat(confirmedCaptor.getValue().subject()).isEqualTo("城市偏好");
        assertThat(candidateCaptor.getValue().profileType()).isEqualTo("fear");
        assertThat(candidateCaptor.getValue().subject()).isEqualTo("担心无法陪伴父母");
        verifyNoInteractions(profileSceneMemoryService);
    }

    @Test
    void skipsDuplicateDecisionWithSameTopicAndChoice() {
        ProfileDecision existing = new ProfileDecision();
        existing.setId(22L);
        existing.setUserId(USER_ID);
        existing.setTopic("是否接受 offer");
        existing.setChoice("接受 A 公司");
        when(decisionRepository.selectList(any())).thenReturn(List.of(existing));

        String analysis = """
                {
                  "decisions": [
                    {
                      "topic": " 是否接受 offer ",
                      "choice": "接受 A 公司",
                      "reason": "成长空间更大",
                      "evidence": ["我决定接受 A 公司"]
                    }
                  ]
                }
                """;

        int saved = service.saveAnalysis(USER_ID, "我在纠结 offer，最后选择接受 A 公司", analysis);

        assertThat(saved).isZero();
        verify(decisionRepository, never()).insert(any(ProfileDecision.class));
        verifyNoInteractions(profileMemoryGovernanceService);
        verifyNoInteractions(profileSceneMemoryService);
    }

    @Test
    void decisionPersistsThroughDecisionRepositoryWithoutGovernance() {
        when(decisionRepository.selectList(any())).thenReturn(List.of());

        String analysis = """
                {
                  "decisions": [
                    {
                      "topic": "是否接受 offer",
                      "choice": "接受 A 公司",
                      "reason": "成长空间更大",
                      "evidence": ["我决定接受 A 公司"]
                    }
                  ]
                }
                """;

        int saved = service.saveAnalysis(USER_ID, "我在纠结 offer，最后决定接受 A 公司", analysis);

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<ProfileDecision> captor = ArgumentCaptor.forClass(ProfileDecision.class);
        verify(decisionRepository).insert(captor.capture());
        assertThat(captor.getValue().getTopic()).isEqualTo("是否接受 offer");
        assertThat(captor.getValue().getChoice()).isEqualTo("接受 A 公司");
        verifyNoInteractions(profileMemoryGovernanceService);
        verifyNoInteractions(profileSceneMemoryService);
    }

    private ProfileExtractService serviceWithSceneMemory(ProfileSceneMemoryService profileSceneMemoryService) {
        return new ProfileExtractService(
                chatClientBuilder,
                valuesRepository,
                decisionRepository,
                emotionRepository,
                relationshipRepository,
                fearRepository,
                profileSceneMemoryService,
                profileMemoryGovernanceService);
    }
}
