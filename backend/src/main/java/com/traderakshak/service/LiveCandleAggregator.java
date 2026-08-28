package com.traderakshak.service;

import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds 5-minute candles from the live tick feed and stores them as they complete.
 *
 * <p>Until now the only writer of {@code broker_market_candles} was the historical backfill, so
 * today's bars did not exist until someone ran a fetch. Ranking, the 20-day volume average and the
 * Historical Trade Analysis therefore always lagged a day behind the market.
 *
 * <p>Ticks arrive continuously; this keeps one open bar per instrument per five-minute bucket in
 * memory and writes a bucket out once it can no longer receive ticks. Nothing is written mid-bucket,
 * so a stored bar is always final.
 *
 * <h3>Two things worth knowing about the data</h3>
 * <ul>
 *   <li><b>Volume is cumulative in the feed.</b> Dhan reports total traded volume for the day, not
 *       per tick, so a bar's volume is the difference between the last and first cumulative reading
 *       inside the bucket. The very first bar after a restart has no earlier reading to subtract
 *       from, so its volume is left at 0 rather than being inflated by the whole day's total.</li>
 *   <li><b>These bars are provisional.</b> They are stamped {@code source='LIVE'} and written with
 *       an upsert on the same unique key the backfill uses, so a later backfill overwrites them with
 *       the broker's official values. Tick-built bars will not always agree with Dhan to the paisa.</li>
 * </ul>
 */
@Service
public class LiveCandleAggregator {

    private static final Logger log = LoggerFactory.getLogger(LiveCandleAggregator.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter CANDLE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Bars are only built inside the trading session; ticks outside it are ignored. */
    private static final LocalTime SESSION_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);

    private final EntityManager em;
    private final PlatformTransactionManager transactionManager;

    @Value("${livecandle.enabled:true}")
    private boolean enabled;

    @Value("${livecandle.interval.minutes:5}")
    private int intervalMinutes;

    /** When on, a detected gap asks the broker for today's candles instead of only reporting it. */
    @Value("${livecandle.autoCatchUp:true}")
    private boolean autoCatchUp;

    /** Open bars, keyed by instrument + bucket start. */
    private final Map<String, Bar> openBars = new ConcurrentHashMap<>();
    /** Last cumulative volume seen per instrument, so a bar's own volume can be derived. */
    private final Map<String, Double> lastCumulativeVolume = new ConcurrentHashMap<>();

    private final AtomicLong barsWritten = new AtomicLong();
    private final AtomicLong ticksApplied = new AtomicLong();

    /** Lazily resolved: the sync service reaches the live feed, which reaches this. Avoids a cycle. */
    private final ObjectProvider<StartupSyncService> syncProvider;

    /** Wall-clock of the previous watchdog run, used to notice the machine having slept. */
    private volatile long lastWatchdogMillis = System.currentTimeMillis();
    /** Wall-clock of the last tick folded, used to notice a silent feed. */
    private volatile long lastTickMillis = 0L;
    /** Plain-language state of today's recording, surfaced by {@link #stats()}. */
    private volatile String healthMessage = "Waiting for the market to open.";
    private volatile String healthLevel = "IDLE";

    public LiveCandleAggregator(EntityManager em, PlatformTransactionManager transactionManager,
                                ObjectProvider<StartupSyncService> syncProvider) {
        this.em = em;
        this.transactionManager = transactionManager;
        this.syncProvider = syncProvider;
    }

    /**
     * Watches for the three ways today's recording can go wrong, and says which one happened.
     *
     * <ul>
     *   <li><b>The machine slept.</b> Detected by this task's own clock jumping further than its
     *       interval - ticks stop arriving while suspended, so bars simply never form.</li>
     *   <li><b>The feed went quiet</b> while the market is open: socket dropped, or token expired.</li>
     *   <li><b>Today's bars are short</b> of what the session should have produced by now.</li>
     * </ul>
     *
     * <p>Each case reports plainly and, when configured to, asks the broker for the missing candles
     * rather than leaving a hole nobody notices until a ranking looks wrong.
     */
    @Scheduled(fixedDelay = 120000, initialDelay = 90000)
    public void watchdog() {
        if (!enabled) {
            healthLevel = "OFF";
            healthMessage = "Live candle recording is switched off.";
            return;
        }

        long now = System.currentTimeMillis();
        long sinceLastRun = now - lastWatchdogMillis;
        lastWatchdogMillis = now;

        ZonedDateTime ist = ZonedDateTime.now(IST);
        LocalTime clock = ist.toLocalTime();
        boolean weekday = ist.getDayOfWeek().getValue() <= 5;
        boolean inSession = weekday && !clock.isBefore(SESSION_OPEN) && !clock.isAfter(SESSION_CLOSE);

        // A run arriving far later than its own interval means the process was suspended.
        boolean slept = sinceLastRun > 300000L;

        if (!weekday) {
            healthLevel = "IDLE";
            healthMessage = "Market closed (weekend). No candles are expected.";
            return;
        }
        if (clock.isBefore(SESSION_OPEN)) {
            healthLevel = "IDLE";
            healthMessage = "Before market open. Recording starts at 09:15.";
            return;
        }

        Coverage cov = coverageToday(ist);

        if (slept) {
            healthLevel = "RECOVERING";
            healthMessage = "This machine was asleep or suspended for " + (sinceLastRun / 60000)
                + " minute(s), so no ticks were received in that time. " + describe(cov)
                + " " + requestCatchUp("machine resumed");
            log.warn("[LiveCandles] {}", healthMessage);
            return;
        }

        if (inSession && lastTickMillis > 0 && now - lastTickMillis > 300000L) {
            healthLevel = "DISCONNECTED";
            healthMessage = "No market ticks for " + ((now - lastTickMillis) / 60000)
                + " minute(s) while the market is open - the broker feed looks disconnected. "
                + describe(cov) + " Check the Broker screen; the token may have expired.";
            log.warn("[LiveCandles] {}", healthMessage);
            return;
        }

        if (inSession && lastTickMillis == 0) {
            healthLevel = "DISCONNECTED";
            healthMessage = "Market is open but no ticks have arrived yet. " + describe(cov);
            return;
        }

        if (cov.missing > 0 && cov.expected > 0) {
            healthLevel = "PARTIAL";
            healthMessage = describe(cov)
                + (inSession ? "" : " " + requestCatchUp("session ended with gaps"));
            return;
        }

        healthLevel = "OK";
        healthMessage = (inSession ? "Recording live. " : "Session complete. ") + describe(cov);
    }

    private String describe(Coverage c) {
        if (c.expected <= 0) return "No candles are expected yet today.";
        if (c.stored == 0) {
            return "No 5-minute candles stored for today yet (about " + c.expected + " expected by now).";
        }
        if (c.missing <= 0) return "Today's candles are complete: " + c.expected + " intervals recorded.";
        return "Today's candles are partial: " + c.stored + " of " + c.expected
             + " intervals recorded, " + c.missing + " missing.";
    }

    private String requestCatchUp(String reason) {
        if (!autoCatchUp) {
            return "Automatic catch-up is off; fetch today from Data Warehouse to fill the gap.";
        }
        StartupSyncService sync = syncProvider.getIfAvailable();
        if (sync == null) return "Catch-up is unavailable right now.";
        return sync.catchUpTodayIntraday(reason);
    }

    /** Distinct 5-minute intervals stored today, against what the session should have produced. */
    private Coverage coverageToday(ZonedDateTime ist) {
        Coverage c = new Coverage();
        try {
            LocalTime now = ist.toLocalTime();
            LocalTime upTo = now.isAfter(SESSION_CLOSE) ? SESSION_CLOSE : now;
            long minutes = java.time.Duration.between(SESSION_OPEN, upTo).toMinutes();
            c.expected = (int) Math.max(0, minutes / Math.max(1, intervalMinutes));

            Object n = em.createNativeQuery(
                "SELECT COUNT(DISTINCT candle_time) FROM broker_market_candles "
                + "WHERE trade_date = ? AND UPPER(COALESCE(candle_mode,'')) = 'INTRADAY'")
                .setParameter(1, ist.toLocalDate().toString()).getSingleResult();
            c.stored = n == null ? 0 : ((Number) n).intValue();
            c.missing = Math.max(0, c.expected - c.stored);
        } catch (Exception e) {
            log.debug("[LiveCandles] Coverage check failed: {}", e.getMessage());
        }
        return c;
    }

    private static final class Coverage { int expected, stored, missing; }

    /**
     * Folds one tick into its bucket. Called on the feed thread, so it does no I/O - the write
     * happens later on the scheduler.
     */
    public void onTick(String symbol, String tradingSymbol, String securityId, String exchangeSegment,
                       double ltp, double cumulativeVolume) {
        if (!enabled || ltp <= 0 || securityId == null || securityId.isBlank()) return;

        ZonedDateTime now = ZonedDateTime.now(IST);
        LocalTime t = now.toLocalTime();
        if (t.isBefore(SESSION_OPEN) || t.isAfter(SESSION_CLOSE)) return;

        LocalDateTime bucket = bucketStart(now.toLocalDateTime());
        String instrumentKey = exchangeSegment + ":" + securityId;
        String barKey = instrumentKey + "@" + bucket;

        // Volume for this bar is the growth in the day's cumulative figure while the bar was open.
        double barVolume = 0;
        if (cumulativeVolume > 0) {
            Double prev = lastCumulativeVolume.put(instrumentKey, cumulativeVolume);
            if (prev != null && cumulativeVolume >= prev) barVolume = cumulativeVolume - prev;
        }

        final double addVolume = barVolume;
        openBars.compute(barKey, (k, bar) -> {
            if (bar == null) {
                return new Bar(symbol, tradingSymbol, securityId, exchangeSegment, bucket, ltp, addVolume);
            }
            bar.accept(ltp, addVolume);
            return bar;
        });
        ticksApplied.incrementAndGet();
        lastTickMillis = System.currentTimeMillis();
    }

    /**
     * Writes out every bucket that can no longer receive ticks. Runs a minute after each boundary
     * so a tick arriving slightly late still lands in the right bar.
     */
    @Scheduled(fixedDelayString = "${livecandle.flush.interval.ms:60000}", initialDelay = 45000)
    public void flushCompletedBars() {
        if (!enabled || openBars.isEmpty()) return;

        LocalDateTime cutoff = bucketStart(LocalDateTime.now(IST));
        List<Bar> ready = new ArrayList<>();
        for (String key : new ArrayList<>(openBars.keySet())) {
            Bar bar = openBars.get(key);
            if (bar != null && bar.bucket.isBefore(cutoff)) {
                Bar removed = openBars.remove(key);   // remove first so a late tick starts a new bar
                if (removed != null) ready.add(removed);
            }
        }
        if (ready.isEmpty()) return;

        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                for (Bar bar : ready) persist(bar);
            });
            long total = barsWritten.addAndGet(ready.size());
            log.info("[LiveCandles] Stored {} completed {}-minute bar(s) ({} total today, {} ticks folded).",
                ready.size(), intervalMinutes, total, ticksApplied.get());
        } catch (Exception e) {
            // Put them back rather than lose the bars; the next flush retries.
            for (Bar bar : ready) openBars.putIfAbsent(bar.key(), bar);
            log.warn("[LiveCandles] Could not store {} bar(s), will retry: {}", ready.size(), e.getMessage());
        }
    }

    /** Clears in-memory state after the close so a new session starts clean. */
    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void endOfSessionFlush() {
        if (!enabled) return;
        List<Bar> remaining = new ArrayList<>(openBars.values());
        openBars.clear();
        if (!remaining.isEmpty()) {
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    for (Bar bar : remaining) persist(bar);
                });
                log.info("[LiveCandles] Session close: stored {} final bar(s).", remaining.size());
            } catch (Exception e) {
                log.warn("[LiveCandles] Session-close store failed: {}", e.getMessage());
            }
        }
        lastCumulativeVolume.clear();
    }

    /**
     * Upsert on the same unique key the backfill uses, so a later historical fetch replaces these
     * provisional bars with the broker's official values instead of colliding with them.
     */
    private void persist(Bar bar) {
        String candleTime = bar.bucket.format(CANDLE_TS);
        String tradeDate = bar.bucket.toLocalDate().toString();
        em.createNativeQuery(
            "INSERT INTO broker_market_candles " +
            "(broker_name,broker_security_id,exchange,exchange_segment,segment,instrument_type," +
            " symbol,trading_symbol,candle_mode,interval_key,interval_minutes," +
            " candle_time,trade_date,open,high,low,close,volume,total_volume,source,data_type) " +
            "VALUES ('dhan',?,'NSE',?,'EQUITY','EQUITY',?,?,'INTRADAY',?,?,?,?,?,?,?,?,?,?,'LIVE','Live 5-minute candles') " +
            "ON CONFLICT (broker_name,broker_security_id,exchange_segment,candle_mode,interval_key,candle_time) " +
            "DO UPDATE SET high=GREATEST(broker_market_candles.high,EXCLUDED.high)," +
            "              low=LEAST(broker_market_candles.low,EXCLUDED.low)," +
            "              close=EXCLUDED.close," +
            "              volume=EXCLUDED.volume, total_volume=EXCLUDED.total_volume")
            .setParameter(1, bar.securityId)
            .setParameter(2, bar.exchangeSegment)
            .setParameter(3, bar.symbol)
            .setParameter(4, bar.tradingSymbol)
            .setParameter(5, intervalMinutes)
            .setParameter(6, intervalMinutes)
            .setParameter(7, candleTime)
            .setParameter(8, tradeDate)
            .setParameter(9, bar.open)
            .setParameter(10, bar.high)
            .setParameter(11, bar.low)
            .setParameter(12, bar.close)
            .setParameter(13, bar.volume)
            .setParameter(14, bar.volume)
            .executeUpdate();
    }

    /** Floors a timestamp to the start of its interval bucket. */
    private LocalDateTime bucketStart(LocalDateTime ts) {
        int step = intervalMinutes <= 0 ? 5 : intervalMinutes;
        return ts.withSecond(0).withNano(0).withMinute((ts.getMinute() / step) * step);
    }

    /** Snapshot for health/diagnostics. */
    public Map<String, Object> stats() {
        return Map.of(
            "enabled", enabled,
            "interval_minutes", intervalMinutes,
            "open_bars", openBars.size(),
            "bars_written", barsWritten.get(),
            "ticks_folded", ticksApplied.get(),
            "health", healthLevel,
            "message", healthMessage);
    }

    /** One bar being built. Guarded by ConcurrentHashMap.compute, so mutation is single-threaded. */
    private static final class Bar {
        final String symbol, tradingSymbol, securityId, exchangeSegment;
        final LocalDateTime bucket;
        final double open;
        double high, low, close, volume;

        Bar(String symbol, String tradingSymbol, String securityId, String exchangeSegment,
            LocalDateTime bucket, double price, double volume) {
            this.symbol = symbol;
            this.tradingSymbol = tradingSymbol;
            this.securityId = securityId;
            this.exchangeSegment = exchangeSegment;
            this.bucket = bucket;
            this.open = price;
            this.high = price;
            this.low = price;
            this.close = price;
            this.volume = volume;
        }

        void accept(double price, double addVolume) {
            if (price > high) high = price;
            if (price < low) low = price;
            close = price;
            volume += addVolume;
        }

        String key() { return exchangeSegment + ":" + securityId + "@" + bucket; }
    }
}
