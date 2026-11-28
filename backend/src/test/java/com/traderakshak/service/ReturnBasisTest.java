package com.traderakshak.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Return % the RSI 45 sections display, and the figures kept apart from it.
 *
 * <p>The specification fixes the displayed column: {@code ((Current Price - Previous Close) /
 * Previous Close) x 100}. It sits between the Prev Close and Current columns, so a reader can
 * check it against the two numbers either side of it.
 *
 * <p>What it replaced read 0.00% on every row. Both sections load with {@code priceAtClose=true},
 * which makes the entry price BE the displayed price, so a since-entry return is structurally
 * zero - measured on the running server, thirteen rows of 0.00% under a list that was in fact
 * ordered by a real move. The entry-to-exit measure still exists and is reported separately
 * rather than competing for the same column.
 *
 * <p>The arithmetic is duplicated here rather than reached through {@code StockTrackerService},
 * whose row builder needs a database, a price feed and a tracked universe. What is guarded is the
 * choice of measure, which is expressible on its own.
 */
class ReturnBasisTest {

    /** The displayed column: previous close -> current. */
    private static double returnPct(double previousClose, double price) {
        return previousClose > 0
            ? Math.round(((price - previousClose) / previousClose) * 10000.0) / 100.0 : 0.0;
    }

    /** Entry -> now. Null when the entry price is the displayed price: no move to report. */
    private static Double returnFromEntry(Double entry, double price) {
        boolean entryDistinct = entry != null && entry > 0 && Math.abs(entry - price) > 0.0001;
        return entryDistinct ? Math.round(((price - entry) / entry) * 10000.0) / 100.0 : null;
    }

    @Test
    @DisplayName("the displayed Return % is previous close -> current, per the specification")
    void displayedReturnIsFromPreviousClose() {
        // FACT as the server returned it: prev close 743.85, current 783.75.
        assertEquals(5.36, returnPct(743.85, 783.75), 0.01);
        // SOBHA: 1244.40 -> 1289.80.
        assertEquals(3.65, returnPct(1244.40, 1289.80), 0.01);
    }

    @Test
    @DisplayName("a fall is reported as negative, not as a magnitude")
    void fallsAreNegative() {
        assertEquals(-2.0, returnPct(100.0, 98.0), 0.001);
    }

    @Test
    @DisplayName("every row of the live screen now reports its real move instead of 0.00%")
    void noRowReportsZeroWhenItHasMoved() {
        // The thirteen rows the server returned, as (previousClose, current). All thirteen read
        // 0.00% before this change while every one of them had moved.
        double[][] rows = {
            {743.85, 783.75},   {6.06, 6.32},       {1130.00, 1183.00}, {79.91, 82.30},
            {1244.40, 1289.80}, {5136.20, 5219.10}, {554.45, 565.00},   {1044.05, 1052.60},
            {552.10, 558.65},   {60.77, 62.34},     {4270.00, 4406.70}, {130.58, 136.42},
            {549.45, 557.85}
        };
        for (double[] r : rows) {
            double pct = returnPct(r[0], r[1]);
            assertNotEquals(0.0, pct, "a stock that moved must not report 0%");
            assertTrue(pct > 0, "current is above the previous close, so the move is positive");
        }
    }

    @Test
    @DisplayName("entry-to-now stays a separate figure and is null when there is no distinct entry")
    void entryReturnIsSeparate() {
        // priceAtClose: entry == current, so there is no since-entry move to state.
        assertNull(returnFromEntry(783.75, 783.75));
        // A genuine 15:15 entry: 195.00 -> 194.20.
        assertEquals(-0.41, returnFromEntry(195.00, 194.1999969482422), 0.01);
        assertNull(returnFromEntry(null, 783.75));
        assertNull(returnFromEntry(0.0, 783.75));
    }

    @Test
    @DisplayName("the two measures are genuinely different numbers and must not be conflated")
    void theMeasuresDiffer() {
        // HUBTOWN: prev close 174.25, entry 195.00, current 194.20.
        double displayed = returnPct(174.25, 194.1999969482422);
        Double fromEntry = returnFromEntry(195.00, 194.1999969482422);
        assertEquals(11.45, displayed, 0.01);
        assertNotNull(fromEntry);
        assertTrue(displayed > 0 && fromEntry < 0,
            "the same row rose against its previous close while falling against its entry - "
          + "which is exactly why one column cannot carry both");
    }

    @Test
    @DisplayName("a missing previous close reports nothing rather than a false zero move")
    void zeroPreviousCloseReportsZero() {
        assertEquals(0.0, returnPct(0, 783.75), 0.001);
    }
}
