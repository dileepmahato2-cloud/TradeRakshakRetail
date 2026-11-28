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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The crossover trade log decides real money outcomes from historical bars, so each of its four
 * statuses is pinned here. The rule under test is that the status comes from what happened
 * FIRST after entry - never from the exit date, and never from today's date.
 */
class CrossoverTradeResolutionTest {

    private static final double ENTRY  = 100.0;
    private static final double STOP   = 93.0;    // entry -7%
    private static final double TARGET = 115.0;   // entry +15%

    private StockTrackerService service() {
        MarketCalendarService calendar = mock(MarketCalendarService.class);
        // Weekdays trade, weekends do not. Enough to prove the hold is counted in sessions.
        when(calendar.isTradingDay(any())).thenAnswer(inv ->
            ((LocalDate) inv.getArgument(0)).getDayOfWeek().getValue() <= 5);
        return new StockTrackerService(
            mock(TechnicalIndicatorEngine.class), calendar, new ObjectMapper(),
            mock(BlockingHttpService.class), mock(DhanBrokerService.class),
            mock(PlatformTransactionManager.class), mock(AppConfigService.class),
            mock(LivePriceBroadcaster.class), mock(StockUploadHistoryService.class));
    }

    /** Builds the newest-first bar series the resolver expects. */
    private List<Object> bars(List<String> dates, List<double[]> hlc) throws Exception {
        Class<?> barType = Class.forName("com.traderakshak.service.StockTrackerService$CandleBar");
        Constructor<?> ctor = barType.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        List<Object> out = new ArrayList<>();
        for (int i = dates.size() - 1; i >= 0; i--) {          // newest first
            double[] b = hlc.get(i);                            // {high, low, close}
            out.add(ctor.newInstance(dates.get(i), b[2], b[0], b[1], 1000.0, "DHAN"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolve(List<String> dates, List<double[]> hlc,
                                        String evaluationDate) throws Exception {
        StockTrackerService svc = service();
        List<Object> series = bars(dates, hlc);
        Map<String, Object> row = new LinkedHashMap<>();

        Method m = null;
        for (Method candidate : StockTrackerService.class.getDeclaredMethods()) {
            if (candidate.getName().equals("resolveCrossoverTrade")) { m = candidate; break; }
        }
        m.setAccessible(true);
        // dIdx is the entry session's index in the newest-first series: the oldest bar.
        m.invoke(svc, row, series, series.size() - 1, ENTRY, STOP, TARGET,
                 dates.get(0), evaluationDate);
        return row;
    }

    /** 20 quiet weekday sessions from Mon 2026-01-05, with one session overridden. */
    private List<String> quietDates() {
        List<String> dates = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-05");
        while (dates.size() < 25) {
            if (d.getDayOfWeek().getValue() <= 5) dates.add(d.toString());
            d = d.plusDays(1);
        }
        return dates;
    }

    /**
     * The hold the service is configured with, read rather than copied.
     *
     * <p>This test used to hardcode 15 sessions and the date that followed from it, and went red
     * the day the strategy was respecified to 22. What is worth pinning is the rule - the defined
     * exit is N trading sessions after entry, weekends skipped, anchored to the entry date - not
     * the value of N, which is the strategy author to choose.
     */
    private static int maxHold() throws Exception {
        java.lang.reflect.Field fld =
            StockTrackerService.class.getDeclaredField("CROSSOVER_MAX_HOLD");
        fld.setAccessible(true);
        return (int) fld.get(null);
    }

    /** N trading sessions after a date, counted the way the calendar mock above trades. */
    private static String sessionsAfter(String from, int sessions) {
        LocalDate d = LocalDate.parse(from);
        for (int i = 0; i < sessions; i++) {
            do { d = d.plusDays(1); } while (d.getDayOfWeek().getValue() > 5);
        }
        return d.toString();
    }

    private List<double[]> quietBars(int n) {
        List<double[]> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) bars.add(new double[]{104.0, 97.0, 100.0}); // never hits either
        return bars;
    }

    @Test
    void targetHitFirstBooksProfitOnThatSession() throws Exception {
        List<String> dates = quietDates();
        List<double[]> bars = quietBars(dates.size());
        bars.set(3, new double[]{116.0, 99.0, 115.5});          // target reached on the 4th session
        Map<String, Object> row = resolve(dates, bars, "2026-12-31");

        assertEquals("PROFIT BOOK", row.get("trade_status"));
        assertEquals(dates.get(3), row.get("exit_date"));
        assertEquals(TARGET, (Double) row.get("exit_price"), 0.001);
        assertEquals(dates.get(3), row.get("target_date"));
        assertNull(row.get("stop_loss_date"), "a booked target must not carry a stop date");
    }

    @Test
    void stopHitFirstClosesAtTheStopEvenWhenTargetComesLater() throws Exception {
        List<String> dates = quietDates();
        List<double[]> bars = quietBars(dates.size());
        bars.set(2, new double[]{104.0, 92.0, 94.0});           // stop on session 3
        bars.set(5, new double[]{120.0, 99.0, 119.0});          // target later - must not win
        Map<String, Object> row = resolve(dates, bars, "2026-12-31");

        assertEquals("STOP LOSS", row.get("trade_status"));
        assertEquals(dates.get(2), row.get("exit_date"));
        assertEquals(STOP, (Double) row.get("exit_price"), 0.001);
        assertNull(row.get("target_date"));
    }

    @Test
    void oneSessionTouchingBothIsTreatedAsTheStop() throws Exception {
        List<String> dates = quietDates();
        List<double[]> bars = quietBars(dates.size());
        bars.set(4, new double[]{116.0, 92.0, 100.0});          // both levels, same session
        Map<String, Object> row = resolve(dates, bars, "2026-12-31");

        assertEquals("STOP LOSS", row.get("trade_status"),
            "a daily bar cannot order the two, so the conservative reading stands");
        assertEquals(Boolean.TRUE, row.get("same_bar_both"),
            "and it must be flagged so the intraday pass can settle it");
    }

    @Test
    void untouchedTradeExitsOnTheDefinedDateAfterTheConfiguredHold() throws Exception {
        List<String> dates = quietDates();
        Map<String, Object> row = resolve(dates, quietBars(dates.size()), "2026-12-31");
        String expected = sessionsAfter(dates.get(0), maxHold());

        assertEquals("DEFINED DATE EXIT", row.get("trade_status"));
        // Trading sessions, not calendar days: the weekends in between must not be counted.
        assertEquals(expected, row.get("defined_exit_date"));
        assertEquals(expected, row.get("exit_date"));
    }

    @Test
    void tradeWhoseDeadlineHasNotArrivedStaysOpenWithNoExit() throws Exception {
        List<String> dates = quietDates();
        // Evaluated a week after entry: the configured hold has not run out yet.
        Map<String, Object> row = resolve(dates, quietBars(dates.size()), "2026-01-12");

        assertEquals("OPEN", row.get("trade_status"));
        assertNull(row.get("exit_date"), "an unfinished trade must not be given an exit date");
        assertNull(row.get("exit_price"));
    }

    @Test
    void entryDateIsNeverReplacedByTheEvaluationDate() throws Exception {
        List<String> dates = quietDates();
        Map<String, Object> row = resolve(dates, quietBars(dates.size()), "2026-12-31");
        // The resolver owns the exit side; the entry date it was handed must survive untouched
        // in the defined exit calculation, which is anchored to 2026-01-05 and not to 2026-12-31.
        // Counting from the evaluation date would land in 2027, which is the bug being excluded.
        assertEquals(sessionsAfter(dates.get(0), maxHold()), row.get("defined_exit_date"));
    }
}
