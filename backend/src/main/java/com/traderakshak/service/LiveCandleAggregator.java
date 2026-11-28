package com.traderakshak.service;

import com.traderakshak.util.DateUtil;
import jakarta.annotation.PreDestroy;
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
 * Builds fixed-interval candles from the live tick feed and stores them as they complete.
 *
 * <p>The bar width is {@code livecandle.interval.minutes} (60). It is not a free number: every
 * price lookup that reads these bars seeks forward to the first bar at or after a configured
 * time, so a bar must start exactly on the times the Stock Upload section buys and sells at.
 *
 * <p>Until now the only writer of {@code broker_market_candles} was the historical backfill, so
 * today's bars did not exist until someone ran a fetch. Ranking, the 20-day volume average and the
 * Historical Trade Analysis therefore always lagged a day behind the market.
 *
 * <p>Ticks arrive continuously; this keeps one open bar per instrument per bucket in memory and
 * writes a bucket out once it can no longer receive ticks. Nothing is written mid-bucket, so a
 * stored bar is always final. The wider the bar, the more unflushed ticks are held - see
 * {@link #flushOnShutdown()}, which is why STOP.bat must stop this process gracefully.
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
    /** Slack between a bucket closing and flushCompleted() storing it. See coverageToday(). */
    private static final long WRITE_GRACE_MINUTES = 2;

    private final EntityManager em;
    private final PlatformTransactionManager transactionManager;
    private final AppConfigService appConfig;

    @Value("${livecandle.enabled:true}")
    private boolean enabled;

    @Value("${livecandle.interval.minutes:60}")
    private int intervalMinutes;

    /** When on, a detected gap asks the broker for today's candles instead of only reporting it. */
    @Value("${livecandle.autoCatchUp:true}")
    private boolean autoCatchUp;

    /**
     * Write the session's daily candle after the close.
     *
     * <p>On by default. Without it a live session leaves intraday bars and no daily bar, and
     * every screen that reads daily bars - which is all of them - cannot see that session until
     * somebody runs a historical fetch by hand.
     */
    @Value("${livecandle.dailyClose.enabled:true}")
    private boolean dailyCloseEnabled;

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
                                ObjectProvider<StartupSyncService> syncProvider,
                                AppConfigService appConfig) {
        this.em = em;
        this.transactionManager = transactionManager;
        this.syncProvider = syncProvider;
        this.appConfig = appConfig;
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
                + describe(cov) + " Check the Broker screen; the token may have expired. "
                + catchUpThrottled("feed disconnected mid-session");
            log.warn("[LiveCandles] {}", healthMessage);
            return;
        }

        if (inSession && lastTickMillis == 0) {
            // The feed subscribed but has never delivered a usable tick.
            //
            // This branch used to set the health field and return - no log, no recovery - so the
            // one failure that actually strands a whole session was the one nobody could see. On
            // 7 Sep 2026 the socket reported SUBSCRIPTION_SUCCESS at 09:15:07 and then went
            // silent; broker_market_candles held zero rows for the day, and every screen built
            // from candles - the bar chart above all - simply stopped at the previous session
            // while live prices carried on updating from the REST quote poll. Nothing said why.
            //
            // Say it, and fetch the day from the broker so the master table still gets it.
            healthLevel = "DISCONNECTED";
            healthMessage = "Market is open but no ticks have arrived yet. " + describe(cov)
                + " " + catchUpThrottled("no ticks since subscription");
            log.warn("[LiveCandles] {}", healthMessage);
            return;
        }

        if (cov.missing > 0 && cov.expected > 0) {
            healthLevel = "PARTIAL";
            healthMessage = describe(cov)
                // Throttled, not raw. This branch is the normal state of every evening - the
                // session has ended and some bars are missing - and the watchdog reaches it every
                // two minutes until the gap is filled. Calling requestCatchUp directly bypassed
                // the cooldown that exists for exactly this, so every pass tried to queue another
                // fetch. Throttled it is four attempts an hour instead of thirty, which is
                // already more often than a broker fetch can usefully be retried.
                + (inSession ? "" : " " + catchUpThrottled("session ended with gaps"));
            return;
        }

        healthLevel = "OK";
        healthMessage = (inSession ? "Recording live. " : "Session complete. ") + describe(cov);
    }

    private String describe(Coverage c) {
        if (c.expected <= 0) return "No candles are expected yet today.";
        if (c.stored == 0) {
            return "No " + intervalMinutes + "-minute candles stored for today yet (about "
                 + c.expected + " expected by now).";
        }
        if (c.missing <= 0) return "Today's candles are complete: " + c.expected + " intervals recorded.";
        return "Today's candles are partial: " + c.stored + " of " + c.expected
             + " intervals recorded, " + c.missing + " missing.";
    }

    /**
     * Catch-up, at most once every fifteen minutes.
     *
     * <p>The watchdog runs every two minutes, and a backfill takes far longer than that. Without
     * a cooldown a quiet feed would queue a fresh job on every pass.
     */
    private long lastCatchUpMillis = 0L;
    private static final long CATCH_UP_COOLDOWN_MS = 15 * 60_000L;

    private String catchUpThrottled(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastCatchUpMillis < CATCH_UP_COOLDOWN_MS) {
            return "A catch-up was requested recently; waiting for it to finish.";
        }
        lastCatchUpMillis = now;
        return requestCatchUp(reason);
    }

    private String requestCatchUp(String reason) {
        if (!autoCatchUp) {
            return "Automatic catch-up is off; fetch today from Data Warehouse to fill the gap.";
        }
        StartupSyncService sync = syncProvider.getIfAvailable();
        if (sync == null) return "Catch-up is unavailable right now.";
        return sync.catchUpTodayIntraday(reason);
    }

    /**
     * Distinct intervals stored today, against what the session should have produced.
     *
     * <p>Filtered on {@code interval_minutes}. Without that filter the count mixed every interval
     * present for the day, so a day holding both 5-minute and hourly rows reported far more bars
     * than the session has buckets and a genuine gap could never show up as missing.
     */
    private Coverage coverageToday(ZonedDateTime ist) {
        Coverage c = new Coverage();
        try {
            LocalTime now = ist.toLocalTime();
            LocalTime upTo = now.isAfter(SESSION_CLOSE) ? SESSION_CLOSE : now;
            // Buckets are anchored at SESSION_OPEN, so the count is how many have CLOSED by now -
            // less a grace period, because closing a bucket and storing it are not the same instant.
            // flushCompleted() runs on its own schedule, so for up to a minute after a bucket closes
            // the bar is legitimately still in memory. Counting it as expected during that window
            // made the health badge read PARTIAL once per bar for no reason; at a 5-minute width
            // that was a twelfth of the session, at an hour it would be every hour on the hour.
            long minutes = java.time.Duration.between(SESSION_OPEN, upTo).toMinutes() - WRITE_GRACE_MINUTES;
            c.expected = (int) Math.max(0, minutes / Math.max(1, intervalMinutes));

            Object n = em.createNativeQuery(
                "SELECT COUNT(DISTINCT candle_time) FROM broker_market_candles "
                + "WHERE trade_date = ? AND UPPER(COALESCE(candle_mode,'')) = 'INTRADAY' "
                + "AND COALESCE(interval_minutes, 0) = ?")
                .setParameter(1, ist.toLocalDate().toString())
                .setParameter(2, intervalMinutes).getSingleResult();
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
     * Writes out every bar still being built, on the way down.
     *
     * <p>An open bar lives only in {@link #openBars} until its bucket closes, so stopping the
     * application mid-bucket loses it. At a 5-minute width that cost at most five minutes of
     * ticks and nobody noticed. At 60 it is up to 59 minutes for every tracked symbol - stopping
     * at 14:50 would have discarded the whole 14:15 bar. Spring runs this on orderly shutdown,
     * which is why STOP.bat now asks the process to close before it forces it: a {@code taskkill /F}
     * reaches no shutdown hook, and whatever is open at that moment is simply gone.
     *
     * <p>Partial by definition - the bucket had not finished - but the upsert is keyed on
     * candle_time, so when the session resumes or a backfill runs, the full bar replaces this one.
     */
    @PreDestroy
    public void flushOnShutdown() {
        if (!enabled) return;
        List<Bar> remaining = new ArrayList<>(openBars.values());
        openBars.clear();
        if (remaining.isEmpty()) return;
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                for (Bar bar : remaining) persist(bar);
            });
            log.info("[LiveCandles] Shutdown: wrote {} partial {}-minute bar(s) before exit.",
                remaining.size(), intervalMinutes);
        } catch (Exception e) {
            log.warn("[LiveCandles] Shutdown flush failed, {} bar(s) lost: {}",
                remaining.size(), e.getMessage());
        }
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
            "VALUES ('dhan',?,'NSE',?,'EQUITY','EQUITY',?,?,'INTRADAY',?,?,?,?,?,?,?,?,?,?,'LIVE',?) " +
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
            .setParameter(15, "Live " + intervalMinutes + "-minute candles")
            .executeUpdate();
    }

    /**
     * Floors a timestamp to the start of its interval bucket, anchored at {@link #SESSION_OPEN}.
     *
     * <p>This used to floor on the minute-of-hour: {@code withMinute((getMinute()/step)*step)}.
     * At a 5-minute step that happened to agree with the exchange, because 09:15 is itself a
     * multiple of 5. At 60 it does not - it produces 09:00, 10:00, 11:00, while the session opens
     * at 09:15 and Dhan's own 60-minute candles are stamped 09:15, 10:15 ... 15:15. Two costs,
     * both silent: our bars would no longer share a candle_time with the broker's, so the upsert
     * key would stop matching and every day would hold two parallel sets of hourly rows instead
     * of one; and the price lookups seek FORWARD to the first bar at or after a time, so a 15:15
     * buy would find no bar at all (the last :00 bucket starts at 15:00, before it) and every
     * trade would be skipped. Anchoring on the session open makes both exact.
     */
    LocalDateTime bucketStart(LocalDateTime ts) {   // package-private: see LiveCandleBucketTest
        int step = intervalMinutes <= 0 ? 60 : intervalMinutes;
        LocalDateTime open = ts.toLocalDate().atTime(SESSION_OPEN);
        if (ts.isBefore(open)) return open;
        long since = java.time.Duration.between(open, ts).toMinutes();
        return open.plusMinutes((since / step) * step);
    }

    /** Snapshot for health/diagnostics. */
    public Map<String, Object> stats() {
        return Map.of(
            "enabled", enabled,
            "interval_minutes", intervalMinutes,
            "bucket_anchor", SESSION_OPEN.toString(),
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
    // ══ End-of-day daily candle ═════════════════════════════════════════════
    //
    // Nothing wrote a DAILY candle for today. The only writer of daily bars was the historical
    // backfill, so a session existed as intraday bars and nothing else until somebody ran a
    // fetch - and if nobody did, that session simply had no daily candle, ever.
    //
    // Everything downstream reads daily bars: the entry tests, the 20-session volume average,
    // the previous close, the charts. So a live session the platform had watched tick by tick
    // was still unscreenable the next morning. The read-time fold that papers over this
    // (StockTrackerService.gapBars) rebuilds the same bar on every request, holds it in memory
    // only, and cannot be backfilled against or audited.
    //
    // This closes the loop: once the session is over, the day's intraday bars are aggregated
    // into one daily candle and stored. Written with source LIVE_EOD and the same upsert key
    // the backfill uses, so an official fetch later overwrites it with the broker's own values -
    // these are a stand-in until that happens, never a replacement for it.

    /**
     * Fold today's intraday bars into one daily candle per symbol, after the close.
     *
     * <p>Runs every ten minutes from 15:40 to 18:00 IST on weekdays rather than once, because a
     * single shot at a fixed minute is one restart away from being missed for that day and a
     * missed day is exactly what this exists to prevent. The write is an upsert, so repeating it
     * costs nothing and corrects itself if late ticks landed after the first run.
     */
    @Scheduled(cron = "0 40/10 15-18 * * MON-FRI", zone = "Asia/Kolkata")
    public void writeDailyCloseCandles() {
        if (!dailyCloseEnabled) return;
        try {
            String today = LocalDate.now(IST).toString();
            int written = writeDailyCandlesFor(today);
            if (written > 0) {
                log.info("[EodCandle] Daily candle stored for {} symbol(s) on {}", written, today);
            }
        } catch (Exception e) {
            log.warn("[EodCandle] Daily close write failed: {}", e.getMessage());
        }
    }

    /**
     * Aggregate one session's intraday bars into a daily candle per symbol.
     *
     * <p>Open is the first bar's open, close the last bar's close, high and low the extremes,
     * volume the sum - the same arithmetic a broker applies, over the bars this process built
     * itself. Returns how many symbols were written.
     *
     * <p>Grouped by bar width as well as symbol, and only one width is kept: a day holding both
     * 60- and 120-minute bars would otherwise report roughly twice the volume it traded. The
     * width currently being written wins; failing that, the finest the session has.
     */
    public int writeDailyCandlesFor(String tradeDate) {
        if (tradeDate == null || tradeDate.isBlank()) return 0;
        try {
            return new TransactionTemplate(transactionManager).execute(status ->
                em.createNativeQuery(
                "INSERT INTO broker_market_candles "
              + "(broker_name,broker_security_id,exchange,exchange_segment,segment,instrument_type,"
              + " symbol,trading_symbol,candle_mode,interval_key,interval_minutes,"
              + " candle_time,trade_date,open,high,low,close,volume,total_volume,source,data_type) "
              + "SELECT 'dhan', z.security_id, 'NSE', z.exchange_segment, 'EQUITY', 'EQUITY', "
              + "       z.symbol, z.trading_symbol, 'DAILY', 'DAY', 1440, "
              + "       z.trade_date || 'T15:30:00', z.trade_date, "
              + "       z.open, z.high, z.low, z.close, z.vol, z.vol, 'LIVE_EOD', 'CANDLE' "
              + "FROM ("
              + "  SELECT broker_security_id AS security_id, exchange_segment, symbol, "
              + "         trading_symbol, trade_date, "
              + "         (ARRAY_AGG(open  ORDER BY candle_time ASC))[1]  AS open, "
              + "         (ARRAY_AGG(close ORDER BY candle_time DESC))[1] AS close, "
              + "         MAX(high) AS high, MIN(low) AS low, "
              + "         SUM(COALESCE(total_volume, volume)) AS vol, "
              + "         ROW_NUMBER() OVER (PARTITION BY broker_security_id, exchange_segment "
              + "           ORDER BY (COALESCE(interval_minutes,0) = :pref) DESC, "
              + "                    COALESCE(interval_minutes,0) ASC) AS pick "
              + "  FROM broker_market_candles "
              + "  WHERE UPPER(COALESCE(candle_mode,'')) = 'INTRADAY' "
              + "    AND trade_date = :d AND close IS NOT NULL AND close > 0 "
              + "  GROUP BY broker_security_id, exchange_segment, symbol, trading_symbol, "
              + "           trade_date, COALESCE(interval_minutes,0)"
              + ") z WHERE z.pick = 1 "
              // Never overwrite the broker's own daily bar with one built from ticks. A row
              // already here from the backfill is the authority; this only fills a hole.
              + "ON CONFLICT (broker_name,broker_security_id,exchange_segment,candle_mode,"
              + "interval_key,candle_time) DO NOTHING")
                .setParameter("d", tradeDate)
                .setParameter("pref", intervalMinutes)
                .executeUpdate());
        } catch (Exception e) {
            log.warn("[EodCandle] Could not write daily candles for {}: {}", tradeDate, e.getMessage());
            return 0;
        }
    }

    /**
     * Warn when a configured trade time does not land on a bar start.
     *
     * <p>The class note says the bar width "is not a free number", because every intraday price
     * lookup seeks the first bar at or AFTER the time it is given. A time that is not a bar
     * start therefore does not fail - it silently resolves to the next one, and the chart shows
     * a trade entered or exited at a different moment from the one configured. With 60-minute
     * bars every :15 time happened to work; at 120 minutes only every other one does.
     *
     * <p>Reported at startup, once, naming the times that do not fit and what they resolve to.
     */
    @jakarta.annotation.PostConstruct
    public void checkTradeTimesAlignToBars() {
        if (!enabled || intervalMinutes <= 0) return;
        try {
            java.time.LocalTime open = java.time.LocalTime.of(9, 15);
            java.util.List<String> starts = new ArrayList<>();
            for (java.time.LocalTime t = open; !t.isAfter(java.time.LocalTime.of(15, 30));
                 t = t.plusMinutes(intervalMinutes)) {
                starts.add(t.toString().substring(0, 5));
            }
            log.info("[LiveCandles] {}-minute bars start at {}", intervalMinutes,
                     String.join(", ", starts));

            for (String[] cfg : new String[][]{
                    {"top10.trade.buy_time",  appConfig.getString("top10.trade.buy_time", "15:15")},
                    {"top10.trade.sell_time", appConfig.getString("top10.trade.sell_time", "15:15")},
                    {"volbreakout.finalize.time",
                     appConfig.getString("volbreakout.finalize.time", "15:15")}}) {
                String value = cfg[1];
                if (value == null || value.length() != 5 || value.charAt(2) != ':') continue;
                if (starts.contains(value)) continue;
                String lands = starts.stream().filter(x -> x.compareTo(value) >= 0)
                    .findFirst().orElse("after the close - no bar at all");
                log.warn("[LiveCandles] {} is {}, which is not a {}-minute bar start. "
                       + "A price lookup at that time resolves to {}. Either move the setting to "
                       + "a bar start or change livecandle.interval.minutes.",
                       cfg[0], value, intervalMinutes, lands);
            }
        } catch (Exception e) {
            log.debug("[LiveCandles] Trade-time alignment check skipped: {}", e.getMessage());
        }
    }

}
