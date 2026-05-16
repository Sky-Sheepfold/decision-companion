package com.sky.decisioncompanion.advisor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.model.*;
import com.sky.decisioncompanion.repository.*;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProfileAdvisorService {

    private final ProfileValuesRepository valuesRepository;
    private final ProfileDecisionRepository decisionRepository;
    private final ProfileEmotionRepository emotionRepository;
    private final ProfileRelationshipRepository relationshipRepository;
    private final ProfileFearRepository fearRepository;
    private final VectorStore vectorStore;

    public ProfileAdvisorService(
            ProfileValuesRepository valuesRepository,
            ProfileDecisionRepository decisionRepository,
            ProfileEmotionRepository emotionRepository,
            ProfileRelationshipRepository relationshipRepository,
            ProfileFearRepository fearRepository,
            @Autowired(required = false) @Nullable VectorStore vectorStore) {
        this.valuesRepository = valuesRepository;
        this.decisionRepository = decisionRepository;
        this.emotionRepository = emotionRepository;
        this.relationshipRepository = relationshipRepository;
        this.fearRepository = fearRepository;
        this.vectorStore = vectorStore;
    }

    public String buildSystemPrompt(Long userId, String userMessage) {
        String valuesContext = getValuesContext(userId);
        String emotionContext = getEmotionContext(userId);
        String semanticContext = getSemanticContext(userId, userMessage);

        return """
                你是用户的人生决策伙伴，像一个懂他的朋友一样陪他想清楚问题。

                【关于这个用户，你已经了解到的：】

                他的价值观：
                %s

                他的情绪模式：
                %s

                相关背景信息：
                %s

                【对话原则：】
                1. 先听完，不打断，不急着给结论
                2. 根据情绪模式判断：他现在需要共情还是分析
                3. 建议要结合他的价值观，不要给"通用"建议
                4. 如果发现他可能在逃避某个恐惧，温和地指出来
                5. 日常小事也认真对待，不要觉得"这不是大事"
                """.formatted(valuesContext, emotionContext, semanticContext);
    }

    private String getValuesContext(Long userId) {
        List<ProfileValues> values = valuesRepository.selectList(
                new LambdaQueryWrapper<ProfileValues>().eq(ProfileValues::getUserId, userId));

        if (values.isEmpty()) {
            return "（暂无价值观信息）";
        }

        return values.stream()
                .map(v -> String.format("- %s：%s（置信度：%.0f%%）",
                        v.getItem(),
                        v.getPreference(),
                        v.getConfidence().doubleValue() * 100))
                .collect(Collectors.joining("\n"));
    }

    private String getEmotionContext(Long userId) {
        List<ProfileEmotion> emotions = emotionRepository.selectList(
                new LambdaQueryWrapper<ProfileEmotion>().eq(ProfileEmotion::getUserId, userId));

        if (emotions.isEmpty()) {
            return "（暂无情绪模式信息）";
        }

        return emotions.stream()
                .map(e -> String.format("- %s：%s",
                        e.getEmotion(),
                        e.getBehavior()))
                .collect(Collectors.joining("\n"));
    }

    private String getSemanticContext(Long userId, String userMessage) {
        if (this.vectorStore == null) {
            return "（暂无相关背景信息 - 向量存储服务暂不可用）";
        }

        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(userMessage)
                    .topK(5)
                    .similarityThreshold(0.6)
                    .filterExpression("userId == '" + userId + "'")
                    .build();

            var relatedDocs = vectorStore.similaritySearch(searchRequest);

            if (relatedDocs.isEmpty()) {
                return "（暂无相关背景信息）";
            }

            return relatedDocs.stream()
                    .limit(3)
                    .map(doc -> doc.toString())
                    .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            return "（暂时无法获取相关背景信息）";
        }
    }
}
