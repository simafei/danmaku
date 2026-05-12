-- Telegram 弹幕系统数据库建表脚本
-- 所有表统一包含 id、create_time、update_time 字段

-- ----------------------------
-- 1. tg_group_config：TG 群配置表
-- ----------------------------
create table if not exists tg_group_config (
  id              bigint          not null auto_increment comment '自增主键',
  group_id        bigint          not null               comment 'TG 群 ID，负整数，如 -1001234567890',
  group_name      varchar(255)                           comment '群名称，仅用于展示',
  language        varchar(16)     not null               comment '群主要语言代码，如 zh、en，影响消息语言标记',
  enabled         tinyint(1)      not null default 1     comment '是否采集该群消息：1=启用，0=禁用',
  push_enabled    tinyint(1)      not null default 1     comment '是否允许该群内容推送弹幕：1=允许，0=禁止（预留）',
  sort_weight     int             not null default 0     comment '排序权重，预留字段',
  trust_level     int             not null default 0     comment '群可信度等级，预留给风控和排序',
  create_time     datetime        not null default current_timestamp                          comment '创建时间',
  update_time     datetime        not null default current_timestamp on update current_timestamp comment '最后更新时间',
  primary key (id),
  unique key uk_group_id (group_id),
  key idx_enabled (enabled)
) engine=InnoDB default charset=utf8mb4 comment='TG 群采集配置表';


-- ----------------------------
-- 2. tg_raw_message：TG 原始消息表
-- ----------------------------
create table if not exists tg_raw_message (
  id                      bigint        not null auto_increment  comment '自增主键',
  update_id               bigint        not null                 comment 'Telegram update ID，用于 polling 幂等去重',
  group_id                bigint        not null                 comment 'TG 群 ID',
  group_name              varchar(255)                           comment 'TG 群名称快照',
  language                varchar(16)   not null                 comment '群语言代码',
  message_id              bigint        not null                 comment '群内消息 ID，与 group_id 联合唯一',
  sender_id               bigint                                 comment '发送人用户 ID',
  sender_name             varchar(255)                           comment '发送人展示名（first + last 拼接）',
  sender_first_name       varchar(255)                           comment '发送人 first name',
  sender_last_name        varchar(255)                           comment '发送人 last name',
  sender_username         varchar(255)                           comment '发送人 @username，无则为空',
  sender_is_admin         tinyint(1)    not null default 0       comment '发送人是否群管理员：1=是，0=否',
  sent_at                 datetime                               comment '消息发送时间（TG 服务器时间转 Asia/Shanghai）',
  text                    text                                   comment '原始消息文本',
  normalized_text         text                                   comment '标准化文本：trim + 合并连续空白，用于分析和推送',
  entities_json           json                                   comment 'Telegram message entities，含链接、mention、#tag 等',
  reply_to_message_id     bigint                                 comment '被回复消息的 message_id，非回复消息为空',
  reply_to_text           text                                   comment '被回复消息文本快照，用于 AI 上下文理解',
  forward_date            datetime                               comment '转发消息的原始发布时间',
  forward_from_id         bigint                                 comment '转发来源用户 ID（MessageOriginUser）',
  forward_from_username   varchar(255)                           comment '转发来源用户 username（MessageOriginHiddenUser）',
  forward_from_chat_id    bigint                                 comment '转发来源群或频道 ID（MessageOriginChat/Channel）',
  has_link                tinyint(1)    not null default 0       comment '文本中是否含链接实体：1=有，0=无',
  has_media               tinyint(1)    not null default 0       comment '是否含媒体（图片/视频/语音等）：1=有，0=无',
  ingest_status           varchar(32)   not null                 comment '处理状态：PENDING / PROCESSING / DONE / FAILED',
  retry_count             int           not null default 0       comment '已重试次数，含超时恢复计数',
  next_retry_at           datetime                               comment '下次可重试时间，重试时更新',
  last_error              text                                   comment '最近一次错误信息（e.toString()）',
  processing_started_at   datetime                               comment 'Worker 抢占时间，用于超时恢复判断',
  create_time             datetime      not null default current_timestamp                          comment '入库时间',
  update_time             datetime      not null default current_timestamp on update current_timestamp comment '最后更新时间',
  primary key (id),
  unique key uk_group_message (group_id, message_id),
  unique key uk_update_id (update_id),
  key idx_status_retry (ingest_status, next_retry_at),
  key idx_group_sent (group_id, sent_at),
  key idx_language_sent (language, sent_at)
) engine=InnoDB default charset=utf8mb4 comment='TG 原始消息表，保存所有符合条件的文本消息，支持审计、重试和 AI 回放';


-- ----------------------------
-- 3. tg_push_decision_log：推送判定日志表
-- ----------------------------
create table if not exists tg_push_decision_log (
  id               bigint        not null auto_increment  comment '自增主键',
  raw_message_id   bigint        not null                 comment '关联的原始消息 ID',
  language         varchar(16)                            comment '消息源语言代码',
  matched_event    varchar(128)                           comment 'AI 从事件列表中匹配到的事件或交易对，无匹配时为空',
  topic            varchar(255)                           comment 'AI 提炼的具体话题描述，6–20 字',
  decision         varchar(16)   not null                 comment '判定结果：PUSH / DISCARD / HOLD',
  decision_reason  varchar(128)                           comment '判定原因，如 ad、not_displayable、no_matched_event、content_too_long 等',
  dedupe_key       varchar(128)                           comment 'Redis 去重 key（SHA-256），PUSH 路径才有值',
  rate_limited     tinyint(1)    not null default 0       comment '是否命中事件限频：1=是，0=否',
  create_time      datetime      not null default current_timestamp                          comment '判定时间',
  update_time      datetime      not null default current_timestamp on update current_timestamp comment '最后更新时间',
  primary key (id),
  key idx_raw_message (raw_message_id),
  key idx_decision_created (decision, create_time),
  key idx_matched_event_created (matched_event, create_time)
) engine=InnoDB default charset=utf8mb4 comment='推送判定日志表，每条消息处理后必写，用于排查和规则迭代';


-- ----------------------------
-- 4. danmaku_push_log：弹幕推送日志表
-- ----------------------------
create table if not exists danmaku_push_log (
  id              bigint        not null auto_increment  comment '自增主键',
  raw_message_id  bigint        not null                 comment '关联的原始消息 ID',
  decision_id     bigint                                 comment '关联的推送判定日志 ID',
  matched_event   varchar(128)                           comment '推送目标事件或交易对',
  language        varchar(16)                            comment '消息源语言代码',
  topic           varchar(255)                           comment 'AI 提炼的话题描述',
  push_content    text                                   comment '实际推送的弹幕正文（原始消息文本）',
  push_status     varchar(32)                            comment '推送状态：success / failed',
  response_body   text                                   comment '外部弹幕服务的响应体',
  request_id      varchar(64)                            comment '外部弹幕服务返回的请求 ID，用于排障',
  create_time     datetime      not null default current_timestamp                          comment '推送时间',
  update_time     datetime      not null default current_timestamp on update current_timestamp comment '最后更新时间',
  primary key (id),
  key idx_raw_message (raw_message_id),
  key idx_decision (decision_id),
  key idx_matched_event_created (matched_event, create_time)
) engine=InnoDB default charset=utf8mb4 comment='弹幕推送日志表，仅 PUSH 决策时写入，记录推送内容和接口响应';


-- ----------------------------
-- 5. danmaku_template：弹幕模板库
-- ----------------------------
create table if not exists danmaku_template (
  id             bigint        not null auto_increment  comment '自增主键',
  matched_event  varchar(128)  not null                 comment '适用的事件或交易对，如 BTCUSDT、美伊战争',
  language       varchar(16)   not null default 'zh'   comment '语言代码，如 zh、en',
  content        varchar(200)  not null                 comment '弹幕文案',
  sentiment      varchar(16)                            comment '情绪倾向：bullish / bearish / neutral',
  event_type     varchar(32)                            comment '事件类型：price / news / opinion / question / other',
  market_type    varchar(16)                            comment '市场类型：SPOT / FUTURE / 空（非加密货币时为空）',
  used_count     int           not null default 0       comment '被推荐次数，用于后续热度统计',
  create_time    datetime      not null default current_timestamp                          comment '创建时间',
  update_time    datetime      not null default current_timestamp on update current_timestamp comment '最后更新时间',
  primary key (id),
  unique key uk_event_lang_content (matched_event, language, content(100)),
  key idx_event_lang (matched_event, language)
) engine=InnoDB default charset=utf8mb4 comment='弹幕模板库，由 AI 批量预生成，用于向用户推荐弹幕选项';
