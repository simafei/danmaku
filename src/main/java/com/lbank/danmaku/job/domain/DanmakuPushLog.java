package com.lbank.danmaku.job.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 弹幕推送日志表。
 *
 * 仅 PUSH 决策时写入，记录推送内容和接口响应，用于幂等、审计和排障。
 */
@Data
@TableName("danmaku_push_log")
public class DanmakuPushLog {
    /** 自增主键。 */
    private Long id;
    /** 关联的原始消息 ID。 */
    private Long rawMessageId;
    /** 关联的推送判定日志 ID。 */
    private Long decisionId;
    /** 推送目标事件或交易对。 */
    private String matchedEvent;
    /** 消息源语言代码。 */
    private String language;
    /** AI 提炼的话题描述。 */
    private String topic;
    /** 实际推送的弹幕正文（原始消息文本）。 */
    private String pushContent;
    /** 推送状态：success / failed。 */
    private String pushStatus;
    /** 外部弹幕服务响应体。 */
    private String responseBody;
    /** 外部弹幕服务返回的请求 ID，用于排障。 */
    private String requestId;
    /** 推送时间。 */
    private LocalDateTime createTime;
    /** 最后更新时间。 */
    private LocalDateTime updateTime;
}
