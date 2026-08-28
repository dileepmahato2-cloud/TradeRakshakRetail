package com.traderakshak.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Utility mirrors Python's now() / today() / gen_user_id() helpers */
public final class DateUtil {

    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter D_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DateUtil() {}

    public static String now() {
        return LocalDateTime.now().format(DT_FMT);
    }

    public static String today() {
        return LocalDate.now().format(D_FMT);
    }

    public static int currentYear() {
        return LocalDate.now().getYear();
    }

    public static String generateTicketId() {
        return "TKT-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) +
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
