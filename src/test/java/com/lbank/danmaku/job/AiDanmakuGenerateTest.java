package com.lbank.danmaku.job;

import com.lbank.danmaku.job.client.OpenAiCompatibleDanmakuClient;
import com.lbank.danmaku.job.config.DanmakuProperties;
import com.lbank.danmaku.job.domain.TgRawMessage;
import com.lbank.danmaku.job.dto.AiDanmakuResult;
import com.lbank.danmaku.job.service.AiDanmakuService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

/**
 * AI 弹幕生成集成测试（OpenAI 兼容接口）。
 *
 * 运行前需设置环境变量：
 *   export AI_API_KEY=sk-xxx
 *   export AI_BASE_URL=https://...  （可选，默认读取 application.yml）
 * 或通过 Maven 传入：
 *   mvn test -DAI_API_KEY=sk-xxx -Dtest=AiDanmakuGenerateTest
 */
class AiDanmakuGenerateTest {

    static AiDanmakuService aiService;

    @BeforeAll
    static void setup() {
        String apiKey = System.getenv("AI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("AI_API_KEY");
        }
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "跳过测试：未配置 AI_API_KEY");

        String baseUrl = System.getenv("AI_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = System.getProperty("AI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        }
        String model = System.getenv("AI_MODEL");
        if (model == null || model.isBlank()) {
            model = System.getProperty("AI_MODEL", "qwen-turbo");
        }

        DanmakuProperties props = new DanmakuProperties();
        props.getAi().setBaseUrl(baseUrl);
        props.getAi().setApiKey(apiKey);
        props.getAi().setModel(model);
        props.getAi().setTemperature(0.4);
        props.getAi().setResponseFormat("json_object");

        RestTemplate restTemplate = new RestTemplateBuilder().build();
        ObjectMapper objectMapper = new ObjectMapper();

        OpenAiCompatibleDanmakuClient client = new OpenAiCompatibleDanmakuClient(props, restTemplate, objectMapper);
        aiService = new AiDanmakuService(client, props, objectMapper);
    }

    // ── 工具方法 ─────────────────────────────────────────────────

    static TgRawMessage msg(long id, String username, String text) {
        return msg(id, username, text, null, null);
    }

    static TgRawMessage msg(long id, String username, String text,
                            String replyToText, LocalDateTime sentAt) {
        TgRawMessage m = new TgRawMessage();
        m.setMessageId(id);
        m.setGroupId(-1001234567890L);
        m.setLanguage("zh");
        m.setSenderUsername(username);
        m.setSenderName(username);
        m.setText(text);
        m.setNormalizedText(text);
        m.setReplyToText(replyToText);
        m.setSentAt(sentAt != null ? sentAt : LocalDateTime.now());
        return m;
    }

    static void print(String caseName, AiDanmakuResult r) {
        System.out.println("\n========== " + caseName + " ==========");
        System.out.println("  displayable  : " + r.isDisplayable());
        System.out.println("  symbol       : " + r.getSymbol());
        System.out.println("  marketType   : " + r.getMarketType());
        System.out.println("  eventType    : " + r.getEventType());
        System.out.println("  sentiment    : " + r.getSentiment());
        System.out.println("  confidence   : " + r.getConfidence());
        System.out.println("  ad           : " + r.isAd()
                + (r.getAdReason() != null && !r.getAdReason().isBlank()
                   ? " (" + r.getAdReason() + ")" : ""));
        System.out.println("  topic        : " + r.getTopic());
        System.out.println("  sourceLanguage: " + r.getSourceLanguage());
        System.out.println("  content      : " + r.getContent());
        System.out.println("  model        : " + r.getModelName());
    }

    // ── 测试用例 ─────────────────────────────────────────────────

    @Test
    @DisplayName("BTC 现货看多情绪")
    void btcSpotBullish() {
        LocalDateTime now = LocalDateTime.now();
        TgRawMessage current = msg(1001, "user_a", "感觉BTC要突破前高了，这波量能起来了", null, now);
        List<TgRawMessage> ctx = List.of(
                msg(1000, "user_b", "昨晚大饼一直在震荡，今天终于有动静了", null, now.minusMinutes(2)),
                msg(999,  "user_c", "我觉得可以，支撑位守得很好", null, now.minusMinutes(4))
        );
        AiDanmakuResult result = aiService.generate(current, ctx);
        print("BTC 现货看多情绪", result);
        assert result.isDisplayable() : "预期 displayable=true";
        assert "BTCUSDT".equals(result.getSymbol()) : "预期 symbol=BTCUSDT";
    }

    @Test
    @DisplayName("ETH 合约做多爆仓讨论")
    void ethFuturesLiquidation() {
        LocalDateTime now = LocalDateTime.now();
        TgRawMessage current = msg(2001, "trader_x",
                "以太刚刚多单被爆了，2500 就是个大坑，资金费率一直是负的", null, now);
        List<TgRawMessage> ctx = List.of(
                msg(2000, "trader_y", "永续合约今天波动太大了，杠杆开高真的危险",
                        null, now.minusMinutes(1)),
                msg(1999, "trader_z", "ETH 合约多空比现在多方占优",
                        null, now.minusMinutes(3))
        );
        AiDanmakuResult result = aiService.generate(current, ctx);
        print("ETH 合约做多爆仓讨论", result);
        assert result.isDisplayable() : "预期 displayable=true";
        assert "FUTURE".equals(result.getMarketType()) : "预期 marketType=FUTURE";
    }

    @Test
    @DisplayName("SOL 带回复上下文")
    void solWithReply() {
        LocalDateTime now = LocalDateTime.now();
        TgRawMessage current = msg(3001, "sol_fan",
                "我觉得这波能，生态项目数量一直在增加，基本面好",
                "SOL 这次能涨到 300 吗？", now);
        List<TgRawMessage> ctx = List.of(
                msg(3000, "sol_bear", "我不看好，之前几次都是假突破", null, now.minusMinutes(1))
        );
        AiDanmakuResult result = aiService.generate(current, ctx);
        print("SOL 带回复上下文", result);
        assert result.isDisplayable() : "预期 displayable=true";
        assert "SOLUSDT".equals(result.getSymbol()) : "预期 symbol=SOLUSDT";
    }

    @Test
    @DisplayName("英文群 XRP 新闻讨论")
    void xrpEnglishNews() {
        TgRawMessage current = msg(4001, "xrp_holder",
                "XRP just won the lawsuit, this is huge for the whole market", null, null);
        current.setLanguage("en");
        List<TgRawMessage> ctx = List.of(
                msg(4000, "news_bot", "SEC dropped the case against Ripple", null,
                        LocalDateTime.now().minusMinutes(2))
        );
        AiDanmakuResult result = aiService.generate(current, ctx);
        print("英文群 XRP 新闻讨论", result);
        assert result.isDisplayable() : "预期 displayable=true";
        assert "XRPUSDT".equals(result.getSymbol()) : "预期 symbol=XRPUSDT";
        assert "en".equals(result.getSourceLanguage()) : "预期 sourceLanguage=en";
    }

    @Test
    @DisplayName("广告导流消息")
    void adMessage() {
        TgRawMessage current = msg(5001, "spammer",
                "🔥稳定收益每天10%，专业带单老师，私信领取免费名额！加微信：xxx888", null, null);
        AiDanmakuResult result = aiService.generate(current, List.of());
        print("广告导流消息", result);
        assert !result.isDisplayable() : "预期 displayable=false";
        assert result.isAd() : "预期 ad=true";
    }

    @Test
    @DisplayName("无意义水聊")
    void meaninglessChat() {
        TgRawMessage current = msg(6001, "chatter", "哈哈哈哈哈 666", null, null);
        AiDanmakuResult result = aiService.generate(current, List.of());
        print("无意义水聊", result);
        assert !result.isDisplayable() : "预期 displayable=false";
    }

    @Test
    @DisplayName("BTC 宏观新闻（无明确多空）")
    void btcMacroNews() {
        TgRawMessage current = msg(7001, "news_reader",
                "美联储今晚宣布暂停加息，BTC短线反应还不错", null, null);
        List<TgRawMessage> ctx = List.of(
                msg(7000, "macro_watcher", "今晚FOMC会议是关键，市场都在等结果",
                        null, LocalDateTime.now().minusMinutes(5))
        );
        AiDanmakuResult result = aiService.generate(current, ctx);
        print("BTC 宏观新闻（无明确多空）", result);
        assert "BTCUSDT".equals(result.getSymbol()) : "预期 symbol=BTCUSDT";
        assert "news".equals(result.getEventType()) : "预期 eventType=news";
    }
}
