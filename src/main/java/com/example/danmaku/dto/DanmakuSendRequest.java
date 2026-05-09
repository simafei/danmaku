package com.example.danmaku.dto;

import lombok.Data;

@Data
/**
 * 调用外部弹幕服务的请求体。
 */
public class DanmakuSendRequest {
    /** 源 TG 消息 ID，作为幂等和排障依据。 */
    private Long rawMessageId;
    /** 目标交易对页面。 */
    private String symbol;
    /** 弹幕语言，直接使用原始 TG 群语言，不在生成弹幕链路做翻译。 */
    private String language;
    /** 最终展示给用户的弹幕正文。 */
    private String content;
    /** 事件类型。 */
    private String eventType;
    /** 情绪倾向。 */
    private String sentiment;
    /** 分享使用的话题关键词。 */
    private String topic;
    /** AI 分析置信度。 */
    private Integer confidence;
    /** 文案生成风格，例如 human_rewrite。 */
    private String contentStyle;
    /** 模板或生成策略 ID，便于回溯。 */
    private String templateId;
}
