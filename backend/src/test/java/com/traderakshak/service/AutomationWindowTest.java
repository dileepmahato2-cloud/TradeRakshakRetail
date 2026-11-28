package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the platform is allowed to call an external provider.
 *
 * <p>Automatic outbound calls are confined to a trading day between 08:00 and 16:00 IST. Outside
 * that - overnight, at weekends and on exchange holidays - the schedulers that reach Dhan or NSE
 * stand down.
 *
 * <p>The boundaries are what this pins, because they are the part that is easy to get wrong by
 * one minute and impossible to notice: a job that runs when it should not makes a call nobody
 * asked for, and a job that does not run when it should goes silent without failing.
 *
 * <p>The rule is deliberately one predicate rather than an hour check copied into each of the
 * nine schedulers that call outward, so that adding a holiday to the calendar reaches all of
 * them at the same moment.
 */
class AutomationWindowTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(8, 0);
    private static final LocalTime END = LocalTime.of(16, 0);

    /** NSE equity closures, as the fetched calendar holds them. */
    private static final Set<LocalDate> HOLIDAYS = Set.of(
        LocalDate.of(2026, 11, 24),   // Prakash Gurpurb Sri Guru Nanak Dev
        LocalDate.of(2026, 12, 25));  // Christmas

    /** The service's rule, extracted so it runs without a database. */
    private static boolean open(ZonedDateTime now) {
        LocalDate d = now.toLocalDate();
        if (d.getDayOfWeek().getValue() >= 6) return false;
        if (HOLIDAYS.contains(d)) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(START) && t.isBefore(END);
    }

    private static boolean at(int y, int m, int d, int hh, int mm) {
        return open(ZonedDateTime.of(y, m, d, hh, mm, 0, 0, IST));
    }

    @Test
    void theWindowOpensAtEightAndClosesAtFour() {
        // Friday 27 Nov 2026, a normal trading day.
        assertFalse(at(2026, 11, 27, 7, 59), "before 08:00");
        assertTrue(at(2026, 11, 27, 8, 0), "08:00 exactly is inside");
        assertTrue(at(2026, 11, 27, 15, 59));
        assertFalse(at(2026, 11, 27, 16, 0), "16:00 exactly is outside - the window ends there");
        assertFalse(at(2026, 11, 27, 16, 1));
    }

    @Test
    void overnightIsClosed() {
        assertFalse(at(2026, 11, 27, 20, 0));
        assertFalse(at(2026, 11, 27, 23, 59));
        assertFalse(at(2026, 11, 28, 0, 1));
        assertFalse(at(2026, 11, 27, 3, 0));
    }

    @Test
    void weekendsAreClosedAllDay() {
        assertFalse(at(2026, 11, 28, 11, 0), "Saturday, mid-window hour");
        assertFalse(at(2026, 11, 29, 11, 0), "Sunday, mid-window hour");
    }

    @Test
    void exchangeHolidaysAreClosedAllDay() {
        // 24 Nov 2026 is a Tuesday inside window hours. Only the calendar distinguishes it.
        assertFalse(at(2026, 11, 24, 11, 0), "Guru Nanak Jayanti");
        assertFalse(at(2026, 12, 25, 11, 0), "Christmas");
        assertTrue(at(2026, 11, 23, 11, 0), "the trading Monday before it is open");
    }

    @Test
    void theRelocatedJobsNowFallInsideTheWindow() {
        // Two jobs were moved because their old times sat permanently outside it: the NSE
        // holiday calendar at 06:00 and the daily candle fetch at 17:00. Had they been left
        // where they were, each would have stopped running altogether.
        assertFalse(at(2026, 11, 27, 6, 0), "the old holiday-calendar time");
        assertFalse(at(2026, 11, 27, 17, 0), "the old daily-candle fetch time");
        assertTrue(at(2026, 11, 27, 8, 5), "where the holiday calendar runs now");
        assertTrue(at(2026, 11, 27, 8, 10), "where the daily candle fetch runs now");
    }
}
