package com.traderakshak.controller;

import com.traderakshak.util.UserMessage;

// =============================================================================
// StockTrackerController — TradeRakshak™ v11.6
// Routes:
//   POST /tracker/upload                 → upload Excel/CSV file
//   GET  /tracker/stocks                 → all tracked stocks (filterable)
//   DELETE /tracker/stocks/{symbol}      → remove single uploaded stock
//   POST /tracker/stocks/delete-selected → soft-delete multiple stocks
//   DELETE /tracker/batch/{batchId}      → soft-delete entire upload batch
//   DELETE /tracker/stocks/all           → soft-delete ALL uploaded stocks (Admin)
//   POST /tracker/stocks/delete-filter   → soft-delete by filter criteria
//   GET  /tracker/upload-history         → upload history with batch details
//   GET  /tracker/upload-stats           → upload management dashboard stats
//   GET  /tracker/deletion-logs          → deletion audit logs
//   GET  /tracker/eligible               → eligible stocks (0% to 5% return)
//   GET  /tracker/distribution           → return distribution curve data
//   POST /tracker/refresh                → manual price refresh
//   POST /tracker/rank                   → run ranking engine now
//   GET  /tracker/ranking/latest         → current + previous Top-10
//   GET  /tracker/ranking/history        → chart data (5/10/15/30/60/90 days)
//   GET  /tracker/dashboard              → summary stats
//   GET  /tracker/positions/summary      → signed-in user's open position symbols
//   GET  /tracker/config                 → factor weight config (admin)
//   PUT  /tracker/config                 → save weights (admin only)
//   GET  /tracker/export/eligible        → CSV download (eligible stocks)
//   GET  /tracker/export/ranking         → CSV download (Top-10)
//   GET  /tracker/sectors                → available sectors (filter)
// =============================================================================

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.service.StockTrackerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/tracker")
@RequiredArgsConstructor
@Tag(name = "Stock Tracker", description = "Stock Upload & Performance Tracker — Sahi-style ranking engine")
public class StockTrackerController {

    private final StockTrackerService trackerService;
    private final com.traderakshak.service.DeadSymbolService deadSymbols;
    /** Owns the upload pipeline (stage-by-stage progress) that the upload screen polls. */
    private final com.traderakshak.service.StockProcessingService processingService;
    /** Append-only upload log: Mode A (latest per symbol) and Mode B (as-of / exact date). */
    private final com.traderakshak.service.StockUploadHistoryService uploadHistory;
    private final com.traderakshak.service.MarketRegimeService marketRegime;
    // Allocation is an annotation on the qualified list, so the controller owns the join between
    // the screen's rows and the capital behind them; neither service needs to know the other.
    private final com.traderakshak.service.CapitalAllocationService allocation;
    private final com.traderakshak.service.DemoAccountService demoAccount;
    private final com.traderakshak.service.BrokerFundsService brokerFunds;

    @PersistenceContext
    private EntityManager em;

    // ── Upload ─────────────────────────────────────────────────────────────────
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload stocks Excel/CSV (Date|Symbol|MarketCapName|Sector)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User user) {

        if (file.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("UPLOAD-400", "File is empty"));
        }
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls") && !name.endsWith(".csv")) {
            return ResponseEntity.ok(ApiResponse.error("UPLOAD-400", "Only .xlsx, .xls, .csv files are supported"));
        }
        try {
            Map<String, Object> result = trackerService.uploadStocks(file, user.getId(), user.getInstitutionId());
            return ResponseEntity.ok(ApiResponse.ok("Upload complete.", result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("UPLOAD-500", UserMessage.of(e, "Upload failed.")));
        }
    }

    // ── All Tracked Stocks ─────────────────────────────────────────────────────
    @GetMapping("/stocks")
    @Operation(summary = "Get all tracked stocks with filters")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStocks(
            @RequestParam(defaultValue = "ALL")  String sector,
            @RequestParam(defaultValue = "ALL")  String mcap,
            @RequestParam(required = false)       Double minReturn,
            @RequestParam(required = false)       Double maxReturn,
            @RequestParam(defaultValue = "return_pct") String sortBy,
            @RequestParam(defaultValue = "desc")  String sortDir,
            @RequestParam(defaultValue = "0")     int page,
            @RequestParam(defaultValue = "500")   int size,
            @RequestParam(defaultValue = "true")  boolean includeSignals,
            @RequestParam(defaultValue = "false") boolean lite,
            @AuthenticationPrincipal User user) {

        // lite=true -> instant DB-only listing (stored values from last refresh,
        // no live enrichment) so page changes never hang the UI.
        List<Map<String, Object>> stocks = lite
            ? trackerService.getUploadedStocksLite(
                user.getId(), user.getInstitutionId(), sector, mcap, minReturn, maxReturn, sortBy, sortDir, page, size)
            : trackerService.getAllTrackedStocks(
                user.getId(), user.getInstitutionId(), sector, mcap, minReturn, maxReturn, sortBy, sortDir, page, size, includeSignals);

        long total = countTrackedStocks(user.getId(), user.getInstitutionId(), sector, mcap, minReturn, maxReturn);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stocks", stocks);
        result.put("total",  total);
        result.put("page",   page);
        result.put("size",   size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @DeleteMapping("/stocks/{symbol}")
    @Operation(summary = "Remove an uploaded stock from the active Stock Upload list")
    public ResponseEntity<ApiResponse<Map<String, Object>>> removeUploadedStock(
            @PathVariable String symbol,
            @AuthenticationPrincipal User user) {
        try {
            Map<String, Object> result = trackerService.removeUploadedStock(
                user.getId(), user.getInstitutionId(), symbol);
            return ResponseEntity.ok(ApiResponse.ok(String.valueOf(result.get("message")), result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRACKER-REMOVE", UserMessage.of(e)));
        }
    }

    // ── Delete Selected Stocks ───────────────────────────────────────────────────
    @PostMapping("/stocks/delete-selected")
    @Operation(summary = "Soft-delete multiple selected uploaded stocks")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteSelected(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        try {
            @SuppressWarnings("unchecked")
            List<String> symbols = (List<String>) body.get("symbols");
            Map<String, Object> result = trackerService.deleteSelectedStocks(
                user.getId(), user.getInstitutionId(), symbols, user.isAdmin());
            return ResponseEntity.ok(ApiResponse.ok(String.valueOf(result.get("message")), result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRACKER-DELETE", UserMessage.of(e)));
        }
    }

    // ── Delete Upload Batch ───────────────────────────────────────────────────────
    @DeleteMapping("/batch/{batchId}")
    @Operation(summary = "Soft-delete all stocks from a specific upload batch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteBatch(
            @PathVariable String batchId,
            @AuthenticationPrincipal User user) {
        try {
            Map<String, Object> result = trackerService.deleteUploadBatch(
                user.getId(), user.getInstitutionId(), batchId, user.isAdmin());
            return ResponseEntity.ok(ApiResponse.ok(String.valueOf(result.get("message")), result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRACKER-BATCH-DELETE", UserMessage.of(e)));
        }
    }

    // ── Delete All Uploaded Stocks ─────────────────────────────────────────────────
    @DeleteMapping("/stocks/all")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','PLATFORM_ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Soft-delete ALL uploaded stocks (Admin only)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteAll(
            @AuthenticationPrincipal User user) {
        try {
            Map<String, Object> result = trackerService.deleteAllUploadedStocks(
                user.getId(), user.getInstitutionId(), user.isAdmin());
            return ResponseEntity.ok(ApiResponse.ok(String.valueOf(result.get("message")), result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRACKER-DELETE-ALL", UserMessage.of(e)));
        }
    }

    // ── Delete by Filter ──────────────────────────────────────────────────────────
    @PostMapping("/stocks/delete-filter")
    @Operation(summary = "Soft-delete stocks matching filter criteria")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteByFilter(
            @RequestBody Map<String, String> filters,
            @AuthenticationPrincipal User user) {
        try {
            Map<String, Object> result = trackerService.deleteByFilter(
                user.getId(), user.getInstitutionId(), filters, user.isAdmin());
            return ResponseEntity.ok(ApiResponse.ok(String.valueOf(result.get("message")), result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRACKER-FILTER-DELETE", UserMessage.of(e)));
        }
    }

    // ── Upload History ────────────────────────────────────────────────────────────
    @GetMapping("/upload-history")
    @Operation(summary = "Get upload history with batch details")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUploadHistory(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getUploadHistory(user.getId(), user.getInstitutionId(), user.isAdmin())));
    }

    // ── Upload Management Stats ───────────────────────────────────────────────────
    @GetMapping("/upload-stats")
    @Operation(summary = "Upload management statistics — total, today, batches, deleted, etc.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUploadStats(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getUploadManagementStats(user.getId(), user.getInstitutionId(), user.isAdmin())));
    }

    // ── Deletion Logs ─────────────────────────────────────────────────────────────
    @GetMapping("/deletion-logs")
    @Operation(summary = "Get deletion audit logs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getDeletionLogs(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getDeletionLogs(user.getInstitutionId(), 50)));
    }

    // ── Eligible Stocks (0% to 5% return, vol > 20D avg) ────────────────────────
    @GetMapping("/eligible")
    @Operation(summary = "Get eligible stocks (price crossing above base price, return 0-5%, vol > 20D avg)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEligible(
            @AuthenticationPrincipal User user) {
        List<Map<String, Object>> stocks = trackerService.getEligibleStocks(user.getId(), user.getInstitutionId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "stocks", stocks, "count", stocks.size(),
            "criteria", "Previous Close < Base Price AND Current Price >= Base Price, Return 0-5%, vol > 20D avg")));
    }

    // ── Return Distribution ───────────────────────────────────────────────────
    @GetMapping("/distribution")
    @Operation(summary = "Return distribution curve data")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDistribution(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getPerformanceDistribution(user.getId(), user.getInstitutionId())));
    }

    // ── Manual Price Refresh ────────────────────────────────────────────────────
    @PostMapping("/refresh")
    @Operation(summary = "Trigger manual price refresh for all tracked stocks")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshPrices(
            @AuthenticationPrincipal User user) {
        try {
            Map<String, Object> result = trackerService.refreshAllPrices(
                user.getId(), user.getInstitutionId(), "manual");
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(result);
            java.time.ZonedDateTime nowIst = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
            out.put("success", true);
            out.put("server_time", nowIst.toInstant().toString());
            out.put("last_updated", nowIst.format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss dd-MMM-yyyy", java.util.Locale.ENGLISH)));
            return ResponseEntity.ok(ApiResponse.ok("Price refresh complete.", out));
        } catch (Exception e) {
            // Never crash the UI — surface a retryable failure.
            return ResponseEntity.ok(ApiResponse.error("REFRESH-500",
                UserMessage.of(e, "Prices could not be refreshed. Your existing prices are unchanged.")));
        }
    }

    // ── Stock Upload Automation config (auto-refresh + auto buy/sell toggles) ──
    @GetMapping("/automation/config")
    @Operation(summary = "Get Stock Upload automation flags (auto-refresh + auto buy/sell)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAutomationConfig(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(trackerService.getAutomationConfig()));
    }

    @PutMapping("/automation/config")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','PLATFORM_ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Update Stock Upload automation flags (Admin). LIVE mode is opt-in.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveAutomationConfig(
            @RequestBody Map<String, Object> updates,
            @AuthenticationPrincipal User user) {
        Map<String, Object> result = trackerService.saveAutomationConfig(updates, user.getId());
        return ResponseEntity.ok(ApiResponse.ok("Automation settings saved.", result));
    }

    // ── Run Ranking Engine ──────────────────────────────────────────────────────
    @PostMapping("/rank")
    @Operation(summary = "Run Sahi-style 13-factor ranking engine manually")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runRanking(
            @AuthenticationPrincipal User user) {
        Map<String, Object> result = trackerService.runRankingEngine(
            user.getId(), user.getInstitutionId(), "manual");
        return ResponseEntity.ok(ApiResponse.ok("Ranking complete.", result));
    }

    // ── Latest Ranking (Current + Previous Top-10) ──────────────────────────────
    @GetMapping("/ranking/latest")
    @Operation(summary = "Get current Top-10 and previous Top-10 (separate metrics, never blended)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLatestRanking(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getLatestRankingFull(user.getId(), user.getInstitutionId())));
    }

    @GetMapping("/export/prev-top10")
    @Operation(summary = "Previous Top 10 as CSV - the on-screen columns, for any past date")
    public ResponseEntity<byte[]> exportPrevTop10(
            @RequestParam(required = false) String runDate,
            @AuthenticationPrincipal User user) {
        byte[] csv = trackerService.exportPrevTop10Csv(
            user.getId(), user.getInstitutionId(), runDate);
        return buildCsvResponse(csv, "TradeRakshak_Previous_Top10_"
            + (runDate == null || runDate.isBlank() ? today() : runDate) + ".csv");
    }

    @GetMapping("/export/rsi45-section")
    @Operation(summary = "The RSI 45 section's stocks, with the same columns the section shows")
    public ResponseEntity<byte[]> exportRsi45Section(
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(defaultValue = "true")  boolean requireCrossing,
            @RequestParam(defaultValue = "false") boolean requireVolume,
            @RequestParam(defaultValue = "false") boolean applyRanking,
            // The flags the SECTION uses. Without them the file could never match the screen:
            // useNewestSession alone put the export two sessions behind what was on display.
            @RequestParam(defaultValue = "false") boolean requireFullVolumeWindow,
            @RequestParam(defaultValue = "false") boolean sortByVolumeRatio,
            @RequestParam(defaultValue = "false") boolean requireStochCross,
            @RequestParam(defaultValue = "false") boolean useNewestSession,
            @RequestParam(defaultValue = "false") boolean sortByReturn,
            @RequestParam(defaultValue = "false") boolean requireRsiLevel,
            @RequestParam(defaultValue = "0") double maxPctAboveBase,
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(required = false) String title,
            @AuthenticationPrincipal User user) {
        byte[] csv = trackerService.exportRsi45SectionCsv(
            user.getId(), user.getInstitutionId(), maxPrice, requireCrossing, requireVolume,
            applyRanking, requireFullVolumeWindow, sortByVolumeRatio, requireStochCross,
            useNewestSession, sortByReturn, requireRsiLevel, Math.max(0.0, maxPctAboveBase),
            Math.max(0, limit), title);
        String slug = (title == null || title.isBlank())
            ? "RSI45_Section" : title.replaceAll("[^A-Za-z0-9]+", "_");
        return buildCsvResponse(csv, "TradeRakshak_" + slug + "_" + today() + ".csv");
    }

    @GetMapping("/export/chart-trades")
    @Operation(summary = "Every trade behind the chart bars, one row per stock per day, as CSV for Excel")
    public ResponseEntity<byte[]> exportChartTrades(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Double maxPrice,
            @AuthenticationPrincipal User user) {
        // Default to the last 30 days, the window the screen offers.
        String toDate = (to == null || to.isBlank())
            ? java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString() : to;
        String fromDate = (from == null || from.isBlank())
            ? java.time.LocalDate.parse(toDate).minusDays(30).toString() : from;

        byte[] csv = trackerService.exportChartTradesCsv(
            user.getId(), user.getInstitutionId(), fromDate, toDate, maxPrice);
        String name = "TradeRakshak_ChartBar_Trades_" + fromDate + "_to_" + toDate
                    + (maxPrice != null ? "_below" + maxPrice.intValue() : "") + ".csv";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(csv);
    }

    @GetMapping("/ranking/provisional-top10")
    @Operation(summary = "The list that would be frozen if the cut-off were now - same entry test "
                       + "and ranking, run against live prices")
    public ResponseEntity<ApiResponse<Map<String, Object>>> provisionalTop10(
            @RequestParam(defaultValue = "true") boolean requireCrossing,
            @RequestParam(defaultValue = "true") boolean applyRanking,
            // Must match the cap of the section this preview is shown inside. 0 = no cap.
            @RequestParam(defaultValue = "0") int limit,
            // The RSI 45 crossover, so this preview screens on the same mandatory conditions as
            // the table it sits beneath. Default false keeps any other caller unchanged.
            // The section's own volume condition and base-price ceiling. Defaulted off
            // so any existing caller keeps the preview it has always had; both RSI 45
            // sections send their own values.
            @RequestParam(defaultValue = "false") boolean requireVolume,
            @RequestParam(defaultValue = "0") double maxPctAboveBase,
            @RequestParam(defaultValue = "false") boolean requireRsiLevel,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(trackerService.getProvisionalTop10(
            user.getId(), user.getInstitutionId(), requireCrossing, applyRanking,
            Math.max(0, limit), requireRsiLevel, requireVolume, Math.max(0.0, maxPctAboveBase))));
    }

    @GetMapping("/stock-history")
    @Operation(summary = "Stored daily closes for one symbol, oldest first, for the price chart")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stockHistory(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "120") int days,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.dailyCloseSeries(symbol, Math.max(2, Math.min(days, 500)))));
    }

    @GetMapping("/volbreakout/previous")
    @Operation(summary = "The five finalized on the previous trading session. Identities frozen, "
                       + "market data live.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> volumeBreakoutPrevious(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getVolumeBreakoutPrevious(user.getId(), user.getInstitutionId())));
    }

    @PostMapping("/volbreakout/finalize")
    @Operation(summary = "Freeze today's shortlist. Idempotent - a day already finalized is not "
                       + "recalculated, which is what makes it final.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> volumeBreakoutFinalize(
            // A past session can be finalized for the first time, but one already stored is never
            // rewritten whatever is passed.
            @RequestParam(required = false) String forDate,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(trackerService.finalizeVolumeBreakoutDay(
            user.getId(), user.getInstitutionId(), forDate)));
    }

    @PostMapping("/volbreakout/attach-prices")
    @Operation(summary = "Fill in entry and exit prices from official daily closes where they are "
                       + "still missing. Never revises a price already set.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> volumeBreakoutAttachPrices(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.attachVolumeBreakoutPrices(user.getId(), user.getInstitutionId())));
    }

    @GetMapping("/export/volbreakout")
    @Operation(summary = "Every stock that qualified for the Volume Breakout section, as CSV")
    public ResponseEntity<byte[]> exportVolumeBreakout(@AuthenticationPrincipal User user) {
        byte[] csv = trackerService.exportVolumeBreakoutCsv(user.getId(), user.getInstitutionId());
        return buildCsvResponse(csv, "TradeRakshak_Volume_Breakout_All_Qualifying_" + today() + ".csv");
    }

    @GetMapping("/dead-symbols")
    @Operation(summary = "Symbols Dhan refuses (DH-905) that are still in the uploaded list. "
                       + "Reading this changes nothing.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deadSymbols(
            // Distinct backfill jobs, not occurrences: one job retries a symbol over several date
            // ranges, so counting occurrences would let a single bad run look like a pattern.
            @RequestParam(defaultValue = "2") int minJobs,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            deadSymbols.report(user.getId(), user.getInstitutionId(), minJobs)));
    }

    @PostMapping("/dead-symbols/deactivate")
    @Operation(summary = "Deactivate NAMED symbols. No bulk form: the symbols must be listed.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deactivateDeadSymbols(
            @RequestBody Map<String, Object> req, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(deadSymbols.deactivate(
            user.getId(), user.getInstitutionId(), stringList(req.get("symbols")), user.getName())));
    }

    @PostMapping("/dead-symbols/reactivate")
    @Operation(summary = "Undo, by name - for a symbol deactivated by mistake or relisted since")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reactivateDeadSymbols(
            @RequestBody Map<String, Object> req, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(deadSymbols.reactivate(
            user.getId(), user.getInstitutionId(), stringList(req.get("symbols")))));
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> stringList(Object raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw instanceof java.util.List<?> l) {
            for (Object o : l) if (o != null) out.add(String.valueOf(o));
        } else if (raw != null && !String.valueOf(raw).isBlank()) {
            for (String s : String.valueOf(raw).split("[,\s]+")) if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    @GetMapping("/volbreakout/current")
    @Operation(summary = "Volume Breakout: close at or above base price, with today's volume above "
                       + "the 20-session average. Shortlisted by volume ratio.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> volumeBreakoutCurrent(
            // 0 lists every qualifying stock, which is what the download asks for. The screen
            // passes nothing and gets the configured shortlist size, so the screen and the file
            // cannot disagree about how many "the top five" is.
            @RequestParam(required = false) Integer limit,
            // Only ever passed false by a caller that wants the unfiltered list - the export. The
            // gate is a display rule, so an export must not inherit it.
            @RequestParam(defaultValue = "true") boolean applyLiquidityGate,
            @AuthenticationPrincipal User user) {
        int screenLimit = limit == null
            ? trackerService.getVolumeBreakoutShortlistSize()
            : Math.max(0, limit);
        return ResponseEntity.ok(ApiResponse.ok(trackerService.getVolumeBreakoutCurrent(
            user.getId(), user.getInstitutionId(), screenLimit, applyLiquidityGate)));
    }

    @GetMapping("/ranking/below-price-top10")
    @Operation(summary = "Below-price Top 10: identify (crossing + volume), then rank, then Buy/Sell, "
                       + "with average return and its trend against the previous trading day")
    public ResponseEntity<ApiResponse<Map<String, Object>>> belowPriceTop10(
            // No default. A default of 45 meant the ceiling still applied even when the screen
            // stopped sending one, so removing the control from the UI changed nothing.
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(defaultValue = "true") boolean requireCrossing,
            @RequestParam(defaultValue = "true") boolean requireVolume,
            @RequestParam(defaultValue = "true") boolean applyRanking,
            // Default OFF so nothing that already calls this changes basis underneath it. The
            // sections that state a close-to-close rule on screen pass it explicitly.
            @RequestParam(defaultValue = "false") boolean priceAtClose,
            // 0 = list every qualifying stock. The two RSI 45 sections run the same entry test
            // but are read differently - one as a shortlist to trade, one as the full list of
            // what qualified - so the cap is the caller's to state, not something to infer here.
            @RequestParam(defaultValue = "0") int limit,
            // Added for the volume-based entry test. All three default to the previous
            // behaviour, so every existing caller is unaffected.
            @RequestParam(defaultValue = "false") boolean requireFullVolumeWindow,
            @RequestParam(defaultValue = "false") boolean sortByVolumeRatio,
            // A screen asks "what qualifies now", a holding panel asks "what was selected last
            // session". Default false keeps every existing caller on the holding reading.
            @RequestParam(defaultValue = "false") boolean useNewestSession,
            // "Strongest move first", for the section whose heading says exactly that. Default
            // false keeps every other caller ordered by traded volume as before.
            @RequestParam(defaultValue = "false") boolean sortByReturn,
            // The RSI 45 crossover as a MANDATORY entry condition. It was computed and displayed
            // but never tested, so both RSI 45 sections listed stocks that had not crossed 45.
            // Default false so only the callers that ask for it change.
            @RequestParam(defaultValue = "false") boolean requireRsiLevel,
            // Exclude stocks whose current price sits more than this percentage above their
            // base price - the base is then too stale for a crossing to be reachable at all.
            // 0 = no limit.
            @RequestParam(defaultValue = "0") double maxPctAboveBase,
            // Stochastic %K crossing above %D. Reported on every row regardless; this decides
            // whether it also excludes a stock.
            // Which trading session after entry the exit close is taken from.
            // 1 = the next session, the basis this endpoint has always used.
            @RequestParam(defaultValue = "1") int holdingDays,
            @RequestParam(defaultValue = "false") boolean requireStochCross,
            // Capital to spread across the stocks this screen qualifies. 0 leaves the list
            // exactly as it was: the allocation is an annotation on these rows, never a filter,
            // so asking for it can add columns but can never change which stocks appear.
            @RequestParam(defaultValue = "0") double investmentAmount,
            // DEMO spends the virtual balance; REAL would spend the broker's. Defaults to DEMO
            // so an allocation can never be sized against real money by omission.
            @RequestParam(defaultValue = "DEMO") String accountType,
            @AuthenticationPrincipal User user) {

        Map<String, Object> panel = trackerService.getBelowPriceTop10(
            user.getId(), user.getInstitutionId(), maxPrice, requireCrossing, requireVolume,
            applyRanking, priceAtClose, Math.max(0, limit),
            requireFullVolumeWindow, sortByVolumeRatio, requireStochCross, useNewestSession,
            sortByReturn, requireRsiLevel, Math.max(0.0, maxPctAboveBase),
            Math.max(1, holdingDays));

        if (investmentAmount > 0) {
            attachAllocation(panel, investmentAmount, accountType, user);
        }
        return ResponseEntity.ok(ApiResponse.ok(panel));
    }

    /**
     * Annotate the qualified rows with each stock's share of the investment.
     *
     * <p>Writes onto the list the strategy produced rather than returning one of its own. A
     * second table would have to be kept in step with the first, and a stock appearing in one
     * but not the other makes both untrustworthy.
     *
     * <p>A REAL allocation needs the broker's buying power, which the platform cannot yet read -
     * there is no funds call in the broker integration. Rather than size an allocation against a
     * number that is not the user's money, this reports that and allocates nothing.
     */
    @SuppressWarnings("unchecked")
    private void attachAllocation(Map<String, Object> panel, double investment,
                                  String accountType, User user) {
        List<Map<String, Object>> rows =
            (List<Map<String, Object>>) panel.getOrDefault("top10", List.of());
        String type = accountType == null ? "DEMO" : accountType.trim().toUpperCase();

        double available;
        Map<String, Object> accountInfo;

        if ("REAL".equals(type)) {
            accountInfo = brokerFunds.funds(user);
            Object bp = accountInfo.get("buying_power");
            if (bp == null) bp = accountInfo.get("available_balance");
            if (!(bp instanceof Number)) {
                // No balance at all - no connected account, and nothing read before. Sizing an
                // order against a number that is not the user's money is the one thing this
                // must never do.
                Map<String, Object> blocked = new LinkedHashMap<>();
                blocked.put("allocated", false);
                blocked.put("account_type", "REAL");
                blocked.put("reason", String.valueOf(accountInfo.getOrDefault("message",
                    "The broker balance is unavailable, so nothing was allocated.")));
                blocked.put("account", accountInfo);
                panel.put("allocation", blocked);
                return;
            }
            available = ((Number) bp).doubleValue();
        } else {
            available = demoAccount.balance(user.getId(), user.getInstitutionId());
            accountInfo = demoAccount.summary(user.getId(), user.getInstitutionId());
        }
        Map<String, Object> summary = allocation.attachToRows(
            rows, investment, available, com.traderakshak.service.BrokerageCalculator.Product.DELIVERY, "NSE");
        summary.put("account_type", type);
        // The account the figures came from, including whether a real balance is stale, so the
        // screen can say which money it is sizing against.
        summary.put("account", accountInfo);
        panel.put("allocation", summary);
    }

    @GetMapping("/ranking/reconstruct-trend")
    @Operation(summary = "Per-day returns for any past range, rebuilt from stored candles "
                       + "(no saved ranking required)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReconstructedTrend(
            // Optional: omitted means "as far back as the stored data supports". The panels used
            // to hardcode a 30-day window while the database held 301 sessions and an upload
            // history reaching to January, so most of the chart simply was not being drawn.
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) String buyTime,
            @RequestParam(required = false) String sellTime,
            @RequestParam(required = false) Boolean applyReturnBand,
            @RequestParam(required = false) Boolean requireCrossing,
            @RequestParam(required = false) Boolean capPerSector,
            @RequestParam(required = false) Boolean applyRanking,
            @RequestParam(required = false) Boolean requireVolume,
            @RequestParam(required = false) Boolean priceAtClose,
            // The Volume Breakout chart needs its own basket rules: ratio order rather than raw
            // volume, and short-history stocks held out. Both default to the old behaviour so no
            // existing caller changes.
            @RequestParam(defaultValue = "false") boolean requireFullVolumeWindow,
// The screen's stochastic checkbox. Default false so every existing caller keeps
            // the basket it has always charted; the RSI 45 section sends its own value, which
            // is what makes its bars and its table the same strategy.
            @RequestParam(defaultValue = "false") boolean requireStochCrossBars,
            // Buy intraday at buyTime, sell at the next trading day's official close. Default
            // false so every existing chart keeps the basis it has always been drawn on.
            @RequestParam(defaultValue = "false") boolean sellAtClose,
            // Same condition on the backtest, so the bars measure the strategy the screen runs.
            // How many exchange trading sessions after entry the exit close is taken from.
            // 1 is the next trading session, which is what this endpoint has always charted, so
            // every existing caller keeps its basis. Counted in sessions, so weekends and
            // exchange holidays are skipped rather than counted as days.
            @RequestParam(defaultValue = "1") int holdingDays,
            @RequestParam(defaultValue = "false") boolean requireRsiLevelBars,
            // The same ceiling the section's table applies. Without it the bars averaged a
            // larger population than the table listed.
            @RequestParam(defaultValue = "0") double maxPctAboveBaseBars,
                        @RequestParam(defaultValue = "false") boolean sortByVolumeRatio,
            // The section's own stock cap, so the bars average exactly the rows its table lists.
            // 0 averages every qualifying stock.
            @RequestParam(defaultValue = "0") int limit,
            @AuthenticationPrincipal User user) {
        // All three default OFF, which is the main trend's entry test: Current Price >= Base Price
        // and Current Volume > 20-Day Average Volume, ten picked straight by score. The crossing
        // panel asks for requireCrossing=true; the Ranking Chart Bar asks for capPerSector=true so
        // its days are selected the way Today top 10 Ranking Stocks selects.
        boolean band = applyReturnBand != null ? applyReturnBand : false;
        boolean cross = requireCrossing != null ? requireCrossing : false;
        boolean cap = capPerSector != null ? capPerSector : false;
        // Defaults to true: every existing caller expects ranked days, and a silent change of
        // population under an unchanged chart would be the worst kind of quiet.
        boolean rank = applyRanking != null ? applyRanking : true;
        // Volume defaults to true, which is what this endpoint has always applied. It is now
        // selectable so a section whose table does not filter on volume can have bars drawn from
        // the same population its table lists.
        boolean vol = requireVolume != null ? requireVolume : true;
        // Close-to-close pricing, off unless asked for, so existing charts keep their basis.
        boolean atClose = priceAtClose != null ? priceAtClose : false;
        return ResponseEntity.ok(ApiResponse.ok(trackerService.getReconstructedTrend(
            user.getId(), user.getInstitutionId(), from, to, maxPrice, buyTime, sellTime,
            band, cross, cap, rank, vol, atClose, Math.max(0, limit),
            requireFullVolumeWindow, sortByVolumeRatio, requireStochCrossBars, sellAtClose,
            requireRsiLevelBars, Math.max(0.0, maxPctAboveBaseBars),
            Math.max(1, holdingDays))));
    }

    @GetMapping("/ranking/reconstruct-day")
    @Operation(summary = "The Top 10 as it would have stood at the close of one past trading day")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> reconstructDay(
            @RequestParam String date,
            @RequestParam(required = false) Double maxPrice,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(trackerService.reconstructTop10AsOf(
            user.getId(), user.getInstitutionId(), date, maxPrice)));
    }

    // ── Ranking History (for charts) ────────────────────────────────────────────
    @GetMapping("/ranking/history")
    @Operation(summary = "Historical ranking data for charts (5/10/15/30/60/90 days)")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRankingHistory(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @AuthenticationPrincipal User user) {
        // An explicit range wins over the rolling window, so the chart can show a fixed period.
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            return ResponseEntity.ok(ApiResponse.ok(
                trackerService.getRankingHistory(user.getId(), user.getInstitutionId(), from, to)));
        }
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getRankingHistory(user.getId(), user.getInstitutionId(), days)));
    }

    // ── Dashboard Summary ────────────────────────────────────────────────────────
    @GetMapping("/dashboard")
    @Operation(summary = "Dashboard summary — stats, market status, ranking summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard(
            @AuthenticationPrincipal User user) {
        trackerService.ensureFreshPrices(user.getId(), user.getInstitutionId(), "dashboard_load");
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getDashboardData(user.getId(), user.getInstitutionId())));
    }

    @GetMapping("/positions/summary")
    @Operation(summary = "Open position lookup for Stock Tracker Buy/Sell behaviour")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPositionSummary(
            @AuthenticationPrincipal User user) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT symbol, SUM(qty) qty, AVG(avg_price) avg_price, MAX(updated_at) updated_at " +
                "FROM positions WHERE user_id=:uid AND status='open' GROUP BY symbol")
                .setParameter("uid", user.getId()).getResultList();

            Map<String, Object> bySymbol = new LinkedHashMap<>();
            for (Object[] r : rows) {
                String symbol = r[0] != null ? r[0].toString() : "";
                if (symbol.isBlank()) continue;
                bySymbol.put(symbol.toUpperCase(Locale.ROOT), Map.of(
                    "symbol", symbol.toUpperCase(Locale.ROOT),
                    "qty", r[1] != null ? ((Number) r[1]).intValue() : 0,
                    "avgPrice", r[2] != null ? ((Number) r[2]).doubleValue() : 0,
                    "updatedAt", r[3] != null ? r[3].toString() : ""
                ));
            }
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "positionsBySymbol", bySymbol,
                "openSymbols", bySymbol.keySet()
            )));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "positionsBySymbol", Map.of(),
                "openSymbols", List.of()
            )));
        }
    }

    // ── Weight Config (read) ────────────────────────────────────────────────────
    @GetMapping("/config")
    @Operation(summary = "Get 13-factor ranking weight configuration")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfig(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getTrackerWeightConfig(user.getInstitutionId())));
    }

    // ── Weight Config (write — Admin) ────────────────────────────────────────────
    @PutMapping("/config")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','PLATFORM_ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Update ranking factor weights (Admin — no code change needed)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveConfig(
            @RequestBody List<Map<String, Object>> factorUpdates,
            @AuthenticationPrincipal User user) {
        Map<String, Object> result = trackerService.saveTrackerWeights(
            user.getInstitutionId(), user.getId(), factorUpdates);
        if (Boolean.FALSE.equals(result.get("success"))) {
            return ResponseEntity.ok(ApiResponse.error("CONFIG-400", (String)result.get("error")));
        }
        return ResponseEntity.ok(ApiResponse.ok("Configuration saved.", result));
    }

    // =========================================================================
    //  EXPORT ENDPOINTS — Fixed: correct data, formatted numbers, all columns
    //  All exports require authentication (JWT passed via header, not URL)
    // =========================================================================

    // ── Eligible Stocks CSV ────────────────────────────────────────────────────
    @GetMapping("/export/eligible")
    @Operation(summary = "Export eligible stocks (price crossing above base, return 0-5%, vol > 20D avg) as CSV")
    public ResponseEntity<byte[]> exportEligible(
            @RequestParam(defaultValue = "false") boolean requireVolume,
            @AuthenticationPrincipal User user) {
        // Volume defaults to reported-not-filtered: with it on, the three price rules selected
        // nine stocks and volume removed all nine.
        byte[] csv = trackerService.exportEligibleStocksCsv(
            user.getId(), user.getInstitutionId(), requireVolume);
        return buildCsvResponse(csv, "TradeRakshak_Eligible_Stocks_" + today() + ".csv");
    }

    // ── Stochastic Crossover CSV ──────────────────────────────────────────
    // Was /export/atr-base-crossover. The screen now confirms a base-price crossover with a
    // Stochastic %K/%D bullish cross and a volume test instead of an ATR floor; ATR is still
    // computed and reported so the filter that did survive testing stays visible.
    @GetMapping("/export/stochastic-crossover")
    @Operation(summary = "Base price breakout confirmed by volume and a Stochastic %K/%D bullish "
                       + "crossover, with stop, target, holding period and per-trade outcome")
    public ResponseEntity<byte[]> exportStochasticCrossover(
            // 0 = ATR does not filter; it is still reported on every row.
            @RequestParam(defaultValue = "0.0") double minAtrPct,
            @RequestParam(defaultValue = "true") boolean requireVolume,
            @RequestParam(defaultValue = "false") boolean firstTriggerOnly,
            @RequestParam(defaultValue = "true") boolean requireStochastic,
            @RequestParam(defaultValue = "100000") double minAvgVolume,
            // Close confirmation. OFF by default - the original entry mechanic: a resting
            // buy-stop at the base price, taken whether or not the breakout holds into the
            // close. Pass true to require Close(D) >= Base Price, which also moves the fill to
            // that close, since the trade is then not decided until the session ends.
            @RequestParam(defaultValue = "false") boolean requireCloseAboveBase,
            @AuthenticationPrincipal User user) {
        byte[] csv = trackerService.exportStochasticCrossoverCsv(
            user.getId(), user.getInstitutionId(), minAtrPct, requireVolume, firstTriggerOnly,
            requireStochastic, minAvgVolume, requireCloseAboveBase);
        return buildCsvResponse(csv, "TradeRakshak_Stochastic_Crossover_" + today() + ".csv");
    }

    // ── Current Top-10 Ranking CSV ────────────────────────────────────────────
    @GetMapping("/export/ranking")
    @Operation(summary = "Export current Top-10 ranking as CSV")
    public ResponseEntity<byte[]> exportRanking(@AuthenticationPrincipal User user) {
        byte[] csv = trackerService.exportRankingCsv(user.getId(), user.getInstitutionId());
        return buildCsvResponse(csv, "TradeRakshak_Current_Top10_" + today() + ".csv");
    }

    // ── Previous Top-10 Ranking CSV ───────────────────────────────────────────
    @GetMapping("/export/previous")
    @Operation(summary = "Export previous Top-10 ranking as CSV")
    public ResponseEntity<byte[]> exportPreviousRanking(@AuthenticationPrincipal User user) {
        byte[] csv = trackerService.exportPreviousRankingCsv(user.getId(), user.getInstitutionId());
        return buildCsvResponse(csv, "TradeRakshak_Previous_Top10_" + today() + ".csv");
    }

    // ── Historical Rankings CSV ───────────────────────────────────────────────
    @GetMapping("/export/historical")
    @Operation(summary = "Export historical ranking data (up to 365 days) as CSV")
    public ResponseEntity<byte[]> exportHistorical(@AuthenticationPrincipal User user) {
        byte[] csv = trackerService.exportHistoricalCsv(user.getId(), user.getInstitutionId());
        return buildCsvResponse(csv, "TradeRakshak_Historical_Rankings_" + today() + ".csv");
    }

    // ── Return Distribution CSV ───────────────────────────────────────────────
    @GetMapping("/export/distribution")
    @Operation(summary = "Export return distribution report as CSV")
    public ResponseEntity<byte[]> exportDistribution(@AuthenticationPrincipal User user) {
        byte[] csv = trackerService.exportDistributionCsv(user.getId(), user.getInstitutionId());
        return buildCsvResponse(csv, "TradeRakshak_Distribution_" + today() + ".csv");
    }

    // ── All Tracked Stocks CSV ────────────────────────────────────────────────
    @GetMapping("/export/all-stocks")
    @Operation(summary = "Export all tracked stocks as CSV")
    public ResponseEntity<byte[]> exportAllStocks(
            @RequestParam(defaultValue = "ALL") String sector,
            @RequestParam(required = false) Double minReturn,
            @RequestParam(required = false) Double maxReturn,
            @RequestParam(defaultValue = "symbol") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir,
            @AuthenticationPrincipal User user) {
        List<Map<String, Object>> stocks = trackerService.getAllTrackedStocks(
            user.getId(), user.getInstitutionId(), sector, null, minReturn, maxReturn,
            sortBy, sortDir, 0, 50000, true);

        StringBuilder csv = new StringBuilder();
        csv.append("\uFEFF");
        csv.append("TradeRakshak\u2122 - Uploaded Stock Details\n");
        csv.append("Generated: ").append(today()).append(",Total: ").append(stocks.size()).append("\n");
        csv.append("DISCLAIMER: For educational purposes only. Not SEBI investment advice.\n\n");
        csv.append("Symbol,Sector,Market Cap,Upload Date,Base Price,Current Price,Status,Price Source,Return %,Signal,Trend,Vol %,52-WH,52-WL,PE,Last Updated,Status Reason\n");

        for (Map<String, Object> s : stocks) {
            csv.append(csvCell(s.get("symbol"))).append(",")
               .append(csvCell(s.get("sector"))).append(",")
               .append(csvCell(s.get("market_cap"))).append(",")
               .append(csvCell(s.get("upload_date"))).append(",")
               .append(csvCell(fmt2(s.get("base_price")))).append(",")
               .append(csvCell(fmt2(s.get("current_price")))).append(",")
               .append(csvCell(s.get("price_status"))).append(",")
               .append(csvCell(s.get("current_price_source"))).append(",")
               .append(csvCell(fmt2(s.get("return_pct")))).append(",")
               .append(csvCell(s.get("buy_sell_signal"))).append(",")
               .append(csvCell(s.get("trend"))).append(",")
               .append(csvCell(fmt2(s.get("today_volume_change_pct")))).append(",")
               .append(csvCell(fmt2(s.get("below_52wh_pct")))).append(",")
               .append(csvCell(fmt2(s.get("above_52wl_pct")))).append(",")
               .append(csvCell(fmt2(s.get("pe_ratio")))).append(",")
               .append(csvCell(s.get("last_refreshed"))).append(",")
               .append(csvCell(s.get("price_status_reason"))).append("\n");
        }
        return buildCsvResponse(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "TradeRakshak_All_Stocks_" + today() + ".csv");
    }

    // ── Helper: build CSV response with headers ────────────────────────────────
    private ResponseEntity<byte[]> buildCsvResponse(byte[] data, String filename) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        h.set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        h.set("Cache-Control", "no-cache, no-store, must-revalidate");
        h.set("Pragma", "no-cache");
        h.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(h).body(data);
    }

    private String today() {
        return java.time.LocalDate.now().toString().replace("-","");
    }
    private String nvl(Object v) { return v == null ? "" : v.toString().replace("\n","").replace("\r",""); }
    private String csvCell(Object v) {
        String text = nvl(v);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    private String fmt2(Object v) {
        if (v == null) return "";
        try { return String.format("%.2f", ((Number)v).doubleValue()); }
        catch(Exception e) { return nvl(v); }
    }

    // ── Market Breadth Dashboard ──────────────────────────────────────────────
    @GetMapping("/breadth")
    @Operation(summary = "Market breadth data — distribution, sector breadth, historical scores")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMarketBreadth(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getMarketBreadthData(user.getId(), user.getInstitutionId())));
    }

    // ── Market Trend Identification Engine ───────────────────────────────────
    @GetMapping("/market-health")
    @Operation(summary = "Market Health Score 0-100 and the Strong Buy / Buy / No Trade / Sell / "
                       + "Strong Sell state, from Nifty 500 breadth")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMarketHealth(
            @RequestParam(defaultValue = "false") boolean force) {
        // No user scoping: market breadth is a property of the market, not of one account's
        // watchlist. Scoping it to uploaded stocks would make two users disagree about whether
        // it is a buy market.
        //
        // force=true is the Refresh button on the status band. It scans ~2,600 symbols over 260
        // sessions, so it is a deliberate action, never the page-load path.
        return ResponseEntity.ok(ApiResponse.ok(marketRegime.marketHealth(force)));
    }

    /** Every stored daily reading, newest first, for the history panel. */
    /** What the platform can measure right now, and what each gap prevents. */
    @GetMapping("/data-health")
    @Operation(summary = "Data availability: missing sessions, intraday coverage, base prices, "
                       + "and what each gap stops the screens from showing")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dataHealth(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.dataHealth(user.getId(), user.getInstitutionId())));
    }

    @GetMapping("/market-health/history")
    @Operation(summary = "Stored daily market status readings, one row per trading session")
    public ResponseEntity<ApiResponse<Map<String, Object>>> marketHealthHistory(
            @RequestParam(defaultValue = "180") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = marketRegime.history(limit);
        out.put("rows", rows);
        out.put("count", rows.size());
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /**
     * The same history as a spreadsheet.
     *
     * <p>CSV rather than a binary workbook: Excel opens it directly, it survives being diffed or
     * scripted against, and the file is the data rather than a rendering of it.
     */
    @GetMapping("/export/market-health")
    @Operation(summary = "Daily market status history as CSV, for Excel")
    public ResponseEntity<byte[]> exportMarketHealth(
            @RequestParam(defaultValue = "2000") int limit) {
        byte[] body = marketRegime.historyCsv(limit);
        return ResponseEntity.ok()
            .header("Content-Type", "text/csv; charset=UTF-8")
            .header("Content-Disposition",
                    "attachment; filename=\"TradeRakshak_Daily_Market_Status.csv\"")
            .body(body);
    }

    // ── Sectors List ─────────────────────────────────────────────────────────────
    @GetMapping("/sectors")
    @Operation(summary = "Available sectors in tracked stocks (for filter dropdown)")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<List<String>>> getSectors(
            @AuthenticationPrincipal User user) {
        try {
            List<String> sectors = em.createNativeQuery(
                "SELECT DISTINCT sector FROM tracked_stocks WHERE institution_id=? " +
                "AND user_id=? AND sector IS NOT NULL AND sector != '' ORDER BY sector")
                .setParameter(1, user.getInstitutionId())
                .setParameter(2, user.getId()).getResultList();
            return ResponseEntity.ok(ApiResponse.ok(sectors));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(List.of()));
        }
    }

    // ── Upload History ─────────────────────────────────────────────────────────
    @GetMapping("/uploads")
    @Operation(summary = "Get upload history for this institution")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUploads(
            @AuthenticationPrincipal User user) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT upload_id,file_name,file_type,total_rows,new_records,duplicate_records," +
                "failed_records,status,upload_date,created_at FROM stock_uploads " +
                "WHERE institution_id=? AND user_id=? ORDER BY created_at DESC LIMIT 50")
                .setParameter(1, user.getInstitutionId())
                .setParameter(2, user.getId()).getResultList();

            List<Map<String, Object>> uploads = rows.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("upload_id",  r[0]); m.put("file_name",  r[1]);
                m.put("file_type",  r[2]); m.put("total_rows", r[3]);
                m.put("new_records",r[4]); m.put("duplicates", r[5]);
                m.put("failed",     r[6]); m.put("status",     r[7]);
                m.put("upload_date",r[8]); m.put("created_at", r[9]);
                return m;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.ok(uploads));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(List.of()));
        }
    }

    // ── Trade Batches ──────────────────────────────────────────────────────────
    @PostMapping("/trade-batches/buy")
    @Operation(summary = "Create trade batch from Current Top 10 (Buy All 10)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTradeBatch(
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal User user) {
        String buyTime = body != null ? body.get("buy_time") : null;
        String tradeType = body != null ? body.getOrDefault("trade_type", "PAPER") : "PAPER";
        String period = body != null ? body.getOrDefault("period", "NOW") : "NOW";
        String customDate = body != null ? body.get("custom_date") : null;
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.createTradeBatch(user.getId(), user.getInstitutionId(), buyTime, tradeType, period, customDate)));
    }

    @GetMapping("/trade-batches/preview")
    @Operation(summary = "Preview Current Top 10 as a portfolio at the selected time period (DB-only, no write)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewTradeBatch(
            @RequestParam(defaultValue = "NOW") String period,
            @RequestParam(required = false) String customDate,
            @RequestParam(required = false) String time,
            @RequestParam(required = false) String sellPeriod,
            @RequestParam(required = false) String sellCustomDate,
            @RequestParam(required = false) String sellTime,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getTop10Preview(user.getId(), user.getInstitutionId(), period, customDate, time,
                sellPeriod, sellCustomDate, sellTime)));
    }

    @GetMapping("/diagnostics/stale-prev-close")
    @Operation(summary = "List stocks whose latest daily candle is stale/missing (Previous Close cannot update)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stalePrevClose(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getStalePrevCloseStocks(user.getId(), user.getInstitutionId())));
    }

    @PostMapping("/trade-batches/{batchId}/sell")
    @Operation(summary = "Sell/close a trade batch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sellTradeBatch(
            @PathVariable String batchId,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal User user) {
        String sellTime = body != null ? body.get("sell_time") : null;
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.sellTradeBatch(batchId, user.getId(), user.getInstitutionId(), sellTime)));
    }

    @GetMapping("/trade-batches")
    @Operation(summary = "Get trade batches with summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTradeBatches(
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getTradeBatches(user.getId(), user.getInstitutionId(), status, limit)));
    }

    @PostMapping("/daily-ranking-trend/recompute")
    @Operation(summary = "Fill in chart bars for past ranking days that have no stored return yet")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recomputeDailyReturns(
            @RequestParam(defaultValue = "60") int days,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.recomputeDailyReturns(user.getId(), user.getInstitutionId(), days)));
    }

    @GetMapping("/daily-ranking-trend")
    @Operation(summary = "One return per real trading day, from that day's official Top 10 ranking")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getDailyRankingTrend(
            @RequestParam(defaultValue = "90") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getDailyRankingTrend(user.getId(), user.getInstitutionId(), limit)));
    }

    @GetMapping("/trade-batches/trend")
    @Operation(summary = "Get trade batch return trend for chart")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTradeBatchTrend(
            @RequestParam(defaultValue = "90") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getTradeBatchTrend(user.getId(), user.getInstitutionId(), limit)));
    }

    @PostMapping("/historical-trade")
    @Operation(summary = "Replay the Top 10 ranked at the primary moment as a BUYER (long) or SELLER (short) trade")
    public ResponseEntity<ApiResponse<Map<String, Object>>> historicalTrade(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        String dir  = str(body, "trade_direction");
        Double cap  = body.get("capital") instanceof Number n ? n.doubleValue() : null;
        @SuppressWarnings("unchecked")
        List<String> syms = body.get("symbols") instanceof List<?> l
            ? l.stream().map(String::valueOf).toList() : null;
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.analyzeHistoricalTrade(user.getId(), user.getInstitutionId(), dir,
                str(body, "primary_date"), str(body, "primary_time"),
                str(body, "secondary_date"), str(body, "secondary_time"),
                cap, str(body, "price_rule"), syms)));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        String s = v == null ? null : String.valueOf(v).trim();
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }

    @GetMapping("/range-backtest")
    @Operation(summary = "Replay Current Top 10 across a date range (stored data only, works outside market hours)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rangeBacktest(
            @RequestParam String fromDate,
            @RequestParam String toDate,
            @RequestParam(required = false) String buyTime,
            @RequestParam(required = false) String sellTime,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getRangeBacktest(user.getId(), user.getInstitutionId(),
                fromDate, toDate, buyTime, sellTime)));
    }

    @GetMapping("/backtest-trend")
    @Operation(summary = "Configurable trend: backtest Top 10 buy@buyTime / sell next day@sellTime using stored intraday prices")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> backtestTrend(
            @RequestParam(defaultValue = "15:15") String buyTime,
            @RequestParam(defaultValue = "15:15") String sellTime,
            @RequestParam(defaultValue = "30") int days,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getBacktestTrend(user.getId(), user.getInstitutionId(), buyTime, sellTime, days)));
    }

    // ── Previous Top 10 Trade Batches ────────────────────────────────────────
    @PostMapping("/prev-trade-batches/buy")
    @Operation(summary = "Buy All 10 — create trade batch from Previous Top 10")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPrevTradeBatch(
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal User user) {
        try {
            String buyTime = body != null ? body.get("buy_time") : null;
            String tradeType = body != null ? body.getOrDefault("trade_type", "PAPER") : "PAPER";
            return ResponseEntity.ok(ApiResponse.ok(
                trackerService.createPrevTop10TradeBatch(user.getId(), user.getInstitutionId(), buyTime, tradeType)));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("PREV-BUY-500", UserMessage.of(e, "The Previous Top 10 batch could not be created.")));
        }
    }

    @GetMapping("/prev-trade-batches/active")
    @Operation(summary = "Get active Previous Top 10 trade batch with live prices")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getActivePrevBatch(
            @AuthenticationPrincipal User user) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(
                trackerService.getActivePrevTop10Batch(user.getId(), user.getInstitutionId())));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("PREV-ACTIVE-500", UserMessage.of(e, "This trade batch could not be loaded.")));
        }
    }

    @PostMapping("/prev-trade-batches/refresh")
    @Operation(summary = "Refresh Previous Top 10 — update current prices and returns only")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshPrevBatch(
            @AuthenticationPrincipal User user) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(
                trackerService.refreshPrevTop10Batch(user.getId(), user.getInstitutionId())));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("PREV-REFRESH-500", UserMessage.of(e, "Refresh failed.")));
        }
    }

    @DeleteMapping("/trade-batches/{batchId}")
    @Operation(summary = "Delete a trade batch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteTradeBatch(
            @PathVariable String batchId,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.deleteTradeBatch(batchId, user.getInstitutionId())));
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private long countTrackedStocks(Long userId, Long institutionId, String sector, String mcap, Double minReturn, Double maxReturn) {
        try {
            StringBuilder q = new StringBuilder("SELECT COUNT(*) FROM tracked_stocks WHERE institution_id=? AND is_active=1");
            List<Object> params = new ArrayList<>();
            params.add(institutionId);
            q.append(" AND user_id=?"); params.add(userId);
            if (sector != null && !sector.isBlank() && !"ALL".equals(sector)) {
                q.append(" AND sector=?"); params.add(sector);
            }
            if (mcap != null && !mcap.isBlank() && !"ALL".equals(mcap)) {
                q.append(" AND market_cap_name LIKE ?"); params.add("%" + mcap + "%");
            }
            if (minReturn != null) { q.append(" AND return_pct >= ?"); params.add(minReturn); }
            if (maxReturn != null) { q.append(" AND return_pct <= ?"); params.add(maxReturn); }
            var query = em.createNativeQuery(q.toString());
            for (int i = 0; i < params.size(); i++) query.setParameter(i + 1, params.get(i));
            Object r = query.getSingleResult();
            return r != null ? ((Number) r).longValue() : 0;
        } catch (Exception e) { return 0; }
    }

    // ==========================================================================
    //  UPLOAD HISTORY — the two data-resolution modes, addressable directly
    //
    //  Mode A (/history/latest) is what the current-state screens already get via
    //  tracked_stocks; it is exposed so a screen can ask for it explicitly.
    //  Mode B (/history/by-date, /history/as-of) is date-driven and must never be
    //  substituted for Mode A on a "today" screen, or vice versa.
    // ==========================================================================

    /** MODE A — each symbol's most recent appearance, whatever date is on screen. */
    @GetMapping("/history/latest")
    @Operation(summary = "Latest upload record per symbol (Mode A — current-state screens)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> historyLatest(
            @AuthenticationPrincipal User user) {
        List<Map<String, Object>> rows =
            uploadHistory.getLatestPerSymbol(user.getId(), user.getInstitutionId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "mode", "LATEST", "stocks", rows, "count", rows.size())));
    }

    /** MODE B — exactly the stocks flagged on one date. */
    @GetMapping("/history/by-date")
    @Operation(summary = "Stocks flagged on an exact date (Mode B — Previous Chart by date)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> historyByDate(
            @RequestParam String date,
            @AuthenticationPrincipal User user) {
        List<Map<String, Object>> rows =
            uploadHistory.getByDate(date, user.getId(), user.getInstitutionId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "mode", "EXACT_DATE", "date", date, "stocks", rows, "count", rows.size())));
    }

    /** MODE B — the book as it stood on a date: each symbol's latest flag on or before it. */
    @GetMapping("/history/as-of")
    @Operation(summary = "Stocks as of a date, latest flag on or before it (Mode B — backtest)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> historyAsOf(
            @RequestParam String date,
            @AuthenticationPrincipal User user) {
        List<Map<String, Object>> rows =
            uploadHistory.getAsOfDate(date, user.getId(), user.getInstitutionId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "mode", "AS_OF_DATE", "date", date, "stocks", rows, "count", rows.size())));
    }

    /**
     * Base price on the flagged date against the latest stored close.
     * {@code asOf=false} prices only the stocks flagged on that exact date;
     * {@code asOf=true} prices the whole book as it stood that day.
     */
    @GetMapping("/history/backtest")
    @Operation(summary = "Backtest returns from the flagged date's base price (Mode B)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> historyBacktest(
            @RequestParam String date,
            @RequestParam(defaultValue = "true") boolean asOf,
            @AuthenticationPrincipal User user) {
        List<Map<String, Object>> rows = uploadHistory.getBacktestReturns(
            date, asOf, user.getId(), user.getInstitutionId());
        // Latent rather than live: this source always writes a number today. Guarded anyway,
        // because the same unguarded cast on the RSI 45 rows took both sections off the screen
        // the moment their Return % became legitimately absent.
        double avg = rows.stream()
            .map(r -> r.get("return_pct"))
            .filter(Number.class::isInstance)
            .mapToDouble(v -> ((Number) v).doubleValue())
            .average().orElse(0);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "mode", asOf ? "AS_OF_DATE" : "EXACT_DATE",
            "date", date, "stocks", rows, "count", rows.size(),
            "avg_return_pct", Math.round(avg * 100.0) / 100.0)));
    }

    /** Every appearance of one symbol — the audit view that shows both modes are right. */
    @GetMapping("/history/symbol/{symbol}")
    @Operation(summary = "All historical appearances of a symbol, newest first")
    public ResponseEntity<ApiResponse<Map<String, Object>>> historyForSymbol(
            @PathVariable String symbol,
            @AuthenticationPrincipal User user) {
        List<Map<String, Object>> rows =
            uploadHistory.getSymbolHistory(symbol, user.getId(), user.getInstitutionId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "symbol", symbol.trim().toUpperCase(),
            "appearances", rows, "count", rows.size(),
            "latest_entry_date", rows.isEmpty() ? "" : rows.get(0).get("entry_date"))));
    }

    /** Signal dates present in the log — populates the historical date picker. */
    @GetMapping("/history/dates")
    @Operation(summary = "Distinct signal dates in the upload history, newest first")
    public ResponseEntity<ApiResponse<Map<String, Object>>> historyDates(
            @RequestParam(defaultValue = "365") int limit,
            @AuthenticationPrincipal User user) {
        List<String> dates =
            uploadHistory.getAvailableDates(user.getId(), user.getInstitutionId(), limit);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("dates", dates, "count", dates.size())));
    }

    /** Re-derives missing base prices from stored candles, per row's own entry_date. */
    @PostMapping("/history/backfill-base-prices")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Backfill upload-history base prices from stored daily candles")
    public ResponseEntity<ApiResponse<Map<String, Object>>> historyBackfillBasePrices(
            @AuthenticationPrincipal User user) {
        int filled = uploadHistory.backfillBasePrices(user.getInstitutionId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "filled", filled,
            "message", filled > 0
                ? filled + " history row(s) priced from stored candles."
                : "No history row needed a base price, or no matching candles are stored yet.")));
    }

    // ── Background Job Tracking ─────────────────────────────────────────────
    @GetMapping("/jobs")
    @Operation(summary = "List background processing jobs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listJobs(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            trackerService.getBackgroundJobs(user.getInstitutionId())));
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Get status of a background processing job")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getJob(
            @PathVariable String jobId,
            @AuthenticationPrincipal User user) {
        Map<String, Object> job = trackerService.getBackgroundJob(jobId);
        if (job == null) return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "NOT_FOUND", "job_id", jobId)));
        return ResponseEntity.ok(ApiResponse.ok(job));
    }

    @GetMapping("/jobs/{jobId}/pipeline")
    @Operation(summary = "Stage-by-stage progress for an upload job")
    public ResponseEntity<ApiResponse<Map<String, Object>>> jobPipeline(
            @PathVariable String jobId,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(processingService.getPipelineStatus(jobId)));
    }

    @PostMapping("/jobs/{jobId}/pause")
    @Operation(summary = "Pause a running background job")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pauseJob(
            @PathVariable String jobId,
            @AuthenticationPrincipal User user) {
        boolean paused = trackerService.pauseBackgroundJob(jobId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "job_id", jobId, "paused", paused,
            "message", paused ? "Upload paused. Press Resume to carry on."
                              : "That upload is not running, so there is nothing to pause.")));
    }

    @PostMapping("/jobs/{jobId}/resume")
    @Operation(summary = "Resume a paused background job")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resumeJob(
            @PathVariable String jobId,
            @AuthenticationPrincipal User user) {
        boolean resumed = trackerService.resumeBackgroundJob(jobId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "job_id", jobId, "resumed", resumed,
            "message", resumed ? "Upload resumed." : "That upload is not paused.")));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @Operation(summary = "Cancel a running background job")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelJob(
            @PathVariable String jobId,
            @AuthenticationPrincipal User user) {
        boolean cancelled = trackerService.cancelBackgroundJob(jobId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "job_id", jobId, "cancelled", cancelled,
            "message", cancelled ? "Job cancellation requested" : "Job not found or not running")));
    }
}
