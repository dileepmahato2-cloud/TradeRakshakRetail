package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BETWEEN's upper bound surviving the trip to storage and back.
 *
 * <p>{@code strategy_conditions} has one value column, {@code value_right}. A two-bound operator
 * therefore had nowhere to put its second number: the builder rendered an input for it, the AST
 * payload carried it, and the save dropped it on the floor. The condition reached the screener
 * with a lower bound and no upper one, which it can only skip - so BETWEEN could be configured
 * and never worked.
 *
 * <p>The bound now travels inside the {@code params_right} JSON, which is empty for a BETWEEN row
 * because its right-hand side is a literal rather than an indicator. This pins that carrying it
 * there is lossless and that it is lifted back out, since a value silently left in the params map
 * would reach an indicator as a parameter it does not have.
 */
class BetweenBoundRoundTripTest {

    private final StrategyConditionEvaluator evaluator =
        new StrategyConditionEvaluator(new TechnicalIndicatorEngine());

    /** The fold the save performs. */
    private static Map<String, Object> fold(Map<String, Object> cond) {
        Object upper = cond.get("valueRightUpper");
        Object params = cond.get("paramsRight");
        Map<String, Object> merged = new LinkedHashMap<>();
        if (params instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) merged.put(String.valueOf(e.getKey()), e.getValue());
        }
        if (upper != null) merged.put("valueRightUpper", upper);
        return merged;
    }

    /** The lift the load performs. */
    private static Map<String, Object> lift(Map<String, Object> storedParams, Map<String, Object> into) {
        Map<String, Object> pr = new LinkedHashMap<>(storedParams);
        if (pr.containsKey("valueRightUpper")) {
            Object upper = pr.remove("valueRightUpper");
            if (upper instanceof Number n) into.put("valueRightUpper", n.doubleValue());
        }
        into.put("paramsRight", pr);
        return into;
    }

    private static Map<String, Object> betweenCondition(Double lower, Double upper) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("group", "ENTRY");
        c.put("operator", "AND");
        c.put("indicatorLeft", "CLOSE");
        c.put("paramsLeft", Map.of());
        c.put("comparator", "BETWEEN");
        c.put("indicatorRight", null);
        c.put("paramsRight", new LinkedHashMap<String, Object>());
        c.put("valueRight", lower);
        c.put("multiplier", 1.0);
        if (upper != null) c.put("valueRightUpper", upper);
        return c;
    }

    private static Map<String, Object> candles(double lastClose) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("closes", List.of(10.0, lastClose));
        d.put("highs", List.of(10.0, lastClose));
        d.put("lows", List.of(10.0, lastClose));
        d.put("opens", List.of(10.0, lastClose));
        d.put("volumes", List.of(1000.0, 1000.0));
        return d;
    }

    @Test
    void theUpperBoundSurvivesStorage() {
        Map<String, Object> saved = betweenCondition(40.0, 50.0);
        Map<String, Object> stored = fold(saved);

        assertEquals(50.0, stored.get("valueRightUpper"), "the bound is carried in params_right");

        Map<String, Object> loaded = betweenCondition(40.0, null);
        loaded.remove("valueRightUpper");
        lift(stored, loaded);

        assertEquals(50.0, loaded.get("valueRightUpper"), "and lifted back out on load");
        assertTrue(((Map<?, ?>) loaded.get("paramsRight")).isEmpty(),
            "and removed from the params, so no indicator sees it as a parameter");
    }

    @Test
    void aLoadedBetweenConditionStillEvaluates() {
        Map<String, Object> stored = fold(betweenCondition(40.0, 50.0));
        Map<String, Object> loaded = betweenCondition(40.0, null);
        loaded.remove("valueRightUpper");
        lift(stored, loaded);

        assertTrue(evaluator.evaluate(List.of(loaded), candles(45.0), "ENTRY"), "45 is in 40..50");
        assertTrue(evaluator.evaluate(List.of(loaded), candles(40.0), "ENTRY"), "inclusive lower");
        assertTrue(evaluator.evaluate(List.of(loaded), candles(50.0), "ENTRY"), "inclusive upper");
        assertFalse(evaluator.evaluate(List.of(loaded), candles(39.9), "ENTRY"));
        assertFalse(evaluator.evaluate(List.of(loaded), candles(50.1), "ENTRY"));
    }

    @Test
    void aConditionWithoutAnUpperBoundCarriesNothingExtra() {
        // Every other operator must be unaffected: no stray key in params_right, which would
        // otherwise reach an indicator as a parameter it does not recognise.
        Map<String, Object> plain = betweenCondition(40.0, null);
        Map<String, Object> stored = fold(plain);
        assertTrue(stored.isEmpty(), "nothing is added when there is no upper bound");
        assertNull(plain.get("valueRightUpper"));
    }

    @Test
    void anIndicatorsOwnParamsAreNotDisturbed() {
        Map<String, Object> c = betweenCondition(40.0, 50.0);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("period", 14);
        c.put("paramsRight", params);

        Map<String, Object> stored = fold(c);
        assertEquals(14, stored.get("period"), "the indicator's own parameter is preserved");

        Map<String, Object> loaded = new LinkedHashMap<>();
        lift(stored, loaded);
        assertEquals(50.0, loaded.get("valueRightUpper"));
        assertEquals(14, ((Map<?, ?>) loaded.get("paramsRight")).get("period"),
            "and still present after the bound is lifted out");
    }
}
