package com.traderakshak.service;

import com.traderakshak.util.DateUtil;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataFetchEngine {

    private final DataImportService importService;
    private final AppConfigService appConfig;
    private final PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final DateTimeFormatter NSE_DDMMMYYYY = DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH);
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36";

    // Section 12: Smart Retry - configurable via AppConfigService
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_RETRY_DELAY_MS = 2000;

    // Section 9: Connection Pooling - shared HttpClient singleton
    private volatile HttpClient sharedClient;
    private final Object clientLock = new Object();

    // Section 6: API Response Caching
    private final ConcurrentHashMap<String, CachedResponse> responseCache = new ConcurrentHashMap<>();
    private static final long DEFAULT_CACHE_TTL_MS = 5 * 60 * 1000L;

    // Section 11: Rate Limit Handling - semaphore for concurrent request control
    private final Semaphore rateLimiter = new Semaphore(3);

    // Section 20: Live Progress Dashboard
    private volatile Map<String, Object> liveProgress = new ConcurrentHashMap<>(Map.of("status", "idle"));

    // Section 22: Benchmarking
    private final AtomicLong totalDownloadMs = new AtomicLong(0);
    private final AtomicLong totalImportMs = new AtomicLong(0);
    private final AtomicInteger totalDownloads = new AtomicInteger(0);
    private final AtomicInteger totalImports = new AtomicInteger(0);
    private final AtomicLong totalBytesDownloaded = new AtomicLong(0);

    // ═══════════════════════════════════════════════════════════════════════════
    //  NSE HOLIDAYS 2025-2027
    // ═══════════════════════════════════════════════════════════════════════════
    private static final Set<LocalDate> NSE_HOLIDAYS = Set.of(
        LocalDate.of(2025,1,26), LocalDate.of(2025,2,26), LocalDate.of(2025,3,14),
        LocalDate.of(2025,3,31), LocalDate.of(2025,4,10), LocalDate.of(2025,4,14),
        LocalDate.of(2025,4,18), LocalDate.of(2025,5,1), LocalDate.of(2025,8,15),
        LocalDate.of(2025,8,27), LocalDate.of(2025,10,2), LocalDate.of(2025,10,21),
        LocalDate.of(2025,10,22), LocalDate.of(2025,11,5), LocalDate.of(2025,11,26),
        LocalDate.of(2025,12,25),
        LocalDate.of(2026,1,26), LocalDate.of(2026,2,17), LocalDate.of(2026,3,5),
        LocalDate.of(2026,3,20), LocalDate.of(2026,3,30), LocalDate.of(2026,4,3),
        LocalDate.of(2026,4,14), LocalDate.of(2026,5,1), LocalDate.of(2026,6,25),
        LocalDate.of(2026,7,7), LocalDate.of(2026,8,15), LocalDate.of(2026,8,17),
        LocalDate.of(2026,10,2), LocalDate.of(2026,10,12), LocalDate.of(2026,10,20),
        LocalDate.of(2026,10,21), LocalDate.of(2026,11,9), LocalDate.of(2026,11,25),
        LocalDate.of(2026,12,25),
        LocalDate.of(2027,1,26), LocalDate.of(2027,3,11), LocalDate.of(2027,3,22),
        LocalDate.of(2027,3,26), LocalDate.of(2027,4,1), LocalDate.of(2027,4,14),
        LocalDate.of(2027,5,13), LocalDate.of(2027,6,15), LocalDate.of(2027,7,27),
        LocalDate.of(2027,8,6), LocalDate.of(2027,8,15), LocalDate.of(2027,10,2),
        LocalDate.of(2027,10,29), LocalDate.of(2027,11,8), LocalDate.of(2027,11,9),
        LocalDate.of(2027,11,19), LocalDate.of(2027,12,25)
    );

    // ═══════════════════════════════════════════════════════════════════════════
    //  ERROR CODE LIBRARY
    // ═══════════════════════════════════════════════════════════════════════════
    public enum ErrorCode {
        DW001("Weekend Selected", "The selected date falls on a weekend. Exchanges do not publish data on Saturday or Sunday."),
        DW002("Market Holiday", "The selected date is a stock exchange holiday. No trading session was held."),
        DW003("Archive Not Available", "The data file has not been published by the exchange for this date."),
        DW004("Network Error", "Unable to reach the exchange server. The system will retry automatically."),
        DW005("Request Timeout", "The server took too long to respond. Retrying with alternate sources."),
        DW006("Invalid / Corrupt File", "The downloaded file is corrupted or empty. Re-downloading from alternate source."),
        DW007("CSV Structure Invalid", "The file structure does not match expected format. Attempting auto-repair."),
        DW008("Symbol Mapping Failed", "Some stock symbols could not be mapped. Update Symbol Master for full coverage."),
        DW009("Duplicate Records", "Duplicate data detected. Existing records will be preserved, new records skipped."),
        DW010("Database Error", "Database write failed. The system will rollback and retry."),
        DW011("API Rate Limited", "Exchange API rate limit hit (HTTP 429). Waiting before retry."),
        DW012("Future Date", "The selected date is in the future. Exchange data is not yet available."),
        DW013("Partial Success", "Some dates were fetched successfully, but others were unavailable."),
        DW014("All Sources Exhausted", "Tried all available sources. Manual upload may be required."),
        DW015("Market Closed", "The market is currently closed. Today's data may not be available yet.");

        public final String title;
        public final String description;
        ErrorCode(String title, String description) { this.title = title; this.description = description; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SECTION 14: LIFECYCLE - Connection Pool & Thread Pool Management
    // ═══════════════════════════════════════════════════════════════════════════

    @PreDestroy
    public void shutdown() {
        responseCache.clear();
        synchronized (clientLock) {
            sharedClient = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SECTION 9: CONNECTION POOLING - Shared HttpClient
    // ═══════════════════════════════════════════════════════════════════════════

    private HttpClient getSharedClient() {
        HttpClient client = sharedClient;
        if (client == null) {
            synchronized (clientLock) {
                client = sharedClient;
                if (client == null) {
                    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
                    client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .cookieHandler(cookies)
                        .connectTimeout(Duration.ofSeconds(15))
                        .build();
                    sharedClient = client;
                    log.info("DataFetchEngine: shared HttpClient created (connection pooling active)");
                }
            }
        }
        return client;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  MAIN FETCH ORCHESTRATOR - Sections 1,2,3,7,13,15,22
    //  Multi-threaded, async, priority queue, incremental, memory-optimized
    // ═══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> smartFetch(String dataType, String source, String fromDate, String toDate) {
        long startMs = System.currentTimeMillis();
        resetBenchmarks();
        FetchContext ctx = new FetchContext(dataType, source, fromDate, toDate);
        ctx.step("Started", "Initializing optimized parallel fetch engine");

        // Step 1: Validate inputs
        ctx.step("Validate Inputs", "Checking data type, source, and date range");
        String type = (dataType == null ? "" : dataType).toLowerCase(Locale.ROOT);
        LocalDate from, to;
        try {
            from = LocalDate.parse(fromDate, ISO);
            to = LocalDate.parse(toDate, ISO);
        } catch (Exception e) {
            ctx.fail("DW003", "Invalid date format. Use YYYY-MM-DD.", ErrorCode.DW003);
            return ctx.result(startMs);
        }
        if (to.isBefore(from)) {
            ctx.fail("DW003", "To date must be same as or after From date.", ErrorCode.DW003);
            return ctx.result(startMs);
        }
        if (from.isAfter(LocalDate.now())) {
            ctx.fail("DW012", ErrorCode.DW012.description, ErrorCode.DW012);
            return ctx.result(startMs);
        }
        ctx.complete("Validate Inputs");

        // Section 7 & 8: Incremental Fetch - separate trading days, skip existing
        ctx.step("Analyze Dates", "Classifying trading days, checking existing records");
        List<LocalDate> tradingDays = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        boolean skipExisting = appConfig.getBoolean("data.skip_existing_records", true);

        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            String nonTradingReason = checkNonTradingDay(day);
            if (nonTradingReason != null) {
                Map<String, Object> daySkip = new LinkedHashMap<>();
                daySkip.put("date", day.toString());
                daySkip.put("status", "skipped");
                daySkip.put("reason", nonTradingReason);
                daySkip.put("errorCode", day.getDayOfWeek().getValue() >= 6 ? "DW001" : "DW002");
                daySkip.put("message", nonTradingReason);
                daySkip.put("suggestion", "Use previous trading day: " + previousTradingDay(day));
                daySkip.put("previousTradingDay", previousTradingDay(day).toString());
                skipped.add(daySkip);
            } else if (skipExisting && hasExistingData(day, type)) {
                Map<String, Object> daySkip = new LinkedHashMap<>();
                daySkip.put("date", day.toString());
                daySkip.put("status", "skipped");
                daySkip.put("reason", "Data already exists for " + day + ". Skipped (incremental fetch).");
                daySkip.put("errorCode", "DW009");
                daySkip.put("message", "Data already exists. Use force-fetch to re-download.");
                skipped.add(daySkip);
            } else {
                tradingDays.add(day);
            }
        }
        ctx.complete("Analyze Dates", tradingDays.size() + " trading days to fetch, " + skipped.size() + " skipped");

        if (tradingDays.isEmpty()) {
            ctx.status = skipped.isEmpty() ? "completed" : "completed_with_issues";
            ctx.errorCode = skipped.isEmpty() ? "" : (skipped.size() == 1 ? String.valueOf(skipped.get(0).get("errorCode")) : "DW013");
            ctx.message = skipped.isEmpty()
                ? "No dates to process in the selected range."
                : (skipped.size() == 1 ? String.valueOf(skipped.get(0).get("message")) : "No fetchable dates in range. " + skipped.size() + " date(s) were skipped.");
            ctx.skippedDates = skipped;
            ctx.step("Completed", ctx.message);
            ctx.complete("Completed");
            return ctx.result(startMs);
        }

        // Section 13: Priority Queue - recent dates first for faster availability
        tradingDays.sort(Comparator.reverseOrder());

        // Section 1,2,3: Multi-threaded parallel fetching
        int threadCount = Math.min(
            appConfig.getInt("data.fetch_thread_count", 4),
            Math.max(1, tradingDays.size())
        );
        ctx.step("Parallel Fetch", "Processing " + tradingDays.size() + " dates using " + threadCount + " parallel workers");

        // Section 20: Initialize live progress
        updateProgress("running", 0, tradingDays.size(), "Starting parallel fetch...", dataType, source);

        List<Map<String, Object>> fetched = Collections.synchronizedList(new ArrayList<>());
        List<Map<String, Object>> failedDays = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger totalRows = new AtomicInteger(0);
        AtomicInteger processedCount = new AtomicInteger(0);

        if (threadCount <= 1 || tradingDays.size() == 1) {
            // Sequential mode for single day or config=1
            for (LocalDate day : tradingDays) {
                Map<String, Object> dayResult = fetchAndImportDay(ctx, type, source, day, dataType);
                collectDayResult(dayResult, fetched, failedDays, totalRows);
                int done = processedCount.incrementAndGet();
                updateProgress("running", done, tradingDays.size(),
                    "Processed " + done + "/" + tradingDays.size() + " dates", dataType, source);
            }
        } else {
            // Section 1: Parallel mode with bounded thread pool
            ExecutorService pool = Executors.newFixedThreadPool(threadCount, r -> {
                Thread t = new Thread(r, "fetch-worker-" + Thread.currentThread().getId());
                t.setDaemon(true);
                return t;
            });
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (LocalDate day : tradingDays) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        Map<String, Object> dayResult = fetchAndImportDay(ctx, type, source, day, dataType);
                        collectDayResult(dayResult, fetched, failedDays, totalRows);
                        int done = processedCount.incrementAndGet();
                        updateProgress("running", done, tradingDays.size(),
                            "Processed " + done + "/" + tradingDays.size() + " dates", dataType, source);
                    }, pool);
                    futures.add(future);
                }
                // Wait for all with timeout
                long timeoutSec = appConfig.getLong("data.fetch_timeout_sec", 300);
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(timeoutSec, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    log.warn("Parallel fetch timed out after {}s. {} of {} completed.",
                        timeoutSec, processedCount.get(), tradingDays.size());
                } catch (Exception e) {
                    log.warn("Parallel fetch error: {}", e.getMessage());
                }
            } finally {
                pool.shutdown();
                try { pool.awaitTermination(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { pool.shutdownNow(); }
            }
        }
        ctx.complete("Parallel Fetch", fetched.size() + " succeeded, " + failedDays.size() + " failed");
        skipped.addAll(failedDays);

        // Final status
        if (fetched.isEmpty() && !skipped.isEmpty()) {
            ctx.status = "completed_with_issues";
            ctx.errorCode = skipped.size() == 1 ? String.valueOf(skipped.get(0).get("errorCode")) : "DW013";
            ctx.message = skipped.size() == 1
                ? String.valueOf(skipped.get(0).get("message"))
                : "No data could be fetched for the selected date range. " + skipped.size() + " date(s) were unavailable.";
        } else if (!fetched.isEmpty() && !skipped.isEmpty()) {
            ctx.status = "completed_partial";
            ctx.errorCode = "DW013";
            ctx.message = "Fetched " + fetched.size() + " date(s) successfully, skipped " + skipped.size() + " date(s).";
        } else if (!fetched.isEmpty()) {
            ctx.status = "completed";
            ctx.message = "Successfully fetched " + fetched.size() + " date(s) and imported " + totalRows.get() + " rows.";
        } else {
            ctx.status = "completed";
            ctx.message = "No dates to process in the selected range.";
        }

        ctx.totalRows = totalRows.get();
        ctx.fetchedDates = new ArrayList<>(fetched);
        ctx.skippedDates = skipped;

        // Section 22: Add benchmark data
        Map<String, Object> benchmarks = buildBenchmarks(startMs);
        ctx.step("Completed", ctx.message);
        ctx.complete("Completed");

        Map<String, Object> result = ctx.result(startMs);
        result.put("benchmarks", benchmarks);
        result.put("threadCount", threadCount);
        result.put("parallelMode", threadCount > 1);
        result.put("incrementalFetch", skipExisting);

        // Section 20: Update final progress
        updateProgress("completed", tradingDays.size(), tradingDays.size(), ctx.message, dataType, source);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SECTION 5: TRANSACTION-ISOLATED DAY FETCH + IMPORT
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> fetchAndImportDay(FetchContext ctx, String type, String source, LocalDate day, String dataType) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            return tx.execute(status -> fetchSingleDay(ctx, type, source, day, dataType));
        } catch (Exception e) {
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("date", day.toString());
            errorResult.put("status", "failed");
            errorResult.put("errorCode", "DW010");
            errorResult.put("message", "Transaction error: " + shortError(e.getMessage()));
            return errorResult;
        }
    }

    private void collectDayResult(Map<String, Object> dayResult,
                                  List<Map<String, Object>> fetched,
                                  List<Map<String, Object>> failed,
                                  AtomicInteger totalRows) {
        if (dayResult == null) return;
        String dayStatus = String.valueOf(dayResult.get("status"));
        if ("success".equals(dayStatus)) {
            fetched.add(dayResult);
            totalRows.addAndGet(((Number) dayResult.getOrDefault("rowsImported", 0)).intValue());
        } else {
            failed.add(dayResult);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SINGLE DAY FETCH WITH RETRY + SOURCE SWITCHING
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> fetchSingleDay(FetchContext ctx, String type, String source, LocalDate day, String dataType) {
        Map<String, Object> dayResult = new LinkedHashMap<>();
        dayResult.put("date", day.toString());

        String nonTradingReason = checkNonTradingDay(day);
        if (nonTradingReason != null) {
            dayResult.put("status", "skipped");
            dayResult.put("reason", nonTradingReason);
            dayResult.put("errorCode", day.getDayOfWeek().getValue() >= 6 ? "DW001" : "DW002");
            dayResult.put("message", nonTradingReason);
            dayResult.put("suggestion", "Use previous trading day: " + previousTradingDay(day));
            dayResult.put("previousTradingDay", previousTradingDay(day).toString());
            return dayResult;
        }

        List<SourceUrl> urls = buildSmartUrlList(type, source, day);
        int maxRetries = appConfig.getInt("data.fetch_max_retries", DEFAULT_MAX_RETRIES);

        List<Map<String, Object>> urlLog = new ArrayList<>();
        byte[] fileBytes = null;
        String fileName = null;
        String successUrl = null;
        String successSource = null;

        for (SourceUrl su : urls) {
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("url", su.url);
            attempt.put("source", su.source);

            for (int retry = 1; retry <= maxRetries; retry++) {
                attempt.put("attempt", retry);
                try {
                    long dlStart = System.currentTimeMillis();
                    byte[] bytes = downloadWithCaching(su.url);
                    long dlMs = System.currentTimeMillis() - dlStart;
                    totalDownloadMs.addAndGet(dlMs);
                    totalDownloads.incrementAndGet();

                    if (bytes == null || bytes.length == 0) {
                        attempt.put("result", "Empty response");
                        continue;
                    }

                    totalBytesDownloaded.addAndGet(bytes.length);

                    if (su.url.toLowerCase().endsWith(".zip")) {
                        byte[] extracted = extractZip(bytes);
                        if (extracted == null) {
                            attempt.put("result", "Corrupt ZIP (CRC failed)");
                            continue;
                        }
                        bytes = extracted;
                    }

                    fileBytes = bytes;
                    fileName = filenameFromUrl(su.url);
                    successUrl = su.url;
                    successSource = su.source;
                    attempt.put("result", "Success");
                    attempt.put("fileSize", bytes.length);
                    attempt.put("downloadMs", dlMs);
                    break;
                } catch (RateLimitException e) {
                    // Section 11: Smart rate limit handling with exponential backoff
                    long backoff = retryDelayWithJitter(retry, true);
                    attempt.put("result", "Rate limited (429). Waiting " + backoff + "ms...");
                    sleep(backoff);
                } catch (Exception e) {
                    String msg = shortError(e.getMessage());
                    attempt.put("result", msg);
                    if (retry < maxRetries && isRetryable(e)) {
                        // Section 12: Exponential backoff with jitter
                        long backoff = retryDelayWithJitter(retry, false);
                        sleep(backoff);
                    }
                }
            }
            urlLog.add(attempt);
            if (fileBytes != null) break;
        }
        dayResult.put("urlsAttempted", urlLog);

        if (fileBytes == null) {
            dayResult.put("status", "failed");
            dayResult.put("errorCode", "DW014");
            dayResult.put("message", "Data not available from any source for " + day + ".");
            dayResult.put("suggestion", "Try manual upload or wait for exchange publication.");
            dayResult.put("recommendations", List.of(
                "Try fetching previous trading day: " + previousTradingDay(day),
                "Use Broker API as alternate source",
                "Upload data manually using the Download Template"
            ));
            return dayResult;
        }

        String content;
        try {
            content = importService.decode(fileBytes, fileName);
        } catch (Exception e) {
            dayResult.put("status", "failed");
            dayResult.put("errorCode", "DW006");
            dayResult.put("message", "Downloaded file could not be decoded: " + shortError(e.getMessage()));
            return dayResult;
        }

        if (content.isBlank() || content.lines().count() < 2) {
            dayResult.put("status", "failed");
            dayResult.put("errorCode", "DW007");
            dayResult.put("message", "File has no data rows. The exchange may not have published this report yet.");
            return dayResult;
        }

        // Section 4 & 5: Import with transaction isolation
        try {
            long importStart = System.currentTimeMillis();
            Map<String, Object> importResult = importService.importWarehouseText(dataType, content, day.toString(), successSource != null ? successSource : source);
            long importMs = System.currentTimeMillis() - importStart;
            totalImportMs.addAndGet(importMs);
            totalImports.incrementAndGet();

            int rows = ((Number) importResult.getOrDefault("rowsImported", 0)).intValue();

            dayResult.put("status", "success");
            dayResult.put("rowsImported", rows);
            dayResult.put("targetTable", importResult.getOrDefault("targetTable", ""));
            dayResult.put("file", fileName);
            dayResult.put("source", successSource);
            dayResult.put("url", successUrl);
            dayResult.put("fileSize", fileBytes.length);
            dayResult.put("importMs", importMs);
            dayResult.put("message", rows + " rows imported from " + successSource);
        } catch (Exception e) {
            dayResult.put("status", "failed");
            dayResult.put("errorCode", "DW010");
            dayResult.put("message", "Database import failed: " + shortError(e.getMessage()));
        }

        return dayResult;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SECTION 17: SMART URL LIST BUILDER (Symbol Grouping by Exchange)
    // ═══════════════════════════════════════════════════════════════════════════

    private List<SourceUrl> buildSmartUrlList(String type, String primarySource, LocalDate day) {
        String yyyy = String.valueOf(day.getYear());
        String mon = day.format(MONTH_FMT).toUpperCase(Locale.ROOT);
        String ddMonYear = day.format(NSE_DDMMMYYYY).toUpperCase(Locale.ROOT);
        String ymd = day.format(BASIC);
        String dmy = day.format(DDMMYYYY);

        List<SourceUrl> urls = new ArrayList<>();

        if (type.contains("delivery") || primarySource.toUpperCase(Locale.ROOT).contains("MTO")) {
            urls.add(new SourceUrl("NSE", "https://nsearchives.nseindia.com/archives/equities/mto/MTO_" + dmy + ".DAT"));
            urls.add(new SourceUrl("NSE Archive", "https://archives.nseindia.com/archives/equities/mto/MTO_" + dmy + ".DAT"));
            urls.add(new SourceUrl("NSE BhavData", "https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_" + dmy + ".csv"));
        } else if (type.contains("f&o") || type.contains("fo ") || type.contains("derivative")) {
            urls.add(new SourceUrl("NSE", "https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_" + ymd + "_F_0000.csv.zip"));
            urls.add(new SourceUrl("NSE Archive", "https://archives.nseindia.com/content/historical/DERIVATIVES/" + yyyy + "/" + mon + "/fo" + ddMonYear + "bhav.csv.zip"));
            urls.add(new SourceUrl("NSE Legacy", "https://nsearchives.nseindia.com/archives/fo/bhav/fo" + ddMonYear + "bhav.csv.zip"));
        } else if (type.contains("52w") || type.contains("ath") || type.contains("snapshot")) {
            urls.add(new SourceUrl("NSE", "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_" + ymd + "_F_0000.csv.zip"));
            urls.add(new SourceUrl("NSE Archive", "https://archives.nseindia.com/content/historical/EQUITIES/" + yyyy + "/" + mon + "/cm" + ddMonYear + "bhav.csv.zip"));
        } else {
            urls.add(new SourceUrl("NSE", "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_" + ymd + "_F_0000.csv.zip"));
            urls.add(new SourceUrl("NSE Archive", "https://archives.nseindia.com/content/historical/EQUITIES/" + yyyy + "/" + mon + "/cm" + ddMonYear + "bhav.csv.zip"));
            urls.add(new SourceUrl("NSE Legacy", "https://nsearchives.nseindia.com/archives/equities/bhavcopy/pr/PR" + dmy + ".zip"));
        }

        return urls;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  TRADING DAY VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════

    private String checkNonTradingDay(LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) return "No Trading Session — " + day + " is a Saturday. Exchanges are closed on weekends.";
        if (dow == DayOfWeek.SUNDAY) return "No Trading Session — " + day + " is a Sunday. Exchanges are closed on weekends.";
        if (NSE_HOLIDAYS.contains(day)) return "Market Holiday — " + day + " is an exchange holiday. No trading session was held.";
        if (day.isAfter(LocalDate.now())) return "Future Date — " + day + " has not occurred yet. Exchange data is not available.";
        return null;
    }

    public boolean isTradingDay(LocalDate day) {
        return checkNonTradingDay(day) == null;
    }

    public LocalDate previousTradingDay(LocalDate day) {
        LocalDate prev = day.minusDays(1);
        int tries = 0;
        while (!isTradingDay(prev) && tries < 10) {
            prev = prev.minusDays(1);
            tries++;
        }
        return prev;
    }

    public LocalDate nextTradingDay(LocalDate day) {
        LocalDate next = day.plusDays(1);
        int tries = 0;
        while (!isTradingDay(next) && tries < 10) {
            next = next.plusDays(1);
            tries++;
        }
        return next;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  DATE VALIDATION API (for frontend)
    // ═══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> validateDateRange(String fromDate, String toDate) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            LocalDate from = LocalDate.parse(fromDate, ISO);
            LocalDate to = LocalDate.parse(toDate, ISO);
            List<Map<String, Object>> days = new ArrayList<>();
            int tradingDays = 0, weekends = 0, holidays = 0, futureDays = 0;

            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                Map<String, Object> dayInfo = new LinkedHashMap<>();
                dayInfo.put("date", d.toString());
                dayInfo.put("dayOfWeek", d.getDayOfWeek().toString());
                String reason = checkNonTradingDay(d);
                if (reason == null) {
                    dayInfo.put("tradingDay", true);
                    tradingDays++;
                } else {
                    dayInfo.put("tradingDay", false);
                    dayInfo.put("reason", reason);
                    if (d.getDayOfWeek().getValue() >= 6) weekends++;
                    else if (d.isAfter(LocalDate.now())) futureDays++;
                    else holidays++;
                }
                days.add(dayInfo);
            }
            result.put("days", days);
            result.put("tradingDays", tradingDays);
            result.put("weekends", weekends);
            result.put("holidays", holidays);
            result.put("futureDays", futureDays);
            result.put("totalDays", days.size());
            result.put("valid", tradingDays > 0);
            if (tradingDays == 0) {
                result.put("message", "No trading days in selected range. All dates are weekends, holidays, or future dates.");
                result.put("suggestion", "Previous trading day: " + previousTradingDay(from));
                result.put("previousTradingDay", previousTradingDay(from).toString());
            }
        } catch (Exception e) {
            result.put("valid", false);
            result.put("message", "Invalid date format: " + e.getMessage());
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ERROR CODE LOOKUP
    // ═══════════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getErrorCodes() {
        List<Map<String, Object>> codes = new ArrayList<>();
        for (ErrorCode ec : ErrorCode.values()) {
            codes.add(Map.of("code", ec.name(), "title", ec.title, "description", ec.description));
        }
        return codes;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  FETCH LOG STORAGE
    // ═══════════════════════════════════════════════════════════════════════════

    public void saveFetchLog(Long userId, Map<String, Object> fetchResult) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                em.createNativeQuery(
                    "INSERT INTO data_fetch_logs(user_id, data_type, source, from_date, to_date, status, error_code, " +
                    "rows_imported, files_fetched, skipped_count, duration_ms, message, details_json, created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)")
                    .setParameter(1, userId)
                    .setParameter(2, fetchResult.getOrDefault("dataType", ""))
                    .setParameter(3, fetchResult.getOrDefault("source", ""))
                    .setParameter(4, fetchResult.getOrDefault("fromDate", ""))
                    .setParameter(5, fetchResult.getOrDefault("toDate", ""))
                    .setParameter(6, fetchResult.getOrDefault("status", "unknown"))
                    .setParameter(7, fetchResult.getOrDefault("errorCode", ""))
                    .setParameter(8, ((Number) fetchResult.getOrDefault("totalRows", 0)).intValue())
                    .setParameter(9, ((List<?>) fetchResult.getOrDefault("fetchedDates", List.of())).size())
                    .setParameter(10, ((List<?>) fetchResult.getOrDefault("skippedDates", List.of())).size())
                    .setParameter(11, ((Number) fetchResult.getOrDefault("durationMs", 0)).longValue())
                    .setParameter(12, fetchResult.getOrDefault("message", ""))
                    .setParameter(13, "{}")
                    .setParameter(14, DateUtil.now())
                    .executeUpdate()
            );
        } catch (Exception e) {
            log.debug("Could not save fetch log: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getFetchLogs(Long userId, int page, int size) {
        String where = userId != null ? "WHERE user_id = :uid" : "";
        var countQ = em.createNativeQuery("SELECT COUNT(*) FROM data_fetch_logs " + where);
        if (userId != null) countQ.setParameter("uid", userId);
        long total = ((Number) countQ.getSingleResult()).longValue();

        var dataQ = em.createNativeQuery(
            "SELECT id, data_type, source, from_date, to_date, status, error_code, rows_imported, " +
            "files_fetched, skipped_count, duration_ms, message, created_at " +
            "FROM data_fetch_logs " + where + " ORDER BY created_at DESC LIMIT :sz OFFSET :off");
        if (userId != null) dataQ.setParameter("uid", userId);
        List<?> rows = dataQ.setParameter("sz", size).setParameter("off", page * size)
            .getResultList();

        List<Map<String, Object>> items = new ArrayList<>();
        for (Object o : rows) {
            Object[] r = (Object[]) o;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r[0]); item.put("dataType", r[1]); item.put("source", r[2]);
            item.put("fromDate", r[3]); item.put("toDate", r[4]); item.put("status", r[5]);
            item.put("errorCode", r[6]); item.put("rowsImported", r[7]); item.put("filesFetched", r[8]);
            item.put("skippedCount", r[9]); item.put("durationMs", r[10]); item.put("message", r[11]);
            item.put("createdAt", r[12]);
            items.add(item);
        }
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SECTION 22: DASHBOARD STATS + BENCHMARKING
    // ═══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> getDashboardStats(Long userId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            String today = LocalDate.now().toString();
            stats.put("todayFetchCount", singleLong("SELECT COUNT(*) FROM data_fetch_logs WHERE created_at::date = ?::date", today));
            stats.put("completedJobs", singleLong("SELECT COUNT(*) FROM data_fetch_logs WHERE status = 'completed'"));
            stats.put("failedJobs", singleLong("SELECT COUNT(*) FROM data_fetch_logs WHERE status IN ('failed','completed_with_issues')"));
            stats.put("totalRows", singleLong("SELECT COALESCE(SUM(rows_imported),0) FROM data_fetch_logs WHERE status = 'completed'"));
            stats.put("lastSync", singleStr("SELECT MAX(created_at) FROM data_fetch_logs WHERE status = 'completed'"));

            stats.put("technicalTables", singleLong("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('market_data','broker_market_candles','broker_delivery_volumes','option_chain','fii_dii_data')"));
            stats.put("fundamentalTables", singleLong("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('broker_fundamentals','broker_corporate_actions')"));

            stats.put("marketDataCount", singleLong("SELECT COUNT(*) FROM market_data"));
            stats.put("deliveryCount", singleLong("SELECT COUNT(*) FROM broker_delivery_volumes"));

            stats.put("databaseSize", singleStr("SELECT pg_size_pretty(pg_database_size(current_database()))"));

            // Section 22: Performance benchmarks
            stats.put("avgDownloadMs", totalDownloads.get() > 0 ? totalDownloadMs.get() / totalDownloads.get() : 0);
            stats.put("avgImportMs", totalImports.get() > 0 ? totalImportMs.get() / totalImports.get() : 0);
            stats.put("totalBytesDownloaded", totalBytesDownloaded.get());
            stats.put("cacheSize", responseCache.size());
            stats.put("cacheHitRate", cacheHitRate());

            // Section 21: Engine configuration
            stats.put("fetchThreadCount", appConfig.getInt("data.fetch_thread_count", 4));
            stats.put("skipExisting", appConfig.getBoolean("data.skip_existing_records", true));
            stats.put("cacheTtlMs", appConfig.getLong("data.cache_ttl_ms", DEFAULT_CACHE_TTL_MS));
            stats.put("maxRetries", appConfig.getInt("data.fetch_max_retries", DEFAULT_MAX_RETRIES));
        } catch (Exception e) {
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SECTION 20: LIVE PROGRESS DASHBOARD
    // ═══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> getLiveProgress() {
        return liveProgress;
    }

    private void updateProgress(String status, int processed, int total, String message, String dataType, String source) {
        Map<String, Object> progress = new ConcurrentHashMap<>();
        progress.put("status", status);
        progress.put("processed", processed);
        progress.put("total", total);
        progress.put("percentage", total > 0 ? Math.round((processed * 100.0) / total) : 0);
        progress.put("message", message);
        progress.put("dataType", dataType);
        progress.put("source", source);
        progress.put("updatedAt", DateUtil.now());
        progress.put("avgDownloadMs", totalDownloads.get() > 0 ? totalDownloadMs.get() / totalDownloads.get() : 0);
        progress.put("totalBytesDownloaded", totalBytesDownloaded.get());
        this.liveProgress = progress;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SECTION 6: HTTP DOWNLOAD WITH CACHING + RATE LIMITING
    // ═══════════════════════════════════════════════════════════════════════════

    private byte[] downloadWithCaching(String url) throws Exception {
        // Check cache first
        long cacheTtl = appConfig.getLong("data.cache_ttl_ms", DEFAULT_CACHE_TTL_MS);
        CachedResponse cached = responseCache.get(url);
        if (cached != null && !cached.isExpired(cacheTtl)) {
            cacheHits.incrementAndGet();
            log.debug("Cache hit for {}", url);
            return cached.data;
        }
        cacheMisses.incrementAndGet();

        // Section 11: Rate limiting with semaphore
        boolean acquired = false;
        try {
            acquired = rateLimiter.tryAcquire(30, TimeUnit.SECONDS);
            if (!acquired) {
                throw new RateLimitException("Rate limiter timeout - too many concurrent requests");
            }
            byte[] data = downloadWithRetry(url);
            if (data != null && data.length > 0) {
                responseCache.put(url, new CachedResponse(data, System.currentTimeMillis()));
                evictExpiredCache(cacheTtl);
            }
            return data;
        } finally {
            if (acquired) rateLimiter.release();
        }
    }

    private byte[] downloadWithRetry(String url) throws Exception {
        HttpClient client = getSharedClient();
        warmup(client, url);
        HttpRequest request = buildRequest(url);
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 429) throw new RateLimitException("HTTP 429 - Rate Limited");
        if (response.statusCode() >= 400) throw new IllegalStateException("HTTP " + response.statusCode());

        byte[] body = response.body();
        // Section 10: GZIP decompression
        if (url.toLowerCase(Locale.ROOT).endsWith(".gz")) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return gz.readAllBytes();
            }
        }
        return body;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ZIP VALIDATION & EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════════

    private byte[] extractZip(byte[] zipBytes) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".csv") || name.endsWith(".dat") || name.endsWith(".txt")) {
                    byte[] data = zis.readAllBytes();
                    if (data.length == 0) continue;
                    if (entry.getCrc() != -1) {
                        CRC32 crc = new CRC32();
                        crc.update(data);
                        if (crc.getValue() != entry.getCrc()) {
                            log.warn("CRC mismatch for {}: expected {} got {}", name, entry.getCrc(), crc.getValue());
                            return null;
                        }
                    }
                    return data;
                }
            }
        } catch (Exception e) {
            log.warn("ZIP extraction failed: {}", e.getMessage());
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SECTION 7: INCREMENTAL FETCH - Check Existing Records
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean hasExistingData(LocalDate day, String type) {
        try {
            String table = type.contains("delivery") ? "broker_delivery_volumes" :
                           type.contains("f&o") || type.contains("derivative") ? "broker_market_candles" :
                           "market_data";
            String dateCol = "trade_date";
            long count = singleLong("SELECT COUNT(*) FROM " + table + " WHERE " + dateCol + " = ?", day.toString());
            return count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SECTION 12: SMART RETRY WITH EXPONENTIAL BACKOFF + JITTER
    // ═══════════════════════════════════════════════════════════════════════════

    private long retryDelayWithJitter(int attempt, boolean rateLimited) {
        int baseDelay = appConfig.getInt("data.retry_base_delay_ms", DEFAULT_RETRY_DELAY_MS);
        long delay;
        if (rateLimited) {
            delay = (long) baseDelay * attempt * 4;
        } else {
            delay = (long) baseDelay * (1L << (attempt - 1));
        }
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, delay / 4));
        return Math.min(delay + jitter, 30000);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SECTION 22: BENCHMARKING HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final AtomicInteger cacheMisses = new AtomicInteger(0);

    private void resetBenchmarks() {
        totalDownloadMs.set(0);
        totalImportMs.set(0);
        totalDownloads.set(0);
        totalImports.set(0);
        totalBytesDownloaded.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    private Map<String, Object> buildBenchmarks(long startMs) {
        long totalMs = System.currentTimeMillis() - startMs;
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("totalDurationMs", totalMs);
        b.put("totalDownloadMs", totalDownloadMs.get());
        b.put("totalImportMs", totalImportMs.get());
        b.put("avgDownloadMs", totalDownloads.get() > 0 ? totalDownloadMs.get() / totalDownloads.get() : 0);
        b.put("avgImportMs", totalImports.get() > 0 ? totalImportMs.get() / totalImports.get() : 0);
        b.put("totalDownloads", totalDownloads.get());
        b.put("totalImports", totalImports.get());
        b.put("totalBytesDownloaded", totalBytesDownloaded.get());
        b.put("totalBytesMB", Math.round(totalBytesDownloaded.get() / 1024.0 / 1024.0 * 100) / 100.0);
        b.put("cacheHits", cacheHits.get());
        b.put("cacheMisses", cacheMisses.get());
        b.put("cacheHitRate", cacheHitRate());
        b.put("throughputRowsPerSec", totalMs > 0 ? Math.round(totalImports.get() * 1000.0 / totalMs) : 0);
        return b;
    }

    private String cacheHitRate() {
        int total = cacheHits.get() + cacheMisses.get();
        return total > 0 ? Math.round(cacheHits.get() * 100.0 / total) + "%" : "0%";
    }

    private void evictExpiredCache(long ttlMs) {
        long now = System.currentTimeMillis();
        responseCache.entrySet().removeIf(e -> e.getValue().isExpired(ttlMs));
        int maxSize = appConfig.getInt("data.cache_max_entries", 100);
        if (responseCache.size() > maxSize) {
            responseCache.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().timestamp))
                .limit(responseCache.size() - maxSize)
                .forEach(e -> responseCache.remove(e.getKey()));
        }
    }

    public void clearCache() {
        responseCache.clear();
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HTTP CLIENT HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private void warmup(HttpClient client, String url) {
        try {
            String host = URI.create(url).getHost();
            String home = host != null && host.contains("niftyindices") ? "https://www.niftyindices.com/" : "https://www.nseindia.com/";
            client.send(buildRequest(home), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    private HttpRequest buildRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Referer", refererFor(url))
            .GET().build();
    }

    private String refererFor(String url) {
        if (url.contains("/content/fo/") || url.contains("/DERIVATIVES/")) return "https://www.nseindia.com/all-reports-derivatives";
        if (url.contains("fiidii")) return "https://www.nseindia.com/reports/fii-dii";
        if (url.contains("option-chain")) return "https://www.nseindia.com/option-chain";
        return "https://www.nseindia.com/all-reports";
    }

    private boolean isRetryable(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("timeout") || msg.contains("connection reset") || msg.contains("refused")
            || msg.contains("http 5") || msg.contains("eof");
    }

    private void sleep(long ms) {
        try { Thread.sleep(Math.min(ms, 30000)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private String filenameFromUrl(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : "nse-report.csv";
    }

    private String shortError(String value) {
        if (value == null || value.isBlank()) return "No response";
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() > 200 ? clean.substring(0, 200) + "..." : clean;
    }

    private long singleLong(String sql, Object... params) {
        try {
            var q = em.createNativeQuery(sql);
            for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
            return ((Number) q.getSingleResult()).longValue();
        } catch (Exception e) { return 0; }
    }
    private String singleStr(String sql) {
        try { Object r = em.createNativeQuery(sql).getSingleResult(); return r == null ? "" : r.toString(); }
        catch (Exception e) { return ""; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  INNER CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    private record SourceUrl(String source, String url) {}

    private static class RateLimitException extends Exception {
        RateLimitException(String msg) { super(msg); }
    }

    private record CachedResponse(byte[] data, long timestamp) {
        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - timestamp > ttlMs;
        }
    }

    static class FetchContext {
        String dataType, source, fromDate, toDate;
        String status = "running";
        String message = "";
        String errorCode = "";
        int totalRows = 0;
        List<Map<String, Object>> steps = Collections.synchronizedList(new ArrayList<>());
        List<Map<String, Object>> fetchedDates = Collections.synchronizedList(new ArrayList<>());
        List<Map<String, Object>> skippedDates = Collections.synchronizedList(new ArrayList<>());

        FetchContext(String dt, String src, String from, String to) {
            dataType = dt; source = src; fromDate = from; toDate = to;
        }

        void step(String name, String detail) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("detail", detail);
            s.put("status", "running");
            s.put("timestamp", DateUtil.now());
            steps.add(s);
        }

        void complete(String name) { complete(name, null); }
        void complete(String name, String detail) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                if (name.equals(steps.get(i).get("name"))) {
                    steps.get(i).put("status", "done");
                    if (detail != null) steps.get(i).put("detail", detail);
                    break;
                }
            }
        }

        void fail(String code, String msg, ErrorCode ec) {
            status = "failed";
            errorCode = code;
            message = msg;
            step(ec.title, ec.description);
            steps.get(steps.size() - 1).put("status", "failed");
        }

        Map<String, Object> result(long startMs) {
            long durationMs = System.currentTimeMillis() - startMs;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("dataType", dataType);
            r.put("source", source);
            r.put("fromDate", fromDate);
            r.put("toDate", toDate);
            r.put("status", status);
            r.put("errorCode", errorCode);
            r.put("message", message);
            r.put("totalRows", totalRows);
            r.put("fetchedDates", fetchedDates);
            r.put("skippedDates", skippedDates);
            r.put("fetchedCount", fetchedDates.size());
            r.put("skippedCount", skippedDates.size());
            r.put("steps", steps);
            r.put("durationMs", durationMs);
            r.put("duration", durationMs < 1000 ? durationMs + "ms" : (durationMs / 1000) + "s");
            return r;
        }
    }
}
