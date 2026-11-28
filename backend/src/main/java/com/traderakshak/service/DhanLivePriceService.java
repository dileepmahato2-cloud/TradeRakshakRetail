package com.traderakshak.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.util.DateUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class DhanLivePriceService {

    private static final String DHAN_FEED_URL = "wss://api-feed.dhan.co";
    private static final int REQUEST_SUBSCRIBE_QUOTE = 17;

    /**
     * Bytes a Dhan v2 Quote packet carries AFTER the last traded price.
     *
     * <p>The packet is 50 bytes: an 8-byte header, a 4-byte LTP, then 38 bytes of
     * last-traded-qty, LTT, ATP, volume, total sell/buy quantity and open/close/high/low. The
     * parser seeks past the header and the LTP, so exactly 38 bytes remain at that point.
     *
     * <p>This guard read {@code >= 42}. Four bytes too many, so for every quote packet ever
     * received the test failed, the branch below was skipped, and each tick was reduced to its
     * LTP. Measured on the live feed: 1,305 of 1,305 quotes had {@code ltp > 0} and volume,
     * open and close all zero, with high and low equal to the LTP - those two only because of
     * the {@code high <= 0} fallback further down, which disguised the gap as a flat bar rather
     * than a missing one.
     *
     * <p>The cost was not cosmetic. Both RSI 45 sections test current-day volume against a
     * 20-session average, and with no live volume that condition fell back to the last stored
     * daily figure all session. The feed had been delivering the number the whole time; nothing
     * read it.
     */
    private static final int QUOTE_BYTES_AFTER_LTP = 38;
    private static final int RESPONSE_TICKER = 2;
    private static final int RESPONSE_QUOTE = 4;
    private static final int RESPONSE_FULL = 8;
    private static final int RESPONSE_DISCONNECT = 50;
    private static final int MAX_CONNECTION_INSTRUMENTS = 5000;
    private static final int SUBSCRIPTION_CHUNK_SIZE = 100;
    private static final int STALE_SECONDS = 45;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final DhanBrokerService dhanBrokerService;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactionManager;
    private final LivePriceBroadcaster broadcaster;
    private final AppConfigService appConfig;

    /**
     * Builds 5-minute bars from the tick stream. Injected by field and optional on purpose: the
     * live feed must keep working even if candle building is disabled or fails to start, since
     * prices drive the screen and the ranking while bars are only a recording of them.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LiveCandleAggregator candleAggregator;

    @PersistenceContext
    private EntityManager em;

    private final AtomicReference<HttpClient> httpClientRef = new AtomicReference<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DhanLivePriceService");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);
    /**
     * Dhan caps a single feed connection at {@link #MAX_CONNECTION_INSTRUMENTS}. Covering a
     * whole exchange segment (NSE_EQ EQUITY is ~9,600 symbols) therefore needs more than one
     * connection — previously the extra symbols were silently dropped by a .limit() call.
     */
    private final List<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final Map<String, DhanLiveInstrument> subscribed = new ConcurrentHashMap<>();
    private final Map<String, LiveQuote> latestQuotes = new ConcurrentHashMap<>();
    /**
     * Quotes changed since the last database flush, keyed the same way as latestQuotes.
     *
     * <p>Every tick used to open its own transaction. Measured on this database: 3.0M UPDATEs
     * against 2,210 rows in tracked_stocks and 3.0M against 2,381 rows in live_price_quotes,
     * of which only 2.4% were HOT — so 5,900 rows of actual data had bloated to 112 MB, and
     * any bulk statement (a Delete All, for instance) sat behind those row locks until the
     * 180s statement timeout killed it. Ticks now land in memory and are written in one
     * batched transaction per interval; the UI still updates immediately over SSE.
     */
    private final Map<String, LiveQuote> pendingQuotes = new ConcurrentHashMap<>();
    private final AtomicLong flushBatches = new AtomicLong();
    private final AtomicLong ticksReceived = new AtomicLong();
    private final AtomicLong ticksIgnored = new AtomicLong();
    private final AtomicLong dbUpdates = new AtomicLong();
    private final AtomicLong reconnectCount = new AtomicLong();
    private final AtomicLong lastTickMillis = new AtomicLong(0);
    private final AtomicLong lastConnectAttemptMillis = new AtomicLong(0);
    private final AtomicReference<String> connectionStatus = new AtomicReference<>("DISCONNECTED");
    private final AtomicReference<String> websocketStatus = new AtomicReference<>("CLOSED");
    private final AtomicReference<String> apiStatus = new AtomicReference<>("UNKNOWN");
    private final AtomicReference<String> lastApiError = new AtomicReference<>("");
    private final AtomicReference<String> currentProcessingSymbol = new AtomicReference<>("");
    private final AtomicReference<List<String>> missingMappingSymbols = new AtomicReference<>(List.of());
    private volatile long ticksPerSecond;
    private volatile long dbUpdatesPerSecond;
    private volatile long uiUpdatesPerSecond;
    private long prevTicks;
    private long prevDbUpdates;
    private long prevUiUpdates;

    @PostConstruct
    public void start() {
        executor.schedule(this::connectSafely, 3, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        closeAllSockets("application shutdown");
        try { flushPendingQuotes(); } catch (Exception ignored) { /* best effort on the way out */ }
        executor.shutdownNow();
    }

    /** Closes every open feed connection; returns how many were closed. */
    private int closeAllSockets(String reason) {
        int closed = 0;
        for (WebSocket ws : sockets) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, reason); closed++; } catch (Exception ignored) {}
        }
        sockets.clear();
        return closed;
    }

    public void restart() {
        logEvent("RECONNECTING", "INFO", null, "Manual live price reconnect requested.", null);
        WebSocket ws = sockets.isEmpty() ? null : sockets.get(0);
        closeAllSockets("reconnect");
        if (ws != null) {
            try { ws.abort(); } catch (Exception ignored) {}
        }
        connectionStatus.set("RECONNECTING");
        websocketStatus.set("CLOSED");
        executor.schedule(this::connectSafely, 1, TimeUnit.SECONDS);
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("connectionStatus", connectionStatus.get());
        status.put("webSocketStatus", websocketStatus.get());
        status.put("apiStatus", apiStatus.get());
        status.put("subscribedSymbols", subscribed.size());
        status.put("pendingWrites", pendingQuotes.size());
        status.put("flushBatches", flushBatches.get());
        status.put("ticksReceivedPerSecond", ticksPerSecond);
        status.put("databaseUpdatesPerSecond", dbUpdatesPerSecond);
        status.put("uiUpdatesPerSecond", uiUpdatesPerSecond);
        status.put("reconnectCount", reconnectCount.get());
        status.put("lastTickTime", lastTickMillis.get() > 0 ? formatMillis(lastTickMillis.get()) : "");
        status.put("lastApiError", lastApiError.get());
        status.put("memoryUsageMb", memoryUsageMb());
        status.put("cpuUsagePercent", cpuUsagePercent());
        status.put("currentProcessingSymbol", currentProcessingSymbol.get());
        List<String> missingMappings = missingMappingSymbols.get();
        status.put("missingMappingCount", missingMappings.size());
        status.put("missingMappingSymbols", missingMappings.stream().limit(12).toList());
        status.put("ticksReceived", ticksReceived.get());
        status.put("ticksIgnored", ticksIgnored.get());
        status.put("databaseUpdates", dbUpdates.get());
        status.put("sseClients", broadcaster.clientCount());
        return status;
    }

    public Collection<Map<String, Object>> latestQuotes() {
        return latestQuotes.values().stream()
            .sorted(Comparator.comparing(LiveQuote::symbol))
            .map(LiveQuote::toMap)
            .toList();
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 20000)
    public void ensureConnected() {
        if (!isMarketOpen()) {
            pauseForMarketClose();
            return;
        }
        long last = lastTickMillis.get();
        boolean stale = !sockets.isEmpty() && last > 0 && System.currentTimeMillis() - last > STALE_SECONDS * 1000L;
        if (sockets.isEmpty() || stale) {
            if (stale) {
                lastApiError.set("Dhan WebSocket stale: no tick for " + STALE_SECONDS + " seconds.");
                logEvent("STALE_CONNECTION", "WARN", null, lastApiError.get(), null);
                WebSocket ws = sockets.isEmpty() ? null : sockets.get(0);
        closeAllSockets("reconnect");
                if (ws != null) ws.abort();
            }
            long sinceAttempt = System.currentTimeMillis() - lastConnectAttemptMillis.get();
            if (sinceAttempt > 10000) connectSafely();
        }
    }

    private boolean isMarketOpen() {
        LocalDateTime now = LocalDateTime.now(IST);
        int dow = now.getDayOfWeek().getValue();
        if (dow >= 6) return false;
        int time = now.getHour() * 100 + now.getMinute();
        return time >= 915 && time <= 1530;
    }

    /**
     * Writes all quotes buffered since the last run in a single transaction. Interval is
     * livefeed.persist.interval_seconds (default 5): at ~105 ticks/second that turns roughly
     * 525 transactions into one, without changing what the screen shows.
     */
    @Scheduled(fixedDelayString = "${livefeed.persist.interval.ms:5000}")
    public void flushPendingQuotes() {
        if (pendingQuotes.isEmpty()) return;
        List<LiveQuote> batch = new ArrayList<>();
        for (String key : new ArrayList<>(pendingQuotes.keySet())) {
            LiveQuote q = pendingQuotes.remove(key);   // remove first: a tick arriving during
            if (q != null) batch.add(q);               // the flush re-marks the symbol dirty
        }
        if (batch.isEmpty()) return;

        // Same lock order as the refresh path's batch upsert in StockTrackerService. pendingQuotes
        // is a ConcurrentHashMap, so its iteration order varies from flush to flush - which is
        // precisely how this thread and an HTTP refresh thread ended up taking the same two row
        // locks in opposite orders and deadlocking on live_price_quotes.
        batch.sort((a, b2) -> (String.valueOf(a.exchangeSegment()) + '|' + String.valueOf(a.securityId()))
            .compareTo(String.valueOf(b2.exchangeSegment()) + '|' + String.valueOf(b2.securityId())));

        try {
            int changed = withLockRetry("quote flush (" + batch.size() + ")", () -> {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setTimeout(30);
                Integer rows = tx.execute(status -> {
                    setLocalLockTimeout();
                    int n = 0;
                    for (LiveQuote q : batch) n += persistQuote(q);
                    return n;
                });
                return rows == null ? 0 : rows;
            });
            if (changed > 0) dbUpdates.addAndGet(changed);
            flushBatches.incrementAndGet();
        } catch (Exception e) {
            // Put them back so the next flush retries rather than losing the prices.
            for (LiveQuote q : batch) pendingQuotes.putIfAbsent(q.key(), q);
            log.warn("Quote flush of {} symbol(s) failed, will retry: {}", batch.size(), e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void publishMonitoringStatus() {
        long tickNow = ticksReceived.get();
        long dbNow = dbUpdates.get();
        long uiNow = broadcaster.uiUpdates();
        ticksPerSecond = Math.max(0, tickNow - prevTicks);
        dbUpdatesPerSecond = Math.max(0, dbNow - prevDbUpdates);
        uiUpdatesPerSecond = Math.max(0, uiNow - prevUiUpdates);
        prevTicks = tickNow;
        prevDbUpdates = dbNow;
        prevUiUpdates = uiNow;
        if (broadcaster.clientCount() > 0) {
            broadcaster.sendStatus(status());
        }
    }

    private void connectSafely() {
        if (!connecting.compareAndSet(false, true)) return;
        try {
            if (!isMarketOpen()) {
                pauseForMarketClose();
                return;
            }
            // Ask the stored expiry BEFORE spending a connection on a token we already know is
            // dead.
            //
            // A Dhan access token lives about 24 hours, so it expires every single day. The expiry
            // was being stored and shown on the Broker screen and consulted by nothing: this
            // method simply connected, got AUTHENTICATION_FAILED, and tried again fifteen seconds
            // later. On 2026-11-24 that produced 156 identical failures between 09:15 and 15:30,
            // no live price anywhere in the application for a whole session, and no statement
            // anywhere of the one fact that would have fixed it in a minute.
            String expired = expiredTokenMessage();
            if (expired != null) {
                connectionStatus.set("DISCONNECTED");
                websocketStatus.set("CLOSED");
                apiStatus.set("TOKEN_EXPIRED");
                lastApiError.set(expired);
                // Logged once per transition rather than on every attempt: 156 copies of one
                // sentence is how the real message got lost in the first place.
                if (tokenExpiryReported.compareAndSet(false, true)) {
                    log.warn("[LiveFeed] {}", expired);
                    logEvent("TOKEN_EXPIRED", "ERROR", null, expired, null);
                }
                return;
            }
            tokenExpiryReported.set(false);
            ensureLivePriceSchema();
            connect();
        } catch (Exception e) {
            connectionStatus.set("DISCONNECTED");
            websocketStatus.set("CLOSED");
            apiStatus.set("FAILED");
            lastApiError.set(safeError(e.getMessage()));
            logEvent("EXCEPTION", "ERROR", null, "Live price connection failed: " + safeError(e.getMessage()), null);
            scheduleReconnect();
        } finally {
            connecting.set(false);
        }
    }

    /** So one sentence is logged per transition, not once per fifteen-second retry. */
    private final java.util.concurrent.atomic.AtomicBoolean tokenExpiryReported =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * A plain statement of the problem when the stored Dhan token has expired, else null.
     *
     * <p>Reads {@code broker_credentials.token_expiry}, which the Broker screen already writes on
     * every re-authentication. Compared as an ISO string prefix rather than parsed, because the
     * column is TEXT and has carried more than one format - and a parse failure must read as "I
     * cannot tell", never as "expired", or a formatting change would silently stop the feed.
     *
     * <p>Only an expiry that is definitely in the past blocks a connection. Anything unreadable,
     * missing, or in the future lets the attempt proceed exactly as before.
     */
    private String expiredTokenMessage() {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT COALESCE(token_expiry,''), COALESCE(broker_label, broker_name), id "
              + "FROM broker_credentials WHERE broker_name='dhan' AND is_active=1 AND is_verified=1 "
              + "ORDER BY updated_at DESC NULLS LAST LIMIT 1").getResultList();
            if (rows.isEmpty()) return null;
            String expiry = String.valueOf(rows.get(0)[0]).trim();
            if (expiry.length() < 10) return null;          // nothing stored - let it try
            // 'yyyy-MM-ddTHH:mm:ss...' or 'yyyy-MM-dd HH:mm:ss'. Normalise the separator so one
            // string comparison covers both.
            String nowIso = LocalDateTime.now(IST).toString().replace('T', ' ');
            String expIso = expiry.replace('T', ' ');
            if (expIso.compareTo(nowIso) > 0) return null;  // still valid
            return "Dhan access token expired at " + expiry.substring(0, Math.min(19, expiry.length()))
                 + ". Live prices will not update anywhere in the application until it is renewed - "
                 + "open Broker, re-authenticate Dhan, and the feed reconnects on its own within "
                 + "fifteen seconds. A Dhan token lasts about 24 hours, so this is due daily "
                 + "before the 09:15 open.";
        } catch (Exception e) {
            // Unknown must not block the feed. Falling through means the worst case is the old
            // behaviour, which is strictly better than refusing to connect over a failed query.
            log.debug("[LiveFeed] Token expiry check skipped: {}", e.getMessage());
            return null;
        }
    }

    private void pauseForMarketClose() {
        WebSocket ws = sockets.isEmpty() ? null : sockets.get(0);
        closeAllSockets("reconnect");
        if (ws != null) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "market closed"); } catch (Exception ignored) {}
        }
        connectionStatus.set("MARKET_CLOSED");
        websocketStatus.set("PAUSED");
        Optional<LiveCredential> credential = activeCredential();
        if (credential.isEmpty()) {
            apiStatus.set("AUTHENTICATION_FAILED");
            lastApiError.set("No verified Dhan Client ID and Access Token found. Reconnect Dhan from Broker settings.");
        } else if (credential.get().expired()) {
            apiStatus.set("TOKEN_EXPIRED");
            lastApiError.set("Dhan token expired. Re-authenticate Dhan in Broker Connect.");
        } else {
            apiStatus.set("AUTHENTICATION_SUCCESS");
            lastApiError.set("");
        }
        currentProcessingSymbol.set("");
    }

    private void ensureLivePriceSchema() {
        if (schemaReady.get()) {
            return;
        }
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.executeWithoutResult(status -> {
                em.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
                em.createNativeQuery("SET LOCAL statement_timeout = '10s'").executeUpdate();
                // Every one of these is checked against the catalogue first. ADD COLUMN IF NOT
                // EXISTS still asks for an ACCESS EXCLUSIVE lock even when the column is already
                // there, so on a busy table it waits out the whole lock timeout and then fails -
                // costing seconds of startup and logging an ERROR, for a column that never needed
                // touching. Reading information_schema takes no lock at all.
                addColumnIfMissing("tracked_stocks", "current_price_source", "TEXT");
                addColumnIfMissing("tracked_stocks", "trading_symbol",       "TEXT");
                addColumnIfMissing("tracked_stocks", "dhan_security_id",     "TEXT");
                addColumnIfMissing("tracked_stocks", "exchange_segment",     "TEXT");
                addColumnIfMissing("tracked_stocks", "last_traded_price",    "REAL");
                addColumnIfMissing("tracked_stocks", "last_trade_time",      "TEXT");
                addColumnIfMissing("tracked_stocks", "price_updated_at",     "TEXT");
                em.createNativeQuery("""
                    CREATE TABLE IF NOT EXISTS live_price_quotes (
                        id                    BIGSERIAL PRIMARY KEY,
                        broker_name            TEXT NOT NULL DEFAULT 'dhan',
                        exchange_segment       TEXT NOT NULL,
                        broker_security_id     TEXT NOT NULL,
                        dhan_security_id       TEXT,
                        symbol                 TEXT NOT NULL,
                        trading_symbol         TEXT,
                        current_price          REAL,
                        last_traded_price      REAL,
                        bid_price              REAL,
                        ask_price              REAL,
                        open_price             REAL,
                        high_price             REAL,
                        low_price              REAL,
                        close_price            REAL,
                        volume                 REAL,
                        last_trade_time        TEXT,
                        change_value           REAL,
                        change_percent         REAL,
                        tick_received_at       TEXT,
                        price_updated_at       TEXT,
                        raw_json               TEXT,
                        created_at             TEXT DEFAULT (CURRENT_TIMESTAMP::text),
                        updated_at             TEXT,
                        UNIQUE(broker_name, exchange_segment, broker_security_id)
                    )
                    """).executeUpdate();
                em.createNativeQuery("ALTER TABLE live_price_quotes ADD COLUMN IF NOT EXISTS dhan_security_id TEXT").executeUpdate();
                em.createNativeQuery("ALTER TABLE live_price_quotes ADD COLUMN IF NOT EXISTS last_traded_price REAL").executeUpdate();
                em.createNativeQuery("ALTER TABLE live_price_quotes ADD COLUMN IF NOT EXISTS price_updated_at TEXT").executeUpdate();
                // Aggregate book quantities from the Quote packet. BIGINT, not REAL: these are
                // share counts that routinely run into crores, and REAL has 24 bits of mantissa -
                // it starts losing whole shares above about 1.7 crore, so a large-cap book would
                // be stored approximately. The gate only asks whether they are zero, but the
                // figures are displayed too, and a displayed share count should be exact.
                em.createNativeQuery("ALTER TABLE live_price_quotes ADD COLUMN IF NOT EXISTS total_buy_quantity BIGINT DEFAULT 0").executeUpdate();
                em.createNativeQuery("ALTER TABLE live_price_quotes ADD COLUMN IF NOT EXISTS total_sell_quantity BIGINT DEFAULT 0").executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_lpq_symbol ON live_price_quotes(symbol, updated_at DESC)").executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_lpq_tick ON live_price_quotes(tick_received_at DESC)").executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_ts_dhan_security ON tracked_stocks(dhan_security_id, exchange_segment)").executeUpdate();
                em.createNativeQuery("""
                    CREATE TABLE IF NOT EXISTS live_price_subscriptions (
                        id                    BIGSERIAL PRIMARY KEY,
                        broker_name            TEXT NOT NULL DEFAULT 'dhan',
                        institution_id         INTEGER,
                        symbol                 TEXT NOT NULL,
                        trading_symbol         TEXT,
                        underlying_symbol      TEXT,
                        exchange_segment       TEXT,
                        broker_security_id     TEXT,
                        status                 TEXT NOT NULL,
                        reason                 TEXT,
                        subscribed_at          TEXT,
                        last_tick_at           TEXT,
                        created_at             TEXT DEFAULT (CURRENT_TIMESTAMP::text),
                        updated_at             TEXT,
                        UNIQUE(broker_name, institution_id, symbol)
                    )
                    """).executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_lps_status ON live_price_subscriptions(status, updated_at DESC)").executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_lps_security ON live_price_subscriptions(broker_name, exchange_segment, broker_security_id)").executeUpdate();
                em.createNativeQuery("""
                    CREATE TABLE IF NOT EXISTS live_price_service_events (
                        id                    BIGSERIAL PRIMARY KEY,
                        broker_name            TEXT NOT NULL DEFAULT 'dhan',
                        event_type             TEXT NOT NULL,
                        severity               TEXT DEFAULT 'INFO',
                        symbol                 TEXT,
                        exchange_segment       TEXT,
                        broker_security_id     TEXT,
                        message                TEXT,
                        details_json           TEXT,
                        created_at             TEXT DEFAULT (CURRENT_TIMESTAMP::text)
                    )
                    """).executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_lpse_created ON live_price_service_events(created_at DESC)").executeUpdate();
            });
            schemaReady.set(true);
        } catch (Exception e) {
            lastApiError.set("Live price schema check failed: " + safeError(e.getMessage()));
            log.warn("Live price schema check failed: {}", e.getMessage());
        }
    }

    /** Adds a column only when the catalogue says it is absent, so the common path takes no lock. */
    private void addColumnIfMissing(String table, String column, String type) {
        if (columnExists(table, column)) return;
        try {
            em.createNativeQuery("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS "
                               + column + " " + type).executeUpdate();
        } catch (Exception e) {
            log.warn("[LivePrice] Could not add {}.{}: {}", table, column, e.getMessage());
        }
    }

    private boolean columnExists(String table, String column) {
        try {
            Object count = em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=? AND column_name=?")
                .setParameter(1, table)
                .setParameter(2, column)
                .getSingleResult();
            return count instanceof Number n && n.longValue() > 0;
        } catch (Exception e) {
            return true;
        }
    }

    private void connect() throws Exception {
        lastConnectAttemptMillis.set(System.currentTimeMillis());
        connectionStatus.set("CONNECTING");
        websocketStatus.set("OPENING");
        logEvent("CONNECTION_STARTED", "INFO", null, "Dhan Live Market Feed connection started.", null);

        Optional<LiveCredential> credential = activeCredential();
        if (credential.isEmpty()) {
            apiStatus.set("AUTHENTICATION_FAILED");
            lastApiError.set("No verified Dhan Client ID and Access Token found.");
            logEvent("AUTHENTICATION_FAILED", "ERROR", null, lastApiError.get(), null);
            scheduleReconnect();
            return;
        }
        if (credential.get().expired()) {
            apiStatus.set("TOKEN_EXPIRED");
            lastApiError.set("Dhan token expired. Re-authenticate Dhan in Broker Connect.");
            logEvent("AUTHENTICATION_FAILED", "ERROR", null, lastApiError.get(), Map.of("clientId", credential.get().clientId()));
            scheduleReconnect();
            return;
        }
        apiStatus.set("AUTHENTICATION_SUCCESS");
        logEvent("AUTHENTICATION_SUCCESS", "INFO", null, "Dhan Client ID and Access Token validated locally.", Map.of("clientId", credential.get().clientId()));

        List<DhanLiveInstrument> instruments = loadSubscriptionUniverse();
        if (instruments.isEmpty()) {
            connectionStatus.set("DISCONNECTED");
            websocketStatus.set("NO_SYMBOLS");
            lastApiError.set("No mapped active symbols available for Dhan live feed.");
            logEvent("SUBSCRIPTION_FAILED", "WARN", null, lastApiError.get(), null);
            scheduleReconnect();
            return;
        }

        subscribed.clear();
        for (DhanLiveInstrument instrument : instruments) {
            subscribed.put(instrument.key(), instrument);
        }

        String uri = DHAN_FEED_URL + "?version=2&token=" +
            URLEncoder.encode(credential.get().accessToken(), StandardCharsets.UTF_8) +
            "&clientId=" + URLEncoder.encode(credential.get().clientId(), StandardCharsets.UTF_8) +
            "&authType=2";

        // One connection per MAX_CONNECTION_INSTRUMENTS slice, each subscribing only its own
        // slice. A single connection would silently drop everything past the cap.
        closeAllSockets("resubscribe");
        List<DhanLiveInstrument> all = new ArrayList<>(subscribed.values());
        int connections = (all.size() + MAX_CONNECTION_INSTRUMENTS - 1) / MAX_CONNECTION_INSTRUMENTS;
        for (int c = 0; c < connections; c++) {
            List<DhanLiveInstrument> slice =
                all.subList(c * MAX_CONNECTION_INSTRUMENTS, Math.min((c + 1) * MAX_CONNECTION_INSTRUMENTS, all.size()));
            WebSocket ws = httpClient().newWebSocketBuilder()
                .buildAsync(URI.create(uri), new FeedListener())
                .join();
            sockets.add(ws);
            subscribeInstruments(ws, slice);
            log.info("Dhan live feed connection {}/{} subscribed {} instruments.", c + 1, connections, slice.size());
        }
        connectionStatus.set("CONNECTED");
        websocketStatus.set("OPEN");
        // Clear the diagnosis that got us here.
        //
        // apiStatus and lastApiError are set on every failure path above but were never reset
        // on success, and only pauseForMarketClose() ever cleared them - at 15:30. So a feed
        // that failed once and then connected kept reporting the stale reason for the rest of
        // the session: reconnecting Dhan at 09:50:31 subscribed 1,266 instruments five seconds
        // later, while the screen went on showing "No verified Dhan Client ID and Access Token
        // found" from the attempt that had preceded it. The banner outlived the fault, which is
        // worse than not showing one, because it sends you to fix something that is working.
        apiStatus.set("AUTHENTICATION_SUCCESS");
        lastApiError.set("");
        logEvent("CONNECTED", "INFO", null, "Dhan WebSocket connected.",
            Map.of("subscribedCount", subscribed.size(), "connections", connections));
        logEvent("SUBSCRIPTION_SUCCESS", "INFO", null, "Dhan live subscription completed.",
            Map.of("subscribedCount", subscribed.size(), "connections", connections));
    }

    private HttpClient httpClient() {
        HttpClient existing = httpClientRef.get();
        if (existing != null) return existing;
        HttpClient created = HttpClient.newHttpClient();
        if (httpClientRef.compareAndSet(null, created)) {
            return created;
        }
        return httpClientRef.get();
    }

    private Optional<LiveCredential> activeCredential() {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT id, client_id, access_token_enc, token_expiry FROM broker_credentials " +
                "WHERE broker_name='dhan' AND is_active=1 AND is_verified=1 " +
                "AND client_id IS NOT NULL AND client_id<>'' " +
                "AND access_token_enc IS NOT NULL AND access_token_enc<>'' " +
                "ORDER BY last_verified DESC NULLS LAST, id DESC LIMIT 1")
                .getResultList();
            if (rows.isEmpty()) return Optional.empty();
            Object[] row = rows.get(0);
            return Optional.of(new LiveCredential(
                ((Number) row[0]).longValue(),
                text(row[1]),
                decodeToken(text(row[2])),
                text(row[3])
            ));
        } catch (Exception e) {
            lastApiError.set("Dhan credential lookup failed: " + safeError(e.getMessage()));
            return Optional.empty();
        }
    }

    /**
     * Symbols the live feed subscribes to.
     *
     * <p>{@code livefeed.universe}:
     * <ul>
     *   <li>{@code TRACKED} (default) — only uploaded stocks. Keeps tick volume and DB writes
     *       proportional to what the tracker actually ranks.</li>
     *   <li>{@code ALL_NSE_EQ} — every NSE equity in the Dhan instrument master (~9,600).
     *       Quotes land in live_price_quotes for the whole universe; tracked_stocks is still
     *       only updated for uploaded symbols, because that UPDATE matches on symbol.</li>
     * </ul>
     * The wide universe costs roughly 8x the ticks and DB writes, and needs more than one
     * feed connection — size the host accordingly.
     */
    private List<DhanLiveInstrument> loadSubscriptionUniverse() {
        if ("ALL_NSE_EQ".equalsIgnoreCase(appConfig.getString("livefeed.universe", "TRACKED"))) {
            List<DhanLiveInstrument> wide = loadWideUniverse();
            if (!wide.isEmpty()) {
                // Still refresh mappings/subscription rows for tracked symbols so the
                // "cannot receive live price" diagnostics keep working.
                try { loadTrackedUniverse(); } catch (Exception e) {
                    log.debug("Tracked mapping refresh skipped during wide subscribe: {}", e.getMessage());
                }
                log.info("Dhan live universe: {} NSE equity instruments (livefeed.universe=ALL_NSE_EQ).", wide.size());
                return wide;
            }
            log.warn("livefeed.universe=ALL_NSE_EQ matched no instruments; falling back to tracked stocks.");
        }
        return loadTrackedUniverse();
    }

    /** Every NSE equity in the saved Dhan instrument master. */
    @SuppressWarnings("unchecked")
    private List<DhanLiveInstrument> loadWideUniverse() {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT security_id, COALESCE(NULLIF(exchange_segment,''),'NSE_EQ'), underlying_symbol, " +
                "trading_symbol, symbol_name, instrument FROM dhan_instruments " +
                "WHERE security_id IS NOT NULL AND security_id<>'' " +
                "AND COALESCE(underlying_symbol,'')<>'' " +
                "AND UPPER(COALESCE(exchange_segment,''))='NSE_EQ' " +
                "AND UPPER(COALESCE(instrument,'')) IN ('EQUITY','ES','EQ') " +
                "ORDER BY underlying_symbol").getResultList();
            Map<String, DhanLiveInstrument> byKey = new LinkedHashMap<>();
            for (Object[] row : rows) {
                String securityId = text(row[0]);
                String segment = normalizeExchangeSegment(text(row[1]));
                if (securityId.isBlank() || segment.isBlank()) continue;
                String underlying = normalizeSymbol(text(row[2]));
                if (underlying.isBlank()) continue;
                DhanLiveInstrument instrument = new DhanLiveInstrument(
                    underlying, text(row[3]), text(row[2]), securityId, segment, text(row[5]));
                byKey.putIfAbsent(instrument.key(), instrument);
            }
            return new ArrayList<>(byKey.values());
        } catch (Exception e) {
            log.warn("Wide live universe load failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<DhanLiveInstrument> loadTrackedUniverse() {
        return withLockRetry("subscription universe", () -> {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setTimeout(20);
            return tx.execute(status -> {
            setLocalLockTimeout();
            List<TrackedSymbol> tracked = activeTrackedSymbols();
            markMissingMappings(tracked);
            Map<String, TrackedSymbol> byNormalized = tracked.stream()
                .collect(Collectors.toMap(TrackedSymbol::normalizedSymbol, v -> v, (a, b) -> a, LinkedHashMap::new));
            Map<String, DhanLiveInstrument> mapped = findDhanMappings(byNormalized);
            upsertSubscriptionRows(tracked, mapped);
            List<String> missing = tracked.stream()
                .map(TrackedSymbol::symbol)
                .filter(symbol -> !mapped.containsKey(symbol))
                .sorted()
                .toList();
            missingMappingSymbols.set(missing);
            return new ArrayList<>(new LinkedHashMap<>(mapped).values());
            });
        });
    }

    @SuppressWarnings("unchecked")
    private List<TrackedSymbol> activeTrackedSymbols() {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT institution_id, symbol FROM tracked_stocks WHERE is_active=1 ORDER BY institution_id, symbol")
            .getResultList();
        List<TrackedSymbol> symbols = new ArrayList<>();
        for (Object[] row : rows) {
            String symbol = normalizeSymbol(text(row[1]));
            if (symbol.isBlank()) continue;
            symbols.add(new TrackedSymbol(((Number) row[0]).longValue(), symbol));
        }
        return symbols;
    }

    private void markMissingMappings(List<TrackedSymbol> tracked) {
        for (TrackedSymbol symbol : tracked) {
            String reason = mappingMissingReason(symbol.symbol());
            em.createNativeQuery(
                "INSERT INTO live_price_subscriptions(broker_name,institution_id,symbol,status,reason,created_at,updated_at) " +
                "VALUES('dhan',?,?, 'MAPPING_FAILED', ?, ?, ?) " +
                "ON CONFLICT (broker_name,institution_id,symbol) DO UPDATE SET status=EXCLUDED.status, reason=EXCLUDED.reason, updated_at=EXCLUDED.updated_at")
                .setParameter(1, symbol.institutionId())
                .setParameter(2, symbol.symbol())
                .setParameter(3, reason)
                .setParameter(4, DateUtil.now())
                .setParameter(5, DateUtil.now())
                .executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, DhanLiveInstrument> findDhanMappings(Map<String, TrackedSymbol> trackedBySymbol) {
        if (trackedBySymbol.isEmpty()) return Map.of();
        Map<String, TrackedSymbol> aliasToTracked = aliasToTrackedSymbol(trackedBySymbol.values());
        List<String> aliases = new ArrayList<>(aliasToTracked.keySet());
        String in = IntStream.range(0, aliases.size()).mapToObj(i -> "?").collect(Collectors.joining(","));
        String matchExpr = "UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(underlying_symbol,''),'-EQ',''),'-BE',''),'-BZ',''),'.NS',''),' ',''))";
        var query = em.createNativeQuery(
            "SELECT security_id, COALESCE(NULLIF(exchange_segment,''),'NSE_EQ'), underlying_symbol, " +
            "trading_symbol, symbol_name, instrument FROM dhan_instruments " +
            "WHERE security_id IS NOT NULL AND security_id<>'' " +
            "AND COALESCE(underlying_symbol,'')<>'' " +
            "AND UPPER(COALESCE(exchange_segment,'')) IN ('NSE_EQ','BSE_EQ') " +
            "AND UPPER(COALESCE(instrument,'')) IN ('EQUITY','ES','EQ') " +
            "AND " + matchExpr + " IN (" + in + ") " +
            "ORDER BY CASE WHEN exchange_segment='NSE_EQ' THEN 0 WHEN exchange_segment='BSE_EQ' THEN 1 ELSE 2 END");
        for (int i = 0; i < aliases.size(); i++) query.setParameter(i + 1, aliases.get(i));
        List<Object[]> rows = query.getResultList();
        Map<String, DhanLiveInstrument> mapped = new LinkedHashMap<>();
        Set<String> seenKeys = new HashSet<>();
        for (Object[] row : rows) {
            String normalized = normalizeSymbol(text(row[2]));
            TrackedSymbol tracked = aliasToTracked.get(normalized);
            if (tracked == null) continue;
            String segment = normalizeExchangeSegment(text(row[1]));
            String securityId = text(row[0]);
            if (segment.isBlank() || securityId.isBlank()) continue;
            String key = segment + ":" + securityId;
            if (!seenKeys.add(key)) continue;
            mapped.putIfAbsent(tracked.symbol(), new DhanLiveInstrument(
                tracked.symbol(), text(row[3]), text(row[2]), securityId, segment, text(row[5])
            ));
        }
        return mapped;
    }

    private void upsertSubscriptionRows(List<TrackedSymbol> tracked, Map<String, DhanLiveInstrument> mapped) {
        for (TrackedSymbol symbol : tracked) {
            DhanLiveInstrument instrument = mapped.get(symbol.symbol());
            if (instrument == null) continue;
            em.createNativeQuery(
                "INSERT INTO live_price_subscriptions(broker_name,institution_id,symbol,trading_symbol,underlying_symbol,exchange_segment,broker_security_id,status,reason,subscribed_at,created_at,updated_at) " +
                "VALUES('dhan',?,?,?,?,?,?, 'SUBSCRIBED', '', ?, ?, ?) " +
                "ON CONFLICT (broker_name,institution_id,symbol) DO UPDATE SET trading_symbol=EXCLUDED.trading_symbol, underlying_symbol=EXCLUDED.underlying_symbol, " +
                "exchange_segment=EXCLUDED.exchange_segment, broker_security_id=EXCLUDED.broker_security_id, status=EXCLUDED.status, reason='', subscribed_at=EXCLUDED.subscribed_at, updated_at=EXCLUDED.updated_at")
                .setParameter(1, symbol.institutionId())
                .setParameter(2, symbol.symbol())
                .setParameter(3, instrument.tradingSymbol())
                .setParameter(4, instrument.underlyingSymbol())
                .setParameter(5, instrument.exchangeSegment())
                .setParameter(6, instrument.securityId())
                .setParameter(7, DateUtil.now())
                .setParameter(8, DateUtil.now())
                .setParameter(9, DateUtil.now())
                .executeUpdate();
            em.createNativeQuery(
                "UPDATE tracked_stocks SET trading_symbol=?, dhan_security_id=?, exchange_segment=?, updated_at=? " +
                "WHERE institution_id=? AND UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(symbol),'.NS',''),'.BO',''),'-EQ',''),'-BE',''),'-BZ',''),'-SM',''),'-ST',''))=? " +
                "AND (COALESCE(trading_symbol,'')<>? OR COALESCE(dhan_security_id,'')<>? OR COALESCE(exchange_segment,'')<>?)")
                .setParameter(1, instrument.tradingSymbol())
                .setParameter(2, instrument.securityId())
                .setParameter(3, instrument.exchangeSegment())
                .setParameter(4, DateUtil.now())
                .setParameter(5, symbol.institutionId())
                .setParameter(6, normalizeSymbol(symbol.symbol()))
                .setParameter(7, instrument.tradingSymbol())
                .setParameter(8, instrument.securityId())
                .setParameter(9, instrument.exchangeSegment())
                .executeUpdate();
        }
    }

    private void subscribeInstruments(WebSocket ws, List<DhanLiveInstrument> instruments) throws Exception {
        for (int start = 0; start < instruments.size(); start += SUBSCRIPTION_CHUNK_SIZE) {
            List<DhanLiveInstrument> chunk = instruments.subList(start, Math.min(start + SUBSCRIPTION_CHUNK_SIZE, instruments.size()));
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("RequestCode", REQUEST_SUBSCRIBE_QUOTE);
            request.put("InstrumentCount", chunk.size());
            request.put("InstrumentList", chunk.stream().map(i -> Map.of(
                "ExchangeSegment", i.exchangeSegment(),
                "SecurityId", i.securityId()
            )).toList());
            ws.sendText(mapper.writeValueAsString(request), true).join();
            log.info("Dhan live subscription sent for {} instruments.", chunk.size());
        }
    }

    private void handleBinary(ByteBuffer buffer) {
        ByteBuffer data = buffer.order(ByteOrder.LITTLE_ENDIAN);
        while (data.remaining() >= 8) {
            int start = data.position();
            int responseCode = Byte.toUnsignedInt(data.get());
            int messageLength = Short.toUnsignedInt(data.getShort());
            int exchangeCode = Byte.toUnsignedInt(data.get());
            long securityId = Integer.toUnsignedLong(data.getInt());
            if (messageLength < 8 || data.remaining() < messageLength - 8) {
                data.position(start);
                ticksIgnored.incrementAndGet();
                return;
            }
            ByteBuffer packet = data.slice(start, messageLength).order(ByteOrder.LITTLE_ENDIAN);
            data.position(start + messageLength);
            DhanLiveInstrument instrument = subscribed.get(exchangeSegment(exchangeCode) + ":" + securityId);
            if (responseCode == RESPONSE_DISCONNECT) {
                String reason = "Dhan disconnected feed. Code packet received.";
                lastApiError.set(reason);
                logEvent("WEBSOCKET_CLOSED", "WARN", null, reason, Map.of("responseCode", responseCode));
                scheduleReconnect();
                continue;
            }
            if (instrument == null) {
                ticksIgnored.incrementAndGet();
                continue;
            }
            currentProcessingSymbol.set(instrument.symbol());
            try {
                LiveQuote quote = parseQuote(responseCode, packet, instrument);
                if (quote == null || quote.ltp() <= 0) {
                    ticksIgnored.incrementAndGet();
                    logEvent("TICK_IGNORED", "WARN", instrument, "Invalid Dhan tick packet.", Map.of("responseCode", responseCode));
                    continue;
                }
                applyQuote(quote);
            } catch (Exception e) {
                ticksIgnored.incrementAndGet();
                logEvent("TICK_IGNORED", "WARN", instrument, "Dhan tick parse failed: " + safeError(e.getMessage()), Map.of("responseCode", responseCode));
            } finally {
                currentProcessingSymbol.set("");
            }
        }
    }

    private LiveQuote parseQuote(int responseCode, ByteBuffer packet, DhanLiveInstrument instrument) {
        if (responseCode != RESPONSE_TICKER && responseCode != RESPONSE_QUOTE && responseCode != RESPONSE_FULL) return null;
        if (packet.capacity() < 16) return null;
        packet.position(8);
        double ltp = packet.getFloat();
        double volume = 0;
        String tradeTime = DateUtil.now();
        double open = 0;
        double high = 0;
        double low = 0;
        double close = 0;
        long totalBuyQty = 0, totalSellQty = 0;
        if (responseCode == RESPONSE_TICKER) {
            if (packet.remaining() >= 4) tradeTime = dhanEpochTime(packet.getInt());
        } else if (packet.remaining() >= QUOTE_BYTES_AFTER_LTP) {
            // Dhan v2 Quote packet (response code 4), offsets from the published spec:
            //   13-14 last traded qty   15-18 LTT   19-22 ATP   23-26 volume
            //   27-30 total SELL qty    31-34 total BUY qty
            //   35-38 open   39-42 close   43-46 high   47-50 low
            packet.getShort();                                        // 13-14 last traded qty
            tradeTime = dhanEpochTime(packet.getInt());               // 15-18
            packet.getFloat();                                        // 19-22 average trade price
            volume = Integer.toUnsignedLong(packet.getInt());         // 23-26
            // These two used to be bare getInt() calls - read off the wire purely to advance the
            // buffer, then discarded. They are the depth figures the shortlist needs in order to
            // tell a stock with no bid from one with a bid, and they have been arriving on every
            // tick all along. Keeping them costs nothing: no extra subscription, no extra bytes.
            //
            // Deliberately NOT switched to the Full packet (response 8 / request 21) to get
            // five-level depth: that is 162 bytes a tick against 50, more than triple the inbound
            // traffic across 5,000 instruments, for detail nothing asks for. Total buy and sell
            // quantity describe the whole book rather than just its top level, which is what
            // "is there a buyer" actually means.
            totalSellQty = Integer.toUnsignedLong(packet.getInt());   // 27-30
            totalBuyQty  = Integer.toUnsignedLong(packet.getInt());   // 31-34
            open = packet.getFloat();                                 // 35-38
            close = packet.getFloat();                                // 39-42, post-close only
            high = packet.getFloat();                                 // 43-46
            low = packet.getFloat();                                  // 47-50
        }
        if (high <= 0 && ltp > 0) high = ltp;
        if (low <= 0 && ltp > 0) low = ltp;
        double change = close > 0 ? ltp - close : 0;
        double changePct = close > 0 ? (change / close) * 100.0 : 0;
        return new LiveQuote(
            instrument.symbol(), instrument.tradingSymbol(), instrument.underlyingSymbol(),
            instrument.securityId(), instrument.exchangeSegment(), ltp, 0, 0, open, high, low,
            close, volume, totalBuyQty, totalSellQty, tradeTime, change, changePct, DateUtil.now()
        );
    }

    private void applyQuote(LiveQuote quote) {
        LiveQuote previous = latestQuotes.put(quote.key(), quote);
        ticksReceived.incrementAndGet();
        lastTickMillis.set(System.currentTimeMillis());
        if (previous != null && previous.sameMarketValues(quote)) return;

        // Fold the tick into its 5-minute bar. Done here rather than in the flush because the
        // flush deliberately drops ticks whose market values are unchanged, and a bar still needs
        // to see them to close on the right price. This call does no I/O.
        if (candleAggregator != null) {
            try {
                candleAggregator.onTick(quote.symbol(), quote.tradingSymbol(), quote.securityId(),
                    quote.exchangeSegment(), quote.ltp(), quote.volume());
            } catch (Exception e) {
                log.debug("Live candle fold failed for {}: {}", quote.symbol(), e.getMessage());
            }
        }

        // Buffer for the next flush; the screen is updated straight away either way.
        pendingQuotes.put(quote.key(), quote);
        broadcaster.sendQuote(quote.toMap());
        log.debug("Tick received and buffered for {} {}", quote.symbol(), quote.ltp());
    }

    private int persistQuote(LiveQuote q) {
        String rawJson = "{}";
        try { rawJson = mapper.writeValueAsString(q.toMap()); } catch (Exception ignored) {}
        int quoteRows = em.createNativeQuery(
            "INSERT INTO live_price_quotes(broker_name,exchange_segment,broker_security_id,dhan_security_id,symbol,trading_symbol,current_price,last_traded_price,bid_price,ask_price,open_price,high_price,low_price,close_price,volume,last_trade_time,change_value,change_percent,tick_received_at,price_updated_at,raw_json,created_at,updated_at,total_buy_quantity,total_sell_quantity) " +
            "VALUES('dhan',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
            "ON CONFLICT (broker_name,exchange_segment,broker_security_id) DO UPDATE SET symbol=EXCLUDED.symbol,trading_symbol=EXCLUDED.trading_symbol,dhan_security_id=EXCLUDED.dhan_security_id,current_price=EXCLUDED.current_price,last_traded_price=EXCLUDED.last_traded_price,bid_price=EXCLUDED.bid_price,ask_price=EXCLUDED.ask_price,open_price=EXCLUDED.open_price,high_price=EXCLUDED.high_price,low_price=EXCLUDED.low_price,close_price=EXCLUDED.close_price,volume=EXCLUDED.volume,last_trade_time=EXCLUDED.last_trade_time,change_value=EXCLUDED.change_value,change_percent=EXCLUDED.change_percent,tick_received_at=EXCLUDED.tick_received_at,price_updated_at=EXCLUDED.price_updated_at,raw_json=EXCLUDED.raw_json,updated_at=EXCLUDED.updated_at,total_buy_quantity=EXCLUDED.total_buy_quantity,total_sell_quantity=EXCLUDED.total_sell_quantity " +
            // The guard stays price/volume/time based, so the thousands of ticks that only shuffle
            // book quantities still cost no write. But the two zero-crossing tests are added,
            // because the liquidity gate reads exactly that: whether a book is empty.
            //
            // Without them a stock that had a bid at 09:20 and lost it by 14:00 without trading in
            // between would never write again, and the gate would keep seeing the 09:20 quantity -
            // showing a tradeable stock that no longer is, which is the one error this gate exists
            // to prevent. Comparing the booleans rather than the numbers means a write happens
            // only when emptiness actually changes, which is rare.
            "WHERE live_price_quotes.current_price IS DISTINCT FROM EXCLUDED.current_price OR live_price_quotes.volume IS DISTINCT FROM EXCLUDED.volume OR live_price_quotes.last_trade_time IS DISTINCT FROM EXCLUDED.last_trade_time " +
            "OR (COALESCE(live_price_quotes.total_buy_quantity,0) = 0) IS DISTINCT FROM (EXCLUDED.total_buy_quantity = 0) " +
            "OR (COALESCE(live_price_quotes.total_sell_quantity,0) = 0) IS DISTINCT FROM (EXCLUDED.total_sell_quantity = 0)")
            .setParameter(1, q.exchangeSegment())
            .setParameter(2, q.securityId())
            .setParameter(3, q.securityId())
            .setParameter(4, q.symbol())
            .setParameter(5, q.tradingSymbol())
            .setParameter(6, q.ltp())
            .setParameter(7, q.ltp())
            .setParameter(8, q.bid())
            .setParameter(9, q.ask())
            .setParameter(10, q.open())
            .setParameter(11, q.high())
            .setParameter(12, q.low())
            .setParameter(13, q.close())
            .setParameter(14, q.volume())
            .setParameter(15, q.lastTradeTime())
            .setParameter(16, q.change())
            .setParameter(17, q.changePercent())
            .setParameter(18, q.tickReceivedAt())
            .setParameter(19, q.tickReceivedAt())
            .setParameter(20, rawJson)
            .setParameter(21, DateUtil.now())
            .setParameter(22, DateUtil.now())
            .setParameter(23, q.totalBuyQuantity())
            .setParameter(24, q.totalSellQuantity())
            .executeUpdate();

        int stockRows = em.createNativeQuery(
            "UPDATE tracked_stocks SET current_price=?, last_traded_price=?, current_price_source='DHAN_WEBSOCKET', " +
            "dhan_security_id=?, exchange_segment=?, trading_symbol=?, last_trade_time=?, price_updated_at=?, " +
            "return_pct=CASE WHEN base_price IS NOT NULL AND base_price>0 THEN ((? - base_price) / base_price)*100 ELSE 0 END, " +
            "current_price_date=?, last_refreshed=?, updated_at=? " +
            "WHERE UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(symbol),'.NS',''),'.BO',''),'-EQ',''),'-BE',''),'-BZ',''),'-SM',''),'-ST',''))=? " +
            "AND is_active=1 AND (current_price IS NULL OR current_price<>? OR last_traded_price IS NULL OR last_traded_price<>? " +
            "OR COALESCE(last_trade_time,'')<>? OR COALESCE(price_updated_at,'')<>? OR COALESCE(dhan_security_id,'')<>? " +
            "OR COALESCE(exchange_segment,'')<>? OR COALESCE(current_price_source,'')<>'DHAN_WEBSOCKET')")
            .setParameter(1, q.ltp())
            .setParameter(2, q.ltp())
            .setParameter(3, q.securityId())
            .setParameter(4, q.exchangeSegment())
            .setParameter(5, q.tradingSymbol())
            .setParameter(6, q.lastTradeTime())
            .setParameter(7, q.tickReceivedAt())
            .setParameter(8, q.ltp())
            .setParameter(9, DateUtil.today())
            .setParameter(10, q.tickReceivedAt())
            .setParameter(11, DateUtil.now())
            .setParameter(12, normalizeSymbol(q.symbol()))
            .setParameter(13, q.ltp())
            .setParameter(14, q.ltp())
            .setParameter(15, q.lastTradeTime() == null ? "" : q.lastTradeTime())
            .setParameter(16, q.tickReceivedAt() == null ? "" : q.tickReceivedAt())
            .setParameter(17, q.securityId())
            .setParameter(18, q.exchangeSegment())
            .executeUpdate();
        if (stockRows > 0) {
            log.debug("Dhan LTP database updated: symbol={} securityId={} exchange={} ltp={}",
                q.symbol(), q.securityId(), q.exchangeSegment(), q.ltp());
        }

        em.createNativeQuery(
            "UPDATE live_price_subscriptions SET status='TICK_RECEIVED', last_tick_at=?, updated_at=? " +
            "WHERE broker_name='dhan' AND exchange_segment=? AND broker_security_id=?")
            .setParameter(1, q.tickReceivedAt())
            .setParameter(2, DateUtil.now())
            .setParameter(3, q.exchangeSegment())
            .setParameter(4, q.securityId())
            .executeUpdate();
        return quoteRows + stockRows;
    }

    private void scheduleReconnect() {
        connectionStatus.set("RECONNECTING");
        websocketStatus.set("CLOSED");
        reconnectCount.incrementAndGet();
        executor.schedule(this::connectSafely, Math.min(60, 5 + reconnectCount.get()), TimeUnit.SECONDS);
    }

    private void logEvent(String type, String severity, DhanLiveInstrument instrument, String message, Object details) {
        log.info("Dhan live price {}: {}", type, message);
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.executeWithoutResult(status -> em.createNativeQuery(
                "INSERT INTO live_price_service_events(broker_name,event_type,severity,symbol,exchange_segment,broker_security_id,message,details_json,created_at) VALUES('dhan',?,?,?,?,?,?,?,?)")
                .setParameter(1, type)
                .setParameter(2, severity)
                .setParameter(3, instrument == null ? null : instrument.symbol())
                .setParameter(4, instrument == null ? null : instrument.exchangeSegment())
                .setParameter(5, instrument == null ? null : instrument.securityId())
                .setParameter(6, message)
                .setParameter(7, toJson(details))
                .setParameter(8, DateUtil.now())
                .executeUpdate());
        } catch (Exception e) {
            log.debug("Live price event log failed: {}", e.getMessage());
        }
    }

    private String toJson(Object value) {
        try { return value == null ? null : mapper.writeValueAsString(value); }
        catch (Exception e) { return null; }
    }

    private String dhanEpochTime(int epochSeconds) {
        if (epochSeconds <= 0) return DateUtil.now();
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(Integer.toUnsignedLong(epochSeconds)), IST)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String formatMillis(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), IST)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private Map<String, TrackedSymbol> aliasToTrackedSymbol(Collection<TrackedSymbol> symbols) {
        Map<String, TrackedSymbol> aliases = new LinkedHashMap<>();
        for (TrackedSymbol tracked : symbols) {
            String s = normalizeSymbol(tracked.symbol());
            if (s.isBlank()) continue;
            aliases.putIfAbsent(s, tracked);
            aliases.putIfAbsent(s + "-EQ", tracked);
            aliases.putIfAbsent(s + "-BE", tracked);
            aliases.putIfAbsent(s + "-BZ", tracked);
            String dhanSymbol = legacyDhanSymbol(s);
            if (!dhanSymbol.isBlank()) {
                aliases.putIfAbsent(dhanSymbol, tracked);
                aliases.putIfAbsent(dhanSymbol + "-EQ", tracked);
                aliases.putIfAbsent(dhanSymbol + "-BE", tracked);
                aliases.putIfAbsent(dhanSymbol + "-BZ", tracked);
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
        return "No Dhan Security ID mapped from underlying_symbol.";
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

    private String normalizeExchangeSegment(String value) {
        String s = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (s.equals("NSE") || s.equals("NSE_EQ")) return "NSE_EQ";
        if (s.equals("BSE") || s.equals("BSE_EQ")) return "BSE_EQ";
        return s.endsWith("_EQ") ? s : "";
    }

    private String exchangeSegment(int exchangeCode) {
        return switch (exchangeCode) {
            case 1 -> "NSE_EQ";
            case 4 -> "BSE_EQ";
            default -> String.valueOf(exchangeCode);
        };
    }

    private String decodeToken(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return dhanBrokerService.decode(value);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safeError(String error) {
        return error == null || error.isBlank() ? "Unknown error" : error;
    }

    private void setLocalLockTimeout() {
        try {
            em.createNativeQuery("SET LOCAL lock_timeout = '1500ms'").executeUpdate();
        } catch (Exception ignored) {}
    }

    private <T> T withLockRetry(String action, Supplier<T> work) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException e) {
                last = e;
                if (!isLockConflict(e) || attempt == 3) throw e;
                log.warn("Dhan live {} lock conflict, retrying attempt {}/3: {}", action, attempt + 1, safeError(e.getMessage()));
                sleepQuietly(150L * attempt);
            }
        }
        throw last == null ? new IllegalStateException("Dhan live " + action + " failed.") : last;
    }

    private boolean isLockConflict(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String text = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            String name = current.getClass().getName().toLowerCase(Locale.ROOT);
            if (text.contains("deadlock detected") || text.contains("lock timeout") ||
                text.contains("could not serialize") || name.contains("lockacquisition")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long memoryUsageMb() {
        Runtime rt = Runtime.getRuntime();
        return Math.max(0, (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
    }

    private double cpuUsagePercent() {
        try {
            Object osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean mx) {
                double load = mx.getProcessCpuLoad();
                return load < 0 ? 0 : Math.round(load * 10000.0) / 100.0;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private record TrackedSymbol(Long institutionId, String symbol) {
        String normalizedSymbol() { return symbol; }
    }

    private record LiveCredential(Long id, String clientId, String accessToken, String tokenExpiry) {
        boolean expired() {
            if (tokenExpiry == null || tokenExpiry.isBlank()) return false;
            try {
                String value = tokenExpiry.length() >= 19 ? tokenExpiry.substring(0, 19).replace("T", " ") : tokenExpiry;
                LocalDateTime expiry = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return expiry.isBefore(LocalDateTime.now(IST));
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private record DhanLiveInstrument(String symbol, String tradingSymbol, String underlyingSymbol,
                                      String securityId, String exchangeSegment, String instrumentType) {
        String key() { return exchangeSegment + ":" + securityId; }
    }

    /**
     * {@code bid} and {@code ask} are best-bid and best-offer prices, which only the Full packet
     * carries - they stay zero and nothing reads them. {@code totalBuyQuantity} and
     * {@code totalSellQuantity} are the aggregate book quantities from the Quote packet, which is
     * what the liquidity gate is built on.
     */
    private record LiveQuote(String symbol, String tradingSymbol, String underlyingSymbol, String securityId,
                             String exchangeSegment, double ltp, double bid, double ask, double open,
                             double high, double low, double close, double volume,
                             long totalBuyQuantity, long totalSellQuantity, String lastTradeTime,
                             double change, double changePercent, String tickReceivedAt) {
        String key() { return exchangeSegment + ":" + securityId; }

        boolean sameMarketValues(LiveQuote other) {
            // Book quantities move on almost every tick even when price and volume have not, so
            // they are deliberately NOT part of this comparison. Including them would defeat the
            // de-duplication this guard exists for and push a database write per tick per symbol -
            // on 1,300 symbols that is the difference between a few hundred writes a minute and
            // tens of thousands. A stale book quantity for a few seconds costs nothing; the gate
            // only asks whether it is zero.
            return Double.compare(ltp, other.ltp) == 0
                && Double.compare(volume, other.volume) == 0
                && String.valueOf(lastTradeTime).equals(String.valueOf(other.lastTradeTime));
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", symbol);
            m.put("tradingSymbol", tradingSymbol);
            m.put("underlyingSymbol", underlyingSymbol);
            m.put("securityId", securityId);
            m.put("exchangeSegment", exchangeSegment);
            m.put("ltp", ltp);
            m.put("currentPrice", ltp);
            m.put("bidPrice", bid);
            m.put("askPrice", ask);
            m.put("open", open);
            m.put("high", high);
            m.put("low", low);
            m.put("close", close);
            m.put("volume", volume);
            m.put("totalBuyQuantity", totalBuyQuantity);
            m.put("totalSellQuantity", totalSellQuantity);
            m.put("lastTradeTime", lastTradeTime);
            m.put("change", change);
            m.put("changePercent", changePercent);
            m.put("tickReceivedAt", tickReceivedAt);
            return m;
        }
    }

    private class FeedListener implements WebSocket.Listener {
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            websocketStatus.set("OPEN");
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            synchronized (partial) {
                partial.writeBytes(bytes);
                if (last) {
                    handleBinary(ByteBuffer.wrap(partial.toByteArray()));
                    partial.reset();
                }
            }
            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String text = String.valueOf(data);
            if (!text.isBlank()) logEvent("WEBSOCKET_MESSAGE", "INFO", null, text, null);
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            sockets.remove(webSocket);
            connectionStatus.set("DISCONNECTED");
            websocketStatus.set("CLOSED");
            lastApiError.set("Dhan WebSocket closed: " + statusCode + " " + reason);
            logEvent("WEBSOCKET_CLOSED", "WARN", null, lastApiError.get(), Map.of("statusCode", statusCode));
            scheduleReconnect();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            sockets.remove(webSocket);
            connectionStatus.set("DISCONNECTED");
            websocketStatus.set("ERROR");
            lastApiError.set("Dhan WebSocket error: " + safeError(error.getMessage()));
            logEvent("EXCEPTION", "ERROR", null, lastApiError.get(), null);
            scheduleReconnect();
        }
    }
}
