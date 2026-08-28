# TradeRakshak — Automatic Data Synchronization & Real-Time Refresh
## Technical Design & Implementation Plan

**Status:** Draft for review · **Owner:** Engineering · **Scope:** Backend (Spring Boot / Java 17 / PostgreSQL) + Angular frontend

**Goal (one line):** The user never clicks "refresh." On startup the app shows the dashboard immediately from stored data, detects and backfills only what's missing in the background, and thereafter data flows automatically **API → Database → Cache → UI** with sub‑second screen updates.

---

## 1. Design principles (non‑negotiable)

1. **Never block the UI.** Startup shows cached/DB data instantly; all sync runs on background threads.
2. **Fetch only what's missing.** Every API call is preceded by a DB gap check. No duplicate downloads.
3. **Short transactions only.** Every write is a bulk INSERT/UPDATE in its own short transaction (preserves the lock‑timeout fixes already shipped).
4. **Connection‑gated.** No Dhan API call unless a broker is connected with a valid, non‑expired token (preserves the gate already shipped in `findVerifiedDhanCredential`).
5. **Push, don't poll.** UI updates are pushed over SSE; polling is only a fallback.
6. **Idempotent & resumable.** Sync can be interrupted (crash, network loss) and safely resumed without corrupting data.
7. **Rate‑limit safe.** Respect Dhan limits (the current 1.2 s inter‑call gap + 30 s 429 backoff). A 30‑day backfill for a large universe is paced over time, not fired all at once.

---

## 2. What already exists (we reuse, not rebuild)

| Capability | Existing component | Reuse in this design |
|---|---|---|
| Live market feed (market hours) | `DhanLivePriceService` (WebSocket, `ensureConnected` @15 s, `activeCredential`, `isMarketOpen`, auto‑reconnect) | Live tick source; extend to publish to cache + SSE |
| Real‑time push to UI | `LivePriceBroadcaster` (SSE: `register`, `sendQuote`, `sendStatus`, `sendTop10Update`) | Add `sync` + per‑module events |
| 5‑min price refresh + ranking | `StockTrackerService.scheduledPriceRefresh` (cron `0 */5 9-15`), `refreshAllPrices`, `runRankingEngine`, `storeDailyPriceSnapshot` | Keep; add per‑phase timing (done) |
| EOD refresh + Previous Top 10 | `eodPriceRefresh` (15:35), `prevTop10AutomationTick` | Extend with final sync + snapshot |
| Historical/intraday backfill | `DhanHistoricalBackfillService` → `dhan_historical_candles` | Core of missing‑candle backfill |
| Connection/expiry gate | `findVerifiedDhanCredential` (+ `isDhanTokenExpired`) | Guard for every fetch |
| Runtime schema | `SymbolSchemaGuard` (ApplicationRunner) | Add `sync_state` table + indexes |
| Config | `AppConfigService` (`application_settings`), `market.holidays` | Sync config + holiday calendar |
| Instant DB read | `getUploadedStocksLite` (lite view, no enrichment) | Pattern for "show immediately" |
| Frontend SSE | `live-price.service` (EventSource, `top10` listener) | Add `sync`/module listeners |

**Key data tables:** `tracked_stocks`, `tracked_stock_prices`, `dhan_historical_candles`, `market_data`, `broker_market_candles`, `live_price_quotes`, `tracker_ranking_items`, `top10_trade_batches`.

**Reconciliation note on the "every 5 s live prices" requirement:** we already receive live ticks via a **WebSocket** (event‑driven, lower latency than 5 s polling). We keep the WebSocket as the primary live source and use a 5 s scheduled *fallback poll* only when the socket is down. This meets the intent ("live prices update automatically") without hammering the quote REST endpoint.

---

## 3. Target data flow

```
App start ─▶ Load config + holiday calendar + session timings
          ─▶ Load cache from DB (last known values)      ─▶ SHOW DASHBOARD NOW (non-blocking)
          ─▶ Detect market status
                 │
     ┌───────────┴───────────┐
 MARKET OPEN             MARKET CLOSED
     │                        │
 WebSocket live feed      Gap-scan last 30 trading days
     │                        │  (missing days/candles/indicators)
 tick ─▶ DB(batch) ─▶ cache ─▶ SSE ─▶ UI     Backfill ONLY missing ─▶ DB(batch) ─▶ cache ─▶ SSE ─▶ UI
                              │                        │
                     Refresh only affected     Recalc indicators + rankings
                     UI component (<1 s)        ─▶ Previous Top 10 snapshot
```

Every write path ends in: **DB (bulk, short txn) → cache invalidate/update → SSE event → targeted UI refresh.**

---

## 4. PHASE 1 — Startup Catch‑Up Synchronization (this phase, detailed)

### 4.1 Orchestration

New bean **`StartupSyncService`** listening for `ApplicationReadyEvent` (fires *after* the web server is up, so the UI is already reachable):

```
onApplicationReady():
    if (!config.sync.enabled) return
    cache.warm()                       // load last-known values from DB into in-memory cache
    marketStatus = marketCalendar.status(now)     // OPEN | CLOSED | PRE_OPEN | HOLIDAY
    broadcaster.sendSync(phase="STARTED", marketStatus)
    executor.submit(() -> runStartupSync(marketStatus))   // BACKGROUND — returns immediately
```

The dashboard is fully usable the instant the server is up; `runStartupSync` runs on a dedicated thread pool and never touches the request threads.

### 4.2 Market status & calendar

New **`MarketCalendarService`** (thin, backed by existing `market.holidays` config + `isMarketOpen` logic):
- `status(Instant)` → enum.
- `tradingDays(fromDate, toDate)` → `List<LocalDate>` (weekdays minus holidays).
- `previousNTradingDays(n)` → last *n* sessions (default `sync.lookbackDays = 30`).
- Session timings from config (`market.session.open = 09:15`, `market.session.close = 15:30`).

### 4.3 Missing‑data detection

New **`MarketDataGapDetector`** — pure DB reads, no API:

```
detectGaps(symbols, lookbackDays):
    expected = calendar.previousNTradingDays(lookbackDays)     // e.g. 30 dates
    for each symbol (batched, IN-clause queries):
        existingDates = SELECT DISTINCT candle_date
                        FROM dhan_historical_candles
                        WHERE symbol IN (...) AND candle_mode='DAILY'
                          AND candle_date >= :from
        missingDates  = expected - existingDates
        (optional) missingIndicators = expected - indicatorDates(symbol)   // Phase 3
    return GapReport { byDate: Map<LocalDate, List<symbol>>, totalMissing, symbolsAffected }
```

- Uses existing indexes `idx_dhan_candles_symbol_time`, `idx_dhan_candles_segment_time`.
- Batched (1000 symbols/query) — the same pattern already used in `loadLatestDailyPrices`.
- Produces a **per‑day work list** so we fetch a whole day for many symbols in one API batch (efficient) rather than per‑stock.

### 4.4 Batch backfill engine

New **`MissingDataBackfillService`** — wraps the existing `DhanHistoricalBackfillService`:

```
backfill(gapReport):
    for each tradingDay in gapReport (oldest → newest):
        symbolsForDay = gapReport.byDate[day]
        for batch in partition(symbolsForDay, batchSize=100):
            if (!dhanConnected()) { pause; retry later }     // connection gate
            candles = dhanHistorical.fetchDaily(batch, day)  // ONE batched call, throttled 1.2s
            bulkUpsertCandles(candles)                        // JDBC batch, short txn, ON CONFLICT DO UPDATE
            cache.mergeCandles(candles)
            broadcaster.sendSync(phase="DOWNLOADING", done, total, currentDay)
        commitCheckpointToSyncState(day)                     // resumable
```

- **Parallelism:** a bounded thread pool (`sync.maxParallel`, default 3) processes independent day‑batches concurrently, but a global permit respects the Dhan rate limit (shared throttle from `dhanThrottle`).
- **Retry:** per batch, exponential backoff on timeout / 429 (reuse `postJsonWithRetry`, `dhanOn429`). Failed symbols are logged to `top10_failed_symbols`‑style table and retried at the end — **the job never stops on one bad symbol.**
- **No duplicates:** `INSERT … ON CONFLICT (symbol, candle_date, candle_mode) DO UPDATE` (add unique constraint).

### 4.5 Non‑blocking startup + progress UI

- **Backend:** progress is pushed via a new SSE event `sync` on the existing `LivePriceBroadcaster` stream, plus a `GET /api/sync/status` REST endpoint (polling fallback + initial state):
  `{ phase, marketStatus, tradingDaysToSync, recordsMissing, recordsDone, percent, message, startedAt, eta }`.
- **Frontend:** a lightweight **global sync banner/toast** (new small standalone component) subscribes to `sync` events and renders the exact messages from the spec:
  `Market Closed — Checking previous 30 trading days… 7 trading days require synchronization. Starting background update…` and, on completion, `Synchronization Completed.` It is **non‑modal** — the user keeps working while it runs.

### 4.6 New database objects (via `SymbolSchemaGuard`, defensive `IF NOT EXISTS`)

```sql
CREATE TABLE IF NOT EXISTS sync_state (
    id BIGSERIAL PRIMARY KEY,
    data_type   TEXT NOT NULL,          -- DAILY_CANDLE | INDICATOR | RANKING | SNAPSHOT
    scope_key   TEXT NOT NULL,          -- institution/user or 'GLOBAL'
    last_synced_date TEXT,              -- last fully-synced trading day
    last_run_at TEXT,
    status TEXT,                        -- IDLE | RUNNING | PARTIAL | ERROR
    detail TEXT,
    UNIQUE(data_type, scope_key)
);
-- unique key to make candle upserts idempotent:
CREATE UNIQUE INDEX IF NOT EXISTS ux_dhc_symbol_date_mode
    ON dhan_historical_candles(symbol_name, candle_time, candle_mode);
```

### 4.7 New / changed classes

| Class | Type | Responsibility |
|---|---|---|
| `StartupSyncService` | new `@Service` + `ApplicationReadyEvent` listener | Orchestrate startup sync on a background executor |
| `MarketCalendarService` | new `@Service` | Trading calendar, session timing, market status |
| `MarketDataGapDetector` | new `@Service` | DB‑only missing‑record detection |
| `MissingDataBackfillService` | new `@Service` | Batched, throttled, resumable backfill |
| `SyncProgressBroadcaster` | new (or method on `LivePriceBroadcaster`) | SSE `sync` events |
| `SyncStatusController` | new `@RestController` | `GET /api/sync/status`, `POST /api/sync/run` (admin) |
| `SyncState` DAO | new (native SQL, like the rest) | Checkpoint read/write |
| `LivePriceBroadcaster` | change | add `sendSync(payload)` (event `sync`) |
| `SymbolSchemaGuard` | change | create `sync_state` + unique candle index |
| `AppConfigService` seed | change | seed `sync.*` config keys |
| `live-price.service.ts` (FE) | change | subscribe to `sync` event |
| `sync-banner.component.ts` (FE) | new | non‑modal progress banner |
| `app.component` (FE) | change | mount the banner globally |

### 4.8 Config keys (seeded, admin‑editable)

```
sync.enabled = true
sync.startupNonBlocking = true
sync.lookbackDays = 30
sync.batchSize = 100
sync.maxParallel = 3
sync.retryMax = 3
sync.marketClosedVerify = true
```

### 4.9 Acceptance for Phase 1

- App reachable and dashboard rendered **before** sync finishes (measure: `/api/actuator/health` + dashboard load succeed while `sync.status = RUNNING`).
- Market status auto‑detected at startup.
- After hours: last 30 trading days scanned, only missing days downloaded, no duplicates (verified by row counts + unique index).
- Progress messages shown exactly as specified; banner is non‑blocking.
- Re‑running startup with no gaps performs **zero** API calls.

---

## 5. PHASE 2 — Auto‑refresh all modules over SSE (outline)

- Generalise the Top 10 SSE pattern into a typed event bus: events `top10`, `prevTop10`, `dashboard`, `watchlist`, `scanner`, `positions`, `journal`, `indicators`, `dataWarehouse`.
- Backend: after each write path, emit the specific event with a small diff payload (changed rows only).
- Frontend: a shared `RealtimeService` maps event → the owning component's `applyUpdate()` (like `applyTop10Update`), refreshing **only** that component's grid. No full‑page reloads. Manual refresh buttons become optional.
- Guard against duplicate/overlapping refreshes (reuse `priceRefreshInProgress` pattern per section).

## 6. PHASE 3 — Missing‑indicator gap‑fill (outline)

- Extend `MarketDataGapDetector` to compare stored indicator dates (RSI/EMA/MACD/ATR/BB/ADX/VWAP/Supertrend) against the trading calendar per symbol.
- Recompute missing indicators **from candles already in DB** (no API) in batch workers; bulk‑upsert.
- Trigger: EOD scheduler + startup sync. Only where an `indicators` table actually stores these (audit first).

## 7. PHASE 4 — Throughput hardening (outline)

- **Batch API:** ensure all fetch paths group ≥100 instruments/call (quote already does; historical to follow).
- **JDBC batch writes:** set `hibernate.jdbc.batch_size`, `order_inserts/updates=true`; use multi‑row `INSERT … VALUES (…),(…) ON CONFLICT` for candles/prices.
- **Cache:** a small in‑memory `MarketDataCache` (ConcurrentHashMap, TTL) in front of hot reads (latest price, latest candle, ranking). Invalidated on write. Frontend keeps its existing page cache.
- **Resilience:** central retry/backoff, circuit‑breaker on Dhan, auto‑reconnect (WebSocket already has this), structured failure logging.

---

## 8. Consolidated schedulers (after all phases)

| Cadence | Job | Status |
|---|---|---|
| Event‑driven (WebSocket) + 5 s fallback poll | Live prices → DB → cache → SSE | exists + add fallback |
| 1 min | Indicator refresh (market open) | new (Phase 3) |
| 5 min | Rankings + dashboard | exists (`scheduledPriceRefresh`) |
| 15 min | Market statistics | new |
| 15:35 EOD | Final sync, indicator recompute, **Previous Top 10 snapshot**, historical snapshot | exists + extend |
| Startup | Catch‑up gap sync | **Phase 1** |

All cron jobs are `zone="Asia/Kolkata"`, holiday‑aware, and self‑invocation‑safe (no `@Transactional` on the scheduled method; writes use short programmatic transactions).

---

## 9. Error handling & resilience (all phases)

- API timeout / disconnect → retry with exponential backoff; resume from `sync_state` checkpoint.
- 429 → existing 30 s backoff; reduce parallelism temporarily.
- DB timeout / lock → short transactions + retry (pattern already shipped for broker).
- Duplicate data → unique constraints + `ON CONFLICT DO UPDATE`.
- Missing/invalid symbols → skip + log to a failed‑symbols table; continue the rest.
- Partial download → checkpoint per day so only the incomplete tail retries.

## 10. Performance targets & how we hit them

| Target | Mechanism |
|---|---|
| UI refresh < 1 s after DB update | SSE push of changed‑rows diff, targeted component refresh |
| Startup non‑blocking | `ApplicationReadyEvent` + background executor; dashboard reads cache/DB |
| No duplicate API / DB work | gap detection + `sync_state` checkpoints + unique indexes |
| Minimal DB locking | short per‑batch transactions, bulk ops, indexed queries |
| Responsive under sync | bounded thread pool, rate‑limited, off the request threads |

## 11. Risks & mitigations

- **Dhan historical rate limits:** a 30‑day × large‑universe backfill is thousands of calls at 1.2 s each ⇒ can take a long time. Mitigation: fetch a whole day for many symbols per call, run in the background over minutes, checkpoint + resume, prioritise the most‑recent days and the visible watchlist first.
- **Large `dhan_historical_candles` table:** enforce the unique index and rely on `idx_dhan_candles_symbol_time`; avoid function‑wrapped WHERE clauses on hot paths.
- **Build/save fragility (observed):** large source files intermittently truncate on save and the backend can't be compiled in this environment. Mitigation: implement **one phase at a time**, edit large files via safe writes, verify structure after each change, and rebuild + smoke‑test before the next phase.
- **Regression risk:** must not reintroduce the 401 spam / lock timeouts / UI hangs just fixed. Every new fetch goes through the connection gate; every write is a short transaction; the UI reads lite/cached data first.

## 12. Acceptance criteria traceability

| Spec criterion | Delivered by |
|---|---|
| 1. Never manually fetch live data | Phase 1 startup sync + Phase 2 SSE auto‑refresh + WebSocket feed |
| 2. Market status auto‑detected at startup | `MarketCalendarService` in `StartupSyncService` |
| 3. Live update DB+UI during market hours | WebSocket → DB(batch) → cache → SSE |
| 4. After hours: 30‑day gap check + sync missing only | `MarketDataGapDetector` + `MissingDataBackfillService` |
| 5. API → DB → Cache → UI | end‑to‑end pipeline (all phases) |
| 6. Instant per‑component refresh, no full reload | SSE diff events + targeted `applyUpdate()` |
| 7. Background sync non‑blocking | `ApplicationReadyEvent` + executor |
| 8. Scalable, fault‑tolerant, high‑perf | batching, checkpoints, retry, cache, indexes |

## 13. Rollout plan

1. **Phase 1** (this): startup catch‑up sync + progress banner. Ship, rebuild, verify against Phase‑1 acceptance.
2. Phase 2: module‑wide SSE auto‑refresh.
3. Phase 3: indicator gap‑fill.
4. Phase 4: throughput hardening.

Each phase is independently shippable and additive; nothing removes the manual controls until its automatic replacement is verified.

---

### Immediate next step (on your approval)
Build **Phase 1** exactly as in §4: `sync_state` schema + `MarketCalendarService` + `MarketDataGapDetector` + `MissingDataBackfillService` + `StartupSyncService` + `SyncStatusController` + `sendSync` SSE + the non‑modal frontend banner — wired to run in the background at startup with the specified progress messages, and gated on the broker connection so it never spams the API when disconnected.
