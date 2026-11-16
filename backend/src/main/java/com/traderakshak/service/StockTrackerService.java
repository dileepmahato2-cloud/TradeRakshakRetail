package com.traderakshak.service;

// =============================================================================
// StockTrackerService — TradeRakshak™ v11.6
//
// Complete Stock Upload & Performance Tracker engine.
//
// Responsibilities:
//   1. Parse Excel/CSV uploads, deduplicate by symbol (keep latest date)
//   2. Fetch base price (closing price on upload date) via Dhan API only
//   3. Auto-refresh current prices every 5 min during market hours
//   4. Compute return% = ((current - base) / base) * 100
//   5. Filter eligible stocks: return >= 0% AND return <= 8%
//   6. Run 13-factor configurable Sahi-style ranking engine
//   7. Generate Top-10 with sector diversification (max 2 per sector)
//   8. Store daily snapshots — never overwrite historical data
//   9. Generate return distribution curve data
//  10. Provide CSV/Excel export for eligible stocks and rankings
//
// Enterprise Policy:
//   • Ranking weights stored in tracker_ranking_config (Admin changes without code)
//   • All price operations are audit-logged in tracker_audit_log
//   • Backward compatible — no existing tables modified
//   • Impact reviewed: Dashboard, Screener, StockRankingEngine, SectorAI
// =============================================================================

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.service.TechnicalIndicatorEngine;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.math.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockTrackerService {

    private final TechnicalIndicatorEngine indicator;
    private final MarketCalendarService    marketCalendar;
    private final ObjectMapper             mapper;
    private final BlockingHttpService      http;
    private final DhanBrokerService        dhanBrokerService;
    private final PlatformTransactionManager transactionManager;
    private final AppConfigService          appConfig;
    private final LivePriceBroadcaster      liveBroadcaster;
    /** Append-only upload log; owns Mode A (latest per symbol) and Mode B (as-of date). */
    private final StockUploadHistoryService uploadHistory;

    @PersistenceContext
    private EntityManager em;

    // ── Dynamic configuration (read from AppConfigService) ───────────────────
    private double getEligibleMinReturn()    { return appConfig.getDouble("stock_upload.eligible_min_return", 0.0); }
    private double getEligibleMaxReturn()    { return appConfig.getDouble("stock_upload.eligible_max_return", 5.0); }
    private int    getMaxPerSector()         { return appConfig.getInt("top10.max_per_sector", 2); }
    private int    getTopN()                 { return appConfig.getInt("top10.count", 10); }

    /**
     * How many stocks the volume shortlist keeps - the RSI 45 section holds six.
     *
     * <p>Separate from top10.count, which is the ranked Top 10's size. These are two different
     * questions: how many the ranking engine promotes, and how many of a screen's matches are
     * worth holding. Tying them together would silently resize one when the other changed.
     */
    private int    getShortlistCount()       { return appConfig.getInt("shortlist.count", 6); }
    private String getDhanApi()              { return appConfig.getString("broker.dhan.api_endpoint", "https://api.dhan.co/v2"); }
    private long   getScreenRefreshGapMs()   { return appConfig.getLong("system.screen_refresh_gap_ms", 45_000L); }
    private long   getMarketOpenStaleSec()   { return appConfig.getLong("system.market_open_stale_sec", 60L); }
    private long   getMarketClosedStaleSec() { return appConfig.getLong("system.market_closed_stale_sec", 900L); }

    // ── Stock Upload automation config (all default to safe / current behavior) ──
    public boolean isTop10AutoRefreshEnabled()   { return appConfig.getBoolean("top10.auto_refresh.enabled", true); }
    public int     getTop10AutoRefreshInterval() { return appConfig.getInt("top10.auto_refresh.interval_sec", 300); }
    /** INTERVAL = run every N minutes while the market is open; FIXED = run at set clock times. */
    public String  getTop10RefreshMode()         { return appConfig.getString("top10.auto_refresh.mode", "INTERVAL"); }
    /** Comma-separated HH:mm list used when the mode is FIXED, e.g. "09:15,12:00,15:15". */
    public String  getTop10RefreshTimes()        { return appConfig.getString("top10.auto_refresh.times", "09:15,15:15"); }
    /** Lock one run a day as that day's official Top 10 - the list that becomes tomorrow's Previous Top 10. */
    public boolean isTop10LockEnabled()          { return appConfig.getBoolean("top10.lock.enabled", false); }
    // 15:15, not 15:20: the session's last ranking run is the one that becomes that day's
    // official Top 10, and it is also the price the Previous Top 10 buys at. Buy and sell then
    // both fall on the final run of their respective days, so a held position is measured at
    // the same point of the session on both sides.
    public String  getTop10LockTime()            { return appConfig.getString("top10.lock.time", "15:15"); }

    /**
     * Time of day the previous day's Top 10 is treated as sold when it was not sold manually.
     * Defaults to the lock time: a position opened at the 15:20 ranking and left alone is
     * closed at 15:20 the next trading day, so buy and sell are measured at the same point of
     * the session and the return is not distorted by intraday drift.
     */
    /**
     * Time of day the previous day's Top 10 is treated as bought. Defaults to the lock time, so
     * the buy price is the price at the ranking that produced the list rather than an arbitrary
     * moment of the session.
     */
    public String  getTradeBuyTime()             { return appConfig.getString("top10.trade.buy_time", getTop10LockTime()); }
    /**
     * Time of day a held position is closed, on the NEXT trading day after the buy.
     *
     * <p>15:00, fifteen minutes before the 15:15 buy that opens the following day's position, so
     * the two never contend for the same moment. "Next trading day" is taken from the sessions
     * that actually have data, so a Friday buy closes on Monday and a buy before a holiday
     * closes when the market next opens - the calendar is never assumed.
     */
    public String  getTradeSellTime()            { return appConfig.getString("top10.trade.sell_time", "10:15"); }
    /** Auto-refresh for the Previous Top 10 panel and the average-return bars beneath it. */
    public boolean isPrevTop10AutoRefreshEnabled() { return appConfig.getBoolean("prevtop10.auto_refresh.enabled", false); }
    public int     getPrevTop10AutoRefreshSec()    { return appConfig.getInt("prevtop10.auto_refresh.interval_sec", 300); }
    public boolean isPrevTop10AutoCycleEnabled() { return appConfig.getBoolean("prevtop10.auto_cycle.enabled", false); }
    public boolean isPrevTop10AutoBuyEnabled()   { return appConfig.getBoolean("prevtop10.auto_buy.enabled", false); }
    public boolean isPrevTop10AutoSellEnabled()  { return appConfig.getBoolean("prevtop10.auto_sell.enabled", false); }
    public String  getPrevTop10BuyTime()         { return appConfig.getString("prevtop10.buy_time", "15:15"); }
    public String  getPrevTop10SellTime()        { return appConfig.getString("prevtop10.sell_time", "10:15"); }
    public String  getPrevTop10ExecutionMode()   { return appConfig.getString("prevtop10.execution_mode", "PAPER"); }
    public boolean useRealPriceIfExecuted()      { return appConfig.getBoolean("prevtop10.use_real_price_if_executed", true); }
    private String getMarketHolidaysRaw()        { return appConfig.getString("market.holidays", ""); }

    /** All automation flags for the settings panel. */
    public Map<String, Object> getAutomationConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("top10_auto_refresh_enabled", isTop10AutoRefreshEnabled());
        m.put("top10_auto_refresh_interval_sec", getTop10AutoRefreshInterval());
        m.put("top10_auto_refresh_mode", getTop10RefreshMode());
        m.put("top10_auto_refresh_times", getTop10RefreshTimes());
        m.put("top10_lock_enabled", isTop10LockEnabled());
        m.put("top10_lock_time", getTop10LockTime());
        m.put("prevtop10_auto_refresh_enabled", isPrevTop10AutoRefreshEnabled());
        m.put("prevtop10_auto_refresh_interval_sec", getPrevTop10AutoRefreshSec());
        m.put("prevtop10_auto_cycle_enabled", isPrevTop10AutoCycleEnabled());
        m.put("prevtop10_auto_buy_enabled", isPrevTop10AutoBuyEnabled());
        m.put("prevtop10_auto_sell_enabled", isPrevTop10AutoSellEnabled());
        m.put("prevtop10_buy_time", getPrevTop10BuyTime());
        m.put("prevtop10_sell_time", getPrevTop10SellTime());
        m.put("prevtop10_execution_mode", getPrevTop10ExecutionMode());
        m.put("prevtop10_use_real_price_if_executed", useRealPriceIfExecuted());
        m.put("market_holidays", getMarketHolidaysRaw());
        return m;
    }

    /** Persist automation flags. Only whitelisted keys are accepted; LIVE mode requires explicit opt-in. */
    public Map<String, Object> saveAutomationConfig(Map<String, Object> updates, Long userId) {
        if (updates == null) updates = Map.of();
        Map<String, String[]> keyMap = new LinkedHashMap<>();
        keyMap.put("top10_auto_refresh_enabled",   new String[]{"top10.auto_refresh.enabled", "BOOLEAN"});
        keyMap.put("top10_auto_refresh_interval_sec", new String[]{"top10.auto_refresh.interval_sec", "INTEGER"});
        keyMap.put("top10_auto_refresh_mode",       new String[]{"top10.auto_refresh.mode", "STRING"});
        keyMap.put("top10_auto_refresh_times",      new String[]{"top10.auto_refresh.times", "STRING"});
        keyMap.put("top10_lock_enabled",            new String[]{"top10.lock.enabled", "BOOLEAN"});
        keyMap.put("top10_lock_time",               new String[]{"top10.lock.time", "STRING"});
        keyMap.put("prevtop10_auto_refresh_enabled",      new String[]{"prevtop10.auto_refresh.enabled", "BOOLEAN"});
        keyMap.put("prevtop10_auto_refresh_interval_sec", new String[]{"prevtop10.auto_refresh.interval_sec", "INTEGER"});
        keyMap.put("prevtop10_auto_cycle_enabled",  new String[]{"prevtop10.auto_cycle.enabled", "BOOLEAN"});
        keyMap.put("prevtop10_auto_buy_enabled",    new String[]{"prevtop10.auto_buy.enabled", "BOOLEAN"});
        keyMap.put("prevtop10_auto_sell_enabled",   new String[]{"prevtop10.auto_sell.enabled", "BOOLEAN"});
        keyMap.put("prevtop10_buy_time",            new String[]{"prevtop10.buy_time", "STRING"});
        keyMap.put("prevtop10_sell_time",           new String[]{"prevtop10.sell_time", "STRING"});
        keyMap.put("prevtop10_execution_mode",      new String[]{"prevtop10.execution_mode", "STRING"});
        keyMap.put("prevtop10_use_real_price_if_executed", new String[]{"prevtop10.use_real_price_if_executed", "BOOLEAN"});
        keyMap.put("market_holidays",               new String[]{"market.holidays", "STRING"});

        for (Map.Entry<String, String[]> e : keyMap.entrySet()) {
            if (!updates.containsKey(e.getKey())) continue;
            String val = String.valueOf(updates.get(e.getKey()));
            String cfgKey = e.getValue()[0];
            String dataType = e.getValue()[1];
            // Safety: execution mode may only be PAPER or LIVE; anything else falls back to PAPER
            if (cfgKey.equals("prevtop10.execution_mode")) {
                val = "LIVE".equalsIgnoreCase(val) ? "LIVE" : "PAPER";
            }
            if (cfgKey.equals("prevtop10.buy_time") || cfgKey.equals("prevtop10.sell_time")) {
                val = normalizeHhmm(val, cfgKey.endsWith("buy_time") ? "15:15" : "10:15");
            }
            if (cfgKey.equals("prevtop10.auto_refresh.interval_sec")) {
                int secs;
                try { secs = Integer.parseInt(val.trim()); } catch (Exception ex) { secs = 300; }
                val = String.valueOf(Math.max(30, Math.min(secs, 3600)));
            }
            if (cfgKey.equals("top10.lock.time")) {
                val = normalizeHhmm(val, "15:20");
            }
            if (cfgKey.equals("top10.auto_refresh.mode")) {
                val = "FIXED".equalsIgnoreCase(val) ? "FIXED" : "INTERVAL";
            }
            if (cfgKey.equals("top10.auto_refresh.times")) {
                // Keep only well-formed HH:mm entries so a typo cannot silently disable the schedule.
                List<String> ok = new ArrayList<>();
                for (String t : val.split(",")) {
                    String c = normalizeHhmm(t, "");
                    if (!c.isEmpty() && !ok.contains(c)) ok.add(c);
                }
                java.util.Collections.sort(ok);
                val = String.join(",", ok);
            }
            if (cfgKey.equals("top10.auto_refresh.interval_sec")) {
                // Floor of 60s: the scheduler ticks once a minute, so anything finer cannot fire.
                int secs;
                try { secs = Integer.parseInt(val.trim()); } catch (Exception ex) { secs = 300; }
                val = String.valueOf(Math.max(60, Math.min(secs, 6 * 3600)));
            }
            appConfig.setValue(cfgKey, val, "STOCK_UPLOAD_AUTOMATION", dataType, userId);
        }
        Map<String, Object> result = getAutomationConfig();
        result.put("success", true);
        return result;
    }

    /** Coerce a HH:mm string; returns fallback when invalid. */
    private String normalizeHhmm(String raw, String fallback) {
        if (raw == null) return fallback;
        String t = raw.trim();
        if (t.matches("^\\d{1,2}:\\d{2}$")) {
            String[] p = t.split(":");
            int h = Integer.parseInt(p[0]); int m = Integer.parseInt(p[1]);
            if (h >= 0 && h <= 23 && m >= 0 && m <= 59) return String.format("%02d:%02d", h, m);
        }
        return fallback;
    }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final Object screenRefreshLock = new Object();
    private volatile boolean screenRefreshRunning = false;
    private volatile long lastScreenRefreshStartedMs = 0L;
    // Global guard: only one price refresh may write tracked_stocks / tracked_stock_prices /
    // ranking tables at a time. Overlapping refreshes (scheduled + screen-load + auto cycle)
    // were contending on the same rows and index tuples → "lock timeout" (55P03).
    private final java.util.concurrent.atomic.AtomicBoolean priceRefreshInProgress =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // ── Thread pool for background processing ───────────────────────────────
    private final java.util.concurrent.ExecutorService uploadExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "StockUploadWorker");
            t.setDaemon(true);
            return t;
        });
    private final AtomicInteger activeUploadJobs = new AtomicInteger(0);

    // ── Short-lived price cache for bulk refresh (5-second TTL) ─────────────
    private volatile Map<String, Double> bulkPriceCache = Map.of();
    private volatile long bulkPriceCacheTs = 0L;
    private static final long BULK_PRICE_CACHE_TTL_MS = 5_000;
    private static final int DEFAULT_DB_FLUSH_SIZE = 100;

    private int getDbFlushSize() {
        try { return appConfig.getInt("upload.batch_flush_size", DEFAULT_DB_FLUSH_SIZE); }
        catch (Exception e) { return DEFAULT_DB_FLUSH_SIZE; }
    }

    // ── Background Job Tracking ─────────────────────────────────────────────
    private final ConcurrentHashMap<String, BackgroundJob> backgroundJobs = new ConcurrentHashMap<>();

    public static class BackgroundJob {
        public String jobId;
        public String fileName;
        public volatile String status; // RUNNING, COMPLETED, FAILED, CANCELLED
        public volatile int totalRecords;
        public volatile int processedRecords;
        public final AtomicInteger successRecords = new AtomicInteger(0);
        public final AtomicInteger failedRecords = new AtomicInteger(0);
        public final AtomicInteger skippedRecords = new AtomicInteger(0);
        public volatile int pendingRecords;
        public volatile double progressPercentage;
        public volatile String currentSymbol;
        public volatile String errorMessage;
        public String createdAt;
        public volatile String lastUpdatedAt;
        public Long institutionId;
        public volatile boolean cancelRequested = false;
        /** Set by Pause. The upload loop parks on this and keeps the job resumable. */
        public volatile boolean pauseRequested = false;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("job_id", jobId);
            m.put("file_name", fileName);
            m.put("status", status);
            m.put("total_records", totalRecords);
            m.put("processed_records", processedRecords);
            m.put("success_records", successRecords.get());
            m.put("failed_records", failedRecords.get());
            m.put("skipped_records", skippedRecords.get());
            m.put("pending_records", pendingRecords);
            m.put("progress_percentage", progressPercentage);
            m.put("current_symbol", currentSymbol);
            m.put("error_message", errorMessage);
            m.put("created_at", createdAt);
            m.put("last_updated_at", lastUpdatedAt);
            return m;
        }

        void updateProgress() {
            int success = successRecords.get();
            int failed = failedRecords.get();
            int skipped = skippedRecords.get();
            processedRecords = success + failed + skipped;
            pendingRecords = totalRecords - processedRecords;
            progressPercentage = totalRecords > 0 ? Math.round(processedRecords * 1000.0 / totalRecords) / 10.0 : 0;
            lastUpdatedAt = DateUtil.now();
        }
    }

    public List<Map<String, Object>> getBackgroundJobs(Long institutionId) {
        return backgroundJobs.values().stream()
            .filter(j -> institutionId.equals(j.institutionId))
            .sorted((a, b) -> b.createdAt.compareTo(a.createdAt))
            .limit(20)
            .map(BackgroundJob::toMap)
            .collect(Collectors.toList());
    }

    public Map<String, Object> getBackgroundJob(String jobId) {
        BackgroundJob job = backgroundJobs.get(jobId);
        return job != null ? job.toMap() : null;
    }

    /** Pauses a running upload. The worker parks; nothing is lost and Resume picks it up. */
    public boolean pauseBackgroundJob(String jobId) {
        BackgroundJob job = backgroundJobs.get(jobId);
        if (job != null && "RUNNING".equals(job.status)) {
            job.pauseRequested = true;
            job.lastUpdatedAt = DateUtil.now();
            return true;
        }
        return false;
    }

    public boolean resumeBackgroundJob(String jobId) {
        BackgroundJob job = backgroundJobs.get(jobId);
        if (job != null && job.pauseRequested && !job.cancelRequested) {
            job.pauseRequested = false;
            job.status = "RUNNING";
            job.lastUpdatedAt = DateUtil.now();
            return true;
        }
        return false;
    }

    public boolean cancelBackgroundJob(String jobId) {
        BackgroundJob job = backgroundJobs.get(jobId);
        if (job != null && ("RUNNING".equals(job.status) || "PAUSED".equals(job.status))) {
            job.cancelRequested = true;
            job.pauseRequested = false;      // let a parked worker fall through and stop
            job.status = "CANCELLED";
            job.lastUpdatedAt = DateUtil.now();
            return true;
        }
        return false;
    }

    // ── Dhan API centralized rate limiter ────────────────────────────────────
    private final AtomicLong dhanLastCallMs = new AtomicLong(0);
    private final AtomicInteger dhan429Count = new AtomicInteger(0);
    private static final long DHAN_MIN_GAP_MS = 1200;
    private static final long DHAN_429_BASE_BACKOFF_MS = 30_000;
    private static final int  DHAN_429_MAX_BACKOFF_MULTIPLIER = 4;

    private void dhanThrottle() {
        int backoffMultiplier = Math.min(dhan429Count.get(), DHAN_429_MAX_BACKOFF_MULTIPLIER);
        long minGap = backoffMultiplier > 0 ? DHAN_429_BASE_BACKOFF_MS * backoffMultiplier : DHAN_MIN_GAP_MS;
        long now = System.currentTimeMillis();
        long last = dhanLastCallMs.get();
        long wait = last + minGap - now;
        if (wait > 0) {
            sleepQuietly(wait);
        }
        dhanLastCallMs.set(System.currentTimeMillis());
    }

    private void dhanOn429() {
        int count = dhan429Count.incrementAndGet();
        long backoff = DHAN_429_BASE_BACKOFF_MS * Math.min(count, DHAN_429_MAX_BACKOFF_MULTIPLIER);
        log.warn("Dhan 429 rate limit hit (count={}). Backing off {}ms before next call.", count, backoff);
        sleepQuietly(backoff);
    }

    private void dhanOnSuccess() {
        dhan429Count.set(0);
    }

    // ── Return distribution buckets ──────────────────────────────────────────
    private static final double[][] BUCKETS = {
        {Double.NEGATIVE_INFINITY, -15},
        {-15, -10}, {-10, -5}, {-5, 0},
        {0, 5}, {5, 10}, {10, 15}, {15, 30},
        {30, Double.POSITIVE_INFINITY}
    };
    private static final String[] BUCKET_LABELS = {
        "Below -15%", "-15% to -10%", "-10% to -5%", "-5% to 0%",
        "0% to 5%", "5% to 10%", "10% to 15%", "15% to 30%", "Above 30%"
    };

    // ==========================================================================
    //  UPLOAD
    // ==========================================================================

    @Transactional
    public Map<String, Object> uploadStocks(MultipartFile file, Long userId, Long institutionId) {
        String uploadId  = "UPL-" + System.currentTimeMillis();
        String batchId   = generateBatchId(institutionId);
        String fileName  = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String fileType  = fileName.toLowerCase().endsWith(".csv") ? "csv" : "xlsx";
        String today     = DateUtil.today();

        // Insert upload record
        try {
            em.createNativeQuery(
                "INSERT INTO stock_uploads(upload_id,user_id,institution_id,file_name,file_type," +
                "status,upload_date,created_at,batch_id,source) VALUES(?,?,?,?,?,'processing',?,?,?,?)")
                .setParameter(1, uploadId).setParameter(2, userId)
                .setParameter(3, institutionId).setParameter(4, fileName)
                .setParameter(5, fileType).setParameter(6, today)
                .setParameter(7, DateUtil.now()).setParameter(8, batchId)
                .setParameter(9, fileType.equalsIgnoreCase("csv") ? "CSV Upload" : "Excel Upload")
                .executeUpdate();
        } catch (Exception e) { log.warn("Upload log insert: {}", e.getMessage()); }

        // Parse file
        List<Map<String, String>> rows;
        try {
            rows = "csv".equals(fileType)
                ? parseCsv(file.getInputStream())
                : parseExcel(file.getInputStream());
        } catch (Exception e) {
            updateUploadStatus(uploadId, "failed", "Parse error: " + e.getMessage());
            return Map.of("success", false, "error", "File parse failed: " + e.getMessage());
        }

        if (rows.isEmpty()) {
            updateUploadStatus(uploadId, "failed", "No valid rows found");
            return Map.of("success", false, "error", "No valid rows in file");
        }

        // ------------------------------------------------------------------ HISTORY
        // Every row is kept. The same symbol on three different dates is three
        // signals, not two duplicates, and the earlier two are what every
        // historical / backtest screen is built to read back. The only row that
        // is NOT stored is an exact repeat of one already present (same symbol,
        // date, market cap and sector), which carries no new information —
        // StockUploadHistoryService enforces that with ON CONFLICT DO NOTHING.
        UUID uploadBatchId = UUID.randomUUID();
        List<StockUploadHistoryService.UploadRow> historyRows = new ArrayList<>();
        int badDateCount = 0, blankSymbolCount = 0;

        for (Map<String, String> row : rows) {
            String sym = clean(row.get("symbol"));
            if (sym == null || sym.isBlank()) { blankSymbolCount++; continue; }
            sym = sym.toUpperCase().trim();

            // Strict on purpose: entry_date decides both the base price and which
            // appearance is a symbol's latest, so a date we cannot read is reported
            // rather than quietly stored as today.
            String entryDate = StockUploadHistoryService.normalizeDate(clean(row.get("date")));
            if (entryDate == null) { badDateCount++; continue; }

            String mcap   = clean(row.getOrDefault("marketcapname", row.getOrDefault("market_cap", "Unknown")));
            String sector = clean(row.getOrDefault("sector", "Unknown"));
            historyRows.add(new StockUploadHistoryService.UploadRow(sym, entryDate, mcap, sector));
        }

        StockUploadHistoryService.RecordResult recorded =
            uploadHistory.recordUpload(uploadBatchId, fileName, historyRows, userId, institutionId);

        // ------------------------------------------------------------------ MODE A PROJECTION
        // tracked_stocks is what every current-state screen reads, so it is
        // rebuilt from the history as "latest entry_date per symbol". Sourcing it
        // from the log rather than from this file's rows is what stops an upload
        // of an OLDER date from dragging a symbol backwards.
        int newCount  = recorded.inserted();
        int dupCount  = recorded.duplicates();
        int failCount = recorded.failed() + badDateCount + blankSymbolCount;
        List<String> symbolsNeedingPrice =
            uploadHistory.syncTrackedStocks(recorded.symbols(), uploadId, batchId, userId, institutionId);

        // Price the new history rows off whatever candles are already stored. The Dhan job below
        // only runs for symbols whose CURRENT row lost its base price, so an upload that adds only
        // older dates would otherwise leave those rows unpriced until the next recovery tick.
        uploadHistory.backfillBasePrices(institutionId);

        // Update upload record
        try {
            em.createNativeQuery(
                "UPDATE stock_uploads SET status='completed',total_rows=?,new_records=?," +
                "duplicate_records=?,failed_records=? WHERE upload_id=?")
                .setParameter(1, rows.size()).setParameter(2, newCount)
                .setParameter(3, dupCount).setParameter(4, failCount)
                .setParameter(5, uploadId).executeUpdate();
        } catch (Exception e) { log.warn("Upload status update: {}", e.getMessage()); }

        // Async: fetch base prices for newly uploaded symbols
        String jobId = null;
        if (!symbolsNeedingPrice.isEmpty()) {
            BackgroundJob job = new BackgroundJob();
            job.jobId = "JOB-" + System.currentTimeMillis();
            job.fileName = fileName;
            job.status = "RUNNING";
            job.totalRecords = symbolsNeedingPrice.size();
            job.createdAt = DateUtil.now();
            job.lastUpdatedAt = DateUtil.now();
            job.institutionId = institutionId;
            backgroundJobs.put(job.jobId, job);
            jobId = job.jobId;
            fetchBasePricesFromDhanAsync(symbolsNeedingPrice, userId, institutionId, job);
        }

        auditLog(institutionId, userId, "STOCK_UPLOAD",
            "upload", uploadId,
            Map.of("file", fileName, "total", rows.size(), "new", newCount, "dups", dupCount,
                   "upload_batch_id", uploadBatchId.toString()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("upload_id", uploadId);
        result.put("batch_id", batchId);
        result.put("upload_batch_id", uploadBatchId.toString());
        result.put("total_rows", rows.size());
        result.put("new_records", newCount);
        result.put("duplicates", dupCount);
        result.put("failed", failCount);
        // Broken out so a file with a bad Date column is visibly rejected rather
        // than silently landing on today's date.
        result.put("invalid_date_rows", badDateCount);
        result.put("blank_symbol_rows", blankSymbolCount);
        result.put("processed_symbols", symbolsNeedingPrice);
        if (jobId != null) result.put("job_id", jobId);
        result.put("message", "Upload successful. Fetching Dhan upload-date closing prices in background...");
        return result;
    }

    // ==========================================================================
    //  BASE PRICE FETCH (upload date closing price)
    // ==========================================================================

    public void fetchBasePricesFromDhanAsync(List<String> symbols, Long userId, Long institutionId) {
        fetchBasePricesFromDhanAsync(symbols, userId, institutionId, null);
    }

    public void fetchBasePricesFromDhanAsync(List<String> symbols, Long userId, Long institutionId, BackgroundJob job) {
        if (!activeUploadJobs.compareAndSet(0, 1)) {
            log.warn("Stock upload job already running — skipping duplicate request for {} symbols.", symbols.size());
            if (job != null) { job.status = "FAILED"; job.errorMessage = "Another upload job is already running."; job.updateProgress(); }
            return;
        }
        Set<String> processedSymbols = ConcurrentHashMap.newKeySet();
        uploadExecutor.submit(() -> {
            try {
                int savedUpdated = backfillBasePricesFromSavedMarketData(symbols, institutionId);
                if (job != null) { job.successRecords.addAndGet(savedUpdated); job.updateProgress(); }
                List<String> remainingSymbols = getSymbolsMissingBasePrice(institutionId).stream()
                    .filter(s -> symbols.stream().map(this::normalizeSymbol).anyMatch(n -> n.equals(normalizeSymbol(s))))
                    .toList();
                if (remainingSymbols.isEmpty()) {
                    log.info("Base price backfill completed from saved daily candles for {} symbols.", savedUpdated);
                    if (job != null) { job.status = "COMPLETED"; job.updateProgress(); }
                    refreshBatchQuotePrices(symbols, userId, institutionId);
                    return;
                }
                if (job != null) { job.totalRecords = savedUpdated + remainingSymbols.size(); job.updateProgress(); }

                // A historical backfill is already saturating Dhan's rate limit. Adding this job's
            // per-symbol history calls on top produced nothing but HTTP 429s from both - the
            // backfill on /charts/intraday and this on /charts/historical, each retrying and each
            // making the other worse. Stored candles have already been tried above; the broker
            // leg can wait for the next Refresh Prices.
            if (isHistoricalBackfillRunning()) {
                log.warn("[BasePrice] {} stock(s) still need a broker lookup, but a historical "
                       + "backfill is running and Dhan is rate limiting. Skipping the broker leg - "
                       + "press Refresh - Live Price once the backfill finishes.", remainingSymbols.size());
                if (job != null) { job.status = "COMPLETED"; job.errorMessage = "Deferred: backfill running"; job.updateProgress(); }
                return;
            }

            Optional<DhanBrokerService.Credential> credential = findVerifiedDhanCredential(userId, institutionId);
                if (credential.isEmpty()) {
                    for (String symbol : remainingSymbols) {
                        markBasePriceError(symbol, institutionId, "DHAN_ERROR: verified Dhan broker token required for base price.");
                        if (job != null) { job.skippedRecords.incrementAndGet(); job.updateProgress(); }
                    }
                    if (job != null) { job.status = "COMPLETED"; job.errorMessage = "No verified Dhan broker token"; job.updateProgress(); }
                    return;
                }

                Map<String, double[]> batchQuoteData = fetchBatchQuoteData(symbols, credential.get());

                List<String> ltpBatchSymbols = new ArrayList<>();
                List<Double> ltpBatchPrices = new ArrayList<>();
                for (Map.Entry<String, double[]> entry : batchQuoteData.entrySet()) {
                    double ltp = entry.getValue()[0];
                    if (ltp > 0) {
                        ltpBatchSymbols.add(entry.getKey());
                        ltpBatchPrices.add(ltp);
                    }
                }
                if (!ltpBatchSymbols.isEmpty()) {
                    int ltpUpdated = batchUpdateCurrentPrices(ltpBatchSymbols, ltpBatchPrices, institutionId);
                    log.info("Batch quote: {}/{} symbols got current price", ltpUpdated, symbols.size());
                } else if (!symbols.isEmpty()) {
                    // The silent branch. With no usable LTP this block simply did nothing and the
                    // refresh went on to report its timing as though it had updated prices, so
                    // "current price is not updating" had no corresponding line anywhere in the
                    // log. Current prices keep their previous values - which is why the screen
                    // showed a stale price rather than a blank one.
                    log.warn("[Refresh] No current price could be updated for any of {} stock(s): "
                           + "the broker returned no usable quote. Existing prices are left as "
                           + "they were, so the screen keeps showing the last good value.",
                        symbols.size());
                }

                Map<String, DhanInstrumentRef> refs = resolveDhanInstruments(remainingSymbols);
                List<String> batchSymbols = new ArrayList<>();
                List<Double> batchPrices = new ArrayList<>();
                List<String> batchSources = new ArrayList<>();
                int batchFlushSize = appConfig.getInt("upload.batch_flush_size", 500);
                long lastBackfillCheckMs = System.currentTimeMillis();

                for (String symbol : remainingSymbols) {
                    if (job != null && job.cancelRequested) {
                        log.info("Base price fetch cancelled by user at symbol {}", symbol);
                        break;
                    }
                    // Park while paused, still watching for a cancel so Pause then Cancel works.
                    while (job != null && job.pauseRequested && !job.cancelRequested) {
                        if (!"PAUSED".equals(job.status)) {
                            job.status = "PAUSED";
                            job.lastUpdatedAt = DateUtil.now();
                            log.info("Base price fetch paused by user at symbol {}", symbol);
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    if (job != null && job.cancelRequested) break;
                    // The backfill-running guard above runs once, before this loop - and this
                    // loop can walk 966 symbols, one broker call each. On 2026-11-10 a backfill
                    // started while it was part-way through, so both were on /charts/historical
                    // at the same time and Dhan rate-limited every request from both. Re-check
                    // as we go, but cheaply: a COUNT at most twice a minute, not once per symbol.
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastBackfillCheckMs > 30_000L) {
                        lastBackfillCheckMs = nowMs;
                        if (isHistoricalBackfillRunning()) {
                            log.warn("[BasePrice] Stopping the broker leg at {}: a historical backfill "
                                   + "has started and Dhan rate-limits per user. Press Refresh - Live "
                                   + "Price once it finishes.", symbol);
                            break;
                        }
                    }
                    String normalized = normalizeSymbol(symbol);
                    if (!processedSymbols.add(normalized)) {
                        if (job != null) { job.skippedRecords.incrementAndGet(); job.updateProgress(); }
                        continue;
                    }
                    if (job != null) { job.currentSymbol = symbol; job.lastUpdatedAt = DateUtil.now(); }
                    try {
                        String uploadDate = normalizeStoredDate(getUploadDate(symbol, institutionId));
                        if (uploadDate == null) {
                            markBasePriceError(symbol, institutionId, "DHAN_ERROR: upload date missing.");
                            if (job != null) { job.skippedRecords.incrementAndGet(); job.updateProgress(); }
                            continue;
                        }
                        DhanInstrumentRef ref = refs.get(normalized);
                        if (ref == null) {
                            markBasePriceError(symbol, institutionId, "DHAN_ERROR: " + mappingMissingReason(symbol));
                            if (job != null) { job.skippedRecords.incrementAndGet(); job.updateProgress(); }
                            continue;
                        }

                        double price = 0;
                        String source = "";

                        // The base price is the close on the upload date - every gain and loss
                        // figure is measured from it - so take it from history first. The live
                        // quote is only the right answer when the stock was uploaded today.
                        // Preferring the quote made base price equal the current price on 1,207
                        // of 1,218 rows, which silently flattened every Return % to zero.
                        boolean historicalDH905 = false;
                        try {
                            price = fetchDhanClosingPrice(ref, uploadDate, credential.get());
                            source = "DHAN_CLOSE:" + uploadDate;
                        } catch (Exception e) {
                            log.debug("Historical close failed for {} on {}: {}", symbol, uploadDate, e.getMessage());
                            if (isDhanRateLimit(e.getMessage())) dhanOn429();
                            String emsg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
                            if (emsg.contains("dh-905")) historicalDH905 = true;
                        }

                        double[] quoteData = batchQuoteData.get(normalized);
                        if (price <= 0 && DateUtil.today().equals(uploadDate)) {
                            if (quoteData != null && quoteData[1] > 0) {
                                price = quoteData[1];
                                source = "DHAN_QUOTE_CLOSE";
                            } else if (quoteData != null && quoteData[0] > 0) {
                                price = quoteData[0];
                                source = "DHAN_QUOTE_LTP";
                            }
                        }

                        if (price <= 0) {
                            boolean batchQuoteEmpty = quoteData == null || (quoteData[0] <= 0 && quoteData[1] <= 0);
                            if (historicalDH905 && batchQuoteEmpty) {
                                markBasePriceError(symbol, institutionId,
                                    "DHAN_UNFETCHABLE: instrument " + ref.securityId + " not supported by Dhan API (DH-905). Will not retry.");
                                log.info("Marked {} (securityId={}) as unfetchable — DH-905 permanent failure.", symbol, ref.securityId);
                            } else {
                                markBasePriceError(symbol, institutionId, "DHAN_ERROR: closing price not available from Dhan for uploaded date " + uploadDate + ".");
                            }
                            if (job != null) { job.failedRecords.incrementAndGet(); job.updateProgress(); }
                            continue;
                        }

                        batchSymbols.add(symbol);
                        batchPrices.add(price);
                        batchSources.add(source);

                        if (batchSymbols.size() >= batchFlushSize) {
                            flushBasePriceBatch(batchSymbols, batchPrices, batchSources, institutionId);
                            if (job != null) { job.successRecords.addAndGet(batchSymbols.size()); job.updateProgress(); }
                            batchSymbols.clear(); batchPrices.clear(); batchSources.clear();
                        }
                    } catch (Exception e) {
                        String friendly = friendlyDhanBasePriceError(e.getMessage());
                        if (isDhanAuthenticationError(e.getMessage())) {
                            markDhanCredentialInvalid(credential.get().id(), "Dhan rejected the broker token. Reconnect or verify the Dhan broker token, then refresh again.");
                            log.warn("Dhan token expired/invalid (DH-901). Stopping base price fetch for remaining symbols.");
                            if (job != null) { job.status = "FAILED"; job.errorMessage = "Dhan token expired/invalid (DH-901)"; job.updateProgress(); }
                            break;
                        }
                        markBasePriceError(symbol, institutionId, "DHAN_ERROR: " + friendly);
                        if (job != null) { job.failedRecords.incrementAndGet(); job.updateProgress(); }
                        log.debug("Dhan base price fetch {}: {}", symbol, e.getMessage());
                    }
                }
                if (!batchSymbols.isEmpty()) {
                    flushBasePriceBatch(batchSymbols, batchPrices, batchSources, institutionId);
                    if (job != null) { job.successRecords.addAndGet(batchSymbols.size()); job.updateProgress(); }
                }
                if (job != null && "RUNNING".equals(job.status)) {
                    job.status = "COMPLETED";
                    job.currentSymbol = null;
                    job.updateProgress();
                }
            } catch (Exception e) {
                log.error("Base price fetch thread error: {}", e.getMessage());
                if (job != null) { job.status = "FAILED"; job.errorMessage = e.getMessage(); job.updateProgress(); }
            } finally {
                activeUploadJobs.set(0);
            }
        });
    }

    /**
     * One batch /marketfeed/quote call for all symbols.
     * Returns Map: normalizedSymbol → [lastPrice, closePrice]
     */
    private Map<String, double[]> fetchBatchQuoteData(List<String> symbols,
                                                       DhanBrokerService.Credential credential) {
        Map<String, double[]> result = new LinkedHashMap<>();
        Map<String, DhanInstrumentRef> instruments = resolveDhanInstruments(symbols);
        if (instruments.isEmpty()) return result;

        Map<String, String> headers = Map.of(
            "Accept", "application/json", "Content-Type", "application/json",
            "access-token", credential.accessToken(), "client-id", credential.clientId());

        List<DhanInstrumentRef> allRefs = new ArrayList<>(instruments.values());

        for (int from = 0; from < allRefs.size(); from += 1000) {
            int to = Math.min(from + 1000, allRefs.size());
            List<DhanInstrumentRef> batch = allRefs.subList(from, to);

            Map<String, List<Long>> payload = new LinkedHashMap<>();
            for (DhanInstrumentRef ref : batch) {
                String segment = normalizeDhanExchangeSegment(ref.exchangeSegment);
                if (segment.isBlank() || ref.securityId == null || ref.securityId.isBlank()) continue;
                try {
                    payload.computeIfAbsent(segment, k -> new ArrayList<>()).add(Long.parseLong(ref.securityId));
                } catch (NumberFormatException ignored) {}
            }
            if (payload.isEmpty()) continue;

            try {
                dhanThrottle();
                String json = http.postJsonWithRetry(getDhanApi() + "/marketfeed/quote", headers, payload, 15000);
                dhanOnSuccess();
                JsonNode root = mapper.readTree(json);
                JsonNode data = root.path("data");
                if (data.isMissingNode() || data.isNull()) data = root;

                int usable = 0;
                for (DhanInstrumentRef ref : batch) {
                    String segment = normalizeDhanExchangeSegment(ref.exchangeSegment);
                    JsonNode quote = data.path(segment).path(ref.securityId);
                    double ltp = firstNumber(quote, "last_price", "ltp", "LTP", "lastPrice");
                    double close = firstNumber(quote.path("ohlc"), "close", "prev_close");
                    if (close <= 0) close = firstNumber(quote, "close", "prev_close");
                    // Only record a quote that actually carries a price.
                    //
                    // This used to put every instrument in the map whatever came back, so a
                    // response with no quotes in it still produced 1,271 entries of {0,0} and the
                    // summary below reported "fetched 1271/1271". The caller then dropped all of
                    // them on its own ltp > 0 test and never called batchUpdateCurrentPrices, so
                    // prices silently stopped moving while every log line said the refresh had
                    // worked. Counting only usable quotes makes that visible instead.
                    if (ltp > 0 || close > 0) {
                        result.put(normalizeSymbol(ref.symbol), new double[]{ltp, close});
                        usable++;
                    }
                }
                if (usable == 0) {
                    // The one piece of information needed to diagnose this was being discarded.
                    //
                    // Observed 2026-11-01 onwards: every call returned HTTP 200 with a 41-byte
                    // body - {"status":"success","data":{"NSE_EQ":{}}} - so it is neither a token
                    // failure (401/DH-901) nor a rate limit (DH-904), both of which are handled
                    // below. The request succeeded and carried no quotes. Meanwhile the same
                    // token kept importing candles from /charts/historical perfectly well, which
                    // is the signature of a live-market-data entitlement problem rather than a
                    // broken credential, because Dhan sells live quotes separately from history.
                    log.warn("[Refresh] Dhan /marketfeed/quote returned no usable price for any of "
                           + "{} instrument(s). The call succeeded, so this is not a token or "
                           + "rate-limit error - the response carried no quotes. Check the Dhan "
                           + "Data API (live market feed) subscription on the broker account; "
                           + "historical candles use a different entitlement and keep working. "
                           + "Response was: {}",
                        batch.size(), json == null ? "(empty)"
                            : json.length() > 400 ? json.substring(0, 400) + "..." : json);
                }
            } catch (Exception e) {
                if (isDhanAuthenticationError(e.getMessage())) {
                    log.warn("Dhan token expired/invalid (DH-901). Aborting batch quote fetch.");
                    break;
                }
                if (isDhanRateLimit(e.getMessage())) {
                    dhanOn429();
                    from -= 1000;
                    continue;
                }
                log.warn("Batch quote fetch failed for {} symbols: {}", batch.size(), e.getMessage());
            }
            if (to < allRefs.size()) sleepQuietly(1100);
        }

        log.info("Batch quote data fetched for {}/{} symbols", result.size(), symbols.size());
        return result;
    }

    private void refreshBatchQuotePrices(List<String> symbols, Long userId, Long institutionId) {
        try {
            int updated = refreshBatchCurrentPrices(symbols, userId, institutionId);
            log.info("Post-upload batch price refresh: {}/{} symbols updated", updated, symbols.size());
        } catch (Exception e) {
            log.warn("Post-upload batch price refresh failed: {}", e.getMessage());
        }
    }

    private void flushBasePriceBatch(List<String> symbols, List<Double> prices, List<String> sources, Long institutionId) {
        if (symbols.isEmpty()) return;
        String now = DateUtil.now();
        runInTransaction(() -> {
            for (int i = 0; i < symbols.size(); i++) {
                em.createNativeQuery(
                    "UPDATE tracked_stocks SET base_price=?," +
                    "return_pct=CASE WHEN current_price IS NOT NULL AND current_price>0 THEN ((current_price - ?) / ?)*100 ELSE 0 END," +
                    "base_price_source=?,last_refreshed=?,updated_at=? WHERE symbol=? AND institution_id=?")
                    .setParameter(1, prices.get(i))
                    .setParameter(2, prices.get(i))
                    .setParameter(3, prices.get(i))
                    .setParameter(4, sources.get(i))
                    .setParameter(5, now)
                    .setParameter(6, now)
                    .setParameter(7, symbols.get(i))
                    .setParameter(8, institutionId)
                    .executeUpdate();
                if ((i + 1) % getDbFlushSize() == 0) { em.flush(); em.clear(); }
            }
            em.flush(); em.clear();
        });
        log.info("Flushed base price batch: {} symbols in single transaction", symbols.size());
    }

    /**
     * Oldest stored close still allowed to stand in for a current price, in days.
     *
     * <p>A close from last quarter is not a current price by any reading, and silently presenting
     * one would trade a visible staleness problem for an invisible one. Past this age the row is
     * left alone: it keeps whatever it had, and the Last Update column goes on reporting that
     * value's real session date, which is the honest outcome.
     */
    private static final int CLOSE_FALLBACK_MAX_AGE_DAYS = 30;

    /**
     * Fills current_price from the newest stored daily close wherever no live quote is available.
     *
     * <p>current_price is written only by the two live paths - the REST quote endpoint and the
     * WebSocket feed - so when the live feed stops, the column freezes at whatever it last held
     * and the screen presents that figure as current indefinitely. Measured on 2026-11-11, nine
     * days after the feed went silent: 3MINDIA displayed 28,990, the 2026-10-30 close captured by
     * the last successful fetch on 2026-11-01, while the newest stored session - Monday
     * 2026-11-09 - closed at 28,070. The screen was 920 points, 3.3%, above the real level, and
     * carried that error into the return it showed, -12.98% against a true figure near -15.9%.
     * The correct number was already in broker_market_candles the whole time; nothing read it.
     *
     * <p>So this is not a new source of data, it is the data already fetched being allowed to
     * reach the screen. The row records where the price came from - BROKER_DAILY_CLOSE, with
     * current_price_date set to that candle's own session - so a close is never passed off as a
     * live tick, and the Last Update column names the session the number belongs to.
     *
     * <p>Three guards keep it from ever degrading a better price:
     * <ul>
     *   <li>it only writes where the stored session is strictly newer than the row's own
     *       current_price_date, so a live quote from today is never replaced by yesterday's
     *       close, whatever order the two run in;</li>
     *   <li>it also writes where current_price is missing or zero, since any dated close beats
     *       no price at all;</li>
     *   <li>it ignores candles older than {@link #CLOSE_FALLBACK_MAX_AGE_DAYS}, which also
     *       bounds the scan to recent rows rather than the whole candle table.</li>
     * </ul>
     *
     * <p>One statement for every symbol, joined on the normalised symbol the way the rest of this
     * class matches tracked stocks to candles. DISTINCT ON picks each symbol's latest session.
     *
     * <p>It also resolves the two rows that looked like unadjusted corporate actions. GNRL and
     * CONCORDBIO sat exactly 10x and 2x away from their stored closes, which reads as a split
     * missing from the candle history - but the scale error was in the frozen quote, not in the
     * candles. GNRL's base price is 9.62 and its stored close 11.79, consistent with each other;
     * it was the stale current_price of 96.23 that was off by a factor of ten, which is what
     * produced the +900% return on the screen. CONCORDBIO is the same shape: base 604.95, stored
     * close 596.80, frozen quote 1245.60, and a reported +105.9% that becomes -1.35%. Because
     * base prices are themselves derived from these candles, pricing both sides from one source
     * makes the two agree by construction.
     *
     * @return the number of rows filled from a stored close
     */
    private int fillCurrentPriceFromStoredClose(Long institutionId) {
        String now = DateUtil.now();
        String floor = java.time.LocalDate.now(IST).minusDays(CLOSE_FALLBACK_MAX_AGE_DAYS).toString();
        String candleKey = normalizedSql("c.symbol");
        String trackedKey = normalizedSql("t.symbol");
        final int[] filled = {0};
        try {
            runInTransaction(() -> {
                int rows = em.createNativeQuery(
                    "UPDATE tracked_stocks t SET " +
                    "current_price=s.close_price, last_traded_price=s.close_price, " +
                    "current_price_source='BROKER_DAILY_CLOSE', current_price_date=s.trade_date, " +
                    "price_updated_at=?, last_refreshed=?, updated_at=?, " +
                    "return_pct=CASE WHEN t.base_price IS NOT NULL AND t.base_price>0 " +
                    "THEN ((s.close_price - t.base_price)/t.base_price)*100 ELSE 0 END " +
                    "FROM (SELECT DISTINCT ON (" + candleKey + ") " + candleKey + " AS key, " +
                    "c.close AS close_price, c.trade_date AS trade_date " +
                    "FROM broker_market_candles c " +
                    "WHERE c.close IS NOT NULL AND c.close>0 AND c.trade_date>=? " +
                    "AND (c.candle_mode IS NULL OR UPPER(c.candle_mode)='DAILY') " +
                    "ORDER BY " + candleKey + ", c.trade_date DESC) s " +
                    "WHERE " + trackedKey + "=s.key AND t.institution_id=? AND t.is_active=1 " +
                    "AND COALESCE(t.is_deleted,0)=0 " +
                    "AND (t.current_price_date IS NULL OR t.current_price_date='' " +
                    "     OR t.current_price_date < s.trade_date " +
                    "     OR t.current_price IS NULL OR t.current_price<=0)")
                    .setParameter(1, now)
                    .setParameter(2, now)
                    .setParameter(3, now)
                    .setParameter(4, floor)
                    .setParameter(5, institutionId)
                    .executeUpdate();
                filled[0] = rows;
            });
            if (filled[0] > 0) {
                log.info("[Refresh] {} stock(s) filled from the most recent stored daily close "
                       + "(no live quote available). Source recorded as BROKER_DAILY_CLOSE with "
                       + "that session's date, so the screen shows which session each price is from.",
                    filled[0]);
            }
        } catch (Exception e) {
            // A fallback that cannot run must not fail the refresh - the live path's own result
            // still stands, and the rows simply keep the values they had.
            log.warn("[Refresh] Could not fill current price from stored closes: {}", safeError(e.getMessage()));
            return 0;
        }
        return filled[0];
    }

    private int batchUpdateCurrentPrices(List<String> symbols, List<Double> prices, Long institutionId) {
        if (symbols.isEmpty()) return 0;
        String now = DateUtil.now();
        // The session the price belongs to, not the day this thread runs on - see
        // priceSessionDate(). Named "today" when it was DateUtil.today(), which is precisely how
        // a Sunday ended up stamped on every row.
        String session = priceSessionDate();
        final int[] updated = {0};
        runInTransaction(() -> {
            for (int i = 0; i < symbols.size(); i++) {
                String normalized = normalizeSymbol(symbols.get(i));
                int rows = em.createNativeQuery(
                    "UPDATE tracked_stocks SET " +
                    "current_price=?, last_traded_price=?, price_updated_at=?, " +
                    "return_pct=CASE WHEN base_price IS NOT NULL AND base_price>0 " +
                    "THEN ((? - base_price) / base_price)*100 ELSE 0 END, " +
                    "current_price_source='DHAN_LTP_REFRESH', current_price_date=?, last_refreshed=?, updated_at=? " +
                    "WHERE UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(symbol),'.NS',''),'.BO',''),'-EQ',''),'-BE',''),'-BZ',''),'-SM',''),'-ST',''))=? " +
                    "AND institution_id=? AND is_active=1")
                    .setParameter(1, prices.get(i))
                    .setParameter(2, prices.get(i))
                    .setParameter(3, now)
                    .setParameter(4, prices.get(i))
                    .setParameter(5, session)
                    .setParameter(6, now)
                    .setParameter(7, now)
                    .setParameter(8, normalized)
                    .setParameter(9, institutionId)
                    .executeUpdate();
                updated[0] += rows;
                if ((i + 1) % getDbFlushSize() == 0) { em.flush(); em.clear(); }
            }
            em.flush(); em.clear();
        });
        return updated[0];
    }

    private boolean isDhanRateLimit(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return text.contains("dh-904") || text.contains("rate_limit") || text.contains("too many requests")
            || text.contains("\"805\"") || text.contains("429");
    }

    private String friendlyDhanBasePriceError(String message) {
        String text = safeError(message);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("dh-904") || lower.contains("rate_limit") || lower.contains("too many requests")) {
            return "Dhan rate limit reached. Base price will retry later.";
        }
        if (lower.contains("dh-905") || lower.contains("missing required fields")) {
            return "Dhan did not return a close price for the upload date.";
        }
        if (lower.contains("token")) return "Dhan token required for base price.";
        return text;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void disabledLegacyFetchBasePricesAsync(List<String> symbols, Long institutionId) {
        new Thread(() -> {
            for (String symbol : symbols) {
                try {
                    String uploadDate = getUploadDate(symbol, institutionId);
                    if (uploadDate == null) continue;

                    double price = 0;
                    if (price > 0) {
                        em.createNativeQuery(
                            "UPDATE tracked_stocks SET base_price=?,current_price=?," +
                            "return_pct=0,last_refreshed=? WHERE symbol=? AND institution_id=?")
                            .setParameter(1, price).setParameter(2, price)
                            .setParameter(3, DateUtil.now()).setParameter(4, symbol)
                            .setParameter(5, institutionId).executeUpdate();
                        log.info("Base price for {}: ₹{}", symbol, price);
                    }
                    Thread.sleep(200); // gentle rate limit
                } catch (Exception e) {
                    log.debug("Base price fetch {}: {}", symbol, e.getMessage());
                }
            }
        }, "BasePriceFetcher").start();
    }

    public void fetchBasePriceForSingleStock(String symbol, Long userId, Long institutionId) {
        int savedUpdated = backfillBasePricesFromSavedMarketData(List.of(symbol), institutionId);
        if (savedUpdated > 0) return;
        if (checkBasePriceExists(symbol, institutionId)) return;

        Optional<DhanBrokerService.Credential> credential = findVerifiedDhanCredential(userId, institutionId);
        if (credential.isEmpty()) {
            markBasePriceError(symbol, institutionId, "DHAN_ERROR: verified Dhan broker token required.");
            throw new RuntimeException("No Dhan token for base price");
        }
        Map<String, DhanInstrumentRef> refs = resolveDhanInstruments(List.of(symbol));
        DhanInstrumentRef ref = refs.get(normalizeSymbol(symbol));
        if (ref == null) {
            markBasePriceError(symbol, institutionId, "DHAN_ERROR: " + mappingMissingReason(symbol));
            throw new RuntimeException("Symbol not mapped in Dhan: " + symbol);
        }
        String uploadDate = normalizeStoredDate(getUploadDate(symbol, institutionId));
        if (uploadDate == null) {
            markBasePriceError(symbol, institutionId, "DHAN_ERROR: upload date missing.");
            throw new RuntimeException("Upload date missing for " + symbol);
        }
        double price;
        try {
            price = fetchDhanClosingPrice(ref, uploadDate, credential.get());
        } catch (Exception e) {
            markBasePriceError(symbol, institutionId, "DHAN_ERROR: " + e.getMessage());
            throw new RuntimeException("Dhan API error for " + symbol + ": " + e.getMessage());
        }
        if (price <= 0) {
            markBasePriceError(symbol, institutionId, "DHAN_ERROR: closing price not available for " + uploadDate);
            throw new RuntimeException("Dhan closing price not available for " + symbol);
        }
        runInTransaction(() -> em.createNativeQuery(
                "UPDATE tracked_stocks SET base_price=?," +
                "return_pct=CASE WHEN current_price IS NOT NULL AND current_price>0 THEN ((current_price - ?) / ?)*100 ELSE 0 END," +
                "base_price_source=?,last_refreshed=?,updated_at=? WHERE symbol=? AND institution_id=?")
                .setParameter(1, price).setParameter(2, price).setParameter(3, price)
                .setParameter(4, "DHAN_CLOSE:" + uploadDate)
                .setParameter(5, DateUtil.now()).setParameter(6, DateUtil.now())
                .setParameter(7, symbol).setParameter(8, institutionId).executeUpdate());
    }

    private boolean checkBasePriceExists(String symbol, Long institutionId) {
        try {
            Object r = em.createNativeQuery(
                "SELECT base_price FROM tracked_stocks WHERE symbol=? AND institution_id=? AND is_active=1")
                .setParameter(1, symbol).setParameter(2, institutionId).getSingleResult();
            return r != null && ((Number) r).doubleValue() > 0;
        } catch (Exception e) { return false; }
    }

    public void refreshSingleStockPrice(String symbol, Long userId, Long institutionId) {
        Optional<DhanBrokerService.Credential> credential = findVerifiedDhanCredential(userId, institutionId);
        if (credential.isEmpty()) return;
        Map<String, DhanInstrumentRef> refs = resolveDhanInstruments(List.of(symbol));
        DhanInstrumentRef ref = refs.get(normalizeSymbol(symbol));
        if (ref == null) return;

        Map<String, String> headers = Map.of(
            "Accept", "application/json", "Content-Type", "application/json",
            "access-token", credential.get().accessToken(), "client-id", credential.get().clientId());
        String segment = normalizeDhanExchangeSegment(ref.exchangeSegment);
        if (segment.isBlank()) return;

        try {
            dhanThrottle();
            Map<String, List<Long>> payload = Map.of(segment, List.of(Long.parseLong(ref.securityId)));
            String json = http.postJsonWithRetry(getDhanApi() + "/marketfeed/quote", headers, payload, 15000);
            dhanOnSuccess();
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            com.fasterxml.jackson.databind.JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) data = root;
            com.fasterxml.jackson.databind.JsonNode quote = data.path(segment).path(ref.securityId);
            double price = firstNumber(quote, "last_price", "ltp", "LTP", "lastPrice", "lastTradedPrice", "last_traded_price");
            if (price > 0) {
                updateCurrentPrice(ref.symbol, institutionId, price);
            }
        } catch (Exception e) {
            if (isDhanRateLimit(e.getMessage())) dhanOn429();
            log.debug("Single stock price refresh failed for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Batch refresh current prices for multiple symbols in one Dhan API call.
     * Uses /marketfeed/quote — supports up to 1000 instruments per request, works after market hours.
     * Returns the number of symbols successfully updated.
     */
    public int refreshBatchCurrentPrices(List<String> symbols, Long userId, Long institutionId) {
        if (symbols == null || symbols.isEmpty()) return 0;
        Optional<DhanBrokerService.Credential> credential = findVerifiedDhanCredential(userId, institutionId);
        if (credential.isEmpty()) return 0;

        Map<String, DhanInstrumentRef> instruments = resolveDhanInstruments(symbols);
        if (instruments.isEmpty()) return 0;

        Map<String, String> headers = Map.of(
            "Accept", "application/json", "Content-Type", "application/json",
            "access-token", credential.get().accessToken(), "client-id", credential.get().clientId());

        List<DhanInstrumentRef> allRefs = new ArrayList<>(instruments.values());
        int totalUpdated = 0;

        for (int from = 0; from < allRefs.size(); from += 1000) {
            int to = Math.min(from + 1000, allRefs.size());
            List<DhanInstrumentRef> batch = allRefs.subList(from, to);

            Map<String, List<Long>> payload = new LinkedHashMap<>();
            for (DhanInstrumentRef ref : batch) {
                String segment = normalizeDhanExchangeSegment(ref.exchangeSegment);
                if (segment.isBlank() || ref.securityId == null || ref.securityId.isBlank()) continue;
                try {
                    payload.computeIfAbsent(segment, k -> new ArrayList<>()).add(Long.parseLong(ref.securityId));
                } catch (NumberFormatException ignored) {}
            }
            if (payload.isEmpty()) continue;

            try {
                dhanThrottle();
                String json = http.postJsonWithRetry(getDhanApi() + "/marketfeed/quote", headers, payload, 15000);
                dhanOnSuccess();
                JsonNode root = mapper.readTree(json);
                JsonNode data = root.path("data");
                if (data.isMissingNode() || data.isNull()) data = root;

                List<String> batchSymbols = new ArrayList<>();
                List<Double> batchPrices = new ArrayList<>();
                for (DhanInstrumentRef ref : batch) {
                    String segment = normalizeDhanExchangeSegment(ref.exchangeSegment);
                    JsonNode quote = data.path(segment).path(ref.securityId);
                    double price = firstNumber(quote, "last_price", "ltp", "LTP", "lastPrice", "lastTradedPrice", "last_traded_price");
                    if (price > 0) {
                        batchSymbols.add(ref.symbol);
                        batchPrices.add(price);
                    }
                }
                if (!batchSymbols.isEmpty()) {
                    totalUpdated += batchUpdateCurrentPrices(batchSymbols, batchPrices, institutionId);
                }
            } catch (Exception e) {
                if (isDhanRateLimit(e.getMessage())) {
                    dhanOn429();
                    from -= 1000;
                    continue;
                }
                log.warn("Batch LTP refresh failed for {} symbols: {}", batch.size(), e.getMessage());
            }

            if (to < allRefs.size()) sleepQuietly(1100);
        }

        log.info("Batch LTP refresh: {}/{} symbols updated in {} API calls",
            totalUpdated, symbols.size(), (int) Math.ceil(allRefs.size() / 1000.0));
        return totalUpdated;
    }

    // ==========================================================================
    //  CURRENT PRICE REFRESH ENGINE
    // ==========================================================================

    /**
     * Ticks once a minute and asks the configured schedule whether this minute is due.
     *
     * <p>The cadence used to be hard-wired to five minutes, which meant the
     * {@code top10.auto_refresh.interval_sec} setting existed but changed nothing. It is now
     * the schedule: pick an interval (1, 5, 10, 15, 30 or 60 minutes) or a list of fixed clock
     * times, and every due tick refreshes prices and writes a fresh ranking run.
     */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledPriceRefresh() {
        java.time.ZonedDateTime tickNow = java.time.ZonedDateTime.now(IST);
        backfillMissedTop10Locks();
        recoverMissingBasePrices();
        boolean lockThisTick = isTop10LockTick(tickNow);
        // The lock minute always runs, whatever the interval works out to - the whole point
        // is that the locked list is the one priced at exactly that time.
        if (!lockThisTick && !isTop10RunDue(tickNow)) return;
        // Stand aside while a historical backfill is writing the candle tables — running
        // both at once causes lock timeouts and slow ranking. The next tick resumes.
        if (isHistoricalBackfillRunning()) {
            log.info("[Refresh] Skipped this tick: a historical backfill is running (avoids DB contention).");
            return;
        }
        // refreshAllPrices() manages its own short per-batch transactions internally,
        // so it must NOT be wrapped in an outer transaction here (that would re-create
        // one long transaction holding row locks for minutes → lock timeouts).
        refreshAllPrices(null, 1L, "auto");
        // Only the Current Top 10 section reacts — recompute + diff + push over SSE.
        recomputeAndBroadcastTop10(null, 1L, "auto");
        if (lockThisTick) lockTodaysTop10(1L);
    }

    /** True on the configured lock minute of a trading day, once only. */
    private boolean isTop10LockTick(java.time.ZonedDateTime now) {
        if (!isTop10LockEnabled()) return false;
        java.time.DayOfWeek dow = now.getDayOfWeek();
        if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) return false;
        if (isMarketHoliday(now.toLocalDate())) return false;

        String hhmm = String.format("%02d:%02d", now.getHour(), now.getMinute());
        String lockAt = getTop10LockTime();

        // On the minute: the normal path.
        if (hhmm.equals(lockAt)) return claimTop10Run(now.toLocalDate() + "#LOCK#" + hhmm);

        // After the minute, catch up if the day was never locked.
        //
        // The lock used to fire only on an exact string match with 15:20, so the application had to
        // be alive for that one particular minute. It never once was: every day in the table shows
        // is_locked = 0, and the logs have no 15:20 activity at all, because this machine sleeps.
        // A day whose Top 10 was never frozen has no official list, which is the whole basis of
        // Previous Top 10 and of every return measured against it.
        //
        // So a late start still freezes the day. It locks the newest run of the day, which is the
        // closest available record of where the list stood - noted as late in the log, because a
        // list frozen at 16:10 is not the same thing as one frozen at 15:20 and the difference
        // should be visible rather than silently absorbed.
        if (hhmm.compareTo(lockAt) > 0 && !isTodayAlreadyLocked()) {
            return claimTop10Run(now.toLocalDate() + "#LOCKCATCHUP");
        }
        return false;
    }

    /**
     * Freezes any past trading day that was never locked, once, at startup.
     *
     * <p>The cut-off used to require the application to be alive for one exact minute, and it never
     * was - every historical day carries is_locked = 0. Those days have rankings but no official
     * list, so Previous Top 10 and every return measured against it have nothing to anchor to.
     *
     * <p>Each such day is locked to its own last run, which is the closest surviving record of
     * where the list stood at the close. It is a repair of days already past, not a substitute for
     * the cut-off: today is left to the live path, which is the only one that can freeze a list at
     * the moment it actually mattered.
     */
    /**
     * When the base-price recovery last did any work. Not a one-shot flag.
     *
     * <p>It was a boolean, set on the first pass and never cleared - which meant the recovery
     * protected against exactly one wipe per application run and then stood down. That is the
     * wrong shape: an upload clears base_price EVERY time it runs, so a second upload in the same
     * session left all 1,232 stocks empty with the guard already spent, the ranking loading 0 rows
     * and every screen that depends on it blank. Exactly that happened - a 12:41 upload was
     * recovered, and the 20:40 upload was not.
     */
    private volatile long lastBasePriceRecoveryMs = 0;

    /**
     * Refills base prices that an interrupted upload left empty.
     *
     * <p>A CSV upload deliberately clears base_price - a new upload date means a new base price,
     * so the old one has to go and be re-derived. The refill then runs on a background worker.
     * That worker does not survive the process: an application restart, a crash or a power cut
     * part-way through leaves the remaining stocks with base_price NULL and nothing to resume
     * them. It happened here - an upload at 12:41 was refilling when the application was
     * restarted two minutes later, and all 1,232 tracked stocks were left without a base price.
     *
     * <p>That is not a cosmetic gap. Base price is half of the ranking entry test
     * (Current Price &gt;= Base Price), so with it empty the ranking has no candidates at all and
     * Today Top 10 silently falls back to the last stored run - showing yesterday's answer as
     * though it were today's.
     *
     * <p>Only the local pass runs here: base prices are read from candles already stored, which is
     * an indexed lookup per symbol and needs no broker call. Stocks that genuinely have no candle
     * on or before their upload date are left for the explicit Refresh Prices action, which is
     * allowed to spend Dhan requests. That keeps startup cheap and cannot trip a rate limit.
     *
     * <p>Driven from the per-minute scheduler rather than ApplicationReadyEvent, for the reason
     * documented on {@link #backfillMissedTop10Locks()}: on the ready event the configuration
     * cache is not populated yet. One pass per run.
     */
    public void recoverMissingBasePrices() {
        try {
            // Cheap indexed count first: on a healthy system this is the only work done, so the
            // check can run on every tick without costing anything.
            List<String> missing = getSymbolsMissingBasePrice(1L);
            if (missing.isEmpty()) return;

            // Something IS missing. Throttle the repair itself so a genuinely unresolvable set -
            // the three symbols Dhan does not list, for instance - cannot turn into a refill
            // attempt every single minute.
            long now = System.currentTimeMillis();
            if (now - lastBasePriceRecoveryMs < 120_000L) return;
            lastBasePriceRecoveryMs = now;

            long started = now;
            int fixed = backfillBasePricesFromSavedMarketData(missing, 1L);
            int stillMissing = getSymbolsMissingBasePrice(1L).size();

            if (fixed > 0) {
                log.info("[BasePriceRecovery] Refilled {} of {} stock(s) from stored daily candles "
                       + "in {} ms. {} still without a base price - use Refresh Prices to fetch "
                       + "those from the broker.",
                    fixed, missing.size(), System.currentTimeMillis() - started, stillMissing);
            } else if (shouldReportUnresolvedBasePrices(missing.size())) {
                log.warn("[BasePriceRecovery] {} stock(s) have no base price and none could be "
                       + "resolved from stored candles. Their upload dates are likely outside the "
                       + "stored candle range - use Refresh Prices, or fetch that period first.",
                    missing.size());
            } else {
                log.debug("[BasePriceRecovery] {} stock(s) still unresolved (already reported).",
                    missing.size());
            }
        } catch (Exception e) {
            // Never let this stop the tick that also refreshes prices and rebuilds the Top 10.
            log.warn("[BasePriceRecovery] Could not run: {}", e.getMessage());
        }
    }

    /** Size of the unresolvable set the last WARN described, and when it was written. */
    private volatile int lastUnresolvedBasePriceCount = -1;
    private volatile long lastUnresolvedBasePriceWarnMs = 0;
    private static final long UNRESOLVED_WARN_REPEAT_MS = 30 * 60_000L;

    /**
     * Whether an unresolvable base-price set is worth another WARN.
     *
     * <p>The repair runs every two minutes, and a set it cannot resolve - stocks whose upload date
     * sits outside the stored candle range, which is the normal state while a backfill is still
     * working towards those months - stays unresolvable for hours. Warning on every pass turned
     * one standing condition into thirty log entries an hour.
     *
     * <p>Report it when the number changes, since that is new information, and otherwise repeat it
     * at most every half hour so a persistent gap is still visible to anyone reading the log.
     */
    private synchronized boolean shouldReportUnresolvedBasePrices(int count) {
        long now = System.currentTimeMillis();
        boolean changed = count != lastUnresolvedBasePriceCount;
        boolean stale = now - lastUnresolvedBasePriceWarnMs >= UNRESOLVED_WARN_REPEAT_MS;
        if (!changed && !stale) return false;
        lastUnresolvedBasePriceCount = count;
        lastUnresolvedBasePriceWarnMs = now;
        return true;
    }

    private volatile boolean lockBackfillDone = false;

    public void backfillMissedTop10Locks() {
        // Driven from the per-minute scheduler, not ApplicationReadyEvent.
        //
        // On the ready event this ran before AppConfigService had populated its cache, so
        // isTop10LockEnabled() fell back to its default of false and the method returned
        // immediately - silently, since the early return logs nothing. The scheduler fires a minute
        // later with configuration loaded, and a flag keeps it to one pass per run.
        if (lockBackfillDone) return;
        if (!isTop10LockEnabled()) return;
        lockBackfillDone = true;
        try {
            String today = DateUtil.today();
            @SuppressWarnings("unchecked")
            List<Object[]> unlocked = em.createNativeQuery(
                "SELECT run_date, MAX(id) FROM tracker_rankings " +
                "WHERE run_date < ? AND ranked_count > 0 " +
                "GROUP BY run_date " +
                "HAVING SUM(CASE WHEN is_locked = 1 THEN 1 ELSE 0 END) = 0 " +
                "ORDER BY run_date DESC LIMIT 60")
                .setParameter(1, today).getResultList();
            if (unlocked.isEmpty()) return;

            // The updates must run inside a transaction - JPA refuses executeUpdate() without one,
            // and the first attempt at this failed silently for exactly that reason. lockTodaysTop10
            // uses the same TransactionTemplate; this now matches it.
            int[] fixedRef = {0};
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                for (Object[] r : unlocked) {
                    Object id = r[1];
                    if (id == null) continue;
                    int n = em.createNativeQuery(
                        "UPDATE tracker_rankings SET is_locked = 1, locked_at = ? WHERE id = ?")
                        .setParameter(1, DateUtil.now())
                        .setParameter(2, ((Number) id).longValue())
                        .executeUpdate();
                    if (n > 0) fixedRef[0]++;
                }
            });
            int fixed = fixedRef[0];
            if (fixed > 0) {
                log.warn("[Top10Lock] Repaired {} past day(s) that were never frozen at the {} "
                       + "cut-off - each locked to that day's final run. Dates: {}",
                    fixed, getTop10LockTime(),
                    unlocked.stream().map(x -> String.valueOf(x[0])).limit(10).toList());
            }
        } catch (Exception e) {
            log.warn("[Top10Lock] Could not repair missed locks: {}", e.getMessage());
        }
    }

    /** True when one of today's runs already carries the official-list flag. */
    private boolean isTodayAlreadyLocked() {
        try {
            Object n = em.createNativeQuery(
                "SELECT COUNT(*) FROM tracker_rankings WHERE run_date = ? AND is_locked = 1")
                .setParameter(1, DateUtil.today()).getSingleResult();
            return n instanceof Number num && num.longValue() > 0;
        } catch (Exception e) {
            // Assume locked on error: a spurious re-lock would overwrite the official list, which is
            // worse than skipping a catch-up that the next tick will retry anyway.
            return true;
        }
    }

    /**
     * Marks the newest run for today as that day's locked Top 10, clearing any earlier lock
     * so exactly one run per day carries the flag. This is the list Previous Top 10 will show
     * tomorrow.
     */
    public void lockTodaysTop10(Long institutionId) {
        String today = DateUtil.today();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                em.createNativeQuery(
                    "UPDATE tracker_rankings SET is_locked=0, locked_at=NULL " +
                    "WHERE institution_id=? AND run_date=? AND is_locked=1")
                    .setParameter(1, institutionId).setParameter(2, today).executeUpdate();
                int n = em.createNativeQuery(
                    "UPDATE tracker_rankings SET is_locked=1, locked_at=? WHERE id = (" +
                    "  SELECT id FROM tracker_rankings WHERE institution_id=? AND run_date=? " +
                    "  ORDER BY id DESC LIMIT 1)")
                    .setParameter(1, DateUtil.now())
                    .setParameter(2, institutionId).setParameter(3, today).executeUpdate();
                if (n > 0) {
                    String nowHhmm = java.time.ZonedDateTime.now(IST)
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                    boolean late = nowHhmm.compareTo(getTop10LockTime()) > 0;
                    if (late) {
                        log.warn("[Top10Lock] Locked {} LATE at {} (cut-off is {}) - the application "
                               + "was not running at the cut-off, so this is the newest run of the "
                               + "day rather than the one that stood at {}.",
                            today, nowHhmm, getTop10LockTime(), getTop10LockTime());
                    } else {
                        log.info("[Top10Lock] Locked today's Top 10 ({}) as the official list for {}.",
                            getTop10LockTime(), today);
                    }
                }
                else log.warn("[Top10Lock] No ranking run exists for {} yet - nothing to lock.", today);
            });
        } catch (Exception e) {
            log.error("[Top10Lock] Failed to lock today's Top 10: {}", e.getMessage());
        }
    }

    /** Guards against the same minute firing twice if a tick is delivered late or re-entered. */
    private volatile String lastTop10AutoRunKey = "";

    /**
     * Is this minute a scheduled Current Top 10 run?
     *
     * <p>FIXED fires on the configured clock times, so a deliberate 15:45 run still happens
     * after the close. INTERVAL only runs while the market is open — outside it the prices do
     * not move, so a one-minute cadence would re-rank identical numbers and burn API calls.
     */
    private boolean isTop10RunDue(java.time.ZonedDateTime now) {
        if (!isTop10AutoRefreshEnabled()) return false;
        java.time.DayOfWeek dow = now.getDayOfWeek();
        if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) return false;
        if (isMarketHoliday(now.toLocalDate())) return false;

        String hhmm = String.format("%02d:%02d", now.getHour(), now.getMinute());
        String runKey = now.toLocalDate() + "#" + hhmm;

        if ("FIXED".equalsIgnoreCase(getTop10RefreshMode())) {
            for (String t : getTop10RefreshTimes().split(",")) {
                if (hhmm.equals(t.trim())) return claimTop10Run(runKey);
            }
            return false;
        }

        if (!isMarketOpen()) return false;
        int everyMin = Math.max(1, getTop10AutoRefreshInterval() / 60);
        if (((now.getHour() * 60) + now.getMinute()) % everyMin != 0) return false;
        return claimTop10Run(runKey);
    }

    private synchronized boolean claimTop10Run(String runKey) {
        if (runKey.equals(lastTop10AutoRunKey)) return false;
        lastTop10AutoRunKey = runKey;
        return true;
    }

    /** True while a Dhan historical backfill job is active, so recurring refresh can pause. */
    private boolean isHistoricalBackfillRunning() {
        try {
            Object c = em.createNativeQuery(
                "SELECT COUNT(*) FROM dhan_historical_jobs WHERE status IN ('running','queued','stopping')")
                .getSingleResult();
            return c instanceof Number n && n.longValue() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Like runInTransaction (defined below) but returns a value from the unit of work. */
    private <T> T callInTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private static final DateTimeFormatter TS_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss dd-MMM-yyyy", Locale.ENGLISH);
    private final ConcurrentHashMap<Long, List<Map<String, Object>>> lastTop10Snapshot = new ConcurrentHashMap<>();

    /**
     * Recalculate the ranking (unchanged Skip logic), compare against the last
     * broadcast Current Top 10 and push a diff over SSE so the frontend animates
     * only changed rows. On no change it pushes {no_change:true,last_updated} so
     * the client updates only the "Last Updated" label without redrawing the table.
     */
    @SuppressWarnings("unchecked")
    public void recomputeAndBroadcastTop10(Long userId, Long institutionId, String reason) {
        try {
            if (!isTop10AutoRefreshEnabled()) return;
            // Ranking was just recomputed by refreshAllPrices() (which runs the Skip-logic
            // engine right before this). Read the latest result and broadcast the diff —
            // no extra ranking write here, and this method makes no DB writes at all.
            Map<String, Object> ranking = getLatestRankingFull(userId, institutionId);
            List<Map<String, Object>> top10 =
                (List<Map<String, Object>>) ranking.getOrDefault("current_top10", List.of());

            Long key = institutionId == null ? 0L : institutionId;
            List<Map<String, Object>> prev = lastTop10Snapshot.getOrDefault(key, List.of());
            List<Map<String, Object>> changed = diffTop10(prev, top10);
            boolean orderSame = sameOrder(prev, top10);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("institution_id", key);
            payload.put("last_updated", java.time.ZonedDateTime.now(IST).format(TS_FMT));
            payload.put("server_time", Instant.now().toString());
            payload.put("count", top10.size());
            if (changed.isEmpty() && orderSame && !prev.isEmpty()) {
                payload.put("no_change", true);
            } else {
                payload.put("no_change", false);
                payload.put("changed", changed);
                payload.put("top10", top10);
            }
            lastTop10Snapshot.put(key, top10);
            liveBroadcaster.sendTop10Update(payload);
            broadcastModuleRefreshes("dashboard", "prevTop10", "stockUpload", "watchlist", "scanner");
        } catch (Exception e) {
            log.warn("Top10 recompute/broadcast failed: {}", e.getMessage());
        }
    }

    /** Rows whose rank, price or return changed (or newly present) versus the prior snapshot. */
    private List<Map<String, Object>> diffTop10(List<Map<String, Object>> prev, List<Map<String, Object>> curr) {
        Map<String, Map<String, Object>> prevBySym = new HashMap<>();
        for (Map<String, Object> r : prev) prevBySym.put(String.valueOf(r.get("symbol")), r);
        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map<String, Object> r : curr) {
            Map<String, Object> p = prevBySym.get(String.valueOf(r.get("symbol")));
            if (p == null) { changed.add(r); continue; }
            boolean diff = ((int) getD(p, "rank")) != ((int) getD(r, "rank"))
                || Math.abs(getD(p, "return_pct") - getD(r, "return_pct")) > 0.0001
                || Math.abs(getD(p, "current_price") - getD(r, "current_price")) > 0.0001;
            if (diff) changed.add(r);
        }
        return changed;
    }

    private boolean sameOrder(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!String.valueOf(a.get(i).get("symbol")).equals(String.valueOf(b.get(i).get("symbol")))) return false;
        }
        return true;
    }

    /** Phase 2: push per-module "refresh" nudges so each screen reloads only its own data. */
    private void broadcastModuleRefreshes(String... modules) {
        try {
            for (String m : modules) liveBroadcaster.sendModuleRefresh(m);
        } catch (Exception ignored) {}
    }

    /**
     * Keeps current_price level with the newest stored close, on its own schedule.
     *
     * <p>{@link #fillCurrentPriceFromStoredClose} was originally reachable only from
     * {@link #refreshAllPrices}, which is the wrong gate for it: that method exists to call the
     * broker, so everything that calls it is scheduled around market hours and a live token.
     * {@link #scheduledPriceRefresh} returns early via isTop10RunDue, which refuses outside
     * 09:15-15:30 IST; the startup path is off whenever sync.enabled=false; the remaining trigger
     * is a 15:35 MON-FRI cron. Between a close and the next open - and right through weekends and
     * holidays - nothing ran, so the screen kept showing whatever the last live fetch left behind.
     * Measured 2026-11-11 08:15 IST, with Monday's closes already stored: 3MINDIA still displayed
     * 28,990 from 2026-10-30 because no tick was due for another hour.
     *
     * <p>Filling from a stored close needs none of that. There is no broker call, no token and no
     * rate limit - it is one statement over rows already fetched, so it can run whenever the
     * candle table has something newer than the screen, which is precisely when the market is
     * shut. That is also when a stale price is least likely to be noticed and most likely to be
     * acted on, since the figure stops moving and so stops looking suspect.
     *
     * <p>Safe to run on any tick. The statement only writes where the stored session is newer
     * than the row's own current_price_date, so during market hours, when live quotes carry the
     * current session, it matches nothing and writes nothing; once it has filled a session it
     * will not fill it again. It stands aside during a historical backfill for the same reason
     * {@link #scheduledPriceRefresh} does - the two would contend for the same tables - and while
     * a live refresh is mid-flight, to keep their writes off each other's locks.
     */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 30_000L)
    public void scheduledStoredCloseFill() {
        if (priceRefreshInProgress.get()) {
            log.debug("[Refresh] Stored-close fill skipped: a live price refresh is running.");
            return;
        }
        if (isHistoricalBackfillRunning()) {
            log.debug("[Refresh] Stored-close fill skipped: a historical backfill is running.");
            return;
        }
        try {
            fillCurrentPriceFromStoredClose(1L);
        } catch (Exception e) {
            log.warn("[Refresh] Stored-close fill tick failed: {}", safeError(e.getMessage()));
        }
    }

    /** Post-market EOD refresh at 15:35 IST */
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void eodPriceRefresh() {
        refreshAllPrices(null, 1L, "eod");
        recomputeAndBroadcastTop10(null, 1L, "eod");
        broadcastModuleRefreshes("dashboard", "prevTop10", "stockUpload", "watchlist", "scanner", "indicators");
    }

    // ==========================================================================
    //  PREVIOUS TOP 10 — AUTOMATIC DAILY BUY/SELL CYCLE  (spec §4, §8)
    //
    //  A single per-minute tick (weekdays, 09:00–16:00 IST) checks the configured
    //  buy_time / sell_time against the wall clock. This makes the schedule fully
    //  config-driven (no restart needed) while remaining idempotent:
    //    • Buy  → deterministic batchId per day+time; duplicate creation is a no-op.
    //    • Sell → only OPEN batches bought on an earlier trading day are closed.
    //  Everything is gated by the auto_cycle / auto_buy / auto_sell toggles and
    //  skips weekends + configured market holidays. Nothing runs by default.
    // ==========================================================================

    private volatile String lastAutoBuyRunKey  = "";
    private volatile String lastAutoSellRunKey = "";

    @Scheduled(cron = "0 * 9-16 * * MON-FRI", zone = "Asia/Kolkata")
    public void prevTop10AutomationTick() {
        try {
            if (!isPrevTop10AutoCycleEnabled()) return;
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(IST);
            String today = DateUtil.today();
            if (isMarketHoliday(now.toLocalDate())) return;
            String hhmm = String.format("%02d:%02d", now.getHour(), now.getMinute());

            if (isPrevTop10AutoBuyEnabled() && hhmm.equals(getPrevTop10BuyTime())) {
                String runKey = today + "#BUY#" + hhmm;
                if (!runKey.equals(lastAutoBuyRunKey)) {
                    lastAutoBuyRunKey = runKey;
                    runAutoBuy(1L, hhmm);
                }
            }
            if (isPrevTop10AutoSellEnabled() && hhmm.equals(getPrevTop10SellTime())) {
                String runKey = today + "#SELL#" + hhmm;
                if (!runKey.equals(lastAutoSellRunKey)) {
                    lastAutoSellRunKey = runKey;
                    runAutoSell(1L, hhmm);
                }
            }
        } catch (Exception e) {
            log.warn("Prev Top 10 automation tick failed: {}", e.getMessage());
        }
    }

    /** BUY leg: snapshot Current Top 10 into a new auto batch (buy price = LTP @ buy time). */
    private void runAutoBuy(Long institutionId, String buyTime) {
        uploadExecutor.submit(() -> {
            try {
                // Refresh prices OUTSIDE the batch transaction (it self-manages per-batch txns).
                try { refreshAllPrices(null, institutionId, "auto_buy"); } catch (Exception ignore) {}
                String mode = getPrevTop10ExecutionMode();
                Map<String, Object> res = callInTransaction(() ->
                    createAutoDailyBuyBatch(null, institutionId, buyTime, mode));
                log.info("Auto BUY @ {} ({}): {}", buyTime, mode, res.get("message"));
            } catch (Exception e) {
                log.error("Auto BUY failed: {}", e.getMessage(), e);
            }
        });
    }

    /** SELL leg: close every OPEN auto batch bought on an earlier trading day. */
    private void runAutoSell(Long institutionId, String sellTime) {
        uploadExecutor.submit(() -> {
            try {
                // Refresh prices OUTSIDE the batch transaction (self-managed per-batch txns).
                try { refreshAllPrices(null, institutionId, "auto_sell"); } catch (Exception ignore) {}
                String today = DateUtil.today();
                List<Object[]> open = callInTransaction(() -> em.createNativeQuery(
                    "SELECT batch_id FROM top10_trade_batches " +
                    "WHERE institution_id=? AND strategy='AUTO_DAILY' AND status='OPEN' AND trade_date < ?")
                    .setParameter(1, institutionId).setParameter(2, today).getResultList());
                int closed = 0;
                for (Object[] row : open) {
                    String batchId = String.valueOf(row[0]);
                    try {
                        // Each batch closes in its own short transaction.
                        Map<String, Object> res = callInTransaction(() ->
                            sellAutoDailyBatch(batchId, null, institutionId, sellTime));
                        if (Boolean.TRUE.equals(res.get("success"))) closed++;
                    } catch (Exception ex) {
                        log.warn("Auto SELL failed for batch {}: {}", batchId, ex.getMessage());
                    }
                }
                log.info("Auto SELL @ {}: {} batch(es) archived.", sellTime, closed);
            } catch (Exception e) {
                log.error("Auto SELL failed: {}", e.getMessage(), e);
            }
        });
    }

    /** True when the given date is a configured market holiday (comma/space separated yyyy-MM-dd list). */
    public boolean isMarketHoliday(java.time.LocalDate date) {
        if (date == null) return false;
        String raw = getMarketHolidaysRaw();
        if (raw == null || raw.isBlank()) return false;
        String iso = date.toString();
        for (String tok : raw.split("[,;\\s]+")) {
            if (!tok.isBlank() && tok.trim().equals(iso)) return true;
        }
        return false;
    }

    /** True on a normal trading day (weekday and not a configured holiday). */
    public boolean isTradingDay(java.time.LocalDate date) {
        if (date == null) return false;
        int dow = date.getDayOfWeek().getValue();
        return dow < 6 && !isMarketHoliday(date);
    }

    /**
     * Create the daily auto-buy batch from the Current Top 10.
     * Stores symbol, buy_date, buy_time, buy_price (LTP @ buy time), preferring the
     * user's real executed BUY price when one exists (and use_real_price_if_executed).
     * strategy='AUTO_DAILY'; idempotent on batchId (day + time).
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> createAutoDailyBuyBatch(Long userId, Long institutionId, String buyTime, String mode) {
        Map<String, Object> ranking = getLatestRankingFull(userId, institutionId);
        List<Map<String, Object>> currentTop10 = (List<Map<String, Object>>) ranking.get("current_top10");
        if (currentTop10 == null || currentTop10.isEmpty()) {
            return Map.of("success", false, "error", "No Current Top 10 available for auto buy.");
        }
        String now = DateUtil.now();
        String today = DateUtil.today();
        if (buyTime == null || buyTime.isBlank()) buyTime = now.substring(11, 16);
        String batchId = "AUTO_" + today.replace("-", "") + "_" + buyTime.replace(":", "");

        List<Object[]> existing = em.createNativeQuery(
            "SELECT batch_id FROM top10_trade_batches WHERE batch_id=? LIMIT 1")
            .setParameter(1, batchId).getResultList();
        if (!existing.isEmpty()) {
            return Map.of("success", false, "error", "Auto batch already exists for " + today + " " + buyTime + ".");
        }

        String tradeType = "LIVE".equalsIgnoreCase(mode) ? "LIVE" : "PAPER";
        em.createNativeQuery(
            "INSERT INTO top10_trade_batches(batch_id,user_id,institution_id,trade_date,buy_time,status,strategy,trade_type,total_stocks,auto_generated,created_at,updated_at) " +
            "VALUES(?,?,?,?,?,'OPEN','AUTO_DAILY',?,?,1,?,?)")
            .setParameter(1, batchId).setParameter(2, userId).setParameter(3, institutionId)
            .setParameter(4, today).setParameter(5, buyTime).setParameter(6, tradeType)
            .setParameter(7, currentTop10.size()).setParameter(8, now).setParameter(9, now)
            .executeUpdate();

        int stored = 0;
        for (Map<String, Object> stock : currentTop10) {
            try {
                String symbol = String.valueOf(stock.getOrDefault("symbol", ""));
                if (symbol.isBlank()) continue;
                double buyPrice = getD(stock, "current_price");
                if (buyPrice <= 0) buyPrice = getD(stock, "base_price");
                String execSource = "SIM";
                if (useRealPriceIfExecuted()) {
                    Double realPx = getRealExecutedPrice(symbol, "BUY", today, institutionId);
                    if (realPx != null && realPx > 0) { buyPrice = realPx; execSource = "REAL"; }
                }
                int rank = (int) getD(stock, "rank");
                String sector = String.valueOf(stock.getOrDefault("sector", ""));
                String mcap = String.valueOf(stock.getOrDefault("market_cap", ""));
                em.createNativeQuery(
                    "INSERT INTO top10_trade_batch_stocks(batch_id,symbol,rank,buy_price,buy_date,buy_time,sector,market_cap,execution_source,status,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,'OPEN',?)")
                    .setParameter(1, batchId).setParameter(2, symbol).setParameter(3, rank)
                    .setParameter(4, buyPrice).setParameter(5, today).setParameter(6, buyTime)
                    .setParameter(7, sector).setParameter(8, mcap).setParameter(9, execSource)
                    .setParameter(10, now).executeUpdate();
                stored++;
            } catch (Exception ex) {
                log.warn("Auto buy skipped a stock in batch {}: {}", batchId, ex.getMessage());
            }
        }

        auditLog(institutionId, userId, "AUTO_DAILY_BUY", "top10_trade_batches", batchId,
            Map.of("stocks", stored, "trade_type", tradeType, "buy_time", buyTime));
        return Map.of("success", true, "batch_id", batchId, "stocks", stored,
            "message", "Auto buy batch " + batchId + " created with " + stored + " stocks.");
    }

    /**
     * Sell/close an auto-daily batch: sell_price = LTP @ sell time (or real executed
     * SELL price), storing sell_date, sell_time, profit_loss and return_pct per stock,
     * then aggregate winners/losers/avg and archive the batch (status=CLOSED).
     */
    @Transactional
    public Map<String, Object> sellAutoDailyBatch(String batchId, Long userId, Long institutionId, String sellTime) {
        List<Object[]> batch = em.createNativeQuery(
            "SELECT status FROM top10_trade_batches WHERE batch_id=? AND institution_id=?")
            .setParameter(1, batchId).setParameter(2, institutionId).getResultList();
        if (batch.isEmpty()) return Map.of("success", false, "error", "Batch not found.");
        if ("CLOSED".equals(String.valueOf(batch.get(0)[0])))
            return Map.of("success", false, "error", "Batch already closed.");

        String now = DateUtil.now();
        String today = DateUtil.today();
        if (sellTime == null || sellTime.isBlank()) sellTime = now.substring(11, 16);

        List<Object[]> stocks = em.createNativeQuery(
            "SELECT id,symbol,buy_price FROM top10_trade_batch_stocks WHERE batch_id=? AND status='OPEN'")
            .setParameter(1, batchId).getResultList();
        List<String> symbols = stocks.stream().map(r -> String.valueOf(r[1])).toList();
        Map<String, Double> currentPrices = resolveCurrentPricesBatch(symbols, institutionId);

        int winning = 0, losing = 0; double totalReturn = 0;
        for (Object[] s : stocks) {
            try {
                long id = ((Number) s[0]).longValue();
                String symbol = String.valueOf(s[1]);
                double buyPrice = getNumber(s[2]);
                double sellPrice = currentPrices.getOrDefault(normalizeSymbol(symbol), 0.0);
                if (useRealPriceIfExecuted()) {
                    Double realPx = getRealExecutedPrice(symbol, "SELL", today, institutionId);
                    if (realPx != null && realPx > 0) sellPrice = realPx;
                }
                if (sellPrice <= 0) sellPrice = buyPrice;
                double profitLoss = Math.round((sellPrice - buyPrice) * 100) / 100.0;
                double returnPct = buyPrice > 0 ? Math.round(((sellPrice - buyPrice) / buyPrice) * 100 * 100) / 100.0 : 0;
                if (returnPct >= 0) winning++; else losing++;
                totalReturn += returnPct;
                em.createNativeQuery(
                    "UPDATE top10_trade_batch_stocks SET sell_price=?,return_pct=?,profit_loss=?,sell_date=?,sell_time=?,status='CLOSED' WHERE id=?")
                    .setParameter(1, sellPrice).setParameter(2, returnPct).setParameter(3, profitLoss)
                    .setParameter(4, today).setParameter(5, sellTime).setParameter(6, id)
                    .executeUpdate();
            } catch (Exception ex) {
                log.warn("Auto sell skipped a stock in batch {}: {}", batchId, ex.getMessage());
            }
        }
        int count = stocks.size();
        double avgReturn = count == 0 ? 0 : Math.round((totalReturn / count) * 100) / 100.0;
        em.createNativeQuery(
            "UPDATE top10_trade_batches SET sell_time=?,status='CLOSED',winning_stocks=?,losing_stocks=?," +
            "avg_return_pct=?,total_return_pct=?,closed_at=?,updated_at=? WHERE batch_id=?")
            .setParameter(1, sellTime).setParameter(2, winning).setParameter(3, losing)
            .setParameter(4, avgReturn).setParameter(5, Math.round(totalReturn * 100) / 100.0)
            .setParameter(6, now).setParameter(7, now).setParameter(8, batchId)
            .executeUpdate();

        auditLog(institutionId, userId, "AUTO_DAILY_SELL", "top10_trade_batches", batchId,
            Map.of("avg_return", avgReturn, "winning", winning, "losing", losing));
        return Map.of("success", true, "batch_id", batchId, "avg_return", avgReturn,
            "winning", winning, "losing", losing,
            "message", "Auto batch " + batchId + " archived. Avg return: " + avgReturn + "%");
    }

    /** Look up the user's most recent real executed fill price for a symbol/side on a date. */
    private Double getRealExecutedPrice(String symbol, String side, String date, Long institutionId) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT avg_fill_price, price FROM real_trades " +
                "WHERE symbol=? AND UPPER(trade_type)=? AND order_status='COMPLETE' " +
                "AND (institution_id=? OR ? IS NULL) AND COALESCE(filled_at, created_at) LIKE ? " +
                "ORDER BY COALESCE(filled_at, created_at) DESC LIMIT 1")
                .setParameter(1, symbol).setParameter(2, side.toUpperCase())
                .setParameter(3, institutionId).setParameter(4, institutionId)
                .setParameter(5, date + "%").getResultList();
            if (rows.isEmpty()) return null;
            Object[] r = rows.get(0);
            double avg = getNumber(r[0]);
            if (avg > 0) return avg;
            double px = getNumber(r[1]);
            return px > 0 ? px : null;
        } catch (Exception e) {
            log.debug("Real executed price lookup skipped for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    public void ensureFreshPrices(Long userId, Long institutionId, String reason) {
        if (institutionId == null) return;
        long now = System.currentTimeMillis();
        synchronized (screenRefreshLock) {
            if (screenRefreshRunning || now - lastScreenRefreshStartedMs < getScreenRefreshGapMs()) return;
            long staleAfter = isMarketOpen() ? getMarketOpenStaleSec() : getMarketClosedStaleSec();
            if (!isTrackedPriceStale(userId, institutionId, staleAfter)) return;
            screenRefreshRunning = true;
            lastScreenRefreshStartedMs = now;
        }
        String refreshType = reason == null || reason.isBlank() ? "screen_load" : reason;
        uploadExecutor.submit(() -> {
            try {
                // refreshAllPrices manages its own per-batch transactions — call it
                // directly (no outer transaction) so locks are not held across HTTP.
                refreshAllPrices(userId, institutionId, refreshType);
            } catch (Exception e) {
                log.warn("Screen price refresh failed: {}", e.getMessage());
            } finally {
                synchronized (screenRefreshLock) {
                    screenRefreshRunning = false;
                }
            }
        });
    }

    private boolean isTrackedPriceStale(Long userId, Long institutionId, long staleAfterSeconds) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*), MAX(price_updated_at), MAX(last_refreshed), MAX(current_price_date) " +
                "FROM tracked_stocks WHERE institution_id=? AND is_active=1");
            if (userId != null) sql.append(" AND user_id=?");
            var q = em.createNativeQuery(sql.toString()).setParameter(1, institutionId);
            if (userId != null) q.setParameter(2, userId);
            Object[] r = (Object[]) q.getSingleResult();
            long total = r[0] == null ? 0 : ((Number) r[0]).longValue();
            if (total == 0) return false;
            String latest = latestText(r[1], r[2]);
            String currentPriceDate = asText(r[3]);
            if (latest.isBlank()) return true;
            if (isMarketOpen() && !DateUtil.today().equals(normalizeStoredDate(currentPriceDate))) return true;

            // Outside a session, stored prices that already match the newest session are not
            // stale - they are final. Ageing them out after fifteen minutes meant every screen
            // load through the evening, the weekend and every holiday fired a full Dhan refresh
            // to fetch numbers that cannot have changed. Age only means something while the
            // market can still move the price.
            if (!isMarketOpen()) {
                String stored = normalizeStoredDate(currentPriceDate);
                String newest = lastTradedAtIST();
                String newestDay = newest == null || newest.length() < 10 ? null : newest.substring(0, 10);
                if (stored != null && stored.equals(newestDay)) return false;
            }
            java.time.LocalDateTime updated = parseDateTime(latest);
            if (updated == null) return true;
            long age = java.time.Duration.between(updated, java.time.LocalDateTime.now(IST)).getSeconds();
            return age > staleAfterSeconds;
        } catch (Exception e) {
            log.debug("Tracked price stale check skipped: {}", e.getMessage());
            return false;
        }
    }

    // NOTE: intentionally NOT @Transactional. This method orchestrates minutes of
    // Dhan HTTP calls (throttling, 429 backoff). Running it in a single transaction
    // held row locks on tracked_stocks for the whole refresh, causing "lock timeout"
    // failures for concurrent writers (base-price fetcher, broker save). Each DB write
    // below now runs in its own short transaction so locks are released immediately.
    public Map<String, Object> refreshAllPrices(Long triggeredBy, Long institutionId, String refreshType) {
        // Serialize refreshes: if one is already writing these tables, skip this run
        // rather than pile on and cause lock-timeout contention. The next scheduled
        // tick (or a later manual click) will refresh.
        if (!priceRefreshInProgress.compareAndSet(false, true)) {
            Map<String, Object> busy = new LinkedHashMap<>();
            busy.put("skipped", true);
            busy.put("message", "A price refresh is already in progress. Please wait a moment and try again.");
            busy.put("refreshed_at", DateUtil.now());
            return busy;
        }
        try {
        long start = System.currentTimeMillis();
        String refreshStartedAt = callInTransaction(() -> insertRefreshLog(institutionId, triggeredBy, refreshType));

        List<String> symbols = getActiveSymbols(triggeredBy, institutionId);
        int updated = 0, failed = 0;
        // Per-batch transactions happen inside refreshPricesFromDhan → refreshDhanBatchWithSplits.
        long tDhan0 = System.currentTimeMillis();
        DhanPriceRefreshOutcome dhanOutcome = refreshPricesFromDhan(symbols, triggeredBy, institutionId);
        long tDhanMs = System.currentTimeMillis() - tDhan0;
        updated += dhanOutcome.updated;

        // Whatever the live feed could not price, price from the newest stored close.
        //
        // Runs after the live path and never against it: the statement only touches rows whose
        // stored session is newer than what they already hold, so a live quote always wins. It
        // runs on every refresh rather than only when the feed is down, because a feed that
        // returns some symbols and not others leaves exactly the same hole for the rest.
        int closeFallbackUpdated = fillCurrentPriceFromStoredClose(institutionId);
        updated += closeFallbackUpdated;

        failed = Math.max(0, symbols.size() - updated);
        List<String> missingBasePrices = getSymbolsMissingBasePrice(triggeredBy, institutionId);
        if (!missingBasePrices.isEmpty()) {
            fetchBasePricesFromDhanAsync(missingBasePrices, triggeredBy, institutionId);
        }

        // Store daily snapshot for all symbols (own short transaction)
        long tSnap0 = System.currentTimeMillis();
        runInTransaction(() -> storeDailyPriceSnapshot(institutionId));
        long tSnapMs = System.currentTimeMillis() - tSnap0;

        // Prices have just moved, so the memoised candidate set is now wrong. Dropping it here -
        // before the ranking engine runs - means the ranking recomputes against the new prices and
        // the panels then share that fresh result instead of each rebuilding it.
        invalidateCandidateCache();

        // Recalculate rankings for the same user scope that triggered the refresh (own transaction)
        long tRank0 = System.currentTimeMillis();
        runInTransaction(() -> runRankingEngine(triggeredBy, institutionId, "auto"));
        long tRankMs = System.currentTimeMillis() - tRank0;

        long elapsed = System.currentTimeMillis() - start;
        // Per-phase timing so a slow refresh reveals WHICH stage is the bottleneck.
        log.info("[Refresh] {} stocks in {} ms — dhan-fetch {} ms ({} api calls), snapshot {} ms, ranking {} ms",
            symbols.size(), elapsed, tDhanMs, dhanOutcome.apiCalls, tSnapMs, tRankMs);
        final int fUpdated = updated, fFailed = failed;
        runInTransaction(() -> completeRefreshLog(institutionId, refreshStartedAt, fUpdated, fFailed, elapsed,
            dhanOutcome.connected ? "dhan" : "fallback"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", updated);
        result.put("failed", failed);
        result.put("total", symbols.size());
        result.put("duration_ms", elapsed);
        result.put("refreshed_at", DateUtil.now());
        result.put("source", "Dhan LTP");
        result.put("dhan_connected", dhanOutcome.connected);
        result.put("dhan_updated", dhanOutcome.updated);
        result.put("fallback_updated", closeFallbackUpdated);
        result.put("dhan_missing_instruments", dhanOutcome.missingInstruments);
        result.put("dhan_api_calls", dhanOutcome.apiCalls);
        result.put("base_price_retry_started", missingBasePrices.size());
        String message = dhanOutcome.message;
        if (closeFallbackUpdated > 0) {
            message = message + " " + closeFallbackUpdated + " stock(s) were filled from their "
                + "most recent stored closing price instead, each shown with the date of the "
                + "session it came from.";
        }
        result.put("message", message);
        return result;
        } finally {
            priceRefreshInProgress.set(false);
        }
    }

    private DhanPriceRefreshOutcome refreshPricesFromDhan(List<String> symbols, Long userId, Long institutionId) {
        DhanPriceRefreshOutcome outcome = new DhanPriceRefreshOutcome();
        if (symbols == null || symbols.isEmpty()) {
            outcome.message = "No active tracked stocks found.";
            return outcome;
        }

        Optional<DhanBrokerService.Credential> credential = findVerifiedDhanCredential(userId, institutionId);
        if (credential.isEmpty()) {
            outcome.message = "No verified Dhan broker token found. Current price was not refreshed.";
            return outcome;
        }

        outcome.connected = true;
        Map<String, DhanInstrumentRef> instruments = resolveDhanInstruments(symbols);
        outcome.missingInstruments = Math.max(0, symbols.size() - instruments.size());
        if (instruments.isEmpty()) {
            outcome.message = "Dhan is connected, but tracked symbols are not mapped. Fetch Public Stock List in Data Warehouse, then refresh again.";
            return outcome;
        }

        List<DhanInstrumentRef> refs = new ArrayList<>(instruments.values());
        Map<String, String> headers = Map.of(
            "Accept", "application/json",
            "Content-Type", "application/json",
            "access-token", credential.get().accessToken(),
            "client-id", credential.get().clientId()
        );

        for (int from = 0; from < refs.size(); from += 1000) {
            int to = Math.min(from + 1000, refs.size());
            List<DhanInstrumentRef> batch = refs.subList(from, to);
            try {
                refreshDhanBatchWithSplits(batch, institutionId, headers, outcome);
                if (to < refs.size()) Thread.sleep(1100);
            } catch (Exception e) {
                String error = e.getMessage() == null ? "" : e.getMessage();
                if (isDhanAuthenticationError(error)) {
                    outcome.message = "Dhan rejected the broker token. Reconnect or verify the Dhan broker token, then refresh again.";
                    markDhanCredentialInvalid(credential.get().id(), outcome.message);
                    log.warn("Dhan LTP authentication failed: {}", error);
                    break;
                }
                outcome.message = "Dhan LTP failed for part of the request. Remaining symbols were not updated.";
                log.warn("Dhan LTP refresh failed for {} instruments: {}", batch.size(), error);
            }
        }

        if (outcome.message.isBlank()) {
            if (outcome.updated > 0) {
                outcome.message = "Dhan LTP refreshed live prices. Unmapped or failed symbols were not updated.";
            } else if (outcome.emptyQuoteBatches > 0) {
                // "no live prices were returned" was true but gave nothing to act on, and the
                // screen went on showing the last good price with no hint of its age. Naming the
                // one thing that distinguishes this from an expired token - that the call
                // succeeded - points at the subscription rather than at reconnecting, which is
                // what an unexplained empty refresh invites you to try first.
                outcome.message = "Dhan accepted the request but returned no quotes, so current "
                    + "prices are unchanged and still show their last known value. The broker "
                    + "token is working - historical candles still import - so check the Dhan "
                    + "Data API (live market feed) subscription on your broker account.";
            } else {
                outcome.message = "Dhan is connected, but no live prices were returned.";
            }
        }
        return outcome;
    }

    private void refreshDhanBatchWithSplits(List<DhanInstrumentRef> batch, Long institutionId,
                                            Map<String, String> headers,
                                            DhanPriceRefreshOutcome outcome) throws Exception {
        if (batch == null || batch.isEmpty()) return;
        try {
            Map<String, List<Long>> payload = new LinkedHashMap<>();
            for (DhanInstrumentRef ref : batch) {
                String segment = normalizeDhanExchangeSegment(ref.exchangeSegment);
                if (segment.isBlank() || ref.securityId == null || ref.securityId.isBlank()) continue;
                payload.computeIfAbsent(segment, k -> new ArrayList<>()).add(Long.parseLong(ref.securityId));
            }
            if (payload.isEmpty()) return;

            dhanThrottle();
            String json = http.postJsonWithRetry(getDhanApi() + "/marketfeed/quote", headers, payload, 15000);
            dhanOnSuccess();
            JsonNode root = mapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) data = root;

            List<String> priceSymbols = new ArrayList<>();
            List<Double> priceValues = new ArrayList<>();
            List<DhanInstrumentRef> quoteRefs = new ArrayList<>();
            List<double[]> quoteOhlcv = new ArrayList<>();

            for (DhanInstrumentRef ref : batch) {
                String segment = normalizeDhanExchangeSegment(ref.exchangeSegment);
                JsonNode quote = data.path(segment).path(ref.securityId);
                double price = firstNumber(quote, "last_price", "ltp", "LTP", "lastPrice", "lastTradedPrice", "last_traded_price");
                if (price > 0) {
                    priceSymbols.add(ref.symbol);
                    priceValues.add(price);
                    double open   = firstNumber(quote, "open", "openPrice", "open_price");
                    double high   = firstNumber(quote, "high", "highPrice", "high_price");
                    double low    = firstNumber(quote, "low", "lowPrice", "low_price");
                    double close  = firstNumber(quote, "close", "closePrice", "close_price", "prev_close");
                    double volume = firstNumber(quote, "volume", "totalVolume", "total_volume");
                    quoteRefs.add(ref);
                    quoteOhlcv.add(new double[]{price, open, high, low, close, volume});
                    outcome.updatedSymbols.add(normalizeSymbol(ref.symbol));
                }
            }

            if (!priceSymbols.isEmpty()) {
                // batchUpdateCurrentPrices() and batchUpsertLiveQuotes() each commit in
                // their own short transaction (via runInTransaction). Now that
                // refreshAllPrices() is no longer one big transaction, these commit
                // per-batch, releasing tracked_stocks locks immediately.
                int batchUpdated = batchUpdateCurrentPrices(priceSymbols, priceValues, institutionId);
                outcome.updated += batchUpdated;
                batchUpsertLiveQuotes(quoteRefs, quoteOhlcv);
            } else {
                // The silent branch on the path that refreshes every tracked stock.
                //
                // A successful call carrying no quotes leaves priceSymbols empty, so the block
                // above is skipped, nothing is written, and the only trace left was
                // outcome.apiCalls++ below - which made the refresh read as though it had worked.
                // Observed from 2026-11-01: both batches of the 1,271-stock refresh returned
                // HTTP 200 with a 41-byte body, {"data":{"NSE_EQ":{}},"status":"success"}, and
                // nothing anywhere said so. The same token kept importing candles from
                // /charts/historical, which is the signature of a live-market-data entitlement
                // rather than a broken credential, since Dhan sells live quotes separately.
                //
                // This is not an exception, so none of the handling below sees it: not
                // isDhanAuthenticationError (no DH-901), not isDhanRateLimit (no DH-904), and not
                // the bisecting retry - splitting a batch that is answered correctly only asks
                // the same question twice.
                outcome.emptyQuoteBatches++;
                log.warn("[Refresh] Dhan /marketfeed/quote answered {} instrument(s) with no "
                       + "usable price. The call succeeded, so this is neither a token nor a "
                       + "rate-limit failure - the response carried no quotes. Check the Dhan "
                       + "Data API (live market feed) subscription on the broker account; "
                       + "historical candles use a different entitlement and keep working. "
                       + "Response was: {}",
                    batch.size(), json == null ? "(empty)"
                        : json.length() > 400 ? json.substring(0, 400) + "..." : json);
            }

            outcome.apiCalls++;
        } catch (Exception e) {
            String error = e.getMessage() == null ? "" : e.getMessage();
            if (isDhanAuthenticationError(error)) throw e;
            if (isDhanRateLimit(error)) {
                dhanOn429();
                refreshDhanBatchWithSplits(batch, institutionId, headers, outcome);
                return;
            }
            if (batch.size() > 25) {
                int mid = batch.size() / 2;
                refreshDhanBatchWithSplits(batch.subList(0, mid), institutionId, headers, outcome);
                refreshDhanBatchWithSplits(batch.subList(mid, batch.size()), institutionId, headers, outcome);
                return;
            }
            outcome.failedBatches++;
            outcome.message = "Dhan LTP failed for a small symbol batch. Those symbols were not updated.";
            log.warn("Dhan LTP refresh failed for {} instruments after split: {}", batch.size(), error);
        }
    }

    private boolean isDhanAuthenticationError(String error) {
        String text = error == null ? "" : error.toLowerCase(Locale.ROOT);
        return text.contains("dh-901") || text.contains("invalid_authentication")
            || text.contains("authentication failed") || text.contains("token invalid")
            || text.contains("invalid token") || text.contains("token is invalid")
            || text.contains("http 401");
    }

    private boolean isDhanPriceSource(String source) {
        return source != null && source.toUpperCase(Locale.ROOT).startsWith("DHAN");
    }

    private boolean hasVerifiedDhanCredential(Long userId, Long institutionId) {
        return findVerifiedDhanCredential(userId, institutionId).isPresent();
    }

    private volatile boolean dhanTokenBlocked = false;

    public void clearDhanTokenBlock() {
        dhanTokenBlocked = false;
        log.info("Dhan token block cleared — API calls will resume on next trigger.");
    }

    private void markDhanCredentialInvalid(Long credentialId, String reason) {
        if (credentialId == null) return;
        dhanTokenBlocked = true;
        try {
            runInTransaction(() -> em.createNativeQuery(
                "UPDATE broker_credentials SET is_verified=0, verification_msg=?, updated_at=? " +
                "WHERE id=? AND broker_name='dhan'")
                .setParameter(1, safeError(reason))
                .setParameter(2, DateUtil.now())
                .setParameter(3, credentialId)
                .executeUpdate());
            log.warn("Dhan credential {} marked invalid. All Dhan API calls blocked until broker is reconnected.", credentialId);
        } catch (Exception e) {
            log.debug("Could not mark Dhan credential invalid: {}", e.getMessage());
        }
    }

    private Optional<DhanBrokerService.Credential> findVerifiedDhanCredential(Long userId, Long institutionId) {
        if (dhanTokenBlocked) {
            log.debug("Dhan API calls blocked — token was rejected. Reconnect broker to resume.");
            return Optional.empty();
        }
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT id, client_id, access_token_enc, token_expiry FROM broker_credentials " +
                "WHERE broker_name='dhan' AND is_active=1 AND is_verified=1 " +
                "AND access_token_enc IS NOT NULL AND access_token_enc<>'' ");
            if (userId != null) {
                sql.append("AND user_id=? ");
            } else {
                sql.append("AND institution_id=? ");
            }
            sql.append("ORDER BY id DESC LIMIT 1");

            var query = em.createNativeQuery(sql.toString());
            query.setParameter(1, userId != null ? userId : institutionId);
            @SuppressWarnings("unchecked")
            List<Object[]> rows = query.getResultList();
            if (rows.isEmpty()) return Optional.empty();
            Object[] row = rows.get(0);

            // Broker is only "connected" if the saved token is still valid. Never
            // fire a Dhan API call with an expired token — treat it as not
            // connected and wait until the broker is re-authenticated (which
            // refreshes token_expiry and clears the block).
            String expiry = row[3] == null ? "" : String.valueOf(row[3]);
            if (isDhanTokenExpired(expiry)) {
                log.debug("Dhan token expired ({}). Skipping Dhan API calls until the broker is reconnected.", expiry);
                return Optional.empty();
            }

            return Optional.of(new DhanBrokerService.Credential(
                ((Number) row[0]).longValue(),
                String.valueOf(row[1]),
                dhanBrokerService.decode(String.valueOf(row[2]))
            ));
        } catch (Exception e) {
            log.debug("Verified Dhan credential lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * True only if a Dhan token_expiry timestamp is definitely in the past.
     * Blank or unparseable values return false (let the API decide) so we never
     * block a genuinely valid connection over a formatting quirk.
     */
    private boolean isDhanTokenExpired(String expiry) {
        if (expiry == null || expiry.isBlank() || "null".equalsIgnoreCase(expiry)) return false;
        String text = expiry.trim();
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        try { return java.time.OffsetDateTime.parse(text).toInstant().isBefore(java.time.Instant.now()); } catch (Exception ignored) {}
        try { return java.time.LocalDateTime.parse(text, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .isBefore(java.time.LocalDateTime.now(ist)); } catch (Exception ignored) {}
        try { return java.time.LocalDate.parse(text, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE).atTime(23, 59, 59)
                .isBefore(java.time.LocalDateTime.now(ist)); } catch (Exception ignored) {}
        for (String pattern : java.util.List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm")) {
            try { return java.time.LocalDateTime.parse(text, java.time.format.DateTimeFormatter.ofPattern(pattern))
                    .isBefore(java.time.LocalDateTime.now(ist)); } catch (Exception ignored) {}
        }
        return false; // unparseable → do not block; let the API call proceed
    }

    private Map<String, DhanInstrumentRef> resolveDhanInstruments(List<String> symbols) {
        Map<String, DhanInstrumentRef> resolved = new LinkedHashMap<>();
        List<String> normalized = symbols.stream()
            .map(this::normalizeSymbol)
            .filter(s -> !s.isBlank())
            .distinct()
            .toList();
        // dhan_instruments is the single instrument master. broker_instruments held a
        // byte-for-byte copy of the same rows, so this fallback could never resolve a symbol
        // the master had already missed.
        resolved.putAll(findDhanInstruments(normalized));
        return resolved;
    }

    private Map<String, DhanInstrumentRef> findDhanInstruments(List<String> symbols) {
        Map<String, DhanInstrumentRef> found = new LinkedHashMap<>();
        if (symbols.isEmpty()) return found;
        try {
            Map<String, String> aliasToSymbol = aliasToTrackedSymbol(symbols);
            List<String> aliases = new ArrayList<>(aliasToSymbol.keySet());
            String in = IntStream.range(0, aliases.size()).mapToObj(i -> "?").collect(Collectors.joining(","));
            var query = em.createNativeQuery(
                "SELECT symbol_name, trading_symbol, display_name, underlying_symbol, " +
                "security_id, COALESCE(NULLIF(exchange_segment,''),'NSE_EQ') AS exchange_segment, instrument " +
                "FROM dhan_instruments WHERE security_id IS NOT NULL AND security_id<>'' " +
                "AND UPPER(COALESCE(exchange_segment,'')) IN ('NSE_EQ','BSE_EQ') " +
                "AND UPPER(COALESCE(instrument,'')) IN ('EQUITY','ES','EQ') " +
                "AND UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(underlying_symbol,''),'-EQ',''),'-BE',''),'-BZ',''),'.NS',''),' ','')) IN (" + in + ") " +
                "ORDER BY CASE WHEN exchange_segment='NSE_EQ' THEN 0 WHEN exchange_segment='BSE_EQ' THEN 1 ELSE 2 END");
            int p = 1;
            for (String alias : aliases) query.setParameter(p++, alias);
            @SuppressWarnings("unchecked")
            List<Object[]> rows = query.getResultList();
            for (Object[] row : rows) {
                String symbol = matchTrackedSymbol(aliasToSymbol, row[3]);
                if (!symbol.isBlank() && !found.containsKey(symbol)) {
                    found.put(symbol, new DhanInstrumentRef(symbol, String.valueOf(row[4]), String.valueOf(row[5]), String.valueOf(row[6])));
                }
            }
        } catch (Exception e) {
            log.debug("Dhan instrument batch lookup: {}", e.getMessage());
        }
        return found;
    }

    private Map<String, DhanInstrumentRef> resolveDhanInstrumentsOneByOne(List<String> symbols) {
        Map<String, DhanInstrumentRef> resolved = new LinkedHashMap<>();
        for (String rawSymbol : symbols) {
            String symbol = normalizeSymbol(rawSymbol);
            if (symbol.isBlank() || resolved.containsKey(symbol)) continue;

            Optional<DhanInstrumentRef> ref = findDhanInstrument(symbol);
            ref.ifPresent(r -> resolved.put(symbol, r));
        }
        return resolved;
    }

    private Optional<DhanInstrumentRef> findDhanInstrument(String symbol) {
        try {
            Map<String, String> aliasToSymbol = aliasToTrackedSymbol(List.of(symbol));
            List<String> aliases = new ArrayList<>(aliasToSymbol.keySet());
            if (aliases.isEmpty()) return Optional.empty();
            String in = IntStream.range(0, aliases.size()).mapToObj(i -> "?").collect(Collectors.joining(","));
            var query = em.createNativeQuery(
                "SELECT security_id, COALESCE(NULLIF(exchange_segment,''),'NSE_EQ'), instrument, underlying_symbol " +
                "FROM dhan_instruments WHERE security_id IS NOT NULL AND security_id<>'' " +
                "AND UPPER(COALESCE(exchange_segment,'')) IN ('NSE_EQ','BSE_EQ') " +
                "AND UPPER(COALESCE(instrument,'')) IN ('EQUITY','ES','EQ') " +
                "AND UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(underlying_symbol,''),'-EQ',''),'-BE',''),'-BZ',''),'.NS',''),' ','')) IN (" + in + ") " +
                "ORDER BY CASE WHEN exchange_segment='NSE_EQ' THEN 0 WHEN exchange_segment='BSE_EQ' THEN 1 ELSE 2 END LIMIT 1");
            for (int i = 0; i < aliases.size(); i++) query.setParameter(i + 1, aliases.get(i));
            @SuppressWarnings("unchecked")
            List<Object[]> rows = query.getResultList();
            if (rows.isEmpty()) return Optional.empty();
            Object[] row = rows.get(0);
            return Optional.of(new DhanInstrumentRef(symbol, String.valueOf(row[0]), String.valueOf(row[1]), String.valueOf(row[2])));
        } catch (Exception e) {
            log.debug("Dhan instrument lookup {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    /** Public alongside {@link #resolveCurrentPricesBatch}: callers must key on the same form. */
    public String normalizeSymbol(String symbol) {
        if (symbol == null) return "";
        String s = symbol.trim().toUpperCase(Locale.ROOT)
            .replace(".NS", "")
            .replace(".BO", "")
            .replace(" ", "");
        for (String suffix : List.of("-EQ", "-BE", "-BZ", "-SM", "-ST")) {
            if (s.endsWith(suffix)) return s.substring(0, s.length() - suffix.length());
        }
        return s;
    }

    private Map<String, String> aliasToTrackedSymbol(List<String> symbols) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String symbol : symbols) {
            String normalized = normalizeSymbol(symbol);
            if (normalized.isBlank()) continue;
            aliases.putIfAbsent(normalized, normalized);
            aliases.putIfAbsent(normalized + "-EQ", normalized);
            aliases.putIfAbsent(normalized + "-BE", normalized);
            aliases.putIfAbsent(normalized + "-BZ", normalized);
            String dhanSymbol = legacyDhanSymbol(normalized);
            if (!dhanSymbol.isBlank()) {
                aliases.putIfAbsent(dhanSymbol, normalized);
                aliases.putIfAbsent(dhanSymbol + "-EQ", normalized);
                aliases.putIfAbsent(dhanSymbol + "-BE", normalized);
                aliases.putIfAbsent(dhanSymbol + "-BZ", normalized);
            }
        }
        return aliases;
    }

    private String legacyDhanSymbol(String normalizedSymbol) {
        return switch (normalizeSymbol(normalizedSymbol)) {
            case "MIRCELECTR" -> "ONIDA";
            default -> "";
        };
    }

    private String mappingMissingReason(String symbol) {
        String normalized = normalizeSymbol(symbol);
        if ("CIGNITITEC".equals(normalized)) {
            return "Dhan instrument master does not list CIGNITITEC. COFORGE exists in Dhan master; verify corporate action before replacing uploaded symbol.";
        }
        return "symbol not mapped in Dhan instrument master.";
    }

    private String matchTrackedSymbol(Map<String, String> aliasToSymbol, Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate == null) continue;
            String normalized = normalizeSymbol(String.valueOf(candidate));
            String symbol = aliasToSymbol.get(normalized);
            if (symbol != null) return symbol;
        }
        return "";
    }

    private String normalizeDhanExchangeSegment(String exchangeSegment) {
        String s = exchangeSegment == null ? "" : exchangeSegment.trim().toUpperCase(Locale.ROOT);
        if (s.equals("NSE") || s.equals("NSE_EQ")) return "NSE_EQ";
        if (s.equals("BSE") || s.equals("BSE_EQ")) return "BSE_EQ";
        return s.endsWith("_EQ") ? s : "";
    }

    private static class DhanInstrumentRef {
        final String symbol;
        final String securityId;
        final String exchangeSegment;
        final String instrument;

        DhanInstrumentRef(String symbol, String securityId, String exchangeSegment, String instrument) {
            this.symbol = symbol;
            this.securityId = securityId;
            this.exchangeSegment = exchangeSegment;
            this.instrument = instrument == null ? "" : instrument;
        }

        String historicalInstrument() {
            String value = instrument.trim().toUpperCase(Locale.ROOT);
            if (value.equals("ES") || value.equals("EQ") || value.isBlank()) return "EQUITY";
            return value;
        }
    }

    private static class DhanPriceRefreshOutcome {
        boolean connected;
        int updated;
        int missingInstruments;
        int apiCalls;
        int failedBatches;
        /**
         * Batches Dhan answered successfully but with no quote in them.
         *
         * <p>Distinct from failedBatches, which counts calls that threw. An empty-but-successful
         * response is the case that had no counter at all, so a refresh that updated nothing
         * reported apiCalls=2, failedBatches=0 and looked healthy.
         */
        int emptyQuoteBatches;
        String message = "";
        Set<String> updatedSymbols = new HashSet<>();
    }

    @Transactional
    private void updateCurrentPrice(String symbol, Long institutionId, double price) {
        em.createNativeQuery(
            "UPDATE tracked_stocks SET " +
            "current_price=?, last_traded_price=?, price_updated_at=?, " +
            "return_pct=CASE WHEN base_price IS NOT NULL AND base_price>0 " +
            "THEN ((? - base_price) / base_price)*100 ELSE 0 END, " +
            "current_price_source='DHAN_LTP_REFRESH', current_price_date=?, last_refreshed=?, updated_at=? " +
            "WHERE UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(symbol),'.NS',''),'.BO',''),'-EQ',''),'-BE',''),'-BZ',''),'-SM',''),'-ST',''))=? " +
            "AND institution_id=? AND is_active=1")
            .setParameter(1, price)
            .setParameter(2, price)
            .setParameter(3, DateUtil.now())
            .setParameter(4, price)
            .setParameter(5, priceSessionDate())
            .setParameter(6, DateUtil.now())
            .setParameter(7, DateUtil.now())
            .setParameter(8, normalizeSymbol(symbol))
            .setParameter(9, institutionId)
            .executeUpdate();
    }

    private void batchUpsertLiveQuotes(List<DhanInstrumentRef> refs, List<double[]> ohlcvList) {
        if (refs.isEmpty()) return;
        try {
            runInTransaction(() -> {
                String now = DateUtil.now();
                // Written in conflict-key order, and the live feed's flush uses the same order.
                //
                // Two independent writers upsert this table: the WebSocket flush on its scheduler
                // and this refresh on an HTTP thread. Both touch the same rows, and each iterated
                // in whatever order its own collection happened to yield - so one could hold the
                // lock on row A while waiting for B, and the other the exact reverse. Postgres
                // broke the tie by killing a transaction: "deadlock detected ... while inserting
                // index tuple in relation live_price_quotes" during the 09:15 refresh on 7 Sep
                // 2026, losing that whole batch of prices. Locks taken in one agreed order cannot
                // form that cycle.
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < refs.size(); i++) order.add(i);
                order.sort((x, y) -> liveQuoteKey(refs.get(x)).compareTo(liveQuoteKey(refs.get(y))));
                for (int oi = 0; oi < order.size(); oi++) {
                    int i = order.get(oi);
                    DhanInstrumentRef ref = refs.get(i);
                    double[] ohlcv = ohlcvList.get(i);
                    em.createNativeQuery(
                        "INSERT INTO live_price_quotes(broker_name,exchange_segment,broker_security_id,dhan_security_id," +
                        "symbol,current_price,last_traded_price,open_price,high_price,low_price,close_price,volume," +
                        "tick_received_at,price_updated_at,created_at,updated_at) " +
                        "VALUES('dhan',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT(broker_name,exchange_segment,broker_security_id) DO UPDATE SET " +
                        "symbol=EXCLUDED.symbol,current_price=EXCLUDED.current_price,last_traded_price=EXCLUDED.last_traded_price," +
                        "open_price=CASE WHEN EXCLUDED.open_price>0 THEN EXCLUDED.open_price ELSE live_price_quotes.open_price END," +
                        "high_price=CASE WHEN EXCLUDED.high_price>0 THEN EXCLUDED.high_price ELSE live_price_quotes.high_price END," +
                        "low_price=CASE WHEN EXCLUDED.low_price>0 THEN EXCLUDED.low_price ELSE live_price_quotes.low_price END," +
                        "close_price=CASE WHEN EXCLUDED.close_price>0 THEN EXCLUDED.close_price ELSE live_price_quotes.close_price END," +
                        "volume=CASE WHEN EXCLUDED.volume>0 THEN EXCLUDED.volume ELSE live_price_quotes.volume END," +
                        "tick_received_at=EXCLUDED.tick_received_at,price_updated_at=EXCLUDED.price_updated_at,updated_at=EXCLUDED.updated_at")
                        .setParameter(1, ref.exchangeSegment)
                        .setParameter(2, ref.securityId)
                        .setParameter(3, ref.securityId)
                        .setParameter(4, ref.symbol)
                        .setParameter(5, ohlcv[0])
                        .setParameter(6, ohlcv[0])
                        .setParameter(7, ohlcv[1])
                        .setParameter(8, ohlcv[2])
                        .setParameter(9, ohlcv[3])
                        .setParameter(10, ohlcv[4])
                        .setParameter(11, ohlcv[5])
                        .setParameter(12, now)
                        .setParameter(13, now)
                        .setParameter(14, now)
                        .setParameter(15, now)
                        .executeUpdate();
                    if ((i + 1) % getDbFlushSize() == 0) { em.flush(); em.clear(); }
                }
                em.flush(); em.clear();
            });
        } catch (Exception e) {
            log.debug("Batch live quote upsert failed for {} quotes: {}", refs.size(), e.getMessage());
        }
    }

    /**
     * The live_price_quotes conflict key, as a sortable string.
     *
     * <p>Every writer of this table must order its batch by this, so concurrent upserts take row
     * locks in the same sequence and cannot deadlock against each other.
     */
    static String liveQuoteKey(DhanInstrumentRef ref) {
        return String.valueOf(ref.exchangeSegment) + '|' + String.valueOf(ref.securityId);
    }

    private void upsertLiveQuoteFromRefresh(DhanInstrumentRef ref, double ltp,
            double open, double high, double low, double close, double volume) {
        try {
            String now = DateUtil.now();
            em.createNativeQuery(
                "INSERT INTO live_price_quotes(broker_name,exchange_segment,broker_security_id,dhan_security_id," +
                "symbol,current_price,last_traded_price,open_price,high_price,low_price,close_price,volume," +
                "tick_received_at,price_updated_at,created_at,updated_at) " +
                "VALUES('dhan',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(broker_name,exchange_segment,broker_security_id) DO UPDATE SET " +
                "symbol=EXCLUDED.symbol,current_price=EXCLUDED.current_price,last_traded_price=EXCLUDED.last_traded_price," +
                "open_price=CASE WHEN EXCLUDED.open_price>0 THEN EXCLUDED.open_price ELSE live_price_quotes.open_price END," +
                "high_price=CASE WHEN EXCLUDED.high_price>0 THEN EXCLUDED.high_price ELSE live_price_quotes.high_price END," +
                "low_price=CASE WHEN EXCLUDED.low_price>0 THEN EXCLUDED.low_price ELSE live_price_quotes.low_price END," +
                "close_price=CASE WHEN EXCLUDED.close_price>0 THEN EXCLUDED.close_price ELSE live_price_quotes.close_price END," +
                "volume=CASE WHEN EXCLUDED.volume>0 THEN EXCLUDED.volume ELSE live_price_quotes.volume END," +
                "tick_received_at=EXCLUDED.tick_received_at,price_updated_at=EXCLUDED.price_updated_at,updated_at=EXCLUDED.updated_at")
                .setParameter(1, ref.exchangeSegment)
                .setParameter(2, ref.securityId)
                .setParameter(3, ref.securityId)
                .setParameter(4, ref.symbol)
                .setParameter(5, ltp)
                .setParameter(6, ltp)
                .setParameter(7, open)
                .setParameter(8, high)
                .setParameter(9, low)
                .setParameter(10, close)
                .setParameter(11, volume)
                .setParameter(12, now)
                .setParameter(13, now)
                .setParameter(14, now)
                .setParameter(15, now)
                .executeUpdate();
        } catch (Exception e) {
            log.debug("Live quote upsert for {}: {}", ref.symbol, e.getMessage());
        }
    }

    @Transactional
    private void storeDailyPriceSnapshot(Long institutionId) {
        String today = DateUtil.today();
        try {
            // The snapshot is write-once-per-day (ON CONFLICT DO NOTHING keeps the first
            // price of the day). Running the full INSERT...SELECT every 5-minute refresh
            // just re-scans and locks index tuples in tracked_stock_prices for nothing —
            // which, when two refreshes overlap, causes "lock timeout" (55P03). Skip early
            // if today's snapshot already exists.
            Object exists = em.createNativeQuery(
                "SELECT 1 FROM tracked_stock_prices WHERE institution_id=? AND price_date=? LIMIT 1")
                .setParameter(1, institutionId).setParameter(2, today)
                .getResultList().stream().findFirst().orElse(null);
            if (exists != null) return;

            em.createNativeQuery(
                "INSERT INTO tracked_stock_prices" +
                "(institution_id,symbol,price_date,close_price,return_pct,base_price,created_at) " +
                "SELECT institution_id,symbol,?,current_price,return_pct,base_price,? " +
                "FROM tracked_stocks WHERE institution_id=? AND is_active=1 AND current_price IS NOT NULL " +
                "ON CONFLICT (institution_id, symbol, price_date) DO NOTHING")
                .setParameter(1, today).setParameter(2, DateUtil.now())
                .setParameter(3, institutionId).executeUpdate();
        } catch (Exception e) { log.debug("Snapshot store: {}", e.getMessage()); }
    }

    // ==========================================================================
    //  RANKING ENGINE (Sahi-style, 13 configurable factors)
    // ==========================================================================

    @Transactional
    public Map<String, Object> runRankingEngine(Long institutionId, String runType) {
        return runRankingEngine(null, institutionId, runType);
    }

    @Transactional
    public Map<String, Object> runRankingEngine(Long userId, Long institutionId, String runType) {
        String today  = DateUtil.today();
        String runId  = "TRK-" + System.currentTimeMillis();

        // Load weights
        Map<String, Double> weights = loadTrackerWeights(institutionId);

        // Get eligible stocks: return >= 0% AND return <= 8%, base_price > 0
        long tCand = System.currentTimeMillis();
        List<Map<String, Object>> eligible = getRankingCandidates(userId, institutionId);
        long candMs = System.currentTimeMillis() - tCand;

        if (eligible.isEmpty()) {
            // Record the empty run instead of returning silently. This used to write no row
            // and log nothing, so "no stocks qualified" and "the ranking never ran" looked
            // identical on the dashboard - both just said "No ranking yet". On 2026-08-23
            // that hid a real fault (no daily bars existed, so every stock failed the
            // prevClose guard) for several hours.
            log.warn("Tracker ranking {}: 0 eligible - nothing ranked. Band {}%-{}%. "
                   + "If this persists, check that daily bars exist for the tracked stocks.",
                runId, getEligibleMinReturn(), getEligibleMaxReturn());
            try {
                persistRankingRun(runId, userId, institutionId, 0, 0, today, runType,
                    0, 0, 0, 0, 0, 0, 0, 0, Map.of());
            } catch (Exception e) {
                log.debug("Could not persist empty ranking run {}: {}", runId, e.getMessage());
            }
            return Map.of("run_id", runId, "eligible", 0, "ranked", 0,
                          "message", "No eligible stocks (" + getEligibleMinReturn() + "%-" + getEligibleMaxReturn() + "% return range)");
        }

        // Return distribution of this run's cohort, for percentile Relative Strength.
        double[] cohortReturns = eligible.stream().mapToDouble(st -> getD(st, "return_pct")).sorted().toArray();

        // Score each eligible stock
        long tScore = System.currentTimeMillis();
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> stock : eligible) {
            Map<String, Object> s = scoreTrackedStock(stock, weights, cohortReturns);
            scored.add(s);
        }
        long scoreMs = System.currentTimeMillis() - tScore;
        // Split the ranking cost so a slow run points at a stage instead of the whole engine.
        log.info("[RankTiming] candidates {} ms ({} eligible of universe), scoring {} ms",
            candMs, eligible.size(), scoreMs);

        // Sort by composite score descending
        scored.sort((a, b) -> Double.compare(
            getD(b, "composite_score"), getD(a, "composite_score")));

        // Top-10 with sector diversification (max 2 per sector)
        List<Map<String, Object>> top10 = selectTop10WithDiversification(scored);

        // Get previous top-10 for comparison (yesterday's run)
        List<Map<String, Object>> prevTop10 = getPreviousTop10(userId, institutionId, today);

        // Compute metrics
        double currAvgReturn = top10.stream().mapToDouble(s -> getD(s, "return_pct")).average().orElse(0);
        double prevAvgReturn = prevTop10.stream().mapToDouble(s -> getD(s, "return_pct")).average().orElse(0);
        long   currPos       = top10.stream().filter(s -> getD(s, "return_pct") >= 0).count();
        long   prevPos       = prevTop10.stream().filter(s -> getD(s, "return_pct") >= 0).count();
        double currWinRatio  = top10.isEmpty() ? 0 : (double) currPos / top10.size() * 100;
        double prevWinRatio  = prevTop10.isEmpty() ? 0 : (double) prevPos / prevTop10.size() * 100;

        // Sector distribution
        Map<String, Long> sectorDist = top10.stream().collect(
            Collectors.groupingBy(s -> (String) s.getOrDefault("sector", "Unknown"), Collectors.counting()));

        // Persist ranking run
        persistRankingRun(runId, userId, institutionId, eligible.size(), top10.size(), today, runType,
            currAvgReturn, currWinRatio, (int)currPos, (int)(top10.size()-currPos),
            prevAvgReturn, prevWinRatio, (int)prevPos, (int)(prevTop10.size()-prevPos),
            sectorDist);

        // Persist ranking items
        for (int i = 0; i < top10.size(); i++) {
            persistRankingItem(runId, userId, institutionId, i + 1, top10.get(i), today, true);
        }

        // The overnight return of a PREVIOUS run is deliberately NOT computed here any more.
        //
        // It is a derived figure, not part of producing today's ranking, and doing it inline cost
        // about 33 seconds of every run - the single largest slice of a 40-second ranking. Worse,
        // it could never finish its work: it picked runs whose return was still null, and a run
        // that cannot be measured was skipped without recording anything, so the very same
        // unmeasurable runs were recomputed on every ranking, every few minutes, forever.
        //
        // The figure is now worked out when something actually asks for it, and the trend chart
        // rebuilds days straight from stored candles, needing no saved value at all.

        log.info("Tracker ranking {}: {} eligible → {} ranked, avg_return={}%, type={}", runId, eligible.size(), top10.size(),
            Math.round(currAvgReturn * 100) / 100.0, runType);

        auditLog(institutionId, userId, "RANKING_RUN", "tracker_rankings", runId,
            Map.of("eligible", eligible.size(), "ranked", top10.size(), "type", runType,
                   "curr_avg_return", Math.round(currAvgReturn * 100) / 100.0,
                   "prev_avg_return", Math.round(prevAvgReturn * 100) / 100.0));

        return buildRankingResponse(runId, top10, prevTop10, scored,
            currAvgReturn, prevAvgReturn, currWinRatio, prevWinRatio,
            (int)currPos, (int)prevPos, eligible.size(), weights, sectorDist);
    }

    /**
     * Takes the best {@code getTopN()} from a list already sorted by composite score, but lets no
     * more than {@code top10.max_per_sector} come from any one sector.
     *
     * <p>Without the cap a single hot sector can take most of the ten, and the basket stops being
     * a diversified selection and becomes a bet on that sector. Skipping a stock pushes everything
     * below it up a rank, so one skip visibly changes several positions.
     *
     * <p>Every list of ten in the application goes through here - the stored ranking run, the
     * provisional list, and every reconstructed past day. It used to have a single caller, which
     * is exactly why the panels showed different stocks for the same moment from the same pool
     * with the same scores.
     *
     * <p>If the cap cannot fill the ten - a small pool, or one concentrated in a couple of sectors
     * - it is relaxed for the remaining slots rather than returning a short list, so the basket
     * size stays what the strategy assumes.
     */
    private List<Map<String, Object>> selectTop10WithDiversification(List<Map<String, Object>> scored) {
        List<Map<String, Object>> top10 = new ArrayList<>();
        Map<String, Integer> sectorCount = new HashMap<>();
        // Membership by symbol. The fill pass below used List.contains, which compares whole maps
        // field by field - correct, but it also treated two rows for the same symbol as different
        // if any one field differed, which could seat a duplicate.
        Set<String> picked = new HashSet<>();

        for (Map<String, Object> s : scored) {
            if (top10.size() >= getTopN()) break;
            String sector = (String) s.getOrDefault("sector", "Unknown");
            int count = sectorCount.getOrDefault(sector, 0);
            if (count < getMaxPerSector()) {
                top10.add(s);
                picked.add(String.valueOf(s.get("symbol")));
                sectorCount.put(sector, count + 1);
            }
        }

        // Short of the full basket - relax the cap for whatever is left, best score first.
        if (top10.size() < getTopN()) {
            for (Map<String, Object> s : scored) {
                if (top10.size() >= getTopN()) break;
                if (picked.add(String.valueOf(s.get("symbol")))) top10.add(s);
            }
        }
        return top10;
    }

    @SuppressWarnings("unchecked")
    private void computeAndStorePreviousTradeReturn(Long userId, Long institutionId, String today) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT run_id, run_date FROM tracker_rankings WHERE institution_id=? AND run_date<? "
              + "AND trade_avg_return IS NULL AND COALESCE(trade_source,'') <> 'unmeasurable'");
            if (userId != null) sql.append(" AND user_id=?");
            sql.append(" ORDER BY run_date DESC, id DESC LIMIT 5");
            var q = em.createNativeQuery(sql.toString())
                .setParameter(1, institutionId).setParameter(2, today);
            if (userId != null) q.setParameter(3, userId);
            List<Object[]> runs = q.getResultList();
            for (Object[] run : runs) {
                String prevRunId = run[0].toString();
                String prevRunDate = run[1] != null ? run[1].toString() : "";
                if (prevRunDate.isEmpty()) continue;
                Map<String, Object> trade = computeTop10OvernightTradeReturn(institutionId, prevRunId, prevRunDate);
                int symbolCount = ((Number) trade.getOrDefault("symbol_count", 0)).intValue();
                if (symbolCount == 0) {
                    // Record the attempt rather than leaving the column null. Left null, this run
                    // matched the "not computed yet" query again on the next pass and was retried
                    // indefinitely at real cost, always failing for the same reason.
                    em.createNativeQuery(
                        "UPDATE tracker_rankings SET trade_source=? WHERE run_id=? AND institution_id=? "
                      + "AND trade_avg_return IS NULL")
                        .setParameter(1, "unmeasurable")
                        .setParameter(2, prevRunId)
                        .setParameter(3, institutionId)
                        .executeUpdate();
                    continue;
                }
                em.createNativeQuery(
                    "UPDATE tracker_rankings SET trade_avg_return=?, trade_winners=?, trade_losers=?, " +
                    "trade_symbol_count=?, trade_source=? WHERE run_id=? AND institution_id=?")
                    .setParameter(1, trade.get("avg_return"))
                    .setParameter(2, trade.get("winners"))
                    .setParameter(3, trade.get("losers"))
                    .setParameter(4, trade.get("symbol_count"))
                    .setParameter(5, trade.get("source"))
                    .setParameter(6, prevRunId)
                    .setParameter(7, institutionId)
                    .executeUpdate();
            }
        } catch (Exception e) {
            log.debug("Compute previous trade return: {}", e.getMessage());
        }
    }

    // ── 13-Factor Scoring ────────────────────────────────────────────────────

    private Map<String, Object> scoreTrackedStock(Map<String, Object> stock, Map<String, Double> weights) {
        return scoreTrackedStock(stock, weights, null);
    }

    /**
     * @param cohortReturns return_pct of every stock being ranked in this run, sorted ascending.
     *        Relative Strength is scored as a percentile against this cohort; pass null/empty and
     *        it falls back to absolute banding.
     */
    private Map<String, Object> scoreTrackedStock(Map<String, Object> stock, Map<String, Double> weights,
                                                   double[] cohortReturns) {
        return scoreTrackedStock(stock, weights, cohortReturns, null);
    }

    /**
     * Rebuilds what the Top 10 would have been at the close of a past trading day.
     *
     * <p>Nothing here re-implements the formula: it calls the same scorer the live ranking calls,
     * with the same weights, and differs only in the data it is allowed to see. Three cutoffs
     * enforce that:
     *
     * <ul>
     *   <li>the stock must already have been uploaded on that date, so a name added later cannot
     *       appear in an earlier day's Top 10;</li>
     *   <li>"current price" is that date's closing price, not today's;</li>
     *   <li>every factor reads bars up to that date only.</li>
     * </ul>
     *
     * <p>Without those, a reconstruction quietly grades itself on prices that had not happened yet
     * and reports returns the strategy could never have earned.
     *
     * @param asOf a trading date; a day with no candles yields an empty list, which is how weekends
     *        and holidays drop out without needing a holiday calendar
     */
    public List<Map<String, Object>> reconstructTop10AsOf(Long userId, Long institutionId, String asOf) {
        return reconstructTop10AsOf(userId, institutionId, asOf, null);
    }

    /**
     * @param maxPrice when set, only stocks whose BASE price is below this are considered, so a
     *        low-base basket can be tracked as its own strategy. It deliberately tests the base
     *        price rather than the traded price: the base is the fixed reference the whole strategy
     *        is measured from, so a stock stays in or out of the basket for the entire period
     *        instead of drifting across the line as its price moves.
     */
    public List<Map<String, Object>> reconstructTop10AsOf(Long userId, Long institutionId,
                                                          String asOf, Double maxPrice) {
        return reconstructTop10AsOf(userId, institutionId, asOf, maxPrice, true);
    }

    /**
     * @param applyReturnBand whether the configured 0-5% return band is part of identification.
     *        The main Top 10 uses it, because that is its long-standing eligibility rule. The
     *        below-base-price basket does NOT: its entry test is exactly the four conditions -
     *        previous close below base, current at or above base (the crossing), and volume above
     *        the 20-day average. Applying the band there quietly removed stocks that had met every
     *        stated condition, which is why that list came back far shorter than it should.
     */
    public List<Map<String, Object>> reconstructTop10AsOf(Long userId, Long institutionId,
                                                          String asOf, Double maxPrice,
                                                          boolean applyReturnBand) {
        return reconstructTop10AsOf(userId, institutionId, asOf, maxPrice, applyReturnBand, true);
    }

    /**
     * @param requireCrossing whether "Previous Close &lt; Base Price" forms part of the entry test.
     *        The crossing-entry panel needs it - that is its whole premise. The main trend does not:
     *        its test is Current Price &gt;= Base Price and Current Volume &gt; 20-Day Average Volume,
     *        which admits a stock that crossed weeks ago and has held above base ever since. The two
     *        answer different questions, so this is a parameter rather than a fixed rule.
     */
    public List<Map<String, Object>> reconstructTop10AsOf(Long userId, Long institutionId,
                                                          String asOf, Double maxPrice,
                                                          boolean applyReturnBand,
                                                          boolean requireCrossing) {
        return reconstructTop10AsOf(userId, institutionId, asOf, maxPrice, applyReturnBand,
                                    requireCrossing, false);
    }

    /**
     * @param capPerSector apply the live ranking run's sector cap ({@code top10.max_per_sector})
     *        when choosing the ten. On for the ranking sections - Previous top 10 Ranking Stocks
     *        and the Ranking Chart Bar under it - so they select the way Today top 10 Ranking
     *        Stocks does and can be compared with it directly. Off elsewhere, which keeps the
     *        Avg Return Trend panels reading a straight top ten by score. Both charts are served
     *        by the same endpoint, so this has to travel with the request rather than be fixed
     *        inside the method.
     */
    public List<Map<String, Object>> reconstructTop10AsOf(Long userId, Long institutionId,
                                                          String asOf, Double maxPrice,
                                                          boolean applyReturnBand,
                                                          boolean requireCrossing,
                                                          boolean capPerSector) {
        return reconstructTop10AsOf(userId, institutionId, asOf, maxPrice, applyReturnBand,
                                    requireCrossing, capPerSector, true);
    }

    /**
     * @param requireVolume keep "Current Volume &gt; 20-Day Average Volume" in the entry test.
     *        Volume is always reported either way; this only controls whether it can exclude a
     *        stock. Sections that ask for false are saying the price rules alone define them.
     */
    public List<Map<String, Object>> reconstructTop10AsOf(Long userId, Long institutionId,
                                                          String asOf, Double maxPrice,
                                                          boolean applyReturnBand,
                                                          boolean requireCrossing,
                                                          boolean capPerSector,
                                                          boolean requireVolume) {
        return reconstructTop10AsOf(userId, institutionId, asOf, maxPrice, applyReturnBand,
                                    requireCrossing, capPerSector, requireVolume, true);
    }

    /**
     * @param applyRanking score the qualifying stocks and keep the best ten. With it off the
     *        method is a screen: every stock meeting the entry test is returned, ordered by the
     *        size of its move, with no composite score and no top-ten cut.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> reconstructTop10AsOf(Long userId, Long institutionId,
                                                          String asOf, Double maxPrice,
                                                          boolean applyReturnBand,
                                                          boolean requireCrossing,
                                                          boolean capPerSector,
                                                          boolean requireVolume,
                                                          boolean applyRanking) {
        Map<String, Double> weights = loadTrackerWeights(institutionId);

        // MODE B — the upload log as it stood on `asOf`, not as it stands today.
        //
        // This used to read tracked_stocks, which holds only each symbol's CURRENT row. Rebuilding
        // a past day from it priced every stock off the base price it has now: a symbol re-flagged
        // last week was backtested against last week's base all the way back through the chart, and
        // a symbol first flagged after `asOf` appeared in a run that could not have known about it.
        // Both are look-ahead bias. The as-of query below returns each symbol's most recent flag on
        // or before `asOf`, carrying that flag's own base price.
        List<Object[]> rows = reconstructionUniverse(userId, institutionId, asOf);
        if (rows.isEmpty()) return List.of();

        // ------------------------------------------------------------------ IDENTIFICATION
        // Price-only tests first, so the (heavier) history load runs for survivors only. The order
        // matters and is deliberate: the universe is narrowed to eligible candidates BEFORE any
        // ranking happens. Ranking the whole universe and filtering afterwards would let an
        // ineligible stock displace an eligible one out of the Top 10.
        double minRet = getEligibleMinReturn(), maxRet = getEligibleMaxReturn();
        List<Map<String, Object>> shortlist = new ArrayList<>();
        for (Object[] r : rows) {
            double base  = r[1] == null ? 0 : ((Number) r[1]).doubleValue();
            double close = r[5] == null ? 0 : ((Number) r[5]).doubleValue();
            if (base <= 0 || close <= 0) continue;
            if (close < base) continue;                                  // Current Price >= Base Price
            // The ceiling is on the BASE price, not the traded price. Those pick different stocks:
            // one whose base is 40 but has since run to 48 belongs in a "base below 45" basket and
            // would be wrongly excluded by a traded-price test, while one with a base of 60 that
            // has fallen to 44 does not belong and would be wrongly included.
            if (maxPrice != null && base >= maxPrice) continue;
            double retPct = Math.round(((close - base) / base) * 10000.0) / 100.0;
            if (applyReturnBand && (retPct < minRet || retPct > maxRet)) continue;
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("symbol",          String.valueOf(r[0]));
            s.put("base_price",      base);
            s.put("sector",          String.valueOf(r[2]));
            s.put("market_cap_name", String.valueOf(r[3]));
            s.put("upload_date",     r[4] == null ? "" : r[4].toString());
            s.put("current_price",   close);
            s.put("return_pct",      retPct);
            shortlist.add(s);
        }
        if (shortlist.isEmpty()) return List.of();

        // Crossing and volume both need history, so they run once the shortlist is small.
        Map<String, List<DayBar>> hist = dailyBarsUpTo(
            shortlist.stream().map(s -> String.valueOf(s.get("symbol"))).toList(), asOf, 45);

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> s : shortlist) {
            List<DayBar> bars = hist.getOrDefault(normalizeSymbol(String.valueOf(s.get("symbol"))), List.of());
            // bars[0] is asOf itself, so bars[1] is the previous close.
            if (bars.size() < 2) continue;
            double base = getD(s, "base_price");
            double prevClose = bars.get(1).close();
            // A previous close is still required - the 20-day volume average and the reported
            // "Prev Close" both depend on having history - but whether it must sit BELOW the base
            // price is the caller's choice.
            if (prevClose <= 0) continue;
            if (requireCrossing && prevClose >= base) continue;   // Previous Close < Base Price
                                                                 // -> with Current >= Base, that is
                                                                 //    the crossing event
            double todayVol = bars.get(0).volume();
            double avg20 = 0; int n = 0;
            for (int i = 1; i < bars.size() && n < 20; i++) {
                if (bars.get(i).volume() > 0) { avg20 += bars.get(i).volume(); n++; }
            }
            avg20 = n == 0 ? 0 : avg20 / n;
            // Volume > 20D Avg, when the caller wants it as a filter. The figures below are
            // stored regardless, so a section that drops the rule still shows the numbers.
            if (requireVolume && (todayVol <= 0 || avg20 <= 0 || todayVol <= avg20)) continue;

            // ATR14 over the sessions behind D-1, as a percentage of the D-1 close - the same
            // measure the ATR Base Price Crossover screen filters on, so a number here and a
            // number there mean the same thing. Reported on every row; whether it excludes a
            // stock is the caller's choice.
            double atr14 = 0, atrPct = 0;
            // Off by one: the guard admitted a 16-bar series and the loop then read index 16,
            // which needs 17. It only bites when a symbol has exactly 16 sessions behind the
            // as-of day - common for early dates, where less history precedes the day being
            // rebuilt - and it threw straight out of the request, so asking the chart for
            // anything older than about a month returned a 500 and drew nothing at all.
            //
            // Clamped rather than simply requiring 17: with 16 bars, indices 15..1 still give
            // fifteen bars and therefore the fourteen true ranges ATR14 needs, so that series
            // yields a real ATR instead of being silently dropped.
            int atrStart = Math.min(16, bars.size() - 1);
            if (atrStart >= 15) {
                List<Double> hs = new ArrayList<>(), ls = new ArrayList<>(), cs = new ArrayList<>();
                for (int i = atrStart; i >= 1; i--) {    // oldest-first, ending at D-1
                    DayBar bar = bars.get(i);
                    hs.add(bar.high()); ls.add(bar.low()); cs.add(bar.close());
                }
                atr14 = indicator.atr(hs, ls, cs, 14);
                if (atr14 > 0 && prevClose > 0) {
                    atrPct = Math.round((atr14 / prevClose) * 10000.0) / 100.0;
                }
            }

            s.put("previous_close", prevClose);
            s.put("volume", todayVol);
            s.put("avg_20d_volume", Math.round(avg20));
            s.put("atr14", Math.round(atr14 * 100.0) / 100.0);
            s.put("atr_pct", atrPct);
            // Change measured against the previous close, matching the Eligible Stocks export.
            s.put("price_change_pct",
                Math.round(((getD(s, "current_price") - prevClose) / prevClose) * 10000.0) / 100.0);
            candidates.add(s);
        }
        if (candidates.isEmpty()) return List.of();

        // A section can ask to be a screen rather than a ranking. Scoring answers "which are the
        // best ten of these"; a screen answers "which ones qualify", and imposing a top-ten cut
        // on it silently hides qualifying stocks. With ranking off, every candidate is returned,
        // strongest move first, and no composite score is attached to pretend otherwise.
        if (!applyRanking) {
            // Not a ranking - a shortlist. Every stock here has already passed the entry test,
            // so what remains is deciding which of them to actually hold, and that is decided on
            // the day's traded volume: the most heavily traded names are the ones a position can
            // be opened and closed in without moving the price against you.
            //
            // Volume is the sole sort key, so a stock with a bigger percentage move but thin
            // trading ranks below a heavier one. That is deliberate; sorting by move would put
            // illiquid names at the top of a list meant to be tradeable.
            candidates.sort((a, b) -> Double.compare(getD(b, "volume"), getD(a, "volume")));
            int keep = getShortlistCount();
            return candidates.size() > keep
                ? new ArrayList<>(candidates.subList(0, keep))
                : candidates;
        }

        // Relative Strength is a percentile against the same run's cohort, so the cohort has to be
        // that day's candidates - not today's, which would score every past day against the present.
        double[] cohort = candidates.stream().mapToDouble(c -> getD(c, "return_pct")).sorted().toArray();
        List<Map<String, Object>> scored = candidates.stream()
            .map(s -> scoreTrackedStock(s, weights, cohort, asOf))
            .sorted((a, b) -> Double.compare(getD(b, "composite_score"), getD(a, "composite_score")))
            .collect(Collectors.toList());
        // With the cap on, a rebuilt past day is selected exactly the way the live run selects
        // today's - which is what makes the ranking sections comparable with Today top 10.
        if (capPerSector) return selectTop10WithDiversification(scored);
        return scored.size() > getTopN() ? new ArrayList<>(scored.subList(0, getTopN())) : scored;
    }

    /**
     * The candidate universe for a rebuilt day: [symbol, base_price, sector, market_cap, entry_date,
     * close-on-asOf], one row per symbol.
     *
     * <p>Read from stock_upload_history in Mode B (each symbol's latest flag on or before
     * {@code asOf}, with that flag's own base price). Falls back to the old tracked_stocks read when
     * the log is empty or has no priced rows for that window — a deployment whose base-price
     * backfill has not caught up yet keeps its existing charts rather than rendering them blank.
     */
    @SuppressWarnings("unchecked")
    /**
     * Every symbol this institution has ever flagged on or before {@code asOf}.
     *
     * <p>A superset of any single day's reconstruction universe, which is what the trend's bar
     * window needs: the universe is re-derived per simulated day, and a symbol that has since
     * dropped out of it still has to have its bars available for the days it was in.
     */
    /**
     * The oldest date a day can be rebuilt for: the first upload, since before it the universe
     * this strategy selects from did not exist. Candle history reaches further back (to
     * 2025-08-01), but a day with no flagged stocks has nothing to rank.
     */
    private String earliestReconstructableDate(Long userId, Long institutionId) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT MIN(entry_date) FROM stock_upload_history " +
                "WHERE institution_id = ?1 AND base_price IS NOT NULL AND base_price > 0");
            if (userId != null) sql.append(" AND user_id = ").append(userId);
            Object v = em.createNativeQuery(sql.toString())
                .setParameter(1, institutionId).getSingleResult();
            if (v != null) return v.toString().substring(0, 10);
        } catch (Exception e) {
            log.debug("Earliest reconstructable date lookup failed: {}", e.getMessage());
        }
        return java.time.LocalDate.now(IST).minusDays(30).toString();
    }

    private List<String> universeSymbols(Long userId, Long institutionId, String asOf) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT symbol FROM stock_upload_history " +
                "WHERE institution_id = ? AND entry_date <= CAST(? AS DATE) " +
                "AND base_price IS NOT NULL AND base_price > 0");
            if (userId != null) sql.append(" AND user_id = ").append(userId);
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(sql.toString())
                .setParameter(1, institutionId).setParameter(2, asOf).getResultList();
            List<String> out = new ArrayList<>();
            for (Object r : rows) if (r != null) out.add(String.valueOf(r));
            return out;
        } catch (Exception e) {
            log.debug("Universe symbol list failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Object[]> reconstructionUniverse(Long userId, Long institutionId, String asOf) {
        StringBuilder historySql = new StringBuilder(
            "SELECT h.symbol, h.base_price, COALESCE(h.sector,''), COALESCE(h.marketcap_name,''), " +
            "to_char(h.entry_date,'YYYY-MM-DD'), c.close " +
            "FROM (SELECT DISTINCT ON (symbol) symbol, entry_date, sector, marketcap_name, base_price " +
            "      FROM stock_upload_history " +
            "      WHERE institution_id = ? AND entry_date <= CAST(? AS DATE)");
        if (userId != null) historySql.append(" AND user_id = ").append(userId);
        historySql.append(
            "      ORDER BY symbol, entry_date DESC, uploaded_at DESC, id DESC) h " +
            "JOIN LATERAL (SELECT bmc.close FROM broker_market_candles bmc " +
            "  WHERE UPPER(COALESCE(bmc.symbol,'')) = UPPER(h.symbol) " +
            "    AND UPPER(COALESCE(bmc.candle_mode,'')) = 'DAILY' " +
            "    AND bmc.trade_date = ? AND bmc.close IS NOT NULL AND bmc.close > 0 " +
            "  LIMIT 1) c ON TRUE " +
            "WHERE h.base_price IS NOT NULL AND h.base_price > 0");

        try {
            List<Object[]> rows = em.createNativeQuery(historySql.toString())
                .setParameter(1, institutionId).setParameter(2, asOf).setParameter(3, asOf)
                .getResultList();
            if (!rows.isEmpty()) return rows;
        } catch (Exception e) {
            log.debug("Upload-history reconstruction for {} unavailable: {}", asOf, e.getMessage());
        }

        StringBuilder legacySql = new StringBuilder(
            "SELECT ts.symbol, ts.base_price, COALESCE(ts.sector,''), COALESCE(ts.market_cap_name,''), " +
            "ts.upload_date, c.close " +
            "FROM tracked_stocks ts " +
            "JOIN LATERAL (SELECT bmc.close FROM broker_market_candles bmc " +
            "  WHERE UPPER(COALESCE(bmc.symbol,'')) = UPPER(ts.symbol) " +
            "    AND UPPER(COALESCE(bmc.candle_mode,'')) = 'DAILY' " +
            "    AND bmc.trade_date = ? AND bmc.close IS NOT NULL AND bmc.close > 0 " +
            "  LIMIT 1) c ON TRUE " +
            "WHERE ts.institution_id = ? AND ts.upload_date <= ? " +
            "  AND ts.base_price IS NOT NULL AND ts.base_price > 0");
        if (userId != null) legacySql.append(" AND ts.user_id = ").append(userId);
        try {
            return em.createNativeQuery(legacySql.toString())
                .setParameter(1, asOf).setParameter(2, institutionId).setParameter(3, asOf)
                .getResultList();
        } catch (Exception e) {
            log.warn("Reconstruction for {} failed: {}", asOf, e.getMessage());
            return List.of();
        }
    }

    /** One symbol's recent daily bars as of a date: [trade_date, close, volume], newest first. */
    /**
     * Each symbol's first recorded price for a day.
     *
     * <p>tracked_stock_prices is written once per symbol per day - the insert uses ON CONFLICT DO
     * NOTHING, so the first refresh of the session wins and later ones cannot overwrite it. That
     * makes it the opening reference for "how far has today moved", with no new storage needed.
     *
     * <p>One honest limit: it is the first run the application actually made, which equals 09:15
     * only when the application was running then. On a day it started late, the reference is that
     * later moment, and the figure means what it says rather than what the clock suggests.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Double> firstRunPrices(Long institutionId, String day) {
        Map<String, Double> out = new HashMap<>();
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT symbol, close_price FROM tracked_stock_prices " +
                "WHERE institution_id=? AND price_date=? AND close_price > 0")
                .setParameter(1, institutionId).setParameter(2, day).getResultList();
            for (Object[] r : rows) {
                out.put(normalizeSymbol(String.valueOf(r[0])), ((Number) r[1]).doubleValue());
            }
        } catch (Exception e) {
            log.debug("First-run prices for {} unavailable: {}", day, e.getMessage());
        }
        return out;
    }

    private volatile Map<String, List<DayBar>> gapBarsCache = Map.of();
    private volatile long gapBarsCacheTs = 0L;
    private static final long GAP_BARS_TTL_MS = 5 * 60 * 1000L;

    /** How far back a missing session is still the screens' problem rather than a backfill's. */
    private static final int GAP_LOOKBACK_DAYS = 21;

    /**
     * Daily bars a complete session carries. A normal day stores about 3,000; anything under
     * this is a partial fetch, whatever its cause, and the session is reconstructed instead.
     */
    private static final long MIN_DAILY_SYMBOLS_PER_SESSION = 2000L;

    /** The width LiveCandleAggregator stores; preferred by the gap-session fold above. */
    @org.springframework.beans.factory.annotation.Value("${livecandle.interval.minutes:60}")
    private int liveCandleIntervalMinutes;

    /**
     * One folded bar per (symbol, session) for sessions that exist in intraday but never got a
     * daily bar, across the recent window - computed once and cached.
     *
     * <p>On 5 Sep 2026 the daily feed stopped at 28 Aug while intraday held five complete
     * sessions after it. Those sessions are real and the screens should show them, but folding
     * them per caller is far too expensive: the whole 45-day window takes 8.2 seconds, against
     * 2.3 for just the missing days across every symbol. So the missing days are folded once for
     * the entire universe and shared. When the daily feed catches up the gap set empties, the
     * query becomes trivial, and this quietly stops doing anything.
     */
    @SuppressWarnings("unchecked")
    /**
     * The current fold, refreshed in the background - a request never waits for it.
     *
     * <p>Blocking on this was catastrophic. Three page loads arrived while the fold was running
     * and all three returned in the same millisecond as it finished, each reporting
     * bars-batch 100,743ms. The fold is inherently expensive against a 52-million-row table, so
     * the answer is not to make callers queue behind it: a screen showing bars that are one
     * refresh cycle behind is fine, a screen that hangs for a minute and a half is not.
     *
     * <p>So a stale cache is served immediately and a single background refresh is kicked off.
     * The first call after startup returns empty, which simply means no reconstructed sessions
     * that once - the daily bars are still there and correct.
     */
    private Map<String, List<DayBar>> gapSessionBars() {
        if (System.currentTimeMillis() - gapBarsCacheTs >= GAP_BARS_TTL_MS
            && gapBarsRefreshing.compareAndSet(false, true)) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { computeGapSessionBars(); }
                catch (Exception e) { log.warn("Gap fold refresh failed: {}", e.getMessage()); }
                finally { gapBarsRefreshing.set(false); }
            });
        }
        return gapBarsCache;
    }

    private final java.util.concurrent.atomic.AtomicBoolean gapBarsRefreshing =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private Map<String, List<DayBar>> computeGapSessionBars() {
        long now = System.currentTimeMillis();

        Map<String, List<DayBar>> out = new HashMap<>();
        try {
            // A session is a gap when its daily coverage is far short of a normal session -
            // 31 Aug 2026 held 291 daily bars against 2,909 symbols traded, present by the letter
            // of "does a daily bar exist" and missing for 90% of the market.
            //
            // The comparison used to count DISTINCT symbols on BOTH sides across 200 days. That
            // aggregation over roughly 40 million intraday rows measured 72 seconds - the whole
            // of the reported slowness. Two changes fix it: judge daily coverage against a fixed
            // floor instead of counting intraday symbols, and look back three weeks rather than
            // seven months. Daily-side counts take 233ms, the intraday date list 2.5s, and a
            // gap older than three weeks is a backfill job, not something a screen should carry.
            String since = java.time.LocalDate.now(IST).minusDays(GAP_LOOKBACK_DAYS).toString();

            List<?> intradayDays = em.createNativeQuery(
                "SELECT DISTINCT trade_date FROM broker_market_candles " +
                "WHERE UPPER(COALESCE(candle_mode,'')) = 'INTRADAY' AND trade_date >= ?")
                .setParameter(1, since).getResultList();

            List<Object[]> dailyCounts = em.createNativeQuery(
                "SELECT trade_date, COUNT(DISTINCT symbol) FROM broker_market_candles " +
                "WHERE UPPER(COALESCE(candle_mode,'')) = 'DAILY' AND trade_date >= ? " +
                "GROUP BY trade_date")
                .setParameter(1, since).getResultList();

            Map<String, Long> daily = new HashMap<>();
            for (Object[] r : dailyCounts) {
                daily.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
            }
            List<Object> gapDays = new ArrayList<>();
            for (Object d : intradayDays) {
                if (d == null) continue;
                if (daily.getOrDefault(String.valueOf(d), 0L) < MIN_DAILY_SYMBOLS_PER_SESSION) {
                    gapDays.add(d);
                }
            }

            List<String> days = new ArrayList<>();
            for (Object d : gapDays) if (d != null) days.add(d.toString());
            if (!days.isEmpty()) {
                String norm = normalizedSql("symbol");
                // One bar width per symbol per session, never a sum across widths.
                //
                // SUM(volume) used to run over every INTRADAY row for the day. While 5 minutes was
                // the only width stored that was the same thing as the session total. It stops being
                // true the moment a second width exists - a day holding both 5-minute and hourly rows
                // would report roughly twice the volume it traded, and silently, because the fold is
                // a read-time stand-in that nothing reconciles against the official daily bar. So
                // group by width as well, then keep one: the width the live aggregator is storing if
                // the session has it, otherwise the finest the session does have.
                List<Object[]> rows = em.createNativeQuery(
                    "SELECT sym, trade_date, close, high, low, vol FROM (" +
                    "SELECT " + norm + " AS sym, trade_date, " +
                    "(ARRAY_AGG(close ORDER BY candle_time DESC))[1] AS close, " +
                    "MAX(high) AS high, MIN(low) AS low, " +
                    "SUM(COALESCE(total_volume, volume)) AS vol, " +
                    "ROW_NUMBER() OVER (PARTITION BY " + norm + ", trade_date ORDER BY " +
                    "  (COALESCE(interval_minutes, 0) = :pref) DESC, COALESCE(interval_minutes, 0) ASC" +
                    ") AS pick " +
                    "FROM broker_market_candles " +
                    "WHERE UPPER(COALESCE(candle_mode,'')) = 'INTRADAY' " +
                    "AND trade_date IN (:days) AND close IS NOT NULL AND close > 0 " +
                    "GROUP BY " + norm + ", trade_date, COALESCE(interval_minutes, 0)" +
                    ") z WHERE pick = 1")
                    .setParameter("days", days)
                    .setParameter("pref", liveCandleIntervalMinutes).getResultList();
                for (Object[] r : rows) {
                    out.computeIfAbsent(String.valueOf(r[0]), k -> new ArrayList<>())
                       .add(new DayBar(String.valueOf(r[1]),
                                       ((Number) r[2]).doubleValue(),
                                       r[3] == null ? 0 : ((Number) r[3]).doubleValue(),
                                       r[4] == null ? 0 : ((Number) r[4]).doubleValue(),
                                       r[5] == null ? 0 : ((Number) r[5]).doubleValue()));
                }
                log.info("[GapBars] {} session(s) with no daily bar folded from intraday for {} "
                       + "symbol(s): {}", days.size(), out.size(), days);
            }
        } catch (Exception e) {
            log.warn("Gap-session fold failed: {}", e.getMessage());
        }
        gapBarsCache = out;
        gapBarsCacheTs = now;
        return out;
    }

    private record DayBar(String date, double close, double high, double low, double volume) {}

    /**
     * Daily history for a whole date range, held for the duration of one multi-day rebuild.
     *
     * <p>The trend loop asks for bars once per simulated day. Each of those calls is its own scan
     * of the candle table - measured at 430 ms for the tracked universe over 45 sessions - so a
     * thirty-day chart paid it twenty-two times and the panel sat on its spinner. The range is a
     * superset of every day in the loop, so it can be loaded once and sliced.
     */
    private record BarWindow(String from, String to, Map<String, List<DayBar>> bars) {}

    /** Set only while a multi-day rebuild is in progress on this thread; never shared. */
    private final ThreadLocal<BarWindow> barWindow = new ThreadLocal<>();

    /**
     * Finished trading days, keyed by the day and the entry rules used to rebuild it.
     *
     * <p>Entries are immutable and never expire: a settled day is history. The bound is a memory
     * guard, not a freshness one - 301 sessions across a handful of rule combinations sits well
     * inside it. Cleared on restart, which is also when a backfill that added candles for a past
     * session takes effect.
     */
    private static final int SETTLED_DAY_CACHE_MAX = 5000;
    private final java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>>
        settledDayCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Daily bars up to and including a date, for many symbols in one query.
     *
     * <p>Used by the as-of identification, which needs more per stock than a single close can
     * give: the previous close (to see a crossing), that day's volume, the twenty-day average
     * volume behind it, and the daily range for ATR. Reading a 45-day window covers twenty
     * trading days with room for holidays, and grouping in memory avoids a query per symbol
     * per day.
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<DayBar>> dailyBarsUpTo(List<String> symbols, String asOf, int lookbackDays) {
        Map<String, List<DayBar>> out = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;
        try {
            String from = java.time.LocalDate.parse(asOf).minusDays(lookbackDays).toString();

            // A rebuild covering this range has already loaded these bars - slice, do not re-query.
            BarWindow w = barWindow.get();
            if (w != null && from.compareTo(w.from()) >= 0 && asOf.compareTo(w.to()) <= 0) {
                for (String sym : symbols) {
                    String key = normalizeSymbol(sym);
                    List<DayBar> all = w.bars().get(key);
                    if (all == null) continue;
                    // Held newest-first, so the window opens at the first bar on or before asOf
                    // and closes at the first one older than `from`.
                    List<DayBar> slice = new ArrayList<>();
                    for (DayBar d : all) {
                        if (d.date().compareTo(asOf) > 0) continue;
                        if (d.date().compareTo(from) < 0) break;
                        slice.add(d);
                    }
                    if (!slice.isEmpty()) out.put(key, slice);
                }
                return out;
            }
            String norm = normalizedSql("symbol");
            List<Object[]> rows = em.createNativeQuery(
                "SELECT " + norm + ", trade_date, close, high, low, " +
                "COALESCE(total_volume, volume) " +
                "FROM broker_market_candles " +
                "WHERE " + norm + " IN (:syms) " +
                "AND UPPER(COALESCE(candle_mode,'')) = 'DAILY' " +
                "AND trade_date <= :asOf AND trade_date >= :from " +
                "AND close IS NOT NULL AND close > 0 " +
                "ORDER BY " + norm + ", trade_date DESC")
                .setParameter("syms", symbols.stream().map(this::normalizeSymbol).toList())
                .setParameter("asOf", asOf)
                .setParameter("from", from)
                .getResultList();
            for (Object[] r : rows) {
                out.computeIfAbsent(String.valueOf(r[0]), k -> new ArrayList<>())
                   .add(new DayBar(String.valueOf(r[1]),
                                   ((Number) r[2]).doubleValue(),
                                   r[3] == null ? 0 : ((Number) r[3]).doubleValue(),
                                   r[4] == null ? 0 : ((Number) r[4]).doubleValue(),
                                   r[5] == null ? 0 : ((Number) r[5]).doubleValue()));
            }

            // Sessions the daily feed never produced are folded in from intraday - but from a
            // shared cache, not from this query.
            //
            // Doing it inline as a UNION cost 8.2 seconds per call, because it re-folded all 45
            // days of intraday every time when only a handful of sessions were actually missing.
            // This method runs once per simulated day, so that turned a chart into minutes of
            // waiting. The cache folds only the genuinely missing sessions, once, and every day
            // in the loop reads the same result.
            Map<String, List<DayBar>> gaps = gapSessionBars();
            for (Map.Entry<String, List<DayBar>> e : gaps.entrySet()) {
                List<DayBar> add = new ArrayList<>();
                for (DayBar g : e.getValue()) {
                    if (g.date().compareTo(from) >= 0 && g.date().compareTo(asOf) <= 0) add.add(g);
                }
                if (add.isEmpty()) continue;
                List<DayBar> series = out.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                series.addAll(add);
                series.sort((x, y) -> y.date().compareTo(x.date()));   // newest first
            }
        } catch (Exception e) {
            log.debug("Daily bars up to {} failed: {}", asOf, e.getMessage());
        }
        return out;
    }

    /**
     * Day 1 to Day 5 returns for one trade, measured from the price it was actually entered at.
     *
     * <p>This is a lookup, not a strategy. It re-reads nothing about entry, exit, stop, target or
     * any filter: it takes the entry price already recorded for the trade and the stored closes of
     * the next five TRADING sessions, and reports what the position was worth on each. Every day
     * is measured from the entry - Day 2 is entry to day two, not day one to day two - so the
     * figures are directly comparable rather than chained.
     *
     * <p>Sessions come from the same in-memory window the rest of the rebuild uses, so this costs
     * no queries. A holding day that has not traded yet is reported as null and never as zero: a
     * zero would enter an average as a flat trade and quietly drag the whole bar towards nothing.
     */
    private void putHoldingDayReturns(Map<String, Object> row, String symbol,
                                      String buyDate, double buyPrice) {
        for (int k = 1; k <= HOLDING_DAYS; k++) {
            row.put("day" + k + "_return", null);
            row.put("day" + k + "_price", null);
        }
        BarWindow w = barWindow.get();
        if (w == null || buyPrice <= 0 || symbol == null) return;
        List<DayBar> all = w.bars().get(normalizeSymbol(symbol));
        if (all == null || all.isEmpty()) return;

        // The window holds each series newest-first, so walking it backwards yields the sessions
        // in date order; everything strictly after the entry date is a holding day.
        List<DayBar> after = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && after.size() < HOLDING_DAYS; i--) {
            DayBar d = all.get(i);
            if (d.date().compareTo(buyDate) > 0) after.add(d);
        }
        for (int k = 1; k <= HOLDING_DAYS && k <= after.size(); k++) {
            double close = after.get(k - 1).close();
            if (close <= 0) continue;
            row.put("day" + k + "_price", Math.round(close * 100.0) / 100.0);
            row.put("day" + k + "_return",
                Math.round(((close - buyPrice) / buyPrice) * 10000.0) / 100.0);
        }
    }

    /** Holding days reported per trade: Day 1 is the first trading session after entry. */
    private static final int HOLDING_DAYS = 5;

    /**
     * The trades behind every chart bar, one row per stock per day, as CSV.
     *
     * <p>Exports exactly what the chart was drawn from rather than recomputing it, so a figure in
     * the spreadsheet and the bar above it can never disagree. Stocks that could not be priced are
     * included with an empty price and the reason, because a day showing four rows when ten were
     * ranked is otherwise indistinguishable from a day that only ranked four.
     *
     * <p>Written as CSV with a UTF-8 BOM: Excel opens it directly as a worksheet, and unlike a
     * generated .xlsx it stays readable by anything else the user points at it.
     */
    public byte[] exportChartTradesCsv(Long userId, Long institutionId,
                                       String from, String to, Double maxPrice) {
        // Band held off explicitly. getReconstructedTrend switches the 0-5% band back on when no
        // price ceiling is given, which would make this export describe a different entry test
        // from the chart it is meant to explain.
        Map<String, Object> trend =
            getReconstructedTrend(userId, institutionId, from, to, maxPrice, null, null, false);
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("TradeRakshak\u2122 \u2014 Chart Bar Trades")
          .append(maxPrice != null ? " (base price below " + maxPrice + ")" : "").append('\n');
        sb.append("Range: ").append(from).append(" to ").append(to).append('\n');
        sb.append("Rule: ").append(csv(String.valueOf(trend.get("rule")))).append('\n');
        sb.append("Generated: ").append(DateUtil.now()).append('\n');
        sb.append("DISCLAIMER: For educational purposes only. Not SEBI investment advice.").append('\n');
        sb.append('\n');
        sb.append("Chart Bar Date,Rank,Symbol,Buy Date,Buy Time,Buy Price,")
          .append("Sell Date,Sell Time,Sell Price,Return %,Status,Note\n");

        List<Map<String, Object>> days = (List<Map<String, Object>>) trend.get("days");
        if (days != null) {
            for (Map<String, Object> d : days) {
                String date = String.valueOf(d.get("date"));
                List<Map<String, Object>> priced = (List<Map<String, Object>>) d.get("priced");
                if (priced != null) {
                    for (Map<String, Object> p : priced) {
                        sb.append(date).append(',')
                          .append(num(p.get("rank"))).append(',')
                          .append(csv(String.valueOf(p.get("symbol")))).append(',')
                          .append(str(p.get("buy_date"))).append(',')
                          .append(str(p.get("buy_time"))).append(',')
                          .append(num(p.get("buy_price"))).append(',')
                          .append(str(p.get("sell_date"))).append(',')
                          .append(str(p.get("sell_time"))).append(',')
                          .append(num(p.get("sell_price"))).append(',')
                          .append(num(p.get("return_pct"))).append(',')
                          .append("Priced").append(",\n");
                    }
                }
                List<Map<String, Object>> unpriced = (List<Map<String, Object>>) d.get("unpriced");
                if (unpriced != null) {
                    for (Map<String, Object> u : unpriced) {
                        sb.append(date).append(',')
                          .append(num(u.get("rank"))).append(',')
                          .append(csv(String.valueOf(u.get("symbol")))).append(',')
                          .append(date).append(',')
                          .append(str(trend.get("buy_time"))).append(',')
                          .append(num(u.get("buy_price"))).append(',')
                          .append(str(d.get("sell_date"))).append(',')
                          .append(str(trend.get("sell_time"))).append(',')
                          .append(num(u.get("sell_price"))).append(',')
                          .append("").append(',')
                          .append("Not priced").append(',')
                          .append(csv(String.valueOf(u.getOrDefault("reason", "")))).append('\n');
                    }
                }
            }
        }

        sb.append('\n');
        sb.append("Summary,,,,,,,,,,,\n");
        sb.append("Trading days,").append(num(trend.get("trading_days"))).append('\n');
        sb.append("Measured days,").append(num(trend.get("measured_days"))).append('\n');
        sb.append("Average return %,").append(num(trend.get("average_return_pct"))).append('\n');
        sb.append("Best day %,").append(num(trend.get("best_day"))).append('\n');
        sb.append("Worst day %,").append(num(trend.get("worst_day"))).append('\n');
        sb.append("Positive days,").append(num(trend.get("positive_days"))).append('\n');
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Quotes a CSV field only when it needs it, so the common case stays readable. */
    private String csv(String v) {
        if (v == null || "null".equals(v)) return "";
        return (v.contains(",") || v.contains("\"") || v.contains("\n"))
            ? '"' + v.replace("\"", "\"\"") + '"'
            : v;
    }

    private String str(Object v) { return v == null ? "" : csv(String.valueOf(v)); }

    private String num(Object v) { return v == null ? "" : String.valueOf(v); }

    /**
     * The below-price Top 10, in the order the strategy actually requires:
     * identify, then rank, then signal.
     *
     * <p>The sequence matters and is not interchangeable. Identification narrows the universe to
     * the stocks that crossed up through their base price on volume; only those are ranked. Ranking
     * the whole universe first and filtering afterwards would let an ineligible stock occupy a slot
     * an eligible one had earned, producing a different Top 10 from the same data.
     *
     * <p>The ranking itself is untouched - the same {@code scoreTrackedStock} the main Top 10 uses,
     * with the same weights - and the signal comes from {@code buildUploadedStockSignals}, the
     * existing Buy/Sell logic, rather than a second methodology invented for this screen.
     *
     * <p>The average return is compared against the previous trading day's list so the trend says
     * whether the basket is improving, not merely what it is today.
     */
    public Map<String, Object> getBelowPriceTop10(Long userId, Long institutionId, Double maxPrice) {
        return getBelowPriceTop10(userId, institutionId, maxPrice, true);
    }

    /**
     * @param requireCrossing true for the crossing panel, false for the main trend. Both run the
     *        identical pipeline - identify, rank, then signal - and differ only in whether the
     *        previous close must sit below the base price. Keeping one method rather than two means
     *        a change to the pipeline cannot reach one section and miss the other.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getBelowPriceTop10(Long userId, Long institutionId, Double maxPrice,
                                                  boolean requireCrossing) {
        return getBelowPriceTop10(userId, institutionId, maxPrice, requireCrossing, true);
    }

    public Map<String, Object> getBelowPriceTop10(Long userId, Long institutionId, Double maxPrice,
                                                  boolean requireCrossing, boolean requireVolume) {
        return getBelowPriceTop10(userId, institutionId, maxPrice, requireCrossing, requireVolume, true);
    }

    /**
     * @param requireVolume see {@link #reconstructTop10AsOf}
     * @param applyRanking  when false the panel is a screen, not a Top 10: every qualifying
     *        stock is listed, strongest move first, and the pipeline text says so.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getBelowPriceTop10(Long userId, Long institutionId, Double maxPrice,
                                                  boolean requireCrossing, boolean requireVolume,
                                                  boolean applyRanking) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            // As-of date: the newest day that actually has prices. During a session that is today;
            // after the close, or on a holiday, it is the last completed trading day - which is
            // what "after market last closing price will be considered" asks for.
            List<String> days = tradingDaysWithData(
                java.time.LocalDate.now(IST).minusDays(30).toString(), DateUtil.today());
            if (days.isEmpty()) {
                out.put("success", false);
                out.put("message", "No trading day with stored prices in the last 30 days.");
                out.put("top10", List.of());
                return out;
            }
            // The section shows what is HELD, which was selected on the session BEFORE the
            // newest one - not the newest itself.
            //
            // A stock that crosses on Friday is a candidate for Friday's 15:15 selection, so on
            // Friday it belongs under "Heading into tomorrow - provisional". It becomes the held
            // position on the next market day, and only then does it belong in this table. Using
            // the newest session for both meant Friday's crossings appeared here on Friday - the
            // provisional and the holding showing the same list, with nothing ever moving from
            // one to the other.
            //
            // days comes from tradingDaysWithData, so "the session before" already skips
            // weekends and holidays: after Friday it is Thursday, and on Monday it is Friday.
            boolean haveTwo = days.size() > 1;
            String asOf = haveTwo ? days.get(days.size() - 2) : days.get(days.size() - 1);
            String prevDay = days.size() > 2 ? days.get(days.size() - 3) : null;
            // The session whose selection is still only provisional - shown by the panel above.
            String provisionalDay = days.get(days.size() - 1);

            // ---- IDENTIFY -> RANK (both inside reconstructTop10AsOf, in that order) ----
            // false: identification here is exactly the four stated conditions, no return band.
            List<Map<String, Object>> top = reconstructTop10AsOf(
                userId, institutionId, asOf, maxPrice, false, requireCrossing, false,
                requireVolume, applyRanking);

            // ---- Live prices while the session is running ----
            //
            // Identification and ranking are settled on the last completed day's close - that is
            // deliberate, since the entry test needs a full day's volume. But once those ten are
            // chosen, holding their prices frozen at yesterday's close makes the panel look stale
            // and its Return % wrong all day. So the SELECTION stays as-of the close while the
            // PRICES track the market, and the panel says which it is showing.
            boolean live = isMarketOpen();
            List<String> topSyms = top.stream().map(s -> String.valueOf(s.get("symbol"))).toList();
            Map<String, Double> livePrices = live
                ? resolveCurrentPricesBatch(topSyms, institutionId)
                : Map.of();

            // The price each position was actually opened at: the traded price at the cut-off on the
            // day the list was frozen. Return is measured from HERE, not from the base price.
            //
            // Base price is where the stock was first uploaded, which can be months and hundreds of
            // percent ago - measured from it, this panel read "AVG RETURN 101.24%" with SIGMAADV at
            // +342%, a number describing the stock's whole history rather than the trade. Measured
            // from the cut-off price it answers the question actually being asked: since this list
            // was frozen and bought, what has it done?
            Map<String, Double> entryPrices =
                intradayPricesAt(topSyms, asOf, getTop10LockTime());

            // ---- BUY/SELL, applied after ranking, never before ----
            List<Map<String, Object>> rows = new ArrayList<>();
            double sumReturn = 0;
            int rank = 0, buys = 0, sells = 0, holds = 0, livePriced = 0;
            for (Map<String, Object> s : top) {
                rank++;
                String symbol = String.valueOf(s.get("symbol"));
                double price = getD(s, "current_price");
                double closePrice = price;
                Double lp = livePrices.get(normalizeSymbol(symbol));
                if (lp != null && lp > 0) { price = lp; livePriced++; }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rank", rank);
                row.put("symbol", symbol);
                row.put("sector", s.get("sector"));
                row.put("base_price", s.get("base_price"));
                row.put("previous_close", s.get("previous_close"));
                row.put("current_price", price);
                row.put("close_price", closePrice);
                row.put("is_live", lp != null && lp > 0);

                // Entry: the cut-off price on the frozen day. Falling back to that day's close keeps
                // a stock that simply did not print at 15:20 in the list, rather than dropping it.
                Double entry = entryPrices.get(normalizeSymbol(symbol));
                if (entry == null || entry <= 0) entry = closePrice;
                row.put("entry_price", entry > 0 ? Math.round(entry * 100.0) / 100.0 : null);
                row.put("entry_time", getTop10LockTime());

                // Return since entry, computed from whatever price is actually displayed so the two
                // can never disagree on screen.
                row.put("return_pct", (entry != null && entry > 0)
                    ? Math.round(((price - entry) / entry) * 10000.0) / 100.0
                    : 0.0);
                // Kept for reference - the move from the original upload price.
                double basePx = getD(s, "base_price");
                row.put("return_from_base_pct", basePx > 0
                    ? Math.round(((price - basePx) / basePx) * 10000.0) / 100.0 : null);
                row.put("price_change_pct", s.get("price_change_pct"));
                row.put("volume", s.get("volume"));
                row.put("avg_20d_volume", s.get("avg_20d_volume"));
                // ATR is computed during identification and was being dropped here: this method
                // builds a fresh row and copies named fields, so anything not listed never
                // reaches the screen. The columns rendered "-" on every line while the numbers
                // existed a few frames earlier.
                row.put("atr14", s.get("atr14"));
                row.put("atr_pct", s.get("atr_pct"));
                row.put("score", s.get("composite_score"));

                Object fs = s.get("factor_scores");
                if (fs instanceof Map<?, ?> fm) {
                    row.put("weight_used", fm.get("_weight_used"));
                    row.put("factor_scores", fm);
                }

                // The existing Buy/Sell logic, unchanged.
                String signal = "HOLD";
                try {
                    Map<String, Object> sig = buildUploadedStockSignals(symbol, price, institutionId);
                    signal = String.valueOf(sig.getOrDefault("buy_sell_signal", "HOLD"));
                    row.put("signal", signal);
                    row.put("trend", sig.get("trend"));
                    row.put("trend_score", sig.get("trend_score"));
                    row.put("signal_message", sig.get("signal_message"));
                } catch (Exception e) {
                    row.put("signal", "DATA REQUIRED");
                    row.put("signal_message", "Not enough stored candles to produce a signal.");
                }
                if ("BUY".equals(signal)) buys++;
                else if ("SELL".equals(signal)) sells++;
                else holds++;

                sumReturn += ((Number) row.get("return_pct")).doubleValue();
                rows.add(row);
            }

            double avgCurrent = rows.isEmpty() ? 0
                : Math.round((sumReturn / rows.size()) * 100.0) / 100.0;

            // ---- Previous run, for the trend ----
            Double avgPrevious = null;
            if (prevDay != null) {
                List<Map<String, Object>> prevTop = reconstructTop10AsOf(
                    userId, institutionId, prevDay, maxPrice, false, requireCrossing, false,
                    requireVolume, applyRanking);
                if (!prevTop.isEmpty()) {
                    // Same measure for the comparison, or the trend would be an artefact of two
                    // different definitions: entry at the previous day's cut-off, valued at the
                    // cut-off of the day after it - the point this basket has now reached.
                    List<String> prevSyms = prevTop.stream()
                        .map(x -> String.valueOf(x.get("symbol"))).toList();
                    Map<String, Double> prevEntry =
                        intradayPricesAt(prevSyms, prevDay, getTop10LockTime());
                    Map<String, Double> prevExit =
                        intradayPricesAt(prevSyms, asOf, getTop10LockTime());
                    double ps = 0;
                    int pn = 0;
                    for (Map<String, Object> x : prevTop) {
                        String k = normalizeSymbol(String.valueOf(x.get("symbol")));
                        Double e = prevEntry.get(k);
                        Double xo = prevExit.get(k);
                        if (e == null || e <= 0 || xo == null || xo <= 0) continue;
                        ps += (xo - e) / e * 100.0;
                        pn++;
                    }
                    if (pn > 0) avgPrevious = Math.round((ps / pn) * 100.0) / 100.0;
                }
            }

            String direction;
            Double trendPct = null;
            if (avgPrevious == null) {
                direction = "NEW";                       // nothing to compare against yet
            } else {
                trendPct = Math.round((avgCurrent - avgPrevious) * 100.0) / 100.0;
                // A hair of movement is noise, not a trend, so a dead band keeps the arrow honest.
                direction = trendPct > 0.01 ? "UP" : trendPct < -0.01 ? "DOWN" : "FLAT";
            }

            out.put("success", true);
            out.put("as_of", asOf);
            out.put("previous_day", prevDay);
            // So the screen can say which session is held and which is still only provisional.
            out.put("provisional_day", provisionalDay);
            out.put("held_from", asOf);
            out.put("max_price", maxPrice);
            out.put("top10", rows);
            out.put("count", rows.size());
            out.put("avg_return_current", avgCurrent);

            // Today's move: first run of the day -> now. tracked_stock_prices is written once per
            // symbol per day, on the first refresh, so it already holds the opening reference
            // without anything new having to be stored. "Now" is the same current price the rest
            // of the screen uses, which outside market hours is the last completed close - so the
            // figure settles at the close rather than drifting against a stale quote.
            Map<String, Double> firstRun = firstRunPrices(institutionId, DateUtil.today());
            double frSum = 0; int frCount = 0;
            for (Map<String, Object> r : rows) {
                Double open = firstRun.get(normalizeSymbol(String.valueOf(r.get("symbol"))));
                double cur = getD(r, "current_price");
                if (open == null || open <= 0 || cur <= 0) continue;
                double pct = Math.round(((cur - open) / open) * 10000.0) / 100.0;
                r.put("return_from_first_run", pct);
                frSum += pct; frCount++;
            }
            out.put("avg_return_from_first_run",
                    frCount == 0 ? null : Math.round((frSum / frCount) * 100.0) / 100.0);
            out.put("first_run_priced", frCount);
            out.put("avg_return_previous", avgPrevious);
            out.put("trend_pct", trendPct);
            out.put("trend_direction", direction);
            // The date and time this list was frozen. Shown on screen because the list you are
            // looking at today was fixed at yesterday's cut-off, and without the date it is easy to
            // read a stale list as a live one.
            out.put("frozen_date", asOf);
            out.put("frozen_time", getTop10LockTime());
            out.put("live", live);
            out.put("live_priced", livePriced);
            out.put("return_basis",
                "Return % is measured from the " + getTop10LockTime() + " price on " + asOf
              + " (when the list was frozen), not from the base price.");
            out.put("price_basis", live && livePriced > 0
                ? "Live prices, selection fixed at the " + asOf + " close"
                : "Prices as at the " + asOf + " close");
            out.put("buy_count", buys);
            out.put("sell_count", sells);
            out.put("hold_count", holds);
            out.put("require_crossing", requireCrossing);
            out.put("require_volume", requireVolume);
            out.put("apply_ranking", applyRanking);
            out.put("pipeline", "Identify: "
              + (requireCrossing
                  ? "Previous Close < Base Price AND Current Price >= Base Price (the crossing)"
                  : "Current Price >= Base Price")
              + (requireVolume ? " AND Current Volume > 20-Day Average Volume" : "")
              + (applyRanking
                  ? " -> Rank (existing engine, existing weights) -> Buy/Sell (existing logic)"
                  : " -> list every match, strongest move first (no ranking)"));
            out.put("scope", maxPrice == null ? "All stocks"
                                              : "Stocks with a base price below " + maxPrice);
            if (rows.isEmpty()) {
                out.put("message", "No stock with a base price under " + maxPrice
                                 + " met the entry rules on " + asOf + ".");
            }
        } catch (Exception e) {
            log.warn("Below-price Top 10 failed: {}", e.getMessage());
            out.put("success", false);
            out.put("message", "Could not build the list: " + e.getMessage());
            out.put("top10", List.of());
        }
        return out;
    }

    /**
     * The list that WOULD be frozen if the cut-off were right now.
     *
     * <p>The panel above it shows yesterday's frozen list, which cannot change today - that is the
     * point of freezing it. This answers the other question people actually have during a session:
     * on today's prices so far, which stocks are heading into tomorrow?
     *
     * <p>It runs the same three-condition entry test and the same ranking engine, but against live
     * prices and today's volume rather than a settled close. So it moves during the day and is not
     * a prediction - it is simply where the list stands at this moment. Anything can still cross,
     * or fall back below base, before {@code getTop10LockTime()}.
     */
    public Map<String, Object> getProvisionalTop10(Long userId, Long institutionId) {
        return getProvisionalTop10(userId, institutionId, true);
    }

    /** @param requireCrossing which entry test to preview - see getBelowPriceTop10. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getProvisionalTop10(Long userId, Long institutionId,
                                                   boolean requireCrossing) {
        return getProvisionalTop10(userId, institutionId, requireCrossing, true);
    }

    /**
     * @param applyRanking rank by score and keep the Top 10, as the ranked sections do. With it
     *        off the pool is shortlisted the way the RSI 45 section shortlists - most traded
     *        first, top six - so the provisional list is drawn from the same rule that will
     *        select tomorrow's holding, not from a different one.
     */
    public Map<String, Object> getProvisionalTop10(Long userId, Long institutionId,
                                                   boolean requireCrossing,
                                                   boolean applyRanking) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Double> weights = loadTrackerWeights(institutionId);
            // Same entry test as the frozen panel above it, whichever that is.
            List<Map<String, Object>> pool =
                selectTrackedCandidates(userId, institutionId, requireCrossing, false);

            if (pool.isEmpty()) {
                out.put("success", true);
                out.put("count", 0);
                out.put("stocks", List.of());
                out.put("message", "No stock currently meets the entry test. "
                                 + "This can change at any time before the " + getTop10LockTime() + " cut-off.");
                out.put("as_of", DateUtil.now());
                out.put("freeze_at", getTop10LockTime());
                out.put("market_open", isMarketOpen());
                return out;
            }

            List<Map<String, Object>> ranked;
            if (!applyRanking) {
                // Shortlist, not ranking: heaviest traded first, top six - identical to the
                // selection the section itself makes, so what is previewed here is what will
                // actually be held.
                ranked = pool.stream()
                    .sorted((a, b) -> Double.compare(getD(b, "volume"), getD(a, "volume")))
                    .limit(getShortlistCount())
                    .collect(Collectors.toList());
            } else {
                double[] cohort = pool.stream().mapToDouble(c -> getD(c, "return_pct")).sorted().toArray();
                // Raw top N by score - no sector cap. The cap is applied to the three ranking
                // sections only (see selectTop10WithDiversification), so this preview stays a
                // straight read of what scores highest right now.
                ranked = pool.stream()
                    .map(s -> scoreTrackedStock(s, weights, cohort))
                    .sorted((a, b) -> Double.compare(getD(b, "composite_score"), getD(a, "composite_score")))
                    .limit(getTopN())
                    .collect(Collectors.toList());
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            int rank = 0;
            double sum = 0;
            for (Map<String, Object> s : ranked) {
                rank++;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rank", rank);
                row.put("symbol", s.get("symbol"));
                row.put("sector", s.get("sector"));
                row.put("base_price", s.get("base_price"));
                row.put("previous_close", s.get("previous_close"));
                // Rounded here, not left to the template: the raw quote is a float and reached the
                // screen as 126.41999816894531 beside neighbours printed to two places.
                row.put("current_price", round2(getD(s, "current_price")));
                // Fresh row map, named fields only - so anything not listed silently never
                // arrives. That is how ATR came to render "-" on every line once already.
                row.put("atr14", s.get("atr14"));
                row.put("atr_pct", s.get("atr_pct"));
                // Prev Close -> Current, matching the two columns either side of it. return_pct
                // (from the base price) is kept alongside for anything still reading it.
                row.put("return_pct", s.getOrDefault("price_change_pct", s.get("return_pct")));
                row.put("return_from_base_pct", s.get("return_pct"));
                row.put("price_change_pct", s.get("price_change_pct"));
                row.put("score", s.get("composite_score"));
                Object fs = s.get("factor_scores");
                if (fs instanceof Map<?, ?> fm) row.put("weight_used", fm.get("_weight_used"));
                sum += getD(s, "return_pct");
                rows.add(row);
            }

            out.put("success", true);
            out.put("count", rows.size());
            out.put("stocks", rows);
            out.put("pool_size", pool.size());
            out.put("avg_return_pct", rows.isEmpty() ? 0
                : Math.round((sum / rows.size()) * 100.0) / 100.0);
            out.put("as_of", DateUtil.now());
            out.put("freeze_at", getTop10LockTime());
            out.put("market_open", isMarketOpen());
            out.put("require_crossing", requireCrossing);
            out.put("note", isMarketOpen()
                ? "Provisional - this list moves with prices until the " + getTop10LockTime() + " cut-off."
                : "Market closed. This is where the list stood at the last prices seen.");
        } catch (Exception e) {
            log.warn("Provisional Top 10 failed: {}", e.getMessage());
            out.put("success", false);
            out.put("stocks", List.of());
            out.put("message", "Could not build the provisional list: " + e.getMessage());
        }
        return out;
    }

    /** Trading days that actually have daily candles, oldest first. Holidays are absent by nature. */
    @SuppressWarnings("unchecked")
    private List<String> tradingDaysWithData(String from, String to) {
        try {
            // Either feed counts. Restricting this to DAILY made the rebuild blind to sessions
            // the database plainly holds: on 5 Sep 2026 the daily feed stopped at 28 Aug while
            // intraday carried five complete sessions after it - 31 Aug and 1-4 Sep, about
            // 208,000 candles each. The chart ended on 28 Aug and looked stale, when in fact the
            // data was there and only this lookup could not see it.
            // Walked one date at a time through the index, not scanned.
            //
            // SELECT DISTINCT over this table costs a parallel sequential scan: UPPER(COALESCE(
            // candle_mode,'')) cannot use an index, so Postgres read 4.5M rows and sorted 1.5M
            // per worker to return 21 dates - measured at 20,455 ms, on a query that runs before
            // every rebuild and on section loads besides. It was the largest single wait on the
            // page and it never appeared in the timings, because the method's own clock starts
            // after this call.
            //
            // The recursive form is the standard loose index scan: take the first date in range,
            // then repeatedly take the next one after it. That is about twenty index probes
            // against idx_bmc_trade_date. Same 21 dates, verified against the old query, in
            // 16.6 ms. The mode test is kept inside both halves, so a third candle_mode arriving
            // later still changes the answer exactly as it would have before.
            List<?> days = em.createNativeQuery(
                "WITH RECURSIVE d AS ( " +
                "  SELECT (SELECT MIN(b.trade_date) FROM broker_market_candles b " +
                "           WHERE b.trade_date >= ?1 AND b.trade_date <= ?2 " +
                "             AND UPPER(COALESCE(b.candle_mode,'')) IN ('DAILY','INTRADAY')" +
                "         ) AS trade_date " +
                "  UNION ALL " +
                "  SELECT (SELECT MIN(b.trade_date) FROM broker_market_candles b " +
                "           WHERE b.trade_date > d.trade_date AND b.trade_date <= ?2 " +
                "             AND UPPER(COALESCE(b.candle_mode,'')) IN ('DAILY','INTRADAY')) " +
                "  FROM d WHERE d.trade_date IS NOT NULL " +
                ") " +
                "SELECT trade_date FROM d WHERE trade_date IS NOT NULL ORDER BY trade_date")
                .setParameter(1, from).setParameter(2, to).getResultList();
            List<String> out = new ArrayList<>();
            for (Object d : days) if (d != null) out.add(d.toString());
            return out;
        } catch (Exception e) {
            log.warn("Trading-day lookup failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Traded price for each symbol at a given time on one date, in a single query.
     *
     * <p>Takes the first candle at or after the requested time, so a symbol that did not print
     * exactly on the minute still gets the next real trade rather than being dropped. A symbol with
     * nothing at or after that time is simply absent - it is never back-filled from an earlier bar,
     * which would report a price the strategy could not have traded at.
     *
     * @param hhmm wall-clock time, "HH:mm"
     */
    @SuppressWarnings("unchecked")
    private Map<String, Double> intradayPricesAt(List<String> symbols, String date, String hhmm) {
        Map<String, Double> out = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;
        try {
            String norm = normalizedSql("symbol");
            List<Object[]> rows = em.createNativeQuery(
                "SELECT DISTINCT ON (" + norm + ") " + norm + ", close " +
                "FROM broker_market_candles " +
                "WHERE " + norm + " IN (:syms) AND trade_date = :d " +
                "AND UPPER(COALESCE(candle_mode,'')) = 'INTRADAY' " +
                "AND close IS NOT NULL AND close > 0 " +
                "AND candle_time >= :cutoff " +
                "ORDER BY " + norm + ", candle_time ASC")
                .setParameter("syms", symbols.stream().map(this::normalizeSymbol).toList())
                .setParameter("d", date)
                .setParameter("cutoff", date + "T" + hhmm)
                .getResultList();
            for (Object[] r : rows) out.put(String.valueOf(r[0]), ((Number) r[1]).doubleValue());
        } catch (Exception e) {
            log.debug("Intraday price lookup {} {} failed: {}", date, hhmm, e.getMessage());
        }
        return out;
    }

    /** Closing prices for a set of symbols on one date, keyed by upper-case symbol. */
    @SuppressWarnings("unchecked")
    private Map<String, Double> closesOn(List<String> symbols, String date) {
        Map<String, Double> out = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT UPPER(symbol), close FROM broker_market_candles " +
                "WHERE UPPER(COALESCE(candle_mode,'')) = 'DAILY' AND trade_date = :d " +
                "AND close IS NOT NULL AND close > 0 AND UPPER(symbol) IN (:syms)")
                .setParameter("d", date)
                .setParameter("syms", symbols.stream().map(String::toUpperCase).toList())
                .getResultList();
            for (Object[] r : rows) out.put(String.valueOf(r[0]), ((Number) r[1]).doubleValue());
        } catch (Exception e) {
            log.debug("Close lookup for {} failed: {}", date, e.getMessage());
        }
        return out;
    }

    /**
     * Per-day returns across a date range, computed from stored candles alone.
     *
     * <p>For each trading day it rebuilds that day's Top 10 as of the close, then measures what
     * those ten did by the next trading day's close. No saved ranking is needed, so this covers
     * dates from long before the ranking engine was ever run.
     *
     * <p>The final day in a range has no following day yet; it is reported as pending rather than
     * counted as a zero, which would drag the average toward nothing.
     */
    public Map<String, Object> getReconstructedTrend(Long userId, Long institutionId,
                                                     String from, String to) {
        return getReconstructedTrend(userId, institutionId, from, to, null);
    }

    /**
     * @param maxPrice optional price ceiling, applied per day, so the same strategy can be measured
     *        over a low-priced basket without needing a second copy of any of this logic
     */
    public Map<String, Object> getReconstructedTrend(Long userId, Long institutionId,
                                                     String from, String to, Double maxPrice) {
        return getReconstructedTrend(userId, institutionId, from, to, maxPrice, null, null);
    }

    /**
     * @param buyTime  "HH:mm" to buy at on the ranking day; defaults to the configured trade buy time
     * @param sellTime "HH:mm" to sell at on the next trading day; defaults to the configured sell time
     */
    public Map<String, Object> getReconstructedTrend(Long userId, Long institutionId,
                                                     String from, String to, Double maxPrice,
                                                     String buyTime, String sellTime) {
        // A price ceiling means this is the below-base-price basket, whose entry test is the four
        // conditions alone. Keeping the flag tied to the ceiling means the chart and the table
        // above it can never be built from different rules.
        return getReconstructedTrend(userId, institutionId, from, to, maxPrice, buyTime, sellTime,
                                     maxPrice == null);
    }

    /** @param applyReturnBand see {@link #reconstructTop10AsOf(Long, Long, String, Double, boolean)} */
    public Map<String, Object> getReconstructedTrend(Long userId, Long institutionId,
                                                     String from, String to, Double maxPrice,
                                                     String buyTime, String sellTime,
                                                     boolean applyReturnBand) {
        return getReconstructedTrend(userId, institutionId, from, to, maxPrice, buyTime, sellTime,
                                     applyReturnBand, false);
    }

    /** @param requireCrossing see {@link #reconstructTop10AsOf(Long, Long, String, Double, boolean, boolean)} */
    public Map<String, Object> getReconstructedTrend(Long userId, Long institutionId,
                                                     String from, String to, Double maxPrice,
                                                     String buyTime, String sellTime,
                                                     boolean applyReturnBand,
                                                     boolean requireCrossing) {
        return getReconstructedTrend(userId, institutionId, from, to, maxPrice, buyTime, sellTime,
                                     applyReturnBand, requireCrossing, false);
    }

    /**
     * @param capPerSector see
     *        {@link #reconstructTop10AsOf(Long, Long, String, Double, boolean, boolean, boolean)}.
     *        The Ranking Chart Bar asks for it; the Avg Return Trend does not.
     */
    public Map<String, Object> getReconstructedTrend(Long userId, Long institutionId,
                                                     String from, String to, Double maxPrice,
                                                     String buyTime, String sellTime,
                                                     boolean applyReturnBand,
                                                     boolean requireCrossing,
                                                     boolean capPerSector) {
        return getReconstructedTrend(userId, institutionId, from, to, maxPrice, buyTime, sellTime,
                                     applyReturnBand, requireCrossing, capPerSector, true);
    }

    /**
     * @param applyRanking rebuild each past day the way the section that shows it selects today.
     *        A section that has dropped ranking must drop it here too, or its bars are the average
     *        of a ranked ten while its table lists every match - two different populations under
     *        one heading, which is worse than either on its own.
     */
    public Map<String, Object> getReconstructedTrend(Long userId, Long institutionId,
                                                     String from, String to, Double maxPrice,
                                                     String buyTime, String sellTime,
                                                     boolean applyReturnBand,
                                                     boolean requireCrossing,
                                                     boolean capPerSector,
                                                     boolean applyRanking) {
        final String bt = (buyTime  == null || buyTime.isBlank())  ? getTradeBuyTime()  : buyTime;
        final String st = (sellTime == null || sellTime.isBlank()) ? getTradeSellTime() : sellTime;
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> bars = new ArrayList<>();
        // No range given means the whole of what can honestly be rebuilt.
        String coverageFrom = earliestReconstructableDate(userId, institutionId);
        if (from == null || from.isBlank()) from = coverageFrom;
        if (to == null || to.isBlank()) to = DateUtil.today();
        out.put("coverage_from", coverageFrom);
        List<String> days = tradingDaysWithData(from, to);

        // Look a little past the range so the last day can still be settled if the market has
        // since traded - otherwise the most interesting day is always the one left unmeasured.
        List<String> forward = tradingDaysWithData(
            to, java.time.LocalDate.parse(to).plusDays(10).toString());

        long started = System.currentTimeMillis();

        // Load every day's history in one pass before the loop starts.
        //
        // Each simulated day used to run its own 45-session candle scan, so the cost of the chart
        // was the number of bars on it - twenty-two scans for a thirty-day range, on top of two
        // intraday lookups per day. That is what left the panel on "Running..." with nothing drawn
        // beneath it. The window below is a superset of every day in the loop, so each day now
        // slices what is already in memory. Primed from the upload history rather than from any
        // one day's universe, because a symbol can drop out of the universe mid-range and must
        // still have its bars when the earlier days ask for them.
        boolean primed = false;
        if (!days.isEmpty()) {
            try {
                String wFrom = java.time.LocalDate.parse(days.get(0)).minusDays(60).toString();
                // Two weeks past the last signal date. Holding-day returns look FORWARD from the
                // entry, so a window that stops at the last signal leaves the newest trades with
                // nothing to measure Day 1-5 against. Only the cache bound moves; the days the
                // loop iterates over are unchanged, so this adds no signal and removes none.
                String wTo = java.time.LocalDate.parse(days.get(days.size() - 1))
                    .plusDays(14).toString();
                List<String> allSyms = universeSymbols(userId, institutionId, wTo);
                if (!allSyms.isEmpty()) {
                    long tw = System.currentTimeMillis();
                    // Loaded with the window unset, so this call does the real query - and picks
                    // up the intraday gap fold on the way, exactly as the per-day calls did.
                    int span = (int) java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.parse(wFrom), java.time.LocalDate.parse(wTo)) + 1;
                    Map<String, List<DayBar>> all = dailyBarsUpTo(allSyms, wTo, span);
                    barWindow.set(new BarWindow(wFrom, wTo, all));
                    primed = true;
                    log.info("[TrendWindow] {} symbol(s) over {}..{} loaded once in {} ms "
                           + "for {} simulated day(s)",
                        all.size(), wFrom, wTo, System.currentTimeMillis() - tw, days.size());
                }
            } catch (Exception e) {
                // A window is an optimisation, never a requirement: fall back to per-day loads.
                log.debug("Trend bar window skipped: {}", e.getMessage());
            }
        }
        try {
        int reused = 0;
        for (int i = 0; i < days.size(); i++) {
            final String day = days.get(i);
            String next = (i + 1 < days.size()) ? days.get(i + 1)
                : forward.stream().filter(d -> d.compareTo(day) > 0).findFirst().orElse(null);

            // A settled day is finished history and cannot change.
            //
            // Its ten are chosen from stored candles up to that date and its return is measured
            // between two prints that have both already happened - so rebuilding it on every
            // request is pure repetition. At roughly 300 ms a day that repetition is the whole
            // reason the chart could only afford a 30-day window while the database holds 301
            // sessions. Cached, the first build of a range pays for it and every later one is
            // near-free, which is what makes the full back history usable at all.
            //
            // Only settled days are stored. The newest day is an open position valued at the
            // live price and must be recomputed every time, so it never enters this map.
            String dayKey = userId + "|" + institutionId + "|" + day + "|" + next + "|" + bt + "|"
                + st + "|" + maxPrice + "|" + applyReturnBand + "|" + requireCrossing + "|"
                + capPerSector + "|" + applyRanking;
            Map<String, Object> cached = settledDayCache.get(dayKey);
            if (cached != null) { bars.add(cached); reused++; continue; }

            List<Map<String, Object>> top = reconstructTop10AsOf(
                userId, institutionId, day, maxPrice, applyReturnBand, requireCrossing,
                capPerSector, true, applyRanking);
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("date", day);
            bar.put("stocks_ranked", top.size());

            if (top.isEmpty()) {
                bar.put("trade_avg_return", null);
                // "Nothing qualified" and "the day is not finished" are different facts, and
                // saying the first when the second is true reads as a strategy that found nothing
                // rather than as a session still in progress. Selection needs the official daily
                // close for the day being rebuilt, and that is published after the session - so
                // mid-session today has intraday candles but no daily bar, and ranks nothing yet.
                boolean sessionIncomplete = day.compareTo(DateUtil.today()) >= 0;
                bar.put("trade_source", sessionIncomplete ? "pending" : "no_data");
                bar.put("note", sessionIncomplete
                    ? "Today is still trading. This bar fills in once the session's daily close "
                    + "is published."
                    : "No stock met the entry rules on this day.");
                bars.add(bar);
                continue;
            }
            List<String> syms = top.stream().map(s -> String.valueOf(s.get("symbol"))).toList();
            bar.put("symbols", syms);

            // A position is open until its EXIT has actually happened - which is not the same as
            // being the last day in the list. The moment today appeared as a day of its own, the
            // previous session stopped being the last row and was treated as settled, so it tried
            // to price an 11:00 exit that is still hours away, found nothing, and reported
            // "no_data" - six ranked stocks showing a blank bar. That is the newest meaningful bar
            // on the chart, so the chart looked stuck one session back.
            boolean exitPending = next == null || next.compareTo(DateUtil.today()) >= 0;
            if (exitPending) {
                // The most recent ranking day has no following session yet, so the trade it opened
                // is still running. Reporting it as blank made an open position with ten stocks in
                // it indistinguishable from a day on which nothing qualified - the two could not be
                // further apart. Value it at the market instead, so the bar moves with the position.
                Map<String, Double> nowPrices = resolveCurrentPricesBatch(syms, institutionId);
                Map<String, Double> openBuys = intradayPricesAt(syms, day, bt);
                double s2 = 0;
                int m = 0, up = 0, down = 0;
                for (Map<String, Object> s : top) {
                    String key = normalizeSymbol(String.valueOf(s.get("symbol")));
                    Double buy = openBuys.get(key);
                    if (buy == null || buy <= 0) buy = getD(s, "current_price");   // that day's close
                    Double now = nowPrices.get(key);
                    if (buy == null || buy <= 0 || now == null || now <= 0) continue;
                    double r = (now - buy) / buy * 100.0;
                    s2 += r; m++;
                    if (r >= 0) up++; else down++;
                }
                bar.put("trade_avg_return", null);        // not settled, so it never enters the average
                bar.put("trade_source", "open");
                bar.put("sell_date", next);
                bar.put("open_return_pct", m > 0 ? Math.round((s2 / m) * 100.0) / 100.0 : null);
                bar.put("open_priced", m);
                bar.put("trade_winners", up);
                bar.put("trade_losers", down);
                bar.put("note", m > 0
                    ? "Open position, valued at the current price. Settles at " + st
                      + (next == null ? " on the next trading day." : " on " + next + ".")
                    : "Open position. No current price available for these stocks yet.");
                bars.add(bar);
                continue;
            }

            // Trade at the configured times, not at closes: the position is opened at the 15:20
            // lock on the ranking day and closed at 15:15 the next trading day. Falling back to a
            // close would quietly measure a different trade from the one the strategy makes.
            Map<String, Double> buyPrices  = intradayPricesAt(syms, day,  bt);
            Map<String, Double> sellPrices = intradayPricesAt(syms, next, st);
            double sum = 0;
            int n = 0, win = 0, loss = 0;
            // Per-stock detail, so a day's number can be taken apart rather than trusted. Each row
            // carries the two prices the return was actually computed from, and a stock that could
            // not be priced says WHICH side was missing - a missing buy and a missing sell are
            // different problems (the stock never traded at the entry time, versus never traded at
            // the exit time), and lumping them together hides which one is happening.
            List<Map<String, Object>> priced = new ArrayList<>();
            List<Map<String, Object>> unpricedRows = new ArrayList<>();
            for (Map<String, Object> s : top) {
                String symbol = String.valueOf(s.get("symbol"));
                String key = normalizeSymbol(symbol);
                Double buy = buyPrices.get(key);
                Double sell = sellPrices.get(key);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("symbol", symbol);
                row.put("rank", priced.size() + unpricedRows.size() + 1);
                row.put("score", s.get("composite_score"));

                boolean hasBuy  = buy  != null && buy  > 0;
                boolean hasSell = sell != null && sell > 0;
                if (!hasBuy || !hasSell) {
                    row.put("buy_price",  hasBuy  ? buy  : null);
                    row.put("sell_price", hasSell ? sell : null);
                    row.put("reason", !hasBuy && !hasSell
                        ? "No trade at " + bt + " on " + day + " or " + st + " on " + next
                        : !hasBuy
                            ? "No trade at " + bt + " on " + day + " (entry)"
                            : "No trade at " + st + " on " + next + " (exit)");
                    unpricedRows.add(row);
                    continue;
                }
                double r = (sell - buy) / buy * 100.0;
                row.put("buy_date",   day);
                row.put("buy_time",   bt);
                row.put("buy_price",  Math.round(buy  * 100.0) / 100.0);
                row.put("sell_date",  next);
                row.put("sell_time",  st);
                row.put("sell_price", Math.round(sell * 100.0) / 100.0);
                row.put("return_pct", Math.round(r * 100.0) / 100.0);
                putHoldingDayReturns(row, symbol, day, buy);
                priced.add(row);
                sum += r;
                n++;
                if (r >= 0) win++; else loss++;
            }
            bar.put("priced", priced);
            bar.put("unpriced", unpricedRows);
            bar.put("priced_count", priced.size());
            bar.put("unpriced_count", unpricedRows.size());
            // How much of the intended basket the day's average actually rests on.
            bar.put("coverage_pct", top.isEmpty() ? 0
                : Math.round((priced.size() * 1000.0 / top.size())) / 10.0);
            if (n == 0) {
                bar.put("trade_avg_return", null);
                bar.put("trade_source", "no_data");
                bar.put("note", "No traded price at " + bt + " on " + day
                              + " or " + st + " on " + next + " for these stocks.");
            } else {
                bar.put("trade_avg_return", Math.round((sum / n) * 100.0) / 100.0);
                bar.put("trade_source", "reconstructed");
                bar.put("trade_winners", win);
                bar.put("trade_losers", loss);
                bar.put("trade_symbol_count", n);
                bar.put("sell_date", next);
                // Settled: both legs are in the past, so this bar is now permanent.
                if (settledDayCache.size() < SETTLED_DAY_CACHE_MAX) {
                    settledDayCache.put(dayKey, java.util.Collections.unmodifiableMap(bar));
                }
            }
            bars.add(bar);
        }
        if (reused > 0) {
            log.info("[TrendCache] {} of {} day(s) served from settled history", reused, days.size());
        }
        } finally {
            // Pooled thread: the window must never outlive this rebuild.
            if (primed) barWindow.remove();
        }

        List<Double> measured = bars.stream()
            .map(b -> (Double) b.get("trade_avg_return"))
            .filter(java.util.Objects::nonNull)
            .toList();
        double avg = measured.isEmpty() ? 0
            : Math.round(measured.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100.0) / 100.0;

        out.put("days", bars);
        out.put("trading_days", days.size());
        out.put("measured_days", measured.size());
        out.put("average_return_pct", avg);
        out.put("best_day", measured.isEmpty() ? null
            : Math.round(measured.stream().mapToDouble(Double::doubleValue).max().orElse(0) * 100.0) / 100.0);
        out.put("worst_day", measured.isEmpty() ? null
            : Math.round(measured.stream().mapToDouble(Double::doubleValue).min().orElse(0) * 100.0) / 100.0);
        out.put("positive_days", measured.stream().filter(v -> v > 0).count());
        // Days measured on less than 70% of the intended ten. Their average is arithmetically
        // fine but rests on a smaller basket, so it is surfaced rather than blended in silently.
        List<String> thin = bars.stream()
            .filter(x -> x.get("trade_avg_return") != null)
            .filter(x -> ((Number) x.getOrDefault("coverage_pct", 100.0)).doubleValue() < 70.0)
            .map(x -> String.valueOf(x.get("date")))
            .toList();
        out.put("low_coverage_days", thin);
        out.put("low_coverage_count", thin.size());
        out.put("took_ms", System.currentTimeMillis() - started);
        out.put("max_price", maxPrice);
        out.put("entry_test", requireCrossing
            ? "Previous Close < Base Price AND Current Price >= Base Price (the crossing) "
              + "AND Current Volume > 20-Day Average Volume"
            : "Current Price >= Base Price AND Current Volume > 20-Day Average Volume");
        out.put("require_crossing", requireCrossing);
        out.put("cap_per_sector", capPerSector);
        out.put("buy_time", bt);
        out.put("sell_time", st);
        out.put("rule", "Buy the reconstructed Top 10 at " + bt + " on the ranking day, "
                      + "sell at " + st + " the next trading day."
                      + (maxPrice != null ? " Only stocks with a base price below " + maxPrice + " are considered." : ""));
        return out;
    }

    /**
     * @param asOf when non-null, every factor is computed from bars up to that date only. Nothing
     *        else changes - same weights, same thresholds, same normalisation - so a reconstructed
     *        score is directly comparable with one produced live on the day.
     */
    private Map<String, Object> scoreTrackedStock(Map<String, Object> stock, Map<String, Double> weights,
                                                   double[] cohortReturns, String asOf) {
        Map<String, Object> result = new LinkedHashMap<>(stock);
        FactorAccumulator acc = new FactorAccumulator();

        String symbol      = (String) stock.get("symbol");
        double returnPct   = getD(stock, "return_pct");
        double currentPrice= getD(stock, "current_price");
        String mcap        = (String) stock.getOrDefault("market_cap_name", "");

        // 260, not 200: 52-Week Position needs ~240 daily bars, so a 200-bar window meant that
        // factor could never qualify no matter how much history existed. A year of Indian
        // trading is ~250 sessions, so 260 covers it with a little slack.
        OhlcvHistory ohlcv = loadOhlcvHistory(symbol, 260, asOf);
        List<Double> closes  = ohlcv.closes();
        List<Double> highs   = ohlcv.highs();
        List<Double> lows    = ohlcv.lows();
        List<Double> volumes = ohlcv.volumes();
        List<Double> deliv   = ohlcv.delivery();
        // 1. Relative Strength — percentile against the stocks being ranked in this run
        double rsWt = weights.getOrDefault("relative_strength", 17.0);
        acc.consider("relative_strength", "Relative Strength", scoreRelativeStrength(returnPct, cohortReturns), rsWt);

        // 2. Volume Growth
        double volWt = weights.getOrDefault("volume_growth", 11.0);
        if (hasBars(volumes, 20)) acc.consider("volume_growth", "Volume Growth", scoreVolumeGrowth(volumes), volWt);
        else acc.skip("volume_growth", "Volume Growth", volWt,  "Need at least 20 volume records in market_data.");

        // Delivery % and Sector Strength were removed from the ranking: delivery data is not
        // reliably present in market_data and sector scores were frequently unavailable, so
        // both were skipped for most stocks anyway. Their combined 20 points are redistributed
        // across the remaining 11 factors (see the defaults below), which now total 100.

        // 5. Trend Strength (EMA alignment)
        double trendWt = weights.getOrDefault("trend_strength", 11.0);
        if (hasBars(closes, 20)) acc.consider("trend_strength", "Trend Strength", scoreTrendStrength(closes, currentPrice), trendWt);
        else acc.skip("trend_strength", "Trend Strength", trendWt,  "Need at least 20 close-price records.");

        // 6. RSI Strength
        double rsiWt = weights.getOrDefault("rsi_strength", 9.0);
        if (hasBars(closes, 15)) acc.consider("rsi_strength", "RSI Strength", scoreRsi(closes), rsiWt);
        else acc.skip("rsi_strength", "RSI Strength", rsiWt,  "Need at least 15 close-price records for RSI(14).");

        // 7. MA Position
        double maWt = weights.getOrDefault("ma_position", 9.0);
        if (hasBars(closes, 20)) acc.consider("ma_position", "MA Position", scoreMaPosition(closes, currentPrice), maWt);
        else acc.skip("ma_position", "MA Position", maWt,  "Need at least 20 close-price records.");

        // 8. Price Momentum (ROC)
        double momWt = weights.getOrDefault("price_momentum", 9.0);
        if (hasBars(closes, 10)) acc.consider("price_momentum", "Price Momentum", scoreMomentum(closes), momWt);
        else acc.skip("price_momentum", "Price Momentum", momWt,  "Need at least 10 close-price records.");

        // 9. 52-Week Position
        double w52Wt = weights.getOrDefault("week52_position", 8.0);
        // 240 trading days ~= 52 weeks. Below that this measured position within whatever
        // history existed and still called it "52-week" - a 4-month high reported as a
        // yearly high. Skipping is honest, and the reason tells the user to backfill more.
        if (hasBars(highs, 240) && hasBars(lows, 240)) acc.consider("week52_position", "52-Week Position", scoreWeek52Position(closes, highs, lows, currentPrice), w52Wt);
        else acc.skip("week52_position", "52-Week Position", w52Wt, 
            "Need ~240 daily bars for a true 52-week range; have " + highs.size()
            + ". Backfill more daily history from the Data Warehouse.");

        // 10. Breakout Strength
        double boWt = weights.getOrDefault("breakout_strength", 8.0);
        if (hasBars(closes, 20) && hasBars(highs, 20)) acc.consider("breakout_strength", "Breakout Strength", scoreBreakout(closes, highs), boWt);
        else acc.skip("breakout_strength", "Breakout Strength", boWt,  "Need at least 20 close/high records.");

        // 11. Liquidity
        double liqWt = weights.getOrDefault("liquidity", 5.0);
        if (!volumes.isEmpty() && currentPrice > 0) acc.consider("liquidity", "Liquidity", scoreLiquidity(volumes, currentPrice), liqWt);
        else acc.skip("liquidity", "Liquidity", liqWt,  "Need volume records and current price.");

        // 12. Market Cap Quality
        double mcapWt = weights.getOrDefault("market_cap_quality", 5.0);
        if (mcap != null && !mcap.isBlank()) acc.consider("market_cap_quality", "Market Cap Quality", scoreMarketCap(mcap), mcapWt);
        else acc.skip("market_cap_quality", "Market Cap Quality", mcapWt,  "MarketCapName is not available in uploaded stock data.");

        // 13. Institutional Interest
        double instWt = weights.getOrDefault("institutional_interest", 8.0);
        if (deliv.size() >= 5) acc.consider("institutional_interest", "Institutional Interest", scoreInstitutional(deliv), instWt);
        else acc.skip("institutional_interest", "Institutional Interest", instWt,  "Need at least 5 delivery percentage records.");

        // Normalise
        double composite = acc.normalizedScore();
        composite = Math.min(100, Math.max(0, Math.round(composite * 10.0) / 10.0));

        result.put("composite_score", composite);
        result.put("factor_scores",   acc.factorScores());
        result.put("considered_components", acc.consideredLabels());
        result.put("skipped_components", acc.skipped());
        result.put("signal",          composite >= 72 ? "STRONG BUY" : composite >= 55 ? "BUY" : "WATCH");
        return result;
    }

    private boolean hasBars(List<Double> values, int min) {
        return values != null && values.stream().filter(v -> v != null && v > 0).count() >= min;
    }

    // ── Individual Factor Scorers ─────────────────────────────────────────────

    /**
     * Relative Strength: where this stock's return sits within the cohort being ranked.
     *
     * <p>This used to band the stock's own return against a hard-coded 0-8% range while being
     * labelled "vs Nifty" — it compared against nothing, and since eligibility caps returns at
     * {@link #getEligibleMaxReturn()} (5%) the top bands were unreachable, so the largest factor
     * in the model could never score above 8/10. There is no index history stored to compare
     * against (index_data is empty), so the cohort itself is the benchmark: outperforming your
     * peers on the day is a genuine relative measure and needs no external feed.
     */
    private double scoreRelativeStrength(double returnPct, double[] cohortReturns) {
        if (returnPct < 0) return 0;
        if (cohortReturns == null || cohortReturns.length < 5) {
            // Too few peers to rank against — band against the configured eligible range so the
            // full 0-10 scale stays reachable whatever that range is set to.
            double max = Math.max(0.01, getEligibleMaxReturn());
            double pct = Math.min(1.0, returnPct / max);
            if (pct >= 0.80) return 10;
            if (pct >= 0.60) return 8;
            if (pct >= 0.40) return 6;
            if (pct >= 0.20) return 4;
            return 2;
        }
        int below = 0;
        for (double r : cohortReturns) {
            if (r < returnPct) below++;
            else break;                     // sorted ascending
        }
        double percentile = (double) below / cohortReturns.length;
        if (percentile >= 0.80) return 10;
        if (percentile >= 0.60) return 8;
        if (percentile >= 0.40) return 6;
        if (percentile >= 0.20) return 4;
        return 2;
    }

    private double scoreVolumeGrowth(List<Double> volumes) {
        if (volumes == null || volumes.size() < 20) return 5;
        double last = volumes.get(volumes.size()-1);
        double avg20 = indicator.sma(volumes, 20);
        if (avg20 <= 0) return 5;
        double ratio = last / avg20;
        if (ratio >= 3)   return 10;
        if (ratio >= 2)   return 8;
        if (ratio >= 1.5) return 6;
        if (ratio >= 1.0) return 4;
        return 2;
    }

    private double scoreDelivery(List<Double> deliv) {
        if (deliv == null || deliv.isEmpty()) return 5;
        double d = deliv.get(deliv.size()-1);
        if (d >= 75) return 10;
        if (d >= 60) return 8;
        if (d >= 45) return 6;
        if (d >= 30) return 4;
        return 2;
    }

    /**
     * EMA alignment, scored only over the averages the history can actually support.
     *
     * <p>This used to substitute EMA50 for EMA200 (and EMA20 for EMA50) when history was
     * short. That made "price above EMA50" and "price above EMA200" the same test scored
     * twice, and made "EMA50 above EMA200" a comparison of a value with itself - never
     * true, so 1.5 of the 10 points were unreachable. With under 200 bars every stock was
     * affected. Each comparison now runs only when its average is real, and the result is
     * rescaled so a shorter history is not structurally penalised.
     */
    private double scoreTrendStrength(List<Double> closes, double cmp) {
        if (closes == null || closes.size() < 20) return 5;
        boolean has50  = closes.size() >= 50;
        boolean has200 = closes.size() >= 200;
        double ema20  = indicator.ema(closes, 20);
        double ema50  = has50  ? indicator.ema(closes, 50)  : 0;
        double ema200 = has200 ? indicator.ema(closes, 200) : 0;

        double score = 0, max = 2.5;
        if (cmp > ema20) score += 2.5;
        if (has50) {
            max += 4;
            if (cmp > ema50)   score += 2;
            if (ema20 > ema50) score += 2;
        }
        if (has200) {
            max += 3.5;
            if (cmp > ema200)   score += 2;
            if (ema50 > ema200) score += 1.5;
        }
        return max <= 0 ? 5 : Math.min(10, Math.round(score / max * 100.0) / 10.0);
    }

    private double scoreRsi(List<Double> closes) {
        // 15, not 14: rsi() needs period + 1 closes and silently returns a neutral 50
        // below that, which would score a fabricated 8/10 instead of being skipped.
        if (closes == null || closes.size() < 15) return 5;
        double rsi = indicator.rsi(closes, 14);
        if (rsi >= 55 && rsi <= 65) return 10;
        if (rsi >= 45 && rsi < 55)  return 8;
        if (rsi >= 65 && rsi <= 72) return 6;
        if (rsi >= 35 && rsi < 45)  return 4;
        return 2;
    }

    /**
     * MA Position: how far price sits above its 50-day average.
     *
     * <p>Previously this delegated straight to {@link #scoreTrendStrength}, so two separately
     * weighted factors returned an identical number from identical inputs — one EMA signal
     * counted twice for 20% of the composite. Trend Strength answers "are the averages stacked
     * in the right order"; this answers "by how much", which is a different question. Extension
     * beyond ~15% is not rewarded further, since that reads as over-extended rather than strong.
     */
    private double scoreMaPosition(List<Double> closes, double cmp) {
        if (closes == null || closes.size() < 20 || cmp <= 0) return 5;
        double sma50 = closes.size() >= 50 ? indicator.sma(closes, 50) : indicator.sma(closes, 20);
        if (sma50 <= 0) return 5;
        double pctAbove = (cmp - sma50) / sma50 * 100.0;
        if (pctAbove >= 15) return 7;      // over-extended
        if (pctAbove >= 8)  return 10;
        if (pctAbove >= 4)  return 9;
        if (pctAbove >= 1)  return 7;
        if (pctAbove >= 0)  return 5;
        if (pctAbove >= -5) return 3;
        return 1;
    }

    private double scoreMomentum(List<Double> closes) {
        if (closes == null || closes.size() < 10) return 5;
        double roc = indicator.roc(closes, 10);
        if (roc >= 5)  return 10;
        if (roc >= 3)  return 8;
        if (roc >= 1)  return 6;
        if (roc >= 0)  return 4;
        return 2;
    }

    private double scoreWeek52Position(List<Double> closes, List<Double> highs, List<Double> lows, double cmp) {
        if (highs == null || lows == null || highs.size() < 10) return 5;
        int bars = Math.min(200, highs.size());
        double high52 = highs.subList(highs.size()-bars, highs.size()).stream().mapToDouble(Double::doubleValue).max().orElse(cmp);
        double low52  = lows.subList(lows.size()-bars, lows.size()).stream().mapToDouble(Double::doubleValue).min().orElse(cmp);
        if (high52 <= low52) return 5;

        double posInRange = (cmp - low52) / (high52 - low52) * 100;
        double distFromHigh = (high52 - cmp) / high52 * 100;

        double score = 0;
        if (posInRange >= 75)  score += 5;
        else if (posInRange >= 50) score += 3;
        else score += 1;
        if (distFromHigh <= 5)  score += 5;
        else if (distFromHigh <= 15) score += 3;
        else score += 1;
        return Math.min(10, score);
    }

    private double scoreBreakout(List<Double> closes, List<Double> highs) {
        if (closes == null || closes.size() < 20) return 3;
        double score = 0;
        if (indicator.isBreakout(closes, highs, 20))  score += 5;
        if (indicator.isBreakout(closes, highs, Math.min(200, closes.size()-1))) score += 5;
        return Math.min(10, score);
    }

    private double scoreLiquidity(List<Double> volumes, double cmp) {
        if (volumes == null || volumes.isEmpty()) return 5;
        double avgVol = indicator.sma(volumes, Math.min(20, volumes.size()));
        double avgTurnover = avgVol * cmp / 1e7; // crores
        if (avgTurnover >= 50)  return 10;
        if (avgTurnover >= 20)  return 8;
        if (avgTurnover >= 10)  return 6;
        if (avgTurnover >= 5)   return 4;
        return 2;
    }

    private double scoreMarketCap(String mcap) {
        if (mcap == null) return 5;
        String m = mcap.toLowerCase();
        if (m.contains("large")) return 8;
        if (m.contains("mid"))   return 7;
        if (m.contains("small")) return 6;
        return 5;
    }

    private double scoreInstitutional(List<Double> deliv) {
        if (deliv == null || deliv.size() < 5) return 5;
        // Trend in delivery % over last 5 sessions
        double recent = deliv.subList(Math.max(0, deliv.size()-5), deliv.size())
            .stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return scoreDelivery(List.of(recent));
    }

    // ==========================================================================
    //  QUERY METHODS
    // ==========================================================================

    @SuppressWarnings("unchecked")
    public Map<String, Object> getDashboardData(Long institutionId) {
        return getDashboardData(null, institutionId);
    }

    public Map<String, Object> getDashboardData(Long userId, Long institutionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*), " +
                "SUM(CASE WHEN return_pct >= 0 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN return_pct < 0 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN return_pct >= " + getEligibleMinReturn() + " AND return_pct <= " + getEligibleMaxReturn() + " THEN 1 ELSE 0 END), " +
                "COALESCE(AVG(return_pct), 0), " +
                "MAX(last_refreshed) " +
                "FROM tracked_stocks WHERE institution_id=? AND is_active=1");
            if (userId != null) sql.append(" AND user_id=?");
            var q = em.createNativeQuery(sql.toString()).setParameter(1, institutionId);
            if (userId != null) q.setParameter(2, userId);
            List<Object[]> rows = q.getResultList();
            if (!rows.isEmpty()) {
                Object[] r = rows.get(0);
                result.put("total_stocks",    getNumber(r[0]));
                result.put("positive_stocks", getNumber(r[1]));
                result.put("negative_stocks", getNumber(r[2]));
                result.put("eligible_stocks", getNumber(r[3]));
                result.put("avg_return",      Math.round(getNumber(r[4]) * 100) / 100.0);
                result.put("last_refreshed",  r[5] != null ? r[5].toString() : "-");
            }
        } catch (Exception e) {
            result.put("total_stocks", 0); result.put("positive_stocks", 0);
            result.put("negative_stocks", 0); result.put("eligible_stocks", 0);
            result.put("avg_return", 0); result.put("last_refreshed", "-");
        }

        Map<String, Object> ranking = getLatestRankingSummary(userId, institutionId);
        result.put("ranking", ranking);
        result.put("market_status", isMarketOpen() ? "OPEN" : "CLOSED");
        return result;
    }

    private Map<String, Object> buildFallbackTop10Ranking(Long institutionId) {
        return buildFallbackTop10Ranking(null, institutionId);
    }

    private Map<String, Object> buildFallbackTop10Ranking(Long userId, Long institutionId) {
        Map<String, Double> weights = loadTrackerWeights(institutionId);
        List<Map<String, Object>> candidates = getRankingCandidates(userId, institutionId);
        if (candidates.isEmpty()) {
            candidates = getAllTrackedStocksScoped(userId, institutionId, null, null, null, null, "return_pct", "desc", 0, 50000, false);
        }
        double[] cohortReturns = candidates.stream().mapToDouble(c -> getD(c, "return_pct")).sorted().toArray();
        List<Map<String, Object>> scored = candidates.stream()
            .map(s -> scoreTrackedStock(s, weights, cohortReturns))
            .sorted((a, b) -> Double.compare(getD(b, "composite_score"), getD(a, "composite_score")))
            .limit(getTopN())
            .collect(Collectors.toList());

        // Two places, both money: round before it leaves. These are float quotes and reached the
        // Today top 10 table as 724.0999755859375 next to neighbours printed to two places.
        for (Map<String, Object> s : scored) {
            if (s.get("current_price") != null)  s.put("current_price",  round2(getD(s, "current_price")));
            if (s.get("previous_close") != null) s.put("previous_close", round2(getD(s, "previous_close")));
            if (s.get("base_price") != null)     s.put("base_price",     round2(getD(s, "base_price")));
            if (s.get("return_pct") != null)     s.put("return_pct",     round2(getD(s, "return_pct")));
        }

        double avgReturn = scored.stream().mapToDouble(s -> getD(s, "return_pct")).average().orElse(0);
        long positive = scored.stream().filter(s -> getD(s, "return_pct") >= 0).count();
        Map<String, Long> sectorDist = scored.stream().collect(
            Collectors.groupingBy(s -> String.valueOf(s.getOrDefault("sector", "Unknown")), Collectors.counting()));

        Map<String, Object> result = buildRankingResponse(
            // Not a fallback any more - this is how Today top 10 is produced. The old prefix
            // told the screen a saved run was missing when nothing was missing at all.
            "LIVE-" + System.currentTimeMillis(),
            scored, List.of(), scored,
            avgReturn, 0, scored.isEmpty() ? 0 : positive * 100.0 / scored.size(), 0,
            (int) positive, 0, candidates.size(), weights, sectorDist);
        result.put("fallback_ranking", true);
        result.put("message", "No saved ranking run is available. Showing fallback Top 10 using uploaded stocks and available components.");
        result.putAll(rankingQualitySummary(scored));
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAllTrackedStocks(Long institutionId,
            String sector, String mcap, Double minReturn, Double maxReturn,
            String sortBy, String sortDir, int page, int size) {
        return getAllTrackedStocksScoped(null, institutionId, sector, mcap, minReturn, maxReturn, sortBy, sortDir, page, size, false);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAllTrackedStocks(Long userId, Long institutionId,
            String sector, String mcap, Double minReturn, Double maxReturn,
            String sortBy, String sortDir, int page, int size) {
        return getAllTrackedStocksScoped(userId, institutionId, sector, mcap, minReturn, maxReturn, sortBy, sortDir, page, size, true);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAllTrackedStocks(Long userId, Long institutionId,
            String sector, String mcap, Double minReturn, Double maxReturn,
            String sortBy, String sortDir, int page, int size, boolean includeSignals) {
        return getAllTrackedStocksScoped(userId, institutionId, sector, mcap, minReturn, maxReturn, sortBy, sortDir, page, size, includeSignals);
    }

    public Map<String, Object> removeUploadedStock(Long userId, Long institutionId, String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw new IllegalArgumentException("Symbol is required to remove an uploaded stock.");
        }
        return new TransactionTemplate(transactionManager).execute(status -> {
            int updated = em.createNativeQuery(
                    "UPDATE tracked_stocks SET is_active=0, is_deleted=1, deleted_at=?, deleted_by=?, delete_type='SINGLE', updated_at=? " +
                    "WHERE institution_id=? AND user_id=? AND is_active=1 AND " +
                    "UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(symbol),'.NS',''),'.BO',''),'-EQ',''),'-BE',''),'-BZ',''),'-SM',''),'-ST',''))=?")
                .setParameter(1, DateUtil.now())
                .setParameter(2, userId)
                .setParameter(3, DateUtil.now())
                .setParameter(4, institutionId)
                .setParameter(5, userId)
                .setParameter(6, normalizedSymbol)
                .executeUpdate();
            if (updated > 0) logDeletion(institutionId, userId, "DELETE_SINGLE", "SINGLE", null, 1, null, normalizedSymbol);
            auditLog(institutionId, userId, "REMOVE_UPLOADED_STOCK", "tracked_stocks", normalizedSymbol,
                updated > 0 ? "Removed uploaded stock from active tracker list." : "Uploaded stock was not active or not found.");
            return Map.of(
                "symbol", normalizedSymbol,
                "removed", updated > 0,
                "message", updated > 0
                    ? normalizedSymbol + " removed from uploaded stock list."
                    : normalizedSymbol + " was not found in your active uploaded stock list."
            );
        });
    }

    // ── Batch Delete: soft-delete all stocks in a specific upload batch ────────
    public Map<String, Object> deleteUploadBatch(Long userId, Long institutionId, String batchId, boolean isAdmin) {
        if (batchId == null || batchId.isBlank()) throw new IllegalArgumentException("Batch ID is required.");
        return new TransactionTemplate(transactionManager).execute(status -> {
            StringBuilder where = new StringBuilder(
                "WHERE institution_id=? AND is_active=1 AND COALESCE(batch_id,'')=?");
            List<Object> params = new ArrayList<>(List.of(institutionId, batchId));
            if (!isAdmin) { where.append(" AND user_id=?"); params.add(userId); }
            int count = ((Number) buildCountQuery("tracked_stocks", where.toString(), params)).intValue();
            if (count == 0) return Map.of("deleted", 0, "message", "No active stocks found for batch " + batchId);
            StringBuilder update = new StringBuilder(
                "UPDATE tracked_stocks SET is_active=0, is_deleted=1, deleted_at=?, deleted_by=?, delete_type='BATCH', updated_at=? ");
            update.append(where);
            List<Object> uParams = new ArrayList<>(List.of(DateUtil.now(), userId, DateUtil.now()));
            uParams.addAll(params);
            var q = em.createNativeQuery(update.toString());
            for (int i = 0; i < uParams.size(); i++) q.setParameter(i + 1, uParams.get(i));
            int deleted = q.executeUpdate();
            try { em.createNativeQuery("UPDATE stock_uploads SET is_deleted=1, deleted_at=?, deleted_by=? WHERE batch_id=? AND institution_id=?")
                .setParameter(1, DateUtil.now()).setParameter(2, userId).setParameter(3, batchId).setParameter(4, institutionId).executeUpdate();
            } catch (Exception ignored) {}
            logDeletion(institutionId, userId, "DELETE_BATCH", "BATCH", batchId, deleted, null, null);
            auditLog(institutionId, userId, "DELETE_UPLOAD_BATCH", "tracked_stocks", batchId,
                Map.of("batch_id", batchId, "deleted", deleted));
            return Map.of("deleted", deleted, "batch_id", batchId,
                "message", "Batch deleted successfully. Total records removed: " + deleted);
        });
    }

    // ── Delete All: soft-delete all uploaded stocks ─────────────────────────────
    public Map<String, Object> deleteAllUploadedStocks(Long userId, Long institutionId, boolean isAdmin) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            StringBuilder where = new StringBuilder("WHERE institution_id=? AND is_active=1");
            List<Object> params = new ArrayList<>(List.of(institutionId));
            if (!isAdmin) { where.append(" AND user_id=?"); params.add(userId); }
            int count = ((Number) buildCountQuery("tracked_stocks", where.toString(), params)).intValue();
            if (count == 0) return Map.of("deleted", 0, "message", "No active stocks to delete.");
            StringBuilder update = new StringBuilder(
                "UPDATE tracked_stocks SET is_active=0, is_deleted=1, deleted_at=?, deleted_by=?, delete_type='ALL', updated_at=? ");
            update.append(where);
            List<Object> uParams = new ArrayList<>(List.of(DateUtil.now(), userId, DateUtil.now()));
            uParams.addAll(params);
            var q = em.createNativeQuery(update.toString());
            for (int i = 0; i < uParams.size(); i++) q.setParameter(i + 1, uParams.get(i));
            int deleted = q.executeUpdate();
            logDeletion(institutionId, userId, "DELETE_ALL", "ALL", null, deleted, null, null);
            auditLog(institutionId, userId, "DELETE_ALL_UPLOADED_STOCKS", "tracked_stocks", null,
                Map.of("deleted", deleted));
            return Map.of("deleted", deleted,
                "message", "All uploaded stocks removed successfully. Total records removed: " + deleted);
        });
    }

    // ── Delete Multiple (selected symbols) ──────────────────────────────────────
    public Map<String, Object> deleteSelectedStocks(Long userId, Long institutionId, List<String> symbols, boolean isAdmin) {
        if (symbols == null || symbols.isEmpty()) throw new IllegalArgumentException("No symbols provided.");
        return new TransactionTemplate(transactionManager).execute(status -> {
            List<String> normalized = symbols.stream().map(this::normalizeSymbol).filter(s -> !s.isBlank()).distinct().toList();
            if (normalized.isEmpty()) return Map.of("deleted", 0, "message", "No valid symbols provided.");
            String in = placeholders(normalized.size());
            StringBuilder sql = new StringBuilder(
                "UPDATE tracked_stocks SET is_active=0, is_deleted=1, deleted_at=?, deleted_by=?, delete_type='MULTIPLE', updated_at=? " +
                "WHERE institution_id=? AND is_active=1 AND " + normalizedSql("symbol") + " IN (" + in + ")");
            List<Object> params = new ArrayList<>(List.of(DateUtil.now(), userId, DateUtil.now(), institutionId));
            if (!isAdmin) { sql.append(" AND user_id=?"); params.add(userId); }
            var q = em.createNativeQuery(sql.toString());
            for (int i = 0; i < params.size(); i++) q.setParameter(i + 1, params.get(i));
            for (int i = 0; i < normalized.size(); i++) q.setParameter(params.size() + i + 1, normalized.get(i));
            int deleted = q.executeUpdate();
            logDeletion(institutionId, userId, "DELETE_SELECTED", "MULTIPLE", null, deleted, null, String.join(",", normalized));
            auditLog(institutionId, userId, "DELETE_SELECTED_STOCKS", "tracked_stocks", null,
                Map.of("symbols", normalized, "deleted", deleted));
            return Map.of("deleted", deleted, "symbols", normalized,
                "message", deleted + " stock" + (deleted == 1 ? "" : "s") + " removed successfully.");
        });
    }

    // ── Filtered Delete ─────────────────────────────────────────────────────────
    public Map<String, Object> deleteByFilter(Long userId, Long institutionId, Map<String, String> filters, boolean isAdmin) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            StringBuilder where = new StringBuilder("WHERE institution_id=? AND is_active=1");
            List<Object> params = new ArrayList<>();
            params.add(institutionId);
            if (!isAdmin) { where.append(" AND user_id=?"); params.add(userId); }
            String uploadDate = filters.getOrDefault("upload_date", "");
            String source = filters.getOrDefault("source", "");
            String batchId = filters.getOrDefault("batch_id", "");
            String filterDesc = "";
            if ("today".equalsIgnoreCase(uploadDate)) {
                where.append(" AND upload_date=?"); params.add(DateUtil.today()); filterDesc = "today";
            } else if ("yesterday".equalsIgnoreCase(uploadDate)) {
                where.append(" AND upload_date=?"); params.add(java.time.LocalDate.now().minusDays(1).toString()); filterDesc = "yesterday";
            } else if (!uploadDate.isBlank()) {
                where.append(" AND upload_date=?"); params.add(uploadDate); filterDesc = uploadDate;
            }
            if (!source.isBlank()) { where.append(" AND upload_id IN (SELECT upload_id FROM stock_uploads WHERE source LIKE ?)"); params.add("%" + source + "%"); filterDesc += " source=" + source; }
            if (!batchId.isBlank()) { where.append(" AND COALESCE(batch_id,'')=?"); params.add(batchId); filterDesc += " batch=" + batchId; }
            int count = ((Number) buildCountQuery("tracked_stocks", where.toString(), params)).intValue();
            if (count == 0) return Map.of("deleted", 0, "message", "No stocks match the filter criteria.");
            StringBuilder update = new StringBuilder(
                "UPDATE tracked_stocks SET is_active=0, is_deleted=1, deleted_at=?, deleted_by=?, delete_type='FILTER', updated_at=? ");
            update.append(where);
            List<Object> uParams = new ArrayList<>(List.of(DateUtil.now(), userId, DateUtil.now()));
            uParams.addAll(params);
            var q = em.createNativeQuery(update.toString());
            for (int i = 0; i < uParams.size(); i++) q.setParameter(i + 1, uParams.get(i));
            int deleted = q.executeUpdate();
            logDeletion(institutionId, userId, "DELETE_FILTER", "FILTER", null, deleted, filterDesc, null);
            auditLog(institutionId, userId, "DELETE_BY_FILTER", "tracked_stocks", null,
                Map.of("filter", filterDesc, "deleted", deleted));
            return Map.of("deleted", deleted, "filter", filterDesc,
                "message", deleted + " stock" + (deleted == 1 ? "" : "s") + " removed matching filter.");
        });
    }

    // ── Upload History with batch details ────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getUploadHistory(Long userId, Long institutionId, boolean isAdmin) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT su.upload_id, su.file_name, su.file_type, su.total_rows, su.new_records, " +
                "su.duplicate_records, su.failed_records, su.status, su.upload_date, su.created_at, " +
                "su.batch_id, COALESCE(su.source,'CSV Upload'), su.user_id, COALESCE(su.imported_records,su.new_records,0), " +
                "COALESCE(su.is_deleted,0) " +
                "FROM stock_uploads su WHERE su.institution_id=?");
            List<Object> params = new ArrayList<>();
            params.add(institutionId);
            if (!isAdmin) { sql.append(" AND su.user_id=?"); params.add(userId); }
            sql.append(" ORDER BY su.created_at DESC LIMIT 100");
            var q = em.createNativeQuery(sql.toString());
            for (int i = 0; i < params.size(); i++) q.setParameter(i + 1, params.get(i));
            List<Object[]> rows = q.getResultList();
            return rows.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("upload_id", r[0]); m.put("file_name", r[1]); m.put("file_type", r[2]);
                m.put("total_records", r[3]); m.put("imported", r[13]);
                m.put("duplicates", r[5]); m.put("failed", r[6]);
                m.put("status", r[7]); m.put("upload_date", r[8]);
                m.put("created_at", r[9]); m.put("batch_id", r[10]);
                m.put("source", r[11]); m.put("uploaded_by_id", r[12]);
                m.put("is_deleted", ((Number) r[14]).intValue() == 1);
                return m;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("Upload history: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Upload Management Dashboard Stats ───────────────────────────────────────
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUploadManagementStats(Long userId, Long institutionId, boolean isAdmin) {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            StringBuilder where = new StringBuilder("WHERE institution_id=?");
            List<Object> params = new ArrayList<>();
            params.add(institutionId);
            if (!isAdmin) { where.append(" AND user_id=?"); params.add(userId); }
            String activeWhere = where + " AND is_active=1";
            String deletedWhere = where + " AND COALESCE(is_deleted,0)=1";
            stats.put("total_uploaded", singleCount("tracked_stocks", where + " AND COALESCE(is_deleted,0)=0 AND is_active=1", params));
            stats.put("today_upload", singleCount("tracked_stocks", where + " AND is_active=1 AND upload_date=?", addParam(params, DateUtil.today())));
            stats.put("total_batches", singleCount("stock_uploads", where + " AND COALESCE(is_deleted,0)=0", params));
            stats.put("deleted_stocks", singleCount("tracked_stocks", deletedWhere, params));
            stats.put("active_stocks", singleCount("tracked_stocks", activeWhere, params));
            stats.put("failed_uploads", singleCount("stock_uploads", where + " AND status='failed'", params));
            stats.put("duplicate_total", singleSum("stock_uploads", "duplicate_records", where + " AND COALESCE(is_deleted,0)=0", params));
            try {
                var lq = em.createNativeQuery("SELECT MAX(created_at) FROM stock_uploads " + where + " AND COALESCE(is_deleted,0)=0");
                for (int i = 0; i < params.size(); i++) lq.setParameter(i + 1, params.get(i));
                Object r = lq.getSingleResult();
                stats.put("latest_upload_time", r != null ? r.toString() : "-");
            } catch (Exception e) { stats.put("latest_upload_time", "-"); }
        } catch (Exception e) {
            stats.put("total_uploaded", 0); stats.put("today_upload", 0); stats.put("total_batches", 0);
            stats.put("deleted_stocks", 0); stats.put("active_stocks", 0); stats.put("failed_uploads", 0);
            stats.put("duplicate_total", 0); stats.put("latest_upload_time", "-");
        }
        return stats;
    }

    // ── Deletion Logs ───────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getDeletionLogs(Long institutionId, int limit) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT action, delete_type, user_id, total_records, filter_criteria, reason, created_at, batch_id " +
                "FROM tracker_deletion_logs WHERE institution_id=? ORDER BY created_at DESC LIMIT ?")
                .setParameter(1, institutionId).setParameter(2, limit).getResultList();
            return rows.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("action", r[0]); m.put("delete_type", r[1]); m.put("user_id", r[2]);
                m.put("total_records", r[3]); m.put("filter_criteria", r[4]);
                m.put("reason", r[5]); m.put("created_at", r[6]); m.put("batch_id", r[7]);
                return m;
            }).collect(Collectors.toList());
        } catch (Exception e) { return List.of(); }
    }

    private void logDeletion(Long institutionId, Long userId, String action, String deleteType,
                             String batchId, int totalRecords, String filterCriteria, String reason) {
        try {
            em.createNativeQuery(
                "INSERT INTO tracker_deletion_logs(institution_id,user_id,action,delete_type,batch_id," +
                "total_records,filter_criteria,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?)")
                .setParameter(1, institutionId).setParameter(2, userId).setParameter(3, action)
                .setParameter(4, deleteType).setParameter(5, batchId).setParameter(6, totalRecords)
                .setParameter(7, filterCriteria).setParameter(8, reason).setParameter(9, DateUtil.now())
                .executeUpdate();
        } catch (Exception ignored) {}
    }

    private String generateBatchId(Long institutionId) {
        String dateStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        try {
            Object r = em.createNativeQuery(
                "SELECT COUNT(*) FROM stock_uploads WHERE institution_id=? AND batch_id LIKE ?")
                .setParameter(1, institutionId).setParameter(2, dateStr + "_%").getSingleResult();
            int seq = (r != null ? ((Number) r).intValue() : 0) + 1;
            return dateStr + "_" + String.format("%03d", seq);
        } catch (Exception e) { return dateStr + "_001"; }
    }

    private Object buildCountQuery(String table, String where, List<Object> params) {
        var q = em.createNativeQuery("SELECT COUNT(*) FROM " + table + " " + where);
        for (int i = 0; i < params.size(); i++) q.setParameter(i + 1, params.get(i));
        Object r = q.getSingleResult();
        return r != null ? r : 0;
    }

    private long singleCount(String table, String where, List<Object> params) {
        try {
            var q = em.createNativeQuery("SELECT COUNT(*) FROM " + table + " " + where);
            for (int i = 0; i < params.size(); i++) q.setParameter(i + 1, params.get(i));
            Object r = q.getSingleResult();
            return r != null ? ((Number) r).longValue() : 0;
        } catch (Exception e) { return 0; }
    }

    private long singleSum(String table, String column, String where, List<Object> params) {
        try {
            var q = em.createNativeQuery("SELECT COALESCE(SUM(" + column + "),0) FROM " + table + " " + where);
            for (int i = 0; i < params.size(); i++) q.setParameter(i + 1, params.get(i));
            Object r = q.getSingleResult();
            return r != null ? ((Number) r).longValue() : 0;
        } catch (Exception e) { return 0; }
    }

    private List<Object> addParam(List<Object> params, Object value) {
        List<Object> result = new ArrayList<>(params);
        result.add(value);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getAllTrackedStocks(Long institutionId,
            String sector, String mcap, Double minReturn, Double maxReturn,
            String sortBy, String sortDir, int page, int size, boolean includeSignals) {
        return getAllTrackedStocksScoped(null, institutionId, sector, mcap, minReturn, maxReturn, sortBy, sortDir, page, size, includeSignals);
    }

    @SuppressWarnings("unchecked")
    // Flag set by getUploadedStocksLite() so getAllTrackedStocksScoped() returns
    // stored values only (no live-quote / daily-price / subscription enrichment).
    private final ThreadLocal<Boolean> liteViewFlag = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Fast, DB-only listing of uploaded stocks: reuses the same filter/sort/
     * pagination query as the full view but skips all live enrichment. Use for
     * instant display and pagination; a later refresh updates the stored values.
     */
    public List<Map<String, Object>> getUploadedStocksLite(Long userId, Long institutionId,
            String sector, String mcap, Double minReturn, Double maxReturn,
            String sortBy, String sortDir, int page, int size) {
        liteViewFlag.set(Boolean.TRUE);
        try {
            return getAllTrackedStocksScoped(userId, institutionId, sector, mcap, minReturn, maxReturn, sortBy, sortDir, page, size, false);
        } finally {
            liteViewFlag.remove();
        }
    }

    private List<Map<String, Object>> buildLiteUploadedRows(List<Object[]> rows) {
        // The lite view deliberately skips live enrichment, but Last Close and Volume need the
        // most recent stored daily candle. This page is paginated (tens of symbols, not the whole
        // universe) and loadLatestDailyPrices() is a per-symbol index seek, so the cost is small.
        List<String> liteSymbols = rows.stream().map(r -> String.valueOf(r[0])).toList();
        Map<String, DailyTrackerPrice> liteDaily = liteSymbols.isEmpty()
            ? Map.of()
            : loadLatestDailyPrices(liteSymbols, loadDhanAliases(liteSymbols));

        List<Map<String, Object>> lite = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            double base = getNumber(r[4]);
            double cur = getNumber(r[5]);
            DailyTrackerPrice dp = liteDaily.get(normalizeSymbol(String.valueOf(r[0])));
            m.put("close_price", dp != null && dp.close > 0 ? round2(dp.close) : null);
            m.put("close_date", dp != null ? normalizeStoredDate(dp.tradeDate) : null);
            m.put("volume", dp != null && dp.volume > 0 ? dp.volume : null);
            m.put("symbol", String.valueOf(r[0]));
            m.put("sector", r[1]);
            m.put("market_cap_name", r[2]);
            m.put("mcap", r[2]);
            m.put("upload_date", r[3]);
            m.put("base_price", base > 0 ? round2(base) : null);
            m.put("current_price", cur > 0 ? round2(cur) : null);
            m.put("return_pct", round2(getNumber(r[6])));

            // Previous Close, under the name the screen actually reads.
            //
            // The close was returned as "close_price" while the table binds "previous_close", so
            // that column rendered "-" on every row and the Today % beside it - which only shows
            // when previous_close is set - stayed blank with it. The value was in the payload the
            // whole time under a different name.
            //
            // Only offered when the close is from an EARLIER session than the current price.
            // Once the day's own daily bar lands, close_price IS today's close, and calling that
            // "previous" would compare the price with itself and report a flat 0.00%.
            String curDate = r[11] == null ? null : String.valueOf(r[11]);
            String closeDate = dp != null ? normalizeStoredDate(dp.tradeDate) : null;
            Double prevClose = (dp != null && dp.close > 0 && closeDate != null && curDate != null
                                && closeDate.compareTo(curDate) < 0) ? dp.close : null;
            m.put("previous_close", prevClose == null ? null : round2(prevClose));
            m.put("today_return_pct", prevClose != null && prevClose > 0 && cur > 0
                ? round2(((cur - prevClose) / prevClose) * 100.0) : null);
            m.put("last_refreshed", r[7]);
            m.put("is_active", r[8]);
            m.put("base_price_source", r[9]);
            m.put("current_price_source", r[10] != null ? String.valueOf(r[10]) : "STORED");
            m.put("price_source", r[10] != null ? String.valueOf(r[10]) : "STORED");
            m.put("current_price_date", r[11]);
            m.put("price_date", r[11]);
            m.put("lite", Boolean.TRUE);
            lite.add(m);
        }
        return lite;
    }

    /**
     * When set, the tracked-stock query adds "current_price >= base_price" itself.
     *
     * <p>This exists for speed, and the numbers are stark. That query is followed by per-symbol
     * price enrichment costing roughly 27 ms a row, so the row count decides the wall time. While
     * the ranking still carried a return band, the band narrowed 1,218 stocks to ~136 inside the
     * database and the stage took 3.5 s. Removing the band from identification removed that
     * narrowing too, all 1,218 rows were enriched, and the stage went to 34 s.
     *
     * <p>Pushing the price condition into SQL restores the narrowing using the very test that
     * identification performs first anyway - so the work is not merely faster, it is done in the
     * right place. A thread-local rather than a parameter because the method has many callers and
     * only this path wants it.
     */
    private final ThreadLocal<Boolean> currentAboveBaseOnly = ThreadLocal.withInitial(() -> false);

    private List<Map<String, Object>> getAllTrackedStocksScoped(Long userId, Long institutionId,
            String sector, String mcap, Double minReturn, Double maxReturn,
            String sortBy, String sortDir, int page, int size, boolean includeSignals) {
        if (isSignalSortColumn(sortBy)) includeSignals = true;
        boolean marketOpen = isMarketOpen();
        StringBuilder q = new StringBuilder(
            "SELECT ts.symbol,ts.sector,ts.market_cap_name,ts.upload_date,ts.base_price," +
            "ts.current_price,ts.return_pct,ts.last_refreshed,ts.is_active,ts.base_price_source,ts.current_price_source,ts.current_price_date " +
            "FROM tracked_stocks ts " +
            "WHERE ts.institution_id=? AND ts.is_active=1");

        List<Object> params = new ArrayList<>();
        params.add(institutionId);
        if (userId != null) {
            q.append(" AND ts.user_id=?");
            params.add(userId);
        }

        if (sector != null && !sector.isBlank() && !"ALL".equals(sector)) {
            q.append(" AND ts.sector=?"); params.add(sector);
        }
        if (mcap != null && !mcap.isBlank() && !"ALL".equals(mcap)) {
            q.append(" AND ts.market_cap_name LIKE ?"); params.add("%" + mcap + "%");
        }
        if (minReturn != null) { q.append(" AND ts.return_pct >= ?"); params.add(minReturn); }
        if (maxReturn != null) { q.append(" AND ts.return_pct <= ?"); params.add(maxReturn); }
        if (Boolean.TRUE.equals(currentAboveBaseOnly.get())) {
            // Identification step one, evaluated where the rows are rather than after loading them.
            q.append(" AND ts.base_price > 0 AND ts.current_price > 0 AND ts.current_price >= ts.base_price");
        }

        boolean sqlPaginate = size < 50000;
        String sqlSortCol = sqlPaginate ? mapSortToColumn(sortBy) : null;
        if (sqlSortCol != null) {
            q.append(" ORDER BY ").append(sqlSortCol).append("asc".equals(sortDir) ? " ASC" : " DESC").append(" NULLS LAST");
            q.append(" LIMIT ? OFFSET ?");
            params.add(size);
            params.add(page * size);
        } else {
            q.append(" ORDER BY ts.symbol ASC");
        }

        var query = em.createNativeQuery(q.toString());
        for (int i = 0; i < params.size(); i++) query.setParameter(i+1, params.get(i));

        List<Object[]> rows = query.getResultList();
        // Lite/fast view: return the values already stored in tracked_stocks
        // (from the last refresh) with NO live enrichment, so page loads and
        // pagination are instant and never block the UI.
        if (Boolean.TRUE.equals(liteViewFlag.get())) {
            return buildLiteUploadedRows(rows);
        }
        List<String> symbols = rows.stream().map(r -> String.valueOf(r[0])).toList();
        Map<String, Set<String>> aliasesBySymbol = loadDhanAliases(symbols);
        Map<String, LiveTrackerQuote> liveQuotes = loadLatestLiveQuotes(symbols, aliasesBySymbol);
        Map<String, DailyTrackerPrice> dailyPrices = loadLatestDailyPrices(symbols, aliasesBySymbol);
        Map<String, SubscriptionStatus> subscriptionStatuses = loadDhanSubscriptionStatuses(symbols);
        boolean dhanVerified = hasVerifiedDhanCredential(userId, institutionId);
        final boolean includeSignalDetails = includeSignals;
        final Long scopedInstitutionId = institutionId;

        List<Map<String, Object>> result = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String symbol = String.valueOf(r[0]);
            String normalizedSymbol = normalizeSymbol(symbol);
            LiveTrackerQuote live = liveQuotes.get(normalizeSymbol(symbol));
            DailyTrackerPrice daily = dailyPrices.get(normalizeSymbol(symbol));
            SubscriptionStatus subscriptionStatus = subscriptionStatuses.get(normalizedSymbol);
            double storedCurrent = getNumber(r[5]);
            double basePrice = getNumber(r[4]);
            String storedCurrentDate = normalizeStoredDate(asText(r[11]));
            String storedRefreshDate = normalizeStoredDate(asText(r[7]));
            String storedPriceDate = latestText(storedCurrentDate, storedRefreshDate);
            String livePriceDate = live == null ? "" : normalizeStoredDate(latestText(latestText(live.tickReceivedAt, live.lastTradeTime), live.updatedAt));
            String dailyPriceDate = daily == null ? "" : normalizeStoredDate(daily.tradeDate);
            boolean freshLive = marketOpen && hasTodayDate(livePriceDate);
            boolean hasLiveClose = live != null && live.close > 0;
            boolean hasLiveLtp = live != null && live.currentPrice > 0;
            Double currentPrice = null;
            String selectedPriceSource = "";
            String selectedPriceType = "";
            String selectedPriceDate = "";
            Object selectedLastRefreshed = r[7];
            if (storedCurrent > 0) {
                currentPrice = storedCurrent;
                selectedPriceSource = r[10] != null ? String.valueOf(r[10]) : "STORED";
                selectedPriceType = "Stored";
                selectedPriceDate = storedPriceDate;
                selectedLastRefreshed = !storedPriceDate.isBlank() ? storedPriceDate : r[7];
            }
            if (daily != null && daily.close > 0 && shouldUseNewerPrice(dailyPriceDate, selectedPriceDate)) {
                currentPrice = daily.close;
                selectedPriceSource = "LAST_MARKET_CLOSE";
                selectedPriceType = "Closing Price";
                selectedPriceDate = dailyPriceDate;
                selectedLastRefreshed = daily.tradeDate;
            }
            if (hasLiveClose && shouldUseNewerPrice(livePriceDate, selectedPriceDate)) {
                currentPrice = live.close;
                selectedPriceSource = "DHAN_CLOSE";
                selectedPriceType = "LTP";
                selectedPriceDate = livePriceDate;
                selectedLastRefreshed = latestText(latestText(live.tickReceivedAt, live.lastTradeTime), live.updatedAt);
            }
            if (hasLiveLtp && (freshLive || shouldUseNewerPrice(livePriceDate, selectedPriceDate))) {
                currentPrice = live.currentPrice;
                selectedPriceSource = "DHAN_LTP";
                selectedPriceType = "LTP";
                selectedPriceDate = livePriceDate;
                selectedLastRefreshed = latestText(latestText(live.tickReceivedAt, live.lastTradeTime), live.updatedAt);
            }
            double returnPct = basePrice > 0 && currentPrice != null
                ? ((currentPrice - basePrice) / basePrice) * 100.0
                : getNumber(r[6]);
            Double closePrice = null;
            Double openPrice = null;
            Double highPrice = null;
            Double lowPrice = null;
            Double volume = null;
            if (!marketOpen && daily != null) closePrice = daily.close;
            else if (hasLiveClose) closePrice = live.close;
            else if (daily != null) closePrice = daily.close;
            if (freshLive && live != null && live.open > 0) openPrice = live.open;
            else if (daily != null) openPrice = daily.open;
            else if (live != null) openPrice = live.open;
            if (freshLive && live != null && live.high > 0) highPrice = live.high;
            else if (daily != null) highPrice = daily.high;
            else if (live != null) highPrice = live.high;
            if (freshLive && live != null && live.low > 0) lowPrice = live.low;
            else if (daily != null) lowPrice = daily.low;
            else if (live != null) lowPrice = live.low;
            if (freshLive && live != null && live.volume > 0) volume = live.volume;
            else if (daily != null) volume = daily.volume;
            else if (live != null) volume = live.volume;
            m.put("symbol",       r[0]); m.put("sector",        r[1]);
            m.put("market_cap",   r[2]); m.put("upload_date",   r[3]);
            m.put("base_price",   r[4]); m.put("current_price", currentPrice);
            m.put("return_pct",   Math.round(returnPct * 100) / 100.0);
            m.put("last_refreshed", selectedLastRefreshed);
            m.put("is_active",    r[8]);
            m.put("base_price_source", r[9]);
            m.put("base_price_error", r[9] != null && String.valueOf(r[9]).startsWith("DHAN_ERROR") ? r[9] : null);
            m.put("close_price", closePrice);
            m.put("open_price", openPrice);
            m.put("high_price", highPrice);
            m.put("low_price", lowPrice);
            m.put("volume", volume);
            m.put("last_trade_time", live != null ? live.lastTradeTime : null);
            String priceSource = selectedPriceSource;
            m.put("current_price_source", priceSource);
            m.put("market_price_date", daily != null ? daily.tradeDate : null);
            m.put("close_date", daily != null ? normalizeStoredDate(daily.tradeDate) : null);
            m.put("market_status", marketOpen ? "Live" : "Closed");
            // "Last Update" describes the Current Price on this row: when that price was
            // last valid, not when we polled for it. In session it is the live tick. Once
            // the market shuts, the current price IS the last close, so report that close
            // - opening the app on a Sunday shows Friday 15:30, not the Sunday fetch time.
            // Out of session, prefer the session the displayed price actually came from over the
            // calendar. lastSessionCloseIST() is pure arithmetic - most recent weekday at 15:30 -
            // so while prices were frozen at the 2026-11-01 fetch and the newest stored daily bar
            // was 2026-10-30, this column still read "10-Nov 15:30" and presented a nine-day-old
            // price as current. The daily bar's own date is the honest answer: a close price IS
            // that session's 15:30 price, and an LTP fetched when the market is shut is the same
            // close. Only when there is no stored bar at all does the calendar stand in.
            String closedUpdate = lastSessionCloseIST();
            if (daily != null && daily.tradeDate != null) {
                String session = normalizeStoredDate(daily.tradeDate);
                if (session != null && !session.isBlank()) closedUpdate = session + " 15:30:00";
            }
            String lastUpdate = marketOpen
                ? (live != null && live.lastTradeTime != null
                    ? String.valueOf(live.lastTradeTime) : DateUtil.now())
                : closedUpdate;
            m.put("last_update", lastUpdate);
            m.put("price_type", selectedPriceType.isBlank() ? "Stored" : selectedPriceType);
            if (normalizedSymbol.isBlank()) {
                m.put("price_status", "Symbol Missing");
                m.put("price_status_reason", "Uploaded stock row does not have a symbol.");
            } else if (subscriptionStatus != null && "MAPPING_FAILED".equalsIgnoreCase(subscriptionStatus.status())) {
                m.put("price_status", "Dhan Mapping Missing");
                m.put("price_status_reason", subscriptionStatus.reason());
            } else if (!dhanVerified && isDhanPriceSource(priceSource)) {
                m.put("price_status", "Dhan Token Invalid");
                m.put("price_status_reason", "Dhan rejected the saved broker token. Reconnect Dhan, then refresh prices.");
            } else if (currentPrice == null || currentPrice <= 0) {
                m.put("price_status", "LTP Not Updated");
                m.put("price_status_reason", "No Dhan LTP or last market close is available for this symbol.");
            } else {
                m.put("price_status", "OK");
                m.put("price_status_reason", "Price mapped by symbol.");
            }
            double signalPrice = currentPrice == null ? 0 : currentPrice;
            if (includeSignalDetails) m.putAll(buildUploadedStockSignals(symbol, signalPrice, scopedInstitutionId));
            else m.putAll(defaultUploadedStockSignals());
            return m;
        }).collect(Collectors.toList());

        if (sqlSortCol != null) {
            return result;
        }
        Comparator<Map<String, Object>> comparator = uploadedStockComparator(sortBy);
        if (!"asc".equals(sortDir)) comparator = comparator.reversed();
        result = result.stream().sorted(comparator).collect(Collectors.toList());
        int from = Math.min(Math.max(0, page * size), result.size());
        int to = Math.min(from + size, result.size());
        return result.subList(from, to);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String[]> loadTradingSymbolAndName(List<String> symbols) {
        Map<String, String[]> result = new HashMap<>();
        List<String> normalized = symbols.stream().map(this::normalizeSymbol).filter(s -> !s.isBlank()).distinct().toList();
        if (normalized.isEmpty()) return result;
        try {
            String in = placeholders(normalized.size());
            var q = em.createNativeQuery(
                "SELECT DISTINCT ON (" + normalizedSql("underlying_symbol") + ") " +
                "underlying_symbol, trading_symbol, COALESCE(display_name, symbol_name, '') " +
                "FROM dhan_instruments WHERE " + normalizedSql("underlying_symbol") + " IN (" + in + ") " +
                "AND (exchange_segment IS NULL OR UPPER(exchange_segment) IN ('NSE','NSE_EQ')) " +
                "ORDER BY " + normalizedSql("underlying_symbol") + ", id DESC");
            for (int i = 0; i < normalized.size(); i++) q.setParameter(i + 1, normalized.get(i));
            for (Object[] r : (List<Object[]>) q.getResultList()) {
                String sym = normalizeSymbol(asText(r[0]));
                result.put(sym, new String[]{asText(r[1]), asText(r[2])});
            }
        } catch (Exception e) { log.debug("Trading symbol lookup skipped: {}", e.getMessage()); }
        for (String sym : normalized) {
            if (result.containsKey(sym)) continue;
            try {
                Object name = em.createNativeQuery(
                    "SELECT company_name FROM stock_master WHERE " + normalizedSql("symbol") + "=? AND company_name IS NOT NULL LIMIT 1")
                    .setParameter(1, sym).getSingleResult();
                result.put(sym, new String[]{sym, asText(name)});
            } catch (Exception ignored) {}
        }
        return result;
    }

    private Map<String, Set<String>> loadDhanAliases(List<String> symbols) {
        Map<String, Set<String>> aliasesBySymbol = new HashMap<>();
        List<String> normalizedSymbols = symbols.stream()
            .map(this::normalizeSymbol)
            .filter(s -> !s.isBlank())
            .distinct()
            .toList();
        Map<String, String> aliasToTracked = aliasToTrackedSymbol(normalizedSymbols);
        for (String symbol : normalizedSymbols) {
            Set<String> aliases = aliasesBySymbol.computeIfAbsent(symbol, k -> new LinkedHashSet<>());
            aliases.add(symbol);
            aliasToTracked.entrySet().stream()
                .filter(e -> symbol.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .forEach(aliases::add);
        }
        if (normalizedSymbols.isEmpty()) return aliasesBySymbol;
        try {
            List<String> lookupAliases = new ArrayList<>(aliasToTracked.keySet());
            String sql = "SELECT underlying_symbol,trading_symbol,symbol_name,display_name FROM dhan_instruments " +
                "WHERE " + normalizedSql("underlying_symbol") + " IN (" + placeholders(lookupAliases.size()) + ") " +
                "AND (exchange_segment IS NULL OR UPPER(exchange_segment) IN ('NSE','NSE_EQ'))";
            var query = em.createNativeQuery(sql);
            for (int i = 0; i < lookupAliases.size(); i++) query.setParameter(i + 1, lookupAliases.get(i));
            for (Object[] r : (List<Object[]>) query.getResultList()) {
                String underlying = normalizeSymbol(asText(r[0]));
                if (underlying.isBlank()) continue;
                String tracked = aliasToTracked.getOrDefault(underlying, underlying);
                Set<String> aliases = aliasesBySymbol.computeIfAbsent(tracked, k -> new LinkedHashSet<>());
                addAlias(aliases, r[0]);
                addAlias(aliases, r[1]);
                addAlias(aliases, r[2]);
                addAlias(aliases, r[3]);
            }
        } catch (Exception e) {
            log.debug("Dhan alias lookup skipped: {}", e.getMessage());
        }
        return aliasesBySymbol;
    }

    @SuppressWarnings("unchecked")
    private Map<String, LiveTrackerQuote> loadLatestLiveQuotes(List<String> symbols, Map<String, Set<String>> aliasesBySymbol) {
        Map<String, String> aliasToSymbol = aliasToSymbol(aliasesBySymbol);
        Map<String, LiveTrackerQuote> quotes = new HashMap<>();
        if (aliasToSymbol.isEmpty()) return quotes;
        Set<String> aliases = aliasToSymbol.keySet();
        List<String> aliasList = new ArrayList<>(aliases);
        String symCol = normalizedSql("symbol");
        int batchSize = 900;
        for (int start = 0; start < aliasList.size(); start += batchSize) {
            List<String> batch = aliasList.subList(start, Math.min(start + batchSize, aliasList.size()));
            try {
                var q = em.createNativeQuery(
                    "SELECT DISTINCT ON (" + symCol + ") symbol,current_price,bid_price,ask_price," +
                    "open_price,high_price,low_price,close_price,volume,last_trade_time,tick_received_at,updated_at " +
                    "FROM live_price_quotes WHERE broker_name='dhan' AND " +
                    symCol + " IN (" + placeholders(batch.size()) + ") " +
                    "ORDER BY " + symCol + ", updated_at DESC NULLS LAST");
                for (int i = 0; i < batch.size(); i++) q.setParameter(i + 1, batch.get(i));
                for (Object[] r : (List<Object[]>) q.getResultList()) {
                    String tracked = aliasToSymbol.get(normalizeSymbol(asText(r[0])));
                    if (tracked == null || quotes.containsKey(tracked)) continue;
                    quotes.put(tracked, new LiveTrackerQuote(
                        getNumber(r[1]), getNumber(r[2]), getNumber(r[3]), getNumber(r[4]),
                        getNumber(r[5]), getNumber(r[6]), getNumber(r[7]), getNumber(r[8]),
                        asText(r[9]), asText(r[10]), asText(r[11])));
                }
            } catch (Exception e) {
                log.debug("Live quote lookup skipped: {}", e.getMessage());
            }
        }
        return quotes;
    }

    /**
     * Latest stored daily candle per tracked symbol.
     *
     * <p>Each source is queried as a per-symbol LATERAL seek rather than
     * {@code DISTINCT ON (...) WHERE normalized_symbol IN (900 values)}. The IN-list form
     * matched ~31% of broker_market_candles (2,928 symbols x ~9,450 rows), so PostgreSQL
     * correctly chose a sequential scan of a 27M-row table and no index could help — that
     * was the bulk of the ~200-second ranking stage. Driving the query from the symbol list
     * instead turns each lookup into a one-row index scan on idx_bmc_norm_date /
     * idx_md_norm_date (see SymbolSchemaGuard), which measured 10,115 ms -> 526 ms for the
     * daily-candle source with identical results.
     *
     * <p>Placeholders sit inside {@code unnest(ARRAY[...])} so the existing positional
     * binding in {@link #mergeDailyPrices} still applies unchanged.
     */
    private Map<String, DailyTrackerPrice> loadLatestDailyPrices(List<String> symbols, Map<String, Set<String>> aliasesBySymbol) {
        Map<String, String> aliasToSymbol = aliasToSymbol(aliasesBySymbol);
        Map<String, DailyTrackerPrice> prices = new HashMap<>();
        if (aliasToSymbol.isEmpty()) return prices;
        Set<String> aliases = aliasToSymbol.keySet();
        String lateralFrom = "FROM unnest(ARRAY[%s]::text[]) AS k(sym) CROSS JOIN LATERAL (";

        mergeDailyPrices(prices, aliasToSymbol, aliases,
            "SELECT k.sym,x.trade_date,x.open,x.high,x.low,x.close,x.volume " + lateralFrom +
            "SELECT b.trade_date,b.open,b.high,b.low,b.close,COALESCE(b.total_volume,b.volume) AS volume " +
            "FROM broker_market_candles b WHERE " + normalizedSql("b.symbol") + "=k.sym " +
            // Predicate written to MATCH idx_bmc_norm_daily_date exactly. Written as
            // "(candle_mode IS NULL OR UPPER(candle_mode)='DAILY')" the planner cannot prove the
            // partial index covers the query, so it fell back to the all-rows index and walked
            // backwards through intraday bars - 72 of them for every daily bar - to find one match.
            // Every row has a candle_mode, so dropping the IS NULL arm loses nothing and lets the
            // partial index be used: 292 ms -> 17 ms across 800 symbols.
            "AND UPPER(COALESCE(b.candle_mode,''))='DAILY' " +
            "ORDER BY b.trade_date DESC LIMIT 1) x");

        // Intraday fallback: aggregate that symbol's most recent intraday day into one bar.
        //
        // The date is pinned with a MAX() subquery BEFORE aggregating, rather than grouping every
        // day and taking the newest. Grouping first made this read every intraday row the symbol
        // has ever had - 19,601 rows for CDSL - and sort the lot to keep one, about 35 ms a symbol
        // and several seconds across a ranking run. Pinning the date first lets the same index
        // answer the MAX and leaves roughly 75 rows to aggregate.
        mergeDailyPrices(prices, aliasToSymbol, aliases,
            "SELECT k.sym,x.trade_date,x.open,x.high,x.low,x.close,x.volume " + lateralFrom +
            "SELECT b.trade_date," +
            "(ARRAY_AGG(b.open ORDER BY b.candle_time ASC))[1] AS open," +
            "MAX(b.high) AS high,MIN(b.low) AS low," +
            "(ARRAY_AGG(b.close ORDER BY b.candle_time DESC))[1] AS close," +
            "SUM(COALESCE(b.total_volume,b.volume)) AS volume " +
            "FROM broker_market_candles b WHERE " + normalizedSql("b.symbol") + "=k.sym " +
            "AND (b.exchange_segment IS NULL OR UPPER(b.exchange_segment) IN ('NSE','NSE_EQ')) " +
            "AND UPPER(COALESCE(b.candle_mode,''))='INTRADAY' AND b.close IS NOT NULL AND b.close>0 " +
            "AND b.trade_date = (SELECT MAX(b2.trade_date) FROM broker_market_candles b2 " +
            "  WHERE " + normalizedSql("b2.symbol") + "=k.sym " +
            "  AND UPPER(COALESCE(b2.candle_mode,''))='INTRADAY' " +
            "  AND b2.close IS NOT NULL AND b2.close>0) " +
            "GROUP BY b.trade_date ORDER BY b.trade_date DESC LIMIT 1) x");
        return prices;
    }

    @SuppressWarnings("unchecked")
    private Map<String, SubscriptionStatus> loadDhanSubscriptionStatuses(List<String> symbols) {
        List<String> normalizedSymbols = symbols.stream()
            .map(this::normalizeSymbol)
            .filter(s -> !s.isBlank())
            .distinct()
            .toList();
        if (normalizedSymbols.isEmpty()) return Map.of();
        try {
            var query = em.createNativeQuery(
                "SELECT symbol,status,reason FROM live_price_subscriptions " +
                "WHERE broker_name='dhan' AND " + normalizedSql("symbol") + " IN (" + placeholders(normalizedSymbols.size()) + ")");
            for (int i = 0; i < normalizedSymbols.size(); i++) query.setParameter(i + 1, normalizedSymbols.get(i));
            Map<String, SubscriptionStatus> result = new HashMap<>();
            for (Object[] row : (List<Object[]>) query.getResultList()) {
                String symbol = normalizeSymbol(asText(row[0]));
                if (symbol.isBlank()) continue;
                result.put(symbol, new SubscriptionStatus(asText(row[1]), asText(row[2])));
            }
            return result;
        } catch (Exception e) {
            log.debug("Dhan subscription status lookup skipped: {}", e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeDailyPrices(Map<String, DailyTrackerPrice> prices, Map<String, String> aliasToSymbol,
                                  Set<String> aliases, String sqlTemplate) {
        if (aliases.isEmpty()) return;
        List<String> aliasList = new ArrayList<>(aliases);
        int batchSize = 900;
        for (int start = 0; start < aliasList.size(); start += batchSize) {
            List<String> batch = aliasList.subList(start, Math.min(start + batchSize, aliasList.size()));
            try {
                var query = em.createNativeQuery(String.format(sqlTemplate, placeholders(batch.size())));
                for (int i = 0; i < batch.size(); i++) query.setParameter(i + 1, batch.get(i));
                for (Object[] r : (List<Object[]>) query.getResultList()) {
                    String tracked = aliasToSymbol.get(normalizeSymbol(asText(r[0])));
                    if (tracked == null) continue;
                    DailyTrackerPrice candidate = new DailyTrackerPrice(asText(r[1]), getNumber(r[2]), getNumber(r[3]),
                        getNumber(r[4]), getNumber(r[5]), getNumber(r[6]));
                    DailyTrackerPrice existing = prices.get(tracked);
                    if (existing != null
                        && candidate.tradeDate.compareTo(existing.tradeDate) <= 0) continue;

                    // Newer is not automatically better.
                    //
                    // Two lookups feed this map: the stored daily bar, and a fallback that folds
                    // intraday rows into one bar per day. Daily bars currently stop at 27 Aug for
                    // most stocks while intraday reaches 28 Aug, so the fallback is always the
                    // newer of the two and used to win outright. The 28 Aug intraday rows exist
                    // but carry no volume at all - 66 bars for 360ONE summing to zero - so a
                    // complete 27 Aug bar showing 317,442 was being replaced by an empty one, and
                    // the Volume column on Uploaded Stock Details rendered "-" for every row.
                    //
                    // A bar with no price is not a bar. A bar with a price but no volume is
                    // usable for price and useless for volume, so it may only take over from a
                    // bar that had no volume either.
                    if (candidate.close <= 0) continue;
                    if (existing != null && candidate.volume <= 0 && existing.volume > 0) continue;

                    prices.put(tracked, candidate);
                }
            } catch (Exception e) {
                log.debug("Daily price lookup skipped: {}", e.getMessage());
            }
        }
    }

    private Map<String, String> aliasToSymbol(Map<String, Set<String>> aliasesBySymbol) {
        Map<String, String> aliasToSymbol = new HashMap<>();
        aliasesBySymbol.forEach((symbol, aliases) -> aliases.forEach(alias -> {
            String normalized = normalizeSymbol(alias);
            if (!normalized.isBlank()) aliasToSymbol.putIfAbsent(normalized, symbol);
        }));
        return aliasToSymbol;
    }

    private void addAlias(Set<String> aliases, Object value) {
        String alias = normalizeSymbol(asText(value));
        if (!alias.isBlank()) aliases.add(alias);
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String latestText(Object first, Object second) {
        String a = asText(first);
        String b = asText(second);
        if (a.isBlank()) return b;
        if (b.isBlank()) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }

    private boolean hasTodayDate(String normalizedDate) {
        return DateUtil.today().equals(normalizedDate == null ? "" : normalizedDate.trim());
    }

    private boolean shouldUseNewerPrice(String candidateDate, String selectedDate) {
        String candidate = candidateDate == null ? "" : candidateDate.trim();
        String selected = selectedDate == null ? "" : selectedDate.trim();
        if (selected.isBlank()) return true;
        if (candidate.isBlank()) return false;
        return candidate.compareTo(selected) >= 0;
    }

    private java.time.LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim().replace('T', ' ');
        if (text.endsWith("Z")) text = text.substring(0, text.length() - 1);
        if (text.length() > 19) text = text.substring(0, 19);
        try { return java.time.LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
        catch (Exception ignored) {}
        try { return java.time.LocalDate.parse(normalizeStoredDate(text)).atStartOfDay(); }
        catch (Exception ignored) {}
        return null;
    }

    private String placeholders(int count) {
        return IntStream.range(0, count).mapToObj(i -> "?").collect(Collectors.joining(","));
    }

    private String normalizedSql(String column) {
        return "UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(COALESCE(" + column + ",'')),'.NS',''),'.BO',''),'-EQ',''),'-BE',''),'-BZ',''),'-SM',''),'-ST',''),' ',''))";
    }

    private record LiveTrackerQuote(double currentPrice, double bid, double ask, double open, double high, double low,
                                    double close, double volume, String lastTradeTime, String tickReceivedAt, String updatedAt) {}

    private record DailyTrackerPrice(String tradeDate, double open, double high, double low, double close, double volume) {}

    private record SavedBasePrice(double close, String tradeDate, String source) {}

    private String mapSortToColumn(String sortBy) {
        if (sortBy == null) return "ts.symbol";
        return switch (sortBy) {
            case "symbol"        -> "ts.symbol";
            case "sector"        -> "ts.sector";
            case "market_cap"    -> "ts.market_cap_name";
            case "base_price"    -> "ts.base_price";
            case "upload_date"   -> "ts.upload_date";
            case "last_refreshed"-> "ts.last_refreshed";
            default -> null;
        };
    }

    private boolean isSignalSortColumn(String sortBy) {
        if (sortBy == null) return false;
        return switch (sortBy) {
            case "buy_sell_signal", "trend", "today_volume_change_pct",
                 "below_52wh_pct", "above_52wl_pct", "pe_ratio" -> true;
            default -> false;
        };
    }

    private Comparator<Map<String, Object>> uploadedStockComparator(String sortBy) {
        String field = sortBy == null ? "symbol" : sortBy;
        return switch (field) {
            case "return_pct", "base_price", "current_price",
                 "today_volume_change_pct", "below_52wh_pct", "above_52wl_pct", "pe_ratio"
                 -> Comparator.comparingDouble(s -> getD(s, field));
            case "upload_date" -> Comparator.comparing(s -> String.valueOf(s.getOrDefault("upload_date", "")));
            case "sector" -> Comparator.comparing(s -> String.valueOf(s.getOrDefault("sector", "")));
            case "market_cap" -> Comparator.comparing(s -> String.valueOf(s.getOrDefault("market_cap", "")));
            case "buy_sell_signal" -> Comparator.comparing(s -> String.valueOf(s.getOrDefault("buy_sell_signal", "")));
            case "trend" -> Comparator.comparing(s -> String.valueOf(s.getOrDefault("trend", "")));
            case "last_refreshed" -> Comparator.comparing(s -> String.valueOf(s.getOrDefault("last_refreshed", "")));
            default -> Comparator.comparing(s -> String.valueOf(s.getOrDefault("symbol", "")));
        };
    }

    private Map<String, Object> defaultUploadedStockSignals() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buy_sell_signal", "DATA REQUIRED");
        result.put("trend", "Data Required");
        result.put("trend_score", 0);
        result.put("price_change_pct", null);
        result.put("today_volume_change_pct", null);
        result.put("yesterday_volume_change_pct", null);
        result.put("today_volume_signal", 0);
        result.put("yesterday_volume_signal", 0);
        result.put("avg20_volume_signal", 0);
        result.put("weekly_volume_signal", 0);
        result.put("avg20_volume", null);
        result.put("weekly_volume_trend", null);
        result.put("signal_message", "Signal calculation is available in ranking/analysis views; stock list is optimized for live price loading.");
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getEligibleStocks(Long institutionId) {
        return getEligibleStocks(null, institutionId);
    }

    @SuppressWarnings("unchecked")
    /**
     * Eligible Stocks (the 0-5% download filter): price crossing above base price,
     * return within the configured band, volume above the 20-day average.
     */
    public List<Map<String, Object>> getEligibleStocks(Long userId, Long institutionId) {
        return selectTrackedCandidates(userId, institutionId, true);
    }

    /**
     * Ranking universe behind Today Top 10: identification runs before any scoring.
     *
     * <p>Entry test, exactly two conditions:
     * Current Price &gt;= Base Price AND Current Volume &gt; 20-Day Average Volume.
     * Only what survives is ranked - the universe is never scored first and filtered after, which
     * would let an ineligible stock take a slot an eligible one earned.
     *
     * <p>This section deliberately does NOT require the crossing. That belongs to the crossing-entry
     * panel, which is a different strategy answering a different question: this one ranks everything
     * currently holding above its base price on strong volume, whichever day it first got there,
     * while that one admits only stocks crossing base today. Applying the crossing here cut the pool
     * from 334 candidates to 5.
     *
     * <p>The return band is not applied either. It belongs to the Eligible Stocks download, which
     * declares it in its own header, and is not one of the two conditions above.
     */
    public List<Map<String, Object>> getRankingCandidates(Long userId, Long institutionId) {
        return selectTrackedCandidates(userId, institutionId, false, false);
    }

    /**
     * @param requireCrossing when true the previous close must sit below the base price,
     *        i.e. the stock is crossing up through it. Current Price &gt;= Base Price,
     *        the return band and the volume test always apply.
     */
    private List<Map<String, Object>> selectTrackedCandidates(Long userId, Long institutionId,
                                                             boolean requireCrossing) {
        return selectTrackedCandidates(userId, institutionId, requireCrossing, true);
    }

    /**
     * @param applyReturnBand whether the configured return band forms part of the entry test. The
     *        Eligible Stocks download keeps it, because that report declares it. Today Top 10 does
     *        not: its identification is the three price and volume conditions alone.
     */
    private List<Map<String, Object>> selectTrackedCandidates(Long userId, Long institutionId,
                                                             boolean requireCrossing,
                                                             boolean applyReturnBand) {
        // Shared result, computed once.
        //
        // Three panels on the tracker page ask this same question on load, and the logs caught two
        // of them running it concurrently to the millisecond - the same 794 candidates, the same
        // 6.6-second candle batch, twice over. Leaving the section and returning re-ran the lot
        // again, which is exactly the "takes just as long the second time" complaint: nothing was
        // being reused, so every visit paid full price.
        //
        // A short entry is enough. Callers get their own copy, so a caller that sorts or annotates
        // its list cannot disturb another's; the maps are copied too, because the ranking stage
        // writes scores into them.
        String key = userId + "|" + institutionId + "|" + requireCrossing + "|" + applyReturnBand;
        long ttl = isMarketOpen() ? CANDIDATE_TTL_OPEN_MS : CANDIDATE_TTL_CLOSED_MS;
        CandidateCacheEntry hit = candidateCache.get(key);
        if (hit != null && (System.currentTimeMillis() - hit.at) < ttl) {
            return copyCandidates(hit.rows);
        }
        // Single-flight: the second caller waits for the first rather than starting its own scan.
        Object lock = candidateLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            CandidateCacheEntry again = candidateCache.get(key);
            if (again != null && (System.currentTimeMillis() - again.at) < ttl) {
                return copyCandidates(again.rows);
            }
            List<Map<String, Object>> fresh =
                computeTrackedCandidates(userId, institutionId, requireCrossing, applyReturnBand);
            candidateCache.put(key, new CandidateCacheEntry(System.currentTimeMillis(), fresh));
            return copyCandidates(fresh);
        }
    }

    /** While the market is moving the answer goes stale quickly; once it shuts it cannot change. */
    private static final long CANDIDATE_TTL_OPEN_MS = 60_000L;
    private static final long CANDIDATE_TTL_CLOSED_MS = 10 * 60_000L;

    private record CandidateCacheEntry(long at, List<Map<String, Object>> rows) {}

    private final java.util.concurrent.ConcurrentHashMap<String, CandidateCacheEntry>
        candidateCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Object>
        candidateLocks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A caller's own copy. The rows are handed to ranking and to CSV builders, both of which write
     * into them, so sharing the stored maps would let one panel's scores appear in another's.
     */
    private List<Map<String, Object>> copyCandidates(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) out.add(new LinkedHashMap<>(r));
        return out;
    }

    /** Drops the memo so the next read recomputes - used whenever prices or uploads change. */
    private void invalidateCandidateCache() {
        candidateCache.clear();
    }

    private List<Map<String, Object>> computeTrackedCandidates(Long userId, Long institutionId,
                                                             boolean requireCrossing,
                                                             boolean applyReturnBand) {
        long tLoad = System.currentTimeMillis();
        List<Map<String, Object>> stocks;
        currentAboveBaseOnly.set(true);
        try {
            stocks = getAllTrackedStocksScoped(userId, institutionId, null, null,
                applyReturnBand ? getEligibleMinReturn() : null,
                applyReturnBand ? getEligibleMaxReturn() : null,
                "return_pct", "asc", 0, 50000, false);
        } finally {
            // Always clear it: the thread is pooled and any later query on it must be unaffected.
            currentAboveBaseOnly.remove();
        }
        long loadMs = System.currentTimeMillis() - tLoad;
        // Price-only filters first, so the (expensive) candle load runs for candidates only.
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> s : stocks) {
            double basePrice = getD(s, "base_price");
            double currentPrice = getD(s, "current_price");
            if (basePrice <= 0 || currentPrice <= 0) continue;
            if (currentPrice < basePrice) continue;
            candidates.add(s);
        }
        // One batched candle load instead of one query per candidate: this loop used to be
        // the whole cost of the ranking stage of a price refresh.
        long tBars = System.currentTimeMillis();
        Map<String, List<CandleBar>> barsBySymbol = loadDailyBarsBatch(
            candidates.stream().map(s -> String.valueOf(s.getOrDefault("symbol", ""))).toList(), 30);
        long barsMs = System.currentTimeMillis() - tBars;
        log.info("[RankTiming] tracked-load {} ms ({} rows), bars-batch {} ms ({} candidates)",
            loadMs, stocks.size(), barsMs, candidates.size());

        // When each candidate's price was last stamped. The row maps this path builds do not
        // carry current_price_date - only the lite uploaded-stocks view does - so the staleness
        // test below had nothing to read and silently passed everything. One query for the
        // institution, reused for every candidate.
        Map<String, String> candidatePriceDates = trackedPriceDates(
            candidates.stream().map(s -> String.valueOf(s.getOrDefault("symbol", ""))).toList(),
            institutionId);

        List<Map<String, Object>> filtered = new ArrayList<>();
        Map<String, double[]> volData = new HashMap<>();
        // Why candidates drop out. "0 eligible" on its own cannot distinguish a genuinely quiet
        // day from missing bars, an unusable volume feed, or a filter that is simply wrong - and
        // that difference is the whole diagnosis.
        int noBars = 0, noPrevClose = 0, notCrossing = 0, noTodayVol = 0, volUnmeasurable = 0,
            volBelowAvg = 0, stalePrice = 0;
        for (Map<String, Object> s : candidates) {
            String sym = String.valueOf(s.getOrDefault("symbol", ""));
            double basePrice = getD(s, "base_price");
            List<CandleBar> bars = barsBySymbol.getOrDefault(normalizeSymbol(sym), List.of());
            boolean latestBarIsCurrent = currentPriceIsLatestBar(bars);
            // The bar BEFORE the one the current price came from.
            //
            // previousBarIndex steps back only when the newest bar is TODAY'S. Outside market
            // hours the newest bar is an earlier completed session and the current price IS its
            // close, so prevClose and currentPrice became the same number. Candidates reach this
            // loop precisely because currentPrice >= basePrice, so "prevClose >= basePrice" was
            // then true for all of them and the crossing test could never fire: 793 of 794
            // rejected as "not crossing" on 6 Sep 2026, which is every evening and every weekend.
            // Against the real previous session 23 of those 794 genuinely cross.
            int prevIdx = latestBarIsCurrent ? 1 : 0;
            if (bars.isEmpty()) { noBars++; continue; }

            // The stored price must be at least as recent as the price history it is judged
            // against. RUBYMILLS ranked FIRST in Today top 10 on a price last stamped 31 Aug,
            // seven sessions stale: its Current read 384.35 against a Previous Close of 428.30
            // from 4 Sep - a stock shown as today's strongest pick while quoted below the very
            // bar it was being compared with, and a 92% "return" measured from a week-old number.
            // A price older than the newest stored bar cannot be today's price, and every figure
            // derived from it - return, crossing, rank - is fiction.
            Object priceDateRaw = s.get("current_price_date");
            String priceDate = priceDateRaw != null ? String.valueOf(priceDateRaw)
                : candidatePriceDates.get(normalizeSymbol(sym));
            String newestBarDate = normalizeStoredDate(bars.get(0).tradeDate);
            if (priceDate != null && !priceDate.isBlank() && newestBarDate != null
                && priceDate.compareTo(newestBarDate) < 0) {
                stalePrice++;
                continue;
            }
            double prevClose = bars.size() > prevIdx ? bars.get(prevIdx).close : 0;
            if (prevClose <= 0) { noPrevClose++; continue; }
            if (requireCrossing && prevClose >= basePrice) { notCrossing++; continue; }
            // Traded volume for the current session. Mid-session the live quote carries it.
            // Outside market hours the quote has no volume at all, so fall back to the newest
            // stored bar - otherwise the stock is dropped for having "no volume" whenever the
            // quote price differs from the aggregated bar close by even a paisa, which after
            // the close is almost always (MAMATA: quote 420.35 against a 421 bar close).
            double quoteVol = getD(s, "volume");
            boolean useBarVolume = latestBarIsCurrent || (quoteVol <= 0 && !bars.isEmpty());

            // The newest bar is not always one that can answer a volume question.
            //
            // Sessions missing from the daily feed are now reconstructed from intraday candles,
            // and 28 Aug 2026 is a complete price session carrying no traded volume whatsoever.
            // Taking bars.get(0) as "today's volume" therefore read 0 for every stock whose
            // 28 Aug bar was reconstructed, and made the 20-day average unmeasurable for the
            // rest - because a reconstructed bar is its own source, so the same-source average
            // had a single bar to work with. The result was all 794 candidates failing the
            // volume test in one run, and not one of them for having low volume.
            //
            // So the volume test is anchored to the newest bar that actually carries volume,
            // and the average is taken from behind THAT bar. Price still comes from the newest
            // bar - the crossover is a price question and the reconstruction answers it well.
            int volIdx = 0;
            while (volIdx < bars.size() && bars.get(volIdx).volume <= 0) volIdx++;
            boolean haveBarVolume = volIdx < bars.size();

            // The average must exclude whichever bar the volume came from, otherwise the
            // comparison includes the very volume it is being judged against.
            int avgFrom = useBarVolume ? volIdx + 1 : 0;
            double avg20Vol = haveBarVolume && bars.size() > avgFrom + 1
                ? sameSourceAverageVolume(bars, avgFrom, Math.min(20, bars.size() - avgFrom),
                                          bars.get(volIdx), MIN_SAME_SOURCE_VOL_BARS) : 0;
            double todayVol = useBarVolume ? (haveBarVolume ? bars.get(volIdx).volume : 0) : quoteVol;
            volData.put(normalizeSymbol(sym), new double[]{todayVol, avg20Vol});
            // "Cannot be measured" is not "failed" - the same distinction the ATR and
            // Eligible exports already make. A stock with no comparable volume history is
            // reported without a volume verdict rather than silently dropped, so a thin feed
            // shortens the evidence rather than emptying the screen.
            boolean volMeasurable = todayVol > 0 && avg20Vol > 0;
            if (todayVol <= 0)             noTodayVol++;
            else if (avg20Vol <= 0)        volUnmeasurable++;
            else if (todayVol <= avg20Vol) volBelowAvg++;

            if (!volMeasurable || todayVol > avg20Vol) {
                // Record the three values the entry test was actually decided on. They were computed
                // here and then thrown away, so any screen showing this candidate had to display 0
                // for previous close - which makes the crossing impossible to verify by eye, and
                // makes a correct list look broken.
                s.put("previous_close", Math.round(prevClose * 100.0) / 100.0);
                // Move since the previous close - the two columns shown beside it. The stored
                // return_pct is measured from the BASE price, which is a different question and
                // made Return % look unrelated to the Prev Close and Current on the same row.
                double curPx = getD(s, "current_price");
                s.put("price_change_pct", prevClose > 0
                    ? Math.round(((curPx - prevClose) / prevClose) * 10000.0) / 100.0 : 0);
                s.put("volume", Math.round(todayVol));
                s.put("avg_20d_volume", Math.round(avg20Vol));
                s.put("volume_tested", volMeasurable ? "Yes" : "n/a");
                // ATR14 over the sessions behind the previous close, as a percentage of it.
                //
                // Only the as-of reconstruction computed this, so every panel fed by the LIVE
                // selection - the provisional list inside the RSI 45 section among them - had no
                // ATR to show and printed "-" on every row. Same window and same denominator as
                // the reconstruction, so the two screens cannot disagree about one stock.
                double atr14 = 0, atrPct = 0;
                int atrEnd = prevIdx + 15;
                if (bars.size() > atrEnd) {
                    List<Double> hs = new ArrayList<>(), ls = new ArrayList<>(), cs = new ArrayList<>();
                    for (int i = atrEnd; i >= prevIdx; i--) {      // oldest-first, ending at D-1
                        CandleBar bar = bars.get(i);
                        hs.add(bar.high); ls.add(bar.low); cs.add(bar.close);
                    }
                    atr14 = indicator.atr(hs, ls, cs, 14);
                    if (atr14 > 0 && prevClose > 0) {
                        atrPct = Math.round((atr14 / prevClose) * 10000.0) / 100.0;
                    }
                }
                s.put("atr14", Math.round(atr14 * 100.0) / 100.0);
                s.put("atr_pct", atrPct);
                filtered.add(s);
            }
        }
        if (filtered.isEmpty() && !candidates.isEmpty()) {
            log.warn("[RankFunnel] {} candidate(s) above base, none eligible. "
                   + "no bars {}, price older than newest bar {}, no prev close {}, not crossing {}, "
                   + "zero volume today {}, 20d average not measurable {}, volume below average {}.",
                candidates.size(), noBars, stalePrice, noPrevClose, notCrossing,
                noTodayVol, volUnmeasurable, volBelowAvg);
        }

        filtered.sort((a, b) -> {
            String symA = normalizeSymbol(String.valueOf(a.getOrDefault("symbol", "")));
            String symB = normalizeSymbol(String.valueOf(b.getOrDefault("symbol", "")));
            double volA = volData.containsKey(symA) ? volData.get(symA)[0] : 0;
            double volB = volData.containsKey(symB) ? volData.get(symB)[0] : 0;
            return Double.compare(volB, volA);
        });
        return filtered;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPerformanceDistribution(Long institutionId) {
        return getPerformanceDistribution(null, institutionId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPerformanceDistribution(Long userId, Long institutionId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total");
        for (int i = 0; i < BUCKETS.length; i++) {
            double lo = BUCKETS[i][0], hi = BUCKETS[i][1];
            sql.append(", SUM(CASE WHEN return_pct ");
            if (lo == Double.NEGATIVE_INFINITY) {
                sql.append("< ").append(hi);
            } else if (hi == Double.POSITIVE_INFINITY) {
                sql.append(">= ").append(lo);
            } else {
                sql.append(">= ").append(lo).append(" AND return_pct < ").append(hi);
            }
            sql.append(" THEN 1 ELSE 0 END)");
        }
        sql.append(" FROM tracked_stocks WHERE institution_id=? AND is_active=1");
        if (userId != null) sql.append(" AND user_id=?");

        var query = em.createNativeQuery(sql.toString()).setParameter(1, institutionId);
        if (userId != null) query.setParameter(2, userId);
        Object[] row = (Object[]) query.getSingleResult();

        int total = ((Number) row[0]).intValue();
        List<Map<String, Object>> distribution = new ArrayList<>();
        for (int i = 0; i < BUCKETS.length; i++) {
            double lo = BUCKETS[i][0], hi = BUCKETS[i][1];
            long count = row[i + 1] != null ? ((Number) row[i + 1]).longValue() : 0;
            distribution.add(Map.of(
                "label",   BUCKET_LABELS[i],
                "count",   count,
                "pct",     total > 0 ? Math.round(count * 1000.0 / total) / 10.0 : 0,
                "min_ret", lo == Double.NEGATIVE_INFINITY ? "-∞" : lo,
                "max_ret", hi == Double.POSITIVE_INFINITY ? "+∞" : hi
            ));
        }
        List<Map<String, Object>> positiveLine = new ArrayList<>();
        List<Map<String, Object>> negativeLine = new ArrayList<>();
        positiveLine.add(Map.of("label", "0", "value", 0, "count", 0));
        negativeLine.add(Map.of("label", "0", "value", 0, "count", 0));
        for (Map<String, Object> bucket : distribution) {
            String label = String.valueOf(bucket.get("label"));
            long count = ((Number) bucket.get("count")).longValue();
            double hi = bucket.get("max_ret") instanceof Number n ? n.doubleValue() : 0;
            double lo = bucket.get("min_ret") instanceof Number n ? n.doubleValue() : 0;
            if (hi <= 0 || label.startsWith("Below")) {
                double value = label.startsWith("Below") ? -15 : Math.min(0, hi);
                negativeLine.add(Map.of("label", label, "value", value, "count", count));
            } else if (lo >= 0 || label.startsWith("Above")) {
                double value = label.startsWith("Above") ? 30 : Math.max(0, hi);
                positiveLine.add(Map.of("label", label, "value", value, "count", count));
            }
        }
        List<Map<String, Object>> curve = getReturnDistributionCurve(userId, institutionId);
        return Map.of("distribution", distribution, "return_distribution_curve", curve,
                      "positive_line", positiveLine, "negative_line", negativeLine, "total_stocks", total,
                      "as_of", DateUtil.today());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getReturnDistributionCurve(Long userId, Long institutionId) {
        String[][] ranges = {
            {"0% to 5%", "0", "5"},
            {"5% to 10%", "5", "10"},
            {"10% to 15%", "10", "15"},
            {"15% to 30%", "15", "30"},
            {"Above 30%", "30", ""}
        };
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < ranges.length; i++) {
            if (i > 0) sql.append(", ");
            String lo = ranges[i][1], hi = ranges[i][2];
            if (hi.isBlank()) {
                sql.append("SUM(CASE WHEN return_pct >= ").append(lo).append(" THEN 1 ELSE 0 END), ")
                   .append("SUM(CASE WHEN return_pct < 0 AND ABS(return_pct) >= ").append(lo).append(" THEN 1 ELSE 0 END)");
            } else {
                sql.append("SUM(CASE WHEN return_pct >= ").append(lo).append(" AND return_pct < ").append(hi).append(" THEN 1 ELSE 0 END), ")
                   .append("SUM(CASE WHEN return_pct < 0 AND ABS(return_pct) >= ").append(lo).append(" AND ABS(return_pct) < ").append(hi).append(" THEN 1 ELSE 0 END)");
            }
        }
        sql.append(" FROM tracked_stocks WHERE institution_id=? AND is_active=1");
        if (userId != null) sql.append(" AND user_id=?");

        var query = em.createNativeQuery(sql.toString()).setParameter(1, institutionId);
        if (userId != null) query.setParameter(2, userId);
        Object[] row = (Object[]) query.getSingleResult();

        long positiveTotal = 0, negativeTotal = 0;
        long[] positiveCounts = new long[ranges.length];
        long[] negativeCounts = new long[ranges.length];
        for (int i = 0; i < ranges.length; i++) {
            positiveCounts[i] = row[i * 2] != null ? ((Number) row[i * 2]).longValue() : 0;
            negativeCounts[i] = row[i * 2 + 1] != null ? ((Number) row[i * 2 + 1]).longValue() : 0;
            positiveTotal += positiveCounts[i];
            negativeTotal += negativeCounts[i];
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < ranges.length; i++) {
            result.add(Map.of(
                "label", ranges[i][0],
                "positive_stock_count", positiveCounts[i],
                "negative_stock_count", negativeCounts[i],
                "positive_pct", positiveTotal > 0 ? Math.round(positiveCounts[i] * 10000.0 / positiveTotal) / 100.0 : 0,
                "negative_pct", negativeTotal > 0 ? Math.round(negativeCounts[i] * 10000.0 / negativeTotal) / 100.0 : 0
            ));
        }
        result.add(Map.of(
            "label", "Total",
            "positive_stock_count", positiveTotal,
            "negative_stock_count", negativeTotal,
            "positive_pct", 100.0,
            "negative_pct", 100.0
        ));
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMarketBreadthData(Long userId, Long institutionId) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. Current stats
        Map<String, Object> dash = getDashboardData(userId, institutionId);
        int totalStocks = ((Number) dash.getOrDefault("total_stocks", 0)).intValue();
        int positiveStocks = ((Number) dash.getOrDefault("positive_stocks", 0)).intValue();
        int negativeStocks = ((Number) dash.getOrDefault("negative_stocks", 0)).intValue();
        double avgReturn = ((Number) dash.getOrDefault("avg_return", 0)).doubleValue();
        double adRatio = negativeStocks > 0 ? Math.round((double) positiveStocks / negativeStocks * 100.0) / 100.0 : (positiveStocks > 0 ? 99.0 : 0);
        int breadthScore = totalStocks > 0 ? Math.round((float) positiveStocks / totalStocks * 100) : 0;

        String sentiment;
        if (breadthScore <= 20) sentiment = "Extreme Bearish";
        else if (breadthScore <= 40) sentiment = "Bearish";
        else if (breadthScore <= 60) sentiment = "Neutral";
        else if (breadthScore <= 80) sentiment = "Bullish";
        else sentiment = "Extreme Bullish";

        result.put("total_stocks", totalStocks);
        result.put("positive_stocks", positiveStocks);
        result.put("negative_stocks", negativeStocks);
        result.put("avg_return", avgReturn);
        result.put("ad_ratio", adRatio);
        result.put("breadth_score", breadthScore);
        result.put("sentiment", sentiment);
        result.put("market_status", dash.getOrDefault("market_status", "CLOSED"));
        result.put("last_refreshed", dash.getOrDefault("last_refreshed", "-"));

        // 2. Distribution buckets
        Map<String, Object> dist = getPerformanceDistribution(userId, institutionId);
        result.put("distribution", dist.get("distribution"));
        result.put("return_distribution_curve", dist.get("return_distribution_curve"));

        // 3. Sector-wise breadth
        try {
            StringBuilder sectorSql = new StringBuilder(
                "SELECT sector, COUNT(*) AS total, " +
                "SUM(CASE WHEN return_pct >= 0 THEN 1 ELSE 0 END) AS positive, " +
                "SUM(CASE WHEN return_pct < 0 THEN 1 ELSE 0 END) AS negative, " +
                "COALESCE(AVG(return_pct), 0) AS avg_ret " +
                "FROM tracked_stocks WHERE institution_id=? AND is_active=1 " +
                "AND sector IS NOT NULL AND sector != ''");
            if (userId != null) sectorSql.append(" AND user_id=?");
            sectorSql.append(" GROUP BY sector ORDER BY sector");
            var sq = em.createNativeQuery(sectorSql.toString()).setParameter(1, institutionId);
            if (userId != null) sq.setParameter(2, userId);
            List<Object[]> sectorRows = sq.getResultList();
            List<Map<String, Object>> sectorBreadth = new ArrayList<>();
            for (Object[] sr : sectorRows) {
                String sec = sr[0] != null ? sr[0].toString() : "Unknown";
                int secTotal = ((Number) sr[1]).intValue();
                int secPositive = sr[2] != null ? ((Number) sr[2]).intValue() : 0;
                int secNegative = sr[3] != null ? ((Number) sr[3]).intValue() : 0;
                double secAvg = sr[4] != null ? Math.round(((Number) sr[4]).doubleValue() * 100) / 100.0 : 0;
                double posPct = secTotal > 0 ? Math.round(secPositive * 1000.0 / secTotal) / 10.0 : 0;
                sectorBreadth.add(Map.of(
                    "sector", sec, "total", secTotal,
                    "positive", secPositive, "negative", secNegative,
                    "positive_pct", posPct, "avg_return", secAvg
                ));
            }
            result.put("sector_breadth", sectorBreadth);
        } catch (Exception e) {
            result.put("sector_breadth", List.of());
        }

        // 4. Historical breadth from ranking runs (last 30 distinct dates)
        try {
            StringBuilder histSql = new StringBuilder(
                "SELECT DISTINCT ON (run_date) run_date, curr_positive_count, curr_negative_count, " +
                "ranked_count, curr_avg_return " +
                "FROM tracker_rankings WHERE institution_id=?");
        if (userId != null) histSql.append(" AND user_id=?");
            histSql.append(" ORDER BY run_date DESC, id DESC LIMIT 30");
            var hq = em.createNativeQuery(histSql.toString()).setParameter(1, institutionId);
            if (userId != null) hq.setParameter(2, userId);
            List<Object[]> histRows = hq.getResultList();
            List<Map<String, Object>> breadthHistory = new ArrayList<>();
            for (Object[] hr : histRows) {
                String date = hr[0] != null ? hr[0].toString().substring(0, Math.min(10, hr[0].toString().length())) : "";
                int pos = hr[1] != null ? ((Number) hr[1]).intValue() : 0;
                int neg = hr[2] != null ? ((Number) hr[2]).intValue() : 0;
                int ranked = hr[3] != null ? ((Number) hr[3]).intValue() : 0;
                double dayAvg = hr[4] != null ? Math.round(((Number) hr[4]).doubleValue() * 100) / 100.0 : 0;
                int dayTotal = pos + neg > 0 ? pos + neg : ranked;
                int dayScore = dayTotal > 0 ? Math.round((float) pos / dayTotal * 100) : 50;
                breadthHistory.add(Map.of(
                    "date", date, "breadth_score", dayScore,
                    "positive", pos, "negative", neg,
                    "total", dayTotal, "avg_return", dayAvg
                ));
            }
            java.util.Collections.reverse(breadthHistory);
            result.put("breadth_history", breadthHistory);
        } catch (Exception e) {
            result.put("breadth_history", List.of());
        }

        result.put("as_of", DateUtil.today());
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getLatestRankingFull(Long institutionId) {
        return getLatestRankingFull(null, institutionId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getLatestRankingFull(Long userId, Long institutionId) {
        return getLatestRankingFull(userId, institutionId, true);
    }

    /**
     * @param allowFallbackRanking when no stored run exists, {@code true} scores the whole
     *        tracked universe on the spot via buildFallbackTop10Ranking(). Ranking belongs to
     *        the Current Top 10 section only, so the Previous Top 10 paths pass {@code false}
     *        and get an empty result instead — they used to reach this path and silently rank
     *        all ~1,200 stocks just to render a historical list.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getLatestRankingFull(Long userId, Long institutionId, boolean allowFallbackRanking) {
        // Today top 10 is CALCULATED, never read back from a stored run.
        //
        // Rankings were being written to tracker_rankings and the screen then read the newest row
        // back. That made the list a record rather than a calculation, and it went wrong in two
        // ways at once. The scheduled job writes its runs with no user_id while a run triggered on
        // a user's own request carries theirs, so this read - which matches user_id exactly, as it
        // must while the institution and retail apps are separate - only ever saw the user-scoped
        // rows. Today top 10 sat on the 09:15 run for the whole morning while fresh runs landed
        // every five minutes behind it. Worse, a list frozen at 09:15 keeps whatever it selected
        // then: RUBYMILLS stayed at rank 1 on a price last stamped 31 August.
        //
        // Computing it here from the current entry rules removes both problems and matches how
        // this section is specified: derive it, show it, and let the download carry the same
        // figures. Stored runs are left alone for anything historical that still refers to them.
        if (allowFallbackRanking) {
            Map<String, Object> live = buildFallbackTop10Ranking(userId, institutionId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> liveTop =
                (List<Map<String, Object>>) live.getOrDefault("current_top10", List.of());
            if (!liveTop.isEmpty()) {
                live.remove("fallback_ranking");
                live.put("computed_live", true);
                live.put("message", "Calculated now from the current entry rules.");
                live.put("last_updated", DateUtil.now());
                return live;
            }
        }

        // Get latest run_id
        StringBuilder latestSql = new StringBuilder(
            "SELECT run_id, run_date, curr_avg_return, curr_win_ratio, curr_positive_count," +
            "curr_negative_count, prev_avg_return, prev_win_ratio, prev_positive_count," +
            "prev_negative_count, eligible_count, ranked_count " +
            "FROM tracker_rankings WHERE institution_id=?");
        if (userId != null) latestSql.append(" AND user_id=?");
        latestSql.append(" ORDER BY run_date DESC, id DESC LIMIT 1");
        var latestQuery = em.createNativeQuery(latestSql.toString()).setParameter(1, institutionId);
        if (userId != null) latestQuery.setParameter(2, userId);
        List<Object[]> runs = latestQuery.getResultList();

        if (runs.isEmpty()) {
            if (!allowFallbackRanking) return new LinkedHashMap<>();
            return buildFallbackTop10Ranking(userId, institutionId);
        }

        Object[] curr = runs.get(0);
        String runId = curr[0].toString();
        String currRunDate = curr[1] != null ? curr[1].toString() : DateUtil.today();

        List<Object[]> items = em.createNativeQuery(
            "SELECT rank_position,symbol,sector,market_cap_name,composite_score,upload_date," +
            "base_price,current_price,return_pct,days_since_upload,factor_scores_json " +
            "FROM tracker_ranking_items WHERE run_id=? AND is_current_top10=1 ORDER BY rank_position")
            .setParameter(1, runId).getResultList();

        List<String> currSymbols = items.stream().map(r -> String.valueOf(r[1])).toList();
        Map<String, Double> currLivePrices = resolveCurrentPricesBatch(currSymbols, institutionId);
        Map<String, Set<String>> top10Aliases = loadDhanAliases(currSymbols);
        Map<String, LiveTrackerQuote> top10LiveQuotes = loadLatestLiveQuotes(currSymbols, top10Aliases);
        Map<String, DailyTrackerPrice> top10DailyPrices = loadLatestDailyPrices(currSymbols, top10Aliases);
        Map<String, String[]> top10Names = loadTradingSymbolAndName(currSymbols);
        // Joins the batch loads above so the previous-close fallback below stays query-free.
        Map<String, List<CandleBar>> top10Bars = loadDailyBarsBatch(currSymbols, 5);

        List<Map<String, Object>> top10 = items.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String sym = String.valueOf(r[1]);
            String normalSym = normalizeSymbol(sym);
            double basePrice = getNumber(r[6]);
            double snapshotPrice = getNumber(r[7]);
            double livePrice = currLivePrices.getOrDefault(normalSym, 0.0);
            LiveTrackerQuote lq = top10LiveQuotes.get(normalSym);
            DailyTrackerPrice dp = top10DailyPrices.get(normalSym);
            double displayPrice = livePrice > 0 ? livePrice : snapshotPrice;
            if (displayPrice <= 0 && lq != null && lq.currentPrice > 0) displayPrice = lq.currentPrice;
            if (displayPrice <= 0 && dp != null && dp.close > 0) displayPrice = dp.close;
            double returnPct = basePrice > 0 && displayPrice > 0
                ? Math.round(((displayPrice - basePrice) / basePrice) * 10000.0) / 100.0
                : (r[8] != null ? Math.round(((Number)r[8]).doubleValue()*100)/100.0 : 0);
            String[] names = top10Names.getOrDefault(normalSym, new String[]{"", ""});
            m.put("rank",        r[0]); m.put("symbol",     sym);
            m.put("trading_symbol", names[0].isEmpty() ? sym : names[0]);
            m.put("company_name", names[1]);
            m.put("sector",      r[2]); m.put("market_cap", r[3]);
            m.put("score",       r[4]); m.put("upload_date",r[5]);
            // Rounded: these are float quotes and reached the Today top 10 table as
            // 384.3500061035156 beside neighbours printed to two places.
            m.put("base_price",  round2(getNumber(r[6]))); m.put("current_price", round2(displayPrice));
            m.put("return_pct",  returnPct);
            m.put("days_since_upload", r[9]);
            m.put("factor_scores", parseJsonSafe(r[10]));
            double prevClose = 0;
            if (lq != null && lq.close > 0) {
                prevClose = lq.close;                       // Dhan's reported prior-day close (most reliable when live)
            } else {
                // No live quote: derive the ACTUAL previous close from stored daily
                // history (second-most-recent bar) so it rolls forward each trading
                // day instead of freezing on the latest/stale daily-price row (dp.close
                // is the *latest* close, not the previous — the BAJFINANCE symptom).
                List<CandleBar> pcBars = top10Bars.getOrDefault(normalSym, List.of());
                int pcIdx = previousBarIndex(pcBars);
                if (pcBars.size() > pcIdx && pcBars.get(pcIdx).close > 0) prevClose = pcBars.get(pcIdx).close;
                else if (dp != null && dp.close > 0) prevClose = dp.close;
            }
            double todayReturn = prevClose > 0 && displayPrice > 0
                ? Math.round(((displayPrice - prevClose) / prevClose) * 10000.0) / 100.0 : 0;
            m.put("previous_close", prevClose > 0 ? round2(prevClose) : null);
            m.put("today_return_pct", todayReturn);
            double volume = 0;
            if (lq != null && lq.volume > 0) volume = lq.volume;
            else if (dp != null && dp.volume > 0) volume = dp.volume;
            m.put("today_volume", volume > 0 ? volume : null);
            // Best bid/ask from the live feed. Both > 0 means there is a buyer AND a seller
            // quoting right now, i.e. the stock is actually tradeable.
            double bid = lq != null ? lq.bid : 0;
            double ask = lq != null ? lq.ask : 0;
            m.put("bid", bid > 0 ? bid : null);
            m.put("ask", ask > 0 ? ask : null);
            m.put("has_two_way_quote", bid > 0 && ask > 0);
            m.putAll(buildUploadedStockSignals(sym, displayPrice, institutionId));
            String lastUpdated = lq != null && lq.tickReceivedAt != null ? lq.tickReceivedAt
                : dp != null && dp.tradeDate != null ? dp.tradeDate
                : r[5] != null ? r[5].toString() : "";
            m.put("last_updated", lastUpdated);
            return m;
        }).collect(Collectors.toList());

        double liveAvgReturn = top10.stream().mapToDouble(s -> getD(s, "return_pct")).average().orElse(getNumber(curr[2]));
        long livePositive = top10.stream().filter(s -> getD(s, "return_pct") >= 0).count();
        double liveWinRatio = top10.isEmpty() ? getNumber(curr[3]) : (double) livePositive / top10.size() * 100;

        // Previous run
        List<Map<String, Object>> prevTop10 = new ArrayList<>();
        String prevRunDate = "";
        List<Object[]> prevRuns;
        StringBuilder prevSql = new StringBuilder(
            "SELECT run_id,run_date,curr_avg_return,curr_win_ratio,curr_positive_count,curr_negative_count " +
            "FROM tracker_rankings WHERE institution_id=? AND run_date<?");
        if (userId != null) prevSql.append(" AND user_id=?");
        prevSql.append(" ORDER BY run_date DESC, id DESC LIMIT 1");
        var prevQuery = em.createNativeQuery(prevSql.toString())
            .setParameter(1, institutionId).setParameter(2, currRunDate);
        if (userId != null) prevQuery.setParameter(3, userId);
        prevRuns = prevQuery.getResultList();
        Object[] prevRun = null;
        if (!prevRuns.isEmpty()) {
            prevRun = prevRuns.get(0);
            String prevRunId = prevRun[0].toString();
            prevRunDate = prevRun[1] != null ? prevRun[1].toString() : "";
            List<Object[]> prevItems = em.createNativeQuery(
                "SELECT rank_position,symbol,sector,market_cap_name,composite_score,upload_date," +
                "base_price,current_price,return_pct,days_since_upload " +
                "FROM tracker_ranking_items WHERE run_id=? ORDER BY rank_position")
                .setParameter(1, prevRunId).getResultList();
            List<String> prevSymbols = prevItems.stream().map(r -> String.valueOf(r[1])).toList();
            Map<String, Double> prevLivePrices = resolveCurrentPricesBatch(prevSymbols, institutionId);
            prevTop10 = prevItems.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                String sym = String.valueOf(r[1]);
                double basePrice = getNumber(r[6]);
                double snapshotPrice = getNumber(r[7]);
                double snapshotReturn = r[8] != null ? Math.round(((Number)r[8]).doubleValue()*100)/100.0 : 0;
                double livePrice = prevLivePrices.getOrDefault(normalizeSymbol(sym), 0.0);
                double currReturn = basePrice > 0 && livePrice > 0
                    ? Math.round(((livePrice - basePrice) / basePrice) * 10000.0) / 100.0 : snapshotReturn;
                String uploadDate = r[5] != null ? r[5].toString() : "";
                long days = 0;
                try { days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(uploadDate), LocalDate.now()); }
                catch (Exception ignored) {}
                m.put("rank", r[0]); m.put("symbol", sym); m.put("sector", r[2]);
                m.put("market_cap", r[3]); m.put("upload_date", uploadDate);
                m.put("base_price", r[6]);
                m.put("snapshot_price", snapshotPrice);
                m.put("current_price", livePrice > 0 ? livePrice : snapshotPrice);
                m.put("return_pct", snapshotReturn);
                m.put("current_return", currReturn);
                m.put("days_since_upload", days > 0 ? days : getNumber(r[9]));
                return m;
            }).collect(Collectors.toList());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id",          runId);
        result.put("run_date",        curr[1]);
        result.put("prev_run_date",   prevRunDate);
        // Show only stocks with a live buyer AND seller. The filter is skipped when it would
        // empty the table (outside market hours the feed carries no depth at all), so the
        // section degrades to "everything" rather than going blank.
        List<Map<String, Object>> tradeable = top10.stream()
            .filter(row -> Boolean.TRUE.equals(row.get("has_two_way_quote")))
            .collect(Collectors.toList());
        int excludedNoTwoWay = top10.size() - tradeable.size();
        boolean twoWayApplied = !tradeable.isEmpty();
        if (twoWayApplied) top10 = tradeable;
        result.put("two_way_filter_applied", twoWayApplied);
        result.put("excluded_no_two_way", twoWayApplied ? excludedNoTwoWay : 0);
        result.put("current_top10",   top10);
        result.put("previous_top10",  prevTop10);
        result.put("curr_avg_return", Math.round(liveAvgReturn * 100) / 100.0);
        result.put("curr_win_ratio",  Math.round(liveWinRatio * 10) / 10.0);
        result.put("curr_positive",   (int) livePositive);
        result.put("curr_negative",   top10.size() - (int) livePositive);
        result.put("prev_avg_return", prevRun != null ? prevRun[2] : 0);
        result.put("prev_win_ratio",  prevRun != null ? prevRun[3] : 0);
        result.put("prev_positive",   prevRun != null ? prevRun[4] : 0);
        result.put("prev_negative",   prevRun != null ? prevRun[5] : 0);
        result.put("eligible_count",  curr[10]);
        result.put("ranked_count",    curr[11]);
        // Market time, not wall-clock. In session this is the live tick; once the market
        // shuts the prices stop moving, so the honest answer is when the last price actually
        // traded - otherwise the header claims a Sunday morning timestamp for Friday's prices.
        result.put("last_updated",    isMarketOpen() ? DateUtil.now() : lastTradedAtIST());
        // Always the traded time, whether or not a session is running, so the screen can say
        // which it is showing rather than leaving the reader to guess.
        result.put("last_traded_at",  lastTradedAtIST());
        result.put("market_open",     isMarketOpen());
        result.putAll(rankingQualitySummary(top10));
        result.put("disclaimer", "TradeRakshak™ rankings are for EDUCATIONAL purposes only. Not SEBI investment advice.");
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRankingHistory(Long institutionId, int days) {
        return getRankingHistory(null, institutionId, days);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRankingHistory(Long userId, Long institutionId, int days) {
        int span = Math.max(1, Math.min(days, 400));
        String to = DateUtil.today();
        String from = java.time.LocalDate.now(IST).minusDays(span).toString();
        return getRankingHistory(userId, institutionId, from, to);
    }

    /**
     * One point per calendar day between two dates, for the return-trend chart.
     *
     * <p>Two things were wrong with taking the newest N rows instead. The engine re-ranks many times
     * a day, so a "90 day" chart was really the newest 90 <em>runs</em> - four days of history, not
     * ninety. Worse, those runs share a date, and the chart keys its bars by date, so the duplicate
     * keys meant it drew nothing at all rather than drawing too much.
     *
     * <p>DISTINCT ON collapses each day to a single run: the locked 15:20 snapshot where one exists,
     * since that is the ranking the day is judged on, and otherwise that day's latest run.
     *
     * @param from first date to include, inclusive (yyyy-MM-dd)
     * @param to   last date to include, inclusive (yyyy-MM-dd)
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRankingHistory(Long userId, Long institutionId,
                                                       String from, String to) {
        StringBuilder sql = new StringBuilder(
            "SELECT DISTINCT ON (run_date) run_id, run_date, curr_avg_return, prev_avg_return, " +
            "curr_win_ratio, prev_win_ratio, curr_positive_count, prev_positive_count, " +
            "eligible_count, trade_avg_return, trade_winners, trade_losers, trade_symbol_count, " +
            "trade_source FROM tracker_rankings WHERE institution_id=? ");
        if (userId != null) sql.append("AND user_id=").append(userId).append(" ");
        sql.append("AND run_date >= ? AND run_date <= ? ");
        // DISTINCT ON keeps the first row per run_date, so this ORDER BY decides which run
        // represents the day: the locked snapshot first, then the most recent.
        sql.append("ORDER BY run_date DESC, is_locked DESC NULLS LAST, id DESC");

        List<Object[]> rows;
        try {
            rows = em.createNativeQuery(sql.toString())
                .setParameter(1, institutionId)
                .setParameter(2, from)
                .setParameter(3, to)
                .getResultList();
        } catch (Exception e) {
            log.warn("Ranking history unavailable: {}", e.getMessage());
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        boolean isFirst = true;
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date",             r[1]);
            m.put("curr_avg_return",  r[2]);
            m.put("prev_avg_return",  r[3]);
            m.put("curr_win_ratio",   r[4]);
            m.put("prev_win_ratio",   r[5]);
            m.put("curr_positive",    r[6]);
            m.put("prev_positive",    r[7]);
            m.put("eligible_count",   r[8]);
            if (isFirst) {
                Map<String, Object> trade = computeTop10OvernightTradeReturn(
                    institutionId, safe(r[0]), safe(r[1]));
                m.put("day_avg_return",     trade.get("avg_return"));
                m.put("trade_avg_return",   trade.get("avg_return"));
                m.put("trade_symbol_count", trade.get("symbol_count"));
                m.put("trade_winners",      trade.get("winners"));
                m.put("trade_losers",       trade.get("losers"));
                m.put("trade_source",       trade.get("source"));
                m.put("trade_rule",         trade.get("rule"));
                m.put("trade_details",      trade.get("details"));
                storeTradeReturn(safe(r[0]), trade);
                isFirst = false;
            } else if (r[9] != null) {
                // Use stored trade return from DB (computed during previous ranking runs)
                double storedTradeAvg = ((Number) r[9]).doubleValue();
                m.put("day_avg_return",     storedTradeAvg);
                m.put("trade_avg_return",   storedTradeAvg);
                m.put("trade_winners",      r[10] != null ? r[10] : 0);
                m.put("trade_losers",       r[11] != null ? r[11] : 0);
                m.put("trade_symbol_count", r[12] != null ? r[12] : 10);
                m.put("trade_source",       r[13] != null ? r[13].toString() : "stored");
                m.put("trade_rule",         "Buy Current Top 10 at 15:20 and sell next trading day at 10:00");
            } else {
                Map<String, Object> trade = computeTop10OvernightTradeReturn(
                    institutionId, safe(r[0]), safe(r[1]));
                Object computedAvg = trade.get("avg_return");
                boolean hasData = computedAvg != null && ((Number) computedAvg).doubleValue() != 0;
                if (hasData) {
                    m.put("day_avg_return",     computedAvg);
                    m.put("trade_avg_return",   computedAvg);
                    m.put("trade_symbol_count", trade.get("symbol_count"));
                    m.put("trade_winners",      trade.get("winners"));
                    m.put("trade_losers",       trade.get("losers"));
                    m.put("trade_source",       trade.get("source"));
                    m.put("trade_rule",         trade.get("rule"));
                    storeTradeReturn(safe(r[0]), trade);
                } else {
                    m.put("day_avg_return",     null);
                    m.put("trade_avg_return",   null);
                    m.put("trade_symbol_count", 0);
                    m.put("trade_winners",      0);
                    m.put("trade_losers",       0);
                    m.put("trade_source",       "no_data");
                    m.put("trade_rule",         "No actual trade data available for this date.");
                }
            }
            result.add(m);
        }
        Collections.reverse(result);
        return result;
    }

    private Map<String, Object> computeTop10OvernightTradeReturn(Long institutionId, String runId, String runDate) {
        List<String> symbols = loadCurrentTop10Symbols(institutionId, runId);
        List<Map<String, Object>> details = new ArrayList<>();
        Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        double sum = 0;
        int count = 0, winners = 0, losers = 0;

        for (String symbol : symbols) {
            TradeQuote buy = findIntradayBuyQuote(symbol, runDate);
            TradeQuote sell = buy == null ? null : findIntradaySellQuote(symbol, runDate);
            String source = "intraday";
            if (buy == null || sell == null) {
                TradeQuote[] daily = findDailyFallbackQuotes(symbol, runDate);
                buy = daily[0];
                sell = daily[1];
                source = "daily_close_fallback";
            }
            // No sell yet means the position is still open - the next-day close has not happened.
            // Value it at the live price so the return reflects where the trade actually stands,
            // rather than dropping the stock and shrinking the average it belongs in.
            boolean stillOpen = false;
            if (buy != null && buy.price > 0 && sell == null) {
                double live = resolveCurrentPrice(symbol, institutionId);
                if (live > 0) {
                    sell = new TradeQuote(live, getPrevTop10SellTime(), "live_price");
                    stillOpen = true;
                    source = "open_at_live_price";
                }
            }
            if (buy == null || sell == null || buy.price <= 0) {
                details.add(Map.of("symbol", symbol, "source", "missing"));
                sourceCounts.merge("missing", 1, Integer::sum);
                continue;
            }
            double ret = ((sell.price - buy.price) / buy.price) * 100.0;
            ret = Math.round(ret * 100.0) / 100.0;
            sum += ret;
            count++;
            if (ret >= 0) winners++; else losers++;
            sourceCounts.merge(source, 1, Integer::sum);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", symbol);
            row.put("buy_price", Math.round(buy.price * 100.0) / 100.0);
            row.put("buy_time", buy.time);
            row.put("sell_price", Math.round(sell.price * 100.0) / 100.0);
            row.put("sell_time", sell.time);
            // The sell happens on a LATER session than the buy, and only the timestamp knew it.
            // The row exposed sell_time alone, so the screen paired 11:00 with the buy's date and
            // showed a sell four and a half hours BEFORE the purchase it closed. TradeQuote.time
            // is the whole candle_time, so the date is already here - it just was not passed on.
            row.put("sell_date", stampDatePart(sell.time));
            row.put("buy_date", stampDatePart(buy.time));
            row.put("return_pct", ret);
            row.put("status", stillOpen ? "OPEN" : "CLOSED");
            row.put("source", source);
            details.add(row);
        }

        double avg = count == 0 ? 0 : Math.round((sum / count) * 100.0) / 100.0;
        String source = sourceCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("missing");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("avg_return", avg);
        result.put("symbol_count", count);
        result.put("winners", winners);
        result.put("losers", losers);
        result.put("source", source);
        result.put("rule", "Buy Current Top 10 at " + getPrevTop10BuyTime()
                         + " and sell next trading day at " + getPrevTop10SellTime());
        result.put("details", details);
        return result;
    }

    private void storeTradeReturn(String runId, Map<String, Object> trade) {
        try {
            Object avgReturn = trade.get("avg_return");
            if (avgReturn == null || runId == null || runId.isBlank()) return;
            em.createNativeQuery(
                "UPDATE tracker_rankings SET trade_avg_return=?, trade_winners=?, trade_losers=?, " +
                "trade_symbol_count=?, trade_source=? WHERE run_id=?")
                .setParameter(1, ((Number) avgReturn).doubleValue())
                .setParameter(2, trade.getOrDefault("winners", 0))
                .setParameter(3, trade.getOrDefault("losers", 0))
                .setParameter(4, trade.getOrDefault("symbol_count", 0))
                .setParameter(5, String.valueOf(trade.getOrDefault("source", "computed")))
                .setParameter(6, runId)
                .executeUpdate();
        } catch (Exception e) {
            log.debug("Store trade return for run {}: {}", runId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> loadCurrentTop10Symbols(Long institutionId, String runId) {
        try {
            return em.createNativeQuery(
                "SELECT symbol FROM tracker_ranking_items WHERE institution_id=? AND run_id=? " +
                "AND is_current_top10=1 ORDER BY rank_position LIMIT 10")
                .setParameter(1, institutionId)
                .setParameter(2, runId)
                .getResultList();
        } catch (Exception e) {
            log.debug("Trade-return symbols load: {}", e.getMessage());
            return List.of();
        }
    }

    private TradeQuote findIntradayBuyQuote(String symbol, String runDate) {
        String brokerSymbol = normalizedSql("symbol");
        String brokerTrading = normalizedSql("trading_symbol");
        TradeQuote quote = queryQuoteByNormalizedSymbol(
            "SELECT close,candle_time FROM broker_market_candles " +
            "WHERE (" + brokerSymbol + "=? OR " + brokerTrading + "=?) AND trade_date=? " +
            "AND close IS NOT NULL AND close>0 AND candle_time >= ? " +
            "ORDER BY candle_time ASC LIMIT 1",
            // 'T', not a space. candle_time is stored ISO-style ("2026-08-04T15:20:00"), and a
            // space sorts BELOW 'T', so the space form compared lower than every candle of the day
            // and matched all of them - the first row back was the 00:00 bar, never the 15:20 price.
            // The Previous Top 10's own buy time (15:20), not the shared trade time. The RSI 45
            // section buys at 15:15 and these two must be able to move independently - one
            // global setting meant a change to either silently repriced the other.
            symbol, runDate, runDate + "T" + getPrevTop10BuyTime(), "broker_intraday", 2);
        return quote;
    }

    private TradeQuote findIntradaySellQuote(String symbol, String runDate) {
        String brokerSymbol = normalizedSql("symbol");
        String brokerTrading = normalizedSql("trading_symbol");
        TradeQuote quote = queryQuoteByNormalizedSymbol(
            "SELECT close,candle_time FROM broker_market_candles " +
            "WHERE (" + brokerSymbol + "=? OR " + brokerTrading + "=?) AND trade_date > ? " +
            // 11:00 on the NEXT trading day. trade_date > runDate takes the first session
            // that actually has data, so weekends and holidays are skipped without the calendar
            // being consulted - a Friday buy sells on Monday.
            "AND close IS NOT NULL AND close>0 AND candle_time::text >= (trade_date::text || 'T" + getPrevTop10SellTime() + "') " +
            "ORDER BY trade_date ASC, candle_time ASC LIMIT 1",
            symbol, runDate, null, "broker_intraday", 2);
        return quote;
    }

    private TradeQuote[] findDailyFallbackQuotes(String symbol, String runDate) {
        String candleSymbol = normalizedSql("symbol");
        String candleTrading = normalizedSql("trading_symbol");
        // broker_market_candles is the only daily store; the market_data pass that ran first here
        // read a copy of these same rows and so could never resolve a quote the candles missed.
        TradeQuote buy = queryQuoteByNormalizedSymbol(
            "SELECT close,trade_date FROM broker_market_candles WHERE (" + candleSymbol + "=? OR " + candleTrading + "=?) AND trade_date=? " +
            "AND close IS NOT NULL AND close>0 AND (candle_mode IS NULL OR UPPER(candle_mode)='DAILY') ORDER BY trade_date DESC LIMIT 1",
            symbol, runDate, null, "broker_daily_close", 2);

        TradeQuote sell = queryQuoteByNormalizedSymbol(
            "SELECT close,trade_date FROM broker_market_candles WHERE (" + candleSymbol + "=? OR " + candleTrading + "=?) AND trade_date>? " +
            "AND close IS NOT NULL AND close>0 AND (candle_mode IS NULL OR UPPER(candle_mode)='DAILY') ORDER BY trade_date ASC LIMIT 1",
            symbol, runDate, null, "broker_daily_close", 2);
        return new TradeQuote[] { buy, sell };
    }

    private TradeQuote queryQuoteByNormalizedSymbol(String sql, String symbol, String date, String time, String source, int symbolParameters) {
        try {
            var q = em.createNativeQuery(sql);
            int p = 1;
            String normalized = normalizeSymbol(symbol);
            for (int i = 0; i < symbolParameters; i++) q.setParameter(p++, normalized);
            q.setParameter(p++, date);
            if (time != null) q.setParameter(p, time);
            List<?> rows = q.getResultList();
            if (rows.isEmpty()) return null;
            Object[] r = (Object[]) rows.get(0);
            double price = getNumber(r[0]);
            if (price <= 0) return null;
            return new TradeQuote(price, safe(r[1]), source);
        } catch (Exception e) {
            log.debug("Trade quote lookup {} {}: {}", symbol, source, e.getMessage());
            return null;
        }
    }

    public Map<String, Object> getTrackerWeightConfig(Long institutionId) {
        Map<String, Double> weights = loadTrackerWeights(institutionId);
        List<Map<String, Object>> configs = new ArrayList<>();
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT factor_key,factor_label,factor_description,weight,is_enabled,factor_order " +
                "FROM tracker_ranking_config WHERE institution_id=? ORDER BY factor_order")
                .setParameter(1, institutionId).getResultList();
            for (Object[] r : rows) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("key",         r[0]); c.put("label",       r[1]);
                c.put("description", r[2]); c.put("weight",       r[3]);
                c.put("is_enabled",  r[4]); c.put("order",        r[5]);
                c.put("min", 0); c.put("max", 30);
                configs.add(c);
            }
        } catch (Exception e) { log.warn("Weight config load: {}", e.getMessage()); }
        double total = configs.stream().mapToDouble(c -> getD(c, "weight")).sum();
        return Map.of("factors", configs, "total_weight", total, "institution_id", institutionId);
    }

    @Transactional
    public Map<String, Object> saveTrackerWeights(Long institutionId, Long adminId,
                                                   List<Map<String, Object>> factorUpdates) {
        double total = factorUpdates.stream().mapToDouble(f -> getD(f, "weight")).sum();
        if (total < 90 || total > 115) {
            return Map.of("success", false, "error",
                "Weights total must be between 90-115. Current: " + total);
        }
        for (Map<String, Object> f : factorUpdates) {
            String key  = (String) f.get("key");
            double wt   = getD(f, "weight");
            int enabled = Boolean.TRUE.equals(f.get("is_enabled")) || Integer.valueOf(1).equals(f.get("is_enabled")) ? 1 : 0;
            try {
                em.createNativeQuery(
                    "UPDATE tracker_ranking_config SET weight=?,is_enabled=?,updated_by=?,updated_at=? " +
                    "WHERE institution_id=? AND factor_key=?")
                    .setParameter(1, wt).setParameter(2, enabled)
                    .setParameter(3, adminId).setParameter(4, DateUtil.now())
                    .setParameter(5, institutionId).setParameter(6, key).executeUpdate();
            } catch (Exception e) { log.warn("Save weight {}: {}", key, e.getMessage()); }
        }
        auditLog(institutionId, adminId, "WEIGHT_CONFIG_UPDATE", "ranking_config", null,
            Map.of("total", total, "factors", factorUpdates.size()));
        return Map.of("success", true, "total", total, "message", "Ranking weights updated successfully.");
    }

    // ==========================================================================
    //  EXPORT
    // ==========================================================================

    // =========================================================================
    //  EXPORT METHODS — Fixed: correct field names, number formatting,
    //  all required columns, null-safe, CSV-safe (quoted commas)
    // =========================================================================

    /**
     * Eligible Stocks CSV Export (0% to 5% return range).
     * Columns: Symbol, Stock Name, Previous Close, Current Price, Price Change %,
     *          Current Volume, Avg 20D Volume, Vol Above 20D Avg, Weekly RSI,
     *          Weekly RSI Zone, Priority Flag, Source, Generated Date & Time
     */
    public byte[] exportEligibleStocksCsv(Long institutionId) {
        return exportEligibleStocksCsv(null, institutionId);
    }

    public byte[] exportEligibleStocksCsv(Long userId, Long institutionId) {
        return exportEligibleStocksCsv(userId, institutionId, false);
    }

    /**
     * @param requireVolume treat "Current Volume &gt; 20-Day Average Volume" as a filter rather
     *        than a reported column. Off by default.
     *
     *        <p>The three price rules - return within the band, current at or above base, and the
     *        previous close below it - are what define this screen. Volume was removing almost
     *        everything they selected: on 30 Aug 2026, 154 stocks were in the band, 9 of those
     *        crossed, and not one of the 9 also traded above its 20-day average, so the file came
     *        out empty. That is not a coincidence to tune around. A 0-5% band selects stocks that
     *        have barely moved, and stocks that barely move trade quietly, so demanding a small
     *        return AND heavier-than-usual volume asks for two things that rarely occur together.
     *
     *        <p>The ratio is still on every row, so a volume-led reading of the same list is one
     *        sort away, and requireVolume=true restores the old behaviour exactly.
     */
    public byte[] exportEligibleStocksCsv(Long userId, Long institutionId, boolean requireVolume) {
        // No signal enrichment needed (those columns were removed) \u2192 faster query.
        List<Map<String, Object>> eligible = getAllTrackedStocksScoped(userId, institutionId, null, null,
            getEligibleMinReturn(), getEligibleMaxReturn(), "return_pct", "asc", 0, 50000, false);

        List<Map<String, Object>> priced = new ArrayList<>();
        for (Map<String, Object> s : eligible) {
            double basePrice = getD(s, "base_price");
            double currentPrice = getD(s, "current_price");
            if (basePrice <= 0 || currentPrice <= 0) continue;
            if (currentPrice < basePrice) continue;
            priced.add(s);
        }
        // Only the most recent ~25 daily bars are needed for previous close and the
        // 20-day average volume, so load 30 instead of 250, for every symbol at once,
        // and skip the per-stock Weekly RSI / signal-date / name lookups entirely.
        Map<String, List<CandleBar>> barsBySymbol = loadDailyBarsBatch(
            priced.stream().map(s -> String.valueOf(s.getOrDefault("symbol", ""))).toList(), 30);

        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map<String, Object> s : priced) {
            String symbol = String.valueOf(s.getOrDefault("symbol", ""));
            double basePrice = getD(s, "base_price");
            double currentPrice = getD(s, "current_price");

            List<CandleBar> bars = barsBySymbol.getOrDefault(normalizeSymbol(symbol), List.of());
            boolean latestBarIsCurrent = currentPriceIsLatestBar(bars);
            // The bar BEFORE whichever one the current price came from.
            //
            // previousBarIndex answers a different question - "which bar should a live quote be
            // compared against" - and returns bar 0 whenever there is no bar for today. Outside
            // market hours the current price IS bar 0's close, so Prev Close and Current came out
            // identical: 1,222 of 1,229 tracked stocks on 6 Sep 2026. That makes the crossing test
            // compare a number with itself and any move measured between them exactly zero.
            int prevIdx = latestBarIsCurrent ? 1 : 0;
            double prevClose = bars.size() > prevIdx ? bars.get(prevIdx).close : 0;
            if (prevClose <= 0 || prevClose >= basePrice) continue;

            // Same rule as the eligibility selector: outside market hours the quote has no
            // traded volume, so use the newest stored bar and drop it from its own average.
            double quoteVol = getD(s, "volume");
            boolean useBarVolume = latestBarIsCurrent || (quoteVol <= 0 && !bars.isEmpty());
            int avgFrom = useBarVolume ? 1 : 0;
            double avg20Vol = bars.size() > avgFrom + 1
                ? sameSourceAverageVolume(bars, avgFrom, Math.min(20, bars.size() - avgFrom),
                                          bars.get(0), MIN_SAME_SOURCE_VOL_BARS) : 0;
            double todayVol = useBarVolume ? bars.get(0).volume : quoteVol;
            // Same distinction as the ATR export above: only exclude on a comparison that
            // could actually be made.
            boolean volMeasurable = todayVol > 0 && avg20Vol > 0;
            boolean volAboveAvg = volMeasurable && todayVol > avg20Vol;
            if (requireVolume && volMeasurable && !volAboveAvg) continue;

            // Price Change % is now measured against the PREVIOUS CLOSE, not the base price.
            double priceChangePct = prevClose > 0
                ? Math.round(((currentPrice - prevClose) / prevClose) * 10000.0) / 100.0 : 0;

            s.put("previous_close", round2(prevClose));
            s.put("price_change_pct", priceChangePct);
            s.put("current_volume", todayVol > 0 ? round2(todayVol) : null);
            // n/a says the test did not run - not enough same-feed history to compare against.
            s.put("vol_above_20d_avg", volMeasurable ? (volAboveAvg ? "Yes" : "No") : "n/a");
            s.put("avg_20d_volume", avg20Vol > 0 ? round2(avg20Vol) : null);
            s.put("vol_ratio", avg20Vol > 0 ? Math.round((todayVol / avg20Vol) * 100.0) / 100.0 : null);
            s.put("source", s.getOrDefault("current_price_source", ""));

            // Which SESSION the Current Price belongs to. "Last Updated" already says when the
            // row was fetched, but a row fetched this evening can still be carrying Friday's
            // close - and those two dates disagreeing is exactly the thing worth seeing. During
            // a live session the price is today's tick; outside one it is the newest stored
            // bar, so that bar's own trade date is the honest answer.
            String priceDate = isMarketOpen() && !bars.isEmpty() && latestBarIsCurrent
                ? DateUtil.today()
                : (bars.isEmpty() ? null : normalizeStoredDate(bars.get(0).tradeDate));
            s.put("current_price_date", priceDate);
            enriched.add(s);
        }

        enriched.sort((a, b) -> {
            double volA = a.get("current_volume") instanceof Number ? ((Number)a.get("current_volume")).doubleValue() : 0;
            double volB = b.get("current_volume") instanceof Number ? ((Number)b.get("current_volume")).doubleValue() : 0;
            return Double.compare(volB, volA);
        });

        String generatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder csv = new StringBuilder();
        csv.append("\uFEFF");
        csv.append("TradeRakshak\u2122 \u2014 Eligible Stocks (0% to 5%)\n");
        csv.append("Filter: Price crossing above Base Price, Return 0% to 5%" + (requireVolume ? ", Volume > 20D Avg" : "") + "\n");
        if (!requireVolume) {
            csv.append("Volume is reported, not filtered on - see Volume Ratio. Add requireVolume=true to the download URL to filter on it.\n");
        }
        csv.append("Condition: Previous Close < Base Price AND Current Price >= Base Price\n");
        csv.append("Note: Price Change % = (Current Price - Previous Close) / Previous Close\n");
        csv.append("Generated: ").append(generatedAt).append(",Total: ").append(enriched.size()).append("\n");
        csv.append("DISCLAIMER: For educational purposes only. Not SEBI investment advice.\n\n");
        csv.append("Symbol,Base Price,Base Price Date,Previous Close,Current Price,"
                 + "Current Price Date,Price Change %,Current Volume,Volume Above 20D Avg,"
                 + "Source,Last Updated,Generated Date & Time\n");

        for (Map<String, Object> s : enriched) {
            csv.append(safe(s.get("symbol"))).append(",")
               .append(fmtNum(s.get("base_price"))).append(",")
               .append(safe(s.get("upload_date"))).append(",")
               .append(fmtNum(s.get("previous_close"))).append(",")
               .append(fmtNum(s.get("current_price"))).append(",")
               .append(safe(s.get("current_price_date"))).append(",")
               .append(fmtNum(s.get("price_change_pct"))).append(",")
               .append(fmtNum(s.get("current_volume"))).append(",")
               .append(safe(s.get("vol_above_20d_avg"))).append(",")
               .append(safe(s.get("source"))).append(",")
               .append(safe(s.get("last_refreshed"))).append(",")
               .append(generatedAt).append("\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Validated exit bracket for the ATR Base Price Crossover screen. See the export below
     *  for why these three numbers are not free parameters. */
    private static final double CROSSOVER_STOP_PCT   = 7.0;
    private static final double CROSSOVER_TARGET_PCT = 15.0;
    private static final int    CROSSOVER_MAX_HOLD   = 15;
    /** Daily bars loaded per symbol: ATR14 needs 15, the volume average 20, and the
     *  "no earlier touch since the base price date" scan needs to reach back to that date. */
    private static final int    CROSSOVER_BARS       = 120;

    /**
     * Bars loaded when the export runs as a trade log rather than a snapshot.
     *
     * <p>Every trade has to start at a real historical session, so the window must reach the
     * oldest base-price date in the tracker - uploads currently run back to 2026-01-02, about
     * 160 sessions. 260 covers a full year with room to spare; the store itself only holds
     * from 2025-08-01, so asking for more simply returns what exists.
     */
    private static final int    CROSSOVER_LOG_BARS   = 260;

    /**
     * ATR Base Price Crossover export.
     *
     * <p>The entry screen validated against the 5-minute candle history in August 2026. Two
     * tests decide the list; a third de-duplicates it:
     *
     * <ol>
     *   <li><b>Volatility</b> - ATR14(D-1) / Close(D-1) x 100 &gt;= 3.0%. The only filter in
     *       the original four-test screen that survived out-of-sample testing: stocks clearing
     *       it returned +3.04 percentage points more per trade than those that did not
     *       (t = +4.47), and the gap held in the untouched out-of-sample window.</li>
     *   <li><b>Trigger</b> - Close(D-1) &lt; Base AND High(D) &gt;= Base. Price closed the
     *       previous session under the base price and has traded up through it today, so a
     *       buy-stop resting at the base price would have filled.</li>
     *   <li><b>First trigger only</b> - the base price must not have been reached in any
     *       completed session between the upload date and D-1. One entry per base-price setup;
     *       a stock that keeps dipping under base and popping back is not a fresh signal.</li>
     * </ol>
     *
     * <p><b>Two tests were removed from the earlier version of this screen.</b> The volume
     * filter (current volume &gt; 20-day average) is gone: at 1.5x the average it was the worst
     * of every filter tested, raising the stop-loss rate from 19.0% to 24.5%, and its verdict
     * reversed between the 1.0x and 1.5x thresholds - a result that flips on a small parameter
     * change has not been established. Volume is still reported in the output, just not used to
     * exclude rows. The separate "Current Price &gt;= Base Price" test is also gone: it duplicated
     * the trigger test in a weaker form, and 45.8% of intraday touches of the base price close
     * back below it, so gating on the live price silently drops trades a buy-stop would already
     * have taken. "Still Above Base" reports that state instead of filtering on it.
     *
     * <p>ADX was tested and deliberately excluded. ADX &lt; 20 cost 0.52 percentage points per
     * trade (t = -0.87) and inverted out-of-sample, where ADX &gt;= 20 scored a profit factor of
     * 2.44 against 1.73. Adding it back would cut the list roughly in half for no gain.
     *
     * <p>ATR is computed with the same engine the rest of the application uses, on bars up to
     * and including D-1, so the number here matches what the indicator screens report.
     *
     * <p><b>The exit levels in the output are part of the rule, not a suggestion.</b> The
     * screen is only meaningful paired with them: stop -7%, target +15%, and a hard exit at the
     * close of the 15th session. A -5% stop sits roughly 1.2 ATR from entry for these names -
     * inside one day's normal range - and pushed the out-of-sample stop-loss rate to 46.9%.
     *
     * <p>One caveat worth passing on to whoever trades this: in testing, waiting for the
     * crossover did not beat simply buying each stock the day after it was added to the
     * tracker (+0.01% per trade, t = +0.04). The screen is a disciplined, backtested way to
     * stagger entries. It has not been shown to select better ones.
     */
    @SuppressWarnings("unchecked")
    public byte[] exportAtrBaseCrossoverCsv(Long userId, Long institutionId, double minAtrPct) {
        return exportAtrBaseCrossoverCsv(userId, institutionId, minAtrPct, true, false);
    }

    /**
     * @param requireVolume    apply "Current Volume &gt; 20-Day Average Volume" as a filter rather
     *                         than only reporting it. On by default: it is one of the four stated
     *                         rules for this screen.
     * @param firstTriggerOnly drop a setup whose base price was already reached in an earlier
     *                         session. Off by default. It is a useful refinement - a level that
     *                         has been tested repeatedly is not the same setup as one crossing
     *                         for the first time - but it is a much stricter rule than the four
     *                         asked for, and on its own it emptied the export completely: all
     *                         eight stocks crossing on 27 Aug 2026 had touched their base before,
     *                         between 2 and 61 times.
     */
    @SuppressWarnings("unchecked")
    public byte[] exportAtrBaseCrossoverCsv(Long userId, Long institutionId, double minAtrPct,
                                            boolean requireVolume, boolean firstTriggerOnly) {
        List<Map<String, Object>> all = getAllTrackedStocksScoped(userId, institutionId, null, null,
            null, null, "symbol", "asc", 0, 50000, false);

        List<Map<String, Object>> priced = new ArrayList<>();
        for (Map<String, Object> st : all) {
            if (getD(st, "base_price") > 0) priced.add(st);
        }
        if (priced.isEmpty()) return atrCrossoverCsv(List.of(), minAtrPct);

        // 15 bars cover ATR14 and 20 the volume average, but the "no earlier touch since
        // upload" scan needs to walk back to the upload date, so load a wider window. Median
        // time from upload to first crossover is 2 sessions; 120 bars covers all but a handful.
        Map<String, List<CandleBar>> barsBySymbol = loadDailyBarsBatch(
            priced.stream().map(x -> String.valueOf(x.getOrDefault("symbol", ""))).toList(),
            CROSSOVER_LOG_BARS);

        // The date the backtest is being evaluated AS OF. Everything after it is unknowable, so
        // it is what separates a trade that ran out of time from one that is still open.
        String evaluationDate;
        try {
            evaluationDate = marketCalendar.lastCompletedTradingDay().toString();
        } catch (Exception e) {
            evaluationDate = DateUtil.today();
        }

        // Which price counts as "now".
        //
        // In a live session the broker quote is the truth. Outside one - after the close, at the
        // weekend, or on a holiday - it is at best the last session's close carrying today's
        // timestamp, and at worst days stale. Pricing off it then would report a crossover
        // against a number that was never traded on the session being judged.
        //
        // So when the market is shut the last COMPLETED session is used throughout: its close is
        // "current", its high is the day high, and the crossover is measured on that session.
        // A run on a holiday therefore reproduces the previous trading day's answer instead of
        // inventing a new one, and the same run repeated tomorrow gives the same result.
        boolean marketOpen = isMarketOpen();

        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> st : priced) {
            String symbol = String.valueOf(st.getOrDefault("symbol", ""));
            double base = getD(st, "base_price");
            double quotePrice = getD(st, "current_price");

            List<CandleBar> bars = barsBySymbol.getOrDefault(normalizeSymbol(symbol), List.of());
            if (bars.size() < 16) continue;                    // not enough history for ATR14

            // The base price has to have existed before session D, otherwise a buy-stop could
            // not have been resting at it. Requiring D-1 to be on or after the base price date
            // is the same "search starts the session after the base is set" rule the backtest
            // used. Without it a stock uploaded today lists immediately on today's own bar,
            // which is a level nobody could have placed an order at.
            String uploadDate = normalizeStoredDate(String.valueOf(st.getOrDefault("upload_date", "")));
            boolean hasUploadDate = uploadDate != null && !uploadDate.isBlank();

            // Whether the loaded window actually reaches back to the base price date. If it does
            // not, the earliest crossing found here might not be the earliest that happened, and
            // the First Trigger column says UNVERIFIED rather than claiming otherwise.
            String oldestLoaded = normalizeStoredDate(bars.get(bars.size() - 1).tradeDate);
            boolean historyReachesUpload = !hasUploadDate
                || (oldestLoaded != null && oldestLoaded.compareTo(uploadDate) <= 0);

            // Every session that could be a session D, oldest first.
            //
            // This used to look at exactly one session - the newest - which is why every row
            // carried the same entry date and no trade could ever have an outcome. A trade log
            // needs the crossing wherever it happened, so each stored session with a session
            // behind it is a candidate, and the live tick is appended as one more when the
            // market is open and today has no bar yet.
            List<Integer> sessionIdx = new ArrayList<>();
            for (int i = bars.size() - 2; i >= 0; i--) sessionIdx.add(i);
            if (marketOpen && !hasTodayBar(bars)) sessionIdx.add(-1);

            int tradesForSymbol = 0;
            for (int dIdx : sessionIdx) {
                int todayIdx = dIdx;
                int prevIdx  = dIdx + 1;
                if (bars.size() <= prevIdx + 15) continue;   // no room for ATR14 behind D-1

                String prevBarDate = normalizeStoredDate(bars.get(prevIdx).tradeDate);
                if (hasUploadDate && prevBarDate != null
                    && prevBarDate.compareTo(uploadDate) < 0) continue;

                double prevClose = bars.get(prevIdx).close;
                if (prevClose <= 0 || prevClose >= base) continue; // (1) Close(D-1) < Base Price

                // (2) the base price was actually traded through during session D.
                CandleBar todayBar = todayIdx >= 0 ? bars.get(todayIdx) : null;

                // A historical session is priced off its OWN bar. The live quote may only stand
                // in for a session still in progress - using it on a past session would date the
                // row to 2026-01 and price it at today, which is the corruption this whole change
                // exists to remove.
                boolean liveSession = marketOpen && todayIdx <= 0;
                double current = liveSession
                    ? (quotePrice > 0 ? quotePrice : (todayBar != null ? todayBar.close : 0))
                    : (todayBar != null ? todayBar.close : 0);
                if (current <= 0) continue;

                // A live session's high has to account for the tick that has just printed; a
                // closed one is already final, so the stored bar is taken as it stands.
                double todayHigh = todayBar != null
                    ? (liveSession ? Math.max(todayBar.high, current) : todayBar.high)
                    : current;
                double todayLow  = todayBar != null ? todayBar.low : current;
                if (todayHigh <= 0 || todayHigh < base) continue;

            // (3) ATR14 as a percentage of the D-1 close. The engine wants oldest-first series,
            // so the window from D-1 backwards is reversed before handing it over.
            List<Double> highs = new ArrayList<>(), lows = new ArrayList<>(), closes = new ArrayList<>();
            for (int i = prevIdx + 15; i >= prevIdx; i--) {
                CandleBar bar = bars.get(i);
                highs.add(bar.high); lows.add(bar.low); closes.add(bar.close);
            }
            double atr14 = indicator.atr(highs, lows, closes, 14);
            if (atr14 <= 0) continue;
            double atrPct = Math.round((atr14 / prevClose) * 10000.0) / 100.0;
            if (atrPct < minAtrPct) continue;

            // (4) first trigger only. The session loop already runs oldest-first, so the
            // first crossing it reaches IS the first trigger - no backward scan needed. All that
            // is left is to say so honestly: only the first row emitted for a symbol is the
            // first trigger, and only if the loaded window reached the base price date.

            // A buy-stop at the base price fills at the first trade at or above it, which can
            // never be below the day's low. When the stock gapped clean over base the fill is
            // worse than the level - 39% of backtested entries did exactly this - so the exit
            // levels are derived from the realistic fill, not from the base price.
            boolean gappedAboveBase = todayLow > base;
            double entryRef = Math.max(base, todayLow);

            // The 20-day average starts at D-1 so session D never counts towards the average it
            // is being measured against.
            double quoteVol = getD(st, "volume");
            double avg20Vol = bars.size() > prevIdx + 1
                ? sameSourceAverageVolume(bars, prevIdx, Math.min(20, bars.size() - prevIdx),
                                          todayBar != null ? todayBar : bars.get(0),
                                          MIN_SAME_SOURCE_VOL_BARS) : 0;
            double todayVol = todayBar != null ? todayBar.volume : quoteVol;
            // "Cannot be measured" is not "failed".
            //
            // sameSourceAverageVolume returns 0 when there are too few bars from the same feed to
            // average - and right now that is the normal case, because Dhan daily bars only cover
            // 24-27 Aug, three usable bars against a minimum of five. Treating that 0 as a failed
            // test silently emptied this export: PENIND and FIVESTAR had been listed and vanished
            // the moment the same-source rule went in, not because their volume fell but because
            // it stopped being comparable.
            //
            // So the filter only bites when the comparison is real. An unmeasurable stock stays in
            // and is labelled, which is the honest outcome: the reader can see the test did not run
            // rather than being shown a shorter list with no explanation.
            boolean volMeasurable = todayVol > 0 && avg20Vol > 0;
            if (requireVolume && volMeasurable && todayVol <= avg20Vol) continue;

            Map<String, Object> row = new LinkedHashMap<>(st);
            row.put("previous_close", round2(prevClose));
            row.put("today_high", round2(todayHigh));
            row.put("today_low", round2(todayLow));
            // The row is a copy of the tracked_stocks record, so current_price is the stored
            // quote. Overwrite it with the price this row was actually judged on - otherwise the
            // export prints one number while the decision used another.
            row.put("current_price", round2(current));
            row.put("still_above_base", current >= base ? "YES" : "NO");
            row.put("price_basis", marketOpen ? "Live (market open)"
                : "Close of " + (todayBar != null ? todayBar.tradeDate : "last session"));
            row.put("gapped_above_base", gappedAboveBase ? "YES" : "NO");
            row.put("entry_ref", round2(entryRef));
            row.put("stop_loss", round2(entryRef * (1 - CROSSOVER_STOP_PCT / 100.0)));
            row.put("target", round2(entryRef * (1 + CROSSOVER_TARGET_PCT / 100.0)));
            row.put("max_hold_sessions", CROSSOVER_MAX_HOLD);

            // Entry Date is session D: the session in which the base price was traded
            // through. It is the stored bar's own date - never today's - so a trade that fired
            // in January stays dated to January however long afterwards the file is generated.
            String entryDate = todayBar != null ? normalizeStoredDate(todayBar.tradeDate)
                                                : DateUtil.today();
            row.put("entry_date", entryDate);

            resolveCrossoverTrade(row, bars, dIdx, entryRef,
                                  round2(entryRef * (1 - CROSSOVER_STOP_PCT / 100.0)),
                                  round2(entryRef * (1 + CROSSOVER_TARGET_PCT / 100.0)),
                                  entryDate, evaluationDate);
            row.put("first_trigger", tradesForSymbol > 0 ? "NO"
                                   : (historyReachesUpload ? "YES" : "UNVERIFIED"));
            row.put("atr14", round2(atr14));
            row.put("atr_pct", atrPct);
            row.put("current_volume", round2(todayVol));
            row.put("avg_20d_volume", round2(avg20Vol));
            row.put("vol_ratio", volMeasurable ? Math.round((todayVol / avg20Vol) * 100.0) / 100.0 : null);
            row.put("vol_tested", volMeasurable ? (todayVol > avg20Vol ? "Yes" : "No") : "n/a");
            row.put("gain_from_base_pct", Math.round(((current - base) / base) * 10000.0) / 100.0);

            hits.add(row);
            tradesForSymbol++;
            if (firstTriggerOnly) break;   // oldest-first, so the first hit IS the first trigger
            }
        }

        // Daily bars gave the session each trade entered and left on. This adds the clock
        // time, and settles any session that touched both levels.
        fillCrossoverTradeTimes(hits);
        for (Map<String, Object> r : hits) r.put("validation", validateCrossoverRow(r));

        // A log reads newest trade first; ties break on volatility, which is the filter the
        // screen is built around.
        hits.sort((a, b) -> {
            int byDate = String.valueOf(b.getOrDefault("entry_date", ""))
                   .compareTo(String.valueOf(a.getOrDefault("entry_date", "")));
            if (byDate != 0) return byDate;
            return Double.compare(getD(b, "atr_pct"), getD(a, "atr_pct"));
        });
        return atrCrossoverCsv(hits, minAtrPct);
    }

    /**
     * Work out what actually happened to one crossover trade, and when.
     *
     * <p>The outcome is never inferred from the exit date - it is found by replaying the
     * sessions after entry and taking the FIRST thing that occurred:
     *
     * <pre>
     *   stop hit first        -&gt; STOP LOSS         exit on that session, at the stop
     *   target hit first      -&gt; PROFIT BOOK       exit on that session, at the target
     *   neither, hold expires -&gt; DEFINED DATE EXIT exit on the deadline, at its close
     *   deadline still ahead  -&gt; OPEN              no exit date at all
     * </pre>
     *
     * <p>A session whose range covers both levels cannot be ordered from a daily bar - the low
     * and the high carry no time. It is provisionally called a stop, which is the conservative
     * reading, and flagged so the intraday pass can settle it from 5-minute candles.
     *
     * <p>OPEN is decided against the evaluation date, not against today's clock: a trade whose
     * deadline has not yet arrived has no outcome to report, and inventing one by closing it at
     * the last available bar would turn "not finished" into a fabricated result.
     */
    private void resolveCrossoverTrade(Map<String, Object> row, List<CandleBar> bars, int dIdx,
                                       double entryRef, double stopPx, double targetPx,
                                       String entryDate, String evaluationDate) {
        String definedExit = plusTradingDays(entryDate, CROSSOVER_MAX_HOLD);
        row.put("defined_exit_date", definedExit);

        String status = null, exitDate = null;
        Double exitPrice = null;
        boolean sameBarBoth = false;
        CandleBar deadlineBar = null;

        // bars are newest-first, so counting the index DOWN walks forward in time from entry.
        for (int i = dIdx - 1; i >= 0; i--) {
            CandleBar bar = bars.get(i);
            String bd = normalizeStoredDate(bar.tradeDate);
            if (bd == null) continue;
            if (definedExit != null && bd.compareTo(definedExit) > 0) break;

            deadlineBar = bar;                       // newest session still inside the hold
            boolean hitStop   = bar.low > 0 && bar.low <= stopPx;
            boolean hitTarget = bar.high >= targetPx;
            if (!hitStop && !hitTarget) continue;

            sameBarBoth = hitStop && hitTarget;
            status    = hitStop ? "STOP LOSS" : "PROFIT BOOK";
            exitDate  = bd;
            exitPrice = hitStop ? stopPx : targetPx;
            break;
        }

        // Ran the full hold without touching either level. Only call that an exit once the
        // deadline is actually in the past - otherwise the trade is simply still running.
        if (status == null && definedExit != null && deadlineBar != null
            && definedExit.compareTo(evaluationDate) <= 0) {
            status    = "DEFINED DATE EXIT";
            exitDate  = normalizeStoredDate(deadlineBar.tradeDate);
            exitPrice = round2(deadlineBar.close);
        }
        if (status == null) status = "OPEN";

        row.put("trade_status", status);
        row.put("exit_date", exitDate);
        row.put("exit_price", exitPrice);
        row.put("same_bar_both", sameBarBoth);

        // These two columns now carry the date the level was actually reached, and stay blank
        // when it never was. They previously held the hold deadline, which is what
        // defined_exit_date is for.
        row.put("stop_loss_date",  "STOP LOSS".equals(status)   ? exitDate : null);
        row.put("target_date",     "PROFIT BOOK".equals(status) ? exitDate : null);

        if (exitPrice != null && entryRef > 0) {
            row.put("return_pct", round2((exitPrice - entryRef) / entryRef * 100.0));
        }
    }

    /**
     * Put a clock time on each trade's entry and exit, from 5-minute candles.
     *
     * <p>Daily bars answer WHICH session a level was reached on but never WHEN, and they cannot
     * order two levels reached on the same session at all - a daily low and high carry no time
     * between them. This resolves both from the intraday feed, which holds 5-minute candles for
     * every session back to 2025-08-01.
     *
     * <p>Where a session touched the stop and the target, whichever candle came first decides
     * the trade, and the status, exit price and hit-date columns are rewritten to match. A
     * single candle spanning both stays a stop - inside 5 minutes there is nothing left to
     * order it by, and assuming the good outcome would flatter every such trade.
     *
     * <p>Missing intraday data costs the time only. The date and status already stand on their
     * own from the daily bars, so a blank Entry Time means "not recorded", never "no entry".
     */
    private void fillCrossoverTradeTimes(List<Map<String, Object>> rows) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            String sym = normalizeSymbol(String.valueOf(r.getOrDefault("symbol", "")));
            if (sym.isBlank()) continue;
            String entry = textOrNull(r.get("entry_date"));
            String exit  = textOrNull(r.get("exit_date"));
            if (entry != null) keys.add(sym + "|" + entry);
            if (exit  != null) keys.add(sym + "|" + exit);
        }
        if (keys.isEmpty()) return;

        Map<String, List<Object[]>> ticks = loadIntradaySessions(keys);
        if (ticks.isEmpty()) return;

        for (Map<String, Object> r : rows) {
            String sym = normalizeSymbol(String.valueOf(r.getOrDefault("symbol", "")));
            if (sym.isBlank()) continue;
            String entryDate = textOrNull(r.get("entry_date"));
            String exitDate  = textOrNull(r.get("exit_date"));
            double base      = getD(r, "base_price");
            double entryRef  = getD(r, "entry_ref");
            double stopPx    = getD(r, "stop_loss");
            double targetPx  = getD(r, "target");

            // Entry fills at the first candle that trades through the base price - that is when
            // a resting buy-stop would have been taken.
            if (entryDate != null) {
                for (Object[] c : ticks.getOrDefault(sym + "|" + entryDate, List.of())) {
                    if (getNumber(c[1]) >= base) { r.put("entry_time", clockOf(c[0])); break; }
                }
            }
            if (exitDate == null) continue;
            List<Object[]> day = ticks.getOrDefault(sym + "|" + exitDate, List.of());
            if (day.isEmpty()) continue;

            if ("DEFINED DATE EXIT".equals(String.valueOf(r.get("trade_status")))) {
                // Out at the close of the deadline session, so the last candle is the exit -
                // and its close is a truer exit price than the daily bar's.
                Object[] last = day.get(day.size() - 1);
                r.put("exit_time", clockOf(last[0]));
                r.put("exit_price", round2(getNumber(last[3])));
            } else {
                int stopAt = -1, targetAt = -1;
                for (int i = 0; i < day.size(); i++) {
                    double hi = getNumber(day.get(i)[1]), lo = getNumber(day.get(i)[2]);
                    if (stopAt   < 0 && lo > 0 && lo <= stopPx)  stopAt = i;
                    if (targetAt < 0 && hi >= targetPx)          targetAt = i;
                    if (stopAt >= 0 && targetAt >= 0) break;
                }
                int at = -1;
                if (stopAt >= 0 && (targetAt < 0 || stopAt <= targetAt)) {
                    at = stopAt;
                    r.put("trade_status", "STOP LOSS");
                    r.put("exit_price", round2(stopPx));
                    r.put("stop_loss_date", exitDate);
                    r.put("target_date", null);
                } else if (targetAt >= 0) {
                    at = targetAt;
                    r.put("trade_status", "PROFIT BOOK");
                    r.put("exit_price", round2(targetPx));
                    r.put("target_date", exitDate);
                    r.put("stop_loss_date", null);
                }
                if (at >= 0) r.put("exit_time", clockOf(day.get(at)[0]));
            }

            double exitPx = getD(r, "exit_price");
            if (exitPx > 0 && entryRef > 0) {
                r.put("return_pct", round2((exitPx - entryRef) / entryRef * 100.0));
            }
        }
    }

    /**
     * 5-minute candles for a set of (symbol, trade_date) pairs, keyed {@code SYMBOL|yyyy-MM-dd}
     * and ordered by time within each session.
     *
     * <p>Matched on the raw {@code symbol} column rather than {@code UPPER(symbol)} so that
     * idx_bmc_symbol_date can be used. That is not a cosmetic choice: over 2,000 pairs the
     * UPPER() form takes 107 seconds because it cannot use the index, against 0.6 seconds this
     * way. The 29 symbols stored in mixed case are all NSE indices and have no intraday rows at
     * all, so nothing is missed by matching exactly.
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<Object[]>> loadIntradaySessions(Collection<String> keys) {
        Map<String, List<Object[]>> out = new HashMap<>();
        List<String> list = new ArrayList<>(keys);
        int batch = 1000;
        for (int start = 0; start < list.size(); start += batch) {
            List<String> chunk = list.subList(start, Math.min(start + batch, list.size()));
            StringBuilder vals = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) vals.append(",");
                // The first row carries the casts; without them Postgres cannot infer a type
                // for the VALUES columns and refuses the join.
                vals.append(i == 0 ? "(CAST(? AS text),CAST(? AS text))" : "(?,?)");
            }
            try {
                var query = em.createNativeQuery(
                    "SELECT c.symbol,c.trade_date,c.candle_time,c.high,c.low,c.close " +
                    "FROM broker_market_candles c " +
                    "JOIN (VALUES " + vals + ") AS w(sym,td) " +
                    "  ON c.symbol=w.sym AND c.trade_date=w.td " +
                    "WHERE UPPER(COALESCE(c.candle_mode,''))='INTRADAY' " +
                    "ORDER BY c.symbol,c.trade_date,c.candle_time");
                int p = 1;
                for (String k : chunk) {
                    int bar = k.indexOf('|');
                    query.setParameter(p++, k.substring(0, bar));
                    query.setParameter(p++, k.substring(bar + 1));
                }
                for (Object[] r : (List<Object[]>) query.getResultList()) {
                    out.computeIfAbsent(asText(r[0]) + "|" + asText(r[1]), k -> new ArrayList<>())
                       .add(new Object[]{ asText(r[2]), getNumber(r[3]),
                                          getNumber(r[4]), getNumber(r[5]) });
                }
            } catch (Exception e) {
                log.debug("Intraday session ticks unavailable for {} pair(s): {}",
                          chunk.size(), e.getMessage());
            }
        }
        return out;
    }

    /** HH:mm:ss out of a stored {@code yyyy-MM-ddTHH:mm:ss} candle timestamp. */
    private String clockOf(Object candleTime) {
        String s = candleTime == null ? "" : String.valueOf(candleTime);
        int at = s.indexOf('T');
        return at >= 0 && s.length() > at + 1 ? s.substring(at + 1) : null;
    }

    private String textOrNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Check a finished row against the rules it claims to follow, so a bad one is visible in the
     * file instead of being read as fact. Returns OK, or every problem found.
     */
    private String validateCrossoverRow(Map<String, Object> r) {
        String status = String.valueOf(r.getOrDefault("trade_status", ""));
        String entry  = textOrNull(r.get("entry_date"));
        String exit   = textOrNull(r.get("exit_date"));
        String defined = textOrNull(r.get("defined_exit_date"));
        List<String> problems = new ArrayList<>();

        if (entry == null) problems.add("no entry date");
        if ("OPEN".equals(status)) {
            if (exit != null) problems.add("OPEN but has an exit date");
        } else {
            if (exit == null) problems.add("closed but has no exit date");
            else if (entry != null && exit.compareTo(entry) <= 0) problems.add("exit not after entry");
        }
        if ("STOP LOSS".equals(status)   && textOrNull(r.get("stop_loss_date")) == null)
            problems.add("STOP LOSS without a stop date");
        if ("PROFIT BOOK".equals(status) && textOrNull(r.get("target_date")) == null)
            problems.add("PROFIT BOOK without a target date");
        // Holidays can leave the deadline itself untraded, so the exit lands on the last session
        // inside the hold. Earlier is fine; later means the hold was overrun.
        if (exit != null && defined != null && exit.compareTo(defined) > 0)
            problems.add("exit past the defined exit date");

        return problems.isEmpty() ? "OK" : String.join("; ", problems);
    }

    /**
     * The RSI 45 section's stocks, as CSV laid out like the ATR Base Price Crossover file.
     *
     * <p>Runs the SAME call the panel runs - identical parameters, no separate filter - so the
     * file can never list a stock the screen does not, which is the failure mode of an export
     * that reimplements its own selection. The button previously pointed at the chart-trades
     * export, a different dataset built from ranked bar-chart days, which is why it appeared to
     * do nothing useful here.
     */
    public byte[] exportRsi45SectionCsv(Long userId, Long institutionId, Double maxPrice,
                                        boolean requireCrossing, boolean requireVolume,
                                        boolean applyRanking) {
        Map<String, Object> panel = getBelowPriceTop10(userId, institutionId, maxPrice,
                                                       requireCrossing, requireVolume, applyRanking);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
            (List<Map<String, Object>>) panel.getOrDefault("top10", List.of());

        String generatedAt = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder csv = new StringBuilder();
        csv.append("\ufeff");
        csv.append("TradeRakshak(TM) - RSI 45 Previous Close Less the Base Price\n");
        csv.append("ENTRY\n");
        csv.append("1. Previous Close < Base Price AND Current Price >= Base Price (the crossing)\n");
        csv.append("2. Volume > 20-Day Average Volume: ")
           .append(requireVolume ? "applied as a filter" : "reported only, does not exclude")
           .append("\n");
        csv.append("3. Selection: ")
           .append(applyRanking ? "ranked, best ten kept"
                                : "every qualifying stock, strongest move first - no ranking")
           .append("\n");
        csv.append("NOTES\n");
        csv.append("Identification is settled on the last completed session's close, because the "
                 + "entry test needs a full day's volume. Prices then track the market while it "
                 + "is open, so Current Price can move after the list is fixed.\n");
        csv.append("ATR14 and ATR % are measured on the sessions behind D-1, the same way the ATR "
                 + "Base Price Crossover screen measures them.\n");
        csv.append("As of: ").append(safe(panel.get("as_of")))
           .append(",Scope: ").append(csvQuote(panel.get("scope")))
           .append(",Total: ").append(rows.size()).append("\n");
        csv.append("Pipeline: ").append(csvQuote(panel.get("pipeline"))).append("\n");
        csv.append("DISCLAIMER: For educational purposes only. Not SEBI investment advice.\n\n");

        csv.append("Rank,Symbol,Sector,Base Price,Base Price Date,Previous Close,Current Price,"
                 + "Price Change %,Return %,ATR14 (D-1),ATR %,Current Volume,20D Avg Volume,"
                 + "Volume Above 20D Avg,Generated Date & Time\n");

        if (rows.isEmpty()) {
            csv.append("No stock met the entry test on the last completed session. That is a "
                     + "normal outcome, not a fault - the crossing has to happen on the day.\n");
        }
        int rank = 0;
        for (Map<String, Object> r : rows) {
            double vol = getD(r, "volume"), avg = getD(r, "avg_20d_volume");
            csv.append(++rank).append(",")
               .append(safe(r.get("symbol"))).append(",")
               .append(csvQuote(r.get("sector"))).append(",")
               .append(fmtNum(r.get("base_price"))).append(",")
               .append(safe(r.get("upload_date"))).append(",")
               .append(fmtNum(r.get("previous_close"))).append(",")
               .append(fmtNum(r.get("current_price"))).append(",")
               .append(fmtNum(r.get("price_change_pct"))).append(",")
               .append(fmtNum(r.get("return_pct"))).append(",")
               .append(fmtNum(r.get("atr14"))).append(",")
               .append(fmtNum(r.get("atr_pct"))).append(",")
               .append(fmtNum(r.get("volume"))).append(",")
               .append(fmtNum(r.get("avg_20d_volume"))).append(",")
               // n/a, not No: with no comparable history the test did not run.
               .append(vol > 0 && avg > 0 ? (vol > avg ? "Yes" : "No") : "n/a").append(",")
               .append(generatedAt).append("\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The Previous Top 10 section as CSV - the columns on screen, for any past date.
     *
     * <p>Built from the SAME preview call the table renders from, so a figure in the file can
     * never disagree with the figure above it. Passing a date replays that session instead of
     * the latest, which is what makes back-dated rows possible: the trade is rebuilt from stored
     * candles at this section's own times rather than read from a saved batch, so a day that was
     * never traded live still produces a row.
     */
    @SuppressWarnings("unchecked")
    public byte[] exportPrevTop10Csv(Long userId, Long institutionId, String runDate) {
        // The very call the table renders from. A date replays that session; no date is the
        // section's default view. Reading the same payload is what guarantees the file and the
        // screen cannot disagree.
        boolean backDated = runDate != null && !runDate.isBlank();
        Map<String, Object> preview = getTop10Preview(userId, institutionId,
            backDated ? "CUSTOM" : "1D", backDated ? runDate : null, getPrevTop10BuyTime(),
            "1D", null, getPrevTop10SellTime());
        // "stocks" is the key getTop10Preview actually returns. Reading "details" meant this
        // export always wrote a header, a summary line taken from the same payload - Winners: 1,
        // Losers: 9 - and then "No stocks for this date", which is self-contradictory on its face.
        List<Map<String, Object>> rows =
            (List<Map<String, Object>>) preview.getOrDefault("stocks", List.of());

        String generatedAt = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder csv = new StringBuilder();
        csv.append("\ufeff");
        csv.append("TradeRakshak(TM) - Previous Top 10 Ranking Stocks\n");
        csv.append("RULE\n");
        csv.append(csvQuote(preview.getOrDefault("selection_basis", preview.get("rule"))))
           .append("\n");
        csv.append("Weekends and market holidays are skipped - the sell leg is the next session "
                 + "that actually traded, so a Friday buy sells on Monday.\n");
        csv.append("A row still OPEN is valued at the live price, because its sell has not "
                 + "happened yet - Return % then measures buy against the current price.\n");
        csv.append("Run date: ").append(safe(preview.get("run_date")))
           .append(",Average Return: ").append(fmtNum(preview.get("avg_return")))
           .append("%,Winners: ").append(safe(preview.get("winners")))
           .append(",Losers: ").append(safe(preview.get("losers")))
           .append(",Stocks: ").append(rows.size()).append("\n");
        csv.append("DISCLAIMER: For educational purposes only. Not SEBI investment advice.\n\n");

        csv.append("Rank,Symbol,Buy Date,Buy Time,Buy Price,Sell Date,Sell Time,Sell Price,"
                 + "Return %,Status,Price Source,Generated Date & Time\n");

        if (rows.isEmpty()) {
            csv.append("No stocks for this date. Either nothing was ranked that session, or no "
                     + "stored candles cover it.\n");
        }
        int rank = 0;
        String buyDay  = String.valueOf(preview.getOrDefault("run_date", ""));
        String sellDay = String.valueOf(preview.getOrDefault("sell_date", ""));
        for (Map<String, Object> r : rows) {
            // Prefer the row's own dates - after the sell leg moved to the next trading day the
            // two legs sit on different sessions, and a single top-level pair cannot say that.
            Object rBuyDay  = r.getOrDefault("buy_date", buyDay);
            Object rSellDay = r.getOrDefault("sell_date", sellDay);
            csv.append(++rank).append(",")
               .append(safe(r.get("symbol"))).append(",")
               .append(safe(rBuyDay)).append(",")
               .append(safe(r.get("buy_time"))).append(",")
               .append(fmtNum(r.get("buy_price"))).append(",")
               .append(safe(rSellDay)).append(",")
               .append(safe(r.get("sell_time"))).append(",")
               .append(fmtNum(r.get("sell_price"))).append(",")
               .append(fmtNum(r.get("return_pct"))).append(",")
               .append(safe(r.getOrDefault("status",
                    Boolean.FALSE.equals(r.get("price_available")) ? "NO PRICE" : "-"))).append(",")
               .append(safe(r.getOrDefault("sell_note", r.get("source")))).append(",")
               .append(generatedAt).append("\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] atrCrossoverCsv(List<Map<String, Object>> rows, double minAtrPct) {
        String generatedAt = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder csv = new StringBuilder();
        csv.append("﻿");
        csv.append("TradeRakshak™ — ATR Base Price Crossover\n");
        csv.append("ENTRY\n");
        csv.append("1. Volatility: ATR14(D-1) / Close(D-1) x 100 >= ").append(minAtrPct).append("%\n");
        csv.append("2. Trigger: Close(D-1) < Base Price AND High(D) >= Base Price\n");
        csv.append("3. First trigger only: base price not reached in any session since the base price date\n");
        csv.append("4. Fill: buy-stop at Base Price - shown as Entry, which allows for a gap through the level\n");
        csv.append("EXIT\n");
        csv.append("5. Stop Loss: Entry -").append(fmtPct(CROSSOVER_STOP_PCT))
           .append("%,Target: Entry +").append(fmtPct(CROSSOVER_TARGET_PCT))
           .append("%,Max Hold: ").append(CROSSOVER_MAX_HOLD).append(" sessions then exit at close\n");
        csv.append("6. First level touched wins. If both are reached in the same candle, treat it as the stop.\n");
        csv.append("7. This file is a TRADE LOG, not a list of today's signals: one row per "
                 + "crossing found anywhere in the stored history, each carrying its own entry "
                 + "session and its own outcome. Every price on a row is that session's price.\n");
        csv.append("8. Stop Loss Date and Target Date are the dates those levels were actually "
                 + "REACHED, and stay blank when they never were. The end of the hold is Defined "
                 + "Exit Date.\n");
        csv.append("9. Trade Status is decided by what happened FIRST after entry, never by the "
                 + "exit date: STOP LOSS, PROFIT BOOK, DEFINED DATE EXIT, or OPEN while the "
                 + "defined exit date is still ahead of the evaluation date.\n");
        csv.append("10. Times come from 5-minute candles. A session that touched both levels is "
                 + "settled by whichever candle came first; one candle holding both counts as the "
                 + "stop. A blank time means no intraday data, not no entry.\n");
        csv.append("11. Validation reports any row that contradicts these rules. Anything other "
                 + "than OK should not be traded on.\n");
        csv.append("NOTES\n");
        csv.append("Price basis: during market hours the live quote is used. Outside a session - "
                 + "evening, weekend or holiday - the last completed session's close and high "
                 + "are used instead, so a run on a holiday reproduces the previous trading day.\n");
        csv.append("Volume is reported but NOT filtered on - the volume filter failed out-of-sample testing.\n");
        csv.append("ADX is deliberately not used - ADX < 20 inverted out-of-sample.\n");
        csv.append("Still Above Base = NO means price has fallen back under base since the trigger; "
                 + "a resting buy-stop would already have filled.\n");
        csv.append("Generated: ").append(generatedAt).append(",Total: ").append(rows.size()).append("\n");
        csv.append("DISCLAIMER: For educational purposes only. Not SEBI investment advice.\n\n");
        csv.append("Symbol,Base Price,Base Price Date,Entry Date,Previous Close,Day High,Day Low,"
                 + "Current Price,Still Above Base,Gapped Above Base,Entry,"
                 + "Stop Loss,Stop Loss Date,Target,Target Date,"
                 + "Max Hold (Sessions),First Trigger,Gain From Base %,ATR14 (D-1),ATR %,"
                 + "Current Volume,20D Avg Volume,Volume Ratio,Price Basis,Last Updated,Generated Date & Time,"
                 + "Entry Time,Defined Exit Date,Exit Date,Exit Time,Exit Price,Trade Status,Return %,Validation\n");
        if (rows.isEmpty()) {
            // An empty list is the normal case, not a fault. Each base price fires once, so the
            // screen only lists setups crossing for the first time today - which over the tested
            // history happened on about one session in five, in bursts a day or two after each
            // batch of uploads. Saying so here saves the reader assuming the export is broken.
            csv.append("No crossings found in the stored history for the uploaded stocks. "
                     + "Check that daily bars exist for them and that base prices are set.\n");
        }
        for (Map<String, Object> r : rows) {
            csv.append(safe(r.get("symbol"))).append(",")
               .append(fmtNum(r.get("base_price"))).append(",")
               .append(safe(r.get("upload_date"))).append(",")
               .append(safe(r.get("entry_date"))).append(",")
               .append(fmtNum(r.get("previous_close"))).append(",")
               .append(fmtNum(r.get("today_high"))).append(",")
               .append(fmtNum(r.get("today_low"))).append(",")
               .append(fmtNum(r.get("current_price"))).append(",")
               .append(safe(r.get("still_above_base"))).append(",")
               .append(safe(r.get("gapped_above_base"))).append(",")
               .append(fmtNum(r.get("entry_ref"))).append(",")
               .append(fmtNum(r.get("stop_loss"))).append(",")
               .append(safe(r.get("stop_loss_date"))).append(",")
               .append(fmtNum(r.get("target"))).append(",")
               .append(safe(r.get("target_date"))).append(",")
               .append(safe(r.get("max_hold_sessions"))).append(",")
               .append(safe(r.get("first_trigger"))).append(",")
               .append(fmtNum(r.get("gain_from_base_pct"))).append(",")
               .append(fmtNum(r.get("atr14"))).append(",")
               .append(fmtNum(r.get("atr_pct"))).append(",")
               .append(fmtNum(r.get("current_volume"))).append(",")
               .append(fmtNum(r.get("avg_20d_volume"))).append(",")
               .append(fmtNum(r.get("vol_ratio"))).append(",")
               .append(csvQuote(r.get("price_basis"))).append(",")
               .append(safe(r.get("last_refreshed"))).append(",")
               .append(generatedAt).append(",")
               .append(safe(r.get("entry_time"))).append(",")
               .append(safe(r.get("defined_exit_date"))).append(",")
               .append(safe(r.get("exit_date"))).append(",")
               .append(safe(r.get("exit_time"))).append(",")
               .append(fmtNum(r.get("exit_price"))).append(",")
               .append(safe(r.get("trade_status"))).append(",")
               .append(fmtNum(r.get("return_pct"))).append(",")
               .append(csvQuote(r.get("validation"))).append("\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Percentages in the header lines read better without a trailing ".0". */
    private String fmtPct(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * Current Top-10 Ranking CSV Export.
     * Columns match the on-screen table exactly:
     * Rank, Symbol, Sector, Market Cap, Score, Upload Date,
     * Base Price, Current Price, Return %, Days Since Upload
     */
    public byte[] exportRankingCsv(Long institutionId) {
        return exportRankingCsv(null, institutionId);
    }

    public byte[] exportRankingCsv(Long userId, Long institutionId) {
        Map<String, Object> ranking = getLatestRankingFull(userId, institutionId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top10 = (List<Map<String, Object>>)
            ranking.getOrDefault("current_top10", List.of());

        StringBuilder csv = new StringBuilder();
        csv.append("\uFEFF");
        // Report header lines
        csv.append("TradeRakshak™ - Current Top 10 Ranking Report\n");
        csv.append("Date: ").append(DateUtil.today())
           .append(",Run ID: ").append(ranking.getOrDefault("run_id",""))
           .append(",Eligible Stocks: ").append(ranking.getOrDefault("eligible_count",""))
           .append("\n");
        csv.append("Current Top 10 Avg Return: ").append(fmtNum(ranking.get("curr_avg_return")))
           .append("%,Win Ratio: ").append(fmtNum(ranking.get("curr_win_ratio")))
           .append("%,Positive: ").append(ranking.getOrDefault("curr_positive",""))
           .append(",Negative: ").append(ranking.getOrDefault("curr_negative",""))
           .append("\n");
        csv.append("DISCLAIMER: For educational purposes only. Not SEBI investment advice.\n");
        csv.append("\n");
        // Column headers
        csv.append("Rank,Symbol,Sector,Market Cap,Score,Upload Date,")
           .append("Base Price (INR),Current Price (INR),Return %,Days Since Upload\n");

        for (Map<String, Object> s : top10) {
            csv.append(safe(s.get("rank"))).append(",")
               .append(safe(s.get("symbol"))).append(",")
               .append(csvQuote(s.get("sector"))).append(",")
               .append(csvQuote(s.get("market_cap"))).append(",")
               .append(fmtNum(s.get("score"))).append(",")
               .append(safe(s.get("upload_date"))).append(",")
               .append(fmtNum(s.get("base_price"))).append(",")
               .append(fmtNum(s.get("current_price"))).append(",")
               .append(fmtNum(s.get("return_pct"))).append(",")
               .append(safe(s.get("days_since_upload"))).append("\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Previous Top-10 Ranking CSV Export.
     * Columns match the Previous Top-10 on-screen table:
     * Rank, Symbol, Sector, Score (Prev Day), Prev Day Close Price,
     * Prev Day Return %, Current Price, Current Return %, Change in Return %, Days Since Upload
     */
    public byte[] exportPreviousRankingCsv(Long institutionId) {
        return exportPreviousRankingCsv(null, institutionId);
    }

    public byte[] exportPreviousRankingCsv(Long userId, Long institutionId) {
        Map<String, Object> ranking = getLatestRankingFull(userId, institutionId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> prevTop10 = (List<Map<String, Object>>)
            ranking.getOrDefault("previous_top10", List.of());

        StringBuilder csv = new StringBuilder();
        csv.append("\uFEFF");
        csv.append("TradeRakshak™ - Previous Top 10 Ranking Report\n");
        csv.append("Date: ").append(DateUtil.today())
           .append(",Previous Avg Return: ").append(fmtNum(ranking.get("prev_avg_return")))
           .append("%,Previous Win Ratio: ").append(fmtNum(ranking.get("prev_win_ratio")))
           .append("%\n");
        csv.append("DISCLAIMER: For educational purposes only. Not SEBI investment advice.\n");
        csv.append("\n");
        csv.append("Rank,Symbol,Sector,Base Price (INR),")
           .append("Current Price (INR),Return %,Gain/Loss (INR),Days Since Upload\n");

        int rank = 1;
        for (Map<String, Object> s : prevTop10) {
            double currRet  = s.get("current_return") != null ? getD(s, "current_return") : getD(s, "return_pct");
            double basePrice = getD(s, "base_price");
            double currPrice = getD(s, "current_price");
            double gainLoss = basePrice > 0 && currPrice > 0 ? currPrice - basePrice : 0;
            csv.append(rank++).append(",")
               .append(safe(s.get("symbol"))).append(",")
               .append(csvQuote(s.get("sector"))).append(",")
               .append(fmtNum(basePrice)).append(",")
               .append(fmtNum(currPrice)).append(",")
               .append(fmtNum(currRet)).append(",")
               .append((gainLoss >= 0 ? "+" : "")).append(fmtNum(gainLoss)).append(",")
               .append(safe(s.get("days_since_upload"))).append("\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Historical Ranking CSV Export — all stored ranking runs.
     * Columns: Date, Curr Avg Return, Prev Avg Return, Curr Win Ratio,
     *          Prev Win Ratio, Curr Positive, Prev Positive, Eligible Count
     */
    public byte[] exportHistoricalCsv(Long institutionId) {
        return exportHistoricalCsv(null, institutionId);
    }

    public byte[] exportHistoricalCsv(Long userId, Long institutionId) {
        List<Map<String, Object>> history = getRankingHistory(userId, institutionId, 365);

        StringBuilder csv = new StringBuilder();
        csv.append("\uFEFF");
        csv.append("TradeRakshak™ - Historical Ranking Report\n");
        csv.append("Generated: ").append(DateUtil.today()).append("\n");
        csv.append("DISCLAIMER: For educational purposes only. Not SEBI investment advice.\n");
        csv.append("\n");
        csv.append("Date,Current Top-10 Avg Return %,Previous Top-10 Avg Return %,")
           .append("Current Win Ratio %,Previous Win Ratio %,")
           .append("Current Positive Stocks,Previous Positive Stocks,Eligible Stocks\n");

        for (Map<String, Object> r : history) {
            csv.append(safe(r.get("date"))).append(",")
               .append(fmtNum(r.get("curr_avg_return"))).append(",")
               .append(fmtNum(r.get("prev_avg_return"))).append(",")
               .append(fmtNum(r.get("curr_win_ratio"))).append(",")
               .append(fmtNum(r.get("prev_win_ratio"))).append(",")
               .append(safe(r.get("curr_positive"))).append(",")
               .append(safe(r.get("prev_positive"))).append(",")
               .append(safe(r.get("eligible_count"))).append("\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Return Distribution CSV Export.
     */
    public byte[] exportDistributionCsv(Long institutionId) {
        return exportDistributionCsv(null, institutionId);
    }

    public byte[] exportDistributionCsv(Long userId, Long institutionId) {
        Map<String, Object> dist = getPerformanceDistribution(userId, institutionId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>)
            dist.getOrDefault("distribution", List.of());

        StringBuilder csv = new StringBuilder();
        csv.append("\uFEFF");
        csv.append("TradeRakshak™ - Return Distribution Report\n");
        csv.append("As of: ").append(dist.getOrDefault("as_of", DateUtil.today()))
           .append(",Total Stocks: ").append(dist.getOrDefault("total_stocks", 0)).append("\n");
        csv.append("DISCLAIMER: For educational purposes only. Not SEBI investment advice.\n");
        csv.append("\n");
        csv.append("Return Range,Stock Count,Percentage %\n");

        for (Map<String, Object> b : buckets) {
            csv.append(csvQuote(b.get("label"))).append(",")
               .append(safe(b.get("count"))).append(",")
               .append(fmtNum(b.get("pct"))).append("\n");
        }
        csv.append(",Total,").append(dist.getOrDefault("total_stocks", 0)).append(",100.00\n");
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── CSV helper: null-safe toString ───────────────────────────────────────
    private String safe(Object v) {
        if (v == null) return "";
        return v.toString().replace("\n","").replace("\r","");
    }

    // ── CSV helper: wrap in quotes if value contains comma ───────────────────
    private String csvQuote(Object v) {
        if (v == null) return "";
        String s = v.toString().replace("\"","\"\"\"");
        return s.contains(",") ? "\"" + s + "\"" : s;
    }

    // ── CSV helper: format Number to 2 decimal places, empty string for null ─
    private String fmtNum(Object v) {
        if (v == null) return "";
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return String.format("%.2f", d);
        }
        String s = v.toString().trim();
        try { return String.format("%.2f", Double.parseDouble(s)); }
        catch (NumberFormatException e) { return s; }
    }

    // ==========================================================================
    //  PRICE API INTEGRATION
    // ==========================================================================

    private double fetchDhanClosingPrice(DhanInstrumentRef ref, String date, DhanBrokerService.Credential credential) throws Exception {
        String normalizedDate = normalizeStoredDate(date);
        LocalDate uploadDate = LocalDate.parse(normalizedDate);
        try {
            List<CandleBar> candles = fetchDhanDailyCandles(ref, uploadDate, uploadDate, credential);
            for (CandleBar candle : candles) {
                if (normalizedDate.equals(candle.tradeDate) && candle.close > 0) return candle.close;
            }
        } catch (Exception e) {
            log.debug("Exact Dhan base close failed for {} on {}: {}", ref.symbol, normalizedDate, e.getMessage());
            if (isDhanAuthenticationError(e.getMessage())) throw e;
        }
        List<CandleBar> widerCandles = fetchDhanDailyCandles(ref, uploadDate.minusDays(10), uploadDate.plusDays(1), credential);
        return widerCandles.stream()
            .filter(c -> c.close > 0 && c.tradeDate.compareTo(normalizedDate) <= 0)
            .max(Comparator.comparing(c -> c.tradeDate))
            .map(c -> c.close)
            .orElse(0.0);
    }

    private double fetchDhanClosingPriceOnOrAfter(DhanInstrumentRef ref, String date, DhanBrokerService.Credential credential) throws Exception {
        String normalizedDate = normalizeStoredDate(date);
        LocalDate uploadDate = LocalDate.parse(normalizedDate);
        List<CandleBar> candles = fetchDhanDailyCandles(ref, uploadDate.minusDays(10), uploadDate.plusDays(10), credential);
        Optional<CandleBar> beforeOrSame = candles.stream()
            .filter(c -> c.close > 0 && c.tradeDate.compareTo(normalizedDate) <= 0)
            .max(Comparator.comparing(c -> c.tradeDate));
        if (beforeOrSame.isPresent()) return beforeOrSame.get().close;
        return candles.stream()
            .filter(c -> c.close > 0)
            .min(Comparator.comparing(c -> c.tradeDate))
            .map(c -> c.close)
            .orElse(0.0);
    }

    private void retrySelectedBasePricesWithWideWindow(List<String> symbols, Long institutionId, Long userId) {
        Optional<DhanBrokerService.Credential> credential = findVerifiedDhanCredential(userId, institutionId);
        if (credential.isEmpty()) return;
        Map<String, DhanInstrumentRef> refs = resolveDhanInstruments(symbols);
        for (String symbol : symbols) {
            try {
                String uploadDate = normalizeStoredDate(getUploadDate(symbol, institutionId));
                DhanInstrumentRef ref = refs.get(normalizeSymbol(symbol));
                if (uploadDate == null || ref == null) continue;
                double price = fetchDhanClosingPriceOnOrAfter(ref, uploadDate, credential.get());
                if (price <= 0) continue;
                runInTransaction(() -> em.createNativeQuery(
                    "UPDATE tracked_stocks SET base_price=?,return_pct=CASE WHEN current_price IS NOT NULL AND current_price>0 THEN ((current_price - ?) / ?)*100 ELSE 0 END," +
                    "base_price_source=?,last_refreshed=?,updated_at=? WHERE symbol=? AND institution_id=?")
                    .setParameter(1, price)
                    .setParameter(2, price)
                    .setParameter(3, price)
                    .setParameter(4, "DHAN_WIDE_CLOSE:" + uploadDate)
                    .setParameter(5, DateUtil.now())
                    .setParameter(6, DateUtil.now())
                    .setParameter(7, symbol)
                    .setParameter(8, institutionId)
                    .executeUpdate());
            } catch (Exception e) {
                log.debug("Wide Dhan base close failed for {}: {}", symbol, e.getMessage());
            }
        }
    }

    private List<CandleBar> fetchDhanDailyCandles(DhanInstrumentRef ref, LocalDate from, LocalDate to,
                                                  DhanBrokerService.Credential credential) throws Exception {
        validateDhanHistoricalRequest(ref, from, to);
        Map<String, String> headers = Map.of(
            "Accept", "application/json",
            "Content-Type", "application/json",
            "access-token", credential.accessToken(),
            "client-id", credential.clientId()
        );
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("securityId", ref.securityId);
        body.put("exchangeSegment", normalizeDhanExchangeSegment(ref.exchangeSegment));
        body.put("instrument", ref.historicalInstrument());
        body.put("expiryCode", 0);
        body.put("oi", false);
        body.put("fromDate", from.toString());
        body.put("toDate", to.plusDays(1).toString());
        dhanThrottle();
        String rawResponse;
        try {
            rawResponse = http.postJsonWithRetry(getDhanApi() + "/charts/historical", headers, body, 30000);
            dhanOnSuccess();
        } catch (Exception e) {
            if (isDhanRateLimit(e.getMessage())) dhanOn429();
            throw e;
        }
        JsonNode json = mapper.readTree(rawResponse);
        JsonNode timestamps = json.path("timestamp");
        if (!timestamps.isArray()) return List.of();
        List<CandleBar> candles = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            String tradeDate = Instant.ofEpochSecond(timestamps.get(i).asLong()).atZone(IST).toLocalDate().toString();
            candles.add(new CandleBar(
                tradeDate,
                at(json, "close", i),
                at(json, "high", i),
                at(json, "low", i),
                at(json, "volume", i),
                "DHAN_API"
            ));
        }
        return candles;
    }

    private void validateDhanHistoricalRequest(DhanInstrumentRef ref, LocalDate from, LocalDate to) {
        if (ref == null) throw new IllegalArgumentException("Dhan historical request failed: instrument mapping missing.");
        if (ref.securityId == null || ref.securityId.isBlank()) {
            throw new IllegalArgumentException("Dhan historical request failed for " + ref.symbol + ": securityId is missing.");
        }
        try {
            Long.parseLong(ref.securityId);
        } catch (Exception e) {
            throw new IllegalArgumentException("Dhan historical request failed for " + ref.symbol + ": securityId must be numeric, actual=" + ref.securityId + ".");
        }
        String exchangeSegment = normalizeDhanExchangeSegment(ref.exchangeSegment);
        if (!"NSE_EQ".equals(exchangeSegment) && !"BSE_EQ".equals(exchangeSegment)) {
            throw new IllegalArgumentException("Dhan historical request failed for " + ref.symbol + ": exchangeSegment must be NSE_EQ or BSE_EQ, actual=" + ref.exchangeSegment + ".");
        }
        if (!"EQUITY".equals(ref.historicalInstrument())) {
            throw new IllegalArgumentException("Dhan historical request failed for " + ref.symbol + ": instrument must resolve to EQUITY, actual=" + ref.instrument + ".");
        }
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("Dhan historical request failed for " + ref.symbol + ": invalid date range " + from + " to " + to + ".");
        }
    }

    private double at(JsonNode json, String key, int index) {
        JsonNode arr = json.path(key);
        if (!arr.isArray() || index >= arr.size() || !arr.get(index).isNumber()) return 0;
        return arr.get(index).asDouble();
    }

    private double firstNumber(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0;
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isNumber()) return value.asDouble();
            if (value.isTextual()) {
                try { return Double.parseDouble(value.asText().trim()); }
                catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private void markBasePriceError(String symbol, Long institutionId, String message) {
        try {
            runInTransaction(() -> em.createNativeQuery(
                    "UPDATE tracked_stocks SET base_price=NULL,return_pct=0,base_price_source=?,last_refreshed=?,updated_at=? " +
                    "WHERE symbol=? AND institution_id=?")
                    .setParameter(1, message)
                    .setParameter(2, DateUtil.now())
                    .setParameter(3, DateUtil.now())
                    .setParameter(4, symbol)
                    .setParameter(5, institutionId)
                    .executeUpdate());
        } catch (Exception e) {
            log.debug("Could not mark Dhan base price error for {}: {}", symbol, e.getMessage());
        }
    }

    private void runInTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private double latestTrackedCurrentPrice(String symbol, Long institutionId) {
        return resolveCurrentPrice(symbol, institutionId);
    }

    private double resolveCurrentPrice(String symbol, Long institutionId) {
        return resolveCurrentPricesBatch(List.of(symbol), institutionId)
            .getOrDefault(normalizeSymbol(symbol), 0.0);
    }

    @SuppressWarnings("unchecked")
    /**
     * The date each tracked price was last stamped with, keyed by normalised symbol.
     *
     * <p>A price is only usable for valuing a position if it is not older than the purchase.
     * Four of the 1,232 tracked stocks currently carry prices several sessions behind, and
     * without this the gap between the stale figure and the buy is reported as a loss.
     */
    private Map<String, String> trackedPriceDates(List<String> symbols, Long institutionId) {
        Map<String, String> out = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT UPPER(TRIM(symbol)), current_price_date FROM tracked_stocks " +
                "WHERE institution_id = ?1 AND current_price_date IS NOT NULL")
                .setParameter(1, institutionId).getResultList();
            for (Object[] r : rows) {
                if (r[0] == null || r[1] == null) continue;
                out.put(normalizeSymbol(String.valueOf(r[0])), String.valueOf(r[1]));
            }
        } catch (Exception e) {
            log.debug("Tracked price dates lookup failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Latest price per symbol: the tracked universe first, then the live Dhan quote feed.
     *
     * <p>Public because the Trading Journal marks open rows to market through this same path.
     * A second price lookup written next to the journal would drift from this one the first
     * time either source changed.
     */
    public Map<String, Double> resolveCurrentPricesBatch(List<String> symbols, Long institutionId) {
        Map<String, Double> prices = new LinkedHashMap<>();
        List<String> normalized = symbols.stream()
            .map(this::normalizeSymbol).filter(s -> !s.isBlank()).distinct().toList();
        if (normalized.isEmpty()) return prices;
        Map<String, Double> cached = bulkPriceCache;
        if (System.currentTimeMillis() - bulkPriceCacheTs < BULK_PRICE_CACHE_TTL_MS && !cached.isEmpty()) {
            boolean allHit = true;
            for (String s : normalized) {
                Double p = cached.get(s);
                if (p != null && p > 0) { prices.put(s, p); } else { allHit = false; }
            }
            if (allHit) return prices;
            prices.clear();
        }
        String symCol = normalizedSql("symbol");
        String in = placeholders(normalized.size());
        try {
            var q = em.createNativeQuery(
                "SELECT " + symCol + " AS sym, current_price FROM tracked_stocks WHERE " +
                symCol + " IN (" + in + ") AND institution_id=? AND is_active=1 AND current_price IS NOT NULL AND current_price>0");
            for (int i = 0; i < normalized.size(); i++) q.setParameter(i + 1, normalized.get(i));
            q.setParameter(normalized.size() + 1, institutionId);
            for (Object[] r : (List<Object[]>) q.getResultList()) {
                String sym = asText(r[0]);
                if (!sym.isBlank() && !prices.containsKey(sym)) prices.put(sym, getNumber(r[1]));
            }
        } catch (Exception ignored) {}
        List<String> missing = normalized.stream().filter(s -> !prices.containsKey(s)).toList();
        if (!missing.isEmpty()) {
            String in2 = placeholders(missing.size());
            try {
                var q = em.createNativeQuery(
                    "SELECT DISTINCT ON (" + symCol + ") " + symCol + " AS sym, current_price " +
                    "FROM live_price_quotes WHERE broker_name='dhan' AND " +
                    symCol + " IN (" + in2 + ") AND current_price IS NOT NULL AND current_price>0 " +
                    "ORDER BY " + symCol + ", updated_at DESC NULLS LAST");
                for (int i = 0; i < missing.size(); i++) q.setParameter(i + 1, missing.get(i));
                for (Object[] r : (List<Object[]>) q.getResultList()) {
                    String sym = asText(r[0]);
                    if (!sym.isBlank() && !prices.containsKey(sym)) prices.put(sym, getNumber(r[1]));
                }
            } catch (Exception ignored) {}
        }
        List<String> still = normalized.stream().filter(s -> !prices.containsKey(s)).toList();
        if (!still.isEmpty()) {
            String in3 = placeholders(still.size());
            try {
                var q = em.createNativeQuery(
                    "SELECT DISTINCT ON (" + symCol + ") " + symCol + " AS sym, close " +
                    "FROM broker_market_candles WHERE " + symCol + " IN (" + in3 + ") AND close IS NOT NULL AND close>0 " +
                    "AND (candle_mode IS NULL OR UPPER(candle_mode)='DAILY') " +
                    "ORDER BY " + symCol + ", trade_date DESC");
                for (int i = 0; i < still.size(); i++) q.setParameter(i + 1, still.get(i));
                for (Object[] r : (List<Object[]>) q.getResultList()) {
                    String sym = asText(r[0]);
                    if (!sym.isBlank() && !prices.containsKey(sym)) prices.put(sym, getNumber(r[1]));
                }
            } catch (Exception ignored) {}
        }
        if (!prices.isEmpty()) {
            Map<String, Double> merged = new LinkedHashMap<>(bulkPriceCache);
            merged.putAll(prices);
            bulkPriceCache = merged;
            bulkPriceCacheTs = System.currentTimeMillis();
        }
        return prices;
    }

    private String safeError(String value) {
        String text = value == null || value.isBlank() ? "Unknown Dhan error" : value;
        return text.length() > 180 ? text.substring(0, 180) : text;
    }

    // ==========================================================================
    //  PARSE HELPERS
    // ==========================================================================

    private List<Map<String, String>> parseCsv(InputStream is) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
            String header = reader.readLine();
            if (header == null) return rows;
            String[] cols = header.split(",");
            // Normalize column names
            for (int i = 0; i < cols.length; i++) {
                cols[i] = cols[i].trim().toLowerCase()
                    .replace(" ", "_").replace("\"", "");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < cols.length && i < parts.length; i++) {
                    row.put(cols[i], parts[i].trim().replace("\"", ""));
                }
                if (!row.isEmpty()) rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, String>> parseExcel(InputStream is) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return rows;

            List<String> cols = new ArrayList<>();
            for (Cell c : headerRow) {
                cols.add(c.toString().trim().toLowerCase()
                    .replace(" ", "_").replace("(", "").replace(")", ""));
            }

            for (int ri = 1; ri <= sheet.getLastRowNum(); ri++) {
                Row row = sheet.getRow(ri);
                if (row == null) continue;
                Map<String, String> m = new LinkedHashMap<>();
                for (int ci = 0; ci < cols.size(); ci++) {
                    Cell cell = row.getCell(ci);
                    String val = cell != null ? cellValue(cell) : "";
                    m.put(cols.get(ci), val);
                }
                if (!m.isEmpty()) rows.add(m);
            }
        }
        return rows;
    }

    private String cellValue(Cell cell) {
        return switch (cell.getCellType()) {
            case NUMERIC -> org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)
                ? new java.text.SimpleDateFormat("dd-MM-yyyy").format(cell.getDateCellValue())
                : String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCachedFormulaResultType() == CellType.NUMERIC
                ? String.valueOf((long) cell.getNumericCellValue())
                : cell.getStringCellValue();
            default -> cell.toString().trim();
        };
    }

    private String parseDate(String raw) {
        if (raw == null || raw.isBlank()) return DateUtil.today();
        String value = raw.trim();
        if (value.matches("\\d+(\\.0+)?")) {
            try {
                long serial = Math.round(Double.parseDouble(value));
                if (serial > 25000 && serial < 90000) {
                    return LocalDate.of(1899, 12, 30).plusDays(serial).toString();
                }
            } catch (Exception ignored) {}
        }
        try {
            for (String fmt : new String[]{"dd-MM-yyyy","dd/MM/yyyy","yyyy-MM-dd","MM/dd/yyyy","d-MMM-yyyy"}) {
                try {
                    return LocalDate.parse(value, DateTimeFormatter.ofPattern(fmt)).toString();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return DateUtil.today();
    }

    // ==========================================================================
    //  DB HELPERS
    // ==========================================================================

    @SuppressWarnings("unchecked")
    private List<String> getActiveSymbols(Long institutionId) {
        return getActiveSymbols(null, institutionId);
    }

    @SuppressWarnings("unchecked")
    private List<String> getActiveSymbols(Long userId, Long institutionId) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT symbol FROM tracked_stocks WHERE institution_id=? AND is_active=1");
            if (userId != null) sql.append(" AND user_id=?");
            var q = em.createNativeQuery(sql.toString()).setParameter(1, institutionId);
            if (userId != null) q.setParameter(2, userId);
            return q.getResultList();
        } catch (Exception e) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private List<String> getSymbolsMissingBasePrice(Long institutionId) {
        return getSymbolsMissingBasePrice(null, institutionId);
    }

    @SuppressWarnings("unchecked")
    private List<String> getSymbolsMissingBasePrice(Long userId, Long institutionId) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT symbol FROM tracked_stocks WHERE institution_id=? AND is_active=1 " +
                "AND (base_price IS NULL OR base_price<=0) " +
                "AND (base_price_source IS NULL OR base_price_source NOT LIKE 'DHAN_UNFETCHABLE%')");
            if (userId != null) sql.append(" AND user_id=?");
            var q = em.createNativeQuery(sql.toString()).setParameter(1, institutionId);
            if (userId != null) q.setParameter(2, userId);
            return q.getResultList();
        } catch (Exception e) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    /**
     * Resolves every missing base price in ONE statement.
     *
     * <p>The per-symbol path below issues two round trips per stock - a lookup and an update - so
     * 1,232 stocks cost about 2,400 queries and took 27.7 seconds measured. For that whole time
     * the screen shows "Waiting for broker price" against every row, which reads as though the
     * application is stuck on the broker when in fact the answer was already in the candle table
     * the entire time.
     *
     * <p>One LATERAL join does the same work set-based, using the same normalized daily index the
     * rest of the application relies on. What it cannot resolve - symbols that only match through
     * an alias - is left for the per-symbol pass, which then has almost nothing to do.
     *
     * @return how many rows were filled
     */
    private int bulkFillBasePricesFromCandles(Long institutionId) {
        String now = DateUtil.now();
        try {
            return new TransactionTemplate(transactionManager).execute(tx -> em.createNativeQuery(
                "UPDATE tracked_stocks t SET "
              + "  base_price = c.close_px, "
              + "  return_pct = CASE WHEN t.current_price IS NOT NULL AND t.current_price > 0 "
              + "                    THEN ((t.current_price - c.close_px) / c.close_px) * 100 ELSE 0 END, "
              + "  base_price_source = c.src, last_refreshed = ?, updated_at = ? "
              + "FROM ( "
              + "  SELECT t2.id AS tid, x.close AS close_px, "
              + "         COALESCE(x.source,'BROKER_DAILY') || ':' || x.trade_date AS src "
              + "  FROM tracked_stocks t2 "
              + "  CROSS JOIN LATERAL ( "
              + "    SELECT b.close, b.trade_date, b.source FROM broker_market_candles b "
              + "     WHERE " + normalizedSql("b.symbol") + " = " + normalizedSql("t2.symbol") + " "
              + "       AND b.close IS NOT NULL AND b.close > 0 "
              + "       AND b.trade_date <= t2.upload_date "
              // Bound the lookback. Without a lower bound this took the most recent daily
              // candle at ANY distance before the upload date - so while the candle table held
              // only July 2025 bhavcopy, every stock uploaded in 2026 matched 2025-07-31 and was
              // stamped with a price up to 456 days stale. 623 of 1,271 rows were filled that
              // way; 360ONE (uploaded 2026-10-16) was priced at 1052.7 from 2025-07-31, which
              // made its return read -6.43% against a base it never traded at. A missing base
              // price is recoverable - the next fetch fills it - but a plausible wrong one is
              // not, because nothing flags it. Seven days covers a weekend plus a holiday; past
              // that, leave it NULL and let the backfill supply the real bar.
              + "       AND b.trade_date >= to_char(t2.upload_date::date - 7, 'YYYY-MM-DD') "
              + "       AND UPPER(COALESCE(b.candle_mode,'')) = 'DAILY' "
              + "     ORDER BY b.trade_date DESC LIMIT 1 "
              + "  ) x "
              + "  WHERE t2.institution_id = ? AND t2.is_active = 1 "
              + "    AND (t2.base_price IS NULL OR t2.base_price <= 0) "
              + "    AND COALESCE(t2.upload_date,'') <> '' "
              + ") c WHERE t.id = c.tid")
                .setParameter(1, now).setParameter(2, now)
                .setParameter(3, institutionId)
                .executeUpdate());
        } catch (Exception e) {
            // Never fatal - the per-symbol path below still works, just slowly.
            log.warn("[BasePrice] Bulk fill from candles failed, falling back to per-symbol: {}",
                e.getMessage());
            return 0;
        }
    }

    private int backfillBasePricesFromSavedMarketData(List<String> symbols, Long institutionId) {
        // Fast path first: one statement resolves nearly everything.
        int bulk = bulkFillBasePricesFromCandles(institutionId);
        if (bulk > 0) log.info("[BasePrice] Bulk-filled {} base price(s) from stored candles.", bulk);

        // The same pass over the upload log. Its base prices are pinned to each row's own
        // entry_date, not to the symbol's current one, which is what lets a backtest of a past
        // date price the signal as it stood that day.
        int history = uploadHistory.backfillBasePrices(institutionId);
        if (history > 0) log.info("[BasePrice] Filled {} upload-history base price(s) from stored candles.", history);

        List<String> normalizedInput = symbols == null ? List.of() : symbols.stream()
            .map(this::normalizeSymbol)
            .filter(s -> !s.isBlank())
            .distinct()
            .toList();
        if (normalizedInput.isEmpty()) return bulk;
        Set<String> requested = new HashSet<>(normalizedInput);
        List<Object[]> rows;
        try {
            rows = em.createNativeQuery(
                "SELECT symbol,upload_date,current_price FROM tracked_stocks WHERE institution_id=? AND is_active=1 " +
                "AND (base_price IS NULL OR base_price<=0)")
                .setParameter(1, institutionId)
                .getResultList();
        } catch (Exception e) {
            log.debug("Saved base price candidate lookup skipped: {}", e.getMessage());
            return 0;
        }
        List<String> candidateSymbols = rows.stream()
            .map(r -> String.valueOf(r[0]))
            .filter(s -> requested.contains(normalizeSymbol(s)))
            .distinct()
            .toList();
        if (candidateSymbols.isEmpty()) return 0;
        Map<String, Set<String>> aliasesBySymbol = loadDhanAliases(candidateSymbols);
        List<String> batchSymbols = new ArrayList<>();
        List<Double> batchPrices = new ArrayList<>();
        List<Double> batchReturns = new ArrayList<>();
        List<String> batchSources = new ArrayList<>();
        for (Object[] row : rows) {
            String symbol = String.valueOf(row[0]);
            String normalized = normalizeSymbol(symbol);
            if (!requested.contains(normalized)) continue;
            String uploadDate = normalizeStoredDate(asText(row[1]));
            if (uploadDate == null || uploadDate.isBlank()) continue;
            Set<String> aliases = aliasesBySymbol.getOrDefault(normalized, Set.of(normalized));
            SavedBasePrice saved = lookupSavedBasePrice(aliases, uploadDate);
            if (saved == null || saved.close <= 0) continue;
            double current = getNumber(row[2]);
            double returnPct = current > 0 ? ((current - saved.close) / saved.close) * 100.0 : 0;
            batchSymbols.add(symbol);
            batchPrices.add(saved.close);
            batchReturns.add(returnPct);
            batchSources.add(saved.source + ":" + saved.tradeDate);
        }
        if (batchSymbols.isEmpty()) return bulk;
        String now = DateUtil.now();
        try {
            runInTransaction(() -> {
                for (int i = 0; i < batchSymbols.size(); i++) {
                    em.createNativeQuery(
                        "UPDATE tracked_stocks SET base_price=?,return_pct=?,base_price_source=?,last_refreshed=?,updated_at=? " +
                        "WHERE symbol=? AND institution_id=? AND (base_price IS NULL OR base_price<=0)")
                        .setParameter(1, batchPrices.get(i))
                        .setParameter(2, batchReturns.get(i))
                        .setParameter(3, batchSources.get(i))
                        .setParameter(4, now)
                        .setParameter(5, now)
                        .setParameter(6, batchSymbols.get(i))
                        .setParameter(7, institutionId)
                        .executeUpdate();
                    if ((i + 1) % getDbFlushSize() == 0) { em.flush(); em.clear(); }
                }
                em.flush(); em.clear();
            });
        } catch (Exception e) {
            log.warn("Batch base price backfill failed for {} symbols: {}", batchSymbols.size(), e.getMessage());
            return 0;
        }
        return bulk + batchSymbols.size();
    }

    @SuppressWarnings("unchecked")
    private SavedBasePrice lookupSavedBasePrice(Set<String> aliases, String uploadDate) {
        List<String> aliasList = aliases == null ? List.of() : aliases.stream()
            .map(this::normalizeSymbol)
            .filter(s -> !s.isBlank())
            .distinct()
            .toList();
        if (aliasList.isEmpty()) return null;

        // Same seven-day floor as bulkFillBasePricesFromCandles, for the same reason.
        //
        // This is the per-symbol fallback that runs on whatever the bulk statement could not
        // resolve, and it was left unbounded when the bulk path was fixed - so the bug simply
        // moved here. Measured on 2026-11-10: clearing the 639 wrongly-stamped rows and letting
        // the recovery refill them produced "Bulk-filled 627" and 639 refilled in total, and the
        // 12 rows that differ are exactly the ones this method wrote - DPABHUSHAN uploaded
        // 2026-07-10 priced from 2025-07-31, 344 days earlier, because that was the newest daily
        // bar stored for it at the time.
        //
        // A missing base price is recoverable and shows as "Waiting for broker price". A
        // plausible wrong one is not, because nothing flags it and every return is measured
        // against it.
        String lowerBound = null;
        try {
            lowerBound = java.time.LocalDate.parse(uploadDate).minusDays(7).toString();
        } catch (Exception e) {
            // An upload date we cannot parse cannot be bounded safely, so do not guess a base
            // price for it at all - leave it to the broker fetch.
            log.debug("Base price lookup skipped: unparseable upload date {}", uploadDate);
            return null;
        }

        String in = placeholders(aliasList.size());
        String candleSymbol = normalizedSql("symbol");
        String sql =
            "SELECT close AS close_price,trade_date,COALESCE(source,'BROKER_DAILY') AS source FROM broker_market_candles " +
            "WHERE " + candleSymbol + " IN (" + in + ") AND close IS NOT NULL AND close>0 " +
            "AND trade_date<=? AND trade_date>=? " +
            "AND (candle_mode IS NULL OR UPPER(candle_mode)='DAILY') " +
            "ORDER BY trade_date DESC LIMIT 1";
        try {
            var query = em.createNativeQuery(sql);
            int p = 1;
            for (String alias : aliasList) query.setParameter(p++, alias);
            query.setParameter(p++, uploadDate);
            query.setParameter(p, lowerBound);
            List<Object[]> rows = query.getResultList();
            if (rows.isEmpty()) return null;
            Object[] row = rows.get(0);
            double close = getNumber(row[0]);
            return close > 0 ? new SavedBasePrice(close, asText(row[1]), asText(row[2])) : null;
        } catch (Exception e) {
            log.debug("Saved base price lookup skipped for {} aliases: {}", aliasList.size(), e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> getUploadDatesBatch(List<String> symbols, Long institutionId) {
        Map<String, String> dates = new LinkedHashMap<>();
        List<String> normalized = symbols.stream()
            .map(this::normalizeSymbol).filter(s -> !s.isBlank()).distinct().toList();
        if (normalized.isEmpty()) return dates;
        String symCol = normalizedSql("symbol");
        try {
            var q = em.createNativeQuery(
                "SELECT " + symCol + " AS sym, upload_date FROM tracked_stocks WHERE " +
                symCol + " IN (" + placeholders(normalized.size()) + ") AND institution_id=?");
            for (int i = 0; i < normalized.size(); i++) q.setParameter(i + 1, normalized.get(i));
            q.setParameter(normalized.size() + 1, institutionId);
            for (Object[] r : (List<Object[]>) q.getResultList()) {
                String sym = asText(r[0]);
                String date = r[1] != null ? normalizeStoredDate(r[1].toString()) : "";
                if (!sym.isBlank()) dates.putIfAbsent(sym, date != null ? date : "");
            }
        } catch (Exception ignored) {}
        return dates;
    }

    private String getUploadDate(String symbol, Long institutionId) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                "SELECT upload_date FROM tracked_stocks WHERE " +
                normalizedSql("symbol") + "=? AND institution_id=? LIMIT 1")
                .setParameter(1, normalizeSymbol(symbol)).setParameter(2, institutionId).getResultList();
            if (rows.isEmpty()) return null;
            Object r = rows.get(0);
            return r != null ? normalizeStoredDate(r.toString()) : null;
        } catch (Exception e) { return null; }
    }

    private String normalizeStoredDate(String value) {
        if (value == null || value.isBlank()) return value;
        String text = value.trim();
        if (text.length() >= 10 && text.charAt(4) == '-' && text.charAt(7) == '-') return text.substring(0, 10);
        return parseDate(text);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getPreviousTop10(Long institutionId, String today) {
        return getPreviousTop10(null, institutionId, today);
    }

    /** Newest run before {@code today}; when {@code lockedOnly}, only runs marked as locked. */
    private String findPreviousTop10RunId(Long userId, Long institutionId, String today, boolean lockedOnly) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT run_id FROM tracker_rankings WHERE institution_id=? AND run_date<?");
            if (lockedOnly) sql.append(" AND is_locked=1");
            if (userId != null) sql.append(" AND user_id=").append(userId);
            sql.append(" ORDER BY run_date DESC, id DESC LIMIT 1");
            List<?> rows = em.createNativeQuery(sql.toString())
                .setParameter(1, institutionId).setParameter(2, today).getResultList();
            return rows.isEmpty() ? null : String.valueOf(rows.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getPreviousTop10(Long userId, Long institutionId, String today) {
        try {
            // Prefer the run the user locked as that day's official Top 10. Without a lock
            // this falls back to "whichever run happened to be last", which is arbitrary when
            // the engine re-ranks every few minutes - the list studied at 15:20 was not the
            // one that resurfaced here the next morning.
            String prevRunId = findPreviousTop10RunId(userId, institutionId, today, true);
            if (prevRunId == null) prevRunId = findPreviousTop10RunId(userId, institutionId, today, false);
            if (prevRunId == null) return List.of();

            List<Object[]> items = em.createNativeQuery(
                "SELECT symbol,sector,market_cap_name,composite_score,base_price,current_price,return_pct " +
                "FROM tracker_ranking_items WHERE run_id=? ORDER BY rank_position")
                .setParameter(1, prevRunId).getResultList();

            List<String> syms = items.stream().map(r -> String.valueOf(r[0])).toList();
            Map<String, Double> livePrices = resolveCurrentPricesBatch(syms, institutionId);
            Map<String, String> uploadDates = getUploadDatesBatch(syms, institutionId);

            return items.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                String sym = String.valueOf(r[0]);
                String normSym = normalizeSymbol(sym);
                double basePrice = r[4]!=null ? ((Number)r[4]).doubleValue() : 0;
                double snapshotPrice = r[5]!=null ? ((Number)r[5]).doubleValue() : 0;
                double prevReturn = r[6]!=null ? Math.round(((Number)r[6]).doubleValue()*100)/100.0 : 0;
                double livePrice = livePrices.getOrDefault(normSym, 0.0);
                double currReturn = basePrice > 0 && livePrice > 0
                    ? Math.round(((livePrice-basePrice)/basePrice)*10000.0)/100.0 : prevReturn;
                String uploadDate = uploadDates.getOrDefault(normSym, "");
                long days = 0;
                try { days = java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.parse(uploadDate), LocalDate.now()); }
                catch (Exception ignored) {}
                m.put("symbol", sym); m.put("sector",r[1]); m.put("market_cap",r[2]);
                m.put("score",r[3]); m.put("base_price",r[4]);
                m.put("snapshot_price", snapshotPrice);
                m.put("current_price", livePrice > 0 ? livePrice : snapshotPrice);
                m.put("return_pct", prevReturn);
                m.put("current_return", currReturn);
                m.put("days_since_upload", days);
                return m;
            }).collect(Collectors.toList());
        } catch (Exception e) { return List.of(); }
    }

    private record RankingRunRef(String runId, String runDate, String createdAt) {}

    /** Clamps a HH:mm string to the 5-minute grid the UI offers; defaults to the 15:20 snapshot. */
    private String normalizeHhmm(String hhmm) {
        // Falls back to a real bar start. Candles are hourly from the 09:15 open, so 15:20 -
        // what this used to return - begins no bar, and a malformed time would have become a
        // time matching no candle at all instead of the last bar of the day.
        if (hhmm == null || !hhmm.trim().matches("\\d{1,2}:\\d{2}")) return "15:15";
        String[] parts = hhmm.trim().split(":");
        int h = Math.min(23, Math.max(0, Integer.parseInt(parts[0])));
        int m = Math.min(59, Math.max(0, Integer.parseInt(parts[1])));
        return String.format("%02d:%02d", h, m);
    }

    /** The ranking run that was current at {@code asOf} ("yyyy-MM-dd HH:mm:ss"), or null. */
    private RankingRunRef rankingRunAsOf(Long userId, Long institutionId, String asOf) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT run_id, run_date, created_at FROM tracker_rankings " +
                "WHERE institution_id=? AND created_at IS NOT NULL AND created_at<=?");
            if (userId != null) sql.append(" AND user_id=?");
            sql.append(" ORDER BY created_at DESC, id DESC LIMIT 1");
            var q = em.createNativeQuery(sql.toString())
                .setParameter(1, institutionId).setParameter(2, asOf);
            if (userId != null) q.setParameter(3, userId);
            List<Object[]> rows = q.getResultList();
            if (rows.isEmpty()) return null;
            Object[] r = rows.get(0);
            return new RankingRunRef(asText(r[0]), normalizeStoredDate(asText(r[1])), asText(r[2]));
        } catch (Exception e) {
            log.debug("Ranking run as-of lookup failed for {}: {}", asOf, e.getMessage());
            return null;
        }
    }

    /** Top 10 rows of one ranking run, ordered by rank. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadRankingItems(String runId) {
        try {
            List<Object[]> items = em.createNativeQuery(
                "SELECT symbol,sector,market_cap_name,composite_score,base_price,current_price,return_pct,rank_position " +
                "FROM tracker_ranking_items WHERE run_id=? AND is_current_top10=1 ORDER BY rank_position")
                .setParameter(1, runId).getResultList();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object[] r : items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol", asText(r[0]));
                m.put("sector", r[1]);
                m.put("market_cap_name", r[2]);
                m.put("score", r[3]);
                m.put("base_price", r[4]);
                m.put("current_price", getNumber(r[5]));
                m.put("return_pct", r[6]);
                if (r[7] != null) m.put("rank", ((Number) r[7]).intValue());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.debug("Ranking item load failed for {}: {}", runId, e.getMessage());
            return List.of();
        }
    }

    /** run_date of the most recent ranking run BEFORE {@code today}, or null if there is none. */
    private String previousRunDate(Long userId, Long institutionId, String today) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT run_date FROM tracker_rankings WHERE institution_id=? AND run_date<?");
            if (userId != null) sql.append(" AND user_id=?");
            sql.append(" ORDER BY run_date DESC, id DESC LIMIT 1");
            var q = em.createNativeQuery(sql.toString())
                .setParameter(1, institutionId).setParameter(2, today);
            if (userId != null) q.setParameter(3, userId);
            List<?> rows = q.getResultList();
            return rows.isEmpty() ? null : normalizeStoredDate(asText(rows.get(0)));
        } catch (Exception e) {
            log.debug("Previous run date lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildUploadedStockSignals(String symbol, double currentPrice, Long institutionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<CandleBar> bars = loadDailyBars(symbol, 260);
        result.put("broker_data_source", bars.isEmpty() ? "Not Available" : bars.get(0).source);

        if (bars.size() < 3) {
            result.put("buy_sell_signal", "DATA REQUIRED");
            result.put("trend", "Data Required");
            result.put("trend_score", 0);
            result.put("signal_message", "Need at least 3 saved broker daily candles for volume signal.");
            result.put("week_52_high", null);
            result.put("week_52_low", null);
            result.put("below_52wh_pct", null);
            result.put("above_52wl_pct", null);
            result.put("today_volume_change_pct", null);
            result.put("yesterday_volume_change_pct", null);
            applySaved52WeekStats(result, currentPrice, bars);
            result.put("pe_ratio", loadPeRatio(symbol, currentPrice));
            return result;
        }

        CandleBar latest = bars.get(0);
        CandleBar previous = bars.get(1);
        CandleBar beforePrevious = bars.get(2);
        double latestClose = latest.close > 0 ? latest.close : currentPrice;
        double previousClose = previous.close;
        double priceChangePct = previousClose > 0 ? ((latestClose - previousClose) / previousClose) * 100.0 : 0.0;
        double absPriceChangePct = Math.abs(priceChangePct);
        double todayVolumeChangePct = previous.volume > 0 ? ((latest.volume - previous.volume) / previous.volume) * 100.0 : 0.0;
        double yesterdayVolumeChangePct = beforePrevious.volume > 0 ? ((previous.volume - beforePrevious.volume) / beforePrevious.volume) * 100.0 : 0.0;
        double avgPrev20Volume = averageVolume(bars, 1, 20);
        double weeklyVolumeTrend = averageVolume(bars, 1, 5);

        boolean currentAboveAvg20 = latest.volume > 0 && avgPrev20Volume > 0 && latest.volume > avgPrev20Volume;
        boolean currentAboveWeekly = latest.volume > 0 && weeklyVolumeTrend > 0 && latest.volume > weeklyVolumeTrend;
        boolean currentAbovePrevious = latest.volume > 0 && previous.volume > 0 && latest.volume > previous.volume;
        boolean closeUp = latestClose > previousClose;
        boolean closeDown = latestClose < previousClose;

        boolean buy = currentAboveAvg20 && currentAboveWeekly && currentAbovePrevious && closeUp && absPriceChangePct > 1.0;
        boolean sell = currentAboveAvg20 && currentAboveWeekly && currentAbovePrevious && closeDown && absPriceChangePct > 1.0;

        int todayVolumeSignal = directionalVolumeSignal(latest.volume, previous.volume, latestClose, previousClose);
        int yesterdayVolumeSignal = directionalVolumeSignal(previous.volume, beforePrevious.volume, previous.close, beforePrevious.close);
        int avg20VolumeSignal = directionalTrendSignal(currentAboveAvg20, latestClose, previousClose);
        int weeklyVolumeSignal = directionalTrendSignal(currentAboveWeekly, latestClose, previousClose);
        int trendScore = todayVolumeSignal + yesterdayVolumeSignal + avg20VolumeSignal + weeklyVolumeSignal;

        String trend = trendScore >= 2 ? "Bullish" : trendScore <= -2 ? "Bearish" : "Neutral";
        String signal = buy ? "BUY" : sell ? "SELL" : "HOLD";

        result.put("buy_sell_signal", signal);
        result.put("trend", trend);
        result.put("trend_score", trendScore);
        result.put("price_change_pct", round2(priceChangePct));
        result.put("today_volume_change_pct", round2(todayVolumeChangePct));
        result.put("yesterday_volume_change_pct", round2(yesterdayVolumeChangePct));
        result.put("today_volume_signal", todayVolumeSignal);
        result.put("yesterday_volume_signal", yesterdayVolumeSignal);
        result.put("avg20_volume_signal", avg20VolumeSignal);
        result.put("weekly_volume_signal", weeklyVolumeSignal);
        result.put("avg20_volume", round2(avgPrev20Volume));
        result.put("weekly_volume_trend", round2(weeklyVolumeTrend));
        applySaved52WeekStats(result, latestClose, bars);
        result.put("pe_ratio", loadPeRatio(symbol, latestClose));
        result.put("signal_message", signal + " from broker candle volume and price trend.");
        recordSignalHistory(symbol, signal, latestClose, trend, 0, institutionId);
        return result;
    }

    private void recordSignalHistory(String symbol, String signal, double price, String trend, double score, Long institutionId) {
        if ("HOLD".equals(signal) || "DATA REQUIRED".equals(signal)) return;
        try {
            String normalSym = normalizeSymbol(symbol);
            String today = DateUtil.today();
            Object existing = null;
            try {
                existing = em.createNativeQuery(
                    "SELECT id FROM signal_history WHERE symbol=? AND signal_type=? AND signal_date=? AND institution_id=? LIMIT 1")
                    .setParameter(1, normalSym).setParameter(2, signal).setParameter(3, today).setParameter(4, institutionId)
                    .getSingleResult();
            } catch (Exception ignored) {}
            if (existing != null) return;
            String previousSignal = null;
            try {
                Object prev = em.createNativeQuery(
                    "SELECT signal_type FROM signal_history WHERE symbol=? AND institution_id=? ORDER BY signal_date DESC, id DESC LIMIT 1")
                    .setParameter(1, normalSym).setParameter(2, institutionId)
                    .getSingleResult();
                previousSignal = prev != null ? String.valueOf(prev) : null;
            } catch (Exception ignored) {}
            em.createNativeQuery(
                "INSERT INTO signal_history (symbol, signal_type, signal_date, signal_price, previous_signal, trend, score, source, institution_id, created_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, normalSym).setParameter(2, signal).setParameter(3, today)
                .setParameter(4, round2(price)).setParameter(5, previousSignal)
                .setParameter(6, trend).setParameter(7, round2(score))
                .setParameter(8, "RANKING_ENGINE").setParameter(9, institutionId)
                .setParameter(10, java.time.LocalDateTime.now().toString())
                .executeUpdate();
        } catch (Exception e) {
            log.debug("Signal history recording skipped for {}: {}", symbol, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String[]> loadLastSignalDates(List<String> symbols, Long institutionId) {
        Map<String, String[]> result = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return result;
        for (String sym : symbols) {
            String[] dates = new String[]{"-", "-"};
            try {
                Object buyDate = em.createNativeQuery(
                    "SELECT signal_date FROM signal_history WHERE symbol=? AND signal_type='BUY' AND institution_id=? ORDER BY signal_date DESC, id DESC LIMIT 1")
                    .setParameter(1, sym).setParameter(2, institutionId).getSingleResult();
                if (buyDate != null) dates[0] = String.valueOf(buyDate);
            } catch (Exception ignored) {}
            try {
                Object sellDate = em.createNativeQuery(
                    "SELECT signal_date FROM signal_history WHERE symbol=? AND signal_type='SELL' AND institution_id=? ORDER BY signal_date DESC, id DESC LIMIT 1")
                    .setParameter(1, sym).setParameter(2, institutionId).getSingleResult();
                if (sellDate != null) dates[1] = String.valueOf(sellDate);
            } catch (Exception ignored) {}
            result.put(sym, dates);
        }
        return result;
    }

    /**
     * Single-symbol daily bars. Delegates to {@link #loadDailyBarsBatch(Collection, int)} so
     * there is one implementation of the market_data / broker_market_candles fallback — and so
     * callers on this path also get the index-friendly broker query rather than the OR'd form
     * that made PostgreSQL sequentially scan broker_market_candles.
     */
    private List<CandleBar> loadDailyBars(String symbol, int bars) {
        // fillTrailing=false. The fold itself is now just a cached map lookup, but this
        // wrapper runs collectBrokerBars once per symbol - 1,232 times for Uploaded Stock
        // Details - and that query is the expensive part, not the fold. Until the batch path is
        // fixed properly, a per-symbol caller must not add to it.
        return loadDailyBarsBatch(List.of(symbol == null ? "" : symbol), bars, false)
            .getOrDefault(normalizeSymbol(symbol), List.of());
    }

    /**
     * Batch form of {@link #loadDailyBars(String, int)}: loads the most recent
     * {@code bars} daily candles for EVERY requested symbol in a handful of queries
     * instead of one pair of queries per symbol.
     *
     * <p>Ranking over ~1,100 tracked stocks was issuing up to ~2,200 round trips here
     * (a market_data query per symbol, plus a broker_market_candles query for each symbol
     * with a short series), which is what made the ranking stage of a refresh take
     * 45-180 seconds. The functional indexes added for this in SymbolSchemaGuard made the
     * market_data lookups cheap, but the broker fallback's OR'd predicate could not use
     * idx_bmc_usym* at all and sequentially scanned a 27M-row table on every call, so the
     * number of queries — and the shape of that predicate — both had to change.
     *
     * <p>Matching semantics are unchanged: the same predicates, the same
     * market_data-then-broker_market_candles fallback, and the same "most recent
     * {@code bars} rows, newest first" per symbol.
     *
     * @return bars keyed by {@link #normalizeSymbol(String)}; symbols with no history are absent.
     */
    private Map<String, List<CandleBar>> loadDailyBarsBatch(Collection<String> symbols, int bars) {
        return loadDailyBarsBatch(symbols, bars, true);
    }

    /**
     * @param fillTrailing reconstruct sessions missing from the daily feed. Worth it for a batch
     *        load; ruinous for a caller that loads one symbol at a time - see
     *        {@link #loadDailyBars(String, int)}.
     */
    private Map<String, List<CandleBar>> loadDailyBarsBatch(Collection<String> symbols, int bars,
                                                            boolean fillTrailing) {
        Map<String, List<CandleBar>> out = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;

        List<String> normalized = symbols.stream()
            .map(this::normalizeSymbol)
            .filter(sym -> !sym.isBlank())
            .distinct()
            .toList();
        if (normalized.isEmpty()) return out;

        // broker_market_candles is the single daily store. This used to read market_data first
        // and fall back to the candles for any symbol with fewer than 3 bars, but market_data
        // only ever held a copy of these same rows, so the first pass could add nothing.
        int batchSize = 900;
        for (int start = 0; start < normalized.size(); start += batchSize) {
            List<String> batch = normalized.subList(start, Math.min(start + batchSize, normalized.size()));
            collectBrokerBars(out, batch, bars);
        }

        // Last resort: fold intraday candles into one bar per day. Without this, a database
        // holding only intraday data yields no bars at all, every symbol fails the
        // "prevClose <= 0" guard, and the ranking silently finds nothing eligible - which is
        // exactly what happened after the 2026-08-23 rebuild restored intraday candles but
        // left market_data and DAILY candles empty. mergeDailyPrices already derives the
        // current price this way; this applies the same rule to the history.
        List<String> stillShort = normalized.stream()
            .filter(sym -> out.getOrDefault(sym, List.of()).size() < 3)
            .toList();
        if (!stillShort.isEmpty()) {
            Map<String, List<CandleBar>> agg = new HashMap<>();
            for (int start = 0; start < stillShort.size(); start += batchSize) {
                List<String> batch = stillShort.subList(start, Math.min(start + batchSize, stillShort.size()));
                collectIntradayAggregatedBars(agg, batch, bars);
            }
            for (String sym : stillShort) {
                List<CandleBar> series = agg.getOrDefault(sym, List.of());
                if (series.size() > out.getOrDefault(sym, List.of()).size()) out.put(sym, series);
            }
        }

        // The rescue above is all-or-nothing: it only helps a symbol with almost no daily
        // history. A symbol with months of daily bars that simply stops short of the last
        // session is not "short", so it was left a session behind even with that session
        // sitting in the same table as intraday rows. Fill those trailing gaps too.
        if (fillTrailing) fillTrailingIntradaySessions(out, normalized, bars, batchSize);
        return out;
    }

    /**
     * How many trailing sessions may be reconstructed from intraday when the daily series has
     * fallen behind. Two weeks is generous for a stalled fetch and still bounds the work; past
     * that the daily feed is broken badly enough that quietly papering over it does no favours.
     */
    private static final int MAX_TRAILING_INTRADAY_SESSIONS = 10;

    /**
     * Prepend sessions that exist as intraday candles but are missing from the daily series.
     *
     * <p>Daily bars and intraday candles arrive on separate schedules, so the daily rollup
     * routinely lags. On 2026-08-28 exactly 92 symbols got a daily bar while 2,954 traded and
     * were recorded intraday 09:15-15:26 - so every tracked stock's newest daily bar was
     * 08-27, and every screen keyed off "the newest bar is the current session" silently
     * reported a session-old answer. The ATR crossover export dated its own entries 08-27 on a
     * Monday, and listed 31 setups where the real session had 20.
     *
     * <p>A folded bar is a stand-in, not an equal. High and low are exact - they are extremes
     * over the same ticks - but the close is the last 5-minute close, not the official closing
     * price, which comes from the closing auction and can differ by a few paise (BFUTILITIE on
     * 08-27: 503.00 folded against 502.55 official). It is tagged {@code intraday-agg} so the
     * same-source volume rule will not average it against broker bars, and so anything else
     * that cares can tell the difference.
     *
     * <p>This never overwrites a real daily bar - only dates strictly newer than the symbol's
     * newest daily bar are added. So it self-heals: once the daily backfill catches up, the
     * real bars win and the fold stops being used with no further change.
     */
    private void fillTrailingIntradaySessions(Map<String, List<CandleBar>> out,
                                              List<String> symbols, int bars, int batchSize) {
        // Sessions the daily feed never produced, merged in from the shared fold.
        //
        // This used to run its own query per call. That cost about 120ms of PLANNING each time -
        // the planner re-examining eleven indexes on a 52-million-row table - which was invisible
        // for a batch load and ruinous for callers that load one symbol at a time: the uploaded
        // stock signals loop turned a seconds-long download into minutes. gapSessionBars() does
        // the fold once for the whole universe and caches it, so this is now a map lookup and
        // every caller can afford it, single-symbol ones included.
        Map<String, List<DayBar>> gaps = gapSessionBars();
        if (gaps.isEmpty()) return;

        int filled = 0;
        String newestFilled = null;
        for (String sym : symbols) {
            List<CandleBar> series = out.get(sym);
            if (series == null || series.isEmpty()) continue;

            String newestDaily = normalizeStoredDate(series.get(0).tradeDate);
            if (newestDaily == null || newestDaily.isBlank()) continue;

            List<DayBar> folded = gaps.get(sym);
            if (folded == null || folded.isEmpty()) continue;

            // Only sessions strictly newer than what this symbol already has. A real daily bar
            // is never displaced - the fold only ever fills the end of the series.
            List<CandleBar> add = new ArrayList<>();
            for (DayBar g : folded) {
                if (g.date().compareTo(newestDaily) > 0) {
                    add.add(new CandleBar(g.date(), g.close(), g.high(), g.low(), g.volume(),
                                          "intraday-agg"));
                }
            }
            if (add.isEmpty()) continue;

            add.sort((x, y) -> y.tradeDate.compareTo(x.tradeDate));   // newest first
            List<CandleBar> merged = new ArrayList<>(add);
            merged.addAll(series);
            if (merged.size() > bars) merged = new ArrayList<>(merged.subList(0, bars));
            out.put(sym, merged);
            filled++;
            String top = add.get(0).tradeDate;
            if (newestFilled == null || top.compareTo(newestFilled) > 0) newestFilled = top;
        }
        if (filled > 0) {
            log.debug("[Bars] {} symbol(s) extended to {} from the shared intraday fold.",
                      filled, newestFilled);
        }
    }

    /**
     * One folded bar per trading day strictly after {@code afterDate}, for the given symbols.
     *
     * <p>Same aggregation as {@link #collectIntradayAggregatedBars}, bounded by date instead of
     * by row count so it can be used to patch the end of an existing series.
     */
    @SuppressWarnings("unchecked")
    private void collectIntradaySessionsAfter(Map<String, List<CandleBar>> out,
                                              List<String> batch, String afterDate) {
        try {
            var query = em.createNativeQuery(
                "SELECT UPPER(COALESCE(symbol,'')) AS sym,trade_date," +
                "(ARRAY_AGG(close ORDER BY candle_time DESC))[1] AS close," +
                "MAX(high) AS high,MIN(low) AS low," +
                "SUM(COALESCE(total_volume,volume)) AS vol," +
                "'intraday-agg' AS source " +
                "FROM broker_market_candles " +
                "WHERE close IS NOT NULL AND close > 0 " +
                "AND UPPER(COALESCE(candle_mode,''))='INTRADAY' " +
                "AND trade_date > ? " +
                "AND UPPER(COALESCE(symbol,'')) IN (" + placeholders(batch.size()) + ") " +
                "GROUP BY UPPER(COALESCE(symbol,'')),trade_date " +
                "ORDER BY sym,trade_date DESC");
            query.setParameter(1, afterDate);
            for (int i = 0; i < batch.size(); i++) query.setParameter(i + 2, batch.get(i));
            for (Object[] r : (List<Object[]>) query.getResultList()) {
                addBar(out, asText(r[0]), MAX_TRAILING_INTRADAY_SESSIONS, r, 1);
            }
        } catch (Exception e) {
            log.debug("Trailing intraday sessions unavailable for {} symbols: {}",
                      batch.size(), e.getMessage());
        }
    }

    /**
     * One bar per trading day, aggregated from that day's intraday candles: first open, max
     * high, min low, last close, summed volume. Used only when neither market_data nor DAILY
     * candles can supply a series.
     */
    @SuppressWarnings("unchecked")
    private void collectIntradayAggregatedBars(Map<String, List<CandleBar>> out, List<String> batch, int bars) {
        try {
            var query = em.createNativeQuery(
                "SELECT sym,trade_date,close,high,low,vol,source FROM (" +
                "SELECT UPPER(COALESCE(symbol,'')) AS sym,trade_date," +
                "(ARRAY_AGG(close ORDER BY candle_time DESC))[1] AS close," +
                "MAX(high) AS high,MIN(low) AS low," +
                "SUM(COALESCE(total_volume,volume)) AS vol," +
                "'intraday-agg' AS source," +
                "ROW_NUMBER() OVER (PARTITION BY UPPER(COALESCE(symbol,'')) ORDER BY trade_date DESC) AS rn " +
                "FROM broker_market_candles " +
                "WHERE close IS NOT NULL AND close > 0 " +
                "AND UPPER(COALESCE(candle_mode,''))='INTRADAY' " +
                "AND UPPER(COALESCE(symbol,'')) IN (" + placeholders(batch.size()) + ") " +
                "GROUP BY UPPER(COALESCE(symbol,'')),trade_date) t " +
                "WHERE rn <= ? ORDER BY sym,trade_date DESC");
            int i = 0;
            for (; i < batch.size(); i++) query.setParameter(i + 1, batch.get(i));
            query.setParameter(i + 1, bars);
            for (Object[] r : (List<Object[]>) query.getResultList()) {
                addBar(out, asText(r[0]), bars, r, 1);
            }
        } catch (Exception e) {
            log.debug("Intraday-aggregated bars unavailable for {} symbols: {}", batch.size(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void collectBrokerBars(Map<String, List<CandleBar>> out, List<String> batch, int bars) {
        try {
            // Deliberately three UNION ALL branches rather than one OR'd predicate: with the
            // ORs, PostgreSQL cannot use idx_bmc_usym / idx_bmc_utsym / idx_bmc_utsym_eq and
            // falls back to a parallel sequential scan of this table (27M+ rows, seconds per
            // call). One branch per index turns it into three bitmap index scans.
            // Each branch tags its rows with the priority the single-symbol path used when a
            // row matched on more than one column (symbol, then trading_symbol, then the
            // -EQ-stripped form), and rows are de-duplicated by id keeping the best priority.
            // A trade_date floor was tried here and REVERTED. On its own the bounded query
            // measured 633ms against 2,459ms unbounded, but inside these three UNION branches it
            // pushed the planner off the symbol indexes and bars-batch went from 5.9 seconds to
            // 181.6. The saving is real but it cannot be taken this way; per-symbol LIMIT or a
            // narrower index would be the route, measured in situ rather than standalone.
            String in = placeholders(batch.size());
            String cols = ",trade_date,close,high,low,COALESCE(total_volume,volume),source " +
                          "FROM broker_market_candles " +
                          "WHERE close IS NOT NULL AND UPPER(COALESCE(candle_mode,''))='DAILY' AND ";
            var query = em.createNativeQuery(
                "SELECT id,sym,trade_date,close,high,low,vol,source FROM (" +
                "SELECT id,UPPER(COALESCE(symbol,'')) AS sym,1 AS pri,trade_date,close,high,low," +
                "COALESCE(total_volume,volume) AS vol,source " +
                "FROM broker_market_candles " +
                "WHERE close IS NOT NULL AND UPPER(COALESCE(candle_mode,''))='DAILY' " +
                "AND UPPER(COALESCE(symbol,'')) IN (" + in + ") " +
                "UNION ALL " +
                "SELECT id,UPPER(COALESCE(trading_symbol,'')),2" + cols +
                "UPPER(COALESCE(trading_symbol,'')) IN (" + in + ") " +
                "UNION ALL " +
                "SELECT id,UPPER(REPLACE(COALESCE(trading_symbol,''),'-EQ','')),3" + cols +
                "UPPER(REPLACE(COALESCE(trading_symbol,''),'-EQ','')) IN (" + in + ")" +
                ") u ORDER BY trade_date DESC, pri");
            for (int i = 0; i < batch.size(); i++) {
                query.setParameter(i + 1, batch.get(i));
                query.setParameter(batch.size() + i + 1, batch.get(i));
                query.setParameter(batch.size() * 2 + i + 1, batch.get(i));
            }
            Set<Object> seen = new HashSet<>();
            for (Object[] r : (List<Object[]>) query.getResultList()) {
                if (!seen.add(asText(r[0]))) continue;   // same physical row matched by another branch
                addBar(out, asText(r[1]), bars, r, 2);
            }
        } catch (Exception e) {
            log.debug("Batch broker bars unavailable for {} symbols: {}", batch.size(), e.getMessage());
        }
    }

    /** Appends one row to a symbol's series, keeping at most {@code bars} newest-first entries. */
    private void addBar(Map<String, List<CandleBar>> out, String key, int bars, Object[] r, int offset) {
        if (key.isBlank()) return;
        List<CandleBar> series = out.computeIfAbsent(key, k -> new ArrayList<>());
        if (series.size() >= bars) return;   // rows arrive newest-first, so this keeps the latest N
        series.add(new CandleBar(
            String.valueOf(r[offset]),
            getNumber(r[offset + 1]),
            getNumber(r[offset + 2]),
            getNumber(r[offset + 3]),
            getNumber(r[offset + 4]),
            String.valueOf(r[offset + 5] == null ? "BROKER" : r[offset + 5])
        ));
    }

    /**
     * Index of the PREVIOUS session's bar in a newest-first series.
     *
     * <p>Callers used to hard-code index 1, assuming bars[0] is always today's in-progress
     * candle. Daily candles lag — intraday there is usually no bar for today at all — so
     * bars[0] is normally the previous close already, and index 1 silently reached a session
     * too far back. That let stocks through the "previous close < base price" gate on a
     * two-day-old close (DLINKINDIA: compared 08-18's 418.85 instead of 08-19's 445.75).
     */
    /**
     * Index of the bar holding the PREVIOUS close - the session before the one the
     * current price belongs to.
     *
     * <p>During the session the current price is a live tick, so the previous session is
     * today's bar when one exists and the latest completed bar otherwise. Once the market
     * closes the current price IS the latest bar's close, so the previous close has to step
     * back one further. Without that step prevClose equals currentPrice after hours, which
     * makes any "crossed above X" test mathematically unable to fire - the Eligible Stocks
     * screen went empty every evening and all weekend for exactly this reason.
     */
    private int previousBarIndex(List<CandleBar> bars) {
        if (bars == null || bars.isEmpty()) return 0;
        // Skip the newest bar ONLY when it is today's session, because then it is the current
        // day rather than the previous close.
        //
        // This used to skip it whenever the market was closed, which is right only if the newest
        // bar happens to be today. When the stored bars trail the calendar - market shut since
        // Friday, or a backfill a few days behind - the newest bar is already a completed earlier
        // session, so skipping it returned the close from the day BEFORE the previous close. On
        // 2026-08-26 that reported SAGILITY's previous close as 42.56 (20 Aug) instead of 43.36
        // (21 Aug), which both mis-stated the figure and moved the "Previous Close < Base Price"
        // test onto the wrong day.
        if (hasTodayBar(bars)) return Math.min(1, bars.size() - 1);
        return 0;
    }

    /**
     * True when the current price is the latest stored bar's close rather than a live
     * tick, i.e. that bar is "today" for comparison purposes. Volume windows must line up
     * with the same bar the price came from.
     */
    private boolean currentPriceIsLatestBar(List<CandleBar> bars) {
        if (bars == null || bars.isEmpty()) return false;
        return hasTodayBar(bars) || !isMarketOpen();
    }

    /** True when the newest bar is for today, i.e. an in-progress session is present. */
    private boolean hasTodayBar(List<CandleBar> bars) {
        if (bars == null || bars.isEmpty()) return false;
        return DateUtil.today().equals(normalizeStoredDate(bars.get(0).tradeDate));
    }

    /**
     * How many same-feed bars the 20-day volume average needs before it is worth trusting.
     *
     * <p>Five is deliberately lenient. The stricter the bar, the more stocks abstain from the
     * volume test altogether while Dhan history is still thin - and an abstention lasting weeks
     * is its own kind of wrong. Five same-source days is a comparison rather than noise, and the
     * count rises on its own as more Dhan history is fetched.
     */
    private static final int MIN_SAME_SOURCE_VOL_BARS = 5;

    /**
     * Average volume over {@code count} bars from {@code start}, counting ONLY bars that came
     * from the same feed as {@code reference}.
     *
     * <p>The two feeds in this table do not measure the same thing. Across 2,793 stocks holding
     * both kinds of bar for the same period, the DERIVED rollups run a median 1.33x the volume
     * Dhan reports for the identical stock and dates - 1,189 of them more than 1.5x higher, 541
     * more than 1.5x lower. DERIVED bars are daily candles folded up from 51 million intraday
     * rows in a single pass on 25 Aug 2026; Dhan bars are the exchange's own daily figure, and
     * summing intraday prints does not reproduce it.
     *
     * <p>Mixing them silently broke "volume above its 20-day average". Dhan bars existed only for
     * 24-27 Aug, so the newest bar was DHAN while the 20-day window behind it was mostly DERIVED:
     * the ratio measured the gap between two feeds, not any change in trading. On 30 Aug all nine
     * crossing stocks "failed" on volume that way, and ELECTCAST - the one name whose DERIVED
     * bars run lower than its Dhan bars - "passed" for the same bad reason.
     *
     * @return the average, or 0 when fewer than {@code minBars} same-source bars are available.
     *         Callers already treat 0 as "no comparison possible", which is the honest answer -
     *         better than a confident number derived from two different measurements.
     */
    private double sameSourceAverageVolume(List<CandleBar> bars, int start, int count,
                                           CandleBar reference, int minBars) {
        if (bars == null || reference == null) return 0;
        String want = reference.source == null ? "" : reference.source.trim().toUpperCase(Locale.ROOT);
        int end = Math.min(bars.size(), start + count);
        double sum = 0;
        int n = 0;
        for (int i = start; i < end; i++) {
            CandleBar bar = bars.get(i);
            String src = bar.source == null ? "" : bar.source.trim().toUpperCase(Locale.ROOT);
            if (!src.equals(want)) continue;
            if (bar.volume > 0) { sum += bar.volume; n++; }
        }
        return n >= minBars ? sum / n : 0;
    }

    /**
     * The date {@code n} trading days after {@code fromDate}, skipping weekends and NSE holidays.
     *
     * <p>Used for the crossover's hold deadline. Counting calendar days would land the exit on a
     * day the market is shut, which is worse than useless in an instruction to close a position.
     *
     * @return null when {@code fromDate} is not a parseable ISO date, so the column blanks out
     *         instead of printing a date that was never computed from anything.
     */
    private String plusTradingDays(String fromDate, int n) {
        if (fromDate == null || fromDate.isBlank()) return null;
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(fromDate.trim());
            int added = 0;
            for (int guard = 0; added < n && guard < n * 4 + 40; guard++) {
                d = d.plusDays(1);
                if (marketCalendar.isTradingDay(d)) added++;
            }
            return d.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private double averageVolume(List<CandleBar> bars, int start, int count) {
        int end = Math.min(bars.size(), start + count);
        double sum = 0;
        int n = 0;
        for (int i = start; i < end; i++) {
            if (bars.get(i).volume > 0) {
                sum += bars.get(i).volume;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    private List<Double> aggregateWeeklyCloses(List<CandleBar> dailyBars) {
        Map<String, Double> weeklyCloseMap = new java.util.TreeMap<>(java.util.Collections.reverseOrder());
        java.time.format.DateTimeFormatter[] fmts = {
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")
        };
        for (CandleBar bar : dailyBars) {
            if (bar.close <= 0 || bar.tradeDate == null) continue;
            java.time.LocalDate date = null;
            for (var fmt : fmts) {
                try { date = java.time.LocalDate.parse(bar.tradeDate.trim().substring(0, 10), fmt); break; }
                catch (Exception ignored) {}
            }
            if (date == null) continue;
            java.time.temporal.WeekFields wf = java.time.temporal.WeekFields.of(java.time.DayOfWeek.MONDAY, 1);
            String weekKey = date.getYear() + "-W" + String.format("%02d", date.get(wf.weekOfWeekBasedYear()));
            weeklyCloseMap.putIfAbsent(weekKey, bar.close);
        }
        return new ArrayList<>(weeklyCloseMap.values());
    }

    private void applyDhan52WeekStats(Map<String, Object> result, String symbol, double currentPrice,
                                      Long institutionId, List<CandleBar> savedBars) {
        List<CandleBar> yearlyBars = savedBars == null ? List.of() : savedBars.subList(0, Math.min(252, savedBars.size()));
        String source = yearlyBars.size() >= 200 ? "SAVED_DHAN_CANDLES" : "DHAN_API";
        if (yearlyBars.size() < 200) {
            yearlyBars = fetchDhan52WeekBars(symbol, institutionId);
        }
        double week52High = yearlyBars.stream().mapToDouble(b -> b.high).filter(v -> v > 0).max().orElse(0);
        double week52Low = yearlyBars.stream().mapToDouble(b -> b.low).filter(v -> v > 0).min().orElse(0);
        double price = currentPrice > 0 ? currentPrice : yearlyBars.stream().mapToDouble(b -> b.close).filter(v -> v > 0).findFirst().orElse(0);
        result.put("week_52_high", week52High > 0 ? round2(week52High) : null);
        result.put("week_52_low", week52Low > 0 ? round2(week52Low) : null);
        result.put("below_52wh_pct", week52High > 0 && price > 0 ? round2((week52High - price) * 100.0 / week52High) : null);
        result.put("above_52wl_pct", week52Low > 0 && price > 0 ? round2((price - week52Low) * 100.0 / week52Low) : null);
        result.put("week_52_source", week52High > 0 && week52Low > 0 ? source : "DHAN_ERROR: 52-week Dhan candles unavailable.");
    }

    private void applySaved52WeekStats(Map<String, Object> result, double currentPrice, List<CandleBar> savedBars) {
        List<CandleBar> yearlyBars = savedBars == null ? List.of() : savedBars.subList(0, Math.min(252, savedBars.size()));
        double week52High = yearlyBars.stream().mapToDouble(b -> b.high).filter(v -> v > 0).max().orElse(0);
        double week52Low = yearlyBars.stream().mapToDouble(b -> b.low).filter(v -> v > 0).min().orElse(0);
        double price = currentPrice > 0 ? currentPrice : yearlyBars.stream().mapToDouble(b -> b.close).filter(v -> v > 0).findFirst().orElse(0);
        result.put("week_52_high", week52High > 0 ? round2(week52High) : null);
        result.put("week_52_low", week52Low > 0 ? round2(week52Low) : null);
        result.put("below_52wh_pct", week52High > 0 && price > 0 ? round2((week52High - price) * 100.0 / week52High) : null);
        result.put("above_52wl_pct", week52Low > 0 && price > 0 ? round2((price - week52Low) * 100.0 / week52Low) : null);
        result.put("week_52_source", week52High > 0 && week52Low > 0 ? "SAVED_CANDLES" : "DATA_REQUIRED: Run Dhan Historical OHLCV backfill for 52-week values.");
    }

    private List<CandleBar> fetchDhan52WeekBars(String symbol, Long institutionId) {
        try {
            Optional<DhanBrokerService.Credential> credential = findVerifiedDhanCredential(null, institutionId);
            if (credential.isEmpty()) return List.of();
            Optional<DhanInstrumentRef> ref = findDhanInstrument(normalizeSymbol(symbol));
            if (ref.isEmpty()) return List.of();
            LocalDate to = LocalDate.now(IST);
            LocalDate from = to.minusDays(370);
            return fetchDhanDailyCandles(ref.get(), from, to, credential.get());
        } catch (Exception e) {
            log.debug("Dhan 52-week fetch {}: {}", symbol, e.getMessage());
            return List.of();
        }
    }

    private int directionalVolumeSignal(double currentVolume, double referenceVolume, double currentClose, double referenceClose) {
        if (currentVolume <= 0 || referenceVolume <= 0 || currentVolume <= referenceVolume) return 0;
        if (currentClose > referenceClose) return 1;
        if (currentClose < referenceClose) return -1;
        return 0;
    }

    private int directionalTrendSignal(boolean condition, double currentClose, double referenceClose) {
        if (!condition) return 0;
        if (currentClose > referenceClose) return 1;
        if (currentClose < referenceClose) return -1;
        return 0;
    }

    private Double loadPeRatio(String symbol, double currentPrice) {
        String normalized = normalizeSymbol(symbol);
        Double pe = loadPeRatioFromSql(
            "SELECT pe,eps FROM broker_fundamentals WHERE UPPER(symbol)=? AND (pe IS NOT NULL OR eps IS NOT NULL) " +
            "ORDER BY COALESCE(period,'' ) DESC, created_at DESC LIMIT 1",
            normalized, currentPrice);
        if (pe != null) return pe;
        pe = loadPeRatioFromSql(
            "SELECT pe,eps FROM fundamentals WHERE UPPER(symbol)=? AND exchange='NSE' AND (pe IS NOT NULL OR eps IS NOT NULL) " +
            "ORDER BY COALESCE(period,'' ) DESC, created_at DESC LIMIT 1",
            normalized, currentPrice);
        if (pe != null) return pe;
        try {
            Object value = em.createNativeQuery("SELECT pe FROM stock_snapshot WHERE UPPER(symbol)=? AND pe IS NOT NULL LIMIT 1")
                .setParameter(1, normalized)
                .getSingleResult();
            double snapshotPe = getNumber(value);
            return snapshotPe > 0 ? round2(snapshotPe) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Double loadPeRatioFromSql(String sql, String symbol, double currentPrice) {
        try {
            List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter(1, symbol)
                .getResultList();
            if (rows.isEmpty()) return null;
            Object[] row = rows.get(0);
            double pe = getNumber(row[0]);
            double eps = getNumber(row[1]);
            if (pe > 0) return round2(pe);
            if (eps > 0 && currentPrice > 0) return round2(currentPrice / eps);
            return null;
        } catch (Exception e) {
            log.debug("PE unavailable for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> loadTrackerWeights(Long institutionId) {
        Map<String, Double> weights = new LinkedHashMap<>();
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT factor_key,weight FROM tracker_ranking_config " +
                "WHERE institution_id=? AND is_enabled=1")
                .setParameter(1, institutionId).getResultList();
            for (Object[] r : rows) {
                if (r[0] != null && r[1] != null)
                    weights.put(r[0].toString(), ((Number)r[1]).doubleValue());
            }
        } catch (Exception e) { log.debug("Load weights: {}", e.getMessage()); }
        return weights;
    }

    private record OhlcvHistory(List<Double> closes, List<Double> highs, List<Double> lows,
                                List<Double> volumes, List<Double> delivery) {}

    @SuppressWarnings("unchecked")
    private OhlcvHistory loadOhlcvHistory(String symbol, int bars) {
        return loadOhlcvHistory(symbol, bars, null);
    }

    /**
     * Price history for one symbol, optionally as it stood on a past date.
     *
     * @param asOf when non-null, only bars on or before this date are read, so a factor computed
     *        from the result can never see a price that had not happened yet. This is the whole
     *        mechanism behind reconstructing a past day's ranking: the scoring formula is untouched
     *        and only the window of data it is handed moves backwards.
     */
    private OhlcvHistory loadOhlcvHistory(String symbol, int bars, String asOf) {
        List<Double> closes = new ArrayList<>(), highs = new ArrayList<>(),
                     lows = new ArrayList<>(), volumes = new ArrayList<>(), delivery = new ArrayList<>();
        boolean pit = asOf != null && !asOf.isBlank();
        try {
            var q1 = em.createNativeQuery(
                "SELECT close,high,low,COALESCE(total_volume,volume),COALESCE(delivery_pct,0) " +
                "FROM broker_market_candles " +
                "WHERE symbol=? AND exchange='NSE' " + (pit ? "AND trade_date <= ? " : "") +
                "ORDER BY trade_date DESC LIMIT ?")
                .setParameter(1, symbol);
            if (pit) q1.setParameter(2, asOf).setParameter(3, bars);
            else q1.setParameter(2, bars);
            List<Object[]> rows = q1.getResultList();

            // Fall back to the daily candles when market_data has nothing. Every ranking factor
            // except Relative Strength comes from this one call, so an empty market_data silently
            // disabled ten of the eleven - each reporting "Need at least 20 ... records" while a
            // full daily history sat in broker_market_candles. Reading the candle master rather
            // than copying rows into market_data keeps one source of truth for this data.
            if (rows.size() < 20) {
                var q2 = em.createNativeQuery(
                    "SELECT close,high,low,COALESCE(total_volume,volume),0 FROM broker_market_candles " +
                    "WHERE UPPER(COALESCE(symbol,''))=UPPER(?) AND UPPER(COALESCE(candle_mode,''))='DAILY' " +
                    "AND close IS NOT NULL AND close>0 " + (pit ? "AND trade_date <= ? " : "") +
                    "ORDER BY trade_date DESC LIMIT ?")
                    .setParameter(1, symbol);
                if (pit) q2.setParameter(2, asOf).setParameter(3, bars);
                else q2.setParameter(2, bars);
                List<Object[]> daily = q2.getResultList();
                if (daily.size() > rows.size()) rows = daily;
            }
            for (int i = rows.size() - 1; i >= 0; i--) {
                Object[] r = rows.get(i);
                closes.add(r[0] != null ? ((Number) r[0]).doubleValue() : 0.0);
                highs.add(r[1] != null ? ((Number) r[1]).doubleValue() : 0.0);
                lows.add(r[2] != null ? ((Number) r[2]).doubleValue() : 0.0);
                volumes.add(r[3] != null ? ((Number) r[3]).doubleValue() : 0.0);
                double dp = r[4] != null ? ((Number) r[4]).doubleValue() : 0.0;
                if (dp > 0) delivery.add(dp);
            }
        } catch (Exception e) { /* empty lists */ }
        return new OhlcvHistory(closes, highs, lows, volumes,
            delivery.size() > 52 ? delivery.subList(delivery.size() - 52, delivery.size()) : delivery);
    }

    private double loadSectorScore(String sector) {
        try {
            Object r = em.createNativeQuery(
                "SELECT COALESCE(trend_score,0) FROM sector_data WHERE sector=? ORDER BY trade_date DESC LIMIT 1")
                .setParameter(1, sector).getSingleResult();
            return r != null ? ((Number)r).doubleValue() : -1.0;
        } catch (Exception e) { return -1.0; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> loadAllSectorScores() {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT DISTINCT ON (sector) sector, COALESCE(trend_score,0) " +
                "FROM sector_data ORDER BY sector, trade_date DESC")
                .getResultList();
            Map<String, Double> map = new HashMap<>();
            for (Object[] r : rows) {
                map.put((String) r[0], ((Number) r[1]).doubleValue());
            }
            return map;
        } catch (Exception e) { return Map.of(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getLatestRankingSummary(Long institutionId) {
        return getLatestRankingSummary(null, institutionId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getLatestRankingSummary(Long userId, Long institutionId) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT curr_avg_return,curr_win_ratio,curr_positive_count,prev_avg_return," +
                "prev_win_ratio,prev_positive_count,run_date FROM tracker_rankings " +
                "WHERE institution_id=?");
            if (userId != null) sql.append(" AND user_id=?");
            sql.append(" ORDER BY run_date DESC, id DESC LIMIT 1");
            var q = em.createNativeQuery(sql.toString()).setParameter(1, institutionId);
            if (userId != null) q.setParameter(2, userId);
            List<Object[]> rows = q.getResultList();
            if (rows.isEmpty()) return Map.of();
            Object[] r = rows.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("curr_avg_return",r[0]); m.put("curr_win_ratio",r[1]);
            m.put("curr_positive",r[2]); m.put("prev_avg_return",r[3]);
            m.put("prev_win_ratio",r[4]); m.put("prev_positive",r[5]);
            m.put("run_date",r[6]);
            return m;
        } catch (Exception e) { return Map.of(); }
    }

    @Transactional
    private void persistRankingRun(String runId, Long institutionId,
            int eligible, int ranked, String date, String runType,
            double currAvg, double currWr, int currPos, int currNeg,
            double prevAvg, double prevWr, int prevPos, int prevNeg,
            Map<String, Long> sectorDist) {
        persistRankingRun(runId, null, institutionId, eligible, ranked, date, runType,
            currAvg, currWr, currPos, currNeg, prevAvg, prevWr, prevPos, prevNeg, sectorDist);
    }

    @Transactional
    private void persistRankingRun(String runId, Long userId, Long institutionId,
            int eligible, int ranked, String date, String runType,
            double currAvg, double currWr, int currPos, int currNeg,
            double prevAvg, double prevWr, int prevPos, int prevNeg,
            Map<String, Long> sectorDist) {
        try {
            em.createNativeQuery(
                "INSERT INTO tracker_rankings(run_id,user_id,institution_id,run_date,run_type," +
                "eligible_count,ranked_count,curr_avg_return,curr_win_ratio,curr_positive_count," +
                "curr_negative_count,prev_avg_return,prev_win_ratio,prev_positive_count," +
                "prev_negative_count,sector_distribution_json,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1,runId).setParameter(2,userId).setParameter(3,institutionId)
                .setParameter(4,date).setParameter(5,runType)
                .setParameter(6,eligible).setParameter(7,ranked)
                .setParameter(8,Math.round(currAvg*100)/100.0).setParameter(9,Math.round(currWr*10)/10.0)
                .setParameter(10,currPos).setParameter(11,currNeg)
                .setParameter(12,Math.round(prevAvg*100)/100.0).setParameter(13,Math.round(prevWr*10)/10.0)
                .setParameter(14,prevPos).setParameter(15,prevNeg)
                .setParameter(16,toJson(sectorDist)).setParameter(17,DateUtil.now())
                .executeUpdate();
        } catch (Exception e) { log.warn("Persist ranking run: {}", e.getMessage()); }
    }

    @Transactional
    private void persistRankingItem(String runId, Long institutionId,
            int rank, Map<String, Object> stock, String date, boolean isCurrent) {
        persistRankingItem(runId, null, institutionId, rank, stock, date, isCurrent);
    }

    @Transactional
    private void persistRankingItem(String runId, Long userId, Long institutionId,
            int rank, Map<String, Object> stock, String date, boolean isCurrent) {
        try {
            String sym  = (String) stock.get("symbol");
            String upDate = (String) stock.getOrDefault("upload_date", "");
            long days = 0;
            try {
                days = java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.parse(upDate), LocalDate.parse(date));
            } catch (Exception ignored) {}

            em.createNativeQuery(
                "INSERT INTO tracker_ranking_items(run_id,user_id,institution_id,rank_position,symbol,sector," +
                "market_cap_name,composite_score,upload_date,base_price,current_price,return_pct," +
                "days_since_upload,factor_scores_json,is_current_top10,run_date,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1,runId).setParameter(2,userId).setParameter(3,institutionId).setParameter(4,rank)
                .setParameter(5,sym).setParameter(6,stock.getOrDefault("sector",""))
                .setParameter(7,stock.getOrDefault("market_cap_name",""))
                .setParameter(8,getD(stock,"composite_score"))
                .setParameter(9,upDate).setParameter(10,getD(stock,"base_price"))
                .setParameter(11,getD(stock,"current_price")).setParameter(12,getD(stock,"return_pct"))
                .setParameter(13,days).setParameter(14,toJson(stock.get("factor_scores")))
                .setParameter(15,isCurrent?1:0).setParameter(16,date).setParameter(17,DateUtil.now())
                .executeUpdate();
        } catch (Exception e) { log.debug("Persist ranking item: {}", e.getMessage()); }
    }

    private String insertRefreshLog(Long institutionId, Long triggeredBy, String type) {
        String startedAt = DateUtil.now();
        try {
            em.createNativeQuery(
                "INSERT INTO tracker_price_refresh_log(institution_id,refresh_type,triggered_by,api_source," +
                "started_at,status,created_at) VALUES(?,?,?,'dhan',?,'running',?)")
                .setParameter(1,institutionId).setParameter(2,type).setParameter(3,triggeredBy)
                .setParameter(4,startedAt).setParameter(5,DateUtil.now()).executeUpdate();
        } catch (Exception e) { log.debug("Refresh log insert: {}", e.getMessage()); }
        return startedAt;
    }

    private void completeRefreshLog(Long institutionId, String startedAt, int updated, int failed, long ms, String apiSource) {
        try {
            em.createNativeQuery(
                "UPDATE tracker_price_refresh_log SET status='completed',symbols_updated=?," +
                "symbols_failed=?,completed_at=?,duration_ms=?,api_source=? WHERE institution_id=? AND started_at=?")
                .setParameter(1,updated).setParameter(2,failed)
                .setParameter(3,DateUtil.now()).setParameter(4,ms)
                .setParameter(5,apiSource)
                .setParameter(6,institutionId).setParameter(7,startedAt).executeUpdate();
        } catch (Exception ignored) {}
    }

    private void updateUploadStatus(String uploadId, String status, String error) {
        try {
            em.createNativeQuery(
                "UPDATE stock_uploads SET status=?,error_message=? WHERE upload_id=?")
                .setParameter(1,status).setParameter(2,error).setParameter(3,uploadId).executeUpdate();
        } catch (Exception ignored) {}
    }

    private void auditLog(Long institutionId, Long userId, String action,
                          String entityType, String entityId, Object details) {
        try {
            em.createNativeQuery(
                "INSERT INTO tracker_audit_log(institution_id,user_id,action,entity_type,entity_id," +
                "details_json,created_at) VALUES(?,?,?,?,?,?,?)")
                .setParameter(1,institutionId).setParameter(2,userId).setParameter(3,action)
                .setParameter(4,entityType).setParameter(5,entityId).setParameter(6,toJson(details))
                .setParameter(7,DateUtil.now()).executeUpdate();
        } catch (Exception ignored) {}
    }

    private Map<String, Object> buildRankingResponse(String runId,
            List<Map<String, Object>> top10, List<Map<String, Object>> prevTop10,
            List<Map<String, Object>> allScored,
            double currAvg, double prevAvg, double currWr, double prevWr,
            int currPos, int prevPos, int eligible,
            Map<String, Double> weights, Map<String, Long> sectorDist) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("run_id",          runId);
        r.put("current_top10",   top10);
        r.put("previous_top10",  prevTop10);
        r.put("curr_avg_return", Math.round(currAvg*100)/100.0);
        r.put("curr_win_ratio",  Math.round(currWr*10)/10.0);
        r.put("curr_positive",   currPos);
        r.put("curr_negative",   top10.size() - currPos);
        r.put("prev_avg_return", Math.round(prevAvg*100)/100.0);
        r.put("prev_win_ratio",  Math.round(prevWr*10)/10.0);
        r.put("prev_positive",   prevPos);
        r.put("prev_negative",   prevTop10.size() - prevPos);
        r.put("eligible_count",  eligible);
        r.put("ranked_count",    top10.size());
        r.put("sector_distribution", sectorDist);
        r.put("run_date",        DateUtil.today());
        r.put("weights_used",    weights);
        r.putAll(rankingQualitySummary(top10));
        r.put("disclaimer","TradeRakshak™ rankings are for EDUCATIONAL purposes only. Not SEBI investment advice.");
        return r;
    }

    /**
     * The most recent session close at or before now: today 15:30 once the session
     * has ended, otherwise the previous weekday. Opening the app on a Sunday morning
     * therefore reports Friday 15:30, which is when the prices on screen last moved.
     * Exchange holidays are not modelled here - the stored candle date is preferred
     * over this whenever one exists.
     */
    private volatile String lastTradedAtCache = null;
    private volatile long lastTradedAtCacheTs = 0L;

    /**
     * When the newest stored price actually traded - the timestamp of the last intraday candle.
     *
     * <p>lastSessionCloseIST below is pure calendar arithmetic: it assumes 15:30, steps back over
     * weekends and knows nothing about holidays or about whether that session has any data. It
     * therefore names a time no trade happened at, and on a holiday names a day the market was
     * shut. The last stored candle is the honest answer - on 6 Sep 2026 that is 2026-09-04
     * 15:27, not the 15:30 the calendar would claim.
     *
     * <p>Cached for a minute: it only changes when new candles land, and the query touches a
     * 52-million-row table (215ms) which is not worth repeating per request.
     */
    private String lastTradedAtIST() {
        long now = System.currentTimeMillis();
        if (lastTradedAtCache != null && now - lastTradedAtCacheTs < 60_000L) return lastTradedAtCache;
        String value = null;
        try {
            Object v = em.createNativeQuery(
                "SELECT MAX(candle_time) FROM broker_market_candles " +
                "WHERE UPPER(COALESCE(candle_mode,'')) = 'INTRADAY' AND trade_date = " +
                "(SELECT MAX(trade_date) FROM broker_market_candles " +
                " WHERE UPPER(COALESCE(candle_mode,'')) = 'INTRADAY')")
                .getSingleResult();
            if (v != null) value = String.valueOf(v).replace('T', ' ');
        } catch (Exception e) {
            log.debug("Last traded time unavailable: {}", e.getMessage());
        }
        // Fall back to the calendar only when there is genuinely nothing stored to read.
        lastTradedAtCache = value != null && !value.isBlank() ? value : lastSessionCloseIST();
        lastTradedAtCacheTs = now;
        return lastTradedAtCache;
    }

    private String lastSessionCloseIST() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.ZonedDateTime close = now.withHour(15).withMinute(30).withSecond(0).withNano(0);
        if (close.isAfter(now)) close = close.minusDays(1);
        while (close.getDayOfWeek().getValue() >= 6) close = close.minusDays(1);
        return close.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * The trading session a price fetched right now belongs to.
     *
     * <p>current_price_date used to be set to {@code DateUtil.today()} - the calendar day the
     * refresh thread happened to run on, not the session the price came from. A refresh that ran
     * on Sunday 2026-11-01 stamped 1,264 of 1,271 tracked stocks with
     * {@code current_price_date = 2026-11-01}, a day on which the exchange was shut and no price
     * existed. The screen then reported a price "as of" a date that never had one.
     *
     * <p>Outside market hours Dhan's quote endpoint returns the last traded price, which belongs
     * to the last session that actually ran - so that is what is recorded. Weekends step back to
     * Friday, and a fetch before 09:15 belongs to the previous session rather than to a day that
     * has not opened yet.
     *
     * <p>Exchange holidays defeated the weekday rule, which is why this now reads the data. On
     * 2026-11-11 at 08:11 IST the quote endpoint came back after nine days down and returned
     * Monday's closes; stepping back one weekday from Wednesday gave Tuesday 2026-11-10, the
     * Diwali Balipratipada holiday, so 1,265 rows carried Monday's prices stamped with a date on
     * which the exchange never opened. That also stalled the stored-close fill, whose guard
     * compares current_price_date against a candle's session: labelled 11-10, the rows looked
     * newer than the 11-09 close that was actually in them, and nothing would have corrected
     * them. The last stored daily candle has neither failure mode - see
     * {@link #latestStoredSessionDate}.
     */
    private String priceSessionDate() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (isMarketOpen()) return now.toLocalDate().toString();

        // Out of session the last stored daily candle IS the last session that ran, so ask the
        // data rather than reason about the calendar. Clamped to today in case a candle is ever
        // stored ahead of the current date.
        String stored = latestStoredSessionDate();
        if (stored != null && stored.compareTo(now.toLocalDate().toString()) <= 0) return stored;

        // Nothing stored to go on: fall back to calendar arithmetic. isTradingDay() also consults
        // the configured holiday list, which is empty by default - hence preferring the data.
        java.time.LocalDate d = now.toLocalDate();
        if (now.toLocalTime().isBefore(java.time.LocalTime.of(9, 15))) d = d.minusDays(1);
        for (int i = 0; i < 10 && !isTradingDay(d); i++) d = d.minusDays(1);
        return d.toString();
    }

    /** Newest stored daily session, cached briefly - it changes once a day at most. */
    private final java.util.concurrent.atomic.AtomicReference<String[]> latestSessionCache =
        new java.util.concurrent.atomic.AtomicReference<>(null);
    private static final long LATEST_SESSION_TTL_MS = 60_000L;

    /**
     * The most recent trade_date present in the stored daily candles, or null if there are none.
     *
     * <p>This is the authoritative answer to "which session was last" and needs no maintenance:
     * it is a fact about the data rather than a rule about the calendar, so it is automatically
     * right about holidays, half days and special sessions alike. It identified Sunday 2026-11-08
     * as a real session - Diwali Muhurat trading - and Tuesday 2026-11-10 as not one, the Diwali
     * Balipratipada holiday, neither of which any weekday rule would get right.
     *
     * <p>Costs about 19 ms against idx_bmc_trade_date, and is called once per write batch, so the
     * result is cached for {@value #LATEST_SESSION_TTL_MS} ms.
     */
    private String latestStoredSessionDate() {
        String[] cached = latestSessionCache.get();
        long nowMs = System.currentTimeMillis();
        if (cached != null && nowMs - Long.parseLong(cached[1]) < LATEST_SESSION_TTL_MS) {
            return cached[0] == null || cached[0].isEmpty() ? null : cached[0];
        }
        String value = null;
        try {
            Object v = em.createNativeQuery(
                "SELECT MAX(trade_date) FROM broker_market_candles " +
                "WHERE (candle_mode IS NULL OR UPPER(candle_mode)='DAILY')")
                .getSingleResult();
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) value = s;
            }
        } catch (Exception e) {
            log.debug("Latest stored session lookup failed: {}", e.getMessage());
        }
        latestSessionCache.set(new String[]{value == null ? "" : value, String.valueOf(nowMs)});
        return value;
    }

    private boolean isMarketOpen() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        int hour = now.getHour(); int min = now.getMinute();
        int dow  = now.getDayOfWeek().getValue();
        if (dow >= 6) return false; // weekend
        int timeNow = hour * 100 + min;
        return timeNow >= 915 && timeNow <= 1530;
    }

    private String clean(String s) {
        return s == null ? null : s.trim().replace("\"","").replace("'","");
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonSafe(Object obj) {
        if (obj == null) return Map.of();
        if (obj instanceof Map) return (Map<String,Object>) obj;
        try { return mapper.readValue(obj.toString(), Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    private double getD(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? 0 : v instanceof Number n ? n.doubleValue() : 0;
    }

    private double getNumber(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); }
        catch (Exception e) { return 0; }
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); }
        catch (Exception e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rankingQualitySummary(List<Map<String, Object>> ranked) {
        Map<String, Map<String, Object>> skippedByKey = new LinkedHashMap<>();
        Set<String> considered = new LinkedHashSet<>();
        for (Map<String, Object> stock : ranked == null ? List.<Map<String, Object>>of() : ranked) {
            Object consideredObj = stock.get("considered_components");
            if (consideredObj instanceof Collection<?> c) {
                for (Object item : c) considered.add(String.valueOf(item));
            }
            Object skippedObj = stock.get("skipped_components");
            if (skippedObj == null) {
                Object factors = stock.get("factor_scores");
                if (factors instanceof Map<?, ?> fm) skippedObj = fm.get("_skipped_components");
            }
            if (skippedObj instanceof Collection<?> skippedList) {
                for (Object item : skippedList) {
                    if (!(item instanceof Map<?, ?> sm)) continue;
                    Object keyObj = sm.get("key");
                    Object labelObj = sm.get("label");
                    Object reasonObj = sm.get("reason");
                    String key = String.valueOf(keyObj != null ? keyObj : (labelObj != null ? labelObj : "unknown"));
                    String label = String.valueOf(labelObj != null ? labelObj : key);
                    String reason = String.valueOf(reasonObj != null ? reasonObj : "Required data is not available.");
                    Map<String, Object> row = skippedByKey.computeIfAbsent(key, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("key", key);
                        m.put("label", label);
                        m.put("reason", reason);
                        m.put("affected_stocks", 0);
                        return m;
                    });
                    row.put("affected_stocks", ((Number) row.get("affected_stocks")).intValue() + 1);
                }
            }
        }
        List<Map<String, Object>> skipped = new ArrayList<>(skippedByKey.values());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("partial_ranking", !skipped.isEmpty());
        result.put("considered_components", new ArrayList<>(considered));
        result.put("skipped_components", skipped);
        result.put("ranking_warning", skipped.isEmpty()
            ? "All available ranking components were considered."
            : "Partial ranking: Top 10 is displayed using available components. Missing components are skipped until their source data is available.");
        return result;
    }

    private static class FactorAccumulator {
        private final Map<String, Object> factors = new LinkedHashMap<>();
        private final List<Map<String, Object>> skipped = new ArrayList<>();
        private final List<String> consideredLabels = new ArrayList<>();
        private double composite = 0;
        private double totalWeight = 0;

        void consider(String key, String label, double score, double weight) {
            if (weight <= 0) return;
            factors.put(key, Map.of("score", score, "weight", weight, "label", label, "status", "considered"));
            consideredLabels.add(label);
            composite += score * weight / 10.0;
            totalWeight += weight;
        }

        void skip(String key, String label, double weight, String reason) {
            // The weight is recorded even though the component scored nothing, because "weight used"
            // is the total MINUS what was skipped - without it the panel cannot say how much of the
            // model actually ran.
            skipped.add(Map.of("key", key, "label", label, "reason", reason,
                               "weight", weight, "status", "skipped"));
        }

        double normalizedScore() {
            if (totalWeight <= 0) return 0;
            // Always divide by the weight that actually ran. The old form skipped this whenever the
            // available weight was within 5 of 100, which quietly under-scored a stock missing one
            // small component against one missing none - the very case normalising exists for.
            return composite / totalWeight * 100.0;
        }

        /** Fixed display order and short labels, so the breakdown reads the same for every stock. */
        private static final String[][] ORDER = {
            {"relative_strength",      "RS",        "Relative Strength"},
            {"volume_growth",          "VolGrowth", "Volume Growth"},
            {"trend_strength",         "Trend",     "Trend Strength"},
            {"rsi_strength",           "RSI",       "RSI Strength"},
            {"ma_position",            "MA",        "MA Position"},
            {"price_momentum",         "Momentum",  "Price Momentum"},
            {"week52_position",        "52W",       "52-Week Position"},
            {"breakout_strength",      "Breakout",  "Breakout Strength"},
            {"institutional_interest", "Inst",      "Institutional Interest"},
            {"liquidity",              "Liquidity", "Liquidity"},
            {"market_cap_quality",     "MktCap",    "Market Cap Quality"},
        };

        Map<String, Object> factorScores() {
            Map<String, Object> out = new LinkedHashMap<>(factors);

            // One row per component in a fixed order, whether it scored or not. A component with no
            // source data carries a null score - never a zero, which would read as "measured and
            // bad" rather than "not measured".
            List<Map<String, Object>> components = new ArrayList<>();
            Map<String, Map<String, Object>> skippedByKey = new LinkedHashMap<>();
            for (Map<String, Object> s : skipped) skippedByKey.put(String.valueOf(s.get("key")), s);

            for (String[] spec : ORDER) {
                String key = spec[0];
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", key);
                row.put("short_label", spec[1]);
                row.put("label", spec[2]);

                Object f = factors.get(key);
                if (f instanceof Map<?, ?> fm) {
                    row.put("score", fm.get("score"));
                    row.put("weight", fm.get("weight"));
                    row.put("available", true);
                } else {
                    Map<String, Object> sk = skippedByKey.get(key);
                    row.put("score", null);
                    row.put("weight", sk == null ? 0.0 : sk.get("weight"));
                    row.put("available", false);
                    row.put("reason", sk == null ? "Not evaluated." : sk.get("reason"));
                }
                components.add(row);
            }

            out.put("_components", components);
            out.put("_skipped_components", skipped);
            out.put("_considered_components", consideredLabels);
            out.put("_available_weight", totalWeight);
            out.put("_weight_used", totalWeight);
            return out;
        }

        List<Map<String, Object>> skipped() {
            return skipped;
        }

        List<String> consideredLabels() {
            return consideredLabels;
        }
    }

    private static class CandleBar {
        final String tradeDate;
        final double close;
        final double high;
        final double low;
        final double volume;
        final String source;

        CandleBar(String tradeDate, double close, double high, double low, double volume, String source) {
            this.tradeDate = tradeDate;
            this.close = close;
            this.high = high;
            this.low = low;
            this.volume = volume;
            this.source = source;
        }
    }

    /** The yyyy-MM-dd half of a stored candle timestamp, or null if it carries no date. */
    private static String stampDatePart(String stamp) {
        if (stamp == null) return null;
        int at = stamp.indexOf('T');
        if (at >= 10) return stamp.substring(0, at);
        return stamp.length() >= 10 && stamp.charAt(4) == '-' ? stamp.substring(0, 10) : null;
    }

    private static class TradeQuote {
        final double price;
        final String time;
        final String source;

        TradeQuote(double price, String time, String source) {
            this.price = price;
            this.time = time;
            this.source = source;
        }
    }

    private record SubscriptionStatus(String status, String reason) {}

    // ==========================================================================
    //  TRADE BATCH SYSTEM (Previous Top 10)
    // ==========================================================================

    /** Back-compat entry: buy at the live price now. */
    @Transactional
    public Map<String, Object> createTradeBatch(Long userId, Long institutionId, String buyTime, String tradeType) {
        return createTradeBatch(userId, institutionId, buyTime, tradeType, "NOW", null);
    }

    /**
     * Buy All 10 with a named time selection (spec §2). For historical periods the
     * buy price is taken from stored DB candles at the resolved date (never a live
     * API call); NOW/PAPER buys at the current price. Saves buy price + buy date/time.
     */
    @Transactional
    public Map<String, Object> createTradeBatch(Long userId, Long institutionId, String buyTime, String tradeType,
                                                String period, String customDate) {
        Map<String, Object> ranking = getLatestRankingFull(userId, institutionId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> currentTop10 = (List<Map<String, Object>>) ranking.get("current_top10");
        if (currentTop10 == null || currentTop10.isEmpty()) {
            return Map.of("success", false, "error", "No Current Top 10 stocks available to create trade batch.");
        }

        String now = DateUtil.now();
        String today = DateUtil.today();
        String p = period == null ? "NOW" : period.trim().toUpperCase(Locale.ROOT);
        String buyDate = resolvePeriodDate(p, customDate);
        boolean historical = !buyDate.equals(today) && !p.equals("NOW") && !p.equals("PAPER");
        // Was a hardcoded "15:20" while top10.trade.buy_time said 15:15, so a back-dated batch
        // priced itself five minutes away from the setting the screen displayed.
        if (buyTime == null || buyTime.isBlank()) buyTime = historical ? getTradeBuyTime() : now.substring(11, 16);
        String batchId = "TOP10_" + buyDate.replace("-", "") + "_" + buyTime.replace(":", "");

        try {
            Object existing = em.createNativeQuery(
                "SELECT batch_id FROM top10_trade_batches WHERE batch_id=? LIMIT 1")
                .setParameter(1, batchId).getResultList().stream().findFirst().orElse(null);
            if (existing != null) {
                return Map.of("success", false, "error", "Trade batch " + batchId + " already exists for today at this time.");
            }
        } catch (Exception ignored) {}

        em.createNativeQuery(
            "INSERT INTO top10_trade_batches(batch_id,user_id,institution_id,trade_date,buy_time,status,strategy,trade_type,total_stocks,created_at,updated_at) " +
            "VALUES(?,?,?,?,?,'OPEN','TOP10',?,?,?,?)")
            .setParameter(1, batchId).setParameter(2, userId).setParameter(3, institutionId)
            .setParameter(4, buyDate).setParameter(5, buyTime)
            .setParameter(6, tradeType != null ? tradeType : "PAPER")
            .setParameter(7, currentTop10.size())
            .setParameter(8, now).setParameter(9, now)
            .executeUpdate();

        int missingHistPrice = 0;
        for (Map<String, Object> stock : currentTop10) {
            String symbol = String.valueOf(stock.getOrDefault("symbol", ""));
            double buyPrice;
            if (historical) {
                Double h = dbCloseAt(symbol, buyDate);
                if (h != null && h > 0) {
                    buyPrice = h;
                } else {
                    buyPrice = getD(stock, "current_price"); // no stored candle at that date → fall back to live
                    missingHistPrice++;
                }
            } else {
                buyPrice = getD(stock, "current_price");
            }
            int rank = (int) getD(stock, "rank");
            String sector = String.valueOf(stock.getOrDefault("sector", ""));
            String mcap = String.valueOf(stock.getOrDefault("market_cap", ""));
            em.createNativeQuery(
                "INSERT INTO top10_trade_batch_stocks(batch_id,symbol,rank,buy_price,sector,market_cap,status,created_at) " +
                "VALUES(?,?,?,?,?,?,'OPEN',?)")
                .setParameter(1, batchId).setParameter(2, symbol).setParameter(3, rank)
                .setParameter(4, buyPrice).setParameter(5, sector).setParameter(6, mcap)
                .setParameter(7, now).executeUpdate();
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("batch_id", batchId);
        res.put("stocks", currentTop10.size());
        res.put("period", p);
        res.put("buy_date", buyDate);
        res.put("historical", historical);
        res.put("missing_prices", missingHistPrice);
        res.put("message", "Trade batch " + batchId + " created with " + currentTop10.size() + " stocks"
            + (historical ? " (buy prices from stored data on " + buyDate + ")" : "") + ".");
        return res;
    }

    @Transactional
    public Map<String, Object> sellTradeBatch(String batchId, Long userId, Long institutionId, String sellTime) {
        List<Object[]> batch = em.createNativeQuery(
            "SELECT status FROM top10_trade_batches WHERE batch_id=? AND institution_id=?")
            .setParameter(1, batchId).setParameter(2, institutionId).getResultList();
        if (batch.isEmpty()) return Map.of("success", false, "error", "Trade batch not found.");
        if ("CLOSED".equals(String.valueOf(batch.get(0)[0])))
            return Map.of("success", false, "error", "Trade batch already closed.");

        String now = DateUtil.now();
        if (sellTime == null || sellTime.isBlank()) sellTime = now.substring(11, 16);

        List<Object[]> stocks = em.createNativeQuery(
            "SELECT id,symbol,buy_price FROM top10_trade_batch_stocks WHERE batch_id=? AND status='OPEN'")
            .setParameter(1, batchId).getResultList();

        List<String> symbols = stocks.stream().map(r -> String.valueOf(r[1])).toList();
        Map<String, Double> currentPrices = resolveCurrentPricesBatch(symbols, institutionId);

        int winning = 0, losing = 0;
        double totalReturn = 0;
        for (Object[] s : stocks) {
            long id = ((Number) s[0]).longValue();
            String symbol = String.valueOf(s[1]);
            double buyPrice = getNumber(s[2]);
            double sellPrice = currentPrices.getOrDefault(normalizeSymbol(symbol), 0.0);
            if (sellPrice <= 0) sellPrice = buyPrice;
            double returnPct = buyPrice > 0 ? ((sellPrice - buyPrice) / buyPrice) * 100.0 : 0;
            returnPct = Math.round(returnPct * 100) / 100.0;
            if (returnPct >= 0) winning++; else losing++;
            totalReturn += returnPct;

            em.createNativeQuery(
                "UPDATE top10_trade_batch_stocks SET sell_price=?,return_pct=?,status='CLOSED' WHERE id=?")
                .setParameter(1, sellPrice).setParameter(2, returnPct).setParameter(3, id)
                .executeUpdate();
        }

        double avgReturn = stocks.isEmpty() ? 0 : Math.round((totalReturn / stocks.size()) * 100) / 100.0;

        em.createNativeQuery(
            "UPDATE top10_trade_batches SET sell_time=?,status='CLOSED',winning_stocks=?,losing_stocks=?," +
            "avg_return_pct=?,total_return_pct=?,closed_at=?,updated_at=? WHERE batch_id=?")
            .setParameter(1, sellTime).setParameter(2, winning).setParameter(3, losing)
            .setParameter(4, avgReturn).setParameter(5, Math.round(totalReturn * 100) / 100.0)
            .setParameter(6, now).setParameter(7, now).setParameter(8, batchId)
            .executeUpdate();

        return Map.of("success", true, "batch_id", batchId, "avg_return", avgReturn,
            "winning", winning, "losing", losing,
            "message", "Batch " + batchId + " closed. Avg return: " + avgReturn + "%");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getTradeBatches(Long userId, Long institutionId, String statusFilter, int limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT id,batch_id,trade_date,buy_time,sell_time,status,strategy,trade_type," +
            "total_stocks,winning_stocks,losing_stocks,avg_return_pct,total_return_pct,created_at,closed_at " +
            "FROM top10_trade_batches WHERE institution_id=?");
        List<Object> params = new ArrayList<>();
        params.add(institutionId);
        if (userId != null) { sql.append(" AND user_id=?"); params.add(userId); }
        if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equals(statusFilter)) {
            sql.append(" AND status=?"); params.add(statusFilter);
        }
        sql.append(" ORDER BY trade_date DESC, buy_time DESC LIMIT ?");
        params.add(limit);

        var query = em.createNativeQuery(sql.toString());
        for (int i = 0; i < params.size(); i++) query.setParameter(i + 1, params.get(i));
        List<Object[]> rows = query.getResultList();

        List<Map<String, Object>> batches = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> b = new LinkedHashMap<>();
            String bid = String.valueOf(r[1]);
            boolean batchOpen = "OPEN".equals(String.valueOf(r[5]));
            b.put("id", r[0]); b.put("batch_id", bid); b.put("trade_date", r[2]);
            b.put("buy_time", r[3]); b.put("sell_time", r[4]); b.put("status", r[5]);
            b.put("strategy", r[6]); b.put("trade_type", r[7]);
            b.put("total_stocks", toInt(r[8])); b.put("winning_stocks", toInt(r[9]));
            b.put("losing_stocks", toInt(r[10]));
            b.put("avg_return_pct", r[11] != null ? Math.round(((Number) r[11]).doubleValue() * 100) / 100.0 : 0);
            b.put("total_return_pct", r[12] != null ? Math.round(((Number) r[12]).doubleValue() * 100) / 100.0 : 0);
            b.put("created_at", r[13]); b.put("closed_at", r[14]);

            List<Object[]> stockRows = em.createNativeQuery(
                "SELECT symbol,rank,buy_price,sell_price,return_pct,sector,market_cap,status " +
                "FROM top10_trade_batch_stocks WHERE batch_id=? ORDER BY rank")
                .setParameter(1, bid).getResultList();

            // Spec §5: for a HELD (OPEN) batch, Return % is LIVE = (current LTP - buy)/buy.
            Map<String, Double> livePrices = java.util.Collections.emptyMap();
            if (batchOpen) {
                List<String> syms = stockRows.stream().map(sr -> String.valueOf(sr[0])).toList();
                livePrices = resolveCurrentPricesBatch(syms, institutionId);
            }
            double liveSum = 0; int liveWin = 0, liveLose = 0, liveCount = 0;
            List<Map<String, Object>> batchStocks = new ArrayList<>();
            for (Object[] sr : stockRows) {
                Map<String, Object> sm = new LinkedHashMap<>();
                String sym = String.valueOf(sr[0]);
                double buyPrice = getNumber(sr[2]);
                boolean stockClosed = "CLOSED".equals(String.valueOf(sr[7]));
                sm.put("symbol", sr[0]); sm.put("rank", sr[1]);
                sm.put("buy_price", sr[2]); sm.put("sell_price", sr[3]);
                sm.put("sector", sr[5]); sm.put("market_cap", sr[6]); sm.put("status", sr[7]);
                if (batchOpen && !stockClosed) {
                    double cur = livePrices.getOrDefault(normalizeSymbol(sym), 0.0);
                    double liveRet = (cur > 0 && buyPrice > 0) ? ((cur - buyPrice) / buyPrice) * 100.0 : 0;
                    liveRet = Math.round(liveRet * 100) / 100.0;
                    sm.put("current_price", cur > 0 ? cur : null);
                    sm.put("return_pct", liveRet);
                    sm.put("live", true);
                    if (cur > 0) { liveSum += liveRet; liveCount++; if (liveRet >= 0) liveWin++; else liveLose++; }
                } else {
                    sm.put("return_pct", sr[4] != null ? Math.round(((Number) sr[4]).doubleValue() * 100) / 100.0 : 0);
                    sm.put("live", false);
                }
                batchStocks.add(sm);
            }
            // Live batch average for held (OPEN) batches (spec §5 "Average Return %").
            if (batchOpen && liveCount > 0) {
                b.put("avg_return_pct", Math.round((liveSum / liveCount) * 100) / 100.0);
                b.put("winning_stocks", liveWin);
                b.put("losing_stocks", liveLose);
                b.put("live", true);
            }
            b.put("stocks", batchStocks);
            batches.add(b);
        }

        // Summary
        List<Object[]> summaryRows = em.createNativeQuery(
            "SELECT COUNT(*), " +
            "SUM(CASE WHEN avg_return_pct >= 0 THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN avg_return_pct < 0 THEN 1 ELSE 0 END), " +
            "AVG(avg_return_pct), MAX(avg_return_pct), MIN(avg_return_pct) " +
            "FROM top10_trade_batches WHERE institution_id=? AND status='CLOSED'" +
            (userId != null ? " AND user_id=" + userId : ""))
            .setParameter(1, institutionId).getResultList();

        Map<String, Object> summary = new LinkedHashMap<>();
        if (!summaryRows.isEmpty()) {
            Object[] s = summaryRows.get(0);
            summary.put("total_batches", toInt(s[0]));
            summary.put("winning_batches", toInt(s[1]));
            summary.put("losing_batches", toInt(s[2]));
            summary.put("avg_return", s[3] != null ? Math.round(((Number) s[3]).doubleValue() * 100) / 100.0 : 0);
            summary.put("highest_return", s[4] != null ? Math.round(((Number) s[4]).doubleValue() * 100) / 100.0 : 0);
            summary.put("lowest_return", s[5] != null ? Math.round(((Number) s[5]).doubleValue() * 100) / 100.0 : 0);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batches", batches);
        result.put("summary", summary);
        return result;
    }

    /**
     * Fills in the chart for past days on demand, instead of waiting for rankings to trickle
     * through five at a time.
     *
     * <p>Walks every ranking day that has no stored return and tries to compute it from the stored
     * candles. Reports what happened per day rather than only a total, because the two reasons a
     * day stays empty are completely different problems: a non-trading day never had prices and
     * never will, while a trading day with no next-day close is simply waiting for tomorrow.
     *
     * @param days how far back to look
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> recomputeDailyReturns(Long userId, Long institutionId, int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> detail = new ArrayList<>();
        int computed = 0, unavailable = 0;

        try {
            String from = java.time.LocalDate.now(IST).minusDays(Math.max(1, Math.min(days, 400))).toString();
            String today = DateUtil.today();
            StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT ON (run_date) run_id, run_date FROM tracker_rankings " +
                "WHERE institution_id=? AND run_date < ? AND run_date >= ? AND trade_avg_return IS NULL");
            if (userId != null) sql.append(" AND user_id=").append(userId);
            // One run per day, preferring the locked snapshot - the same rule the chart uses.
            sql.append(" ORDER BY run_date DESC, is_locked DESC NULLS LAST, id DESC");

            List<Object[]> runs = em.createNativeQuery(sql.toString())
                .setParameter(1, institutionId).setParameter(2, today).setParameter(3, from)
                .getResultList();

            for (Object[] run : runs) {
                String runId = asText(run[0]);
                String runDate = asText(run[1]);
                if (runDate.isEmpty()) continue;

                Map<String, Object> trade = computeTop10OvernightTradeReturn(institutionId, runId, runDate);
                int symbolCount = ((Number) trade.getOrDefault("symbol_count", 0)).intValue();

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", runDate);
                if (symbolCount == 0) {
                    unavailable++;
                    row.put("status", "unavailable");
                    row.put("reason", explainMissingDay(runDate));
                    detail.add(row);
                    continue;
                }
                em.createNativeQuery(
                    "UPDATE tracker_rankings SET trade_avg_return=?, trade_winners=?, trade_losers=?, " +
                    "trade_symbol_count=?, trade_source=? WHERE run_id=? AND institution_id=?")
                    .setParameter(1, trade.get("avg_return"))
                    .setParameter(2, trade.get("winners"))
                    .setParameter(3, trade.get("losers"))
                    .setParameter(4, trade.get("symbol_count"))
                    .setParameter(5, trade.get("source"))
                    .setParameter(6, runId)
                    .setParameter(7, institutionId)
                    .executeUpdate();
                computed++;
                row.put("status", "computed");
                row.put("avg_return_pct", trade.get("avg_return"));
                row.put("stocks", symbolCount);
                detail.add(row);
            }
            out.put("success", true);
        } catch (Exception e) {
            log.warn("Recompute of daily returns failed: {}", e.getMessage());
            out.put("success", false);
            out.put("error", "Could not recompute past days: " + e.getMessage());
        }

        out.put("computed", computed);
        out.put("unavailable", unavailable);
        out.put("days", detail);
        out.put("message", computed == 0 && unavailable == 0
            ? "Every ranking day already has a return; the chart is up to date."
            : computed + " day(s) added to the chart"
              + (unavailable > 0 ? ", " + unavailable + " could not be measured (see details)." : "."));
        return out;
    }

    /** Why a given ranking day could not produce a return, in words rather than a code. */
    private String explainMissingDay(String runDate) {
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(runDate);
            if (!isTradingDay(d)) {
                return "Not a trading day, so there were never any prices to measure.";
            }
            Object n = em.createNativeQuery(
                "SELECT COUNT(*) FROM broker_market_candles WHERE trade_date > ? " +
                "AND UPPER(COALESCE(candle_mode,'')) = 'INTRADAY'")
                .setParameter(1, runDate).getSingleResult();
            long after = n == null ? 0 : ((Number) n).longValue();
            if (after == 0) {
                return "Waiting for the next trading day's prices - the sell side does not exist yet.";
            }
            return "No stored price for these stocks at the buy or sell time.";
        } catch (Exception e) {
            return "Could not determine why.";
        }
    }

    /**
     * One bar per real trading day: the return the day's official Top 10 actually produced.
     *
     * <p>The chart used to read {@code top10_trade_batches}, which only holds batches created by
     * hand with Buy All 10 / Sell All 10. With none created it drew nothing, even though the daily
     * rankings had returns stored all along.
     *
     * <p>A day can hold many ranking runs - the engine re-ranks every few minutes - so this takes
     * exactly one per day: the locked run where one exists, since that is the 15:20 snapshot the
     * day is judged on, and otherwise the most recent run of that day. Days whose return could not
     * be computed are left out rather than drawn as zero, because a missing measurement and a flat
     * day are not the same thing.
     *
     * <p>Output keys match {@link #getTradeBatchTrend} so the existing chart renders it unchanged.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getDailyRankingTrend(Long userId, Long institutionId, int limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT DISTINCT ON (run_date) run_date, trade_avg_return, trade_winners, trade_losers, " +
            "ranked_count, is_locked, run_id " +
            "FROM tracker_rankings WHERE institution_id=? AND trade_avg_return IS NOT NULL");
        if (userId != null) sql.append(" AND user_id=").append(userId);
        // DISTINCT ON keeps the first row per run_date, so order decides which run represents the
        // day: locked first, then the newest.
        sql.append(" ORDER BY run_date DESC, is_locked DESC NULLS LAST, id DESC LIMIT ?");

        List<Object[]> rows;
        try {
            rows = em.createNativeQuery(sql.toString())
                .setParameter(1, institutionId)
                .setParameter(2, Math.max(1, Math.min(limit, 400)))
                .getResultList();
        } catch (Exception e) {
            log.warn("Daily ranking trend unavailable: {}", e.getMessage());
            return List.of();
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            String day = asText(r[0]);
            m.put("batch_id", "DAY-" + day);          // the chart tracks rows by batch_id
            m.put("trade_date", day);
            m.put("buy_time", getTradeBuyTime());
            m.put("sell_time", getTradeSellTime());
            m.put("avg_return_pct", r[1] == null ? 0
                : Math.round(((Number) r[1]).doubleValue() * 100) / 100.0);
            m.put("total_return_pct", m.get("avg_return_pct"));
            m.put("winning_stocks", toInt(r[2]));
            m.put("losing_stocks", toInt(r[3]));
            m.put("total_stocks", toInt(r[4]));
            m.put("locked", toInt(r[5]) == 1);
            m.put("run_id", asText(r[6]));
            m.put("strategy", "Daily Top 10");
            m.put("trade_type", "BUY");
            trend.add(m);
        }
        // Oldest first, so the chart reads left to right in time.
        Collections.reverse(trend);
        return trend;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getTradeBatchTrend(Long userId, Long institutionId, int limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT batch_id,trade_date,buy_time,sell_time,avg_return_pct,total_return_pct," +
            "winning_stocks,losing_stocks,total_stocks,strategy,trade_type " +
            "FROM top10_trade_batches WHERE institution_id=? AND status='CLOSED'");
        if (userId != null) sql.append(" AND user_id=").append(userId);
        sql.append(" ORDER BY trade_date ASC, buy_time ASC LIMIT ?");

        List<Object[]> rows = em.createNativeQuery(sql.toString())
            .setParameter(1, institutionId).setParameter(2, limit).getResultList();

        List<Map<String, Object>> trend = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batch_id", r[0]); m.put("trade_date", r[1]);
            m.put("buy_time", r[2]); m.put("sell_time", r[3]);
            m.put("avg_return_pct", r[4] != null ? Math.round(((Number) r[4]).doubleValue() * 100) / 100.0 : 0);
            m.put("total_return_pct", r[5] != null ? Math.round(((Number) r[5]).doubleValue() * 100) / 100.0 : 0);
            m.put("winning_stocks", toInt(r[6])); m.put("losing_stocks", toInt(r[7]));
            m.put("total_stocks", toInt(r[8])); m.put("strategy", r[9]); m.put("trade_type", r[10]);
            trend.add(m);
        }
        return trend;
    }

    // =========================================================================
    //  HISTORICAL TRADE ANALYSIS ENGINE  (BUYER / SELLER)
    //
    //  One engine, one direction flag. BUYER opens with a buy and closes with a
    //  sell; SELLER opens with a sell and closes with a buyback. Everything that
    //  differs between them is expressed as entry/exit rather than buy/sell, so
    //  the P&L, return, capital and aggregation code is written once.
    //
    //  Reads stored rankings and stored candles only - never the live feed - so
    //  a result is reproducible and the screen works outside market hours.
    // =========================================================================

    /** Trading session bounds in IST. Kept here so validation and messages agree. */
    private static final int SESSION_OPEN_HHMM  = 915;
    private static final int SESSION_CLOSE_HHMM = 1530;

    /** Why a timestamp cannot be used, or null when it is a valid trading moment. */
    private String sessionRejection(String date, String hhmm, String label) {
        if (date == null || date.isBlank() || hhmm == null || hhmm.isBlank())
            return "Please select all required Date and Time fields.";
        java.time.LocalDate d;
        try { d = java.time.LocalDate.parse(date); }
        catch (Exception e) { return label + " date is not a valid date."; }

        if (d.isAfter(java.time.LocalDate.now(IST)))
            return label + " date is in the future. Pick a date that has already traded.";
        java.time.DayOfWeek dow = d.getDayOfWeek();
        if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY)
            return label + " date falls on a weekend. Please select a valid trading date and time.";
        if (isMarketHoliday(d))
            return label + " date is an exchange holiday. Please select a valid trading date and time.";

        String t = normalizeHhmm(hhmm, "");
        if (t.isEmpty()) return label + " time is not a valid time.";
        int hm = Integer.parseInt(t.substring(0, 2)) * 100 + Integer.parseInt(t.substring(3));
        if (hm < SESSION_OPEN_HHMM || hm > SESSION_CLOSE_HHMM)
            return label + " time is outside the trading session (09:15-15:30 IST)."
                 + " Please select a valid market trading time.";
        return null;
    }

    /**
     * Traded price for one symbol at one moment, under the chosen matching rule.
     *
     * <p>EXACT takes only a candle stamped at that minute. PREVIOUS walks back to the last
     * trade at or before it, NEXT forward to the first at or after it, and NEAREST tries
     * back then forward. The daily close is the last resort for a day with no intraday
     * history. Never returns 0 for "not found" - a missing price stays null so the caller
     * can exclude the stock instead of treating it as free.
     */
    private Double priceAt(String symbol, String date, String hhmm, String rule) {
        if (symbol == null || date == null || hhmm == null) return null;
        try {
            String up = symbol.trim().toUpperCase(Locale.ROOT);
            String norm = normalizeSymbol(symbol);
            List<String> keys = norm.equals(up) ? List.of(up) : List.of(up, norm);
            // 'T', not a space - same rule as findIntradayBuyQuote above. candle_time is stored
            // ISO-style ("2026-08-04T15:20:00") and compared as text; ' ' (32) sorts below 'T' (84),
            // so a space-separated upper bound excludes every candle of the day and this returned
            // no price at all. (tracker_rankings.created_at IS space-separated - do not change
            // the rankingRunAsOf bounds to match this.)
            String dayStart = date + "T00:00:00";
            String target   = date + "T" + hhmm + ":59";
            String exactLo  = date + "T" + hhmm + ":00";
            String dayEnd   = date + "T23:59:59";
            String r = rule == null ? "NEXT" : rule.trim().toUpperCase(Locale.ROOT);

            Double px = null;
            switch (r) {
                case "EXACT":
                    px = intradayPick("symbol", keys, exactLo, target, true);
                    if (px == null) px = intradayPick("trading_symbol", keys, exactLo, target, true);
                    // EXACT deliberately does not fall back to a neighbouring candle or the
                    // daily close - a missing trade at that minute must read as missing, never
                    // as a guess. A non-positive close is missing too, not a free share.
                    return (px != null && px > 0) ? px : null;
                case "PREVIOUS":
                    px = intradayPick("symbol", keys, dayStart, target, true);
                    if (px == null) px = intradayPick("trading_symbol", keys, dayStart, target, true);
                    break;
                case "NEAREST":
                    px = intradayPick("symbol", keys, dayStart, target, true);
                    if (px == null) px = intradayPick("trading_symbol", keys, dayStart, target, true);
                    if (px == null) px = intradayPick("symbol", keys, target, dayEnd, false);
                    if (px == null) px = intradayPick("trading_symbol", keys, target, dayEnd, false);
                    break;
                default:                             // NEXT available market price
                    px = intradayPick("symbol", keys, target, dayEnd, false);
                    if (px == null) px = intradayPick("trading_symbol", keys, target, dayEnd, false);
                    break;
            }
            if (px == null || px <= 0) px = dbCloseAt(symbol, date);
            return (px != null && px > 0) ? px : null;
        } catch (Exception e) {
            log.debug("priceAt failed {} {} {} ({}): {}", symbol, date, hhmm, rule, e.getMessage());
            return null;
        }
    }

    /** Per-share P&L. A long gains when the exit is higher, a short when the buyback is lower. */
    static double perSharePnl(double entry, double exit, boolean isBuyer) {
        return isBuyer ? (exit - entry) : (entry - exit);
    }

    /** Whole shares affordable at the opening price. No capital, or no price, buys nothing. */
    static long sharesFor(double capital, double entryPrice) {
        if (capital <= 0 || entryPrice <= 0) return 0;
        return (long) Math.floor(capital / entryPrice);
    }

    /**
     * Dates that actually have a stored ranking run, newest first. Turns an "unavailable"
     * message into something actionable rather than a dead end.
     */
    private List<String> availableRankingDates(Long userId, Long institutionId, int limit) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT run_date FROM tracker_rankings WHERE institution_id=?");
            if (userId != null) sql.append(" AND user_id=").append(userId);
            sql.append(" ORDER BY run_date DESC LIMIT ").append(Math.max(1, Math.min(limit, 30)));
            List<?> rows = em.createNativeQuery(sql.toString())
                .setParameter(1, institutionId).getResultList();
            return rows.stream().map(String::valueOf).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Replays one historical trade across the Top 10 that was ranked at the primary moment.
     *
     * <p>BUYER: primary = buy, secondary = sell. SELLER: primary = sell, secondary = buyback.
     * The Top 10 is always the snapshot at the PRIMARY moment - a stock that later dropped out
     * of the ranking is still part of the trade, because it was in the portfolio when it opened.
     *
     * <p>With no secondary moment supplied the engine returns the snapshot and entry prices
     * only, which is the first half of the workflow (pick a direction and a moment, see the
     * list before committing to the close).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeHistoricalTrade(
            Long userId, Long institutionId, String tradeDirection,
            String primaryDate, String primaryTime,
            String secondaryDate, String secondaryTime,
            Double capitalPerStock, String priceRule, List<String> onlySymbols) {

        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> stocks = new ArrayList<>();
        out.put("stocks", stocks);

        String dir = tradeDirection == null ? "" : tradeDirection.trim().toUpperCase(Locale.ROOT);
        if (!dir.equals("BUY") && !dir.equals("SELL")) {
            out.put("success", false);
            out.put("error", "Please select a Trade Direction (Buyer or Seller) before calculating.");
            return out;
        }
        boolean isBuyer = dir.equals("BUY");
        out.put("trade_direction", dir);

        String primaryLabel   = isBuyer ? "Buy" : "Sell";
        String secondaryLabel = isBuyer ? "Sell" : "Buy";
        out.put("primary_label", primaryLabel);
        out.put("secondary_label", secondaryLabel);

        String bad = sessionRejection(primaryDate, primaryTime, primaryLabel);
        if (bad != null) { out.put("success", false); out.put("error", bad); return out; }

        // 15:15, not 15:20. priceAt("EXACT") looks only inside [hh:mm:00, hh:mm:59] and returns
        // null rather than a neighbouring bar - deliberately, so a missing trade reads as
        // missing. Candles are hourly now, anchored on the 09:15 open, so the last bar starts
        // at 15:15 and nothing begins at 15:20: every EXACT row would have read "no trade".
        String pTime = normalizeHhmm(primaryTime, "15:15");
        boolean hasSecondary = secondaryDate != null && !secondaryDate.isBlank()
                            && secondaryTime != null && !secondaryTime.isBlank();
        String sTime = null;
        if (hasSecondary) {
            bad = sessionRejection(secondaryDate, secondaryTime, secondaryLabel);
            if (bad != null) { out.put("success", false); out.put("error", bad); return out; }
            sTime = normalizeHhmm(secondaryTime, "15:15");
            // A position closes after it opens, whichever way round the legs are named.
            java.time.LocalDateTime p = java.time.LocalDateTime.parse(primaryDate + "T" + pTime + ":00");
            java.time.LocalDateTime s = java.time.LocalDateTime.parse(secondaryDate + "T" + sTime + ":00");
            if (!s.isAfter(p)) {
                out.put("success", false);
                out.put("error", secondaryLabel + " date and time must be after the "
                    + primaryLabel.toLowerCase(Locale.ROOT) + " date and time.");
                return out;
            }
        }

        String rule = priceRule == null || priceRule.isBlank() ? "NEXT"
                    : priceRule.trim().toUpperCase(Locale.ROOT);
        out.put("price_rule", rule);

        // ---- The Top 10 exactly as it stood at the PRIMARY moment -----------
        RankingRunRef run = rankingRunAsOf(userId, institutionId, primaryDate + " " + pTime + ":59");
        List<Map<String, Object>> items = run == null ? List.of() : loadRankingItems(run.runId());
        if (items.isEmpty()) {
            List<String> have = availableRankingDates(userId, institutionId, 10);
            String msg = "Historical Top 10 data is unavailable for the selected "
                + primaryLabel.toLowerCase(Locale.ROOT) + " date and time.";
            if (!have.isEmpty()) msg += " Ranking data exists for: " + String.join(", ", have) + ".";
            out.put("success", false);
            out.put("error", msg);
            out.put("available_dates", have);
            return out;
        }
        out.put("run_id", run.runId());
        out.put("run_date", run.runDate());
        out.put("run_created_at", run.createdAt());
        out.put("primary_date", primaryDate);
        out.put("primary_time", pTime);
        out.put("secondary_date", hasSecondary ? secondaryDate : null);
        out.put("secondary_time", hasSecondary ? sTime : null);

        // A non-null list is an explicit selection even when it is empty: "Deselect All" must
        // show nothing, not silently widen back to the whole Top 10.
        java.util.Set<String> keep = null;
        if (onlySymbols != null) {
            keep = new java.util.HashSet<>();
            for (String s : onlySymbols) if (s != null) keep.add(normalizeSymbol(s));
        }

        double cap = capitalPerStock == null ? 0 : capitalPerStock;
        if (cap < 0) {
            out.put("success", false);
            out.put("error", "Capital cannot be negative.");
            return out;
        }

        double sumReturn = 0, totalInvest = 0, totalPnl = 0;
        int valid = 0, win = 0, lose = 0, rank = 0;

        for (Map<String, Object> it : items) {
            rank++;
            String sym = String.valueOf(it.getOrDefault("symbol", ""));
            if (keep != null && !keep.contains(normalizeSymbol(sym))) continue;

            Double entry = priceAt(sym, primaryDate, pTime, rule);
            Double exit  = hasSecondary ? priceAt(sym, secondaryDate, sTime, rule) : null;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", sym);
            m.put("rank", rank);
            m.put("sector", it.getOrDefault("sector", ""));
            m.put("entry_date", primaryDate);
            m.put("entry_time", pTime);
            m.put("exit_date", hasSecondary ? secondaryDate : null);
            m.put("exit_time", hasSecondary ? sTime : null);
            m.put("entry_price", entry);
            m.put("exit_price", exit);
            // Buy/sell aliases so the table can label columns by direction without re-deriving.
            m.put("buy_price",  isBuyer ? entry : exit);
            m.put("sell_price", isBuyer ? exit  : entry);
            m.put("buy_date",   isBuyer ? primaryDate : (hasSecondary ? secondaryDate : null));
            m.put("buy_time",   isBuyer ? pTime : sTime);
            m.put("sell_date",  isBuyer ? (hasSecondary ? secondaryDate : null) : primaryDate);
            m.put("sell_time",  isBuyer ? sTime : pTime);

            if (entry == null) {
                m.put("status", "NO_ENTRY_PRICE");
                m.put("message", "Historical price unavailable for the selected "
                    + primaryLabel.toLowerCase(Locale.ROOT) + " timestamp.");
                m.put("price_available", false);
                m.put("return_pct", null); m.put("pnl", null);
                m.put("quantity", null); m.put("investment", null);
                stocks.add(m); continue;
            }

            // Quantity comes off the ENTRY price for both directions: it is the price at which
            // the position is opened, whether that open is a buy or a short sell.
            long qty = sharesFor(cap, entry);
            double positionValue = qty * entry;
            m.put("quantity", qty);
            m.put("investment", Math.round(positionValue * 100.0) / 100.0);

            if (!hasSecondary) {
                m.put("status", "OPEN");
                m.put("price_available", true);
                m.put("return_pct", null); m.put("pnl", null);
                stocks.add(m); continue;
            }
            if (exit == null) {
                m.put("status", "NO_EXIT_PRICE");
                m.put("message", "Historical price unavailable for the selected "
                    + secondaryLabel.toLowerCase(Locale.ROOT) + " timestamp.");
                m.put("price_available", false);
                m.put("return_pct", null); m.put("pnl", null);
                stocks.add(m); continue;
            }

            // Long gains when the exit is higher; a short gains when the buyback is lower.
            double perShare = perSharePnl(entry, exit, isBuyer);
            double ret = Math.round((perShare / entry) * 10000.0) / 100.0;
            double pnl = Math.round(perShare * qty * 100.0) / 100.0;

            m.put("status", "CLOSED");
            m.put("price_available", true);
            m.put("pnl_per_share", Math.round(perShare * 100.0) / 100.0);
            m.put("return_pct", ret);
            m.put("pnl", pnl);
            stocks.add(m);

            sumReturn += ret; valid++;
            totalInvest += positionValue; totalPnl += pnl;
            if (ret >= 0) win++; else lose++;
        }

        int considered = stocks.size();
        out.put("success", true);
        out.put("total_stocks", considered);
        out.put("valid_stocks", valid);
        out.put("winning_stocks", win);
        out.put("losing_stocks", lose);
        out.put("capital_per_stock", cap);
        // Average of the per-stock percentages, over the stocks that actually priced.
        out.put("average_return_pct", valid > 0 ? Math.round((sumReturn / valid) * 100.0) / 100.0 : 0.0);
        out.put("total_investment", Math.round(totalInvest * 100.0) / 100.0);
        out.put("total_pnl", Math.round(totalPnl * 100.0) / 100.0);
        // Capital-weighted, which is NOT the same number as the simple average above.
        out.put("overall_return_pct", totalInvest > 0
            ? Math.round((totalPnl / totalInvest) * 10000.0) / 100.0 : null);
        out.put("closed", hasSecondary);
        if (considered == 0) {
            out.put("warning", "No stocks are selected. Tick at least one stock to see results.");
        } else if (hasSecondary && valid == 0) {
            out.put("warning", "No stock in this Top 10 has stored prices at both selected "
                + "timestamps, so no return could be calculated.");
        }
        return out;
    }

    // =========================================================================
    //  DATE-RANGE BACKTEST — replay the Current Top 10 across a chosen window.
    //  Pure DB reads (stored rankings + stored candles), so it runs outside market
    //  hours exactly the same as during them.
    // =========================================================================

    /** Previous trading day, walking back over weekends and configured holidays. */
    private java.time.LocalDate previousTradingDay(java.time.LocalDate d) {
        java.time.LocalDate p = d.minusDays(1);
        int guard = 0;
        while (!isTradingDay(p) && guard++ < 30) p = p.minusDays(1);
        return p;
    }

    /**
     * For every trading day in [fromDate, toDate]: the Top 10 that was current at
     * {@code buyTime} that day, bought at that moment and sold at {@code sellTime} the
     * next trading day, plus the Top 10 from the day before (date-1) for comparison.
     *
     * <p>Each row carries {@code trade_date} and {@code avg_return_pct}, the two fields the
     * average-return bars already read, so the same chart renders a range without changes.
     * A day whose stocks have no stored price at those moments is reported with
     * {@code counted = 0} rather than being quietly dropped or estimated.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRangeBacktest(Long userId, Long institutionId,
                                                String fromDate, String toDate,
                                                String buyTime, String sellTime) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> days = new ArrayList<>();
        try {
            String bt = normalizeHhmm(buyTime, "15:15");
            String st = normalizeHhmm(sellTime, "10:15");
            java.time.LocalDate from = java.time.LocalDate.parse(fromDate);
            java.time.LocalDate to   = java.time.LocalDate.parse(toDate);
            if (to.isBefore(from)) { java.time.LocalDate t = from; from = to; to = t; }
            // 180 trading days is roughly nine months - enough for any review window, and a
            // hard stop so a mistyped year cannot walk the loop for a decade.
            if (from.isBefore(to.minusDays(400))) from = to.minusDays(400);

            double sum = 0; int counted = 0, win = 0, lose = 0, skipped = 0;
            for (java.time.LocalDate d = from; !d.isAfter(to) && days.size() < 180; d = d.plusDays(1)) {
                if (!isTradingDay(d)) continue;
                String day = d.toString();

                RankingRunRef run = rankingRunAsOf(userId, institutionId, day + " " + bt + ":59");
                if (run == null) { skipped++; continue; }
                List<Map<String, Object>> items = loadRankingItems(run.runId());
                if (items.isEmpty()) { skipped++; continue; }

                String sellDay = nextTradingDayWithCandles(day);
                java.time.LocalDate prevDay = previousTradingDay(d);
                RankingRunRef prevRun = rankingRunAsOf(userId, institutionId, prevDay + " " + bt + ":59");

                List<Map<String, Object>> stocks = new ArrayList<>();
                double daySum = 0; int dayCount = 0, dayWin = 0, dayLose = 0, dayMissing = 0;
                for (int i = 0; i < items.size(); i++) {
                    String sym = String.valueOf(items.get(i).getOrDefault("symbol", ""));
                    Double buy = intradayCloseAt(sym, day, bt);
                    if (buy == null || buy <= 0) buy = dbCloseAt(sym, day);
                    Double sell = sellDay == null ? null : intradayCloseAt(sym, sellDay, st);
                    if ((sell == null || sell <= 0) && sellDay != null) sell = dbCloseAt(sym, sellDay);

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("symbol", sym);
                    m.put("rank", i + 1);
                    m.put("sector", items.get(i).getOrDefault("sector", ""));
                    m.put("buy_date", day);   m.put("buy_time", bt);
                    m.put("sell_date", sellDay); m.put("sell_time", sellDay == null ? null : st);
                    m.put("buy_price", buy);  m.put("sell_price", sell);
                    if (buy != null && buy > 0 && sell != null && sell > 0) {
                        double ret = Math.round(((sell - buy) / buy) * 10000.0) / 100.0;
                        m.put("return_pct", ret);
                        m.put("price_available", true);
                        daySum += ret; dayCount++;
                        if (ret >= 0) dayWin++; else dayLose++;
                    } else {
                        m.put("return_pct", null);
                        m.put("price_available", false);
                        dayMissing++;
                    }
                    stocks.add(m);
                }

                double dayAvg = dayCount > 0 ? Math.round((daySum / dayCount) * 100) / 100.0 : 0;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("batch_id", "RANGE-" + day);      // the chart tracks rows by batch_id
                row.put("trade_date", day);
                row.put("avg_return_pct", dayAvg);        // the value the bars plot
                row.put("buy_time", bt);
                row.put("sell_time", sellDay == null ? null : st);
                row.put("sell_date", sellDay);
                row.put("run_id", run.runId());
                row.put("total_stocks", stocks.size());
                row.put("winning_stocks", dayWin);
                row.put("losing_stocks", dayLose);
                row.put("counted", dayCount);
                row.put("missing_prices", dayMissing);
                // date-1: the Top 10 as it stood the previous trading day.
                row.put("previous_date", prevDay.toString());
                row.put("previous_run_id", prevRun == null ? null : prevRun.runId());
                row.put("previous_top10", prevRun == null ? List.of()
                    : loadRankingItems(prevRun.runId()).stream()
                        .map(x -> String.valueOf(x.getOrDefault("symbol", ""))).toList());
                row.put("stocks", stocks);
                days.add(row);

                if (dayCount > 0) { sum += dayAvg; counted++; if (dayAvg >= 0) win++; else lose++; }
            }

            out.put("success", true);
            out.put("from_date", from.toString());
            out.put("to_date", to.toString());
            out.put("buy_time", bt);
            out.put("sell_time", st);
            out.put("days", days);
            out.put("days_with_data", counted);
            out.put("days_skipped", skipped);
            out.put("winning_days", win);
            out.put("losing_days", lose);
            out.put("avg_return", counted > 0 ? Math.round((sum / counted) * 100) / 100.0 : 0);
        } catch (Exception e) {
            log.error("Range backtest failed: {}", e.getMessage(), e);
            out.put("success", false);
            out.put("error", "Range backtest failed: " + e.getMessage());
            out.put("days", days);
        }
        return out;
    }

    // =========================================================================
    //  CONFIGURABLE BACKTEST TREND (spec §7) — actual stored intraday prices only.
    //  For each of the last N ranking days: take that day's Top 10, buy at buyTime
    //  (nearest intraday candle that day), sell the NEXT trading day at sellTime,
    //  compute real return per stock, average across the 10. Never ranking average,
    //  never estimated — a stock with no candle at that time is skipped for that day.
    // =========================================================================
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getBacktestTrend(Long userId, Long institutionId, String buyTime, String sellTime, int days) {
        String bt = normalizeHhmm(buyTime, "15:15");
        String st = normalizeHhmm(sellTime, "10:15");
        int n = Math.max(1, Math.min(days, 90));

        // Distinct ranking days that produced a Current Top 10, most recent first.
        var dq = em.createNativeQuery(
            "SELECT DISTINCT run_date FROM tracker_ranking_items " +
            "WHERE institution_id=? AND is_current_top10=1 " + (userId != null ? "AND user_id=" + userId + " " : "") +
            "ORDER BY run_date DESC LIMIT ?")
            .setParameter(1, institutionId).setParameter(2, n);
        List<Object> dateRows = dq.getResultList();
        List<String> buyDays = new ArrayList<>();
        for (Object d : dateRows) if (d != null) buyDays.add(String.valueOf(d));
        java.util.Collections.reverse(buyDays); // oldest → newest for the chart

        List<Map<String, Object>> trend = new ArrayList<>();
        for (String d : buyDays) {
            String sellDay = nextTradingDayWithCandles(d);
            List<Object> syms = em.createNativeQuery(
                "SELECT symbol FROM tracker_ranking_items WHERE institution_id=? AND run_date=? AND is_current_top10=1 " +
                (userId != null ? "AND user_id=" + userId + " " : "") + "ORDER BY rank_position LIMIT 10")
                .setParameter(1, institutionId).setParameter(2, d).getResultList();

            double sum = 0; int win = 0, lose = 0, cnt = 0, missing = 0;
            for (Object so : syms) {
                String sym = String.valueOf(so);
                Double buyPx = intradayCloseAt(sym, d, bt);
                Double sellPx = sellDay == null ? null : intradayCloseAt(sym, sellDay, st);
                if (buyPx != null && buyPx > 0 && sellPx != null && sellPx > 0) {
                    double ret = Math.round(((sellPx - buyPx) / buyPx) * 100 * 100) / 100.0;
                    sum += ret; cnt++; if (ret >= 0) win++; else lose++;
                } else {
                    missing++;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batch_id", "BT_" + d.replace("-", "") + "_" + bt.replace(":", "")); // for trackBy
            m.put("trade_date", d);
            m.put("sell_date", sellDay);
            m.put("buy_time", bt);
            m.put("sell_time", st);
            m.put("avg_return_pct", cnt > 0 ? Math.round((sum / cnt) * 100) / 100.0 : 0);
            m.put("winning_stocks", win);
            m.put("losing_stocks", lose);
            m.put("total_stocks", cnt);
            m.put("missing_prices", missing);
            m.put("source", "INTRADAY");
            trend.add(m);
        }
        return trend;
    }

    /**
     * Close of the intraday candle to fill at for {@code date} + {@code hhmm}.
     *
     * <p>Prefers the last candle at or before the requested minute - that is the price actually
     * available to a trader at that moment - and only looks forward when the session had not yet
     * printed one (a 10:00 sell on a symbol whose first tick is 10:07, say).
     *
     * <p>Matching runs against the bare columns rather than {@code UPPER(TRIM(...))}, so the
     * (symbol_name, candle_time) index applies; both symbol columns are stored already upper-cased
     * and trimmed. The previous form paired a day-wide LIKE with ORDER BY ABS(candle_time - target),
     * neither of which an index can serve: every lookup full-scanned ~5.4M rows at ~3.8s, so a
     * 60-day back-test of ten stocks would have taken over an hour.
     */
    private Double intradayCloseAt(String symbol, String date, String hhmm) {
        if (symbol == null || date == null) return null;
        try {
            String up = symbol.trim().toUpperCase(Locale.ROOT);
            String norm = normalizeSymbol(symbol);
            List<String> keys = norm.equals(up) ? List.of(up) : List.of(up, norm);
            // 'T', not a space - see priceAt above. A space-separated bound matched no candle.
            String dayStart = date + "T00:00:00";
            String target   = date + "T" + hhmm + ":59";
            String dayEnd   = date + "T23:59:59";

            Double px = intradayPick("symbol", keys, dayStart, target, true);
            if (px == null) px = intradayPick("trading_symbol", keys, dayStart, target, true);
            if (px == null) px = intradayPick("symbol", keys, target, dayEnd, false);
            if (px == null) px = intradayPick("trading_symbol", keys, target, dayEnd, false);
            return px;
        } catch (Exception e) {
            log.debug("Intraday close lookup failed for {} {} {}: {}", symbol, date, hhmm, e.getMessage());
            return null;
        }
    }

    /**
     * One indexed seek: the newest candle in [from,to] when {@code newest}, otherwise the oldest.
     * {@code column} is a caller-supplied literal, never user input.
     */
    private Double intradayPick(String column, List<String> keys, String from, String to, boolean newest) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) in.append(i == 0 ? "?" : ",?");
        var q = em.createNativeQuery(
            "SELECT close FROM broker_market_candles " +
            "WHERE candle_mode='INTRADAY' AND close IS NOT NULL AND close > 0 " +
            "AND " + column + " IN (" + in + ") " +
            "AND candle_time >= ? AND candle_time <= ? " +
            "ORDER BY candle_time " + (newest ? "DESC" : "ASC") + " LIMIT 1");
        int idx = 1;
        for (String k : keys) q.setParameter(idx++, k);
        q.setParameter(idx++, from);
        q.setParameter(idx, to);
        List<Object> rows = q.getResultList();
        if (rows.isEmpty()) return null;
        double c = getNumber(rows.get(0));
        return c > 0 ? c : null;
    }

    /** Next weekday (non-holiday) after {date} that has intraday candles stored. */
    private String nextTradingDayWithCandles(String date) {
        try {
            java.time.LocalDate day = java.time.LocalDate.parse(date);
            for (int i = 1; i <= 10; i++) {
                java.time.LocalDate cand = day.plusDays(i);
                if (!isTradingDay(cand)) continue;
                String cs = cand.toString();
                Object hit = em.createNativeQuery(
                    "SELECT 1 FROM broker_market_candles WHERE candle_mode='INTRADAY' AND candle_time LIKE ? LIMIT 1")
                    .setParameter(1, cs + "%").getResultList().stream().findFirst().orElse(null);
                if (hit != null) return cs;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // =========================================================================
    //  NAMED TIME-SELECTION (spec §2) — DB-only historical buy pricing.
    //  Resolves a named period (PAPER/NOW/1D/2D/3D/5D/1W/2W/1M/CUSTOM) to an
    //  ISO buy date. NOW/PAPER = today (buy at the live price). Historical
    //  periods look back N *trading* days. Buy prices come only from stored
    //  candles — never a live API call, never a ranking average.
    // =========================================================================
    String resolvePeriodDate(String period, String customDate) {
        String p = period == null ? "NOW" : period.trim().toUpperCase(Locale.ROOT);
        if (p.equals("CUSTOM")) {
            if (customDate != null && customDate.trim().matches("\\d{4}-\\d{2}-\\d{2}")) return customDate.trim();
            return DateUtil.today();
        }
        int back;
        switch (p) {
            case "PAPER": case "NOW": case "0D": back = 0; break;
            case "1D": back = 1; break;
            case "2D": back = 2; break;
            case "3D": back = 3; break;
            case "5D": back = 5; break;
            case "1W": back = 5; break;
            case "2W": back = 10; break;
            case "1M": back = 21; break;
            default:   back = 0;
        }
        if (back <= 0) return DateUtil.today();
        java.time.LocalDate d = java.time.LocalDate.now();
        int stepped = 0, guard = 0;
        while (stepped < back && guard < back * 4 + 40) {
            d = d.minusDays(1);
            if (isTradingDay(d)) stepped++;
            guard++;
        }
        return d.toString();
    }

    /** DB-only close for a symbol on a date: intraday near the last bar, else DAILY, else null. */
    Double dbCloseAt(String symbol, String date) {
        if (symbol == null || date == null) return null;
        // 15:20 was not a bar start once bars became hourly (09:15, 10:15 ... 15:15).
        // intradayCloseAt looks backwards before it looks forwards, so this kept working by
        // luck; naming the real last bucket makes it the first seek instead of the fallback.
        Double intr = intradayCloseAt(symbol, date, "15:15");
        if (intr != null && intr > 0) return intr;
        try {
            String up = symbol.trim().toUpperCase(Locale.ROOT);
            String norm = normalizeSymbol(symbol);
            List<Object> rows = em.createNativeQuery(
                "SELECT close FROM broker_market_candles " +
                "WHERE candle_mode='DAILY' AND close IS NOT NULL AND close > 0 " +
                "AND trade_date LIKE ? " +
                "AND (UPPER(TRIM(symbol))=? OR UPPER(TRIM(trading_symbol))=? OR UPPER(TRIM(symbol))=? OR UPPER(TRIM(trading_symbol))=?) " +
                "LIMIT 1")
                .setParameter(1, date + "%")
                .setParameter(2, up).setParameter(3, up)
                .setParameter(4, norm).setParameter(5, norm)
                .getResultList();
            if (!rows.isEmpty()) { double c = getNumber(rows.get(0)); return c > 0 ? c : null; }
        } catch (Exception e) {
            log.debug("Daily close lookup failed for {} {}: {}", symbol, date, e.getMessage());
        }
        return null;
    }

    /**
     * Live preview of the Current Top 10 as a Previous-Top-10 portfolio at the
     * selected time period — no DB write. Buy price = DB close at the resolved
     * historical date (or live price for NOW/PAPER); current price = live/last;
     * return % = (current - buy) / buy. Powers the "changing the period refreshes
     * all calculations immediately" behaviour. Ranking logic is NOT applied here.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTop10Preview(Long userId, Long institutionId, String period, String customDate) {
        return getTop10Preview(userId, institutionId, period, customDate, null);
    }

    /**
     * @param buyTimeHHmm time-of-day the portfolio is priced at, e.g. "15:15". The Top 10 shown
     *        is the ranking run that was current at {@code date + buyTimeHHmm} — picking 1 Day
     *        at 15:00 shows an earlier day's list when that day's run only happened later in
     *        the evening. Defaults to the 15:20 daily snapshot convention.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTop10Preview(Long userId, Long institutionId, String period,
                                               String customDate, String buyTimeHHmm) {
        return getTop10Preview(userId, institutionId, period, customDate, buyTimeHHmm, null, null, null);
    }

    /**
     * Adds a sell leg so a completed round trip can be back-tested.
     *
     * <p>Leave the sell period unset (or NOW) and the sell side stays the live price, which
     * is the original behaviour. Choose a sell day and time and the sell price is read from
     * history exactly the way the buy price is, so Return % becomes the result of the trade
     * between two chosen moments rather than "buy then mark to market".
     *
     * @param sellPeriod     same vocabulary as the buy period (NOW, 1D, CUSTOM, ...)
     * @param sellCustomDate yyyy-MM-dd when sellPeriod is CUSTOM
     * @param sellTimeHHmm   time of day to price the sell leg at, e.g. "10:15"
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTop10Preview(Long userId, Long institutionId, String period,
                                               String customDate, String buyTimeHHmm,
                                               String sellPeriod, String sellCustomDate,
                                               String sellTimeHHmm) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String p = period == null ? "NOW" : period.trim().toUpperCase(Locale.ROOT);
            String buyDate = resolvePeriodDate(p, customDate);
            // No day chosen and the market is shut: price off the last completed session
            // rather than a live tick that is really just that session's close wearing
            // today's date. Gives every row a real buy date and time to show.
            boolean defaultToLastSession = p.equals("NOW") && !isMarketOpen();
            String lastSessionDay = lastSessionCloseIST().substring(0, 10);
            String today = DateUtil.today();
            String buyTime = normalizeHhmm(buyTimeHHmm);
            boolean historical = !buyDate.equals(today) && !p.equals("NOW") && !p.equals("PAPER");
            String source = "CURRENT_TOP10";

            List<Map<String, Object>> currentTop10 = null;

            // Day + time selection: show the Top 10 exactly as it stood at that moment.
            // Rankings re-run through the day, so 1 Day @ 15:00 legitimately resolves to an
            // EARLIER day's list when the selected day's run only happened that evening.
            // PAPER keeps buying at the live price, which is the point of paper trading.
            if (!p.equals("PAPER")) {
                // Rebuild the day's list under the CURRENT rules rather than reading the run that
                // was stored at the time.
                //
                // Those two answered different questions and showed completely different stocks for
                // the same date - on 2026-08-27, CDSL/KFINTECH/WONDERLA... from storage against
                // INOXINDIA/MEDANTA/SIGMAADV... from the current rules, with no overlap at all. A
                // stored run carries whichever entry test was live at the minute it ran, and this
                // application's entry test changed several times: a 0-5% return band, then a
                // crossing requirement, then the present "Current Price >= Base Price AND Current
                // Volume > 20-Day Average Volume". The factor weights changed too (V43), so even a
                // shared stock could be ranked differently.
                //
                // Recomputing means every day on screen is judged by one rule, so this panel and
                // the trend section below it can no longer disagree about the same date. The trade
                // off is deliberate and worth stating: this is what today's rules select for that
                // day, not a record of what was picked at the time.
                // capPerSector=true: this section is the historical counterpart of Today top 10
                // Ranking Stocks, so it selects its ten the same way - sector cap included.
                List<Map<String, Object>> rebuilt =
                    reconstructTop10AsOf(userId, institutionId, buyDate, null, false, false, true);
                if (!rebuilt.isEmpty()) {
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (int i = 0; i < rebuilt.size(); i++) {
                        Map<String, Object> s = new LinkedHashMap<>(rebuilt.get(i));
                        s.put("rank", i + 1);
                        s.putIfAbsent("score", s.get("composite_score"));
                        items.add(s);
                    }
                    currentTop10 = items;
                    historical = true;
                    source = "REBUILT_CURRENT_RULES";
                    out.put("run_date", buyDate);
                    out.put("selection_basis",
                        "Rebuilt under the current entry test (Current Price >= Base Price AND "
                      + "Current Volume > 20-Day Average Volume), not the run stored on the day.");
                }
            }

            out.put("buy_time", buyTime);

            // Sell leg. Unset or NOW keeps the live price - the original behaviour, and the
            // reason Buy and Current read the same on a NOW preview: both are the live tick.
            String sp = sellPeriod == null || sellPeriod.isBlank()
                ? "NOW" : sellPeriod.trim().toUpperCase(Locale.ROOT);
            boolean sellHistorical = !sp.equals("NOW") && !sp.equals("PAPER");
            // The sell is the session AFTER the buy, not a second period resolved on its own.
            //
            // Both legs defaulted to "1D" and resolvePeriodDate counts back from today, so both
            // landed on the same session - 4 Sep bought at 15:20 and sold at 11:00 the SAME
            // morning, four hours before the purchase it was closing. The rule this section
            // states is one trade held overnight: bought at the cut-off, sold at 11:00 on the
            // next market day. plusTradingDays walks the market calendar, so a Friday buy sells
            // on Monday and a holiday is stepped over.
            //
            // An explicitly chosen CUSTOM sell date is still honoured - that control exists so a
            // particular pair of dates can be examined - but it is validated below.
            String sellDate = !sellHistorical ? null
                : (sp.equals("CUSTOM") ? resolvePeriodDate(sp, sellCustomDate)
                                       : plusTradingDays(buyDate, 1));
            String sellTime = normalizeHhmm(sellTimeHHmm);
            // Unselected sell leg outside market hours falls back the same way as the buy leg.
            boolean sellFromLastSession = !sellHistorical && !isMarketOpen();
            String sellShownDate = sellHistorical ? sellDate : (sellFromLastSession ? lastSessionDay : DateUtil.today());
            String sellShownTime = sellHistorical ? sellTime : (sellFromLastSession ? "15:30" : "LIVE");
            // Compared as instants, not as dates. The old test only caught a sell on an EARLIER
            // day, so a sell at 11:00 on the buy's own day passed straight through it.
            if (sellHistorical && sellDate != null) {
                String buyStamp  = buyDate + "T" + (buyTime == null ? "00:00" : buyTime);
                String sellStamp = sellDate + "T" + (sellTime == null ? "00:00" : sellTime);
                if (sellStamp.compareTo(buyStamp) <= 0) {
                    out.put("success", false);
                    out.put("error", "The sell (" + sellDate + " " + sellTime + ") is not after the buy ("
                                   + buyDate + " " + buyTime + ").");
                    out.put("stocks", new ArrayList<>());
                    return out;
                }
            }
            out.put("sell_period", sp);
            out.put("sell_date", sellDate);
            out.put("sell_time", sellHistorical ? sellTime : null);
            out.put("sell_historical", sellHistorical);

            if (currentTop10 == null) {
                Map<String, Object> ranking = getLatestRankingFull(userId, institutionId, false);
                currentTop10 = (List<Map<String, Object>>) ranking.get("current_top10");
            }
            if (currentTop10 == null || currentTop10.isEmpty()) {
                out.put("success", false);
                out.put("error", p.equals("NOW")
                    ? "No previous ranking run found yet. Run ranking on at least two different days."
                    : "No Current Top 10 stocks available. Run ranking first.");
                out.put("stocks", new ArrayList<>());
                return out;
            }
            out.put("source", source);

            List<String> symbols = currentTop10.stream()
                .map(s -> String.valueOf(s.getOrDefault("symbol", ""))).toList();
            Map<String, Double> livePrices = resolveCurrentPricesBatch(symbols, institutionId);
            // When each tracked price was last stamped. A stock whose stored price predates the
            // buy is being valued at a price from BEFORE it was bought: RUBYMILLS carried a
            // 31 Aug price of 384.35 against a 4 Sep purchase at 428.30 and reported -10.26%, a
            // loss made entirely of the three sessions between the two figures.
            Map<String, String> priceDates = trackedPriceDates(symbols, institutionId);

            List<Map<String, Object>> stocks = new ArrayList<>();
            double totalReturn = 0; int win = 0, lose = 0, missing = 0, counted = 0, openRows = 0;
            for (Map<String, Object> s : currentTop10) {
                String sym = String.valueOf(s.getOrDefault("symbol", ""));
                double current = livePrices.getOrDefault(normalizeSymbol(sym), 0.0);
                if (current <= 0) current = getD(s, "current_price");
                String curDate = priceDates.get(normalizeSymbol(sym));
                boolean currentPredatesBuy = curDate != null && !curDate.isBlank()
                    && historical && buyDate != null && curDate.compareTo(buyDate) < 0;
                String staleNote = null;
                if (currentPredatesBuy) {
                    // Fall back to the newest close actually held for the buy day, so the row
                    // reads flat - unknown - instead of inventing a move out of stale data.
                    Double onBuyDay = dbCloseAt(sym, buyDate);
                    staleNote = "No price newer than the buy - last stored price is " + curDate + ".";
                    current = onBuyDay != null && onBuyDay > 0 ? onBuyDay : 0;
                }
                // Kept as separate branches: mixing Double and double in one conditional
                // expression makes Java unbox the null returned when no candle exists.
                Double buy;
                String rowBuyDate, rowBuyTime;
                if (historical) {
                    // Nearest intraday candle to the selected time; the stored daily close is
                    // the fallback when that day has no intraday history.
                    buy = intradayCloseAt(sym, buyDate, buyTime);
                    if (buy == null || buy <= 0) buy = dbCloseAt(sym, buyDate);
                    rowBuyDate = buyDate; rowBuyTime = buyTime;
                } else if (defaultToLastSession) {
                    buy = dbCloseAt(sym, lastSessionDay);
                    rowBuyDate = lastSessionDay; rowBuyTime = "15:30";
                } else {
                    buy = current > 0 ? current : getD(s, "current_price");
                    rowBuyDate = DateUtil.today(); rowBuyTime = "LIVE";
                }

                // Sell price: historical when a sell day was chosen, otherwise the live tick.
                Double sell;
                String sellNote = null;
                if (sellHistorical) {
                    sell = intradayCloseAt(sym, sellDate, sellTime);
                    if (sell == null || sell <= 0) {
                        // dbCloseAt falls back to that day's 15:20 print, then its daily close. On
                        // a DIFFERENT day from the buy that is a coarser but real exit. On the SAME
                        // day it returns the very bar the buy came from, so the row showed an
                        // identical buy and sell and a 0.00% return that looked like a real flat
                        // trade - and counted as a winner in the average. RUBYMILLS did exactly
                        // this: no intraday at all on 4 Sep, both legs falling back to the 428.30
                        // daily close. Say the price is missing instead of inventing a trade.
                        if (sellDate != null && sellDate.compareTo(DateUtil.today()) >= 0) {
                            // The exit is today or later: this position is simply still running.
                            // Bought at Friday's cut-off, sells at 11:00 on the next market day -
                            // until that print exists the trade is OPEN, so value it at the
                            // current price and say so, rather than showing ten blank rows.
                            sell = current > 0 ? current : null;
                            sellNote = sell == null ? "Open - no current price yet"
                                : staleNote != null
                                    ? "OPEN - " + staleNote
                                    : "OPEN - valued at the current price; settles at " + sellTime
                                      + " on " + sellDate + ".";
                            openRows++;
                        } else if (sellDate != null && sellDate.equals(rowBuyDate)) {
                            sell = null;
                            sellNote = "No traded price at " + sellTime + " on " + sellDate;
                        } else {
                            sell = dbCloseAt(sym, sellDate);
                            if (sell != null && sell > 0) sellNote = "Exit taken from the " + sellDate
                                + " close - no intraday print at " + sellTime + ".";
                        }
                    }
                } else if (sellFromLastSession) {
                    sell = dbCloseAt(sym, lastSessionDay);
                } else {
                    sell = current > 0 ? current : null;
                }

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol", sym);
                m.put("rank", (int) getD(s, "rank"));
                m.put("sector", s.getOrDefault("sector", ""));
                // Rounded: these are float quotes and reached the screen as 428.29998779296875.
                m.put("current_price", round2(current));
                m.put("sell_price", sell == null ? null : round2(sell));
                if (sellNote != null) m.put("sell_note", sellNote);
                m.put("buy_date", rowBuyDate);
                m.put("buy_time", rowBuyTime);
                m.put("sell_date", sellShownDate);
                m.put("sell_time", sellShownTime);
                if (buy == null || buy <= 0 || sell == null || sell <= 0) {
                    m.put("buy_price", buy != null && buy > 0 ? round2(buy) : null);
                    m.put("return_pct", null);
                    m.put("price_available", false);
                    missing++;
                } else {
                    double ret = Math.round(((sell - buy) / buy) * 10000.0) / 100.0;
                    m.put("buy_price", round2(buy));
                    m.put("return_pct", ret);
                    m.put("price_available", true);
                    // OPEN vs closed is the difference between a result and a running position.
                    m.put("status", sellNote != null && sellNote.startsWith("OPEN") ? "OPEN" : "CLOSED");
                    totalReturn += ret; counted++;
                    if (ret >= 0) win++; else lose++;
                }
                stocks.add(m);
            }

            out.put("success", true);
            out.put("period", p);
            out.put("buy_date", buyDate);
            out.put("historical", historical);
            out.put("stocks", stocks);
            out.put("avg_return", counted > 0 ? Math.round((totalReturn / counted) * 100) / 100.0 : 0);
            out.put("winners", win);
            out.put("losers", lose);
            out.put("missing_prices", missing);
            out.put("open_positions", openRows);
            out.put("counted", counted);
        } catch (Exception e) {
            log.error("Top 10 preview failed: {}", e.getMessage(), e);
            out.put("success", false);
            out.put("error", "Preview failed: " + e.getMessage());
            out.put("stocks", new ArrayList<>());
        }
        return out;
    }

    // =========================================================================
    //  DIAGNOSTIC — "stale previous close" finder (BAJFINANCE-type cases).
    //  Lists active tracked stocks whose most recent stored DAILY candle is older
    //  than the last few trading days (or missing entirely) across all daily
    //  sources. These are the stocks whose Previous Close cannot roll forward.
    //  Pure DB reads — no API calls.
    // =========================================================================
    @SuppressWarnings("unchecked")
    public Map<String, Object> getStalePrevCloseStocks(Long userId, Long institutionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            // Staleness threshold = 3 trading days ago (a fresh universe updates daily).
            java.time.LocalDate d = java.time.LocalDate.now();
            int stepped = 0, guard = 0;
            while (stepped < 3 && guard < 40) { d = d.minusDays(1); if (isTradingDay(d)) stepped++; guard++; }
            String threshold = d.toString();

            StringBuilder sql = new StringBuilder(
                "WITH freshest AS (" +
                "  SELECT UPPER(TRIM(COALESCE(symbol,trading_symbol))) AS sym, MAX(LEFT(CAST(trade_date AS TEXT),10)) AS latest_daily " +
                "  FROM broker_market_candles WHERE close IS NOT NULL AND UPPER(COALESCE(candle_mode,''))='DAILY' " +
                "  GROUP BY UPPER(TRIM(COALESCE(symbol,trading_symbol))) " +
                ") " +
                "SELECT ts.symbol, f.latest_daily, ts.base_price, ts.current_price, ts.last_refreshed, ts.base_price_source " +
                "FROM tracked_stocks ts LEFT JOIN freshest f ON f.sym = UPPER(TRIM(ts.symbol)) " +
                "WHERE ts.institution_id=? AND ts.is_active=1 ");
            if (userId != null) sql.append("AND ts.user_id=").append(userId).append(" ");
            sql.append("AND (f.latest_daily IS NULL OR f.latest_daily < ?) " +
                "ORDER BY f.latest_daily NULLS FIRST, ts.symbol LIMIT 1000");

            List<Object[]> rows = em.createNativeQuery(sql.toString())
                .setParameter(1, institutionId)
                .setParameter(2, threshold)
                .getResultList();

            List<Map<String, Object>> stocks = new ArrayList<>();
            for (Object[] r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                String latestDaily = r[1] == null ? null : String.valueOf(r[1]);
                m.put("symbol", r[0]);
                m.put("latest_daily_candle", latestDaily == null ? "None" : latestDaily);
                m.put("base_price", getNumber(r[2]) > 0 ? getNumber(r[2]) : null);
                m.put("current_price", getNumber(r[3]) > 0 ? getNumber(r[3]) : null);
                m.put("last_refreshed", r[4]);
                m.put("base_price_source", r[5]);
                Integer daysStale = null;
                if (latestDaily != null && latestDaily.length() >= 10) {
                    try {
                        daysStale = (int) java.time.temporal.ChronoUnit.DAYS.between(
                            java.time.LocalDate.parse(latestDaily.substring(0, 10)), java.time.LocalDate.now());
                    } catch (Exception ignored) {}
                }
                m.put("days_stale", daysStale);
                stocks.add(m);
            }

            out.put("success", true);
            out.put("threshold_date", threshold);
            out.put("count", stocks.size());
            out.put("generated_at", DateUtil.now());
            out.put("stocks", stocks);
        } catch (Exception e) {
            log.error("Stale prev-close diagnostic failed: {}", e.getMessage(), e);
            out.put("success", false);
            out.put("error", "Diagnostic failed: " + e.getMessage());
            out.put("stocks", new ArrayList<>());
            out.put("count", 0);
        }
        return out;
    }
    // =========================================================================
    //  PREVIOUS TOP 10 — Trade Batch Operations
    // =========================================================================

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> createPrevTop10TradeBatch(Long userId, Long institutionId, String buyTime, String tradeType) {
        try {
            List<Object[]> openBatches = em.createNativeQuery(
                "SELECT batch_id FROM top10_trade_batches WHERE institution_id=? AND strategy='PREV_TOP10' AND status='OPEN'" +
                (userId != null ? " AND user_id=" + userId : "") + " LIMIT 1")
                .setParameter(1, institutionId).getResultList();
            if (!openBatches.isEmpty()) {
                return Map.of("success", false, "error",
                    "An active Previous Top 10 batch already exists (" + openBatches.get(0)[0] + "). Sell it before creating a new one.");
            }

            Map<String, Object> ranking = getLatestRankingFull(userId, institutionId, false);
            List<Map<String, Object>> prevTop10 = (List<Map<String, Object>>) ranking.get("previous_top10");
            if (prevTop10 == null || prevTop10.isEmpty()) {
                return Map.of("success", false, "error", "No Previous Top 10 stocks available. Run ranking at least twice.");
            }

            String now = DateUtil.now();
            String today = DateUtil.today();
            if (buyTime == null || buyTime.isBlank()) buyTime = now.substring(11, 16);
            String batchId = "PREV10_" + today.replace("-", "") + "_" + buyTime.replace(":", "");

            List<Object[]> existing = em.createNativeQuery(
                "SELECT batch_id FROM top10_trade_batches WHERE batch_id=? LIMIT 1")
                .setParameter(1, batchId).getResultList();
            if (!existing.isEmpty()) {
                return Map.of("success", false, "error", "Trade batch " + batchId + " already exists for today at this time.");
            }

            em.createNativeQuery(
                "INSERT INTO top10_trade_batches(batch_id,user_id,institution_id,trade_date,buy_time,status,strategy,trade_type,total_stocks,created_at,updated_at) " +
                "VALUES(?,?,?,?,?,'OPEN','PREV_TOP10',?,?,?,?)")
                .setParameter(1, batchId).setParameter(2, userId).setParameter(3, institutionId)
                .setParameter(4, today).setParameter(5, buyTime)
                .setParameter(6, tradeType != null ? tradeType : "PAPER")
                .setParameter(7, prevTop10.size())
                .setParameter(8, now).setParameter(9, now)
                .executeUpdate();

            for (int i = 0; i < prevTop10.size(); i++) {
                Map<String, Object> stock = prevTop10.get(i);
                String symbol = String.valueOf(stock.getOrDefault("symbol", ""));
                double buyPrice = getD(stock, "current_price");
                if (buyPrice <= 0) buyPrice = getD(stock, "base_price");
                int rankPos = i + 1;
                String sector = String.valueOf(stock.getOrDefault("sector", ""));
                String mcap = String.valueOf(stock.getOrDefault("market_cap", ""));
                em.createNativeQuery(
                    "INSERT INTO top10_trade_batch_stocks(batch_id,symbol,rank,buy_price,sector,market_cap,status,created_at) " +
                    "VALUES(?,?,?,?,?,?,'OPEN',?)")
                    .setParameter(1, batchId).setParameter(2, symbol).setParameter(3, rankPos)
                    .setParameter(4, buyPrice).setParameter(5, sector).setParameter(6, mcap)
                    .setParameter(7, now).executeUpdate();
            }

            auditLog(institutionId, userId, "PREV_TOP10_BUY", "top10_trade_batches", batchId,
                Map.of("stocks", prevTop10.size(), "trade_type", tradeType != null ? tradeType : "PAPER"));

            return Map.of("success", true, "batch_id", batchId, "stocks", prevTop10.size(),
                "message", "Previous Top 10 batch " + batchId + " created with " + prevTop10.size() + " stocks.");

        } catch (Exception e) {
            log.error("Failed to create Previous Top 10 trade batch: {}", e.getMessage(), e);
            return Map.of("success", false, "error", "Failed to create trade batch: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getActivePrevTop10Batch(Long userId, Long institutionId) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT b.batch_id,b.trade_date,b.buy_time,b.status,b.trade_type,b.total_stocks,b.created_at " +
                "FROM top10_trade_batches b WHERE b.institution_id=? AND b.strategy='PREV_TOP10' AND b.status='OPEN'");
            if (userId != null) sql.append(" AND b.user_id=?");
            sql.append(" ORDER BY b.created_at DESC LIMIT 1");
            var q = em.createNativeQuery(sql.toString()).setParameter(1, institutionId);
            if (userId != null) q.setParameter(2, userId);
            List<Object[]> batches = q.getResultList();

            if (batches.isEmpty()) {
                return Map.of("active", false, "message", "No active Previous Top 10 trade batch.");
            }

            Object[] b = batches.get(0);
            String batchId = String.valueOf(b[0]);

            List<Object[]> stockRows = em.createNativeQuery(
                "SELECT id,symbol,rank,buy_price,sector,market_cap,status FROM top10_trade_batch_stocks " +
                "WHERE batch_id=? ORDER BY rank")
                .setParameter(1, batchId).getResultList();

            List<String> symbols = stockRows.stream().map(r -> String.valueOf(r[1])).toList();
            Map<String, Double> livePrices = resolveCurrentPricesBatch(symbols, institutionId);

            List<Map<String, Object>> stocks = new ArrayList<>();
            double totalReturn = 0;
            int winners = 0, losers = 0;
            for (Object[] sr : stockRows) {
                Map<String, Object> sm = new LinkedHashMap<>();
                String symbol = String.valueOf(sr[1]);
                double buyPrice = getNumber(sr[3]);
                double currentPrice = livePrices.getOrDefault(normalizeSymbol(symbol), 0.0);
                if (currentPrice <= 0) currentPrice = buyPrice;
                double returnPct = buyPrice > 0 ? Math.round(((currentPrice - buyPrice) / buyPrice) * 10000.0) / 100.0 : 0;
                sm.put("id", sr[0]); sm.put("symbol", symbol); sm.put("rank", sr[2]);
                sm.put("buy_price", buyPrice); sm.put("current_price", currentPrice);
                sm.put("return_pct", returnPct);
                sm.put("sector", sr[4]); sm.put("market_cap", sr[5]); sm.put("status", sr[6]);
                stocks.add(sm);
                totalReturn += returnPct;
                if (returnPct >= 0) winners++; else losers++;
            }

            double avgReturn = stocks.isEmpty() ? 0 : Math.round((totalReturn / stocks.size()) * 100) / 100.0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("active", true);
            result.put("batch_id", batchId);
            result.put("trade_date", b[1]); result.put("buy_time", b[2]);
            result.put("status", b[3]); result.put("trade_type", b[4]);
            result.put("total_stocks", toInt(b[5])); result.put("created_at", b[6]);
            result.put("stocks", stocks);
            result.put("avg_return", avgReturn);
            result.put("winners", winners); result.put("losers", losers);
            return result;

        } catch (Exception e) {
            log.error("Failed to get active Previous Top 10 batch: {}", e.getMessage(), e);
            return Map.of("active", false, "error", "Failed to load batch: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> refreshPrevTop10Batch(Long userId, Long institutionId) {
        try {
            Map<String, Object> activeBatch = getActivePrevTop10Batch(userId, institutionId);
            if (!Boolean.TRUE.equals(activeBatch.get("active"))) {
                return Map.of("success", false, "error", "No active Previous Top 10 trade batch to refresh.");
            }

            List<Map<String, Object>> stocks = (List<Map<String, Object>>) activeBatch.get("stocks");
            if (stocks == null || stocks.isEmpty()) {
                return Map.of("success", false, "error", "Active batch has no stocks.");
            }

            List<String> symbols = stocks.stream().map(s -> String.valueOf(s.get("symbol"))).toList();
            Map<String, Double> freshPrices = resolveCurrentPricesBatch(symbols, institutionId);

            double totalReturn = 0;
            int winners = 0, losers = 0;
            for (Map<String, Object> stock : stocks) {
                String symbol = String.valueOf(stock.get("symbol"));
                double buyPrice = getD(stock, "buy_price");
                double freshPrice = freshPrices.getOrDefault(normalizeSymbol(symbol), 0.0);
                if (freshPrice > 0) stock.put("current_price", freshPrice);
                double currentPrice = getD(stock, "current_price");
                double returnPct = buyPrice > 0 ? Math.round(((currentPrice - buyPrice) / buyPrice) * 10000.0) / 100.0 : 0;
                stock.put("return_pct", returnPct);
                totalReturn += returnPct;
                if (returnPct >= 0) winners++; else losers++;
            }

            double avgReturn = stocks.isEmpty() ? 0 : Math.round((totalReturn / stocks.size()) * 100) / 100.0;
            activeBatch.put("avg_return", avgReturn);
            activeBatch.put("winners", winners);
            activeBatch.put("losers", losers);
            activeBatch.put("success", true);
            activeBatch.put("last_refreshed", DateUtil.now());
            return activeBatch;

        } catch (Exception e) {
            log.error("Failed to refresh Previous Top 10 batch: {}", e.getMessage(), e);
            return Map.of("success", false, "error", "Refresh failed: " + e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> deleteTradeBatch(String batchId, Long institutionId) {
        em.createNativeQuery("DELETE FROM top10_trade_batch_stocks WHERE batch_id=?")
            .setParameter(1, batchId).executeUpdate();
        int deleted = em.createNativeQuery("DELETE FROM top10_trade_batches WHERE batch_id=? AND institution_id=?")
            .setParameter(1, batchId).setParameter(2, institutionId).executeUpdate();
        return Map.of("success", deleted > 0, "message", deleted > 0 ? "Batch deleted." : "Batch not found.");
    }
}
