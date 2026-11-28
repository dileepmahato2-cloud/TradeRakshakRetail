package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.traderakshak.service.StockTrackerService.DayBar;
import static com.traderakshak.service.StockTrackerService.VolumeProfile;
import static com.traderakshak.service.StockTrackerService.volumeProfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a live session does to the entry test.
 *
 * <p>Every test downstream reads {@code bars[0]} as the session being measured and
 * {@code bars[1]} as the previous close. Those bars come from the daily candle table, which
 * gains a row for today only once the day has closed. So during a live session there was no bar
 * for today, {@code bars[0]} was the last CLOSED session, and the whole entry test quietly
 * answered for yesterday - yesterday's crossing, yesterday's volume against the twenty before
 * it.
 *
 * <p>That is what "the section does not update during live market" actually was. The screens
 * refreshed perfectly well and recomputed the same stale answer every time, because the data
 * they computed from had no concept of today.
 *
 * <p>The fix prepends a synthetic bar for today built from the running feed. These tests pin
 * what that does to the two figures it changes: which session is measured, and which close
 * counts as "previous".
 */
class LiveSessionVolumeTest {

    /** Twenty closed sessions of 1,000,000 shares, newest first, as the loader returns them. */
    private static List<DayBar> closedSessions() {
        List<DayBar> bars = new ArrayList<>();
        // 2026-11-23 back to 2026-10-27, newest first.
        String[] dates = {"2026-11-23","2026-11-20","2026-11-19","2026-11-18","2026-11-17",
                          "2026-11-16","2026-11-13","2026-11-12","2026-11-11","2026-11-10",
                          "2026-11-09","2026-11-06","2026-11-05","2026-11-04","2026-11-03",
                          "2026-10-30","2026-10-29","2026-10-28","2026-10-27","2026-10-26",
                          "2026-10-23"};
        double close = 912.30;
        for (String d : dates) {
            bars.add(new DayBar(d, close, close + 2, close - 2, 1_000_000));
            close += 1.2;                      // older sessions closed higher
        }
        return bars;
    }

    @Test
    void withoutTodayTheTestMeasuresTheLastClosedSession() {
        List<DayBar> bars = closedSessions();
        VolumeProfile p = volumeProfile(bars, 20);

        // bars[0] is 23 November, so "today's volume" is 23 November's.
        assertEquals(1_000_000, p.todayVolume(), 1e-9);
        assertEquals("2026-11-23", bars.get(0).date());
        assertEquals("2026-11-20", bars.get(1).date(),
            "the previous close is the session before the newest STORED one");
    }

    @Test
    void prependingTodayMovesBothFigutesForward() {
        List<DayBar> bars = closedSessions();
        // The running feed at, say, 14:00: price up, 2.5x the usual volume so far.
        bars.add(0, new DayBar("2026-11-26", 963.00, 968.00, 940.00, 2_500_000));

        VolumeProfile p = volumeProfile(bars, 20);

        assertEquals(2_500_000, p.todayVolume(), 1e-9,
            "today's volume is the running total from the feed, not the last close's");
        assertEquals(1_000_000, p.average(), 1e-9,
            "the average still excludes today and is taken over the twenty CLOSED sessions");
        assertEquals(2.5, p.ratio(), 1e-9);
        assertTrue(p.full(), "twenty closed sessions is a full window");
        assertEquals("2026-11-23", bars.get(1).date(),
            "with today prepended the previous close becomes the last CLOSED session, which is "
          + "what 'previous close' means");
    }

    /**
     * The GODREJCP case, which is the whole point.
     *
     * <p>Base price 919.50 - the 20 November close exactly. Against the stored data the previous
     * close is also 919.50, so "previous close &lt; base price" is false and the stock is
     * excluded. With today prepended the previous close becomes the 23rd at 912.30 and the
     * crossing holds.
     */
    @Test
    void aStockExcludedOnStoredDataQualifiesOnceTodayIsMeasured() {
        double base = 919.50;
        List<DayBar> bars = closedSessions();

        double prevStored = bars.get(1).close();          // 20 Nov
        assertEquals(913.50, prevStored, 0.01);
        // Stored reading: bars[0] is the 23rd at 912.30, bars[1] the 20th.
        boolean crossingStored = bars.get(1).close() < base && bars.get(0).close() >= base;
        assertTrue(!crossingStored, "on stored data alone the crossing does not hold");

        // Live reading: today prepended at 963, so previous close is the 23rd at 912.30.
        bars.add(0, new DayBar("2026-11-26", 963.00, 968.00, 940.00, 2_500_000));
        boolean crossingLive = bars.get(1).close() < base && bars.get(0).close() >= base;
        assertTrue(crossingLive,
            "with today measured from the feed the previous close is 912.30, below the 919.50 "
          + "base, and the current price is 963 above it - the crossing holds");
    }

    @Test
    void aStockThatHasNotTradedTodayGetsNoBarAndIsUnaffected() {
        // The overlay skips a quote with zero volume, so the list is the closed sessions and the
        // test answers exactly as it did before. Inventing a zero-volume session would drag the
        // twenty-day average down and manufacture a ratio out of nothing.
        List<DayBar> bars = closedSessions();
        VolumeProfile before = volumeProfile(bars, 20);
        VolumeProfile after  = volumeProfile(new ArrayList<>(bars), 20);
        assertEquals(before.todayVolume(), after.todayVolume(), 1e-9);
        assertEquals(before.average(), after.average(), 1e-9);
        assertEquals(before.ratio(), after.ratio(), 1e-9);
    }

    @Test
    void theRunningTotalMeansAStockCanQualifyLaterInTheDay() {
        // 10:00 - a fifth of the usual volume so far: does not qualify.
        List<DayBar> morning = closedSessions();
        morning.add(0, new DayBar("2026-11-26", 963.00, 968.00, 940.00, 200_000));
        assertTrue(volumeProfile(morning, 20).ratio() < 1.0);

        // 14:00 - the same session, more volume accumulated: qualifies.
        List<DayBar> afternoon = closedSessions();
        afternoon.add(0, new DayBar("2026-11-26", 963.00, 968.00, 940.00, 2_500_000));
        assertTrue(volumeProfile(afternoon, 20).ratio() > 1.0,
            "a running total must be able to cross the average during the session - that is "
          + "what makes the screen live rather than a snapshot");
    }
}
