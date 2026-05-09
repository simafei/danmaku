# Telegram 弹幕系统技术设计文档

> 本文档基于当前实际代码编写，是唯一有效的技术参考文档，替代所有历史版本设计文档。

---

## 1. 背景与目标

将平台自有 Telegram 官方群的实时讨论，转化为 K 线页面的情绪弹幕。

- 输入：自有 TG 官方群的文本消息
- 处理：AI 判断价值，生成口语化弹幕
- 输出：调用外部弹幕接口，推送到对应币对页面

**核心原则：** 每条消息独立判断，只有有明确币对且内容有价值的消息才推送。弹幕文案使用原始群语言，不做翻译。

---

## 2. 整体架构

```
TG 群 → Long Polling Bot → TelegramMessageCollector → tg_raw_message (PENDING)
                                                               ↓
                                                    TelegramMessageWorker (定时扫描)
                                                               ↓
                                                    ContextMessageService (加载上下文)
                                                               ↓
                                                    AiDanmakuService (AI 生成弹幕)
                                                               ↓
                                                    PushDecisionService (推送判定)
                                                               ↓
                                                    DanmakuPushService (推送 + 记录日志)
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

使用 Bot API 7.0 引入的 `forward_origin` 字段（对应 SDK 的 `message.getForwardOrigin()`），覆盖四种转发来源：

| 类型 | 存储字段 |
|------|---------|
| `MessageOriginUser`（来自普通用户）| `forward_from_id`、`forward_from_username` |
| `MessageOriginHiddenUser`（隐藏资料用户）| `forward_from_username` |
| `MessageOriginChat`（来自群组）| `forward_from_chat_id` |
| `MessageOriginChannel`（来自频道）| `forward_from_chat_id` |

### 3.5 幂等保证

- 主唯一键：`uk_tg_raw_group_message (group_id, message_id)`
- 辅助唯一键：`uk_tg_raw_update_id (update_id)`（`NOT NULL`）
- Long Polling 重启后可能拿到重复 update，`saveRawMessage()` 捕获 `DuplicateKeyException` 静默忽略

### 3.6 时间处理

所有时间字段（`sent_at`、`forward_date`）统一使用 `Asia/Shanghai` 时区，与 `application.yml` 中 MySQL `serverTimezone=Asia/Shanghai` 保持一致。

### 3.7 管理员缓存

`AdminCacheService` 从 Redis 读取 `tg:admin:{groupId}:{userId}`，值为 `1` 则认为是管理员。**当前无写入逻辑，需要外部系统或手动写入 Redis。**

---

## 4. Worker 处理层

### 4.1 触发机制

`TelegramMessageWorker.tick()` 由 Spring `@Scheduled` 驱动，`fixedDelay = 3000ms`（上一次执行完成后等 3 秒再启动下一次）。

### 4.2 处理流程

```
recoverTimedOut()          // 超时消息回收
    ↓
selectList(PENDING, limit 50)  // 批量查询
    ↓
claim(id)                  // 乐观抢占：UPDATE WHERE status=PENDING → PROCESSING
    ↓
process(id)
    ├─ loadNearbyContext()  // 同群 10 分钟内近 20 条消息
    ├─ aiDanmakuService.generate()
    ├─ decisionService.decide()
    └─ pushService.push()  // 仅 PUSH 决策时调用
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

**超时恢复**：`recoverTimedOut()` 每次 tick 开始时执行，将 `PROCESSING` 且 `processing_started_at <= now-5min` 的消息重置。超时同样计入 `retry_count`，超过上限后标记为 `FAILED`，避免无限超时循环。

失败原因写入 `last_error` 字段，格式为 `e.toString()`（保证非 null），同时记录 error 级别日志。

---

## 5. AI 服务层

### 5.1 接口设计

`AiDanmakuClient` 是 OpenAI 兼容接口，只传递 `model`、`systemPrompt`、`userPrompt`、`temperature`、`responseFormat`，不绑定业务对象。

**当前无真实实现**，`StubAiDanmakuClient`（`@ConditionalOnMissingBean`）兜底返回 `hold`，不会误推送。接真实 AI 时实现 `AiDanmakuClient` Bean 即可，Stub 自动失效。

### 5.2 Prompt 构造

**System Prompt** 固定，包含：过滤规则（广告、水聊、非文字）、币对识别规则、语言规则（弹幕必须使用原始群语言）、输出格式要求。

**User Prompt** 包含：
- 弹幕语言（来自 `tg_group_config.language`）
- 当前消息（messageId、language、username、normalizedText）
- 上下文消息列表（同群 10 分钟内最近 20 条，按时间由近到远）

### 5.3 AI 输出字段

```json
{
  "decision": "push | discard | hold",
  "decisionReason": "简短原因",
  "ad": false,
  "adReason": "",
  "displayable": true,
  "symbol": "BTCUSDT",
  "eventType": "price | news | opinion | question | other",
  "sentiment": "bullish | bearish | neutral",
  "topic": "话题关键词，4-12字",
  "confidence": 82,
  "sourceLanguage": "zh",
  "content": "最终弹幕文案"
}
```

### 5.4 异常兜底

| 情况 | 处理 |
|------|------|
| AI 无响应或空响应 | 返回 `hold(empty_ai_response)` |
| JSON 解析失败 | 返回 `hold(invalid_ai_json)` |
| `content` 字段为空 | 强制改为 `hold(empty_content)`，不以原始文本兜底推送 |
| `sourceLanguage` 为空 | 填充 `rawMessage.language` |
| `modelName` 为空 | 填充 AI 响应中的 model 名 |

---

## 6. 推送判定层

### 6.1 判定顺序

`PushDecisionService.decide()` 按以下顺序判断，命中即返回：

```
1. rejectReason()     → DISCARD（规则直接拒绝）
2. Redis dedup        → HOLD（近似重复内容）
3. Redis rate limit   → HOLD（单币对限频）
4.                    → PUSH
```

### 6.2 DISCARD 规则（rejectReason）

| 原因 | 触发条件 |
|------|---------|
| `admin_message` | `rawMessage.senderIsAdmin = true`（当前恒为 false，保留为手动测试入口） |
| `non_text_message` | `rawMessage.hasMedia = true` |
| `ad:{reason}` | `aiResult.isAd() = true` |
| `not_displayable` | `aiResult.isDisplayable() = false` |
| `no_symbol` | `aiResult.symbol` 为空 |
| `low_confidence` | `aiResult.confidence < minConfidence`（默认 50） |

### 6.3 去重（Redis）

- Key：`tg:dedupe:` + SHA-256(`symbol:content`)
- TTL：60 秒（`danmaku.decision.duplicate-ttl-seconds`）
- 使用 `setIfAbsent`（原子操作），命中则 `HOLD(duplicate_similar_content)`

### 6.4 限频（Redis）

- Key：`tg:rate:symbol:{symbol}`
- TTL：15 秒（`danmaku.decision.symbol-rate-limit-seconds`）
- 命中则 `HOLD(symbol_rate_limited)`
- 仅在通过去重后才检查限频

### 6.5 判定日志

每条消息无论结果如何都写入 `tg_push_decision_log`，记录：decision、decisionReason、dedupeKey、rateLimited、finalContent、symbol 等，用于排查和规则迭代。

---

## 7. 弹幕推送层

`DanmakuPushService.push()` 仅在 `PushDecisionService` 返回 `PUSH` 时调用。

### 7.1 推送接口

`DanmakuSenderClient` 同样是外部接口，`StubDanmakuSenderClient` 为空实现兜底。推送请求字段：

```json
{
  "rawMessageId": 123,
  "symbol": "BTCUSDT",
  "language": "zh",
  "content": "看多的人开始变多了",
  "eventType": "opinion",
  "sentiment": "bullish",
  "topic": "BTC突破讨论",
  "confidence": 82,
  "contentStyle": "human_rewrite",
  "templateId": "ai_direct"
}
```

### 7.2 推送日志

推送结果（成功/失败、responseBody、requestId）写入 `danmaku_push_log`，关联 `raw_message_id` 和 `decision_id`。

---

## 8. 群配置管理

### 8.1 配置表

`tg_group_config` 核心字段：

| 字段 | 说明 |
|------|------|
| `group_id` | TG 群 ID，负整数（如 `-1001234567890`） |
| `language` | 群主要语言，如 `zh`、`en`，影响弹幕语言标记 |
| `enabled` | 是否采集该群消息 |
| `push_enabled` | 是否允许该群内容推送到弹幕（当前由 AI 和判定层控制，此字段预留） |
| `allowed_symbols_json` | 该群重点覆盖的币种，预留字段 |

### 8.2 管理接口

`GroupConfigController` 提供三个接口：

```
GET    /admin/groups                         # 查询所有群配置
POST   /admin/groups                         # 新增群配置
PATCH  /admin/groups/{groupId}/enabled       # 启用或禁用某个群
```

POST 请求体：

```json
{
  "groupId": -1001234567890,
  "groupName": "BTC讨论群",
  "language": "zh",
  "enabled": true,
  "pushEnabled": true
}
```

重复注册同一 `groupId` 返回 `409 Conflict`，不覆盖已有配置。

### 8.3 获取群 ID

Bot 加入群后，调用以下接口获取 `chat.id`（Long Polling 拉到的消息中也可以直接看到）：

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
| entities_json | json | Telegram 消息实体（链接、mention 等） |
| has_link | tinyint(1) | 是否含 url/text_link 类型实体 |

### 9.3 `tg_push_decision_log` — 推送判定日志表

每条消息处理后必写，无论推送还是丢弃，用于排查和规则迭代。

关键字段：`decision`、`decision_reason`、`dedupe_key`、`rate_limited`、`final_content`、`symbol`、`confidence`。

### 9.4 `danmaku_push_log` — 弹幕推送日志表

仅 PUSH 决策时写入，记录推送内容、状态、接口响应。

---

## 10. 配置说明

### 10.1 必须配置

```bash
# Bot Token，polling 开启时必填
TELEGRAM_BOT_TOKEN=xxxx
```

### 10.2 完整配置项

```yaml
danmaku:
  telegram:
    bot-token: ${TELEGRAM_BOT_TOKEN:}
    polling:
      enabled: true          # false 则该实例不启动 polling
  worker:
    enabled: true            # false 则该实例不处理消息
    fixed-delay-ms: 3000     # Worker 扫描间隔（ms）
    batch-size: 50           # 每次处理消息数
    max-retry: 3             # 最大重试次数（含超时恢复）
    processing-timeout-minutes: 5  # 超时恢复阈值
  decision:
    min-confidence: 50           # AI 置信度下限
    symbol-rate-limit-seconds: 15 # 同币对限频窗口
    duplicate-ttl-seconds: 60    # 去重 TTL
  ai:
    model: gpt-4o-mini           # 传给 AiDanmakuClient 的模型名，由具体实现解析
    temperature: 0.4
    response-format: json_object
```

---

## 11. 多实例部署

### 11.1 角色划分

| 角色 | polling.enabled | worker.enabled | 实例数 |
|------|----------------|----------------|--------|
| Collector | true | true | **必须且只能 1 台** |
| Worker | false | true | 可横向扩展 |

**Polling 不能多实例**：Telegram Long Polling 的 offset 在 SDK 内存中维护，多实例并发 poll 同一 token 会导致 update 被随机分发到不同实例，虽然 DB 唯一键可防止重复入库，但行为不可控。

**Worker 天然多实例安全**：`claim()` 用 `UPDATE WHERE status=PENDING` 实现乐观抢占，Redis 用于跨实例共享去重和限频状态。

### 11.2 Docker Compose 示例

```yaml
services:
  collector:
    image: danmaku:latest
    environment:
      - TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}
      - DANMAKU_TELEGRAM_POLLING_ENABLED=true
      - DANMAKU_WORKER_ENABLED=true
    deploy:
      replicas: 1        # 固定 1

  worker:
    image: danmaku:latest
    environment:
      - DANMAKU_TELEGRAM_POLLING_ENABLED=false
      - DANMAKU_WORKER_ENABLED=true
    deploy:
      replicas: 3        # 按负载调整
```

---

## 12. 外部依赖接入

当前两个外部接口均有 Stub 实现，服务可独立启动和测试。正式接入时实现对应 Bean 即可：

### 12.1 `AiDanmakuClient`

```java
@Component
public class RealAiDanmakuClient implements AiDanmakuClient {
    @Override
    public AiPromptResponse complete(AiPromptRequest request) {
        // 调用 OpenAI / Gemini / 其他兼容接口
    }
}
```

注意：`request.getModel()` 由配置项 `danmaku.ai.model` 传入，实现类按需解析。

### 12.2 `DanmakuSenderClient`

```java
@Component
public class RealDanmakuSenderClient implements DanmakuSenderClient {
    @Override
    public DanmakuSendResult send(DanmakuSendRequest request) {
        // 调用现有弹幕推送接口
    }
}
```

---

## 13. 常见问题排查

| 现象 | 排查方向 |
|------|---------|
| Bot 只收到命令消息，收不到普通聊天 | 检查 BotFather 是否关闭了 Privacy Mode |
| 某个群消息没有入库 | 查 `tg_group_config` 该群是否存在且 `enabled=1` |
| 消息入库但没有推送判定日志 | 查 `tg_raw_message.ingest_status`，确认 worker 是否启用 |
| 有判定日志但 decision 都是 HOLD/DISCARD | 查 `decision_reason` 字段，对照判定规则逐一排查 |
| `last_error` 为 `processing_timeout` | AI 接口响应超过 5 分钟，检查 AI 服务可用性或调大 `processing-timeout-minutes` |
| 推送弹幕内容是 `hold` | `AiDanmakuClient` 未接真实实现，Stub 返回 hold |
| 转发消息 `forward_date` 为 null | 该消息非转发消息（`getForwardOrigin() == null`），属正常情况 |
