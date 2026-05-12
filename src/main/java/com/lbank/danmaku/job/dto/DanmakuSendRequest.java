package com.lbank.danmaku.job.dto;

import lombok.Data;

/**
 * 调用外部弹幕服务的请求体。
 */
@Data
public class DanmakuSendRequest {
    /** 源 TG 消息 ID，作为幂等和排障依据。 */
    private Long rawMessageId;
    /** 匹配到的事件或交易对。 */
    private String matchedEvent;
    /** 消息源语言。 */
    private String language;
    /** 弹幕正文，即原始消息文本。 */
    private String content;
    /** AI 匹配置信度，0–100。 */
    private Integer confidence;
    /** 市场类型：SPOT / FUTURE；非加密货币或无法判断时为空。 */
    private String marketType;
}
