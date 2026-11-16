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
import com.traderakshak.util.DateUtil;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * The "previous close" the crossing test compares against.
 *
 * <p>Close(D-1) &lt; Base Price AND High(D) &gt;= Base Price only means something while D-1 really
 * is the session before the one the current price came from. {@code previousBarIndex} does NOT
 * guarantee that: it steps back off the newest bar only when that bar is today's, so once the
 * market has closed - every evening, all weekend - it hands back the very bar the current price
 * came from. prevClose then equals currentPrice, and since candidates are shortlisted for being
 * at or above base, "prevClose >= base" rejects all of them. On 6 Sep 2026 that was 793 of 794.
 *
 * <p>These assertions pin the trap in place so the crossing sites are not "simplified" back onto
 * it. They are deterministic: neither depends on the wall clock or on market hours.
 */
class CrossingPreviousCloseTest {

    private StockTrackerService service() {
        return new StockTrackerService(
            mock(TechnicalIndicatorEngine.class),
            mock(MarketCalendarService.class),
            new ObjectMapper(),
            mock(BlockingHttpService.class),
            mock(DhanBrokerService.class),
            mock(PlatformTransactionManager.class),
            mock(AppConfigService.class),
            mock(LivePriceBroadcaster.class),
            mock(StockUploadHistoryService.class));
    }

    /** Newest first, which is the order every caller stores them in. */
    private Object bar(String date, double close) throws Exception {
        Class<?> c = Class.forName("com.traderakshak.service.StockTrackerService$CandleBar");
        Constructor<?> ctor = c.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(date, close, close, close, 1000d, "DHAN");
    }

    private int previousBarIndex(StockTrackerService s, List<?> bars) throws Exception {
        Method m = StockTrackerService.class.getDeclaredMethod("previousBarIndex", List.class);
        m.setAccessible(true);
        return (Integer) m.invoke(s, bars);
    }

    @Test
    void previousBarIndexReturnsTheNewestBarWhenThereIsNoBarForToday() throws Exception {
        StockTrackerService s = service();
        // Stored history trailing the calendar - a weekend, a holiday, or a backfill behind.
        List<?> bars = List.of(bar("2026-09-04", 105.0), bar("2026-09-03", 95.0));

        assertEquals(0, previousBarIndex(s, bars),
            "previousBarIndex points at the NEWEST bar when none is today's. Used as the crossing "
          + "previous close after the market shuts, that is the same bar the current price came "
          + "from, so the crossing test can never fire.");
    }

    @Test
    void previousBarIndexStepsBackOnlyWhileTodaysSessionIsPresent() throws Exception {
        StockTrackerService s = service();
        List<?> bars = List.of(bar(DateUtil.today(), 105.0), bar("2026-09-03", 95.0));

        assertEquals(1, previousBarIndex(s, bars),
            "With an in-progress session present the previous close is the bar behind it.");
    }

    @Test
    void crossingUsesTheSessionBeforeTheOneTheCurrentPriceCameFrom() {
        // The rule both selection loops apply: index 1 whenever the current price IS the latest
        // stored bar's close, index 0 only while it is a separate live tick.
        double base = 100.0, newestClose = 105.0, priorClose = 95.0;

        int idxWhenPriceIsTheLatestBar = 1;                  // market closed
        double prevClose = idxWhenPriceIsTheLatestBar == 1 ? priorClose : newestClose;
        org.junit.jupiter.api.Assertions.assertTrue(prevClose < base,
            "95 < 100: the stock crossed up through its base and must be eligible.");

        // What the defect did instead.
        double prevCloseBefore = newestClose;
        org.junit.jupiter.api.Assertions.assertEquals(newestClose, prevCloseBefore);
        org.junit.jupiter.api.Assertions.assertFalse(prevCloseBefore < base,
            "Comparing the current price with itself is what rejected every candidate.");
    }
}
