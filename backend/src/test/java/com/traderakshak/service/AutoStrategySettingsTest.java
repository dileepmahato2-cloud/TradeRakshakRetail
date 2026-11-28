package com.traderakshak.service;

import com.traderakshak.repository.StrategyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Per-strategy automation settings.
 *
 * <p>Automation now runs per strategy, which makes one property load-bearing in a way it was not
 * when there was only ever one: <b>arming must never be inherited.</b> Every other setting falls
 * back to a shared default so a new strategy starts with sensible numbers, and that is right -
 * but if "is Auto Buy on" fell back the same way, arming one strategy would arm every strategy
 * that had not been configured yet, the moment it was first opened. Nobody would have asked for
 * that and nothing on screen would have said it happened.
 */
class AutoStrategySettingsTest {

    private Map<String, String> store;
    private AutoStrategySourceService svc;

    @BeforeEach
    void setUp() {
        store = new HashMap<>();
        AppConfigService cfg = mock(AppConfigService.class);

        when(cfg.getString(anyString(), anyString())).thenAnswer(i ->
            store.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(cfg.getDouble(anyString(), anyDouble())).thenAnswer(i -> {
            String v = store.get(i.<String>getArgument(0));
            try { return v == null ? i.getArgument(1) : Double.parseDouble(v); }
            catch (NumberFormatException e) { return i.getArgument(1); }
        });
        when(cfg.getInt(anyString(), anyInt())).thenAnswer(i -> {
            String v = store.get(i.<String>getArgument(0));
            try { return v == null ? i.getArgument(1) : (int) Double.parseDouble(v); }
            catch (NumberFormatException e) { return i.getArgument(1); }
        });
        when(cfg.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
            String v = store.get(i.<String>getArgument(0));
            return v == null ? i.getArgument(1) : "true".equalsIgnoreCase(v);
        });
        // Explicit type witnesses: without them String.valueOf(Object) loses to
        // String.valueOf(char[]) in overload resolution and every write throws.
        doAnswer(i -> {
            store.put(i.<String>getArgument(0), i.<String>getArgument(1));
            return null;
        }).when(cfg).setValue(anyString(), anyString(), anyString(), anyString(), any());

        svc = new AutoStrategySourceService(
            mock(StockTrackerService.class), mock(StrategyBuilderService.class),
            mock(StrategyRepository.class), cfg, mock(VolumeBreakoutAutoTradeService.class));
    }

    private static Map<String, Object> req(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<String> rejected(Map<String, Object> out) {
        return (List<String>) out.get("rejected");
    }

    @Test
    void armingIsNeverInheritedFromTheSharedDefaults() {
        // Someone armed the original volume-breakout strategy. Those shared keys are the
        // fallback for every numeric setting.
        store.put("volbreakout.auto.buy", "true");
        store.put("volbreakout.auto.sell", "true");

        Map<String, Object> fresh = svc.settings(AutoStrategySourceService.RSI45_CURRENT, "PAPER");
        assertFalse((Boolean) fresh.get("autoBuy"),
            "a strategy nobody has configured came up with Auto Buy already on, inherited from "
          + "another strategy's setting");
        assertFalse((Boolean) fresh.get("autoSell"), "same for Auto Sell");
        assertFalse((Boolean) fresh.get("armed"), "same for armed");
    }

    @Test
    void theNumbersDoInheritSoANewStrategyStartsSensible() {
        store.put("volbreakout.stop.loss.pct", "4");
        store.put("volbreakout.investment.amount", "250000");
        store.put("volbreakout.max.open.positions", "7");

        Map<String, Object> s = svc.settings(AutoStrategySourceService.RSI45_VOLUME, "PAPER");
        assertEquals(4.0, (Double) s.get("stopLossPct"), 1e-9);
        assertEquals(250000.0, (Double) s.get("investmentAmount"), 1e-9);
        assertEquals(7, (Integer) s.get("maxOpenPositions"));
    }

    @Test
    void armingOneStrategyDoesNotArmAnother() {
        svc.saveSettings(1L, AutoStrategySourceService.RSI45_CURRENT, "PAPER",
            req("armed", true, "autoBuy", true, "autoSell", true));

        Map<String, Object> other = svc.settings(AutoStrategySourceService.RSI45_VOLUME, "PAPER");
        assertFalse((Boolean) other.get("armed"));
        assertFalse((Boolean) other.get("autoBuy"));

        Map<String, Object> same = svc.settings(AutoStrategySourceService.RSI45_CURRENT, "PAPER");
        assertTrue((Boolean) same.get("armed"));
        assertTrue((Boolean) same.get("autoBuy"));
    }

    @Test
    void paperAndRealAreSeparatePerStrategy() {
        svc.saveSettings(1L, AutoStrategySourceService.RSI45_CURRENT, "PAPER",
            req("stopLossPct", 9, "armed", true));
        svc.saveSettings(1L, AutoStrategySourceService.RSI45_CURRENT, "REAL",
            req("stopLossPct", 1.5));

        Map<String, Object> paper = svc.settings(AutoStrategySourceService.RSI45_CURRENT, "PAPER");
        Map<String, Object> real  = svc.settings(AutoStrategySourceService.RSI45_CURRENT, "REAL");
        assertEquals(9.0, (Double) paper.get("stopLossPct"), 1e-9);
        assertEquals(1.5, (Double) real.get("stopLossPct"), 1e-9);
        assertTrue((Boolean) paper.get("armed"));
        assertFalse((Boolean) real.get("armed"),
            "arming Paper also armed Real, which is the one mistake that spends real money");
    }

    @Test
    void aBuilderStrategyGetsItsOwnKeyspace() {
        svc.saveSettings(1L, "BUILDER:42", "PAPER", req("stopLossPct", 3, "armed", true));
        svc.saveSettings(1L, "BUILDER:43", "PAPER", req("stopLossPct", 8));

        assertEquals(3.0, (Double) svc.settings("BUILDER:42", "PAPER").get("stopLossPct"), 1e-9);
        assertEquals(8.0, (Double) svc.settings("BUILDER:43", "PAPER").get("stopLossPct"), 1e-9);
        assertFalse((Boolean) svc.settings("BUILDER:43", "PAPER").get("armed"));

        // The colon is not a legal config-key character, so the key is slugged. Pinned because
        // a change to that slug silently orphans every setting already saved under the old one.
        assertTrue(store.keySet().stream().anyMatch(k -> k.startsWith("autotrade.builder_42.paper.")),
            "expected a slugged keyspace, got: " + store.keySet());
    }

    @Test
    void aRejectedValueIsNotWritten() {
        Map<String, Object> out = svc.saveSettings(1L, AutoStrategySourceService.RSI45_CURRENT,
            "PAPER", req("stopLossPct", -3, "targetPct", 6, "tradingStartTime", "25:00"));

        assertFalse((Boolean) out.get("success"));
        assertEquals(2, rejected(out).size(), rejected(out).toString());
        assertNull(store.get("autotrade.rsi45_current_day.paper.stop.loss.pct"));
        assertNull(store.get("autotrade.rsi45_current_day.paper.trading.start.time"));
        // The legal field in the same request still landed.
        assertEquals("6", store.get("autotrade.rsi45_current_day.paper.target.pct"));
    }

    @Test
    void zeroIsStorableBecauseItMeansNoLimit() {
        Map<String, Object> out = svc.saveSettings(1L, AutoStrategySourceService.RSI45_CURRENT,
            "PAPER", req("maxDailyLoss", 0, "stopLossPct", 0, "maxCapitalPerStock", 0));
        assertTrue((Boolean) out.get("success"));
        assertEquals(0.0, (Double) svc.settings(AutoStrategySourceService.RSI45_CURRENT, "PAPER")
            .get("maxDailyLoss"), 1e-9);
    }

    @Test
    void aBackwardsWindowIsReported() {
        Map<String, Object> out = svc.saveSettings(1L, AutoStrategySourceService.RSI45_CURRENT,
            "PAPER", req("tradingStartTime", "15:30", "tradingEndTime", "09:15"));
        assertFalse((Boolean) out.get("success"));
        assertTrue(rejected(out).get(0).toLowerCase().contains("start"), rejected(out).toString());
    }
}
