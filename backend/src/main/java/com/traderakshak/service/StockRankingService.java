package com.traderakshak.service;

// =============================================================================
// StockRankingService — TradeRakshak™ v11.5
//
// Evaluates every stock in the selected universe against user-defined logic
// (from the Strategy Builder) and produces a ranked list of trade opportunities.
//
// Scoring model — 11 weighted parameters (configurable by Super Admin):
//   1.  Price Action            — HH/HL, trend structure         (default wt: 12)
//   2.  Volume Strength         — vol vs 20-day SMA              (default wt: 10)
//   3.  Delivery %              — institutional participation     (default wt:  8)
//   4.  RSI                     — momentum, not overbought        (default wt: 10)
//   5.  EMA Trend               — 20/50/200 alignment            (default wt: 12)
//   6.  Breakout Confirmation   — price vs 52w/20d high          (default wt: 10)
//   7.  Support & Resistance    — proximity to S&R zones         (default wt:  8)
//   8.  Risk-Reward Ratio       — R:R ≥ configurable minimum     (default wt: 10)
//   9.  Backtest Win Rate       — historical strategy success     (default wt: 12)
//  10.  Sector Strength         — sector trend score             (default wt:  8)
//  11.  Market Trend Confirmation — index alignment              (default wt: 10)
//
// Enterprise Policy:
//   • Weights are stored in ranking_config table — Admin changes without coding
//   • All 11 parameters are independently scored 0-10 then weighted
//   • Reason text is generated for every parameter (transparent ranking)
//   • Results stored in stock_rankings table for history and export
//   • Backward compatible — does not touch any existing tables
//   • Impact reviewed: Strategy Builder, Backtesting, Screener, Dashboard
// =============================================================================

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.entity.Strategy;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockRankingService {

    private final TechnicalIndicatorEngine   indicator;
    private final StrategyConditionEvaluator evaluator;
    private final ObjectMapper               objectMapper;
    private final AppConfigService           appConfig;

    @PersistenceContext
    private EntityManager em;

    private Map<String, Double> getDefaultWeights() {
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("price_action",      appConfig.getDouble("ranking.weight.price_action", 12.0));
        w.put("volume_strength",   appConfig.getDouble("ranking.weight.volume_strength", 10.0));
        w.put("delivery_pct",      appConfig.getDouble("ranking.weight.delivery_pct", 8.0));
        w.put("rsi",               appConfig.getDouble("ranking.weight.rsi", 10.0));
        w.put("ema_trend",         appConfig.getDouble("ranking.weight.ema_trend", 12.0));
        w.put("breakout",          appConfig.getDouble("ranking.weight.breakout", 10.0));
        w.put("sr_proximity",      appConfig.getDouble("ranking.weight.sr_proximity", 8.0));
        w.put("risk_reward",       appConfig.getDouble("ranking.weight.risk_reward", 10.0));
        w.put("backtest_win_rate", appConfig.getDouble("ranking.weight.backtest_win_rate", 12.0));
        w.put("sector_strength",   appConfig.getDouble("ranking.weight.sector_strength", 8.0));
        w.put("market_trend",      appConfig.getDouble("ranking.weight.market_trend", 10.0));
        return w;
    }

    private static final double HIGH_PRIORITY_THRESHOLD  = 72.0;
    private static final double MEDIUM_PRIORITY_THRESHOLD = 55.0;

    // ==========================================================================
    //  PUBLIC API
    // ==========================================================================

    /**
     * Run ranking for a strategy against its universe.
     * Filters can include: sector, tradeType, timeframe, riskLevel,
     *                       minConfidence, strategyId, maxResults
     */
    @Transactional
    public Map<String, Object> runRanking(Long strategyId, Long userId, Long institutionId,
                                           Map<String, Object> filters) {
        long start = System.currentTimeMillis();

        // 1. Load strategy
        Strategy strategy = loadStrategy(strategyId);
        if (strategy == null) {
            return Map.of("error", "Strategy not found: " + strategyId);
        }

        // 2. Load admin-configured weights (falls back to defaults)
        Map<String, Double> weights = loadWeights(institutionId);

        // 3. Determine stock universe
        List<String> symbols = getUniverse(strategy.getUniverse(), strategy.getSegment(),
                                           (String) filters.getOrDefault("sector", null));

        // 4. Load latest market index for market-trend confirmation
        double indexTrendScore = getMarketTrendScore();

        // 5. Evaluate every symbol
        List<Map<String, Object>> ranked = new ArrayList<>();
        int maxResults = getInt(filters, "maxResults", 200);

        for (String symbol : symbols) {
            try {
                Map<String, Object> result = scoreSymbol(symbol, strategy, weights, indexTrendScore);
                if (result == null) continue;

                // Apply strategy logic filter — pass only if conditions are met
                List<Map<String, Object>> conditions = loadConditions(strategyId);
                boolean passesLogic = conditions.isEmpty() ||
                                      evaluator.evaluate(conditions, symbol, "NSE", "entry");
                result.put("passes_logic", passesLogic);

                // Apply user filters
                if (!applyFilters(result, filters)) continue;

                ranked.add(result);
            } catch (Exception e) {
                log.debug("Score error for {}: {}", symbol, e.getMessage());
            }
        }

        // 6. Sort by composite score descending
        ranked.sort((a, b) -> Double.compare(
            getD(b, "composite_score"), getD(a, "composite_score")));

        // 7. Assign rank numbers
        for (int i = 0; i < ranked.size(); i++) {
            Map<String, Object> r = ranked.get(i);
            r.put("rank", i + 1);
            r.put("priority", getPriority(getD(r, "composite_score")));
        }

        // 8. Trim to maxResults
        List<Map<String, Object>> topN = ranked.subList(0, Math.min(ranked.size(), maxResults));

        // 9. Persist to stock_rankings table
        String runId = persistRankingRun(strategyId, userId, institutionId, topN, filters);

        long elapsed = System.currentTimeMillis() - start;
        log.info("Ranking '{}': {} ranked from {} symbols in {}ms",
                 strategy.getName(), ranked.size(), symbols.size(), elapsed);

        // 10. Build summary stats
        Map<String, Object> summary = buildSummary(topN);

        return buildResponse(strategy, topN, summary, weights, runId,
                             symbols.size(), ranked.size(), elapsed);
    }

    /**
     * Get weight configuration for the admin UI.
     */
    public Map<String, Object> getWeightConfig(Long institutionId) {
        Map<String, Double> weights = loadWeights(institutionId);
        List<Map<String, Object>> configs = new ArrayList<>();
        PARAMETER_META.forEach((key, meta) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("key",         key);
            c.put("label",       meta.get("label"));
            c.put("description", meta.get("description"));
            c.put("weight",      weights.getOrDefault(key, getDefaultWeights().getOrDefault(key, 10.0)));
            c.put("default",     getDefaultWeights().getOrDefault(key, 10.0));
            c.put("min", 0); c.put("max", 30);
            configs.add(c);
        });
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        return Map.of("parameters", configs, "total_weight", total,
                      "institution_id", institutionId);
    }

    /**
     * Save updated weights (Admin only).
     */
    @Transactional
    public Map<String, Object> saveWeights(Long institutionId, Long adminUserId,
                                            Map<String, Double> newWeights) {
        double total = newWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total < 90 || total > 110) {
            return Map.of("success", false,
                          "error", "Weights must total 100 (±10). Current total: " + total);
        }

        // Delete existing config for this institution
        try {
            em.createNativeQuery(
                "DELETE FROM ranking_config WHERE institution_id=:iid")
                .setParameter("iid", institutionId).executeUpdate();
        } catch (Exception ignored) {}

        // Insert new weights
        for (Map.Entry<String, Double> e : newWeights.entrySet()) {
            try {
                em.createNativeQuery(
                    "INSERT INTO ranking_config(institution_id, param_key, weight, updated_by, updated_at) " +
                    "VALUES(?,?,?,?,?)")
                    .setParameter(1, institutionId).setParameter(2, e.getKey())
                    .setParameter(3, e.getValue()).setParameter(4, adminUserId)
                    .setParameter(5, DateUtil.now()).executeUpdate();
            } catch (Exception ex) {
                log.warn("Weight save error for {}: {}", e.getKey(), ex.getMessage());
            }
        }

        log.info("Admin {} updated ranking weights for institution {}: total={}",
                 adminUserId, institutionId, total);
        return Map.of("success", true, "total", total,
                      "message", "Ranking weights updated successfully.");
    }

    /**
     * Get historic ranking runs for a strategy.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRankingHistory(Long strategyId, Long userId) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT run_id, strategy_id, ranked_count, top_symbol, top_score, " +
                "scan_date, scan_time_ms, created_at " +
                "FROM stock_ranking_runs WHERE strategy_id=:sid " +
                "ORDER BY id DESC LIMIT 20")
                .setParameter("sid", strategyId).getResultList();

            return rows.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("run_id",      r[0]); m.put("strategy_id", r[1]);
                m.put("ranked_count",r[2]); m.put("top_symbol",  r[3]);
                m.put("top_score",   r[4]); m.put("scan_date",   r[5]);
                m.put("scan_time_ms",r[6]); m.put("created_at",  r[7]);
                return m;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Get results of a specific ranking run.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRankingRunResults(String runId) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT rank_position, symbol, sector, cmp, trade_type, signal, " +
                "entry_price, stop_loss, target, rr_ratio, composite_score, " +
                "priority, backtest_win_rate, reason_json, score_breakdown_json " +
                "FROM stock_rankings WHERE run_id=:rid ORDER BY rank_position")
                .setParameter("rid", runId).getResultList();

            return rows.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rank",            r[0]); m.put("symbol",       r[1]);
                m.put("sector",          r[2]); m.put("cmp",          r[3]);
                m.put("trade_type",      r[4]); m.put("signal",        r[5]);
                m.put("entry_price",     r[6]); m.put("stop_loss",     r[7]);
                m.put("target",          r[8]); m.put("rr_ratio",      r[9]);
                m.put("composite_score", r[10]); m.put("priority",     r[11]);
                m.put("backtest_win_rate", r[12]);
                m.put("reasons",  parseJson(r[13]));
                m.put("score_breakdown", parseJson(r[14]));
                return m;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==========================================================================
    //  CORE SCORING ENGINE
    // ==========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> scoreSymbol(String symbol, Strategy strategy,
                                             Map<String, Double> weights,
                                             double indexTrendScore) {
        // Load OHLCV
        Map<String, Object> data = evaluator.loadSymbolData(symbol, "NSE", 200);
        if (data == null) return null;

        List<Double> closes  = (List<Double>) data.get("closes");
        List<Double> highs   = (List<Double>) data.get("highs");
        List<Double> lows    = (List<Double>) data.get("lows");
        List<Double> volumes = (List<Double>) data.get("volumes");
        List<Double> deliv   = (List<Double>) data.get("delivery_pct");
        double sectorScore   = getD(data, "sector_score");

        if (closes == null || closes.size() < 20) return null;

        double cmp = closes.get(closes.size() - 1);
        if (cmp <= 0) return null;

        // ── Meta ─────────────────────────────────────────────────────────────
        String sector    = getSymbolSector(symbol);
        String tradeType = strategy.getStrategyCategory() != null ? strategy.getStrategyCategory() : "swing";

        // ── S&R for proximity scoring ─────────────────────────────────────────
        double nearestSupportDist    = getNearestSRDist(cmp, lows, "support");
        double nearestResistanceDist = getNearestSRDist(cmp, highs, "resistance");

        // ── Backtest win rate for this strategy ───────────────────────────────
        double backtestWR = getBacktestWinRate(strategy.getId(), symbol);

        // ── Risk-Reward ───────────────────────────────────────────────────────
        double slPct  = strategy.getStopLossPct() != null ? strategy.getStopLossPct() : 5.0;
        double tgtPct = strategy.getTargetPct()   != null ? strategy.getTargetPct()   : 10.0;
        double sl     = Math.round(cmp * (1 - slPct / 100) * 100) / 100.0;
        double target = Math.round(cmp * (1 + tgtPct / 100) * 100) / 100.0;
        double rr     = cmp > sl ? Math.round((target - cmp) / (cmp - sl) * 100) / 100.0 : 0;

        // ── SCORE each of the 11 parameters (0-10 raw) ───────────────────────
        Map<String, double[]> scoreDetails = new LinkedHashMap<>(); // [raw_score, weight, weighted]
        List<String> reasons = new ArrayList<>();

        // 1. Price Action (0-10)
        double[] pa = scorePriceAction(closes, highs, lows, reasons);
        scoreDetails.put("price_action", pa);

        // 2. Volume Strength (0-10)
        double[] vs = scoreVolumeStrength(volumes, reasons);
        scoreDetails.put("volume_strength", vs);

        // 3. Delivery % (0-10)
        double[] dp = scoreDelivery(deliv, reasons);
        scoreDetails.put("delivery_pct", dp);

        // 4. RSI (0-10)
        double[] rsiScore = scoreRsi(closes, reasons);
        scoreDetails.put("rsi", rsiScore);

        // 5. EMA Trend (0-10)
        double[] ema = scoreEmaTrend(closes, reasons);
        scoreDetails.put("ema_trend", ema);

        // 6. Breakout (0-10)
        double[] bo = scoreBreakout(closes, highs, lows, reasons);
        scoreDetails.put("breakout", bo);

        // 7. S&R Proximity (0-10)
        double[] sr = scoreSRProximity(nearestSupportDist, nearestResistanceDist, reasons);
        scoreDetails.put("sr_proximity", sr);

        // 8. Risk-Reward (0-10)
        double[] rrScore = scoreRiskReward(rr, reasons, strategy.getRrMin() != null ? strategy.getRrMin() : 2.0);
        scoreDetails.put("risk_reward", rrScore);

        // 9. Backtest Win Rate (0-10)
        double[] bwScore = scoreBacktestWR(backtestWR, reasons);
        scoreDetails.put("backtest_win_rate", bwScore);

        // 10. Sector Strength (0-10)
        double[] ss = scoreSectorStrength(sectorScore, reasons);
        scoreDetails.put("sector_strength", ss);

        // 11. Market Trend Confirmation (0-10)
        double[] mt = scoreMarketTrend(indexTrendScore, reasons);
        scoreDetails.put("market_trend", mt);

        // ── Compute weighted composite (0-100) ───────────────────────────────
        double composite = 0;
        double totalWt   = 0;
        Map<String, Object> breakdown = new LinkedHashMap<>();

        for (Map.Entry<String, double[]> e : scoreDetails.entrySet()) {
            String key     = e.getKey();
            double rawScore= e.getValue()[0];
            double wt      = weights.getOrDefault(key, getDefaultWeights().getOrDefault(key, 10.0));
            double weighted = rawScore * wt / 10.0;
            composite += weighted;
            totalWt   += wt;

            breakdown.put(key, Map.of(
                "raw",      Math.round(rawScore * 10) / 10.0,
                "weight",   wt,
                "weighted", Math.round(weighted * 10) / 10.0,
                "label",    PARAMETER_META.getOrDefault(key, Map.of("label", key)).get("label")
            ));
        }

        // Normalise to 100 if total weight deviates
        if (totalWt > 0 && Math.abs(totalWt - 100.0) > 5.0) {
            composite = composite / totalWt * 100.0;
        }
        composite = Math.min(100, Math.max(0, Math.round(composite * 10.0) / 10.0));

        // ── Signal ───────────────────────────────────────────────────────────
        String signal = composite >= HIGH_PRIORITY_THRESHOLD ? "STRONG BUY"
                      : composite >= MEDIUM_PRIORITY_THRESHOLD ? "BUY"
                      : composite >= 40.0 ? "WATCH"
                      : "AVOID";

        // ── Build result ─────────────────────────────────────────────────────
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol",           symbol);
        result.put("sector",           sector);
        result.put("cmp",              Math.round(cmp * 100.0) / 100.0);
        result.put("trade_type",       tradeType);
        result.put("signal",           signal);
        result.put("entry_price",      cmp);
        result.put("stop_loss",        sl);
        result.put("target",           target);
        result.put("rr_ratio",         rr);
        result.put("composite_score",  composite);
        result.put("backtest_win_rate",Math.round(backtestWR * 10.0) / 10.0);
        result.put("sector_score",     Math.round(sectorScore * 10.0) / 10.0);
        result.put("reasons",          reasons);
        result.put("score_breakdown",  breakdown);
        result.put("passes_logic",     false); // updated by caller
        result.put("scan_date",        DateUtil.today());

        return result;
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  11 INDIVIDUAL SCORERS  (each returns double[]{raw_0_to_10})
    // ──────────────────────────────────────────────────────────────────────────

    private double[] scorePriceAction(List<Double> closes, List<Double> highs,
                                       List<Double> lows, List<String> reasons) {
        double score = 0;
        List<String> pts = new ArrayList<>();

        // Higher highs (2 pts)
        if (indicator.isHigherHigh(highs)) { score += 2; pts.add("Higher highs ✓"); }
        // Higher lows (2 pts)
        if (indicator.isHigherLow(lows))   { score += 2; pts.add("Higher lows ✓"); }
        // Price above 20-day SMA (2 pts)
        double sma20 = indicator.sma(closes, 20);
        double cmp   = closes.get(closes.size()-1);
        if (cmp > sma20) { score += 2; pts.add("Price > SMA20 ✓"); }
        // Recent positive candle (1 pt)
        if (closes.size() >= 2 && closes.get(closes.size()-1) > closes.get(closes.size()-2)) {
            score += 1; pts.add("Positive candle ✓");
        }
        // Candle pattern (1 pt each, max 3)
        if (closes.size() >= 1) {
            int n = closes.size();
            double o = closes.get(n-1), h = highs.get(n-1), l = lows.get(n-1), c = closes.get(n-1);
            if (indicator.isHammer(o, h, l, c))   { score = Math.min(10, score+2); pts.add("Hammer pattern ✓"); }
            if (indicator.isMarubozu(o, h, l, c)) { score = Math.min(10, score+1); pts.add("Marubozu ✓"); }
        }
        // ROC positive (1 pt)
        double roc = indicator.roc(closes, 10);
        if (roc > 2) { score = Math.min(10, score+1); pts.add(String.format("10-day ROC +%.1f%% ✓", roc)); }

        score = Math.min(10, score);
        if (!pts.isEmpty()) reasons.add("Price Action [" + score + "/10]: " + String.join(", ", pts));
        return new double[]{score};
    }

    private double[] scoreVolumeStrength(List<Double> volumes, List<String> reasons) {
        if (volumes == null || volumes.size() < 20) {
            reasons.add("Volume Strength [0/10]: Insufficient data");
            return new double[]{0};
        }
        double lastVol  = volumes.get(volumes.size()-1);
        double volSma20 = indicator.sma(volumes, 20);
        double ratio    = volSma20 > 0 ? lastVol / volSma20 : 0;

        double score;
        String desc;
        if      (ratio >= 3.0) { score = 10; desc = String.format("Volume %.1fx SMA20 🔥 Very strong", ratio); }
        else if (ratio >= 2.0) { score = 8;  desc = String.format("Volume %.1fx SMA20 🟢", ratio); }
        else if (ratio >= 1.5) { score = 6;  desc = String.format("Volume %.1fx SMA20 ✓", ratio); }
        else if (ratio >= 1.0) { score = 4;  desc = String.format("Volume %.1fx SMA20 (avg)", ratio); }
        else                   { score = 2;  desc = String.format("Volume %.1fx SMA20 (below avg)", ratio); }

        reasons.add("Volume Strength [" + (int)score + "/10]: " + desc);
        return new double[]{score};
    }

    private double[] scoreDelivery(List<Double> deliv, List<String> reasons) {
        if (deliv == null || deliv.isEmpty()) {
            reasons.add("Delivery% [5/10]: Data unavailable (neutral)");
            return new double[]{5};
        }
        double d = deliv.get(deliv.size()-1);
        double score;
        String desc;
        if      (d >= 75) { score = 10; desc = String.format("%.1f%% — Exceptional institutional buying", d); }
        else if (d >= 60) { score = 8;  desc = String.format("%.1f%% — Strong delivery ✓", d); }
        else if (d >= 45) { score = 6;  desc = String.format("%.1f%% — Good delivery", d); }
        else if (d >= 30) { score = 4;  desc = String.format("%.1f%% — Moderate delivery", d); }
        else              { score = 2;  desc = String.format("%.1f%% — Weak delivery (speculative)", d); }

        reasons.add("Delivery% [" + (int)score + "/10]: " + desc);
        return new double[]{score};
    }

    private double[] scoreRsi(List<Double> closes, List<String> reasons) {
        double rsi = indicator.rsi(closes, 14);
        double score;
        String desc;
        // Optimal RSI zone for longs: 45-65
        if      (rsi >= 55 && rsi <= 65) { score = 10; desc = String.format("RSI %.1f — Ideal momentum zone", rsi); }
        else if (rsi >= 45 && rsi < 55)  { score = 8;  desc = String.format("RSI %.1f — Healthy (building momentum)", rsi); }
        else if (rsi >= 65 && rsi <= 72) { score = 6;  desc = String.format("RSI %.1f — Strong but approaching overbought", rsi); }
        else if (rsi >= 35 && rsi < 45)  { score = 5;  desc = String.format("RSI %.1f — Weak/recovering", rsi); }
        else if (rsi < 35)               { score = 2;  desc = String.format("RSI %.1f — Oversold (reversal watch)", rsi); }
        else                             { score = 1;  desc = String.format("RSI %.1f — Overbought ⚠", rsi); }

        reasons.add("RSI [" + (int)score + "/10]: " + desc);
        return new double[]{score};
    }

    private double[] scoreEmaTrend(List<Double> closes, List<String> reasons) {
        double cmp   = closes.get(closes.size()-1);
        double ema20 = indicator.ema(closes, 20);
        double ema50 = closes.size() >= 50 ? indicator.ema(closes, 50)  : ema20;
        double ema200= closes.size() >= 200? indicator.ema(closes, 200) : ema50;

        double score = 0;
        List<String> pts = new ArrayList<>();

        if (cmp > ema20)   { score += 2.5; pts.add("CMP>EMA20"); }
        if (cmp > ema50)   { score += 2;   pts.add("CMP>EMA50"); }
        if (cmp > ema200)  { score += 2;   pts.add("CMP>EMA200"); }
        if (ema20 > ema50) { score += 2;   pts.add("EMA20>EMA50 (uptrend)"); }
        if (ema50 > ema200){ score += 1.5; pts.add("EMA50>EMA200 (golden zone)"); }

        score = Math.min(10, score);
        reasons.add("EMA Trend [" + (int)score + "/10]: " + (pts.isEmpty() ? "Bearish alignment" : String.join(", ", pts)));
        return new double[]{score};
    }

    private double[] scoreBreakout(List<Double> closes, List<Double> highs,
                                    List<Double> lows, List<String> reasons) {
        double score = 0;
        List<String> pts = new ArrayList<>();

        // 20-day breakout (most important — 4 pts)
        if (indicator.isBreakout(closes, highs, 20)) {
            score += 4; pts.add("20-day high breakout ✓");
        }
        // 52-week (approx 200 trading days) breakout (4 pts)
        if (indicator.isBreakout(closes, highs, Math.min(200, closes.size()-1))) {
            score += 4; pts.add("52-week breakout 🚀");
        }
        // Price above VWAP proxy (2 pts)
        double sma10  = indicator.sma(closes, 10);
        double cmp    = closes.get(closes.size()-1);
        if (cmp > sma10) { score += 2; pts.add("Price>SMA10 ✓"); }

        score = Math.min(10, score);
        if (score == 0) {
            reasons.add("Breakout [0/10]: No breakout — price in range");
        } else {
            reasons.add("Breakout [" + (int)score + "/10]: " + String.join(", ", pts));
        }
        return new double[]{score};
    }

    private double[] scoreSRProximity(double supportDist, double resistanceDist,
                                       List<String> reasons) {
        // Best scenario: price near support (good entry), far from resistance (room to run)
        double score = 5; // neutral
        String desc;

        if (supportDist >= 0 && supportDist < 1.0 && resistanceDist > 3.0) {
            score = 10; desc = String.format("%.1f%% above support, %.1f%% to resistance — ideal entry", supportDist, resistanceDist);
        } else if (supportDist < 2.0 && resistanceDist > 2.0) {
            score = 8;  desc = String.format("Near support (%.1f%%), room to run (%.1f%% to R)", supportDist, resistanceDist);
        } else if (supportDist < 3.0) {
            score = 6;  desc = String.format("%.1f%% above support", supportDist);
        } else if (resistanceDist < 1.5) {
            score = 2;  desc = String.format("%.1f%% from resistance — entering may be risky", resistanceDist);
        } else {
            score = 4;  desc = String.format("S: %.1f%%, R: %.1f%%", supportDist, resistanceDist);
        }

        reasons.add("S&R [" + (int)score + "/10]: " + desc);
        return new double[]{score};
    }

    private double[] scoreRiskReward(double rr, List<String> reasons, double rrMin) {
        double score;
        String desc;
        if      (rr >= 4.0) { score = 10; desc = String.format("R:R=%.1f — Excellent ✓", rr); }
        else if (rr >= 3.0) { score = 8;  desc = String.format("R:R=%.1f — Very good", rr); }
        else if (rr >= 2.0) { score = 6;  desc = String.format("R:R=%.1f — Acceptable (min %.1f)", rr, rrMin); }
        else if (rr >= 1.5) { score = 4;  desc = String.format("R:R=%.1f — Below minimum %.1f ⚠", rr, rrMin); }
        else                { score = 1;  desc = String.format("R:R=%.1f — Poor ✗", rr); }

        reasons.add("Risk-Reward [" + (int)score + "/10]: " + desc);
        return new double[]{score};
    }

    private double[] scoreBacktestWR(double winRate, List<String> reasons) {
        double score;
        String desc;
        if (winRate == 0) {
            score = 5; desc = "Not yet backtested (neutral)";
        } else if (winRate >= 70) {
            score = 10; desc = String.format("%.0f%% win rate — Excellent strategy performance 🏆", winRate);
        } else if (winRate >= 60) {
            score = 8;  desc = String.format("%.0f%% win rate — Good", winRate);
        } else if (winRate >= 50) {
            score = 6;  desc = String.format("%.0f%% win rate — Acceptable", winRate);
        } else if (winRate >= 40) {
            score = 3;  desc = String.format("%.0f%% win rate — Below average ⚠", winRate);
        } else {
            score = 1;  desc = String.format("%.0f%% win rate — Poor, review strategy ✗", winRate);
        }
        reasons.add("Backtest Win Rate [" + (int)score + "/10]: " + desc);
        return new double[]{score};
    }

    private double[] scoreSectorStrength(double sectorScore, List<String> reasons) {
        double score;
        String desc;
        if      (sectorScore >= 80) { score = 10; desc = String.format("Sector score %.0f — Very strong sector tailwind 🚀", sectorScore); }
        else if (sectorScore >= 65) { score = 8;  desc = String.format("Sector score %.0f — Strong sector ✓", sectorScore); }
        else if (sectorScore >= 50) { score = 6;  desc = String.format("Sector score %.0f — Neutral sector", sectorScore); }
        else if (sectorScore >= 35) { score = 3;  desc = String.format("Sector score %.0f — Weak sector ⚠", sectorScore); }
        else                        { score = 1;  desc = String.format("Sector score %.0f — Sector in downtrend ✗", sectorScore); }
        reasons.add("Sector [" + (int)score + "/10]: " + desc);
        return new double[]{score};
    }

    private double[] scoreMarketTrend(double indexTrendScore, List<String> reasons) {
        double score;
        String desc;
        if      (indexTrendScore >= 75) { score = 10; desc = "Bull market — high probability environment 🟢"; }
        else if (indexTrendScore >= 55) { score = 7;  desc = "Mildly bullish — good conditions"; }
        else if (indexTrendScore >= 45) { score = 5;  desc = "Sideways market — selective trades"; }
        else if (indexTrendScore >= 30) { score = 3;  desc = "Bearish bias — trade with caution ⚠"; }
        else                            { score = 1;  desc = "Bear market — avoid longs ✗"; }
        reasons.add("Market Trend [" + (int)score + "/10]: " + desc);
        return new double[]{score};
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ──────────────────────────────────────────────────────────────────────────

    private String getPriority(double score) {
        if (score >= HIGH_PRIORITY_THRESHOLD)  return "HIGH";
        if (score >= MEDIUM_PRIORITY_THRESHOLD) return "MEDIUM";
        return "LOW";
    }

    private boolean applyFilters(Map<String, Object> result, Map<String, Object> filters) {
        // Sector filter
        String sectorFilter = (String) filters.get("sector");
        if (sectorFilter != null && !sectorFilter.isBlank() && !"ALL".equals(sectorFilter)) {
            String sym = (String) result.get("sector");
            if (!sectorFilter.equalsIgnoreCase(sym)) return false;
        }
        // Trade type filter
        String tradeTypeFilter = (String) filters.get("tradeType");
        if (tradeTypeFilter != null && !tradeTypeFilter.isBlank() && !"ALL".equals(tradeTypeFilter)) {
            if (!tradeTypeFilter.equalsIgnoreCase((String)result.get("trade_type"))) return false;
        }
        // Min confidence score
        double minConfidence = getDouble(filters, "minConfidence", 0);
        if (getD(result, "composite_score") < minConfidence) return false;
        // Risk level filter
        String riskFilter = (String) filters.get("riskLevel");
        if (riskFilter != null && !riskFilter.isBlank() && !"ALL".equals(riskFilter)) {
            String priority = (String) result.get("priority");
            if ("LOW_RISK".equals(riskFilter) && !"HIGH".equals(priority)) return false;
        }
        // Logic filter — only show stocks that pass strategy conditions
        Object logicFilter = filters.get("passesLogicOnly");
        if (Boolean.TRUE.equals(logicFilter) && !Boolean.TRUE.equals(result.get("passes_logic"))) {
            return false;
        }
        return true;
    }

    private Map<String, Object> buildSummary(List<Map<String, Object>> ranked) {
        long high   = ranked.stream().filter(r -> "HIGH".equals(r.get("priority"))).count();
        long medium = ranked.stream().filter(r -> "MEDIUM".equals(r.get("priority"))).count();
        long low    = ranked.stream().filter(r -> "LOW".equals(r.get("priority"))).count();

        double avgScore = ranked.stream()
            .mapToDouble(r -> getD(r, "composite_score")).average().orElse(0);

        OptionalDouble topScoreOpt = ranked.stream()
            .mapToDouble(r -> getD(r, "composite_score")).max();

        // Sector distribution
        Map<String, Long> bySector = ranked.stream()
            .collect(Collectors.groupingBy(
                r -> (String) r.getOrDefault("sector", "Unknown"),
                Collectors.counting()));

        return Map.of(
            "total_ranked",  ranked.size(),
            "high_priority", high,
            "medium_priority", medium,
            "low_priority",  low,
            "avg_score",     Math.round(avgScore * 10) / 10.0,
            "top_score",     topScoreOpt.isPresent() ? Math.round(topScoreOpt.getAsDouble() * 10) / 10.0 : 0.0,
            "top_symbol",    ranked.isEmpty() ? "" : ranked.get(0).getOrDefault("symbol", ""),
            "by_sector",     bySector
        );
    }

    private Map<String, Object> buildResponse(Strategy strategy,
                                               List<Map<String, Object>> ranked,
                                               Map<String, Object> summary,
                                               Map<String, Double> weights,
                                               String runId,
                                               int totalScanned, int totalMatched,
                                               long elapsed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("run_id",        runId);
        r.put("strategy_id",   strategy.getId());
        r.put("strategy_name", strategy.getName());
        r.put("strategy_category", strategy.getStrategyCategory());
        r.put("timeframe",     strategy.getTimeframe());
        r.put("universe",      strategy.getUniverse());
        r.put("results",       ranked);
        r.put("summary",       summary);
        r.put("weights",       weights);
        r.put("total_scanned", totalScanned);
        r.put("total_matched", totalMatched);
        r.put("scan_date",     DateUtil.today());
        r.put("scan_time_ms",  elapsed);
        r.put("high_priority_threshold",  HIGH_PRIORITY_THRESHOLD);
        r.put("medium_priority_threshold",MEDIUM_PRIORITY_THRESHOLD);
        r.put("disclaimer", "TradeRakshak™ ranking is for EDUCATIONAL purposes only. Not SEBI investment advice.");
        return r;
    }

    @Transactional
    private String persistRankingRun(Long strategyId, Long userId, Long institutionId,
                                      List<Map<String, Object>> results,
                                      Map<String, Object> filters) {
        String runId = "RNK-" + System.currentTimeMillis();
        Map<String, Object> summary = buildSummary(results);

        try {
            em.createNativeQuery(
                "INSERT INTO stock_ranking_runs(run_id,strategy_id,user_id,institution_id," +
                "ranked_count,top_symbol,top_score,filters_json,scan_date,scan_time_ms,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, runId).setParameter(2, strategyId)
                .setParameter(3, userId).setParameter(4, institutionId)
                .setParameter(5, results.size())
                .setParameter(6, summary.getOrDefault("top_symbol", ""))
                .setParameter(7, summary.getOrDefault("top_score", 0))
                .setParameter(8, toJson(filters))
                .setParameter(9, DateUtil.today())
                .setParameter(10, 0)
                .setParameter(11, DateUtil.now())
                .executeUpdate();

            // Persist individual stock rankings
            for (Map<String, Object> r : results) {
                try {
                    em.createNativeQuery(
                        "INSERT INTO stock_rankings(run_id,strategy_id,user_id,institution_id," +
                        "rank_position,symbol,sector,cmp,trade_type,signal,entry_price," +
                        "stop_loss,target,rr_ratio,composite_score,priority," +
                        "backtest_win_rate,passes_logic,reason_json,score_breakdown_json,scan_date,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                        .setParameter(1, runId).setParameter(2, strategyId)
                        .setParameter(3, userId).setParameter(4, institutionId)
                        .setParameter(5, r.get("rank")).setParameter(6, r.get("symbol"))
                        .setParameter(7, r.get("sector")).setParameter(8, r.get("cmp"))
                        .setParameter(9, r.get("trade_type")).setParameter(10, r.get("signal"))
                        .setParameter(11, r.get("entry_price")).setParameter(12, r.get("stop_loss"))
                        .setParameter(13, r.get("target")).setParameter(14, r.get("rr_ratio"))
                        .setParameter(15, r.get("composite_score")).setParameter(16, r.get("priority"))
                        .setParameter(17, r.get("backtest_win_rate"))
                        .setParameter(18, Boolean.TRUE.equals(r.get("passes_logic")) ? 1 : 0)
                        .setParameter(19, toJson(r.get("reasons")))
                        .setParameter(20, toJson(r.get("score_breakdown")))
                        .setParameter(21, DateUtil.today()).setParameter(22, DateUtil.now())
                        .executeUpdate();
                } catch (Exception ex) {
                    log.debug("Persist ranking row error: {}", ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Persist ranking run error: {}", e.getMessage());
        }
        return runId;
    }

    // ── Data helpers ──────────────────────────────────────────────────────────

    private Strategy loadStrategy(Long strategyId) {
        try {
            List<?> res = em.createNativeQuery(
                "SELECT id,name,strategy_category,timeframe,universe,segment," +
                "stop_loss_pct,target_pct,rr_min,win_rate FROM strategies WHERE id=:id AND status='active'")
                .setParameter("id", strategyId).getResultList();
            if (res.isEmpty()) return null;
            Object[] r = (Object[]) res.get(0);
            Strategy s = new Strategy();
            s.setId(((Number)r[0]).longValue());
            s.setName((String)r[1]);
            s.setStrategyCategory((String)r[2]);
            s.setTimeframe((String)r[3]);
            s.setUniverse((String)r[4]);
            s.setSegment((String)r[5]);
            s.setStopLossPct(r[6] != null ? ((Number)r[6]).doubleValue() : 5.0);
            s.setTargetPct(r[7]   != null ? ((Number)r[7]).doubleValue() : 10.0);
            s.setRrMin(r[8]       != null ? ((Number)r[8]).doubleValue() : 2.0);
            s.setWinRate(r[9]     != null ? ((Number)r[9]).doubleValue() : 0.0);
            return s;
        } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadConditions(Long strategyId) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT condition_group,operator,indicator_left,params_left,comparator," +
                "indicator_right,params_right,value_right,multiplier,group_operator " +
                "FROM strategy_conditions WHERE strategy_id=:id ORDER BY condition_order")
                .setParameter("id", strategyId).getResultList();

            return rows.stream().map(r -> {
                Map<String, Object> c = new HashMap<>();
                c.put("group",r[0]); c.put("operator",r[1]); c.put("indicatorLeft",r[2]);
                c.put("paramsLeft", parseJson(r[3])); c.put("comparator",r[4]);
                c.put("indicatorRight",r[5]); c.put("paramsRight",parseJson(r[6]));
                c.put("valueRight",r[7]!=null?((Number)r[7]).doubleValue():0);
                c.put("multiplier",r[8]!=null?((Number)r[8]).doubleValue():1.0);
                c.put("groupOperator",r[9]);
                return c;
            }).collect(Collectors.toList());
        } catch (Exception e) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private List<String> getUniverse(String universe, String segment, String sectorFilter) {
        String q;
        if      ("NSE_50".equals(universe))  q = "nifty50_flag=1";
        else if ("NSE_500".equals(universe)) q = "nifty500_flag=1";
        else                                  q = "1=1";
        String sectorClause = (sectorFilter != null && !sectorFilter.isBlank() && !"ALL".equals(sectorFilter))
                              ? " AND sector='" + sectorFilter.replace("'","''") + "'" : "";
        try {
            return em.createNativeQuery(
                "SELECT DISTINCT symbol FROM stock_master WHERE " + q +
                " AND exchange='NSE' AND status='active'" + sectorClause +
                " LIMIT 500").getResultList();
        } catch (Exception e) { return List.of(); }
    }

    private double getMarketTrendScore() {
        try {
            // Use Nifty 50 last 20-day close data to compute market trend
            List<Object[]> rows = em.createNativeQuery(
                "SELECT close FROM market_data WHERE exchange='NSE' AND symbol='NIFTY 50' " +
                "ORDER BY trade_date DESC LIMIT 20").getResultList();
            if (rows.size() < 10) return 55.0; // neutral

            List<Double> closes = new ArrayList<>();
            for (Object[] r : rows) closes.add(r[0]!=null?((Number)r[0]).doubleValue():0);
            Collections.reverse(closes);

            double cmp   = closes.get(closes.size()-1);
            double ema20 = indicator.ema(closes, Math.min(20, closes.size()));
            double rsi   = indicator.rsi(closes, Math.min(14, closes.size()-1));
            double roc   = indicator.roc(closes, Math.min(10, closes.size()-1));

            double score = 50;
            if (cmp > ema20)   score += 15;
            if (rsi > 55)      score += 10;
            if (rsi < 45)      score -= 10;
            if (roc > 2)       score += 10;
            if (roc < -2)      score -= 10;
            return Math.min(100, Math.max(0, score));
        } catch (Exception e) { return 55.0; }
    }

    private String getSymbolSector(String symbol) {
        try {
            Object r = em.createNativeQuery(
                "SELECT sector FROM stock_master WHERE symbol=:s AND exchange='NSE' LIMIT 1")
                .setParameter("s", symbol).getSingleResult();
            return r != null ? r.toString() : "Unknown";
        } catch (Exception e) { return "Unknown"; }
    }

    private double getBacktestWinRate(Long strategyId, String symbol) {
        // Use overall strategy win rate as proxy (per-symbol backtest would be expensive)
        try {
            Object r = em.createNativeQuery(
                "SELECT win_rate FROM strategies WHERE id=:id").setParameter("id", strategyId).getSingleResult();
            return r != null ? ((Number)r).doubleValue() : 0;
        } catch (Exception e) { return 0; }
    }

    private double getNearestSRDist(double cmp, List<Double> series, String type) {
        if (series == null || series.size() < 5) return 3.0;
        if ("support".equals(type)) {
            // Recent swing lows below CMP
            double best = 0;
            for (int i = series.size()-1; i >= Math.max(0, series.size()-20); i--) {
                double p = series.get(i);
                if (p < cmp) { best = p; break; }
            }
            return best > 0 ? Math.abs(cmp - best) / cmp * 100 : 3.0;
        } else {
            // Recent swing highs above CMP
            double best = Double.MAX_VALUE;
            for (int i = series.size()-1; i >= Math.max(0, series.size()-20); i--) {
                double p = series.get(i);
                if (p > cmp && p < best) { best = p; }
            }
            return best < Double.MAX_VALUE ? Math.abs(best - cmp) / cmp * 100 : 3.0;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> loadWeights(Long institutionId) {
        Map<String, Double> weights = new LinkedHashMap<>(getDefaultWeights());
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT param_key, weight FROM ranking_config WHERE institution_id=:iid")
                .setParameter("iid", institutionId).getResultList();
            for (Object[] r : rows) {
                if (r[0] != null && r[1] != null)
                    weights.put(r[0].toString(), ((Number)r[1]).doubleValue());
            }
        } catch (Exception ignored) {}
        return weights;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(Object obj) {
        if (obj == null) return Map.of();
        if (obj instanceof Map) return (Map<String,Object>) obj;
        try { return objectMapper.readValue(obj.toString(), Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    private double getD(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? 0 : v instanceof Number n ? n.doubleValue() : 0;
    }

    private int getInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        return v == null ? def : ((Number)v).intValue();
    }

    private double getDouble(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        return v == null ? def : ((Number)v).doubleValue();
    }

    // ── Parameter metadata (for admin UI labels) ─────────────────────────────
    private static final Map<String, Map<String, String>> PARAMETER_META = Map.ofEntries(
        Map.entry("price_action",      Map.of("label","Price Action",           "description","HH/HL structure, candle patterns, ROC")),
        Map.entry("volume_strength",   Map.of("label","Volume Strength",        "description","Current volume vs 20-day SMA")),
        Map.entry("delivery_pct",      Map.of("label","Delivery %",             "description","NSE delivery % — institutional participation")),
        Map.entry("rsi",               Map.of("label","RSI (14)",               "description","Relative Strength Index momentum score")),
        Map.entry("ema_trend",         Map.of("label","EMA Trend",              "description","Price position vs EMA20/50/200 alignment")),
        Map.entry("breakout",          Map.of("label","Breakout Confirmation",  "description","20-day and 52-week price breakout")),
        Map.entry("sr_proximity",      Map.of("label","S&R Proximity",         "description","Distance from nearest support/resistance")),
        Map.entry("risk_reward",       Map.of("label","Risk-Reward Ratio",      "description","Trade R:R based on SL% and target%")),
        Map.entry("backtest_win_rate", Map.of("label","Backtest Win Rate",      "description","Historical strategy win rate from backtests")),
        Map.entry("sector_strength",   Map.of("label","Sector Strength",        "description","Sector AI trend score")),
        Map.entry("market_trend",      Map.of("label","Market Trend",           "description","Nifty 50 trend confirmation score"))
    );
}
