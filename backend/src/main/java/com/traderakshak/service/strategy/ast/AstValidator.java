package com.traderakshak.service.strategy.ast;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Semantic checks over a parsed rule tree.
 *
 * <p>Collects every problem rather than stopping at the first, because the builder highlights
 * nodes and a user fixing one error at a time per save is the worst version of this screen.
 *
 * <p>Errors block a save. Warnings do not: a rule referencing a fundamental is perfectly valid
 * and will simply match nothing until that data lands, and refusing to let someone author it
 * today would be wrong.
 */
@Component
@RequiredArgsConstructor
public class AstValidator {

    public enum Severity { ERROR, WARNING }

    public record Issue(String path, Severity severity, String message) {}

    public record Result(List<Issue> issues) {
        public boolean ok() {
            return issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
        }
        public List<Issue> errors() {
            return issues.stream().filter(i -> i.severity() == Severity.ERROR).toList();
        }
    }

    /** How far back an offset may reach before it is more likely a typo than an intention. */
    private static final int MAX_SANE_OFFSET = 500;

    private final FieldCatalogue catalogue;
    private final TimeframeAvailability timeframes;

    public Result validate(AstNode root) {
        List<Issue> issues = new ArrayList<>();
        if (root == null) {
            issues.add(new Issue("", Severity.ERROR, "The strategy has no rule."));
            return new Result(issues);
        }
        walk(root, "", issues);
        if (!hasIdentify(root)) {
            issues.add(new Issue("", Severity.WARNING,
                "Nothing in this rule identifies a stock, so a scan will always return empty."));
        }
        return new Result(issues);
    }

    // Java 17: no switch patterns, so instanceof chains. Order matters only for readability -
    // AstNode is sealed, so the final else is genuinely unreachable for a complete hierarchy.
    private void walk(AstNode n, String path, List<Issue> out) {
        if (n instanceof AstNode.Logical l) {
            if (l.children().isEmpty()) {
                out.add(new Issue(path, Severity.ERROR, "This group has no conditions in it."));
            }
            for (int i = 0; i < l.children().size(); i++) {
                walk(l.children().get(i), path + ".children[" + i + "]", out);
            }

        } else if (n instanceof AstNode.Compare c) {
            checkTimeframe(c.timeframe(), path, out);
            checkCompare(c, path, out);

        } else if (n instanceof AstNode.Branch b) {
            if (b.cases().isEmpty()) {
                out.add(new Issue(path, Severity.ERROR, "This IF has no conditions."));
            }
            for (int i = 0; i < b.cases().size(); i++) {
                walk(b.cases().get(i).when(), path + ".cases[" + i + "].when", out);
            }

        } else if (n instanceof AstNode.Quantifier q) {
            checkTimeframe(q.timeframe(), path, out);
            checkQuantifier(q, path, out);
        }
    }

    /**
     * A timeframe with no candles behind it is a warning, not an error.
     *
     * <p>Warning because the rule is correct and will start working the day those candles are
     * collected; blocking the save would make the author rewrite a rule that is not wrong. But
     * it must be said, because the alternative - an empty result that looks exactly like a
     * strategy nobody qualifies for - is the one failure of a screener that cannot be debugged
     * from the screen.
     */
    private void checkTimeframe(Timeframe tf, String path, List<Issue> out) {
        if (tf != null && !timeframes.available(tf)) {
            out.add(new Issue(path, Severity.WARNING, timeframes.unavailableReason(tf)));
        }
    }

    private void checkCompare(AstNode.Compare c, String path, List<Issue> out) {
        int want = c.op().arity();
        if (c.right().size() != want) {
            out.add(new Issue(path, Severity.ERROR, c.op().symbol() + " takes " + want
                + (want == 1 ? " value" : " values") + "; this has " + c.right().size() + "."));
        }
        checkOperand(c.left(), path + ".left", out);
        for (int i = 0; i < c.right().size(); i++) {
            checkOperand(c.right().get(i), path + ".right[" + i + "]", out);
        }

        // Two literal bounds the wrong way round match nothing, and the mistake is invisible on
        // screen because both numbers look reasonable.
        if (c.op() == AstNode.CompareOp.BETWEEN && c.right().size() == 2
            && c.right().get(0) instanceof Operand.Literal lo
            && c.right().get(1) instanceof Operand.Literal hi
            && lo.value() > hi.value()) {
            out.add(new Issue(path, Severity.ERROR, "BETWEEN " + lo.value() + " and " + hi.value()
                + " can never be true - the lower bound is above the upper one."));
        }

        // A price compared against a 0-100 oscillator is almost always a picked-the-wrong-field
        // mistake. It is legal, so it warns rather than blocks.
        String lu = unitOf(c.left());
        if (!c.right().isEmpty()) {
            String ru = unitOf(c.right().get(0));
            if (lu != null && ru != null && !lu.equals(ru)
                && !(c.right().get(0) instanceof Operand.Literal)) {
                out.add(new Issue(path, Severity.WARNING, "Comparing " + lu + " against " + ru
                    + ". Check this is the comparison you meant."));
            }
        }
    }

    private void checkQuantifier(AstNode.Quantifier q, String path, List<Issue> out) {
        if (q.periods() < 2) {
            out.add(new Issue(path, Severity.ERROR, q.op() + " needs at least 2 periods to mean "
                + "anything; this has " + q.periods() + "."));
        } else if (q.periods() > 40) {
            out.add(new Issue(path, Severity.WARNING, q.periods() + " periods is a long run - "
                + "few stocks will satisfy it, and quarterly data may not reach that far back."));
        }
        checkOperand(q.over(), path + ".over", out);
    }

    private void checkOperand(Operand o, String path, List<Issue> out) {
        if (o instanceof Operand.Literal) {
            return;

        } else if (o instanceof Operand.Field f) {
            FieldCatalogue.FieldDef def = catalogue.get(f.name());
            if (def == null) {
                out.add(new Issue(path, Severity.ERROR, "There is no field called \""
                    + f.name() + "\"."));
                return;
            }
            warnIfNoData(def, path, out);

        } else if (o instanceof Operand.Indicator i) {
            FieldCatalogue.FieldDef def = catalogue.get(i.name());
            if (def == null) {
                out.add(new Issue(path, Severity.ERROR, "There is no indicator called \""
                    + i.name() + "\"."));
                return;
            }
            warnIfNoData(def, path, out);
            checkParams(def, i.params(), path, out);

        } else if (o instanceof Operand.Fundamental f) {
            FieldCatalogue.FieldDef def = catalogue.get(f.metric());
            if (def == null) {
                out.add(new Issue(path, Severity.ERROR, "There is no financial metric called \""
                    + f.metric() + "\"."));
                return;
            }
            warnIfNoData(def, path, out);
            if (f.offset() < 0) {
                out.add(new Issue(path, Severity.ERROR,
                    "Offset counts periods backwards and cannot be negative."));
            }
            // QOQ on an annual figure, or FY on a quarter, is a basis that cannot be computed.
            if (f.period() == Operand.Period.YEAR
                && (f.basis() == Operand.Basis.QOQ || f.basis() == Operand.Basis.HOH)) {
                out.add(new Issue(path, Severity.ERROR, f.basis()
                    + " does not apply to an annual figure."));
            }

        } else if (o instanceof Operand.Shifted sh) {
            if (sh.barsBack() > MAX_SANE_OFFSET) {
                out.add(new Issue(path, Severity.WARNING, sh.barsBack() + " bars back is around "
                    + (sh.barsBack() / 250) + " years of daily history - check this is the offset "
                    + "you meant, as most stocks will not reach that far."));
            }
            checkOperand(sh.of(), path + ".of", out);

        } else if (o instanceof Operand.Arith a) {
            if (a.op() == Operand.ArithOp.DIV
                && a.right() instanceof Operand.Literal l && l.value() == 0d) {
                out.add(new Issue(path, Severity.ERROR, "Division by zero."));
            }
            checkOperand(a.left(), path + ".left", out);
            checkOperand(a.right(), path + ".right", out);
        }
    }

    /**
     * A warning, never an error, when the platform has no data for a field.
     *
     * <p>The rule is accepted and the condition is SKIPPED at evaluation - not failed, and not
     * silently passed. Rejecting it would stop a perfectly good strategy being written ahead of
     * the data; passing it would drop a filter the author wrote without telling them. Skipping
     * it and saying so is the only reading that is both usable and honest, and it starts
     * applying on its own the moment the data lands, with nothing to edit.
     */
    private void warnIfNoData(FieldCatalogue.FieldDef def, String path, List<Issue> out) {
        if (def.availability() != FieldCatalogue.Availability.NO_DATA) return;
        out.add(new Issue(path, Severity.WARNING, def.label()
            + " has no data loaded yet. The condition is accepted and saved, but it is SKIPPED "
            + "while the data is missing - it will neither include nor exclude any stock. It "
            + "starts applying on its own once the data is loaded."));
    }

    private void checkParams(FieldCatalogue.FieldDef def, Map<String, Object> params,
                             String path, List<Issue> out) {
        if (params == null) return;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            FieldCatalogue.ParamDef p = def.params().stream()
                .filter(d -> d.name().equalsIgnoreCase(e.getKey()))
                .findFirst().orElse(null);
            if (p == null) {
                out.add(new Issue(path, Severity.WARNING, def.label() + " takes no parameter \""
                    + e.getKey() + "\"; it will be ignored."));
                continue;
            }
            if (!(e.getValue() instanceof Number num)) {
                out.add(new Issue(path, Severity.ERROR,
                    p.name() + " must be a number."));
                continue;
            }
            double v = num.doubleValue();
            if (p.min() != null && v < p.min() || p.max() != null && v > p.max()) {
                out.add(new Issue(path, Severity.ERROR, p.name() + " must be between "
                    + fmt(p.min()) + " and " + fmt(p.max()) + "; this is " + fmt(v) + "."));
            }
        }
    }

    /** The unit a comparison would be in, or null when it cannot be known. */
    private String unitOf(Operand o) {
        if (o instanceof Operand.Field f) {
            FieldCatalogue.FieldDef d = catalogue.get(f.name());
            return d == null ? null : d.unit();
        }
        if (o instanceof Operand.Indicator i) {
            FieldCatalogue.FieldDef d = catalogue.get(i.name());
            return d == null ? null : d.unit();
        }
        // Arithmetic mixes units by design ("2 x average volume" is still volume), so the left
        // side's unit carries through and a multiplier does not change it.
        if (o instanceof Operand.Arith a) {
            String l = unitOf(a.left());
            return l != null ? l : unitOf(a.right());
        }
        // A close 20 bars ago is still a price, so the shift must not hide the unit - otherwise
        // every "today vs N days ago" comparison would carry a spurious mismatch warning.
        if (o instanceof Operand.Shifted sh) {
            return unitOf(sh.of());
        }
        return null;
    }

    private static boolean hasIdentify(AstNode n) {
        if (n instanceof AstNode.Branch b) {
            for (AstNode.Case c : b.cases()) {
                if (c.then().action() == AstNode.Action.IDENTIFY) return true;
            }
            return b.orElse().action() == AstNode.Action.IDENTIFY;
        }
        // A bare condition tree with no branch around it identifies whatever it matches.
        return true;
    }

    private static String fmt(Double d) {
        if (d == null) return "any";
        return d == Math.rint(d) ? String.valueOf(d.longValue())
                                 : String.format(Locale.ROOT, "%.2f", d);
    }
}
