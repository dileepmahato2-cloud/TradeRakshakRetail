package com.traderakshak.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects missing market data by comparing the expected trading calendar against
 * what is already stored in {@code broker_market_candles} (DAILY mode). Pure
 * DB reads — no API calls — so it is cheap and safe to run at startup.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataGapDetector {

    private final MarketCalendarService calendar;

    @PersistenceContext
    private EntityManager em;

    /** Result of a gap scan. */
    public static class GapReport {
        public final List<LocalDate> missingDays = new ArrayList<>();
        public int universeSize = 0;
        public int recordsMissing = 0;
        public LocalDate earliestMissing;
        public LocalDate latestMissing;
    }

    /**
     * Scan the last {@code lookbackDays} trading days for the given institution.
     * A trading day counts as "missing/incomplete" when the number of symbols
     * with a stored DAILY candle for that day is below {@code coverageThreshold}
     * of the active universe (default 0.5).
     */
    public GapReport detectDailyGaps(Long institutionId, int lookbackDays, double coverageThreshold) {
        GapReport report = new GapReport();
        // Only completed trading sessions have data. Exclude today when the market
        // is still open / EOD data is not yet published, and skip weekends/holidays.
        LocalDate lastComplete = calendar.lastCompletedTradingDay();
        List<LocalDate> expected = new ArrayList<>();
        for (LocalDate d : calendar.previousNTradingDays(lookbackDays)) {
            if (!d.isAfter(lastComplete)) expected.add(d);
        }
        if (expected.isEmpty()) return report;

        report.universeSize = countUniverse(institutionId);
        if (report.universeSize == 0) return report; // nothing uploaded yet

        LocalDate minDay = expected.get(0);
        Map<String, Integer> coverageByDate = coverageByDate(minDay.toString());

        int needed = Math.max(1, (int) Math.floor(report.universeSize * coverageThreshold));
        for (LocalDate day : expected) {
            int have = coverageByDate.getOrDefault(day.toString(), 0);
            if (have < needed) {
                report.missingDays.add(day);
                report.recordsMissing += Math.max(0, report.universeSize - have);
                if (report.earliestMissing == null) report.earliestMissing = day;
                report.latestMissing = day;
            }
        }
        log.info("[Sync] Gap scan inst={} universe={} lookback={} -> {} day(s) need sync ({} records)",
            institutionId, report.universeSize, lookbackDays, report.missingDays.size(), report.recordsMissing);
        return report;
    }

    private int countUniverse(Long institutionId) {
        try {
            Object c = em.createNativeQuery(
                "SELECT COUNT(*) FROM tracked_stocks WHERE institution_id=? AND is_active=1")
                .setParameter(1, institutionId).getSingleResult();
            return c instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) {
            log.debug("[Sync] universe count failed: {}", e.getMessage());
            return 0;
        }
    }

    /** date(text) -> distinct symbol count of DAILY candles on/after minDay. */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> coverageByDate(String minDayIso) {
        Map<String, Integer> out = new HashMap<>();
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT trade_date, COUNT(DISTINCT symbol) FROM broker_market_candles " +
                "WHERE candle_mode='DAILY' AND trade_date IS NOT NULL AND trade_date >= ? " +
                "GROUP BY trade_date")
                .setParameter(1, minDayIso)
                .getResultList();
            for (Object[] r : rows) {
                String date = r[0] == null ? "" : String.valueOf(r[0]).trim();
                if (date.length() >= 10) date = date.substring(0, 10); // normalize 'yyyy-MM-dd...'
                int cnt = r[1] instanceof Number n ? n.intValue() : 0;
                out.merge(date, cnt, Integer::sum);
            }
        } catch (Exception e) {
            log.debug("[Sync] coverage query failed: {}", e.getMessage());
        }
        return out;
    }
}
