package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 20-session volume average and the ratio built from it.
 *
 * <p>This decides the Volume Breakout shortlist, and the ratio is the sort key - so an error here
 * does not fail, it picks different stocks. Three things in particular are pinned: that the current
 * session is excluded from its own average, that a zero-volume session is skipped rather than
 * averaged in, and that a window which could not be filled is reported as such instead of quietly
 * yielding a ratio measured against a shorter history.
 *
 * <p>That last one is the dangerous case. A ratio is a fraction with the average on the bottom, so
 * the fewer sessions it is taken over the easier an extreme value is to produce - which means a
 * short-history stock does not merely compete on a different measure, it competes on an easier one,
 * and ranking by ratio would push it to the top of the list.
 */
class VolumeProfileTest {

    /** Newest-first, like the screen's own history: bars[0] is the session being measured. */
    private static List<StockTrackerService.DayBar> bars(double... volumesNewestFirst) {
        List<StockTrackerService.DayBar> out = new ArrayList<>();
        for (int i = 0; i < volumesNewestFirst.length; i++) {
            out.add(new StockTrackerService.DayBar("D-" + i, 100, 101, 99, volumesNewestFirst[i]));
        }
        return out;
    }

    /** One current session plus `priorSessions` prior ones, each of `each` shares. */
    private static List<StockTrackerService.DayBar> withHistory(double today, int priorSessions, double each) {
        double[] v = new double[priorSessions + 1];
        v[0] = today;
        for (int i = 1; i <= priorSessions; i++) v[i] = each;
        return bars(v);
    }

    @Test
    void averagesThePriorSessionsAndExcludesTheCurrentOne() {
        // Today 10,00,000 against twenty prior sessions of 1,00,000 each. If today leaked into its
        // own average the divisor would be 21 and the average 1,42,857 - a ratio of 7.00 instead
        // of 10.00, understating every breakout on the screen.
        var p = StockTrackerService.volumeProfile(withHistory(1_000_000, 20, 100_000), 20);

        assertEquals(1_000_000.0, p.todayVolume());
        assertEquals(100_000.0,   p.average());
        assertEquals(20,          p.sessionsUsed());
        assertEquals(10.00,       p.ratio());
        assertTrue(p.full());
    }

    @Test
    void usesExactlyTwentySessionsWhenMoreAreAvailable() {
        // Forty prior sessions: the nearest twenty at 2,00,000, then twenty older at 1,00,000.
        // Reaching past twenty would average 1,50,000 and halve every ratio on the screen.
        double[] v = new double[41];
        v[0] = 400_000;
        for (int i = 1; i <= 20; i++)  v[i] = 200_000;
        for (int i = 21; i <= 40; i++) v[i] = 100_000;

        var p = StockTrackerService.volumeProfile(bars(v), 20);

        assertEquals(200_000.0, p.average(), "only the nearest twenty count");
        assertEquals(20,        p.sessionsUsed());
        assertEquals(2.00,      p.ratio());
    }

    @Test
    void skipsZeroVolumeSessionsAndExtendsTheWindowPastThem() {
        // A halted or untraded day is not a completed session with no volume - it is not a
        // completed session. Averaging it in would divide by a day of nothing and manufacture a
        // ratio: here, five zero days inside the window would give 75,000 instead of 1,00,000,
        // turning a ratio of 2.00 into 2.67.
        double[] v = new double[27];
        v[0] = 200_000;
        v[1] = 0; v[2] = 0; v[3] = 0; v[4] = 0; v[5] = 0;
        for (int i = 6; i <= 26; i++) v[i] = 100_000;

        var p = StockTrackerService.volumeProfile(bars(v), 20);

        assertEquals(100_000.0, p.average());
        assertEquals(20,        p.sessionsUsed(), "the window reached past the five blank days");
        assertEquals(2.00,      p.ratio());
        assertTrue(p.full());
    }

    @Test
    void aShortWindowIsReportedAsNotFullRatherThanSilentlyAveragedOverFewer() {
        var p = StockTrackerService.volumeProfile(withHistory(500_000, 6, 50_000), 20);

        assertEquals(6, p.sessionsUsed());
        assertFalse(p.full(), "six sessions is not twenty, and the caller has to be able to tell");
        assertEquals(10.00, p.ratio(),
            "the ratio is still computed - it is reported, it just must not be ranked on");
    }

    @Test
    void nineteenSessionsIsStillNotFull() {
        // The boundary, because off-by-one here would admit a stock to the shortlist on a window
        // one session short and nothing downstream would notice.
        assertFalse(StockTrackerService.volumeProfile(withHistory(1, 19, 1), 20).full());
        assertTrue(StockTrackerService.volumeProfile(withHistory(1, 20, 1), 20).full());
    }

    @Test
    void zeroVolumeDaysThatPreventAFullWindowStillLeaveItNotFull() {
        // Twenty-five prior bars, but five are blank - so only twenty non-zero exist and the
        // window is exactly filled. One more blank and it is not.
        double[] ok = new double[26];
        ok[0] = 10;
        for (int i = 1; i <= 25; i++) ok[i] = (i <= 5) ? 0 : 1_000;
        assertTrue(StockTrackerService.volumeProfile(bars(ok), 20).full());

        double[] short1 = new double[26];
        short1[0] = 10;
        for (int i = 1; i <= 25; i++) short1[i] = (i <= 6) ? 0 : 1_000;
        var p = StockTrackerService.volumeProfile(bars(short1), 20);
        assertEquals(19, p.sessionsUsed());
        assertFalse(p.full());
    }

    @Test
    void aStockWithNoPriorVolumeAtAllYieldsNothingRatherThanDividingByZero() {
        var p = StockTrackerService.volumeProfile(withHistory(900_000, 20, 0), 20);

        assertEquals(0.0, p.average());
        assertEquals(0.0, p.ratio(), "no average means no ratio, not infinity");
        assertEquals(0,   p.sessionsUsed());
        assertFalse(p.full());
    }

    @Test
    void aSingleBarCannotProduceAnAverage() {
        assertEquals(0.0, StockTrackerService.volumeProfile(bars(500_000), 20).ratio());
        assertFalse(StockTrackerService.volumeProfile(bars(500_000), 20).full());
    }

    @Test
    void handlesMissingInputWithoutThrowing() {
        assertEquals(0.0, StockTrackerService.volumeProfile(null, 20).ratio());
        assertEquals(0.0, StockTrackerService.volumeProfile(List.of(), 20).ratio());
        assertEquals(0.0, StockTrackerService.volumeProfile(withHistory(1, 20, 1), 0).ratio());
    }

    @Test
    void todayBelowItsAverageGivesARatioUnderOneAndIsNotHiddenAsZero() {
        // The entry test is "ratio > 1", applied by the caller. The profile must report the real
        // figure for a stock that failed it, because the download file lists non-qualifying rows.
        var p = StockTrackerService.volumeProfile(withHistory(50_000, 20, 100_000), 20);

        assertEquals(0.50, p.ratio());
        assertTrue(p.full());
    }

    @Test
    void ratioIsRoundedToTwoDecimalsSoRepeatedLoadsCannotReorderEqualRows() {
        var p = StockTrackerService.volumeProfile(withHistory(1_000_000, 20, 333_333), 20);
        assertEquals(3.0, p.ratio(), 0.005);
        assertEquals(p.ratio(), Math.round(p.ratio() * 100.0) / 100.0);
    }
}
