# Stock Upload Module — Enhancement Implementation Plan

**Module:** Current Top 10 · Previous Top 10 · Avg Return Trend · Performance
**Stack:** Spring Boot backend (`StockTrackerService`, `StockTrackerController`) + Angular standalone component (`stock-tracker.component.ts`) + PostgreSQL + Dhan API + existing WebSocket live-price feed.
**Prime directive:** *Implement every enhancement without breaking any existing functionality.* All changes are additive or behind config flags that default to current behavior.

---

## 0. What already exists (verified in the codebase)

Before planning changes I audited the current system. A large share of the requested functionality is already present; the plan below **builds on it** rather than rewriting it.

| Area | Already present | File / location |
|---|---|---|
| Upload + skip-record job | Async job with `skipped_records`, download-skipped | `StockTrackerService` upload job (`UploadJob`), `/tracker/upload`, `/tracker/jobs/*` |
| 13-factor ranking with **Skip logic** | `acc.skip(component, label, reason)` → `skipped_components` surfaced to UI; fallback/partial ranking | `StockTrackerService#computeRanking` / `scoreStock` (lines ~1560–1790) |
| Eligible filter | return-range filter, base_price>0, max-per-sector, TopN=10 (all config-driven) | `getEligibleStocks`, `top10.count`, `top10.max_per_sector` |
| 5-min auto price refresh (market hours) + EOD | `@Scheduled(cron="0 */5 9-15 * * MON-FRI")`, `@Scheduled(...15:35...)` | `StockTrackerService#scheduledPriceRefresh`, `eodPriceRefresh` |
| Live prices over WebSocket | broadcaster + FE subscriber | `LivePriceBroadcaster`, `live-price.service.ts` |
| Trade batches (Previous Top 10 buy/sell/trend) | manual buy/sell, PREV_TOP10 strategy, trend | `top10_trade_batches`, `top10_trade_batch_stocks` (DDL in `SymbolSchemaGuard`), `/tracker/trade-batches/*`, `/prev-trade-batches/*` |
| Buy/Sell time + hold-day selection (Current Top 10 & Trend) | dropdowns (`15:20`, etc.), `trendBuyTime/trendSellTime/trendHoldDays` | `stock-tracker.component.ts` |
| Config store (key/value per institution) | `AppConfigService.getInt/String/Double/Long/Bool` | used throughout `StockTrackerService` |
| Rate-limit / 429 handling, batched LTP, async executor | `dhanOn429`, `uploadExecutor`, batch LTP refresh | `StockTrackerService` |

### The real gaps (what this plan delivers)
1. **Scheduled auto-buy at 15:20 and auto-sell at 09:30 next trading day** (today these are manual endpoints).
2. **Auto Buy/Sell enable-disable toggles** (+ paper-default / live-opt-in switch) for both Current Top 10 and Previous Top 10.
3. **Row-level diff + animate-only-changed** on the 5-min Current-Top-10 refresh (today it refreshes prices, not a targeted diff/redraw-suppression of the Top-10 table).
4. **Manual-refresh state machine**: `Updating… → Completed (HH:MM:SS DD-MMM-YYYY)` / `Failed – Retry`.
5. **Strict processing-order orchestration** (calc → average → statistics → trend → chart → table → save) with "never chart before calc".
6. **Trading-day / holiday awareness** for the daily cycle.
7. **Hardening**: exception isolation per-stock, exponential backoff, failed-symbol logging, section-only refresh guarantees, table virtualization for 700–5000+ rows.

---

## 1. Configuration flags (new, all default to safe/current behavior)

Stored via existing `AppConfigService`, scoped per institution. New keys:

| Key | Default | Meaning |
|---|---|---|
| `top10.auto_refresh.enabled` | `true` | 5-min Current-Top-10 auto refresh on/off |
| `top10.auto_refresh.interval_sec` | `300` | refresh cadence |
| `prevtop10.auto_cycle.enabled` | `false` | master switch for daily auto buy+sell cycle |
| `prevtop10.auto_buy.enabled` | `false` | auto-buy at buy-time |
| `prevtop10.auto_sell.enabled` | `false` | auto-sell next trading day at sell-time |
| `prevtop10.buy_time` | `15:20` | daily buy time (IST) |
| `prevtop10.sell_time` | `09:30` | next-day sell time (IST) |
| `prevtop10.execution_mode` | `PAPER` | `PAPER` (default) or `LIVE` (opt-in) |
| `prevtop10.use_real_price_if_executed` | `true` | prefer actual executed price when a real trade exists |

**Live-order safety:** `execution_mode=LIVE` is the only path that can place real Dhan orders; it defaults **off** and is a separate switch from the enable toggles. In `PAPER` mode the cycle only records simulated LTP-based trades (using a real executed price when one already exists). No real order is ever auto-placed unless `LIVE` is explicitly turned on.

New endpoints on `StockTrackerController`:
- `GET  /tracker/automation/config` → returns all flags above.
- `PUT  /tracker/automation/config` (Admin) → updates flags.

Frontend: a small "Automation" settings panel in the Current Top 10 and Previous Top 10 section headers with the enable/disable toggles + a clearly-labeled, off-by-default **Live orders** switch guarded by a confirm dialog.

---

## 2. Phased delivery

### Phase 1 — Config + toggles (foundation)
- Add the config keys, `getAutomationConfig()/saveAutomationConfig()` in `StockTrackerService`, and the two endpoints.
- Frontend: automation panel + toggles wired to the endpoints; persist and reflect state. No behavior change yet when everything is default.
- **Acceptance:** toggles round-trip and persist; defaults preserve today's behavior exactly.

### Phase 2 — Current Top 10 auto-refresh with row diff (spec §1, §2, §9)
- Keep the existing 5-min price `@Scheduled`. Add a **Top-10 recompute + compare** step that: fetches latest prices → recomputes ranking (reusing `computeRanking`, unchanged skip logic) → diffs against the currently-served Top 10.
- Push the diff over the **existing WebSocket** (`LivePriceBroadcaster`) on a new `top10:update` channel: `{changed:[...rows], lastUpdated}` or `{noChange:true, lastUpdated}`.
- Frontend: `trackBy` on the Top-10 table; when `noChange`, update only "Last Updated" (no redraw); when changed, patch only changed rows and apply a CSS flash animation to those `<tr>`s. Honors `top10.auto_refresh.enabled`.
- Respects user-selected buy/sell time (already in the dropdowns) — recompute uses the selected time context.
- **Acceptance:** unchanged ranking → table not redrawn, only timestamp moves; changed ranking → only changed rows animate; sidebar/header/other tabs never reload.

### Phase 3 — Manual refresh state machine (spec §3)
- Frontend: `refreshState: 'idle'|'updating'|'completed'|'failed'`. Button shows `Updating…`, disabled while running; on success `Completed` + `Last Updated: HH:MM:SS DD-MMM-YYYY`; on failure `Failed – Retry` with a retry action. Prevent duplicate concurrent clicks.
- Backend `/tracker/refresh` already exists; wrap response to return a precise server timestamp + success/fail.
- **Acceptance:** the exact status transitions and timestamp format render; button cannot be double-fired.

### Phase 4 — Previous Top 10 daily cycle automation (spec §4, §5, §8)
- New `@Scheduled` **buy job** (cron from `prevtop10.buy_time`, default `0 20 15 * * MON-FRI`, IST) — guarded by trading-day/holiday check and `auto_buy.enabled`:
  - Snapshot Current Top 10 → store `symbol, buy_date, buy_time, buy_price(LTP@buytime)` into `top10_trade_batches`/`_stocks` (reusing existing PREV_TOP10 buy path). If a real executed buy exists and `use_real_price_if_executed`, use the actual price.
- New `@Scheduled` **sell job** (cron from `prevtop10.sell_time`, default `0 30 9 * * MON-FRI`, IST) — guarded by trading-day/holiday + `auto_sell.enabled`:
  - Sell all OPEN previous-top-10 stocks at LTP@selltime (or real sell price if executed) → store `sell_price, sell_time, sell_date, profit_loss, return_pct` → compute avg → **archive** batch (`status=CLOSED`).
- Trading-day/holiday: reuse/extend existing market-open logic (`isMarketOpen`) + a holiday list (config `market.holidays` or existing `EventCalendar`). Weekends already excluded by `MON-FRI` cron.
- All DB writes wrapped per the strict order in §8; each step must succeed before the next.
- **Acceptance:** on a trading day, 15:20 creates a Previous-Top-10 batch with buy prices; next trading day 09:30 sells, computes returns, archives. Skips weekends/holidays. Toggles gate everything; `PAPER` places no real orders.

### Phase 5 — Calculations, statistics, trend ordering (spec §5, §6, §7)
- Enforce orchestrated order: `Return% → Average → Statistics → Trend → Chart → Table → Save`. Chart data is only emitted after calculations complete (guard flag; never render chart first).
- Return% = (Current−Buy)/Buy per stock; Average across the 10; statistics (winners/losers/avg/total) — reuse existing computations, wrap in the ordered pipeline.
- Trend: configurable Buy Time, Sell Time, Number of Days — already partially present (`/tracker/trade-batches/trend`, `trendBuyTime/SellTime/HoldDays`). Ensure the chart uses **only actual stored buy/sell prices** (never ranking average, never estimated). Add validation that rejects estimated/missing-price points.
- **Acceptance:** trend chart values equal recomputation from stored prices; chart never appears before calc; buy/sell/day selectors drive the series.

### Phase 6 — Performance + exception hardening (spec §10, §11)
- **Backend:** confirm async workers (`uploadExecutor`), batch/bulk writes, changed-only updates, per-refresh ranking cache, dedup of in-flight refresh, concurrent live-price fetch within rate limits (existing 429 backoff). Add exponential backoff for transient failures + separate failed-symbol log table/log line. Per-stock try/catch so one bad symbol never aborts the job (mirrors existing skip behavior).
- **Frontend:** virtualize the large uploaded-stocks table (700–5000+ rows) via CDK virtual scroll or windowing; patch changed rows only; guard against duplicate refresh requests; section-scoped loading indicators.
- **Acceptance:** large dataset stays responsive; killing one symbol/price/API call does not stop the batch; UI shows meaningful status without crashing; market-closed/holiday/empty-dataset paths handled gracefully.

### Phase 7 — Verification
- Backend compiles (`mvn -q -pl backend compile`); frontend type-checks/builds.
- Targeted checks: ranking skip still identical to current; default-config regression (behavior unchanged when all new flags default); simulate buy/sell cycle on a sample batch; confirm no full-page reloads (only active section).
- Manual test checklist mapped to spec §12 "Expected Result".

---

## 3. Files to be touched (additive)

**Backend**
- `service/StockTrackerService.java` — automation config, scheduled buy/sell jobs, Top-10 diff broadcast, ordered pipeline, backoff/failed-symbol log.
- `controller/StockTrackerController.java` — `/tracker/automation/config` GET/PUT; refresh response timestamp.
- `service/LivePriceBroadcaster.java` — `top10:update` channel (or reuse existing broadcast).
- `config/SymbolSchemaGuard.java` — add columns if needed (`profit_loss`, `sell_date`, `buy_date` if not present) + a `top10_failed_symbols` log table.
- New migration `V31__stock_upload_automation.sql` — automation config seed + any new columns/indexes.

**Frontend**
- `features/stock-tracker/stock-tracker.component.ts` — automation panel + toggles; manual-refresh state machine; Top-10 `trackBy` + row-diff animation; virtualized uploaded-stocks table; section-scoped refresh guards.
- `core/services/live-price.service.ts` — subscribe to `top10:update`.

---

## 4. Risks & mitigations
- **Real-money orders:** mitigated by `PAPER` default + explicit off-by-default `LIVE` switch + confirm dialog. I will not enable live auto-orders.
- **Large single files (5.5k-line service, 3.1k-line component):** changes are localized and additive; existing method signatures preserved.
- **Dirty working tree (many uncommitted changes):** building on current state as requested; no unrelated files touched.
- **Scheduling correctness (IST, holidays):** reuse existing IST zone + market-open checks; holiday list configurable.

---

## 5. Open items I'll assume unless told otherwise
- Holiday source: use a configurable `market.holidays` list (seeded with NSE holidays) unless the existing `EventCalendar` already exposes holidays — I'll wire to whichever is cleanest.
- "User executed a real trade" detection: matched via existing `real_trades` table by symbol + date.
- Sell time default `09:30` and buy time `15:20` per spec (overridable in config).
