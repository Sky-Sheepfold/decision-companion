package com.sky.decisioncompanion.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.decisioncompanion.common.Result;
import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.model.User;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import com.sky.decisioncompanion.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private final ProfileController controller = new ProfileController(
            userService,
            valuesRepository,
            decisionRepository,
            emotionRepository,
            relationshipRepository,
            fearRepository
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
    void getProfileSerializesDecisionTagsAsArray() {
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

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            Result<?> result = controller.getProfile().getBody();
            JsonNode tags = objectMapper.valueToTree(result.getData()).get("decisions").get(0).get("tags");

            assertThat(tags.isArray()).isTrue();
            assertThat(tags.get(0).asText()).isEqualTo("实习");
            assertThat(tags.get(1).asText()).isEqualTo("城市");
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
}
