package com.example.danmaku.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("tg_group_config")
/**
 * TG 群配置表。
 */
public class TgGroupConfig {
    /** 自增主键。 */
    private Long id;
    /** TG 群 ID。 */
    private Long groupId;
    /** 群名称。 */
    private String groupName;
    /** 群语言。 */
    private String language;
    /** 是否启用该群。 */
    private Boolean enabled;
    /** 排序权重，不代表消息推送优先级。 */
    private Integer sortWeight;
    /** 群可信度，预留给后续风控和排序。 */
    private Integer trustLevel;
    /** 该群允许重点覆盖的交易对 JSON。 */
    private String allowedSymbolsJson;
    /** 是否允许该群内容推送到弹幕。 */
    private Boolean pushEnabled;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 更新时间。 */
    private LocalDateTime updatedAt;

}
