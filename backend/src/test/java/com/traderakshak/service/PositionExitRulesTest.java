package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import static com.traderakshak.service.PositionExitRules.Reason;
import static com.traderakshak.service.PositionExitRules.advanceHighWater;
import static com.traderakshak.service.PositionExitRules.evaluate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exit rules for an open long position.
 *
 * <p>Pinned closely because a stop-loss that does not fire is indistinguishable from one that was
 * never configured, and because the trailing stop is the one rule that cannot be checked by
 * reading a single row: it depends on a high-water mark that only this code maintains.
 *
 * <p>Entry is 100 throughout, with a 5% stop, a 10% target and a 4% trail, so the levels are 95,
 * 110 and "4% below the high".
 */
class PositionExitRulesTest {

    private static final double ENTRY = 100, STOP = 5, TARGET = 10, TRAIL = 4;

    @Test
    void holdsWhilePriceSitsBetweenTheStopAndTheTarget() {
        var d = evaluate(ENTRY, 103, 103, STOP, TRAIL, TARGET, false);
        assertFalse(d.exit());
        assertEquals(Reason.NONE, d.reason());
    }

    @Test
    void exitsOnTarget() {
        var d = evaluate(ENTRY, 110, 110, STOP, TRAIL, TARGET, false);
        assertTrue(d.exit());
        assertEquals(Reason.TARGET, d.reason());
        assertEquals(110.0, d.triggerPrice());
    }

    @Test
    void exitsOnTheFixedStopBeforePriceHasRisen() {
        // High-water equals entry, so the trail sits at 96 and the fixed stop at 95. The trail is
        // the higher of the two and therefore the rule that binds - which is correct, and is why
        // the reason must name the trail rather than the fixed stop.
        var d = evaluate(ENTRY, 95, ENTRY, STOP, TRAIL, TARGET, false);
        assertTrue(d.exit());
        assertEquals(Reason.TRAILING_SL, d.reason());
        assertEquals(96.0, d.triggerPrice());
    }

    @Test
    void withNoTrailConfiguredTheFixedStopIsTheOneThatFires() {
        var d = evaluate(ENTRY, 95, ENTRY, STOP, 0, TARGET, false);
        assertTrue(d.exit());
        assertEquals(Reason.STOP_LOSS, d.reason());
        assertEquals(95.0, d.triggerPrice());
    }

    @Test
    void theTrailingStopRidesUpWithTheHighWaterMark() {
        // Price ran to 120, so the trail is at 115.20 - well above the fixed stop at 95. A
        // retrace to 114 must close the position and keep the gain.
        var d = evaluate(ENTRY, 114, 120, STOP, TRAIL, 0, false);
        assertTrue(d.exit());
        assertEquals(Reason.TRAILING_SL, d.reason());
        assertEquals(115.20, d.triggerPrice(), 0.001);
    }

    @Test
    void aRetraceThatStaysAboveTheTrailIsStillHeld() {
        // 116 against a trail at 115.20. One-and-a-half rupees of room left.
        assertFalse(evaluate(ENTRY, 116, 120, STOP, TRAIL, 0, false).exit());
    }

    @Test
    void theTrailNeverFallsBackWhenPriceDoes() {
        // The high-water mark is an input, so this test states the contract the monitor must keep:
        // given the same high, a lower current price produces a LOWER distance to the stop, never
        // a lower stop. A trail that reset on a dip would give back everything it had locked in.
        double trailAt120 = evaluate(ENTRY, 119, 120, 0, TRAIL, 0, false).triggerPrice();
        var stillAt120 = evaluate(ENTRY, 115, 120, 0, TRAIL, 0, false);
        assertFalse(trailAt120 > 0 && stillAt120.exit() && stillAt120.triggerPrice() < 115.20,
            "the level is measured from the high, not from the current price");
        assertEquals(115.20, stillAt120.triggerPrice(), 0.001);
    }

    @Test
    void highWaterMarkOnlyEverRises() {
        assertEquals(120.0, advanceHighWater(120, ENTRY, 110), "a lower price cannot lower it");
        assertEquals(125.0, advanceHighWater(120, ENTRY, 125), "a higher price raises it");
        assertEquals(ENTRY, advanceHighWater(0, ENTRY, 90),
            "seeded from entry, so a position never above its fill still has a sane trail base");
        assertEquals(ENTRY, advanceHighWater(0, ENTRY, 0), "a missing price changes nothing");
    }

    @Test
    void targetWinsOverTheTimeExitOnTheFinalSession() {
        // Both are true. The trade is attributed to the rule that actually made the money, not to
        // the clock - otherwise every profitable exit on the last day reads as TIME_EXIT and the
        // dashboard cannot tell which rules work.
        var d = evaluate(ENTRY, 112, 112, STOP, TRAIL, TARGET, true);
        assertEquals(Reason.TARGET, d.reason());
    }

    @Test
    void stopWinsOverTheTimeExitOnTheFinalSession() {
        var d = evaluate(ENTRY, 94, ENTRY, STOP, 0, TARGET, true);
        assertEquals(Reason.STOP_LOSS, d.reason());
    }

    @Test
    void timeExitClosesAtTheCurrentPriceWhenNothingElseFired() {
        var d = evaluate(ENTRY, 103, 103, STOP, TRAIL, TARGET, true);
        assertTrue(d.exit());
        assertEquals(Reason.TIME_EXIT, d.reason());
        assertEquals(103.0, d.triggerPrice());
    }

    @Test
    void aPositionWithNoEntryPriceIsHeldRatherThanExitedOnAGuess() {
        // Entry price is attached from the official close, which does not exist at 15:30. Until it
        // lands there is nothing to measure a stop against, and booking a P&L against a price that
        // was never paid is worse than waiting.
        var d = evaluate(0, 95, 120, STOP, TRAIL, TARGET, true);
        assertFalse(d.exit());
        assertEquals(Reason.NONE, d.reason());
    }

    @Test
    void aMissingCurrentPriceIsHeldToo() {
        assertFalse(evaluate(ENTRY, 0, 120, STOP, TRAIL, TARGET, true).exit());
    }

    @Test
    void allRulesDisabledMeansOnlyTheClockCanClose() {
        assertFalse(evaluate(ENTRY, 50, 200, 0, 0, 0, false).exit(),
            "with no stop, no trail and no target configured there is nothing to breach");
        assertEquals(Reason.TIME_EXIT, evaluate(ENTRY, 50, 200, 0, 0, 0, true).reason());
    }

    @Test
    void exitIsInclusiveAtTheLevelNotStrictlyBeyondIt() {
        // Touching the stop closes the position. Requiring a breach would hold through a price
        // that sat exactly on the level all session.
        assertTrue(evaluate(ENTRY, 95, ENTRY, STOP, 0, 0, false).exit());
        assertTrue(evaluate(ENTRY, 110, 110, 0, 0, TARGET, false).exit());
    }
}
