package com.traderakshak.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

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
 * Saving one mode's automation settings.
 *
 * <p>Three things are pinned here, all of which are ways this could quietly go wrong rather
 * than loudly fail:
 *
 * <ol>
 *   <li><b>A rejected value is not written.</b> The alternative - clamping it to the nearest
 *       legal number and reporting success - leaves the user with a stop loss they did not set
 *       and no indication that it changed.</li>
 *   <li><b>Saving one mode does not touch the other.</b> The whole point of per-mode settings
 *       is that Paper can be tuned without moving what Real will do with real money.</li>
 *   <li><b>An absent field is left alone, and 0 is a real value.</b> A partial save must not
 *       blank the fields it did not mention, and "no limit" has to be storable.</li>
 * </ol>
 */
class VolumeBreakoutSettingsSaveTest {

    private Map<String, String> settings;
    private VolumeBreakoutAutoTradeService svc;

    @BeforeEach
    void setUp() {
        settings = new HashMap<>();
        AppConfigService cfg = mock(AppConfigService.class);

        when(cfg.getString(anyString(), anyString())).thenAnswer(i ->
            settings.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(cfg.getDouble(anyString(), anyDouble())).thenAnswer(i -> {
            String v = settings.get(i.<String>getArgument(0));
            try { return v == null ? i.getArgument(1) : Double.parseDouble(v); }
            catch (NumberFormatException e) { return i.getArgument(1); }
        });
        when(cfg.getInt(anyString(), anyInt())).thenAnswer(i -> {
            String v = settings.get(i.<String>getArgument(0));
            try { return v == null ? i.getArgument(1) : (int) Double.parseDouble(v); }
            catch (NumberFormatException e) { return i.getArgument(1); }
        });
        when(cfg.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
            String v = settings.get(i.<String>getArgument(0));
            return v == null ? i.getArgument(1) : "true".equalsIgnoreCase(v);
        });
        // Explicit type witnesses: without them String.valueOf(Object) loses to
        // String.valueOf(char[]) during overload resolution and every write throws.
        doAnswer(i -> {
            settings.put(i.<String>getArgument(0), i.<String>getArgument(1));
            return null;
        }).when(cfg).setValue(anyString(), anyString(), anyString(), anyString(), any());

        svc = new VolumeBreakoutAutoTradeService(cfg,
            mock(StockTrackerService.class), mock(BrokerageCalculator.class),
            mock(CapitalAllocationService.class), mock(TransactionTemplate.class),
            // The automation window only gates the scheduled tick, which these tests do not run.
            mock(MarketCalendarService.class));
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
    void savesEachFieldUnderTheModesOwnKey() {
        Map<String, Object> out = svc.saveSettings(1L, "PAPER", req(
            "autoBuy", true, "autoSell", true,
            "investmentAmount", 50000, "maxCapitalPerStock", 12000,
            "maxOpenPositions", 4, "maxDailyTrades", 8, "maxDailyLoss", 2500,
            "stopLossPct", 2.5, "trailingStopPct", 1.5, "targetPct", 3,
            "tradingStartTime", "09:20", "tradingEndTime", "15:20"));

        assertTrue((Boolean) out.get("success"), out.get("message").toString());
        assertTrue(rejected(out).isEmpty());
        assertEquals("true",  settings.get("volbreakout.paper.auto.buy"));
        assertEquals("true",  settings.get("volbreakout.paper.auto.sell"));
        assertEquals("50000", settings.get("volbreakout.paper.investment.amount"));
        assertEquals("12000", settings.get("volbreakout.paper.max.capital.per.stock"));
        assertEquals("4",     settings.get("volbreakout.paper.max.open.positions"));
        assertEquals("8",     settings.get("volbreakout.paper.max.daily.trades"));
        assertEquals("2500",  settings.get("volbreakout.paper.max.daily.loss"));
        assertEquals("2.5",   settings.get("volbreakout.paper.stop.loss.pct"));
        assertEquals("1.5",   settings.get("volbreakout.paper.trailing.stop.pct"));
        assertEquals("3",     settings.get("volbreakout.paper.target.pct"));
        assertEquals("09:20", settings.get("volbreakout.paper.trading.start.time"));
        assertEquals("15:20", settings.get("volbreakout.paper.trading.end.time"));
    }

    @Test
    void wholeNumbersAreStoredWithoutADecimalTail() {
        // "5.0" open positions in the Settings screen reads as a bug in the Settings screen.
        svc.saveSettings(1L, "PAPER", req("maxOpenPositions", 5.0, "investmentAmount", 100000.0));
        assertEquals("5",      settings.get("volbreakout.paper.max.open.positions"));
        assertEquals("100000", settings.get("volbreakout.paper.investment.amount"));
    }

    @Test
    void savingPaperLeavesRealUntouched() {
        svc.saveSettings(1L, "REAL",  req("stopLossPct", 1.0, "investmentAmount", 20000));
        svc.saveSettings(1L, "PAPER", req("stopLossPct", 9.0, "investmentAmount", 90000));

        // "1", not "1.0": a whole percentage stores without its tail, as pinned above.
        assertEquals("1",     settings.get("volbreakout.real.stop.loss.pct"));
        assertEquals("20000", settings.get("volbreakout.real.investment.amount"));
        assertEquals("9",     settings.get("volbreakout.paper.stop.loss.pct"));
        assertEquals("90000", settings.get("volbreakout.paper.investment.amount"));
    }

    @Test
    void neverWritesTheSharedKeys() {
        // The shared keys are the fallback for every mode. Writing one while saving a mode would
        // move the OTHER mode too, for any setting it was inheriting.
        svc.saveSettings(1L, "PAPER", req(
            "autoBuy", true, "stopLossPct", 4, "maxOpenPositions", 3,
            "tradingStartTime", "09:30"));
        for (String k : settings.keySet()) {
            assertTrue(k.startsWith("volbreakout.paper."),
                "saving PAPER wrote outside its own prefix: " + k);
        }
    }

    @Test
    void aRejectedValueIsNotWritten() {
        Map<String, Object> out = svc.saveSettings(1L, "PAPER", req(
            "stopLossPct", -3,          // negative: not a stop
            "targetPct", 5,             // fine, and must still be saved
            "maxOpenPositions", 9999,   // past the ceiling
            "tradingStartTime", "25:00" // not a time
        ));

        assertFalse((Boolean) out.get("success"));
        assertEquals(3, rejected(out).size(), rejected(out).toString());
        assertNull(settings.get("volbreakout.paper.stop.loss.pct"));
        assertNull(settings.get("volbreakout.paper.max.open.positions"));
        assertNull(settings.get("volbreakout.paper.trading.start.time"));
        // The legal value in the same request still landed: one bad field does not lose the rest.
        assertEquals("5", settings.get("volbreakout.paper.target.pct"));
    }

    @Test
    void zeroIsARealValueAndIsStored() {
        // 0 means "no limit" for the caps and "disabled" for the levels. It must be storable, or
        // a limit can be set and never removed.
        Map<String, Object> out = svc.saveSettings(1L, "PAPER", req(
            "maxDailyLoss", 0, "maxCapitalPerStock", 0, "maxDailyTrades", 0,
            "stopLossPct", 0, "trailingStopPct", 0, "targetPct", 0));

        assertTrue((Boolean) out.get("success"));
        assertEquals("0", settings.get("volbreakout.paper.max.daily.loss"));
        assertEquals("0", settings.get("volbreakout.paper.max.capital.per.stock"));
        assertEquals("0", settings.get("volbreakout.paper.stop.loss.pct"));
        assertEquals("0", settings.get("volbreakout.paper.target.pct"));
    }

    @Test
    void zeroInvestmentIsRefusedBecauseItIsNotALimit() {
        // Unlike the caps, the amount to deploy has no "no limit" reading: zero capital means
        // every quantity rounds to nothing and automation silently does nothing at all.
        Map<String, Object> out = svc.saveSettings(1L, "PAPER", req("investmentAmount", 0));
        assertFalse((Boolean) out.get("success"));
        assertNull(settings.get("volbreakout.paper.investment.amount"));
    }

    @Test
    void anAbsentFieldIsLeftAlone() {
        svc.saveSettings(1L, "PAPER", req("stopLossPct", 2, "targetPct", 6));
        svc.saveSettings(1L, "PAPER", req("stopLossPct", 3));   // target not mentioned

        assertEquals("3", settings.get("volbreakout.paper.stop.loss.pct"));
        assertEquals("6", settings.get("volbreakout.paper.target.pct"),
            "a partial save blanked a field it never mentioned");
    }

    @Test
    void aBackwardsWindowIsReportedEvenThoughBothTimesAreValid() {
        // Each time is a legal HH:MM, so neither is rejected on its own. Together they describe
        // a window automation can never be inside, which is worth saying out loud rather than
        // leaving to be discovered by nothing ever happening.
        Map<String, Object> out = svc.saveSettings(1L, "PAPER", req(
            "tradingStartTime", "15:30", "tradingEndTime", "09:15"));
        assertFalse((Boolean) out.get("success"));
        assertTrue(rejected(out).get(0).toLowerCase().contains("start"),
            rejected(out).toString());
    }

    @Test
    void settingsForReadsTheNamedModeNotTheLiveOne() {
        settings.put("volbreakout.mode", "PAPER");
        settings.put("volbreakout.real.stop.loss.pct", "1.25");
        settings.put("volbreakout.paper.stop.loss.pct", "7.5");

        assertEquals(1.25, (Double) svc.settingsFor("REAL").get("stopLossPct"), 1e-9);
        assertEquals(7.5,  (Double) svc.settingsFor("PAPER").get("stopLossPct"), 1e-9);
        assertTrue((Boolean) svc.settingsFor("PAPER").get("live"));
        assertFalse((Boolean) svc.settingsFor("REAL").get("live"));
    }

    @Test
    void settingsForSaysWhetherEachValueIsItsOwnOrInherited() {
        settings.put("volbreakout.stop.loss.pct", "5");            // shared
        settings.put("volbreakout.paper.stop.loss.pct", "inherit"); // explicitly not set
        settings.put("volbreakout.paper.target.pct", "9");          // its own

        @SuppressWarnings("unchecked")
        Map<String, Object> src =
            (Map<String, Object>) svc.settingsFor("PAPER").get("valueSource");
        assertEquals("shared", src.get("stop.loss.pct"));
        assertEquals("mode",   src.get("target.pct"));
        assertEquals(5.0, (Double) svc.settingsFor("PAPER").get("stopLossPct"), 1e-9);
    }
}
