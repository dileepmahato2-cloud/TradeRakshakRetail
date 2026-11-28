package com.traderakshak.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * "Now" and "today" for an Indian-exchange platform: always Asia/Kolkata.
 *
 * <p>These two methods resolved in the JVM's DEFAULT zone, and the deployed server runs UTC.
 * Nearly 500 call sites across the backend ask this class what day it is, so that one omission
 * put the whole platform 5 hours 30 minutes behind the market it trades:
 *
 * <ul>
 *   <li>Every stored and displayed timestamp - last refreshed, generated at, last successful
 *       update - read 5½ hours old the moment it was written. Measured on the live server: the
 *       market-health panel reported {@code generated_at 2026-11-27 04:30:18} when the clock in
 *       Mumbai said 10:00. A reader cannot tell that from a panel that has stopped refreshing,
 *       which is how it was reported.</li>
 *   <li>Worse, {@link #today()} was wrong outright between 00:00 and 05:30 IST. UTC is behind
 *       IST, so through those five and a half hours it returned YESTERDAY - and "today" decides
 *       which session is screened, which date a candle is stored against and which day a trade
 *       is booked on.</li>
 * </ul>
 *
 * <p>The rest of the codebase already knew this: the scheduled jobs name
 * {@code zone = "Asia/Kolkata"} and the services carry their own IST constants. This class was
 * the one place still asking the host.
 *
 * <p>Deliberately not configurable. A second zone here would mean two answers to "what day is
 * it" inside one process, and the exchange this platform serves does not move.
 */
public final class DateUtil {

    /** The exchange's zone. Every method here resolves in it, never in the host's. */
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter D_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DateUtil() {}

    /** The current date and time in IST, as {@code yyyy-MM-dd HH:mm:ss}. */
    public static String now() {
        return LocalDateTime.now(IST).format(DT_FMT);
    }

    /** Today's date on the exchange, as {@code yyyy-MM-dd}. */
    public static String today() {
        return LocalDate.now(IST).format(D_FMT);
    }

    /** Today's date on the exchange, unformatted. */
    public static LocalDate todayDate() {
        return LocalDate.now(IST);
    }

    public static int currentYear() {
        return LocalDate.now(IST).getYear();
    }

    public static String generateTicketId() {
        return "TKT-" + LocalDate.now(IST).format(DateTimeFormatter.ofPattern("yyyyMMdd")) +
               "-" + (int)(Math.random() * 9000 + 1000);
    }

    public static long daysBetween(String from, String to) {
        try {
            LocalDate d1 = LocalDate.parse(from, D_FMT);
            LocalDate d2 = LocalDate.parse(to,   D_FMT);
            return java.time.temporal.ChronoUnit.DAYS.between(d1, d2);
        } catch (Exception e) {
            return 0;
        }
    }
}
