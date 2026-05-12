package com.lbank.danmaku.job.dto;

import lombok.Data;

/**
 * 调用外部弹幕服务的请求体。
 */
@Data
public class DanmakuSendRequest {
    /** 源 TG 消息 ID，作为幂等和排障依据。 */
    private Long rawMessageId;
    /** 目标交易对页面。 */
    private String symbol;
    /** 消息源语言。 */
    private String language;
    /** 事件类型：price / news / opinion / question / other。 */
    private String eventType;
    /** 情绪倾向：bullish / bearish / neutral。 */
    private String sentiment;
    /** 结合上下文提炼的核心话题。 */
    private String topic;
    /** AI 分析置信度，0–100。 */
    private Integer confidence;
    /** 市场类型：SPOT / FUTURE；无法判断时为空。 */
    private String marketType;
}
