package com.lbank.danmaku.job.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("danmaku_push_log")
/**
 * 弹幕推送日志表。
 *
 * 每次调用外部弹幕服务都会写入一条记录，用于幂等、审计和排障。
 */
public class DanmakuPushLog {
    /** 自增主键。 */
    private Long id;
    /** 原始消息 ID。 */
    private Long rawMessageId;
    /** 推送判定日志 ID。 */
    private Long decisionId;
    /** 目标交易对。 */
    private String symbol;
    /** 源语言。 */
    private String language;
    /** 分享使用的话题关键词。 */
    private String topic;
    /** 实际推送的弹幕正文。 */
    private String pushContent;
    /** 推送状态：success、failed。 */
    private String pushStatus;
    /** 外部弹幕服务响应内容。 */
    private String responseBody;
    /** 外部弹幕服务请求 ID。 */
    private String requestId;
    /** 推送时间。 */
    private LocalDateTime pushedAt;

}
