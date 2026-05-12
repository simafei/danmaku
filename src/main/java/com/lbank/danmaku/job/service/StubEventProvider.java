package com.lbank.danmaku.job.service;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/**
 * EventProvider 默认占位实现。
 *
 * 返回常见加密货币交易对，供开发和测试使用。
 * 接入真实事件数据源后此 Bean 自动失效。
 */
@Service
@ConditionalOnMissingBean(EventProvider.class)
public class StubEventProvider implements EventProvider {

    private static final List<String> DEFAULT_EVENTS = List.of(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "BNBUSDT",
            "DOGEUSDT", "ADAUSDT", "AVAXUSDT", "LINKUSDT", "DOTUSDT",
            "MATICUSDT", "UNIUSDT", "LTCUSDT", "BCHUSDT", "ATOMUSDT"
    );

    @Override
    public List<String> getActiveEvents() {
        return DEFAULT_EVENTS;
    }
}
