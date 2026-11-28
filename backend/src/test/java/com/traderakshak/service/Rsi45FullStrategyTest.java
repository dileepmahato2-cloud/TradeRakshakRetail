package com.traderakshak.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The complete strategy, written exactly as specified, evaluated end to end.
 *
 * <pre>
 * ( CASH
 *   AND ( WEEKLY_RSI(14) &gt; 45
 *         AND WEEKLY_RSI(14, 1 WEEK AGO) &lt;= 45
 *         AND MARKET_CAP &gt;= 1000 CRORE )
 *   AND ( PREVIOUS_CLOSE &lt; BASE_PRICE
 *         AND CURRENT_PRICE &gt;= BASE_PRICE
 *         AND CURRENT_DAY_VOLUME &gt; AVG_VOLUME(20, PREVIOUS_COMPLETED_SESSIONS)
 *         AND STOCHASTIC_%K(14,3,3) &gt; STOCHASTIC_%D(14,3,3)
 *         AND PREVIOUS_SESSION_STOCHASTIC_%K(14,3,3) &lt;= PREVIOUS_SESSION_STOCHASTIC_%D(14,3,3) ) )
 * </pre>
 *
 * <p>Two groups, both ALL, joined by AND - which is exactly what the Strategy Builder's canvas
 * draws, and what it serialises. Every condition below is written in the form the builder emits,
 * so this test is the strategy as a user would actually build it rather than a paraphrase of it.
 *
 * <p><b>MARKET_CAP is deliberately absent.</b> The column exists on stock_master and is empty
 * for all 10,045 NSE rows, so the catalogue publishes it as NO_DATA and the builder greys it
 * out. Including it here would make the test pass against data that does not exist.
 */
class Rsi45FullStrategyTest {

    private StrategyConditionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new StrategyConditionEvaluator(new TechnicalIndicatorEngine());
    }

    // ── condition builders, in the shape the canvas emits ────────────────────

    private static Map<String, Object> base(String group, String left, String comparator) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("group", "entry");
        c.put("groupIndex", group);
        c.put("groupMode", "ALL");
        c.put("groupJoin", "AND");
        c.put("operator", "AND");
        c.put("indicatorLeft", left);
        c.put("paramsLeft", Map.of());
        c.put("paramsRight", Map.of());
        c.put("comparator", comparator);
        c.put("multiplier", 1.0);
        c.put("valueRight", 0.0);
        c.put("timeframe", "DAILY");
        return c;
    }

    /** left COMPARATOR right, both named fields. */
    private static Map<String, Object> vs(String group, String left, String comparator, String right) {
        Map<String, Object> c = base(group, left, comparator);
        c.put("indicatorRight", right);
        return c;
    }

    /** left COMPARATOR number. */
    private static Map<String, Object> val(String group, String left, String comparator, double v) {
        Map<String, Object> c = base(group, left, comparator);
        c.put("valueRight", v);
        return c;
    }

    private static final Map<String, Object> STOCH =
        Map.of("kPeriod", 14, "smoothK", 3, "dPeriod", 3);

    /**
     * The strategy, as eight rows across two groups.
     *
     * <p>The weekly crossing is written as the specification writes it - two rows, the second
     * reading one week back - rather than collapsed into CROSSES_ABOVE. Both forms are
     * available and they mean the same thing; this is the one that cannot be expressed without
     * per-operand offsets, so it is the one worth pinning.
     */
    private List<Map<String, Object>> strategy() {
        List<Map<String, Object>> out = new ArrayList<>();

        // ── Group 1: the weekly RSI(14) crossing above 45 ────────────────────
        Map<String, Object> w1 = val("1", "RSI", ">", 45);
        w1.put("paramsLeft", Map.of("period", 14));
        w1.put("timeframe", "WEEKLY");
        out.add(w1);

        Map<String, Object> w2 = val("1", "RSI", "<=", 45);
        w2.put("paramsLeft", Map.of("period", 14));
        w2.put("timeframe", "WEEKLY");
        w2.put("offsetLeft", 1);                 // one WEEKLY bar ago = 1 week ago
        out.add(w2);

        // MARKET_CAP >= 1000 CRORE is not here. See the class note.

        // ── Group 2: the entry test ──────────────────────────────────────────
        out.add(vs("2", "prev_close", "<",  "base_price"));
        out.add(vs("2", "close",      ">=", "base_price"));

        Map<String, Object> vol = vs("2", "volume", ">", "avg_volume");
        vol.put("paramsRight", Map.of("period", 20));
        out.add(vol);

        Map<String, Object> kNow = vs("2", "STOCH_K", ">", "STOCH_D");
        kNow.put("paramsLeft", STOCH);
        kNow.put("paramsRight", STOCH);
        out.add(kNow);

        Map<String, Object> kPrev = vs("2", "STOCH_K", "<=", "STOCH_D");
        kPrev.put("paramsLeft", STOCH);
        kPrev.put("paramsRight", STOCH);
        kPrev.put("offsetLeft", 1);              // previous session, both sides
        kPrev.put("offsetRight", 1);
        out.add(kPrev);

        return out;
    }

    /**
     * A qualifying series.
     *
     * <p>Built to satisfy every condition at once: a long decline that puts the weekly RSI under
     * 45 a week ago, a recovery that lifts it through 45 now, yesterday's close below the base
     * price, today's at or above it, today's volume five times its twenty-session average, and
     * the stochastic turning up off the low so %K crosses %D on this session.
     */
    private Map<String, Object> qualifyingSeries() {
        // Shape, in daily bars, found by measuring rather than by guessing - the two crossings
        // pull against each other and most shapes satisfy one or the other:
        //
        //   0..264   a long decline, 400 down to 136, which puts the weekly RSI deep under 45
        //   265..294 a steady recovery to 166, six weeks of it
        //   295..298 a four-day dip to 154, which resets the daily stochastic so %K is BELOW
        //            %D on the previous session - without this there is no crossing to detect
        //   299      today: 180, which is both above the base price and above the close six
        //            weeks ago, so the week closes up and the weekly RSI crosses 45
        //
        // Measured on this series: weekly RSI 47.3 now against 35.9 a week ago; stochastic
        // (14,3,3) %K 40.1 over %D 36.1 today, having been 23.3 under 46.1 yesterday.
        List<Double> closes = new ArrayList<>(), highs = new ArrayList<>(),
                     lows = new ArrayList<>(), opens = new ArrayList<>(), vols = new ArrayList<>();
        for (int i = 0; i < 265; i++) closes.add(400 - i * 1.0);
        double low = closes.get(closes.size() - 1);
        for (int i = 0; i < 30; i++) closes.add(low + (i + 1) * 1.0);
        double top = closes.get(closes.size() - 1);
        for (int i = 0; i < 4; i++) closes.add(top - (i + 1) * 3.0);
        closes.add(closes.get(closes.size() - 1) + 26.0);

        for (int i = 0; i < closes.size(); i++) {
            double p = closes.get(i);
            opens.add(p - 0.4);
            highs.add(p + 1.0);
            lows.add(p - 1.0);
            vols.add(i == closes.size() - 1 ? 5_000_000.0 : 1_000_000.0);
        }

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("closes", closes); d.put("highs", highs); d.put("lows", lows);
        d.put("opens", opens); d.put("volumes", vols);
        d.put("base_price", 170.0);         // yesterday 154 < 170 <= 180 today
        return d;
    }

    // ── the tests ────────────────────────────────────────────────────────────

    @Test
    void everyConditionIsIndividuallyEvaluableAndCorrect() {
        Map<String, Object> d = qualifyingSeries();
        // Each row on its own, so a failure names the condition rather than the strategy.
        for (Map<String, Object> c : strategy()) {
            Map<String, Object> solo = new LinkedHashMap<>(c);
            solo.put("groupIndex", "1");
            boolean ok = evaluator.evaluate(List.of(solo), d, "entry");
            assertTrue(ok, describe(c) + "  →  false, on a series built to satisfy it");
        }
    }

    @Test
    void theWholeStrategyIdentifiesTheQualifyingStock() {
        assertTrue(evaluator.evaluate(strategy(), qualifyingSeries(), "entry"),
            "the full eight-row strategy did not identify a stock that meets every condition");
    }

    @Test
    void itRejectsAStockThatFailsTheCrossing() {
        // Today's close below the base price: condition 2 of group 2 fails, so the whole
        // strategy must fail - an ALL group cannot be carried by its other members.
        Map<String, Object> d = qualifyingSeries();
        @SuppressWarnings("unchecked")
        List<Double> closes = (List<Double>) d.get("closes");
        closes.set(299, 160.0);   // below the 170 base price
        assertFalse(evaluator.evaluate(strategy(), d, "entry"),
            "the strategy identified a stock whose current price is below the base price");
    }

    @Test
    void itRejectsAStockOnAverageVolume() {
        Map<String, Object> d = qualifyingSeries();
        @SuppressWarnings("unchecked")
        List<Double> vols = (List<Double>) d.get("volumes");
        vols.set(299, 900_000.0);          // today below the 1,000,000 average
        assertFalse(evaluator.evaluate(strategy(), d, "entry"),
            "the strategy identified a stock whose volume is below its 20-session average");
    }

    @Test
    void theWeeklyGroupIsMeasuredOnWeeklyBars() {
        Map<String, Object> d = qualifyingSeries();
        assertTrue(evaluator.evaluate(strategy().subList(0, 2), d, "entry"),
            "the weekly RSI(14) crossing above 45 did not hold");

        // Comparing the weekly and daily VERDICTS would pin this series rather than the engine:
        // a series can satisfy both crossings at once, and this one does. What has to be true is
        // that the two are measured on different bars, so compare the numbers. The weekly RSI
        // here is ~47 against a daily RSI in the high 50s - the same indicator, two widths.
        double weekly = rsiAt("WEEKLY", d);
        double daily  = rsiAt("DAILY",  d);
        assertTrue(Math.abs(weekly - daily) > 1.0,
            "weekly RSI (" + weekly + ") and daily RSI (" + daily + ") came out within 1 point "
          + "of each other, which means the timeframe is not being applied");
    }

    /** RSI(14) on a given timeframe, read back by bisecting on the comparison. */
    private double rsiAt(String timeframe, Map<String, Object> d) {
        double lo = 0, hi = 100;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            Map<String, Object> c = val("1", "RSI", ">", mid);
            c.put("paramsLeft", Map.of("period", 14));
            c.put("timeframe", timeframe);
            if (evaluator.evaluate(List.of(c), d, "entry")) lo = mid; else hi = mid;
        }
        return Math.round((lo + hi) / 2 * 10) / 10.0;
    }

    /**
     * The explicit two-row crossing and the CROSSES_ABOVE operator agree.
     *
     * <p>They are two spellings of one idea, and a builder that offers both has to mean the same
     * thing by each or it is offering a trap.
     */
    @Test
    void theExplicitPairAgreesWithTheCrossingOperator() {
        Map<String, Object> d = qualifyingSeries();

        List<Map<String, Object>> explicit = new ArrayList<>();
        Map<String, Object> now = vs("1", "STOCH_K", ">", "STOCH_D");
        now.put("paramsLeft", STOCH); now.put("paramsRight", STOCH);
        explicit.add(now);
        Map<String, Object> before = vs("1", "STOCH_K", "<=", "STOCH_D");
        before.put("paramsLeft", STOCH); before.put("paramsRight", STOCH);
        before.put("offsetLeft", 1); before.put("offsetRight", 1);
        explicit.add(before);

        Map<String, Object> operator = vs("1", "STOCH_K", "CROSSABOVE", "STOCH_D");
        operator.put("paramsLeft", STOCH);
        operator.put("paramsRight", STOCH);

        assertEquals(evaluator.evaluate(List.of(operator), d, "entry"),
                     evaluator.evaluate(explicit, d, "entry"),
            "\"%K > %D now AND %K <= %D last session\" and \"%K crosses above %D\" disagreed");
    }

    /**
     * (14, 3, 3) is not (14, 3).
     *
     * <p>The middle 3 smooths %K. Without it the raw line jumps the full height of its range on
     * one bar and crosses %D far more often, so a crossing rule built on the fast stochastic
     * fires on noise the slow one never sees. Being able to ASK for (14,3,3) and silently get
     * (14,3) is the failure this pins.
     */
    @Test
    void theSmoothingParameterChangesTheAnswer() {
        TechnicalIndicatorEngine eng = new TechnicalIndicatorEngine();
        Map<String, Object> d = qualifyingSeries();
        @SuppressWarnings("unchecked") List<Double> c = (List<Double>) d.get("closes");
        @SuppressWarnings("unchecked") List<Double> h = (List<Double>) d.get("highs");
        @SuppressWarnings("unchecked") List<Double> l = (List<Double>) d.get("lows");

        double[] fast = eng.stochastic(h, l, c, 14, 1, 3);     // raw %K
        double[] slow = eng.stochastic(h, l, c, 14, 3, 3);     // %K smoothed over 3

        assertTrue(Math.abs(fast[0] - slow[0]) > 0.5,
            "smoothed and unsmoothed %K came out within 0.5 of each other (" + fast[0]
          + " vs " + slow[0] + "), so the smoothing parameter is not being applied");

        // And the two-argument form must keep its old meaning: the fast stochastic.
        double[] legacy = eng.stochastic(h, l, c, 14, 3);
        assertEquals(fast[0], legacy[0], 1e-9,
            "the existing two-argument stochastic changed meaning, which would silently move "
          + "every rule already written against it");
    }

    private static String describe(Map<String, Object> c) {
        return "G" + c.get("groupIndex") + " "
             + c.getOrDefault("timeframe", "DAILY") + " "
             + c.get("indicatorLeft")
             + (toInt(c.get("offsetLeft")) > 0 ? "[-" + c.get("offsetLeft") + "]" : "")
             + " " + c.get("comparator") + " "
             + (c.get("indicatorRight") != null
                 ? c.get("indicatorRight")
                   + (toInt(c.get("offsetRight")) > 0 ? "[-" + c.get("offsetRight") + "]" : "")
                 : String.valueOf(c.get("valueRight")));
    }

    private static int toInt(Object o) { return o instanceof Number n ? n.intValue() : 0; }
}
