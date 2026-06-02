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
                9L));

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
                .containsEntry("sourceConversationId", "9");
        assertThat(document.getText())
                .contains("场景记忆")
                .contains("用户表达: 我怕离家太远以后没时间陪父母")
                .contains("关键证据: [我怕离家太远以后没时间陪父母]")
                .contains("关联画像类型: value")
                .contains("场景线索: [value:城市偏好:更偏向离家近的城市]");
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
                null));
    }
}
