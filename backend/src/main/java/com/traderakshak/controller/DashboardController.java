package com.traderakshak.controller;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.repository.*;
import com.traderakshak.service.SectorTrendService;
import com.traderakshak.service.SupportResistanceService;
import com.traderakshak.util.DateUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;

/** Replaces Python /dashboard + /api/v1/dashboard routes */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Dashboard stats and market overview")
public class DashboardController {

    private final TradeJournalRepository   journalRepo;
    private final PaperTradeRepository     paperRepo;
    private final StrategyRepository       strategyRepo;
    private final SectorTrendService       sectorService;
    private final SupportResistanceService srService;

    @PersistenceContext
    private EntityManager em;

    @GetMapping
    @Operation(summary = "Dashboard stats — R10 Mobile API")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dashboard(
            @AuthenticationPrincipal User user) {

        // Stats — mirrors Python dashboard() function
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            Object[] sc = (Object[]) em.createNativeQuery(
                "SELECT " +
                "(SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='stock_master'), " +
                "(SELECT COUNT(*) FROM strategies WHERE status='active'), " +
                "(SELECT COUNT(*) FROM paper_trades WHERE status='open'), " +
                "(SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='trade_journal'), " +
                "(SELECT COALESCE(SUM(net_pnl),0) FROM trade_journal WHERE user_id=?), " +
                "(SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='watchlists'), " +
                "(SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='backtests'), " +
                "(SELECT COUNT(*) FROM screeners WHERE status='active')")
                .setParameter(1, user.getId()).getSingleResult();
            stats.put("stocks",     sc[0] != null ? ((Number)sc[0]).longValue() : 0);
            stats.put("strategies", sc[1] != null ? ((Number)sc[1]).longValue() : 0);
            stats.put("paper_open", sc[2] != null ? ((Number)sc[2]).longValue() : 0);
            stats.put("journal",    sc[3] != null ? ((Number)sc[3]).longValue() : 0);
            stats.put("pnl",        sc[4] != null ? ((Number)sc[4]).doubleValue() : 0.0);
            stats.put("watchlists", sc[5] != null ? ((Number)sc[5]).longValue() : 0);
            stats.put("backtests",  sc[6] != null ? ((Number)sc[6]).longValue() : 0);
            stats.put("screeners",  sc[7] != null ? ((Number)sc[7]).longValue() : 0);
        } catch (Exception e) {
            stats.put("stocks", 0L); stats.put("strategies", 0L); stats.put("paper_open", 0L);
            stats.put("journal", 0L); stats.put("pnl", 0.0); stats.put("watchlists", 0L);
            stats.put("backtests", 0L); stats.put("screeners", 0L);
        }

        // Market mood — breadth calculation
        Map<String, Object> mood = marketMood();

        // Top sector signals
        List<?> sectorSignals = em.createNativeQuery(
            "SELECT sector,trend_score,trend_phase,signal FROM sector_data " +
            "WHERE trade_date=(SELECT MAX(trade_date) FROM sector_data) " +
            "ORDER BY trend_score DESC LIMIT 10")
            .getResultList();

        // Recent scanner results
        List<?> recentScans = em.createNativeQuery(
            "SELECT sr.*,s.name as strategy_name FROM scanner_results sr " +
            "LEFT JOIN strategies s ON s.id=sr.strategy_id " +
            "ORDER BY sr.id DESC LIMIT 10")
            .getResultList();

        // Observation gate
        Object gate = em.createNativeQuery(
            "SELECT * FROM observation_gate ORDER BY gate_date DESC LIMIT 1")
            .getResultList().stream().findFirst().orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user",          Map.of("name", user.getName(), "plan", user.getPlan(),
                                           "tr_user_id", user.getTrUserId()));
        result.put("stats",         stats);
        result.put("market_mood",   mood);
        result.put("sector_signals", sectorSignals);
        result.put("recent_scans",  recentScans);
        result.put("gate",          gate);
        String mktDate = lastMarketDate();
        result.put("market_date",   mktDate);
        result.put("market_closed", !mktDate.equals(DateUtil.today()) ||
                                     java.time.LocalDate.now().getDayOfWeek().getValue() >= 6);

        // ── Support & Resistance — Sahi App Logic ─────────────────────────────
        try {
            result.put("support_resistance", srService.getAllIndicesSR());
        } catch (Exception e) {
            result.put("support_resistance", null);
        }

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Map<String, Object> marketMood() {
        String eff = lastMarketDate();
        List<?> rows = em.createNativeQuery(
            "SELECT close, prev_close FROM broker_market_candles " +
            "WHERE trade_date=:d AND UPPER(COALESCE(candle_mode,''))='DAILY'")
            .setParameter("d", eff).getResultList();
        if (rows.isEmpty()) return Map.of("score", 50, "label", "Neutral", "color", "yellow");
        long pos = rows.stream().filter(r -> {
            Object[] a = (Object[]) r;
            double cl = a[0] == null ? 0 : ((Number) a[0]).doubleValue();
            double pc = a[1] == null ? 0 : ((Number) a[1]).doubleValue();
            return cl >= pc;
        }).count();
        int score = (int) Math.round((double) pos / rows.size() * 100);
        return Map.of("score", score,
                      "label", score >= 60 ? "Positive" : score < 40 ? "Negative" : "Neutral",
                      "color", score >= 60 ? "green"    : score < 40 ? "red"       : "yellow");
    }

    private String lastMarketDate() {
        Object r = em.createNativeQuery(
            "SELECT MAX(trade_date) FROM broker_market_candles " +
            "WHERE trade_date <= :d AND UPPER(COALESCE(candle_mode,''))='DAILY'")
            .setParameter("d", DateUtil.today()).getSingleResult();
        return r != null ? r.toString() : DateUtil.today();
    }

    private boolean isMarketClosed() {
        return !lastMarketDate().equals(DateUtil.today()) ||
               java.time.LocalDate.now().getDayOfWeek().getValue() >= 6;
    }

    private long countQuery(String sql) {
        try {
            Object r = em.createNativeQuery(sql).getSingleResult();
            return r == null ? 0 : ((Number) r).longValue();
        } catch (Exception e) { return 0; }
    }
}
