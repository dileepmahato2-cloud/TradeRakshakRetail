package com.traderakshak.controller;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.service.AppConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/config")
@RequiredArgsConstructor
@Tag(name = "Application Configuration", description = "Centralized configuration management for all modules")
public class AppConfigController {

    private final AppConfigService configService;

    // ── Read Operations ──────────────────────────────────────────────────────

    @GetMapping("/settings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Get all active settings grouped by category")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllSettings() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settings", configService.getAllSettings());
        result.put("categories", configService.getCategories());
        result.put("config_version", configService.getConfigVersion());
        result.put("cache_size", configService.getCacheSize());
        result.put("last_cache_refresh", configService.getLastCacheRefresh());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/settings/category/{category}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Get settings by category")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getByCategory(@PathVariable String category) {
        return ResponseEntity.ok(ApiResponse.ok(configService.getSettingsByCategory(category)));
    }

    @GetMapping("/settings/{key}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Get single setting by key")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getByKey(@PathVariable String key) {
        return ResponseEntity.ok(ApiResponse.ok(configService.getSettingByKey(key)));
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Get all setting categories")
    public ResponseEntity<ApiResponse<List<String>>> getCategories() {
        return ResponseEntity.ok(ApiResponse.ok(configService.getCategories()));
    }

    // ── Write Operations ─────────────────────────────────────────────────────

    @PutMapping("/settings/{key}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN')")
    @Operation(summary = "Update a single setting value")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSetting(
            @PathVariable String key,
            @RequestBody UpdateSettingRequest req,
            @AuthenticationPrincipal User admin,
            HttpServletRequest httpReq) {
        String ip = httpReq.getRemoteAddr();
        String browser = httpReq.getHeader("User-Agent");
        Map<String, Object> result = configService.updateSetting(key, req.getValue(), admin, req.getReason(), ip, browser);
        return ResponseEntity.ok(ApiResponse.ok("Setting updated successfully.", result));
    }

    @PutMapping("/settings/bulk")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN')")
    @Operation(summary = "Bulk update multiple settings")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> bulkUpdate(
            @RequestBody BulkUpdateRequest req,
            @AuthenticationPrincipal User admin,
            HttpServletRequest httpReq) {
        String ip = httpReq.getRemoteAddr();
        String browser = httpReq.getHeader("User-Agent");
        List<Map<String, Object>> results = configService.bulkUpdate(req.getSettings(), admin, ip, browser);
        return ResponseEntity.ok(ApiResponse.ok("Bulk update completed.", results));
    }

    // ── Reset Operations ─────────────────────────────────────────────────────

    @PostMapping("/settings/reset/category/{category}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN')")
    @Operation(summary = "Reset all settings in a category to defaults")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetCategory(
            @PathVariable String category,
            @AuthenticationPrincipal User admin,
            HttpServletRequest httpReq) {
        int count = configService.resetCategory(category, admin, httpReq.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok("Reset " + count + " settings to defaults.",
            Map.of("category", category, "reset_count", count)));
    }

    @PostMapping("/settings/reset/all")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Reset ALL settings to defaults (Super Admin only)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetAll(
            @AuthenticationPrincipal User admin,
            HttpServletRequest httpReq) {
        int count = configService.resetAll(admin, httpReq.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok("Reset " + count + " settings to defaults.",
            Map.of("reset_count", count)));
    }

    // ── Cache Operations ─────────────────────────────────────────────────────

    @PostMapping("/cache/refresh")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN')")
    @Operation(summary = "Force refresh configuration cache")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshCache() {
        configService.refreshCache();
        return ResponseEntity.ok(ApiResponse.ok("Cache refreshed.",
            Map.of("cache_size", configService.getCacheSize(),
                   "config_version", configService.getConfigVersion())));
    }

    // ── Export / Import ──────────────────────────────────────────────────────

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN')")
    @Operation(summary = "Export all settings as JSON")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> exportSettings() {
        return ResponseEntity.ok(ApiResponse.ok(configService.exportSettings()));
    }

    @PostMapping("/import")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Import settings from JSON (Super Admin only)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importSettings(
            @RequestBody ImportRequest req,
            @AuthenticationPrincipal User admin,
            HttpServletRequest httpReq) {
        int count = configService.importSettings(req.getSettings(), admin, httpReq.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok("Imported " + count + " settings.",
            Map.of("imported_count", count)));
    }

    // ── Audit Log ────────────────────────────────────────────────────────────

    @GetMapping("/audit-log")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN')")
    @Operation(summary = "Get configuration change audit log")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAuditLog(
            @RequestParam(required = false) String settingKey,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(configService.getAuditLog(settingKey, limit)));
    }

    // ── Feature Toggles ──────────────────────────────────────────────────────

    @GetMapping("/feature-toggles")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Get all feature toggle states")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> getFeatureToggles() {
        return ResponseEntity.ok(ApiResponse.ok(configService.getAllFeatureToggles()));
    }

    @GetMapping("/my-features")
    @Operation(summary = "Get feature access for current user (role+plan+overrides)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myFeatures(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(configService.getUserFeatureAccess(user)));
    }

    // ── Public config endpoint for frontend ──────────────────────────────────

    @GetMapping("/public")
    @Operation(summary = "Get public configuration needed by frontend")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPublicConfig(@AuthenticationPrincipal User user) {
        Map<String, Object> publicConfig = new LinkedHashMap<>();
        publicConfig.put("config_version", configService.getConfigVersion());
        publicConfig.put("feature_toggles", configService.getAllFeatureToggles());
        if (user != null) {
            publicConfig.put("user_features", configService.getUserFeatureAccess(user));
        }
        publicConfig.put("trading", Map.of(
            "market_open_time", configService.getString("trading.market_open_time", "09:15"),
            "market_close_time", configService.getString("trading.market_close_time", "15:30"),
            "testing_mode", configService.getBoolean("trading.testing_mode", true)
        ));
        publicConfig.put("top10", Map.of(
            "count", configService.getInt("top10.count", 10),
            "auto_refresh", configService.getBoolean("top10.auto_refresh", true),
            "refresh_interval_sec", configService.getInt("top10.refresh_interval_sec", 300)
        ));
        return ResponseEntity.ok(ApiResponse.ok(publicConfig));
    }

    // ── Request DTOs ─────────────────────────────────────────────────────────

    @Data static class UpdateSettingRequest {
        private String value;
        private String reason;
    }

    @Data static class BulkUpdateRequest {
        private List<Map<String, Object>> settings;
    }

    @Data static class ImportRequest {
        private List<Map<String, Object>> settings;
    }
}
