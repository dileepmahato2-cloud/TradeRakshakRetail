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
    private final ObjectMapper             mapper;
    private final BlockingHttpService      http;
    private final DhanBrokerService        dhanBrokerService;
    private final PlatformTransactionManager transactionManager;
    private final AppConfigService          appConfig;
    private final LivePriceBroadcaster      liveBroadcaster;

    @PersistenceContext
    private EntityManager em;

    // ── Dynamic configuration (read from AppConfigService) ───────────────────
    private double getEligibleMinReturn()    { return appConfig.getDouble("stock_upload.eligible_min_return", 0.0); }
    private double getEligibleMaxReturn()    { return appConfig.getDouble("stock_upload.eligible_max_return", 5.0); }
    private int    getMaxPerSector()         { return appConfig.getInt("top10.max_per_sector", 2); }
    private int    getTopN()                 { return appConfig.getInt("top10.count", 10); }
    private String getDhanApi()              { return appConfig.getString("broker.dhan.api_endpoint", "https://api.dhan.co/v2"); }
    private long   getScreenRefreshGapMs()   { return appConfig.getLong("system.screen_refresh_gap_ms", 45_000L); }
    private long   getMarketOpenStaleSec()   { return appConfig.getLong("system.market_open_stale_sec", 60L); }
    private long   getMarketClosedStaleSec() { return appConfig.getLong("system.market_closed_stale_sec", 900L); }

    // ── Stock Upload automation config (all default to safe / current behavior) ──
    public boolean isTop10AutoRefreshEnabled()   { return appConfig.getBoolean("top10.auto_refresh.enabled", true); }
    public int     getTop10AutoRefreshInterval() { return appConfig.getInt("top10.auto_refresh.interval_sec", 300); }
    /** INTERVAL = run every N minutes while the market is open; FIXED = run at set clock times. */
    public String  getTop10RefreshMode()         { return appConfig.getString("top10.auto_refresh.mode", "INTERVAL"); }
    /** Comma-separated HH:mm list used when the mode is FIXED, e.g. "09:20,12:00,15:20". */
    public String  getTop10RefreshTimes()        { return appConfig.getString("top10.auto_refresh.times", "09:20,15:20"); }
    /** Lock one run a day as that day's official Top 10 - the list that becomes tomorrow's Previous Top 10. */
    public boolean isTop10LockEnabled()          { return appConfig.getBoolean("top10.lock.enabled", false); }
    public String  getTop10LockTime()            { return appConfig.getString("top10.lock.time", "15:20"); }
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
    // Sell is its own time, not the lock time: the position is bought at the 15:20 lock and closed
    // just before the next day's, so defaulting it to the lock time would sell five minutes late.
    public String  getTradeSellTime()            { return appConfig.getString("top10.trade.sell_time", "15:15"); }
    /** Auto-refresh for the Previous Top 10 panel and the average-return bars beneath it. */
    public boolean isPrevTop10AutoRefreshEnabled() { return appConfig.getBoolean("prevtop10.auto_refresh.enabled", false); }
    public int     getPrevTop10AutoRefreshSec()    { return appConfig.getInt("prevtop10.auto_refresh.interval_sec", 300); }
    public boolean isPrevTop10AutoCycleEnabled() { return appConfig.getBoolean("prevtop10.auto_cycle.enabled", false); }
    public boolean isPrevTop10AutoBuyEnabled()   { return appConfig.getBoolean("prevtop10.auto_buy.enabled", false); }
    public boolean isPrevTop10AutoSellEnabled()  { return appConfig.getBoolean("prevtop10.auto_sell.enabled", false); }
    public String  getPrevTop10BuyTime()         { return appConfig.getString("prevtop10.buy_time", "15:20"); }
    public String  getPrevTop10SellTime()        { return appConfig.getString("prevtop10.sell_time", "09:30"); }
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
                val = normalizeHhmm(val, cfgKey.endsWith("buy_time") ? "15:20" : "09:30");
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

        // Deduplicate: keep latest date per symbol
        Map<String, Map<String, String>> deduped = new LinkedHashMap<>();
        int dupCount = 0;
        for (Map<String, String> row : rows) {
            String sym = clean(row.get("symbol"));
            if (sym == null || sym.isBlank()) continue;
            sym = sym.toUpperCase().trim();
            Map<String, String> normalizedRow = new LinkedHashMap<>(row);
            normalizedRow.put("symbol", sym);
            normalizedRow.put("date", parseDate(clean(row.get("date"))));

            if (deduped.containsKey(sym)) {
                // Keep the later date
                String existingDate = deduped.get(sym).get("date");
                String newDate      = normalizedRow.get("date");
                if (compareDates(newDate, existingDate) > 0) {
                    deduped.put(sym, normalizedRow);
                }
                dupCount++;
            } else {
                deduped.put(sym, normalizedRow);
            }
        }

        // Upsert into tracked_stocks
        int newCount = 0, failCount = 0;
        List<String> symbolsNeedingPrice = new ArrayList<>();

        for (Map.Entry<String, Map<String, String>> entry : deduped.entrySet()) {
            String sym = entry.getKey();
            Map<String, String> row = entry.getValue();
            String uploadDate = parseDate(clean(row.get("date")));
            String mcap   = clean(row.getOrDefault("marketcapname", row.getOrDefault("market_cap", "Unknown")));
            String sector = clean(row.getOrDefault("sector", "Unknown"));

            try {
                int affected = em.createNativeQuery(
                    "INSERT INTO tracked_stocks(institution_id,user_id,upload_id,symbol," +
                    "market_cap_name,sector,upload_date,is_active,created_at,updated_at,batch_id,is_deleted) " +
                    "VALUES(?,?,?,?,?,?,?,1,?,?,?,0) " +
                    "ON CONFLICT(institution_id,symbol) DO UPDATE SET " +
                    "upload_id=excluded.upload_id, upload_date=excluded.upload_date, " +
                    "market_cap_name=excluded.market_cap_name, sector=excluded.sector, " +
                    "base_price=NULL, base_price_source=NULL, return_pct=0, is_active=1, " +
                    "batch_id=excluded.batch_id, is_deleted=0, deleted_at=NULL, deleted_by=NULL, delete_type=NULL, " +
                    "updated_at=excluded.updated_at")
                    .setParameter(1, institutionId).setParameter(2, userId)
                    .setParameter(3, uploadId).setParameter(4, sym)
                    .setParameter(5, mcap).setParameter(6, sector)
                    .setParameter(7, uploadDate).setParameter(8, DateUtil.now())
                    .setParameter(9, DateUtil.now()).setParameter(10, batchId)
                    .executeUpdate();
                if (affected > 0) {
                    newCount++;
                    symbolsNeedingPrice.add(sym);
                }
            } catch (Exception e) {
                failCount++;
                log.debug("Insert tracked_stock {}: {}", sym, e.getMessage());
            }
        }

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
            Map.of("file", fileName, "total", rows.size(), "new", newCount, "dups", dupCount));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("upload_id", uploadId);
        result.put("batch_id", batchId);
        result.put("total_rows", rows.size());
        result.put("new_records", newCount);
        result.put("duplicates", dupCount);
        result.put("failed", failCount);
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
                }

                Map<String, DhanInstrumentRef> refs = resolveDhanInstruments(remainingSymbols);
                List<String> batchSymbols = new ArrayList<>();
                List<Double> batchPrices = new ArrayList<>();
                List<String> batchSources = new ArrayList<>();
                int batchFlushSize = appConfig.getInt("upload.batch_flush_size", 500);

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

                for (DhanInstrumentRef ref : batch) {
                    String segment = normalizeDhanExchangeSegment(ref.exchangeSegment);
                    JsonNode quote = data.path(segment).path(ref.securityId);
                    double ltp = firstNumber(quote, "last_price", "ltp", "LTP", "lastPrice");
                    double close = firstNumber(quote.path("ohlc"), "close", "prev_close");
                    if (close <= 0) close = firstNumber(quote, "close", "prev_close");
                    result.put(normalizeSymbol(ref.symbol), new double[]{ltp, close});
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

    private int batchUpdateCurrentPrices(List<String> symbols, List<Double> prices, Long institutionId) {
        if (symbols.isEmpty()) return 0;
        String now = DateUtil.now();
        String today = DateUtil.today();
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
                    .setParameter(5, today)
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
        failed = Math.max(0, symbols.size() - updated);
        List<String> missingBasePrices = getSymbolsMissingBasePrice(triggeredBy, institutionId);
        if (!missingBasePrices.isEmpty()) {
            fetchBasePricesFromDhanAsync(missingBasePrices, triggeredBy, institutionId);
        }

        // Store daily snapshot for all symbols (own short transaction)
        long tSnap0 = System.currentTimeMillis();
        runInTransaction(() -> storeDailyPriceSnapshot(institutionId));
        long tSnapMs = System.currentTimeMillis() - tSnap0;

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
        result.put("fallback_updated", 0);
        result.put("dhan_missing_instruments", dhanOutcome.missingInstruments);
        result.put("dhan_api_calls", dhanOutcome.apiCalls);
        result.put("base_price_retry_started", missingBasePrices.size());
        result.put("message", dhanOutcome.message);
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
            outcome.message = outcome.updated > 0
                ? "Dhan LTP refreshed live prices. Unmapped or failed symbols were not updated."
                : "Dhan is connected, but no live prices were returned.";
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
        resolved.putAll(findDhanInstruments(normalized));
        List<String> missing = normalized.stream()
            .filter(s -> !resolved.containsKey(s))
            .toList();
        resolved.putAll(findBrokerInstruments(missing));
        return resolved;
    }

    private Map<String, DhanInstrumentRef> findBrokerInstruments(List<String> symbols) {
        Map<String, DhanInstrumentRef> found = new LinkedHashMap<>();
        if (symbols.isEmpty()) return found;
        try {
            Map<String, String> aliasToSymbol = aliasToTrackedSymbol(symbols);
            List<String> aliases = new ArrayList<>(aliasToSymbol.keySet());
            String in = IntStream.range(0, aliases.size()).mapToObj(i -> "?").collect(Collectors.joining(","));
            var query = em.createNativeQuery(
                "SELECT symbol, trading_symbol, display_name, underlying_symbol, " +
                "broker_security_id, COALESCE(NULLIF(exchange_segment,''),'NSE_EQ') AS exchange_segment, instrument_type " +
                "FROM broker_instruments WHERE broker_name='dhan' " +
                "AND broker_security_id IS NOT NULL AND broker_security_id<>'' " +
                "AND UPPER(COALESCE(exchange_segment,'')) IN ('NSE_EQ','BSE_EQ') " +
                "AND UPPER(COALESCE(instrument_type,'')) IN ('EQUITY','ES','EQ') " +
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
            log.debug("Broker instrument batch lookup: {}", e.getMessage());
        }
        return found;
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

            Optional<DhanInstrumentRef> ref = findBrokerInstrument(symbol);
            if (ref.isEmpty()) ref = findDhanInstrument(symbol);
            ref.ifPresent(r -> resolved.put(symbol, r));
        }
        return resolved;
    }

    private Optional<DhanInstrumentRef> findBrokerInstrument(String symbol) {
        try {
            Map<String, String> aliasToSymbol = aliasToTrackedSymbol(List.of(symbol));
            List<String> aliases = new ArrayList<>(aliasToSymbol.keySet());
            if (aliases.isEmpty()) return Optional.empty();
            String in = IntStream.range(0, aliases.size()).mapToObj(i -> "?").collect(Collectors.joining(","));
            var query = em.createNativeQuery(
                "SELECT broker_security_id, COALESCE(NULLIF(exchange_segment,''),'NSE_EQ'), instrument_type, underlying_symbol " +
                "FROM broker_instruments WHERE broker_name='dhan' " +
                "AND broker_security_id IS NOT NULL AND broker_security_id<>'' " +
                "AND UPPER(COALESCE(exchange_segment,'')) IN ('NSE_EQ','BSE_EQ') " +
                "AND UPPER(COALESCE(instrument_type,'')) IN ('EQUITY','ES','EQ') " +
                "AND UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(underlying_symbol,''),'-EQ',''),'-BE',''),'-BZ',''),'.NS',''),' ','')) IN (" + in + ") " +
                "ORDER BY CASE WHEN exchange_segment='NSE_EQ' THEN 0 WHEN exchange_segment='BSE_EQ' THEN 1 ELSE 2 END LIMIT 1");
            for (int i = 0; i < aliases.size(); i++) query.setParameter(i + 1, aliases.get(i));
            @SuppressWarnings("unchecked")
            List<Object[]> rows = query.getResultList();
            if (rows.isEmpty()) return Optional.empty();
            Object[] row = rows.get(0);
            return Optional.of(new DhanInstrumentRef(symbol, String.valueOf(row[0]), String.valueOf(row[1]), String.valueOf(row[2])));
        } catch (Exception e) {
            log.debug("Broker instrument lookup {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
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

    private String normalizeSymbol(String symbol) {
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
            .setParameter(5, DateUtil.today())
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
                for (int i = 0; i < refs.size(); i++) {
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
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> reconstructTop10AsOf(Long userId, Long institutionId,
                                                          String asOf, Double maxPrice,
                                                          boolean applyReturnBand,
                                                          boolean requireCrossing,
                                                          boolean capPerSector) {
        Map<String, Double> weights = loadTrackerWeights(institutionId);

        StringBuilder sql = new StringBuilder(
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
        if (userId != null) sql.append(" AND ts.user_id = ").append(userId);

        List<Object[]> rows;
        try {
            rows = em.createNativeQuery(sql.toString())
                .setParameter(1, asOf).setParameter(2, institutionId).setParameter(3, asOf)
                .getResultList();
        } catch (Exception e) {
            log.warn("Reconstruction for {} failed: {}", asOf, e.getMessage());
            return List.of();
        }
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
            if (todayVol <= 0 || avg20 <= 0 || todayVol <= avg20) continue;   // Volume > 20D Avg

            s.put("previous_close", prevClose);
            s.put("volume", todayVol);
            s.put("avg_20d_volume", Math.round(avg20));
            // Change measured against the previous close, matching the Eligible Stocks export.
            s.put("price_change_pct",
                Math.round(((getD(s, "current_price") - prevClose) / prevClose) * 10000.0) / 100.0);
            candidates.add(s);
        }
        if (candidates.isEmpty()) return List.of();

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

    /** One symbol's recent daily bars as of a date: [trade_date, close, volume], newest first. */
    private record DayBar(String date, double close, double volume) {}

    /**
     * Daily bars up to and including a date, for many symbols in one query.
     *
     * <p>Used by the as-of identification, which needs three things per stock that a single close
     * cannot give: the previous close (to see a crossing), that day's volume, and the twenty-day
     * average volume behind it. Reading a 45-day window covers twenty trading days with room for
     * holidays, and grouping in memory avoids a query per symbol per day.
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<DayBar>> dailyBarsUpTo(List<String> symbols, String asOf, int lookbackDays) {
        Map<String, List<DayBar>> out = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;
        try {
            String from = java.time.LocalDate.parse(asOf).minusDays(lookbackDays).toString();
            String norm = normalizedSql("symbol");
            List<Object[]> rows = em.createNativeQuery(
                "SELECT " + norm + ", trade_date, close, COALESCE(total_volume, volume) " +
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
                                   r[3] == null ? 0 : ((Number) r[3]).doubleValue()));
            }
        } catch (Exception e) {
            log.debug("Daily bars up to {} failed: {}", asOf, e.getMessage());
        }
        return out;
    }

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
            String asOf = days.get(days.size() - 1);
            String prevDay = days.size() > 1 ? days.get(days.size() - 2) : null;

            // ---- IDENTIFY -> RANK (both inside reconstructTop10AsOf, in that order) ----
            // false: identification here is exactly the four stated conditions, no return band.
            List<Map<String, Object>> top =
                reconstructTop10AsOf(userId, institutionId, asOf, maxPrice, false, requireCrossing);

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
                List<Map<String, Object>> prevTop =
                    reconstructTop10AsOf(userId, institutionId, prevDay, maxPrice, false, requireCrossing);
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
            out.put("max_price", maxPrice);
            out.put("top10", rows);
            out.put("count", rows.size());
            out.put("avg_return_current", avgCurrent);
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
            out.put("pipeline", "Identify: "
              + (requireCrossing
                  ? "Previous Close < Base Price AND Current Price >= Base Price (the crossing) AND "
                  : "Current Price >= Base Price AND ")
              + "Current Volume > 20-Day Average Volume "
              + "-> Rank (existing engine, existing weights) -> Buy/Sell (existing logic)");
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

            double[] cohort = pool.stream().mapToDouble(c -> getD(c, "return_pct")).sorted().toArray();
            // Raw top N by score - no sector cap. The cap is applied to the three ranking sections
            // only (see selectTop10WithDiversification), so this preview stays a straight read of
            // what scores highest right now.
            List<Map<String, Object>> ranked = pool.stream()
                .map(s -> scoreTrackedStock(s, weights, cohort))
                .sorted((a, b) -> Double.compare(getD(b, "composite_score"), getD(a, "composite_score")))
                .limit(getTopN())
                .collect(Collectors.toList());

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
                row.put("current_price", s.get("current_price"));
                row.put("return_pct", s.get("return_pct"));
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
            List<?> days = em.createNativeQuery(
                "SELECT DISTINCT trade_date FROM broker_market_candles " +
                "WHERE UPPER(COALESCE(candle_mode,'')) = 'DAILY' AND trade_date >= ? AND trade_date <= ? " +
                "ORDER BY trade_date")
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
        final String bt = (buyTime  == null || buyTime.isBlank())  ? getTradeBuyTime()  : buyTime;
        final String st = (sellTime == null || sellTime.isBlank()) ? getTradeSellTime() : sellTime;
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> bars = new ArrayList<>();
        List<String> days = tradingDaysWithData(from, to);

        // Look a little past the range so the last day can still be settled if the market has
        // since traded - otherwise the most interesting day is always the one left unmeasured.
        List<String> forward = tradingDaysWithData(
            to, java.time.LocalDate.parse(to).plusDays(10).toString());

        long started = System.currentTimeMillis();
        for (int i = 0; i < days.size(); i++) {
            final String day = days.get(i);
            String next = (i + 1 < days.size()) ? days.get(i + 1)
                : forward.stream().filter(d -> d.compareTo(day) > 0).findFirst().orElse(null);

            List<Map<String, Object>> top = reconstructTop10AsOf(
                userId, institutionId, day, maxPrice, applyReturnBand, requireCrossing, capPerSector);
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("date", day);
            bar.put("stocks_ranked", top.size());

            if (top.isEmpty()) {
                bar.put("trade_avg_return", null);
                bar.put("trade_source", "no_data");
                bar.put("note", "No stock met the entry rules on this day.");
                bars.add(bar);
                continue;
            }
            List<String> syms = top.stream().map(s -> String.valueOf(s.get("symbol"))).toList();
            bar.put("symbols", syms);

            if (next == null) {
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
                bar.put("open_return_pct", m > 0 ? Math.round((s2 / m) * 100.0) / 100.0 : null);
                bar.put("open_priced", m);
                bar.put("trade_winners", up);
                bar.put("trade_losers", down);
                bar.put("note", m > 0
                    ? "Open position, valued at the current price. Settles at " + st
                      + " on the next trading day."
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
            }
            bars.add(bar);
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

        double avgReturn = scored.stream().mapToDouble(s -> getD(s, "return_pct")).average().orElse(0);
        long positive = scored.stream().filter(s -> getD(s, "return_pct") >= 0).count();
        Map<String, Long> sectorDist = scored.stream().collect(
            Collectors.groupingBy(s -> String.valueOf(s.getOrDefault("sector", "Unknown")), Collectors.counting()));

        Map<String, Object> result = buildRankingResponse(
            "FALLBACK-" + System.currentTimeMillis(),
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
            m.put("close_price", dp != null && dp.close > 0 ? dp.close : null);
            m.put("close_date", dp != null ? normalizeStoredDate(dp.tradeDate) : null);
            m.put("volume", dp != null && dp.volume > 0 ? dp.volume : null);
            m.put("symbol", String.valueOf(r[0]));
            m.put("sector", r[1]);
            m.put("market_cap_name", r[2]);
            m.put("mcap", r[2]);
            m.put("upload_date", r[3]);
            m.put("base_price", base > 0 ? base : null);
            m.put("current_price", cur > 0 ? cur : null);
            m.put("return_pct", getNumber(r[6]));
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
            String lastUpdate = marketOpen
                ? (live != null && live.lastTradeTime != null
                    ? String.valueOf(live.lastTradeTime) : DateUtil.now())
                : lastSessionCloseIST();
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
            "SELECT m.trade_date,m.open,m.high,m.low,m.close,COALESCE(m.total_volume,m.volume) AS volume " +
            "FROM market_data m WHERE " + normalizedSql("m.symbol") + "=k.sym " +
            "AND (m.exchange IS NULL OR UPPER(m.exchange) IN ('NSE','NSE_EQ')) " +
            "ORDER BY m.trade_date DESC LIMIT 1) x");

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
                    if (existing == null || candidate.tradeDate.compareTo(existing.tradeDate) > 0) {
                        prices.put(tracked, candidate);
                    }
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

        List<Map<String, Object>> filtered = new ArrayList<>();
        Map<String, double[]> volData = new HashMap<>();
        for (Map<String, Object> s : candidates) {
            String sym = String.valueOf(s.getOrDefault("symbol", ""));
            double basePrice = getD(s, "base_price");
            List<CandleBar> bars = barsBySymbol.getOrDefault(normalizeSymbol(sym), List.of());
            boolean latestBarIsCurrent = currentPriceIsLatestBar(bars);
            int prevIdx = previousBarIndex(bars);
            double prevClose = bars.size() > prevIdx ? bars.get(prevIdx).close : 0;
            if (prevClose <= 0) continue;
            if (requireCrossing && prevClose >= basePrice) continue;
            // Traded volume for the current session. Mid-session the live quote carries it.
            // Outside market hours the quote has no volume at all, so fall back to the newest
            // stored bar - otherwise the stock is dropped for having "no volume" whenever the
            // quote price differs from the aggregated bar close by even a paisa, which after
            // the close is almost always (MAMATA: quote 420.35 against a 421 bar close).
            double quoteVol = getD(s, "volume");
            boolean useBarVolume = latestBarIsCurrent || (quoteVol <= 0 && !bars.isEmpty());
            // The 20-day average must exclude whichever bar the volume came from, otherwise
            // the comparison includes the very volume it is being judged against.
            int avgFrom = useBarVolume ? 1 : 0;
            double avg20Vol = bars.size() > avgFrom + 1
                ? averageVolume(bars, avgFrom, Math.min(20, bars.size() - avgFrom)) : 0;
            double todayVol = useBarVolume ? bars.get(0).volume : quoteVol;
            volData.put(normalizeSymbol(sym), new double[]{todayVol, avg20Vol});
            if (todayVol > 0 && avg20Vol > 0 && todayVol > avg20Vol) {
                // Record the three values the entry test was actually decided on. They were computed
                // here and then thrown away, so any screen showing this candidate had to display 0
                // for previous close - which makes the crossing impossible to verify by eye, and
                // makes a correct list look broken.
                s.put("previous_close", Math.round(prevClose * 100.0) / 100.0);
                s.put("volume", Math.round(todayVol));
                s.put("avg_20d_volume", Math.round(avg20Vol));
                filtered.add(s);
            }
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
            m.put("base_price",  r[6]); m.put("current_price", displayPrice);
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
            m.put("previous_close", prevClose > 0 ? prevClose : null);
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
        // shuts the prices stop moving, so the honest answer is the last session close -
        // otherwise the header claims a Sunday morning timestamp for Friday's prices.
        result.put("last_updated",    isMarketOpen() ? DateUtil.now() : lastSessionCloseIST());
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
                    sell = new TradeQuote(live, getTradeSellTime(), "live_price");
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
        result.put("rule", "Buy Current Top 10 at 15:20 and sell next trading day at 10:00");
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
            symbol, runDate, runDate + "T" + getTradeBuyTime(), "broker_intraday", 2);
        return quote;
    }

    private TradeQuote findIntradaySellQuote(String symbol, String runDate) {
        String brokerSymbol = normalizedSql("symbol");
        String brokerTrading = normalizedSql("trading_symbol");
        TradeQuote quote = queryQuoteByNormalizedSymbol(
            "SELECT close,candle_time FROM broker_market_candles " +
            "WHERE (" + brokerSymbol + "=? OR " + brokerTrading + "=?) AND trade_date > ? " +
            "AND close IS NOT NULL AND close>0 AND candle_time::text >= (trade_date::text || 'T" + getTradeSellTime() + "') " +
            "ORDER BY trade_date ASC, candle_time ASC LIMIT 1",
            symbol, runDate, null, "broker_intraday", 2);
        return quote;
    }

    private TradeQuote[] findDailyFallbackQuotes(String symbol, String runDate) {
        String marketSymbol = normalizedSql("symbol");
        String candleSymbol = normalizedSql("symbol");
        String candleTrading = normalizedSql("trading_symbol");
        TradeQuote buy = queryQuoteByNormalizedSymbol(
            "SELECT close,trade_date FROM market_data WHERE " + marketSymbol + "=? AND trade_date=? " +
            "AND close IS NOT NULL AND close>0 ORDER BY trade_date DESC LIMIT 1",
            symbol, runDate, null, "market_daily_close", 1);
        if (buy == null) buy = queryQuoteByNormalizedSymbol(
            "SELECT close,trade_date FROM broker_market_candles WHERE (" + candleSymbol + "=? OR " + candleTrading + "=?) AND trade_date=? " +
            "AND close IS NOT NULL AND close>0 AND (candle_mode IS NULL OR UPPER(candle_mode)='DAILY') ORDER BY trade_date DESC LIMIT 1",
            symbol, runDate, null, "broker_daily_close", 2);

        TradeQuote sell = queryQuoteByNormalizedSymbol(
            "SELECT close,trade_date FROM market_data WHERE " + marketSymbol + "=? AND trade_date>? " +
            "AND close IS NOT NULL AND close>0 ORDER BY trade_date ASC LIMIT 1",
            symbol, runDate, null, "market_daily_close", 1);
        if (sell == null) sell = queryQuoteByNormalizedSymbol(
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
            int prevIdx = previousBarIndex(bars);
            double prevClose = bars.size() > prevIdx ? bars.get(prevIdx).close : 0;
            if (prevClose <= 0 || prevClose >= basePrice) continue;

            // Same rule as the eligibility selector: outside market hours the quote has no
            // traded volume, so use the newest stored bar and drop it from its own average.
            double quoteVol = getD(s, "volume");
            boolean useBarVolume = latestBarIsCurrent || (quoteVol <= 0 && !bars.isEmpty());
            int avgFrom = useBarVolume ? 1 : 0;
            double avg20Vol = bars.size() > avgFrom + 1
                ? averageVolume(bars, avgFrom, Math.min(20, bars.size() - avgFrom)) : 0;
            double todayVol = useBarVolume ? bars.get(0).volume : quoteVol;
            boolean volAboveAvg = todayVol > 0 && avg20Vol > 0 && todayVol > avg20Vol;
            if (!volAboveAvg) continue;

            // Price Change % is now measured against the PREVIOUS CLOSE, not the base price.
            double priceChangePct = prevClose > 0
                ? Math.round(((currentPrice - prevClose) / prevClose) * 10000.0) / 100.0 : 0;

            s.put("previous_close", round2(prevClose));
            s.put("price_change_pct", priceChangePct);
            s.put("current_volume", todayVol > 0 ? round2(todayVol) : null);
            s.put("vol_above_20d_avg", "Yes");
            s.put("source", s.getOrDefault("current_price_source", ""));
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
        csv.append("Filter: Price crossing above Base Price, Return 0% to 5%, Volume > 20D Avg\n");
        csv.append("Condition: Previous Close < Base Price AND Current Price >= Base Price\n");
        csv.append("Note: Price Change % = (Current Price - Previous Close) / Previous Close\n");
        csv.append("Generated: ").append(generatedAt).append(",Total: ").append(enriched.size()).append("\n");
        csv.append("DISCLAIMER: For educational purposes only. Not SEBI investment advice.\n\n");
        csv.append("Symbol,Base Price,Base Price Date,Previous Close,Current Price,Price Change %,Current Volume,Volume Above 20D Avg,Source,Last Updated,Generated Date & Time\n");

        for (Map<String, Object> s : enriched) {
            csv.append(safe(s.get("symbol"))).append(",")
               .append(fmtNum(s.get("base_price"))).append(",")
               .append(safe(s.get("upload_date"))).append(",")
               .append(fmtNum(s.get("previous_close"))).append(",")
               .append(fmtNum(s.get("current_price"))).append(",")
               .append(fmtNum(s.get("price_change_pct"))).append(",")
               .append(fmtNum(s.get("current_volume"))).append(",")
               .append(safe(s.get("vol_above_20d_avg"))).append(",")
               .append(safe(s.get("source"))).append(",")
               .append(safe(s.get("last_refreshed"))).append(",")
               .append(generatedAt).append("\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
    private Map<String, Double> resolveCurrentPricesBatch(List<String> symbols, Long institutionId) {
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
                    "FROM market_data WHERE " + symCol + " IN (" + in3 + ") AND close IS NOT NULL AND close>0 " +
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

    private int compareDates(String d1, String d2) {
        if (d1 == null) return -1;
        if (d2 == null) return 1;
        try {
            return LocalDate.parse(parseDate(d1)).compareTo(LocalDate.parse(parseDate(d2)));
        } catch (Exception e) {
            return parseDate(d1).compareTo(parseDate(d2));
        }
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
    private int backfillBasePricesFromSavedMarketData(List<String> symbols, Long institutionId) {
        List<String> normalizedInput = symbols == null ? List.of() : symbols.stream()
            .map(this::normalizeSymbol)
            .filter(s -> !s.isBlank())
            .distinct()
            .toList();
        if (normalizedInput.isEmpty()) return 0;
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
        if (batchSymbols.isEmpty()) return 0;
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
        return batchSymbols.size();
    }

    @SuppressWarnings("unchecked")
    private SavedBasePrice lookupSavedBasePrice(Set<String> aliases, String uploadDate) {
        List<String> aliasList = aliases == null ? List.of() : aliases.stream()
            .map(this::normalizeSymbol)
            .filter(s -> !s.isBlank())
            .distinct()
            .toList();
        if (aliasList.isEmpty()) return null;
        String in = placeholders(aliasList.size());
        String marketSymbol = normalizedSql("symbol");
        String candleSymbol = normalizedSql("symbol");
        String sql =
            "SELECT close_price,trade_date,source FROM (" +
            "SELECT close AS close_price,trade_date,COALESCE(source,'MARKET_DATA') AS source FROM market_data " +
            "WHERE " + marketSymbol + " IN (" + in + ") AND close IS NOT NULL AND close>0 AND trade_date<=? " +
            "UNION ALL " +
            "SELECT close AS close_price,trade_date,COALESCE(source,'BROKER_DAILY') AS source FROM broker_market_candles " +
            "WHERE " + candleSymbol + " IN (" + in + ") AND close IS NOT NULL AND close>0 AND trade_date<=? " +
            "AND (candle_mode IS NULL OR UPPER(candle_mode)='DAILY')" +
            ") q ORDER BY trade_date DESC LIMIT 1";
        try {
            var query = em.createNativeQuery(sql);
            int p = 1;
            for (String alias : aliasList) query.setParameter(p++, alias);
            query.setParameter(p++, uploadDate);
            for (String alias : aliasList) query.setParameter(p++, alias);
            query.setParameter(p, uploadDate);
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
        if (hhmm == null || !hhmm.trim().matches("\\d{1,2}:\\d{2}")) return "15:20";
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
        return loadDailyBarsBatch(List.of(symbol == null ? "" : symbol), bars)
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
        Map<String, List<CandleBar>> out = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;

        List<String> normalized = symbols.stream()
            .map(this::normalizeSymbol)
            .filter(sym -> !sym.isBlank())
            .distinct()
            .toList();
        if (normalized.isEmpty()) return out;

        int batchSize = 900;
        for (int start = 0; start < normalized.size(); start += batchSize) {
            List<String> batch = normalized.subList(start, Math.min(start + batchSize, normalized.size()));
            collectMarketDataBars(out, batch, bars);
        }

        // Same fallback rule as the single-symbol path: fewer than 3 market_data bars
        // means we retry that symbol against the broker candles and keep the longer series.
        List<String> needsBroker = normalized.stream()
            .filter(sym -> out.getOrDefault(sym, List.of()).size() < 3)
            .toList();
        if (!needsBroker.isEmpty()) {
            Map<String, List<CandleBar>> broker = new HashMap<>();
            for (int start = 0; start < needsBroker.size(); start += batchSize) {
                List<String> batch = needsBroker.subList(start, Math.min(start + batchSize, needsBroker.size()));
                collectBrokerBars(broker, batch, bars);
            }
            for (String sym : needsBroker) {
                List<CandleBar> brokerSeries = broker.getOrDefault(sym, List.of());
                if (brokerSeries.size() > out.getOrDefault(sym, List.of()).size()) out.put(sym, brokerSeries);
            }
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
        return out;
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
    private void collectMarketDataBars(Map<String, List<CandleBar>> out, List<String> batch, int bars) {
        try {
            // ROW_NUMBER() applies the per-symbol "newest N" cut inside the database, so this
            // returns ~bars rows per symbol instead of every stored candle for every symbol.
            var query = em.createNativeQuery(
                "SELECT sym,trade_date,close,high,low,vol,source FROM (" +
                "SELECT UPPER(symbol) AS sym,trade_date,close,high,low," +
                "COALESCE(total_volume,volume) AS vol,source," +
                "ROW_NUMBER() OVER (PARTITION BY UPPER(symbol) ORDER BY trade_date DESC) AS rn " +
                "FROM market_data WHERE close IS NOT NULL AND UPPER(symbol) IN (" +
                placeholders(batch.size()) + ")) t WHERE rn <= ? ORDER BY sym, trade_date DESC");
            int i = 0;
            for (; i < batch.size(); i++) query.setParameter(i + 1, batch.get(i));
            query.setParameter(i + 1, bars);
            for (Object[] r : (List<Object[]>) query.getResultList()) {
                addBar(out, asText(r[0]), bars, r, 1);
            }
        } catch (Exception e) {
            log.debug("Batch daily bars unavailable for {} symbols: {}", batch.size(), e.getMessage());
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
            Optional<DhanInstrumentRef> ref = findBrokerInstrument(normalizeSymbol(symbol));
            if (ref.isEmpty()) ref = findDhanInstrument(normalizeSymbol(symbol));
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
                "SELECT close,high,low,volume,COALESCE(delivery_pct,0) FROM market_data " +
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
    private String lastSessionCloseIST() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.ZonedDateTime close = now.withHour(15).withMinute(30).withSecond(0).withNano(0);
        if (close.isAfter(now)) close = close.minusDays(1);
        while (close.getDayOfWeek().getValue() >= 6) close = close.minusDays(1);
        return close.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
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
        if (buyTime == null || buyTime.isBlank()) buyTime = historical ? "15:20" : now.substring(11, 16);
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
            String dayStart = date + " 00:00:00";
            String target   = date + " " + hhmm + ":59";
            String exactLo  = date + " " + hhmm + ":00";
            String dayEnd   = date + " 23:59:59";
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

        String pTime = normalizeHhmm(primaryTime, "15:20");
        boolean hasSecondary = secondaryDate != null && !secondaryDate.isBlank()
                            && secondaryTime != null && !secondaryTime.isBlank();
        String sTime = null;
        if (hasSecondary) {
            bad = sessionRejection(secondaryDate, secondaryTime, secondaryLabel);
            if (bad != null) { out.put("success", false); out.put("error", bad); return out; }
            sTime = normalizeHhmm(secondaryTime, "15:20");
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
            String bt = normalizeHhmm(buyTime, "15:20");
            String st = normalizeHhmm(sellTime, "10:00");
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
        String bt = normalizeHhmm(buyTime, "15:20");
        String st = normalizeHhmm(sellTime, "10:00");
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
            String dayStart = date + " 00:00:00";
            String target   = date + " " + hhmm + ":59";
            String dayEnd   = date + " 23:59:59";

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

    /** DB-only close for a symbol on a date: intraday near 15:20, else DAILY close, else null. */
    Double dbCloseAt(String symbol, String date) {
        if (symbol == null || date == null) return null;
        Double intr = intradayCloseAt(symbol, date, "15:20");
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
     * @param buyTimeHHmm time-of-day the portfolio is priced at, e.g. "15:00". The Top 10 shown
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
     * @param sellTimeHHmm   time of day to price the sell leg at, e.g. "15:20"
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
            String sellDate = sellHistorical ? resolvePeriodDate(sp, sellCustomDate) : null;
            String sellTime = normalizeHhmm(sellTimeHHmm);
            // Unselected sell leg outside market hours falls back the same way as the buy leg.
            boolean sellFromLastSession = !sellHistorical && !isMarketOpen();
            String sellShownDate = sellHistorical ? sellDate : (sellFromLastSession ? lastSessionDay : DateUtil.today());
            String sellShownTime = sellHistorical ? sellTime : (sellFromLastSession ? "15:30" : "LIVE");
            if (sellHistorical && sellDate != null && sellDate.compareTo(buyDate) < 0) {
                out.put("success", false);
                out.put("error", "Sell date " + sellDate + " is before the buy date " + buyDate + ".");
                out.put("stocks", new ArrayList<>());
                return out;
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

            List<Map<String, Object>> stocks = new ArrayList<>();
            double totalReturn = 0; int win = 0, lose = 0, missing = 0, counted = 0;
            for (Map<String, Object> s : currentTop10) {
                String sym = String.valueOf(s.getOrDefault("symbol", ""));
                double current = livePrices.getOrDefault(normalizeSymbol(sym), 0.0);
                if (current <= 0) current = getD(s, "current_price");
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
                if (sellHistorical) {
                    sell = intradayCloseAt(sym, sellDate, sellTime);
                    if (sell == null || sell <= 0) sell = dbCloseAt(sym, sellDate);
                } else if (sellFromLastSession) {
                    sell = dbCloseAt(sym, lastSessionDay);
                } else {
                    sell = current > 0 ? current : null;
                }

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol", sym);
                m.put("rank", (int) getD(s, "rank"));
                m.put("sector", s.getOrDefault("sector", ""));
                m.put("current_price", current);
                m.put("sell_price", sell);
                m.put("buy_date", rowBuyDate);
                m.put("buy_time", rowBuyTime);
                m.put("sell_date", sellShownDate);
                m.put("sell_time", sellShownTime);
                if (buy == null || buy <= 0 || sell == null || sell <= 0) {
                    m.put("buy_price", buy != null && buy > 0 ? buy : null);
                    m.put("return_pct", null);
                    m.put("price_available", false);
                    missing++;
                } else {
                    double ret = Math.round(((sell - buy) / buy) * 10000.0) / 100.0;
                    m.put("buy_price", buy);
                    m.put("return_pct", ret);
                    m.put("price_available", true);
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
                "WITH latest AS (" +
                "  SELECT UPPER(TRIM(symbol)) AS sym, MAX(LEFT(CAST(trade_date AS TEXT),10)) AS dd " +
                "  FROM market_data WHERE close IS NOT NULL GROUP BY UPPER(TRIM(symbol)) " +
                "  UNION ALL " +
                "  SELECT UPPER(TRIM(COALESCE(symbol,trading_symbol))) AS sym, MAX(LEFT(CAST(trade_date AS TEXT),10)) AS dd " +
                "  FROM broker_market_candles WHERE close IS NOT NULL AND UPPER(COALESCE(candle_mode,''))='DAILY' " +
                "  GROUP BY UPPER(TRIM(COALESCE(symbol,trading_symbol))) " +
                "), freshest AS (SELECT sym, MAX(dd) AS latest_daily FROM latest GROUP BY sym) " +
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
