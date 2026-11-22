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
