package com.sky.decisioncompanion.service.memory;

import com.sky.decisioncompanion.config.MemoryRetrievalProperties;
import com.sky.decisioncompanion.model.ProfileEmotion;
import com.sky.decisioncompanion.model.ProfileFear;
import com.sky.decisioncompanion.model.ProfileRelationship;
import com.sky.decisioncompanion.model.ProfileSceneMemoryLink;
import com.sky.decisioncompanion.model.ProfileValues;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileSceneMemoryLinkRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryRetrievalServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ProfileValuesRepository valuesRepository;

    @Mock
    private ProfileEmotionRepository emotionRepository;

    @Mock
    private ProfileFearRepository fearRepository;

    @Mock
    private ProfileRelationshipRepository relationshipRepository;

    @Mock
    private ProfileSceneMemoryLinkRepository sceneMemoryLinkRepository;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private DecisionRecallService decisionRecallService;

    @Mock
    private MemoryRetrievalLogService retrievalLogService;

    private MemoryRetrievalService service;

    @BeforeEach
    void setUp() {
        service = service(new MemoryRetrievalProperties(), vectorStore);
    }

    @Test
    void retrieveBuildsStructuredPromptContextFromProfilesAndSemanticMemory() {
        when(valuesRepository.selectList(any())).thenReturn(List.of(value("城市偏好", "更看重离家近", "0.90")));
        when(emotionRepository.selectList(any())).thenReturn(List.of(emotion("焦虑", "被催促时容易压力变大")));
        when(relationshipRepository.selectList(any())).thenReturn(List.of(
                relationship("妈妈", "母亲", "高", "从安全和稳定角度影响选择", "希望用户不要离家太远")));
        when(fearRepository.selectList(any())).thenReturn(List.of(fear("fear", "害怕离家太远", "0.80")));
        when(decisionRecallService.recall(USER_ID, "我在纠结外地 offer", 3))
                .thenReturn(decisionResult(decision("外地 offer", "暂缓接受", "担心家庭距离")));
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
        verify(retrievalLogService).recordAutoRecall(eq(USER_ID), eq("我在纠结外地 offer"),
                eq(context), eq(5), eq(0.3));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        SearchRequest request = captor.getValue();
        assertThat(request.getQuery()).isEqualTo("我在纠结外地 offer");
        assertThat(request.getTopK()).isEqualTo(5);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.3);
        assertThat(request.getFilterExpression().toString()).contains("userId");
        assertThat(request.getFilterExpression().toString()).contains("1");
    }

    @Test
    void retrieveOmitsInactiveFormalProfileRecordsFromPromptContext() {
        ProfileValues activeValue = value("城市偏好", "更看重离家近", "0.90");
        ProfileValues inactiveValue = value("过期偏好", "曾经想远离家庭", "0.40");
        inactiveValue.setActive(false);
        ProfileEmotion activeEmotion = emotion("焦虑", "被催促时容易压力变大");
        ProfileEmotion inactiveEmotion = emotion("兴奋", "已失效的冲动模式");
        inactiveEmotion.setActive(false);
        ProfileRelationship activeRelationship = relationship("妈妈", "母亲", "高", "从安全和稳定角度影响选择", "希望不要离家太远");
        ProfileRelationship inactiveRelationship = relationship("前同事", "同事", "低", "已失效的建议影响", "不再联系");
        inactiveRelationship.setActive(false);
        ProfileFear activeFear = fear("fear", "害怕离家太远", "0.80");
        ProfileFear inactiveFear = fear("boundary", "已撤销的边界", "0.20");
        inactiveFear.setActive(false);

        when(valuesRepository.selectList(any())).thenReturn(List.of(activeValue, inactiveValue));
        when(emotionRepository.selectList(any())).thenReturn(List.of(activeEmotion, inactiveEmotion));
        when(relationshipRepository.selectList(any())).thenReturn(List.of(activeRelationship, inactiveRelationship));
        when(fearRepository.selectList(any())).thenReturn(List.of(activeFear, inactiveFear));
        when(decisionRecallService.recall(USER_ID, "query", 3)).thenReturn(decisionResult());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        MemoryContext context = service.retrieve(USER_ID, "query");

        assertThat(context.promptContext()).contains("更看重离家近");
        assertThat(context.promptContext()).contains("被催促时容易压力变大");
        assertThat(context.promptContext()).contains("从安全和稳定角度影响选择");
        assertThat(context.promptContext()).contains("害怕离家太远");
        assertThat(context.promptContext()).doesNotContain("曾经想远离家庭");
        assertThat(context.promptContext()).doesNotContain("已失效的冲动模式");
        assertThat(context.promptContext()).doesNotContain("已失效的建议影响");
        assertThat(context.promptContext()).doesNotContain("已撤销的边界");
        assertThat(context.metrics().valueCount()).isEqualTo(1);
        assertThat(context.metrics().emotionCount()).isEqualTo(1);
        assertThat(context.metrics().relationshipCount()).isEqualTo(1);
        assertThat(context.metrics().fearCount()).isEqualTo(1);
    }

    @Test
    void retrieveFallsBackToStructuredProfilesWhenVectorStoreIsMissing() {
        MemoryRetrievalService serviceWithoutVector = service(new MemoryRetrievalProperties(), null);
        when(valuesRepository.selectList(any())).thenReturn(List.of(value("稳定性", "偏好长期确定性", "0.85")));
        when(emotionRepository.selectList(any())).thenReturn(List.of());
        when(relationshipRepository.selectList(any())).thenReturn(List.of());
        when(fearRepository.selectList(any())).thenReturn(List.of());
        when(decisionRecallService.recall(USER_ID, "随便聊聊", 3)).thenReturn(decisionResult());

        MemoryContext context = serviceWithoutVector.retrieve(USER_ID, "随便聊聊");

        assertThat(context.promptContext()).contains("偏好长期确定性");
        assertThat(context.semanticMemories()).isEmpty();
        assertThat(context.metrics().vectorAvailable()).isFalse();
        assertThat(context.metrics().degraded()).isTrue();
    }

    @Test
    void retrieveKeepsReturningContextWhenRetrievalLogWriteFails() {
        when(valuesRepository.selectList(any())).thenReturn(List.of(value("稳定性", "偏好长期确定性", "0.85")));
        when(emotionRepository.selectList(any())).thenReturn(List.of());
        when(relationshipRepository.selectList(any())).thenReturn(List.of());
        when(fearRepository.selectList(any())).thenReturn(List.of());
        when(decisionRecallService.recall(USER_ID, "随便聊聊", 3)).thenReturn(decisionResult());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        doThrow(new RuntimeException("db down")).when(retrievalLogService)
                .recordAutoRecall(eq(USER_ID), eq("随便聊聊"), any(MemoryContext.class), eq(5), eq(0.3));

        MemoryContext context = service.retrieve(USER_ID, "随便聊聊");

        assertThat(context.promptContext()).contains("偏好长期确定性");
        assertThat(context.metrics().degraded()).isFalse();
        verify(retrievalLogService).recordAutoRecall(eq(USER_ID), eq("随便聊聊"),
                eq(context), eq(5), eq(0.3));
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
        when(decisionRecallService.recall(USER_ID, "query", 3)).thenReturn(decisionResult());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("x".repeat(260)).score(0.7).build()));

        MemoryContext context = service.retrieve(USER_ID, "query");

        assertThat(context.values()).hasSize(5);
        assertThat(context.promptContext()).contains("v5");
        assertThat(context.promptContext()).doesNotContain("v6");
        assertThat(context.semanticMemories().get(0).content()).hasSize(200);
    }

    @Test
    void retrieveUsesConfiguredSemanticParametersAndSectionLimits() {
        MemoryRetrievalProperties properties = new MemoryRetrievalProperties();
        properties.setSectionLimit(2);
        properties.setTextMaxLength(20);
        properties.setSemanticTopK(4);
        properties.setSemanticSimilarityThreshold(0.72);
        service = service(properties, vectorStore);
        when(valuesRepository.selectList(any())).thenReturn(List.of(
                value("v1", "p1", "0.9"),
                value("v2", "p2", "0.9"),
                value("v3", "p3", "0.9")));
        when(emotionRepository.selectList(any())).thenReturn(List.of());
        when(relationshipRepository.selectList(any())).thenReturn(List.of());
        when(fearRepository.selectList(any())).thenReturn(List.of());
        when(decisionRecallService.recall(USER_ID, "query", 3)).thenReturn(decisionResult());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("场景记忆内容".repeat(10)).score(0.8).build()));

        MemoryContext context = service.retrieve(USER_ID, "query");

        assertThat(context.values()).hasSize(2);
        assertThat(context.promptContext()).contains("v2");
        assertThat(context.promptContext()).doesNotContain("v3");
        assertThat(context.semanticMemories().get(0).content()).hasSize(20);
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getTopK()).isEqualTo(4);
        assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.72);
        verify(decisionRecallService).recall(eq(USER_ID), eq("query"), eq(3));
    }

    @Test
    void searchSemanticMemoriesOmitsInactiveLinkedSceneMemories() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().id("doc-deleted").text("已删除场景").score(0.9).build(),
                Document.builder().id("doc-active").text("仍有效场景").score(0.8).build(),
                Document.builder().id("doc-legacy").text("历史无关联场景").score(0.7).build()));
        when(sceneMemoryLinkRepository.selectOne(any())).thenReturn(
                sceneMemoryLink(false, "delete_failed"),
                sceneMemoryLink(true, "active"),
                null);

        MemoryRetrievalService.SemanticSearchResult result = service.searchSemanticMemories(USER_ID, "query", 5);

        assertThat(result.memories()).extracting(MemoryContext.SemanticMemory::content)
                .containsExactly("仍有效场景", "历史无关联场景");
        assertThat(result.maxScore()).isEqualTo(0.8);
        assertThat(result.degraded()).isFalse();
    }

    private MemoryRetrievalService service(MemoryRetrievalProperties properties, VectorStore vectorStore) {
        return new MemoryRetrievalService(
                valuesRepository,
                emotionRepository,
                relationshipRepository,
                fearRepository,
                sceneMemoryLinkRepository,
                vectorStore,
                properties,
                decisionRecallService,
                retrievalLogService);
    }

    private ProfileValues value(String item, String preference, String confidence) {
        ProfileValues value = new ProfileValues();
        value.setUserId(USER_ID);
        value.setActive(true);
        value.setItem(item);
        value.setPreference(preference);
        value.setConfidence(new BigDecimal(confidence));
        return value;
    }

    private ProfileEmotion emotion(String name, String behavior) {
        ProfileEmotion emotion = new ProfileEmotion();
        emotion.setUserId(USER_ID);
        emotion.setActive(true);
        emotion.setEmotion(name);
        emotion.setBehavior(behavior);
        return emotion;
    }

    private ProfileFear fear(String type, String description, String confidence) {
        ProfileFear fear = new ProfileFear();
        fear.setUserId(USER_ID);
        fear.setActive(true);
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
        relationship.setActive(true);
        relationship.setName(name);
        relationship.setRole(role);
        relationship.setInfluenceLevel(influenceLevel);
        relationship.setInfluenceStyle(influenceStyle);
        relationship.setNote(note);
        return relationship;
    }

    private DecisionRecallService.DecisionRecallResult decisionResult(
            DecisionRecallService.DecisionRecallItem... items) {
        return new DecisionRecallService.DecisionRecallResult(List.of(items));
    }

    private DecisionRecallService.DecisionRecallItem decision(String topic, String choice, String reason) {
        return new DecisionRecallService.DecisionRecallItem(
                topic,
                choice,
                reason,
                "",
                null,
                10,
                List.of("topic"));
    }

    private ProfileSceneMemoryLink sceneMemoryLink(boolean active, String deleteStatus) {
        ProfileSceneMemoryLink link = new ProfileSceneMemoryLink();
        link.setUserId(USER_ID);
        link.setActive(active);
        link.setDeleteStatus(deleteStatus);
        return link;
    }
}
