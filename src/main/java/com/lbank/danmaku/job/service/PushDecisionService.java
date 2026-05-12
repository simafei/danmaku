package com.lbank.danmaku.job.service;

import com.lbank.danmaku.job.config.DanmakuProperties;
import com.lbank.danmaku.job.domain.Decision;
import com.lbank.danmaku.job.domain.TgPushDecisionLog;
import com.lbank.danmaku.job.domain.TgRawMessage;
import com.lbank.danmaku.job.dto.AiDanmakuResult;
import com.lbank.danmaku.job.mapper.TgPushDecisionLogMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PushDecisionService {
    private final DanmakuProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final TgPushDecisionLogMapper decisionLogMapper;

    public PushDecisionService(
            DanmakuProperties properties,
            StringRedisTemplate redisTemplate,
            TgPushDecisionLogMapper decisionLogMapper) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.decisionLogMapper = decisionLogMapper;
    }

    public TgPushDecisionLog decide(TgRawMessage rawMessage, AiDanmakuResult aiResult) {
        TgPushDecisionLog log = baseLog(rawMessage, aiResult);
        String reason = rejectReason(rawMessage, aiResult);
        if (reason != null) {
            log.setDecision(Decision.DISCARD);
            log.setDecisionReason(reason);
            decisionLogMapper.insert(log);
            return log;
        }
        // 以原始消息文本做去重，相同事件下相同内容短时间内不重复推
        String text = nullToEmpty(rawMessage.getNormalizedText());
        String dedupeKey = "tg:dedupe:" + sha256(aiResult.getMatchedEvent() + ":" + text);
        log.setDedupeKey(dedupeKey);
        Boolean dedupeSet = redisTemplate.opsForValue().setIfAbsent(
                dedupeKey,
                String.valueOf(rawMessage.getId()),
                Duration.ofSeconds(properties.getDecision().getDuplicateTtlSeconds()));
        if (Boolean.FALSE.equals(dedupeSet)) {
            log.setDecision(Decision.HOLD);
            log.setDecisionReason("duplicate_similar_content");
            decisionLogMapper.insert(log);
            return log;
        }
        String rateKey = "tg:rate:event:" + aiResult.getMatchedEvent();
        Boolean rateSet = redisTemplate.opsForValue().setIfAbsent(
                rateKey,
                String.valueOf(rawMessage.getId()),
                Duration.ofSeconds(properties.getDecision().getSymbolRateLimitSeconds()));
        if (Boolean.FALSE.equals(rateSet)) {
            log.setDecision(Decision.HOLD);
            log.setDecisionReason("event_rate_limited");
            log.setRateLimited(true);
            decisionLogMapper.insert(log);
            return log;
        }
        log.setDecision(Decision.PUSH);
        log.setDecisionReason("pushable");
        log.setRateLimited(false);
        decisionLogMapper.insert(log);
        return log;
    }

    private TgPushDecisionLog baseLog(TgRawMessage rawMessage, AiDanmakuResult aiResult) {
        TgPushDecisionLog log = new TgPushDecisionLog();
        log.setRawMessageId(rawMessage.getId());
        log.setLanguage(rawMessage.getLanguage());
        log.setSymbol(aiResult.getMatchedEvent());
        log.setTopic(aiResult.getTopic());
        log.setRateLimited(false);
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }

    private String rejectReason(TgRawMessage rawMessage, AiDanmakuResult aiResult) {
        if (Boolean.TRUE.equals(rawMessage.getSenderIsAdmin())) {
            return "admin_message";
        }
        if (Boolean.TRUE.equals(rawMessage.getHasMedia())) {
            return "non_text_message";
        }
        if (aiResult.isAd()) {
            return "ad:" + nullToEmpty(aiResult.getAdReason());
        }
        if (!aiResult.isDisplayable()) {
            return "not_displayable";
        }
        if (!StringUtils.hasText(aiResult.getMatchedEvent())) {
            return "no_matched_event";
        }
        if (aiResult.getConfidence() == null || aiResult.getConfidence() < properties.getDecision().getMinConfidence()) {
            return "low_confidence";
        }
        // 原始消息超过字数限制不推送弹幕
        String text = nullToEmpty(rawMessage.getNormalizedText());
        if (text.length() > properties.getDecision().getMaxContentLength()) {
            return "content_too_long";
        }
        return null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
