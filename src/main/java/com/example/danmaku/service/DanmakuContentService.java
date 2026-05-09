package com.example.danmaku.service;

import com.example.danmaku.domain.TgRawMessage;
import com.example.danmaku.dto.AiDanmakuResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DanmakuContentService {
    public String buildContent(
            TgRawMessage rawMessage,
            AiDanmakuResult aiResult) {
        // 最终弹幕必须结合用户原始发言和附近上下文生成，
        // 这样才更接近真实讨论，而不是退化成固定模板。
        return normalizeGeneratedContent(aiResult.getContent(), rawMessage);
    }

    private String normalizeGeneratedContent(String generated, TgRawMessage rawMessage) {
        // 在真实 AI 实现还没接好时保持链路可运行：优先生成文案，其次摘要，最后原文。
        if (StringUtils.hasText(generated)) {
            return generated.trim();
        }
        return rawMessage == null ? "" : rawMessage.getNormalizedText();
    }
}
