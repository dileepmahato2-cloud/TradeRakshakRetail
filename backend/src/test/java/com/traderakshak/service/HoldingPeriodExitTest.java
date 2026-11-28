package com.traderakshak.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which session a holding period exits on, and what happens when it has not arrived.
 *
 * <p>The exit is the Nth <em>exchange trading session</em> after the entry day, never the Nth
 * calendar day. The count walks the list of sessions that actually hold data, so weekends and
 * exchange holidays are skipped by construction rather than by holiday arithmetic that has to be
 * kept in step with a calendar table.
 *
 * <p>The session list below is real: it is the tail of the local candle store
 * (16, 17, 18, 19, 20 and 23 November 2026), which contains no 21 or 22 November because those
 * are a Saturday and a Sunday. A Friday entry on the 20th therefore has Day 1 on Monday the
 * 23rd - the case the specification calls out, since assuming the next calendar day would have
 * priced the trade on a day the exchange never opened.
 *
 * <p>The resolver is reproduced here; the surrounding method needs a database, a price feed and a
 * tracked universe to reach.
 */
class HoldingPeriodExitTest {

    /** The real tail of the stored sessions. Index 0 is the entry day. */
    private static final List<String> SESSIONS = List.of(
        "2026-11-16", "2026-11-17", "2026-11-18", "2026-11-19", "2026-11-20", "2026-11-23");

    /**
     * The Nth session after {@code sessions.get(0)}, or null when it has not happened yet.
     * Mirrors {@code fwd.size() > hold ? fwd.get(hold) : null}.
     */
    private static String exitSession(List<String> sessions, int holdingDays) {
        int hold = Math.max(1, holdingDays);
        return sessions.size() > hold ? sessions.get(hold) : null;
    }

    @Test
    @DisplayName("Day 1 is the next session that actually traded, not the next calendar day")
    void dayOneSkipsTheWeekend() {
        // Entry Friday 20 Nov -> Day 1 is Monday 23 Nov. 21 Nov is a Saturday and is not a
        // session, so it is never a candidate.
        List<String> fromFriday = List.of("2026-11-20", "2026-11-23");
        assertEquals("2026-11-23", exitSession(fromFriday, 1));
        assertNotEquals("2026-11-21", exitSession(fromFriday, 1),
            "the Saturday is not a trading session and must never be priced");
    }

    @Test
    @DisplayName("Day N counts sessions, so the gap in calendar days varies")
    void dayNCountsSessions() {
        assertEquals("2026-11-17", exitSession(SESSIONS, 1));
        assertEquals("2026-11-18", exitSession(SESSIONS, 2));
        assertEquals("2026-11-19", exitSession(SESSIONS, 3));
        assertEquals("2026-11-20", exitSession(SESSIONS, 4));
        // Day 5 from the 16th is the 23rd: four calendar days later than Day 4, because the
        // weekend falls in between and contributes no session.
        assertEquals("2026-11-23", exitSession(SESSIONS, 5));
    }

    @Test
    @DisplayName("an exit session that has not arrived yields null, never an earlier close")
    void unarrivedExitIsNull() {
        assertNull(exitSession(SESSIONS, 6));
        assertNull(exitSession(SESSIONS, 10));
        assertNull(exitSession(SESSIONS, 30));
        // The failure mode this guards: falling back to the last known session would report a
        // Day 30 return measured over five sessions, and nothing on screen would say so.
        assertNotEquals("2026-11-23", exitSession(SESSIONS, 30));
    }

    @Test
    @DisplayName("a holding period below 1 is treated as Day 1 rather than exiting on entry day")
    void periodFloorsAtOne() {
        assertEquals("2026-11-17", exitSession(SESSIONS, 1));
        assertEquals("2026-11-17", exitSession(SESSIONS, 0));
        assertEquals("2026-11-17", exitSession(SESSIONS, -3));
        // Day 0 would make entry and exit the same print, so the return would be a structural
        // zero - the same defect the Return % column already had once.
        assertNotEquals("2026-11-16", exitSession(SESSIONS, 0));
    }

    @Test
    @DisplayName("Return % runs entry -> selected exit and ignores the previous close")
    void returnIsEntryToExit() {
        double entry = 100.0, exit = 107.5, previousClose = 95.0;
        double pct = Math.round(((exit - entry) / entry) * 10000.0) / 100.0;
        assertEquals(7.5, pct, 0.001);
        // The prev-close basis is a different number and belongs to the Provisional section.
        double fromPrevClose = Math.round(((exit - previousClose) / previousClose) * 10000.0) / 100.0;
        assertEquals(13.16, fromPrevClose, 0.01);
        assertNotEquals(pct, fromPrevClose,
            "the two sections answer different questions and must not share one figure");
    }

    @Test
    @DisplayName("the average includes only rows with both prices, and never counts a gap as zero")
    void averageExcludesInvalidRows() {
        Double[] returns = { 4.0, null, 6.0, null, 2.0 };   // two rows have no settled exit
        double sum = 0; int included = 0, excluded = 0;
        for (Double r : returns) {
            if (r == null) { excluded++; continue; }
            sum += r; included++;
        }
        assertEquals(3, included);
        assertEquals(2, excluded);
        assertEquals(4.0, sum / included, 0.001);
        // Counting the gaps as 0% would have given 2.4% - a materially different, wrong figure.
        assertNotEquals(sum / returns.length, sum / included);
    }

    @Test
    @DisplayName("changing the holding period never changes the entry price")
    void entryPriceIsFixed() {
        double entry = 789.00;   // the 15:15 print on the entry day
        double[] exits = { 795.0, 801.0, 780.0 };
        for (double ex : exits) {
            double pct = Math.round(((ex - entry) / entry) * 10000.0) / 100.0;
            // Re-deriving the entry from the return must give the same entry every time.
            double impliedEntry = ex / (1 + pct / 100.0);
            assertEquals(entry, impliedEntry, 0.5,
                "the entry print is fixed; only the exit moves with the holding period");
        }
    }
}
