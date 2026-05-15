package com.lbank.danmaku.job.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbank.danmaku.job.config.DanmakuProperties;
import com.lbank.danmaku.job.dto.AiPromptRequest;
import com.lbank.danmaku.job.dto.AiPromptResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 基于 OpenAI 兼容接口的 AI 弹幕客户端实现。
 *
 * 当 danmaku.ai.api-key 配置不为空时生效，自动替代 StubAiDanmakuClient。
 */
@Component
@ConditionalOnExpression("!'${danmaku.ai.api-key:}'.isEmpty()")
public class OpenAiCompatibleDanmakuClient implements AiDanmakuClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleDanmakuClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final DanmakuProperties properties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleDanmakuClient(
            DanmakuProperties properties,
            OkHttpClient httpClient,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiPromptResponse complete(AiPromptRequest request) {
        DanmakuProperties.Ai ai = properties.getAi();
        String url = ai.getBaseUrl() + "/chat/completions";
        String rawBody = null;
        try {
            String jsonPayload = objectMapper.writeValueAsString(buildRequestBody(request, ai));
            Request httpRequest = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + ai.getApiKey())
                    .post(RequestBody.create(jsonPayload, JSON))
                    .build();
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                rawBody = response.body() != null ? response.body().string() : null;
                if (!response.isSuccessful()) {
                    log.error("AI API returned HTTP {}: {}", response.code(), rawBody);
                    return errorResponse(request.getModel(), rawBody);
                }
                return parseResponse(rawBody, request.getModel());
            }
        } catch (Exception e) {
            log.error("AI API call failed, model={}", request.getModel(), e);
            return errorResponse(request.getModel(), rawBody);
        }
    }

    private Map<String, Object> buildRequestBody(AiPromptRequest request, DanmakuProperties.Ai ai) {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", request.getSystemPrompt()),
                Map.of("role", "user",   "content", request.getUserPrompt())
        );
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel() != null ? request.getModel() : ai.getModel());
        body.put("messages", messages);
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if ("json_object".equals(request.getResponseFormat())) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    private AiPromptResponse parseResponse(String rawBody, String requestModel) {
        AiPromptResponse resp = new AiPromptResponse();
        resp.setRawResponse(rawBody);
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isMissingNode()) {
                resp.setContent(content.asText());
            }
            JsonNode model = root.path("model");
            resp.setModel(model.isMissingNode() ? requestModel : model.asText());
            JsonNode requestId = root.path("id");
            if (!requestId.isMissingNode()) {
                resp.setRequestId(requestId.asText());
            }
        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", rawBody, e);
            resp.setModel(requestModel);
        }
        return resp;
    }

    private AiPromptResponse errorResponse(String model, String rawBody) {
        AiPromptResponse resp = new AiPromptResponse();
        resp.setModel(model);
        resp.setRawResponse(rawBody);
        return resp;
    }
}
