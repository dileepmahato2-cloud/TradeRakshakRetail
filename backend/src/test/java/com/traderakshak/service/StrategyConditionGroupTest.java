package com.traderakshak.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Numbered condition groups, as the Strategy Builder canvas draws them.
 *
 * <p>The thing being pinned is that grouping is <em>semantic</em>. A flat left-to-right fold
 * reads {@code a AND b OR c} as {@code (a AND b) OR c} because of the order the rows happen to
 * be in, not because anyone said so - so the same three conditions, reordered, quietly become a
 * different strategy. Once the canvas lets someone draw "all of these" and "any one of those"
 * and join them, that structure has to reach the screener or the canvas is decoration.
 *
 * <p>The last test is the one that matters most: a strategy saved before groups existed carries
 * no group fields at all, and must fold exactly the way it always did.
 */
class StrategyConditionGroupTest {

    private StrategyConditionEvaluator evaluator;
    private Map<String, Object> data;

    @BeforeEach
    void setUp() {
        // The EntityManager is left null: nothing below touches it, because every condition
        // reads a raw OHLCV column straight out of the supplied data map.
        evaluator = new StrategyConditionEvaluator(new TechnicalIndicatorEngine());

        data = new LinkedHashMap<>();
        data.put("closes",  series(100, 101, 102, 103, 110));
        data.put("opens",   series(100, 100, 100, 100, 100));
        data.put("highs",   series(101, 102, 103, 104, 112));
        data.put("lows",    series( 99, 100, 101, 102, 108));
        data.put("volumes", series(1000, 1000, 1000, 1000, 5000));
    }

    private static List<Double> series(double... v) {
        List<Double> out = new ArrayList<>(v.length);
        for (double d : v) out.add(d);
        return out;
    }

    /**
     * One condition on the close.
     *
     * @param group null for "no group named", which is what a pre-group strategy stores
     */
    private static Map<String, Object> close(String comparator, double value,
                                             String group, String mode, String join) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("group", "entry");
        c.put("indicatorLeft", "close");
        c.put("comparator", comparator);
        c.put("valueRight", value);
        c.put("multiplier", 1.0);
        if (group != null) c.put("groupIndex", group);
        if (mode  != null) c.put("groupMode", mode);
        if (join  != null) c.put("groupJoin", join);
        return c;
    }

    private boolean run(Map<String, Object>... conds) {
        return evaluator.evaluate(new ArrayList<>(Arrays.asList(conds)), data, "entry");
    }

    // close is 110 throughout, so ">100" is true and ">500" is false.

    @Test @SuppressWarnings("unchecked")
    void everyConditionInAnAllGroupMustHold() {
        assertTrue(run(close(">", 100, "1", "ALL", "AND"),
                       close(">",  50, "1", "ALL", "AND")));
        assertFalse(run(close(">", 100, "1", "ALL", "AND"),
                        close(">", 500, "1", "ALL", "AND")),
            "one failing condition did not fail an ALL group");
    }

    @Test @SuppressWarnings("unchecked")
    void oneConditionIsEnoughInAnAnyGroup() {
        assertTrue(run(close(">", 500, "1", "ANY", "AND"),
                       close(">", 100, "1", "ANY", "AND")),
            "a passing condition did not carry an ANY group");
        assertFalse(run(close(">", 500, "1", "ANY", "AND"),
                        close(">", 900, "1", "ANY", "AND")));
    }

    @Test @SuppressWarnings("unchecked")
    void groupsAreJoinedByTheirConnectorNotByRowOrder() {
        // Group 1 holds, group 2 does not.
        Map<String, Object> g1 = close(">", 100, "1", "ALL", "AND");
        Map<String, Object> g2 = close(">", 500, "2", "ALL", "AND");
        assertFalse(run(g1, g2), "AND between groups accepted a failing group");

        // The same two groups joined by OR must now pass.
        Map<String, Object> g1or = close(">", 100, "1", "ALL", "OR");
        assertTrue(run(g1or, g2), "OR between groups rejected a passing group");
    }

    @Test @SuppressWarnings("unchecked")
    void aFailingAllGroupCannotBeRescuedByAPassingConditionInAnotherGroup() {
        // This is the case a flat fold gets wrong. Written flat as
        //   close>100 AND close>500 OR close>50
        // a left-to-right fold yields (true AND false) OR true = TRUE.
        // As two groups ANDed together it is (close>100 AND close>500) AND (close>50) = FALSE.
        assertFalse(run(close(">", 100, "1", "ALL", "AND"),
                        close(">", 500, "1", "ALL", "AND"),
                        close(">",  50, "2", "ALL", "AND")));
    }

    @Test @SuppressWarnings("unchecked")
    void reorderingConditionsInsideAGroupDoesNotChangeTheVerdict() {
        // The property a flat fold does not have, and the whole reason groups exist.
        boolean a = run(close(">", 100, "1", "ALL", "AND"),
                        close(">", 500, "1", "ALL", "AND"),
                        close(">",  50, "2", "ANY", "AND"));
        boolean b = run(close(">", 500, "1", "ALL", "AND"),
                        close(">", 100, "1", "ALL", "AND"),
                        close(">",  50, "2", "ANY", "AND"));
        assertFalse(a);
        assertFalse(b);
    }

    @Test @SuppressWarnings("unchecked")
    void aStrategySavedBeforeGroupsExistedFoldsExactlyAsItAlwaysDid() {
        // No groupIndex, no groupMode, no groupJoin - every row the old builder wrote. The old
        // behaviour is a left-to-right fold over the per-row operator, so
        //   close>100  OR  close>500   is TRUE
        // and it must stay TRUE.
        Map<String, Object> first  = close(">", 100, null, null, null);
        Map<String, Object> second = close(">", 500, null, null, null);
        second.put("operator", "OR");
        assertTrue(run(first, second));

        Map<String, Object> third = close(">", 500, null, null, null);
        third.put("operator", "AND");
        assertFalse(run(first, third));
    }

    @Test @SuppressWarnings("unchecked")
    void aGroupWithNoDeclaredModeStillFoldsItsRowOperators() {
        // Half-migrated rows: grouped, but with no mode recorded. Falling back to the row
        // operators is the only reading that cannot change an existing strategy's meaning.
        Map<String, Object> a = close(">", 100, "1", null, "AND");
        Map<String, Object> b = close(">", 500, "1", null, "AND");
        b.put("operator", "OR");
        assertTrue(run(a, b));
    }
}
