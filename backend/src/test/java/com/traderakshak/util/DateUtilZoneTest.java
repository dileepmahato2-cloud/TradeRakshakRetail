package com.traderakshak.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Today" must be the exchange's today, whatever the host's clock is set to.
 *
 * <p>{@link DateUtil#today()} and {@link DateUtil#now()} resolved in the JVM's default zone, and
 * the deployed server runs UTC. Close to 500 call sites ask this class what day it is, so the
 * platform ran 5 hours 30 minutes behind its own market: timestamps were born stale - the live
 * server reported {@code generated_at 04:30:18} at 10:00 IST - and {@code today()} returned
 * YESTERDAY for the five and a half hours between midnight and 05:30 IST.
 *
 * <p>The first test is the one that matters, because it fails on a UTC host and passes on an
 * Indian developer's laptop. That asymmetry is why this went unnoticed: it is invisible wherever
 * the people reading the screen happen to be.
 */
class DateUtilZoneTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void todayIsTheExchangeDateNotTheHostDate() {
        assertEquals(LocalDate.now(IST).toString(), DateUtil.today(),
            "today() must resolve in Asia/Kolkata regardless of the host's default zone");
    }

    @Test
    void nowIsStampedInIst() {
        LocalDateTime ist = LocalDateTime.now(IST);
        String stamped = DateUtil.now();
        assertTrue(stamped.startsWith(ist.toLocalDate().toString()),
            "now() should carry the IST date, got " + stamped);
        // Hour must match IST, not UTC. On a UTC host the two differ by 5, which is exactly the
        // gap that made every "last updated" look hours old.
        int stampedHour = Integer.parseInt(stamped.substring(11, 13));
        assertTrue(Math.abs(stampedHour - ist.getHour()) <= 1,
            "now() hour " + stampedHour + " should track IST hour " + ist.getHour());
    }

    @Test
    void theMidnightWindowIsWhereUtcGotItWrong() {
        // 02:00 IST on 27 Nov is still 20:30 UTC on 26 Nov. A host-zone today() returns the 26th
        // through this whole window - and "today" chooses the session to screen, the date a
        // candle is stored against, and the day a trade is booked on.
        ZonedDateTime earlyIst = ZonedDateTime.of(2026, 11, 27, 2, 0, 0, 0, IST);
        LocalDate istDate = earlyIst.toLocalDate();
        LocalDate utcDate = earlyIst.withZoneSameInstant(ZoneId.of("UTC")).toLocalDate();

        assertEquals(LocalDate.of(2026, 11, 27), istDate);
        assertEquals(LocalDate.of(2026, 11, 26), utcDate);
        assertNotEquals(istDate, utcDate,
            "this is the off-by-one-day the host zone produced every night");
    }

    @Test
    void theZoneIsFixedToTheExchange() {
        assertEquals("Asia/Kolkata", DateUtil.IST.getId());
        assertEquals(LocalDate.now(IST), DateUtil.todayDate());
    }
}
