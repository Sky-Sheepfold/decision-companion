package com.sky.decisioncompanion.service.profile;

import com.sky.decisioncompanion.config.PostureGateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 深层画像门控（PostureGate）。
 *
 * <p>借鉴 OpenBiliClaw 的"深层封死 + 门控重建"设计：价值观 / 恐惧 / 边界属于深层画像，
 * 普通对话提炼默认只进候选，配置 enforce 模式后由 LLM 裁判决定"这条深层改写值不值得写入"，
 * 裁判异常时保守降级（不直接写入），避免 LLM 幻觉污染长期记忆。
 */
@Service
public class PostureGateService {

    private static final Logger logger = LoggerFactory.getLogger(PostureGateService.class);

    private final PostureGateProperties properties;
    private final ChatClient chatClient;

    public PostureGateService(PostureGateProperties properties, ChatClient.Builder chatClientBuilder) {
        this.properties = properties;
        this.chatClient = chatClientBuilder.build();
    }

    public boolean shouldGate(String profileType) {
        return properties.isEnabled() && ProfileWritePolicy.isDeep(profileType);
    }

    public GateVerdict evaluate(
            Long userId,
            String profileType,
            String subject,
            String content,
            List<String> evidence,
            BigDecimal confidence) {
        String mode = properties.getMode();
        if (!properties.isEnabled() || "off".equals(mode)) {
            return GateVerdict.accept("mode_off");
        }
        if ("shadow".equals(mode)) {
            // 影子模式：不阻塞写入，仅记录裁判结论用于评估；把裁判原始结论带回供审计落库
            try {
                String judgment = callJudge(profileType, subject, content, evidence, confidence);
                String judgeVerdict = parseVerdict(judgment);
                logger.info("深层画像门控影子裁判, userId: {}, profileType: {}, subject: {}, verdict: {}",
                        userId, profileType, subject, judgeVerdict);
                return GateVerdict.accept(judgeVerdict);
            } catch (Exception e) {
                logger.debug("深层画像门控影子裁判异常, userId: {}, subject: {}", userId, subject, e);
                return GateVerdict.accept("shadow_error");
            }
        }
        // enforce：阻塞等待裁判，异常保守降级
        try {
            String judgment = callJudge(profileType, subject, content, evidence, confidence);
            String action = parseVerdict(judgment);
            if ("reject".equals(action) || "downgrade".equals(action)) {
                logger.info("深层画像被门控拦截, userId: {}, profileType: {}, subject: {}, action: {}",
                        userId, profileType, subject, action);
                return GateVerdict.reject(action);
            }
            return GateVerdict.accept(action);
        } catch (Exception e) {
            logger.warn("深层画像门控裁判异常，保守降级, userId: {}, profileType: {}, subject: {}",
                    userId, profileType, subject, e);
            return GateVerdict.reject("error_downgrade");
        }
    }

    private String callJudge(String profileType, String subject, String content,
                             List<String> evidence, BigDecimal confidence) {
        String prompt = """
                你是用户长期记忆的守卫。系统准备把以下深层画像写入用户正式档案，请判断是否值得。

                画像类型：%s
                画像内容：%s
                置信度：%s
                证据：%s

                只返回一个 JSON 对象：
                {"verdict": "accept"} 表示内容与已有长期理解一致且证据充分，值得直接写入；
                {"verdict": "downgrade"} 表示部分可信，需要用户确认后再写入；
                {"verdict": "reject"} 表示证据不足或疑似模型幻觉，不应写入。

                不要返回其他任何文字。
                """.formatted(
                profileType,
                subject + "：" + content,
                confidence,
                String.join("；", evidence == null ? List.of() : evidence));
        return chatClient.prompt().messages(new UserMessage(prompt)).call().content();
    }

    private String parseVerdict(String raw) {
        if (raw == null) {
            return "reject";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("reject")) {
            return "reject";
        }
        if (lower.contains("downgrade")) {
            return "downgrade";
        }
        if (lower.contains("accept")) {
            return "accept";
        }
        return "reject";
    }

    public record GateVerdict(boolean accepted, String action) {

        static GateVerdict accept(String action) {
            return new GateVerdict(true, action);
        }

        static GateVerdict reject(String action) {
            return new GateVerdict(false, action);
        }
    }
}
