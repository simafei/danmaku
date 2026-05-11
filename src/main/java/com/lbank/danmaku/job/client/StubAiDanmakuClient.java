package com.lbank.danmaku.job.client;

import com.lbank.danmaku.job.dto.AiPromptRequest;
import com.lbank.danmaku.job.dto.AiPromptResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容接口的默认占位实现。
 *
 * 真实项目里实现 AiDanmakuClient 后，这个 Bean 会自动失效；
 * 默认返回 hold，避免未接 AI 时误推送内容。
 */
@Component
@ConditionalOnMissingBean(AiDanmakuClient.class)
public class StubAiDanmakuClient implements AiDanmakuClient {
    @Override
    public AiPromptResponse complete(AiPromptRequest request) {
        AiPromptResponse response = new AiPromptResponse();
        response.setModel(request == null ? null : request.getModel());
        response.setContent("""
                {"decision":"hold","decisionReason":"ai_client_not_implemented","ad":false,"displayable":false,"confidence":0,"content":""}
                """);
        return response;
    }
}
