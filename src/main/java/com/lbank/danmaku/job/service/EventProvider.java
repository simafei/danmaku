package com.lbank.danmaku.job.service;

import java.util.List;

/**
 * 事件列表提供接口。
 *
 * 实现类负责返回当前活跃的事件/交易对列表，供 AI 匹配使用。
 * 列表内每个元素可以是交易对（如 BTCUSDT）或具名事件（如"BTC 现货 ETF 通过"）。
 */
public interface EventProvider {
    List<String> getActiveEvents();
}
