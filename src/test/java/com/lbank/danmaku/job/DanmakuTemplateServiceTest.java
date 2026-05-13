package com.lbank.danmaku.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbank.danmaku.job.client.AiDanmakuClient;
import com.lbank.danmaku.job.domain.DanmakuTemplate;
import com.lbank.danmaku.job.dto.AiPromptResponse;
import com.lbank.danmaku.job.mapper.DanmakuTemplateMapper;
import com.lbank.danmaku.job.service.DanmakuTemplateService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class DanmakuTemplateServiceTest {

    @Mock
    private AiDanmakuClient aiDanmakuClient;

    @Mock
    private DanmakuTemplateMapper templateMapper;

    private DanmakuTemplateService service;

    @BeforeEach
    void setup() {
        // 使用真实 ObjectMapper，避免 mock 掩盖 JSON 解析问题
        service = new DanmakuTemplateService(aiDanmakuClient, templateMapper, new ObjectMapper());
    }

    // ── generate：批次拆分 ────────────────────────────────────────────

    @Test
    @DisplayName("total=50 只调用 AI 一次")
    void generate_singleBatch_callsAiOnce() {
        when(aiDanmakuClient.complete(any())).thenReturn(aiResponse(buildItems(50)));

        service.generate("BTCUSDT", "zh", 50);

        verify(aiDanmakuClient, times(1)).complete(any());
    }

    @Test
    @DisplayName("total=100 调用 AI 两次，第二批 count 参数正确")
    void generate_twoBatches_eachBatchHasCorrectCount() {
        when(aiDanmakuClient.complete(any())).thenReturn(aiResponse(buildItems(50)));

        service.generate("BTCUSDT", "zh", 100);

        verify(aiDanmakuClient, times(2)).complete(any());
    }

    @Test
    @DisplayName("total=75：第一批 50 条，第二批只需 25 条，user prompt 中数量正确")
    void generate_unevenBatch_lastBatchCountIsRemainder() {
        when(aiDanmakuClient.complete(any())).thenReturn(aiResponse(buildItems(50)));

        service.generate("BTCUSDT", "zh", 75);

        // 捕获两次调用的 userPrompt
        ArgumentCaptor<com.lbank.danmaku.job.dto.AiPromptRequest> captor =
                ArgumentCaptor.forClass(com.lbank.danmaku.job.dto.AiPromptRequest.class);
        verify(aiDanmakuClient, times(2)).complete(captor.capture());

        List<com.lbank.danmaku.job.dto.AiPromptRequest> calls = captor.getAllValues();
        assertThat(calls.get(0).getUserPrompt()).contains("50");   // 第一批
        assertThat(calls.get(1).getUserPrompt()).contains("25");   // 第二批
    }

    // ── generate：AI 响应异常 ─────────────────────────────────────────

    @Test
    @DisplayName("AI 返回 null 响应，跳过该批次，不抛异常")
    void generate_nullResponse_skipsAndContinues() {
        when(aiDanmakuClient.complete(any())).thenReturn(null);

        int saved = service.generate("BTCUSDT", "zh", 50);

        assertThat(saved).isZero();
        verify(templateMapper, never()).insert(any(DanmakuTemplate.class));
    }

    @Test
    @DisplayName("AI 返回空 content，跳过该批次")
    void generate_emptyContent_skipsAndContinues() {
        AiPromptResponse resp = new AiPromptResponse();
        resp.setContent("");
        when(aiDanmakuClient.complete(any())).thenReturn(resp);

        int saved = service.generate("BTCUSDT", "zh", 50);

        assertThat(saved).isZero();
        verify(templateMapper, never()).insert(any(DanmakuTemplate.class));
    }

    @Test
    @DisplayName("AI 返回非 JSON 内容，解析失败静默跳过")
    void generate_invalidJson_parsesAsEmpty() {
        when(aiDanmakuClient.complete(any())).thenReturn(aiResponse("not a json at all"));

        int saved = service.generate("BTCUSDT", "zh", 50);

        assertThat(saved).isZero();
    }

    @Test
    @DisplayName("AI 返回的 items 中 content 为空的条目被过滤掉")
    void generate_emptyContentItems_filtered() {
        String json = """
                {"items":[
                  {"content":"量能这么大要飞了","sentiment":"bullish","eventType":"price","marketType":"SPOT"},
                  {"content":"","sentiment":"neutral","eventType":"other","marketType":""},
                  {"content":"  ","sentiment":"neutral","eventType":"other","marketType":""}
                ]}""";
        when(aiDanmakuClient.complete(any())).thenReturn(aiResponse(json));

        int saved = service.generate("BTCUSDT", "zh", 50);

        assertThat(saved).isEqualTo(1);
        verify(templateMapper, times(1)).insert(any(DanmakuTemplate.class));
    }

    // ── generate：入库行为 ────────────────────────────────────────────

    @Test
    @DisplayName("正常解析并入库，返回正确数量")
    void generate_validResponse_savesAllItems() {
        String json = """
                {"items":[
                  {"content":"量能这么大要飞了","sentiment":"bullish","eventType":"price","marketType":"SPOT"},
                  {"content":"短线看空，压力位没过","sentiment":"bearish","eventType":"price","marketType":""},
                  {"content":"今天波动真大","sentiment":"neutral","eventType":"other","marketType":"FUTURE"}
                ]}""";
        when(aiDanmakuClient.complete(any())).thenReturn(aiResponse(json));

        int saved = service.generate("BTCUSDT", "zh", 50);

        assertThat(saved).isEqualTo(3);
        verify(templateMapper, times(3)).insert(any(DanmakuTemplate.class));
    }

    @Test
    @DisplayName("入库字段填充正确：matchedEvent、language、marketType 为空时存 null")
    void generate_fieldsFilledCorrectly() {
        String json = """
                {"items":[
                  {"content":"量能这么大要飞了","sentiment":"bullish","eventType":"price","marketType":""}
                ]}""";
        when(aiDanmakuClient.complete(any())).thenReturn(aiResponse(json));

        service.generate("BTCUSDT", "zh", 50);

        ArgumentCaptor<DanmakuTemplate> captor = ArgumentCaptor.forClass(DanmakuTemplate.class);
        verify(templateMapper).insert(captor.capture());
        DanmakuTemplate saved = captor.getValue();

        assertThat(saved.getMatchedEvent()).isEqualTo("BTCUSDT");
        assertThat(saved.getLanguage()).isEqualTo("zh");
        assertThat(saved.getContent()).isEqualTo("量能这么大要飞了");
        assertThat(saved.getSentiment()).isEqualTo("bullish");
        assertThat(saved.getEventType()).isEqualTo("price");
        assertThat(saved.getMarketType()).isNull();   // 空字符串应存为 null
        assertThat(saved.getUsedCount()).isZero();
    }

    @Test
    @DisplayName("非加密货币事件的 marketType 为空时存 null")
    void generate_nonCryptoEvent_marketTypeNull() {
        String json = """
                {"items":[
                  {"content":"决赛太精彩了点球大战","sentiment":"neutral","eventType":"other","marketType":""}
                ]}""";
        when(aiDanmakuClient.complete(any())).thenReturn(aiResponse(json));

        service.generate("2026年世界杯", "zh", 50);

        ArgumentCaptor<DanmakuTemplate> captor = ArgumentCaptor.forClass(DanmakuTemplate.class);
        verify(templateMapper).insert(captor.capture());
        assertThat(captor.getValue().getMarketType()).isNull();
    }

    @Test
    @DisplayName("内容重复（DuplicateKeyException）时跳过，其他条目继续入库")
    void generate_duplicateKey_skipsAndContinues() {
        String json = """
                {"items":[
                  {"content":"第一条","sentiment":"bullish","eventType":"price","marketType":""},
                  {"content":"第二条（重复）","sentiment":"neutral","eventType":"other","marketType":""},
                  {"content":"第三条","sentiment":"bearish","eventType":"price","marketType":""}
                ]}""";
        when(aiDanmakuClient.complete(any())).thenReturn(aiResponse(json));
        // 第二条抛异常
        lenient().when(templateMapper.insert(argThat((DanmakuTemplate t) -> "第二条（重复）".equals(t.getContent()))))
                .thenThrow(new DuplicateKeyException("uk violation"));

        int saved = service.generate("BTCUSDT", "zh", 50);

        assertThat(saved).isEqualTo(2);  // 只保存了第一条和第三条
        verify(templateMapper, times(3)).insert(any(DanmakuTemplate.class));
    }

    // ── recommend ────────────────────────────────────────────────────

    @Test
    @DisplayName("无用户输入时返回候选池前 limit 条（候选池已随机排序）")
    void recommend_noInput_returnsPoolHead() {
        List<DanmakuTemplate> pool = buildPool(10);
        when(templateMapper.selectRandom("BTCUSDT", "zh", 100)).thenReturn(pool);

        List<DanmakuTemplate> result = service.recommend("BTCUSDT", "zh", 3, null);

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isSameAs(pool.get(0));
        assertThat(result.get(1)).isSameAs(pool.get(1));
        assertThat(result.get(2)).isSameAs(pool.get(2));
    }

    @Test
    @DisplayName("空白输入等同于无输入")
    void recommend_blankInput_returnsPoolHead() {
        List<DanmakuTemplate> pool = buildPool(10);
        when(templateMapper.selectRandom(anyString(), anyString(), anyInt())).thenReturn(pool);

        List<DanmakuTemplate> result = service.recommend("SOLUSDT", "zh", 3, "   ");

        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("候选池不足 limit 时返回全部，不做 BM25")
    void recommend_poolSmallerThanLimit_returnsAll() {
        List<DanmakuTemplate> pool = buildPool(2);
        when(templateMapper.selectRandom(anyString(), anyString(), anyInt())).thenReturn(pool);

        List<DanmakuTemplate> result = service.recommend("SOLUSDT", "zh", 3, "sol要飞");

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("BM25：语义相关的模板排在最前面")
    void recommend_bm25_relevantTemplateRanksFirst() {
        DanmakuTemplate irrelevant = template("比特币今天横盘了");
        DanmakuTemplate relevant   = template("以太坊感觉要涨了涨涨涨");
        DanmakuTemplate noise      = template("BTC量能在萎缩");
        // relevant 故意放在列表末尾，验证 BM25 能把它排到第一
        List<DanmakuTemplate> pool = new ArrayList<>(List.of(irrelevant, noise, relevant));
        when(templateMapper.selectRandom(anyString(), anyString(), anyInt())).thenReturn(pool);

        List<DanmakuTemplate> result = service.recommend("ETHUSDT", "zh", 1, "以太坊要涨");

        assertThat(result.get(0)).isSameAs(relevant);
    }

    @Test
    @DisplayName("BM25：无任何词元命中时仍返回 limit 条（分数全 0，保留原始随机顺序）")
    void recommend_bm25_noTermHit_stillReturnsLimit() {
        List<DanmakuTemplate> pool = buildPool(10);
        when(templateMapper.selectRandom(anyString(), anyString(), anyInt())).thenReturn(pool);

        // query 的 bigram 与所有模板内容完全无交集
        List<DanmakuTemplate> result = service.recommend("BTCUSDT", "zh", 3, "zzz");

        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("BM25：mapper 只被调用一次，传入固定候选池大小 100")
    void recommend_onlyOneMapperCallWithPoolSize100() {
        when(templateMapper.selectRandom("BTCUSDT", "zh", 100)).thenReturn(buildPool(50));

        service.recommend("BTCUSDT", "zh", 3, "btc涨了");

        verify(templateMapper, times(1)).selectRandom("BTCUSDT", "zh", 100);
    }

    // ── count / clear ─────────────────────────────────────────────────

    @Test
    @DisplayName("count 委托给 mapper")
    void count_delegatesToMapper() {
        when(templateMapper.countByEventAndLanguage("BTCUSDT", "zh")).thenReturn(500);

        assertThat(service.count("BTCUSDT", "zh")).isEqualTo(500);
    }

    @Test
    @DisplayName("clear 委托给 mapper 并返回删除数")
    void clear_delegatesToMapper() {
        when(templateMapper.delete(any())).thenReturn(200);

        assertThat(service.clear("BTCUSDT", "zh")).isEqualTo(200);
    }

    // ── 工具方法 ──────────────────────────────────────────────────────

    private AiPromptResponse aiResponse(String content) {
        AiPromptResponse resp = new AiPromptResponse();
        resp.setContent(content);
        return resp;
    }

    private DanmakuTemplate template(String content) {
        DanmakuTemplate t = new DanmakuTemplate();
        t.setContent(content);
        return t;
    }

    private List<DanmakuTemplate> buildPool(int n) {
        List<DanmakuTemplate> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(template("模板" + i));
        return list;
    }

    /** 生成包含 n 条 item 的合法 JSON 字符串。 */
    private String buildItems(int n) {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"content\":\"弹幕").append(i).append("\",")
              .append("\"sentiment\":\"neutral\",")
              .append("\"eventType\":\"other\",")
              .append("\"marketType\":\"\"}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
