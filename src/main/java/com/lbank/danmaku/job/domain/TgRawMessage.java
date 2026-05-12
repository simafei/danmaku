package com.lbank.danmaku.job.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("tg_raw_message")
/**
 * TG 原始消息表。
 *
 * 只保存产品要求允许抓取的文本消息字段，用于审计、重试、AI 分析和回放。
 */
public class TgRawMessage {
    /** 自增主键。 */
    private Long id;
    /** Telegram update ID，用于 polling 幂等。 */
    private Long updateId;
    /** TG 群 ID。 */
    private Long groupId;
    /** TG 群名称。 */
    private String groupName;
    /** 群语言。 */
    private String language;
    /** 群内消息 ID。 */
    private Long messageId;
    /** 发送人用户 ID。 */
    private Long senderId;
    /** 发送人展示名。 */
    private String senderName;
    /** 发送人的名。 */
    private String senderFirstName;
    /** 发送人的姓。 */
    private String senderLastName;
    /** 发送人的 @username。 */
    private String senderUsername;
    /** 发送人是否群管理员。管理员消息本期不处理。 */
    private Boolean senderIsAdmin;
    /** 消息发送时间。 */
    private LocalDateTime sentAt;
    /** 原始文本。 */
    private String text;
    /** 标准化文本，用于去重、分析和检索。 */
    private String normalizedText;
    /** Telegram entities JSON，用于广告链接、@、#tag 等识别。 */
    private String entitiesJson;
    /** 被回复消息 ID。 */
    private Long replyToMessageId;
    /** 被回复消息文本快照。 */
    private String replyToText;
    /** 转发消息原始发布时间。 */
    private LocalDateTime forwardDate;
    /** 转发消息原始发送人 ID。 */
    private Long forwardFromId;
    /** 转发消息原始发送人 username。 */
    private String forwardFromUsername;
    /** 转发消息原始来源群或频道 ID。 */
    private Long forwardFromChatId;
    /** 文本中是否包含链接实体。 */
    private Boolean hasLink;
    /** 是否媒体消息。本期媒体消息应在入库前过滤。 */
    private Boolean hasMedia;
    /** 状态机状态：pending、processing、done、failed、discarded。 */
    private String ingestStatus;
    /** 已重试次数。 */
    private Integer retryCount;
    /** 下次可重试时间。 */
    private LocalDateTime nextRetryAt;
    /** 最近一次错误信息。 */
    private String lastError;
    /** worker 抢占开始处理的时间，用于超时回收。 */
    private LocalDateTime processingStartedAt;
    /** 入库时间。 */
    private LocalDateTime createTime;
    /** 最后更新时间。 */
    private LocalDateTime updateTime;

}
