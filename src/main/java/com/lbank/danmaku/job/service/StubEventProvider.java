package com.lbank.danmaku.job.service;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/**
 * EventProvider 默认占位实现，返回空列表。
 *
 * 事件列表由业务方提供（数据库、配置中心、外部 API 等），
 * 实现 EventProvider 并注册为 Bean 后此占位自动失效。
 */
@Service
@ConditionalOnMissingBean(EventProvider.class)
public class StubEventProvider implements EventProvider {

    @Override
    public List<String> getActiveEvents() {
        return List.of();
    }
}
