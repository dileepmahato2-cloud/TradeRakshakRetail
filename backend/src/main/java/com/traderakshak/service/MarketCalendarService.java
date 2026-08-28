package com.traderakshak.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for market status and the trading calendar.
 *
 * Holidays come from the {@code market.holidays} application setting (comma /
 * newline / space separated {@code yyyy-MM-dd} dates). Session timings come from
 * {@code market.session.open} / {@code market.session.close} (defaults 09:15 /
 * 15:30 IST). Everything is holiday- and weekend-aware.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketCalendarService {

    public enum MarketStatus { OPEN, PRE_OPEN, CLOSED, WEEKEND, HOLIDAY }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AppConfigService appConfig;
    private final MarketDataCache cache;

    public ZoneId zone() { return IST; }

    public LocalDate today() { return LocalDate.now(IST); }

    /** Parsed set of configured holiday dates (defensive against bad input). */
    public Set<LocalDate> holidays() {
        Set<LocalDate> cached = cache.get("market.holidays");
        if (cached != null) return cached;
        Set<LocalDate> out = new HashSet<>();
        String raw = appConfig.getString("market.holidays", "");
        if (raw != null && !raw.isBlank()) {
            for (String token : raw.split("[,;\\s]+")) {
                String t = token.trim();
                if (t.isEmpty()) continue;
                try { out.add(LocalDate.parse(t, ISO)); } catch (Exception ignored) {}
            }
        }
        cache.put("market.holidays", out, 60000L);
        return out;
    }

    public boolean isHoliday(LocalDate date) {
        return holidays().contains(date);
    }

    public boolean isWeekend(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    /** A trading day = weekday that is not a configured holiday. */
    public boolean isTradingDay(LocalDate date) {
        return !isWeekend(date) && !isHoliday(date);
    }

    private LocalTime sessionOpen() {
        return parseTime(appConfig.getString("market.session.open", "09:15"), LocalTime.of(9, 15));
    }

    private LocalTime sessionClose() {
        return parseTime(appConfig.getString("market.session.close", "15:30"), LocalTime.of(15, 30));
    }

    private LocalTime parseTime(String value, LocalTime fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalTime.parse(value.trim()); } catch (Exception e) { return fallback; }
    }

    /** Current market status in IST. */
    public MarketStatus status() {
        return status(ZonedDateTime.now(IST));
    }

    public MarketStatus status(ZonedDateTime nowIst) {
        LocalDate date = nowIst.toLocalDate();
        if (isWeekend(date)) return MarketStatus.WEEKEND;
        if (isHoliday(date)) return MarketStatus.HOLIDAY;
        LocalTime now = nowIst.toLocalTime();
        LocalTime open = sessionOpen();
        LocalTime close = sessionClose();
        LocalTime preOpenStart = open.minusMinutes(15);
        if (!now.isBefore(open) && !now.isAfter(close)) return MarketStatus.OPEN;
        if (!now.isBefore(preOpenStart) && now.isBefore(open)) return MarketStatus.PRE_OPEN;
        return MarketStatus.CLOSED;
    }

    public boolean isOpen() {
        return status() == MarketStatus.OPEN;
    }

    /**
     * The most recent COMPLETED trading session. Daily/EOD candle data only
     * exists once a session has closed, so this is the newest date worth
     * syncing. Today counts only if it is a trading day AND the market has
     * already closed; otherwise we fall back to the most recent prior
     * trading day. Weekends/holidays are skipped automatically.
     */
    public LocalDate lastCompletedTradingDay() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        LocalDate d = now.toLocalDate();
        // The closing bell does not mean the EOD daily candle exists yet. Observed on Dhan:
        // at 22:40 IST — seven hours after close — every same-day daily-candle request still
        // returned DH-907 "no data present", while the previous session fetched fine. Treating
        // today as complete made the gap scan flag a day that cannot be fetched, so every
        // startup queued a backfill that 400'd on all ~1,200 symbols and filled the log.
        // Flip sync.eod.same_day_available to true if the broker ever serves same-day EOD.
        boolean sameDayEod = appConfig.getBoolean("sync.eod.same_day_available", false);
        boolean todayComplete = sameDayEod
            && isTradingDay(d) && !now.toLocalTime().isBefore(sessionClose());
        if (!todayComplete) d = d.minusDays(1);
        int guard = 0;
        while (!isTradingDay(d) && guard < 40) { d = d.minusDays(1); guard++; }
        return d;
    }

    /** All trading days in [from, to] inclusive. */
    public List<LocalDate> tradingDays(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        if (from == null || to == null || from.isAfter(to)) return out;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isTradingDay(d)) out.add(d);
        }
        return out;
    }

    /**
     * The last {@code n} trading days ending today (or the most recent completed
     * session if today is not a trading day), oldest first.
     */
    public List<LocalDate> previousNTradingDays(int n) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = today();
        int guard = 0;
        while (out.size() < n && guard < n * 4 + 20) {
            if (isTradingDay(d)) out.add(d);
            d = d.minusDays(1);
            guard++;
        }
        java.util.Collections.reverse(out);
        return out;
    }
}
