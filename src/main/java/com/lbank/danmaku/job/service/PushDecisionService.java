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
        String dedupeKey = "tg:dedupe:" + sha256(aiResult.getSymbol() + ":" + aiResult.getTopic());
        log.setDedupeKey(dedupeKey);
        // Redis 只做短时间窗口记忆，用于拦截重复或近似重复消息；MySQL 仍是审计来源。
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
        String rateKey = "tg:rate:symbol:" + aiResult.getSymbol();
        // 单币对限频只是保护阈值，产品决策仍然是逐条消息 push / discard / hold。
        Boolean rateSet = redisTemplate.opsForValue().setIfAbsent(
                rateKey,
                String.valueOf(rawMessage.getId()),
                Duration.ofSeconds(properties.getDecision().getSymbolRateLimitSeconds()));
        if (Boolean.FALSE.equals(rateSet)) {
            log.setDecision(Decision.HOLD);
            log.setDecisionReason("symbol_rate_limited");
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
        log.setSymbol(aiResult.getSymbol());
        log.setEventType(aiResult.getEventType());
        log.setSentiment(aiResult.getSentiment());
        log.setTopic(aiResult.getTopic());
        log.setFinalContent(null);
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
        if (!StringUtils.hasText(aiResult.getSymbol())) {
            return "no_symbol";
        }
        if (aiResult.getConfidence() == null || aiResult.getConfidence() < properties.getDecision().getMinConfidence()) {
            return "low_confidence";
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
