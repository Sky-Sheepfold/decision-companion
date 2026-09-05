package com.sky.decisioncompanion.advisor;

import com.sky.decisioncompanion.service.memory.MemoryContext;
import com.sky.decisioncompanion.service.memory.MemoryRetrievalService;
import org.springframework.stereotype.Service;

/**
 * 组装生成前的上下文。
 *
 * <p>采用 OpenBiliClaw 的"稳定块 / 易变块"分层：
 * <ul>
 *   <li>稳定块（Core）：高置信价值观/恐惧边界，恒定进 system prompt 前缀，利于 provider prompt cache 命中；</li>
 *   <li>易变块（Volatile）：意图限量的动态召回 + 场景语义记忆，前置到 user message，避免频繁变化打爆缓存。</li>
 * </ul>
 */
@Service
public class ProfileAdvisorService {

    private final MemoryRetrievalService memoryRetrievalService;

    public ProfileAdvisorService(MemoryRetrievalService memoryRetrievalService) {
        this.memoryRetrievalService = memoryRetrievalService;
    }

    public String buildSystemPrompt(Long userId, String userMessage) {
        return buildProfilePrompt(userId, userMessage).systemPrompt();
    }

    public ProfilePrompt buildProfilePrompt(Long userId, String userMessage) {
        MemoryContext memoryContext = memoryRetrievalService.retrieve(userId, userMessage);
        String coreSection = memoryRetrievalService.renderCoreSection(memoryContext.coreProfiles());

        String systemPrompt = """
                你是用户的人生决策伙伴，像一个懂他的朋友一样陪他想清楚问题。

                %s

                【对话原则：】
                1. 先听完，不打断，不急着给结论
                2. 根据情绪模式判断：他现在需要共情还是分析
                3. 建议要结合他的价值观，不要给"通用"建议
                4. 如果发现他可能在逃避某个恐惧，温和地指出来
                5. 日常小事也认真对待，不要觉得"这不是大事"
                """.formatted(coreSection);
        return new ProfilePrompt(systemPrompt, memoryContext, memoryContext.promptContext());
    }

    public record ProfilePrompt(String systemPrompt, MemoryContext memoryContext, String volatileContext) {
    }
}
