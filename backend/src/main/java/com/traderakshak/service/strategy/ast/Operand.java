package com.traderakshak.service.strategy.ast;

import java.util.Map;

/**
 * Anything that resolves to a number when evaluated against one symbol on one date.
 *
 * <p>Sealed on purpose. The evaluator has to handle every operand kind, and a sealed hierarchy
 * turns "someone added a node type and forgot a branch" from a wrong answer at runtime into a
 * compile error. For a component whose silent wrong answer is a bad trade, that is worth the
 * small loss of extensibility.
 *
 * <p>Nested records rather than ten files: the whole operand language is small enough to read in
 * one screen, and keeping it that way is a feature.
 */
public sealed interface Operand {

    /** A raw OHLCV column on the bar being evaluated: close, open, high, low, volume. */
    record Field(String name) implements Operand {}

    /**
     * A computed indicator, read from the cache when it is warm and computed when it is not.
     *
     * @param params free-form so an indicator can take more than a period (MACD takes three,
     *               Bollinger takes a period and a multiplier). Validated against
     *               {@link FieldCatalogue}, not here.
     */
    record Indicator(String name, Map<String, Object> params) implements Operand {}

    /**
     * A financial figure, which is a triple rather than a name: metric, period, basis.
     *
     * <p>The basis is the lookup, not a modifier on it - "quarterly profit growth YoY" and
     * "quarterly profit" read different rows. Offset counts periods back from the one in force
     * on the evaluation date, so offset 1 on a QUARTER is the quarter before last.
     */
    record Fundamental(String metric, Period period, Basis basis, int offset) implements Operand {}

    /** Arithmetic over two operands, which is what "2 * AvgVolume20" needs. */
    record Arith(ArithOp op, Operand left, Operand right) implements Operand {}

    /** A constant. */
    record Literal(double value) implements Operand {}

    /**
     * The same operand, {@code barsBack} bars earlier on the condition's own timeframe.
     *
     * <p>A wrapper rather than an {@code offset} component on Field and Indicator. Three reasons.
     * It covers arithmetic for free, so "yesterday's close minus the close five days before it"
     * needs nothing new. It keeps the shift in one place in the evaluator - move the context,
     * resolve the inner operand - instead of once per operand kind, each of which would be a
     * chance to forget. And it makes "20 days ago" composable with the crossings: a crossing
     * resolves both sides on two sessions, and a shifted operand inside one simply resolves
     * further back on each.
     *
     * <p>In JSON this is sugar: {@code {"type":"field","name":"high","offset":20}} parses to a
     * Shifted wrapping a Field, because that is how the builder renders it - one row with an
     * "N bars ago" box, not a nested node the author has to reason about.
     *
     * @param barsBack how many bars earlier, counted backwards. 0 is the bar being evaluated and
     *                 parses to the inner operand with no wrapper at all.
     */
    record Shifted(Operand of, int barsBack) implements Operand {

        /**
         * A negative shift would read a bar that had not happened yet.
         *
         * <p>Rejected at construction rather than flagged by the validator, because this is the
         * one mistake in the whole language that cannot be allowed to reach a backtest. A rule
         * that peeks one bar into the future backtests beautifully and loses money live, and it
         * does so without ever looking wrong. Making the node impossible to build is worth more
         * than a warning nobody reads.
         */
        public Shifted {
            if (barsBack < 0) {
                throw new IllegalArgumentException("An offset counts bars backwards and cannot be "
                    + "negative - " + barsBack + " would read a bar that has not happened yet.");
            }
        }
    }

    enum ArithOp {
        ADD("+"), SUB("-"), MUL("*"), DIV("/");

        private final String symbol;

        ArithOp(String symbol) { this.symbol = symbol; }

        public String symbol() { return symbol; }

        public static ArithOp from(String s) {
            for (ArithOp op : values()) {
                if (op.symbol.equals(s) || op.name().equalsIgnoreCase(s)) return op;
            }
            throw new IllegalArgumentException("Unknown arithmetic operator: " + s);
        }
    }

    enum Period { QUARTER, HALF_YEAR, YEAR }

    /**
     * What a fundamental figure is measured against.
     *
     * <p>YOY is the default for anything quarterly and the builder should make choosing QOQ a
     * deliberate act: most Indian businesses have seasonality that makes quarter-on-quarter
     * growth largely a statement about which quarter it is.
     */
    enum Basis {
        ABS,        // the figure itself
        QOQ,        // vs the immediately preceding quarter
        YOY,        // vs the same quarter last year
        HOH,        // vs the preceding half-year
        FY,         // vs the preceding financial year
        AVG2, AVG3, AVG5;   // vs the mean of the last N years

        public static Basis from(String s) {
            if (s == null || s.isBlank()) return ABS;
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }
}
