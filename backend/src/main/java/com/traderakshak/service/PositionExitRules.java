package com.traderakshak.service;

/**
 * When an open long position should be closed, and why.
 *
 * <p>Pure and separate from the monitor that applies it, because this is the logic that decides
 * whether a stop-loss works. The columns {@code stop_loss}, {@code target} and
 * {@code trailing_sl_level} have existed on {@code paper_trades} since before this change with
 * nothing evaluating them, so there was no prior behaviour to preserve - only a rule to get right.
 *
 * <p><b>Long-only.</b> Every strategy on this platform buys first, so the comparisons are written
 * one way round deliberately rather than generalised to a side that does not exist yet. A short
 * position would need its own rules, not a sign flip on these.
 */
public final class PositionExitRules {

    private PositionExitRules() {}

    /** Why a position closed. Stored on the trade so outcomes can be attributed to rules. */
    public enum Reason { TARGET, STOP_LOSS, TRAILING_SL, TIME_EXIT, NONE }

    public record Decision(boolean exit, Reason reason, double triggerPrice) {
        static final Decision HOLD = new Decision(false, Reason.NONE, 0);
    }

    /**
     * @param entry      fill price.
     * @param current    latest traded price.
     * @param highWater  the highest price seen since entry. The trailing stop is measured from
     *                   this and from nothing else; derived from the entry price it would not
     *                   trail, and recomputed from candles it would miss the intraday high.
     * @param stopPct    fixed stop, percent below entry. 0 disables it.
     * @param trailPct   trailing stop, percent below the high-water mark. 0 disables it.
     * @param targetPct  target, percent above entry. 0 disables it.
     * @param timeExitDue the holding period has run out - for this strategy, the next session's
     *                   close has arrived.
     */
    public static Decision evaluate(double entry, double current, double highWater,
                                    double stopPct, double trailPct, double targetPct,
                                    boolean timeExitDue) {
        if (entry <= 0 || current <= 0) {
            // No entry price means the official close has not landed yet, so there is nothing to
            // measure against. Holding is the only honest answer: exiting on a guess would book a
            // P&L figure against a price that was never paid.
            return Decision.HOLD;
        }

        // Every level is rounded to paise BEFORE it is compared, not just before it is reported.
        //
        // Not cosmetic: 100 * (1 + 10/100.0) is 110.00000000000001 in binary floating point, so a
        // target of exactly 110 against a price of exactly 110 did not fire. A stop or target that
        // silently fails at precisely its own level is close to the worst possible defect here,
        // and it only showed up because the test asserted the boundary. Rounding first also keeps
        // the level that was compared identical to the level that is reported, so a trade cannot
        // claim it exited at a price the comparison never used. Prices are quoted in paise, so
        // nothing finer is meaningful anyway.
        double target = targetPct > 0 ? round(entry * (1 + targetPct / 100.0)) : 0;
        if (target > 0 && current >= target) {
            return new Decision(true, Reason.TARGET, target);
        }

        double fixedStop = stopPct > 0 ? round(entry * (1 - stopPct / 100.0)) : 0;
        // The trailing stop rides the high-water mark, so once price has risen it can sit ABOVE
        // the fixed stop. Taking the higher of the two is the point of having a trailing stop at
        // all - the fixed one would otherwise keep giving back gains the trail had locked in.
        double high = Math.max(highWater, entry);
        double trailStop = trailPct > 0 ? round(high * (1 - trailPct / 100.0)) : 0;

        boolean trailBinds = trailStop > 0 && trailStop >= fixedStop;
        double effectiveStop = Math.max(fixedStop, trailStop);
        if (effectiveStop > 0 && current <= effectiveStop) {
            // Named by which rule actually produced the level, not by which is "more important".
            // A trade reported as STOP_LOSS when the trailing stop closed it would misattribute
            // the outcome, and attribution is the whole reason exit_reason exists.
            return new Decision(true, trailBinds ? Reason.TRAILING_SL : Reason.STOP_LOSS,
                                effectiveStop);
        }

        if (timeExitDue) {
            // Last, so a target or stop touched on the final session is recorded as the rule that
            // closed it rather than as the clock running out.
            return new Decision(true, Reason.TIME_EXIT, round(current));
        }
        return Decision.HOLD;
    }

    /**
     * The high-water mark after seeing a price. Only ever rises.
     *
     * <p>Seeded from the entry price rather than from zero, so a position that has never traded
     * above its fill still has a sane trailing stop instead of one anchored at nothing.
     */
    public static double advanceHighWater(double currentHighWater, double entry, double price) {
        double base = Math.max(currentHighWater, Math.max(entry, 0));
        return Math.max(base, Math.max(price, 0));
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
