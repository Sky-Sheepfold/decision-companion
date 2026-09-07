package com.sky.decisioncompanion.service.agenttool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.model.AgentToolEffect;
import com.sky.decisioncompanion.model.ProfileMemoryCandidate;
import com.sky.decisioncompanion.repository.AgentToolEffectRepository;
import com.sky.decisioncompanion.repository.ProfileMemoryCandidateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实环境端到端幂等测试（可选，非默认）。
 *
 * <p>通过环境变量 {@code DC_E2E_REAL=true} 才会加载；否则整个类被跳过、不连库，普通 {@code mvn test} 不受影响。
 * 运行时依赖：本机 SSH 隧道 {@code 13306→服务器 MySQL}、{@code 18000→服务器 chroma}，
 * 及环境变量 {@code DC_E2E_DB_PWD / DC_E2E_DASH_KEY / DC_E2E_JWT / DC_E2E_USER_ID}。</p>
 *
 * <p>验证目标：同一会话同一规范化参数的两次 {@code updateUserProfile}，只产生一次业务副作用
 * （候选仅 1 条），幂等账本仅 1 条且由首次请求落账（requestId=req-1），第二次调用被短路重放。</p>
 */
@EnabledIfEnvironmentVariable(named = "DC_E2E_REAL", matches = "true")
@SpringBootTest
class AgentToolIdempotencyE2ETest {

    private static final long TEST_CONVERSATION_ID = 988877L;
    private static final String TOOL_NAME = "updateUserProfile";

    @DynamicPropertySource
    static void overrideToServerInfra(DynamicPropertyRegistry registry) {
        String dbPwd = System.getenv("DC_E2E_DB_PWD");
        String dashKey = System.getenv("DC_E2E_DASH_KEY");
        String jwt = System.getenv("DC_E2E_JWT");
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:13306/companion"
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> dbPwd == null ? "" : dbPwd);
        registry.add("spring.ai.dashscope.api-key", () -> dashKey == null ? "" : dashKey);
        registry.add("sa-token.jwt-secret-key", () -> jwt == null ? "e2e-secret" : jwt);
        registry.add("spring.ai.vectorstore.chroma.client.host", () -> "http://127.0.0.1");
        registry.add("spring.ai.vectorstore.chroma.client.port", () -> 18000);
        registry.add("decision-companion.memory.extract.enabled", () -> "false");
        registry.add("decision-companion.memory.awareness.enabled", () -> "false");
        registry.add("decision-companion.memory.insight.enabled", () -> "false");
        registry.add("decision-companion.memory.posture-gate.mode", () -> "off");
    }

    @Autowired
    private DecisionAgentToolService service;

    @Autowired
    private AgentToolEffectRepository effectRepository;

    @Autowired
    private ProfileMemoryCandidateRepository candidateRepository;

    @Test
    void identicalWritesProduceSingleEffectAndSecondCallShortCircuits() {
        long userId = Long.parseLong(System.getenv("DC_E2E_USER_ID"));
        effectRepository.delete(new LambdaQueryWrapper<AgentToolEffect>()
                .eq(AgentToolEffect::getUserId, userId));
        candidateRepository.delete(new LambdaQueryWrapper<ProfileMemoryCandidate>()
                .eq(ProfileMemoryCandidate::getUserId, userId));

        try {
            // 第一次：medium 置信度 -> needs_confirmation，落一个候选并回填账本
            DecisionAgentToolService.UpdateUserProfileToolResult first = service.updateUserProfile(
                    "value", " 测试城市偏好 ", " 测试倾向内容 ", " 测试备注 ", 0.70,
                    List.of("这是一条证据"), ctx(userId, "req-1"));
            assertThat(first.action()).isEqualTo("needs_confirmation");

            // 第二次：相同规范化参数，应命中账本短路（重放首次结果）
            DecisionAgentToolService.UpdateUserProfileToolResult second = service.updateUserProfile(
                    "value", " 测试城市偏好 ", " 测试倾向内容 ", " 测试备注 ", 0.70,
                    List.of("这是一条证据"), ctx(userId, "req-2"));
            assertThat(second.action()).isEqualTo("needs_confirmation");

            // 账本：仅 1 条 committed，且落账的是首次请求
            List<AgentToolEffect> effects = effectRepository.selectList(new LambdaQueryWrapper<AgentToolEffect>()
                    .eq(AgentToolEffect::getUserId, userId)
                    .eq(AgentToolEffect::getToolName, TOOL_NAME));
            assertThat(effects).hasSize(1);
            assertThat(effects.get(0).getRequestId()).isEqualTo("req-1");
            assertThat(effects.get(0).getStatus()).isEqualTo("committed");
            assertThat(effects.get(0).getAction()).isEqualTo("needs_confirmation");
            assertThat(effects.get(0).getCandidateId()).isNotNull();

            // 业务副作用：候选仅 1 条（第二次调用未再写入）
            Long candidateCount = candidateRepository.selectCount(new LambdaQueryWrapper<ProfileMemoryCandidate>()
                    .eq(ProfileMemoryCandidate::getUserId, userId)
                    .eq(ProfileMemoryCandidate::getProfileType, "value")
                    .eq(ProfileMemoryCandidate::getSubject, "测试城市偏好")
                    .eq(ProfileMemoryCandidate::getContent, "测试倾向内容"));
            assertThat(candidateCount).isEqualTo(1L);
        } finally {
            effectRepository.delete(new LambdaQueryWrapper<AgentToolEffect>()
                    .eq(AgentToolEffect::getUserId, userId));
            candidateRepository.delete(new LambdaQueryWrapper<ProfileMemoryCandidate>()
                    .eq(ProfileMemoryCandidate::getUserId, userId));
        }
    }

    private ToolContext ctx(long userId, String requestId) {
        return new ToolContext(Map.of(
                AgentToolContext.USER_ID, userId,
                AgentToolContext.CONVERSATION_ID, TEST_CONVERSATION_ID,
                AgentToolContext.MESSAGE, "我正在考虑是否接受外地 offer",
                AgentToolContext.REQUEST_ID, requestId));
    }
}