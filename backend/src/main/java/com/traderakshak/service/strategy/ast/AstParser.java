package com.traderakshak.service.strategy.ast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a rule tree from the JSON stored in {@code strategies.rule_ast}.
 *
 * <p>The division of labour with {@link AstValidator} is deliberate. This class rejects anything
 * it cannot turn into a tree - a missing operator, a branch with no cases, an unknown node type -
 * and throws on the first one, because once the shape is wrong there is nothing further to say
 * about it. Everything that is structurally a tree but semantically wrong - an indicator that does
 * not exist, a period of -5, BETWEEN with three bounds - is the validator's job, and it collects
 * all of them so the builder can highlight every bad node at once rather than one per save.
 *
 * <p>Every error carries the JSON path of the offending node ({@code cases[0].when.children[2]}),
 * which is what lets the UI point at it instead of showing a toast.
 */
@Component
public class AstParser {

    /** A shape error, with the path of the node that caused it. */
    public static class AstParseException extends RuntimeException {
        private final String path;

        public AstParseException(String path, String message) {
            super(message);
            this.path = path;
        }

        public String path() { return path; }
    }

    public AstNode parse(String json) {
        if (json == null || json.isBlank()) {
            throw new AstParseException("", "The strategy has no rule.");
        }
        try {
            return parse(new ObjectMapper().readTree(json));
        } catch (AstParseException e) {
            throw e;
        } catch (Exception e) {
            throw new AstParseException("", "The rule is not valid JSON: " + e.getMessage());
        }
    }

    public AstNode parse(JsonNode root) {
        return node(root, "");
    }

    // ------------------------------------------------------------------ nodes

    private AstNode node(JsonNode n, String path) {
        require(n != null && n.isObject(), path, "Expected a rule node here.");
        String type = text(n, "type");
        require(type != null, path, "A rule node needs a \"type\".");

        switch (type.toLowerCase(Locale.ROOT)) {
            case "logical":    return logical(n, path);
            case "compare":    return compare(n, path);
            case "branch":     return branch(n, path);
            case "quantifier": return quantifier(n, path);
            default:
                throw new AstParseException(path, "Unknown node type \"" + type
                    + "\". Expected logical, compare, branch or quantifier.");
        }
    }

    private AstNode logical(JsonNode n, String path) {
        AstNode.LogicalOp op;
        try {
            op = AstNode.LogicalOp.valueOf(req(n, "op", path).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AstParseException(path, "Unknown logical operator \"" + text(n, "op")
                + "\". Expected AND, OR or NOT.");
        }
        JsonNode kids = n.get("children");
        require(kids != null && kids.isArray() && kids.size() > 0, path,
                "A logical node needs at least one child.");
        // NOT over several children has no agreed meaning - it is either "none of" or a typo, and
        // guessing which is how a rule quietly means something other than it says.
        require(op != AstNode.LogicalOp.NOT || kids.size() == 1, path,
                "NOT takes exactly one child; this has " + kids.size() + ".");

        List<AstNode> children = new ArrayList<>(kids.size());
        for (int i = 0; i < kids.size(); i++) {
            children.add(node(kids.get(i), path + ".children[" + i + "]"));
        }
        return new AstNode.Logical(op, children);
    }

    private AstNode compare(JsonNode n, String path) {
        AstNode.CompareOp op;
        try {
            op = AstNode.CompareOp.from(req(n, "op", path));
        } catch (IllegalArgumentException e) {
            throw new AstParseException(path, e.getMessage());
        }
        JsonNode left = n.get("left");
        require(left != null, path, "A comparison needs a left operand.");

        // BETWEEN's two bounds may be written as an array or as "low"/"high"; both read naturally
        // in hand-written JSON and both arrive from the builder, so both are accepted here and the
        // count is checked by the validator.
        List<Operand> right = new ArrayList<>(2);
        JsonNode r = n.get("right");
        if (r != null && r.isArray()) {
            for (int i = 0; i < r.size(); i++) {
                right.add(operand(r.get(i), path + ".right[" + i + "]"));
            }
        } else if (r != null && !r.isNull()) {
            right.add(operand(r, path + ".right"));
        } else if (n.has("low") || n.has("high")) {
            if (n.has("low"))  right.add(operand(n.get("low"),  path + ".low"));
            if (n.has("high")) right.add(operand(n.get("high"), path + ".high"));
        } else {
            throw new AstParseException(path, "A comparison needs a right operand.");
        }

        return new AstNode.Compare(op, operand(left, path + ".left"), right);
    }

    private AstNode branch(JsonNode n, String path) {
        JsonNode cases = n.get("cases");
        require(cases != null && cases.isArray() && cases.size() > 0, path,
                "An IF needs at least one case.");

        List<AstNode.Case> out = new ArrayList<>(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            String cp = path + ".cases[" + i + "]";
            JsonNode c = cases.get(i);
            require(c != null && c.isObject(), cp, "Expected a case object.");
            JsonNode when = c.get("when");
            require(when != null, cp, "A case needs a \"when\" condition.");
            out.add(new AstNode.Case(node(when, cp + ".when"),
                                     outcome(c.get("then"), cp + ".then", true)));
        }
        // A branch with no ELSE is a rule that silently does nothing for most stocks. Defaulting
        // it to SKIP is the honest reading and keeps the node total.
        return new AstNode.Branch(out, outcome(n.get("else"), path + ".else", false));
    }

    private AstNode.Outcome outcome(JsonNode n, String path, boolean defaultIdentify) {
        if (n == null || n.isNull()) {
            return defaultIdentify ? AstNode.Outcome.identify() : AstNode.Outcome.skip();
        }
        if (n.isTextual()) {
            return named(n.asText(), path);
        }
        require(n.isObject(), path, "Expected an outcome.");
        AstNode.Outcome base = named(text(n, "action"), path);
        double strength = n.has("strength") ? n.get("strength").asDouble(1.0) : base.strength();
        return new AstNode.Outcome(base.action(), strength);
    }

    private AstNode.Outcome named(String action, String path) {
        if (action == null || action.isBlank()) return AstNode.Outcome.identify();
        switch (action.trim().toUpperCase(Locale.ROOT)) {
            case "IDENTIFY": case "MATCH": case "BUY": return AstNode.Outcome.identify();
            case "SKIP": case "NONE": case "IGNORE":   return AstNode.Outcome.skip();
            default:
                throw new AstParseException(path,
                    "Unknown action \"" + action + "\". Expected IDENTIFY or SKIP.");
        }
    }

    private AstNode quantifier(JsonNode n, String path) {
        AstNode.QuantifierOp op;
        try {
            op = AstNode.QuantifierOp.valueOf(req(n, "op", path).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AstParseException(path, "Unknown quantifier \"" + text(n, "op")
                + "\". Expected RISING, FALLING, HIGHEST or LOWEST.");
        }
        JsonNode over = n.get("over");
        require(over != null, path, "A quantifier needs something to measure.");
        int periods = n.has("periods") ? n.get("periods").asInt(0) : 0;
        return new AstNode.Quantifier(op, periods, operand(over, path + ".over"));
    }

    // --------------------------------------------------------------- operands

    private Operand operand(JsonNode n, String path) {
        require(n != null && !n.isNull(), path, "Expected an operand here.");

        // A bare number is a literal. Writing {"type":"literal","value":2} for every constant is
        // noise in hand-written rules and in anything an LLM produces.
        if (n.isNumber()) return new Operand.Literal(n.asDouble());
        if (n.isTextual()) return new Operand.Field(n.asText());

        require(n.isObject(), path, "Expected an operand object.");
        String type = text(n, "type");
        require(type != null, path, "An operand needs a \"type\".");

        switch (type.toLowerCase(Locale.ROOT)) {
            case "literal": {
                JsonNode v = n.get("value");
                require(v != null && v.isNumber(), path, "A literal needs a numeric \"value\".");
                return new Operand.Literal(v.asDouble());
            }
            case "field":
                return new Operand.Field(req(n, "name", path));
            case "indicator":
                return new Operand.Indicator(req(n, "name", path), params(n.get("params")));
            case "fundamental": {
                String metric = req(n, "metric", path);
                Operand.Period period;
                try {
                    String p = text(n, "period");
                    period = p == null ? Operand.Period.QUARTER
                        : Operand.Period.valueOf(p.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new AstParseException(path, "Unknown period \"" + text(n, "period")
                        + "\". Expected QUARTER, HALF_YEAR or YEAR.");
                }
                Operand.Basis basis;
                try {
                    basis = Operand.Basis.from(text(n, "basis"));
                } catch (IllegalArgumentException e) {
                    throw new AstParseException(path, "Unknown basis \"" + text(n, "basis")
                        + "\". Expected ABS, QOQ, YOY, HOH, FY, AVG2, AVG3 or AVG5.");
                }
                int offset = n.has("offset") ? n.get("offset").asInt(0) : 0;
                return new Operand.Fundamental(metric, period, basis, offset);
            }
            case "arith": {
                Operand.ArithOp op;
                try {
                    op = Operand.ArithOp.from(req(n, "op", path));
                } catch (IllegalArgumentException e) {
                    throw new AstParseException(path, e.getMessage());
                }
                JsonNode l = n.get("left"), r = n.get("right");
                require(l != null && r != null, path, "Arithmetic needs both operands.");
                return new Operand.Arith(op, operand(l, path + ".left"), operand(r, path + ".right"));
            }
            default:
                throw new AstParseException(path, "Unknown operand type \"" + type
                    + "\". Expected field, indicator, fundamental, arith or literal.");
        }
    }

    private Map<String, Object> params(JsonNode n) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (n == null || !n.isObject()) return out;
        n.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (v.isNumber()) out.put(e.getKey(), v.numberValue());
            else if (v.isBoolean()) out.put(e.getKey(), v.booleanValue());
            else out.put(e.getKey(), v.asText());
        });
        return out;
    }

    // ----------------------------------------------------------------- helpers

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String req(JsonNode n, String field, String path) {
        String v = text(n, field);
        if (v == null || v.isBlank()) {
            throw new AstParseException(path, "Missing \"" + field + "\".");
        }
        return v;
    }

    private static void require(boolean ok, String path, String message) {
        if (!ok) throw new AstParseException(path, message);
    }
}
