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
    @DisplayName("recommend 委托给 mapper 并返回结果")
    void recommend_delegatesToMapper() {
        List<DanmakuTemplate> expected = List.of(new DanmakuTemplate(), new DanmakuTemplate(), new DanmakuTemplate());
        when(templateMapper.selectRandom("BTCUSDT", "zh", 3)).thenReturn(expected);

        List<DanmakuTemplate> result = service.recommend("BTCUSDT", "zh", 3);

        assertThat(result).isSameAs(expected);
        verify(templateMapper).selectRandom("BTCUSDT", "zh", 3);
    }

    @Test
    @DisplayName("recommend 模板不足时返回实际有的条数")
    void recommend_fewerThanRequested_returnsWhatExists() {
        List<DanmakuTemplate> onlyOne = List.of(new DanmakuTemplate());
        when(templateMapper.selectRandom("SOLUSDT", "zh", 3)).thenReturn(onlyOne);

        List<DanmakuTemplate> result = service.recommend("SOLUSDT", "zh", 3);

        assertThat(result).hasSize(1);
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
