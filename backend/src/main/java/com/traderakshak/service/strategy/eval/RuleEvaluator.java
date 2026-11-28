package com.traderakshak.service.strategy.eval;

import com.traderakshak.service.strategy.ast.AstNode;
import com.traderakshak.service.strategy.ast.Operand;
import com.traderakshak.service.strategy.ast.Timeframe;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates one rule tree against one symbol on one date.
 *
 * <p>Stateless and side-effect free: everything it can see arrives through {@link EvalContext},
 * which is what lets the screening engine run it across thousands of symbols on a shared,
 * already-loaded candle window without any per-symbol setup.
 *
 * <p>Java 17 has no switch patterns (they were preview until 21), so the dispatch below is an
 * {@code instanceof} chain. {@link AstNode} is sealed, so the set is closed and complete even
 * though the compiler cannot prove exhaustiveness in this form.
 */
@Service
public class RuleEvaluator {

    /** Comparisons on doubles need a tolerance, or EQ is a coin toss on computed values. */
    private static final double EPSILON = 1e-9;

    public EvalResult eval(AstNode node, EvalContext ctx) {
        if (node instanceof AstNode.Logical n)    return logical(n, ctx);
        if (node instanceof AstNode.Compare n)    return compare(n, ctx);
        if (node instanceof AstNode.Branch n)     return branch(n, ctx);
        if (node instanceof AstNode.Quantifier n) return quantifier(n, ctx);
        return EvalResult.unknown("unknown node", "Unsupported rule node.");
    }

    // ------------------------------------------------------------------ logical

    private EvalResult logical(AstNode.Logical n, EvalContext ctx) {
        if (n.op() == AstNode.LogicalOp.NOT) {
            EvalResult inner = eval(n.children().get(0), ctx);
            return EvalResult.group(inner.value().not(), "NOT", List.of(inner));
        }

        boolean and = n.op() == AstNode.LogicalOp.AND;
        TriState acc = and ? TriState.TRUE : TriState.FALSE;
        List<EvalResult> kids = new ArrayList<>(n.children().size());

        for (AstNode child : n.children()) {
            EvalResult r = eval(child, ctx);
            kids.add(r);
            acc = and ? acc.and(r.value()) : acc.or(r.value());

            // Short-circuit only on a definite answer. Stopping on UNKNOWN would discard the
            // remaining reasons, which are exactly what the diagnostics panel needs.
            if (and && acc == TriState.FALSE) break;
            if (!and && acc == TriState.TRUE) break;
        }
        return EvalResult.group(acc, n.op().name(), kids);
    }

    // ------------------------------------------------------------------ compare

    private EvalResult compare(AstNode.Compare n, EvalContext outer) {
        String label = describe(n);

        EvalContext ctx = outer.on(n.timeframe());
        if (ctx == null) return noBars(n.timeframe(), outer, label);

        if (n.op().needsPriorSession()) return crossing(n, ctx, label);

        Double left = resolve(n.left(), ctx);
        if (left == null) {
            return EvalResult.unknown(label, describe(n.left()) + " is not available for "
                + ctx.symbol() + " on " + ctx.date() + ".");
        }

        if (n.op() == AstNode.CompareOp.BETWEEN) {
            if (n.right().size() != 2) {
                return EvalResult.unknown(label, "BETWEEN needs two bounds.");
            }
            Double lo = resolve(n.right().get(0), ctx);
            Double hi = resolve(n.right().get(1), ctx);
            if (lo == null || hi == null) {
                return EvalResult.unknown(label, "A bound of BETWEEN is not available.");
            }
            return EvalResult.of(left >= lo - EPSILON && left <= hi + EPSILON, label, left);
        }

        if (n.right().isEmpty()) {
            return EvalResult.unknown(label, "The comparison has no right-hand value.");
        }
        Double right = resolve(n.right().get(0), ctx);
        if (right == null) {
            return EvalResult.unknown(label, describe(n.right().get(0))
                + " is not available for " + ctx.symbol() + " on " + ctx.date() + ".");
        }
        return EvalResult.of(test(left, n.op(), right), label, left);
    }

    /**
     * A crossing needs yesterday as well as today, which is why evaluation takes a window.
     *
     * <p>Moving-average crossover needs no special field: it is CROSSES_ABOVE with an indicator
     * on both sides. Gap up and breakout fall out of the same mechanism.
     */
    private EvalResult crossing(AstNode.Compare n, EvalContext ctx, String label) {
        EvalContext prior = ctx.shift(-1);
        if (prior == null) {
            return EvalResult.unknown(label, "No prior session for " + ctx.symbol()
                + " before " + ctx.date() + ", so a crossing cannot be judged.");
        }
        if (n.right().isEmpty()) {
            return EvalResult.unknown(label, "The crossing has no right-hand value.");
        }

        Double lNow  = resolve(n.left(), ctx);
        Double rNow  = resolve(n.right().get(0), ctx);
        Double lPrev = resolve(n.left(), prior);
        Double rPrev = resolve(n.right().get(0), prior);

        if (lNow == null || rNow == null || lPrev == null || rPrev == null) {
            return EvalResult.unknown(label,
                "Both sessions are needed for a crossing and one of the values is missing.");
        }

        boolean fired = n.op() == AstNode.CompareOp.CROSSES_ABOVE
            ? lPrev <= rPrev + EPSILON && lNow > rNow + EPSILON
            : lPrev >= rPrev - EPSILON && lNow < rNow - EPSILON;
        return EvalResult.of(fired, label, lNow);
    }

    /**
     * UNKNOWN, not FALSE, when the bar width a condition asks for is not stored.
     *
     * <p>The distinction is the point of the three-valued logic. FALSE would mean "this stock
     * does not qualify", which is a claim about the stock; UNKNOWN means "we cannot tell", which
     * is a claim about the data - and it is the one that is true.
     */
    private EvalResult noBars(Timeframe tf, EvalContext ctx, String label) {
        return EvalResult.unknown(label, "No " + tf.label() + " candles are stored for "
            + ctx.symbol() + ", so this condition cannot be measured.");
    }

    private boolean test(double l, AstNode.CompareOp op, double r) {
        switch (op) {
            case GT:  return l > r + EPSILON;
            case LT:  return l < r - EPSILON;
            case GTE: return l >= r - EPSILON;
            case LTE: return l <= r + EPSILON;
            case EQ:  return Math.abs(l - r) <= EPSILON;
            case NEQ: return Math.abs(l - r) > EPSILON;
            default:  return false;
        }
    }

    // ------------------------------------------------------------------- branch

    /**
     * IF / ELSE IF / ELSE: the first case whose condition is definitely TRUE wins.
     *
     * <p>An UNKNOWN case does not fall through to the next one. If the first arm cannot be
     * judged, the whole branch is unknown - continuing would quietly apply a later arm's outcome
     * to a stock the author's first condition might well have claimed.
     */
    private EvalResult branch(AstNode.Branch n, EvalContext ctx) {
        List<EvalResult> seen = new ArrayList<>();
        for (AstNode.Case c : n.cases()) {
            EvalResult w = eval(c.when(), ctx);
            seen.add(w);
            if (w.value() == TriState.TRUE) {
                return EvalResult.group(
                    TriState.of(c.then().action() == AstNode.Action.IDENTIFY), "IF", seen);
            }
            if (w.value() == TriState.UNKNOWN) {
                return EvalResult.group(TriState.UNKNOWN, "IF", seen);
            }
        }
        return EvalResult.group(
            TriState.of(n.orElse().action() == AstNode.Action.IDENTIFY), "ELSE", seen);
    }

    // --------------------------------------------------------------- quantifier

    private EvalResult quantifier(AstNode.Quantifier n, EvalContext outer) {
        String label = n.op() + " for " + n.periods() + " periods: " + describe(n.over());

        EvalContext ctx = outer.on(n.timeframe());
        if (ctx == null) return noBars(n.timeframe(), outer, label);

        List<Double> s = ctx.series(n.over(), n.periods());

        if (s == null || s.size() < n.periods()) {
            int have = s == null ? 0 : s.size();
            return EvalResult.unknown(label, "Needs " + n.periods() + " periods, "
                + ctx.symbol() + " has " + have + ".");
        }
        for (Double v : s) {
            if (v == null) {
                return EvalResult.unknown(label, "A period in the run could not be measured.");
            }
        }

        // series() is oldest-first, so "rising" is each value above the one before it.
        switch (n.op()) {
            case RISING: {
                for (int i = 1; i < s.size(); i++) {
                    if (s.get(i) <= s.get(i - 1)) {
                        return EvalResult.of(false, label, s.get(s.size() - 1));
                    }
                }
                return EvalResult.of(true, label, s.get(s.size() - 1));
            }
            case FALLING: {
                for (int i = 1; i < s.size(); i++) {
                    if (s.get(i) >= s.get(i - 1)) {
                        return EvalResult.of(false, label, s.get(s.size() - 1));
                    }
                }
                return EvalResult.of(true, label, s.get(s.size() - 1));
            }
            case HIGHEST: {
                double last = s.get(s.size() - 1);
                for (int i = 0; i < s.size() - 1; i++) {
                    if (s.get(i) >= last) return EvalResult.of(false, label, last);
                }
                return EvalResult.of(true, label, last);
            }
            case LOWEST: {
                double last = s.get(s.size() - 1);
                for (int i = 0; i < s.size() - 1; i++) {
                    if (s.get(i) <= last) return EvalResult.of(false, label, last);
                }
                return EvalResult.of(true, label, last);
            }
            default:
                return EvalResult.unknown(label, "Unsupported quantifier.");
        }
    }

    // ----------------------------------------------------------------- operands

    /** Resolves an operand to a number, or null when it cannot be measured. */
    public Double resolve(Operand o, EvalContext ctx) {
        if (o instanceof Operand.Literal l) return l.value();
        if (o instanceof Operand.Field f)   return ctx.field(f.name());
        if (o instanceof Operand.Indicator i) return ctx.indicator(i.name(), i.params());
        if (o instanceof Operand.Fundamental f) {
            return ctx.fundamental(f.metric(), f.period(), f.basis(), f.offset());
        }
        if (o instanceof Operand.Shifted sh) {
            // Move the window, then resolve the inner operand there. One place, so every operand
            // kind gets "N bars ago" without each needing to know about it.
            EvalContext back = ctx.shift(-sh.barsBack());
            return back == null ? null : resolve(sh.of(), back);
        }
        if (o instanceof Operand.Arith a) {
            Double l = resolve(a.left(), ctx);
            Double r = resolve(a.right(), ctx);
            if (l == null || r == null) return null;
            switch (a.op()) {
                case ADD: return l + r;
                case SUB: return l - r;
                case MUL: return l * r;
                case DIV: return Math.abs(r) < EPSILON ? null : l / r;
                default:  return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------- labels

    private String describe(AstNode.Compare n) {
        StringBuilder sb = new StringBuilder(describe(n.left()))
            .append(' ').append(n.op().symbol());
        for (int i = 0; i < n.right().size(); i++) {
            sb.append(i == 1 ? " and " : " ").append(describe(n.right().get(i)));
        }
        // Stated only when it is not daily, so the Why column does not repeat "on Daily" on
        // every row of a single-timeframe strategy.
        if (n.timeframe() != Timeframe.DAILY) sb.append(" on ").append(n.timeframe().label());
        return sb.toString();
    }

    /** Human-readable operand, for the Why column. */
    public String describe(Operand o) {
        if (o instanceof Operand.Literal l) {
            double v = l.value();
            return v == Math.rint(v) ? String.valueOf((long) v)
                                     : String.format(Locale.ROOT, "%.2f", v);
        }
        if (o instanceof Operand.Field f) return f.name();
        if (o instanceof Operand.Indicator i) {
            Map<String, Object> p = i.params();
            if (p == null || p.isEmpty()) return i.name();
            StringBuilder sb = new StringBuilder(i.name()).append('(');
            boolean first = true;
            for (Object v : p.values()) {
                if (!first) sb.append(',');
                sb.append(v);
                first = false;
            }
            return sb.append(')').toString();
        }
        if (o instanceof Operand.Fundamental f) {
            return f.metric() + " " + f.period() + " " + f.basis();
        }
        if (o instanceof Operand.Arith a) {
            return describe(a.left()) + " " + a.op().symbol() + " " + describe(a.right());
        }
        if (o instanceof Operand.Shifted sh) {
            return describe(sh.of()) + " " + sh.barsBack()
                 + (sh.barsBack() == 1 ? " bar ago" : " bars ago");
        }
        return "?";
    }
}
