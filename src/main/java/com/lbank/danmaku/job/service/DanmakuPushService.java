package com.lbank.danmaku.job.service;

import com.lbank.danmaku.job.client.DanmakuSenderClient;
import com.lbank.danmaku.job.domain.DanmakuPushLog;
import com.lbank.danmaku.job.domain.TgPushDecisionLog;
import com.lbank.danmaku.job.domain.TgRawMessage;
import com.lbank.danmaku.job.dto.AiDanmakuResult;
import com.lbank.danmaku.job.dto.DanmakuSendRequest;
import com.lbank.danmaku.job.dto.DanmakuSendResult;
import com.lbank.danmaku.job.mapper.DanmakuPushLogMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class DanmakuPushService {
    private final DanmakuSenderClient senderClient;
    private final DanmakuPushLogMapper pushLogMapper;

    public DanmakuPushService(
            DanmakuSenderClient senderClient,
            DanmakuPushLogMapper pushLogMapper) {
        this.senderClient = senderClient;
        this.pushLogMapper = pushLogMapper;
    }

    public void push(
            TgRawMessage rawMessage,
            AiDanmakuResult aiResult,
            TgPushDecisionLog decisionLog) {
        // 弹幕内容：原文超过50字时使用 AI 精简版，否则使用原文
        String content = rawMessage.getNormalizedText();
        String simplified = aiResult.getSimplifiedContent();
        if (simplified != null && !simplified.isBlank()) {
            content = simplified;
        }

        DanmakuSendRequest request = new DanmakuSendRequest();
        request.setRawMessageId(rawMessage.getId());
        request.setMatchedEvent(aiResult.getMatchedEvent());
        request.setLanguage(rawMessage.getLanguage());
        request.setContent(content);
        request.setTopic(aiResult.getTopic());
        request.setConfidence(aiResult.getConfidence());
        request.setMarketType(aiResult.getMarketType());

        DanmakuSendResult result = senderClient.send(request);

        DanmakuPushLog log = new DanmakuPushLog();
        log.setRawMessageId(rawMessage.getId());
        log.setDecisionId(decisionLog.getId());
        log.setMatchedEvent(aiResult.getMatchedEvent());
        log.setLanguage(rawMessage.getLanguage());
        log.setPushContent(content);
        log.setPushStatus(result.isSuccess() ? "success" : "failed");
        log.setResponseBody(result.getResponseBody());
        log.setRequestId(result.getRequestId());
        log.setCreateTime(LocalDateTime.now());
        pushLogMapper.insert(log);
    }
}
