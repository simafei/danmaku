package com.lbank.danmaku.job.dto;

import lombok.Data;

/**
 * AI 分析结果：过滤 + 话题/事件提炼。
 *
 * 不生成弹幕文案，只输出结构化的过滤标记和事件元数据。
 */
@Data
public class AiDanmakuResult {
    /** 最终建议：push、discard、hold。 */
    private String decision;
    /** 建议原因，便于排查为什么没推送。 */
    private String decisionReason;
    /** 是否广告、导流、返佣或带单。 */
    private boolean ad;
    /** 广告判断原因。 */
    private String adReason;
    /** 是否适合展示（过滤广告、水聊、无关内容后）。 */
    private boolean displayable;
    /** 关联交易对，格式如 BTCUSDT；无明确交易对时为空。 */
    private String symbol;
    /** 事件类型：price / news / opinion / question / other。 */
    private String eventType;
    /** 情绪倾向：bullish / bearish / neutral。 */
    private String sentiment;
    /** 结合当前消息和上下文提炼的核心话题，6–20字。 */
    private String topic;
    /** 置信度，0–100。 */
    private Integer confidence;
    /** 源语言代码，如 zh / en / ru。 */
    private String sourceLanguage;
    /** 市场类型：SPOT（现货）/ FUTURE（合约）；无法判断时为空。 */
    private String marketType;
    /** AI 模型名称，便于日志排查。 */
    private String modelName;
}
