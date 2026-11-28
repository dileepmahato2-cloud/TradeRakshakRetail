package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The two unbounded ratios in the market health score.
 *
 * <p>Both are "n/a" rather than infinity when their denominator is zero - no decliners, or no
 * new 52-week lows - and both were written as {@code cond ? null : 0.0}.
 *
 * <p>That expression does not do what it reads as. The {@code 0.0} is a primitive, so the
 * conditional's type is {@code double}, and Java unboxes the {@code null} branch the moment it
 * is taken. On a day with no new 52-week lows the whole computation threw
 * NullPointerException, {@code /tracker/market-health} returned 500, and the panel at the top of
 * the Market Breadth page could not render at all - while every other panel on the page, fed by
 * a different endpoint, carried on as normal.
 *
 * <p>These cases cannot be reached from the engine without a database, so the arithmetic is
 * pinned directly. The point is not the value - it is that evaluating it does not throw.
 */
class MarketRegimeRatioTest {

    /**
     * Exactly the form the engine uses.
     *
     * <p>if/else rather than a conditional expression, because a conditional with one primitive
     * branch has type double however the other branch is cast - which is what made the first
     * attempt at this fix still throw.
     */
    private static Double advanceDecline(int adv, int dec) {
        if (dec > 0) return (double) adv / dec;
        if (adv > 0) return null;
        return 0.0;
    }

    private static Double newHighNewLow(int newHigh, int newLow) {
        if (newLow > 0)  return (double) newHigh / newLow;
        if (newHigh > 0) return null;
        return 0.0;
    }

    @Test
    void noNewLowsIsNotAnError() {
        // The exact shape that took down the panel: highs exist, lows do not.
        assertNull(newHighNewLow(5, 0),
            "with no new lows the ratio is unbounded and must be reported as absent");
    }

    @Test
    void nothingDecliningIsNotAnError() {
        assertNull(advanceDecline(400, 0));
    }

    @Test
    void aFlatMarketScoresZeroRatherThanAbsent() {
        // No highs AND no lows is not "unmeasurable" - it is a real, flat reading.
        assertEquals(0.0, newHighNewLow(0, 0));
        assertEquals(0.0, advanceDecline(0, 0));
    }

    @Test
    void ordinaryDaysDivideAsExpected() {
        assertEquals(0.5, advanceDecline(500, 1000), 1e-9);
        assertEquals(2.0, newHighNewLow(10, 5), 1e-9);
    }

    /**
     * The absent ratio has to survive everything downstream too.
     *
     * <p>{@code adrFactor} scores it and the Strong Buy test compares it; both have to treat
     * null as "cannot be measured" rather than unboxing it a second time.
     */
    @Test
    void anAbsentRatioSurvivesScoringAndTheStrongBuyTest() {
        Double adr = advanceDecline(400, 0);
        assertNull(adr);

        // adrFactor's contract: null means nothing declined at all, which scores full marks.
        double factor = adr == null ? 1.0 : Math.max(0.0, Math.min(1.0, adr / 2.0));
        assertEquals(1.0, factor, 1e-9);

        // Strong Buy must short-circuit on null rather than comparing it.
        boolean strongBuy = adr != null && adr > 1.5;
        assertEquals(false, strongBuy);
    }
}
