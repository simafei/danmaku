# 弹幕系统 AI 能力需求文档

**提交方**：弹幕业务团队  
**版本**：1.0  
**日期**：2026-05-13

---

## 1. 背景与目标

弹幕系统从 Telegram 群采集实时消息，经过 AI 分析后推送为用户可见的弹幕。同时系统支持 AI 预生成弹幕模板，供用户在发送时快速选用。

业务侧需要 AI 团队提供两个独立的模型能力接口，分别对应两个不同的调用场景。

---

## 2. 调用接口规范

### 2.1 协议

要求实现 **OpenAI Chat Completions 兼容接口**：

```
POST {baseUrl}/v1/chat/completions
Authorization: Bearer {apiKey}
Content-Type: application/json
```

### 2.2 请求结构

```json
{
  "model": "模型名称",
  "temperature": 0.4,
  "response_format": { "type": "json_object" },
  "messages": [
    { "role": "system", "content": "系统提示词" },
    { "role": "user",   "content": "用户提示词" }
  ]
}
```

### 2.3 响应结构

```json
{
  "id": "...",
  "model": "实际使用的模型名",
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "合法 JSON 字符串"
      }
    }
  ]
}
```

> **关键约束**：`response_format.type = json_object` 时，`content` 必须是可直接 `JSON.parse` 的字符串，不得包含 Markdown 代码块（` ```json ... ``` `）或其他前缀文本。

### 2.4 配置参数

| 参数 | 说明 | 当前默认值 |
|---|---|---|
| `baseUrl` | 接口基础地址 | 由 AI 团队提供 |
| `apiKey` | Bearer 鉴权 Key | 由 AI 团队提供 |
| `model` | 模型名称 | 由 AI 团队提供 |
| `temperature` | 温度，控制输出稳定性 | 0.4 |
| `response_format` | 固定 `json_object` | json_object |

---

## 3. 能力一：消息过滤与事件匹配

### 3.1 业务场景

每条从 Telegram 群同步来的消息，都需要实时调用 AI 完成两件事：

1. **过滤**：识别广告、噪声、无意义内容，打上 `displayable=false`
2. **事件匹配**：从业务方提供的事件列表中，找出与当前消息最相关的一个事件

这是**实时在线调用**，每条消息触发一次，要求较低延迟（建议 P95 < 3s）。

### 3.2 输入格式

系统提示词固定（见 3.3），用户提示词结构如下：

```
【事件列表】
- BTCUSDT
- ETHUSDT
- SOLUSDT
- 2026年世界杯
- 美伊局势
- 美联储加息

【当前消息】
user=alice, text=以太坊这波直接打到支撑位了，多单压力好大
  ↳ 回复的消息：昨天那根大阴线还没修复呢

【近期上下文，按时间从近到远】
- [30秒前] user=bob, text=以太合约资金费率开始转负了
- [2分钟前] user=charlie, text=ETH现在感觉要跌破支撑
```

**说明**：
- 事件列表由业务方动态注入，内容可以是加密货币交易对（`BTCUSDT`、`ETHUSDT`）或非加密货币事件（`2026年世界杯`、`美伊局势`、`美联储加息`），数量不固定，通常 10–200 条
- 上下文消息数量可配置，默认最近 5 条，按时间从近到远排列
- 时间标签格式：`N秒前` 或 `N分钟前`

### 3.3 系统提示词

```
你是一个社区消息的过滤和分类助手。
输入包含一条消息、近期上下文，以及一个【事件列表】。
你需要完成两件事：
1. 过滤掉广告和无意义噪声
2. 从事件列表中找到与消息最相关的一条，作为 matchedEvent 输出

## 一、displayable 判断
以下情况填 false，其余填 true：
- 广告、推广、导流链接、带单、拉群邀请
- 纯表情、纯寒暄、无意义噪声（例如"哈哈""好的""666""👍"）

## 二、matchedEvent（事件匹配）
- 必须从【事件列表】中原文选取一项，不得修改或拼造
- 选取与当前消息及上下文语义最相关的一项
- 若消息与列表中任何事件都不相关，或 displayable=false，填空字符串

## 三、topic（话题提炼）
- 结合当前消息和上下文，提炼正在讨论的具体话题
- 中文 6–20 字，英文 5–15 词；语言与消息保持一致
- 例如："美伊局势引发BTC暴跌""以太合约多单爆仓风险""XRP胜诉利好""世界杯决赛点球大战"
- displayable=false 时留空

## 四、marketType（仅加密货币讨论时填写）
- 当消息明确讨论加密货币的合约或现货交易时填写，否则留空字符串
- FUTURE：提到做多、做空、爆仓、资金费率、永续合约、杠杆、多单、空单
- SPOT：提到买入、卖出、现货、持币，且没有合约相关语境
- 无法判断或与加密交易无关：留空字符串

## 五、其他字段说明
- confidence：0–100，对 matchedEvent 选择的把握程度；displayable=false 时填 0
- ad：是否广告或推广内容
- adReason：广告判断原因，ad=false 时留空
- sourceLanguage：原始消息的语言代码，如 zh、en、ru、tr

## 六、输出要求
- 只输出合法 JSON 对象，不加 Markdown 代码块，不写任何解释
- 所有字段必须存在，缺失值用空字符串，布尔型用 false，数字型用 0
```

### 3.4 期望输出格式

```json
{
  "ad": false,
  "adReason": "",
  "displayable": true,
  "matchedEvent": "ETHUSDT",
  "topic": "以太合约多单爆仓风险",
  "marketType": "FUTURE",
  "confidence": 88,
  "sourceLanguage": "zh"
}
```

### 3.5 输出字段说明

| 字段 | 类型 | 说明 |
|---|---|---|
| `ad` | boolean | 是否广告/推广/带单内容 |
| `adReason` | string | 广告判断原因；`ad=false` 时留空 |
| `displayable` | boolean | 是否适合展示为弹幕（非广告、非水聊） |
| `matchedEvent` | string | 从事件列表原文选取的匹配项；无匹配时为空字符串 |
| `topic` | string | AI 提炼的话题描述，6–20 字；`displayable=false` 时为空 |
| `marketType` | string | `SPOT` / `FUTURE` / 空字符串 |
| `confidence` | integer | 对 `matchedEvent` 的置信度，0–100 |
| `sourceLanguage` | string | 消息来源语言代码，如 `zh` / `en` / `ru` / `tr` |

### 3.6 典型用例

| 消息 | 期望 matchedEvent | 期望 marketType | 期望 displayable |
|---|---|---|---|
| "BTC现在多少了，准备建仓了" | BTCUSDT | SPOT | true |
| "以太多单全止损了，亏麻了" | ETHUSDT | FUTURE | true |
| "微信群加我，手把手带你赚钱" | （空） | （空） | false（ad=true） |
| "哈哈哈666" | （空） | （空） | false |
| "美联储今晚要不要加息啊大家觉得" | 美联储加息 | （空） | true |
| "世界杯决赛进点球了！！" | 2026年世界杯 | （空） | true |

### 3.7 性能要求

| 指标 | 要求 |
|---|---|
| P50 延迟 | < 1.5s |
| P95 延迟 | < 3s |
| 可用性 | ≥ 99.5% |
| 并发 | 峰值约 20 QPS（多个 Telegram 群并发处理） |

---

## 4. 能力二：弹幕模板批量生成

### 4.1 业务场景

运营人员希望为某个热点事件（如 `BTCUSDT`、`2026年世界杯`）预先生成 1000 条多样化弹幕模板存入数据库，用户发弹幕时从中随机推荐 3 条选择。

这是**离线批量调用**，每次请求生成 50 条，共调用约 20 次完成 1000 条的目标。对延迟没有严格要求，但需要保证输出数量和格式稳定。

### 4.2 输入格式

系统提示词见 4.3，用户提示词结构如下：

```
话题事件：BTCUSDT
语言：zh
请生成 50 条弹幕模板（第 3 批，风格与之前批次有所不同）。
```

**说明**：
- `话题事件` 可以是任意字符串，不限于加密货币（如 `2026年世界杯`）
- `语言` 决定输出文案的语言，`zh` 表示中文，`en` 表示英文
- `批次编号` 提示 AI 在同一事件的不同批次中生成不同风格的内容，避免重复

### 4.3 系统提示词

````
你是弹幕文案生成助手。用户会告诉你一个话题事件，你需要生成多条不同风格的弹幕模板。

要求：
- 语言：中文，口语化，8–30字（若语言参数为 en，则改为 English, colloquial, 5–20 words）
- 口语化，像真实用户随手发的一句话，不要像新闻标题
- 多样化：涵盖不同情绪（看涨/看跌/中性）、不同视角（价格/新闻/观点/提问）
- 禁止：喊单（快买/快跑）、绝对判断（必涨/必跌）、收益承诺
- 禁止：广告、导流、带单相关内容
- 同一批次内风格和措辞要有明显差异，避免重复

输出格式（合法 JSON 对象，不加 Markdown 代码块）：
{
  "items": [
    {
      "content": "弹幕文案",
      "sentiment": "bullish|bearish|neutral",
      "eventType": "price|news|opinion|question|other",
      "marketType": "SPOT|FUTURE|"
    }
  ]
}
````

### 4.4 期望输出格式

```json
{
  "items": [
    {
      "content": "量能这么大，感觉要飞了",
      "sentiment": "bullish",
      "eventType": "price",
      "marketType": "SPOT"
    },
    {
      "content": "短线看空，压力位一直没过",
      "sentiment": "bearish",
      "eventType": "opinion",
      "marketType": ""
    },
    {
      "content": "今天波动真大，心跳加速",
      "sentiment": "neutral",
      "eventType": "other",
      "marketType": ""
    }
  ]
}
```

### 4.5 输出字段说明

| 字段 | 类型 | 枚举值 | 说明 |
|---|---|---|---|
| `content` | string | — | 弹幕文案正文，不能为空 |
| `sentiment` | string | `bullish` / `bearish` / `neutral` | 情绪倾向 |
| `eventType` | string | `price` / `news` / `opinion` / `question` / `other` | 内容类型 |
| `marketType` | string | `SPOT` / `FUTURE` / 空字符串 | 市场类型；非加密货币事件统一留空 |

### 4.6 数量与多样性要求

- 每次请求须**恰好返回请求条数**（如请求 50 条必须返回 50 条），不足时补充到位
- 同一批次内，`sentiment` 分布均衡，`bullish`：`bearish`：`neutral` 大致 1:1:1
- `eventType` 分布均衡，不同类型交替出现
- 内容措辞有明显差异，避免"换个说法表达同一意思"式的重复
- 跨批次（通过批次编号提示区分）内容重复率尽量低

### 4.7 性能要求

| 指标 | 要求 |
|---|---|
| 单次调用延迟 | < 30s（50 条输出） |
| 调用频率 | 低频（按需触发，约每次批量任务 20 次调用） |
| 稳定性 | 输出格式解析成功率 ≥ 99% |

---

## 5. 异常处理约定

| 场景 | 业务侧处理方式 | 期望 AI 侧配合 |
|---|---|---|
| 网络超时 / 5xx 错误 | 整批跳过，记录日志，不重试（下一轮重新触发） | 稳定可用，避免长时间不响应 |
| `content` 为空或非 JSON | 静默跳过该批次 | 严格保证 `response_format=json_object` 时输出合法 JSON |
| 字段缺失 | 缺失字段用空字符串 / 0 / false 兜底 | 所有字段必须存在 |
| `matchedEvent` 不在事件列表 | 视为无匹配，不推送 | 严格只从列表中原文选取 |
| 模板内容数据库唯一键冲突 | 跳过重复条目，继续入库其他条目 | 尽量降低跨批次重复率 |

---

## 6. 总结

| 能力 | 调用时机 | 频率 | 延迟要求 | 输出格式 |
|---|---|---|---|---|
| 消息过滤 + 事件匹配 | 每条 Telegram 消息实时触发 | ~20 QPS 峰值 | P95 < 3s | 单 JSON 对象（8 个字段） |
| 弹幕模板批量生成 | 运营按需离线触发 | 低频，每次约 20 次调用 | < 30s / 50 条 | JSON 对象含 `items` 数组 |

两个能力共用同一套 OpenAI 兼容接口和鉴权体系，通过 `systemPrompt` 区分任务类型，业务侧不感知具体模型版本，通过配置切换。
