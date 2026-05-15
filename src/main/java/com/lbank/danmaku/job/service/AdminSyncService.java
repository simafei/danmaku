package com.lbank.danmaku.job.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbank.danmaku.job.config.DanmakuProperties;
import com.lbank.danmaku.job.domain.TgGroupConfig;
import com.lbank.danmaku.job.mapper.TgGroupConfigMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 从 Telegram Bot API 同步各群的管理员列表，写入 Redis 供 AdminCacheService 使用。
 *
 * Redis key 格式：tg:admin:{groupId}:{userId} = "1"，TTL = 2 小时。
 * 每小时定时刷新，启动时也会执行一次，保证服务启动后立即生效。
 */
@Service
public class AdminSyncService {
    private static final Logger log = LoggerFactory.getLogger(AdminSyncService.class);
    private static final Duration ADMIN_TTL = Duration.ofHours(2);
    private static final String TG_API_BASE = "https://api.telegram.org";

    private final DanmakuProperties properties;
    private final TgGroupConfigMapper groupConfigMapper;
    private final AdminCacheService adminCacheService;
    private final StringRedisTemplate redisTemplate;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AdminSyncService(
            DanmakuProperties properties,
            TgGroupConfigMapper groupConfigMapper,
            AdminCacheService adminCacheService,
            StringRedisTemplate redisTemplate,
            OkHttpClient httpClient,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.groupConfigMapper = groupConfigMapper;
        this.adminCacheService = adminCacheService;
        this.redisTemplate = redisTemplate;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** 启动完成后立即同步一次，确保服务起来就能过滤管理员消息 */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (!StringUtils.hasText(properties.getTelegram().getBotToken())) {
            log.info("Bot token not configured, skipping admin sync");
            return;
        }
        syncAll();
    }

    /** 每小时刷新一次，防止管理员变更后缓存过期，在 XXL-JOB 控制台配置 cron */
    @XxlJob("adminSyncJobHandler")
    public void scheduledSync() {
        if (!StringUtils.hasText(properties.getTelegram().getBotToken())) {
            XxlJobHelper.log("Bot token not configured, skipping admin sync");
            return;
        }
        syncAll();
    }

    /** 同步所有已启用群的管理员 */
    public void syncAll() {
        List<TgGroupConfig> groups = groupConfigMapper.selectList(
                new LambdaQueryWrapper<TgGroupConfig>().eq(TgGroupConfig::getEnabled, true));
        log.info("Syncing admins for {} enabled groups", groups.size());
        XxlJobHelper.log("Syncing admins for {0} enabled groups", groups.size());
        for (TgGroupConfig group : groups) {
            try {
                syncGroup(group.getGroupId());
            } catch (Exception e) {
                log.error("Failed to sync admins for groupId={}", group.getGroupId(), e);
                XxlJobHelper.log("Failed to sync admins for groupId={0}: {1}", group.getGroupId(), e.toString());
            }
        }
    }

    /** 同步单个群的管理员，返回写入 Redis 的管理员数量 */
    public int syncGroup(Long groupId) {
        String token = properties.getTelegram().getBotToken();
        String url = TG_API_BASE + "/bot" + token + "/getChatAdministrators?chat_id=" + groupId;
        Request request = new Request.Builder().url(url).get().build();

        String responseBody = null;
        try (Response response = httpClient.newCall(request).execute()) {
            responseBody = response.body() != null ? response.body().string() : null;
            if (!response.isSuccessful()) {
                log.warn("Telegram API returned HTTP {} for groupId={}: {}", response.code(), groupId, responseBody);
                return 0;
            }
        } catch (Exception e) {
            log.error("Failed to call getChatAdministrators for groupId={}", groupId, e);
            return 0;
        }

        List<Long> adminIds = parseAdminIds(responseBody);
        for (Long userId : adminIds) {
            redisTemplate.opsForValue().set(
                    adminCacheService.adminKey(groupId, userId), "1", ADMIN_TTL);
        }
        log.info("Synced {} admins for groupId={}", adminIds.size(), groupId);
        XxlJobHelper.log("Synced {0} admins for groupId={1}", adminIds.size(), groupId);
        return adminIds.size();
    }

    private List<Long> parseAdminIds(String responseBody) {
        List<Long> ids = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (!root.path("ok").asBoolean()) {
                log.warn("Telegram API returned ok=false: {}", responseBody);
                return ids;
            }
            for (JsonNode member : root.path("result")) {
                JsonNode user = member.path("user");
                if (!user.isMissingNode() && !user.path("id").isMissingNode()) {
                    ids.add(user.path("id").asLong());
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse getChatAdministrators response", e);
        }
        return ids;
    }
}
