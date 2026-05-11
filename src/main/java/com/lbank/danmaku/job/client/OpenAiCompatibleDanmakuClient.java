package com.lbank.danmaku.job.client;

import com.lbank.danmaku.job.config.DanmakuProperties;
import com.lbank.danmaku.job.dto.AiPromptRequest;
import com.lbank.danmaku.job.dto.AiPromptResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 基于 OpenAI 兼容接口的 AI 弹幕客户端实现。
 *
 * 当 danmaku.ai.api-key 配置不为空时生效，自动替代 StubAiDanmakuClient。
 */
@Component
@ConditionalOnExpression("!'${danmaku.ai.api-key:}'.isEmpty()")
public class OpenAiCompatibleDanmakuClient implements AiDanmakuClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleDanmakuClient.class);

    private final DanmakuProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleDanmakuClient(
            DanmakuProperties properties,
            RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiPromptResponse complete(AiPromptRequest request) {
        DanmakuProperties.Ai ai = properties.getAi();
        String url = ai.getBaseUrl() + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(ai.getApiKey());

        Map<String, Object> body = buildRequestBody(request, ai);

        String rawResponse = null;
        try {
            rawResponse = restTemplate.postForObject(
                    url, new HttpEntity<>(body, headers), String.class);
            return parseResponse(rawResponse, request.getModel());
        } catch (Exception e) {
            log.error("AI API call failed, model={}", request.getModel(), e);
            AiPromptResponse resp = new AiPromptResponse();
            resp.setModel(request.getModel());
            resp.setRawResponse(rawResponse);
            return resp;
        }
    }

    private Map<String, Object> buildRequestBody(AiPromptRequest request, DanmakuProperties.Ai ai) {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", request.getSystemPrompt()),
                Map.of("role", "user", "content", request.getUserPrompt())
        );

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", request.getModel() != null ? request.getModel() : ai.getModel());
        body.put("messages", messages);
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        // json_object 格式强制 AI 输出合法 JSON
        if ("json_object".equals(request.getResponseFormat())) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    private AiPromptResponse parseResponse(String rawResponse, String requestModel) {
        AiPromptResponse resp = new AiPromptResponse();
        resp.setRawResponse(rawResponse);
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            // 取 choices[0].message.content
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isMissingNode()) {
                resp.setContent(content.asText());
            }
            // 取实际使用的模型名
            JsonNode model = root.path("model");
            resp.setModel(model.isMissingNode() ? requestModel : model.asText());
            // 取请求 ID
            JsonNode requestId = root.path("id");
            if (!requestId.isMissingNode()) {
                resp.setRequestId(requestId.asText());
            }
        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", rawResponse, e);
            resp.setModel(requestModel);
        }
        return resp;
    }
}
