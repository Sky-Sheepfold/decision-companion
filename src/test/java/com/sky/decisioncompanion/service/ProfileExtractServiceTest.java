package com.sky.decisioncompanion.service;

import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.model.ProfileFear;
import com.sky.decisioncompanion.model.ProfileValues;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import com.sky.decisioncompanion.service.memory.ProfileSceneMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
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
                      "confidence": 0.84,
                      "evidence": ["我更想要稳定"]
                    }
                  ],
                  "fears": [
                    {
                      "type": "fear",
                      "description": "害怕失败",
                      "confidence": 0.89,
                      "evidence": ["我怕失败"]
                    }
                  ]
                }
                """;

        int saved = service.saveAnalysis(USER_ID, "我更想要稳定，但也怕失败", analysis);

        assertThat(saved).isZero();
        verify(valuesRepository, never()).insert(any(ProfileValues.class));
        verify(valuesRepository, never()).updateById(any(ProfileValues.class));
        verify(fearRepository, never()).insert(any(ProfileFear.class));
        verify(fearRepository, never()).updateById(any(ProfileFear.class));
        verifyNoInteractions(profileSceneMemoryService);
    }

    @Test
    void skipsMediumConfidenceSensitiveProfileThatNeedsConfirmation() {
        String analysis = """
                {
                  "fears": [
                    {
                      "type": "fear",
                      "description": "失去对生活节奏的掌控",
                      "manifestation": "未明确描述具体表现",
                      "confidence": 0.85,
                      "evidence": ["失去对生活节奏的掌控"]
                    }
                  ]
                }
                """;

        int saved = service.saveAnalysis(USER_ID, "我担心失去对生活节奏的掌控", analysis);

        assertThat(saved).isZero();
        verify(fearRepository, never()).insert(any(ProfileFear.class));
        verify(fearRepository, never()).updateById(any(ProfileFear.class));
        verifyNoInteractions(profileSceneMemoryService);
    }

    @Test
    void updatesExistingValueInsteadOfInsertingDuplicate() {
        ProfileValues existing = new ProfileValues();
        existing.setId(15L);
        existing.setUserId(USER_ID);
        existing.setItem("稳定");
        existing.setPreference("旧描述");
        existing.setConfidence(new BigDecimal("0.70"));
        existing.setEvidence("[\"旧证据\"]");
        when(valuesRepository.selectList(any())).thenReturn(List.of(existing));

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
        ArgumentCaptor<ProfileValues> captor = ArgumentCaptor.forClass(ProfileValues.class);
        verify(valuesRepository).updateById(captor.capture());
        verify(valuesRepository, never()).insert(any(ProfileValues.class));
        ProfileValues updated = captor.getValue();
        assertThat(updated.getId()).isEqualTo(15L);
        assertThat(updated.getPreference()).isEqualTo("更看重长期确定性");
        assertThat(updated.getConfidence()).isEqualByComparingTo("0.90");
        assertThat(updated.getEvidence()).contains("我还是想稳定一点");
        verify(profileSceneMemoryService).saveSceneMemory(argThat(memory -> memory.profileRecordCount() == 1));
    }

    @Test
    void writesSceneEvidenceMemoryToVectorStoreInsteadOfDuplicatingProfileSummary() {
        when(valuesRepository.selectList(any())).thenReturn(List.of());

        String analysis = """
                {
                  "values": [
                    {
                      "item": "城市偏好",
                      "preference": "希望离父母近一点",
                      "confidence": 0.92,
                      "evidence": ["我怕离家太远以后没时间陪父母"]
                    }
                  ]
                }
                """;

        int saved = service.saveAnalysis(USER_ID, "我怕离家太远以后没时间陪父母", analysis);

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<ProfileSceneMemoryService.SceneMemoryWrite> captor =
                ArgumentCaptor.forClass(ProfileSceneMemoryService.SceneMemoryWrite.class);
        verify(profileSceneMemoryService).saveSceneMemory(captor.capture());
        ProfileSceneMemoryService.SceneMemoryWrite memory = captor.getValue();

        assertThat(memory.userId()).isEqualTo(USER_ID);
        assertThat(memory.userMessage()).isEqualTo("我怕离家太远以后没时间陪父母");
        assertThat(memory.source()).isEqualTo("profile_extract");
        assertThat(memory.profileRecordCount()).isEqualTo(1);
        assertThat(memory.profileTypes()).containsExactly("values");
        assertThat(memory.memoryType()).isEqualTo("values");
        assertThat(memory.confidence()).isEqualByComparingTo("0.92");
        assertThat(memory.evidence()).containsExactly("我怕离家太远以后没时间陪父母");
        assertThat(memory.sceneSignals()).containsExactly("value:城市偏好:希望离父母近一点");
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
        verifyNoInteractions(profileSceneMemoryService);
    }

    @Test
    void validRecordPersistsWhenSceneMemoryWriteFails() {
        when(valuesRepository.selectList(any())).thenReturn(List.of());
        doThrow(new RuntimeException("chroma down"))
                .when(profileSceneMemoryService)
                .saveSceneMemory(any(ProfileSceneMemoryService.SceneMemoryWrite.class));

        String analysis = """
                {
                  "values": [
                    {
                      "item": "自由度",
                      "preference": "希望保留自主安排时间的空间",
                      "confidence": 0.90,
                      "evidence": ["我不想每天被排满"]
                    }
                  ]
                }
                """;

        int saved = service.saveAnalysis(USER_ID, "我不想每天被排满", analysis);

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<ProfileValues> captor = ArgumentCaptor.forClass(ProfileValues.class);
        verify(valuesRepository).insert(captor.capture());
        assertThat(captor.getValue().getItem()).isEqualTo("自由度");
        assertThat(captor.getValue().getEvidence()).contains("我不想每天被排满");
    }

    private ProfileExtractService serviceWithSceneMemory(ProfileSceneMemoryService profileSceneMemoryService) {
        return new ProfileExtractService(
                chatClientBuilder,
                valuesRepository,
                decisionRepository,
                emotionRepository,
                relationshipRepository,
                fearRepository,
                profileSceneMemoryService);
    }
}
