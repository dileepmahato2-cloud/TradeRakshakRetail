package com.traderakshak.service;

// =============================================================================
// SupportResistanceService — TradeRakshak™ v11.4
// Implements Sahi App S&R logic:
//   1. Classic Pivot Points (Floor Trader's Pivot) — Daily / Weekly / Monthly
//   2. Fibonacci Retracement levels
//   3. Fractal-based S&R (Bill Williams)
//   4. Volume-Weighted Price Zones (High-volume nodes)
//   5. Psychological Round Numbers
//   6. Zone Strength Scoring (touches, volume, recency)
//   7. Proximity Alert — how close is CMP to each level
//
// Supported indices: NIFTY 50, NIFTY 500, FINNIFTY, BANKNIFTY
// =============================================================================

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupportResistanceService {

    @PersistenceContext
    private EntityManager em;

    // ── Index → Market-data symbol mapping ────────────────────────────────────
    private static final Map<String, String> INDEX_SYMBOLS = Map.of(
        "NIFTY50",   "NIFTY 50",
        "NIFTY500",  "NIFTY 500",
        "FINNIFTY",  "NIFTY FIN SERVICE",
        "BANKNIFTY", "NIFTY BANK"
    );

    private static final Map<String, String> INDEX_LABELS = Map.of(
        "NIFTY50",   "Nifty 50",
        "NIFTY500",  "Nifty 500",
        "FINNIFTY",  "Fin Nifty",
        "BANKNIFTY", "Bank Nifty"
    );

    // ─────────────────────────────────────────────────────────────────────────
    //  PUBLIC API — compute S&R for all 4 indices
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> getAllIndicesSR() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("NIFTY50", "BANKNIFTY", "FINNIFTY", "NIFTY500")) {
            result.put(key, computeForIndex(key));
        }
        result.put("computed_at", java.time.LocalDateTime.now().toString());
        return result;
    }

    public Map<String, Object> getIndexSR(String indexKey) {
        return computeForIndex(indexKey.toUpperCase());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CORE COMPUTATION
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> computeForIndex(String indexKey) {
        String symbol  = INDEX_SYMBOLS.getOrDefault(indexKey, indexKey);
        String label   = INDEX_LABELS.getOrDefault(indexKey, indexKey);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index",  indexKey);
        result.put("label",  label);
        result.put("symbol", symbol);

        // ── Fetch OHLCV history (90 trading days for robust fractal detection) ──
        List<Object[]> rows;
        try {
            rows = em.createNativeQuery(
                "SELECT trade_date, open, high, low, close, volume " +
                "FROM market_data " +
                "WHERE exchange='NSE' AND symbol=:sym " +
                "ORDER BY trade_date DESC LIMIT 90")
                .setParameter("sym", symbol)
                .getResultList();
        } catch (Exception e) {
            log.warn("SR fetch error for {}: {}", symbol, e.getMessage());
            rows = new ArrayList<>();
        }

        // Also try INDEX_DATA table as fallback (Sahi app uses both)
        if (rows.isEmpty()) {
            try {
                rows = em.createNativeQuery(
                    "SELECT trade_date, open, high, low, close, COALESCE(volume,0) " +
                    "FROM index_data " +
                    "WHERE symbol=:sym " +
                    "ORDER BY trade_date DESC LIMIT 90")
                    .setParameter("sym", symbol)
                    .getResultList();
            } catch (Exception ignored) {}
        }

        if (rows.isEmpty()) {
            result.put("error", "No market data available for " + label);
            result.put("levels", Collections.emptyList());
            result.put("current_price", null);
            return result;
        }

        // ── Parse into typed lists (rows are DESC — reverse for chronological) ──
        List<Object[]> chronoRows = new ArrayList<>(rows);
        Collections.reverse(chronoRows);

        List<Double> opens   = new ArrayList<>();
        List<Double> highs   = new ArrayList<>();
        List<Double> lows    = new ArrayList<>();
        List<Double> closes  = new ArrayList<>();
        List<Double> volumes = new ArrayList<>();
        List<String> dates   = new ArrayList<>();

        for (Object[] r : chronoRows) {
            dates.add(r[0].toString());
            opens.add(toDouble(r[1]));
            highs.add(toDouble(r[2]));
            lows.add(toDouble(r[3]));
            closes.add(toDouble(r[4]));
            volumes.add(toDouble(r[5]));
        }

        double cmp = closes.get(closes.size() - 1);
        result.put("current_price", round2(cmp));
        result.put("as_of_date",    dates.get(dates.size() - 1));

        // ── Compute all level sets ────────────────────────────────────────────
        List<Map<String, Object>> allLevels = new ArrayList<>();

        allLevels.addAll(pivotLevels(highs, lows, closes, volumes, dates));
        allLevels.addAll(weeklyPivotLevels(highs, lows, closes));
        allLevels.addAll(monthlyPivotLevels(highs, lows, closes));
        allLevels.addAll(fibRetracementLevels(highs, lows, closes));
        allLevels.addAll(fractalLevels(highs, lows, closes, dates));
        allLevels.addAll(psychologicalLevels(cmp, indexKey));
        allLevels.addAll(volumeProfileLevels(closes, volumes));

        // ── Cluster nearby levels (Sahi logic: merge levels within 0.2% band) ──
        List<Map<String, Object>> clustered = clusterLevels(allLevels, cmp);

        // ── Score each level ──────────────────────────────────────────────────
        clustered.forEach(l -> scoreLevelStrength(l, closes, volumes));

        // ── Tag proximity to CMP ──────────────────────────────────────────────
        clustered.forEach(l -> tagProximity(l, cmp));

        // ── Sort: supports descending from CMP, resistances ascending from CMP
        List<Map<String, Object>> supports    = clustered.stream()
            .filter(l -> "support".equals(l.get("zone_type")))
            .sorted(Comparator.comparingDouble(l -> -toDouble(l.get("price"))))
            .collect(Collectors.toList());

        List<Map<String, Object>> resistances = clustered.stream()
            .filter(l -> "resistance".equals(l.get("zone_type")))
            .sorted(Comparator.comparingDouble(l -> toDouble(l.get("price"))))
            .collect(Collectors.toList());

        // ── Nearest S&R (most actionable — Sahi highlights these) ────────────
        Map<String, Object> nearestSupport    = supports.isEmpty() ? null : supports.get(0);
        Map<String, Object> nearestResistance = resistances.isEmpty() ? null : resistances.get(0);

        // ── ATR for context ───────────────────────────────────────────────────
        double atr14 = computeATR(highs, lows, closes, 14);

        // ── Trend bias ────────────────────────────────────────────────────────
        String trendBias = trendBias(closes);

        result.put("supports",           supports);
        result.put("resistances",        resistances);
        result.put("nearest_support",    nearestSupport);
        result.put("nearest_resistance", nearestResistance);
        result.put("atr_14",             round2(atr14));
        result.put("trend_bias",         trendBias);
        result.put("total_levels",       clustered.size());

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  1. DAILY PIVOT POINTS (Floor Trader Classic — as used by Sahi)
    //     PP  = (H + L + C) / 3
    //     R1  = 2×PP − L    S1 = 2×PP − H
    //     R2  = PP + (H−L)  S2 = PP − (H−L)
    //     R3  = H + 2×(PP−L) S3 = L − 2×(H−PP)
    // ─────────────────────────────────────────────────────────────────────────
    private List<Map<String, Object>> pivotLevels(
            List<Double> highs, List<Double> lows, List<Double> closes,
            List<Double> volumes, List<String> dates) {

        if (highs.size() < 2) return Collections.emptyList();
        int n   = highs.size();
        // Use previous day's H,L,C for today's pivots (standard floor-trader rule)
        double H = highs.get(n - 2);
        double L = lows.get(n - 2);
        double C = closes.get(n - 2);

        double pp = (H + L + C) / 3.0;
        double range = H - L;

        List<Map<String, Object>> levels = new ArrayList<>();
        // Resistance levels
        addLevel(levels, pp + (H - L),            "resistance", "Pivot R2", "Daily Pivot", 3);
        addLevel(levels, 2 * pp - L,              "resistance", "Pivot R1", "Daily Pivot", 4);
        // Pivot itself — can be support or resistance depending on CMP
        addLevel(levels, pp,                      "pivot",      "Pivot PP", "Daily Pivot", 5);
        // Support levels
        addLevel(levels, 2 * pp - H,              "support",    "Pivot S1", "Daily Pivot", 4);
        addLevel(levels, pp - range,              "support",    "Pivot S2", "Daily Pivot", 3);
        addLevel(levels, H + 2 * (pp - L),        "resistance", "Pivot R3", "Daily Pivot", 2);
        addLevel(levels, L - 2 * (H - pp),        "support",    "Pivot S3", "Daily Pivot", 2);

        return levels;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  2. WEEKLY PIVOT POINTS
    // ─────────────────────────────────────────────────────────────────────────
    private List<Map<String, Object>> weeklyPivotLevels(
            List<Double> highs, List<Double> lows, List<Double> closes) {

        if (highs.size() < 6) return Collections.emptyList();
        int n  = highs.size();
        // Approximate: last 5 trading days = 1 week
        int start = Math.max(0, n - 7);
        double wH = highs.subList(start, n - 1).stream().mapToDouble(d -> d).max().orElse(highs.get(n-2));
        double wL = lows.subList(start, n - 1).stream().mapToDouble(d -> d).min().orElse(lows.get(n-2));
        double wC = closes.get(n - 2);

        double pp = (wH + wL + wC) / 3.0;
        List<Map<String, Object>> levels = new ArrayList<>();
        addLevel(levels, 2 * pp - wL, "resistance", "Weekly R1", "Weekly Pivot", 4);
        addLevel(levels, pp + (wH - wL), "resistance", "Weekly R2", "Weekly Pivot", 3);
        addLevel(levels, pp,           "pivot",      "Weekly PP", "Weekly Pivot", 3);
        addLevel(levels, 2 * pp - wH, "support",    "Weekly S1", "Weekly Pivot", 4);
        addLevel(levels, pp - (wH - wL), "support",  "Weekly S2", "Weekly Pivot", 3);

        return levels;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  3. MONTHLY PIVOT POINTS
    // ─────────────────────────────────────────────────────────────────────────
    private List<Map<String, Object>> monthlyPivotLevels(
            List<Double> highs, List<Double> lows, List<Double> closes) {

        if (highs.size() < 22) return Collections.emptyList();
        int n = highs.size();
        int start = Math.max(0, n - 23);
        double mH = highs.subList(start, n - 1).stream().mapToDouble(d -> d).max().orElse(highs.get(n-2));
        double mL = lows.subList(start, n - 1).stream().mapToDouble(d -> d).min().orElse(lows.get(n-2));
        double mC = closes.get(n - 2);

        double pp = (mH + mL + mC) / 3.0;
        List<Map<String, Object>> levels = new ArrayList<>();
        addLevel(levels, 2 * pp - mL, "resistance", "Monthly R1", "Monthly Pivot", 5);
        addLevel(levels, pp + (mH - mL), "resistance", "Monthly R2", "Monthly Pivot", 4);
        addLevel(levels, pp,           "pivot",      "Monthly PP", "Monthly Pivot", 4);
        addLevel(levels, 2 * pp - mH, "support",    "Monthly S1", "Monthly Pivot", 5);
        addLevel(levels, pp - (mH - mL), "support", "Monthly S2", "Monthly Pivot", 4);

        return levels;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  4. FIBONACCI RETRACEMENT (Sahi uses last major swing — 52w high/low)
    //     Levels: 23.6%, 38.2%, 50%, 61.8%, 78.6%
    // ─────────────────────────────────────────────────────────────────────────
    private List<Map<String, Object>> fibRetracementLevels(
            List<Double> highs, List<Double> lows, List<Double> closes) {

        if (highs.size() < 10) return Collections.emptyList();
        // Use the available data range for swing detection
        double swingHigh = highs.stream().mapToDouble(d -> d).max().orElse(0);
        double swingLow  = lows.stream().mapToDouble(d -> d).min().orElse(0);
        double range     = swingHigh - swingLow;
        if (range <= 0) return Collections.emptyList();

        double cmp = closes.get(closes.size() - 1);
        boolean inUptrend = cmp > (swingHigh + swingLow) / 2;

        List<Map<String, Object>> levels = new ArrayList<>();
        double[] fibLevels = {0.0, 0.236, 0.382, 0.500, 0.618, 0.786, 1.0};
        String[] fibNames  = {"Fib 0%", "Fib 23.6%", "Fib 38.2%", "Fib 50%", "Fib 61.8%", "Fib 78.6%", "Fib 100%"};
        int[]    fibScores = {3, 3, 4, 5, 5, 4, 3};

        for (int i = 0; i < fibLevels.length; i++) {
            double price;
            String zone;
            if (inUptrend) {
                price = swingHigh - fibLevels[i] * range;
                zone  = price < cmp ? "support" : "resistance";
            } else {
                price = swingLow + fibLevels[i] * range;
                zone  = price > cmp ? "resistance" : "support";
            }
            addLevel(levels, price, zone, fibNames[i], "Fibonacci", fibScores[i]);
        }

        return levels;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  5. FRACTAL S&R (Bill Williams: 5-candle pivot fractal)
    //     Fractal High: middle candle high > 2 candles on each side
    //     Fractal Low:  middle candle low < 2 candles on each side
    // ─────────────────────────────────────────────────────────────────────────
    private List<Map<String, Object>> fractalLevels(
            List<Double> highs, List<Double> lows, List<Double> closes, List<String> dates) {

        List<Map<String, Object>> levels = new ArrayList<>();
        int n = highs.size();
        if (n < 5) return levels;

        Set<Double> seen = new HashSet<>();

        // Look for fractals in last 60 bars, report last 5 distinct each side
        int fractalHighCount = 0, fractalLowCount = 0;

        for (int i = n - 3; i >= 2 && (fractalHighCount < 5 || fractalLowCount < 5); i--) {
            double h = highs.get(i);
            double l = lows.get(i);

            // Fractal High
            if (fractalHighCount < 5 &&
                h > highs.get(i-2) && h > highs.get(i-1) &&
                h > highs.get(i+1) && h > highs.get(i+2)) {
                double rounded = round2(h);
                if (seen.add(rounded)) {
                    int recencyScore = (i >= n - 10) ? 5 : (i >= n - 25) ? 4 : 3;
                    String zone = h < closes.get(n-1) ? "support" : "resistance";
                    addLevel(levels, rounded, zone,
                        "Fractal " + (zone.equals("resistance") ? "R" : "S"),
                        "Fractal", recencyScore);
                    fractalHighCount++;
                }
            }

            // Fractal Low
            if (fractalLowCount < 5 &&
                l < lows.get(i-2) && l < lows.get(i-1) &&
                l < lows.get(i+1) && l < lows.get(i+2)) {
                double rounded = round2(l);
                if (seen.add(rounded)) {
                    int recencyScore = (i >= n - 10) ? 5 : (i >= n - 25) ? 4 : 3;
                    String zone = l < closes.get(n-1) ? "support" : "resistance";
                    addLevel(levels, rounded, zone,
                        "Fractal " + (zone.equals("support") ? "S" : "R"),
                        "Fractal", recencyScore);
                    fractalLowCount++;
                }
            }
        }

        return levels;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  6. PSYCHOLOGICAL ROUND NUMBERS (Sahi highlights these prominently)
    //     Nifty: every 500 / 100 points
    //     BankNifty: every 1000 / 500 points
    //     FinNifty: every 500 / 100
    //     Nifty500: every 100 / 50
    // ─────────────────────────────────────────────────────────────────────────
    private List<Map<String, Object>> psychologicalLevels(double cmp, String indexKey) {
        List<Map<String, Object>> levels = new ArrayList<>();

        double majorStep = switch (indexKey) {
            case "BANKNIFTY" -> 1000;
            case "NIFTY50"   -> 500;
            case "FINNIFTY"  -> 500;
            case "NIFTY500"  -> 100;
            default -> 500;
        };
        double minorStep = majorStep / 5.0;

        // Generate ±10 major levels around CMP
        double baseMajor = Math.floor(cmp / majorStep) * majorStep;
        for (int i = -3; i <= 3; i++) {
            double price = baseMajor + i * majorStep;
            if (price <= 0) continue;
            String zone = price < cmp ? "support" : "resistance";
            addLevel(levels, price, zone, "Round " + (int) price, "Psychological", 4);
        }

        // Generate minor round numbers ±5 around CMP
        double baseMinor = Math.floor(cmp / minorStep) * minorStep;
        for (int i = -5; i <= 5; i++) {
            double price = baseMinor + i * minorStep;
            if (price <= 0) continue;
            // Skip if already covered by major (within 0.01%)
            double finalPrice = price;
            boolean isMajor = levels.stream().anyMatch(l ->
                Math.abs(toDouble(l.get("price")) - finalPrice) < finalPrice * 0.0001);
            if (!isMajor) {
                String zone = price < cmp ? "support" : "resistance";
                addLevel(levels, price, zone, "Round " + (int) price, "Psychological", 2);
            }
        }

        return levels;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  7. VOLUME PROFILE — High Volume Nodes (HVN) act as S&R
    //     Divide price range into 20 buckets, find top-3 HVNs
    // ─────────────────────────────────────────────────────────────────────────
    private List<Map<String, Object>> volumeProfileLevels(
            List<Double> closes, List<Double> volumes) {

        List<Map<String, Object>> levels = new ArrayList<>();
        if (closes.size() < 10) return levels;

        double minP = closes.stream().mapToDouble(d -> d).min().orElse(0);
        double maxP = closes.stream().mapToDouble(d -> d).max().orElse(1);
        double range = maxP - minP;
        if (range <= 0) return levels;

        int buckets = 20;
        double bucketSize = range / buckets;
        double[] volBuckets = new double[buckets];
        double[] priceBuckets = new double[buckets];

        // Populate buckets
        for (int i = 0; i < buckets; i++) {
            priceBuckets[i] = minP + (i + 0.5) * bucketSize;
        }
        for (int i = 0; i < closes.size(); i++) {
            int bucket = (int) Math.min(buckets - 1,
                Math.floor((closes.get(i) - minP) / bucketSize));
            if (bucket >= 0) volBuckets[bucket] += volumes.get(i);
        }

        // Find top-3 HVN buckets
        double cmp = closes.get(closes.size() - 1);
        double totalVol = Arrays.stream(volBuckets).sum();
        if (totalVol <= 0) return levels;

        List<int[]> sorted = new ArrayList<>();
        for (int i = 0; i < buckets; i++) sorted.add(new int[]{i});
        sorted.sort((a, b) -> Double.compare(volBuckets[b[0]], volBuckets[a[0]]));

        for (int k = 0; k < Math.min(4, sorted.size()); k++) {
            int idx = sorted.get(k)[0];
            double price = priceBuckets[idx];
            double volPct = volBuckets[idx] / totalVol * 100;
            String zone  = price < cmp ? "support" : "resistance";
            int strength = volPct > 10 ? 5 : volPct > 7 ? 4 : 3;
            addLevel(levels, round2(price), zone, "HVN " + (k + 1), "Volume Profile", strength);
        }

        return levels;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CLUSTER MERGER — merge levels within 0.2% of each other (Sahi logic)
    // ─────────────────────────────────────────────────────────────────────────
    private List<Map<String, Object>> clusterLevels(
            List<Map<String, Object>> raw, double cmp) {

        // Sort all by price
        raw.sort(Comparator.comparingDouble(l -> toDouble(l.get("price"))));

        List<Map<String, Object>> clustered = new ArrayList<>();
        double clusterBand = cmp * 0.002; // 0.2% band

        int i = 0;
        while (i < raw.size()) {
            double basePrice = toDouble(raw.get(i).get("price"));
            List<Map<String, Object>> group = new ArrayList<>();
            group.add(raw.get(i));

            int j = i + 1;
            while (j < raw.size() && Math.abs(toDouble(raw.get(j).get("price")) - basePrice) <= clusterBand) {
                group.add(raw.get(j));
                j++;
            }

            // Merge group: weighted average price by base_strength, collect sources
            double totalWeight = 0, weightedPrice = 0;
            Set<String> sources = new LinkedHashSet<>();
            Set<String> names   = new LinkedHashSet<>();
            int maxStrength = 0;
            String dominantZone = "support";
            int suppCount = 0, resCount = 0;

            for (Map<String, Object> l : group) {
                double w = toDouble(l.get("base_strength"));
                weightedPrice += toDouble(l.get("price")) * w;
                totalWeight   += w;
                sources.add(l.get("source").toString());
                names.add(l.get("name").toString());
                maxStrength = Math.max(maxStrength, (int) toDouble(l.get("base_strength")));
                if ("resistance".equals(l.get("zone_type"))) resCount++;
                else if ("support".equals(l.get("zone_type"))) suppCount++;
            }

            double mergedPrice = totalWeight > 0 ? weightedPrice / totalWeight : basePrice;
            if (resCount > suppCount) dominantZone = "resistance";
            else if (suppCount > 0) dominantZone = "support";
            else dominantZone = "pivot";

            Map<String, Object> merged = new LinkedHashMap<>();
            merged.put("price",       round2(mergedPrice));
            merged.put("zone_type",   dominantZone);
            merged.put("name",        String.join(" + ", names));
            merged.put("source",      String.join(", ", sources));
            merged.put("base_strength", maxStrength);
            merged.put("confluence",  group.size()); // how many methods agree
            merged.put("is_confluence", group.size() >= 2);

            clustered.add(merged);
            i = j;
        }

        // Remove pivot-only entries & re-classify "pivot" based on CMP
        for (Map<String, Object> l : clustered) {
            if ("pivot".equals(l.get("zone_type"))) {
                double price = toDouble(l.get("price"));
                l.put("zone_type", price < cmp ? "support" : "resistance");
            }
        }

        return clustered;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LEVEL STRENGTH SCORING
    //  Sahi app grades each level: VERY STRONG / STRONG / MODERATE / WEAK
    //  Formula: base_strength × confluence_bonus × recency_factor
    // ─────────────────────────────────────────────────────────────────────────
    private void scoreLevelStrength(Map<String, Object> level,
                                    List<Double> closes, List<Double> volumes) {
        double price      = toDouble(level.get("price"));
        int baseStrength  = (int) toDouble(level.get("base_strength"));
        int confluence    = (int) toDouble(level.get("confluence"));

        // Touch count — how many times did price touch this level (±0.3% band)
        double band = price * 0.003;
        int touches = 0;
        for (double c : closes) {
            if (Math.abs(c - price) <= band) touches++;
        }

        // Confluence bonus
        double confluenceBonus = confluence >= 4 ? 2.0 : confluence == 3 ? 1.6 : confluence == 2 ? 1.3 : 1.0;

        // Touches bonus
        double touchBonus = touches >= 5 ? 1.5 : touches >= 3 ? 1.3 : touches >= 1 ? 1.1 : 1.0;

        double finalScore = baseStrength * confluenceBonus * touchBonus;

        String grade;
        if (finalScore >= 12) grade = "VERY STRONG";
        else if (finalScore >= 8) grade = "STRONG";
        else if (finalScore >= 5) grade = "MODERATE";
        else grade = "WEAK";

        level.put("touch_count",  touches);
        level.put("final_score",  round1(finalScore));
        level.put("grade",        grade);
        level.put("grade_color",  gradeColor(grade));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PROXIMITY TAG — how close is CMP to each level
    // ─────────────────────────────────────────────────────────────────────────
    private void tagProximity(Map<String, Object> level, double cmp) {
        double price = toDouble(level.get("price"));
        double distPct = Math.abs(price - cmp) / cmp * 100;
        double distAbs = Math.abs(price - cmp);

        String proximity;
        if (distPct < 0.3)      proximity = "AT LEVEL";
        else if (distPct < 1.0) proximity = "VERY NEAR";
        else if (distPct < 2.0) proximity = "NEAR";
        else if (distPct < 5.0) proximity = "MODERATE";
        else                    proximity = "FAR";

        level.put("dist_pct",  round2(distPct));
        level.put("dist_abs",  round2(distAbs));
        level.put("proximity", proximity);
        level.put("is_active", distPct < 2.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TREND BIAS
    // ─────────────────────────────────────────────────────────────────────────
    private String trendBias(List<Double> closes) {
        if (closes.size() < 20) return "NEUTRAL";
        double ema20 = ema(closes, 20);
        double ema50 = closes.size() >= 50 ? ema(closes, 50) : ema20;
        double cmp   = closes.get(closes.size() - 1);
        if (cmp > ema20 && ema20 > ema50) return "BULLISH";
        if (cmp < ema20 && ema20 < ema50) return "BEARISH";
        return "NEUTRAL";
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ATR COMPUTATION
    // ─────────────────────────────────────────────────────────────────────────
    private double computeATR(List<Double> highs, List<Double> lows,
                              List<Double> closes, int period) {
        if (closes.size() < period + 1) return 0;
        List<Double> trs = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            double tr = Math.max(highs.get(i) - lows.get(i),
                        Math.max(Math.abs(highs.get(i) - closes.get(i-1)),
                                 Math.abs(lows.get(i) - closes.get(i-1))));
            trs.add(tr);
        }
        return trs.subList(Math.max(0, trs.size() - period), trs.size())
                  .stream().mapToDouble(d -> d).average().orElse(0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private void addLevel(List<Map<String, Object>> list, double price,
                          String zone, String name, String source, int strength) {
        if (price <= 0) return;
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("price",         round2(price));
        l.put("zone_type",     zone);
        l.put("name",          name);
        l.put("source",        source);
        l.put("base_strength", strength);
        l.put("confluence",    1);
        list.add(l);
    }

    private double ema(List<Double> prices, int period) {
        if (prices == null || prices.size() < period) return prices != null && !prices.isEmpty() ? prices.get(prices.size()-1) : 0;
        double k = 2.0 / (period + 1);
        double e = prices.subList(0, period).stream().mapToDouble(d -> d).average().orElse(0);
        for (int i = period; i < prices.size(); i++) e = prices.get(i) * k + e * (1 - k);
        return e;
    }

    private String gradeColor(String grade) {
        return switch (grade) {
            case "VERY STRONG" -> "emerald";
            case "STRONG"      -> "green";
            case "MODERATE"    -> "yellow";
            default            -> "slate";
        };
    }

    private double toDouble(Object val) {
        if (val == null) return 0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return 0; }
    }

    private double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
