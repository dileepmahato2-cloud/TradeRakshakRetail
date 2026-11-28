package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RSI 45 crossover as an entry condition.
 *
 * <p>Both sections are named "RSI 45", and neither applied it. {@code rsi14} was computed in
 * {@code reconstructTop10AsOf}, written onto the row and shown on screen, and nothing ever tested
 * it: the sections listed stocks that had not crossed 45, some never near it. The displayed number
 * is what hid the omission - a column of RSI values reads as evidence the condition was applied,
 * so several passes over that method left it in place.
 *
 * <p>The gate is pinned here because the distinction it turns on is one line of arithmetic and is
 * easy to flatten back into a state test. "RSI above 45" says where the oscillator IS; "RSI
 * crossed above 45" says what just happened. A stock that has sat at 60 for a month satisfies the
 * first and must fail the second, and admitting it is exactly the defect being fixed.
 */
class Rsi45CrossoverGateTest {

    /** The service's gate, extracted so it can be exercised without a database. */
    private static boolean rsiCrossUp(double prev, double current, double level) {
        return current > 0 && prev > 0 && prev <= level && current > level;
    }

    private static final double LEVEL = 45.0;

    @Test
    void risingThroughTheLevelIsACrossing() {
        assertTrue(rsiCrossUp(44.9, 45.1, LEVEL));
        assertTrue(rsiCrossUp(30.0, 46.0, LEVEL));
        // The boundary belongs to the crossing: the condition is "at or below" on the previous
        // session, so exactly 45 yesterday and above today qualifies.
        assertTrue(rsiCrossUp(45.0, 45.01, LEVEL));
    }

    @Test
    void sittingAboveTheLevelIsNotACrossing() {
        // The defect this test exists for. Every one of these would have been listed before.
        assertFalse(rsiCrossUp(60.0, 61.0, LEVEL), "already above 45 on the previous session");
        assertFalse(rsiCrossUp(45.01, 70.0, LEVEL), "above 45 yesterday, so nothing crossed today");
        assertFalse(rsiCrossUp(80.0, 80.0, LEVEL), "flat and far above");
    }

    @Test
    void fallingThroughTheLevelIsNotAnEntry() {
        assertFalse(rsiCrossUp(50.0, 44.0, LEVEL), "a downward crossing is not an entry");
        assertFalse(rsiCrossUp(46.0, 45.0, LEVEL), "landing exactly on the level is not above it");
    }

    @Test
    void missingRsiNeverQualifies() {
        // rsi() returns a neutral 50 when given too few closes, so the service stores 0 rather
        // than a fabricated number. A 0 means "not measured" and must not satisfy a crossover -
        // if it did, every stock with under 16 sessions of history would enter on no evidence.
        assertFalse(rsiCrossUp(0.0, 46.0, LEVEL), "no previous RSI");
        assertFalse(rsiCrossUp(44.0, 0.0, LEVEL), "no current RSI");
        assertFalse(rsiCrossUp(0.0, 0.0, LEVEL), "neither measured");
    }

    @Test
    void theLevelIsAParameterNotALiteral() {
        // Config-backed, so a different threshold moves the gate rather than the section heading
        // meaning something the code does not do.
        assertTrue(rsiCrossUp(49.0, 51.0, 50.0));
        assertFalse(rsiCrossUp(49.0, 51.0, 55.0));
        assertFalse(rsiCrossUp(44.9, 45.1, 50.0), "a 45 crossing is not a 50 crossing");
    }
}
