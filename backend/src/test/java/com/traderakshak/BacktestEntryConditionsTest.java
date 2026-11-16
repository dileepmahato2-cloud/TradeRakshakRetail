package com.traderakshak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.service.AppConfigService;
import com.traderakshak.service.BacktestEngine;
import com.traderakshak.service.StrategyConditionEvaluator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The evaluator picks conditions out by group name, so a strategy that does not happen to call
 * its group "entry" would silently backtest as having no rules at all. Strategy 2 in this
 * database files its rules under "G1", which is exactly that case.
 */
class BacktestEntryConditionsTest {

    private BacktestEngine engine() {
        return new BacktestEngine(mock(AppConfigService.class),
                                  mock(StrategyConditionEvaluator.class),
                                  new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalise(List<Map<String, Object>> in) throws Exception {
        Method m = BacktestEngine.class.getDeclaredMethod("normaliseEntryGroup", List.class);
        m.setAccessible(true);
        return (List<Map<String, Object>>) m.invoke(engine(), in);
    }

    private Map<String, Object> cond(String group, String indicator) {
        Map<String, Object> c = new HashMap<>();
        c.put("group", group);
        c.put("indicatorLeft", indicator);
        return c;
    }

    private List<Map<String, Object>> list(Map<String, Object>... c) {
        return new ArrayList<>(List.of(c));
    }

    @Test
    void conditionsAlreadyLabelledEntryAreLeftAlone() throws Exception {
        List<Map<String, Object>> out = normalise(list(cond("entry", "RSI"), cond("exit", "RSI")));

        assertEquals("entry", out.get(0).get("group"));
        assertEquals("exit",  out.get(1).get("group"), "an exit group must not be relabelled");
    }

    @Test
    void aStrategyThatCallsItsGroupG1StillHasEntryRules() throws Exception {
        List<Map<String, Object>> out = normalise(list(cond("G1", "RSI"), cond("G1", "EMA")));

        assertTrue(out.stream().allMatch(c -> "entry".equals(c.get("group"))),
            "G1 is this strategy's entry group, so it must be testable");
    }

    @Test
    void onlyTheFirstGroupIsAdoptedSoExitRulesStaySeparate() throws Exception {
        List<Map<String, Object>> out = normalise(list(
            cond("G1", "RSI"), cond("G1", "EMA"), cond("G2", "ATR")));

        assertEquals("entry", out.get(0).get("group"));
        assertEquals("entry", out.get(1).get("group"));
        assertEquals("G2",    out.get(2).get("group"),
            "a second group is a different phase and must not be swallowed into entry");
    }

    @Test
    void ungroupedConditionsAreAllTreatedAsEntry() throws Exception {
        Map<String, Object> bare = new HashMap<>();
        bare.put("indicatorLeft", "RSI");
        List<Map<String, Object>> out = normalise(list(bare));

        assertEquals("entry", out.get(0).get("group"));
    }

    @Test
    void noConditionsMeansNothingToTest() throws Exception {
        assertTrue(normalise(new ArrayList<>()).isEmpty());
    }
}
