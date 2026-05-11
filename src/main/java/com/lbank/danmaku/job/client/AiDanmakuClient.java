package com.lbank.danmaku.job.client;

import com.lbank.danmaku.job.dto.AiPromptRequest;
import com.lbank.danmaku.job.dto.AiPromptResponse;

/**
 * 外部 OpenAI 兼容调用接口。
 *
 * 这里不要绑定业务对象，只保留标准 AI 调用需要的模型、系统提示词、
 * 用户提示词和响应格式。后续接 OpenAI 官方接口时，实现这个方法即可。
 */
public interface AiDanmakuClient {
    AiPromptResponse complete(AiPromptRequest request);
}
