package com.traderakshak.service;

import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Market Trend Identification Engine: one 0-100 health score for the whole market, and the
 * Strong Buy / Buy / No Trade / Sell / Strong Sell state that follows from it.
 *
 * <h3>Two places the specification had to be interpreted, both stated on screen</h3>
 *
 * <p><b>How a reading becomes points.</b> The weights are given (20 DMA 15, 50 DMA 25,
 * 200 DMA 25, A/D 15, volume 10, trend 5, RSI 5) but not the mapping from a reading to its
 * share of them. The three DMA breadths and volume participation are already percentages, so
 * they scale linearly - 62% above the 50 DMA earns 62% of 25 points. The A/D ratio is not a
 * percentage and is unbounded above, so it is mapped with 1.0 (as many advancing as declining)
 * at half marks and 2.0 or better at full, which puts the specification's own Strong Buy
 * threshold of 1.5 at three quarters. Trend and RSI are three-state and score full, half, zero.
 *
 * <p><b>There is no Nifty 500 index price series in this database</b> - broker_market_candles
 * holds equities only, with no index symbol. Items 7 and 8 need one, so the engine builds an
 * EQUAL-WEIGHTED composite by chaining the average daily return of the constituents. That is
 * not the published index: the real Nifty 500 is free-float market-cap weighted, so it follows
 * large caps while this follows the median constituent. For a breadth dashboard the equal
 * weighting is arguably the more honest of the two, but it must be read as what it is, so the
 * API calls it a composite rather than passing it off as the index.
 */
@Service
public class MarketRegimeService {

    private static final Logger log = LoggerFactory.getLogger(MarketRegimeService.class);

    /** Sessions a stock needs before it may vote: enough for a 200 DMA. */
    private static final int MIN_BARS = 200;
    /** Sessions read per stock: one trading year, for the 52-week high and low. */
    private static final int LOOKBACK_SESSIONS = 260;

    private final EntityManager em;
    private final TechnicalIndicatorEngine indicators;

    public MarketRegimeService(EntityManager em, TechnicalIndicatorEngine indicators) {
        this.em = em;
        this.indicators = indicators;
    }

    // ---------------------------------------------------------------- universe

    private record Universe(List<String> symbols, String label, boolean isNifty500) {}

    /**
     * The Nifty 500 constituents, or every NSE equity with enough history when that list has
     * never been fetched.
     *
     * <p>The fallback is not silently equivalent and the result says so. Nifty 500 breadth and
     * all-listed breadth answer different questions - the broad universe is dominated by small
     * caps, which turn earlier and harder, so its percentages swing wider than the index's.
     */
    @SuppressWarnings("unchecked")
    private Universe resolveUniverse() {
        try {
            List<String> n500 = em.createNativeQuery(
                "SELECT DISTINCT UPPER(TRIM(symbol)) FROM stock_index_membership "
              + "WHERE index_key = 'NIFTY_500' AND COALESCE(is_active,1) = 1 "
              + "AND COALESCE(symbol,'') <> ''").getResultList();
            if (n500.size() >= 100) return new Universe(n500, "Nifty 500", true);
        } catch (Exception e) {
            log.debug("[MarketRegime] Index membership unavailable: {}", e.getMessage());
        }
        List<String> all = em.createNativeQuery(
            "SELECT symbol FROM ("
          + "  SELECT UPPER(TRIM(symbol)) AS symbol, COUNT(DISTINCT trade_date) AS bars "
          + "  FROM broker_market_candles "
          + "  WHERE UPPER(COALESCE(candle_mode,'')) = 'DAILY' AND close > 0 "
          + "  GROUP BY 1) z WHERE bars >= " + MIN_BARS).getResultList();
        return new Universe(all, "All NSE equities with " + MIN_BARS + "+ sessions", false);
    }

    // ---------------------------------------------------------------- per stock

    /** Everything one stock contributes, read in a single pass over its recent sessions. */
    private record StockState(String symbol, double close, double prevClose,
                              double dma20, double dma50, double dma100, double dma200,
                              double hi52, double lo52, double volume, double avgVol20) {}

    @SuppressWarnings("unchecked")
    private List<StockState> loadStates(Universe u) {
        if (u.symbols().isEmpty()) return List.of();
        // Ranked newest-first per symbol, so every moving average is one FILTER over that rank.
        // A single statement because the alternative - a query per symbol - is 2,500 round trips
        // for a panel that has to redraw on demand.
        String sql =
            "WITH b AS ("
          + "  SELECT UPPER(TRIM(symbol)) AS sym, close, high, low,"
          + "         COALESCE(total_volume, volume) AS vol,"
          + "         ROW_NUMBER() OVER (PARTITION BY UPPER(TRIM(symbol))"
          + "                            ORDER BY trade_date DESC) AS rn"
          + "  FROM broker_market_candles"
          + "  WHERE UPPER(COALESCE(candle_mode,'')) = 'DAILY' AND close > 0"
          + "    AND UPPER(TRIM(symbol)) IN (:syms)"
          + ") SELECT sym,"
          + "  MAX(close) FILTER (WHERE rn = 1)   AS last_close,"
          + "  MAX(close) FILTER (WHERE rn = 2)   AS prev_close,"
          + "  AVG(close) FILTER (WHERE rn <= 20)  AS dma20,"
          + "  AVG(close) FILTER (WHERE rn <= 50)  AS dma50,"
          + "  AVG(close) FILTER (WHERE rn <= 100) AS dma100,"
          + "  AVG(close) FILTER (WHERE rn <= 200) AS dma200,"
          + "  MAX(high)  AS hi52,"
          + "  MIN(low)   AS lo52,"
          + "  MAX(vol)   FILTER (WHERE rn = 1) AS last_vol,"
          // The 20 sessions BEFORE today, so today's volume is compared against its own history
          // rather than against an average it is itself a twentieth of.
          + "  AVG(vol)   FILTER (WHERE rn BETWEEN 2 AND 21) AS avg_vol20,"
          + "  COUNT(*)   AS bars"
          + " FROM b WHERE rn <= " + LOOKBACK_SESSIONS + " GROUP BY sym";

        List<Object[]> rows = em.createNativeQuery(sql)
            .setParameter("syms", u.symbols()).getResultList();

        List<StockState> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Double barsVal = num(r[11]);
            if (barsVal == null || barsVal < MIN_BARS) continue;  // no 200 DMA without 200 bars
            Double close = num(r[1]);
            if (close == null || close <= 0) continue;
            out.add(new StockState(String.valueOf(r[0]), close, d(r[2]),
                d(r[3]), d(r[4]), d(r[5]), d(r[6]), d(r[7]), d(r[8]), d(r[9]), d(r[10])));
        }
        return out;
    }

    private static Double num(Object o) { return o instanceof Number n ? n.doubleValue() : null; }
    private static double d(Object o)   { Double v = num(o); return v == null ? 0 : v; }

    // ---------------------------------------------------------------- composite

    /** Equal-weighted constituent index: the average daily return, chained. See the class note. */
    @SuppressWarnings("unchecked")
    private List<Double> compositeSeries(Universe u) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "WITH b AS ("
              + "  SELECT trade_date, close,"
              + "         LAG(close) OVER (PARTITION BY UPPER(TRIM(symbol))"
              + "                          ORDER BY trade_date) AS prev"
              + "  FROM broker_market_candles"
              + "  WHERE UPPER(COALESCE(candle_mode,'')) = 'DAILY' AND close > 0"
              + "    AND UPPER(TRIM(symbol)) IN (:syms)"
              + ") SELECT trade_date, AVG(close / prev) AS growth"
              + "  FROM b WHERE prev IS NOT NULL AND prev > 0"
              // A handful of symbols reporting on a date does not make it a session; a quorum
              // stops a thin day from showing as a violent move in the composite.
              + "  GROUP BY trade_date HAVING COUNT(*) >= 50"
              + "  ORDER BY trade_date")
                .setParameter("syms", u.symbols()).getResultList();

            List<Double> series = new ArrayList<>();
            double level = 1000.0;                   // arbitrary base; only ratios matter
            series.add(level);
            for (Object[] r : rows) {
                Double g = num(r[1]);
                if (g == null || g <= 0) continue;
                level *= g;
                series.add(level);
            }
            return series;
        } catch (Exception e) {
            log.warn("[MarketRegime] Composite series failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ---------------------------------------------------------------- the score

    private static double pct(long part, long whole) {
        return whole <= 0 ? 0 : (part * 100.0) / whole;
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    public Map<String, Object> computeMarketHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        Universe u = resolveUniverse();
        List<StockState> states = loadStates(u);

        out.put("universe", u.label());
        out.put("is_nifty500", u.isNifty500());
        if (!u.isNifty500()) {
            out.put("universe_warning",
                "Nifty 500 membership has never been fetched, so this is every NSE equity with "
              + MIN_BARS + "+ sessions. Refresh it from Data Warehouse for true index breadth.");
        }
        out.put("total_stocks", states.size());

        if (states.size() < 50) {
            out.put("success", false);
            out.put("message", "Only " + states.size() + " stock(s) have " + MIN_BARS
                  + "+ daily sessions stored. Market breadth needs a broad universe - "
                  + "backfill more daily history first.");
            return out;
        }

        long above20 = 0, above50 = 0, above100 = 0, above200 = 0;
        long adv = 0, dec = 0, unch = 0, newHigh = 0, newLow = 0, volUp = 0, volMeasurable = 0;
        for (StockState s : states) {
            if (s.dma20()  > 0 && s.close() > s.dma20())  above20++;
            if (s.dma50()  > 0 && s.close() > s.dma50())  above50++;
            if (s.dma100() > 0 && s.close() > s.dma100()) above100++;
            if (s.dma200() > 0 && s.close() > s.dma200()) above200++;
            if (s.prevClose() > 0) {
                if (s.close() > s.prevClose()) adv++;
                else if (s.close() < s.prevClose()) dec++;
                else unch++;
            }
            // Touching the extreme counts: that is what "new high" means on the day it is set.
            if (s.hi52() > 0 && s.close() >= s.hi52()) newHigh++;
            if (s.lo52() > 0 && s.close() <= s.lo52()) newLow++;
            if (s.avgVol20() > 0 && s.volume() > 0) {
                volMeasurable++;
                if (s.volume() > s.avgVol20()) volUp++;
            }
        }
        long total = states.size();

        double pctAbove20  = pct(above20, total);
        double pctAbove50  = pct(above50, total);
        double pctAbove100 = pct(above100, total);
        double pctAbove200 = pct(above200, total);
        // Zero declines would make the ratio infinite; report that rather than dividing by it.
        Double adr  = dec > 0 ? (double) adv / dec : (adv > 0 ? null : 0.0);
        Double nhnl = newLow > 0 ? (double) newHigh / newLow : (newHigh > 0 ? null : 0.0);
        double volParticipation = pct(volUp, volMeasurable);

        List<Double> comp = compositeSeries(u);
        String trend = "UNKNOWN";
        Double compRsi = null, compClose = null, compDma50 = null, compDma200 = null;
        if (comp.size() >= 200) {
            compClose  = comp.get(comp.size() - 1);
            compDma50  = avgLast(comp, 50);
            compDma200 = avgLast(comp, 200);
            if (compClose > compDma50 && compDma50 > compDma200)      trend = "BULLISH";
            else if (compClose < compDma50 && compDma50 < compDma200) trend = "BEARISH";
            else                                                      trend = "NEUTRAL";
            compRsi = round2(indicators.rsi(comp, 14));
        }
        String rsiState = compRsi == null ? "UNKNOWN"
                        : compRsi > 60 ? "BULLISH" : compRsi < 40 ? "BEARISH" : "NEUTRAL";

        double p20  = 15.0 * (pctAbove20  / 100.0);
        double p50  = 25.0 * (pctAbove50  / 100.0);
        double p200 = 25.0 * (pctAbove200 / 100.0);
        double pAdr = 15.0 * adrFactor(adr);
        double pVol = 10.0 * (volParticipation / 100.0);
        double pTr  = "BULLISH".equals(trend)    ? 5.0 : "NEUTRAL".equals(trend)    ? 2.5 : 0.0;
        double pRsi = "BULLISH".equals(rsiState) ? 5.0 : "NEUTRAL".equals(rsiState) ? 2.5 : 0.0;
        double score = Math.max(0, Math.min(100, p20 + p50 + p200 + pAdr + pVol + pTr + pRsi));

        // Strong Buy carries four conditions, not just the score: a score can reach 80 on
        // short-term breadth alone while the long-term participation that makes aggressive
        // sizing survivable is absent. All four must hold, exactly as specified.
        boolean strongBuy = score >= 80
            && pctAbove50 >= 70
            && pctAbove200 >= 60
            && adr != null && adr > 1.5;

        String status, action, colour;
        if (strongBuy) {
            status = "STRONG BUY MARKET";  colour = "#16a34a";
            action = "Allow aggressive long trades.";
        } else if (score >= 60) {
            status = "BUY MARKET";         colour = "#4ade80";
            action = "Allow long trades.";
        } else if (score >= 40) {
            status = "NO TRADE MARKET";    colour = "#facc15";
            action = "Avoid new swing positions. Reduce position size by 50%.";
        } else if (score >= 20) {
            status = "SELL MARKET";        colour = "#f97316";
            action = "Avoid long trades. Short selling allowed.";
        } else {
            status = "STRONG SELL MARKET"; colour = "#dc2626";
            action = "Only short trades allowed.";
        }
        // A score of 80+ that misses one of the other three is a Buy market, not a Strong Buy.
        // Say which test failed, or the screen looks like it is contradicting its own rule.
        if (!strongBuy && score >= 80) {
            List<String> missed = new ArrayList<>();
            if (pctAbove50 < 70)  missed.add("50 DMA breadth " + round1(pctAbove50) + "% is under 70%");
            if (pctAbove200 < 60) missed.add("200 DMA breadth " + round1(pctAbove200) + "% is under 60%");
            if (adr == null || adr <= 1.5)
                missed.add("A/D ratio " + (adr == null ? "n/a" : round2(adr)) + " is not above 1.5");
            out.put("strong_buy_blocked_by", missed);
        }

        out.put("success", true);
        out.put("as_of", latestSession());
        out.put("generated_at", DateUtil.now());
        out.put("market_status", status);
        out.put("status_colour", colour);
        out.put("recommended_action", action);
        out.put("market_health_score", round1(score));

        out.put("pct_above_20dma",  round1(pctAbove20));
        out.put("pct_above_50dma",  round1(pctAbove50));
        out.put("pct_above_100dma", round1(pctAbove100));
        out.put("pct_above_200dma", round1(pctAbove200));
        out.put("advancing", adv);
        out.put("declining", dec);
        out.put("unchanged", unch);
        out.put("advance_decline_ratio", adr == null ? null : round2(adr));
        out.put("new_highs", newHigh);
        out.put("new_lows", newLow);
        out.put("nh_nl_ratio", nhnl == null ? null : round2(nhnl));
        out.put("volume_participation_pct", round1(volParticipation));
        out.put("stocks_above_avg_volume", volUp);
        out.put("composite_trend", trend);
        out.put("composite_rsi", compRsi);
        out.put("composite_rsi_state", rsiState);
        out.put("composite_close",  compClose  == null ? null : round2(compClose));
        out.put("composite_dma50",  compDma50  == null ? null : round2(compDma50));
        out.put("composite_dma200", compDma200 == null ? null : round2(compDma200));
        out.put("composite_note",
            "Equal-weighted index built from the constituents' average daily return. The published "
          + "Nifty 500 is free-float market-cap weighted, so this will not match it tick for tick.");

        Map<String, Object> pts = new LinkedHashMap<>();
        pts.put("above_20dma",          scoreRow(p20,  15));
        pts.put("above_50dma",          scoreRow(p50,  25));
        pts.put("above_200dma",         scoreRow(p200, 25));
        pts.put("advance_decline",      scoreRow(pAdr, 15));
        pts.put("volume_participation", scoreRow(pVol, 10));
        pts.put("composite_trend",      scoreRow(pTr,   5));
        pts.put("composite_rsi",        scoreRow(pRsi,  5));
        out.put("score_breakdown", pts);
        return out;
    }

    private static Map<String, Object> scoreRow(double earned, int max) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("earned", round1(earned));
        m.put("max", max);
        return m;
    }

    /**
     * A/D ratio to a 0-1 share of its points.
     *
     * <p>Parity - as many up as down - is a neutral tape and scores half; 2:1 or better is full.
     * The specification's Strong Buy gate of 1.5 therefore lands at three quarters. The ratio is
     * unbounded above, so it has to be capped somewhere, or one lopsided session would carry the
     * whole score on its own.
     */
    private static double adrFactor(Double adr) {
        if (adr == null) return 1.0;                 // nothing declined at all
        if (adr <= 0) return 0.0;
        return Math.max(0.0, Math.min(1.0, adr / 2.0));
    }

    private static Double avgLast(List<Double> series, int n) {
        if (series.size() < n) return null;
        double sum = 0;
        for (int i = series.size() - n; i < series.size(); i++) sum += series.get(i);
        return sum / n;
    }

    private String latestSession() {
        try {
            Object o = em.createNativeQuery(
                "SELECT MAX(trade_date) FROM broker_market_candles "
              + "WHERE UPPER(COALESCE(candle_mode,'')) = 'DAILY'").getSingleResult();
            return o == null ? null : String.valueOf(o);
        } catch (Exception e) {
            return null;
        }
    }
}
