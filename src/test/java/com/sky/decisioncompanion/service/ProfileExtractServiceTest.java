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
import static org.mockito.Mockito.times;
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
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

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

    @Mock
    private com.sky.decisioncompanion.service.profile.PostureGateService postureGateService;

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

    @Test
    void retriesStrictPromptWhenModelOutputIsUnparsable() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(any(org.springframework.ai.chat.messages.Message[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content())
                .thenReturn("抱歉，这段我不太确定该怎么分析。")
                .thenReturn("""
                        {
                          "values": [
                            {
                              "item": "稳定",
                              "preference": "更想要稳定",
                              "confidence": 0.9,
                              "evidence": ["我更想要稳定"]
                            }
                          ]
                        }
                        """);
        when(profileMemoryGovernanceService.writeConfirmedMemory(any()))
                .thenReturn(new ProfileMemoryGovernanceService.GovernanceResult(
                        true, "confirm", "value", 101L, null, "画像记忆已写入"));

        int saved = service.extractAndSave(USER_ID, "我更想要稳定", "好的，我理解你的偏好");

        assertThat(saved).isEqualTo(1);
        verify(chatClient, times(2)).prompt();
    }

    @Test
    void splitsLongUnparsableInputAndRecursivelyExtracts() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(any(org.springframework.ai.chat.messages.Message[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        String longMessage = "第一段：我最近一直在纠结要不要接受外地的高薪 offer，离父母很远。"
                + "第二段：妈妈很希望我留在本地，她说一家人在一起更重要。"
                + "第三段：我自己其实很看重家庭和稳定，但又不想放弃职业成长的机会。"
                + "第四段：最近压力很大，晚上经常睡不好，一直在反复权衡这两个选择。"
                + "第五段：这份 offer 的平台更大，能接触更多项目，未来跳槽也更有竞争力。"
                + "第六段：但我又很担心错过陪伴父母的时光，毕竟他们年纪越来越大，我希望能常回家看看。"
                + "第七段：朋友说年轻应该拼一拼，可我心里总有个声音说稳定也很重要。"
                + "第八段：我反复在想，到底什么样的选择才是我真正想要的，真的很纠结。"
                + "第九段：如果选择去外地，我会很担心周末回不了家，父母有急事也帮不上忙，心里会很愧疚。"
                + "第十段：但如果留在本地，又怕错过一次重要的成长机会，以后回想起来会不会后悔当初没有出去闯一闯。"
                + "第十一段：我还想听听你的建议，结合我的情况，你觉得应该优先考虑家庭还是优先考虑职业发展呢。";
        when(callResponseSpec.content())
                .thenReturn("不可解析的输出")
                .thenReturn("还是不可解析")
                .thenReturn("""
                        {"values": [{"item": "稳定", "preference": "更想要稳定", "confidence": 0.9, "evidence": ["更看重家庭"]}]}
                        """)
                .thenReturn("""
                        {"relationships": [{"name": "妈妈", "role": "母亲", "influenceLevel": "高", "influenceStyle": "从家庭角度影响选择", "note": "更看重陪伴家人", "confidence": 0.9, "evidence": ["希望我留在本地"]}]}
                        """);
        when(profileMemoryGovernanceService.writeConfirmedMemory(any()))
                .thenReturn(new ProfileMemoryGovernanceService.GovernanceResult(
                        true, "confirm", "value", 101L, null, "画像记忆已写入"));

        int saved = service.extractAndSave(USER_ID, longMessage, "好的，我理解你的纠结");

        assertThat(saved).isEqualTo(2);
        verify(chatClient, times(4)).prompt();
    }

    @Test
    void givesUpShortUnparsableOutputWithoutExtraCalls() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(any(org.springframework.ai.chat.messages.Message[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("不可解析的输出", "还是不可解析");

        int saved = service.extractAndSave(USER_ID, "你好", "你好");

        assertThat(saved).isZero();
        verify(chatClient, times(2)).prompt();
        verifyNoInteractions(profileMemoryGovernanceService);
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
                profileMemoryGovernanceService,
                postureGateService);
    }
}
