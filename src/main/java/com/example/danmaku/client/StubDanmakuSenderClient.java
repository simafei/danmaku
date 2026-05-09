package com.example.danmaku.client;

import com.example.danmaku.dto.DanmakuSendRequest;
import com.example.danmaku.dto.DanmakuSendResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(DanmakuSenderClient.class)
public class StubDanmakuSenderClient implements DanmakuSenderClient {
    @Override
    public DanmakuSendResult send(DanmakuSendRequest request) {
        return DanmakuSendResult.success("stub-" + request.getRawMessageId(), "stub sender");
    }
}
