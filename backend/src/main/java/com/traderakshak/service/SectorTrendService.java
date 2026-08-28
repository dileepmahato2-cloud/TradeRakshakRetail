package com.traderakshak.service;

import com.traderakshak.util.DateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SectorTrendService {

    @PersistenceContext
    private EntityManager em;

    public Double rsi(List<Double> prices, int period) {
        if (prices == null || prices.size() < period + 1) return null;
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double diff = prices.get(i) - prices.get(i - 1);
            if (diff >= 0) gain += diff; else loss -= diff;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        for (int i = period + 1; i < prices.size(); i++) {
            double diff = prices.get(i) - prices.get(i - 1);
            avgGain = (avgGain * (period - 1) + Math.max(diff, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-diff, 0)) / period;
        }
        if (avgLoss == 0) return 100.0;
        return Math.round((100.0 - 100.0 / (1 + avgGain / avgLoss)) * 10.0) / 10.0;
    }

    public Double ema(List<Double> prices, int period) {
        if (prices == null || prices.size() < period) return null;
        double k = 2.0 / (period + 1);
        double e = prices.subList(0, period).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        for (int i = period; i < prices.size(); i++) e = prices.get(i) * k + e * (1 - k);
        return Math.round(e * 100.0) / 100.0;
    }

    public boolean macdBull(List<Double> prices) {
        if (prices == null || prices.size() < 26) return false;
        Double e12 = ema(prices, 12), e26 = ema(prices, 26);
        return e12 != null && e26 != null && (e12 - e26) > 0;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> sectorTrend(String sector) {
        List<Object[]> rows;
        try {
            rows = em.createQuery(
                "SELECT m.symbol, m.close, m.volume FROM MarketData m " +
                "WHERE m.sector = :sector AND m.segment = 'Equity' " +
                "ORDER BY m.symbol, m.tradeDate", Object[].class)
                .setParameter("sector", sector).getResultList();
        } catch (Exception e) {
            return neutral(sector);
        }

        if (rows.isEmpty()) return neutral(sector);

        Map<String, List<Double>> closes  = new LinkedHashMap<>();
        Map<String, List<Double>> volumes = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String sym = (String) r[0];
            closes.computeIfAbsent(sym, k -> new ArrayList<>()).add(((Number) r[1]).doubleValue());
            volumes.computeIfAbsent(sym, k -> new ArrayList<>()).add(((Number) r[2]).doubleValue());
        }

        List<Double> rsiVals = new ArrayList<>(), prevRsi = new ArrayList<>(), prev2Rsi = new ArrayList<>();
        int above20 = 0, above50 = 0, macdBullCount = 0, total = 0, adv = 0, dec = 0;
        List<Double> volRatios = new ArrayList<>();

        for (Map.Entry<String, List<Double>> entry : closes.entrySet()) {
            List<Double> cs = entry.getValue();
            if (cs.size() < 14) continue;
            total++;
            Double ri  = rsi(cs, 14);
            Double pr  = cs.size() > 15 ? rsi(cs.subList(0, cs.size() - 1), 14) : ri;
            Double p2r = cs.size() > 16 ? rsi(cs.subList(0, cs.size() - 2), 14) : pr;
            Double e20 = ema(cs, Math.min(20, cs.size() - 1));
            Double e50 = cs.size() > 50 ? ema(cs, 50) : null;
            double last = cs.get(cs.size() - 1);
            double prev = cs.size() >= 2 ? cs.get(cs.size() - 2) : last;
            if (last >= prev) adv++; else dec++;
            if (ri  != null) rsiVals.add(ri);
            if (pr  != null) prevRsi.add(pr);
            if (p2r != null) prev2Rsi.add(p2r);
            if (e20 != null && last > e20) above20++;
            if (e50 != null && last > e50) above50++;
            if (macdBull(cs)) macdBullCount++;
            List<Double> vols = volumes.get(entry.getKey());
            if (vols != null && vols.size() >= 20) {
                double avg = vols.subList(vols.size() - 20, vols.size()).stream()
                    .mapToDouble(Double::doubleValue).average().orElse(1);
                if (avg > 0) volRatios.add(vols.get(vols.size() - 1) / avg);
            }
        }

        if (total == 0) return neutral(sector);

        double sectorRsi = avg(rsiVals, 50.0);
        double prevR     = avg(prevRsi, sectorRsi);
        double prev2R    = avg(prev2Rsi, prevR);
        double slope     = Math.round((sectorRsi - prev2R) * 10.0) / 10.0;
        double e20p      = Math.round((double) above20 / total * 100 * 10) / 10.0;
        double e50p      = Math.round((double) above50 / total * 100 * 10) / 10.0;
        double macdP     = Math.round((double) macdBullCount / total * 100 * 10) / 10.0;
        double volR      = volRatios.isEmpty() ? 1.0 :
                           Math.round(volRatios.stream().mapToDouble(Double::doubleValue)
                               .average().orElse(1.0) * 100.0) / 100.0;
        double adr       = total > 0 ? Math.round((double) adv / total * 100.0) / 100.0 : 0;
        boolean r45x     = prevR < 45 && sectorRsi >= 45;
        boolean r55x     = prevR < 55 && sectorRsi >= 55;

        double score = 0;
        Map<String, Map<String, Object>> breakdown = new LinkedHashMap<>();

        double rsiPts = sectorRsi >= 65 ? 40 : sectorRsi >= 55 ? 30 :
                        sectorRsi >= 45 ? 18 : sectorRsi >= 38 ? 8 : 0;
        score += rsiPts;
        breakdown.put("RSI(14W)", cond2("pts", rsiPts, "max", 40, "value", sectorRsi));

        double emaPts = e20p >= 70 && e50p >= 70 ? 25 : e20p >= 60 ? 18 :
                        e20p >= 50 ? 12 : e50p >= 50 ? 7 : 0;
        score += emaPts;
        breakdown.put("EMA Alignment", cond2("pts", emaPts, "max", 25, "value", e20p + "% EMA20"));

        double slopePts = slope > 5 ? 15 : slope > 2 ? 10 : slope > 0 ? 5 :
                          slope > -2 ? 0 : slope > -5 ? -5 : -10;
        score += slopePts;
        breakdown.put("RSI Slope", cond2("pts", slopePts, "max", 15, "value", String.format("%+.1f", slope)));

        double macdPts = macdP >= 60 ? 10 : macdP >= 40 ? 5 : 0;
        score += macdPts;
        breakdown.put("MACD", cond2("pts", macdPts, "max", 10, "value", macdP + "%"));

        double volPts = volR >= 1.5 ? 10 : volR >= 1.2 ? 7 : volR >= 1.0 ? 3 : 0;
        score += volPts;
        breakdown.put("Volume", cond2("pts", volPts, "max", 10, "value", volR + "x"));

        score = Math.max(0, Math.min(100, score));

        String phase = score >= 75 ? "STRONG_BULL" : score >= 55 ? "BULL" :
                       score >= 40 ? "RISING" : score >= 25 ? "NEUTRAL" :
                       score >= 15 ? "WEAK" : "BEAR";

        String signal = "STRONG_BULL".equals(phase) && slope >= 0 ? "BUY_AGGRESSIVE" :
                        "BULL".equals(phase) && e20p >= 60 ? "BUY" :
                        "RISING".equals(phase) && r45x ? "WATCH_BUY" :
                        "NEUTRAL".equals(phase) ? "HOLD" :
                        "WEAK".equals(phase) ? "CAUTION" : "AVOID";

        Map<String, String> decisionMap = new LinkedHashMap<>();
        decisionMap.put("STRONG_BULL", "FRESH_POSITION");
        decisionMap.put("BULL",        "FRESH_POSITION");
        decisionMap.put("RISING",      "WATCH_OPEN");
        decisionMap.put("NEUTRAL",     "HOLD");
        decisionMap.put("WEAK",        "PARTIAL_EXIT");
        decisionMap.put("BEAR",        "FULL_EXIT");
        String decision = decisionMap.getOrDefault(phase, "HOLD");

        List<Map<String, Object>> conditions = List.of(
            cond("Sector RSI >= 45",  sectorRsi >= 45, String.valueOf(sectorRsi), ">=45=uptrend"),
            cond("RSI Slope > 0",     slope >= 0, String.format("%+.1f", slope), "Positive = momentum"),
            cond(">50% above EMA20",  e20p >= 50, e20p + "%", "Short-term breadth"),
            cond(">50% above EMA50",  e50p >= 50, e50p + "%", "Medium-term breadth"),
            cond(">50% MACD bull",    macdP >= 50, macdP + "%", "MACD above zero"),
            cond("Volume >= 1.2x",    volR >= 1.2, volR + "x", "Institutional participation"),
            cond("RSI 45 Crossover",  r45x, r45x ? "Y" : "N", "Fresh entry trigger"));

        persistSectorData(sector, sectorRsi, slope, above20 >= total / 2,
                          above50 >= total / 2, volR, adv, dec, adr,
                          (int) Math.round(score), phase, signal);

        String riskLevel = List.of("BUY_AGGRESSIVE", "BUY").contains(signal) ? "Low-Medium" :
                           List.of("WATCH_BUY", "HOLD").contains(signal) ? "Medium" : "High";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sector",         sector);
        result.put("sector_rsi",     sectorRsi);
        result.put("prev_rsi",       prevR);
        result.put("rsi_slope",      slope);
        result.put("rsi45_cross",    r45x);
        result.put("rsi55_cross",    r55x);
        result.put("ema20_pct",      e20p);
        result.put("ema50_pct",      e50p);
        result.put("macd_pct",       macdP);
        result.put("vol_ratio",      volR);
        result.put("ad_ratio",       adr);
        result.put("advance",        adv);
        result.put("decline",        dec);
        result.put("total_stocks",   total);
        result.put("trend_score",    Math.round(score));
        result.put("trend_phase",    phase);
        result.put("signal",         signal);
        result.put("decision",       decision);
        result.put("conditions",     conditions);
        result.put("pass_count",     conditions.stream().filter(c -> Boolean.TRUE.equals(c.get("pass"))).count());
        result.put("score_breakdown",breakdown);
        result.put("risk_level",     riskLevel);
        result.put("computed_at",    DateUtil.now());
        return result;
    }

    public List<Map<String, Object>> allSectors() {
        List<String> sectors;
        try {
            sectors = em.createQuery(
                "SELECT DISTINCT m.sector FROM MarketData m " +
                "WHERE m.sector IS NOT NULL ORDER BY m.sector", String.class).getResultList();
        } catch (Exception e) {
            return List.of();
        }
        return sectors.stream().map(this::sectorTrend).collect(Collectors.toList());
    }

    public Map<String, Object> marketScore(List<Map<String, Object>> sectorResults) {
        if (sectorResults == null || sectorResults.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("score", 50); m.put("zone", "AMBER"); m.put("bull_sectors", 0);
            return m;
        }
        long bull   = sectorResults.stream().filter(s -> ((Number) s.getOrDefault("trend_score", 0)).longValue() >= 55).count();
        long strong = sectorResults.stream().filter(s -> ((Number) s.getOrDefault("trend_score", 0)).longValue() >= 70).count();
        double avgS = sectorResults.stream().mapToLong(s -> ((Number) s.getOrDefault("trend_score", 50L)).longValue()).average().orElse(50);
        int scoreVal = (int) Math.min(100, Math.max(0, Math.round(avgS * 0.6 + ((double) bull / sectorResults.size()) * 100 * 0.4)));
        String zone = scoreVal >= 65 ? "GREEN" : scoreVal < 40 ? "RED" : "AMBER";
        double avgRsi = sectorResults.stream().mapToDouble(s -> {
            Object v = s.getOrDefault("sector_rsi", 50.0);
            return v instanceof Number ? ((Number) v).doubleValue() : 50.0;
        }).average().orElse(50);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", scoreVal); m.put("zone", zone);
        m.put("bull_sectors", bull); m.put("strong_sectors", strong); m.put("avg_rsi", avgRsi);
        return m;
    }

    private Map<String, Object> neutral(String sector) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sector",       sector);
        m.put("sector_rsi",   50.0);
        m.put("trend_score",  50L);
        m.put("trend_phase",  "NEUTRAL");
        m.put("signal",       "HOLD");
        m.put("decision",     "HOLD");
        m.put("total_stocks", 0);
        m.put("rsi_slope",    0.0);
        m.put("conditions",   List.of());
        m.put("computed_at",  DateUtil.now());
        return m;
    }

    private double avg(List<Double> list, double fallback) {
        return list.isEmpty() ? fallback :
               Math.round(list.stream().mapToDouble(Double::doubleValue).average().orElse(fallback) * 10) / 10.0;
    }

    private Map<String, Object> cond(String name, boolean pass, String value, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("pass", pass); m.put("value", value); m.put("note", note);
        return m;
    }

    private Map<String, Object> cond2(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1); m.put(k2, v2); m.put(k3, v3);
        return m;
    }

    private void persistSectorData(String sector, double rsi, double slope,
                                   boolean aboveEma20, boolean aboveEma50,
                                   double volR, int adv, int dec, double adr,
                                   int score, String phase, String signal) {
        try {
            em.createNativeQuery(
                "INSERT INTO sector_data " +
                "(sector,trade_date,weekly_rsi,change_pct,above_ema20,above_ema50," +
                "volume_ratio,advance_count,decline_count,advance_decline_ratio," +
                "trend_score,trend_phase,signal,source,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT (sector, trade_date) DO UPDATE SET " +
                "weekly_rsi=EXCLUDED.weekly_rsi, change_pct=EXCLUDED.change_pct, above_ema20=EXCLUDED.above_ema20, " +
                "above_ema50=EXCLUDED.above_ema50, volume_ratio=EXCLUDED.volume_ratio, advance_count=EXCLUDED.advance_count, " +
                "decline_count=EXCLUDED.decline_count, advance_decline_ratio=EXCLUDED.advance_decline_ratio, " +
                "trend_score=EXCLUDED.trend_score, trend_phase=EXCLUDED.trend_phase, signal=EXCLUDED.signal, " +
                "source=EXCLUDED.source, created_at=EXCLUDED.created_at")
                .setParameter(1, sector).setParameter(2, DateUtil.today())
                .setParameter(3, rsi).setParameter(4, slope)
                .setParameter(5, aboveEma20 ? 1 : 0).setParameter(6, aboveEma50 ? 1 : 0)
                .setParameter(7, volR).setParameter(8, adv).setParameter(9, dec)
                .setParameter(10, adr).setParameter(11, score)
                .setParameter(12, phase).setParameter(13, signal)
                .setParameter(14, "computed").setParameter(15, DateUtil.now())
                .executeUpdate();
        } catch (Exception e) {
            log.debug("sector_data upsert: {}", e.getMessage());
        }
    }
}
