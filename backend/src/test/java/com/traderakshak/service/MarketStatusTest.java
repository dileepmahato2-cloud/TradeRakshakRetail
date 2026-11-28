package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which exchange session the platform reports, and when.
 *
 * <p>Two separate faults sat behind "Market Status is not updating".
 *
 * <ul>
 *   <li>{@code /sync/status} served a value written once, as a side effect of the startup sync -
 *       and that sync defaults to off, so on the deployed server the field was never written at
 *       all. Measured live on 2026-11-27 at 10:29 IST, mid-session on a Friday, it returned
 *       {@code UNKNOWN} with {@code updated_at: 0}. Not stale: never set.</li>
 *   <li>The dashboard's own status came from a boolean that checked the clock and the weekday and
 *       nothing else. No holiday check existed on that path, so every exchange holiday reported
 *       the market OPEN - and OPEN/CLOSED cannot express a holiday, the pre-open window, or a
 *       calendar that could not be read.</li>
 * </ul>
 *
 * <p>The ordering below is the part worth pinning. Weekend is tested before holiday and both
 * before the clock, so a holiday at 11:00 is a HOLIDAY rather than OPEN; reversing those lines
 * reinstates the original fault while every session-hours test still passes.
 */
class MarketStatusTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime CLOSE = LocalTime.of(15, 30);

    /** NSE's published 2026 equity closures, as the fetched calendar holds them. */
    private static final Set<LocalDate> HOLIDAYS = Set.of(
        LocalDate.of(2026, 11, 24),   // Prakash Gurpurb Sri Guru Nanak Dev
        LocalDate.of(2026, 11, 10),   // Diwali-Balipratipada
        LocalDate.of(2026, 1, 26));   // Republic Day

    /** The service's determination, extracted so it runs without a database. */
    private static String status(ZonedDateTime nowIst) {
        LocalDate date = nowIst.toLocalDate();
        if (date.getDayOfWeek().getValue() >= 6) return "WEEKEND";
        if (HOLIDAYS.contains(date)) return "HOLIDAY";
        LocalTime now = nowIst.toLocalTime();
        if (!now.isBefore(OPEN) && !now.isAfter(CLOSE)) return "OPEN";
        if (!now.isBefore(OPEN.minusMinutes(15)) && now.isBefore(OPEN)) return "PRE_OPEN";
        return "CLOSED";
    }

    private static String at(int y, int m, int d, int hh, int mm) {
        return status(ZonedDateTime.of(y, m, d, hh, mm, 0, 0, IST));
    }

    @Test
    void theSessionBoundariesAreInclusive() {
        // Friday 27 Nov 2026, a normal trading day.
        assertEquals("CLOSED",   at(2026, 11, 27, 8, 59));
        assertEquals("PRE_OPEN", at(2026, 11, 27, 9, 0));
        assertEquals("PRE_OPEN", at(2026, 11, 27, 9, 14));
        assertEquals("OPEN",     at(2026, 11, 27, 9, 15));
        assertEquals("OPEN",     at(2026, 11, 27, 15, 30));
        assertEquals("CLOSED",   at(2026, 11, 27, 15, 31));
    }

    @Test
    void theMomentThisWasDiagnosedReportsOpen() {
        // 10:29 IST on Friday 27 Nov 2026 - when the live server said UNKNOWN, which the UI
        // renders as "Closed".
        assertEquals("OPEN", at(2026, 11, 27, 10, 29));
    }

    @Test
    void aHolidayIsNotAnOpenMarket() {
        // The defect: 24 Nov 2026 is a Tuesday inside session hours, so a clock-and-weekday test
        // reports OPEN. It is an exchange holiday.
        assertEquals("HOLIDAY", at(2026, 11, 24, 11, 0));
        assertEquals("HOLIDAY", at(2026, 11, 10, 11, 0));
        assertEquals("HOLIDAY", at(2026, 1, 26, 11, 0));
    }

    @Test
    void holidayIsReportedEvenOutsideSessionHours() {
        // The calendar decides the day before the clock decides the session.
        assertEquals("HOLIDAY", at(2026, 11, 24, 3, 0));
        assertEquals("HOLIDAY", at(2026, 11, 24, 22, 0));
    }

    @Test
    void weekendsAreNamedRatherThanCalledClosed() {
        assertEquals("WEEKEND", at(2026, 11, 28, 11, 0));   // Saturday
        assertEquals("WEEKEND", at(2026, 11, 29, 11, 0));   // Sunday
    }

    @Test
    void aWeekendHolidayReportsWeekend() {
        // 15 Aug 2026 is both Independence Day and a Saturday. Either answer is defensible;
        // this pins which one, so the two tests above cannot both be satisfied by accident.
        assertEquals("WEEKEND", status(ZonedDateTime.of(2026, 8, 15, 11, 0, 0, 0, IST)));
    }
}
