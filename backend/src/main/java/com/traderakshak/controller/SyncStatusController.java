package com.traderakshak.controller;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.service.StartupSyncService;
import com.traderakshak.service.SyncStatusHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read the current data-synchronization status (also pushed over SSE) and allow
 * a manual re-trigger. Used by the non-modal sync banner in the UI.
 */
@RestController
@RequestMapping("/sync")
@RequiredArgsConstructor
public class SyncStatusController {

    private final SyncStatusHolder status;
    private final StartupSyncService startupSync;
    private final com.traderakshak.service.MarketDataCache cache;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        return ResponseEntity.ok(ApiResponse.ok(status.snapshot()));
    }

    @PostMapping("/run")
    public ResponseEntity<ApiResponse<Map<String, Object>>> run() {
        return ResponseEntity.ok(ApiResponse.ok(startupSync.triggerManualRun()));
    }

    @GetMapping("/cache-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cacheStats() {
        return ResponseEntity.ok(ApiResponse.ok(cache.stats()));
    }
}
