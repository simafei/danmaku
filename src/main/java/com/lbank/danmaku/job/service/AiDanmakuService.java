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
                你是加密货币社区消息的分析助手。
                输入是来自 Telegram 官方群的一条消息及其近期上下文，你需要完成两件事：
                1. 过滤掉不值得展示的消息
                2. 从有效消息中提炼出正在讨论的话题和事件

                ## 一、displayable 判断
                以下情况填 false，其余填 true：
                - 广告、导流链接、返佣、带单、拉群邀请
                - 纯表情、纯寒暄、无意义水聊（例如"哈哈""好的""666"）
                - 与加密货币行情完全无关的闲聊
                - 无法识别出关联交易对的消息

                ## 二、symbol 识别
                - 格式必须是 USDT 计价交易对，例如 BTCUSDT、ETHUSDT、SOLUSDT、XRPUSDT
                - 根据消息语义判断，不要猜测；币对本身就是一种事件，能识别出来就填
                - 多个交易对时只填最主要的一个
                - 无法确定时留空字符串

                ## 三、topic（核心话题）提炼
                - 结合当前消息和上下文，提炼出正在讨论的核心话题或事件
                - 中文 6–20 字，英文 5–15 词；语言与消息保持一致
                - 要能说明"在讨论什么"，例如：
                    "BTC 量能放大是否突破前高""以太多单爆仓风险""XRP 胜诉监管利好""美联储暂停加息短线反应"
                - displayable=false 时留空

                ## 四、eventType 说明
                - price：价格走势、突破、支撑压力位、涨跌幅
                - news：公告、上所、合作、监管、宏观事件
                - opinion：个人观点、看法、预测、分析
                - question：提问
                - other：以上都不符合

                ## 五、其他字段说明
                - sentiment：bullish（看涨）/ bearish（看跌）/ neutral（中性或无法判断）
                - confidence：0–100，对 symbol 识别和 displayable 判断的综合把握程度
                - ad：是否广告或导流内容
                - adReason：广告判断原因，ad=false 时留空
                - sourceLanguage：原始消息的语言代码，如 zh、en、ru、tr
                - marketType：SPOT（现货）或 FUTURE（合约/永续/杠杆）；区分不出时留空
                  判断依据：提到"做多/做空/爆仓/资金费率/永续/杠杆/合约"→ FUTURE；提到"买入/卖出/持仓/现货"且无合约语境 → SPOT

                ## 六、输出要求
                - 只输出合法 JSON 对象，不加 Markdown 代码块，不写任何解释
                - 所有字段必须存在，缺失值用空字符串，布尔型用 false，数字型用 0

                JSON 字段列表：
                ad, adReason, displayable, symbol, marketType, eventType, sentiment, topic, confidence, sourceLanguage
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
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
