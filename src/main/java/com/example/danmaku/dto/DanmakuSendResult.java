package com.example.danmaku.dto;

import lombok.Data;

@Data
/**
 * 外部弹幕服务返回结果。
 */
public class DanmakuSendResult {
    /** 是否发送成功。 */
    private boolean success;
    /** 外部服务请求 ID，便于跨系统排查。 */
    private String requestId;
    /** 外部服务原始响应或错误摘要。 */
    private String responseBody;

    public static DanmakuSendResult success(String requestId, String responseBody) {
        DanmakuSendResult result = new DanmakuSendResult();
        result.setSuccess(true);
        result.setRequestId(requestId);
        result.setResponseBody(responseBody);
        return result;
    }

    public static DanmakuSendResult failure(String responseBody) {
        DanmakuSendResult result = new DanmakuSendResult();
        result.setSuccess(false);
        result.setResponseBody(responseBody);
        return result;
    }
}
