package com.lbank.danmaku.job.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lbank.danmaku.job.client.AiDanmakuClient;
import com.lbank.danmaku.job.domain.DanmakuTemplate;
import com.lbank.danmaku.job.dto.AiPromptRequest;
import com.lbank.danmaku.job.dto.AiPromptResponse;
import com.lbank.danmaku.job.mapper.DanmakuTemplateMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
    /** BM25 词频饱和参数，控制词频对分数的影响上限。 */
    private static final double BM25_K1 = 1.5;
    /** BM25 文档长度归一化参数。 */
    private static final double BM25_B = 0.75;

    /**
     * 预建的 BM25 语料索引，缓存全量模板及预计算的语料统计信息。
     *
     * <p>key 格式：{matchedEvent}:{language}，如 {@code __TRADING_PAIR_FUTURE__:zh}。
     * 写入后 30 分钟过期；{@link #clear} 和 {@link #generate} 完成后主动失效。
     */
    private final Cache<String, TemplateIndex> indexCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .maximumSize(50)
            .build();

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
        // 新模板入库后失效缓存，下次请求重建索引
        evictIndex(matchedEvent, language);
        return saved;
    }

    /**
     * 根据用户输入推荐弹幕模板。
     *
     * <p>从内存索引（Caffeine 缓存）中检索全量候选，有输入时用 BM25 排序；
     * 无输入时随机返回。首次请求时从数据库加载并预建索引，后续命中缓存。
     *
     * @param matchedEvent 事件或交易对；具体币对会先归一化为抽象 key
     * @param language     语言代码
     * @param limit        推荐条数，通常为 3
     * @param userInput    用户正在输入的文字（可为空）
     */
    public List<DanmakuTemplate> recommend(String matchedEvent, String language, int limit, String userInput) {
        // 将具体币对（如 BTCUSDT、btc_usdt）归一化为抽象交易事件 key，复用通用模板
        String lookupEvent = TradingPairUtil.normalizeEvent(matchedEvent);
        TemplateIndex index = loadIndex(lookupEvent, language);

        if (index.templates().isEmpty()) {
            return List.of();
        }
        if (!StringUtils.hasText(userInput)) {
            // 无输入时从全量模板中随机抽取
            List<DanmakuTemplate> shuffled = new ArrayList<>(index.templates());
            Collections.shuffle(shuffled);
            return shuffled.subList(0, Math.min(limit, shuffled.size()));
        }
        return rankBm25(userInput.trim(), index, limit);
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
        int deleted = templateMapper.delete(new LambdaQueryWrapper<DanmakuTemplate>()
                .eq(DanmakuTemplate::getMatchedEvent, matchedEvent)
                .eq(DanmakuTemplate::getLanguage, language));
        evictIndex(matchedEvent, language);
        return deleted;
    }

    // ── 缓存管理 ──────────────────────────────────────────────────────

    /**
     * 加载或命中缓存的模板索引。
     */
    private TemplateIndex loadIndex(String event, String language) {
        return indexCache.get(event + ":" + language, k -> buildIndex(event, language));
    }

    /**
     * 从数据库加载全量模板并预建 BM25 语料索引。
     *
     * <p>预计算每个 bigram 的文档频率（DF）和平均文档长度（avgdl），
     * 使后续每次查询只需计算查询词的 IDF，无需遍历整个语料库。
     */
    private TemplateIndex buildIndex(String event, String language) {
        List<DanmakuTemplate> templates = templateMapper.selectAll(event, language);
        log.info("构建模板索引 event={} lang={} size={}", event, language, templates.size());

        List<List<String>> docTokens = templates.stream()
                .map(t -> tokenize(t.getContent()))
                .toList();

        double avgdl = docTokens.stream().mapToInt(List::size).average().orElse(1.0);

        // 预计算语料库中每个 bigram 出现在多少篇文档中（DF）
        Map<String, Long> dfMap = new HashMap<>();
        for (List<String> tokens : docTokens) {
            new HashSet<>(tokens).forEach(t -> dfMap.merge(t, 1L, Long::sum));
        }

        return new TemplateIndex(templates, docTokens, avgdl, dfMap);
    }

    /**
     * 主动失效指定 event/language 的缓存。
     */
    private void evictIndex(String event, String language) {
        indexCache.invalidate(event + ":" + language);
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
        String eventDesc = toEventDescription(matchedEvent);
        return "话题事件：%s\n语言：%s\n请生成 %d 条弹幕模板（第 %d 批，风格与之前批次有所不同）。"
                .formatted(eventDesc, language, count, batchIndex);
    }

    /**
     * 将抽象交易事件 key 转换为 AI 可理解的描述；其他事件原样返回。
     */
    private static String toEventDescription(String matchedEvent) {
        if (TradingPairUtil.TRADING_PAIR_FUTURE.equals(matchedEvent)) {
            return "加密货币合约交易（适用于任意合约/永续/杠杆币对，如 BTCUSDT、ETHUSDT）";
        }
        if (TradingPairUtil.TRADING_PAIR_SPOT.equals(matchedEvent)) {
            return "加密货币现货交易（适用于任意现货币对，如 btc_usdt、eth_usdt）";
        }
        return matchedEvent;
    }

    /**
     * BM25 排序：利用预建索引中的 DF 和 avgdl，对全量模板打分，返回得分最高的 limit 条。
     */
    private List<DanmakuTemplate> rankBm25(String query, TemplateIndex index, int limit) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            List<DanmakuTemplate> shuffled = new ArrayList<>(index.templates());
            Collections.shuffle(shuffled);
            return shuffled.subList(0, Math.min(limit, shuffled.size()));
        }

        Set<String> queryTerms = new LinkedHashSet<>(queryTokens);
        int N = index.templates().size();

        // 利用预计算的 DF 计算每个查询词的 IDF
        Map<String, Double> idf = new HashMap<>();
        for (String term : queryTerms) {
            long df = index.dfMap().getOrDefault(term, 0L);
            idf.put(term, Math.log((N - df + 0.5) / (df + 0.5) + 1.0));
        }

        // BM25 打分（docTokens 已预先词元化，无需重复计算）
        List<List<String>> docTokens = index.docTokens();
        double avgdl = index.avgdl();
        double[] scores = new double[N];

        for (int i = 0; i < N; i++) {
            List<String> tokens = docTokens.get(i);
            int dl = tokens.size();
            if (dl == 0) continue;
            Map<String, Long> tf = tokens.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            for (String term : queryTerms) {
                long freq = tf.getOrDefault(term, 0L);
                if (freq == 0) continue;
                scores[i] += idf.get(term)
                        * (freq * (BM25_K1 + 1))
                        / (freq + BM25_K1 * (1 - BM25_B + BM25_B * dl / avgdl));
            }
        }

        // 按分数降序取前 limit 条
        Integer[] order = IntStream.range(0, N).boxed().toArray(Integer[]::new);
        Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));
        return Arrays.stream(order).limit(limit).map(index.templates()::get).toList();
    }

    /**
     * 字符 bigram 词元化。小写 + 去空白后，对每两个相邻字符生成一个词元。
     *
     * <p>示例："以太坊要涨" → ["以太", "太坊", "坊要", "要涨"]
     */
    private static List<String> tokenize(String text) {
        if (text == null || text.length() < 2) return List.of();
        List<String> tokens = new ArrayList<>();
        String s = text.toLowerCase().replaceAll("\\s+", "");
        for (int i = 0; i < s.length() - 1; i++) {
            tokens.add(s.substring(i, i + 2));
        }
        return tokens;
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

    // ── 内部数据结构 ──────────────────────────────────────────────────

    /**
     * 预建的 BM25 语料索引。
     *
     * @param templates 全量模板列表
     * @param docTokens 与 templates 一一对应的 bigram 词元列表（预计算，避免重复分词）
     * @param avgdl     语料库平均文档长度（bigram 数）
     * @param dfMap     bigram → 包含该 bigram 的文档数（预计算 DF，查询时直接用）
     */
    private record TemplateIndex(
            List<DanmakuTemplate> templates,
            List<List<String>> docTokens,
            double avgdl,
            Map<String, Long> dfMap) {}
}
