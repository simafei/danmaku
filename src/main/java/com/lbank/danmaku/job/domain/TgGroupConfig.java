package com.lbank.danmaku.job.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * TG 群采集配置表。
 */
@Data
@TableName("tg_group_config")
public class TgGroupConfig {
    /** 自增主键。 */
    private Long id;
    /** TG 群 ID，负整数。 */
    private Long groupId;
    /** 群名称，仅用于展示。 */
    private String groupName;
    /** 群主要语言代码，如 zh、en。 */
    private String language;
    /** 是否采集该群消息。 */
    private Boolean enabled;
    /** 是否允许该群内容推送弹幕（预留）。 */
    private Boolean pushEnabled;
    /** 排序权重（预留）。 */
    private Integer sortWeight;
    /** 群可信度等级（预留给风控）。 */
    private Integer trustLevel;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 最后更新时间。 */
    private LocalDateTime updateTime;
}
