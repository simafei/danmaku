package com.example.danmaku.service;

import com.example.danmaku.client.AiDanmakuClient;
import com.example.danmaku.config.DanmakuProperties;
import com.example.danmaku.domain.TgRawMessage;
import com.example.danmaku.dto.AiDanmakuResult;
import com.example.danmaku.dto.AiPromptRequest;
import com.example.danmaku.dto.AiPromptResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AiDanmakuService {
    private final AiDanmakuClient aiDanmakuClient;
    private final DanmakuProperties properties;
    private final ObjectMapper objectMapper;

    public AiDanmakuService(
            AiDanmakuClient aiDanmakuClient,
            DanmakuProperties properties,
            ObjectMapper objectMapper) {
        this.aiDanmakuClient = aiDanmakuClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AiDanmakuResult generate(TgRawMessage rawMessage, List<TgRawMessage> contextMessages) {
        AiPromptRequest request = new AiPromptRequest();
        request.setModel(properties.getAi().getModel());
        request.setTemperature(properties.getAi().getTemperature());
        request.setResponseFormat(properties.getAi().getResponseFormat());
        request.setSystemPrompt(systemPrompt());
        request.setUserPrompt(userPrompt(rawMessage, contextMessages));

        AiPromptResponse response = aiDanmakuClient.complete(request);
        AiDanmakuResult result = parseResult(response);
        fillFallback(result, rawMessage, response);
        return result;
    }

    private String systemPrompt() {
        return """
                你是加密货币 K 线页面的弹幕内容助手。
                输入是来自 Telegram 官方群的一条消息及其近期上下文，你需要分析消息内容并填写以下 JSON 字段。

                ## 一、displayable 判断
                以下情况填 false，其余填 true：
                - 广告、导流链接、返佣、带单、拉群邀请
                - 纯表情、纯寒暄、无意义水聊（例如"哈哈""好的""666"）
                - 与加密货币行情完全无关的闲聊
                - 无法识别出关联交易对的消息

                ## 二、symbol 识别
                - 格式必须是 USDT 计价交易对，例如 BTCUSDT、ETHUSDT、SOLUSDT、XRPUSDT
                - 根据消息语义判断，不要猜测
                - 多个交易对时只填最主要的一个
                - 无法确定时留空字符串

                ## 三、content 生成
                - 仅在 displayable=true 且 symbol 不为空时生成，否则留空
                - 必须使用原始消息的主要语言，不做翻译
                - 长度：中文 8–20 字，英文 6–15 词，其他语言等比
                - 口语化，像真实用户在 K 线页随手发的一句弹幕
                - 禁止：喊单（快买/快跑）、绝对判断（必涨/必跌）、收益承诺
                - 禁止：系统播报腔（"群内讨论升温""用户认为""市场情绪"）
                - 禁止：编造原文没有的信息，不伪造具体身份（如"群主说""大户确认"）
                - 正文不重复币对名称，页面已经是对应交易对的 K 线页
                - 结合上下文的具体细节生成，同一事件不要重复使用相同句式

                ## 四、eventType 说明
                - price：价格走势、突破、支撑压力位、涨跌幅讨论
                - news：公告、上所、合作、监管、宏观事件
                - opinion：个人观点、看法、预测、分析
                - question：提问
                - other：以上都不符合

                ## 五、其他字段说明
                - sentiment：bullish（看涨）/ bearish（看跌）/ neutral（中性或无法判断）
                - confidence：0–100，你对 symbol 识别和 displayable 判断的综合把握程度
                - topic：4–12 字的话题关键词，用于分享展示；无明确话题时留空
                - ad：是否广告或导流内容
                - adReason：广告判断原因，ad=false 时留空
                - sourceLanguage：原始消息的语言代码，如 zh、en、ru、tr
                - marketType：市场类型，SPOT（现货）或 FUTURE（合约/永续/杠杆）；根据消息语义判断，区分不出时留空字符串
                  判断依据举例：提到"做多/做空/爆仓/资金费率/永续/杠杆/合约"→ FUTURE；提到"买入/卖出/持仓/现货"且无合约语境 → SPOT

                ## 六、输出要求
                - 只输出合法 JSON 对象，不加 Markdown 代码块，不写任何解释
                - 所有字段必须存在，缺失值用空字符串，布尔型用 false，数字型用 0

                JSON 字段列表：
                ad, adReason, displayable, symbol, marketType, eventType, sentiment, topic, confidence, sourceLanguage, content
                """;
    }

    private String userPrompt(TgRawMessage rawMessage, List<TgRawMessage> contextMessages) {
        StringBuilder builder = new StringBuilder();
        builder.append("弹幕语言：").append(rawMessage == null ? "" : nullToEmpty(rawMessage.getLanguage())).append('\n');
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
        // 如果是回复消息，附上被回复的内容帮助 AI 理解语境
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
            if (secondsBefore < 60) {
                timeLabel = secondsBefore + "秒前";
            } else {
                timeLabel = (secondsBefore / 60) + "分钟前";
            }
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
        // AI 未生成弹幕文案时不以原始消息兜底推送，改为 hold，避免把未经处理的长文本发出去。
        if (!StringUtils.hasText(result.getContent())) {
            result.setDecision("hold");
            result.setDecisionReason("empty_content");
            result.setDisplayable(false);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
