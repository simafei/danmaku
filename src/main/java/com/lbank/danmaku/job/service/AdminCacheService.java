package com.lbank.danmaku.job.service;

import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminCacheService {
    private final StringRedisTemplate redisTemplate;

    public AdminCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isAdmin(Long groupId, Long userId) {
        if (groupId == null || userId == null) {
            return false;
        }
        String value = redisTemplate.opsForValue().get(adminKey(groupId, userId));
        return Objects.equals("1", value);
    }

    public String adminKey(Long groupId, Long userId) {
        return "tg:admin:" + groupId + ":" + userId;
    }
}
