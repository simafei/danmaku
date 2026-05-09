package com.example.danmaku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.danmaku.config.DanmakuProperties;
import com.example.danmaku.domain.Decision;
import com.example.danmaku.domain.IngestStatus;
import com.example.danmaku.domain.TgPushDecisionLog;
import com.example.danmaku.domain.TgRawMessage;
import com.example.danmaku.dto.AiDanmakuResult;
import com.example.danmaku.mapper.TgRawMessageMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TelegramMessageWorker {
    private static final Logger log = LoggerFactory.getLogger(TelegramMessageWorker.class);

    private final DanmakuProperties properties;
    private final TgRawMessageMapper rawMessageMapper;
    private final ContextMessageService contextMessageService;
    private final AiDanmakuService aiDanmakuService;
    private final PushDecisionService decisionService;
    private final DanmakuPushService pushService;

    public TelegramMessageWorker(
            DanmakuProperties properties,
            TgRawMessageMapper rawMessageMapper,
            ContextMessageService contextMessageService,
            AiDanmakuService aiDanmakuService,
            PushDecisionService decisionService,
            DanmakuPushService pushService) {
        this.properties = properties;
        this.rawMessageMapper = rawMessageMapper;
        this.contextMessageService = contextMessageService;
        this.aiDanmakuService = aiDanmakuService;
        this.decisionService = decisionService;
        this.pushService = pushService;
    }

    @XxlJob("danmakuWorkerJobHandler")
    public void tick() {
        if (!properties.getWorker().isEnabled()) {
            return;
        }
        // 使用数据库驱动 worker，让 SDK polling 线程只负责快速落库，也方便重试、超时回收和后续扩容。
        recoverTimedOut();
        List<TgRawMessage> batch = rawMessageMapper.selectList(new LambdaQueryWrapper<TgRawMessage>()
                .eq(TgRawMessage::getIngestStatus, IngestStatus.PENDING)
                .and(wrapper -> wrapper.isNull(TgRawMessage::getNextRetryAt)
                        .or()
                        .le(TgRawMessage::getNextRetryAt, LocalDateTime.now()))
                .orderByAsc(TgRawMessage::getId)
                .last("limit " + properties.getWorker().getBatchSize()));
        for (TgRawMessage rawMessage : batch) {
            if (claim(rawMessage.getId())) {
                process(rawMessage.getId());
            }
        }
    }

    private boolean claim(Long id) {
        // 乐观抢占，避免多个 worker 同时处理同一条 TG 消息。
        int updated = rawMessageMapper.update(null, new LambdaUpdateWrapper<TgRawMessage>()
                .eq(TgRawMessage::getId, id)
                .eq(TgRawMessage::getIngestStatus, IngestStatus.PENDING)
                .set(TgRawMessage::getIngestStatus, IngestStatus.PROCESSING)
                .set(TgRawMessage::getProcessingStartedAt, LocalDateTime.now())
                .set(TgRawMessage::getUpdatedAt, LocalDateTime.now()));
        return updated == 1;
    }

    private void process(Long id) {
        TgRawMessage rawMessage = rawMessageMapper.selectById(id);
        try {
            // MVP 阶段不再落 analysis/topic 表：AI 直接基于当前消息和上下文输出推送决策与弹幕文案。
            List<TgRawMessage> context = contextMessageService.loadNearbyContext(rawMessage);
            AiDanmakuResult aiResult = aiDanmakuService.generate(rawMessage, context);
            TgPushDecisionLog decisionLog = decisionService.decide(rawMessage, aiResult);
            if (Decision.PUSH.equals(decisionLog.getDecision())) {
                pushService.push(rawMessage, aiResult, decisionLog);
            }
            markDone(rawMessage.getId());
        } catch (Exception e) {
            log.error("Failed to process message id={}", id, e);
            XxlJobHelper.log("Failed to process message id={0}: {1}", id, e.toString());
            markRetryOrFailed(rawMessage, e);
        }
    }

    private void markDone(Long id) {
        rawMessageMapper.update(null, new LambdaUpdateWrapper<TgRawMessage>()
                .eq(TgRawMessage::getId, id)
                .set(TgRawMessage::getIngestStatus, IngestStatus.DONE)
                .set(TgRawMessage::getUpdatedAt, LocalDateTime.now()));
    }

    private void markRetryOrFailed(TgRawMessage rawMessage, Exception e) {
        int retryCount = rawMessage.getRetryCount() == null ? 0 : rawMessage.getRetryCount();
        boolean failed = retryCount + 1 >= properties.getWorker().getMaxRetry();
        rawMessageMapper.update(null, new LambdaUpdateWrapper<TgRawMessage>()
                .eq(TgRawMessage::getId, rawMessage.getId())
                .set(TgRawMessage::getIngestStatus, failed ? IngestStatus.FAILED : IngestStatus.PENDING)
                .set(TgRawMessage::getRetryCount, retryCount + 1)
                .set(TgRawMessage::getNextRetryAt, failed ? null : LocalDateTime.now().plusSeconds(30))
                .set(TgRawMessage::getLastError, e.toString())
                .set(TgRawMessage::getUpdatedAt, LocalDateTime.now()));
    }

    private void recoverTimedOut() {
        LocalDateTime expiredBefore = LocalDateTime.now().minus(properties.getWorker().processingTimeout());
        // 如果进程处理中途挂掉，processing 状态的消息会在超时后回到 pending。
        // 超时同样算一次重试；超过上限后直接标记 FAILED，避免死循环。
        List<TgRawMessage> timedOut = rawMessageMapper.selectList(new LambdaQueryWrapper<TgRawMessage>()
                .eq(TgRawMessage::getIngestStatus, IngestStatus.PROCESSING)
                .le(TgRawMessage::getProcessingStartedAt, expiredBefore));
        for (TgRawMessage msg : timedOut) {
            int retryCount = msg.getRetryCount() == null ? 0 : msg.getRetryCount();
            boolean failed = retryCount + 1 >= properties.getWorker().getMaxRetry();
            log.warn("Recovering timed-out message id={} retry={} failed={}", msg.getId(), retryCount + 1, failed);
            rawMessageMapper.update(null, new LambdaUpdateWrapper<TgRawMessage>()
                    .eq(TgRawMessage::getId, msg.getId())
                    .eq(TgRawMessage::getIngestStatus, IngestStatus.PROCESSING)
                    .set(TgRawMessage::getIngestStatus, failed ? IngestStatus.FAILED : IngestStatus.PENDING)
                    .set(TgRawMessage::getRetryCount, retryCount + 1)
                    .set(TgRawMessage::getNextRetryAt, failed ? null : LocalDateTime.now().plusSeconds(30))
                    .set(TgRawMessage::getLastError, "processing_timeout")
                    .set(TgRawMessage::getUpdatedAt, LocalDateTime.now()));
        }
    }
}
