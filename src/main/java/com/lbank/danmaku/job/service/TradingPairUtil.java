package com.lbank.danmaku.job.service;

import java.util.regex.Pattern;

/**
 * 交易对识别工具。
 *
 * <p>将具体交易对名称（如 BTCUSDT、btc_usdt）统一映射到抽象交易事件，
 * 避免为 3000+ 个币对各自生成海量弹幕模板。
 *
 * <ul>
 *   <li>合约：全大写字母/数字，无分隔符，如 BTCUSDT、1000SHIBUSDT → {@link #TRADING_PAIR_FUTURE}</li>
 *   <li>现货：小写字母/数字 + 下划线分隔，如 btc_usdt、eth_btc → {@link #TRADING_PAIR_SPOT}</li>
 * </ul>
 */
public final class TradingPairUtil {

    /** 合约交易对的抽象事件 key，模板库使用此 key 存储合约通用弹幕。 */
    public static final String TRADING_PAIR_FUTURE = "__TRADING_PAIR_FUTURE__";

    /** 现货交易对的抽象事件 key，模板库使用此 key 存储现货通用弹幕。 */
    public static final String TRADING_PAIR_SPOT = "__TRADING_PAIR_SPOT__";

    /** 合约币对正则：3–20 位全大写字母或数字，无分隔符，如 BTCUSDT、1000SHIBUSDT。 */
    private static final Pattern FUTURE_PATTERN = Pattern.compile("^[A-Z0-9]{3,20}$");

    /** 现货币对正则：小写字母开头 + 可选数字，下划线分隔，如 btc_usdt、eth_btc。 */
    private static final Pattern SPOT_PATTERN = Pattern.compile("^[a-z][a-z0-9]*_[a-z0-9]+$");

    private TradingPairUtil() {}

    /**
     * 若 event 匹配交易对格式则返回对应的抽象事件 key，否则原样返回。
     *
     * <p>示例：
     * <pre>
     *   normalizeEvent("BTCUSDT")  → "__TRADING_PAIR_FUTURE__"
     *   normalizeEvent("btc_usdt") → "__TRADING_PAIR_SPOT__"
     *   normalizeEvent("美伊战争")  → "美伊战争"
     * </pre>
     */
    public static String normalizeEvent(String event) {
        if (event == null || event.isEmpty()) {
            return event;
        }
        if (FUTURE_PATTERN.matcher(event).matches()) {
            return TRADING_PAIR_FUTURE;
        }
        if (SPOT_PATTERN.matcher(event).matches()) {
            return TRADING_PAIR_SPOT;
        }
        return event;
    }
}
