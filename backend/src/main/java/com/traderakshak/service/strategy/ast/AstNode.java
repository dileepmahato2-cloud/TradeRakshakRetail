package com.traderakshak.service.strategy.ast;

import java.util.List;

/**
 * A node in a strategy's rule tree.
 *
 * <p>Four kinds, and everything in the requirements is one of them. Keeping the set this small is
 * what keeps the evaluator short enough to be read and verified in one sitting.
 *
 * <ul>
 *   <li>{@link Logical} - AND / OR / NOT over N children</li>
 *   <li>{@link Compare} - a binary test, including BETWEEN and the crossings</li>
 *   <li>{@link Branch}  - an ordered IF / ELSE IF / ELSE chain</li>
 *   <li>{@link Quantifier} - a claim about a window of periods, not a comparison</li>
 * </ul>
 */
public sealed interface AstNode {

    record Logical(LogicalOp op, List<AstNode> children) implements AstNode {}

    /**
     * A comparison.
     *
     * @param right a list because BETWEEN takes two bounds. Every other operator takes one, and
     *              the validator enforces the arity - making this a list rather than two nullable
     *              fields keeps "RSI between 55 and 75" one node, which is how the builder renders
     *              it and how the strength scorer counts it.
     */
    record Compare(CompareOp op, Operand left, List<Operand> right) implements AstNode {}

    /**
     * IF / ELSE IF / ELSE.
     *
     * <p>Cases are ordered and the first whose condition is TRUE wins, which is the whole reason
     * this cannot be modelled as a flat list of conditions: order is semantic.
     */
    record Branch(List<Case> cases, Outcome orElse) implements AstNode {}

    record Case(AstNode when, Outcome then) {}

    /**
     * What a branch yields when it matches.
     *
     * @param strength a multiplier on the computed match strength, so a weaker ELSE IF arm can be
     *                 marked as such by the author rather than scoring identically to the first.
     */
    record Outcome(Action action, double strength) {
        public static Outcome identify() { return new Outcome(Action.IDENTIFY, 1.0); }
        public static Outcome skip()     { return new Outcome(Action.SKIP, 0.0); }
    }

    /**
     * A claim over a run of periods: rising, falling, highest, lowest.
     *
     * <p>Deliberately not a {@link Compare}. Conflating the two is tempting because they both end
     * in a boolean, but a quantifier reads N periods and has no right-hand operand, so folding it
     * into Compare means a node whose shape depends on its operator.
     */
    record Quantifier(QuantifierOp op, int periods, Operand over) implements AstNode {}

    enum Action { IDENTIFY, SKIP }

    enum LogicalOp { AND, OR, NOT }

    enum QuantifierOp { RISING, FALLING, HIGHEST, LOWEST }

    enum CompareOp {
        GT(">"), LT("<"), GTE(">="), LTE("<="), EQ("="), NEQ("!="),
        BETWEEN("BETWEEN"),
        CROSSES_ABOVE("CROSSES_ABOVE"),
        CROSSES_BELOW("CROSSES_BELOW");

        private final String symbol;

        CompareOp(String symbol) { this.symbol = symbol; }

        public String symbol() { return symbol; }

        /** How many right-hand operands this operator takes. */
        public int arity() { return this == BETWEEN ? 2 : 1; }

        /**
         * True when the operator needs the previous session as well as the current one.
         *
         * <p>This is why evaluation takes a window rather than a row, and why moving-average
         * crossover needs no special field: it is CROSSES_ABOVE over two indicator operands.
         */
        public boolean needsPriorSession() {
            return this == CROSSES_ABOVE || this == CROSSES_BELOW;
        }

        public static CompareOp from(String s) {
            if (s == null) throw new IllegalArgumentException("Missing comparison operator");
            String t = s.trim();
            for (CompareOp op : values()) {
                if (op.symbol.equalsIgnoreCase(t) || op.name().equalsIgnoreCase(t)) return op;
            }
            // "==" and "<>" are common enough in hand-written JSON to be worth accepting.
            if ("==".equals(t)) return EQ;
            if ("<>".equals(t)) return NEQ;
            throw new IllegalArgumentException("Unknown comparison operator: " + s);
        }
    }
}
