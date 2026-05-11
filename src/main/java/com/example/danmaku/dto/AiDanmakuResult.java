package com.example.danmaku.dto;

import lombok.Data;

/**
 * AI 生成弹幕的结果。
 *
 * 这是前期 MVP 的核心结果对象，不再单独拆“分析结果表”和“话题表”。
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
    /** 是否适合展示为弹幕。 */
    private boolean displayable;
    /** 关联交易对；无明确交易对时前期不推送。 */
    private String symbol;
    /** 事件类型，可为空。 */
    private String eventType;
    /** 情绪倾向，可为空。 */
    private String sentiment;
    /** 分享使用的话题关键词，来自当前消息和相关上下文的提炼。 */
    private String topic;
    /** 置信度，0 到 100。 */
    private Integer confidence;
    /** 源语言。 */
    private String sourceLanguage;
    /** 最终弹幕正文，必须结合原文和上下文生成。 */
    private String content;
    /** 市场类型：SPOT（现货）/ FUTURE（合约）；无法判断时为空。 */
    private String marketType;
    /** AI 模型名称，便于日志排查。 */
    private String modelName;
}
