package com.lbank.danmaku.job.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 推送判定日志表。
 *
 * 每条消息处理后必写，无论推送还是丢弃，用于排查和规则迭代。
 */
@Data
@TableName("tg_push_decision_log")
public class TgPushDecisionLog {
    /** 自增主键。 */
    private Long id;
    /** 关联的原始消息 ID。 */
    private Long rawMessageId;
    /** 消息源语言代码。 */
    private String language;
    /** AI 从事件列表中匹配到的事件或交易对，无匹配时为空。 */
    private String matchedEvent;
    /** AI 提炼的具体话题描述。 */
    private String topic;
    /** 判定结果：PUSH / DISCARD / HOLD。 */
    private String decision;
    /** 判定原因。 */
    private String decisionReason;
    /** Redis 去重 key，PUSH 路径才有值。 */
    private String dedupeKey;
    /** 是否命中事件限频。 */
    private Boolean rateLimited;
    /** 判定时间。 */
    private LocalDateTime createTime;
    /** 最后更新时间。 */
    private LocalDateTime updateTime;
}
