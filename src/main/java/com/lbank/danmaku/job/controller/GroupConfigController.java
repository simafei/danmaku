package com.lbank.danmaku.job.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lbank.danmaku.job.domain.TgGroupConfig;
import com.lbank.danmaku.job.mapper.TgGroupConfigMapper;
import com.lbank.danmaku.job.service.AdminSyncService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/groups")
public class GroupConfigController {

    private final TgGroupConfigMapper groupConfigMapper;
    private final AdminSyncService adminSyncService;

    public GroupConfigController(TgGroupConfigMapper groupConfigMapper, AdminSyncService adminSyncService) {
        this.groupConfigMapper = groupConfigMapper;
        this.adminSyncService = adminSyncService;
    }

    /** 查询所有群配置 */
    @GetMapping
    public List<TgGroupConfig> list() {
        return groupConfigMapper.selectList(
                new LambdaQueryWrapper<TgGroupConfig>().orderByAsc(TgGroupConfig::getId));
    }

    /** 新增群配置 */
    @PostMapping
    public ResponseEntity<TgGroupConfig> create(@RequestBody CreateGroupRequest req) {
        if (req.getGroupId() == null || req.getLanguage() == null || req.getLanguage().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        boolean exists = groupConfigMapper.selectCount(
                new LambdaQueryWrapper<TgGroupConfig>()
                        .eq(TgGroupConfig::getGroupId, req.getGroupId())) > 0;
        if (exists) {
            return ResponseEntity.status(409).build();
        }
        TgGroupConfig config = new TgGroupConfig();
        config.setGroupId(req.getGroupId());
        config.setGroupName(req.getGroupName());
        config.setLanguage(req.getLanguage());
        config.setEnabled(req.getEnabled() != null ? req.getEnabled() : true);
        config.setPushEnabled(req.getPushEnabled() != null ? req.getPushEnabled() : true);
        config.setSortWeight(req.getSortWeight() != null ? req.getSortWeight() : 0);
        config.setTrustLevel(0);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        groupConfigMapper.insert(config);
        // 新增群后立即同步一次管理员，确保入群消息立刻被正确过滤
        try {
            adminSyncService.syncGroup(config.getGroupId());
        } catch (Exception ignored) {
            // 同步失败不影响配置写入，下次定时任务会补偿
        }
        return ResponseEntity.ok(config);
    }

    /** 手动触发某个群的管理员同步 */
    @PostMapping("/{groupId}/admins/sync")
    public ResponseEntity<Map<String, Object>> syncAdmins(@PathVariable Long groupId) {
        boolean exists = groupConfigMapper.selectCount(
                new LambdaQueryWrapper<TgGroupConfig>()
                        .eq(TgGroupConfig::getGroupId, groupId)) > 0;
        if (!exists) {
            return ResponseEntity.notFound().build();
        }
        int count = adminSyncService.syncGroup(groupId);
        return ResponseEntity.ok(Map.of("groupId", groupId, "adminCount", count));
    }

    /** 启用或禁用某个群 */
    @PatchMapping("/{groupId}/enabled")
    public ResponseEntity<Void> setEnabled(
            @PathVariable Long groupId,
            @RequestBody EnabledRequest req) {
        int updated = groupConfigMapper.update(null, new LambdaUpdateWrapper<TgGroupConfig>()
                .eq(TgGroupConfig::getGroupId, groupId)
                .set(TgGroupConfig::getEnabled, req.getEnabled())
                .set(TgGroupConfig::getUpdatedAt, LocalDateTime.now()));
        return updated > 0 ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // ---------- request bodies ----------

    @Setter
    @Getter
    public static class CreateGroupRequest {
        private Long groupId;
        private String groupName;
        private String language;
        private Boolean enabled;
        private Boolean pushEnabled;
        private Integer sortWeight;

    }

    @Setter
    @Getter
    public static class EnabledRequest {
        private Boolean enabled;

    }
}
