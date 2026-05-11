package com.lbank.danmaku.job.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lbank.danmaku.job.domain.IngestStatus;
import com.lbank.danmaku.job.domain.TgGroupConfig;
import com.lbank.danmaku.job.domain.TgRawMessage;
import com.lbank.danmaku.job.mapper.TgGroupConfigMapper;
import com.lbank.danmaku.job.mapper.TgRawMessageMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOrigin;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginChannel;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginChat;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginHiddenUser;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginUser;

@Service
public class TelegramMessageCollector {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final TgGroupConfigMapper groupConfigMapper;
    private final TgRawMessageMapper rawMessageMapper;
    private final AdminCacheService adminCacheService;
    private final ObjectMapper objectMapper;

    public TelegramMessageCollector(
            TgGroupConfigMapper groupConfigMapper,
            TgRawMessageMapper rawMessageMapper,
            AdminCacheService adminCacheService,
            ObjectMapper objectMapper) {
        this.groupConfigMapper = groupConfigMapper;
        this.rawMessageMapper = rawMessageMapper;
        this.adminCacheService = adminCacheService;
        this.objectMapper = objectMapper;
    }

    public void collect(Update update) {
        if (update == null || !update.hasMessage()) {
            return;
        }
        Message message = update.getMessage();
        Chat chat = message.getChat();
        if (chat == null) {
            return;
        }
        TgGroupConfig groupConfig = loadEnabledGroup(chat.getId());
        if (groupConfig == null || !isCollectable(message, chat.getId())) {
            return;
        }
        saveRawMessage(update, message, groupConfig);
    }

    private TgGroupConfig loadEnabledGroup(Long groupId) {
        return groupConfigMapper.selectOne(new LambdaQueryWrapper<TgGroupConfig>()
                .eq(TgGroupConfig::getGroupId, groupId)
                .eq(TgGroupConfig::getEnabled, true)
                .last("limit 1"));
    }

    private boolean isCollectable(Message message, Long groupId) {
        // 只采集纯文本消息；图片、视频、语音、文件和 caption 不进入弹幕链路。
        if (!message.hasText() || !StringUtils.hasText(message.getText())) {
            return false;
        }
        if (message.hasPhoto() || message.hasVideo() || message.hasVoice() || message.hasDocument()) {
            return false;
        }
        User sender = message.getFrom();
        if (sender == null || sender.getIsBot()) {
            return false;
        }
        return !adminCacheService.isAdmin(groupId, sender.getId());
    }

    private void saveRawMessage(Update update, Message message, TgGroupConfig groupConfig) {
        TgRawMessage rawMessage = buildRawMessage(update, message, groupConfig);
        try {
            rawMessageMapper.insert(rawMessage);
        } catch (DuplicateKeyException ignored) {
            // Long polling 可能在重启或 offset 交接时拿到重复 update；唯一键命中说明已经入库。
        }
    }

    private TgRawMessage buildRawMessage(Update update, Message message, TgGroupConfig groupConfig) {
        LocalDateTime now = LocalDateTime.now();
        Chat chat = message.getChat();
        User sender = message.getFrom();

        TgRawMessage raw = new TgRawMessage();
        raw.setUpdateId(toLong(update.getUpdateId()));
        raw.setGroupId(chat.getId());
        raw.setGroupName(chat.getTitle());
        raw.setLanguage(groupConfig.getLanguage());
        raw.setMessageId(toLong(message.getMessageId()));
        raw.setSenderIsAdmin(false);
        fillSender(raw, sender);
        fillReply(raw, message.getReplyToMessage());
        fillForward(raw, message);
        raw.setSentAt(fromEpoch(message.getDate()));
        raw.setText(message.getText());
        raw.setNormalizedText(normalize(message.getText()));
        raw.setEntitiesJson(writeJson(message.getEntities()));
        raw.setHasLink(hasLink(message.getEntities()));
        raw.setHasMedia(false);
        raw.setIngestStatus(IngestStatus.PENDING);
        raw.setRetryCount(0);
        raw.setCreatedAt(now);
        raw.setUpdatedAt(now);
        return raw;
    }

    private void fillSender(TgRawMessage raw, User sender) {
        if (sender == null) {
            return;
        }
        raw.setSenderId(sender.getId());
        raw.setSenderFirstName(sender.getFirstName());
        raw.setSenderLastName(sender.getLastName());
        raw.setSenderUsername(sender.getUserName());
        raw.setSenderName(joinName(sender));
    }

    private void fillReply(TgRawMessage raw, Message reply) {
        if (reply == null) {
            return;
        }
        raw.setReplyToMessageId(toLong(reply.getMessageId()));
        raw.setReplyToText(reply.getText());
    }

    private void fillForward(TgRawMessage raw, Message message) {
        // Bot API 7.0 起转发来源统一用 forward_origin，旧字段已废弃。
        MessageOrigin origin = message.getForwardOrigin();
        if (origin == null) {
            return;
        }
        if (origin instanceof MessageOriginUser user) {
            raw.setForwardDate(fromEpoch(user.getDate()));
            raw.setForwardFromId(user.getSenderUser().getId());
            raw.setForwardFromUsername(user.getSenderUser().getUserName());
        } else if (origin instanceof MessageOriginHiddenUser hidden) {
            raw.setForwardDate(fromEpoch(hidden.getDate()));
            raw.setForwardFromUsername(hidden.getSenderUserName());
        } else if (origin instanceof MessageOriginChat chat) {
            raw.setForwardDate(fromEpoch(chat.getDate()));
            raw.setForwardFromChatId(chat.getSenderChat().getId());
        } else if (origin instanceof MessageOriginChannel channel) {
            raw.setForwardDate(fromEpoch(channel.getDate()));
            raw.setForwardFromChatId(channel.getChat().getId());
        }
    }

    private String normalize(String text) {
        return text == null ? null : text.trim().replaceAll("\\s+", " ");
    }

    private String joinName(User user) {
        return (nullToEmpty(user.getFirstName()) + " " + nullToEmpty(user.getLastName())).trim();
    }

    private boolean hasLink(List<MessageEntity> entities) {
        return entities != null && entities.stream()
                .anyMatch(entity -> "url".equals(entity.getType()) || "text_link".equals(entity.getType()));
    }

    private LocalDateTime fromEpoch(Integer epochSecond) {
        if (epochSecond == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond.longValue()), ZONE);
    }

    private Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
