package com.example.danmaku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.danmaku.domain.TgRawMessage;
import com.example.danmaku.mapper.TgRawMessageMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 上下文消息服务。
 *
 * 前期不做话题表，只取同群近 10 分钟消息作为 AI 生成弹幕的上下文。
 */
@Service
public class ContextMessageService {
    private final TgRawMessageMapper rawMessageMapper;

    public ContextMessageService(TgRawMessageMapper rawMessageMapper) {
        this.rawMessageMapper = rawMessageMapper;
    }

    public List<TgRawMessage> loadNearbyContext(TgRawMessage rawMessage) {
        if (rawMessage.getGroupId() == null || rawMessage.getSentAt() == null) {
            return List.of();
        }
        LocalDateTime from = rawMessage.getSentAt().minusMinutes(10);
        return rawMessageMapper.selectList(new LambdaQueryWrapper<TgRawMessage>()
                .eq(TgRawMessage::getGroupId, rawMessage.getGroupId())
                .ge(TgRawMessage::getSentAt, from)
                .lt(TgRawMessage::getSentAt, rawMessage.getSentAt())
                .orderByDesc(TgRawMessage::getSentAt)
                .last("limit 20"));
    }
}
