package com.sky.decisioncompanion.service.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProfileSceneMemoryServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Test
    void writesSceneMemoryWithTypedMetadata() {
        ProfileSceneMemoryService service = new ProfileSceneMemoryService(vectorStore);

        service.saveSceneMemory(new ProfileSceneMemoryService.SceneMemoryWrite(
                7L,
                "我怕离家太远以后没时间陪父母",
                "agent_tool_update",
                1,
                List.of("value"),
                List.of("我怕离家太远以后没时间陪父母"),
                List.of("value:城市偏好:更偏向离家近的城市"),
                "value",
                new BigDecimal("0.95"),
                9L,
                null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Document document = captor.getValue().get(0);

        assertThat(document.getMetadata())
                .containsEntry("userId", "7")
                .containsEntry("type", "conversation_scene")
                .containsEntry("memoryRole", "scene_evidence")
                .containsEntry("source", "agent_tool_update")
                .containsEntry("profileRecordCount", 1)
                .containsEntry("memoryType", "value")
                .containsEntry("profileTypes", "value")
                .containsEntry("confidence", 0.95d)
                .containsEntry("sourceConversationId", "9")
                .containsEntry("chunkType", "typed_memory")
                .containsEntry("chunkIndex", 0)
                .containsKeys("evidenceHash", "dedupeKey");
        assertThat(document.getId()).isEqualTo(document.getMetadata().get("dedupeKey"));
        assertThat(document.getText())
                .contains("场景记忆片段")
                .contains("记忆类型: value")
                .contains("用户表达: 我怕离家太远以后没时间陪父母")
                .contains("关键证据: [我怕离家太远以后没时间陪父母]")
                .contains("场景线索: value:城市偏好:更偏向离家近的城市");
    }

    @Test
    void splitsSceneSignalsIntoTypedChunks() {
        ProfileSceneMemoryService service = new ProfileSceneMemoryService(vectorStore);

        service.saveSceneMemory(new ProfileSceneMemoryService.SceneMemoryWrite(
                7L,
                "我希望离家近一点，也担心高压环境让我失去生活节奏",
                "profile_extract",
                2,
                List.of("values", "fears"),
                List.of("我希望离家近一点", "我担心长期高压"),
                List.of(
                        "value:城市偏好:更偏向离家近的城市",
                        "boundary:工作强度:不接受长期高压"),
                "mixed",
                new BigDecimal("0.93"),
                null,
                null));

        List<Document> documents = captureDocuments();

        assertThat(documents).hasSize(2);
        assertThat(documents)
                .extracting(document -> document.getMetadata().get("memoryType"))
                .containsExactly("value", "fear");
        assertThat(documents)
                .extracting(document -> document.getMetadata().get("chunkIndex"))
                .containsExactly(0, 1);
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.getMetadata())
                    .containsEntry("chunkType", "typed_memory")
                    .containsKeys("evidenceHash", "dedupeKey");
            assertThat(document.getId()).isEqualTo(document.getMetadata().get("dedupeKey"));
        });
    }

    @Test
    void repeatedNormalizedSignalUsesSameStableDocumentId() {
        ProfileSceneMemoryService service = new ProfileSceneMemoryService(vectorStore);

        service.saveSceneMemory(sceneMemory("value:Career   Choice:Remote WORK", "value"));
        service.saveSceneMemory(sceneMemory(" VALUE:Career Choice:remote work ", "value"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).add(captor.capture());

        assertThat(captor.getAllValues().get(0).get(0).getId())
                .isEqualTo(captor.getAllValues().get(1).get(0).getId());
    }

    @Test
    void normalizedDuplicateSignalsWithinWriteProduceSingleChunk() {
        ProfileSceneMemoryService service = new ProfileSceneMemoryService(vectorStore);

        service.saveSceneMemory(new ProfileSceneMemoryService.SceneMemoryWrite(
                7L,
                "用户表达",
                "agent_tool_update",
                1,
                List.of("value"),
                List.of("证据"),
                List.of(
                        "value:Career   Choice:Remote WORK",
                        " VALUE:Career Choice:remote work "),
                "value",
                new BigDecimal("0.95"),
                9L,
                null));

        assertThat(captureDocuments()).hasSize(1);
    }

    @Test
    void samePayloadWithDifferentTypesUsesDifferentDocumentIds() {
        ProfileSceneMemoryService service = new ProfileSceneMemoryService(vectorStore);

        service.saveSceneMemory(sceneMemory("value:稳定:希望保持可预期", "value"));
        service.saveSceneMemory(sceneMemory("fear:稳定:希望保持可预期", "fear"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).add(captor.capture());

        assertThat(captor.getAllValues().get(0).get(0).getId())
                .isNotEqualTo(captor.getAllValues().get(1).get(0).getId());
    }

    @Test
    void missingSignalsFallsBackToEvidenceChunks() {
        ProfileSceneMemoryService service = new ProfileSceneMemoryService(vectorStore);

        service.saveSceneMemory(new ProfileSceneMemoryService.SceneMemoryWrite(
                7L,
                "我需要保留自己的节奏",
                "profile_extract",
                1,
                List.of("values"),
                List.of("我不想每天被排满", "我希望自己安排时间"),
                List.of(),
                "values",
                new BigDecimal("0.90"),
                null,
                null));

        List<Document> documents = captureDocuments();

        assertThat(documents).hasSize(2);
        assertThat(documents)
                .extracting(document -> document.getMetadata().get("memoryType"))
                .containsOnly("value");
        assertThat(documents)
                .extracting(Document::getText)
                .allSatisfy(text -> assertThat(text).contains("场景线索: "));
    }

    @Test
    void missingSignalsAndEvidenceFallsBackToUserMessageChunk() {
        ProfileSceneMemoryService service = new ProfileSceneMemoryService(vectorStore);

        service.saveSceneMemory(new ProfileSceneMemoryService.SceneMemoryWrite(
                7L,
                "我需要保留自己的节奏",
                "profile_extract",
                1,
                List.of(),
                List.of(),
                List.of(),
                "",
                null,
                null,
                null));

        List<Document> documents = captureDocuments();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getMetadata())
                .containsEntry("memoryType", "mixed")
                .containsEntry("chunkType", "typed_memory");
        assertThat(documents.get(0).getText())
                .contains("场景线索: 我需要保留自己的节奏");
    }

    @Test
    void missingVectorStoreDoesNotBlockSceneMemorySave() {
        ProfileSceneMemoryService service = new ProfileSceneMemoryService(null);

        service.saveSceneMemory(new ProfileSceneMemoryService.SceneMemoryWrite(
                7L,
                "我不想每天被排满",
                "profile_extract",
                1,
                List.of("values"),
                List.of("我不想每天被排满"),
                List.of("value:自由度:希望保留自主安排时间的空间"),
                "values",
                new BigDecimal("0.90"),
                null,
                null));
    }

    @Test
    void saveSceneMemoryReturnsWrittenDocumentIdsAndSourceProfileRecordId() {
        ProfileSceneMemoryService service = new ProfileSceneMemoryService(vectorStore);

        ProfileSceneMemoryService.SceneMemoryWriteResult result = service.saveSceneMemory(
                new ProfileSceneMemoryService.SceneMemoryWrite(
                        7L,
                        "我更在意需要时能回家",
                        "user_confirm",
                        1,
                        List.of("value"),
                        List.of("我更在意需要时能回家"),
                        List.of("value:城市距离:需要时能回家"),
                        "value",
                        new BigDecimal("0.90"),
                        9L,
                        101L));

        List<Document> documents = captureDocuments();

        assertThat(result.documentIds()).containsExactly(documents.get(0).getId());
        assertThat(documents.get(0).getMetadata())
                .containsEntry("sourceProfileRecordId", "101");
    }

    @Test
    void deleteSceneMemoryDeletesDocumentById() {
        ProfileSceneMemoryService service = new ProfileSceneMemoryService(vectorStore);

        boolean deleted = service.deleteSceneMemory("profile-scene:7:value:abc");

        assertThat(deleted).isTrue();
        verify(vectorStore).delete(List.of("profile-scene:7:value:abc"));
    }

    @Test
    void deleteSceneMemoryReturnsFalseWhenVectorStoreMissing() {
        ProfileSceneMemoryService service = new ProfileSceneMemoryService(null);

        assertThat(service.deleteSceneMemory("profile-scene:7:value:abc")).isFalse();
    }

    private ProfileSceneMemoryService.SceneMemoryWrite sceneMemory(String signal, String memoryType) {
        return new ProfileSceneMemoryService.SceneMemoryWrite(
                7L,
                "用户表达",
                "agent_tool_update",
                1,
                List.of(memoryType),
                List.of("证据"),
                List.of(signal),
                memoryType,
                new BigDecimal("0.95"),
                9L,
                null);
    }

    private List<Document> captureDocuments() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        return captor.getValue();
    }
}
