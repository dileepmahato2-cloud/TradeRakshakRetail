package com.traderakshak.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Automation screen's Buy/Sell Time and Enable must actually reach the scheduler.
 *
 * <p>They did not. There are two settings stores: the Auto Buy &amp; Sell screen writes
 * {@code autotrade.<strategy>.<mode>.<name>}, while the per-minute engine read
 * {@code volbreakout.<mode>.<name>}. A buy time typed on the screen was stored, shown back, and
 * then ignored by the only thing that could act on it - so "execute the buy logic at the
 * configured time" had no configured time to execute at.
 *
 * <p>These cases pin the resolution order the engine now uses, against the real settings store:
 * the screen's value first, then the per-mode key, then the original shared key so an existing
 * deployment keeps the time it already has.
 *
 * <p>Writes only to the {@code paper} keys of the source the scheduler trades, and restores every
 * value it touched afterwards. Run with {@code -Dtest=ScheduledTriggerResolutionIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.datasource.password=123456",
    "sync.enabled=false",
    "server.ssl.enabled=false"
})
class ScheduledTriggerResolutionIT {

    /** The source whose finalized shortlist the scheduler actually trades. */
    private static final String SCREEN = "autotrade.rsi45_previous_day.paper.";
    private static final String SHARED = "volbreakout.";

    @Autowired VolumeBreakoutAutoTradeService engine;
    @Autowired AppConfigService config;

    private String savedArmed, savedBuy, savedSell, savedAutoBuy;

    private void remember() {
        savedArmed   = config.getString(SCREEN + "armed", "");
        savedBuy     = config.getString(SCREEN + "buy.time", "");
        savedSell    = config.getString(SCREEN + "sell.time", "");
        savedAutoBuy = config.getString(SCREEN + "auto.buy", "");
    }

    private void set(String key, String value) {
        config.setValue(key, value, "AUTOTRADE", "STRING", 1L);
    }

    @AfterEach
    void restore() {
        // Put back exactly what was there, including "not set" as the inherit sentinel.
        set(SCREEN + "armed",     savedArmed.isBlank()   ? "inherit" : savedArmed);
        set(SCREEN + "buy.time",  savedBuy.isBlank()     ? "inherit" : savedBuy);
        set(SCREEN + "sell.time", savedSell.isBlank()    ? "inherit" : savedSell);
        set(SCREEN + "auto.buy",  savedAutoBuy.isBlank() ? "inherit" : savedAutoBuy);
    }

    @Test
    @DisplayName("the screen's Buy Time is what the scheduler fires on")
    void screenBuyTimeWins() {
        remember();
        // Deliberately not 15:25, the shared default, so a pass cannot be a coincidence.
        set(SCREEN + "buy.time", "09:42");
        assertEquals("09:42", engine.realOrderTime(),
            "the Buy Time saved on the Automation screen must be the time the engine uses");
    }

    @Test
    @DisplayName("the screen's Sell Time is what the sell sweep fires on")
    void screenSellTimeWins() {
        remember();
        set(SCREEN + "sell.time", "14:07");
        assertEquals("14:07", engine.sellTime());
    }

    @Test
    @DisplayName("with nothing set on the screen, the original shared key still decides")
    void fallsBackToTheSharedKey() {
        remember();
        set(SCREEN + "buy.time", "inherit");
        // volbreakout.real.order.time is the key this has always read. An existing deployment
        // must keep its configured time rather than silently reverting to a default.
        String shared = config.getString(SHARED + "real.order.time", "15:25");
        assertEquals(shared, engine.realOrderTime(),
            "an unset screen value must not override the deployment's existing time");
    }

    @Test
    @DisplayName("Disable stops automation, and is the default when nothing was ever saved")
    void disableStopsAutomationAndFailsClosed() {
        remember();
        set(SCREEN + "armed", "false");
        assertFalse(engine.automationArmed(),
            "Disable must stop automation - this is the switch the tick checks first");

        set(SCREEN + "armed", "inherit");
        assertFalse(engine.automationArmed(),
            "automation that was never deliberately enabled must not act");
    }

    @Test
    @DisplayName("Enable alone does not arm automation - Auto Buy is still required")
    void enableAloneIsNotEnough() {
        remember();
        set(SCREEN + "armed", "true");
        set(SCREEN + "auto.buy", "false");
        assertTrue(engine.automationArmed());
        assertFalse(engine.isAutoBuyEnabled(),
            "two switches, so neither on its own can start placing orders");
    }

    @Test
    @DisplayName("the screen's Auto Buy reaches the engine")
    void screenAutoBuyReachesTheEngine() {
        remember();
        set(SCREEN + "auto.buy", "true");
        assertTrue(engine.isAutoBuyEnabled(),
            "the Auto Buy checkbox had no effect on the scheduler before this");
    }

    @Test
    @DisplayName("a time set on the screen is a valid clock time the tick can match")
    void timeIsComparableToTheTickFormat() {
        remember();
        set(SCREEN + "buy.time", "09:05");
        // The tick builds "%02d:%02d" and compares with equals, so a stored "9:05" would never
        // match and automation would simply never fire.
        assertTrue(engine.realOrderTime().matches("^([01]\\d|2[0-3]):[0-5]\\d$"),
            "the stored time must be zero-padded HH:MM or the per-minute comparison never matches");
    }
}
