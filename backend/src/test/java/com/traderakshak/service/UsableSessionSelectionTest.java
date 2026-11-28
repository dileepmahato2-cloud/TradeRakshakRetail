package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which stored session a rebuild is allowed to treat as "as-of".
 *
 * <p>This is the bug that emptied both RSI 45 sections. The rule was an OR - a session counted as
 * usable if it had enough DAILY symbols <b>or</b> enough INTRADAY bars - but a rebuild never reads
 * intraday: {@code reconstructionUniverse} joins every uploaded symbol to a DAILY candle on
 * exactly the as-of date. A session with intraday bars and no daily candle therefore yields an
 * empty universe, an empty shortlist, and no rows at all.
 *
 * <p>Measured on the live server on 2026-11-27: 26 November had thousands of intraday bars from
 * the live feed and no usable daily candle. The OR declared it usable, it was selected as the
 * as-of session, and both sections went empty - while every individual symbol's daily series
 * still ended on the 25th. The caller's own comment already stated the correct rule: "a date with
 * a handful of live-feed bars and no daily candle is not a session this can be rebuilt from, and
 * picking one blanks the panel."
 *
 * <p>The fallback is kept deliberately: when a window holds no daily coverage at all, an
 * installation running on live bars alone should still get an answer. What must never happen
 * again is a single intraday-only day displacing the daily sessions around it.
 */
class UsableSessionSelectionTest {

    private static final long MIN_DAILY_SYMBOLS = 2000L;
    private static final long MIN_INTRADAY_BARS = 3L;

    /** The service's rule, extracted so it runs without a database. */
    private static List<String> usable(List<String> candidates, Map<String, long[]> coverage) {
        boolean anyDailyCoverage = coverage.values().stream()
            .anyMatch(c -> c[0] >= MIN_DAILY_SYMBOLS);
        List<String> out = new ArrayList<>();
        for (String d : candidates) {
            long[] c = coverage.get(d);
            if (c == null) continue;
            boolean ok = anyDailyCoverage
                ? c[0] >= MIN_DAILY_SYMBOLS
                : c[1] >= MIN_INTRADAY_BARS;
            if (ok) out.add(d);
        }
        return out;
    }

    /** {date -> {dailySymbols, intradayBars}} */
    private static Map<String, long[]> coverage(Object... pairs) {
        Map<String, long[]> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 3) {
            m.put((String) pairs[i], new long[]{((Number) pairs[i + 1]).longValue(),
                                                ((Number) pairs[i + 2]).longValue()});
        }
        return m;
    }

    @Test
    void anIntradayOnlySessionCannotBeChosenOverDailyOnes() {
        // The live server's situation on 2026-11-27, reproduced.
        List<String> days = List.of("2026-11-25", "2026-11-26");
        Map<String, long[]> cov = coverage(
            "2026-11-25", 2940, 0,      // a real daily session
            "2026-11-26", 0, 4000);     // live feed ran, no daily candle

        List<String> got = usable(days, cov);

        assertEquals(List.of("2026-11-25"), got,
            "26 Nov has no daily candle, so nothing can be rebuilt from it");
        assertFalse(got.contains("2026-11-26"),
            "selecting it as as-of is what emptied both RSI 45 sections");
        // The newest usable session is what the rebuild answers for.
        assertEquals("2026-11-25", got.get(got.size() - 1));
    }

    @Test
    void dailySessionsAreKeptAndThinOnesDropped() {
        List<String> days = List.of("2026-11-20", "2026-11-23", "2026-11-24");
        Map<String, long[]> cov = coverage(
            "2026-11-20", 2950, 1200,
            "2026-11-23", 2940, 1100,
            "2026-11-24", 12, 4);        // a handful of rows: not a session
        assertEquals(List.of("2026-11-20", "2026-11-23"), usable(days, cov));
    }

    @Test
    void intradayStillCarriesAWindowWithNoDailyDataAtAll() {
        // An installation running on live bars alone must still get an answer; the fallback is
        // why the original OR existed, and it is preserved.
        List<String> days = List.of("2026-11-25", "2026-11-26");
        Map<String, long[]> cov = coverage(
            "2026-11-25", 0, 900,
            "2026-11-26", 0, 4000);
        assertEquals(List.of("2026-11-25", "2026-11-26"), usable(days, cov));
    }

    @Test
    void aDateWithNoCoverageRowIsNeverUsable() {
        List<String> days = List.of("2026-11-23", "2026-11-26");
        Map<String, long[]> cov = coverage("2026-11-23", 2940, 0);
        assertEquals(List.of("2026-11-23"), usable(days, cov));
    }

    @Test
    void oneDailySessionAnywhereDisablesTheIntradayFallbackEverywhere() {
        // The flag is computed over the whole window, not per date. Otherwise an intraday-only
        // day would qualify on its own terms and reappear beside the daily sessions - which is
        // exactly the behaviour being removed.
        List<String> days = List.of("2026-11-23", "2026-11-26");
        Map<String, long[]> cov = coverage(
            "2026-11-23", 2940, 0,
            "2026-11-26", 0, 9999);
        List<String> got = usable(days, cov);
        assertTrue(got.contains("2026-11-23"));
        assertFalse(got.contains("2026-11-26"),
            "however many intraday bars it has, it still has no daily candle");
    }
}
