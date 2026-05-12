# Telegram 弹幕系统技术设计文档

> 本文档基于当前实际代码编写，是唯一有效的技术参考文档，替代所有历史版本设计文档。

---

## 1. 背景与目标

将平台 Telegram 群的实时讨论，作为弹幕推送到对应页面（K 线页、赛事页等）。

- **输入**：自有 TG 官方群的文本消息
- **处理**：AI 过滤噪声，匹配当前活跃事件，识别话题
- **输出**：以原始消息文本为内容，调用外部弹幕接口推送到对应事件页面

**核心原则：**
- 弹幕内容 = 原始消息文本，不做 AI 改写
- 事件列表由业务方动态提供，不限加密货币，可以是世界杯、宏观事件等任何话题
- 每条消息独立判断，只有通过过滤、成功匹配到事件且原文字数不超限才推送

---

## 2. 整体架构

```
TG 群 → Long Polling Bot → TelegramMessageCollector → tg_raw_message (PENDING)
                                                               ↓
                                                    TelegramMessageWorker（XXL-JOB 驱动）
                                                               ↓
                                                    ContextMessageService（加载上下文）
                                                               ↓
                                          EventProvider.getActiveEvents()（获取事件列表）
                                                               ↓
                                                    AiDanmakuService（过滤 + 事件匹配）
                                                               ↓
                                                    PushDecisionService（推送判定）
                                                               ↓
                                                    DanmakuPushService（推送原文 + 记录日志）
```

---

## 3. 消息接入层

### 3.1 接入方式

使用 **Telegram Bot SDK Long Polling**（telegrambots 9.5.0），服务主动向 Telegram 拉取 update，不需要公网回调地址。

实现类：`TelegramPollingBot`，由 `telegrambots-springboot-longpolling-starter` 自动管理 polling 生命周期。

### 3.2 接入前提

1. 通过 BotFather 创建 Bot，获取 `BOT_TOKEN`
2. 将 Bot 加入目标官方群
3. **关闭 BotFather 的 Privacy Mode**（否则 Bot 只能收到命令消息，拿不到普通群聊内容）

### 3.3 消息过滤规则

`TelegramMessageCollector.isCollectable()` 在入库前做轻过滤：

| 过滤条件 | 说明 |
|---------|------|
| 非文本消息 | `hasText() == false` 的消息直接丢弃，含图片、视频、语音、文件 |
| 带媒体的消息 | `hasPhoto / hasVideo / hasVoice / hasDocument` 为 true 时丢弃 |
| Bot 发送的消息 | `sender.getIsBot() == true` 时丢弃 |
| 管理员消息 | Redis 缓存中 `tg:admin:{groupId}:{userId} = 1` 时丢弃 |
| 未配置的群 | `tg_group_config` 中不存在或 `enabled=false` 的群丢弃 |

**只处理 `update.hasMessage()` 类型的 update**，不处理频道帖子、回调等其他类型。

### 3.4 转发消息处理

使用 Bot API 7.0 的 `forward_origin` 字段（`message.getForwardOrigin()`），覆盖四种转发来源：

| 类型 | 存储字段 |
|------|---------|
| `MessageOriginUser`（来自普通用户）| `forward_from_id` |
| `MessageOriginHiddenUser`（隐藏资料用户）| `forward_from_username` |
| `MessageOriginChat`（来自群组）| `forward_from_chat_id` |
| `MessageOriginChannel`（来自频道）| `forward_from_chat_id` |

### 3.5 幂等保证

- 主唯一键：`uk_tg_raw_group_message (group_id, message_id)`
- 辅助唯一键：`uk_tg_raw_update_id (update_id)`（`NOT NULL`）
- Long Polling 重启后可能拿到重复 update，`saveRawMessage()` 捕获 `DuplicateKeyException` 静默忽略

### 3.6 管理员缓存同步

`AdminSyncService` 通过 Telegram `getChatAdministrators` API 拉取管理员列表，写入 Redis：

- Key：`tg:admin:{groupId}:{userId}`，值 `"1"`，TTL 2 小时
- 启动时自动同步（`@EventListener(ApplicationReadyEvent.class)`）
- 支持 XXL-JOB 定时刷新（JobHandler：`adminSyncJobHandler`）
- 支持 `POST /admin/groups/{groupId}/admins/sync` 手动触发

---

## 4. Worker 处理层

### 4.1 触发机制

`TelegramMessageWorker.tick()` 由 **XXL-JOB** 驱动（JobHandler：`danmakuWorkerJobHandler`），在 XXL-JOB Admin 配置调度频率。不再使用 Spring `@Scheduled`。

### 4.2 处理流程

```
recoverTimedOut()               // 超时消息回收
    ↓
selectList(PENDING, limit 50)   // 批量查询
    ↓
claim(id)                       // 乐观抢占：UPDATE WHERE status=PENDING → PROCESSING
    ↓
process(id)
    ├─ loadNearbyContext()       // 同群 10 分钟内近 20 条消息
    ├─ aiDanmakuService.generate()
    ├─ decisionService.decide()
    └─ pushService.push()        // 仅 PUSH 决策时调用
    ↓
markDone() 或 markRetryOrFailed()
```

### 4.3 状态机

```
PENDING → PROCESSING → DONE
                    ↘
                    PENDING（重试，next_retry_at = now+30s）
                    ↘
                    FAILED（超过 maxRetry）
```

状态定义见 `IngestStatus` 枚举。

### 4.4 重试与超时

| 参数 | 默认值 | 配置键 |
|------|--------|-------|
| 最大重试次数 | 3 | `danmaku.worker.max-retry` |
| 重试间隔 | 30s | 代码固定 |
| 处理超时 | 5 分钟 | `danmaku.worker.processing-timeout-minutes` |
| 批次大小 | 50 | `danmaku.worker.batch-size` |

**超时恢复**：`recoverTimedOut()` 每次 tick 开始时执行，将 `PROCESSING` 且 `processing_started_at <= now-5min` 的消息重置。超时同样计入 `retry_count`，超过上限后标记为 `FAILED`。

---

## 5. AI 服务层

### 5.1 职责

AI **不生成弹幕文案**，只做两件事：

1. **过滤**：识别广告、推广、无意义噪声（纯表情/寒暄），标记 `displayable=false`
2. **事件匹配**：从 `EventProvider` 提供的事件列表中选出与消息最相关的一项，输出为 `matchedEvent`

弹幕内容直接使用原始消息文本（`normalizedText`），不经过 AI 改写。

### 5.2 EventProvider 接口

```java
public interface EventProvider {
    List<String> getActiveEvents();
}
```

- 事件列表可以是任何话题，不限加密货币：`["BTCUSDT", "美伊战争", "2026年世界杯", ...]`
- 实现类负责从数据库、配置中心或外部 API 获取当前活跃事件
- 默认 `StubEventProvider` 返回空列表，业务方实现 Bean 后自动替换（`@ConditionalOnMissingBean`）

### 5.3 AI 客户端接口

`AiDanmakuClient` 对接 OpenAI 兼容接口，只传 `model`、`systemPrompt`、`userPrompt`、`temperature`、`responseFormat`。

内置实现 `OpenAiCompatibleDanmakuClient`：

- 条件：`danmaku.ai.api-key` 不为空时生效（`@ConditionalOnExpression`）
- 调用 `{baseUrl}/chat/completions`，Bearer Auth
- 兜底：`StubAiDanmakuClient`（`@ConditionalOnMissingBean`），返回 `displayable=false`，不会误推送

### 5.4 Prompt 构造

**System Prompt** 固定，包含：过滤规则、matchedEvent 选取规则（必须原文选取不得拼造）、marketType 判断规则、输出格式。

**User Prompt** 动态生成，包含三部分：

```
【事件列表】
- BTCUSDT
- 美伊战争
- 2026年世界杯
...

【当前消息】
user=xxx, text=美伊局势紧张BTC跌了5%
  ↳ 回复的消息：（如有）

【近期上下文，按时间从近到远】
- [2分钟前] user=yyy, text=...
- [5分钟前] user=zzz, text=...
```

### 5.5 AI 输出字段

```json
{
  "ad": false,
  "adReason": "",
  "displayable": true,
  "matchedEvent": "美伊战争",
  "topic": "美伊局势引发BTC暴跌",
  "marketType": "SPOT",
  "confidence": 85,
  "sourceLanguage": "zh"
}
```

| 字段 | 说明 |
|------|------|
| `displayable` | 是否通过过滤（false = 广告或无意义噪声） |
| `ad` / `adReason` | 是否广告及原因 |
| `matchedEvent` | 从事件列表原文选取的匹配项；无匹配时为空 |
| `topic` | AI 提炼的具体话题，6–20 字，如"美伊局势引发BTC暴跌" |
| `marketType` | `SPOT` / `FUTURE` / 空；仅加密货币交易语境下填写 |
| `confidence` | 对 matchedEvent 选择的把握程度，0–100 |
| `sourceLanguage` | 消息语言代码 |

**`matchedEvent` 与 `topic` 的关系：**
- `matchedEvent` 是结构化标识，用于路由到哪个事件页面（来自事件列表）
- `topic` 是内容描述，告诉用户正在讨论什么（AI 自由生成）
- 例：`matchedEvent=美伊战争`，`topic=美伊局势引发BTC暴跌`

### 5.6 异常兜底

| 情况 | 处理 |
|------|------|
| AI 无响应或空响应 | 返回 `hold(empty_ai_response)` |
| JSON 解析失败 | 返回 `hold(invalid_ai_json)` |
| `sourceLanguage` 为空 | 填充 `rawMessage.language` |
| `modelName` 为空 | 填充 AI 响应中的 model 名 |

---

## 6. 推送判定层

### 6.1 判定顺序

`PushDecisionService.decide()` 按以下顺序判断，命中即返回：

```
1. rejectReason()     → DISCARD（规则直接拒绝）
2. Redis dedup        → HOLD（相同事件下相同文本，短时间内重复）
3. Redis rate limit   → HOLD（同事件限频）
4.                    → PUSH
```

### 6.2 DISCARD 规则（rejectReason）

| 原因 | 触发条件 |
|------|---------|
| `admin_message` | `rawMessage.senderIsAdmin = true` |
| `non_text_message` | `rawMessage.hasMedia = true` |
| `ad:{reason}` | `aiResult.isAd() = true` |
| `not_displayable` | `aiResult.isDisplayable() = false`（广告或无意义噪声） |
| `no_matched_event` | `aiResult.matchedEvent` 为空（未匹配任何事件） |
| `low_confidence` | `aiResult.confidence < minConfidence`（默认 50） |
| `content_too_long` | 原始消息字数 > `maxContentLength`（默认 30） |

### 6.3 去重（Redis）

- Key：`tg:dedupe:` + SHA-256(`matchedEvent:normalizedText`)
- TTL：60 秒（`danmaku.decision.duplicate-ttl-seconds`）
- 使用 `setIfAbsent`（原子操作），命中则 `HOLD(duplicate_similar_content)`

### 6.4 限频（Redis）

- Key：`tg:rate:event:{matchedEvent}`
- TTL：15 秒（`danmaku.decision.symbol-rate-limit-seconds`）
- 命中则 `HOLD(event_rate_limited)`
- 仅在通过去重后才检查限频

### 6.5 判定日志

每条消息无论结果如何都写入 `tg_push_decision_log`，记录 decision、decisionReason、dedupeKey、rateLimited、symbol（存 matchedEvent）、topic 等，用于排查和规则迭代。

---

## 7. 弹幕推送层

`DanmakuPushService.push()` 仅在 `PushDecisionService` 返回 `PUSH` 时调用。

### 7.1 推送内容

**弹幕正文直接使用原始消息的 `normalizedText`**，不经过 AI 改写。

推送请求字段：

```json
{
  "rawMessageId": 123,
  "matchedEvent": "美伊战争",
  "language": "zh",
  "content": "美伊局势这么紧张BTC真跌了",
  "topic": "美伊局势引发BTC暴跌",
  "marketType": "SPOT",
  "confidence": 85
}
```

### 7.2 推送接口

`DanmakuSenderClient` 是外部接口，`StubDanmakuSenderClient` 为空实现兜底。

### 7.3 推送日志

推送结果（成功/失败、responseBody、requestId）写入 `danmaku_push_log`，关联 `raw_message_id` 和 `decision_id`。

---

## 8. 群配置管理

### 8.1 配置表

`tg_group_config` 核心字段：

| 字段 | 说明 |
|------|------|
| `group_id` | TG 群 ID，负整数（如 `-1001234567890`） |
| `language` | 群主要语言，如 `zh`、`en` |
| `enabled` | 是否采集该群消息 |
| `push_enabled` | 是否允许推送弹幕（预留） |

### 8.2 管理接口

`GroupConfigController` 提供以下接口：

```
GET    /admin/groups                          # 查询所有群配置
POST   /admin/groups                          # 新增群配置（同时自动同步管理员缓存）
PATCH  /admin/groups/{groupId}/enabled        # 启用或禁用某个群
POST   /admin/groups/{groupId}/admins/sync    # 手动刷新管理员缓存
```

POST 新增群请求体：

```json
{
  "groupId": -1001234567890,
  "groupName": "BTC讨论群",
  "language": "zh",
  "enabled": true,
  "pushEnabled": true
}
```

重复注册同一 `groupId` 返回 `409 Conflict`。

### 8.3 获取群 ID

Bot 加入群后，调用以下接口获取 `chat.id`：

```bash
curl "https://api.telegram.org/bot<BOT_TOKEN>/getUpdates"
```

---

## 9. 数据库表结构

### 9.1 `tg_group_config` — 群配置表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 自增 |
| group_id | bigint UNIQUE | TG 群 ID |
| language | varchar(16) | 群语言 |
| enabled | tinyint(1) | 是否采集 |
| push_enabled | tinyint(1) | 是否推弹幕 |
| allowed_symbols_json | json | 允许覆盖的币种（预留） |

### 9.2 `tg_raw_message` — 原始消息表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 自增 |
| update_id | bigint NOT NULL UNIQUE | TG update ID |
| group_id + message_id | UNIQUE | 消息幂等键 |
| ingest_status | varchar(32) | PENDING / PROCESSING / DONE / FAILED |
| retry_count | int | 重试次数（含超时恢复） |
| next_retry_at | datetime | 下次重试时间 |
| processing_started_at | datetime | 抢占时间，用于超时判断 |
| last_error | text | 最近一次错误信息 |
| normalized_text | text | trim + 合并空白后的文本 |
| has_link | tinyint(1) | 是否含链接类型实体 |

### 9.3 `tg_push_decision_log` — 推送判定日志表

每条消息处理后必写，无论推送还是丢弃。

关键字段：`decision`、`decision_reason`、`dedupe_key`、`rate_limited`、`symbol`（存 matchedEvent）、`topic`。

### 9.4 `danmaku_push_log` — 弹幕推送日志表

仅 PUSH 决策时写入，记录推送内容（原始消息文本）、状态、接口响应。

---

## 10. 配置说明

### 10.1 环境变量

| 变量 | 必填 | 说明 |
|------|------|------|
| `TELEGRAM_BOT_TOKEN` | polling 实例必填 | Bot Token |
| `AI_API_KEY` | 推荐 | 不配置则使用 Stub，不会推送 |
| `AI_BASE_URL` | 与 API_KEY 配套 | OpenAI 兼容接口地址 |
| `AI_MODEL` | 与 API_KEY 配套 | 模型名，如 `qwen-turbo`、`gpt-4o-mini` |

### 10.2 完整配置项

```yaml
danmaku:
  telegram:
    bot-token: ${TELEGRAM_BOT_TOKEN:}
    polling:
      enabled: true          # false 则该实例不启动 polling
  worker:
    enabled: true            # false 则该实例不处理消息
    batch-size: 50           # 每次处理消息数
    max-retry: 3             # 最大重试次数（含超时恢复）
    processing-timeout-minutes: 5  # 超时恢复阈值
  decision:
    min-confidence: 50           # AI 置信度下限
    symbol-rate-limit-seconds: 15 # 同事件限频窗口（秒）
    duplicate-ttl-seconds: 60    # 去重 TTL（秒）
    max-content-length: 30       # 原始消息字数上限，超过不推送
  ai:
    base-url: ${AI_BASE_URL:}
    api-key: ${AI_API_KEY:}
    model: ${AI_MODEL:}
    temperature: 0.4
    response-format: json_object

xxl:
  job:
    admin:
      addresses: ${XXL_JOB_ADMIN_ADDRESSES:http://localhost:8090/xxl-job-admin}
    accessToken: ${XXL_JOB_ACCESS_TOKEN:default_token}
    executor:
      appname: danmaku-executor
      port: ${XXL_JOB_EXECUTOR_PORT:9999}
      logpath: ${XXL_JOB_LOG_PATH:/data/applogs/xxl-job}
      logretentiondays: 30
```

### 10.3 XXL-JOB 任务配置

| JobHandler | 说明 | 建议调度 |
|------------|------|---------|
| `danmakuWorkerJobHandler` | 消息处理主循环 | 固定间隔 3s |
| `adminSyncJobHandler` | 管理员缓存刷新 | 每 1 小时 |

---

## 11. 多实例部署

### 11.1 角色划分

| 角色 | polling.enabled | worker.enabled | 实例数 |
|------|----------------|----------------|--------|
| Collector | true | true | **必须且只能 1 台** |
| Worker | false | true | 可横向扩展 |

**Polling 不能多实例**：Telegram Long Polling 的 offset 在 SDK 内存中维护，多实例并发 poll 同一 token 会导致 update 被随机分发到不同实例。

**Worker 天然多实例安全**：`claim()` 用 `UPDATE WHERE status=PENDING` 实现乐观抢占，Redis 用于跨实例共享去重和限频状态。

### 11.2 Docker Compose 示例

```yaml
services:
  collector:
    image: danmaku:latest
    environment:
      - TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}
      - AI_API_KEY=${AI_API_KEY}
      - AI_BASE_URL=${AI_BASE_URL}
      - AI_MODEL=${AI_MODEL}
      - DANMAKU_TELEGRAM_POLLING_ENABLED=true
      - DANMAKU_WORKER_ENABLED=true
    deploy:
      replicas: 1        # 固定 1

  worker:
    image: danmaku:latest
    environment:
      - AI_API_KEY=${AI_API_KEY}
      - AI_BASE_URL=${AI_BASE_URL}
      - AI_MODEL=${AI_MODEL}
      - DANMAKU_TELEGRAM_POLLING_ENABLED=false
      - DANMAKU_WORKER_ENABLED=true
    deploy:
      replicas: 3        # 按负载调整
```

---

## 12. 外部依赖接入

当前所有外部接口均有 Stub 实现，服务可独立启动和测试。正式接入时实现对应 Bean 即可。

### 12.1 `EventProvider` — 事件列表

```java
@Service
public class RealEventProvider implements EventProvider {
    @Override
    public List<String> getActiveEvents() {
        // 从数据库、配置中心或外部 API 获取当前活跃事件列表
        // 例如：["BTCUSDT", "ETHUSDT", "美伊战争", "2026年世界杯"]
    }
}
```

### 12.2 `AiDanmakuClient` — AI 接口

内置 `OpenAiCompatibleDanmakuClient`，配置 `AI_API_KEY` 后自动激活，支持所有 OpenAI 兼容接口（阿里百炼、Azure OpenAI 等）。

需要切换模型时修改环境变量 `AI_MODEL` 即可，无需改代码。

### 12.3 `DanmakuSenderClient` — 弹幕推送接口

```java
@Service
public class RealDanmakuSenderClient implements DanmakuSenderClient {
    @Override
    public DanmakuSendResult send(DanmakuSendRequest request) {
        // 调用现有弹幕推送接口
        // request 包含：matchedEvent、content（原始消息文本）、topic、marketType 等
    }
}
```

---

## 13. 常见问题排查

| 现象 | 排查方向 |
|------|---------|
| Bot 只收到命令消息，收不到普通聊天 | 检查 BotFather 是否关闭了 Privacy Mode |
| 某个群消息没有入库 | 查 `tg_group_config` 该群是否存在且 `enabled=1` |
| 消息入库但没有推送判定日志 | 查 `tg_raw_message.ingest_status`，确认 worker 是否启用，XXL-JOB 任务是否运行 |
| 有判定日志但 decision 都是 DISCARD | 查 `decision_reason`：`no_matched_event` 说明事件列表为空或无匹配；`content_too_long` 说明消息超过字数限制 |
| 有判定日志但 decision 都是 HOLD | 查 `decision_reason`：`duplicate_similar_content` 或 `event_rate_limited`，属正常限流行为 |
| `last_error` 为 `processing_timeout` | AI 接口响应超过 5 分钟，检查 AI 服务可用性或调大 `processing-timeout-minutes` |
| 推送弹幕但 `matchedEvent` 都为空 | `EventProvider` 未实现真实数据源，Stub 返回空列表，所有消息会被 `no_matched_event` 拒绝 |
| 转发消息 `forward_date` 为 null | 该消息非转发消息，属正常情况 |
