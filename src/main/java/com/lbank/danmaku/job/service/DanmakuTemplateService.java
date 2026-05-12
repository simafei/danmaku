package com.lbank.danmaku.job.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbank.danmaku.job.client.AiDanmakuClient;
import com.lbank.danmaku.job.domain.DanmakuTemplate;
import com.lbank.danmaku.job.dto.AiPromptRequest;
import com.lbank.danmaku.job.dto.AiPromptResponse;
import com.lbank.danmaku.job.mapper.DanmakuTemplateMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DanmakuTemplateService {
    private static final Logger log = LoggerFactory.getLogger(DanmakuTemplateService.class);

    /** 每次 AI 调用生成的条数。 */
    private static final int BATCH_SIZE = 50;

    private final AiDanmakuClient aiDanmakuClient;
    private final DanmakuTemplateMapper templateMapper;
    private final ObjectMapper objectMapper;

    public DanmakuTemplateService(
            AiDanmakuClient aiDanmakuClient,
            DanmakuTemplateMapper templateMapper,
            ObjectMapper objectMapper) {
        this.aiDanmakuClient = aiDanmakuClient;
        this.templateMapper = templateMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 为指定事件批量生成弹幕模板并存入数据库。
     *
     * @param matchedEvent 事件或交易对名称，如 BTCUSDT、美伊战争
     * @param language     语言代码，如 zh、en
     * @param total        目标生成总条数
     * @return 实际入库条数（去重后）
     */
    public int generate(String matchedEvent, String language, int total) {
        int saved = 0;
        int batchCount = (int) Math.ceil((double) total / BATCH_SIZE);

        for (int batch = 1; batch <= batchCount; batch++) {
            int need = Math.min(BATCH_SIZE, total - saved);
            log.info("生成弹幕模板 event={} lang={} batch={}/{} need={}", matchedEvent, language, batch, batchCount, need);

            List<DanmakuTemplate> items = generateBatch(matchedEvent, language, need, batch);
            for (DanmakuTemplate item : items) {
                try {
                    templateMapper.insert(item);
                    saved++;
                } catch (DuplicateKeyException ignored) {
                    // 内容重复，跳过
                }
            }
        }
        log.info("弹幕模板生成完成 event={} lang={} saved={}", matchedEvent, language, saved);
        return saved;
    }

    /**
     * 随机推荐弹幕模板。
     *
     * @param matchedEvent 事件或交易对
     * @param language     语言代码
     * @param limit        推荐条数，通常为 3
     */
    public List<DanmakuTemplate> recommend(String matchedEvent, String language, int limit) {
        return templateMapper.selectRandom(matchedEvent, language, limit);
    }

    /**
     * 查询某个事件已有的模板数量。
     */
    public int count(String matchedEvent, String language) {
        return templateMapper.countByEventAndLanguage(matchedEvent, language);
    }

    /**
     * 清空某个事件的所有模板（重新生成前调用）。
     */
    public int clear(String matchedEvent, String language) {
        return templateMapper.delete(new LambdaQueryWrapper<DanmakuTemplate>()
                .eq(DanmakuTemplate::getMatchedEvent, matchedEvent)
                .eq(DanmakuTemplate::getLanguage, language));
    }

    // ── 私有方法 ─────────────────────────────────────────────────────

    private List<DanmakuTemplate> generateBatch(String matchedEvent, String language,
                                                 int count, int batchIndex) {
        AiPromptRequest request = new AiPromptRequest();
        request.setSystemPrompt(systemPrompt(language));
        request.setUserPrompt(userPrompt(matchedEvent, language, count, batchIndex));
        request.setResponseFormat("json_object");

        AiPromptResponse response = aiDanmakuClient.complete(request);
        if (response == null || !StringUtils.hasText(response.getContent())) {
            log.warn("AI 返回空响应 event={} batch={}", matchedEvent, batchIndex);
            return List.of();
        }
        return parseTemplates(response.getContent(), matchedEvent, language);
    }

    private String systemPrompt(String language) {
        String langHint = "zh".equals(language) ? "中文，口语化，8–30字" : "English, colloquial, 5–20 words";
        return """
                你是弹幕文案生成助手。用户会告诉你一个话题事件，你需要生成多条不同风格的弹幕模板。

                要求：
                - 语言：%s
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
                """.formatted(langHint);
    }

    private String userPrompt(String matchedEvent, String language, int count, int batchIndex) {
        return "话题事件：%s\n语言：%s\n请生成 %d 条弹幕模板（第 %d 批，风格与之前批次有所不同）。"
                .formatted(matchedEvent, language, count, batchIndex);
    }

    private List<DanmakuTemplate> parseTemplates(String content, String matchedEvent, String language) {
        List<DanmakuTemplate> result = new ArrayList<>();
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return result;
            }
            JsonNode root = objectMapper.readTree(content.substring(start, end + 1));
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                return result;
            }
            for (JsonNode item : items) {
                String text = item.path("content").asText("").trim();
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                DanmakuTemplate t = new DanmakuTemplate();
                t.setMatchedEvent(matchedEvent);
                t.setLanguage(language);
                t.setContent(text);
                t.setSentiment(item.path("sentiment").asText(null));
                t.setEventType(item.path("eventType").asText(null));
                String mt = item.path("marketType").asText("");
                t.setMarketType(StringUtils.hasText(mt) ? mt : null);
                t.setUsedCount(0);
                result.add(t);
            }
        } catch (Exception e) {
            log.error("解析 AI 弹幕模板响应失败: {}", content, e);
        }
        return result;
    }
}
