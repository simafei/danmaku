package com.lbank.danmaku.job.service;

import com.lbank.danmaku.job.client.AiDanmakuClient;
import com.lbank.danmaku.job.config.DanmakuProperties;
import com.lbank.danmaku.job.domain.TgRawMessage;
import com.lbank.danmaku.job.dto.AiDanmakuResult;
import com.lbank.danmaku.job.dto.AiPromptRequest;
import com.lbank.danmaku.job.dto.AiPromptResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AiDanmakuService {
    private final AiDanmakuClient aiDanmakuClient;
    private final EventProvider eventProvider;
    private final DanmakuProperties properties;
    private final ObjectMapper objectMapper;

    public AiDanmakuService(
            AiDanmakuClient aiDanmakuClient,
            EventProvider eventProvider,
            DanmakuProperties properties,
            ObjectMapper objectMapper) {
        this.aiDanmakuClient = aiDanmakuClient;
        this.eventProvider = eventProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AiDanmakuResult generate(TgRawMessage rawMessage, List<TgRawMessage> contextMessages) {
        List<String> events = eventProvider.getActiveEvents();

        AiPromptRequest request = new AiPromptRequest();
        request.setModel(properties.getAi().getModel());
        request.setTemperature(properties.getAi().getTemperature());
        request.setResponseFormat(properties.getAi().getResponseFormat());
        request.setSystemPrompt(systemPrompt());
        request.setUserPrompt(userPrompt(rawMessage, contextMessages, events));

        AiPromptResponse response = aiDanmakuClient.complete(request);
        AiDanmakuResult result = parseResult(response);
        fillFallback(result, rawMessage, response);
        return result;
    }

    private String systemPrompt() {
        return """
                你是一个社区消息的过滤和分类助手。
                输入包含一条消息、近期上下文，以及一个【事件列表】。
                你需要完成两件事：
                1. 过滤掉广告和无意义噪声
                2. 从事件列表中找到与消息最相关的一条，作为 matchedEvent 输出

                ## 一、displayable 判断
                以下情况填 false，其余填 true：
                - 广告、推广、导流链接、带单、拉群邀请
                - 纯表情、纯寒暄、无意义噪声（例如"哈哈""好的""666""👍"）

                ## 二、matchedEvent（事件匹配）
                - 必须从【事件列表】中原文选取一项，不得修改或拼造
                - 选取与当前消息及上下文语义最相关的一项
                - 若消息与列表中任何事件都不相关，或 displayable=false，填空字符串

                ## 三、topic（话题提炼）
                - 结合当前消息和上下文，提炼正在讨论的具体话题
                - 中文 6–20 字，英文 5–15 词；语言与消息保持一致
                - 例如："美伊局势引发BTC暴跌""以太合约多单爆仓风险""XRP胜诉利好""世界杯决赛点球大战"
                - displayable=false 时留空

                ## 四、simplifiedContent（弹幕内容精简）
                - 若当前消息文字长度超过 50 字，提供一个不超过 50 字的精简版本
                - 精简时保留核心观点，语言与原文一致
                - 若原文不超过 50 字，或 displayable=false，留空字符串

                ## 五、marketType（仅加密货币讨论时填写）
                - 当消息明确讨论加密货币的合约或现货交易时填写，否则留空字符串
                - FUTURE：提到做多、做空、爆仓、资金费率、永续合约、杠杆、多单、空单
                - SPOT：提到买入、卖出、现货、持币，且没有合约相关语境
                - 无法判断或与加密交易无关：留空字符串

                ## 六、其他字段说明
                - confidence：0–100，对 matchedEvent 选择的把握程度；displayable=false 时填 0
                - ad：是否广告或推广内容
                - adReason：广告判断原因，ad=false 时留空
                - sourceLanguage：原始消息的语言代码，如 zh、en、ru、tr

                ## 七、输出要求
                - 只输出合法 JSON 对象，不加 Markdown 代码块，不写任何解释
                - 所有字段必须存在，缺失值用空字符串，布尔型用 false，数字型用 0

                JSON 字段列表：
                ad, adReason, displayable, matchedEvent, topic, simplifiedContent, marketType, confidence, sourceLanguage
                """;
    }

    private String userPrompt(TgRawMessage rawMessage, List<TgRawMessage> contextMessages,
                               List<String> events) {
        StringBuilder builder = new StringBuilder();

        // 事件列表
        builder.append("【事件列表】\n");
        if (events == null || events.isEmpty()) {
            builder.append("（无）\n");
        } else {
            for (String event : events) {
                builder.append("- ").append(event).append('\n');
            }
        }
        builder.append('\n');

        // 当前消息
        builder.append("【当前消息】\n");
        builder.append(formatCurrentMessage(rawMessage));
        builder.append('\n');

        // 近期上下文（按时间从近到远）
        builder.append("【近期上下文，按时间从近到远】\n");
        if (contextMessages == null || contextMessages.isEmpty()) {
            builder.append("无\n");
        } else {
            for (TgRawMessage ctx : contextMessages) {
                builder.append(formatContextMessage(ctx, rawMessage)).append('\n');
            }
        }
        return builder.toString();
    }

    /** 当前消息格式，含回复上下文 */
    private String formatCurrentMessage(TgRawMessage message) {
        if (message == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("user=").append(nullToEmpty(message.getSenderUsername()))
          .append(", text=").append(nullToEmpty(message.getNormalizedText()));
        if (StringUtils.hasText(message.getReplyToText())) {
            sb.append("\n  ↳ 回复的消息：").append(message.getReplyToText().trim());
        }
        return sb.toString();
    }

    /** 上下文消息格式，带相对时间 */
    private String formatContextMessage(TgRawMessage ctx, TgRawMessage current) {
        String timeLabel = "";
        if (ctx.getSentAt() != null && current != null && current.getSentAt() != null) {
            long secondsBefore = ChronoUnit.SECONDS.between(ctx.getSentAt(), current.getSentAt());
            timeLabel = secondsBefore < 60 ? secondsBefore + "秒前" : (secondsBefore / 60) + "分钟前";
        }
        return "- [" + timeLabel + "] user=" + nullToEmpty(ctx.getSenderUsername())
                + ", text=" + nullToEmpty(ctx.getNormalizedText());
    }

    private AiDanmakuResult parseResult(AiPromptResponse response) {
        String content = response == null ? null : response.getContent();
        if (!StringUtils.hasText(content)) {
            return hold("empty_ai_response");
        }
        try {
            return objectMapper.readValue(extractJson(content), AiDanmakuResult.class);
        } catch (JsonProcessingException e) {
            return hold("invalid_ai_json");
        }
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }

    private AiDanmakuResult hold(String reason) {
        AiDanmakuResult result = new AiDanmakuResult();
        result.setDecision("hold");
        result.setDecisionReason(reason);
        result.setDisplayable(false);
        result.setAd(false);
        result.setConfidence(0);
        return result;
    }

    private void fillFallback(
            AiDanmakuResult result,
            TgRawMessage rawMessage,
            AiPromptResponse response) {
        if (result == null) {
            return;
        }
        if (!StringUtils.hasText(result.getSourceLanguage()) && rawMessage != null) {
            result.setSourceLanguage(rawMessage.getLanguage());
        }
        if (!StringUtils.hasText(result.getModelName()) && response != null) {
            result.setModelName(response.getModel());
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
