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
    private final com.traderakshak.service.MarketCalendarService marketCalendar;

    /**
     * Synchronization progress, plus the CURRENT exchange session.
     *
     * <p>market_status used to come straight from the holder, where it was written once as a side
     * effect of the startup sync - and that sync defaults to off (sync.enabled=false), so on this
     * deployment the field was never written at all and the endpoint served its initialiser,
     * "UNKNOWN", for the life of the process. Even with the sync enabled it was a value frozen at
     * startup: correct for a few hours, then wrong at the next session boundary.
     *
     * <p>Overwritten here from the calendar on every request, which is cheap (a cached date set
     * and two time comparisons) and cannot go stale. The holder keeps its own copy for the sync
     * log; this is what the screens read.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        Map<String, Object> snap = status.snapshot();
        try {
            snap.put("market_status", marketCalendar.status().name());
            snap.put("market_status_at", java.time.ZonedDateTime.now(marketCalendar.zone()).toString());
            snap.put("exchange_timezone", marketCalendar.zone().getId());
            snap.put("trading_date", marketCalendar.today().toString());
        } catch (Exception e) {
            // A calendar failure is not a closed market, and must not be reported as one.
            snap.put("market_status", "DATA_UNAVAILABLE");
            snap.put("market_status_error", e.getMessage());
        }
        return ResponseEntity.ok(ApiResponse.ok(snap));
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
