package com.traderakshak.service.strategy;

import com.traderakshak.service.strategy.ast.Timeframe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a bar width is worth offering, decided from the counts alone.
 *
 * <p>The numbers below are the ones actually measured in this database on 24 Nov 2026, because
 * this rule has already been got wrong once in exactly the way a made-up fixture would not have
 * caught: a first version tested the TOTAL bars in a trailing window, and the trailing window
 * held 4,696 hourly bars across 1,294 symbols - comfortably over any total threshold, and under
 * four bars per symbol. The depth test is the point, so the shallow case is pinned first.
 */
class TimeframeCoverageTest {

    /** {bars, symbols, sessions} as the coverage query returns them. */
    private static Map<Integer, long[]> counts(long bars, long symbols, long sessions) {
        return Map.of(60, new long[]{bars, symbols, sessions});
    }

    private static Map<Integer, String[]> range(String first, String last) {
        return Map.of(60, new String[]{first, last});
    }

    @Test
    @DisplayName("Many bars spread thinly over many symbols is NOT usable")
    void totalBarsIsTheWrongTest() {
        // The trailing-window numbers that fooled the first version of this rule.
        TimeframeCatalogue.Coverage c = TimeframeCatalogue.intradayCoverage(
            Timeframe.H1, counts(4_696, 1_294, 20), range("2026-07-27", "2026-11-16"),
            "2026-11-20");

        assertFalse(c.available(), "3 bars per symbol cannot support any indicator");
        assertEquals(3, c.barsPerSymbol());
        assertNotNull(c.reason());
        assertTrue(c.reason().contains("1294"), "the reason should show the working: " + c.reason());
    }

    @Test
    @DisplayName("The hourly history this database actually holds is usable but behind")
    void deepButStale() {
        TimeframeCatalogue.Coverage c = TimeframeCatalogue.intradayCoverage(
            Timeframe.H1, counts(2_198_511, 2_733, 156), range("2025-04-01", "2026-11-16"),
            "2026-11-20");

        assertTrue(c.available(), "804 bars a symbol is plenty to measure an indicator on");
        assertFalse(c.current(), "but it ends four sessions before the daily feed");
        assertNotNull(c.reason());
        assertTrue(c.reason().contains("2026-11-16") && c.reason().contains("2026-11-20"),
            "the reason must give both dates so the gap is visible: " + c.reason());
    }

    @Test
    @DisplayName("A width level with the daily feed is available, current and silent")
    void deepAndCurrent() {
        TimeframeCatalogue.Coverage c = TimeframeCatalogue.intradayCoverage(
            Timeframe.H1, counts(2_198_511, 2_733, 156), range("2025-04-01", "2026-11-20"),
            "2026-11-20");

        assertTrue(c.available());
        assertTrue(c.current());
        assertNull(c.reason(), "nothing to say about a width that is fully covered");
    }

    @Test
    @DisplayName("A width with no rows at all names the width that does exist")
    void missingWidthPointsAtWhatIsThere() {
        TimeframeCatalogue.Coverage c = TimeframeCatalogue.intradayCoverage(
            Timeframe.M5, counts(2_198_511, 2_733, 156), range("2025-04-01", "2026-11-16"),
            "2026-11-20");

        assertFalse(c.available());
        assertEquals(0, c.bars());
        assertTrue(c.reason().contains("1 Hour"),
            "a dead end is worth less than a next step: " + c.reason());
    }

    @Test
    @DisplayName("Daily availability is the same depth test, and weekly rides on it")
    void dailyAndItsRollUps() {
        long[] measured = {1_068_391L, 3_143L, 301L};
        TimeframeCatalogue.Coverage daily = TimeframeCatalogue.dailyCoverage(
            Timeframe.DAILY, measured, "2025-01-01", "2026-11-20");
        TimeframeCatalogue.Coverage weekly = TimeframeCatalogue.dailyCoverage(
            Timeframe.WEEKLY, measured, "2025-01-01", "2026-11-20");

        assertTrue(daily.available());
        assertEquals(339, daily.barsPerSymbol());
        assertTrue(weekly.available(), "weekly bars are rolled up from daily ones");
        assertEquals(Timeframe.DAILY, weekly.derivedFrom());
    }

    @Test
    @DisplayName("A thin daily table makes weekly unavailable too, and says why")
    void shallowDailyTakesItsRollUpsWithIt() {
        TimeframeCatalogue.Coverage weekly = TimeframeCatalogue.dailyCoverage(
            Timeframe.WEEKLY, new long[]{9_000L, 3_000L, 3L}, "2026-11-18", "2026-11-20");

        assertFalse(weekly.available());
        assertTrue(weekly.reason().contains("Weekly"), weekly.reason());
    }

    @Test
    @DisplayName("When the measurement itself fails, intraday is reported missing, not assumed")
    void fallbackErrsTowardsSayingSo() {
        Map<Timeframe, TimeframeCatalogue.Coverage> fb = TimeframeCatalogue.fallback();

        assertTrue(fb.get(Timeframe.DAILY).available());
        assertTrue(fb.get(Timeframe.MONTHLY).available());
        for (Timeframe tf : Timeframe.values()) {
            if (tf.intraday()) {
                assertFalse(fb.get(tf).available(),
                    tf + " must not be promised when coverage could not be read");
            }
        }
    }

    @Test
    @DisplayName("Every width is answered, so the selector can never show a blank row")
    void everyWidthIsCovered() {
        Set<Timeframe> answered = TimeframeCatalogue.fallback().keySet();
        for (Timeframe tf : Timeframe.values()) {
            assertTrue(answered.contains(tf), tf + " has no coverage entry");
        }
    }
}
