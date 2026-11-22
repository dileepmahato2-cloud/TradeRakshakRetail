package com.traderakshak.service.strategy.eval;

/**
 * Three-valued logic, because "could not be measured" is not "did not qualify".
 *
 * <p>If RSI cannot be computed for a stock with nine days of listed history, {@code RSI > 60} is
 * not false - it is unknown. Collapsing the two means every newly listed stock silently fails
 * every rule, with no error anywhere, and nobody notices for months because the screener keeps
 * returning plausible results.
 *
 * <p>The asymmetry in {@link #and} is the part worth reading twice. {@code FALSE AND UNKNOWN} is
 * FALSE - one definite failure settles the conjunction however unknown the rest of it is. But
 * {@code TRUE AND UNKNOWN} is UNKNOWN, because the answer genuinely depends on the thing that
 * could not be measured. Getting this backwards is the difference between excluding a stock with
 * a stated reason and dropping it without one.
 */
public enum TriState {
    TRUE, FALSE, UNKNOWN;

    public TriState and(TriState other) {
        if (this == FALSE || other == FALSE) return FALSE;
        if (this == UNKNOWN || other == UNKNOWN) return UNKNOWN;
        return TRUE;
    }

    public TriState or(TriState other) {
        if (this == TRUE || other == TRUE) return TRUE;
        if (this == UNKNOWN || other == UNKNOWN) return UNKNOWN;
        return FALSE;
    }

    /** NOT UNKNOWN is UNKNOWN: negating something unmeasured measures nothing. */
    public TriState not() {
        if (this == TRUE) return FALSE;
        if (this == FALSE) return TRUE;
        return UNKNOWN;
    }

    public static TriState of(boolean b) { return b ? TRUE : FALSE; }

    /** Only a definite TRUE matches. UNKNOWN never produces a signal. */
    public boolean matched() { return this == TRUE; }
}
