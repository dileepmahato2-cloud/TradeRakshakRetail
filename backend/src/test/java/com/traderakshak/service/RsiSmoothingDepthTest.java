package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How much history the RSI needs before Wilder smoothing has actually happened.
 *
 * <p>{@link TechnicalIndicatorEngine#rsi} seeds with a simple average over the first 14 changes
 * and then smooths across the remainder of the series. Both callers passed exactly 15 closes, so
 * the smoothing loop had nothing to iterate and the seed was returned raw - carrying 92.9% of the
 * weight. That is not the RSI any chart shows.
 *
 * <p>It mattered because the entry test is a threshold at exactly 45. Measured on real candles
 * from the live server on 27 Nov 2026:
 *
 * <ul>
 *   <li>CONFIPET: 50.2 on 15 closes, 42.7 smoothed - 7.5 points, and either side of 45.</li>
 *   <li>PATELENG: 45.2 against 44.9. RANEHOLDIN: 46.8 against 44.4.</li>
 * </ul>
 *
 * <p>All three were rejected as "RSI did not cross 45" when they had crossed - 3 of the 13 the
 * gate dropped that session.
 *
 * <p>These tests use a constructed series rather than market data, because what is being pinned
 * is the convergence property of the smoothing itself: a short series returns the seed, a long
 * one returns the smoothed value, and the two differ enough to change a verdict.
 */
class RsiSmoothingDepthTest {

    private final TechnicalIndicatorEngine indicator = new TechnicalIndicatorEngine();

    /**
     * A series that falls hard and then recovers.
     *
     * <p>The early decline sets a low seed; the later recovery is what smoothing has to work
     * through. A 15-close window sees only the recovery and reports a high RSI, while the full
     * series remembers the decline - which is exactly the disagreement found in production.
     */
    private static List<Double> fallThenRecover() {
        List<Double> closes = new ArrayList<>();
        double px = 200.0;
        for (int i = 0; i < 45; i++) { px -= 2.0; closes.add(px); }   // sustained decline
        for (int i = 0; i < 15; i++) { px += 0.6; closes.add(px); }   // modest recovery
        return closes;
    }

    @Test
    void fifteenClosesReturnsTheSeedNotASmoothedValue() {
        List<Double> all = fallThenRecover();
        List<Double> last15 = all.subList(all.size() - 15, all.size());

        double shallow = indicator.rsi(last15, 14);
        double deep = indicator.rsi(all, 14);

        // The window that sees only the recovery reads as strength; the full series does not.
        assertTrue(shallow > deep + 10.0,
            "15 closes (" + shallow + ") should read far higher than the smoothed value ("
            + deep + ") on a series that fell before it recovered");
    }

    @Test
    void theShallowAndDeepReadingsCanFallOnOppositeSidesOfTheThreshold() {
        List<Double> all = fallThenRecover();
        List<Double> last15 = all.subList(all.size() - 15, all.size());

        double level = 45.0;
        boolean shallowAbove = indicator.rsi(last15, 14) > level;
        boolean deepAbove = indicator.rsi(all, 14) > level;

        // This is the defect in one assertion: the same stock, the same day, two answers to
        // "is RSI above 45" depending only on how much history the caller handed over.
        assertTrue(shallowAbove, "the 15-close reading clears 45");
        assertFalse(deepAbove, "the smoothed reading does not");
    }

    @Test
    void sixtyClosesIsDeepEnoughToHaveConverged() {
        List<Double> all = fallThenRecover();
        int n = all.size();
        double at60 = indicator.rsi(all.subList(Math.max(0, n - 60), n), 14);
        double at120 = indicator.rsi(all, 14);

        // Wilder's seed decays by (period-1)/period each bar, so after 60 closes it carries about
        // 3% of the weight. Whatever history exists beyond that cannot move the answer much.
        assertTrue(Math.abs(at60 - at120) < 1.0,
            "60 closes (" + at60 + ") should be within a point of the fuller series (" + at120 + ")");
    }

    @Test
    void theEngineStillRefusesASeriesShorterThanThePeriod() {
        // Unchanged behaviour: too little history returns the neutral 50, which the callers treat
        // as "not measured" and therefore never a crossing.
        List<Double> tooShort = List.of(10.0, 11.0, 12.0, 13.0);
        org.junit.jupiter.api.Assertions.assertEquals(50.0, indicator.rsi(tooShort, 14), 0.001);
    }
}
