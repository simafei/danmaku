package com.lbank.danmaku.job.dto;

import lombok.Data;

/**
 * AI 分析结果：过滤 + 事件匹配。
 *
 * AI 只负责两件事：
 * 1. 判断消息是否值得展示（过滤广告、水聊）
 * 2. 从提供的事件列表中匹配最相关的一条
 */
@Data
public class AiDanmakuResult {
    /** 内部流转建议：push、discard、hold。 */
    private String decision;
    /** 建议原因，便于排查。 */
    private String decisionReason;
    /** 是否广告、导流、返佣或带单。 */
    private boolean ad;
    /** 广告判断原因。 */
    private String adReason;
    /** 是否值得展示（非广告、非水聊、与加密行情相关）。 */
    private boolean displayable;
    /** 从事件列表中匹配到的事件或交易对；无匹配时为空。 */
    private String matchedEvent;
    /** 结合当前消息和上下文提炼的具体话题，6–20字；displayable=false 时为空。 */
    private String topic;
    /** 弹幕展示内容：原文超过50字时由 AI 提供的精简版（≤50字）；原文不超过50字时为空，直接使用原文。 */
    private String simplifiedContent;
    /** 对 matchedEvent 匹配的置信度，0–100。 */
    private Integer confidence;
    /** 源语言代码，如 zh / en / ru。 */
    private String sourceLanguage;
    /** 市场类型：SPOT（现货）/ FUTURE（合约/永续/杠杆）；非加密货币讨论或无法判断时为空。 */
    private String marketType;
    /** AI 模型名称，便于日志排查。 */
    private String modelName;
}
