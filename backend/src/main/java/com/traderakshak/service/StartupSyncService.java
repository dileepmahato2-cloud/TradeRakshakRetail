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
     * Fetches today's DAILY candles once the session has settled.
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
    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Kolkata")
    public void fetchTodaysDailyCandles() {
        String today = DateUtil.today();
        try {
            User systemUser = userRepository.findAll().stream().findFirst().orElse(null);
            if (systemUser == null) return;
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("mode", "DAILY");
            req.put("fromDate", today);
            req.put("toDate", today);
            req.put("exchanges", "NSE");
            req.put("segments", "NSE_EQ");
            req.put("instrumentFilter", "ES");
            req.put("scope", "ALL");
            req.put("continueOnMissingData", true);
            Map<String, Object> res = historical.startHistoricalBackfill(systemUser, req);
            log.info("[Sync] Post-close daily fetch for {} started: {}",
                today, res.getOrDefault("message", "queued"));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.toLowerCase(java.util.Locale.ROOT).contains("current activity")
                || msg.contains("DATA-ACTIVE")) {
                log.info("[Sync] Post-close daily fetch skipped for {} - another job is running.", today);
            } else {
                log.warn("[Sync] Post-close daily fetch for {} could not start: {}", today, msg);
            }
        }
    }

    public String catchUpTodayIntraday(String reason) {
        try {
            User systemUser = resolveSystemUser();
            if (systemUser == null) {
                return "Cannot fetch today's missing candles: no connected Dhan broker. "
                     + "Connect your broker in the Broker screen, then try again.";
            }
            String today = calendar.today().toString();
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("mode", "INTRADAY");
            req.put("intervalMinutes", 5);
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
                || lower.contains("nothing to fetch")) {
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
