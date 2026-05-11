package com.lbank.danmaku.job.client;

import com.lbank.danmaku.job.dto.DanmakuSendRequest;
import com.lbank.danmaku.job.dto.DanmakuSendResult;

public interface DanmakuSenderClient {
    DanmakuSendResult send(DanmakuSendRequest request);
}
