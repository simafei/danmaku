package com.example.danmaku.client;

import com.example.danmaku.dto.DanmakuSendRequest;
import com.example.danmaku.dto.DanmakuSendResult;

public interface DanmakuSenderClient {
    DanmakuSendResult send(DanmakuSendRequest request);
}
