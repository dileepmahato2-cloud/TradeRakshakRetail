package com.traderakshak.service.strategy;

import com.traderakshak.service.strategy.ast.Timeframe;
import com.traderakshak.service.strategy.ast.TimeframeAvailability;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Which bar widths the stored candles can actually answer a question on.
 *
 * <p>Measured, not configured. Every intraday candle in this database is 60-minute, which no
 * setting anywhere states - it is simply what was collected. A builder that offers 5-minute
 * conditions because a config file lists them produces rules that return an empty list, and an
 * empty list is indistinguishable on screen from a strategy nobody qualifies for.
 *
 * <p>Two separate facts are reported, because they answer different questions and conflating them
 * would mislead either way:
 *
 * <ul>
 *   <li><b>available</b> - a typical symbol holds enough bars of this width to measure an
 *       indicator over. This is what decides whether a rule can be written and backtested.</li>
 *   <li><b>current</b> - the width is as up to date as the daily feed. The hourly history here
 *       runs several sessions behind, which matters for a scan of today and not at all for a
 *       backtest, so it is said rather than folded into availability.</li>
 * </ul>
 *
 * <p>Weekly and monthly are available whenever daily bars are, because they are rolled up from
 * them rather than stored.
 */
@Slf4j
@Component
public class TimeframeCatalogue implements TimeframeAvailability {

    /** Availability is a fact about collected data, which changes only when a backfill runs. */
    private static final long CACHE_MS = 30 * 60 * 1000L;

    /**
     * Bars a typical symbol needs before a width is worth offering.
     *
     * <p>Measured as the mean per symbol, not as a total. A total is the wrong test and says so
     * clearly here: the last 120 days hold 4,696 hourly bars across 1,294 symbols, which passes
     * any plausible total threshold while giving each symbol under four bars - not enough to
     * compute anything. 200 is roughly the longest window the indicator engine offers.
     */
    private static final long MIN_BARS_PER_SYMBOL = 200L;

    public record Coverage(Timeframe timeframe, boolean available, boolean current, String reason,
                           long bars, long symbols, long sessions, long barsPerSymbol,
                           String first, String latest, Timeframe derivedFrom) {}

    @PersistenceContext private EntityManager em;

    private final AtomicReference<Map<Timeframe, Coverage>> cache = new AtomicReference<>();
    private final AtomicLong cachedAt = new AtomicLong(0L);
    private final AtomicBoolean measuring = new AtomicBoolean(false);

    @Override
    public boolean available(Timeframe tf) {
        Coverage c = snapshot().get(tf);
        return c != null && c.available();
    }

    @Override
    public String unavailableReason(Timeframe tf) {
        Coverage c = snapshot().get(tf);
        return c == null || c.reason() == null
            ? TimeframeAvailability.super.unavailableReason(tf) : c.reason();
    }

    /** Every width with what is behind it, for the builder's timeframe selector. */
    public List<Coverage> all() {
        return new ArrayList<>(snapshot().values());
    }

    /** Drops the cache, so a finished backfill is reflected without a restart. */
    public void invalidate() { cachedAt.set(0L); }

    /**
     * Measured once at startup, off the request path.
     *
     * <p>Counting distinct symbols across 2.2 million intraday rows takes about four seconds, and
     * the first person to open the strategy builder should not pay for it. Warming here means the
     * answer is already in hand by the time anyone asks, and a failure costs nothing: the next
     * caller simply measures it then.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        Thread t = new Thread(() -> {
            try {
                snapshot();
                Coverage daily = cache.get() == null ? null : cache.get().get(Timeframe.DAILY);
                log.info("[Timeframes] Candle coverage measured. Daily: {} bars over {} symbols.",
                    daily == null ? 0 : daily.bars(), daily == null ? 0 : daily.symbols());
            } catch (Exception e) {
                log.warn("[Timeframes] Startup measurement failed: {}", e.getMessage());
            }
        }, "timeframe-coverage-warm");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ measurement

    private Map<Timeframe, Coverage> snapshot() {
        Map<Timeframe, Coverage> have = cache.get();
        if (have != null && System.currentTimeMillis() - cachedAt.get() < CACHE_MS) return have;

        // One measurement at a time. Two requests arriving together on a cold cache would
        // otherwise both run a four-second scan to produce the same answer.
        if (!measuring.compareAndSet(false, true)) {
            return have != null ? have : fallback();
        }
        try {
            Map<Timeframe, Coverage> fresh = measure(have);
            cache.set(fresh);
            cachedAt.set(System.currentTimeMillis());
            return fresh;
        } finally {
            measuring.set(false);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Timeframe, Coverage> measure(Map<Timeframe, Coverage> previous) {
        Map<Integer, long[]> intraday = new LinkedHashMap<>();
        Map<Integer, String[]> intradayRange = new LinkedHashMap<>();
        long[] daily;
        String dailyFirst;
        String dailyLatest;

        try {
            // All of history, not a trailing window. A width is usable for a backtest over the
            // range it covers even if collection has since stopped, and how recent it is comes
            // back separately as the latest date rather than as a reason to hide it.
            List<Object[]> rows = em.createNativeQuery(
                "SELECT interval_minutes, COUNT(*), COUNT(DISTINCT symbol), " +
                "       COUNT(DISTINCT trade_date), MIN(trade_date), MAX(trade_date) " +
                "FROM broker_market_candles " +
                "WHERE UPPER(COALESCE(candle_mode,'')) = 'INTRADAY' " +
                "GROUP BY interval_minutes").getResultList();
            for (Object[] r : rows) {
                if (r[0] == null) continue;
                int mins = ((Number) r[0]).intValue();
                intraday.put(mins, new long[]{num(r[1]), num(r[2]), num(r[3])});
                intradayRange.put(mins, new String[]{str(r[4]), str(r[5])});
            }

            Object[] d = (Object[]) em.createNativeQuery(
                "SELECT COUNT(*), COUNT(DISTINCT symbol), COUNT(DISTINCT trade_date), " +
                "       MIN(trade_date), MAX(trade_date) " +
                "FROM broker_market_candles " +
                "WHERE UPPER(COALESCE(candle_mode,'')) = 'DAILY'").getSingleResult();
            daily = new long[]{num(d[0]), num(d[1]), num(d[2])};
            dailyFirst = str(d[3]);
            dailyLatest = str(d[4]);

        } catch (Exception e) {
            // Keeping the last good answer beats guessing. With no previous answer, report only
            // daily and its roll-ups - the widths this application cannot run at all without -
            // so the worst case is a warning on an intraday rule rather than a silent empty scan.
            log.warn("[Timeframes] Could not measure candle coverage: {}", e.getMessage());
            return previous != null ? previous : fallback();
        }

        Map<Timeframe, Coverage> out = new EnumMap<>(Timeframe.class);
        for (Timeframe tf : Timeframe.values()) {
            out.put(tf, tf.intraday()
                ? intradayCoverage(tf, intraday, intradayRange, dailyLatest)
                : dailyCoverage(tf, daily, dailyFirst, dailyLatest));
        }
        return out;
    }

    /** Package-private and static so the availability rule can be tested without a database. */
    static Coverage intradayCoverage(Timeframe tf, Map<Integer, long[]> counts,
                                     Map<Integer, String[]> ranges, String dailyLatest) {
        long[] c = counts.getOrDefault(tf.minutes(), new long[]{0L, 0L, 0L});
        String[] range = ranges.getOrDefault(tf.minutes(), new String[]{null, null});
        long perSymbol = c[1] > 0 ? c[0] / c[1] : 0L;
        boolean available = perSymbol >= MIN_BARS_PER_SYMBOL;
        boolean current = available && range[1] != null && dailyLatest != null
                          && range[1].compareTo(dailyLatest) >= 0;

        String reason;
        if (!available) {
            reason = shortfall(tf, c[0], c[1], perSymbol, counts.keySet());
        } else if (!current) {
            reason = tf.label() + " history runs to " + range[1] + ", while daily data runs to "
                   + dailyLatest + ". Rules on this width backtest over the covered range but a "
                   + "scan of today has nothing to read.";
        } else {
            reason = null;
        }
        return new Coverage(tf, available, current, reason,
                            c[0], c[1], c[2], perSymbol, range[0], range[1], null);
    }

    static Coverage dailyCoverage(Timeframe tf, long[] daily, String first, String latest) {
        long perSymbol = daily[1] > 0 ? daily[0] / daily[1] : 0L;
        boolean available = perSymbol >= MIN_BARS_PER_SYMBOL;
        String reason = available ? null
            : (tf.derived()
                ? "Not enough daily candles to aggregate into " + tf.label() + " bars - a typical "
                  + "symbol holds " + perSymbol + "."
                : "A typical symbol holds only " + perSymbol + " daily candles, which is too few "
                  + "to measure an indicator over.");
        return new Coverage(tf, available, available, reason,
                            daily[0], daily[1], daily[2], perSymbol, first, latest,
                            tf.derivedFrom());
    }

    /** Says what is there instead, which is the difference between a dead end and a next step. */
    private static String shortfall(Timeframe tf, long bars, long symbols, long perSymbol,
                                    Set<Integer> widthsPresent) {
        StringBuilder sb = new StringBuilder("No ").append(tf.label())
            .append(" candles are stored");
        if (bars > 0) {
            sb.append(" (").append(bars).append(" bars across ").append(symbols)
              .append(" symbols is about ").append(perSymbol)
              .append(" each, too few to measure an indicator on)");
        }
        sb.append(", so a condition on this timeframe cannot be measured.");

        List<String> have = new ArrayList<>();
        for (Timeframe other : Timeframe.values()) {
            if (other != tf && other.intraday() && widthsPresent.contains(other.minutes())) {
                have.add(other.label());
            }
        }
        if (!have.isEmpty()) {
            sb.append(" Intraday history is held at ").append(String.join(", ", have))
              .append(" only.");
        }
        return sb.toString();
    }

    static Map<Timeframe, Coverage> fallback() {
        Map<Timeframe, Coverage> out = new EnumMap<>(Timeframe.class);
        for (Timeframe tf : Timeframe.values()) {
            boolean ok = !tf.intraday();
            out.put(tf, new Coverage(tf, ok, ok,
                ok ? null : "Candle coverage could not be read, so intraday widths are reported "
                          + "as unavailable rather than assumed available.",
                0L, 0L, 0L, 0L, null, null, tf.derivedFrom()));
        }
        return out;
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
