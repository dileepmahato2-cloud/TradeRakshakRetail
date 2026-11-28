package com.traderakshak.service;

import com.traderakshak.entity.User;
import com.traderakshak.repository.UserRepository;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Startup Catch-Up Synchronization (Phase 1).
 *
 * Fires AFTER the web server is up (ApplicationReadyEvent) so the UI is already
 * reachable, then runs the whole sync on a background thread — the request
 * threads are never touched and startup never blocks. Every step is guarded so a
 * failure here can never destabilize the application.
 *
 * Flow: detect market status -> DB-only gap scan of the last N trading days ->
 * refresh current prices (connection-gated) -> trigger historical backfill for
 * only the missing days -> checkpoint to sync_state -> push progress over SSE.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StartupSyncService {

    private final AppConfigService appConfig;
    private final MarketCalendarService calendar;
    private final MarketDataGapDetector gapDetector;
    private final SyncStatusHolder status;
    private final LivePriceBroadcaster broadcaster;
    private final StockTrackerService stockTracker;
    private final IndicatorWarmupService indicatorWarmup;
    private final DhanHistoricalBackfillService historical;
    private final UserRepository userRepository;
    private final PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "startup-sync");
        t.setDaemon(true);
        return t;
    });

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            // Default OFF. This used to fetch on every single start: a gap scan, then Dhan quote
            // calls, then a full ranking - repeated on each restart even when nothing had changed,
            // competing with any backfill already running and adding minutes before the screen was
            // trustworthy. Scheduled work still covers the real need: the post-close daily fetch at
            // 17:00 and the per-minute price refresh during market hours.
            //
            // Set sync.enabled=true in Application Settings to bring it back.
            if (!appConfig.getBoolean("sync.enabled", false)) {
                log.info("[Sync] Startup fetch is off (sync.enabled=false). Scheduled jobs still run.");
                return;
            }
            // Never block startup: hand off to a background worker and return.
            executor.submit(this::runSafely);
        } catch (Exception e) {
            log.warn("[Sync] Could not schedule startup sync: {}", e.getMessage());
        }
    }

    private void runSafely() {
        if (!status.tryStart()) {
            log.info("[Sync] A synchronization run is already in progress; skipping.");
            return;
        }
        try {
            run();
        } catch (Throwable t) {
            log.error("[Sync] Startup synchronization failed: {}", t.getMessage(), t);
            status.set(SyncStatusHolder.Phase.ERROR, "Synchronization error: " + t.getMessage());
            push();
        } finally {
            status.finish();
        }
    }

    private void run() {
        long institutionId = appConfig.getInt("sync.systemInstitutionId", 1);
        int lookback = appConfig.getInt("sync.lookbackDays", 30);

        status.set(SyncStatusHolder.Phase.STARTED, "Starting data synchronization...");
        MarketCalendarService.MarketStatus ms = calendar.status();
        status.setMarketStatus(ms.name());
        push();

        if (ms == MarketCalendarService.MarketStatus.OPEN) {
            status.set(SyncStatusHolder.Phase.CHECKING, "Market is open. Verifying data and connecting to the live feed...");
        } else {
            status.set(SyncStatusHolder.Phase.CHECKING, "Market closed. Checking previous " + lookback + " trading days...");
        }
        push();

        // 1) DB-only gap scan (no API calls).
        MarketDataGapDetector.GapReport gaps = gapDetector.detectDailyGaps(institutionId, lookback, 0.5);
        status.setPlan(gaps.missingDays.size(), gaps.recordsMissing);
        if (gaps.missingDays.isEmpty()) {
            status.set(SyncStatusHolder.Phase.REFRESHING, "Data is up to date. Refreshing current prices...");
        } else {
            status.set(SyncStatusHolder.Phase.DOWNLOADING,
                gaps.missingDays.size() + " trading day(s) require synchronization. Starting background update...");
        }
        push();

        // 2) Refresh current prices (safe: internally connection-gated, short txns).
        try {
            stockTracker.refreshAllPrices(null, institutionId, "startup");
        } catch (Exception e) {
            log.warn("[Sync] Current-price refresh skipped: {}", e.getMessage());
        }

        // Phase 3: warm the indicator cache for the universe (background, from stored candles).
        try { indicatorWarmup.warmAsync(institutionId); } catch (Exception ignored) {}

        // 3) Backfill ONLY the missing daily history (opt-in default on, fully guarded).
        // Default OFF: a full historical backfill across the whole universe is
        // rate-limited by Dhan and can take many minutes. Gap detection still runs
        // and reports what is missing; enable sync.autoBackfillHistory=true (or run
        // the backfill from Data Warehouse) to fill history automatically.
        boolean autoHistory = appConfig.getBoolean("sync.autoBackfillHistory", false);
        if (!gaps.missingDays.isEmpty() && autoHistory) {
            triggerHistoricalBackfill(gaps);
        }

        // 4) Checkpoint + complete. Only claim coverage up to the day BEFORE the earliest
        // gap: stamping today's date while days are still missing made last_synced_date
        // report the universe as current when it was not, hiding the gap from anything
        // that keys off the checkpoint rather than off the candles themselves.
        String checkpoint = gaps.missingDays.isEmpty() || gaps.earliestMissing == null
            ? calendar.today().toString()
            : gaps.earliestMissing.minusDays(1).toString();
        writeSyncState("DAILY_CANDLE", "INST_" + institutionId,
            checkpoint, gaps.missingDays.isEmpty() ? "IDLE" : "PARTIAL",
            gaps.missingDays.size() + " day(s) flagged"
                + (gaps.missingDays.isEmpty() ? "" : "; set sync.autoBackfillHistory=true to fill automatically"));

        status.set(SyncStatusHolder.Phase.COMPLETED, gaps.missingDays.isEmpty()
            ? "Synchronization completed. Data is current."
            : "Background synchronization started for " + gaps.missingDays.size() + " trading day(s).");
        push();
    }

    /**
     * Fetches today's intraday candles from the broker, for use when the live feed could not
     * record them itself - the machine slept, the socket dropped, or the app was not running
     * during part of the session.
     *
     * <p>Returns a sentence describing what happened, suitable for showing to a person. It never
     * throws: a catch-up that cannot start must not take down whatever asked for it.
     */
    /**
     * Fetches the newest DAILY candles the broker will serve, once the session has settled.
     *
     * <p>Daily bars are published by the broker only after the close, so a job run during the day
     * silently fetches nothing for today. That is exactly what happened on 2026-08-27: a daily
     * backfill covering 22-27 August ran on the 27th and returned no bar for the 27th, so every
     * date-driven screen still believed the last trading day was the 26th the following morning.
     *
     * <p>Everything downstream keys off daily candles - which trading days exist, previous closes,
     * 20-day volume, the chart bars - so a missing daily bar silently freezes the whole application
     * one day in the past. Without this the gap reappears every single day.
     *
     * <p>17:00 IST: comfortably after the 15:30 close and the settlement that follows, while still
     * leaving the evening free to notice and retry if it fails.
     */
    /**
     * Widest gap the nightly fetch will close on its own, in days.
     *
     * <p>Generous because it is cheap - one 90-day window per instrument covers it either way -
     * but not unbounded, so the size of what a nightly job can start stays predictable.
     */
    private static final int MAX_EOD_GAP_DAYS = 30;

    /**
     * Newest daily session actually fetched from the broker, or null if there is none.
     *
     * <p>Deliberately excludes the bars {@link #saveTodayDailyCandlesFromIntraday()} folds from
     * intraday at 16:00. Those are written in DAILY mode on the same key, so counting them would
     * make this method report today as already stored an hour before the 17:00 fetch runs - and
     * the fetch would then conclude there was nothing to do and never replace the provisional
     * bar with the broker's official one.
     */
    private String newestBrokerDailySession() {
        try {
            Object v = em.createNativeQuery(
                "SELECT MAX(trade_date) FROM broker_market_candles " +
                "WHERE (candle_mode IS NULL OR UPPER(candle_mode)='DAILY') " +
                "AND COALESCE(data_type,'') <> ?")
                .setParameter(1, DERIVED_DAILY_TYPE)
                .getSingleResult();
            if (v == null) return null;
            String s = String.valueOf(v).trim();
            return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
        } catch (Exception e) {
            log.debug("[Sync] Newest stored daily session lookup failed: {}", e.getMessage());
            return null;
        }
    }

    // Moved from 17:00 to 08:10, because automatic outbound calls are now confined to
    // 08:00-16:00 IST and a 17:00 job would never run.
    //
    // Functionally the same fetch. It asks for the last COMPLETED trading day, and Dhan does not
    // publish same-day EOD (sync.eod.same_day_available defaults to false), so at 17:00 it was
    // already fetching the previous session rather than the one that had just closed. Running it
    // the next morning asks for exactly the same candles, a few hours later.
    @Scheduled(cron = "0 10 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void fetchLatestCompletedDailyCandles() {
        if (!calendar.isAutomationWindowOpen()) {
            log.debug("[Sync] Daily candle fetch skipped: {}.", calendar.automationWindowReason());
            return;
        }
        // This asked for target, and could therefore never fetch anything. startHistoricalBackfill
        // refuses any range starting after lastCompletedTradingDay(), and while
        // sync.eod.same_day_available is false - which is its default, because Dhan does not
        // publish same-day EOD - today is never a completed day. So the call threw "Nothing to
        // fetch" on every single run and the job silently did nothing from the day that guard
        // was added. Ask for the last day the broker will actually serve instead. Today's own
        // bar arrives on tomorrow's run; until then screens use the intraday fold in
        // StockTrackerService.gapSessionBars(), which already stands in for a session with no
        // daily bar and steps aside once the real one lands.
        java.time.LocalDate to = calendar.lastCompletedTradingDay();
        java.time.LocalDate from = to;

        // Fetch the GAP, not just the last completed day.
        //
        // This asked for exactly one date, so a day the application was not running for was
        // never fetched by anything, ever - the next evening's run asked only for that next
        // evening's day and moved on. Measured on 2026-11-16: the application was down from the
        // 11th to the 16th, daily candles stopped at the 9th, and the 11th, 12th and 13th were
        // simply absent. Nothing reported it, because each night's run had done exactly what it
        // was written to do.
        //
        // The cost of widening this is close to zero. Since monthlyBatches() moved to fixed
        // 90-day windows, a four-day range and a one-day range are both a single request per
        // instrument - the gap fill is the same size as the job it replaces.
        //
        // Those holes do not stay harmless either. tradingDaysWithData() counts a date as a
        // session if EITHER feed has anything on it, so the 11th still looked like a session on
        // the strength of 394 live-feed intraday bars while holding no daily candle at all. The
        // Previous Top 10 panels take their as-of date from that list, landed on the 11th, found
        // nothing to reconstruct from, and went blank.
        String newest = newestBrokerDailySession();
        if (newest != null) {
            try {
                java.time.LocalDate next = java.time.LocalDate.parse(newest).plusDays(1);
                if (next.isBefore(from)) from = next;
            } catch (Exception e) {
                log.debug("[Sync] Could not read newest stored daily session '{}': {}", newest, e.getMessage());
            }
        }

        // Bound a long outage so a machine off for a year does not silently queue a year of
        // history from a nightly job. Anything older than this is a deliberate backfill, run
        // from Data Warehouse where its size is visible before it starts.
        java.time.LocalDate floor = to.minusDays(MAX_EOD_GAP_DAYS);
        if (from.isBefore(floor)) {
            log.warn("[Sync] Daily candles are missing further back than {} days (newest stored: {}). "
                   + "Filling {} -> {} only; run a backfill from Data Warehouse for the rest.",
                MAX_EOD_GAP_DAYS, newest, floor, to);
            from = floor;
        }
        if (from.isAfter(to)) {
            log.debug("[Sync] Post-close daily fetch: nothing missing (newest stored {} covers {}).", newest, to);
            return;
        }

        String target = to.toString();
        try {
            User systemUser = userRepository.findAll().stream().findFirst().orElse(null);
            if (systemUser == null) return;
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("mode", "DAILY");
            req.put("fromDate", from.toString());
            req.put("toDate", target);
            req.put("exchanges", "NSE");
            req.put("segments", "NSE_EQ");
            req.put("instrumentFilter", "ES");
            req.put("scope", "ALL");
            req.put("continueOnMissingData", true);
            Map<String, Object> res = historical.startHistoricalBackfill(systemUser, req);
            log.info("[Sync] Post-close daily fetch for {} -> {} started: {}",
                from, target, res.getOrDefault("message", "queued"));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.toLowerCase(java.util.Locale.ROOT).contains("current activity")
                || msg.contains("DATA-ACTIVE")) {
                log.info("[Sync] Post-close daily fetch skipped for {} - another job is running.", target);
            } else {
                log.warn("[Sync] Post-close daily fetch for {} could not start: {}", target, msg);
            }
        }
    }

    @org.springframework.beans.factory.annotation.Value("${livecandle.interval.minutes:60}")
    private int liveCandleIntervalMinutes;

    /** Marks a daily bar this service folded itself, so it can be told apart and cleaned up. */
    private static final String DERIVED_DAILY_TYPE = "Daily Candles (derived from intraday)";

    /**
     * Folds today's intraday candles into one daily bar per symbol, right after the close.
     *
     * <p>Dhan does not publish a same-day daily candle: at 22:40 IST — seven hours after the
     * bell — every same-day request still returned DH-907 "no data present". That is why
     * {@link MarketCalendarService#lastCompletedTradingDay()} treats today as incomplete and why
     * {@link #fetchLatestCompletedDailyCandles()} asks for yesterday. The consequence is that
     * today has no daily bar until tomorrow evening, and everything keyed off daily candles —
     * previous close, 20-day volume, chart bars — sits a day behind for the whole evening.
     *
     * <p>The session's own intraday bars already contain the answer, so derive it instead of
     * waiting: first open, highest high, lowest low, last close, summed volume. The row is
     * written on the same unique key the backfill uses, so when the broker's official bar for
     * this date arrives tomorrow it upserts straight over this provisional one.
     *
     * <p>16:00 IST: after {@code LiveCandleAggregator.endOfSessionFlush()} at 15:45 has written
     * the last partial bar, and before the 17:00 broker fetch.
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void saveTodayDailyCandlesFromIntraday() {
        String today = calendar.today().toString();
        if (!calendar.isTradingDay(java.time.LocalDate.parse(today))) {
            log.debug("[Sync] {} is not a trading day; no daily fold.", today);
            return;
        }
        deriveDailyCandles(today, "post-close");
    }

    /**
     * Writes the derived daily bar for one date. Separate from the schedule so a catch-up or a
     * manual run can reuse it.
     *
     * @return how many daily rows were written, or -1 if the fold could not run.
     */
    public int deriveDailyCandles(String tradeDate, String reason) {
        try {
            return new TransactionTemplate(transactionManager).execute(tx -> {
                // GROUP BY the storage key, not the symbol: the same company can sit under two
                // Dhan security ids, and folding them together would blend two price series.
                int written = em.createNativeQuery(
                    "INSERT INTO broker_market_candles(" +
                    " broker_name,broker_security_id,exchange,exchange_segment,segment,instrument_type," +
                    " symbol,trading_symbol,candle_mode,interval_key,interval_minutes," +
                    " candle_time,trade_date,open,high,low,close,volume,total_volume,source,data_type," +
                    " created_at,updated_at) " +
                    "SELECT broker_name, broker_security_id, MAX(exchange), exchange_segment," +
                    "       MAX(segment), MAX(instrument_type), MAX(symbol), MAX(trading_symbol)," +
                    "       'DAILY', 0, NULL," +
                    "       ? , ?," +
                    "       (ARRAY_AGG(open  ORDER BY candle_time ASC ))[1]," +
                    "       MAX(high), MIN(low)," +
                    "       (ARRAY_AGG(close ORDER BY candle_time DESC))[1]," +
                    "       SUM(COALESCE(volume,0)), SUM(COALESCE(total_volume, volume, 0))," +
                    "       'DERIVED', ?, ?, ? " +
                    "FROM broker_market_candles " +
                    "WHERE UPPER(COALESCE(candle_mode,''))='INTRADAY' AND trade_date = ? " +
                    "  AND close IS NOT NULL AND close > 0 " +
                    "GROUP BY broker_name, broker_security_id, exchange_segment " +
                    "ON CONFLICT (broker_name,broker_security_id,exchange_segment,candle_mode,interval_key,candle_time) " +
                    "DO UPDATE SET open=EXCLUDED.open, high=EXCLUDED.high, low=EXCLUDED.low," +
                    "              close=EXCLUDED.close, volume=EXCLUDED.volume," +
                    "              total_volume=EXCLUDED.total_volume, updated_at=EXCLUDED.updated_at " +
                    // Never overwrite the broker's own daily bar with a folded one. Once the
                    // official candle for this date has landed it is the better record, and the
                    // fold would otherwise replace it every time this ran.
                    "WHERE broker_market_candles.source = 'DERIVED'")
                    .setParameter(1, tradeDate + "T00:00:00")
                    .setParameter(2, tradeDate)
                    .setParameter(3, DERIVED_DAILY_TYPE)
                    .setParameter(4, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()))
                    .setParameter(5, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()))
                    .setParameter(6, tradeDate)
                    .executeUpdate();

                // Dhan stamps its daily candles with its own time-of-day, so the official bar for
                // a date does not necessarily collide with the 00:00:00 key used above. Drop the
                // folded row once a real one exists for the same instrument and date, otherwise
                // that date ends up with two daily bars and every reader double-counts volume.
                int cleaned = em.createNativeQuery(
                    "DELETE FROM broker_market_candles d " +
                    "WHERE d.source='DERIVED' AND UPPER(COALESCE(d.candle_mode,''))='DAILY' " +
                    "  AND EXISTS (SELECT 1 FROM broker_market_candles o " +
                    "              WHERE o.broker_security_id = d.broker_security_id " +
                    "                AND o.exchange_segment   = d.exchange_segment " +
                    "                AND o.trade_date         = d.trade_date " +
                    "                AND UPPER(COALESCE(o.candle_mode,''))='DAILY' " +
                    "                AND COALESCE(o.source,'') <> 'DERIVED')")
                    .executeUpdate();

                log.info("[Sync] Daily fold for {} ({}): {} daily bar(s) written from intraday{}.",
                    tradeDate, reason, written,
                    cleaned > 0 ? ", " + cleaned + " superseded by the broker's own bar" : "");
                return written;
            });
        } catch (Exception e) {
            log.warn("[Sync] Daily fold for {} failed: {}", tradeDate, e.getMessage());
            return -1;
        }
    }

    public String catchUpTodayIntraday(String reason) {
        try {
            // Ask first, rather than starting and being refused.
            //
            // Only one backfill may run at a time - Dhan rate-limits per user - and a full
            // historical fetch runs for hours. The watchdog behind this call fires every two
            // minutes, so for the whole of that time every pass threw the concurrency refusal and
            // logged it at WARN: a three-line entry, twenty times an hour, for a system working
            // exactly as designed. That is the noise that hides a real fault.
            //
            // Nothing is lost by standing down. The running job is already importing candles, and
            // the watchdog keeps checking, so today's gap is picked up on the first pass after the
            // backfill finishes.
            if (historical.isAnyJobActive()) {
                log.debug("[Sync] Intraday catch-up ({}) deferred: a Dhan backfill is still running.", reason);
                return "A Dhan data fetch is already running. Today's missing candles will be "
                     + "fetched automatically once it finishes.";
            }
            User systemUser = resolveSystemUser();
            if (systemUser == null) {
                return "Cannot fetch today's missing candles: no connected Dhan broker. "
                     + "Connect your broker in the Broker screen, then try again.";
            }
            String today = calendar.today().toString();
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("mode", "INTRADAY");
            // The same width LiveCandleAggregator stores. Asking for 5 while the store holds
            // hourly bars would fill the day with a second, finer set of rows that no reader
            // filters apart, and the intraday fold sums volume across whatever it finds.
            req.put("intervalMinutes", liveCandleIntervalMinutes);
            req.put("fromDate", today);
            req.put("toDate", today);
            req.put("exchanges", "NSE");
            req.put("segments", "NSE_EQ");
            req.put("instrumentFilter", "ES");
            req.put("scope", "TRACKED");
            req.put("continueOnMissingData", true);
            Map<String, Object> res = historical.startHistoricalBackfill(systemUser, req);
            log.info("[Sync] Intraday catch-up job {} started for {} ({}).", res.get("jobId"), today, reason);
            return "Fetching today's missing 5-minute candles from the broker (" + reason + ").";
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.toLowerCase(java.util.Locale.ROOT).contains("current activity")
                || msg.contains("DATA-ACTIVE")) {
                return "A data fetch is already running; today's candles will be filled by it.";
            }
            // "Not a completed trading day yet" is the expected answer for most of the day, and
            // the watchdog asks every two minutes. Logged at WARN it produced hundreds of entries
            // a day for nothing being wrong, which is exactly the noise that hides a real fault.
            // Anything unexpected still warns.
            String lower = msg.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("not a completed trading day")
                || lower.contains("nothing to fetch")
                // A job that started between the check above and this call. Expected, not a fault.
                || msg.contains(DhanHistoricalBackfillService.ANOTHER_JOB_RUNNING_MARKER)) {
                log.debug("[Sync] Intraday catch-up not needed yet: {}", msg);
            } else {
                log.warn("[Sync] Intraday catch-up could not start: {}", msg);
            }
            return "Could not start the catch-up fetch: " + msg;
        }
    }

    private void triggerHistoricalBackfill(MarketDataGapDetector.GapReport gaps) {
        try {
            User systemUser = resolveSystemUser();
            if (systemUser == null) {
                log.info("[Sync] No connected Dhan broker found; historical backfill deferred until a broker is connected.");
                return;
            }
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("mode", "DAILY");
            req.put("fromDate", gaps.earliestMissing.toString());
            req.put("toDate", calendar.today().toString());
            req.put("exchanges", "NSE");
            req.put("segments", "NSE_EQ");
            req.put("instrumentFilter", "ES");
            // The startup sync exists to keep the ranked universe fresh, so it stays scoped to
            // uploaded stocks. Data Warehouse fetches default to the whole category instead;
            // without this the nightly sync would silently widen to every NSE equity.
            req.put("scope", "TRACKED");
            Map<String, Object> res = historical.startHistoricalBackfill(systemUser, req);
            log.info("[Sync] Historical daily backfill job {} started for {} -> {}.",
                res.get("jobId"), gaps.earliestMissing, calendar.today());
        } catch (Exception e) {
            // Never fail the whole sync because history couldn't start (e.g. no stock list yet).
            log.warn("[Sync] Historical backfill not started: {}", e.getMessage());
        }
    }

    /** The user who owns a verified, active Dhan credential (used as the backfill scope). */
    private User resolveSystemUser() {
        try {
            Object uid = em.createNativeQuery(
                "SELECT user_id FROM broker_credentials WHERE broker_name='dhan' AND is_active=1 AND is_verified=1 " +
                "AND access_token_enc IS NOT NULL AND access_token_enc<>'' ORDER BY id DESC LIMIT 1")
                .getSingleResult();
            if (uid == null) return null;
            long userId = ((Number) uid).longValue();
            return userRepository.findById(userId).orElse(null);
        } catch (Exception e) {
            return null; // no verified credential
        }
    }

    private void writeSyncState(String dataType, String scopeKey, String lastSyncedDate, String state, String detail) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(st ->
                em.createNativeQuery(
                    "INSERT INTO sync_state(data_type,scope_key,last_synced_date,last_run_at,status,detail) " +
                    "VALUES(?,?,?,?,?,?) " +
                    "ON CONFLICT(data_type,scope_key) DO UPDATE SET " +
                    "last_synced_date=EXCLUDED.last_synced_date, last_run_at=EXCLUDED.last_run_at, " +
                    "status=EXCLUDED.status, detail=EXCLUDED.detail")
                    .setParameter(1, dataType)
                    .setParameter(2, scopeKey)
                    .setParameter(3, lastSyncedDate)
                    .setParameter(4, DateUtil.now())
                    .setParameter(5, state)
                    .setParameter(6, detail)
                    .executeUpdate());
        } catch (Exception e) {
            log.debug("[Sync] sync_state write skipped: {}", e.getMessage());
        }
    }

    private void push() {
        try {
            broadcaster.sendSync(status.snapshot());
        } catch (Exception ignored) {}
    }

    /** Allow an admin/manual re-trigger from the controller. */
    public Map<String, Object> triggerManualRun() {
        if (status.isRunning()) {
            return Map.of("started", false, "message", "A synchronization run is already in progress.");
        }
        executor.submit(this::runSafely);
        return Map.of("started", true, "message", "Synchronization started.");
    }
}
