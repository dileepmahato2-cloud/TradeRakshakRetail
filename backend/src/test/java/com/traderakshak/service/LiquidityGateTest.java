package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import static com.traderakshak.service.StockTrackerService.liquidityGateApplies;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the buyer/seller rule may hide a stock.
 *
 * <p>Pinned because this failed in exactly the way that is hardest to notice. At 12:31 on a
 * Tuesday - a live session by the clock - the gate hid all five shortlisted stocks and reported
 * that none of them had a buyer or a seller. The clock was right; the feed had last delivered a
 * tick two days earlier and every stored book quantity was zero. The gate read an absence of data
 * as an absence of buyers.
 *
 * <p>So the rule is: hide a row only when the feed is in a position to say something about it.
 * Every other combination fails open and shows the full list, because a reader who is shown a
 * thin-book stock loses a glance, while a reader shown an empty section loses the strategy - and
 * is told something untrue about why.
 */
class LiquidityGateTest {

    @Test
    void appliesOnlyWhenEveryConditionHolds() {
        assertTrue(liquidityGateApplies(true, true, true, true, true));
    }

    @Test
    void doesNotApplyWhenTheMarketIsClosed() {
        // The user's rule, stated directly: outside market hours this is not a rule at all. Every
        // book is empty when the exchange is shut, so a gate that ran would hide everything.
        assertFalse(liquidityGateApplies(true, true, false, true, true));
        assertFalse(liquidityGateApplies(true, true, false, false, false));
    }

    @Test
    void doesNotApplyWhenTheFeedHasNoDataFromToday() {
        // The case that actually broke. Market open, gate enabled, caller wants it - but the feed
        // has not run today, so there is no order book to check and nothing may be hidden.
        assertFalse(liquidityGateApplies(true, true, true, false, true),
            "a stale feed means unknown, and unknown must not hide a stock");
        assertFalse(liquidityGateApplies(true, true, true, false, false));
    }

    @Test
    void doesNotApplyWhenNoStockReportsAnyBookAtAll() {
        // A fresh feed can still carry no depth - the subscription may not be established yet.
        // All-zero is indistinguishable from genuine emptiness, so it cannot be acted on.
        assertFalse(liquidityGateApplies(true, true, true, true, false),
            "an all-zero reading across every stock is a feed symptom, not a market fact");
    }

    @Test
    void doesNotApplyWhenTheSettingIsOff() {
        assertFalse(liquidityGateApplies(true, false, true, true, true));
    }

    @Test
    void doesNotApplyForTheExport() {
        // The download answers "which stocks qualified", which is a question about the entry test
        // and not about who happened to be bidding when someone opened the page.
        assertFalse(liquidityGateApplies(false, true, true, true, true),
            "a stock that qualified belongs in the day's record whatever its book looked like");
    }

    @Test
    void everySingleMissingConditionIsEnoughToStandDown() {
        // Exhaustive on the five inputs: the gate is on for exactly one combination out of 32.
        int applied = 0;
        for (int mask = 0; mask < 32; mask++) {
            boolean on = liquidityGateApplies(
                (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0,
                (mask & 8) != 0, (mask & 16) != 0);
            if (on) applied++;
        }
        assertTrue(applied == 1,
            "the gate must require all five conditions, so only the all-true case may apply");
    }
}
