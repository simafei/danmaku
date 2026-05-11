package com.example.danmaku.service;

import com.example.danmaku.client.DanmakuSenderClient;
import com.example.danmaku.domain.DanmakuPushLog;
import com.example.danmaku.domain.TgPushDecisionLog;
import com.example.danmaku.domain.TgRawMessage;
import com.example.danmaku.dto.AiDanmakuResult;
import com.example.danmaku.dto.DanmakuSendRequest;
import com.example.danmaku.dto.DanmakuSendResult;
import com.example.danmaku.mapper.DanmakuPushLogMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class DanmakuPushService {
    private final DanmakuSenderClient senderClient;
    private final DanmakuPushLogMapper pushLogMapper;
    private final DanmakuContentService contentService;

    public DanmakuPushService(
            DanmakuSenderClient senderClient,
            DanmakuPushLogMapper pushLogMapper,
            DanmakuContentService contentService) {
        this.senderClient = senderClient;
        this.pushLogMapper = pushLogMapper;
        this.contentService = contentService;
    }

    public void push(
            TgRawMessage rawMessage,
            AiDanmakuResult aiResult,
            TgPushDecisionLog decisionLog) {
        String content = contentService.buildContent(rawMessage, aiResult);
        DanmakuSendRequest request = new DanmakuSendRequest();
        request.setRawMessageId(rawMessage.getId());
        request.setSymbol(aiResult.getSymbol());
        request.setLanguage(rawMessage.getLanguage());
        request.setContent(content);
        request.setEventType(aiResult.getEventType());
        request.setSentiment(aiResult.getSentiment());
        request.setTopic(aiResult.getTopic());
        request.setConfidence(aiResult.getConfidence());
        request.setMarketType(aiResult.getMarketType());
        request.setContentStyle("human_rewrite");
        request.setTemplateId("ai_direct");

        DanmakuSendResult result = senderClient.send(request);
        DanmakuPushLog log = new DanmakuPushLog();
        log.setRawMessageId(rawMessage.getId());
        log.setDecisionId(decisionLog.getId());
        log.setSymbol(aiResult.getSymbol());
        log.setLanguage(rawMessage.getLanguage());
        log.setTopic(aiResult.getTopic());
        log.setPushContent(content);
        log.setPushStatus(result.isSuccess() ? "success" : "failed");
        log.setResponseBody(result.getResponseBody());
        log.setRequestId(result.getRequestId());
        log.setPushedAt(LocalDateTime.now());
        pushLogMapper.insert(log);
    }

}
