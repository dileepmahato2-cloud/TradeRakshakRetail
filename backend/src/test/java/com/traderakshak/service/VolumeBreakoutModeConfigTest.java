package com.traderakshak.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Separate Paper and Real settings, and the inheritance between them.
 *
 * <p>Pinned because this is the failure mode that has already happened once on this screen: the
 * ratio-cap settings were seeded, looked configured, and were read by nothing. Here the risk is
 * the mirror image - a per-mode key that is always present because it is seeded, silently
 * overriding the shared value with its own default and making the shared setting unreachable.
 *
 * <p>Hence the {@code inherit} sentinel rather than a numeric one: 0 is a real value for several
 * of these ("no limit") and false is a real value for the toggles, so neither can mean "not set".
 */
class VolumeBreakoutModeConfigTest {

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

        svc = new VolumeBreakoutAutoTradeService(cfg,
            mock(StockTrackerService.class), mock(BrokerageCalculator.class),
            mock(CapitalAllocationService.class), mock(TransactionTemplate.class),
            // The automation window only gates the scheduled tick, which these tests do not run.
            mock(MarketCalendarService.class));
    }

    /** Mirrors what the seeder writes: every per-mode key exists, set to the sentinel. */
    private void seedAsShipped() {
        for (String mode : new String[]{"paper", "real"}) {
            for (String k : new String[]{"stop.loss.pct", "target.pct", "trailing.stop.pct",
                                         "investment.amount", "max.open.positions",
                                         "max.daily.loss", "max.daily.trades",
                                         "max.capital.per.stock", "auto.buy", "auto.sell",
                                         "trading.start.time", "trading.end.time"}) {
                settings.put("volbreakout." + mode + "." + k, "inherit");
            }
        }
    }

    @Test
    void asShippedEveryModeKeyInheritsTheSharedValue() {
        // The case that would have been broken by a numeric sentinel: the keys all exist, so a
        // typed read would return their default and the shared value could never apply.
        seedAsShipped();
        settings.put("volbreakout.mode", "PAPER");
        settings.put("volbreakout.stop.loss.pct", "5");
        settings.put("volbreakout.target.pct", "12");
        settings.put("volbreakout.auto.buy", "true");

        assertEquals(5.0, svc.stopLossPct());
        assertEquals(12.0, svc.targetPct());
        assertTrue(svc.isAutoBuyEnabled(), "a shared true must survive a seeded per-mode key");
    }

    @Test
    void aPerModeValueOverridesTheSharedOne() {
        seedAsShipped();
        settings.put("volbreakout.mode", "REAL");
        settings.put("volbreakout.stop.loss.pct", "5");
        settings.put("volbreakout.real.stop.loss.pct", "2");

        assertEquals(2.0, svc.stopLossPct(),
            "real money should be able to run a tighter stop than the simulator");
    }

    @Test
    void theTwoModesAreGenuinelyIndependent() {
        seedAsShipped();
        settings.put("volbreakout.stop.loss.pct", "5");
        settings.put("volbreakout.paper.stop.loss.pct", "8");
        settings.put("volbreakout.real.stop.loss.pct", "2");

        settings.put("volbreakout.mode", "PAPER");
        assertEquals(8.0, svc.stopLossPct());
        settings.put("volbreakout.mode", "REAL");
        assertEquals(2.0, svc.stopLossPct(),
            "switching mode must not carry the other mode's number across");
    }

    @Test
    void zeroIsARealOverrideNotAnAbsentOne() {
        // 0 means "no limit" for these, so it has to be distinguishable from "not set". A numeric
        // sentinel would have made this impossible to express.
        seedAsShipped();
        settings.put("volbreakout.mode", "REAL");
        settings.put("volbreakout.max.open.positions", "5");
        settings.put("volbreakout.real.max.open.positions", "0");

        assertEquals(0, svc.maxOpenPositions(), "an explicit 0 must override a shared 5");
    }

    @Test
    void falseIsARealOverrideToo() {
        seedAsShipped();
        settings.put("volbreakout.mode", "REAL");
        settings.put("volbreakout.auto.buy", "true");
        settings.put("volbreakout.real.auto.buy", "false");

        assertFalse(svc.isAutoBuyEnabled(),
            "turning automation off for real money must not be overridden by the shared setting");
    }

    @Test
    void anUnparseableOverrideFallsBackRatherThanThrowing() {
        seedAsShipped();
        settings.put("volbreakout.mode", "PAPER");
        settings.put("volbreakout.stop.loss.pct", "5");
        settings.put("volbreakout.paper.stop.loss.pct", "five percent");

        assertEquals(5.0, svc.stopLossPct(),
            "a typo in one setting must not take the strategy down with it");
    }

    @Test
    void blankIsTreatedAsInherit() {
        seedAsShipped();
        settings.put("volbreakout.mode", "PAPER");
        settings.put("volbreakout.target.pct", "9");
        settings.put("volbreakout.paper.target.pct", "   ");

        assertEquals(9.0, svc.targetPct(), "a cleared field means inherit, not zero");
    }

    @Test
    void theTradingWindowIsPerModeAndReportedAsSuch() {
        seedAsShipped();
        settings.put("volbreakout.mode", "REAL");
        settings.put("volbreakout.trading.start.time", "09:15");
        settings.put("volbreakout.trading.end.time", "15:30");
        settings.put("volbreakout.real.trading.start.time", "10:00");

        assertEquals("10:00", svc.tradingStartTime());
        assertEquals("15:30", svc.tradingEndTime(), "the end time still inherits");
    }

    @Test
    void effectiveSettingsNamesTheModeAndThePrefixInForce() {
        // So a user can see WHICH key is being read rather than guessing why a number differs
        // from the one they just edited.
        seedAsShipped();
        settings.put("volbreakout.mode", "REAL");
        Map<String, Object> eff = svc.effectiveSettings();

        assertEquals("REAL", eff.get("mode"));
        assertEquals("volbreakout.real.", eff.get("keyPrefix"));
        assertEquals("volbreakout.", eff.get("fallbackPrefix"));
    }

    @Test
    void anUnknownModeFallsBackToTheSharedSettings() {
        seedAsShipped();
        settings.put("volbreakout.mode", "SOMETHING_ELSE");
        settings.put("volbreakout.stop.loss.pct", "7");
        assertEquals(7.0, svc.stopLossPct());
    }
}
