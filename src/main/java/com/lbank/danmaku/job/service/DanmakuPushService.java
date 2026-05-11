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
