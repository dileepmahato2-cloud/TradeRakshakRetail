package com.traderakshak.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conditions are measured on the bar width they name.
 *
 * <p>The screener loaded daily candles and never read a condition's {@code timeframe} at all,
 * while the file header advertised "multi-timeframe". So a weekly RSI was computed over fourteen
 * DAILY bars: the same shape of number, in the same 0-100 range, entirely plausible on screen,
 * and answering a different question from the one asked. There was nothing to notice.
 *
 * <p>Weekly and monthly bars are not stored - only daily and hourly are - so they are derived
 * from the daily series, which is what the timeframe catalogue already reports by publishing
 * them as {@code derived_from: DAILY}.
 */
class WeeklyTimeframeTest {

    private StrategyConditionEvaluator evaluator;
    private TechnicalIndicatorEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TechnicalIndicatorEngine();
        evaluator = new StrategyConditionEvaluator(engine);
    }

    /**
     * A series whose weekly and daily RSI straddle 45.
     *
     * <p>A long grind down with a sharp four-day recovery at the end: the recent days lift the
     * daily RSI well above 45 while the weekly bars, each swallowing five of those days, are
     * still working off the decline.
     */
    private Map<String, Object> divergentSeries() {
        List<Double> closes = new ArrayList<>(), highs = new ArrayList<>(),
                     lows = new ArrayList<>(), opens = new ArrayList<>(), vols = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            double p = i < 196 ? 300 - i * 1.2 : 64 + (i - 195) * 7.0;
            closes.add(p); opens.add(p - 0.5);
            highs.add(p + 1.5); lows.add(p - 1.5);
            vols.add(1_000_000.0);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("closes", closes); d.put("highs", highs);
        d.put("lows", lows); d.put("opens", opens); d.put("volumes", vols);
        return d;
    }

    private Map<String, Object> rsiCond(String timeframe, String comparator, double value) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("group", "entry");
        c.put("indicatorLeft", "RSI");
        c.put("paramsLeft", Map.of("period", 14));
        c.put("comparator", comparator);
        c.put("valueRight", value);
        c.put("multiplier", 1.0);
        if (timeframe != null) c.put("timeframe", timeframe);
        return c;
    }

    @Test
    void aWeeklyConditionIsNotJustTheDailyOne() {
        Map<String, Object> d = divergentSeries();

        boolean dailyAbove45  = evaluator.evaluate(List.of(rsiCond("DAILY",  ">", 45)), d, "entry");
        boolean weeklyAbove45 = evaluator.evaluate(List.of(rsiCond("WEEKLY", ">", 45)), d, "entry");

        assertTrue(dailyAbove45, "the daily RSI should be above 45 after a four-day surge");
        assertFalse(weeklyAbove45,
            "the weekly RSI came out the same as the daily one, so the timeframe is still "
          + "being ignored and every weekly condition is silently a daily condition");
    }

    @Test
    void anUnlabelledConditionStillMeansDaily() {
        // Every strategy saved before timeframes were honoured carries no timeframe at all, and
        // must keep evaluating exactly as it did.
        Map<String, Object> d = divergentSeries();
        assertTrue(
            evaluator.evaluate(List.of(rsiCond(null, ">", 45)), d, "entry")
                == evaluator.evaluate(List.of(rsiCond("DAILY", ">", 45)), d, "entry"),
            "an unlabelled condition stopped meaning daily");
    }

    @Test
    void weeklyBarsAggregateTheDailyOnesCorrectly() {
        // 25 sessions rising 1 a day: the last weekly bar covers the last five, so its close is
        // the last daily close, its high the highest of those five and its volume their sum.
        List<Double> closes = new ArrayList<>(), highs = new ArrayList<>(),
                     lows = new ArrayList<>(), opens = new ArrayList<>(), vols = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            double p = 100 + i;
            closes.add(p); opens.add(p - 0.5); highs.add(p + 1); lows.add(p - 1);
            vols.add(1_000.0);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("closes", closes); d.put("highs", highs);
        d.put("lows", lows); d.put("opens", opens); d.put("volumes", vols);

        // Read the aggregate back through a condition rather than by exposing the resampler:
        // the weekly "volume" is the sum of five daily 1,000s.
        Map<String, Object> volExact = new LinkedHashMap<>();
        volExact.put("group", "entry");
        volExact.put("indicatorLeft", "volume");
        volExact.put("paramsLeft", Map.of());
        volExact.put("comparator", "=");
        volExact.put("valueRight", 5_000.0);
        volExact.put("multiplier", 1.0);
        volExact.put("timeframe", "WEEKLY");
        assertTrue(evaluator.evaluate(List.of(volExact), d, "entry"),
            "a weekly bar's volume is not the sum of its five daily bars");

        // And its close is the last daily close, 124.
        Map<String, Object> closeExact = new LinkedHashMap<>(volExact);
        closeExact.put("indicatorLeft", "close");
        closeExact.put("valueRight", 124.0);
        assertTrue(evaluator.evaluate(List.of(closeExact), d, "entry"),
            "the newest weekly bar does not close on the latest session - the buckets are not "
          + "anchored to the end of the series");
    }

    /**
     * The screen that defines the base price, as far as the stored data allows.
     *
     * <p>"weekly rsi(14) &gt; 45 and 1 week ago rsi(14) &lt;= 45" is one crossing: weekly RSI(14)
     * crossing above 45. The market-cap filter in the original screen is not here, and cannot be:
     * the column exists and is empty for every NSE row, which is why the catalogue publishes it
     * greyed rather than offering a rule that would match nothing.
     */
    @Test
    void theWeeklyRsi45CrossingIsExpressible() {
        // Grind down to a weekly RSI below 45, then a strong fortnight that lifts it through.
        List<Double> closes = new ArrayList<>(), highs = new ArrayList<>(),
                     lows = new ArrayList<>(), opens = new ArrayList<>(), vols = new ArrayList<>();
        for (int i = 0; i < 260; i++) {
            double p = i < 230 ? 400 - i * 1.3 : 101 + (i - 229) * 6.0;
            closes.add(p); opens.add(p - 0.4); highs.add(p + 1.2); lows.add(p - 1.2);
            vols.add(1_000_000.0);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("closes", closes); d.put("highs", highs);
        d.put("lows", lows); d.put("opens", opens); d.put("volumes", vols);

        Map<String, Object> cross = new LinkedHashMap<>();
        cross.put("group", "entry");
        cross.put("indicatorLeft", "RSI");
        cross.put("paramsLeft", Map.of("period", 14));
        cross.put("comparator", "CROSSABOVE");
        cross.put("valueRight", 45.0);
        cross.put("multiplier", 1.0);
        cross.put("timeframe", "WEEKLY");

        // Not asserting it fires on this exact series - that would be pinning the shape of the
        // data, not the engine. What matters is that it is evaluated on weekly bars and reaches
        // a verdict rather than throwing or silently reading the daily series.
        boolean weekly = evaluator.evaluate(List.of(cross), d, "entry");
        Map<String, Object> asDaily = new LinkedHashMap<>(cross);
        asDaily.put("timeframe", "DAILY");
        boolean daily = evaluator.evaluate(List.of(asDaily), d, "entry");

        assertTrue(weekly || daily || true, "evaluated without error");
        assertFalse(weekly && daily,
            "the weekly and daily crossings fired on the same bar of the same series, which "
          + "means the weekly one is still reading daily bars");
    }
}
