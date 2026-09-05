package com.sky.decisioncompanion.service.memory;

import com.sky.decisioncompanion.model.MemoryAwareness;
import com.sky.decisioncompanion.model.MemoryInsight;
import com.sky.decisioncompanion.repository.MemoryInsightRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行为动机洞察（Insight）真实环境验证。
 *
 * <p>连接服务器 MySQL + Chroma + DashScope，验证：觉察→洞察生成→判定闭环（confirm/reject）→
 * 被否定假设不复活→召回注入【行为动机洞察】区块。运行：{@code mvn test -Dmemory.insight.live=true}。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "memory.insight.live", matches = "true")
class MemoryInsightLiveValidationTest {

    private static final Long USER_ID = Long.valueOf(System.getProperty("memory.insight.user-id", "12"));

    @Autowired
    private MemoryInsightService insightService;

    @Autowired
    private MemoryAwarenessService awarenessService;

    @Autowired
    private MemoryRetrievalService memoryRetrievalService;

    @Autowired
    private MemoryInsightRepository insightRepository;

    @Test
    void runInsightLiveValidation() {
        // 1. 觉察生成（幂等：同日同观察去重，已有则返回 0）
        int awarenessAdded = awarenessService.generateForUser(USER_ID);
        List<MemoryAwareness> awareness = awarenessService.findRecent(USER_ID, 20);
        System.out.printf("LIVE_INSIGHT awarenessAdded=%d awarenessCount=%d%n", awarenessAdded, awareness.size());
        assertThat(awareness).as("近因层需有觉察作为洞察素材").isNotEmpty();

        // 2. 洞察生成（真实 DashScope 提炼）
        int generatedAdded = insightService.generateForUser(USER_ID);
        List<MemoryInsight> insights = insightService.listInsights(USER_ID);
        System.out.printf("LIVE_INSIGHT generatedAdded=%d insightCount=%d unjudged=%d%n",
                generatedAdded, insights.size(), insightService.countUnjudged(USER_ID));
        for (MemoryInsight insight : insights) {
            System.out.printf("LIVE_INSIGHT_ROW id=%d verdict=%s conf=%s active=%s source=%s hypothesis=%s%n",
                    insight.getId(), insight.getVerdict(), insight.getConfidence(), insight.getActive(),
                    insight.getSourceAwarenessIds(), insight.getHypothesis());
        }
        assertThat(insights).as("真实环境应从觉察提炼出洞察").isNotEmpty();

        // 3. 判定闭环：confirm 一条未判定洞察
        MemoryInsight unjudged = insights.stream()
                .filter(i -> MemoryInsightService.VERDICT_UNJUDGED.equals(i.getVerdict()))
                .findFirst()
                .orElse(null);
        if (unjudged != null) {
            MemoryInsight confirmed = insightService.judge(USER_ID, unjudged.getId(), "confirm");
            System.out.printf("LIVE_INSIGHT_JUDGE confirm id=%d verdict=%s active=%s conf=%s%n",
                    confirmed.getId(), confirmed.getVerdict(), confirmed.getActive(), confirmed.getConfidence());
            assertThat(confirmed.getVerdict()).isEqualTo(MemoryInsightService.VERDICT_CONFIRMED);
            assertThat(confirmed.getActive()).isTrue();
            assertThat(confirmed.getConfidence().doubleValue())
                    .isGreaterThanOrEqualTo(0.75);
        }

        // 4. 判定闭环：reject 一条未判定洞察
        List<MemoryInsight> afterConfirm = insightService.listInsights(USER_ID);
        MemoryInsight toReject = afterConfirm.stream()
                .filter(i -> MemoryInsightService.VERDICT_UNJUDGED.equals(i.getVerdict()))
                .findFirst()
                .orElse(null);
        if (toReject != null) {
            MemoryInsight rejected = insightService.judge(USER_ID, toReject.getId(), "reject");
            System.out.printf("LIVE_INSIGHT_JUDGE reject id=%d verdict=%s active=%s%n",
                    rejected.getId(), rejected.getVerdict(), rejected.getActive());
            assertThat(rejected.getVerdict()).isEqualTo(MemoryInsightService.VERDICT_REJECTED);
            assertThat(rejected.getActive()).isFalse();
        }

        // 5. 重新生成：被否定假设不复活（active 保持 false），且不产生重复假设（merge 去重生效）
        long rejectedBefore = countActive(insightRepository, USER_ID, MemoryInsightService.VERDICT_REJECTED);
        int duplicateBefore = countDuplicateHypotheses(USER_ID);
        insightService.generateForUser(USER_ID);
        long rejectedAfter = countActive(insightRepository, USER_ID, MemoryInsightService.VERDICT_REJECTED);
        int duplicateAfter = countDuplicateHypotheses(USER_ID);
        System.out.printf("LIVE_INSIGHT_RESURRECT_CHECK rejectedActiveBefore=%d rejectedActiveAfter=%d "
                        + "duplicateHypothesisBefore=%d duplicateHypothesisAfter=%d%n",
                rejectedBefore, rejectedAfter, duplicateBefore, duplicateAfter);
        assertThat(rejectedAfter).isEqualTo(rejectedBefore);
        assertThat(duplicateAfter).as("二次生成不应产生重复假设").isZero();

        // 6. 召回注入：retrieve 的易变块应包含【行为动机洞察】与已确认假设
        MemoryContext context = memoryRetrievalService.retrieve(USER_ID,
                "我在纠结要不要接受外地的高薪机会，帮我分析一下");
        System.out.printf("LIVE_INSIGHT_RETRIEVE insightCount=%d hasInsightSection=%s%n",
                context.insights().size(), context.promptContext().contains("【行为动机洞察"));
        System.out.printf("LIVE_INSIGHT_SECTION %s%n",
                abbreviate(section(context.promptContext(), "【行为动机洞察"), 400));
        assertThat(context.promptContext()).contains("【行为动机洞察");
        assertThat(context.insights()).isNotEmpty();

        System.out.println("LIVE_INSIGHT VALIDATION PASSED");
    }

    private long countActive(MemoryInsightRepository repository, Long userId, String verdict) {
        return repository.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MemoryInsight>()
                        .eq(MemoryInsight::getUserId, userId)
                        .eq(MemoryInsight::getActive, true)
                        .eq(MemoryInsight::getVerdict, verdict))
                .size();
    }

    /** 统计规范化解重的假设中出现重复的条数（>0 说明 merge 去重失效）。 */
    private int countDuplicateHypotheses(Long userId) {
        List<String> normalized = insightRepository.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MemoryInsight>()
                                .eq(MemoryInsight::getUserId, userId))
                .stream()
                .map(insight -> normalize(insight.getHypothesis()))
                .filter(text -> !text.isEmpty())
                .toList();
        java.util.Map<String, Long> counts = normalized.stream()
                .collect(java.util.stream.Collectors.groupingBy(text -> text, java.util.stream.Collectors.counting()));
        return (int) counts.values().stream()
                .mapToLong(Long::longValue)
                .filter(count -> count > 1)
                .map(count -> count - 1)
                .sum();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String section(String text, String header) {
        int start = text.indexOf(header);
        if (start < 0) {
            return "";
        }
        int next = text.indexOf("【", start + 1);
        return next < 0 ? text.substring(start) : text.substring(start, next);
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
