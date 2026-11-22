package com.traderakshak.service.strategy.eval;

import com.traderakshak.service.strategy.ast.AstNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an evaluation produced - a tree, not a boolean.
 *
 * <p>The extra allocation per leaf buys the Why column: when a user asks why a stock appeared,
 * the honest answer is which conditions fired and what values they saw, and that cannot be
 * reconstructed afterwards once the indicator cache has moved on to the next session.
 *
 * <p>It also carries the reason a node is UNKNOWN, which is the difference between a screen that
 * reports "RSI needs 14 sessions, this stock has 9" and one that silently returns nothing.
 */
public record EvalResult(TriState value,
                         String label,
                         Double actual,
                         String reason,
                         List<EvalResult> children) {

    public static EvalResult of(boolean b, String label, Double actual) {
        return new EvalResult(TriState.of(b), label, actual, null, List.of());
    }

    public static EvalResult unknown(String label, String reason) {
        return new EvalResult(TriState.UNKNOWN, label, null, reason, List.of());
    }

    public static EvalResult group(TriState value, String label, List<EvalResult> children) {
        return new EvalResult(value, label, null, null, children);
    }

    public boolean matched() { return value.matched(); }

    /**
     * The leaves that fired, flattened - what goes into {@code signal_history.matched_json}.
     *
     * <p>Only the matched leaves, not the whole tree: on a rule with a dozen conditions the
     * failures are the bulk of the tree and nobody reads them, and this column becomes the
     * largest in the schema if it keeps everything.
     */
    public List<Map<String, Object>> matchedLeaves() {
        List<Map<String, Object>> out = new ArrayList<>();
        collect(this, out);
        return out;
    }

    private static void collect(EvalResult r, List<Map<String, Object>> out) {
        if (r.children().isEmpty()) {
            if (r.value() == TriState.TRUE && r.label() != null) {
                Map<String, Object> leaf = new LinkedHashMap<>();
                leaf.put("condition", r.label());
                if (r.actual() != null) leaf.put("value", r.actual());
                out.add(leaf);
            }
            return;
        }
        for (EvalResult c : r.children()) collect(c, out);
    }

    /** Why this did not produce a signal, for the diagnostics panel. */
    public List<String> unknownReasons() {
        List<String> out = new ArrayList<>();
        collectReasons(this, out);
        return out;
    }

    private static void collectReasons(EvalResult r, List<String> out) {
        if (r.value() == TriState.UNKNOWN && r.reason() != null) out.add(r.reason());
        for (EvalResult c : r.children()) collectReasons(c, out);
    }

    /** How emphatically each matched leaf cleared its bar - see the strength scorer. */
    public int leafCount() {
        if (children().isEmpty()) return 1;
        int n = 0;
        for (EvalResult c : children()) n += c.leafCount();
        return n;
    }

    /** Convenience for a branch outcome that carries no condition tree of its own. */
    public static EvalResult outcome(AstNode.Outcome o, EvalResult when) {
        return new EvalResult(TriState.of(o.action() == AstNode.Action.IDENTIFY),
                              "then " + o.action(), null, null,
                              when == null ? List.of() : List.of(when));
    }
}
