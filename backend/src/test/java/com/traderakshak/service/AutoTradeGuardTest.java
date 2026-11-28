package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import static com.traderakshak.service.AutoTradeGuard.evaluate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last check between a scheduled job and real money.
 *
 * <p>Asserted exhaustively because the failure is asymmetric: wrongly blocking costs a missed
 * trade, wrongly allowing places an order nobody authorised. Every condition is therefore tested
 * on its own, and the paper and real paths are tested separately - the three real-money
 * permissions must not be reachable by a paper configuration.
 */
class AutoTradeGuardTest {

    /** Everything in order, paper mode. */
    private static AutoTradeGuard.Verdict paperOk() {
        return evaluate(true, true, true, false, false, false, false,
                        0, 5, 0, 5000, 0, 10);
    }

    /** Everything in order, real mode. */
    private static AutoTradeGuard.Verdict realOk() {
        return evaluate(true, true, true, true, true, true, true,
                        0, 5, 0, 5000, 0, 10);
    }

    @Test
    void allowsAPaperOrderWhenEverythingIsInOrder() {
        assertTrue(paperOk().allowed());
        assertTrue(paperOk().blockedBy().isEmpty());
    }

    @Test
    void paperModeDoesNotRequireAnyOfTheRealMoneyPermissions() {
        // Deliberate: paper trading must work for a user with no broker connected and no real
        // trading permission. Requiring them would make the simulator useless to the people most
        // in need of one.
        var v = evaluate(true, true, true, false,
                         /*realTradingAllowed*/ false, /*gatePassed*/ false, /*brokerReady*/ false,
                         0, 5, 0, 5000, 0, 10);
        assertTrue(v.allowed());
    }

    @Test
    void allowsARealOrderOnlyWithAllThreePermissions() {
        assertTrue(realOk().allowed());

        assertFalse(evaluate(true, true, true, true, false, true, true, 0, 5, 0, 5000, 0, 10).allowed(),
            "real trading not enabled for the user");
        assertFalse(evaluate(true, true, true, true, true, false, true, 0, 5, 0, 5000, 0, 10).allowed(),
            "compliance gate did not pass");
        assertFalse(evaluate(true, true, true, true, true, true, false, 0, 5, 0, 5000, 0, 10).allowed(),
            "no verified trading-enabled broker");
    }

    @Test
    void theStrategySwitchAndTheSideSwitchBothBlock() {
        assertFalse(evaluate(false, true, true, false, false, false, false, 0, 5, 0, 5000, 0, 10).allowed());
        assertFalse(evaluate(true, false, true, false, false, false, false, 0, 5, 0, 5000, 0, 10).allowed());
    }

    @Test
    void aClosedMarketBlocks() {
        assertFalse(evaluate(true, true, false, false, false, false, false, 0, 5, 0, 5000, 0, 10).allowed());
        assertTrue(evaluate(true, true, false, false, false, false, false, 0, 5, 0, 5000, 0, 10)
            .blockedBy().stream().anyMatch(s -> s.contains("market is closed")));
    }

    @Test
    void theDailyLossLimitStopsAStrategyThatIsOtherwiseWorking() {
        // The one limit that must bite while everything else is fine. At the limit, not past it.
        assertFalse(evaluate(true, true, true, false, false, false, false,
                             0, 5, /*loss*/ 5000, /*limit*/ 5000, 0, 10).allowed());
        assertTrue(evaluate(true, true, true, false, false, false, false,
                            0, 5, 4999.99, 5000, 0, 10).allowed(),
            "just under the limit is still allowed");
    }

    @Test
    void aZeroLimitMeansNoLimitRatherThanZeroTolerance() {
        // Easy to get backwards, and getting it backwards blocks every trade for every user who
        // never configured a limit.
        assertTrue(evaluate(true, true, true, false, false, false, false,
                            99, 0, 999999, 0, 99, 0).allowed(),
            "zero means unlimited for positions, loss and trade count alike");
    }

    @Test
    void theOpenPositionLimitBlocksAtTheLimit() {
        assertFalse(evaluate(true, true, true, false, false, false, false,
                             5, 5, 0, 5000, 0, 10).allowed());
        assertTrue(evaluate(true, true, true, false, false, false, false,
                            4, 5, 0, 5000, 0, 10).allowed());
    }

    @Test
    void theDailyTradeCountBlocksAtTheLimit() {
        assertFalse(evaluate(true, true, true, false, false, false, false,
                             0, 5, 0, 5000, 10, 10).allowed());
        assertTrue(evaluate(true, true, true, false, false, false, false,
                            0, 5, 0, 5000, 9, 10).allowed());
    }

    @Test
    void everyReasonIsReportedNotJustTheFirst() {
        // A user whose automation did nothing is owed the whole explanation. Returning only the
        // first reason means fixing it reveals the next one, one restart at a time.
        var v = evaluate(false, false, false, true, false, false, false,
                         9, 5, 9000, 5000, 99, 10);
        assertFalse(v.allowed());
        assertTrue(v.blockedBy().size() >= 7,
            "expected every failing condition, got: " + v.blockedBy());
        assertTrue(v.summary().contains("daily limit"));
        assertTrue(v.summary().contains("compliance gate"));
    }

    @Test
    void aPassingVerdictSummarisesAsAllowed() {
        assertEquals("Allowed", realOk().summary());
    }
}
