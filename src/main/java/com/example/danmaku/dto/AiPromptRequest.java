package com.example.danmaku.dto;

import lombok.Data;

/**
 * 标准 OpenAI 兼容调用入参。
 *
 * 业务层负责把 TG 消息和上下文组织成提示词；外部实现只需要把这些字段
 * 映射到 OpenAI 的请求结构里。
 */
@Data
public class AiPromptRequest {
    /** 模型名称，例如 gpt-4.1-mini、gpt-4o-mini 等。 */
    private String model;
    /** 系统提示词，用于约束 AI 的角色、输出格式和安全规则。 */
    private String systemPrompt;
    /** 用户提示词，包含当前消息、上下文和目标语言等业务输入。 */
    private String userPrompt;
    /** 响应格式；前期建议固定为 json_object，方便稳定解析。 */
    private String responseFormat;
    /** 温度参数；弹幕需要自然但不能太飘，建议使用较低到中等的值。 */
    private Double temperature;
}
