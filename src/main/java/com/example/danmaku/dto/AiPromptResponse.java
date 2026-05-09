package com.example.danmaku.dto;

import lombok.Data;

/**
 * 标准 OpenAI 兼容调用出参。
 */
@Data
public class AiPromptResponse {
    /** AI 返回的正文内容；业务层会按 JSON 解析为 AiDanmakuResult。 */
    private String content;
    /** 实际使用的模型名称，便于日志排查。 */
    private String model;
    /** 原始响应体，可选；真实实现里可以保存 OpenAI 返回的完整 JSON。 */
    private String rawResponse;
    /** 请求 ID，可选；方便排查 OpenAI 侧调用问题。 */
    private String requestId;
}
