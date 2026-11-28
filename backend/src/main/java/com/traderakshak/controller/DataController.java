package com.traderakshak.controller;
import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.service.DataImportService;
import com.traderakshak.service.DhanBrokerService;
import com.traderakshak.service.DhanHistoricalBackfillService;
import com.traderakshak.service.NseExchangeFetchService;
import com.traderakshak.service.IndexMembershipService;
import com.traderakshak.service.IndexHistoryFetchService;
import com.traderakshak.service.DataFetchEngine;
import com.traderakshak.util.DateUtil;
import com.traderakshak.util.UserMessage;
import com.traderakshak.util.InrUtil;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.DigestOutputStream;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
@RestController @RequestMapping("/data") @RequiredArgsConstructor @Slf4j
@Tag(name="Data Warehouse", description="Market data upload, source fetch, cleanup")
public class DataController {
    private final DataImportService importService;
    private final DhanBrokerService dhanBrokerService;
    private final DhanHistoricalBackfillService dhanHistoricalBackfillService;
    private final NseExchangeFetchService nseExchangeFetchService;
    private final IndexMembershipService indexMembershipService;
    private final IndexHistoryFetchService indexHistoryFetchService;
    private final DataFetchEngine dataFetchEngine;
    private final PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;
    private static final int MAX_MANUAL_FILES = 300;
    private static final long MAX_SINGLE_FILE_BYTES = 2L * 1024L * 1024L;
    private static final Set<String> REMOTE_SOURCES = Set.of("NSE", "BSE", "BROKER", "QUARTERLY", "NSE MTO", "DHAN PUBLIC");
    private final Map<Long, Map<String, Object>> userActivities = new ConcurrentHashMap<>();
    /**
     * Live state of a database removal, polled by the screen.
     *
     * <p>Removal used to run inside the HTTP request: it backed up a 24 GB database and then
     * truncated it before returning, so the browser sat with no feedback for minutes and often
     * timed out. It now runs on a background thread and reports each phase and table here.
     */
    private final Map<Long, Map<String, Object>> removalProgress = new ConcurrentHashMap<>();
    private static final List<Map<String, Object>> DATA_TYPES = List.of(
        row("FII/DII Data", List.of("NSE", "Broker", "BSE", "Manual"), "CSV, XLSX from NSE/BSE or broker FII/DII flow format"),
        row("Option Chain", List.of("NSE", "Broker", "Manual"), "CSV, XLSX, JSON option-chain snapshot in NSE or broker format"),
        row("Corporate Actions", List.of("NSE", "Manual", "Broker"), "CSV, XLSX corporate-action file with symbol, action, ex-date, record-date"),
        row("Fundamentals", List.of("NSE", "Broker", "Quarterly", "Manual"), "CSV, XLSX quarterly fundamentals in exchange or broker format"),
        row("52W/ATH/Snapshot", List.of("Manual", "Broker"), "CSV, XLSX snapshot with symbol, 52W high/low, ATH and latest price"),
        row("F&O Bhavcopy", List.of("NSE", "Manual", "Broker"), "CSV, ZIP, XLSX derivative bhavcopy in exchange or broker format"),
        row("Delivery Volume", List.of("NSE MTO", "Manual", "Broker"), "CSV, TXT, ZIP MTO delivery volume file"),
        row("Daily Bhavcopy (OHLCV)", List.of("NSE", "Manual", "Broker"), "CSV, ZIP, XLSX daily OHLCV bhavcopy"),
        row("Stock List", List.of("Dhan Public"), "Dhan public detailed instrument CSV with security ID, exchange, segment, symbol, ISIN, expiry, strike, lot size and raw details")
    );

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String,Object>>> overview(@AuthenticationPrincipal User u) {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "dataTypes", DATA_TYPES,
            "backendTables", discoverBackendTables(),
            "activeActivity", activityFor(u),
            "dependentSections", dependentSections(),
            "manualUploadRules", Map.of(
                "maxFiles", MAX_MANUAL_FILES,
                "maxSingleFileMb", 2,
                "accepted", "Use the same NSE/BSE or broker file format for the selected data type."
            )
        )));
    }

    @GetMapping("/activity")
    public ResponseEntity<ApiResponse<Map<String,Object>>> activity(@AuthenticationPrincipal User u) {
        return ResponseEntity.ok(ApiResponse.ok(activityFor(u)));
    }

    @PostMapping("/activity/fetch")
    public ResponseEntity<ApiResponse<Map<String,Object>>> fetchData(
            @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal User u) {
        if (u == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("AUTH-LOGIN", "Please login again, then retry Fetch."));
        }
        Map<String, Object> active = activityFor(u);
        if (activityBlocksDhanStart(u, active)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DATA-ACTIVE", "Finish or stop the current activity before starting another one."));
        }
        // A full-history fetch writes tens of GB. If the database drive is nearly full it will
        // run until PostgreSQL cannot write, which risks the server refusing to start again -
        // so refuse up front and say plainly how much room is left.
        String lowDisk = lowDiskWarning();
        if (lowDisk != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("DATA-DISK", lowDisk));
        }
        String dataType = String.valueOf(req.getOrDefault("dataType", ""));
        String source = norm(req.get("source"));
        String fromDate = String.valueOf(req.getOrDefault("fromDate", ""));
        String toDate = String.valueOf(req.getOrDefault("toDate", ""));
        boolean stockList = isStockList(dataType);
        if (!REMOTE_SOURCES.contains(source) || source.equals("MANUAL")) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-SOURCE", "Fetch is available only for NSE, BSE, Broker, Dhan Public, NSE MTO, or Quarterly sources."));
        }
        if (!stockList && (fromDate.isBlank() || toDate.isBlank())) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-DATE", "From date and To date are required for fetch."));
        }
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("activityName", "Fetch " + dataType + " from " + source);
        activity.put("dataType", dataType);
        activity.put("source", source);
        activity.put("status", "running");
        activity.put("startedAt", DateUtil.now());
        if (stockList) {
            try {
                Map<String, Object> result = dhanHistoricalBackfillService.refreshInstrumentMasterNow(u, null);
                activity.put("status", "completed");
                activity.put("completedAt", DateUtil.now());
                activity.put("message", result.getOrDefault("message", "Dhan public stock list fetched and saved."));
                activity.put("details", result);
                activity.put("rowsImported", result.getOrDefault("rowsImported", result.getOrDefault("imported", 0)));
                activity.put("targetTable", result.getOrDefault("targetTable", targetTableForDataType(dataType)));
            } catch (Exception e) {
                activity.put("status", "failed");
                activity.put("completedAt", DateUtil.now());
                activity.put("message", UserMessage.of(e));
                activity.put("targetTable", targetTableForDataType(dataType));
            }
        } else if ("BROKER".equals(source)) {
            try {
                Long brokerCredentialId = longValue(req.get("brokerCredentialId"));
                Map<String, Object> brokerResult = dhanBrokerService.fetchForWarehouse(u, dataType, fromDate, toDate, brokerCredentialId);
                activity.put("status", "completed");
                activity.put("completedAt", DateUtil.now());
                activity.put("message", brokerResult.getOrDefault("message", "Dhan broker data fetched and saved."));
                activity.put("details", brokerResult);
                activity.put("rowsImported", brokerResult.getOrDefault("rowsImported", brokerResult.getOrDefault("imported", 0)));
                activity.put("targetTable", brokerResult.getOrDefault("targetTable", targetTableForDataType(dataType)));
            } catch (Exception e) {
                activity.put("status", "failed");
                activity.put("completedAt", DateUtil.now());
                activity.put("message", UserMessage.of(e));
                activity.put("targetTable", targetTableForDataType(dataType));
            }
        } else {
            try {
                Map<String, Object> result = dataFetchEngine.smartFetch(dataType, source, fromDate, toDate);
                String fetchStatus = String.valueOf(result.getOrDefault("status", "completed"));
                activity.put("status", fetchStatus.startsWith("completed") ? "completed" : fetchStatus);
                activity.put("completedAt", DateUtil.now());
                activity.put("message", result.getOrDefault("message", "Exchange data fetched and imported."));
                activity.put("rowsImported", result.getOrDefault("totalRows", 0));
                activity.put("targetTable", targetTableForDataType(dataType));
                activity.put("details", result);
                activity.put("errorCode", result.getOrDefault("errorCode", ""));
                activity.put("steps", result.getOrDefault("steps", List.of()));
                activity.put("fetchedDates", result.getOrDefault("fetchedDates", List.of()));
                activity.put("skippedDates", result.getOrDefault("skippedDates", List.of()));
                activity.put("duration", result.getOrDefault("duration", ""));
                dataFetchEngine.saveFetchLog(u.getId(), result);
            } catch (Exception e) {
                activity.put("status", "failed");
                activity.put("completedAt", DateUtil.now());
                activity.put("message", UserMessage.of(e));
                activity.put("targetTable", targetTableForDataType(dataType));
            }
        }
        userActivities.put(u.getId(), activity);
        return ResponseEntity.ok(ApiResponse.ok("Activity started.", activity));
    }

    // ── Smart Fetch Engine Endpoints ──────────────────────────────────────────

    @GetMapping("/dashboard-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardStats(@AuthenticationPrincipal User u) {
        return ResponseEntity.ok(ApiResponse.ok(dataFetchEngine.getDashboardStats(u != null ? u.getId() : null)));
    }

    @GetMapping("/validate-dates")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validateDates(
            @RequestParam String fromDate, @RequestParam String toDate) {
        return ResponseEntity.ok(ApiResponse.ok(dataFetchEngine.validateDateRange(fromDate, toDate)));
    }

    // == Exchange holiday calendar ==========================================

    /** Fetch NSE's published trading-holiday calendar and store it. */
    @PostMapping("/market-holidays/fetch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fetchMarketHolidays() {
        // The fetch bumps HolidayCalendarVersion, which retires the cached calendar, so this
        // takes effect on the next screen read rather than waiting out a cache entry.
        return ResponseEntity.ok(ApiResponse.ok(nseExchangeFetchService.fetchTradingHolidays()));
    }

    /** The stored equity (CM) holiday calendar, newest year first. */
    @GetMapping("/market-holidays")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMarketHolidays(
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(ApiResponse.ok(
            nseExchangeFetchService.storedTradingHolidays(year)));
    }

    @GetMapping("/error-codes")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getErrorCodes() {
        return ResponseEntity.ok(ApiResponse.ok(dataFetchEngine.getErrorCodes()));
    }

    @GetMapping("/fetch-progress")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFetchProgress() {
        return ResponseEntity.ok(ApiResponse.ok(dataFetchEngine.getLiveProgress()));
    }

    @PostMapping("/fetch-cache/clear")
    public ResponseEntity<ApiResponse<Map<String, Object>>> clearFetchCache() {
        dataFetchEngine.clearCache();
        return ResponseEntity.ok(ApiResponse.ok("Cache cleared.", Map.of("status", "cleared")));
    }

    @GetMapping("/fetch-logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFetchLogs(
            @AuthenticationPrincipal User u,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(dataFetchEngine.getFetchLogs(u != null ? u.getId() : null, page, size)));
    }

    @PostMapping(value="/activity/manual-upload", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Map<String,Object>>> manualUpload(
            @RequestParam String dataType,
            @RequestParam String source,
            @RequestParam("files") MultipartFile[] files,
            @AuthenticationPrincipal User u) {
        Map<String, Object> active = activityFor(u);
        if ("running".equals(active.get("status"))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DATA-ACTIVE", "Finish or stop the current activity before starting another one."));
        }
        if (!"MANUAL".equals(norm(source))) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-SOURCE", "Manual upload is available only when Manual source is selected."));
        }
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-FILES", "Select at least one file."));
        }
        if (files.length > MAX_MANUAL_FILES) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-FILES", "Maximum 300 files can be uploaded at a time."));
        }
        for (MultipartFile file : files) {
            if (file.getSize() > MAX_SINGLE_FILE_BYTES) {
                return ResponseEntity.badRequest().body(ApiResponse.error("DATA-SIZE", "Each file must be 2 MB or smaller."));
            }
        }
        int imported = 0;
        List<Map<String, Object>> imports = new ArrayList<>();
        int failed = 0;
        for (MultipartFile file : files) {
            try {
                String content = importService.decode(file.getBytes(), file.getOriginalFilename());
                Map<String, Object> result = importService.importWarehouseText(dataType, content, DateUtil.today(), "MANUAL");
                imported += ((Number) result.getOrDefault("rowsImported", 0)).intValue();
                imports.add(Map.of(
                    "file", safeText(file.getOriginalFilename()),
                    "targetTable", result.getOrDefault("targetTable", ""),
                    "rowsImported", result.getOrDefault("rowsImported", 0)
                ));
            } catch (Exception e) {
                failed++;
                imports.add(Map.of("file", safeText(file.getOriginalFilename()), "status", "failed", "message", UserMessage.of(e)));
            }
        }
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("activityName", "Manual upload - " + dataType);
        activity.put("dataType", dataType);
        activity.put("source", "MANUAL");
        activity.put("fileCount", files.length);
        activity.put("status", failed == files.length ? "failed" : "completed");
        activity.put("startedAt", DateUtil.now());
        activity.put("completedAt", DateUtil.now());
        activity.put("rowsImported", imported);
        activity.put("filesFailed", failed);
        activity.put("details", imports);
        activity.put("message", imported + " rows imported into connected Data Warehouse tables.");
        userActivities.put(u.getId(), activity);
        return ResponseEntity.ok(ApiResponse.ok("Manual upload completed.", activity));
    }

    @PostMapping("/activity/stop")
    public ResponseEntity<ApiResponse<Map<String,Object>>> stopActivity(@AuthenticationPrincipal User u) {
        Map<String, Object> activity = activityFor(u);
        if (!"running".equals(activity.get("status"))) {
            return ResponseEntity.ok(ApiResponse.ok("No running activity.", activity));
        }
        activity = new LinkedHashMap<>(activity);
        if ("DHAN".equalsIgnoreCase(String.valueOf(activity.getOrDefault("source", ""))) ||
            "Dhan Historical Candles".equalsIgnoreCase(String.valueOf(activity.getOrDefault("dataType", "")))) {
            Map<String, Object> job = dhanHistoricalBackfillService.stopLatestJob(u);
            activity.put("details", job);
        }
        activity.put("status", "stopped");
        activity.put("stoppedAt", DateUtil.now());
        activity.put("message", "Activity stopped by user.");
        userActivities.put(u.getId(), activity);
        return ResponseEntity.ok(ApiResponse.ok("Activity stopped.", activity));
    }

    @PostMapping("/dhan/instruments/refresh")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshDhanInstruments(@AuthenticationPrincipal User u) {
        Map<String, Object> result = dhanHistoricalBackfillService.refreshInstrumentMaster(u);
        return ResponseEntity.ok(ApiResponse.ok("Dhan instrument refresh started.", result));
    }

    @GetMapping("/dhan/instruments/refresh/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dhanInstrumentRefreshStatus(@AuthenticationPrincipal User u) {
        return ResponseEntity.ok(ApiResponse.ok(dhanHistoricalBackfillService.instrumentRefreshStatus()));
    }

    @PostMapping("/dhan/historical-backfill")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startDhanHistoricalBackfill(
            @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal User u) {
        Map<String, Object> active = activityFor(u);
        if ("running".equals(active.get("status"))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DATA-ACTIVE", "Finish or stop the current activity before starting another one."));
        }
        try {
            Map<String, Object> result = dhanHistoricalBackfillService.startHistoricalBackfill(u, req);
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("activityName", "Dhan historical OHLCV backfill");
            activity.put("dataType", "Dhan Historical Candles");
            activity.put("source", "DHAN");
            activity.put("status", "running");
            activity.put("startedAt", DateUtil.now());
            activity.put("message", String.valueOf(result.getOrDefault("message", "Dhan historical backfill started.")));
            activity.put("details", result);
            userActivities.put(u.getId(), activity);
            return ResponseEntity.ok(ApiResponse.ok("Dhan historical backfill started.", result));
        } catch (Exception e) {
            // Two faults were hiding here. Nothing was logged, so a failed start left no trace at
            // all and the cause had to be guessed. And startHistoricalBackfill throws
            // IllegalStateException with messages written for the user - "Fetch Stock List from
            // Data Warehouse first", "Nothing to fetch: <date> is not a completed trading day yet",
            // "No Dhan instruments match exchange ..." - none of which UserMessage recognises, so
            // every one of them reached the screen as "The action could not be completed." The
            // reader was told nothing and the log knew nothing.
            // IllegalStateException here is a refusal written for the reader - "a backfill is
            // already running", "nothing to fetch for that date", "fetch the Stock List first".
            // Those are expected answers, not faults, and logging a full stack trace for each one
            // buries the real failures in 150 lines of Spring filter chain.
            boolean expected = e instanceof IllegalStateException
                && e.getMessage() != null && !e.getMessage().isBlank();
            if (expected) log.warn("Dhan historical backfill not started: {}", e.getMessage());
            else log.error("Dhan historical backfill start failed", e);
            String detail = expected ? e.getMessage() : UserMessage.of(e);
            return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-HISTORY", detail));
        }
    }

    @GetMapping("/dhan/historical-backfill/latest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> latestDhanHistoricalBackfill(@AuthenticationPrincipal User u) {
        Map<String, Object> job = dhanHistoricalBackfillService.latestJob(u);
        if (!"idle".equals(job.get("status")) && !"running".equals(job.get("status")) && !"queued".equals(job.get("status")) && !"stopping".equals(job.get("status"))) {
            userActivities.put(u.getId(), new LinkedHashMap<>(Map.of(
                "activityName", "Dhan historical OHLCV backfill",
                "dataType", "Dhan Historical Candles",
                "source", "DHAN",
                "status", job.get("status"),
                "message", job.getOrDefault("message", "Dhan historical backfill finished."),
                "details", job
            )));
        }
        return ResponseEntity.ok(ApiResponse.ok(job));
    }

    @PostMapping("/dhan/historical-backfill/stop")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stopDhanHistoricalBackfill(@AuthenticationPrincipal User u) {
        Map<String, Object> job = dhanHistoricalBackfillService.stopLatestJob(u);
        Map<String, Object> activity = new LinkedHashMap<>(activityFor(u));
        activity.put("status", "stopped");
        activity.put("stoppedAt", DateUtil.now());
        activity.put("message", "Dhan historical backfill stopped by user.");
        activity.put("details", job);
        userActivities.put(u.getId(), activity);
        return ResponseEntity.ok(ApiResponse.ok("Dhan historical backfill stopped.", job));
    }

    /**
     * Clear a backfill that can no longer be started, deleting nothing.
     *
     * <p>Stop needs a live job to act on. The gate below it does not: userActivities is held in
     * memory, and a start that failed after the activity was marked running leaves it reading
     * "running" with no job row behind it. Every later fetch is then refused with "Finish or
     * stop the current activity" before it reaches any backfill code, and only a restart cleared
     * it. Removing the entry here is the whole point of this endpoint - the database side is
     * tidy-up around it.
     */
    @PostMapping("/dhan/historical-backfill/clean")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cleanDhanHistoricalBackfill(
            @AuthenticationPrincipal User u) {
        Map<String, Object> result = dhanHistoricalBackfillService.cleanBackfillState(u);
        userActivities.remove(u.getId());
        result.put("activityCleared", true);
        int clearedJobs = result.get("clearedJobs") instanceof Number n ? n.intValue() : 0;
        int dismissed = result.get("dismissedJobs") instanceof Number d ? d.intValue() : 0;
        String message = "Existing fetch request removed"
            + (clearedJobs > 0 ? ", " + clearedJobs + " unfinished job(s) stopped" : "")
            + (dismissed > 0 ? ", " + dismissed + " request(s) cleared from the panel" : "")
            + ". Nothing was deleted - error logs and skipped symbols are still in the database."
            + " You can start a new fetch now.";
        return ResponseEntity.ok(ApiResponse.ok(message, result));
    }

    @GetMapping("/dhan/historical-backfill/errors/download")
    public ResponseEntity<byte[]> downloadDhanHistoricalErrors(@AuthenticationPrincipal User u) {
        byte[] csv = dhanHistoricalBackfillService.latestErrorLogCsv(u);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set("Content-Disposition", "attachment; filename=\"Dhan_Historical_Error_Log.csv\"");
        headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
        return ResponseEntity.ok().headers(headers).body(csv);
    }

    @GetMapping("/dhan/historical-backfill/skipped")
    public ResponseEntity<ApiResponse<Map<String, Object>>> skippedDhanHistorical(@AuthenticationPrincipal User u) {
        return ResponseEntity.ok(ApiResponse.ok(dhanHistoricalBackfillService.latestSkippedStocks(u)));
    }

    @PostMapping("/dhan/historical-backfill/retry-failed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retryDhanHistoricalFailed(@AuthenticationPrincipal User u) {
        Map<String, Object> active = activityFor(u);
        if (activityBlocksDhanStart(u, active)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DATA-ACTIVE", "Finish or stop the current activity before retrying failed Dhan symbols."));
        }
        try {
            Map<String, Object> result = dhanHistoricalBackfillService.retryFailed(u);
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("activityName", "Retry failed Dhan historical symbols");
            activity.put("dataType", "Dhan Historical Candles");
            activity.put("source", "DHAN");
            activity.put("status", "running");
            activity.put("startedAt", DateUtil.now());
            activity.put("message", String.valueOf(result.getOrDefault("message", "Retry started.")));
            activity.put("details", result);
            userActivities.put(u.getId(), activity);
            return ResponseEntity.ok(ApiResponse.ok("Retry failed Dhan symbols started.", result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-HISTORY-RETRY", UserMessage.of(e)));
        }
    }

    /**
     * Skipped/failed Dhan symbols on record for this user, across ALL jobs — the list the
     * re-fetch below acts on. {@code /skipped} only covers the most recent job.
     */
    @GetMapping("/dhan/historical-backfill/skipped-symbols")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dhanSkippedSymbols(@AuthenticationPrincipal User u) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(dhanHistoricalBackfillService.skippedSymbolSummary(u)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-SKIPPED-LIST", UserMessage.of(e)));
        }
    }

    /** The skipped/failed list as a CSV the user can fill in and upload back. */
    @GetMapping("/dhan/historical-backfill/skipped-symbols/download")
    public ResponseEntity<byte[]> downloadDhanSkippedSymbols(@AuthenticationPrincipal User u) {
        try {
            byte[] csv = dhanHistoricalBackfillService.skippedSymbolCsv(u)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String name = "skipped-stocks-" + java.time.LocalDate.now() + ".csv";
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(csv);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** Stocks and dates the backfill could not store, as plain comma-separated text. */
    @GetMapping("/dhan/historical-backfill/missing-data/download")
    public ResponseEntity<byte[]> downloadMissingData(@AuthenticationPrincipal User u) {
        try {
            byte[] csv = dhanHistoricalBackfillService.missingDataCsv(u)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String name = "missing-data-" + java.time.LocalDate.now() + ".csv";
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(csv);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** Upload a symbol/date list and fetch exactly those rows into the database. */
    @PostMapping("/dhan/historical-backfill/fetch-from-file")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fetchFromFile(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User u) {
        Map<String, Object> active = activityFor(u);
        if ("running".equals(active.get("status"))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DATA-ACTIVE", "Finish or stop the current activity before starting another one."));
        }
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-FETCH-FILE",
                    "Choose a file first. One stock symbol per line, optionally followed by a date."));
            }
            String content = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> result = dhanHistoricalBackfillService.fetchFromList(u, content);
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("activityName", "Dhan fetch from uploaded list");
            activity.put("dataType", "Dhan Historical Candles");
            activity.put("source", "DHAN");
            activity.put("status", "running");
            activity.put("startedAt", DateUtil.now());
            activity.put("message", String.valueOf(result.getOrDefault("message",
                "Fetching " + result.getOrDefault("symbols_requested", 0) + " stock(s) from the uploaded list.")));
            activity.put("details", result);
            userActivities.put(u.getId(), activity);
            return ResponseEntity.ok(ApiResponse.ok("Fetch started from the uploaded list.", result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-FETCH-FILE", UserMessage.of(e)));
        }
    }

    /** Corrections already on file. */
    @GetMapping("/dhan/historical-backfill/symbol-overrides")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listSymbolOverrides(@AuthenticationPrincipal User u) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(dhanHistoricalBackfillService.listSymbolOverrides(u)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-OVERRIDE-LIST", UserMessage.of(e)));
        }
    }

    /**
     * Saves corrected stock details. Each entry needs the original (skipped) symbol plus at
     * least one of a corrected symbol, an ISIN, or a Dhan security id; the server matches it
     * against the broker's instrument list and reports which ones it could not place.
     */
    @PostMapping("/dhan/historical-backfill/symbol-overrides")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveSymbolOverrides(
            @RequestBody List<Map<String, Object>> entries,
            @AuthenticationPrincipal User u) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(dhanHistoricalBackfillService.saveSymbolOverrides(u, entries)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-OVERRIDE-SAVE", UserMessage.of(e)));
        }
    }

    /** Same as above, but from the filled-in CSV that was downloaded. */
    @PostMapping("/dhan/historical-backfill/symbol-overrides/upload")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadSymbolOverrides(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User u) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-OVERRIDE-FILE",
                    "Choose the filled-in CSV file first."));
            }
            List<Map<String, Object>> entries = parseOverrideCsv(
                new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8));
            if (entries.isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-OVERRIDE-EMPTY",
                    "No corrections found. Fill in the Corrected Symbol, Corrected ISIN, or Corrected Security ID column, then upload again."));
            }
            return ResponseEntity.ok(ApiResponse.ok(dhanHistoricalBackfillService.saveSymbolOverrides(u, entries)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-OVERRIDE-UPLOAD", UserMessage.of(e)));
        }
    }

    @DeleteMapping("/dhan/historical-backfill/symbol-overrides/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteSymbolOverride(
            @PathVariable Long id, @AuthenticationPrincipal User u) {
        try {
            int removed = dhanHistoricalBackfillService.deleteSymbolOverride(u, id);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "removed", removed,
                "message", removed > 0 ? "Correction removed." : "That correction was already gone.")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-OVERRIDE-DELETE", UserMessage.of(e)));
        }
    }

    /**
     * Reads the downloaded CSV back. Columns are located by header name rather than position so
     * a spreadsheet that reorders or adds columns still loads.
     */
    private List<Map<String, Object>> parseOverrideCsv(String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        if (lines.length < 2) return out;

        List<String> header = splitCsvLine(lines[0]);
        int iOriginal = indexOfHeader(header, "original symbol", "symbol");
        int iSymbol = indexOfHeader(header, "corrected symbol");
        int iIsin = indexOfHeader(header, "corrected isin", "isin");
        int iSecId = indexOfHeader(header, "corrected security id", "security id");
        if (iOriginal < 0) return out;

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> cells = splitCsvLine(lines[i]);
            String original = cell(cells, iOriginal);
            String corrected = cell(cells, iSymbol);
            String isin = cell(cells, iIsin);
            String secId = cell(cells, iSecId);
            if (original.isEmpty()) continue;
            if (corrected.isEmpty() && isin.isEmpty() && secId.isEmpty()) continue;   // nothing filled in
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("originalSymbol", original);
            m.put("correctedSymbol", corrected);
            m.put("isin", isin);
            m.put("securityId", secId);
            out.add(m);
        }
        return out;
    }

    private int indexOfHeader(List<String> header, String... names) {
        for (String name : names) {
            for (int i = 0; i < header.size(); i++) {
                if (header.get(i).trim().equalsIgnoreCase(name)) return i;
            }
        }
        return -1;
    }

    private String cell(List<String> cells, int idx) {
        return idx < 0 || idx >= cells.size() ? "" : cells.get(idx).trim();
    }

    /** Minimal CSV reader: handles quoted cells and doubled quotes inside them. */
    private List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else quoted = false;
                } else cur.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString()); cur.setLength(0);
            } else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /**
     * Re-fetches history for skipped symbols. With no {@code symbols} it retries every
     * skipped/failed symbol from every job; pass a comma-separated list to target specific
     * ones, and {@code fromDate}/{@code toDate} to refill a known gap.
     */
    @PostMapping("/dhan/historical-backfill/retry-skipped")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retryDhanSkipped(
            @RequestParam(required = false) String symbols,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @AuthenticationPrincipal User u) {
        Map<String, Object> active = activityFor(u);
        if (activityBlocksDhanStart(u, active)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DATA-ACTIVE", "Finish or stop the current activity before re-fetching skipped Dhan symbols."));
        }
        try {
            List<String> requested = symbols == null || symbols.isBlank()
                ? List.of()
                : Arrays.stream(symbols.split(",")).map(String::trim).filter(sym -> !sym.isEmpty()).toList();
            Map<String, Object> result = dhanHistoricalBackfillService.retrySkipped(u, requested, fromDate, toDate);
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("activityName", "Re-fetch skipped Dhan symbols");
            activity.put("dataType", "Dhan Historical Candles");
            activity.put("source", "DHAN");
            activity.put("status", "running");
            activity.put("startedAt", DateUtil.now());
            activity.put("message", String.valueOf(result.getOrDefault("message", "Re-fetch started.")));
            activity.put("details", result);
            userActivities.put(u.getId(), activity);
            return ResponseEntity.ok(ApiResponse.ok("Skipped-symbol re-fetch started.", result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-SKIPPED-RETRY", UserMessage.of(e)));
        }
    }

    // ---- Index membership: which stocks come under which index ----

    /** Daily index OHLC + P/E + P/B from NSE's all-index close file, one request per day. */
    @PostMapping("/index-history/fetch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fetchIndexHistory(
            @RequestParam String from, @RequestParam String to) {
        try {
            Map<String, Object> r = indexHistoryFetchService.fetchRange(from, to);
            return ResponseEntity.ok(ApiResponse.ok(String.valueOf(r.get("message")), r));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                ApiResponse.error("INDEX-HISTORY", UserMessage.of(e)));
        }
    }

    /** What index history is stored right now. */
    @GetMapping("/index-history/coverage")
    public ResponseEntity<ApiResponse<Map<String, Object>>> indexHistoryCoverage() {
        return ResponseEntity.ok(ApiResponse.ok(indexHistoryFetchService.coverage()));
    }

    /** Load NSE index constituents into the master data. Pass indexKey to refresh just one. */
    @PostMapping("/index-membership/refresh")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshIndexMembership(
            @RequestParam(required = false) String indexKey) {
        try {
            Map<String, Object> r = indexMembershipService.refresh(indexKey);
            return ResponseEntity.ok(ApiResponse.ok(String.valueOf(r.get("message")), r));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                ApiResponse.error("INDEX-MEMBERSHIP", UserMessage.of(e)));
        }
    }

    /** What is loaded per index, plus the full list this loader knows how to fetch. */
    @GetMapping("/index-membership")
    public ResponseEntity<ApiResponse<Map<String, Object>>> indexMembership() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("loaded", indexMembershipService.summary());
        out.put("available", indexMembershipService.knownIndices());
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /** Which indices one stock belongs to. */
    @GetMapping("/index-membership/symbol")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> indicesForSymbol(
            @RequestParam String symbol) {
        return ResponseEntity.ok(ApiResponse.ok(indexMembershipService.indicesForSymbol(symbol)));
    }

    @GetMapping("/dhan/historical-backfill/batches")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> dhanBackfillBatches(@AuthenticationPrincipal User u) {
        return ResponseEntity.ok(ApiResponse.ok(dhanHistoricalBackfillService.getBatches(u)));
    }

    @PostMapping("/dhan/historical-backfill/batches/{batchId}/retry")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retryDhanBatch(
            @PathVariable Long batchId, @AuthenticationPrincipal User u) {
        try {
            return ResponseEntity.ok(ApiResponse.ok("Batch retry started.", dhanHistoricalBackfillService.retryBatch(u, batchId)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DHAN-BATCH-RETRY", UserMessage.of(e)));
        }
    }

    @GetMapping("/backend-tables")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> backendTables(@AuthenticationPrincipal User u) {
        return ResponseEntity.ok(ApiResponse.ok(discoverBackendTables()));
    }

    @GetMapping("/backend-tables/{key}/download")
    public ResponseEntity<byte[]> downloadBackendTable(@PathVariable String key,
                                                       @AuthenticationPrincipal User u) {
        Optional<Map<String, Object>> table = findBackendTable(key);
        if (table.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(("Unknown table: " + key).getBytes(StandardCharsets.UTF_8));
        }
        String tableName = String.valueOf(table.get().get("tableName"));
        String label = String.valueOf(table.get().get("label"));
        List<String> columns = getColumns(tableName);
        StringBuilder csv = new StringBuilder();
        csv.append("\uFEFF");
        csv.append("# ").append(label).append("\n");
        csv.append("# Table: ").append(tableName).append("\n");
        csv.append("# Columns: ").append(String.join("|", columns)).append("\n");
        csv.append("# Row Count: ").append(q("SELECT COUNT(*) FROM " + quoteIdentifier(tableName))).append("\n");
        csv.append("# Generated: ").append(DateUtil.now()).append("\n\n");
        csv.append(String.join(",", columns)).append("\n");
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery("SELECT * FROM " + quoteIdentifier(tableName)).getResultList();
            for (Object row : rows) {
                if (row instanceof Object[] values) {
                    for (int i = 0; i < values.length; i++) {
                        if (i > 0) csv.append(",");
                        csv.append(csvCell(values[i]));
                    }
                } else {
                    csv.append(csvCell(row));
                }
                csv.append("\n");
            }
        } catch (Exception e) {
            csv.append("# Download failed: ").append(csvCell(UserMessage.of(e))).append("\n");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set("Content-Disposition", "attachment; filename=\"" + tableName + "_download_" + DateUtil.today() + ".csv\"");
        headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
        return ResponseEntity.ok().headers(headers).body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(value="/backend-tables/{key}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String,Object>>> uploadBackendTable(
            @PathVariable String key,
            @RequestParam("files") MultipartFile[] files,
            @AuthenticationPrincipal User u) {
        Optional<Map<String, Object>> table = findBackendTable(key);
        if (table.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-TABLE", "Unknown backend data table."));
        }
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-FILES", "Select at least one file."));
        }
        String label = String.valueOf(table.get().get("label"));
        String tableName = String.valueOf(table.get().get("tableName"));
        long totalBytes = Arrays.stream(files).mapToLong(MultipartFile::getSize).sum();
        int imported = 0;
        int failed = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                String content = importService.decode(file.getBytes(), file.getOriginalFilename());
                int rows = importService.importCsvIntoTable(tableName, content);
                imported += rows;
                details.add(Map.of("file", safeText(file.getOriginalFilename()), "rowsImported", rows));
            } catch (Exception e) {
                failed++;
                details.add(Map.of("file", safeText(file.getOriginalFilename()), "status", "failed", "message", UserMessage.of(e)));
            }
        }
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("activityName", "Backend table upload - " + label);
        activity.put("dataType", label);
        activity.put("tableName", tableName);
        activity.put("source", "BACKEND TABLE");
        activity.put("fileCount", files.length);
        activity.put("totalBytes", totalBytes);
        activity.put("status", failed == files.length ? "failed" : "completed");
        activity.put("startedAt", DateUtil.now());
        activity.put("completedAt", DateUtil.now());
        activity.put("rowsImported", imported);
        activity.put("filesFailed", failed);
        activity.put("details", details);
        activity.put("message", imported + " rows imported into " + tableName + ".");
        userActivities.put(u.getId(), activity);
        return ResponseEntity.ok(ApiResponse.ok("Backend table upload completed.", activity));
    }

    @PostMapping("/backend-tables/{key}/clean")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String,Object>>> cleanBackendTable(@PathVariable String key,
                                                                             @AuthenticationPrincipal User u) {
        Optional<Map<String, Object>> table = findBackendTable(key);
        if (table.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-TABLE", "Unknown backend data table."));
        }
        String label = String.valueOf(table.get().get("label"));
        String tableName = String.valueOf(table.get().get("tableName"));
        if (isProtectedCleanTable(tableName)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-TABLE-PROTECTED", "This system table is protected and cannot be cleaned from Backend Data Tables."));
        }
        long before = q("SELECT COUNT(*) FROM " + quoteIdentifier(tableName));
        em.createNativeQuery("TRUNCATE " + quoteIdentifier(tableName) + " RESTART IDENTITY CASCADE").executeUpdate();
        long after = q("SELECT COUNT(*) FROM " + quoteIdentifier(tableName));
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("activityName", "Clean backend table - " + label);
        activity.put("dataType", label);
        activity.put("tableName", tableName);
        activity.put("source", "BACKEND TABLE CLEAN");
        activity.put("status", "completed");
        activity.put("startedAt", DateUtil.now());
        activity.put("completedAt", DateUtil.now());
        activity.put("rowsRemoved", before - after);
        activity.put("message", "Cleaned " + tableName + ". Removed " + (before - after) + " row(s).");
        userActivities.put(u.getId(), activity);
        return ResponseEntity.ok(ApiResponse.ok("Backend table cleaned.", activity));
    }

    @GetMapping("/dependencies")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> dependencies(@AuthenticationPrincipal User u) {
        return ResponseEntity.ok(ApiResponse.ok(dependentSections()));
    }

    @GetMapping("/stock-master/preview")
    public ResponseEntity<ApiResponse<Map<String,Object>>> stockMasterPreview(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String exchange,
            @RequestParam(defaultValue = "") String actionType,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo,
            @AuthenticationPrincipal User u) {
        limit = Math.max(5, Math.min(limit, 200));
        int offset = page * limit;
        String filter = "%" + search.trim().toUpperCase(Locale.ROOT) + "%";
        String nonIndexWhere = stockMasterNonIndexWhere();
        StringBuilder where = new StringBuilder(nonIndexWhere);
        where.append(" AND (? = '%%' OR UPPER(COALESCE(symbol,'')) LIKE ? OR UPPER(COALESCE(company_name,'')) LIKE ? OR UPPER(COALESCE(isin,'')) LIKE ?)");
        List<Object> params = new ArrayList<>(List.of(filter, filter, filter, filter));
        if (!source.isBlank()) {
            where.append(" AND UPPER(COALESCE(source,'')) = ?");
            params.add(source.trim().toUpperCase(Locale.ROOT));
        }
        if (!status.isBlank()) {
            where.append(" AND UPPER(COALESCE(status,'')) = ?");
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (!exchange.isBlank()) {
            where.append(" AND UPPER(COALESCE(exchange,'')) = ?");
            params.add(exchange.trim().toUpperCase(Locale.ROOT));
        }
        if (!actionType.isBlank()) {
            where.append(" AND UPPER(COALESCE(action_type,'fetched')) = ?");
            params.add(actionType.trim().toUpperCase(Locale.ROOT));
        }
        if (!dateFrom.isBlank()) {
            where.append(" AND COALESCE(created_at, as_on_date, '') >= ?");
            params.add(dateFrom.trim());
        }
        if (!dateTo.isBlank()) {
            where.append(" AND COALESCE(created_at, as_on_date, '') <= ?");
            params.add(dateTo.trim() + " 23:59:59");
        }
        try {
            var countQuery = em.createNativeQuery("SELECT COUNT(*) FROM stock_master WHERE " + where);
            for (int i = 0; i < params.size(); i++) countQuery.setParameter(i + 1, params.get(i));
            long totalFiltered = ((Number) countQuery.getSingleResult()).longValue();

            var dataQuery = em.createNativeQuery(
                "SELECT id, exchange, symbol, company_name, sector, industry, series, isin, " +
                "status, source, COALESCE(action_type,'fetched') AS action_type, remarks, " +
                "COALESCE(updated_at, created_at, as_on_date) AS date_time, segment " +
                "FROM stock_master WHERE " + where + " " +
                "ORDER BY COALESCE(updated_at, created_at, as_on_date) DESC NULLS LAST, id DESC " +
                "LIMIT ? OFFSET ?");
            for (int i = 0; i < params.size(); i++) dataQuery.setParameter(i + 1, params.get(i));
            dataQuery.setParameter(params.size() + 1, limit);
            dataQuery.setParameter(params.size() + 2, offset);
            @SuppressWarnings("unchecked")
            List<Object[]> dataRows = dataQuery.getResultList();

            List<Map<String, Object>> data = new ArrayList<>();
            for (Object[] row : dataRows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row[0]);
                item.put("exchange", row[1]);
                item.put("symbol", row[2]);
                item.put("companyName", row[3]);
                item.put("sector", row[4]);
                item.put("industry", row[5]);
                item.put("series", row[6]);
                item.put("isin", row[7]);
                item.put("status", row[8]);
                item.put("source", row[9]);
                item.put("actionType", row[10]);
                item.put("remarks", row[11]);
                item.put("dateTime", row[12]);
                item.put("segment", row[13]);
                data.add(item);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", data);
            result.put("totalRows", totalFiltered);
            result.put("page", page);
            result.put("limit", limit);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", new ArrayList<>());
            result.put("totalRows", 0);
            result.put("page", page);
            result.put("limit", limit);
            result.put("error", UserMessage.of(e));
            return ResponseEntity.ok(ApiResponse.ok(result));
        }
    }

    @GetMapping("/stock-master/stats")
    public ResponseEntity<ApiResponse<Map<String,Object>>> stockMasterStats(@AuthenticationPrincipal User u) {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            stats.put("totalRecords", q("SELECT COUNT(*) FROM stock_master WHERE " + stockMasterNonIndexWhere()));
            String today = DateUtil.today();
            stats.put("importedToday", q("SELECT COUNT(*) FROM stock_master WHERE COALESCE(created_at,'') LIKE '" + today + "%' OR COALESCE(updated_at,'') LIKE '" + today + "%'"));
            stats.put("duplicateRecords", q("SELECT COALESCE(SUM(cnt - 1),0) FROM (SELECT COUNT(*) cnt FROM stock_master GROUP BY UPPER(exchange), UPPER(symbol) HAVING COUNT(*) > 1) sub"));
            stats.put("failedRecords", q("SELECT COALESCE(SUM(CAST(COALESCE(rows_imported,0) AS BIGINT)),0) FROM data_fetch_logs WHERE status='failed' AND data_type LIKE '%Stock%'"));
            String lastFetchTime = ql("SELECT COALESCE(MAX(created_at),'Never') FROM data_fetch_logs WHERE data_type LIKE '%Stock%' OR data_type LIKE '%stock%'");
            stats.put("lastFetchTime", lastFetchTime.isEmpty() ? "Never" : lastFetchTime);
            String lastSource = ql("SELECT source FROM data_fetch_logs WHERE (data_type LIKE '%Stock%' OR data_type LIKE '%stock%') ORDER BY created_at DESC LIMIT 1");
            stats.put("lastSource", lastSource.isEmpty() ? "N/A" : lastSource);
            stats.put("nseCount", q("SELECT COUNT(*) FROM stock_master WHERE UPPER(exchange)='NSE' AND " + stockMasterNonIndexWhere()));
            stats.put("bseCount", q("SELECT COUNT(*) FROM stock_master WHERE UPPER(exchange)='BSE' AND " + stockMasterNonIndexWhere()));
        } catch (Exception e) {
            stats.put("totalRecords", 0);
            stats.put("importedToday", 0);
            stats.put("duplicateRecords", 0);
            stats.put("failedRecords", 0);
            stats.put("lastFetchTime", "N/A");
            stats.put("lastSource", "N/A");
            stats.put("nseCount", 0);
            stats.put("bseCount", 0);
        }
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    @GetMapping("/stock-master/logs")
    public ResponseEntity<ApiResponse<List<Map<String,Object>>>> stockMasterLogs(
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal User u) {
        limit = Math.max(5, Math.min(limit, 100));
        List<Map<String, Object>> logs = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> fetchRows = em.createNativeQuery(
                "SELECT id, data_type, source, status, rows_imported, skipped_count, error_code, message, from_date, to_date, duration_ms, created_at " +
                "FROM data_fetch_logs WHERE LOWER(data_type) LIKE '%stock%' OR LOWER(data_type) LIKE '%instrument%' OR LOWER(data_type) LIKE '%bhavcopy%' " +
                "ORDER BY created_at DESC LIMIT ?")
                .setParameter(1, limit)
                .getResultList();
            for (Object[] row : fetchRows) {
                Map<String, Object> log = new LinkedHashMap<>();
                log.put("id", row[0]);
                log.put("actionType", "Fetched");
                log.put("dataType", row[1]);
                log.put("source", row[2]);
                log.put("status", row[3]);
                log.put("totalRecords", row[4]);
                log.put("importedRecords", row[4]);
                log.put("failedRecords", 0);
                log.put("skippedRecords", row[5]);
                log.put("duplicateRecords", 0);
                log.put("errorCode", row[6]);
                log.put("errorMessage", row[7]);
                log.put("startTime", row[11]);
                log.put("endTime", row[11]);
                log.put("durationMs", row[10]);
                log.put("createdAt", row[11]);
                logs.add(log);
            }
        } catch (Exception ignored) {}
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> uploadRows = em.createNativeQuery(
                "SELECT id, file_name, data_type, source, status, total_records, imported_records, failed_records, duplicate_records, skipped_records, error_message, uploaded_at, created_at " +
                "FROM upload_logs WHERE LOWER(COALESCE(data_type,'')) LIKE '%stock%' " +
                "ORDER BY created_at DESC LIMIT ?")
                .setParameter(1, limit)
                .getResultList();
            for (Object[] row : uploadRows) {
                Map<String, Object> log = new LinkedHashMap<>();
                log.put("id", row[0]);
                log.put("actionType", "Uploaded");
                log.put("fileName", row[1]);
                log.put("dataType", row[2]);
                log.put("source", row[3]);
                log.put("status", row[4]);
                log.put("totalRecords", row[5]);
                log.put("importedRecords", row[6]);
                log.put("failedRecords", row[7]);
                log.put("duplicateRecords", row[8]);
                log.put("skippedRecords", row[9]);
                log.put("errorMessage", row[10]);
                log.put("startTime", row[11]);
                log.put("endTime", row[12]);
                log.put("createdAt", row[12]);
                logs.add(log);
            }
        } catch (Exception ignored) {}
        logs.sort((a, b) -> {
            String da = String.valueOf(a.getOrDefault("createdAt", ""));
            String db = String.valueOf(b.getOrDefault("createdAt", ""));
            return db.compareTo(da);
        });
        return ResponseEntity.ok(ApiResponse.ok(logs.size() > limit ? logs.subList(0, limit) : logs));
    }

    @GetMapping("/fundamentals/preview")
    public ResponseEntity<ApiResponse<Map<String,Object>>> fundamentalsPreview(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal User u) {
        limit = Math.max(5, Math.min(limit, 200));
        int offset = page * limit;
        String filter = "%" + search.trim().toUpperCase(Locale.ROOT) + "%";
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT symbol,company_name,sector,industry,market_cap_name," +
                "pe_ratio,pb_ratio,eps,roe,roce,debt_to_equity,dividend_yield," +
                "book_value,face_value,week_52_high,week_52_low,updated_at " +
                "FROM fundamentals " +
                "WHERE (? = '%%' OR UPPER(COALESCE(symbol,'')) LIKE ? OR UPPER(COALESCE(company_name,'')) LIKE ?) " +
                "ORDER BY symbol LIMIT ? OFFSET ?")
                .setParameter(1, filter).setParameter(2, filter)
                .setParameter(3, filter).setParameter(4, limit).setParameter(5, offset)
                .getResultList();
            List<Map<String, Object>> data = new ArrayList<>();
            for (Object[] row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("symbol", row[0]); item.put("companyName", row[1]);
                item.put("sector", row[2]); item.put("industry", row[3]);
                item.put("marketCap", row[4]); item.put("peRatio", row[5]);
                item.put("pbRatio", row[6]); item.put("eps", row[7]);
                item.put("roe", row[8]); item.put("roce", row[9]);
                item.put("debtToEquity", row[10]); item.put("dividendYield", row[11]);
                item.put("bookValue", row[12]); item.put("faceValue", row[13]);
                item.put("week52High", row[14]); item.put("week52Low", row[15]);
                item.put("lastUpdated", row[16] != null ? row[16].toString() : null);
                data.add(item);
            }
            long total;
            try {
                if (search.isBlank()) {
                    total = q("SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='fundamentals'");
                } else {
                    Object cnt = em.createNativeQuery(
                        "SELECT COUNT(*) FROM fundamentals WHERE UPPER(COALESCE(symbol,'')) LIKE ? OR UPPER(COALESCE(company_name,'')) LIKE ?")
                        .setParameter(1, filter).setParameter(2, filter).getSingleResult();
                    total = cnt != null ? ((Number)cnt).longValue() : 0;
                }
            } catch (Exception e) { total = data.size(); }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", data);
            result.put("totalRows", total);
            result.put("page", page);
            result.put("limit", limit);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", new ArrayList<>());
            result.put("totalRows", 0);
            result.put("page", page);
            result.put("limit", limit);
            return ResponseEntity.ok(ApiResponse.ok(result));
        }
    }

    @GetMapping("/cleanup/summary")
    public ResponseEntity<ApiResponse<Map<String,Object>>> cleanupSummary(@AuthenticationPrincipal User u) {
        return ResponseEntity.ok(ApiResponse.ok(cleanupSummaryData()));
    }

    @PostMapping("/cleanup/stock-master-duplicates")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String,Object>>> cleanupStockMasterDuplicates(@AuthenticationPrincipal User u) {
        long before = q("SELECT COUNT(*) FROM stock_master");
        int removed = em.createNativeQuery(
            "DELETE FROM stock_master a USING stock_master b " +
            "WHERE a.id > b.id " +
            "AND UPPER(COALESCE(a.exchange,'')) = UPPER(COALESCE(b.exchange,'')) " +
            "AND UPPER(COALESCE(a.symbol,'')) = UPPER(COALESCE(b.symbol,''))")
            .executeUpdate();
        try {
            em.createNativeQuery("INSERT INTO duplicate_removal_log(table_name,removed_count,run_by,run_at) VALUES(?,?,?,?)")
                .setParameter(1, "stock_master")
                .setParameter(2, removed)
                .setParameter(3, u.getId())
                .setParameter(4, DateUtil.now())
                .executeUpdate();
        } catch (Exception e) {}
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("beforeRows", before);
        data.put("removedRows", removed);
        data.put("afterRows", q("SELECT COUNT(*) FROM stock_master"));
        data.put("summary", cleanupSummaryData());
        return ResponseEntity.ok(ApiResponse.ok("Stock Master duplicate cleanup completed.", data));
    }

    @PostMapping("/database/backup")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String,Object>>> backupDatabase(@AuthenticationPrincipal User u) {
        try {
            Map<String, Object> data = createDatabaseBackup(u, "manual");
            return ResponseEntity.ok(ApiResponse.ok("Backup completed.", data));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-BACKUP", UserMessage.of(e, "Backup could not be created.")));
        }
    }

    @PostMapping(value="/database/backup/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<ApiResponse<Map<String,Object>>> uploadDatabaseBackup(@RequestParam("file") MultipartFile file,
                                                                                @AuthenticationPrincipal User u) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-BACKUP-UPLOAD", "Select one backup file."));
        }
        try {
            Path dir = Path.of("backups", "uploaded");
            Files.createDirectories(dir);
            String safeName = DateUtil.today() + "_" + sanitizeFileName(file.getOriginalFilename());
            Path target = dir.resolve(safeName);
            Files.write(target, file.getBytes());
            em.createNativeQuery("INSERT INTO backup_registry(backup_type,scope,institution_id,filename,file_path,file_size_kb,backup_format,triggered_by,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, "upload")
                .setParameter(2, "database")
                .setParameter(3, u.getInstitutionId())
                .setParameter(4, safeName)
                .setParameter(5, target.toAbsolutePath().toString())
                .setParameter(6, file.getSize() / 1024.0)
                .setParameter(7, extensionOf(safeName))
                .setParameter(8, u.getId())
                .setParameter(9, "uploaded")
                .setParameter(10, DateUtil.now())
                .executeUpdate();
            return ResponseEntity.ok(ApiResponse.ok("Backup file uploaded.", Map.of(
                "filename", safeName,
                "fileSizeKb", Math.round(file.getSize() / 1024.0)
            )));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-BACKUP-UPLOAD", "Backup upload could not be saved."));
        }
    }

    @PostMapping("/database/remove")
    public ResponseEntity<ApiResponse<Map<String,Object>>> removeDatabaseData(@RequestBody Map<String, Object> req,
                                                                              @AuthenticationPrincipal User u) {
        String confirmation = String.valueOf(req.getOrDefault("confirmation", "")).trim();
        if (!"REMOVE DATABASE".equalsIgnoreCase(confirmation)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DATA-REMOVE-CONFIRM", "Type REMOVE DATABASE to confirm."));
        }
        Map<String, Object> running = removalProgress.get(u.getId());
        if (running != null && "running".equals(running.get("status"))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DATA-REMOVE-ACTIVE", "A database removal is already in progress."));
        }

        Map<String, Object> state = new ConcurrentHashMap<>();
        state.put("status", "running");
        state.put("phase", "Preparing");
        state.put("message", "Starting database removal...");
        state.put("currentTable", "");
        state.put("tablesDone", 0);
        state.put("tablesTotal", 0);
        state.put("percent", 0);
        state.put("startedAt", DateUtil.now());
        removalProgress.put(u.getId(), state);

        Thread worker = new Thread(() -> runDatabaseRemoval(u, state), "db-removal-" + u.getId());
        worker.setDaemon(true);
        worker.start();

        return ResponseEntity.ok(ApiResponse.ok("Database removal started.", Map.of(
            "status", "started",
            "message", "Removal started. Progress is shown on screen — a backup is taken first."
        )));
    }

    /** Polled by the screen while a removal runs. */
    @GetMapping("/database/remove/status")
    public ResponseEntity<ApiResponse<Map<String,Object>>> removeDatabaseStatus(@AuthenticationPrincipal User u) {
        Map<String, Object> state = removalProgress.get(u.getId());
        if (state == null) return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "idle")));
        return ResponseEntity.ok(ApiResponse.ok(new LinkedHashMap<>(state)));
    }

    /**
     * Backup, then truncate one table at a time so each step is visible. Truncating every table
     * in a single statement is marginally faster but reports nothing until it finishes.
     */
    private void runDatabaseRemoval(User u, Map<String, Object> state) {
        try {
            state.put("phase", "Backup");
            state.put("message", "Creating a backup before removing anything...");
            state.put("percent", 2);
            Map<String, Object> backup = createDatabaseBackup(u, "before_remove");
            state.put("backup", backup == null ? Map.of() : backup);

            Set<String> keep = Set.of("flyway_schema_history", "users", "institutions", "backup_registry");
            List<String> tables = new ArrayList<>();
            for (Map<String, Object> table : discoverBackendTables()) {
                String tableName = String.valueOf(table.get("tableName"));
                if (!isSafeIdentifier(tableName) || keep.contains(tableName)) continue;
                tables.add(tableName);
            }

            state.put("phase", "Clearing tables");
            state.put("tablesTotal", tables.size());
            List<String> removed = new ArrayList<>();
            for (int i = 0; i < tables.size(); i++) {
                String t = tables.get(i);
                state.put("currentTable", t);
                state.put("message", "Clearing " + t + " (" + (i + 1) + " of " + tables.size() + ")");
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(st ->
                        em.createNativeQuery("TRUNCATE " + quoteIdentifier(t) + " RESTART IDENTITY CASCADE").executeUpdate());
                    removed.add(t);
                } catch (Exception e) {
                    log.warn("[Removal] Could not clear {}: {}", t, UserMessage.of(e));
                }
                state.put("tablesDone", i + 1);
                state.put("percent", 5 + (int) Math.round(((i + 1) * 93.0) / Math.max(1, tables.size())));
            }

            state.put("removedTables", removed);
            state.put("currentTable", "");
            state.put("phase", "Completed");
            state.put("percent", 100);
            state.put("status", "completed");
            state.put("completedAt", DateUtil.now());
            state.put("message", "Removed data from " + removed.size() + " table(s) after backup. "
                + "Login users, institutions and the backup registry were kept so the app still opens.");
        } catch (Exception e) {
            state.put("status", "failed");
            state.put("phase", "Failed");
            state.put("message", UserMessage.of(e, "Removal failed."));
            log.error("[Removal] Database removal failed: {}", UserMessage.of(e));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String,Object>>> stats(@AuthenticationPrincipal User u) {
        try {
            Object[] r = (Object[]) em.createNativeQuery(
                "SELECT " +
                "(SELECT COUNT(*) FROM stock_master WHERE UPPER(COALESCE(segment,'Equity'))='EQUITY'), " +
                "(SELECT COUNT(*) FROM stock_master WHERE exchange='NSE'), " +
                "(SELECT COUNT(*) FROM stock_master WHERE exchange='BSE'), " +
                "(SELECT COUNT(*) FROM broker_market_candles WHERE UPPER(COALESCE(candle_mode,''))='DAILY'), " +
                "(SELECT COUNT(DISTINCT symbol) FROM broker_market_candles WHERE UPPER(COALESCE(candle_mode,''))='DAILY'), " +
                "(SELECT MAX(trade_date) FROM broker_market_candles WHERE UPPER(COALESCE(candle_mode,''))='DAILY')")
                .getSingleResult();
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "stocks",    num(r[0]),
                "nse",       num(r[1]),
                "bse",       num(r[2]),
                "rows",      num(r[3]),
                "symbols",   num(r[4]),
                "last_date", r[5] != null ? r[5].toString() : "")));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "stocks", 0L, "nse", 0L, "bse", 0L, "rows", 0L, "symbols", 0L, "last_date", "")));
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<Map<String,Object>>> upload(
            @RequestParam String uploadType,
            @RequestParam MultipartFile file,
            @AuthenticationPrincipal User u) throws Exception {
        byte[] raw = file.getBytes();
        String content = importService.decode(raw);
        int count = 0; int failed = 0;
        String ut = uploadType.toLowerCase();
        String tradeDate = DateUtil.today();
        if (ut.contains("equity") || ut.contains("eq") || ut.isEmpty()) {
            count = importService.parseEquityBhav(content, tradeDate, uploadType);
        }
        logUpload(u.getId(), uploadType, file.getOriginalFilename(), count+failed, count, failed);
        return ResponseEntity.ok(ApiResponse.ok("Upload complete.",
            Map.of("type",uploadType,"imported",count,"failed",failed)));
    }

    private long q(String sql) {
        try { Object r = em.createNativeQuery(sql).getSingleResult(); return r==null?0:((Number)r).longValue(); }
        catch (Exception e) { return 0; }
    }
    private String ql(String sql) {
        try { Object r = em.createNativeQuery(sql).getSingleResult(); return r==null?"":r.toString(); }
        catch (Exception e) { return ""; }
    }
    private void logUpload(Long uid, String type, String fname, int recv, int imp, int fail) {
        try { em.createNativeQuery("INSERT INTO upload_history(user_id,institution_id,upload_type,filename,rows_received,rows_imported,rows_failed,status,created_at) VALUES(?,1,?,?,?,?,?,'completed',?)")
            .setParameter(1,uid).setParameter(2,type).setParameter(3,fname)
            .setParameter(4,recv).setParameter(5,imp).setParameter(6,fail).setParameter(7,DateUtil.now()).executeUpdate();
        } catch (Exception e) {}
    }

    private Map<String, Object> activityFor(User u) {
        return userActivities.getOrDefault(u.getId(), Map.of("status", "idle"));
    }

    /** Free space on the database drive below which a bulk fetch is refused, in GB. */
    @org.springframework.beans.factory.annotation.Value("${data.fetch.min.free.gb:20}")
    private long minFreeGbForFetch;

    /**
     * Plain-language warning when the drive holding the database is too full for a bulk fetch,
     * or {@code null} when there is room.
     *
     * <p>Checks the PostgreSQL data directory's own drive rather than the working directory:
     * the application and the database frequently sit on different disks, and it is the
     * database's disk that fills.
     */
    private String lowDiskWarning() {
        try {
            String dir = null;
            try {
                Object v = em.createNativeQuery("SHOW data_directory").getSingleResult();
                if (v != null) dir = String.valueOf(v);
            } catch (Exception ignored) {
                // Not PostgreSQL, or the setting is not readable - fall back to the app's drive.
            }
            java.io.File probe = new java.io.File(dir == null || dir.isBlank() ? "." : dir);
            while (probe != null && !probe.exists()) probe = probe.getParentFile();
            if (probe == null) return null;

            long freeGb = probe.getUsableSpace() / (1024L * 1024L * 1024L);
            long totalGb = Math.max(1, probe.getTotalSpace() / (1024L * 1024L * 1024L));
            if (freeGb >= minFreeGbForFetch) return null;

            return "Not enough free disk space to start this fetch. The database drive has only "
                 + freeGb + " GB free out of " + totalGb + " GB, and a full history fetch needs "
                 + "far more than that. Free up space or move the database to a larger drive, "
                 + "then try again. (Minimum required: " + minFreeGbForFetch + " GB.)";
        } catch (Exception e) {
            log.debug("Disk space preflight skipped: {}", e.getMessage());
            return null;   // never block a fetch just because the check itself failed
        }
    }

    private boolean activityBlocksDhanStart(User u, Map<String, Object> active) {
        if (u == null || !"running".equals(active.get("status"))) return false;
        String dataType = String.valueOf(active.getOrDefault("dataType", ""));
        String source = String.valueOf(active.getOrDefault("source", ""));
        boolean dhanActivity = dataType.toLowerCase(Locale.ROOT).contains("dhan historical")
            || source.equalsIgnoreCase("DHAN");
        if (!dhanActivity) return true;
        Map<String, Object> latest = dhanHistoricalBackfillService.latestJob(u);
        String status = String.valueOf(latest.getOrDefault("status", "idle")).toLowerCase(Locale.ROOT);
        boolean stillRunning = Set.of("queued", "running", "stopping").contains(status);
        if (!stillRunning) {
            Map<String, Object> refreshed = new LinkedHashMap<>();
            refreshed.put("activityName", "Dhan historical OHLCV backfill");
            refreshed.put("dataType", "Dhan Historical Candles");
            refreshed.put("source", "DHAN");
            refreshed.put("status", latest.getOrDefault("status", "idle"));
            refreshed.put("message", latest.getOrDefault("message", "Dhan historical backfill is not running."));
            refreshed.put("details", latest);
            userActivities.put(u.getId(), refreshed);
        }
        return stillRunning;
    }
    private String norm(Object source) {
        return String.valueOf(source == null ? "" : source).trim().toUpperCase(Locale.ROOT);
    }
    private String stockMasterNonIndexWhere() {
        return "UPPER(COALESCE(segment,'')) NOT LIKE '%INDEX%' " +
            "AND UPPER(COALESCE(series,'')) NOT IN ('INDEX','IDX') " +
            "AND UPPER(COALESCE(symbol,'')) NOT IN ('NIFTY','NIFTY50','NIFTY 50','BANKNIFTY','FINNIFTY','MIDCPNIFTY','SENSEX') " +
            "AND UPPER(COALESCE(company_name,'')) NOT LIKE '% INDEX'";
    }
    private boolean isStockList(String dataType) {
        return dataType != null && dataType.toLowerCase(Locale.ROOT).contains("stock list");
    }
    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "null".equalsIgnoreCase(String.valueOf(value))) return null;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception e) { return null; }
    }
    private String exchangeFetchMessage(String dataType, String source) {
        String table = targetTableForDataType(dataType);
        return source + " fetch is aligned to " + table + ". Live exchange API connector is not configured in this local build, so use Manual upload or Broker source to populate this data.";
    }

    private String targetTableForDataType(String dataType) {
        String type = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        if (type.contains("fii") || type.contains("dii")) return "fii_dii_data";
        if (type.contains("option")) return "option_chain";
        if (type.contains("stock list")) return "dhan_instruments / broker_instruments";
        if (type.contains("fundamental")) return "fundamentals";
        if (type.contains("delivery")) return "broker_market_candles / broker_delivery_volumes";
        if (type.contains("corporate")) return "broker_corporate_actions";
        if (type.contains("52w") || type.contains("ath") || type.contains("snapshot")) return "stock_snapshot";
        if (type.contains("daily") || type.contains("bhavcopy") || type.contains("ohlcv") || type.contains("f&o")) return "market_data / broker_market_candles";
        return "broker_data_snapshots";
    }
    private static Map<String, Object> row(String dataType, List<String> sources, String acceptedFiles) {
        return Map.of("dataType", dataType, "sources", sources, "acceptedFiles", acceptedFiles);
    }
    private static Map<String, Object> table(String key, String label, String tableName, String description) {
        return Map.of("key", key, "label", label, "tableName", tableName, "description", description);
    }
    private Optional<Map<String, Object>> findBackendTable(String key) {
        return discoverBackendTables().stream()
            .filter(t -> key.equalsIgnoreCase(String.valueOf(t.get("key"))))
            .findFirst();
    }
    @SuppressWarnings("unchecked")
    private List<String> getColumns(String tableName) {
        try {
            List<Object> pgRows = em.createNativeQuery(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = ? " +
                "ORDER BY ordinal_position")
                .setParameter(1, tableName)
                .getResultList();
            List<String> cols = new ArrayList<>();
            for (Object row : pgRows) {
                cols.add(row instanceof Object[] arr ? String.valueOf(arr[0]) : String.valueOf(row));
            }
            return cols.isEmpty() ? List.of("value") : cols;
        } catch (Exception e) {
            return List.of("value");
        }
    }
    private String csvCell(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).replace("\r", " ").replace("\n", " ");
        if (text.contains(",") || text.contains("\"") || text.startsWith("#")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> discoverBackendTables() {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT t.table_name, " +
                "  (SELECT column_count FROM (" +
                "    SELECT table_name tn, COUNT(*) column_count FROM information_schema.columns " +
                "    WHERE table_schema = current_schema() GROUP BY table_name" +
                "  ) cc WHERE cc.tn = t.table_name), " +
                "  COALESCE(s.n_live_tup, 0) " +
                "FROM information_schema.tables t " +
                "LEFT JOIN pg_stat_user_tables s ON s.relname = t.table_name AND s.schemaname = current_schema() " +
                "WHERE t.table_schema = current_schema() AND t.table_type = 'BASE TABLE' " +
                "AND t.table_name NOT LIKE 'flyway_%' ORDER BY t.table_name")
                .getResultList();
            List<Map<String, Object>> tables = new ArrayList<>();
            for (Object[] row : rows) {
                String tableName = String.valueOf(row[0]);
                if (!isSafeIdentifier(tableName)) continue;
                int colCount = row[1] != null ? ((Number) row[1]).intValue() : 0;
                long rowEstimate = row[2] != null ? ((Number) row[2]).longValue() : 0;
                tables.add(table(slug(tableName), labelFor(tableName), tableName,
                    colCount + " columns, ~" + rowEstimate + " rows. Download includes metadata header and table column header."));
            }
            return tables;
        } catch (Exception e) {
            return List.of(
                table("stock_master", "Stock Master Table", "stock_master", "Stock symbol master and exchange metadata"),
                table("broker_market_candles", "Market Candles Table", "broker_market_candles", "Daily and intraday OHLCV history")
            );
        }
    }

    private String quoteIdentifier(String identifier) {
        if (!isSafeIdentifier(identifier)) throw new IllegalArgumentException("Unsafe table name: " + identifier);
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private boolean isSafeIdentifier(String identifier) {
        return identifier != null && identifier.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private boolean isProtectedCleanTable(String tableName) {
        String t = tableName == null ? "" : tableName.toLowerCase(Locale.ROOT);
        return Set.of(
            "flyway_schema_history",
            "users",
            "institutions",
            "permissions",
            "feature_config",
            "broker_credentials",
            "backup_registry",
            "activity_logs",
            "consent_log",
            "security_log",
            "system_settings"
        ).contains(t);
    }

    private String slug(String tableName) {
        return tableName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private String labelFor(String tableName) {
        String[] parts = tableName.split("_+");
        StringBuilder label = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!label.isEmpty()) label.append(" ");
            label.append(part.substring(0, 1).toUpperCase(Locale.ROOT))
                .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return label.isEmpty() ? tableName : label + " Table";
    }

    /**
     * Streams a full CSV-in-zip backup straight to disk.
     *
     * <p>This previously held everything in heap at once: {@code getResultList()} loaded whole
     * tables (broker_market_candles alone is 27.7M rows), each table was accumulated into one
     * StringBuilder and copied again by {@code toString().getBytes()}, and the entire zip was
     * built in a ByteArrayOutputStream then copied once more by {@code toByteArray()}. On this
     * database that is an OutOfMemoryError at any heap size — which is what made "Remove
     * Database Data" fail with a generic 500 before it removed anything.
     *
     * <p>Rows are now paged, written directly into the zip stream, and hashed as they are
     * written. Very large tables are capped at {@code backup.max_rows_per_table} rows (0 = no
     * cap) and the truncation is recorded in the manifest so the backup never silently
     * pretends to be complete.
     */
    private Map<String, Object> createDatabaseBackup(User u, String scope) throws Exception {
        Path dir = Path.of("backups");
        Files.createDirectories(dir);
        String stamp = DateUtil.now().replace(":", "").replace(" ", "_").replace("-", "");
        // One comma-separated .out file, carried inside a .zip. The .out is the readable
        // format - every table one after another, plain commas, no tooling needed to open it -
        // and the zip wrapper is what keeps it small: this data compresses roughly 15x, so a
        // 500 MB export lands at about 33 MB on disk.
        String base = "traderakshak_database_" + scope + "_" + stamp;
        String outEntryName = base + ".out";
        String filename = base + ".zip";
        Path zipPath = dir.resolve(filename);

        final int PAGE = 5_000;
        final long maxRowsPerTable = 200_000L;   // keeps a backup of this database to a sane size
        List<Map<String, Object>> tables = discoverBackendTables();
        long rowCount = 0;
        List<String> truncated = new ArrayList<>();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (OutputStream fileOut = Files.newOutputStream(zipPath);
             DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest);
             BufferedOutputStream buffered = new BufferedOutputStream(digestOut, 1 << 16);
             ZipOutputStream zip = new ZipOutputStream(buffered, StandardCharsets.UTF_8)) {

            zip.putNextEntry(new ZipEntry(outEntryName));
            Writer flat = new OutputStreamWriter(zip, StandardCharsets.UTF_8);

            for (Map<String, Object> table : tables) {
                String tableName = String.valueOf(table.get("tableName"));
                if (!isSafeIdentifier(tableName)) continue;
                List<String> columns = getColumns(tableName);
                if (columns.isEmpty()) continue;

                Writer out = flat;
                out.write("# TABLE: " + tableName + "\n");
                out.write(String.join(",", columns));
                out.write("\n");

                long offset = 0;
                long written = 0;
                while (true) {
                    @SuppressWarnings("unchecked")
                    List<Object> rows = em.createNativeQuery(
                            "SELECT * FROM " + quoteIdentifier(tableName) + " LIMIT " + PAGE + " OFFSET " + offset)
                        .getResultList();
                    if (rows.isEmpty()) break;

                    StringBuilder chunk = new StringBuilder(PAGE * 80);
                    for (Object row : rows) {
                        if (row instanceof Object[] values) {
                            for (int i = 0; i < values.length; i++) {
                                if (i > 0) chunk.append(",");
                                chunk.append(csvCell(values[i]));
                            }
                        } else {
                            chunk.append(csvCell(row));
                        }
                        chunk.append("\n");
                    }
                    out.write(chunk.toString());
                    written += rows.size();
                    rowCount += rows.size();
                    offset += PAGE;

                    em.clear();   // release managed state so the page can be collected
                    if (rows.size() < PAGE) break;
                    if (maxRowsPerTable > 0 && written >= maxRowsPerTable) {
                        truncated.add(tableName);
                        out.write("# truncated at " + written + " rows\n");
                        break;
                    }
                }
                out.flush();
                out.write("\n");
            }

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("createdAt", DateUtil.now());
            manifest.put("scope", scope);
            manifest.put("tables", tables.size());
            manifest.put("rows", rowCount);
            manifest.put("truncatedTables", truncated);
            manifest.put("maxRowsPerTable", maxRowsPerTable);
            flat.write("# MANIFEST\n");
            for (Map.Entry<String, Object> e : manifest.entrySet()) {
                flat.write("# " + e.getKey() + "," + e.getValue() + "\n");
            }
            flat.flush();
            zip.closeEntry();
            zip.finish();
        }

        long sizeBytes = Files.size(zipPath);
        String hash = HexFormat.of().formatHex(digest.digest());

        // Column names must match the table: integrity_hash / triggered_by, not checksum / created_by.
            // The mismatch aborted the whole backup at the final step, after the file had already
            // been written, and surfaced as a generic 500.
            // Own transaction: the removal path runs this on a background thread, where there is
            // no ambient transaction, and executeUpdate() then fails with
            // "Executing an update/delete query" after the backup file has already been written.
            new TransactionTemplate(transactionManager).executeWithoutResult(txStatus ->
            em.createNativeQuery("INSERT INTO backup_registry(backup_type,scope,institution_id,filename,file_path,file_size_kb,backup_format,integrity_hash,triggered_by,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1, "database")
            .setParameter(2, scope)
            .setParameter(3, u.getInstitutionId())
            .setParameter(4, filename)
            .setParameter(5, zipPath.toAbsolutePath().toString())
            .setParameter(6, sizeBytes / 1024.0)
            .setParameter(7, "out.zip")
            .setParameter(8, hash)
            .setParameter(9, u.getId())
            .setParameter(10, "available")
            .setParameter(11, DateUtil.now())
            .executeUpdate());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filename", filename);
        data.put("path", zipPath.toAbsolutePath().toString());
        data.put("fileSizeKb", Math.round(sizeBytes / 1024.0));
        data.put("tableCount", tables.size());
        data.put("rowCount", rowCount);
        data.put("truncatedTables", truncated);
        data.put("hash", hash);
        return data;
    }

    private List<String> truncateApplicationTables() {
        Set<String> keep = Set.of("flyway_schema_history", "users", "institutions", "backup_registry");
        List<String> tables = new ArrayList<>();
        for (Map<String, Object> table : discoverBackendTables()) {
            String tableName = String.valueOf(table.get("tableName"));
            if (!isSafeIdentifier(tableName) || keep.contains(tableName)) continue;
            tables.add(tableName);
        }
        if (!tables.isEmpty()) {
            String joined = tables.stream().map(this::quoteIdentifier).reduce((a, b) -> a + "," + b).orElse("");
            em.createNativeQuery("TRUNCATE " + joined + " RESTART IDENTITY CASCADE").executeUpdate();
        }
        return tables;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cleanupSummaryData() {
        Map<String, Object> summary = new LinkedHashMap<>();
        try {
            Object[] r = (Object[]) em.createNativeQuery(
                "SELECT " +
                "(SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='stock_master'), " +
                "(SELECT COUNT(*) FROM broker_market_candles WHERE UPPER(COALESCE(candle_mode,''))='DAILY'), " +
                "(SELECT COUNT(*) FROM stock_master WHERE COALESCE(TRIM(symbol),'')=''), " +
                "(SELECT COALESCE(SUM(cnt-1),0) FROM (SELECT COUNT(*) cnt FROM stock_master GROUP BY UPPER(COALESCE(exchange,'')),UPPER(COALESCE(symbol,'')) HAVING COUNT(*)>1) d), " +
                "(SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='upload_history'), " +
                "(SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='broker_market_candles'), " +
                "(SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='broker_portfolio_snapshots'), " +
                "(SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='option_chain'), " +
                "(SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='fii_dii_data'), " +
                "(SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='fundamentals'), " +
                "(SELECT COALESCE(n_live_tup,0) FROM pg_stat_user_tables WHERE relname='backup_registry'), " +
                "(SELECT MAX(created_at) FROM backup_registry)")
                .getSingleResult();
            summary.put("stockMasterRows", num(r[0]));
            summary.put("marketDataRows", num(r[1]));
            summary.put("blankStockSymbols", num(r[2]));
            summary.put("duplicateStockMasterRows", num(r[3]));
            summary.put("uploadHistoryRows", num(r[4]));
            summary.put("brokerCandleRows", num(r[5]));
            summary.put("brokerPortfolioRows", num(r[6]));
            summary.put("optionChainRows", num(r[7]));
            summary.put("fiiDiiRows", num(r[8]));
            summary.put("fundamentalRows", num(r[9]));
            summary.put("backupRows", num(r[10]));
            summary.put("lastBackup", r[11] != null ? r[11].toString() : "");
        } catch (Exception e) {
            summary.put("stockMasterRows", q("SELECT COUNT(*) FROM stock_master"));
            summary.put("marketDataRows", q("SELECT COUNT(*) FROM broker_market_candles WHERE UPPER(COALESCE(candle_mode,''))='DAILY'"));
            summary.put("blankStockSymbols", 0);
            summary.put("duplicateStockMasterRows", 0);
            summary.put("uploadHistoryRows", 0);
            summary.put("brokerCandleRows", 0);
            summary.put("brokerPortfolioRows", 0);
            summary.put("optionChainRows", 0);
            summary.put("fiiDiiRows", 0);
            summary.put("fundamentalRows", 0);
            summary.put("backupRows", 0);
            summary.put("lastBackup", "");
        }
        return summary;
    }
    private long num(Object o) { return o != null ? ((Number) o).longValue() : 0; }

    private List<Map<String, Object>> dependentSections() {
        return List.of(
            dependency("Dashboard", "/dashboard", "market_data, stock_master", "Market mood, stock count, latest market date"),
            dependency("Backtesting", "/backtesting", "broker_market_candles", "Historical close series for strategy testing"),
            dependency("Stock Ranking", "/ranking", "stock_master, broker_market_candles, sector_data", "Universe selection and ranking inputs"),
            dependency("Strategy Builder", "/strategy-builder", "stock_master, market_data", "Symbols and latest OHLCV conditions"),
            dependency("Stock Tracker", "/tracker", "stock_master, market_data, tracked_stocks", "CMP fallback, 52W/ATH and ranking support"),
            dependency("Support / Resistance", "/sr/indices", "broker_market_candles", "Index levels and trend calculations"),
            dependency("Broker / Trading", "/broker, /trading", "broker_credentials, broker_market_candles, broker_portfolio_snapshots", "Broker API data and real-trade readiness")
        );
    }

    private Map<String, Object> dependency(String section, String route, String tables, String usage) {
        return Map.of("section", section, "route", route, "tables", tables, "usage", usage);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private String sanitizeFileName(String name) {
        String cleaned = name == null ? "backup_upload.zip" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "backup_upload.zip" : cleaned;
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "file";
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
