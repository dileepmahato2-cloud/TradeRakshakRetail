package com.traderakshak.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.entity.User;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import java.sql.Timestamp;

@Service
@RequiredArgsConstructor
@Slf4j
public class DhanHistoricalBackfillService {

    private static final String DHAN_API = "https://api.dhan.co/v2";
    private static final String DHAN_HISTORICAL_URL = DHAN_API + "/charts/historical";
    private static final String DHAN_INTRADAY_URL = DHAN_API + "/charts/intraday";
    private static final String DHAN_SCRIP_MASTER = "https://images.dhan.co/api-data/api-scrip-master-detailed.csv";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DHAN_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DHAN_INTRADAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> DHAN_INTRADAY_INTERVALS = Set.of("1", "5", "15", "25", "60");
    // IDX_I is Dhan's segment for index candles (NIFTY, BANKNIFTY, ...). The instrument master
    // stores these rows as NSE_I / BSE_I; normalizeExchangeSegment maps both onto IDX_I, which is
    // what the charts API expects. Without IDX_I here every index request was rejected locally.
    private static final Set<String> DHAN_EXCHANGE_SEGMENTS = Set.of("NSE_EQ", "NSE_FNO", "BSE_EQ", "BSE_FNO", "MCX_COMM", "IDX_I");
    private static final int STALE_JOB_MINUTES = 10;

    /**
     * Stable marker carried by {@link #refuseIfAnotherJobIsRunning()}'s message.
     *
     * <p>Callers that expect the refusal match on this rather than on the prose around it, so the
     * wording can change without turning an expected skip back into a logged fault - which is
     * exactly what happened: the refusal was new, StartupSyncService's catch only recognised the
     * older "DATA-ACTIVE" phrasing, and its two-minute watchdog logged a three-line WARN for a
     * backfill that was behaving perfectly normally.
     */
    public static final String ANOTHER_JOB_RUNNING_MARKER = "Only one backfill can run at a time";

    private final BlockingHttpService http;

    // Pace Dhan historical/intraday calls to avoid HTTP 429 (DH-904) rate-limit storms.
    //
    // This gap used to be a flat 1100 ms, chosen defensively rather than measured. At roughly
    // 0.84 s of real API and database work per instrument, that meant 57% of every backfill was
    // spent asleep - about 101 minutes for 3,136 instruments, of which 57 were deliberate waiting.
    //
    // It is now self-tuning between a floor and a ceiling. It starts low, and the only thing that
    // widens it is Dhan actually complaining: a rate-limit answer doubles the gap immediately, and
    // it narrows again slowly after a run of clean calls. So a permissive API is used at full
    // speed, a busy one is backed off from within one request, and neither case needs a guess
    // baked into the source. The retry handler with its 8-second backoff remains underneath as the
    // safety net if the gap is still too small.
    // 250 ms was never sustainable: across 2h30m of job #6 the gap reached the floor nine times
    // and was rate-limited within seconds on every one of them. Start where the evidence says
    // the API is comfortable.
    private static final long GAP_FLOOR_MS   = 500;
    private static final long GAP_CEILING_MS = 2500;
    private static final int  CLEAN_CALLS_BEFORE_NARROWING = 25;

    private final java.util.concurrent.atomic.AtomicLong historicalGapMs =
        new java.util.concurrent.atomic.AtomicLong(GAP_FLOOR_MS);
    private final java.util.concurrent.atomic.AtomicInteger cleanCallStreak =
        new java.util.concurrent.atomic.AtomicInteger(0);
    /** Widest gap Dhan has already refused this run; narrowing never goes back below it. */
    private final java.util.concurrent.atomic.AtomicLong lastRefusedGapMs =
        new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong lastHistoricalCallMs = new java.util.concurrent.atomic.AtomicLong(0);
    private final DhanBrokerService dhanBrokerService;
    private final MarketCalendarService calendar;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactionManager;
    private final AtomicBoolean instrumentRefreshRunning = new AtomicBoolean(false);
    private volatile Map<String, Object> instrumentRefreshStatus = Map.of("status", "idle");

    @PersistenceContext
    private EntityManager em;

    public Map<String, Object> refreshInstrumentMaster(User user) {
        if (!instrumentRefreshRunning.compareAndSet(false, true)) {
            return instrumentRefreshStatus;
        }
        instrumentRefreshStatus = instrumentStatus("running", "Connecting to Dhan public instrument master.", 0, 100, "Connecting", null);
        Thread worker = new Thread(() -> {
            try {
                refreshInstrumentMasterInternal(user, null, false);
            } catch (Exception e) {
                instrumentRefreshStatus = instrumentStatus("failed", "Dhan Stock List fetch failed: " + e.getMessage(), 0, 100, "Failed", null);
            } finally {
                instrumentRefreshRunning.set(false);
            }
        }, "dhan-instrument-refresh");
        worker.setDaemon(true);
        worker.start();
        return instrumentRefreshStatus;
    }

    public Map<String, Object> refreshInstrumentMasterNow(User user, Long brokerCredentialId) throws Exception {
        return refreshInstrumentMasterInternal(user, null, false);
    }

    public Map<String, Object> instrumentRefreshStatus() {
        return instrumentRefreshStatus;
    }

    private Map<String, Object> refreshInstrumentMasterInternal(User user, DhanBrokerService.Credential credential, boolean requireBroker) throws Exception {
        if (requireBroker && credential == null) {
            throw new IllegalStateException("Select a verified Dhan broker connection before fetching Stock List.");
        }
        // Streamed, not buffered. The previous shape of this method was:
        //
        //     String csv  = http.get(...)            // 40 MB of text, plus the 40 MB byte[]
        //     List<Map>   = parseCsv(csv)            // ~212,000 LinkedHashMaps
        //     one transaction around 212,000 inserts
        //
        // which is what exhausted the heap on a 2 GB server. parseCsv held every row TWICE -
        // once as String[] and again as a LinkedHashMap - and rebuilt the column name for every
        // cell of every row (header[c].trim().toUpperCase() inside the inner loop), so a file
        // with ~20 columns produced over four million throwaway key strings. Peak live set ran
        // to several hundred megabytes for a file of forty.
        //
        // Now: one line in memory at a time, column names resolved once into an index, and the
        // inserts committed in chunks. Peak heap is a chunk, not a file, so this no longer
        // scales with the size of Dhan's master.
        instrumentRefreshStatus = instrumentStatus("running",
            "Streaming Dhan public instrument master CSV.", 5, 100, "Downloading", null);

        final Map<String, Integer> columnIndex = new LinkedHashMap<>();
        final List<String[]> batch = new ArrayList<>(INSTRUMENT_CHUNK);
        final int[] counters = new int[2];          // 0 = instruments written, 1 = stock_master rows
        final TransactionTemplate tx = new TransactionTemplate(transactionManager);

        http.forEachLine(DHAN_SCRIP_MASTER, Map.of("Accept", "text/csv"), 30000, line -> {
            if (line == null || line.isBlank()) return;
            String[] cols = splitCsv(line);
            if (columnIndex.isEmpty()) {
                // Header, resolved once. This is the allocation that used to happen per cell.
                for (int i = 0; i < cols.length; i++) {
                    columnIndex.put(cols[i].trim().toUpperCase(Locale.ROOT), i);
                }
                return;
            }
            batch.add(cols);
            if (batch.size() >= INSTRUMENT_CHUNK) {
                flushInstrumentBatch(tx, columnIndex, batch, counters);
                instrumentRefreshStatus = instrumentStatus("running",
                    "Saving Dhan instruments and Stock Master equity rows.",
                    counters[0], 0, "Saving", Map.of("equityStockRows", counters[1]));
            }
        });
        if (!batch.isEmpty()) flushInstrumentBatch(tx, columnIndex, batch, counters);

        if (columnIndex.isEmpty()) {
            throw new IllegalStateException(
                "Dhan instrument master returned no header row - nothing was imported.");
        }
        final int count = counters[0];

        tx.executeWithoutResult(status -> em.createNativeQuery(
            "INSERT INTO broker_data_snapshots(user_id,institution_id,broker_credential_id,broker_name,data_type,source,payload_json,status,message,created_at,normalized_table,rows_imported) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1, user.getId())
            .setParameter(2, user.getInstitutionId())
            .setParameter(3, credential == null ? null : credential.id())
            .setParameter(4, "dhan")
            .setParameter(5, "Stock List")
            .setParameter(6, "DHAN")
            .setParameter(7, "{\"sourceUrl\":\"" + DHAN_SCRIP_MASTER + "\",\"imported\":" + count + "}")
            .setParameter(8, "completed")
            .setParameter(9, "Dhan stock list refreshed.")
            .setParameter(10, DateUtil.now())
            .setParameter(11, "dhan_instruments")
            .setParameter(12, count)
            .executeUpdate());
        // Recovery: a fresh instrument master may carry corrected security IDs for
        // symbols that were previously marked permanently unfetchable (DH-905 on
        // both quote + historical, usually a stale/wrong securityId). Clear that
        // blacklist so those stocks (e.g. GUJGASLTD / newly-listed VIKRAMSOLR) get
        // one more chance to resolve a base price with the new mapping.
        try {
            int cleared = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(st -> em.createNativeQuery(
                    "UPDATE tracked_stocks SET base_price_source=NULL " +
                    "WHERE base_price_source LIKE 'DHAN_UNFETCHABLE%'").executeUpdate());
            if (cleared > 0) {
                log.info("[Instruments] Cleared DHAN_UNFETCHABLE flag on {} stock(s) after stock-list refresh; they will be retried on the next price refresh.", cleared);
            }
        } catch (Exception e) {
            log.debug("[Instruments] Could not clear unfetchable flags: {}", e.getMessage());
        }
        Map<String, Object> completed = Map.of(
            "status", "completed",
            "source", "DHAN",
            "sourceUrl", DHAN_SCRIP_MASTER,
            "targetTable", "dhan_instruments / stock_master",
            "rowsImported", count,
            "processedRows", count,
            // The row count IS the import count now: streaming never holds the file, so there is
            // no parsed list to measure a separate total against.
            "totalRows", count,
            "currentStep", "Completed",
            "message", "Dhan stock list fetched and " + count + " instrument rows saved."
        );
        instrumentRefreshStatus = completed;
        return completed;
    }

    private Map<String, Object> instrumentStatus(String status, String message, int processed, int total, String step, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("source", "DHAN");
        data.put("dataType", "Stock List");
        data.put("targetTable", "dhan_instruments / broker_instruments / stock_master");
        data.put("processedRows", processed);
        data.put("totalRows", total);
        data.put("currentStep", step);
        data.put("message", message);
        data.put("updatedAt", DateUtil.now());
        if (extra != null) data.putAll(extra);
        return data;
    }

    private boolean isEquityStockInstrument(String exchange, String exchangeSegment, String instrument, String instrumentType) {
        String ex = norm(exchange);
        String seg = norm(exchangeSegment);
        String inst = norm(instrument);
        String type = norm(instrumentType);
        return Set.of("NSE", "BSE").contains(ex)
            && (seg.endsWith("_EQ") || "NSE-EQ".equals(seg) || "BSE-EQ".equals(seg))
            && Set.of("ES", "EQUITY", "EQ").contains(inst)
            && !type.contains("INDEX");
    }

    private String normalizeExchange(String exchange) {
        String ex = norm(exchange);
        if (ex.contains("BSE")) return "BSE";
        return "NSE";
    }

    /**
     * Candle modes this endpoint can actually produce, keyed by the "Type of Data" selection.
     * A {@code null} value means the type is valid here but does not pin the mode, so whatever
     * the Mode dropdown says is used.
     */
    private static final Map<String, String> CANDLE_DATA_TYPES = Map.of(
        "HISTORICAL OHLCV", "DAILY",
        "INTRADAY OHLCV",   "INTRADAY"
    );
    private static final Set<String> MODE_FREE_DATA_TYPES = Set.of("FUTURES DATA");

    /**
     * Where each unsupported "Type of Data" actually comes from. The historical backfill calls
     * one Dhan endpoint - /charts/historical and /charts/intraday - so anything that is not a
     * candle has to be fetched somewhere else, and the message has to say where instead of
     * failing with a bare "unsupported".
     */
    private static final Map<String, String> DATA_TYPE_ELSEWHERE = Map.ofEntries(
        Map.entry("INSTRUMENT MASTER", "Use the Fetch Public Stock List button in this panel - it loads the Dhan instrument master."),
        Map.entry("EQUITY LTP",        "Live prices come from the broker live-price feed, not from historical candles."),
        Map.entry("HOLDINGS",          "Holdings are a portfolio snapshot. Fetch them from the Broker section."),
        Map.entry("POSITIONS",         "Positions are a portfolio snapshot. Fetch them from the Broker section."),
        Map.entry("ORDERS",            "Orders are a portfolio snapshot. Fetch them from the Broker section."),
        Map.entry("TRADES",            "Trades are a portfolio snapshot. Fetch them from the Broker section."),
        Map.entry("FUNDS / MARGIN",    "Funds and margin are a portfolio snapshot. Fetch them from the Broker section."),
        Map.entry("DAILY BHAVCOPY",    "Not available from the Dhan candle API. Use manual upload or the NSE source."),
        Map.entry("F&O BHAVCOPY",      "Not available from the Dhan candle API. Use manual upload or the NSE source."),
        Map.entry("OPTION CHAIN",      "Not available from the Dhan candle API. Use manual upload."),
        Map.entry("INDEX DATA",        "Not available from the Dhan candle API. Use manual upload."),
        Map.entry("CORPORATE ACTIONS", "Not available from the Dhan API."),
        Map.entry("FII/DII DATA",      "Not available from the Dhan API. Use manual upload."),
        Map.entry("DELIVERY VOLUME",   "Not available from the Dhan API. Use manual upload."),
        Map.entry("FUNDAMENTALS",      "Not available from the Dhan API. Use manual upload.")
    );

    /**
     * Validates the "Type of Data" selection against what a candle backfill can do and returns
     * the mode to run in. Throws for anything this endpoint cannot fetch, so the caller sees why
     * nothing happened instead of receiving an unrelated candle job.
     */
    private String applyDataTypeSelection(String dataType, String requestedMode) {
        String key = norm(dataType);
        if (key.isBlank()) return requestedMode;                 // older clients send no dataType
        String pinnedMode = CANDLE_DATA_TYPES.get(key);
        if (pinnedMode != null) {
            if (!pinnedMode.equals(requestedMode)) {
                log.info("[Backfill] Type of Data '{}' pins mode to {} (Mode dropdown said {}).",
                    dataType, pinnedMode, requestedMode);
            }
            return pinnedMode;
        }
        if (MODE_FREE_DATA_TYPES.contains(key)) return requestedMode;
        String where = DATA_TYPE_ELSEWHERE.get(key);
        throw new IllegalStateException(
            "Historical OHLCV Backfill cannot fetch \"" + dataType + "\", so nothing was requested from Dhan. "
          + (where != null ? where : "This panel fetches historical candles only.")
          + " Choose Historical OHLCV, Intraday OHLCV or Futures Data to use this panel.");
    }

    public Map<String, Object> startHistoricalBackfill(User user, Map<String, Object> req) {
        ensureBackfillAuditSchema();
        markStaleJobs(user);
        refuseIfAnotherJobIsRunning();
        Long brokerCredentialId = longValue(req.get("brokerCredentialId"));
        DhanBrokerService.Credential credential = brokerCredentialId == null
            ? dhanBrokerService.activeDhanCredential(user)
            : dhanBrokerService.activeDhanCredential(user, brokerCredentialId);
        // The "Type of Data" dropdown used to be sent and then ignored: whatever was selected,
        // this method ran a historical candle backfill. Picking Holdings or Orders and pressing
        // Start Backfill fetched equity candles instead, with nothing on screen to say so.
        // Honour the selection - drive the mode from it where it implies one, and refuse the
        // types this endpoint cannot fetch rather than quietly doing something else.
        // Default empty, not "Historical OHLCV": StartupSyncService calls this with a mode but
        // no dataType, and one of those runs is INTRADAY. Defaulting to a type that pins DAILY
        // would silently turn the scheduled intraday sync into a daily one. Absent means
        // "no opinion" and the Mode field is left to decide.
        String dataType = String.valueOf(req.getOrDefault("dataType", "")).trim();
        String mode = applyDataTypeSelection(dataType, norm(req.getOrDefault("mode", "DAILY")));
        int interval = intValue(req.get("intervalMinutes"), 1);
        LocalDate requestedFrom = parseDhanUiDate(req.getOrDefault("fromDate", "2020-01-01"), LocalDate.of(2020, 1, 1));
        LocalDate requestedTo = parseDhanUiDate(req.getOrDefault("toDate", LocalDate.now(IST).toString()), LocalDate.now(IST));
        LocalDate fiveYearsAgo = LocalDate.now(IST).minusYears(5);
        LocalDate effectiveFrom = "INTRADAY".equals(mode) && requestedFrom.isBefore(fiveYearsAgo) ? fiveYearsAgo : requestedFrom;
        String exchanges = String.valueOf(req.getOrDefault("exchanges", "NSE"));
        String segments = String.valueOf(req.getOrDefault("segments", "NSE_EQ"));
        String instrumentFilter = String.valueOf(req.getOrDefault("instrumentFilter", "ES"));
        // "Futures Data" is a candle fetch, but only over futures instruments. Left unchecked it
        // runs happily against the default ES filter and downloads equities, which is the same
        // "fetched something other than what was selected" problem one level down.
        if (MODE_FREE_DATA_TYPES.contains(norm(dataType)) && !norm(instrumentFilter).startsWith("FUT")) {
            throw new IllegalStateException(
                "Type of Data is \"" + dataType + "\" but Instrument Filter is \"" + instrumentFilter
              + "\", which selects " + ("ES".equals(norm(instrumentFilter)) ? "equities" : "non-futures instruments")
              + ". Set Instrument Filter to Futures Index, Futures Stock or All Futures, and Segments to an F&O segment.");
        }
        int maxInstruments = intValue(req.get("maxInstruments"), 0);
        // A Data Warehouse fetch populates a whole category by default. Pass scope=TRACKED to
        // restrict it to uploaded symbols (what the startup sync does).
        boolean scopeToTracked = "TRACKED".equalsIgnoreCase(norm(req.getOrDefault("scope", "ALL")));
        long stockListRows = countDhanStockList();
        if (stockListRows == 0) {
            throw new IllegalStateException("Fetch Stock List from Data Warehouse first. Historical backfill uses saved Dhan security IDs from the Stock List.");
        }
        int matchingRows = loadInstruments(exchanges, segments, instrumentFilter, maxInstruments, scopeToTracked).size();

        // Tell the caller what is already stored before spending an hour re-fetching it, and
        // refuse date ranges that cannot return data. Previously every such request was sent
        // to Dhan anyway and came back DH-907 "no data present", which surfaced to the user as
        // hundreds of unexplained "symbol skipped" lines.
        LocalDate lastFetchable = calendar.lastCompletedTradingDay();
        if (effectiveFrom.isAfter(lastFetchable)) {
            throw new IllegalStateException(
                "Nothing to fetch: " + effectiveFrom + " is not a completed trading day yet. "
                + "Daily candles are published after the session settles — the latest fetchable date is "
                + lastFetchable + ".");
        }
        LocalDate cappedTo = requestedTo.isAfter(lastFetchable) ? lastFetchable : requestedTo;
        String coverageReport = describeExistingCoverage(effectiveFrom, cappedTo, matchingRows);

        if (matchingRows == 0) {
            // Name the three values and what each one on its own would have matched. The old
            // message just said "nothing matched", which gave no way to tell a broken Stock List
            // apart from an impossible combination - NSE_EQ with an INDEX filter, say, where the
            // segment holds thousands of instruments but none of them are indices.
            long inSegment = countInstruments(exchanges, segments, "ALL");
            long inFilter  = countInstruments("ALL", "ALL", instrumentFilter);
            throw new IllegalStateException(
                "No Dhan instruments match exchange " + exchanges + ", segment " + segments
              + ", instrument filter " + instrumentFilter + ". That segment holds " + inSegment
              + " instrument(s), and " + inFilter + " instrument(s) anywhere match that filter"
              + (inSegment > 0 && inFilter > 0
                    ? " - so the two are valid on their own but never occur together. Change the"
                      + " Instrument Filter (equities in NSE_EQ are 'ES')."
                    : " - fetch the Stock List first, or pick a different exchange and segment."));
        }

        Long jobId = new TransactionTemplate(transactionManager).execute(status -> {
            return ((Number) em.createNativeQuery(
                "INSERT INTO dhan_historical_jobs(user_id,institution_id,status,mode,interval_minutes,from_date,to_date,requested_from_date,requested_to_date," +
                "exchanges,segments,instrument_filter,message,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id")
                .setParameter(1, user.getId())
                .setParameter(2, user.getInstitutionId())
                .setParameter(3, "queued")
                .setParameter(4, mode)
                .setParameter(5, "INTRADAY".equals(mode) ? interval : null)
                .setParameter(6, effectiveFrom.toString())
                .setParameter(7, cappedTo.toString())
                .setParameter(8, requestedFrom.toString())
                .setParameter(9, requestedTo.toString())
                .setParameter(10, exchanges)
                .setParameter(11, segments)
                .setParameter(12, instrumentFilter)
                .setParameter(13, messageFor(mode, requestedFrom, effectiveFrom))
                .setParameter(14, DateUtil.now())
                .setParameter(15, DateUtil.now())
                .getSingleResult()).longValue();
        });

        // cappedTo, not requestedTo: the cap above exists to stop requests for days the source
        // cannot serve yet, which come back as DH-907 "no data present" for every symbol. It was
        // only ever used for the message, so the whole run still went out uncapped.
        List<DateRange> batches = monthlyBatches(effectiveFrom, cappedTo);
        createBatchRecords(jobId, effectiveFrom, cappedTo);

        Thread worker = new Thread(() -> runJob(jobId, credential, mode, interval, effectiveFrom, cappedTo, exchanges, segments, instrumentFilter, maxInstruments, true, scopeToTracked),
            "dhan-history-job-" + jobId);
        worker.setDaemon(true);
        worker.start();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("status", "started");
        result.put("mode", mode);
        result.put("fromDate", effectiveFrom.toString());
        result.put("toDate", cappedTo.toString());
        result.put("stockListRows", stockListRows);
        result.put("matchedInstruments", matchingRows);
        result.put("totalBatches", batches.size());
        result.put("continueOnMissingData", true);
        result.put("existingCoverage", coverageReport);
        result.put("latestFetchableDate", lastFetchable.toString());
        result.put("message", messageFor(mode, requestedFrom, effectiveFrom) + " Using " + matchingRows + " matching Stock List instrument(s) in " + batches.size() + " monthly batch(es)." + (coverageReport.isEmpty() ? "" : " " + coverageReport));
        return result;
    }

    public Map<String, Object> latestJob(User user) {
        ensureBackfillAuditSchema();
        markStaleJobs(user);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id,status,mode,interval_minutes,from_date,to_date,total_instruments,processed_instruments,total_chunks,processed_chunks," +
            "imported_candles,failed_chunks,current_symbol,message,created_at,updated_at,completed_at,successful_instruments,failed_instruments,skipped_instruments " +
            "FROM dhan_historical_jobs WHERE user_id=? AND COALESCE(dismissed,0)=0 ORDER BY id DESC LIMIT 1")
            .setParameter(1, user.getId())
            .getResultList();
        if (rows.isEmpty()) return Map.of("status", "idle");
        Object[] r = rows.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        String[] keys = {"id","status","mode","intervalMinutes","fromDate","toDate","totalInstruments","processedInstruments","totalChunks","processedChunks","importedCandles","failedChunks","currentSymbol","message","createdAt","updatedAt","completedAt","successfulInstruments","failedInstruments","skippedInstruments"};
        for (int i = 0; i < keys.length; i++) m.put(keys[i], r[i]);
        m.put("requiresConfirmation", false);
        return m;
    }

    public Map<String, Object> stopLatestJob(User user) {
        markStaleJobs(user);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            em.createNativeQuery("UPDATE dhan_historical_jobs SET stop_requested=1,status='stopped',message='Dhan historical backfill stopped by user.',completed_at=?,updated_at=? WHERE user_id=? AND status IN ('queued','running','stopping')")
                .setParameter(1, DateUtil.now())
                .setParameter(2, DateUtil.now())
                .setParameter(3, user.getId())
                .executeUpdate());
        return latestJob(user);
    }

    /**
     * Release a backfill that can no longer be started, without deleting anything.
     *
     * <p>Stop only reaches jobs this user still has in queued/running/stopping. When a start
     * fails after the activity was marked running - a browser closed mid-fetch, a start that
     * threw before the job row was written - there is nothing left for Stop to act on, yet the
     * in-memory activity gate in DataController still answers "running" and refuses every
     * later fetch. Until now the only cure was restarting the application; markStaleJobs only
     * intervenes after {@value #STALE_JOB_MINUTES} minutes and never touches that map.
     *
     * <p>stop_requested is set as well as the status, because a worker thread that is still
     * alive reads that column and not the status - clearing the row alone would leave it
     * fetching against a job the screen believes is finished.
     *
     * <p>Finished jobs are marked dismissed rather than deleted. Clearing the activity flag on
     * its own was not enough: the panel reads the newest job row whatever its status, so a
     * stopped request stayed on screen with its Resume banner and monthly batches - and worse,
     * every poll of /latest wrote that status straight back into the activity map, undoing the
     * clear within a second. The flag takes the row out of both paths at once.
     *
     * <p>Deletes no history: job rows, batches and the per-symbol error log all survive in the
     * database, so nothing recorded about a run is lost by clearing it off the screen.
     */
    public Map<String, Object> cleanBackfillState(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (user == null) {
            out.put("clearedJobs", 0);
            return out;
        }
        markStaleJobs(user);
        int[] cleared = {0};
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            cleared[0] = em.createNativeQuery(
                "UPDATE dhan_historical_jobs SET stop_requested=1, status='stopped', " +
                "message='Cleared from Data Warehouse. A worker still running stops at its next chunk.', " +
                "completed_at=?, updated_at=? WHERE user_id=? AND status IN ('queued','running','stopping')")
                .setParameter(1, DateUtil.now())
                .setParameter(2, DateUtil.now())
                .setParameter(3, user.getId())
                .executeUpdate());
        int[] dismissed = {0};
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            dismissed[0] = em.createNativeQuery(
                "UPDATE dhan_historical_jobs SET dismissed=1, updated_at=? " +
                "WHERE user_id=? AND COALESCE(dismissed,0)=0")
                .setParameter(1, DateUtil.now())
                .setParameter(2, user.getId())
                .executeUpdate());
        out.put("clearedJobs", cleared[0]);
        out.put("dismissedJobs", dismissed[0]);
        out.put("latestJob", latestJob(user));
        return out;
    }

    public byte[] latestErrorLogCsv(User user) {
        ensureBackfillAuditSchema();
        Long jobId = latestJobId(user);
        StringBuilder csv = new StringBuilder();
        csv.append("symbol,securityId,exchangeSegment,instrument,fromDate,toDate,interval,apiUrl,requestBody,errorCode,errorMessage,status,createdAt\n");
        if (jobId == null) return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
            "SELECT symbol,security_id,exchange_segment,instrument,from_date,to_date,interval_minutes,api_url,request_body,error_code,error_message,status,created_at " +
            "FROM dhan_historical_errors WHERE job_id=? ORDER BY id")
            .setParameter(1, jobId)
            .getResultList();
        for (Object[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) csv.append(',');
                csv.append(csvCell(r[i]));
            }
            csv.append('\n');
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> latestSkippedStocks(User user) {
        ensureBackfillAuditSchema();
        Long jobId = latestJobId(user);
        Map<String, Object> result = new LinkedHashMap<>();
        if (jobId == null) {
            result.put("skipped", List.of());
            result.put("failed", List.of());
            result.put("total", 0);
            return result;
        }
        // Joined to the broker's instrument list so the row can name the company, not just the
        // ticker — "VERANDA" alone is not enough for the user to tell which stock was missed,
        // or to work out what the correct name should be.
        List<Object[]> rows = em.createNativeQuery(
            "SELECT e.symbol, MAX(e.exchange_segment), e.status, MAX(e.error_code), MAX(e.error_message), " +
            "MIN(e.from_date), MAX(e.to_date), MAX(e.security_id), COUNT(*), " +
            "MAX(i.display_name), MAX(i.trading_symbol), MAX(i.isin) " +
            "FROM dhan_historical_errors e " +
            "LEFT JOIN dhan_instruments i ON UPPER(TRIM(COALESCE(i.underlying_symbol,'')))=UPPER(TRIM(COALESCE(e.symbol,''))) " +
            "AND i.exchange_segment=e.exchange_segment " +
            "WHERE e.job_id=? GROUP BY e.symbol, e.status ORDER BY e.symbol")
            .setParameter(1, jobId)
            .getResultList();
        List<Map<String, String>> skipped = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();
        for (Object[] r : rows) {
            String code = str(r[3]);
            String rawMessage = str(r[4]);
            String from = str(r[5]);
            String to = str(r[6]);
            int attempts = toInt(r[8]);
            String name = firstNonBlank(str(r[9]), str(r[10]));

            Map<String, String> m = new LinkedHashMap<>();
            m.put("symbol", str(r[0]));
            m.put("stockName", name.isEmpty() ? "Not in the broker's stock list" : name);
            m.put("exchange", str(r[1]));
            m.put("errorCode", code);
            m.put("securityId", str(r[7]));
            m.put("isin", str(r[11]));
            m.put("dateRange", from.isEmpty() && to.isEmpty() ? "" : from + " to " + to);
            m.put("attempts", String.valueOf(attempts));
            m.put("reason", friendlySkipReason(code, rawMessage));
            m.put("remark", skipRemark(code, rawMessage, name, from, to, attempts));
            m.put("brokerMessage", rawMessage);
            if ("SKIPPED".equals(str(r[2]))) skipped.add(m);
            else failed.add(m);
        }
        result.put("skipped", skipped);
        result.put("failed", failed);
        result.put("total", skipped.size() + failed.size());
        result.put("jobId", jobId);
        return result;
    }

    /**
     * A full sentence the user can act on: what was asked for, what came back, and what to do.
     * The short {@code reason} says what happened; this says why it matters and what fixes it.
     */
    private String skipRemark(String errorCode, String errorMessage, String stockName,
                              String from, String to, int attempts) {
        StringBuilder b = new StringBuilder();
        String window = from.isEmpty() && to.isEmpty() ? "the requested period" : from + " to " + to;

        if ("NO_DATA".equals(errorCode) || "DH-905".equals(errorCode) || "DH-907".equals(errorCode)) {
            b.append("The broker holds no price history for this stock between ").append(window).append(". ");
            if (stockName == null || stockName.isBlank()) {
                b.append("It is also not in the broker's current stock list, which usually means the ")
                 .append("ticker has been renamed or the company has been delisted. ")
                 .append("Enter the correct name, ISIN, or security ID to fetch it.");
            } else {
                b.append("Common causes are a listing date after this period, a suspension, or a rename. ")
                 .append("If you know the stock trades under another name, enter it to fetch again.");
            }
        } else if ("DH-901".equals(errorCode)) {
            b.append("The broker rejected the request because the Dhan token was expired or invalid. ")
             .append("Reconnect the broker on the Broker screen, then retry — nothing is wrong with this stock.");
        } else if ("VALIDATION".equals(errorCode)) {
            b.append("The broker's stock list marks this entry as a test or placeholder instrument, ")
             .append("so no history was requested for it. No action needed.");
        } else if (errorCode != null && errorCode.startsWith("DH-")) {
            b.append("The broker returned error ").append(errorCode).append(" for ").append(window).append(". ");
            if (errorMessage != null && !errorMessage.isBlank()) b.append("It said: ").append(errorMessage).append(". ");
            b.append("Retry once; if it repeats, the stock may need a corrected name.");
        } else {
            b.append("The request for ").append(window).append(" did not complete. ");
            if (errorMessage != null && !errorMessage.isBlank()) b.append(errorMessage).append(". ");
            b.append("Retry, and supply a corrected name if it keeps failing.");
        }
        if (attempts > 1) b.append(" Tried ").append(attempts).append(" times.");
        return b.toString();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b == null ? "" : b;
    }

    private String friendlySkipReason(String errorCode, String errorMessage) {
        if (errorCode == null) errorCode = "";
        if (errorMessage == null) errorMessage = "";
        if ("DH-905".equals(errorCode) || "NO_DATA".equals(errorCode))
            return "No historical data available from Dhan for the selected date range.";
        if ("VALIDATION".equals(errorCode))
            return "This instrument was identified as a test or invalid entry in the Dhan stock list.";
        if (errorCode.startsWith("DH-"))
            return "Dhan API returned an error (" + errorCode + "). This may be a temporary issue — try again later.";
        if (errorMessage.contains("securityId"))
            return "Missing or invalid security ID mapping for this instrument.";
        if (errorMessage.contains("exchangeSegment"))
            return "Invalid exchange segment for this instrument.";
        if (errorMessage.contains("timeout") || errorMessage.contains("timed out"))
            return "Request timed out while fetching data. The Dhan API may be slow — try again later.";
        if (errorMessage.length() > 200)
            return "An unexpected error occurred while fetching data for this instrument.";
        return errorMessage.isEmpty() ? "Unknown error during data fetch." : errorMessage;
    }

    public Map<String, Object> retryFailed(User user) {
        ensureBackfillAuditSchema();
        markStaleJobs(user);
        Long previousJobId = latestJobId(user);
        if (previousJobId == null) throw new IllegalStateException("No Dhan historical backfill job found to retry.");
        @SuppressWarnings("unchecked")
        List<Object[]> configRows = em.createNativeQuery(
            "SELECT mode,interval_minutes,from_date,to_date,exchanges,segments,instrument_filter FROM dhan_historical_jobs WHERE id=?")
            .setParameter(1, previousJobId)
            .getResultList();
        if (configRows.isEmpty()) throw new IllegalStateException("Previous Dhan historical job could not be found.");
        Object[] cfg = configRows.get(0);
        String mode = norm(cfg[0]);
        int interval = cfg[1] == null ? 1 : ((Number) cfg[1]).intValue();
        LocalDate from = parseDhanUiDate(cfg[2], LocalDate.of(2020, 1, 1));
        LocalDate to = parseDhanUiDate(cfg[3], LocalDate.now(IST));
        List<Instrument> instruments = loadRetryInstruments(previousJobId, String.valueOf(cfg[4]), String.valueOf(cfg[5]), String.valueOf(cfg[6]));
        if (instruments.isEmpty()) throw new IllegalStateException("No failed, skipped, or unprocessed Dhan symbols found for retry.");
        DhanBrokerService.Credential credential = dhanBrokerService.activeDhanCredential(user);
        Long jobId = createRetryJob(user, mode, interval, from, to, String.valueOf(cfg[4]), String.valueOf(cfg[5]), String.valueOf(cfg[6]), previousJobId);
        Thread worker = new Thread(() -> runJobWithInstruments(jobId, credential, mode, interval, from, to, instruments),
            "dhan-history-retry-" + jobId);
        worker.setDaemon(true);
        worker.start();
        return Map.of(
            "jobId", jobId,
            "status", "started",
            "retryOfJobId", previousJobId,
            "matchedInstruments", instruments.size(),
            "message", "Retry started for " + instruments.size() + " failed, skipped, or unprocessed Dhan symbols."
        );
    }

    /**
     * Re-fetches history for symbols Dhan skipped or failed on, across EVERY job of this user
     * — not just the most recent one, which is all {@link #retryFailed(User)} covers. Skips
     * accumulate per job (DH-904 / DH-905 / no-data), so without this they stay stranded as
     * soon as another backfill has run.
     *
     * @param symbols  explicit symbols to re-fetch; null/empty means "every skipped or failed
     *                 symbol on record". Named symbols are also resolved from the instrument
     *                 master when they were never logged as an error, so a gap left by
     *                 something other than Dhan (an aborted write, say) can be refilled too.
     * @param fromDate optional yyyy-MM-dd override; defaults to the latest job's range.
     * @param toDate   optional yyyy-MM-dd override; defaults to the latest job's range.
     */
    /**
     * Every stock/date the backfill could not store, as plain comma-separated text.
     *
     * One row per symbol per date range, so it can be edited in Notepad or Excel and fed
     * straight back to {@link #fetchFromList}. Deliberately narrow - symbol, dates, why -
     * because the wide diagnostic CSV is awkward to hand-edit.
     */
    public String missingDataCsv(User user) {
        ensureBackfillAuditSchema();
        List<Object[]> rows = em.createNativeQuery(
            "SELECT e.symbol, MIN(e.from_date), MAX(e.to_date), MAX(e.status), " +
            "MAX(e.error_code), MAX(e.error_message), COUNT(*) " +
            "FROM dhan_historical_errors e JOIN dhan_historical_jobs j ON j.id=e.job_id " +
            "WHERE j.user_id=? AND e.status IN ('FAILED','SKIPPED') " +
            "GROUP BY e.symbol ORDER BY e.symbol")
            .setParameter(1, user.getId())
            .getResultList();

        StringBuilder csv = new StringBuilder();
        csv.append("symbol,from_date,to_date,status,error_code,reason,attempts\n");
        for (Object[] r : rows) {
            csv.append(csvCell(r[0])).append(',')
               .append(csvCell(r[1])).append(',')
               .append(csvCell(r[2])).append(',')
               .append(csvCell(r[3])).append(',')
               .append(csvCell(r[4])).append(',')
               .append(csvCell(r[5])).append(',')
               .append(csvCell(r[6])).append('\n');
        }
        return csv.toString();
    }

    /**
     * Re-fetch exactly the stocks and dates listed in an uploaded file.
     *
     * Accepts "symbol", "symbol,date" or "symbol,from,to" per line, with or without the
     * header the download produces. Blank lines and lines starting with # are ignored, so
     * the exported file can be uploaded back unedited.
     *
     * Rows may carry different date ranges; the fetch covers the union of them and relies
     * on the candle upsert to leave already-stored days untouched. That keeps this to a
     * single job rather than one per range, which the activity lock would reject anyway.
     */
    public Map<String, Object> fetchFromList(User user, String fileContent) {
        if (fileContent == null || fileContent.isBlank()) {
            throw new IllegalStateException("The uploaded file is empty.");
        }
        List<String> symbols = new ArrayList<>();
        LocalDate min = null, max = null;
        int ignored = 0;

        for (String raw : fileContent.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] cols = line.split(",");
            String sym = norm(cols[0].replace("\"", ""));
            if (sym.isEmpty() || sym.equalsIgnoreCase("symbol")) { ignored++; continue; }
            symbols.add(sym);
            for (int c = 1; c <= 2 && c < cols.length; c++) {
                String cell = cols[c].replace("\"", "").trim();
                if (cell.isEmpty()) continue;
                try {
                    LocalDate d = parseDhanUiDate(cell, null);
                    if (d == null) continue;
                    if (min == null || d.isBefore(min)) min = d;
                    if (max == null || d.isAfter(max)) max = d;
                } catch (Exception ignore) { /* a non-date column is not an error */ }
            }
        }
        if (symbols.isEmpty()) {
            throw new IllegalStateException(
                "No stock symbols found in the file. Expected one symbol per line, "
              + "optionally followed by a date or a from,to pair.");
        }
        log.info("[Backfill] Uploaded list: {} symbol(s), dates {} to {}, {} line(s) ignored.",
            symbols.size(), min, max, ignored);

        Map<String, Object> result = retrySkipped(user, symbols,
            min == null ? null : min.toString(), max == null ? null : max.toString());
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("symbols_requested", symbols.size());
        out.put("from_date", min == null ? "job default" : min.toString());
        out.put("to_date", max == null ? "job default" : max.toString());
        out.put("lines_ignored", ignored);
        return out;
    }

    public Map<String, Object> retrySkipped(User user, List<String> symbols, String fromDate, String toDate) {
        ensureBackfillAuditSchema();
        markStaleJobs(user);
        Long previousJobId = latestJobId(user);
        if (previousJobId == null) throw new IllegalStateException("No Dhan historical backfill job found to retry.");

        Object[] cfg = jobConfig(previousJobId);
        String mode = norm(cfg[0]);
        int interval = cfg[1] == null ? 1 : ((Number) cfg[1]).intValue();
        LocalDate from = fromDate != null && !fromDate.isBlank()
            ? parseDhanUiDate(fromDate, LocalDate.of(2020, 1, 1))
            : parseDhanUiDate(cfg[2], LocalDate.of(2020, 1, 1));
        LocalDate to = toDate != null && !toDate.isBlank()
            ? parseDhanUiDate(toDate, LocalDate.now(IST))
            : parseDhanUiDate(cfg[3], LocalDate.now(IST));
        if (to.isBefore(from)) throw new IllegalStateException("toDate cannot be earlier than fromDate.");

        Set<String> wanted = new LinkedHashSet<>();
        if (symbols != null) {
            for (String sym : symbols) {
                String cleaned = norm(sym);
                if (!cleaned.isEmpty()) wanted.add(cleaned);
            }
        }

        Map<String, Instrument> retry = new LinkedHashMap<>();
        for (Instrument instrument : loadSkippedInstruments(user, wanted)) {
            retry.putIfAbsent(instrumentKey(instrument), instrument);
        }
        // Named symbols with no error-log row: resolve them from the instrument master.
        if (!wanted.isEmpty()) {
            Set<String> found = new HashSet<>();
            for (Instrument i : retry.values()) found.add(norm(i.symbol()));
            Set<String> missing = new LinkedHashSet<>(wanted);
            missing.removeAll(found);
            if (!missing.isEmpty()) {
                for (Instrument instrument : loadInstruments(String.valueOf(cfg[4]), String.valueOf(cfg[5]),
                        String.valueOf(cfg[6]), 0)) {
                    if (missing.contains(norm(instrument.symbol()))) {
                        retry.putIfAbsent(instrumentKey(instrument), instrument);
                    }
                }
            }
        }

        List<Instrument> instruments = new ArrayList<>(retry.values());
        if (instruments.isEmpty()) {
            throw new IllegalStateException(wanted.isEmpty()
                ? "No skipped or failed Dhan symbols found to re-fetch."
                : "None of the requested symbols were found in the error log or the Dhan instrument master.");
        }

        // A symbol blacklisted as permanently unfetchable stays excluded from the base-price
        // fetch even once its candles land, so clear that flag for what we are about to retry.
        clearUnfetchableFlag(instruments);

        DhanBrokerService.Credential credential = dhanBrokerService.activeDhanCredential(user);
        Long jobId = createRetryJob(user, mode, interval, from, to,
            String.valueOf(cfg[4]), String.valueOf(cfg[5]), String.valueOf(cfg[6]), previousJobId);
        Thread worker = new Thread(
            () -> runJobWithInstruments(jobId, credential, mode, interval, from, to, instruments),
            "dhan-history-skipped-" + jobId);
        worker.setDaemon(true);
        worker.start();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("status", "started");
        result.put("retryOfJobId", previousJobId);
        result.put("matchedInstruments", instruments.size());
        result.put("requestedSymbols", wanted.size());
        result.put("fromDate", from.toString());
        result.put("toDate", to.toString());
        result.put("message", "Re-fetch started for " + instruments.size() + " skipped symbol(s), "
            + from + " to " + to + ".");
        return result;
    }

    /** Skipped/failed symbols on record for this user, newest job first, with counts. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> skippedSymbolSummary(User user) {
        ensureBackfillAuditSchema();
        List<Object[]> rows = em.createNativeQuery(
            "SELECT e.symbol, e.error_code, MAX(e.status) AS status, COUNT(*) AS attempts, " +
            "MAX(e.job_id) AS last_job_id, MAX(e.created_at) AS last_seen, MAX(e.error_message) AS last_message " +
            "FROM dhan_historical_errors e JOIN dhan_historical_jobs j ON j.id=e.job_id " +
            "WHERE j.user_id=? AND e.status IN ('FAILED','SKIPPED') " +
            "GROUP BY e.symbol, e.error_code ORDER BY MAX(e.created_at) DESC, e.symbol")
            .setParameter(1, user.getId())
            .getResultList();

        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> distinct = new LinkedHashSet<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", r[0]);
            m.put("errorCode", r[1]);
            m.put("status", r[2]);
            m.put("attempts", r[3] == null ? 0 : ((Number) r[3]).intValue());
            m.put("lastJobId", r[4]);
            m.put("lastSeen", r[5]);
            m.put("reason", friendlySkipReason(String.valueOf(r[1] == null ? "" : r[1]),
                                              String.valueOf(r[6] == null ? "" : r[6])));
            items.add(m);
            if (r[0] != null) distinct.add(norm(r[0]));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("distinctSymbols", distinct.size());
        out.put("rows", items.size());
        out.put("symbols", new ArrayList<>(distinct));
        out.put("items", items);
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SKIPPED-SYMBOL CORRECTIONS
    //  A stock is skipped when Dhan has no history under the name we asked for — most often
    //  because the ticker was renamed, or the uploaded list carries a slightly different name.
    //  The user downloads the skipped list, fills in the right name (or ISIN, or security id),
    //  uploads it back, and the re-fetch then asks the broker using the corrected identity.
    // ─────────────────────────────────────────────────────────────────────────

    /** The skipped/failed list as CSV, with blank correction columns for the user to fill in. */
    @SuppressWarnings("unchecked")
    public String skippedSymbolCsv(User user) {
        ensureBackfillAuditSchema();
        List<Object[]> rows = em.createNativeQuery(
            "SELECT e.symbol, MAX(e.status), MAX(e.error_code), COUNT(*), MAX(e.created_at), " +
            "MAX(e.security_id), MAX(e.exchange_segment), MAX(e.error_message), " +
            "MAX(o.corrected_symbol), MAX(o.isin), MAX(o.security_id), MAX(o.resolved), " +
            "MAX(i.display_name), MAX(i.trading_symbol), MAX(i.isin), MIN(e.from_date), MAX(e.to_date) " +
            "FROM dhan_historical_errors e JOIN dhan_historical_jobs j ON j.id=e.job_id " +
            "LEFT JOIN dhan_symbol_overrides o ON o.user_id=j.user_id " +
            "AND UPPER(TRIM(o.original_symbol))=UPPER(TRIM(e.symbol)) " +
            "LEFT JOIN dhan_instruments i ON UPPER(TRIM(COALESCE(i.underlying_symbol,'')))=UPPER(TRIM(COALESCE(e.symbol,''))) " +
            "AND i.exchange_segment=e.exchange_segment " +
            "WHERE j.user_id=? AND e.status IN ('FAILED','SKIPPED') " +
            "GROUP BY e.symbol ORDER BY e.symbol")
            .setParameter(1, user.getId())
            .getResultList();

        StringBuilder csv = new StringBuilder();
        csv.append("Original Symbol,Stock Name,ISIN,Status,Reason,Remarks,Error Code,Attempts,")
           .append("Date Range,Last Seen,Dhan Security ID,Exchange Segment,")
           .append("Corrected Symbol,Corrected ISIN,Corrected Security ID,Already Resolved\n");
        for (Object[] r : rows) {
            String code = str(r[2]);
            String rawMessage = str(r[7]);
            String name = firstNonBlank(str(r[12]), str(r[13]));
            String from = str(r[15]);
            String to = str(r[16]);
            csv.append(csvCell(r[0])).append(',')
               .append(csvCell(name.isEmpty() ? "Not in the broker's stock list" : name)).append(',')
               .append(csvCell(r[14])).append(',')
               .append(csvCell(r[1])).append(',')
               .append(csvCell(friendlySkipReason(code, rawMessage))).append(',')
               .append(csvCell(skipRemark(code, rawMessage, name, from, to, toInt(r[3])))).append(',')
               .append(csvCell(code)).append(',')
               .append(csvCell(r[3])).append(',')
               .append(csvCell(from.isEmpty() && to.isEmpty() ? "" : from + " to " + to)).append(',')
               .append(csvCell(r[4])).append(',')
               .append(csvCell(r[5])).append(',')
               .append(csvCell(r[6])).append(',')
               .append(csvCell(r[8])).append(',')
               .append(csvCell(r[9])).append(',')
               .append(csvCell(r[10])).append(',')
               .append(toInt(r[11]) == 1 ? "YES" : "NO")
               .append('\n');
        }
        return csv.toString();
    }

    /** Corrections already on file for this user, newest first. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSymbolOverrides(User user) {
        ensureBackfillAuditSchema();
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id, original_symbol, corrected_symbol, isin, security_id, exchange_segment, " +
            "instrument, matched_name, resolved, resolve_note, updated_at " +
            "FROM dhan_symbol_overrides WHERE user_id=? ORDER BY resolved, original_symbol")
            .setParameter(1, user.getId())
            .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]);
            m.put("originalSymbol", r[1]);
            m.put("correctedSymbol", r[2]);
            m.put("isin", r[3]);
            m.put("securityId", r[4]);
            m.put("exchangeSegment", r[5]);
            m.put("instrument", r[6]);
            m.put("matchedName", r[7]);
            m.put("resolved", toInt(r[8]) == 1);
            m.put("note", r[9]);
            m.put("updatedAt", r[10]);
            out.add(m);
        }
        return out;
    }

    /**
     * Stores corrections and immediately tries to match each one against the Dhan instrument
     * master. Reporting the match back straight away matters: an unresolved correction would
     * otherwise fail silently later, during a long re-fetch.
     */
    public Map<String, Object> saveSymbolOverrides(User user, List<Map<String, Object>> entries) {
        ensureBackfillAuditSchema();
        int saved = 0, resolved = 0, unresolved = 0;
        List<String> problems = new ArrayList<>();

        for (Map<String, Object> e : entries == null ? List.<Map<String, Object>>of() : entries) {
            String original = norm(str(e.get("originalSymbol")));
            if (original.isEmpty()) continue;
            String corrected = norm(str(e.get("correctedSymbol")));
            String isin = norm(str(e.get("isin")));
            String secId = norm(str(e.get("securityId")));
            if (corrected.isEmpty() && isin.isEmpty() && secId.isEmpty()) continue;

            Object[] hit = findInstrument(corrected, isin, secId);
            boolean ok = hit != null;
            if (ok) resolved++; else { unresolved++; problems.add(original); }

            final String note = ok
                ? "Matched to " + str(hit[4]) + " (" + str(hit[1]) + ")"
                : "No stock in the Dhan list matches that name, ISIN, or security ID.";
            final String fSecId = ok ? str(hit[0]) : secId;
            final String fSeg = ok ? str(hit[1]) : "";
            final String fInst = ok ? str(hit[2]) : "";
            final String fName = ok ? str(hit[4]) : "";
            final String fCorrected = corrected.isEmpty() && ok ? str(hit[3]) : corrected;

            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                int updated = em.createNativeQuery(
                    "UPDATE dhan_symbol_overrides SET corrected_symbol=?, isin=?, security_id=?, " +
                    "exchange_segment=?, instrument=?, matched_name=?, resolved=?, resolve_note=?, updated_at=? " +
                    "WHERE user_id=? AND UPPER(TRIM(original_symbol))=?")
                    .setParameter(1, fCorrected).setParameter(2, isin).setParameter(3, fSecId)
                    .setParameter(4, fSeg).setParameter(5, fInst).setParameter(6, fName)
                    .setParameter(7, ok ? 1 : 0).setParameter(8, note).setParameter(9, DateUtil.now())
                    .setParameter(10, user.getId()).setParameter(11, original)
                    .executeUpdate();
                if (updated == 0) {
                    em.createNativeQuery(
                        "INSERT INTO dhan_symbol_overrides(user_id,original_symbol,corrected_symbol,isin," +
                        "security_id,exchange_segment,instrument,matched_name,resolved,resolve_note,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
                        .setParameter(1, user.getId()).setParameter(2, original).setParameter(3, fCorrected)
                        .setParameter(4, isin).setParameter(5, fSecId).setParameter(6, fSeg)
                        .setParameter(7, fInst).setParameter(8, fName).setParameter(9, ok ? 1 : 0)
                        .setParameter(10, note).setParameter(11, DateUtil.now()).setParameter(12, DateUtil.now())
                        .executeUpdate();
                }
            });
            saved++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", saved);
        out.put("resolved", resolved);
        out.put("unresolved", unresolved);
        out.put("unresolvedSymbols", problems);
        out.put("message", saved == 0
            ? "Nothing to save - fill in a corrected symbol, ISIN, or security ID first."
            : resolved + " of " + saved + " correction(s) matched a stock at the broker."
              + (unresolved > 0 ? " " + unresolved + " could not be matched and will be left out of the re-fetch." : ""));
        return out;
    }

    public int deleteSymbolOverride(User user, Long id) {
        ensureBackfillAuditSchema();
        Integer n = new TransactionTemplate(transactionManager).execute(st ->
            em.createNativeQuery("DELETE FROM dhan_symbol_overrides WHERE id=? AND user_id=?")
                .setParameter(1, id).setParameter(2, user.getId()).executeUpdate());
        return n == null ? 0 : n;
    }

    /**
     * Finds one equity in the Dhan instrument master from whatever the user supplied — ticker,
     * full name, ISIN, or security id. NSE wins over BSE when both list the same company.
     */
    @SuppressWarnings("unchecked")
    private Object[] findInstrument(String symbol, String isin, String securityId) {
        String sym = symbol == null ? "" : symbol;
        String isn = isin == null ? "" : isin;
        String sid = securityId == null ? "" : securityId;
        if (sym.isEmpty() && isn.isEmpty() && sid.isEmpty()) return null;
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT security_id, exchange_segment, instrument, underlying_symbol, trading_symbol " +
                "FROM dhan_instruments " +
                "WHERE exchange_segment IN ('NSE_EQ','BSE_EQ') AND (" +
                "  (?<>'' AND (UPPER(TRIM(underlying_symbol))=? OR UPPER(TRIM(trading_symbol))=? OR UPPER(TRIM(display_name))=?)) " +
                "  OR (?<>'' AND UPPER(TRIM(COALESCE(isin,'')))=?) " +
                "  OR (?<>'' AND TRIM(security_id)=?)) " +
                "ORDER BY CASE WHEN exchange_segment='NSE_EQ' THEN 0 ELSE 1 END, underlying_symbol LIMIT 1")
                .setParameter(1, sym).setParameter(2, sym).setParameter(3, sym).setParameter(4, sym)
                .setParameter(5, isn).setParameter(6, isn)
                .setParameter(7, sid).setParameter(8, sid)
                .getResultList();
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("Instrument lookup failed for {}/{}/{}: {}", sym, isn, sid, e.getMessage());
            return null;
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    private Object[] jobConfig(Long jobId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
            "SELECT mode,interval_minutes,from_date,to_date,exchanges,segments,instrument_filter " +
            "FROM dhan_historical_jobs WHERE id=?")
            .setParameter(1, jobId)
            .getResultList();
        if (rows.isEmpty()) throw new IllegalStateException("Previous Dhan historical job could not be found.");
        return rows.get(0);
    }

    /** Distinct FAILED/SKIPPED instruments across every job belonging to this user. */
    @SuppressWarnings("unchecked")
    private List<Instrument> loadSkippedInstruments(User user, Set<String> wanted) {
        StringBuilder sql = new StringBuilder(
            "SELECT DISTINCT e.security_id,e.exchange_segment,e.instrument,e.symbol " +
            "FROM dhan_historical_errors e JOIN dhan_historical_jobs j ON j.id=e.job_id " +
            "WHERE j.user_id=? AND e.status IN ('FAILED','SKIPPED')");
        if (!wanted.isEmpty()) {
            sql.append(" AND UPPER(TRIM(COALESCE(e.symbol,''))) IN (");
            for (int i = 0; i < wanted.size(); i++) sql.append(i == 0 ? "?" : ",?");
            sql.append(")");
        }
        sql.append(" ORDER BY e.symbol");

        var query = em.createNativeQuery(sql.toString()).setParameter(1, user.getId());
        int idx = 2;
        for (String sym : wanted) query.setParameter(idx++, sym);

        // Corrections the user supplied for stocks the broker could not match by name. Applying
        // them here means the retry asks Dhan using the corrected identity rather than the one
        // that already came back empty.
        Map<String, Object[]> overrides = resolvedOverrides(user);

        List<Instrument> instruments = new ArrayList<>();
        for (Object[] r : (List<Object[]>) query.getResultList()) {
            String original = norm(r[3]);
            Object[] fix = overrides.get(original);
            Instrument instrument = fix != null
                ? new Instrument(str(fix[0]), exchangeFromSegment(str(fix[1])),
                    normalizeExchangeSegment(str(fix[1])), normalizeInstrument(str(fix[2])),
                    str(fix[3]).isEmpty() ? String.valueOf(r[3]) : str(fix[3]), str(fix[3]))
                : new Instrument(String.valueOf(r[0]), exchangeFromSegment(String.valueOf(r[1])),
                    normalizeExchangeSegment(String.valueOf(r[1])), normalizeInstrument(String.valueOf(r[2])),
                    String.valueOf(r[3]), String.valueOf(r[3]));
            if (isValidDhanInstrument(instrument)) instruments.add(instrument);
        }
        if (!overrides.isEmpty()) {
            log.info("[Backfill] Applied {} user correction(s) to the skipped-symbol re-fetch.", overrides.size());
        }
        return instruments;
    }

    /** Resolved corrections keyed by the original (skipped) symbol, upper-cased. */
    @SuppressWarnings("unchecked")
    private Map<String, Object[]> resolvedOverrides(User user) {
        Map<String, Object[]> out = new LinkedHashMap<>();
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT original_symbol, security_id, exchange_segment, instrument, corrected_symbol " +
                "FROM dhan_symbol_overrides WHERE user_id=? AND resolved=1 " +
                "AND COALESCE(security_id,'')<>''")
                .setParameter(1, user.getId())
                .getResultList();
            for (Object[] r : rows) {
                out.put(norm(r[0]), new Object[] { r[1], r[2], r[3], r[4] });
            }
        } catch (Exception e) {
            log.debug("Could not load symbol overrides: {}", e.getMessage());
        }
        return out;
    }

    private void clearUnfetchableFlag(List<Instrument> instruments) {
        List<String> symbols = new ArrayList<>(new LinkedHashSet<>(
            instruments.stream().map(i -> norm(i.symbol())).filter(sym -> !sym.isEmpty()).toList()));
        if (symbols.isEmpty()) return;
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < symbols.size(); i++) in.append(i == 0 ? "?" : ",?");
        try {
            Integer cleared = new TransactionTemplate(transactionManager).execute(st -> {
                var q = em.createNativeQuery(
                    "UPDATE tracked_stocks SET base_price_source=NULL " +
                    "WHERE base_price_source LIKE 'DHAN_UNFETCHABLE%' " +
                    "AND UPPER(TRIM(COALESCE(symbol,''))) IN (" + in + ")");
                for (int i = 0; i < symbols.size(); i++) q.setParameter(i + 1, symbols.get(i));
                return q.executeUpdate();
            });
            if (cleared != null && cleared > 0) {
                log.info("[Backfill] Cleared DHAN_UNFETCHABLE on {} stock(s) queued for skipped-symbol re-fetch.", cleared);
            }
        } catch (Exception e) {
            log.debug("[Backfill] Could not clear unfetchable flags for retry: {}", e.getMessage());
        }
    }

    /**
     * Human-readable summary of what is already stored for a date range, so the caller can see
     * "1,180 of 1,200 already present" instead of watching the job skip them one by one.
     */
    @SuppressWarnings("unchecked")
    private String describeExistingCoverage(LocalDate from, LocalDate to, int instrumentCount) {
        try {
            List<LocalDate> tradingDays = calendar.tradingDays(from, to);
            if (tradingDays.isEmpty()) return "No trading days in the selected range.";
            int expected = tradingDays.size() * Math.max(1, instrumentCount);

            Object existing = em.createNativeQuery(
                "SELECT COUNT(*) FROM broker_market_candles " +
                "WHERE candle_mode='DAILY' AND trade_date BETWEEN ? AND ?")
                .setParameter(1, from.toString())
                .setParameter(2, to.toString())
                .getSingleResult();
            long have = existing == null ? 0 : ((Number) existing).longValue();
            long missing = Math.max(0, expected - have);
            int pct = expected == 0 ? 100 : (int) Math.min(100, (have * 100) / expected);

            if (missing == 0) {
                return "All " + expected + " expected candles for " + from + " to " + to
                     + " are already stored — nothing new to fetch.";
            }
            return have + " of " + expected + " candles already stored (" + pct + "%); "
                 + "fetching the remaining " + missing + " across " + tradingDays.size() + " trading day(s).";
        } catch (Exception e) {
            log.debug("Coverage summary skipped: {}", e.getMessage());
            return "";
        }
    }

    private Long latestJobId(User user) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("SELECT id FROM dhan_historical_jobs WHERE user_id=? AND COALESCE(dismissed,0)=0 ORDER BY id DESC LIMIT 1")
            .setParameter(1, user.getId())
            .getResultList();
        return rows.isEmpty() ? null : ((Number) rows.get(0)).longValue();
    }

    private Long createRetryJob(User user, String mode, int interval, LocalDate from, LocalDate to,
                                String exchanges, String segments, String instrumentFilter, Long retryOfJobId) {
        return new TransactionTemplate(transactionManager).execute(status ->
            ((Number) em.createNativeQuery(
                "INSERT INTO dhan_historical_jobs(user_id,institution_id,status,mode,interval_minutes,from_date,to_date,requested_from_date,requested_to_date," +
                "exchanges,segments,instrument_filter,message,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id")
                .setParameter(1, user.getId())
                .setParameter(2, user.getInstitutionId())
                .setParameter(3, "queued")
                .setParameter(4, mode)
                .setParameter(5, "INTRADAY".equals(mode) ? interval : null)
                .setParameter(6, from.toString())
                .setParameter(7, to.toString())
                .setParameter(8, from.toString())
                .setParameter(9, to.toString())
                .setParameter(10, exchanges)
                .setParameter(11, segments)
                .setParameter(12, instrumentFilter)
                .setParameter(13, "Retry queued for failed/skipped/unprocessed Dhan historical symbols from job " + retryOfJobId + ".")
                .setParameter(14, DateUtil.now())
                .setParameter(15, DateUtil.now())
                .getSingleResult()).longValue());
    }

    @SuppressWarnings("unchecked")
    private List<Instrument> loadFailedInstruments(Long jobId) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT DISTINCT security_id,exchange_segment,instrument,symbol FROM dhan_historical_errors " +
            "WHERE job_id=? AND status IN ('FAILED','SKIPPED') ORDER BY symbol")
            .setParameter(1, jobId)
            .getResultList();
        List<Instrument> instruments = new ArrayList<>();
        for (Object[] r : rows) {
            Instrument instrument = new Instrument(String.valueOf(r[0]), exchangeFromSegment(String.valueOf(r[1])),
                normalizeExchangeSegment(String.valueOf(r[1])), normalizeInstrument(String.valueOf(r[2])),
                String.valueOf(r[3]), String.valueOf(r[3]));
            if (isValidDhanInstrument(instrument)) instruments.add(instrument);
        }
        return instruments;
    }

    private List<Instrument> loadRetryInstruments(Long previousJobId, String exchanges, String segments, String instrumentFilter) {
        Map<String, Instrument> retry = new LinkedHashMap<>();
        for (Instrument instrument : loadFailedInstruments(previousJobId)) {
            retry.putIfAbsent(instrumentKey(instrument), instrument);
        }
        int processed = processedInstrumentCount(previousJobId);
        List<Instrument> all = loadInstruments(exchanges, segments, instrumentFilter, 0);
        for (int i = Math.max(0, processed); i < all.size(); i++) {
            Instrument instrument = all.get(i);
            retry.putIfAbsent(instrumentKey(instrument), instrument);
        }
        return new ArrayList<>(retry.values());
    }

    private int processedInstrumentCount(Long jobId) {
        try {
            Object value = em.createNativeQuery("SELECT COALESCE(processed_instruments,0) FROM dhan_historical_jobs WHERE id=?")
                .setParameter(1, jobId)
                .getSingleResult();
            return value == null ? 0 : ((Number) value).intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    private String instrumentKey(Instrument instrument) {
        return normalizeExchangeSegment(instrument.exchangeSegment()) + ":" + instrument.securityId();
    }

    /**
     * True when this instrument already has candles covering the batch window, so the fetch can be
     * skipped. Deliberately conservative: it only reports true when rows exist for both ends of the
     * range, so a partially-fetched instrument is still re-fetched rather than silently left short.
     */
    /**
     * Whether this instrument's candles for a window are already stored, month by month.
     *
     * <p>Coverage is judged per calendar month inside the window, not once across the whole of it.
     * The 80% rule used to be applied to the window as a single span, which was equivalent while a
     * window was one calendar month - but it also meant the window width silently set the
     * resolution at which a gap could be seen. At a 365-day window an instrument missing one whole
     * month still reads as about 92% covered, clears the threshold, and is skipped: the gap
     * becomes permanent and no Resume would ever go back for it.
     *
     * <p>Requiring every month in the window to carry 80% of its own weekdays decouples the two.
     * Gap detection stays at month resolution whatever the window is, so the window is free to be
     * as wide as the broker will serve - which is the whole point of widening it.
     *
     * <p>Identical in behaviour to the old check for a one-month window, so nothing about the
     * existing 90-day default changes.
     */
    private boolean hasStoredCandles(Instrument instrument, String mode, int interval, DateRange batch) {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT substr(trade_date,1,7) AS ym, COUNT(DISTINCT trade_date) " +
                "FROM broker_market_candles " +
                "WHERE broker_security_id = ? AND UPPER(COALESCE(candle_mode,'')) = ? " +
                "AND trade_date BETWEEN ? AND ? GROUP BY substr(trade_date,1,7)")
                .setParameter(1, instrument.securityId())
                .setParameter(2, mode.toUpperCase(Locale.ROOT))
                .setParameter(3, batch.from().toString())
                .setParameter(4, batch.to().toString())
                .getResultList();

            Map<String, Long> stored = new LinkedHashMap<>();
            for (Object[] r : rows) {
                if (r[0] == null || r[1] == null) continue;
                stored.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
            }

            // Weekdays the window actually contains in each month - the first and last month of a
            // window are usually partial, so a month's own length is the wrong denominator.
            Map<String, Long> expected = new LinkedHashMap<>();
            batch.from().datesUntil(batch.to().plusDays(1))
                .filter(d -> d.getDayOfWeek().getValue() <= 5)
                .forEach(d -> expected.merge(d.toString().substring(0, 7), 1L, Long::sum));
            if (expected.isEmpty()) return false;

            for (Map.Entry<String, Long> e : expected.entrySet()) {
                // A calendar month holds ~19-23 trading sessions, so most of its weekdays being
                // present is what distinguishes a finished month from a half-finished one.
                if (stored.getOrDefault(e.getKey(), 0L) < e.getValue() * 0.8) return false;
            }
            return true;
        } catch (Exception e) {
            return false;   // cannot tell - fetch it
        }
    }

    private void runJobWithInstruments(Long jobId, DhanBrokerService.Credential credential, String mode, int interval,
                                       LocalDate from, LocalDate to, List<Instrument> instruments) {
        try {
            updateJob(jobId, "running", "Dhan retry backfill running.", Map.of("started_at", DateUtil.now()));
            int totalChunks = instruments.stream().mapToInt(i -> chunks(mode, from, to).size()).sum();
            updateJobCounts(jobId, instruments.size(), totalChunks);
            Map<String, String> headers = Map.of(
                "Accept", "application/json",
                "Content-Type", "application/json",
                "access-token", credential.accessToken(),
                "client-id", credential.clientId()
            );
            int processedInstruments = 0;
            for (Instrument instrument : instruments) {
                if (stopRequested(jobId)) {
                    updateJob(jobId, "stopped", "Dhan historical backfill stopped by user.", Map.of("completed_at", DateUtil.now()));
                    return;
                }
                updateCurrent(jobId, instrument.symbol());


                int importedForInstrument = 0;
                boolean failedInstrument = false;
                boolean skippedInstrument = false;
                boolean authFailed = false;
                for (DateRange chunk : chunks(mode, from, to)) {
                    try {
                        JsonNode json = fetchCandlesWithRetry(headers, instrument, mode, interval, chunk);
                        int imported = saveCandles(jobId, credential.id(), instrument, mode, interval, json);
                        importedForInstrument += imported;
                        if (!"INTRADAY".equals(mode)) noteDailyChunkAccepted();
                        incrementJob(jobId, 1, imported, 0);
                    } catch (Exception e) {
                        String emsg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
                        if (emsg.contains("dh-901") || emsg.contains("invalid_authentication") || emsg.contains("http 401") || emsg.contains("token is invalid")) {
                            log.warn("Dhan token expired/invalid (DH-901). Stopping retry job {}.", jobId);
                            recordBackfillIssue(jobId, instrument, mode, interval, chunk, "FAILED", "DH-901", "Token expired or invalid — backfill aborted.");
                            incrementJob(jobId, 1, 0, 1);
                            incrementInstrumentOutcome(jobId, 0, 1, 0);
                            updateJob(jobId, "failed", "Dhan broker token is expired or invalid. Please reconnect your Dhan broker token and retry.", Map.of("completed_at", DateUtil.now()));
                            authFailed = true;
                            break;
                        }
                        boolean noData = isDhanNoDataError(e);
                        // DH-905 is Dhan's catch-all "bad values for parameters". It is how an
                        // over-wide date range would come back, and also how an instrument with no
                        // listing in the window comes back, so it is counted rather than acted on.
                        if (!"INTRADAY".equals(mode) && emsg.contains("dh-905")) {
                            noteDailyChunkRejected(chunk);
                        }
                        recordBackfillIssue(jobId, instrument, mode, interval, chunk, noData ? "SKIPPED" : "FAILED", dhanErrorCode(e), dhanErrorMessage(e));
                        incrementJob(jobId, 1, 0, 1);
                        if (noData) skippedInstrument = true;
                        else failedInstrument = true;
                    }
                }
                if (authFailed) return;
                if (importedForInstrument == 0 && !failedInstrument && !skippedInstrument) {
                    recordBackfillIssue(jobId, instrument, mode, interval, new DateRange(from, to), "SKIPPED", "NO_DATA",
                        "Dhan returned no candles for the selected period.");
                    skippedInstrument = true;
                }
                if (importedForInstrument > 0) incrementInstrumentOutcome(jobId, 1, 0, 0);
                else if (failedInstrument) incrementInstrumentOutcome(jobId, 0, 1, 0);
                else incrementInstrumentOutcome(jobId, 0, 0, 1);
                processedInstruments++;
                updateProcessedInstruments(jobId, processedInstruments);
            }
            Map<String, Object> summary = latestJobById(jobId);
            updateJob(jobId, "completed",
                "Dhan retry completed. Success: " + summary.getOrDefault("successfulInstruments", 0) +
                    ", Failed: " + summary.getOrDefault("failedInstruments", 0) +
                    ", Skipped: " + summary.getOrDefault("skippedInstruments", 0) + ".",
                Map.of("completed_at", DateUtil.now()));
        } catch (Exception e) {
            updateJob(jobId, "failed", friendlyJobError(e), Map.of("completed_at", DateUtil.now()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> batchIdsForJob(Long jobId) {
        List<Object> rows = em.createNativeQuery("SELECT id FROM dhan_backfill_batches WHERE job_id=? ORDER BY batch_index")
            .setParameter(1, jobId).getResultList();
        return rows.stream().map(o -> ((Number) o).longValue()).toList();
    }

    private void runJob(Long jobId, DhanBrokerService.Credential credential, String mode, int interval, LocalDate from, LocalDate to,
                        String exchanges, String segments, String instrumentFilter, int maxInstruments,
                        boolean continueOnMissingData, boolean scopeToTracked) {
        try {
            if (stopRequested(jobId)) {
                updateJob(jobId, "stopped", "Dhan historical backfill stopped by user.", Map.of("completed_at", DateUtil.now()));
                return;
            }
            updateJob(jobId, "running", "Dhan historical backfill running.", Map.of("started_at", DateUtil.now()));
            List<Instrument> instruments = loadInstruments(exchanges, segments, instrumentFilter, maxInstruments, scopeToTracked);
            if (instruments.isEmpty()) {
                updateJob(jobId, "failed", "No Stock List instruments found for this backfill selection.", Map.of("completed_at", DateUtil.now()));
                return;
            }
            List<Long> batchIds = batchIdsForJob(jobId);
            List<DateRange> batches = monthlyBatches(from, to);
            int totalChunks = instruments.size() * batches.size();
            updateJobCounts(jobId, instruments.size(), totalChunks);
            Map<String, String> headers = Map.of(
                "Accept", "application/json", "Content-Type", "application/json",
                "access-token", credential.accessToken(), "client-id", credential.clientId());
            int globalProcessed = 0;
            int completedBatches = 0, failedBatches = 0;
            for (int bi = 0; bi < batches.size(); bi++) {
                DateRange batch = batches.get(bi);
                Long batchId = bi < batchIds.size() ? batchIds.get(bi) : null;
                if (batchId != null) updateBatchStatus(batchId, "running", null, Map.of("started_at", DateUtil.now()));
                if (batchId != null) updateBatchInstrumentCount(batchId, instruments.size());
                String batchLabel = windowLabel(batch);
                updateJob(jobId, "running", "Processing batch " + (bi + 1) + "/" + batches.size() + " (" + batchLabel + ").", Map.of());
                int batchProcessed = 0, batchImported = 0, batchSuccess = 0, batchFailed = 0, batchSkipped = 0;
                for (Instrument instrument : instruments) {
                    if (stopRequested(jobId)) {
                        if (batchId != null) updateBatchStatus(batchId, "stopped", "Stopped by user.", Map.of("completed_at", DateUtil.now()));
                        updateJob(jobId, "stopped", "Dhan historical backfill stopped by user.", Map.of("completed_at", DateUtil.now()));
                        return;
                    }
                    updateCurrent(jobId, instrument.symbol());

                    // Already stored for this batch window? Skip it. Without this, Resume restarted
                    // from the first symbol every time: a run stopped at chunk 797 of 3136 began
                    // again at 0 and spent its whole time re-downloading rows the upsert would only
                    // discard. Each skip is also a broker call not spent, which matters under rate
                    // limits. Only the range backfill skips - the retry path must always re-ask,
                    // since it exists precisely to repair symbols that have no rows.
                    if (hasStoredCandles(instrument, mode, interval, batch)) {
                        // An instrument already holding candles for this range counts as a
                        // success without a download - but it is still one instrument dealt
                        // with, so it has to advance the processed counter like any other.
                        //
                        // This "continue" used to jump straight past the batchProcessed++ at the
                        // bottom of the loop, so re-running a range that was mostly already
                        // fetched produced cards that contradicted themselves: AUG 2025 read
                        // "Processed: 717/3136" beside "Saved: 2615", and the stated rule
                        // Saved + No data + Failed = Processed came out as 3131 = 717. The 2,414
                        // instruments that were already stored were counted in Saved and nowhere
                        // else.
                        batchSuccess++;
                        incrementInstrumentOutcome(jobId, 1, 0, 0);
                        incrementJob(jobId, 1, 0, 0);
                        batchProcessed++;
                        globalProcessed++;
                        updateProcessedInstruments(jobId, globalProcessed);
                        if (batchId != null) {
                            updateBatchProgress(batchId, batchProcessed, batchImported,
                                                batchSuccess, batchFailed, batchSkipped);
                        }
                        continue;
                    }
                    try {
                        JsonNode json = fetchCandlesWithRetry(headers, instrument, mode, interval, batch);
                        int imported = saveCandles(jobId, credential.id(), instrument, mode, interval, json);
                        batchImported += imported;
                        if (imported > 0) { batchSuccess++; incrementInstrumentOutcome(jobId, 1, 0, 0); }
                        else { batchSkipped++; incrementInstrumentOutcome(jobId, 0, 0, 1); }
                        incrementJob(jobId, 1, imported, 0);
                    } catch (Exception e) {
                        String emsg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
                        if (emsg.contains("dh-901") || emsg.contains("invalid_authentication") || emsg.contains("http 401") || emsg.contains("token is invalid")) {
                            log.warn("Dhan token expired/invalid (DH-901). Stopping backfill job {}.", jobId);
                            recordBackfillIssue(jobId, instrument, mode, interval, batch, "FAILED", "DH-901", "Token expired or invalid — backfill aborted.");
                            incrementJob(jobId, 1, 0, 1);
                            batchFailed++;
                            incrementInstrumentOutcome(jobId, 0, 1, 0);
                            if (batchId != null) updateBatchStatus(batchId, "failed", "Dhan authentication failed (DH-901). Reconnect broker token.", Map.of("completed_at", DateUtil.now()));
                            updateJob(jobId, "failed", "Dhan broker token is expired or invalid. Please reconnect your Dhan broker token and retry.", Map.of("completed_at", DateUtil.now()));
                            return;
                        }
                        boolean noData = isDhanNoDataError(e);
                        recordBackfillIssue(jobId, instrument, mode, interval, batch, noData ? "SKIPPED" : "FAILED", dhanErrorCode(e), dhanErrorMessage(e));
                        incrementJob(jobId, 1, 0, 1);
                        if (noData) { batchSkipped++; incrementInstrumentOutcome(jobId, 0, 0, 1); }
                        else { batchFailed++; incrementInstrumentOutcome(jobId, 0, 1, 0); }
                    }
                    batchProcessed++;
                    globalProcessed++;
                    updateProcessedInstruments(jobId, globalProcessed);
                    if (batchId != null) updateBatchProgress(batchId, batchProcessed, batchImported, batchSuccess, batchFailed, batchSkipped);
                }
                String batchStatus = batchFailed > 0 && batchSuccess > 0 ? "partially_completed" : batchFailed > 0 ? "failed" : "completed";
                if (batchId != null) {
                    updateBatchStatus(batchId, batchStatus, "Batch " + batchLabel + ": Success=" + batchSuccess + " Failed=" + batchFailed + " Skipped=" + batchSkipped,
                        Map.of("completed_at", DateUtil.now()));
                }
                if ("completed".equals(batchStatus) || "partially_completed".equals(batchStatus)) completedBatches++;
                if ("failed".equals(batchStatus)) failedBatches++;
            }
            Map<String, Object> summary = latestJobById(jobId);
            updateJob(jobId, "completed",
                "Dhan historical backfill completed. Batches: " + completedBatches + "/" + batches.size() +
                    ". Success: " + summary.getOrDefault("successfulInstruments", 0) +
                    ", Failed: " + summary.getOrDefault("failedInstruments", 0) +
                    ", Skipped: " + summary.getOrDefault("skippedInstruments", 0) + ".",
                Map.of("completed_at", DateUtil.now()));
            logSkipSummary(jobId);
        } catch (Exception e) {
            updateJob(jobId, "failed", friendlyJobError(e), Map.of("completed_at", DateUtil.now()));
        }
    }

    private String friendlyJobError(Exception e) {
        String msg = e == null || e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("access-token") || msg.contains("401") || msg.contains("Unauthorized"))
            return "Dhan broker authentication failed. Please check your access token in Broker settings and try again.";
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("SocketTimeout"))
            return "Connection to Dhan API timed out. The service may be temporarily slow — please try again in a few minutes.";
        if (msg.contains("Connection refused") || msg.contains("UnknownHost"))
            return "Could not connect to Dhan API. Please check your internet connection and try again.";
        if (msg.contains("429") || msg.contains("Too Many Requests") || msg.contains("rate limit"))
            return "Dhan API rate limit reached. Please wait a few minutes before starting another backfill.";
        if (msg.contains("Stock List") || msg.contains("stockListRows"))
            return msg;
        if (msg.length() > 200)
            return "Backfill encountered an unexpected error. Please check the error log for details and try again.";
        return msg.isEmpty() ? "Backfill failed due to an unexpected error. Please try again." : msg;
    }

    /** Enforce the current minimum gap between Dhan candle calls, so the backfill stays under the limit. */
    private void historicalThrottle() {
        long now = System.currentTimeMillis();
        long wait = lastHistoricalCallMs.get() + historicalGapMs.get() - now;
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        lastHistoricalCallMs.set(System.currentTimeMillis());
    }

    /**
     * Widen the gap because Dhan just refused a call for rate reasons.
     *
     * <p>Doubling on the way up and stepping down slowly is deliberate: being too fast costs real
     * failures and retries, while being too slow only costs time, so the penalty for overshooting
     * is worse than the penalty for undershooting.
     */
    private void widenHistoricalGap(String reason) {
        cleanCallStreak.set(0);
        long before = historicalGapMs.get();
        // Remember the gap that just failed. Narrowing will stop one step above it instead of
        // walking all the way back to the floor and provoking the same refusal again.
        lastRefusedGapMs.set(Math.max(lastRefusedGapMs.get(), before));
        long after = Math.min(GAP_CEILING_MS, Math.max(GAP_FLOOR_MS, before * 2));
        if (after != before) {
            historicalGapMs.set(after);
            log.info("[Backfill] Dhan pushed back ({}), slowing candle calls {} ms -> {} ms",
                reason, before, after);
        }
    }

    /**
     * Narrow the gap after a sustained run of clean calls - but never back below a width Dhan
     * has already refused.
     *
     * <p>Widening doubles and narrowing subtracts 250 ms, so the gap used to climb to the
     * ceiling and then take 225 clean calls to walk back to the floor, where it was refused
     * again immediately. Job #6 logged 13 widenings against 28 narrowings: it spent its life
     * sawtoothing rather than settling. Holding a floor at the last refused width turns that
     * oscillation into convergence.
     */
    private void noteCleanHistoricalCall() {
        if (cleanCallStreak.incrementAndGet() < CLEAN_CALLS_BEFORE_NARROWING) return;
        cleanCallStreak.set(0);
        long before = historicalGapMs.get();
        long refused = lastRefusedGapMs.get();
        long safeFloor = refused > 0 ? Math.min(GAP_CEILING_MS, refused + 250) : GAP_FLOOR_MS;
        long after = Math.max(Math.max(GAP_FLOOR_MS, safeFloor), before - 250);
        if (after != before) {
            historicalGapMs.set(after);
            log.info("[Backfill] {} clean calls, speeding candle calls {} ms -> {} ms{}",
                CLEAN_CALLS_BEFORE_NARROWING, before, after,
                refused > 0 ? " (holding above " + refused + " ms, which was refused)" : "");
        }
    }

    private JsonNode fetchCandles(Map<String, String> headers, Instrument instrument, String mode, int interval, DateRange range) throws Exception {
        validateCandleRequest(instrument, mode, interval, range);
        historicalThrottle();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("securityId", instrument.securityId());
        body.put("exchangeSegment", normalizeExchangeSegment(instrument.exchangeSegment()));
        body.put("instrument", historicalInstrument(instrument.instrument()));
        body.put("oi", isDerivativeInstrument(instrument.instrument()));
        if ("INTRADAY".equals(mode)) {
            body.put("interval", normalizeIntradayInterval(interval));
            body.put("fromDate", range.from().atTime(9, 15).format(DHAN_INTRADAY));
            body.put("toDate", range.to().atTime(15, 30).format(DHAN_INTRADAY));
            // postJson, NOT postJsonWithRetry - see the note on fetchCandlesWithRetry.
            return mapper.readTree(http.postJson(DHAN_INTRADAY_URL, headers, body, 30000));
        }
        body.put("expiryCode", 0);
        body.put("fromDate", range.from().format(DHAN_DATE));
        body.put("toDate", range.to().plusDays(1).format(DHAN_DATE));
        return mapper.readTree(http.postJson(DHAN_HISTORICAL_URL, headers, body, 30000));
    }

    private void validateCandleRequest(Instrument instrument, String mode, int interval, DateRange range) {
        if (instrument == null) throw new IllegalArgumentException("Dhan candle request failed: instrument mapping missing.");
        if (!isValidDhanInstrument(instrument)) {
            throw new IllegalArgumentException("Dhan candle request skipped: invalid/test Dhan instrument " + instrument.symbol() + " / " + instrument.tradingSymbol() + ".");
        }
        if (instrument.securityId() == null || instrument.securityId().isBlank()) {
            throw new IllegalArgumentException("Dhan candle request failed for " + instrument.symbol() + ": securityId is missing.");
        }
        try {
            Long.parseLong(instrument.securityId());
        } catch (Exception e) {
            throw new IllegalArgumentException("Dhan candle request failed for " + instrument.symbol() + ": securityId must be numeric, actual=" + instrument.securityId() + ".");
        }
        String segment = normalizeExchangeSegment(instrument.exchangeSegment());
        if (segment.isBlank() || !DHAN_EXCHANGE_SEGMENTS.contains(segment)) {
            throw new IllegalArgumentException("Dhan candle request failed for " + instrument.symbol() + ": invalid exchangeSegment " + instrument.exchangeSegment() + ".");
        }
        String dhanInstrument = historicalInstrument(instrument.instrument());
        if (dhanInstrument.isBlank()) {
            throw new IllegalArgumentException("Dhan candle request failed for " + instrument.symbol() + ": invalid instrument " + instrument.instrument() + ".");
        }
        if ("NSE_EQ".equals(segment) && !"EQUITY".equals(dhanInstrument)) {
            throw new IllegalArgumentException("Dhan candle request failed for " + instrument.symbol() + ": NSE equity instruments must be EQUITY/ES, actual=" + instrument.instrument() + ".");
        }
        if (range == null || range.from() == null || range.to() == null || range.from().isAfter(range.to())) {
            throw new IllegalArgumentException("Dhan candle request failed for " + instrument.symbol() + ": invalid date range.");
        }
        if ("INTRADAY".equals(mode) && !DHAN_INTRADAY_INTERVALS.contains(String.valueOf(interval))) {
            log.warn("Invalid Dhan intraday interval {} for {}; using 1 minute.", interval, instrument.symbol());
        }
    }

    private static final int CANDLE_BATCH_SIZE = 50;


    private static final String BROKER_CANDLE_UPSERT_SQL =
        "INSERT INTO broker_market_candles(source_job_type,source_job_id,broker_name,broker_credential_id,data_type,exchange,exchange_segment," +
        "segment,instrument_type,broker_security_id,symbol,trading_symbol,candle_mode,interval_minutes,interval_key,candle_time,trade_date," +
        "open,high,low,close,volume,total_volume,open_interest,source,created_at,updated_at) " +
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
        "ON CONFLICT (broker_name, broker_security_id, exchange_segment, candle_mode, interval_key, candle_time) DO UPDATE SET " +
        "source_job_type=EXCLUDED.source_job_type, source_job_id=EXCLUDED.source_job_id, broker_credential_id=EXCLUDED.broker_credential_id, " +
        "data_type=EXCLUDED.data_type, exchange=EXCLUDED.exchange, segment=EXCLUDED.segment, instrument_type=EXCLUDED.instrument_type, " +
        "symbol=EXCLUDED.symbol, trading_symbol=EXCLUDED.trading_symbol, interval_minutes=EXCLUDED.interval_minutes, trade_date=EXCLUDED.trade_date, " +
        "open=EXCLUDED.open, high=EXCLUDED.high, low=EXCLUDED.low, close=EXCLUDED.close, volume=EXCLUDED.volume, " +
        "total_volume=EXCLUDED.total_volume, open_interest=EXCLUDED.open_interest, source=EXCLUDED.source, updated_at=EXCLUDED.updated_at";

    private int saveCandles(Long jobId, Long brokerCredentialId, Instrument instrument, String mode, int interval, JsonNode json) {
        JsonNode timestamps = json.path("timestamp");
        if (!timestamps.isArray() || timestamps.isEmpty()) return 0;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            boolean isDaily = "DAILY".equals(mode);
            int intervalKey = "INTRADAY".equals(mode) ? interval : 0;
            Integer intervalMinutes = "INTRADAY".equals(mode) ? interval : null;
            String dataType = isDaily ? "Daily Bhavcopy (OHLCV)" : "Intraday Candles";
            // created_at/updated_at are native timestamps (V38), so bind a real
            // Timestamp instead of DateUtil's formatted string.
            Timestamp nowTs = Timestamp.valueOf(LocalDateTime.now());
            int[] result = {0};
            Session session = em.unwrap(Session.class);
            session.doWork(connection -> {
                // One store: every candle is written to broker_market_candles and nowhere else.
                // Each daily equity candle used to be inserted into market_data as well, which is
                // how that table came to hold a full copy of the daily candles.
                try (PreparedStatement ps2 = connection.prepareStatement(BROKER_CANDLE_UPSERT_SQL)) {
                    // Dhan sometimes returns the same candle_time twice in one response - now and
                    // then an exact duplicate, now and then the same bar with every price scaled by
                    // ten (seen on AQYLON: 156.79 and 1567.9 both stamped 2026-02-01). Both rows
                    // carry the same conflict key, and PostgreSQL refuses a batch that touches one
                    // row twice - "ON CONFLICT DO UPDATE command cannot affect row a second time" -
                    // which aborts the ENTIRE batch, so one bad bar silently costs a whole symbol's
                    // history. Keep only the last occurrence of each candle_time: that is exactly
                    // what the upsert would have settled on had the rows arrived in separate
                    // statements, and it keeps the rest of the batch intact.
                    int candleCount = timestamps.size();
                    String[] candleTimes = new String[candleCount];
                    for (int i = 0; i < candleCount; i++) {
                        candleTimes[i] = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamps.get(i).asLong()), IST)
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    }
                    Map<String, Integer> lastIndexPerTime = new HashMap<>();
                    for (int i = 0; i < candleCount; i++) lastIndexPerTime.put(candleTimes[i], i);
                    int duplicateCandleTimes = candleCount - lastIndexPerTime.size();
                    if (duplicateCandleTimes > 0) {
                        log.warn("[Backfill] {} returned {} duplicate candle time(s) out of {}; keeping the last of each so the batch still saves.",
                            instrument.symbol(), duplicateCandleTimes, candleCount);
                    }

                    int batchCount = 0;
                    for (int i = 0; i < candleCount; i++) {
                        if (lastIndexPerTime.get(candleTimes[i]) != i) continue;   // superseded duplicate
                        String candleTime = candleTimes[i];
                        String tradeDate = candleTime.substring(0, 10);
                        double open = at(json, "open", i);
                        double high = at(json, "high", i);
                        double low = at(json, "low", i);
                        double close = at(json, "close", i);
                        double volume = at(json, "volume", i);
                        double oi = at(json, "open_interest", i);


                        ps2.setString(1, "dhan_historical_jobs");
                        ps2.setLong(2, jobId);
                        ps2.setString(3, "dhan");
                        ps2.setLong(4, brokerCredentialId);
                        ps2.setString(5, dataType);
                        ps2.setString(6, instrument.exchange());
                        ps2.setString(7, instrument.exchangeSegment());
                        ps2.setString(8, instrument.instrument());
                        ps2.setString(9, instrument.instrument());
                        ps2.setString(10, instrument.securityId());
                        ps2.setString(11, instrument.symbol());
                        ps2.setString(12, instrument.tradingSymbol());
                        ps2.setString(13, mode);
                        if (intervalMinutes != null) ps2.setInt(14, intervalMinutes); else ps2.setNull(14, Types.INTEGER);
                        ps2.setInt(15, intervalKey);
                        ps2.setString(16, candleTime);
                        ps2.setString(17, tradeDate);
                        ps2.setDouble(18, open);
                        ps2.setDouble(19, high);
                        ps2.setDouble(20, low);
                        ps2.setDouble(21, close);
                        ps2.setDouble(22, volume);
                        ps2.setDouble(23, volume);
                        ps2.setDouble(24, oi);
                        ps2.setString(25, "DHAN");
                        ps2.setTimestamp(26, nowTs);
                        ps2.setTimestamp(27, nowTs);
                        ps2.addBatch();

                        batchCount++;
                        if (batchCount % CANDLE_BATCH_SIZE == 0) {
                            ps2.executeBatch();
                        }
                    }
                    if (batchCount % CANDLE_BATCH_SIZE != 0) {
                        ps2.executeBatch();
                    }
                    result[0] = batchCount;
                }
            });
            return result[0];
        });
    }

    @SuppressWarnings("unchecked")
    /** Keeps the historical default: fetch only what the tracker actually ranks. */
    private List<Instrument> loadInstruments(String exchanges, String segments, String instrumentFilter, int maxInstruments) {
        return loadInstruments(exchanges, segments, instrumentFilter, maxInstruments, true);
    }

    /**
     * @param scopeToTracked {@code true} narrows the selection to symbols present in
     *        tracked_stocks — right for the startup sync and for retries, which exist to keep
     *        the ranked universe fresh. {@code false} takes every instrument matching the
     *        requested exchange / segment / instrument category, which is what a Data Warehouse
     *        fetch means: populate the whole category, not just what happens to be uploaded.
     */
    @SuppressWarnings("unchecked")
    private List<Instrument> loadInstruments(String exchanges, String segments, String instrumentFilter,
                                             int maxInstruments, boolean scopeToTracked) {
        String base = instrumentSelectionSql(exchanges, segments, instrumentFilter,
            "SELECT security_id,exchange,exchange_segment,instrument,underlying_symbol AS app_symbol,trading_symbol FROM dhan_instruments");
        String order = " ORDER BY exchange_segment, underlying_symbol";
        String limit = maxInstruments > 0 ? " LIMIT " + maxInstruments : "";
        // Scope the backfill to the user's tracked stocks (not the whole equity
        // universe), so we fetch exactly the symbols the app ranks/displays. If the
        // tracked filter matches nothing (symbol-format mismatch), fall back to the
        // full selection so the backfill never fetches an empty set.
        String trackedFilter =
            " AND (UPPER(TRIM(underlying_symbol)) IN (SELECT DISTINCT UPPER(TRIM(symbol)) FROM tracked_stocks WHERE is_active=1)" +
            " OR UPPER(TRIM(trading_symbol)) IN (SELECT DISTINCT UPPER(TRIM(symbol)) FROM tracked_stocks WHERE is_active=1))";
        List<Object[]> rows;
        if (!scopeToTracked) {
            rows = em.createNativeQuery(base + order + limit).getResultList();
        } else {
            try {
                rows = em.createNativeQuery(base + trackedFilter + order + limit).getResultList();
            } catch (Exception e) {
                log.warn("Tracked-scope filter failed, using full instrument selection: {}", e.getMessage());
                rows = em.createNativeQuery(base + order + limit).getResultList();
            }
            if (rows.isEmpty()) {
                log.warn("Tracked-scope backfill matched 0 instruments (symbol-format mismatch or no uploads); using full equity selection.");
                rows = em.createNativeQuery(base + order + limit).getResultList();
            }
        }
        // Dhan lists some stocks under more than one security id - 3IINFOLTD, for instance, is
        // both 6232 and 6240. Left alone, each twin is downloaded and stored separately, giving
        // two identical price histories for one company: measured at 305 symbols and 3.4 million
        // redundant rows here. The unique constraint cannot catch it, because the security ids
        // genuinely differ. Keep the first id seen per symbol and segment, and skip the rest -
        // that saves the download as well as the storage.
        List<Instrument> result = new ArrayList<>();
        Map<String, String> firstIdPerSymbol = new LinkedHashMap<>();
        List<String> duplicateSymbolSkips = new ArrayList<>();
        for (Object[] r : rows) {
            Instrument instrument = new Instrument(String.valueOf(r[0]), String.valueOf(r[1]), normalizeExchangeSegment(String.valueOf(r[2])),
                normalizeInstrument(String.valueOf(r[3])), String.valueOf(r[4]), String.valueOf(r[5]));
            if (!isValidDhanInstrument(instrument)) continue;

            String key = normalizeSymbolKey(instrument.symbol()) + "|" + instrument.exchangeSegment();
            String alreadyKept = firstIdPerSymbol.putIfAbsent(key, instrument.securityId());
            if (alreadyKept != null) {
                if (duplicateSymbolSkips.size() < 20) {
                    duplicateSymbolSkips.add(instrument.symbol() + " (id " + instrument.securityId()
                        + " duplicates id " + alreadyKept + ")");
                }
                continue;
            }
            result.add(instrument);
        }

        int duplicatesSkipped = firstIdPerSymbol.size() > 0 ? (int) (rows.size() - result.size()) : 0;
        lastDuplicateSymbolSkips = duplicatesSkipped;
        if (duplicatesSkipped > 0) {
            log.warn("[Backfill] Skipped {} duplicate listing(s): the same stock appears under more than one Dhan id, "
                   + "so only the first id of each is downloaded. Examples: {}",
                   duplicatesSkipped, String.join(", ", duplicateSymbolSkips));
        }
        log.info("[Backfill] Loaded {} instruments ({}){}.", result.size(),
            scopeToTracked ? "scoped to tracked stocks" : "full " + segments + "/" + instrumentFilter + " category",
            duplicatesSkipped > 0 ? " after skipping " + duplicatesSkipped + " duplicate listing(s)" : "");
        return result;
    }

    /** How many duplicate listings the last instrument load skipped, for the progress screen. */
    private volatile int lastDuplicateSymbolSkips = 0;

    public int getLastDuplicateSymbolSkips() {
        return lastDuplicateSymbolSkips;
    }

    /** Same normalisation the tracker uses, so "RELIANCE-EQ" and "RELIANCE" count as one stock. */
    private String normalizeSymbolKey(String symbol) {
        if (symbol == null) return "";
        String v = symbol.trim().toUpperCase(Locale.ROOT)
            .replace(".NS", "").replace(".BO", "").replace(" ", "");
        for (String suffix : List.of("-EQ", "-BE", "-BZ", "-SM", "-ST")) {
            if (v.endsWith(suffix)) return v.substring(0, v.length() - suffix.length());
        }
        return v;
    }

    private long countDhanStockList() {
        try {
            Object value = em.createNativeQuery("SELECT COUNT(*) FROM dhan_instruments WHERE security_id IS NOT NULL AND security_id<>''").getSingleResult();
            return ((Number) value).longValue();
        } catch (Exception e) {
            return 0;
        }
    }

    private long countMatchingInstruments(String exchanges, String segments, String instrumentFilter) {
        try {
            Object value = em.createNativeQuery(instrumentSelectionSql(exchanges, segments, instrumentFilter, "SELECT COUNT(*) FROM dhan_instruments")).getSingleResult();
            return ((Number) value).longValue();
        } catch (Exception e) {
            return 0;
        }
    }

    /** How many instruments a selection would match, for explaining an empty result. */
    private long countInstruments(String exchanges, String segments, String instrumentFilter) {
        try {
            Object n = em.createNativeQuery(instrumentSelectionSql(exchanges, segments,
                instrumentFilter, "SELECT COUNT(*) FROM dhan_instruments")).getSingleResult();
            return n == null ? 0 : ((Number) n).longValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private String instrumentSelectionSql(String exchanges, String segments, String instrumentFilter, String selectClause) {
        String sql = selectClause + " WHERE security_id IS NOT NULL AND security_id<>''";
        sql += " AND COALESCE(underlying_symbol, '')<>''";
        sql += " AND UPPER(COALESCE(underlying_symbol, '')) NOT LIKE '%TEST%'";
        sql += " AND UPPER(COALESCE(underlying_symbol, '')) NOT LIKE '%DUMMY%'";
        if (!"ALL".equalsIgnoreCase(String.valueOf(exchanges))) {
            sql += " AND exchange IN (" + quotedList(exchanges, List.of("NSE", "BSE")) + ")";
        }
        if (!"ALL".equalsIgnoreCase(segments)) {
            sql += " AND exchange_segment IN (" + quotedList(segments, List.of()) + ")";
        }
        if (!"ALL".equalsIgnoreCase(instrumentFilter)) {
            String filter = norm(instrumentFilter);
            if ("ES".equals(filter) || "EQUITY".equals(filter) || "EQ".equals(filter)) {
                sql += " AND UPPER(COALESCE(instrument,'')) IN ('ES','EQUITY','EQ')";
                sql += " AND UPPER(COALESCE(instrument_type,''))='ES'";
            } else {
                sql += " AND UPPER(COALESCE(instrument,'')) LIKE '%" + safeLike(filter) + "%'";
            }
        }
        return sql;
    }

    /**
     * Splits a date range into one request per chunk.
     *
     * <p>DAILY used to be cut into calendar months, which is what made a wide fetch take hours.
     * The request count is instruments x chunks and the pace is one request every
     * {@value #GAP_FLOOR_MS} ms, so the chunk width sets the runtime directly. Measured on job #8
     * (DAILY, 2026-04-01 to 2026-10-30, 3,222 instruments): 7 months became 7 chunks per
     * instrument - 22,554 requests, 8,843 of them done in 73.9 minutes, dead on 1.99/s. At one
     * request per instrument the same fetch is 3,222 requests, about 27 minutes.
     *
     * <p>Nothing about the endpoint required months. INTRADAY - which is the narrower of the two,
     * being capped at 90 days - has always used 89-day windows here; DAILY, the wider one, was
     * being cut thirty times finer. Month chunking also multiplied the dead requests: of job #8's
     * 1,115 errors, every one was DH-907 "no data present" or DH-905, spread over 413 instruments
     * at about 2.7 months each, i.e. the same instrument asked again for each month it has no
     * data in. One wide request per instrument asks once.
     *
     * <p>Width comes from configuration and narrows itself if the broker ever rejects it - see
     * {@link #currentDailyChunkDays()}. There were no DH-904 rate-limit errors anywhere in job
     * #8, so the pacing floor is left exactly as it is: this reduces the amount of work, not the
     * safety margin that stopped the earlier 429 storm.
     */
    private List<DateRange> chunks(String mode, LocalDate from, LocalDate to) {
        List<DateRange> ranges = new ArrayList<>();
        LocalDate cursor = from;
        int span = "INTRADAY".equals(mode) ? 89 : currentDailyChunkDays();
        while (!cursor.isAfter(to)) {
            LocalDate end = cursor.plusDays(span - 1);
            if (end.isAfter(to)) end = to;
            ranges.add(new DateRange(cursor, end));
            cursor = end.plusDays(1);
        }
        return ranges;
    }

    /**
     * Days of history asked of Dhan in one request, and therefore one progress batch.
     *
     * <p>90, because that is the widest window this codebase has ever proven Dhan accepts: the
     * intraday path has used 89-day windows against the same chart endpoints since it was written.
     * Wider is very likely allowed for daily candles - a 7-month daily request returns about 145
     * rows - but "likely" is not a basis for the setting, so the default stays at the proven
     * figure and {@link #noteDailyChunkRejected} makes raising it safe.
     *
     * <p>It cannot usefully exceed 90 for INTRADAY, which Dhan caps at 90 days.
     */
    @org.springframework.beans.factory.annotation.Value("${dhan.history.batch.days:90}")
    private int configuredDailyChunkDays;

    /** 0 until first use, then the width in force; only ever decreases within a run. */
    private final java.util.concurrent.atomic.AtomicInteger dailyChunkDays =
        new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Consecutive wide-range rejections with no success in between.
     *
     * <p>Deliberately not "narrow on the first rejection". DH-905 is also what Dhan returns for an
     * instrument asked about a period it has no listing for - 108 symbols hit it in job #8 at
     * month width, where no width question can be involved - so a single rejection says nothing
     * about the width. A width that is genuinely too wide fails for every instrument, so it shows
     * up as an unbroken run; anything instrument-specific is interrupted by the successes around
     * it and resets this.
     */
    private final java.util.concurrent.atomic.AtomicInteger consecutiveWideRejections =
        new java.util.concurrent.atomic.AtomicInteger(0);

    private static final int WIDE_REJECTIONS_BEFORE_NARROWING = 10;

    private int currentDailyChunkDays() {
        int now = dailyChunkDays.get();
        if (now > 0) return now;
        int start = configuredDailyChunkDays > 0 ? configuredDailyChunkDays : 90;
        dailyChunkDays.compareAndSet(0, start);
        return dailyChunkDays.get();
    }

    /** A DAILY chunk came back clean, so the width in force is fine. */
    private void noteDailyChunkAccepted() {
        consecutiveWideRejections.set(0);
    }

    /**
     * Records a possible width rejection, and steps the width down once enough pile up unbroken.
     *
     * <p>The ladder's last rung is 31 days, which is the old month behaviour, so the worst case of
     * getting this wrong is the speed we had before - never a fetch that cannot run.
     *
     * <p>This narrows {@link #chunks}, which the retry path calls per instrument. The main range
     * backfill fixes its windows once at job start, so a narrowing reaches it on the next run, not
     * mid-job. At the default 90 nothing here can fire; it exists for a raised setting.
     */
    private void noteDailyChunkRejected(DateRange chunk) {
        int span = (int) (chunk.to().toEpochDay() - chunk.from().toEpochDay()) + 1;
        if (span <= 90) return;
        if (consecutiveWideRejections.incrementAndGet() < WIDE_REJECTIONS_BEFORE_NARROWING) return;
        consecutiveWideRejections.set(0);
        int before = currentDailyChunkDays();
        int after = before > 90 ? 90 : 31;
        if (after >= before) return;
        if (dailyChunkDays.compareAndSet(before, after)) {
            log.warn("[Backfill] Dhan rejected {}-day daily windows {} times in a row; narrowing to "
                   + "{} days for the rest of this run. Set dhan.history.batch.days to {} to "
                   + "start there next time.", before, WIDE_REJECTIONS_BEFORE_NARROWING, after, after);
        }
    }

    /**
     * The windows the range backfill fetches, one broker request per instrument per window.
     *
     * <p>This, not {@link #chunks}, is what sets the runtime of a Data Warehouse fetch: the main
     * loop asks Dhan for one window at a time and the pace is one request every
     * {@value #GAP_FLOOR_MS} ms. It used to cut the range into calendar months, which is why a
     * wide fetch ran for hours. Job #8 - DAILY, 2026-04-01 to 2026-10-30, 3,222 instruments -
     * became 3,222 x 7 = 22,554 requests and was measured at 1.99/s, i.e. 3h 09m, all of it
     * explained by the request count and the pacing floor rather than by the broker being slow.
     * There was not one DH-904 rate-limit error in the job, so there was no rate problem to
     * solve; there was simply seven times more asking than the data needed. At 90-day windows the
     * same fetch is 3 windows per instrument, 9,666 requests, about 81 minutes.
     *
     * <p>Month chunking also multiplied the dead requests. All 1,151 of job #8's errors were
     * DH-907 "no data present" or DH-905, spread across 413 instruments at roughly 2.7 months
     * each: the same instrument asked again for every month it has nothing in. A wider window
     * asks once.
     *
     * <p>Width is deliberately not unbounded. {@link #hasStoredCandles} decides a window is
     * already done when 80% of its weekdays are present, so the window is also the resolution at
     * which Resume detects a gap. At 90 days a wholly missing month inside the window leaves
     * coverage near 67% and is correctly re-fetched; at a year it would be ~92% and would be
     * skipped. 90 keeps gap detection honest and is the width Dhan is known to accept.
     */
    private List<DateRange> monthlyBatches(LocalDate from, LocalDate to) {
        List<DateRange> batches = new ArrayList<>();
        int span = Math.max(1, currentDailyChunkDays());
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            LocalDate end = cursor.plusDays(span - 1);
            if (end.isAfter(to)) end = to;
            batches.add(new DateRange(cursor, end));
            cursor = end.plusDays(1);
        }
        return batches;
    }

    /**
     * Label for a fetch window, for the batch cards.
     *
     * <p>A window is no longer one calendar month, so naming it after its first month alone would
     * put "APR 2026" on a card covering April to June.
     */
    private String windowLabel(DateRange w) {
        String fromMonth = w.from().getMonth().toString().substring(0, 3);
        String toMonth = w.to().getMonth().toString().substring(0, 3);
        int fromYear = w.from().getYear();
        int toYear = w.to().getYear();
        if (fromYear == toYear && fromMonth.equals(toMonth)) return fromMonth + " " + fromYear;
        if (fromYear == toYear) return fromMonth + "-" + toMonth + " " + fromYear;
        return fromMonth + " " + fromYear + "-" + toMonth + " " + toYear;
    }


    /** Rows written per transaction. Bounds both heap and how much WAL one commit produces. */
    private static final int INSTRUMENT_CHUNK = 1000;

    /** One CSV cell by column name, trying each alias in turn. Replaces the per-row Map. */
    static String at(String[] cols, Map<String, Integer> index, String... names) {   // package-private: see DhanScripMasterParsingTest
        for (String name : names) {
            Integer i = index.get(name);
            if (i != null && i < cols.length) {
                String v = cols[i];
                if (v != null && !v.isBlank()) return v.trim();
            }
        }
        return "";
    }

    /**
     * Writes one chunk of instrument rows and clears the batch.
     *
     * <p>Its own transaction, deliberately. The whole import used to run inside a single
     * transaction spanning ~212,000 inserts, which holds every row lock to the end, keeps one
     * PostgreSQL backend pinned, and leaves nothing on disk if the last row fails. Committing in
     * chunks means a failure costs one chunk, and the EntityManager is cleared each time so the
     * persistence context cannot grow with the file.
     */
    private void flushInstrumentBatch(TransactionTemplate tx, Map<String, Integer> index,
                                      List<String[]> batch, int[] counters) {
        if (batch.isEmpty()) return;
        List<String[]> chunk = new ArrayList<>(batch);
        batch.clear();
        tx.executeWithoutResult(status -> {
            for (String[] cols : chunk) {
                String securityId = at(cols, index, "SEM_SMST_SECURITY_ID", "SECURITY_ID", "SMST_SECURITY_ID");
                if (securityId.isBlank()) continue;
                String exchange = at(cols, index, "SEM_EXM_EXCH_ID", "EXCH_ID");
                String segment = at(cols, index, "SEM_SEGMENT", "SEGMENT");
                String exchangeSegment = exchangeSegment(exchange, segment);
                String instrument = at(cols, index, "SEM_INSTRUMENT_NAME", "INSTRUMENT");
                String instrumentType = at(cols, index, "SEM_EXCH_INSTRUMENT_TYPE", "INSTRUMENT_TYPE");
                String symbol = at(cols, index, "SM_SYMBOL_NAME", "SYMBOL_NAME");
                String dhanTradingSymbol = at(cols, index, "SEM_TRADING_SYMBOL", "TRADING_SYMBOL");
                String display = at(cols, index, "SEM_CUSTOM_SYMBOL", "DISPLAY_NAME");
                String isin = at(cols, index, "ISIN", "SEM_ISIN_CODE", "SM_ISIN_CODE");
                String underlyingSecurityId = at(cols, index, "UNDERLYING_SECURITY_ID", "SEM_UNDERLYING_SECURITY_ID", "SM_UNDERLYING_SECURITY_ID");
                String underlyingSymbol = at(cols, index, "UNDERLYING_SYMBOL", "SEM_UNDERLYING_SYMBOL", "SM_UNDERLYING_SYMBOL");
                String expiryDate = at(cols, index, "SEM_EXPIRY_DATE", "SM_EXPIRY_DATE", "EXPIRY_DATE");
                String optionType = at(cols, index, "SEM_OPTION_TYPE", "OPTION_TYPE");
                Double lotSize = number(at(cols, index, "SEM_LOT_UNITS", "LOT_SIZE"));

                // raw_json is deliberately not written for instruments. Every field it held is
                // already parsed into a typed column below, and keeping Dhan's whole CSV row as
                // JSON - column names repeated on each of ~212k rows - cost about 161 MB per
                // instrument table for data nothing reads.
                em.createNativeQuery(
                    "INSERT INTO dhan_instruments(security_id,exchange,segment_code,exchange_segment,instrument,instrument_type," +
                    "symbol_name,trading_symbol,display_name,isin,underlying_security_id,underlying_symbol,expiry_date,strike_price,option_type,lot_size,updated_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT (security_id, exchange_segment) DO UPDATE SET " +
                    "exchange=EXCLUDED.exchange, segment_code=EXCLUDED.segment_code, instrument=EXCLUDED.instrument, " +
                    "instrument_type=EXCLUDED.instrument_type, symbol_name=EXCLUDED.symbol_name, trading_symbol=EXCLUDED.trading_symbol, " +
                    "display_name=EXCLUDED.display_name, isin=EXCLUDED.isin, underlying_security_id=EXCLUDED.underlying_security_id, " +
                    "underlying_symbol=EXCLUDED.underlying_symbol, expiry_date=EXCLUDED.expiry_date, strike_price=EXCLUDED.strike_price, " +
                    "option_type=EXCLUDED.option_type, lot_size=EXCLUDED.lot_size, updated_at=EXCLUDED.updated_at")
                    .setParameter(1, securityId)
                    .setParameter(2, exchange)
                    .setParameter(3, segment)
                    .setParameter(4, exchangeSegment)
                    .setParameter(5, instrument)
                    .setParameter(6, instrumentType)
                    .setParameter(7, symbol)
                    .setParameter(8, dhanTradingSymbol.isBlank() ? symbol : dhanTradingSymbol)
                    .setParameter(9, display)
                    .setParameter(10, isin)
                    .setParameter(11, underlyingSecurityId)
                    .setParameter(12, underlyingSymbol)
                    .setParameter(13, expiryDate)
                    .setParameter(14, number(at(cols, index, "SEM_STRIKE_PRICE", "STRIKE_PRICE")))
                    .setParameter(15, optionType)
                    .setParameter(16, lotSize)
                    .setParameter(17, DateUtil.now())
                    .executeUpdate();

                if (isEquityStockInstrument(exchange, exchangeSegment, instrument, instrumentType)
                    && !underlyingSymbol.isBlank()) {
                    em.createNativeQuery(
                        "INSERT INTO stock_master(exchange,symbol,company_name,sector,industry,status,source,as_on_date,series,isin,segment,lot_size) " +
                        "VALUES(?,?,?,?,?,'active',?,?,?,?,?,?) " +
                        "ON CONFLICT (exchange, symbol) DO UPDATE SET " +
                        "company_name=COALESCE(NULLIF(EXCLUDED.company_name,''),stock_master.company_name), " +
                        "source=EXCLUDED.source, as_on_date=EXCLUDED.as_on_date, series=EXCLUDED.series, isin=EXCLUDED.isin, " +
                        "segment=EXCLUDED.segment, lot_size=EXCLUDED.lot_size, status='active'")
                        .setParameter(1, normalizeExchange(exchange))
                        .setParameter(2, underlyingSymbol.trim().toUpperCase(Locale.ROOT))
                        .setParameter(3, display.isBlank() ? underlyingSymbol : display)
                        .setParameter(4, "Unknown")
                        .setParameter(5, "Unknown")
                        .setParameter(6, "DHAN")
                        .setParameter(7, DateUtil.today())
                        .setParameter(8, instrument.isBlank() ? "ES" : instrument)
                        .setParameter(9, isin)
                        .setParameter(10, "Equity")
                        .setParameter(11, lotSize == null ? 1 : lotSize.intValue())
                        .executeUpdate();
                    counters[1]++;
                }
                counters[0]++;
            }
            // Without this the persistence context keeps every statement's state for the whole
            // import, which is the same unbounded growth the chunking was added to remove.
            em.flush();
            em.clear();
        });
    }

    static String[] splitCsv(String line) {   // package-private + static: pure, see DhanScripMasterParsingTest
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quote = !quote;
                }
            } else if (ch == ',' && !quote) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells.toArray(new String[0]);
    }

    private void updateJob(Long jobId, String status, String message, Map<String, Object> extra) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            String completedAt = String.valueOf(extra.getOrDefault("completed_at", ""));
            String startedAt = String.valueOf(extra.getOrDefault("started_at", ""));
            String stopGuard = ("stopped".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status))
                ? ""
                : " AND status NOT IN ('stopped','failed')";
            em.createNativeQuery(
                "UPDATE dhan_historical_jobs SET status=?, message=?, started_at=CASE WHEN ?<>'' THEN ? ELSE started_at END, " +
                "completed_at=CASE WHEN ?<>'' THEN ? ELSE completed_at END, updated_at=? WHERE id=?" + stopGuard)
                .setParameter(1, status)
                .setParameter(2, message)
                .setParameter(3, startedAt)
                .setParameter(4, startedAt)
                .setParameter(5, completedAt)
                .setParameter(6, completedAt)
                .setParameter(7, DateUtil.now())
                .setParameter(8, jobId)
                .executeUpdate();
        });
    }

    /**
     * Releases jobs left marked running by a process that no longer exists.
     *
     * <p>A backfill runs on a plain worker thread, so it dies with the JVM - but its row stays
     * {@code running} in the database. Nothing was reading that row before, so the orphan was
     * invisible. Now that {@link #refuseIfAnotherJobIsRunning()} treats the row as the truth, an
     * orphan blocks every subsequent start: on 2026-11-10 job #5 was started at 17:16, the
     * application was restarted at 17:18, and every attempt afterwards was refused on behalf of a
     * job whose thread had been gone for minutes. The symptom reads as "the backfill is very
     * slow" because progress simply stops.
     *
     * <p>Waiting for {@link #markStaleJobs(User)} is not enough: it needs
     * {@value #STALE_JOB_MINUTES} minutes of silence, only runs when somebody tries to start a
     * backfill, and is scoped to one user. At startup the conclusion is certain and global - no
     * worker survives a restart - so say so immediately and leave the partial work resumable.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void releaseOrphanedJobsOnStartup() {
        try {
            int released = new TransactionTemplate(transactionManager).execute(tx ->
                em.createNativeQuery(
                    "UPDATE dhan_historical_jobs SET status='stopped', completed_at=?, updated_at=?, " +
                    "message='Stopped because the application restarted while this backfill was running. " +
                    "Press Resume to continue from the last completed batch.' " +
                    "WHERE status IN ('queued','running','stopping')")
                    .setParameter(1, DateUtil.now())
                    .setParameter(2, DateUtil.now())
                    .executeUpdate());
            if (released > 0) {
                log.warn("[Backfill] Released {} backfill job(s) left running by a previous process. "
                       + "Their worker threads did not survive the restart - press Resume to continue.", released);
            }
        } catch (Exception e) {
            log.warn("[Backfill] Could not release orphaned backfill jobs: {}", e.getMessage());
        }
    }

    /**
     * Refuses to start a second backfill while one is already running.
     *
     * <p>The only guard used to be an in-memory map in DataController, keyed by user id and set
     * after the job had already started. It missed two whole routes. {@link StartupSyncService}
     * calls this method directly, so its two-minute intraday catch-up never saw that map at all;
     * and the map is cleared by a restart, so a job still running in the database looked idle to
     * the next request.
     *
     * <p>The cost of missing it is not a tidy "two jobs are slower" - Dhan rate-limits per user,
     * so every extra job makes all of them fail. Observed on 2026-11-10: jobs 2, 3 and 4 running
     * together with the tracker's own base-price lookups, producing an unbroken wall of DH-904
     * "Too many requests" and importing nothing. The database row is the one piece of state every
     * caller shares, so it is what decides.
     *
     * <p>Stale rows cannot wedge this shut: {@link #markStaleJobs(User)} runs first and fails any
     * job that has recorded no progress for {@value #STALE_JOB_MINUTES} minutes.
     */
    /**
     * True while any Dhan historical backfill job is queued, running or stopping.
     *
     * <p>Lets a scheduled caller decide not to ask at all, instead of asking every two minutes and
     * catching the refusal. The refusal itself is right, but it is an exception, and an exception
     * thrown on a recurring timer reads in the log as a fault when nothing is wrong.
     */
    public boolean isAnyJobActive() {
        try {
            Object c = em.createNativeQuery(
                "SELECT COUNT(*) FROM dhan_historical_jobs WHERE status IN ('queued','running','stopping')")
                .getSingleResult();
            return c instanceof Number n && n.longValue() > 0;
        } catch (Exception e) {
            // Unknown means "do not block" - the same stance refuseIfAnotherJobIsRunning() takes.
            return false;
        }
    }

    private void refuseIfAnotherJobIsRunning() {
        Object[] active;
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT id, status, COALESCE(processed_chunks,0), COALESCE(total_chunks,0), COALESCE(message,'') " +
                "FROM dhan_historical_jobs WHERE status IN ('queued','running','stopping') " +
                "ORDER BY id DESC LIMIT 1").getResultList();
            active = rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            // Never block a fetch because the guard itself could not run.
            log.debug("Dhan historical concurrency check skipped: {}", e.getMessage());
            return;
        }
        if (active == null) return;
        long done = ((Number) active[2]).longValue();
        long total = ((Number) active[3]).longValue();
        String progress = total > 0 ? done + " of " + total + " chunks" : done + " chunks";
        String message = String.valueOf(active[4]);
        throw new IllegalStateException(
            "Dhan historical backfill job #" + active[0] + " is still " + active[1] + " (" + progress + ")"
          + (message.isBlank() ? "" : " - " + message)
          + ". " + ANOTHER_JOB_RUNNING_MARKER + ": Dhan rate-limits per user, so a second one makes "
          + "both fail with \"Too many requests\". Wait for it to finish, or press Stop first.");
    }

    private void markStaleJobs(User user) {
        if (user == null) return;
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                em.createNativeQuery(
                    "UPDATE dhan_historical_jobs SET status='failed', completed_at=?, updated_at=?, " +
                    "message='Dhan historical backfill marked failed because no progress was recorded for " + STALE_JOB_MINUTES + " minutes. Use Retry Failed to continue remaining symbols.' " +
                    "WHERE user_id=? AND status IN ('queued','running','stopping') " +
                    "AND COALESCE(NULLIF(updated_at,''), NULLIF(created_at,''))::timestamp < (NOW() - (? * INTERVAL '1 minute'))")
                    .setParameter(1, DateUtil.now())
                    .setParameter(2, DateUtil.now())
                    .setParameter(3, user.getId())
                    .setParameter(4, STALE_JOB_MINUTES)
                    .executeUpdate());
        } catch (Exception e) {
            log.debug("Dhan historical stale job check skipped: {}", e.getMessage());
        }
    }

    private void updateJobCounts(Long jobId, int totalInstruments, int totalChunks) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
            em.createNativeQuery("UPDATE dhan_historical_jobs SET total_instruments=?, total_chunks=?, updated_at=? WHERE id=? AND status NOT IN ('stopped','failed')")
                .setParameter(1, totalInstruments)
                .setParameter(2, totalChunks)
                .setParameter(3, DateUtil.now())
                .setParameter(4, jobId)
                .executeUpdate());
    }

    private void incrementJob(Long jobId, int chunks, int candles, int failed) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
            em.createNativeQuery("UPDATE dhan_historical_jobs SET processed_chunks=processed_chunks+?, imported_candles=imported_candles+?, failed_chunks=failed_chunks+?, updated_at=? WHERE id=? AND status NOT IN ('stopped','failed')")
                .setParameter(1, chunks)
                .setParameter(2, candles)
                .setParameter(3, failed)
                .setParameter(4, DateUtil.now())
                .setParameter(5, jobId)
                .executeUpdate());
    }

    private void updateProcessedInstruments(Long jobId, int processed) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
            em.createNativeQuery("UPDATE dhan_historical_jobs SET processed_instruments=?, updated_at=? WHERE id=? AND status NOT IN ('stopped','failed')")
                .setParameter(1, processed)
                .setParameter(2, DateUtil.now())
                .setParameter(3, jobId)
                .executeUpdate());
    }

    private void updateCurrent(Long jobId, String symbol) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
            em.createNativeQuery("UPDATE dhan_historical_jobs SET current_symbol=?, updated_at=? WHERE id=? AND status NOT IN ('stopped','failed')")
                .setParameter(1, symbol)
                .setParameter(2, DateUtil.now())
                .setParameter(3, jobId)
                .executeUpdate());
    }

    private boolean stopRequested(Long jobId) {
        Object value = em.createNativeQuery("SELECT stop_requested FROM dhan_historical_jobs WHERE id=?")
            .setParameter(1, jobId)
            .getSingleResult();
        return ((Number) value).intValue() == 1;
    }

    private void incrementInstrumentOutcome(Long jobId, int success, int failed, int skipped) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
            em.createNativeQuery("UPDATE dhan_historical_jobs SET successful_instruments=successful_instruments+?, failed_instruments=failed_instruments+?, skipped_instruments=skipped_instruments+?, updated_at=? WHERE id=? AND status NOT IN ('stopped','failed')")
                .setParameter(1, success)
                .setParameter(2, failed)
                .setParameter(3, skipped)
                .setParameter(4, DateUtil.now())
                .setParameter(5, jobId)
                .executeUpdate());
    }

    /**
     * One line per REASON at the end of a job, instead of one line per symbol during it.
     *
     * <p>A run across ~3,000 instruments logged hundreds of identical warnings, which read as a
     * failing job and buried everything else in the file. Grouping them says the same thing in
     * a form that can actually be acted on: how many, why, and which symbols to look at.
     */
    private void logSkipSummary(Long jobId) {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT COALESCE(error_code,'?'), COUNT(*), " +
                "       STRING_AGG(symbol, ', ' ORDER BY symbol) " +
                "FROM dhan_historical_errors WHERE job_id=? GROUP BY 1 ORDER BY 2 DESC")
                .setParameter(1, jobId).getResultList();
            if (rows.isEmpty()) {
                log.info("[Backfill] Job {} finished with no skipped symbols.", jobId);
                return;
            }
            for (Object[] r : rows) {
                String code = String.valueOf(r[0]);
                long n = ((Number) r[1]).longValue();
                String syms = String.valueOf(r[2]);
                if (syms.length() > 400) syms = syms.substring(0, 400) + " ...";
                log.info("[Backfill] Job {}: {} symbol(s) skipped - {} | {}",
                         jobId, n, explainDhanSkip(code, code), syms);
            }
        } catch (Exception e) {
            log.debug("Skip summary unavailable for job {}: {}", jobId, e.getMessage());
        }
    }

    private Map<String, Object> latestJobById(Long jobId) {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT successful_instruments,failed_instruments,skipped_instruments FROM dhan_historical_jobs WHERE id=?")
                .setParameter(1, jobId)
                .getResultList();
            if (rows.isEmpty()) return Map.of();
            Object[] r = rows.get(0);
            return Map.of("successfulInstruments", r[0], "failedInstruments", r[1], "skippedInstruments", r[2]);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Dhan's error code, in words, with what it means for this fetch.
     *
     * <p>The HTTP layer could only ever say "symbol skipped (no data / bad params)" because it
     * does not know which symbol, which dates, or which of the two very different causes
     * applied. Both are worth telling apart: DH-907 is routine and self-correcting, DH-905
     * means the instrument will never return data and the row is dead weight in the upload.
     */
    private String explainDhanSkip(String errorCode, String errorMessage) {
        String code = errorCode == null ? "" : errorCode.trim().toUpperCase(java.util.Locale.ROOT);
        String body = errorMessage == null ? "" : errorMessage.toLowerCase(java.util.Locale.ROOT);
        if (code.contains("DH-907") || body.contains("dh-907") || body.contains("no data present")) {
            return "Dhan holds no candles for this range - the stock did not trade on those "
                 + "sessions, or is suspended. Nothing to fix; it fills in if it trades again.";
        }
        if (code.contains("DH-905") || body.contains("dh-905") || body.contains("missing required fields")) {
            // This used to read "almost always delisted, or the security id is no longer valid.
            // It will keep failing until it is removed from the uploaded list." That was wrong,
            // and it was advising the removal of live stocks.
            //
            // Checked against the data on 2026-11-24, for the 167 symbols job 18 reported:
            //   - every sampled symbol IS listed on NSE and present in Dhan's own scrip master
            //     with instrument=EQUITY;
            //   - the security id sent matched the master's NSE_EQ id for all of them - no
            //     mismatch, no stale id;
            //   - the request body was well formed and identical in shape to the ~1,100 that
            //     succeeded in the same job;
            //   - 89 of the 167 already hold stored daily candles, so they have returned data
            //     before;
            //   - 156 of the 167 had no bar inside the requested window, which looks like Dhan
            //     answering "no data for this range" with an input-exception code rather than
            //     DH-907 - but 11 of them did trade in the window and still failed, so that does
            //     not explain all of it.
            //
            // So the only honest message is what is actually known. Dhan documents DH-905 as
            // "Missing required fields, bad values for parameters etc.", which describes the
            // request, not the instrument - and we can show the request was fine.
            return "Dhan rejected the request for this instrument with DH-905 (an input error). "
                 + "This does NOT mean the stock is delisted: these are usually NSE-listed with a "
                 + "valid security id, and many already have stored history. It most often "
                 + "accompanies a range in which the stock did not trade. Nothing to remove - "
                 + "check the symbol on Dhan directly before taking it out of the uploaded list.";
        }
        if (code.contains("DH-904") || body.contains("rate") || body.contains("429")) {
            return "Rate limited by Dhan. The job backs off and retries; no data is lost.";
        }
        if (body.contains("401") || body.contains("unauthor") || body.contains("token")) {
            return "Dhan rejected the credentials - the access token has most likely expired. "
                 + "Regenerate it in Broker Connect.";
        }
        return errorMessage == null || errorMessage.isBlank() ? "No reason returned." : errorMessage;
    }

    private void recordBackfillIssue(Long jobId, Instrument instrument, String mode, int interval, DateRange range,
                                     String status, String errorCode, String errorMessage) {
        // Named, dated and explained - the generic HTTP line could give none of these.
        log.debug("[Backfill] {} ({}) {} to {} skipped: {}",
                  instrument.symbol(), instrument.securityId(),
                  range == null ? "?" : range.from(), range == null ? "?" : range.to(),
                  explainDhanSkip(errorCode, errorMessage));
        try {
            DhanRequestLog request = requestLog(instrument, mode, interval, range);
            new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                em.createNativeQuery(
                    "INSERT INTO dhan_historical_errors(job_id,symbol,security_id,exchange_segment,instrument,from_date,to_date,interval_minutes,api_url,request_body,error_code,error_message,status,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                    .setParameter(1, jobId)
                    .setParameter(2, instrument.symbol())
                    .setParameter(3, instrument.securityId())
                    .setParameter(4, normalizeExchangeSegment(instrument.exchangeSegment()))
                    .setParameter(5, historicalInstrument(instrument.instrument()))
                    .setParameter(6, request.fromDate())
                    .setParameter(7, request.toDate())
                    .setParameter(8, request.interval())
                    .setParameter(9, request.apiUrl())
                    .setParameter(10, request.bodyJson())
                    .setParameter(11, errorCode == null || errorCode.isBlank() ? "DHAN_ERROR" : errorCode)
                    .setParameter(12, errorMessage == null || errorMessage.isBlank() ? "Unknown Dhan error" : errorMessage)
                    .setParameter(13, status)
                    .setParameter(14, DateUtil.now())
                    .executeUpdate());
        } catch (Exception logError) {
            log.debug("Could not save Dhan historical error log for {}: {}", instrument.symbol(), logError.getMessage());
        }
    }

    private DhanRequestLog requestLog(Instrument instrument, String mode, int interval, DateRange range) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("securityId", instrument.securityId());
        body.put("exchangeSegment", normalizeExchangeSegment(instrument.exchangeSegment()));
        body.put("instrument", historicalInstrument(instrument.instrument()));
        body.put("oi", isDerivativeInstrument(instrument.instrument()));
        String apiUrl;
        String fromDate;
        String toDate;
        Integer intervalValue = null;
        if ("INTRADAY".equals(mode)) {
            apiUrl = DHAN_INTRADAY_URL;
            intervalValue = Integer.parseInt(normalizeIntradayInterval(interval));
            fromDate = range.from().atTime(9, 15).format(DHAN_INTRADAY);
            toDate = range.to().atTime(15, 30).format(DHAN_INTRADAY);
            body.put("interval", String.valueOf(intervalValue));
        } else {
            apiUrl = DHAN_HISTORICAL_URL;
            fromDate = range.from().format(DHAN_DATE);
            toDate = range.to().plusDays(1).format(DHAN_DATE);
            body.put("expiryCode", 0);
        }
        body.put("fromDate", fromDate);
        body.put("toDate", toDate);
        return new DhanRequestLog(apiUrl, writeJson(body), fromDate, toDate, intervalValue);
    }

    private String dhanErrorCode(Exception e) {
        String text = e == null || e.getMessage() == null ? "" : e.getMessage();
        try {
            JsonNode json = mapper.readTree(text);
            String code = json.path("errorCode").asText("");
            if (!code.isBlank()) return code;
        } catch (Exception ignored) {}
        if (text.contains("DH-905")) return "DH-905";
        if (text.toLowerCase(Locale.ROOT).contains("invalid/test")) return "VALIDATION";
        return "DHAN_ERROR";
    }

    private String dhanErrorMessage(Exception e) {
        String text = e == null || e.getMessage() == null ? "" : e.getMessage();
        try {
            JsonNode json = mapper.readTree(text);
            String message = json.path("errorMessage").asText("");
            if (!message.isBlank()) return message;
            message = json.path("message").asText("");
            if (!message.isBlank()) return message;
        } catch (Exception ignored) {}
        return text.isBlank() ? "Unknown Dhan error" : text;
    }

    private boolean isDhanNoDataError(Exception e) {
        String code = dhanErrorCode(e);
        String message = dhanErrorMessage(e).toLowerCase(Locale.ROOT);
        return "DH-905".equalsIgnoreCase(code) || message.contains("no data present") || message.contains("no historical data") || message.contains("incorrect parameters");
    }

    private String messageFor(String mode, LocalDate requestedFrom, LocalDate effectiveFrom) {
        if ("INTRADAY".equals(mode) && effectiveFrom.isAfter(requestedFrom)) {
            return "Dhan intraday candle API supports the last 5 years only. Start date adjusted from " + requestedFrom + " to " + effectiveFrom + ".";
        }
        return "Dhan historical candle backfill queued.";
    }

    private String exchangeSegment(String exchange, String segment) {
        String ex = norm(exchange);
        String seg = norm(segment);
        if ("NSE".equals(ex) && "E".equals(seg)) return "NSE_EQ";
        if ("BSE".equals(ex) && "E".equals(seg)) return "BSE_EQ";
        if ("NSE".equals(ex) && "D".equals(seg)) return "NSE_FNO";
        if ("BSE".equals(ex) && "D".equals(seg)) return "BSE_FNO";
        if ("MCX".equals(ex)) return "MCX_COMM";
        return ex + "_" + seg;
    }

    private String normalizeInstrument(String value) {
        String v = norm(value);
        if (v.contains("FUTIDX")) return "FUTIDX";
        if (v.contains("FUTSTK")) return "FUTSTK";
        if (v.contains("OPTIDX")) return "OPTIDX";
        if (v.contains("OPTSTK")) return "OPTSTK";
        if (v.contains("INDEX")) return "INDEX";
        if (v.contains("EQUITY") || v.equals("EQ")) return "EQUITY";
        return v.isBlank() ? "EQUITY" : v;
    }

    private String historicalInstrument(String value) {
        String v = normalizeInstrument(value);
        if ("ES".equals(v) || "EQ".equals(v) || v.isBlank()) return "EQUITY";
        return v;
    }

    private String normalizeExchangeSegment(String value) {
        String v = norm(value).replace("-", "_");
        if ("NSE".equals(v) || "NSE_EQ".equals(v)) return "NSE_EQ";
        if ("BSE".equals(v) || "BSE_EQ".equals(v)) return "BSE_EQ";
        if ("NFO".equals(v) || "NSE_FNO".equals(v) || "NSE_FO".equals(v)) return "NSE_FNO";
        if ("BFO".equals(v) || "BSE_FNO".equals(v) || "BSE_FO".equals(v)) return "BSE_FNO";
        if ("MCX".equals(v) || "MCX_COMM".equals(v)) return "MCX_COMM";
        // Index rows arrive from the instrument master as NSE_I / BSE_I (segment_code "I").
        // Dhan's charts API addresses every index, NSE or BSE, as IDX_I.
        if ("NSE_I".equals(v) || "BSE_I".equals(v) || "IDX_I".equals(v) || "I".equals(v)) return "IDX_I";
        return v;
    }

    private String exchangeFromSegment(String segment) {
        // Read the raw value first: BSE_I normalizes to IDX_I, which no longer carries the prefix.
        String raw = norm(segment).replace("-", "_");
        if (raw.startsWith("BSE")) return "BSE";
        if (raw.startsWith("MCX")) return "MCX";
        String value = normalizeExchangeSegment(segment);
        if (value.startsWith("BSE")) return "BSE";
        if (value.startsWith("MCX")) return "MCX";
        return "NSE";
    }

    private boolean isDerivativeInstrument(String value) {
        String v = historicalInstrument(value);
        return v.startsWith("FUT") || v.startsWith("OPT");
    }

    private String normalizeIntradayInterval(int interval) {
        String value = String.valueOf(interval);
        return DHAN_INTRADAY_INTERVALS.contains(value) ? value : "1";
    }

    private LocalDate parseDhanUiDate(Object value, LocalDate fallback) {
        String text = String.valueOf(value == null ? "" : value).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) return fallback;
        try { return LocalDate.parse(text, DHAN_DATE); } catch (Exception ignored) {}
        try { return LocalDate.parse(text, DateTimeFormatter.ofPattern("dd-MM-yyyy")); } catch (Exception ignored) {}
        try { return LocalDate.parse(text, DateTimeFormatter.ofPattern("dd/MM/yyyy")); } catch (Exception ignored) {}
        throw new IllegalArgumentException("Invalid Dhan date '" + text + "'. Use YYYY-MM-DD.");
    }

    private boolean isValidDhanInstrument(Instrument instrument) {
        if (instrument == null) return false;
        String securityId = String.valueOf(instrument.securityId() == null ? "" : instrument.securityId()).trim();
        if (securityId.isBlank()) return false;
        try {
            if (Long.parseLong(securityId) <= 0) return false;
        } catch (Exception e) {
            return false;
        }
        String symbol = norm(instrument.symbol());
        String trading = norm(instrument.tradingSymbol());
        if (symbol.isBlank() && trading.isBlank()) return false;
        String combined = symbol + " " + trading;
        if (combined.contains("TEST") || combined.contains("DUMMY") || combined.contains("SAMPLE")) return false;
        if (combined.matches(".*\\bNULL\\b.*") || combined.matches(".*\\bN/A\\b.*")) return false;
        String segment = normalizeExchangeSegment(instrument.exchangeSegment());
        if (!DHAN_EXCHANGE_SEGMENTS.contains(segment)) return false;
        String inst = historicalInstrument(instrument.instrument());
        if (inst.isBlank()) return false;
        // Indices used to be rejected here unconditionally because no index segment was
        // supported. Dhan does serve their candles, but only under IDX_I.
        if ("INDEX".equals(inst)) return "IDX_I".equals(segment);
        return true;
    }

    private void ensureBackfillAuditSchema() {
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.executeWithoutResult(status -> {
                em.createNativeQuery("ALTER TABLE dhan_historical_jobs ADD COLUMN IF NOT EXISTS successful_instruments INTEGER DEFAULT 0").executeUpdate();
                em.createNativeQuery("ALTER TABLE dhan_historical_jobs ADD COLUMN IF NOT EXISTS failed_instruments INTEGER DEFAULT 0").executeUpdate();
                em.createNativeQuery("ALTER TABLE dhan_historical_jobs ADD COLUMN IF NOT EXISTS skipped_instruments INTEGER DEFAULT 0").executeUpdate();
                // Set by Remove Existing Request. The row and its error log stay; it just stops
                // being the job the Data Warehouse panel reads, so a finished request can be
                // cleared off the screen without destroying what it recorded.
                em.createNativeQuery("ALTER TABLE dhan_historical_jobs ADD COLUMN IF NOT EXISTS dismissed SMALLINT DEFAULT 0").executeUpdate();
                em.createNativeQuery(
                    "CREATE TABLE IF NOT EXISTS dhan_historical_errors (" +
                    "id BIGSERIAL PRIMARY KEY, job_id INTEGER, symbol TEXT, security_id TEXT, exchange_segment TEXT, instrument TEXT, " +
                    "from_date TEXT, to_date TEXT, interval_minutes INTEGER, api_url TEXT, request_body TEXT, " +
                    "error_code TEXT, error_message TEXT, status TEXT, created_at TEXT DEFAULT (CURRENT_TIMESTAMP::text))").executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_dhan_hist_err_job ON dhan_historical_errors(job_id, status, created_at DESC)").executeUpdate();
                // User-supplied corrections for stocks the broker could not match by name.
                // resolved=1 means the correction was matched against the Dhan instrument master
                // and carries a usable security_id, which is what the re-fetch actually needs.
                em.createNativeQuery(
                    "CREATE TABLE IF NOT EXISTS dhan_symbol_overrides (" +
                    "id BIGSERIAL PRIMARY KEY, user_id BIGINT, original_symbol TEXT NOT NULL, " +
                    "corrected_symbol TEXT, isin TEXT, security_id TEXT, exchange_segment TEXT, instrument TEXT, " +
                    "matched_name TEXT, resolved INTEGER DEFAULT 0, resolve_note TEXT, " +
                    "created_at TEXT, updated_at TEXT)").executeUpdate();
                em.createNativeQuery(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_dhan_sym_override_key " +
                    "ON dhan_symbol_overrides(user_id, UPPER(TRIM(original_symbol)))").executeUpdate();
            });
        } catch (Exception e) {
            log.warn("Dhan historical audit schema check failed: {}", e.getMessage());
        }
    }

    private void createBatchRecords(Long jobId, LocalDate from, LocalDate to) {
        List<DateRange> months = monthlyBatches(from, to);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < months.size(); i++) {
                DateRange m = months.get(i);
                String monthLabel = windowLabel(m);
                em.createNativeQuery(
                    "INSERT INTO dhan_backfill_batches(job_id,batch_index,month_label,from_date,to_date,status,created_at) VALUES(?,?,?,?,?,?,?)")
                    .setParameter(1, jobId).setParameter(2, i).setParameter(3, monthLabel)
                    .setParameter(4, m.from().toString()).setParameter(5, m.to().toString())
                    .setParameter(6, "pending").setParameter(7, DateUtil.now())
                    .executeUpdate();
            }
        });
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getBatches(User user) {
        Long jobId = latestJobId(user);
        if (jobId == null) return List.of();
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id,job_id,batch_index,month_label,from_date,to_date,status,total_instruments," +
            "processed_instruments,imported_candles,failed_instruments,skipped_instruments," +
            "successful_instruments,error_message,started_at,completed_at FROM dhan_backfill_batches " +
            "WHERE job_id=? ORDER BY batch_index")
            .setParameter(1, jobId).getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        String[] keys = {"id","jobId","batchIndex","monthLabel","fromDate","toDate","status",
            "totalInstruments","processedInstruments","importedCandles","failedInstruments",
            "skippedInstruments","successfulInstruments","errorMessage","startedAt","completedAt"};
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < keys.length && i < r.length; i++) m.put(keys[i], r[i]);
            result.add(m);
        }
        return result;
    }

    public Map<String, Object> retryBatch(User user, Long batchId) {
        ensureBackfillAuditSchema();
        markStaleJobs(user);
        @SuppressWarnings("unchecked")
        List<Object[]> batchRows = em.createNativeQuery(
            "SELECT b.job_id,b.from_date,b.to_date,b.batch_index,b.month_label,j.mode,j.interval_minutes,j.exchanges,j.segments,j.instrument_filter " +
            "FROM dhan_backfill_batches b JOIN dhan_historical_jobs j ON j.id=b.job_id WHERE b.id=? AND b.status IN ('failed','partially_completed')")
            .setParameter(1, batchId).getResultList();
        if (batchRows.isEmpty()) throw new IllegalStateException("Batch not found or not in a retryable state.");
        Object[] br = batchRows.get(0);
        Long jobId = ((Number) br[0]).longValue();
        LocalDate from = parseDhanUiDate(br[1], LocalDate.now(IST));
        LocalDate to = parseDhanUiDate(br[2], LocalDate.now(IST));
        String mode = norm(br[5]);
        int interval = br[6] == null ? 1 : ((Number) br[6]).intValue();
        String exchanges = String.valueOf(br[7]);
        String segments = String.valueOf(br[8]);
        String instrumentFilter = String.valueOf(br[9]);
        DhanBrokerService.Credential credential = dhanBrokerService.activeDhanCredential(user);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
            em.createNativeQuery("UPDATE dhan_backfill_batches SET status='pending',error_message=NULL,processed_instruments=0," +
                "imported_candles=0,failed_instruments=0,skipped_instruments=0,successful_instruments=0," +
                "started_at=NULL,completed_at=NULL WHERE id=?")
                .setParameter(1, batchId).executeUpdate());
        Thread worker = new Thread(() -> runSingleBatch(jobId, batchId, credential, mode, interval, from, to, exchanges, segments, instrumentFilter, 0),
            "dhan-batch-retry-" + batchId);
        worker.setDaemon(true);
        worker.start();
        return Map.of("batchId", batchId, "status", "retrying", "message", "Retrying batch " + br[4] + " (" + from + " to " + to + ").");
    }

    private void runSingleBatch(Long jobId, Long batchId, DhanBrokerService.Credential credential,
                                String mode, int interval, LocalDate from, LocalDate to,
                                String exchanges, String segments, String instrumentFilter, int maxInstruments) {
        try {
            updateBatchStatus(batchId, "running", null, Map.of("started_at", DateUtil.now()));
            List<Instrument> instruments = loadInstruments(exchanges, segments, instrumentFilter, maxInstruments);
            updateBatchInstrumentCount(batchId, instruments.size());
            Map<String, String> headers = Map.of(
                "Accept", "application/json", "Content-Type", "application/json",
                "access-token", credential.accessToken(), "client-id", credential.clientId());
            int processed = 0, imported = 0, success = 0, failed = 0, skipped = 0;
            final long batchStartedMs = System.currentTimeMillis();
            for (Instrument instrument : instruments) {
                if (stopRequested(jobId)) {
                    updateBatchStatus(batchId, "stopped", "Stopped by user.", Map.of("completed_at", DateUtil.now()));
                    return;
                }
                try {
                    JsonNode json = fetchCandlesWithRetry(headers, instrument, mode, interval, new DateRange(from, to));
                    int count = saveCandles(jobId, credential.id(), instrument, mode, interval, json);
                    imported += count;
                    if (count > 0) {
                        success++;
                    } else {
                        // Dhan answered without error but returned nothing for this stock. That is a
                        // skip, and it has to be written down: the counter alone left 731 stocks
                        // invisible to "View Failed / Skipped", so they could be neither reviewed nor
                        // corrected. The main (non-batch) path already records this case.
                        skipped++;
                        recordBackfillIssue(jobId, instrument, mode, interval, new DateRange(from, to),
                            "SKIPPED", "NO_DATA", "Dhan returned no candles for this period.");
                    }
                } catch (Exception e) {
                    if (isDhanNoDataError(e)) skipped++;
                    else failed++;
                    recordBackfillIssue(jobId, instrument, mode, interval, new DateRange(from, to),
                        isDhanNoDataError(e) ? "SKIPPED" : "FAILED", dhanErrorCode(e), dhanErrorMessage(e));
                }
                processed++;
                updateBatchProgress(batchId, processed, imported, success, failed, skipped);

                // Progress heartbeat. A backfill of a few thousand symbols runs for hours and
                // used to log nothing at all between "Loaded N instruments" and the final line,
                // so a healthy job and a hung one looked identical in the log. One line every
                // 250 symbols is enough to see it moving without reinstating per-symbol spam.
                if (processed % 250 == 0 || processed == instruments.size()) {
                    long elapsedSec = Math.max(1, (System.currentTimeMillis() - batchStartedMs) / 1000);
                    long rate = processed / elapsedSec;
                    long remainingSec = rate > 0 ? (instruments.size() - processed) / rate : -1;
                    log.info("[Backfill] {}/{} processed ({}%) - saved {}, no data {}, failed {}, {} candles in {}s{}",
                        processed, instruments.size(), processed * 100 / Math.max(1, instruments.size()),
                        success, skipped, failed, imported, elapsedSec,
                        remainingSec >= 0 ? "; about " + (remainingSec / 60) + "m left" : "");
                }
            }
            String status = failed > 0 && success > 0 ? "partially_completed" : failed > 0 ? "failed" : "completed";
            updateBatchStatus(batchId, status, "Batch finished. Success: " + success + ", Failed: " + failed + ", Skipped: " + skipped + ".",
                Map.of("completed_at", DateUtil.now()));
        } catch (Exception e) {
            updateBatchStatus(batchId, "failed", friendlyJobError(e), Map.of("completed_at", DateUtil.now()));
        }
    }

    /**
     * The single retry layer for candle calls. {@link #fetchCandles} deliberately uses
     * {@code postJson} rather than {@code postJsonWithRetry}: there used to be two nested retry
     * loops - 4 HTTP attempts inside 5 of these - so one rate-limited chunk could cost twenty
     * requests. Worse, the inner loop fired its attempts on its own 1s/2s/4s schedule without
     * going back through {@link #historicalThrottle()}, so the adaptive gap was bypassed exactly
     * when it mattered. Measured on 2026-11-10: 330 chunks hit the limit and 506 requests were
     * spent on them; BOROSCI alone burned 20 requests over two minutes and returned nothing.
     * Retrying here instead means every failure also widens the gap, which is the response the
     * API is actually asking for.
     */
    private JsonNode fetchCandlesWithRetry(Map<String, String> headers, Instrument instrument, String mode, int interval, DateRange range) throws Exception {
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                JsonNode ok = fetchCandles(headers, instrument, mode, interval, range);
                noteCleanHistoricalCall();
                return ok;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
                boolean rateLimited = msg.contains("429") || msg.contains("dh-904")
                    || msg.contains("rate limit") || msg.contains("too many");
                // A timeout is also a signal we are pushing too hard, just a weaker one, so it
                // widens the gap as well rather than only being retried.
                if (rateLimited) widenHistoricalGap("rate limit");
                else if (msg.contains("timeout") || msg.contains("timed out")) widenHistoricalGap("timeout");
                boolean retryable = msg.contains("timeout") || msg.contains("timed out") || msg.contains("429")
                    || msg.contains("dh-904") || msg.contains("rate limit") || msg.contains("too many");
                if (!retryable || attempt == maxRetries) throw e;
                log.info("Dhan API retry {}/{} for {} ({})", attempt, maxRetries, instrument.symbol(), msg.substring(0, Math.min(80, msg.length())));
                long delayMs = msg.contains("dh-904") || msg.contains("rate limit") || msg.contains("too many")
                    ? attempt * 8000L
                    : attempt * 2500L;
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        throw new RuntimeException("Max retries exhausted for " + instrument.symbol());
    }

    private void updateBatchStatus(Long batchId, String status, String errorMessage, Map<String, Object> extra) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            String completedAt = String.valueOf(extra.getOrDefault("completed_at", ""));
            String startedAt = String.valueOf(extra.getOrDefault("started_at", ""));
            em.createNativeQuery(
                "UPDATE dhan_backfill_batches SET status=?, error_message=COALESCE(?,error_message), " +
                "started_at=CASE WHEN ?<>'' THEN ? ELSE started_at END, " +
                "completed_at=CASE WHEN ?<>'' THEN ? ELSE completed_at END WHERE id=?")
                .setParameter(1, status).setParameter(2, errorMessage)
                .setParameter(3, startedAt).setParameter(4, startedAt)
                .setParameter(5, completedAt).setParameter(6, completedAt)
                .setParameter(7, batchId).executeUpdate();
        });
    }

    private void updateBatchInstrumentCount(Long batchId, int total) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
            em.createNativeQuery("UPDATE dhan_backfill_batches SET total_instruments=? WHERE id=?")
                .setParameter(1, total).setParameter(2, batchId).executeUpdate());
    }

    private void updateBatchProgress(Long batchId, int processed, int imported, int success, int failed, int skipped) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
            em.createNativeQuery("UPDATE dhan_backfill_batches SET processed_instruments=?,imported_candles=?," +
                "successful_instruments=?,failed_instruments=?,skipped_instruments=? WHERE id=?")
                .setParameter(1, processed).setParameter(2, imported)
                .setParameter(3, success).setParameter(4, failed).setParameter(5, skipped)
                .setParameter(6, batchId).executeUpdate());
    }


    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { return "{}"; }
    }

    private Double number(String value) {
        try { return value == null || value.isBlank() ? null : Double.parseDouble(value); }
        catch (Exception e) { return null; }
    }

    private int intValue(Object value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "null".equalsIgnoreCase(String.valueOf(value))) return null;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception e) { return null; }
    }

    private boolean booleanValue(Object value) {
        if (value == null) return false;
        String s = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private double at(JsonNode json, String field, int index) {
        JsonNode arr = json.path(field);
        return arr.isArray() && index < arr.size() ? arr.get(index).asDouble(0) : 0;
    }

    private String quotedList(String csv, List<String> fallback) {
        List<String> values = Arrays.stream((csv == null || csv.isBlank() ? String.join(",", fallback) : csv).split(","))
            .map(String::trim)
            .filter(v -> !v.isBlank() && v.matches("[A-Za-z0-9_]+"))
            .map(v -> "'" + v.toUpperCase(Locale.ROOT) + "'")
            .collect(Collectors.toList());
        return values.isEmpty() ? "'NSE','BSE'" : String.join(",", values);
    }

    private String safeLike(String text) {
        return norm(text).replace("'", "").replace("%", "").replace("_", "");
    }

    private String norm(Object value) {
        return String.valueOf(value == null ? "" : value).trim().toUpperCase(Locale.ROOT);
    }

    private String csvCell(Object value) {
        String text = String.valueOf(value == null ? "" : value).replace("\"", "\"\"");
        return "\"" + text + "\"";
    }

    private record DateRange(LocalDate from, LocalDate to) {}
    private record Instrument(String securityId, String exchange, String exchangeSegment, String instrument, String symbol, String tradingSymbol) {}
    private record DhanRequestLog(String apiUrl, String bodyJson, String fromDate, String toDate, Integer interval) {}
}
