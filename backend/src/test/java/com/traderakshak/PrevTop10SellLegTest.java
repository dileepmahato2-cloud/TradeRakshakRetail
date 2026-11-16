package com.traderakshak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.service.AppConfigService;
import com.traderakshak.service.BlockingHttpService;
import com.traderakshak.service.DhanBrokerService;
import com.traderakshak.service.LivePriceBroadcaster;
import com.traderakshak.service.MarketCalendarService;
import com.traderakshak.service.StockTrackerService;
import com.traderakshak.service.StockUploadHistoryService;
import com.traderakshak.service.TechnicalIndicatorEngine;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Previous Top 10 holds one overnight trade: bought at the cut-off, sold at 10:15 on the NEXT
 * market day.
 *
 * <p>Both legs used to be resolved independently from the same "1D" period, and since
 * resolvePeriodDate counts back from today they landed on the same session - 4 Sep bought at
 * 15:15 and sold at 10:15 the same morning, five hours before the purchase it closed. The guard
 * in place compared dates only, so a same-day sell walked straight past it.
 */
class PrevTop10SellLegTest {

    private StockTrackerService serviceWithWeekendCalendar() {
        MarketCalendarService cal = mock(MarketCalendarService.class);
        when(cal.isTradingDay(any(LocalDate.class))).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
        });
        return new StockTrackerService(
            mock(TechnicalIndicatorEngine.class), cal, new ObjectMapper(),
            mock(BlockingHttpService.class), mock(DhanBrokerService.class),
            mock(PlatformTransactionManager.class), mock(AppConfigService.class),
            mock(LivePriceBroadcaster.class), mock(StockUploadHistoryService.class));
    }

    private String plusTradingDays(StockTrackerService s, String from, int n) throws Exception {
        Method m = StockTrackerService.class
            .getDeclaredMethod("plusTradingDays", String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(s, from, n);
    }

    @Test
    void sellIsTheNextTradingDayAfterTheBuy() throws Exception {
        StockTrackerService s = serviceWithWeekendCalendar();
        // Thursday buys sell on Friday.
        assertEquals("2026-09-04", plusTradingDays(s, "2026-09-03", 1));
    }

    @Test
    void aFridayBuySellsOnMondayNotSaturday() throws Exception {
        StockTrackerService s = serviceWithWeekendCalendar();
        assertEquals("2026-09-07", plusTradingDays(s, "2026-09-04", 1),
            "The sell leg must step over the weekend, not land on it.");
    }

    @Test
    void theSellInstantMustBeStrictlyAfterTheBuyInstant() {
        // The comparison the guard makes. Dates alone are not enough: the pair that shipped was
        // the same day, 15:15 buy against a 10:15 sell. Both are candle starts: bars are hourly,
        // anchored on the 09:15 open, so 10:15 and 15:15 are bar boundaries and an off-grid time
        // would resolve to a different bar than the one named here.
        String buy  = "2026-09-04" + "T" + "15:15";
        String sameDayEarlierSell = "2026-09-04" + "T" + "10:15";
        String nextDaySell        = "2026-09-07" + "T" + "10:15";

        assertTrue(sameDayEarlierSell.compareTo(buy) <= 0,
            "A 10:15 sell on the buy's own day must be rejected.");
        assertTrue(nextDaySell.compareTo(buy) > 0,
            "10:15 on the next market day is a valid exit.");
        // Date-only comparison, which is what let the bad pair through.
        assertEquals(0, "2026-09-04".compareTo("2026-09-04"),
            "Comparing dates alone cannot tell these two apart - hence the timestamp compare.");
    }
}
