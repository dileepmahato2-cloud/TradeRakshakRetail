package com.traderakshak.controller;

// =============================================================================
// StockRankingController — TradeRakshak™ v11.5
//
// REST API for the Stock Ranking Engine.
// Routes:
//   POST /ranking/run/{strategyId}          → execute ranking for a strategy
//   GET  /ranking/history/{strategyId}      → ranking run history
//   GET  /ranking/results/{runId}           → results of a specific run
//   GET  /ranking/weights                   → get current weight config
//   PUT  /ranking/weights                   → update weights (Admin only)
//   GET  /ranking/sectors                   → available sectors for filter
//   GET  /ranking/export/{runId}            → export as CSV (Excel/PDF done frontend)
//
// Enterprise Policy:
//   • All routes require JWT authentication
//   • Weight update requires ADMIN or SUPER_ADMIN role
//   • All responses include SEBI disclaimer (via ApiResponse)
//   • Super Admin access controlled by feature_configs table (no code change needed)
//   • Backward compatible — no existing endpoints changed
// =============================================================================

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.service.StockRankingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/ranking")
@RequiredArgsConstructor
@Tag(name = "Stock Ranking", description = "Multi-parameter stock ranking engine — trade selection assistant")
public class StockRankingController {

    private final StockRankingService rankingService;

    @PersistenceContext
    private EntityManager em;

    // ── Run Ranking ────────────────────────────────────────────────────────────
    @PostMapping("/run/{strategyId}")
    @Operation(summary = "Execute stock ranking for a strategy",
               description = "Evaluates all stocks in the strategy's universe using 11 weighted parameters. " +
                             "Returns ranked list with scores, signals, entry/SL/target, and reasons.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runRanking(
            @PathVariable Long strategyId,
            @RequestBody(required = false) Map<String, Object> filters,
            @AuthenticationPrincipal User user) {

        if (filters == null) filters = new HashMap<>();

        // Inject user context into filters
        filters.put("userId",        user.getId());
        filters.put("institutionId", user.getInstitutionId());

        Map<String, Object> result = rankingService.runRanking(
            strategyId, user.getId(), user.getInstitutionId(), filters);

        if (result.containsKey("error")) {
            return ResponseEntity.ok(
                ApiResponse.error("RANK-404", (String) result.get("error")));
        }

        return ResponseEntity.ok(ApiResponse.ok("Ranking completed successfully.", result));
    }

    // ── Ranking History ────────────────────────────────────────────────────────
    @GetMapping("/history/{strategyId}")
    @Operation(summary = "Get ranking run history for a strategy")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getHistory(
            @PathVariable Long strategyId,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(
            ApiResponse.ok(rankingService.getRankingHistory(strategyId, user.getId())));
    }

    // ── Run Results ────────────────────────────────────────────────────────────
    @GetMapping("/results/{runId}")
    @Operation(summary = "Get results of a specific ranking run")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getResults(
            @PathVariable String runId,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(
            ApiResponse.ok(rankingService.getRankingRunResults(runId)));
    }

    // ── Weight Config (read) ───────────────────────────────────────────────────
    @GetMapping("/weights")
    @Operation(summary = "Get current ranking weight configuration")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getWeights(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(
            ApiResponse.ok(rankingService.getWeightConfig(user.getInstitutionId())));
    }

    // ── Weight Config (update — Admin only) ────────────────────────────────────
    @PutMapping("/weights")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','admin','super_admin')")
    @Operation(summary = "Update ranking weights (Admin only — no coding required)",
               description = "Allows admin to change the contribution of each parameter " +
                             "without any source code change or redeployment.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateWeights(
            @RequestBody Map<String, Double> newWeights,
            @AuthenticationPrincipal User user) {
        Map<String, Object> result = rankingService.saveWeights(
            user.getInstitutionId(), user.getId(), newWeights);
        if (Boolean.FALSE.equals(result.get("success"))) {
            return ResponseEntity.ok(
                ApiResponse.error("RANK-400", (String) result.get("error")));
        }
        return ResponseEntity.ok(ApiResponse.ok("Weights updated.", result));
    }

    // ── Available Sectors ──────────────────────────────────────────────────────
    @GetMapping("/sectors")
    @Operation(summary = "List available sectors for ranking filter")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<List<String>>> getSectors(
            @AuthenticationPrincipal User user) {
        try {
            List<String> sectors = em.createNativeQuery(
                "SELECT DISTINCT sector FROM stock_master WHERE exchange='NSE' " +
                "AND sector IS NOT NULL AND sector != '' ORDER BY sector")
                .getResultList();
            return ResponseEntity.ok(ApiResponse.ok(sectors));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(List.of()));
        }
    }

    // ── Export CSV ─────────────────────────────────────────────────────────────
    @GetMapping("/export/{runId}")
    @Operation(summary = "Export ranking results as CSV")
    public ResponseEntity<byte[]> exportCsv(
            @PathVariable String runId,
            @AuthenticationPrincipal User user) {

        List<Map<String, Object>> results = rankingService.getRankingRunResults(runId);

        StringBuilder csv = new StringBuilder();
        // Header
        csv.append("Rank,Symbol,Sector,CMP,Trade Type,Signal,Entry Price,Stop Loss,Target,R:R Ratio,");
        csv.append("Confidence Score,Priority,Backtest Win Rate%,Reason Summary\n");

        // Rows
        for (Map<String, Object> r : results) {
            List<?> reasons = r.get("reasons") instanceof List ? (List<?>) r.get("reasons") : List.of();
            String reasonSummary = reasons.isEmpty() ? "" :
                "\"" + reasons.get(0).toString().replace("\"", "\"\"") + "\"";

            csv.append(r.getOrDefault("rank", "")).append(",")
               .append(r.getOrDefault("symbol", "")).append(",")
               .append(r.getOrDefault("sector", "")).append(",")
               .append(r.getOrDefault("cmp", "")).append(",")
               .append(r.getOrDefault("trade_type", "")).append(",")
               .append(r.getOrDefault("signal", "")).append(",")
               .append(r.getOrDefault("entry_price", "")).append(",")
               .append(r.getOrDefault("stop_loss", "")).append(",")
               .append(r.getOrDefault("target", "")).append(",")
               .append(r.getOrDefault("rr_ratio", "")).append(",")
               .append(r.getOrDefault("composite_score", "")).append(",")
               .append(r.getOrDefault("priority", "")).append(",")
               .append(r.getOrDefault("backtest_win_rate", "")).append(",")
               .append(reasonSummary).append("\n");
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment",
            "TradeRakshak_Ranking_" + runId + ".csv");

        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    // ── List available strategies for ranking dropdown ─────────────────────────
    @GetMapping("/strategies")
    @Operation(summary = "List user strategies available for ranking")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getStrategies(
            @AuthenticationPrincipal User user) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT id, name, strategy_category, timeframe, universe, win_rate " +
                "FROM strategies WHERE user_id=:uid AND status='active' ORDER BY id DESC")
                .setParameter("uid", user.getId()).getResultList();

            List<Map<String, Object>> list = rows.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",       r[0]); m.put("name",     r[1]);
                m.put("category", r[2]); m.put("timeframe",r[3]);
                m.put("universe", r[4]); m.put("win_rate",  r[5]);
                return m;
            }).collect(Collectors.toList());

            return ResponseEntity.ok(ApiResponse.ok(list));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(List.of()));
        }
    }
}
