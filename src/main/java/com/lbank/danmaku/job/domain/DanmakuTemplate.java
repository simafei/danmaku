package com.lbank.danmaku.job.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 弹幕模板库。
 *
 * 由 AI 批量预生成，用于向用户推荐弹幕选项。
 */
@Data
@TableName("danmaku_template")
public class DanmakuTemplate {
    /** 自增主键。 */
    private Long id;
    /** 适用的事件或交易对，如 BTCUSDT、美伊战争。 */
    private String matchedEvent;
    /** 语言代码，如 zh、en。 */
    private String language;
    /** 弹幕文案。 */
    private String content;
    /** 情绪倾向：bullish / bearish / neutral。 */
    private String sentiment;
    /** 事件类型：price / news / opinion / question / other。 */
    private String eventType;
    /** 市场类型：SPOT / FUTURE / 空（非加密货币时为空）。 */
    private String marketType;
    /** 被推荐次数。 */
    private Integer usedCount;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 最后更新时间。 */
    private LocalDateTime updateTime;
}
