package com.lbank.danmaku.job.client;

import com.lbank.danmaku.job.dto.DanmakuSendRequest;
import com.lbank.danmaku.job.dto.DanmakuSendResult;
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
