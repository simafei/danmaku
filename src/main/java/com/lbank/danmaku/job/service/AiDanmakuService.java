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
                你是加密货币社区消息的过滤和分类助手。
                输入包含一条 Telegram 群消息、近期上下文，以及一个【事件列表】。
                你需要完成两件事：
                1. 判断消息是否值得展示（过滤广告、水聊）
                2. 从事件列表中找到与消息最相关的一条，作为 matchedEvent 输出

                ## 一、displayable 判断
                以下情况填 false，其余填 true：
                - 广告、导流链接、返佣、带单、拉群邀请
                - 纯表情、纯寒暄、无意义水聊（例如"哈哈""好的""666"）
                - 与加密货币行情完全无关的闲聊

                ## 二、matchedEvent（事件匹配）
                - 必须从【事件列表】中原文选取一项，不得修改或拼造
                - 选取与当前消息及上下文语义最相关的一项
                - 若消息与列表中任何事件都不相关，或 displayable=false，填空字符串

                ## 三、其他字段说明
                - confidence：0–100，对 matchedEvent 选择的把握程度；displayable=false 时填 0
                - ad：是否广告或导流内容
                - adReason：广告判断原因，ad=false 时留空
                - sourceLanguage：原始消息的语言代码，如 zh、en、ru、tr

                ## 四、输出要求
                - 只输出合法 JSON 对象，不加 Markdown 代码块，不写任何解释
                - 所有字段必须存在，缺失值用空字符串，布尔型用 false，数字型用 0

                JSON 字段列表：
                ad, adReason, displayable, matchedEvent, confidence, sourceLanguage
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
