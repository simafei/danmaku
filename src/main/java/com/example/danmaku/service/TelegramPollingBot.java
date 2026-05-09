package com.example.danmaku.service;

import com.example.danmaku.config.DanmakuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@ConditionalOnProperty(prefix = "danmaku.telegram.polling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TelegramPollingBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {
    private static final Logger log = LoggerFactory.getLogger(TelegramPollingBot.class);

    private final DanmakuProperties properties;
    private final TelegramMessageCollector messageCollector;

    public TelegramPollingBot(
            DanmakuProperties properties,
            TelegramMessageCollector messageCollector) {
        this.properties = properties;
        this.messageCollector = messageCollector;
    }

    @Override
    public String getBotToken() {
        String token = properties.getTelegram().getBotToken();
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("danmaku.telegram.bot-token must be configured when polling is enabled");
        }
        return token;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        // SDK polling 入口只负责接收消息并快速落库，后续 AI 和推送仍由 worker 异步处理。
        // 捕获所有异常，避免单条 update 处理失败导致 SDK polling 线程中断。
        try {
            messageCollector.collect(update);
        } catch (Exception e) {
            log.error("Failed to collect update updateId={}", update == null ? null : update.getUpdateId(), e);
        }
    }
}
