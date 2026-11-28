package com.traderakshak.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether automated trading may place an order right now.
 *
 * <p>Pure, so every condition can be asserted. This is the last thing standing between a scheduled
 * job and real money, and the failure mode is asymmetric: a guard that wrongly blocks costs a
 * missed trade, while a guard that wrongly allows places an order nobody authorised. So every
 * check fails closed, and the result carries the reasons rather than a bare boolean - a user whose
 * automation did nothing is owed an explanation.
 */
public final class AutoTradeGuard {

    private AutoTradeGuard() {}

    /** @param blockedBy every reason it was refused, not just the first. */
    public record Verdict(boolean allowed, List<String> blockedBy) {
        public String summary() {
            return allowed ? "Allowed" : String.join(" ", blockedBy);
        }
    }

    /**
     * @param strategyEnabled    the strategy's own master switch.
     * @param autoSideEnabled    auto buy, or auto sell, for the side being attempted.
     * @param sessionLive        a weekday, not a holiday, inside market hours.
     * @param realMode           whether this would place a REAL order.
     * @param realTradingAllowed the user's own permission for real trading.
     * @param gatePassed         the compliance gate's decision. Already fails closed itself.
     * @param brokerReady        an active, verified, trading-enabled broker connection exists.
     * @param openPositions      positions currently open for this strategy.
     * @param maxOpenPositions   0 means no limit.
     * @param todayRealizedLoss  today's realised loss as a POSITIVE number. 0 if flat or up.
     * @param maxDailyLoss       0 means no limit.
     * @param ordersPlacedToday  how many this strategy has already placed today.
     * @param maxDailyTrades     0 means no limit.
     */
    public static Verdict evaluate(boolean strategyEnabled, boolean autoSideEnabled,
                                   boolean sessionLive, boolean realMode,
                                   boolean realTradingAllowed, boolean gatePassed,
                                   boolean brokerReady,
                                   int openPositions, int maxOpenPositions,
                                   double todayRealizedLoss, double maxDailyLoss,
                                   int ordersPlacedToday, int maxDailyTrades) {
        List<String> blocked = new ArrayList<>();

        if (!strategyEnabled) blocked.add("The strategy is switched off.");
        if (!autoSideEnabled) blocked.add("Automated trading is not enabled for this side.");
        if (!sessionLive)     blocked.add("The market is closed.");

        // The daily loss limit is checked before anything about the broker, because it is the one
        // condition that should stop a strategy that is otherwise working perfectly. A limit only
        // consulted after the broker checks pass would not be a risk limit at all.
        if (maxDailyLoss > 0 && todayRealizedLoss >= maxDailyLoss) {
            blocked.add("Today's realised loss of " + round(todayRealizedLoss)
                + " has reached the daily limit of " + round(maxDailyLoss) + ".");
        }
        if (maxOpenPositions > 0 && openPositions >= maxOpenPositions) {
            blocked.add("There are already " + openPositions + " open positions, at the limit of "
                + maxOpenPositions + ".");
        }
        if (maxDailyTrades > 0 && ordersPlacedToday >= maxDailyTrades) {
            blocked.add(ordersPlacedToday + " orders have been placed today, at the limit of "
                + maxDailyTrades + ".");
        }

        // Real money needs three more things, every one of them an explicit permission rather than
        // an absence of objection.
        if (realMode) {
            if (!realTradingAllowed) blocked.add("Real trading is not enabled for this user.");
            if (!brokerReady)        blocked.add("No active, verified, trading-enabled broker connection.");
            if (!gatePassed)         blocked.add("The pre-trade compliance gate did not pass.");
        }
        return new Verdict(blocked.isEmpty(), blocked);
    }

    private static String round(double v) {
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }
}
