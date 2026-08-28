package com.traderakshak.controller;

import com.traderakshak.util.UserMessage;

// =============================================================================
// SupportResistanceController — TradeRakshak™ v11.4
// Dedicated REST endpoints for S&R engine (Sahi App logic)
//
// Routes:
//   GET /sr/indices       → all 4 indices (NIFTY50, BANKNIFTY, FINNIFTY, NIFTY500)
//   GET /sr/index/{key}   → single index by key
//
// Enterprise Policy:
//   • Backward-compatible: /dashboard still embeds SR via DashboardController
//   • Error-safe: returns ApiResponse with error field instead of HTTP 500
//   • Security: authenticated users only (JWT via SecurityConfig)
//   • Impact: Dashboard, Strategy Builder, Backtesting all consume SR data
// =============================================================================

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.service.SupportResistanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/sr")
@RequiredArgsConstructor
@Tag(name = "Support & Resistance", description = "Sahi-logic S&R engine: Pivot + Fib + Fractal + Volume Profile for major NSE indices")
public class SupportResistanceController {

    private final SupportResistanceService srService;

    // ── GET /sr/indices — All 4 indices in single call (dashboard widget) ────
    @GetMapping("/indices")
    @Operation(summary = "S&R levels for all 4 indices — primary dashboard endpoint",
               description = "Returns NIFTY50, BANKNIFTY, FINNIFTY, NIFTY500 S&R levels " +
                             "computed via Pivot (Daily/Weekly/Monthly) + Fibonacci + Fractal + Volume Profile + Psychological levels. " +
                             "Each level includes grade (VERY STRONG/STRONG/MODERATE/WEAK), confluence count, " +
                             "proximity to CMP, and touch count — identical to Sahi App logic.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllIndices(
            @AuthenticationPrincipal User user) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(srService.getAllIndicesSR()));
        } catch (Exception e) {
            return ResponseEntity.ok(
                ApiResponse.error("SR_ENGINE_ERROR", UserMessage.of(e, "Support and resistance levels could not be calculated for this stock.")));
        }
    }

    // ── GET /sr/index/{key} — Single index ───────────────────────────────────
    @GetMapping("/index/{key}")
    @Operation(summary = "S&R levels for a single index",
               description = "key: NIFTY50 | BANKNIFTY | FINNIFTY | NIFTY500")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getIndex(
            @PathVariable String key,
            @AuthenticationPrincipal User user) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(srService.getIndexSR(key)));
        } catch (Exception e) {
            return ResponseEntity.ok(
                ApiResponse.error("SR_ENGINE_ERROR", "S&R computation failed for " + key + ": " + UserMessage.of(e)));
        }
    }
}
