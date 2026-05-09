package com.example.danmaku.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("tg_push_decision_log")
/**
 * 推送判定日志表。
 *
 * 记录每条消息为什么推送、丢弃或暂存，方便产品验收和排障。
 */
public class TgPushDecisionLog {
    /** 自增主键。 */
    private Long id;
    /** 原始消息 ID。 */
    private Long rawMessageId;
    /** 源语言。 */
    private String language;
    /** 判定归属的交易对。 */
    private String symbol;
    /** 事件类型。 */
    private String eventType;
    /** 情绪倾向。 */
    private String sentiment;
    /** 分享使用的话题关键词。 */
    private String topic;
    /** 最终判定：push、discard、hold。 */
    private String decision;
    /** 判定原因。 */
    private String decisionReason;
    /** Redis 去重 key。 */
    private String dedupeKey;
    /** 是否命中单币对限频。 */
    private Boolean rateLimited;
    /** 最终弹幕文案快照，可为空。 */
    private String finalContent;
    /** 创建时间。 */
    private LocalDateTime createdAt;

}
