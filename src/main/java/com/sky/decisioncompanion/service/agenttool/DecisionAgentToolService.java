package com.sky.decisioncompanion.service.agenttool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.model.ProfileDecision;
import com.sky.decisioncompanion.repository.ProfileDecisionRepository;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class DecisionAgentToolService {

    private static final int DEFAULT_LIMIT = 3;
    private static final int MAX_LIMIT = 5;
    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 5;

    private final ProfileDecisionRepository decisionRepository;
    private final VectorStore vectorStore;
    private final AgentToolCallLogService logService;

    public DecisionAgentToolService(
            ProfileDecisionRepository decisionRepository,
            @Autowired(required = false) @Nullable VectorStore vectorStore,
            AgentToolCallLogService logService) {
        this.decisionRepository = decisionRepository;
        this.vectorStore = vectorStore;
        this.logService = logService;
    }

    @Tool(name = "searchDecisionHistory", description = "查询当前用户的历史决策记录，用于识别相似选择和决策模式。该工具只读，不会写入或修改任何档案。")
    public DecisionHistoryToolResult searchDecisionHistory(
            @ToolParam(description = "与当前决策相关的查询文本") String query,
            @ToolParam(required = false, description = "最多返回多少条结果，默认 3 条，最多 5 条") Integer limit,
            ToolContext toolContext) {
        long start = System.nanoTime();
        AgentToolContext.Execution context = AgentToolContext.from(toolContext);
        String inputSummary = "query=" + query + ", limit=" + limit;

        try {
            int safeLimit = normalizeLimit(limit);
            List<String> queryTokens = tokens(query);
            List<DecisionHistoryItem> items = decisionRepository.selectList(
                            new LambdaQueryWrapper<ProfileDecision>()
                                    .eq(ProfileDecision::getUserId, context.userId())
                                    .last("LIMIT " + (safeLimit * 3)))
                    .stream()
                    .filter(decision -> Objects.equals(context.userId(), decision.getUserId()))
                    .filter(decision -> matchesDecision(decision, queryTokens))
                    .limit(safeLimit)
                    .map(this::toDecisionHistoryItem)
                    .toList();

            DecisionHistoryToolResult result = new DecisionHistoryToolResult(
                    true,
                    items.isEmpty() ? "未找到相似历史决策" : "找到相似历史决策",
                    items);
            logService.recordSuccess(context.userId(), context.conversationId(), "searchDecisionHistory",
                    inputSummary, summarizeDecisions(items), elapsedMillis(start));
            return result;
        } catch (Exception e) {
            logService.recordFailure(context.userId(), context.conversationId(), "searchDecisionHistory",
                    inputSummary, failureMessage("查询历史决策", e), elapsedMillis(start));
            return new DecisionHistoryToolResult(false, "暂时无法查询历史决策", List.of());
        }
    }

    @Tool(name = "searchSemanticMemory", description = "从向量存储中检索当前用户的长期语义记忆，用于补充结构化档案之外的背景信息。该工具只读，不会写入或修改任何档案。")
    public SemanticMemoryToolResult searchSemanticMemory(
            @ToolParam(description = "当前决策、复盘或困惑的查询文本") String query,
            @ToolParam(required = false, description = "最多返回多少条语义记忆，默认 3 条，最多 5 条") Integer topK,
            ToolContext toolContext) {
        long start = System.nanoTime();
        AgentToolContext.Execution context = AgentToolContext.from(toolContext);
        String inputSummary = "query=" + query + ", topK=" + topK;

        if (vectorStore == null) {
            logService.recordSkipped(context.userId(), context.conversationId(), "searchSemanticMemory",
                    inputSummary, "向量存储服务暂不可用", elapsedMillis(start));
            return new SemanticMemoryToolResult(false, "向量存储服务暂不可用", List.of());
        }

        try {
            int safeTopK = normalizeLimit(topK);
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(safeTopK)
                    .similarityThreshold(0.6)
                    .filterExpression("userId == '" + context.userId() + "'")
                    .build();
            List<SemanticMemoryItem> memories = vectorStore.similaritySearch(searchRequest)
                    .stream()
                    .limit(safeTopK)
                    .map(this::toSemanticMemoryItem)
                    .toList();
            SemanticMemoryToolResult result = new SemanticMemoryToolResult(
                    true,
                    memories.isEmpty() ? "未找到相关长期记忆" : "找到相关长期记忆",
                    memories);
            logService.recordSuccess(context.userId(), context.conversationId(), "searchSemanticMemory",
                    inputSummary, summarizeMemories(memories), elapsedMillis(start));
            return result;
        } catch (Exception e) {
            logService.recordFailure(context.userId(), context.conversationId(), "searchSemanticMemory",
                    inputSummary, failureMessage("查询长期语义记忆", e), elapsedMillis(start));
            return new SemanticMemoryToolResult(false, "暂时无法查询长期语义记忆", List.of());
        }
    }

    @Tool(name = "generateDecisionMatrix", description = "根据用户给出的选项和比较维度生成决策矩阵，帮助把纠结拆成可讨论的结构化维度。该工具只做分析，不会写入档案或保存决策记录。")
    public DecisionMatrixToolResult generateDecisionMatrix(
            @ToolParam(description = "决策主题") String topic,
            @ToolParam(description = "用户正在比较的选项列表") List<String> options,
            @ToolParam(description = "用于比较这些选项的决策维度列表") List<String> dimensions,
            ToolContext toolContext) {
        long start = System.nanoTime();
        AgentToolContext.Execution context = AgentToolContext.from(toolContext);
        String inputSummary = "topic=" + topic + ", options=" + options + ", dimensions=" + dimensions;

        try {
            List<String> safeOptions = safeList(options, 5);
            List<String> safeDimensions = safeList(dimensions, 8);
            List<DecisionMatrixOption> matrix = safeOptions.stream()
                    .map(option -> toDecisionMatrixOption(topic, option, safeDimensions))
                    .toList();
            DecisionMatrixToolResult result = new DecisionMatrixToolResult(true, "已生成决策矩阵", matrix);
            logService.recordSuccess(context.userId(), context.conversationId(), "generateDecisionMatrix",
                    inputSummary, summarizeMatrix(matrix), elapsedMillis(start));
            return result;
        } catch (Exception e) {
            logService.recordFailure(context.userId(), context.conversationId(), "generateDecisionMatrix",
                    inputSummary, failureMessage("生成决策矩阵", e), elapsedMillis(start));
            return new DecisionMatrixToolResult(false, "暂时无法生成决策矩阵", List.of());
        }
    }

    private DecisionHistoryItem toDecisionHistoryItem(ProfileDecision decision) {
        return new DecisionHistoryItem(
                decision.getTopic(),
                decision.getChoice(),
                decision.getReason(),
                decision.getOutcome(),
                decision.getSatisfaction());
    }

    private SemanticMemoryItem toSemanticMemoryItem(Document document) {
        Object type = document.getMetadata().getOrDefault("type", "memory");
        return new SemanticMemoryItem(
                document.getText() == null ? document.getFormattedContent() : document.getText(),
                type.toString(),
                document.getScore());
    }

    private DecisionMatrixOption toDecisionMatrixOption(String topic, String option, List<String> dimensions) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String dimension : dimensions) {
            scores.put(dimension, deterministicScore(topic, option, dimension));
        }
        return new DecisionMatrixOption(option, scores, option + " 在这些维度上可继续逐项讨论。");
    }

    private int deterministicScore(String topic, String option, String dimension) {
        int hash = Math.abs(Objects.hash(topic, option, dimension));
        return MIN_SCORE + (hash % (MAX_SCORE - MIN_SCORE + 1));
    }

    private boolean matchesDecision(ProfileDecision decision, List<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return true;
        }
        String haystack = String.join(" ",
                safe(decision.getTopic()),
                safe(decision.getChoice()),
                safe(decision.getReason()),
                safe(decision.getOutcome()),
                safe(decision.getTags())).toLowerCase(Locale.ROOT);
        return queryTokens.stream().anyMatch(haystack::contains);
    }

    private List<String> tokens(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        String[] rawTokens = normalized.split("[\\s,，。；;、]+");
        List<String> result = new ArrayList<>();
        for (String token : rawTokens) {
            if (StringUtils.hasText(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private List<String> safeList(List<String> values, int maxSize) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .limit(maxSize)
                .toList();
    }

    private String summarizeDecisions(List<DecisionHistoryItem> items) {
        return items.stream()
                .map(item -> item.topic() + ":" + item.choice())
                .collect(Collectors.joining("; "));
    }

    private String summarizeMemories(List<SemanticMemoryItem> memories) {
        return memories.stream()
                .map(memory -> safe(memory.content()))
                .collect(Collectors.joining("; "));
    }

    private String summarizeMatrix(List<DecisionMatrixOption> matrix) {
        return matrix.stream()
                .map(DecisionMatrixOption::option)
                .collect(Collectors.joining("; "));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private String failureMessage(String action, Exception e) {
        String detail = e.getMessage();
        if (!StringUtils.hasText(detail)) {
            return action + "失败";
        }
        return action + "失败：" + detail;
    }

    public record DecisionHistoryToolResult(boolean available, String message, List<DecisionHistoryItem> items) {
    }

    public record DecisionHistoryItem(
            String topic,
            String choice,
            String reason,
            String outcome,
            Integer satisfaction) {
    }

    public record SemanticMemoryToolResult(boolean available, String message, List<SemanticMemoryItem> memories) {
    }

    public record SemanticMemoryItem(String content, String type, Double score) {
    }

    public record DecisionMatrixToolResult(boolean available, String message, List<DecisionMatrixOption> matrix) {
    }

    public record DecisionMatrixOption(String option, Map<String, Integer> scores, String summary) {
    }
}
