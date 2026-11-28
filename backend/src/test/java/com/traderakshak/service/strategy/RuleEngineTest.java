package com.traderakshak.service.strategy;

import com.traderakshak.service.strategy.ast.AstNode;
import com.traderakshak.service.strategy.ast.AstParser;
import com.traderakshak.service.strategy.ast.AstValidator;
import com.traderakshak.service.strategy.ast.FieldCatalogue;
import com.traderakshak.service.strategy.ast.Timeframe;
import com.traderakshak.service.strategy.ast.TimeframeAvailability;
import com.traderakshak.service.strategy.eval.EvalResult;
import com.traderakshak.service.strategy.eval.MapEvalContext;
import com.traderakshak.service.strategy.eval.RuleEvaluator;
import com.traderakshak.service.strategy.eval.TriState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rule engine: one test per operator, plus the three-valued logic the whole thing rests on.
 *
 * <p>No Spring context and no database - {@link MapEvalContext} supplies hand-written sessions,
 * which is what makes it affordable to test every operator rather than one happy path.
 */
class RuleEngineTest {

    private final RuleEvaluator evaluator = new RuleEvaluator();
    private final AstParser parser = new AstParser();
    private final AstValidator validator =
        new AstValidator(new FieldCatalogue(), TimeframeAvailability.ALL);

    /** Hourly and daily present, every width below an hour absent - which is this database. */
    private final TimeframeAvailability hourlyAndDailyOnly = tf ->
        tf == Timeframe.H1 || tf == Timeframe.DAILY
            || tf == Timeframe.WEEKLY || tf == Timeframe.MONTHLY;

    /** One session with a close and an RSI, which most of these tests are enough served by. */
    private MapEvalContext one(double close, Double rsi) {
        MapEvalContext.Session s = MapEvalContext.session("2026-11-20")
            .put("close", close);
        if (rsi != null) s.put("RSI(14)", rsi);
        return MapEvalContext.at("TEST", List.of(s));
    }

    private AstNode compare(String op, double threshold) {
        return parser.parse("{\"type\":\"compare\",\"op\":\"" + op + "\","
            + "\"left\":{\"type\":\"field\",\"name\":\"close\"},"
            + "\"right\":" + threshold + "}");
    }

    // ===================================================================== TriState

    @Nested
    @DisplayName("Three-valued logic")
    class TriStateLogic {

        @Test
        @DisplayName("FALSE AND UNKNOWN is FALSE - one definite failure settles it")
        void falseAndUnknownIsFalse() {
            assertEquals(TriState.FALSE, TriState.FALSE.and(TriState.UNKNOWN));
            assertEquals(TriState.FALSE, TriState.UNKNOWN.and(TriState.FALSE));
        }

        @Test
        @DisplayName("TRUE AND UNKNOWN is UNKNOWN - the answer depends on what we cannot see")
        void trueAndUnknownIsUnknown() {
            assertEquals(TriState.UNKNOWN, TriState.TRUE.and(TriState.UNKNOWN));
        }

        @Test
        @DisplayName("TRUE OR UNKNOWN is TRUE, FALSE OR UNKNOWN is UNKNOWN")
        void orSemantics() {
            assertEquals(TriState.TRUE, TriState.TRUE.or(TriState.UNKNOWN));
            assertEquals(TriState.UNKNOWN, TriState.FALSE.or(TriState.UNKNOWN));
        }

        @Test
        @DisplayName("NOT UNKNOWN is UNKNOWN")
        void notUnknown() {
            assertEquals(TriState.UNKNOWN, TriState.UNKNOWN.not());
        }

        @Test
        @DisplayName("Only TRUE matches - UNKNOWN never produces a signal")
        void onlyTrueMatches() {
            assertTrue(TriState.TRUE.matched());
            assertFalse(TriState.FALSE.matched());
            assertFalse(TriState.UNKNOWN.matched());
        }
    }

    // =================================================================== comparisons

    @Nested
    @DisplayName("Comparison operators")
    class Comparisons {

        @Test void greaterThan() {
            assertTrue(evaluator.eval(compare(">", 100), one(120, null)).matched());
            assertFalse(evaluator.eval(compare(">", 100), one(100, null)).matched());
        }

        @Test void lessThan() {
            assertTrue(evaluator.eval(compare("<", 100), one(80, null)).matched());
            assertFalse(evaluator.eval(compare("<", 100), one(100, null)).matched());
        }

        @Test void greaterOrEqual() {
            assertTrue(evaluator.eval(compare(">=", 100), one(100, null)).matched());
            assertFalse(evaluator.eval(compare(">=", 100), one(99.9, null)).matched());
        }

        @Test void lessOrEqual() {
            assertTrue(evaluator.eval(compare("<=", 100), one(100, null)).matched());
        }

        @Test
        @DisplayName("= uses a tolerance, or equality on computed values is a coin toss")
        void equalityIsTolerant() {
            assertTrue(evaluator.eval(compare("=", 100), one(100.0000000001, null)).matched());
            assertFalse(evaluator.eval(compare("=", 100), one(100.01, null)).matched());
        }

        @Test void notEqual() {
            assertTrue(evaluator.eval(compare("!=", 100), one(101, null)).matched());
            assertFalse(evaluator.eval(compare("!=", 100), one(100, null)).matched());
        }

        @Test
        @DisplayName("BETWEEN is inclusive at both bounds")
        void between() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\"BETWEEN\","
                + "\"left\":{\"type\":\"indicator\",\"name\":\"RSI\",\"params\":{\"period\":14}},"
                + "\"right\":[55,75]}");
            assertTrue(evaluator.eval(ast, one(100, 60.0)).matched());
            assertTrue(evaluator.eval(ast, one(100, 55.0)).matched());
            assertTrue(evaluator.eval(ast, one(100, 75.0)).matched());
            assertFalse(evaluator.eval(ast, one(100, 54.9)).matched());
            assertFalse(evaluator.eval(ast, one(100, 75.1)).matched());
        }
    }

    // ===================================================================== crossings

    @Nested
    @DisplayName("Crossings need two sessions")
    class Crossings {

        private MapEvalContext twoSessions(double closeYesterday, double emaYesterday,
                                           double closeToday, double emaToday) {
            return MapEvalContext.at("TEST", List.of(
                MapEvalContext.session("2026-11-19").put("close", closeYesterday).put("EMA(20)", emaYesterday),
                MapEvalContext.session("2026-11-20").put("close", closeToday).put("EMA(20)", emaToday)));
        }

        private AstNode crossing(String op) {
            return parser.parse("{\"type\":\"compare\",\"op\":\"" + op + "\","
                + "\"left\":{\"type\":\"field\",\"name\":\"close\"},"
                + "\"right\":{\"type\":\"indicator\",\"name\":\"EMA\",\"params\":{\"period\":20}}}");
        }

        @Test
        @DisplayName("CROSSES_ABOVE fires only on the session it crosses")
        void crossesAbove() {
            assertTrue(evaluator.eval(crossing("CROSSES_ABOVE"),
                twoSessions(98, 100, 105, 100)).matched());
            // Already above yesterday: still above today, but it did not cross today.
            assertFalse(evaluator.eval(crossing("CROSSES_ABOVE"),
                twoSessions(105, 100, 110, 100)).matched());
        }

        @Test void crossesBelow() {
            assertTrue(evaluator.eval(crossing("CROSSES_BELOW"),
                twoSessions(105, 100, 95, 100)).matched());
            assertFalse(evaluator.eval(crossing("CROSSES_BELOW"),
                twoSessions(95, 100, 90, 100)).matched());
        }

        @Test
        @DisplayName("No prior session is UNKNOWN, not false")
        void firstSessionIsUnknown() {
            MapEvalContext only = MapEvalContext.at("TEST", List.of(
                MapEvalContext.session("2026-11-20").put("close", 105.0).put("EMA(20)", 100.0)));
            EvalResult r = evaluator.eval(crossing("CROSSES_ABOVE"), only);
            assertEquals(TriState.UNKNOWN, r.value());
            assertFalse(r.unknownReasons().isEmpty(), "should say why it could not be judged");
        }
    }

    // =============================================================== missing data

    @Nested
    @DisplayName("Unmeasurable is not false")
    class MissingData {

        @Test
        @DisplayName("A missing indicator is UNKNOWN, with a reason")
        void missingIndicatorIsUnknown() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\","
                + "\"left\":{\"type\":\"indicator\",\"name\":\"RSI\",\"params\":{\"period\":14}},"
                + "\"right\":60}");
            EvalResult r = evaluator.eval(ast, one(100, null));   // no RSI on the session
            assertEquals(TriState.UNKNOWN, r.value());
            assertFalse(r.matched());
            assertTrue(r.unknownReasons().get(0).contains("RSI"),
                "the reason should name what was missing");
        }

        @Test
        @DisplayName("An AND with one definite failure is FALSE even if another arm is unknown")
        void definiteFailureWins() {
            AstNode ast = parser.parse("{\"type\":\"logical\",\"op\":\"AND\",\"children\":["
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":9999},"
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"indicator\",\"name\":\"RSI\",\"params\":{\"period\":14}},\"right\":60}]}");
            assertEquals(TriState.FALSE, evaluator.eval(ast, one(100, null)).value());
        }

        @Test
        @DisplayName("An AND that would pass but for an unknown arm is UNKNOWN, not a match")
        void unknownArmBlocksAMatch() {
            AstNode ast = parser.parse("{\"type\":\"logical\",\"op\":\"AND\",\"children\":["
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":50},"
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"indicator\",\"name\":\"RSI\",\"params\":{\"period\":14}},\"right\":60}]}");
            EvalResult r = evaluator.eval(ast, one(100, null));
            assertEquals(TriState.UNKNOWN, r.value());
            assertFalse(r.matched());
        }
    }

    // ======================================================================== logic

    @Nested
    @DisplayName("Logical nodes")
    class Logic {

        @Test void andRequiresAll() {
            AstNode ast = parser.parse("{\"type\":\"logical\",\"op\":\"AND\",\"children\":["
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":50},"
                + "{\"type\":\"compare\",\"op\":\"<\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":200}]}");
            assertTrue(evaluator.eval(ast, one(100, null)).matched());
            assertFalse(evaluator.eval(ast, one(300, null)).matched());
        }

        @Test void orNeedsOne() {
            AstNode ast = parser.parse("{\"type\":\"logical\",\"op\":\"OR\",\"children\":["
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":9999},"
                + "{\"type\":\"compare\",\"op\":\"<\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":200}]}");
            assertTrue(evaluator.eval(ast, one(100, null)).matched());
        }

        @Test void notInverts() {
            AstNode ast = parser.parse("{\"type\":\"logical\",\"op\":\"NOT\",\"children\":["
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":50}]}");
            assertFalse(evaluator.eval(ast, one(100, null)).matched());
            assertTrue(evaluator.eval(ast, one(10, null)).matched());
        }

        @Test
        @DisplayName("Nesting three deep evaluates correctly")
        void nested() {
            AstNode ast = parser.parse("{\"type\":\"logical\",\"op\":\"AND\",\"children\":["
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":50},"
                + "{\"type\":\"logical\",\"op\":\"OR\",\"children\":["
                + "  {\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":9999},"
                + "  {\"type\":\"logical\",\"op\":\"AND\",\"children\":["
                + "    {\"type\":\"compare\",\"op\":\"<\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":500},"
                + "    {\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":10}]}]}]}");
            assertTrue(evaluator.eval(ast, one(100, null)).matched());
        }
    }

    // ===================================================================== branches

    @Nested
    @DisplayName("IF / ELSE IF / ELSE")
    class Branches {

        private final String ast =
            "{\"type\":\"branch\",\"cases\":["
          + "{\"when\":{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":200},"
          + " \"then\":{\"action\":\"IDENTIFY\"}},"
          + "{\"when\":{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"field\",\"name\":\"close\"},\"right\":100},"
          + " \"then\":{\"action\":\"SKIP\"}}],"
          + "\"else\":{\"action\":\"IDENTIFY\"}}";

        @Test
        @DisplayName("The first matching case wins, so order is semantic")
        void firstCaseWins() {
            assertTrue(evaluator.eval(parser.parse(ast), one(300, null)).matched());   // case 1
            assertFalse(evaluator.eval(parser.parse(ast), one(150, null)).matched());  // case 2 SKIP
        }

        @Test void elseApplies() {
            assertTrue(evaluator.eval(parser.parse(ast), one(50, null)).matched());
        }

        @Test
        @DisplayName("An unknown case does not fall through to a later arm")
        void unknownDoesNotFallThrough() {
            String a = "{\"type\":\"branch\",\"cases\":["
                + "{\"when\":{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"indicator\",\"name\":\"RSI\",\"params\":{\"period\":14}},\"right\":60},"
                + " \"then\":{\"action\":\"SKIP\"}}],"
                + "\"else\":{\"action\":\"IDENTIFY\"}}";
            // Without the guard this would fall to ELSE and wrongly identify the stock.
            EvalResult r = evaluator.eval(parser.parse(a), one(100, null));
            assertEquals(TriState.UNKNOWN, r.value());
            assertFalse(r.matched());
        }
    }

    // =================================================================== quantifiers

    @Nested
    @DisplayName("Quantifiers over N periods")
    class Quantifiers {

        private MapEvalContext withEps(double... eps) {
            java.util.List<MapEvalContext.Session> ss = new java.util.ArrayList<>();
            for (int i = 0; i < eps.length; i++) {
                ss.add(MapEvalContext.session("2026-01-0" + (i + 1)).put("eps", eps[i]));
            }
            return MapEvalContext.at("TEST", ss);
        }

        private AstNode q(String op, int periods) {
            return parser.parse("{\"type\":\"quantifier\",\"op\":\"" + op + "\",\"periods\":"
                + periods + ",\"over\":{\"type\":\"field\",\"name\":\"eps\"}}");
        }

        @Test void rising() {
            assertTrue(evaluator.eval(q("RISING", 4), withEps(1, 2, 3, 4)).matched());
            assertFalse(evaluator.eval(q("RISING", 4), withEps(1, 2, 2, 4)).matched());
        }

        @Test
        @DisplayName("Only the last N periods count - earlier history is outside the window")
        void falling() {
            assertTrue(evaluator.eval(q("FALLING", 3), withEps(9, 5, 4, 3)).matched());
            // The leading 1 is outside a 3-period window, so this reads 3,4,3 and does not fall.
            assertFalse(evaluator.eval(q("FALLING", 3), withEps(1, 3, 4, 3)).matched());
            // And a rise anywhere inside the window breaks it.
            assertFalse(evaluator.eval(q("FALLING", 4), withEps(9, 5, 6, 3)).matched());
        }

        @Test void highest() {
            assertTrue(evaluator.eval(q("HIGHEST", 4), withEps(1, 9, 3, 10)).matched());
            assertFalse(evaluator.eval(q("HIGHEST", 4), withEps(1, 9, 3, 8)).matched());
        }

        @Test void lowest() {
            assertTrue(evaluator.eval(q("LOWEST", 4), withEps(5, 9, 3, 1)).matched());
        }

        @Test
        @DisplayName("Too little history is UNKNOWN, with the shortfall named")
        void shortHistoryIsUnknown() {
            EvalResult r = evaluator.eval(q("RISING", 8), withEps(1, 2, 3));
            assertEquals(TriState.UNKNOWN, r.value());
            assertTrue(r.unknownReasons().get(0).contains("8"));
        }
    }

    // ==================================================================== arithmetic

    @Test
    @DisplayName("Volume > 2 x average volume - an operand that is itself an expression")
    void arithmeticOperand() {
        AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\","
            + "\"left\":{\"type\":\"field\",\"name\":\"volume\"},"
            + "\"right\":{\"type\":\"arith\",\"op\":\"*\",\"left\":2,"
            + "  \"right\":{\"type\":\"indicator\",\"name\":\"avg_volume\",\"params\":{\"period\":20}}}}");

        MapEvalContext heavy = MapEvalContext.at("TEST", List.of(
            MapEvalContext.session("2026-11-20").put("volume", 500_000.0).put("avg_volume(20)", 200_000.0)));
        MapEvalContext light = MapEvalContext.at("TEST", List.of(
            MapEvalContext.session("2026-11-20").put("volume", 300_000.0).put("avg_volume(20)", 200_000.0)));

        assertTrue(evaluator.eval(ast, heavy).matched());
        assertFalse(evaluator.eval(ast, light).matched());
    }

    // ======================================================================== parser

    @Nested
    @DisplayName("Parser")
    class Parsing {

        @Test
        @DisplayName("The worked example from the specification parses and matches")
        void specExample() {
            AstNode ast = parser.parse("{\"type\":\"branch\",\"cases\":[{\"when\":"
                + "{\"type\":\"logical\",\"op\":\"AND\",\"children\":["
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"field\",\"name\":\"close\"},"
                + " \"right\":{\"type\":\"indicator\",\"name\":\"EMA\",\"params\":{\"period\":20}}},"
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"field\",\"name\":\"volume\"},"
                + " \"right\":{\"type\":\"arith\",\"op\":\"*\",\"left\":2,"
                + "   \"right\":{\"type\":\"indicator\",\"name\":\"avg_volume\",\"params\":{\"period\":20}}}},"
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"indicator\",\"name\":\"RSI\",\"params\":{\"period\":14}},"
                + " \"right\":60}]},"
                + "\"then\":{\"action\":\"IDENTIFY\"}}],\"else\":{\"action\":\"SKIP\"}}");

            MapEvalContext ctx = MapEvalContext.at("RELIANCE", List.of(
                MapEvalContext.session("2026-11-20")
                    .put("close", 1324.40).put("EMA(20)", 1300.0)
                    .put("volume", 500_000.0).put("avg_volume(20)", 200_000.0)
                    .put("RSI(14)", 64.0)));

            EvalResult r = evaluator.eval(ast, ctx);
            assertTrue(r.matched());
            assertEquals(3, r.matchedLeaves().size(), "all three conditions should be recorded");
        }

        @Test
        @DisplayName("A bare number is a literal and a bare string is a field")
        void shorthandOperands() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\",\"left\":\"close\",\"right\":100}");
            assertTrue(evaluator.eval(ast, one(150, null)).matched());
        }

        @Test
        @DisplayName("Errors carry the path of the offending node")
        void errorsCarryPath() {
            AstParser.AstParseException e = assertThrows(AstParser.AstParseException.class, () ->
                parser.parse("{\"type\":\"logical\",\"op\":\"AND\",\"children\":["
                    + "{\"type\":\"compare\",\"op\":\"££\",\"left\":\"close\",\"right\":1}]}"));
            assertEquals(".children[0]", e.path());
        }

        @Test void notTakesExactlyOneChild() {
            assertThrows(AstParser.AstParseException.class, () ->
                parser.parse("{\"type\":\"logical\",\"op\":\"NOT\",\"children\":["
                    + "{\"type\":\"compare\",\"op\":\">\",\"left\":\"close\",\"right\":1},"
                    + "{\"type\":\"compare\",\"op\":\">\",\"left\":\"close\",\"right\":2}]}"));
        }
    }

    // ===================================================================== validator

    @Nested
    @DisplayName("Validator")
    class Validation {

        @Test void unknownFieldIsAnError() {
            AstNode ast = parser.parse(
                "{\"type\":\"compare\",\"op\":\">\",\"left\":\"clsoe\",\"right\":100}");
            AstValidator.Result r = validator.validate(ast);
            assertFalse(r.ok());
            assertTrue(r.errors().get(0).message().contains("clsoe"));
        }

        @Test
        @DisplayName("BETWEEN with one bound is rejected")
        void betweenArity() {
            AstNode ast = parser.parse(
                "{\"type\":\"compare\",\"op\":\"BETWEEN\",\"left\":\"close\",\"right\":[55]}");
            assertFalse(validator.validate(ast).ok());
        }

        @Test
        @DisplayName("Reversed BETWEEN bounds can never match, so they are an error")
        void reversedBounds() {
            AstNode ast = parser.parse(
                "{\"type\":\"compare\",\"op\":\"BETWEEN\",\"left\":\"close\",\"right\":[75,55]}");
            assertFalse(validator.validate(ast).ok());
        }

        @Test void divisionByZero() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\",\"left\":\"close\","
                + "\"right\":{\"type\":\"arith\",\"op\":\"/\",\"left\":1,\"right\":0}}");
            assertFalse(validator.validate(ast).ok());
        }

        @Test
        @DisplayName("A period outside the catalogue's range is rejected")
        void paramRange() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\","
                + "\"left\":{\"type\":\"indicator\",\"name\":\"RSI\",\"params\":{\"period\":-5}},"
                + "\"right\":60}");
            assertFalse(validator.validate(ast).ok());
        }

        @Test
        @DisplayName("A financial metric warns but does not block - the data is simply not loaded")
        void fundamentalWarnsOnly() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\","
                + "\"left\":{\"type\":\"fundamental\",\"metric\":\"net_profit\",\"period\":\"QUARTER\",\"basis\":\"YOY\"},"
                + "\"right\":25}");
            AstValidator.Result r = validator.validate(ast);
            assertTrue(r.ok(), "should be saveable");
            assertTrue(r.issues().stream()
                .anyMatch(i -> i.severity() == AstValidator.Severity.WARNING),
                "but should warn that it will match nothing yet");
        }

        @Test
        @DisplayName("A valid rule produces no errors")
        void validRulePasses() {
            AstNode ast = parser.parse("{\"type\":\"logical\",\"op\":\"AND\",\"children\":["
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":\"close\","
                + " \"right\":{\"type\":\"indicator\",\"name\":\"EMA\",\"params\":{\"period\":20}}},"
                + "{\"type\":\"compare\",\"op\":\"BETWEEN\","
                + " \"left\":{\"type\":\"indicator\",\"name\":\"RSI\",\"params\":{\"period\":14}},"
                + " \"right\":[55,75]}]}");
            assertTrue(validator.validate(ast).ok());
        }
    }

    // ================================================================== offsets

    @Nested
    @DisplayName("Bars-ago offsets")
    class Offsets {

        /** Three sessions, oldest first, so "2 bars ago" has somewhere to point. */
        private MapEvalContext threeCloses(double oldest, double middle, double latest) {
            return MapEvalContext.at("TEST", List.of(
                MapEvalContext.session("2026-11-18").put("close", oldest),
                MapEvalContext.session("2026-11-19").put("close", middle),
                MapEvalContext.session("2026-11-20").put("close", latest)));
        }

        @Test
        @DisplayName("offset reads the bar N back, not the current one")
        void offsetReadsBackwards() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\","
                + "\"left\":\"close\","
                + "\"right\":{\"type\":\"field\",\"name\":\"close\",\"offset\":2}}");
            // 120 today against 100 two bars back. Without the shift this is a number compared
            // with itself, which is the mistake this test exists to catch.
            assertTrue(evaluator.eval(ast, threeCloses(100, 110, 120)).matched());
        }

        @Test
        @DisplayName("offset 0 is the bar being evaluated and adds no wrapper")
        void zeroOffsetIsIdentity() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\"=\","
                + "\"left\":{\"type\":\"field\",\"name\":\"close\",\"offset\":0},"
                + "\"right\":120}");
            assertTrue(evaluator.eval(ast, threeCloses(100, 110, 120)).matched());
        }

        @Test
        @DisplayName("An offset past the start of the window is UNKNOWN, not FALSE")
        void offsetBeyondWindowIsUnknown() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\","
                + "\"left\":\"close\","
                + "\"right\":{\"type\":\"field\",\"name\":\"close\",\"offset\":40}}");
            EvalResult r = evaluator.eval(ast, threeCloses(100, 110, 120));
            assertEquals(TriState.UNKNOWN, r.value(),
                "a stock without 40 bars of history has not failed the test - we cannot run it");
        }

        @Test
        @DisplayName("A negative offset is refused: it would read a bar that has not happened")
        void negativeOffsetRefused() {
            AstParser.AstParseException e = assertThrows(AstParser.AstParseException.class, () ->
                parser.parse("{\"type\":\"compare\",\"op\":\">\",\"left\":\"close\","
                    + "\"right\":{\"type\":\"field\",\"name\":\"close\",\"offset\":-1}}"));
            assertTrue(e.getMessage().toLowerCase().contains("negative"), e.getMessage());
        }

        @Test
        @DisplayName("A fractional offset is refused rather than quietly truncated")
        void fractionalOffsetRefused() {
            assertThrows(AstParser.AstParseException.class, () ->
                parser.parse("{\"type\":\"compare\",\"op\":\">\",\"left\":\"close\","
                    + "\"right\":{\"type\":\"field\",\"name\":\"close\",\"offset\":1.5}}"));
        }

        @Test
        @DisplayName("An offset works on an indicator, not just a raw field")
        void offsetOnIndicator() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\","
                + "\"left\":{\"type\":\"indicator\",\"name\":\"RSI\",\"params\":{\"period\":14}},"
                + "\"right\":{\"type\":\"indicator\",\"name\":\"RSI\",\"params\":{\"period\":14},"
                + "           \"offset\":1}}");
            MapEvalContext ctx = MapEvalContext.at("TEST", List.of(
                MapEvalContext.session("2026-11-19").put("RSI(14)", 48.0),
                MapEvalContext.session("2026-11-20").put("RSI(14)", 55.0)));
            assertTrue(evaluator.eval(ast, ctx).matched(), "RSI turning up over one bar");
        }

        @Test
        @DisplayName("Arithmetic inside a shifted operand is shifted with it")
        void offsetWrapsArithmetic() {
            // (high - low) two bars ago: the day's range, read two sessions back.
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\","
                + "\"left\":{\"type\":\"arith\",\"op\":\"-\",\"left\":\"high\",\"right\":\"low\","
                + "          \"offset\":2},"
                + "\"right\":5}");
            MapEvalContext ctx = MapEvalContext.at("TEST", List.of(
                MapEvalContext.session("2026-11-18").put("high", 120.0).put("low", 100.0),
                MapEvalContext.session("2026-11-19").put("high", 101.0).put("low", 100.0),
                MapEvalContext.session("2026-11-20").put("high", 102.0).put("low", 101.0)));
            assertTrue(evaluator.eval(ast, ctx).matched(),
                "the 20-point range two bars back, not the 1-point range today");
        }

        @Test
        @DisplayName("A bar offset on a financial figure is refused - those move by period")
        void offsetOnFundamentalRefused() {
            assertThrows(AstParser.AstParseException.class, () ->
                parser.parse("{\"type\":\"compare\",\"op\":\">\",\"left\":{\"type\":\"shifted\","
                    + "\"of\":{\"type\":\"fundamental\",\"metric\":\"eps\"},\"offset\":2},"
                    + "\"right\":10}"));
        }

        @Test
        @DisplayName("A fundamental's own offset still counts reporting periods")
        void fundamentalOffsetUnchanged() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\","
                + "\"left\":{\"type\":\"fundamental\",\"metric\":\"eps\",\"period\":\"QUARTER\","
                + "          \"basis\":\"ABS\",\"offset\":1},"
                + "\"right\":10}");
            MapEvalContext ctx = MapEvalContext.at("TEST", List.of(
                MapEvalContext.session("2026-11-20").put("eps:QUARTER:ABS:1", 12.0)));
            assertTrue(evaluator.eval(ast, ctx).matched());
        }

        @Test
        @DisplayName("The reason text says how far back it read")
        void offsetIsDescribed() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\",\"left\":\"close\","
                + "\"right\":{\"type\":\"field\",\"name\":\"close\",\"offset\":1}}");
            EvalResult r = evaluator.eval(ast, threeCloses(100, 110, 120));
            assertTrue(r.label().contains("1 bar ago"), "was: " + r.label());
        }
    }

    // =============================================================== timeframes

    @Nested
    @DisplayName("Timeframes")
    class Timeframes {

        /** RSI as the builder sends it: a named indicator with its period, not a bare field. */
        private static final String RSI =
            "{\"type\":\"indicator\",\"name\":\"RSI\",\"params\":{\"period\":14}}";

        /** Daily and hourly bars for one symbol, each with their own history. */
        private MapEvalContext dailyAndHourly() {
            return MapEvalContext.at("TEST", Map.of(
                Timeframe.DAILY, List.of(
                    MapEvalContext.session("2026-11-19").put("close", 100.0).put("RSI(14)", 28.0),
                    MapEvalContext.session("2026-11-20").put("close", 102.0).put("RSI(14)", 29.0)),
                Timeframe.H1, List.of(
                    MapEvalContext.session("2026-11-20 13:00")
                        .put("close", 101.0).put("RSI(14)", 45.0),
                    MapEvalContext.session("2026-11-20 14:00")
                        .put("close", 102.0).put("RSI(14)", 58.0))),
                Timeframe.DAILY);
        }

        @Test
        @DisplayName("A condition with no timeframe is daily, exactly as before")
        void defaultsToDaily() {
            AstNode ast = parser.parse(
                "{\"type\":\"compare\",\"op\":\"<\",\"left\":\"RSI\",\"right\":30}");
            assertEquals(Timeframe.DAILY, ((AstNode.Compare) ast).timeframe());
        }

        @Test
        @DisplayName("Two conditions on two timeframes read two different sets of bars")
        void conditionsReadTheirOwnTimeframe() {
            // Daily RSI oversold AND hourly RSI already above 50: one rule, two bar widths. On a
            // single timeframe this is a contradiction, which is the point of the test.
            AstNode ast = parser.parse("{\"type\":\"logical\",\"op\":\"AND\",\"children\":["
                + "{\"type\":\"compare\",\"op\":\"<\",\"left\":" + RSI + ",\"right\":30,"
                + " \"timeframe\":\"daily\"},"
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":" + RSI + ",\"right\":50,"
                + " \"timeframe\":\"1h\"}]}");
            assertTrue(evaluator.eval(ast, dailyAndHourly()).matched());
        }

        @Test
        @DisplayName("A timeframe with no bars is UNKNOWN with a reason, never FALSE")
        void missingTimeframeIsUnknown() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\"<\",\"left\":" + RSI
                + ",\"right\":30,\"timeframe\":\"5m\"}");
            EvalResult r = evaluator.eval(ast, dailyAndHourly());
            assertEquals(TriState.UNKNOWN, r.value());
            assertTrue(r.unknownReasons().stream().anyMatch(x -> x.contains("5 Minutes")),
                "the reason should name the width that is missing: " + r.unknownReasons());
        }

        @Test
        @DisplayName("An offset counts bars of the condition's own timeframe")
        void offsetFollowsTheTimeframe() {
            // One hourly bar back is 101, not yesterday's daily close of 100.
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\"=\","
                + "\"left\":{\"type\":\"field\",\"name\":\"close\",\"offset\":1},"
                + "\"right\":101,\"timeframe\":\"1h\"}");
            assertTrue(evaluator.eval(ast, dailyAndHourly()).matched());
        }

        @Test
        @DisplayName("A quantifier counts bars of its own timeframe too")
        void quantifierHonoursTimeframe() {
            AstNode ast = parser.parse("{\"type\":\"quantifier\",\"op\":\"RISING\",\"periods\":2,"
                + "\"over\":" + RSI + ",\"timeframe\":\"1h\"}");
            assertTrue(evaluator.eval(ast, dailyAndHourly()).matched(), "45 then 58 is rising");
        }

        @Test
        @DisplayName("Chart shorthand, labels and enum names all parse to the same width")
        void spellingsAgree() {
            assertEquals(Timeframe.H1, Timeframe.from("1h"));
            assertEquals(Timeframe.H1, Timeframe.from("1 Hour"));
            assertEquals(Timeframe.H1, Timeframe.from("H1"));
            assertEquals(Timeframe.M15, Timeframe.from("15m"));
            assertEquals(Timeframe.DAILY, Timeframe.from("1d"));
            assertEquals(Timeframe.DAILY, Timeframe.from(null), "silence means daily");
            assertThrows(IllegalArgumentException.class, () -> Timeframe.from("fortnightly"));
        }

        @Test
        @DisplayName("An unknown timeframe is a parse error carrying the node path")
        void unknownTimeframeIsAParseError() {
            AstParser.AstParseException e = assertThrows(AstParser.AstParseException.class, () ->
                parser.parse("{\"type\":\"logical\",\"op\":\"AND\",\"children\":["
                    + "{\"type\":\"compare\",\"op\":\">\",\"left\":\"close\",\"right\":1,"
                    + " \"timeframe\":\"2 weeks\"}]}"));
            assertEquals(".children[0]", e.path());
        }

        @Test
        @DisplayName("A width with no candles warns but does not block the save")
        void unavailableTimeframeWarnsOnly() {
            AstValidator strict = new AstValidator(new FieldCatalogue(), hourlyAndDailyOnly);
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\"<\",\"left\":" + RSI
                + ",\"right\":30,\"timeframe\":\"5m\"}");
            AstValidator.Result r = strict.validate(ast);
            assertTrue(r.ok(), "the rule is correct and works once 5-minute bars are collected");
            assertTrue(r.issues().stream()
                .anyMatch(i -> i.severity() == AstValidator.Severity.WARNING),
                "but the author must be told it matches nothing today");
        }

        @Test
        @DisplayName("Hourly and daily pass the same validator without a warning")
        void availableTimeframesAreSilent() {
            AstValidator strict = new AstValidator(new FieldCatalogue(), hourlyAndDailyOnly);
            AstNode ast = parser.parse("{\"type\":\"logical\",\"op\":\"AND\",\"children\":["
                + "{\"type\":\"compare\",\"op\":\"<\",\"left\":" + RSI + ",\"right\":30,"
                + " \"timeframe\":\"daily\"},"
                + "{\"type\":\"compare\",\"op\":\">\",\"left\":" + RSI + ",\"right\":50,"
                + " \"timeframe\":\"1h\"}]}");
            assertTrue(strict.validate(ast).issues().isEmpty(),
                "a rule on widths we hold should produce nothing at all");
        }

        @Test
        @DisplayName("A shifted operand keeps its unit, so no spurious mismatch warning")
        void shiftedOperandKeepsItsUnit() {
            AstNode ast = parser.parse("{\"type\":\"compare\",\"op\":\">\",\"left\":\"close\","
                + "\"right\":{\"type\":\"field\",\"name\":\"close\",\"offset\":20}}");
            assertTrue(validator.validate(ast).issues().isEmpty(),
                "price against price is not a unit mismatch");
        }
    }
}
