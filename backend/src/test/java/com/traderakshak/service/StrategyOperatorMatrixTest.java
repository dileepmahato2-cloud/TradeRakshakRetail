package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Strategy Builder operator, against known candles.
 *
 * <p>The builder offers Greater Than, Greater or Equal, Less Than, Less or Equal, Equal To, Not
 * Equal To, Between, Crosses Above and Crosses Below. Each has one correct meaning and several
 * plausible wrong ones, and the wrong ones are invisible: a condition that silently evaluates as
 * {@code >} still returns a list of stocks, so the screen looks like it worked.
 *
 * <p>Two silent fallbacks make that failure mode easy to reach. The frontend translator ends in
 * {@code default: return '>'} and the backend comparator ends in {@code default -> left > right},
 * so any operator either layer does not recognise becomes greater-than rather than an error.
 *
 * <p>The matrix below is deliberately boundary-heavy: for each comparison it tests a value below,
 * exactly equal to, and above the threshold, because {@code >} versus {@code >=} differs on
 * exactly one of those three and that is the case users notice.
 */
class StrategyOperatorMatrixTest {

    private final StrategyConditionEvaluator evaluator =
        new StrategyConditionEvaluator(new TechnicalIndicatorEngine());

    // ---------------------------------------------------------------- helpers

    /** A data map whose CLOSE series ends with the given values, oldest-first. */
    private static Map<String, Object> candles(double... closes) {
        List<Double> cs = new ArrayList<>();
        for (double c : closes) cs.add(c);
        List<Double> highs = new ArrayList<>(cs);
        List<Double> lows = new ArrayList<>(cs);
        List<Double> opens = new ArrayList<>(cs);
        List<Double> vols = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) vols.add(100000.0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("closes", cs);
        data.put("highs", highs);
        data.put("lows", lows);
        data.put("opens", opens);
        data.put("volumes", vols);
        return data;
    }

    /** One condition row in the shape the builder saves. */
    private static Map<String, Object> cond(String indicator, String comparator, double rightValue) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("group", "ENTRY");
        c.put("indicatorLeft", indicator);
        c.put("paramsLeft", Map.of());
        c.put("comparator", comparator);
        c.put("indicatorRight", null);
        c.put("paramsRight", Map.of());
        c.put("valueRight", rightValue);
        c.put("multiplier", 1.0);
        c.put("operator", "AND");
        return c;
    }

    private boolean eval(Map<String, Object> data, String comparator, double right) {
        return evaluator.evaluate(List.of(cond("CLOSE", comparator, right)), data, "ENTRY");
    }

    // ---------------------------------------------------------------- comparisons

    @Test
    void greaterThanIsStrict() {
        assertFalse(eval(candles(10, 44.0), ">", 45), "44 > 45 is false");
        assertFalse(eval(candles(10, 45.0), ">", 45), "45 > 45 is false - strictly greater");
        assertTrue(eval(candles(10, 46.0), ">", 45), "46 > 45 is true");
    }

    @Test
    void greaterOrEqualIncludesTheBoundary() {
        assertFalse(eval(candles(10, 44.0), ">=", 45));
        assertTrue(eval(candles(10, 45.0), ">=", 45), "the boundary is the whole difference from >");
        assertTrue(eval(candles(10, 46.0), ">=", 45));
    }

    @Test
    void lessThanIsStrict() {
        assertTrue(eval(candles(10, 44.0), "<", 45));
        assertFalse(eval(candles(10, 45.0), "<", 45), "45 < 45 is false");
        assertFalse(eval(candles(10, 46.0), "<", 45));
    }

    @Test
    void lessOrEqualIncludesTheBoundary() {
        assertTrue(eval(candles(10, 44.0), "<=", 45));
        assertTrue(eval(candles(10, 45.0), "<=", 45));
        assertFalse(eval(candles(10, 46.0), "<=", 45));
    }

    @Test
    void equalAndNotEqual() {
        assertTrue(eval(candles(10, 45.0), "=", 45));
        assertFalse(eval(candles(10, 45.5), "=", 45));
        assertFalse(eval(candles(10, 45.0), "!=", 45));
        assertTrue(eval(candles(10, 45.5), "!=", 45));
    }

    @Test
    void decimalsAreComparedNumericallyNotAsText() {
        // "9.5" sorts after "45" as text and before it as a number. A string comparison would
        // get both of these wrong.
        assertTrue(eval(candles(10, 9.5), "<", 45));
        assertFalse(eval(candles(10, 9.5), ">", 45));
        assertTrue(eval(candles(10, 100.25), ">", 99.75));
    }

    // ---------------------------------------------------------------- crossings

    private boolean cross(String comparator, double prevClose, double nowClose, double level) {
        // 20 bars of history so indicators have something to work with, then the two that matter.
        List<Double> cs = new ArrayList<>();
        for (int i = 0; i < 20; i++) cs.add(level);
        cs.add(prevClose);
        cs.add(nowClose);
        double[] arr = new double[cs.size()];
        for (int i = 0; i < cs.size(); i++) arr[i] = cs.get(i);
        return evaluator.evaluate(List.of(cond("CLOSE", comparator, level)), candles(arr), "ENTRY");
    }

    @Test
    void crossesAboveNeedsBothCandles() {
        assertTrue(cross("CROSSABOVE", 44.0, 46.0, 45.0), "below then above is a crossing");
        assertTrue(cross("CROSSABOVE", 45.0, 46.0, 45.0), "equal then above is a crossing");
        assertFalse(cross("CROSSABOVE", 46.0, 47.0, 45.0), "above then above is not a crossing");
        assertFalse(cross("CROSSABOVE", 44.0, 44.5, 45.0), "below then below is not a crossing");
        assertFalse(cross("CROSSABOVE", 46.0, 44.0, 45.0), "that is a cross DOWN");
    }

    @Test
    void crossesBelowNeedsBothCandles() {
        assertTrue(cross("CROSSBELOW", 46.0, 44.0, 45.0), "above then below is a crossing");
        assertTrue(cross("CROSSBELOW", 45.0, 44.0, 45.0), "equal then below is a crossing");
        assertFalse(cross("CROSSBELOW", 44.0, 43.0, 45.0), "below then below is not a crossing");
        assertFalse(cross("CROSSBELOW", 46.0, 47.0, 45.0), "above then above is not a crossing");
        assertFalse(cross("CROSSBELOW", 44.0, 46.0, 45.0), "that is a cross UP");
    }

    @Test
    void aCrossingIsNotAPlainComparison() {
        // The defect this guards: implementing "crosses above 45" as "is above 45" admits a stock
        // that has sat above 45 for twenty sessions and never crossed anything.
        assertFalse(cross("CROSSABOVE", 46.0, 47.0, 45.0));
        assertTrue(cross(">", 46.0, 47.0, 45.0), "...while the plain comparison does admit it");
    }

    // ---------------------------------------------------------------- AND / OR

    @Test
    void multipleConditionsCombineAsConfigured() {
        Map<String, Object> data = candles(10, 50.0);

        Map<String, Object> passes = cond("CLOSE", ">", 45);
        Map<String, Object> fails = cond("CLOSE", ">", 99);

        List<Map<String, Object>> and = new ArrayList<>(List.of(passes, fails));
        and.get(1).put("operator", "AND");
        assertFalse(evaluator.evaluate(and, data, "ENTRY"), "true AND false is false");

        Map<String, Object> passes2 = cond("CLOSE", ">", 45);
        Map<String, Object> fails2 = cond("CLOSE", ">", 99);
        fails2.put("operator", "OR");
        assertTrue(evaluator.evaluate(new ArrayList<>(List.of(passes2, fails2)), data, "ENTRY"),
            "true OR false is true");
    }

    // ---------------------------------------------------------------- between

    private boolean between(double value, double lower, double upper) {
        Map<String, Object> c = cond("CLOSE", "BETWEEN", lower);
        c.put("valueRightUpper", upper);
        return evaluator.evaluate(List.of(c), candles(10, value), "ENTRY");
    }

    @Test
    void betweenIsInclusiveOnBothBounds() {
        assertFalse(between(39.9, 40, 50));
        assertTrue(between(40.0, 40, 50), "the lower bound is included");
        assertTrue(between(45.0, 40, 50));
        assertTrue(between(50.0, 40, 50), "the upper bound is included");
        assertFalse(between(50.1, 40, 50));
    }

    @Test
    void betweenWithoutAnUpperBoundIsSkippedNotGuessed() {
        // The builder offers BETWEEN but saves a single value. Without the second bound there is
        // no range to test, and inventing one would produce a list for a rule nobody wrote.
        Map<String, Object> data = candles(10, 45.0);
        assertFalse(evaluator.evaluate(List.of(cond("CLOSE", "BETWEEN", 40)), data, "ENTRY"));
    }

    // ---------------------------------------------------------------- unknown operators

    @Test
    void anUnrecognisedOperatorMustNotSilentlyBecomeGreaterThan() {
        // "BETWEEN" is offered by the builder's operator list and has no translation, so it
        // reaches the backend unmapped. A comparator the engine cannot honour must not quietly
        // evaluate as something else - that produces a list of stocks for a rule nobody wrote.
        Map<String, Object> data = candles(10, 50.0);
        assertFalse(evaluator.evaluate(List.of(cond("CLOSE", "NOT_AN_OPERATOR", 45)), data, "ENTRY"),
            "an operator the engine does not implement must not pass as >");
        assertTrue(evaluator.lastSkipped().stream().anyMatch(n -> n.contains("does not support")),
            "and the reason must be reported, not swallowed: " + evaluator.lastSkipped());
    }
}
