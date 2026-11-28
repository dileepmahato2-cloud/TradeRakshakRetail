package com.traderakshak.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading NSE's published holiday calendar.
 *
 * <p>Before this, {@code market.holidays} shipped empty and was never filled, so every holiday in
 * the platform's history read as an ordinary trading day. The visible cost was on the data-health
 * panel: its scan skips weekends and holidays, the holiday half never matched, and so it reported
 * each closed exchange as a session missing its candles - sending a reader off to backfill a day
 * on which nothing had traded. 24 Nov 2026 was reported that way for days.
 *
 * <p>Exercised against a payload captured from NSE rather than a hand-written sample, because the
 * two things that break in this parse are both silent:
 *
 * <ul>
 *   <li>The dates are {@code dd-MMM-yyyy} ("24-Nov-2026"). An ISO parser throws on every row, and
 *       since a row that will not parse is skipped by design, the result is an empty calendar and
 *       a successful-looking fetch.</li>
 *   <li>The segments are separate calendars. NSE closes the capital market 20 days in 2026 and the
 *       corporate bond market 24; folding them together marks four sessions as holidays on which
 *       equities in fact traded, which would hide real gaps instead of explaining them.</li>
 * </ul>
 */
class NseHolidayParseTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/nse-holiday-master.json");

    private static List<NseExchangeFetchService.Holiday> parse() throws Exception {
        return NseExchangeFetchService.parseHolidayPayload(
            new ObjectMapper().readTree(Files.readString(FIXTURE)));
    }

    private static Set<LocalDate> datesFor(List<NseExchangeFetchService.Holiday> all, String segment) {
        return all.stream()
            .filter(h -> segment.equals(h.segment()))
            .map(NseExchangeFetchService.Holiday::date)
            .collect(Collectors.toSet());
    }

    @Test
    void readsNseDayMonthYearDates() throws Exception {
        List<NseExchangeFetchService.Holiday> all = parse();
        assertFalse(all.isEmpty(), "nothing parsed: the dd-MMM-yyyy format is not being read");

        // The date that prompted all of this, with NSE's own description.
        NseExchangeFetchService.Holiday gurpurb = all.stream()
            .filter(h -> "CM".equals(h.segment()) && h.date().equals(LocalDate.of(2026, 11, 24)))
            .findFirst().orElseThrow(() -> new AssertionError("24 Nov 2026 missing from CM"));
        assertEquals("Tuesday", gurpurb.weekDay());
        assertTrue(gurpurb.description().contains("Guru Nanak"),
            "expected NSE's reason, got: " + gurpurb.description());
    }

    @Test
    void keepsSegmentsApart() throws Exception {
        List<NseExchangeFetchService.Holiday> all = parse();
        Set<LocalDate> cm = datesFor(all, "CM");
        Set<LocalDate> cbm = datesFor(all, "CBM");

        assertEquals(20, cm.size(), "2026 closes the capital market on 20 days");
        assertEquals(24, cbm.size(), "2026 closes the corporate bond market on 24 days");

        // A date in CBM alone must not reach the equity calendar: 19 Feb 2026 is a bond-market
        // closure and a normal trading Thursday for equities.
        LocalDate bondOnly = LocalDate.of(2026, 2, 19);
        assertTrue(cbm.contains(bondOnly), "fixture no longer has the bond-only date");
        assertFalse(cm.contains(bondOnly),
            bondOnly + " traded on the capital market; marking it a holiday retires a real session");
    }

    @Test
    void skipsAMalformedRowWithoutLosingTheCalendar() throws Exception {
        // The fixture carries one unparseable date. The whole point is that it costs that row and
        // nothing else - a single bad entry must not empty the calendar.
        Set<LocalDate> cm = datesFor(parse(), "CM");
        assertEquals(20, cm.size(), "the malformed row should cost only itself");
    }

    @Test
    void equityCalendarIncludesWeekendClosuresItIsHarmlessToKeep() throws Exception {
        Set<LocalDate> cm = datesFor(parse(), "CM");
        // NSE lists holidays that fall on a weekend (15 Feb 2026 is a Sunday). They are kept as
        // published rather than filtered: the weekend test already excludes them everywhere they
        // are used, and dropping them would make the stored calendar disagree with NSE's.
        long weekend = cm.stream()
            .filter(d -> d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY)
            .count();
        assertEquals(4, weekend, "2026 lists four CM holidays on weekend days");
    }
}
