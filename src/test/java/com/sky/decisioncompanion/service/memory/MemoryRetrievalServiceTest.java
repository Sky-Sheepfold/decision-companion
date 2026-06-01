package com.sky.decisioncompanion.service.memory;

import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.model.ProfileEmotion;
import com.sky.decisioncompanion.model.ProfileFear;
import com.sky.decisioncompanion.model.ProfileRelationship;
import com.sky.decisioncompanion.model.ProfileValues;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryRetrievalServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ProfileValuesRepository valuesRepository;

    @Mock
    private ProfileDecisionRepository decisionRepository;

    @Mock
    private ProfileEmotionRepository emotionRepository;

    @Mock
    private ProfileFearRepository fearRepository;

    @Mock
    private ProfileRelationshipRepository relationshipRepository;

    @Mock
    private VectorStore vectorStore;

    private MemoryRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new MemoryRetrievalService(
                valuesRepository,
                decisionRepository,
                emotionRepository,
                relationshipRepository,
                fearRepository,
                vectorStore);
    }

    @Test
    void retrieveBuildsStructuredPromptContextFromProfilesAndSemanticMemory() {
        when(valuesRepository.selectList(any())).thenReturn(List.of(value("城市偏好", "更看重离家近", "0.90")));
        when(emotionRepository.selectList(any())).thenReturn(List.of(emotion("焦虑", "被催促时容易压力变大")));
        when(relationshipRepository.selectList(any())).thenReturn(List.of(
                relationship("妈妈", "母亲", "高", "从安全和稳定角度影响选择", "希望用户不要离家太远")));
        when(fearRepository.selectList(any())).thenReturn(List.of(fear("fear", "害怕离家太远", "0.80")));
        when(decisionRepository.selectList(any())).thenReturn(
                List.of(decision("外地 offer", "暂缓接受", "担心家庭距离")));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder()
                        .text("场景记忆：用户多次提到不希望离父母太远")
                        .metadata("type", "conversation_scene")
                        .metadata("memoryRole", "scene_evidence")
                        .metadata("profileRecordCount", 2)
                        .score(0.82)
                        .build()));

        MemoryContext context = service.retrieve(USER_ID, "我在纠结外地 offer");

        assertThat(context.promptContext()).contains("仅作为参考");
        assertThat(context.promptContext()).contains("【稳定价值观】");
        assertThat(context.promptContext()).contains("更看重离家近");
        assertThat(context.promptContext()).contains("【相似历史决策】");
        assertThat(context.promptContext()).contains("外地 offer");
        assertThat(context.promptContext()).contains("【关系影响】");
        assertThat(context.promptContext()).contains("妈妈");
        assertThat(context.promptContext()).contains("从安全和稳定角度影响选择");
        assertThat(context.promptContext()).contains("结构化画像表示较稳定的长期结论");
        assertThat(context.promptContext()).contains("【相关场景记忆】");
        assertThat(context.promptContext()).doesNotContain("【相关语义记忆】");
        assertThat(context.promptContext()).contains("离父母太远");
        assertThat(context.relationships()).hasSize(1);
        assertThat(context.metrics().relationshipCount()).isEqualTo(1);
        assertThat(context.semanticMemories()).hasSize(1);
        assertThat(context.metrics().semanticHitCount()).isEqualTo(1);
        assertThat(context.metrics().maxSemanticScore()).isEqualTo(0.82);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        SearchRequest request = captor.getValue();
        assertThat(request.getQuery()).isEqualTo("我在纠结外地 offer");
        assertThat(request.getTopK()).isEqualTo(5);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.6);
        assertThat(request.getFilterExpression().toString()).contains("userId");
        assertThat(request.getFilterExpression().toString()).contains("1");
    }

    @Test
    void retrieveFallsBackToStructuredProfilesWhenVectorStoreIsMissing() {
        MemoryRetrievalService serviceWithoutVector = new MemoryRetrievalService(
                valuesRepository,
                decisionRepository,
                emotionRepository,
                relationshipRepository,
                fearRepository,
                null);
        when(valuesRepository.selectList(any())).thenReturn(List.of(value("稳定性", "偏好长期确定性", "0.85")));
        when(emotionRepository.selectList(any())).thenReturn(List.of());
        when(relationshipRepository.selectList(any())).thenReturn(List.of());
        when(fearRepository.selectList(any())).thenReturn(List.of());
        when(decisionRepository.selectList(any())).thenReturn(List.of());

        MemoryContext context = serviceWithoutVector.retrieve(USER_ID, "随便聊聊");

        assertThat(context.promptContext()).contains("偏好长期确定性");
        assertThat(context.semanticMemories()).isEmpty();
        assertThat(context.metrics().vectorAvailable()).isFalse();
        assertThat(context.metrics().degraded()).isTrue();
    }

    @Test
    void retrieveBoundsPromptItemsAndTruncatesLongContent() {
        when(valuesRepository.selectList(any())).thenReturn(List.of(
                value("v1", "p1", "0.9"),
                value("v2", "p2", "0.9"),
                value("v3", "p3", "0.9"),
                value("v4", "p4", "0.9"),
                value("v5", "p5", "0.9"),
                value("v6", "p6", "0.9")));
        when(emotionRepository.selectList(any())).thenReturn(List.of());
        when(relationshipRepository.selectList(any())).thenReturn(List.of());
        when(fearRepository.selectList(any())).thenReturn(List.of());
        when(decisionRepository.selectList(any())).thenReturn(List.of());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("x".repeat(260)).score(0.7).build()));

        MemoryContext context = service.retrieve(USER_ID, "query");

        assertThat(context.values()).hasSize(5);
        assertThat(context.promptContext()).contains("v5");
        assertThat(context.promptContext()).doesNotContain("v6");
        assertThat(context.semanticMemories().get(0).content()).hasSize(200);
    }

    private ProfileValues value(String item, String preference, String confidence) {
        ProfileValues value = new ProfileValues();
        value.setUserId(USER_ID);
        value.setItem(item);
        value.setPreference(preference);
        value.setConfidence(new BigDecimal(confidence));
        return value;
    }

    private ProfileEmotion emotion(String name, String behavior) {
        ProfileEmotion emotion = new ProfileEmotion();
        emotion.setUserId(USER_ID);
        emotion.setEmotion(name);
        emotion.setBehavior(behavior);
        return emotion;
    }

    private ProfileFear fear(String type, String description, String confidence) {
        ProfileFear fear = new ProfileFear();
        fear.setUserId(USER_ID);
        fear.setType(type);
        fear.setDescription(description);
        fear.setConfidence(new BigDecimal(confidence));
        return fear;
    }

    private ProfileRelationship relationship(
            String name,
            String role,
            String influenceLevel,
            String influenceStyle,
            String note) {
        ProfileRelationship relationship = new ProfileRelationship();
        relationship.setUserId(USER_ID);
        relationship.setName(name);
        relationship.setRole(role);
        relationship.setInfluenceLevel(influenceLevel);
        relationship.setInfluenceStyle(influenceStyle);
        relationship.setNote(note);
        return relationship;
    }

    private ProfileDecision decision(String topic, String choice, String reason) {
        ProfileDecision decision = new ProfileDecision();
        decision.setUserId(USER_ID);
        decision.setTopic(topic);
        decision.setChoice(choice);
        decision.setReason(reason);
        return decision;
    }
}
