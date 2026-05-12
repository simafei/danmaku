package com.lbank.danmaku.job.controller;

import com.lbank.danmaku.job.domain.DanmakuTemplate;
import com.lbank.danmaku.job.service.DanmakuTemplateService;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/danmaku-templates")
public class DanmakuTemplateController {

    private final DanmakuTemplateService templateService;

    public DanmakuTemplateController(DanmakuTemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * 触发 AI 批量生成弹幕模板。
     *
     * POST /danmaku-templates/generate
     * {
     *   "matchedEvent": "BTCUSDT",
     *   "language": "zh",
     *   "total": 1000
     * }
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@RequestBody GenerateRequest req) {
        int saved = templateService.generate(
                req.getMatchedEvent(),
                req.getLanguage() != null ? req.getLanguage() : "zh",
                req.getTotal() != null ? req.getTotal() : 1000);
        GenerateResponse resp = new GenerateResponse();
        resp.setSaved(saved);
        resp.setMatchedEvent(req.getMatchedEvent());
        return ResponseEntity.ok(resp);
    }

    /**
     * 随机推荐弹幕模板供用户选择。
     *
     * GET /danmaku-templates/recommend?event=BTCUSDT&language=zh&limit=3
     */
    @GetMapping("/recommend")
    public ResponseEntity<List<DanmakuTemplate>> recommend(
            @RequestParam String event,
            @RequestParam(defaultValue = "zh") String language,
            @RequestParam(defaultValue = "3") int limit) {
        List<DanmakuTemplate> items = templateService.recommend(event, language, Math.min(limit, 10));
        return ResponseEntity.ok(items);
    }

    /**
     * 查询某个事件已有的模板数量。
     *
     * GET /danmaku-templates/count?event=BTCUSDT&language=zh
     */
    @GetMapping("/count")
    public ResponseEntity<CountResponse> count(
            @RequestParam String event,
            @RequestParam(defaultValue = "zh") String language) {
        int total = templateService.count(event, language);
        CountResponse resp = new CountResponse();
        resp.setMatchedEvent(event);
        resp.setLanguage(language);
        resp.setTotal(total);
        return ResponseEntity.ok(resp);
    }

    /**
     * 清空某个事件的所有模板（重新生成前调用）。
     *
     * DELETE /danmaku-templates?event=BTCUSDT&language=zh
     */
    @DeleteMapping
    public ResponseEntity<DeleteResponse> clear(
            @RequestParam String event,
            @RequestParam(defaultValue = "zh") String language) {
        int deleted = templateService.clear(event, language);
        DeleteResponse resp = new DeleteResponse();
        resp.setDeleted(deleted);
        return ResponseEntity.ok(resp);
    }

    // ---------- request / response bodies ----------

    @Setter
    @Getter
    public static class GenerateRequest {
        private String matchedEvent;
        private String language;
        /** 目标生成总条数，默认 1000。 */
        private Integer total;
    }

    @Setter
    @Getter
    public static class GenerateResponse {
        private String matchedEvent;
        private int saved;
    }

    @Setter
    @Getter
    public static class CountResponse {
        private String matchedEvent;
        private String language;
        private int total;
    }

    @Setter
    @Getter
    public static class DeleteResponse {
        private int deleted;
    }
}
