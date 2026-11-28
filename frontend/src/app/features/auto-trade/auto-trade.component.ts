import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';

/**
 * Auto Buy &amp; Sell, for any strategy in the system.
 *
 * <p>Automation used to be a panel inside the stock tracker, welded to one strategy. This screen
 * asks which strategy first and everything else follows from that answer: the stocks it currently
 * identifies, the settings that govern it, and the buttons that act on it. A strategy built in
 * the Strategy Builder reaches execution the same way the built-in sections do.
 *
 * <p>Settings are per strategy AND per mode. A stop loss that suits a five-stock overnight basket
 * is not the one for a screen returning thirty names, and arming one strategy must never arm
 * another.
 */
@Component({
  selector: 'app-auto-trade',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host { display:block; }
    .wrap { background:#0a1422; min-height:100vh; padding:16px 20px 28px; color:#e2e8f0;
            font-family:'Segoe UI',system-ui,-apple-system,sans-serif; }

    .hdr { display:flex; align-items:center; gap:13px; flex-wrap:wrap; margin-bottom:15px; }
    .hdr-mark { width:42px; height:42px; border-radius:11px; display:flex; align-items:center;
                justify-content:center; font-size:20px; flex:0 0 auto;
                background:linear-gradient(135deg,#0f766e,#1d4ed8); }
    .hdr-t { font-size:22px; font-weight:800; color:#fff; line-height:1.15; }
    .hdr-s { font-size:11.5px; color:#8fa3bd; margin-top:2px; }

    .card { background:#0f1b2d; border:1px solid #1d2c42; border-radius:12px; margin-bottom:13px; }
    .card-hd { display:flex; align-items:center; gap:9px; padding:12px 14px; flex-wrap:wrap;
               border-bottom:1px solid #1d2c42; }
    .card-t { font-size:14px; font-weight:800; color:#fff; }
    .card-b { padding:13px 14px; }

    .btn { border-radius:8px; font-size:11.5px; font-weight:700; padding:7px 14px; cursor:pointer;
           border:1px solid #223449; background:#132033; color:#cbd5e1; white-space:nowrap; }
    .btn:hover:not([disabled]) { background:#1b2c44; }
    .btn[disabled] { opacity:.5; cursor:not-allowed; }
    .btn-buy  { background:#0b3b37; border-color:#16d6a4; color:#5ff5cd; font-weight:800; }
    .btn-buy:hover:not([disabled])  { background:#11564f; }
    .btn-sell { background:#3b1116; border-color:#ef4444; color:#fecaca; font-weight:800; }
    .btn-sell:hover:not([disabled]) { background:#58181f; }

    .sel, .num { background:#0e1d31; border:1px solid #2a3f58; border-radius:8px; color:#e2e8f0;
                 font-size:11.5px; padding:7px 9px; min-height:34px; }
    .sel:focus, .num:focus { outline:none; border-color:#3b82f6; }

    .seg { display:inline-flex; background:#132033; border:1px solid #223449; border-radius:9px;
           padding:3px; gap:3px; }
    .seg button { background:transparent; border:none; color:#8fa3bd; font-size:11.5px;
                  font-weight:700; padding:6px 15px; border-radius:7px; cursor:pointer; }
    .seg button.on { background:#1d4ed8; color:#fff; }
    .seg button.on.real { background:#b91c1c; }

    .pill { font-size:10px; font-weight:800; border-radius:999px; padding:3px 11px; }
    .pill-on  { background:#0b3b37; color:#5ff5cd; }
    .pill-off { background:#241b06; color:#fde68a; }
    .pill-real{ background:#4c1118; color:#fca5a5; }


    .fld { display:flex; flex-direction:column; gap:4px; background:#0b1626;
           border:1px solid #223449; border-radius:9px; padding:9px 11px; }
    .fld-l { font-size:9px; color:#8fa3bd; text-transform:uppercase; letter-spacing:.5px;
             font-weight:700; }
    .fld-h { font-size:9px; color:#64748b; line-height:1.5; }
    .fld .num, .fld .sel { width:100%; }
    .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(215px,1fr)); gap:9px; }

    table { width:100%; border-collapse:collapse; }
    th { font-size:10px; text-transform:uppercase; letter-spacing:.5px; color:#8fa3bd;
         text-align:left; padding:6px 8px; border-bottom:1px solid #223449; font-weight:700; }
    td { font-size:11.5px; color:#cbd5e1; padding:6px 8px; border-bottom:1px solid #16263d; }
    .r { text-align:right; }
    .scroll-x { width:100%; overflow-x:auto; }

    .note { font-size:10.5px; line-height:1.6; border-radius:8px; padding:8px 11px;
            overflow-wrap:anywhere; }
    .note-ok   { background:#0b3b37; border:1px solid #16d6a4; color:#bbf7d0; }
    .note-warn { background:#3a2608; border:1px solid #d97706; color:#fde68a; }
    .note-err  { background:#3b1116; border:1px solid #7f1d1d; color:#fca5a5; }
    .note-dim  { background:#0b1626; border:1px solid #223449; color:#9eb8dd; }

    .alloc-v { font-size:14px; font-weight:800; color:#e2e8f0; }
    /* The nine Automation settings, in at most two horizontal rows. flex-wrap keeps them to two
       on a desktop and stacks them on a phone rather than forcing a sideways scroll. */
    .autoRow { display:flex; gap:10px; flex-wrap:wrap; align-items:flex-end; margin-bottom:10px; }
    .autoRow > .fld { flex:1 1 150px; min-width:140px; margin:0; }
    @media (max-width:640px) { .autoRow > .fld { flex:1 1 100%; } }
    .empty { font-size:11.5px; color:#8fa3bd; line-height:1.7; padding:10px 2px; }
    .spin { width:12px; height:12px; border:2px solid rgba(255,255,255,.25); border-top-color:#fff;
            border-radius:50%; animation:sp .7s linear infinite; display:inline-block; }
    @keyframes sp { to { transform:rotate(360deg); } }
    pre.out { font-size:10px; line-height:1.6; color:#cbd5e1; white-space:pre-wrap;
              overflow-wrap:anywhere; margin:0; }

    .cols { display:grid; grid-template-columns:minmax(0,1fr); gap:13px; align-items:start; }

    /* The filter bar. Wraps rather than scrolls: a filter you cannot see is a filter you
       forget is applied, which is how a screen ends up lying about what it is showing. */
    .filters { display:grid; grid-template-columns:repeat(auto-fit,minmax(170px,1fr)); gap:9px;
               align-items:end; }
    .fil { display:flex; flex-direction:column; gap:4px; min-width:0; }
    .fil-l { font-size:9px; color:#8fa3bd; text-transform:uppercase; letter-spacing:.5px;
             font-weight:700; }
    .fil .sel, .fil .num, .fil .btn { width:100%; }
    @media (max-width:720px) {
      .wrap { padding:12px 12px 22px; }
      .sel, .num { min-height:40px; font-size:12.5px; }
      .btn { padding:9px 14px; }
    }
  `],
  template: `
<div class="wrap">

  <div class="hdr">
    <div class="hdr-mark">&#129302;</div>
    <div>
      <div class="hdr-t">Auto Buy &amp; Sell</div>
      <div class="hdr-s">Pick a strategy, see what it identifies now, and let it trade on its own.</div>
    </div>
    <div style="margin-left:auto; display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
      <span class="pill" [class.pill-on]="ctx?.liveMode === 'PAPER'"
            [class.pill-real]="ctx?.liveMode === 'REAL'">Live mode: {{ ctx?.liveMode || '—' }}</span>
      <button class="btn" (click)="loadAll()" [disabled]="loading">
        @if (loading) { <span class="spin"></span> Reading… } @else { Refresh }
      </button>
    </div>
  </div>

  <div class="cols">

    <!-- ── filters ─────────────────────────────────────────────────────── -->
    <div class="card" style="grid-column:1 / -1;">
      <div class="card-hd"><span class="card-t">Filters</span>
        <span style="margin-left:auto; font-size:10px; color:#64748b;">
          {{ sources.length }} strategy(ies) available</span>
      </div>
      <div class="card-b">
        <div class="filters">
          <label class="fil">
            <span class="fil-l">Strategy</span>
            <select class="sel" [(ngModel)]="selectedKey" (ngModelChange)="onSelect()"
                    [disabled]="loading || !sources.length"
                    title="Which strategy automation acts on. Built-in sections first, then anything saved in the Strategy Builder.">
              @if (!sources.length) { <option value="">No strategy available</option> }
              @for (g of sourceGroups(); track g.label) {
                <optgroup [label]="g.label">
                  @for (x of g.items; track x.key) {
                    <option [value]="x.key">{{ x.name }}</option>
                  }
                </optgroup>
              }
            </select>
          </label>

          <label class="fil">
            <span class="fil-l">Account</span>
            <select class="sel" [(ngModel)]="mode" (ngModelChange)="setMode(mode)"
                    title="Which money this screen spends. The Demo Account is a virtual ledger and nothing reaches the broker. The Real Account needs an authorized, connected broker.">
              <option value="PAPER">Demo Account (virtual)</option>
              <option value="REAL">Real Account (broker)</option>
            </select>
          </label>

          <label class="fil">
            <span class="fil-l">Status</span>
            <select class="sel" [(ngModel)]="rowFilter"
                    title="Narrow the stock list below without changing what the strategy selected.">
              <option value="ALL">All stocks</option>
              <option value="GAINERS">Gainers only</option>
              <option value="LOSERS">Losers only</option>
              <option value="OPEN">Open positions</option>
            </select>
          </label>

          <label class="fil">
            <span class="fil-l">Investment Amount</span>
            <input class="num" type="number" min="0" [max]="buyingPower ?? undefined"
                   [(ngModel)]="form.investmentAmount" (ngModelChange)="onInvestmentChanged()"
                   title="The capital to split across the stocks selected below. This is the same figure automation deploys, so the manual and automatic paths cannot disagree about it.">
            <span class="fld-h">
              @if (investmentIssue(); as issue) {
                <span style="color:#fca5a5;">{{ issue }}</span>
              } @else {
                Unallocated {{ RUPEE }}{{ unallocated() | number:'1.0-2' }}
              }
            </span>
          </label>
          <label class="fil">
            <span class="fil-l">Search</span>
            <input class="num" [(ngModel)]="rowSearch" placeholder="Symbol"
                   style="text-transform:uppercase;"
                   title="Filters the list below by symbol.">
          </label>

          <div class="fil" style="justify-content:flex-end;">
            <span class="fil-l">&nbsp;</span>
            <button class="btn" (click)="loadStocks()" [disabled]="stocksLoading || !selectedKey">
              @if (stocksLoading) { <span class="spin"></span> Screening&hellip; } @else { Apply }
            </button>
          </div>
        </div>

        <!-- Selected Account, Available Balance, Investment Amount and Remaining
             Unallocated, together, because the specification asks for all four to be visible at
             once and because an investment figure means nothing without the balance beside it.

             The real figures are the broker's own and are never invented: available cash, used
             margin and buying power are reported separately wherever the broker distinguishes
             them, and a reading that could not be refreshed says so with its timestamp rather
             than showing a stale number as though it were current. -->
        <div style="display:flex; gap:16px; flex-wrap:wrap; align-items:flex-start;
                    border-top:1px solid #1d2c42; padding-top:10px; margin-top:10px;">
          <div>
            <div class="fld-l">Selected Account</div>
            <div class="alloc-v" [style.color]="mode === 'REAL' ? '#fbbf24' : '#5ff5cd'">
              {{ acctSummary.label || (mode === 'REAL' ? 'Real Account' : 'Demo Account') }}
            </div>
            @if (mode === 'REAL') {
              <div style="font-size:9.5px; color:#8fa3bd; margin-top:2px;">
                @if (acctSummary.connected) {
                  {{ acctSummary.brokerName || 'Broker' }}
                  @if (acctSummary.clientId) { &middot; {{ acctSummary.clientId }} }
                } @else {
                  <span style="color:#fca5a5;">No broker connected</span>
                }
              </div>
            } @else {
              <div style="font-size:9.5px; color:#8fa3bd; margin-top:2px;">
                Virtual ledger &middot; nothing reaches the broker
              </div>
            }
          </div>

          <div>
            <div class="fld-l">Available Balance</div>
            <div class="alloc-v">
              @if (balance !== null) { {{ RUPEE }}{{ balance | number:'1.0-2' }} }
              @else { <span style="font-size:11px; color:#fca5a5;">Not available</span> }
            </div>
            @if (balanceStale && balanceAsOf) {
              <div style="font-size:9.5px; color:#fca5a5; margin-top:2px;">
                Could not be refreshed &middot; as at {{ balanceAsOf }}
              </div>
            }
          </div>

          <!-- Only shown where the broker actually distinguishes them. A demo account has no
               margin facility, so printing "used margin 0" there would invent a concept. -->
          @if (mode === 'REAL' && acctSummary.connected) {
            <div>
              <div class="fld-l">Used Margin</div>
              <div class="alloc-v" style="color:#94a3b8;">
                @if (acctSummary.usedMargin !== null) { {{ RUPEE }}{{ acctSummary.usedMargin | number:'1.0-2' }} }
                @else { <span style="font-size:11px; color:#64748b;">Not reported</span> }
              </div>
            </div>
          }

          <div>
            <div class="fld-l">Buying Power</div>
            <div class="alloc-v" style="color:#e2e8f0;">
              @if (buyingPower !== null) { {{ RUPEE }}{{ buyingPower | number:'1.0-2' }} }
              @else { <span style="font-size:11px; color:#64748b;">Unknown</span> }
            </div>
          </div>

          <div>
            <div class="fld-l">Investment Amount</div>
            <div class="alloc-v" style="color:#fbbf24;">
              {{ RUPEE }}{{ (form.investmentAmount || 0) | number:'1.0-2' }}
            </div>
          </div>

          <div>
            <div class="fld-l">Remaining Unallocated</div>
            <div class="alloc-v" style="color:#94a3b8;">
              {{ RUPEE }}{{ unallocated() | number:'1.0-2' }}
            </div>
          </div>

          <div style="margin-left:auto;">
            <button class="btn" (click)="loadBalance()" [disabled]="balanceLoading"
                    title="Re-reads the balance from the broker.">
              @if (balanceLoading) { <span class="spin"></span> Reading&hellip; } @else { Refresh balance }
            </button>
          </div>
        </div>

        @if (investmentIssue(); as issue) {
          <div class="note note-err" style="margin-top:9px;">{{ issue }}</div>
        }
        @if (balanceError && balance === null) {
          <div class="note note-warn" style="margin-top:9px;">{{ balanceError }}</div>
        }

        @if (selectedSource(); as src) {
          <!-- What the chosen strategy actually is. In a dropdown the description is hidden
               until after the choice is made, which is the one moment it is needed - so it is
               restated here under the filter. -->
          <div style="font-size:10px; color:#8fa3bd; line-height:1.6; margin-top:9px;
                      border-top:1px solid #1d2c42; padding-top:9px;">
            <strong style="color:#cbd5e1;">{{ src.name }}</strong>
            &mdash; {{ src.description }}
            <span style="color:#64748b;">({{ src.basis }}{{ src.universe ? ' · ' + src.universe : '' }})</span>
          </div>
        }
      </div>
    </div>

    <!-- ── what it identifies, and what to do about it ──────────────────── -->
    <div style="min-width:0;">

      @if (!selectedKey) {
        <div class="card"><div class="card-b">
          <div class="empty">Pick a strategy on the left to see the stocks it identifies now.</div>
        </div></div>
      } @else {

        <!-- stocks -->
        <div class="card">
          <div class="card-hd">
            <span class="card-t">{{ selectedName() }}</span>
            <button class="btn" style="margin-left:auto;" (click)="loadStocks()"
                    [disabled]="stocksLoading">
              @if (stocksLoading) { <span class="spin"></span> Screening… } @else { Refresh stocks }
            </button>
          </div>
          <div class="card-b">

            @if (stocks?.as_of) {
              <div class="note" style="margin-bottom:10px;"
                   [class.note-warn]="(stocks.days_behind_today || 0) > 1"
                   [class.note-dim]="(stocks.days_behind_today || 0) <= 1">
                Evaluated on the <strong>{{ stocks.as_of }}</strong> session
                @if (stocks.qualifying_count) { · {{ stocks.qualifying_count }} qualified }
                @if ((stocks.days_behind_today || 0) > 1) {
                  · <strong>{{ stocks.days_behind_today }} days behind today.</strong>
                  Later daily closes are not stored, so a stock that qualified since then cannot
                  appear here.
                }
              </div>
            }

            @if (stocks?.skippedConditions?.length) {
              <div class="note note-warn" style="margin-bottom:10px;">
                <strong>Conditions skipped — the data for them is not loaded:</strong>
                @for (m of stocks.skippedConditions; track $index) { <div>• {{ m }}</div> }
              </div>
            }

            @if (stocksLoading && !stocks) {
              <div class="empty"><span class="spin"></span> Running the strategy…</div>
            } @else if (visibleRows().length) {
              <div class="scroll-x">
                <table>
                  <thead><tr>
                    <th style="width:28px;">
                      <input type="checkbox" [checked]="allPicked()" (change)="toggleAll($event)"
                             title="Select or clear every row">
                    </th>
                    <th>Symbol</th>
                    <th class="r">Current</th>
                    <th class="r">Return %</th>
                    <th class="r">Volume</th>
                    <th class="r">Ratio</th>
                    <th class="r" title="This stock's share of the investment amount above">Allocated</th>
                    <th class="r" title="Whole shares that fit inside the allocated amount at a fresh price">Qty</th>
                    <th class="r" title="The capital deployed plus the round-trip charges it will pay - what the order actually costs">Est. Investment</th>
                    <th title="Why a stock received no quantity, when it did not">Allocation</th>
                    <th>Status</th>
                  </tr></thead>
                  <tbody>
                    @for (r of visibleRows(); track r.symbol) {
                      <tr>
                        <td><input type="checkbox" [checked]="picked.has(r.symbol)"
                                   (change)="toggle(r.symbol)"></td>
                        <td style="color:#fff; font-weight:700;">{{ r.symbol }}</td>
                        <td class="r">{{ r.current_price | number:'1.2-2' }}</td>
                        <td class="r" [style.color]="(r.return_pct || 0) >= 0 ? '#22c55e' : '#ef4444'">
                          {{ r.return_pct == null ? '—'
                             : ((r.return_pct > 0 ? '+' : '') + (r.return_pct | number:'1.2-2') + '%') }}</td>
                        <td class="r" style="color:#94a3b8;">{{ volFmt(r.volume) }}</td>
                        <td class="r" style="font-weight:700; color:#fbbf24;">
                          {{ r.volume_ratio ? (r.volume_ratio | number:'1.2-2') + 'x' : '—' }}</td>
                        <!-- Allocation, on the row it belongs to. It used to be a second table
                             below this one, which meant reading a stock's quantity in one place
                             and everything else about it in another. -->
                        @if (legFor(r.symbol); as l) {
                          <td class="r">{{ RUPEE }}{{ l.allocated | number:'1.0-2' }}</td>
                          <td class="r" style="font-weight:800;"
                              [style.color]="l.quantity > 0 ? '#fff' : '#f87171'">{{ l.quantity }}</td>
                          <td class="r">
                            @if (legCost(r.symbol); as c) { {{ RUPEE }}{{ c | number:'1.0-2' }} }
                            @else { — }
                          </td>
                          <td style="color:#94a3b8; font-size:10px;">{{ l.note || '—' }}</td>
                        } @else {
                          <!-- Nothing sized yet. Dashes rather than zeros: a zero quantity is a
                               decision, and no allocation having been run is not one. -->
                          <td class="r" style="color:#64748b;">—</td>
                          <td class="r" style="color:#64748b;">—</td>
                          <td class="r" style="color:#64748b;">—</td>
                          <td style="color:#64748b; font-size:10px;">Not sized</td>
                        }
                        <td style="color:#94a3b8;">{{ r.status || r.signal || '—' }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
              <div style="font-size:10px; color:#64748b; margin-top:7px;">
                {{ picked.size }} of {{ visibleRows().length }} shown selected.
                @if (visibleRows().length !== rows().length) {
                  <span>({{ rows().length }} before filters.)</span>
                }
                Buy and Sell act on the selection; with nothing ticked they act on
                everything currently shown.
              </div>
            } @else {
              <div class="empty">{{ stocks?.message || 'This strategy identifies no stock right now.' }}</div>
            }
          </div>
        </div>

        <!-- automation -->
        <div class="card">
          <div class="card-hd">
            <span class="card-t">Automation</span>
            <span class="pill" style="margin-left:auto;"
                  [class.pill-on]="mode === 'PAPER'" [class.pill-real]="mode === 'REAL'">
              {{ mode === 'REAL' ? 'Real Trading' : 'Paper Trading' }}
            </span>
          </div>
          <div class="card-b">

            @if (mode === 'REAL') {
              <div class="note" style="margin-bottom:11px;"
                   [class.note-ok]="realReady" [class.note-warn]="!realReady">
                @if (realReady) {
                  <strong>Real trading is authorized and the broker is connected.</strong>
                  These settings take effect while REAL is the live mode.
                } @else {
                  <strong>Real orders cannot be placed yet.</strong>
                  @if (!ctx?.realTradingAuthorized) { This account has not enabled real trading. }
                  @if (!ctx?.brokerReady) { No broker is connected and authorized. }
                  Settings can still be prepared — they will not fire until both are in place,
                  and neither can be granted from this screen.
                }
              </div>
            }

            <!-- Automation configuration: the nine settings, in two horizontal rows.
                 Everything that decides WHETHER and WHEN automation acts is in row one; what it
                 spends and how far it may go is in row two. The risk settings sit below in a
                 group of their own rather than a third row - they are limits on an order, not
                 the configuration of the schedule. -->

            <div class="autoRow">
              <label class="fld">
                <span class="fld-l">Automation Status</span>
                <select class="sel" [(ngModel)]="form.armed"
                        title="Disable means no automatic order is placed, whatever else is set here.">
                  <option [ngValue]="true">Enable</option>
                  <option [ngValue]="false">Disable</option>
                </select>
                <span class="fld-h">
                  @if (form.armed) {
                    <span style="color:#86efac;">Automation may act in this account.</span>
                  } @else {
                    <span style="color:#fca5a5;">No automatic order will be placed.</span>
                  }
                </span>
              </label>

              <label class="fld">
                <span class="fld-l">Account Type</span>
                <select class="sel" [(ngModel)]="mode" (ngModelChange)="setMode(mode)"
                        title="Which money automation spends. Changing it reloads that account's own settings and balance.">
                  <option value="PAPER">Paper (Demo)</option>
                  <option value="REAL">Real (Broker)</option>
                </select>
                <span class="fld-h">Settings are stored per account.</span>
              </label>

              <label class="fld">
                <span class="fld-l">Selected Strategy</span>
                <select class="sel" [(ngModel)]="selectedKey" (ngModelChange)="onSelect()"
                        [disabled]="loading || !sources.length"
                        title="Automation acts on this strategy's own qualified stocks. The strategy logic itself is unchanged.">
                  @for (g of sourceGroups(); track g.label) {
                    <optgroup [label]="g.label">
                      @for (x of g.items; track x.key) {
                        <option [value]="x.key">{{ x.name }}</option>
                      }
                    </optgroup>
                  }
                </select>
                <span class="fld-h">The existing strategy, reused as-is.</span>
              </label>

              <label class="fld">
                <span class="fld-l">Buy Time</span>
                <input class="num" type="time" [(ngModel)]="form.buyTime">
                <span class="fld-h">Entry conditions are rechecked at this time.</span>
              </label>

              <label class="fld">
                <span class="fld-l">Sell Time</span>
                <input class="num" type="time" [(ngModel)]="form.sellTime">
                <span class="fld-h">Exit rules may close earlier.</span>
              </label>
            </div>

            <div class="autoRow">
              <label class="fld">
                <span class="fld-l">Investment Amount (&#8377;)</span>
                <input class="num" type="number" [(ngModel)]="form.investmentAmount"
                       (ngModelChange)="onInvestmentChanged()" min="1" step="1000">
                <span class="fld-h">The same figure the list above allocates.</span>
              </label>

              <label class="fld">
                <span class="fld-l">Allocation Method</span>
                <!-- One method exists. Shown as a fixed value rather than a dropdown, because a
                     list with a single option implies there are others to pick. -->
                <input class="num" type="text" [value]="allocationMethodLabel" readonly
                       style="cursor:default; color:#94a3b8;">
                <span class="fld-h">Whole shares; the remainder stays unallocated.</span>
              </label>

              <label class="fld">
                <span class="fld-l">Max Number of Stocks</span>
                <input class="num" type="number" [(ngModel)]="form.maxOpenPositions" min="0" step="1">
                <span class="fld-h">0 for no limit. Never blocks an exit.</span>
              </label>

              <label class="fld">
                <span class="fld-l">Max Capital Utilization (%)</span>
                <input class="num" type="number" [(ngModel)]="form.maxCapitalUtilizationPct"
                       min="1" max="100" step="1">
                <span class="fld-h">
                  Ceiling as a share of the balance.
                  @if (utilizationCeiling() !== null) {
                    <span style="color:#8fa3bd;">Caps at &#8377;{{ utilizationCeiling() | number:'1.0-0' }}.</span>
                  }
                </span>
              </label>
            </div>

            @if (!form.armed) {
              <div class="note note-warn" style="margin-bottom:10px;">
                Automation is <strong>disabled</strong> for this strategy in the
                {{ mode === 'REAL' ? 'Real' : 'Paper' }} account. Nothing will be placed
                automatically until it is enabled and saved.
              </div>
            }

            <!-- Risk limits. Grouped and collapsed rather than moved to another screen: they are
                 bounds on an individual order, and keeping them here means one place to read the
                 whole configuration. -->
            <details style="margin-bottom:11px;">
              <summary style="font-size:11px; font-weight:700; color:#fbbf24; cursor:pointer;
                              padding:5px 0;">
                Risk limits and trading window
              </summary>
              <div class="grid" style="margin-top:9px;">
                <label class="fld">
                  <span class="fld-l">Auto Buy</span>
                  <span style="display:flex; align-items:center; gap:7px;">
                    <input type="checkbox" [(ngModel)]="form.autoBuy">
                    <span style="font-size:10px;">{{ form.autoBuy ? 'Enabled' : 'Disabled' }}</span>
                  </span>
                  <span class="fld-h">Opens positions at the Buy Time above.</span>
                </label>
                <label class="fld">
                  <span class="fld-l">Auto Sell</span>
                  <span style="display:flex; align-items:center; gap:7px;">
                    <input type="checkbox" [(ngModel)]="form.autoSell">
                    <span style="font-size:10px;">{{ form.autoSell ? 'Enabled' : 'Disabled' }}</span>
                  </span>
                  <span class="fld-h">Closes them on an exit condition. Independent of Auto Buy.</span>
                </label>
                <label class="fld"><span class="fld-l">Max capital per stock (&#8377;)</span>
                  <input class="num" type="number" [(ngModel)]="form.maxCapitalPerStock" min="0" step="1000">
                  <span class="fld-h">0 for no cap.</span></label>
                <label class="fld"><span class="fld-l">Max daily trades</span>
                  <input class="num" type="number" [(ngModel)]="form.maxDailyTrades" min="0" step="1">
                  <span class="fld-h">0 for no limit.</span></label>
                <label class="fld"><span class="fld-l">Max daily loss (&#8377;)</span>
                  <input class="num" type="number" [(ngModel)]="form.maxDailyLoss" min="0" step="500">
                  <span class="fld-h">Realised loss today. Past it, no new entry is taken.</span></label>
                <label class="fld"><span class="fld-l">Stop loss %</span>
                  <input class="num" type="number" [(ngModel)]="form.stopLossPct" min="0" max="100" step="0.25">
                  <span class="fld-h">Below each position's own entry price. 0 disables it.</span></label>
                <label class="fld"><span class="fld-l">Trailing stop %</span>
                  <input class="num" type="number" [(ngModel)]="form.trailingStopPct" min="0" max="100" step="0.25">
                  <span class="fld-h">Below that position's own high water mark.</span></label>
                <label class="fld"><span class="fld-l">Target %</span>
                  <input class="num" type="number" [(ngModel)]="form.targetPct" min="0" max="1000" step="0.25">
                  <span class="fld-h">Above entry. 0 lets it run to the time exit.</span></label>
                <label class="fld"><span class="fld-l">Trading start</span>
                  <input class="num" type="time" [(ngModel)]="form.tradingStartTime">
                  <span class="fld-h">Automation does nothing before this.</span></label>
                <label class="fld"><span class="fld-l">Trading end</span>
                  <input class="num" type="time" [(ngModel)]="form.tradingEndTime">
                  <span class="fld-h">Automation does nothing after this.</span></label>
              </div>
            </details>

            <!-- ── Capital Allocation ──────────────────────────────────────────
                 Here rather than on the strategy's own screen, because this is where the money
                 is actually committed. The amount it splits is the Capital to deploy above, so
                 the figure that governs automation and the figure a manual Buy uses are one
                 number instead of two that can disagree. -->
            <div style="border-top:1px solid #1d2c42; padding-top:11px; margin-bottom:11px;">
              <div style="display:flex; align-items:center; gap:9px; flex-wrap:wrap; margin-bottom:9px;">
                <span style="font-size:11px; font-weight:800; color:#fbbf24;">Capital Allocation</span>
                <span style="font-size:10px; color:#8fa3bd;">
                  &#8377;{{ form.investmentAmount | number:'1.0-0' }} split equally across the
                  {{ actOn().length }} stock(s) selected above, in whole shares.
                </span>
                <button class="btn" style="margin-left:auto;" (click)="allocate()"
                        [disabled]="allocLoading || !actOn().length"
                        title="Re-sizes every quantity against a fresh price on the server.">
                  @if (allocLoading) { <span class="spin"></span> Working&hellip; } @else { Recalculate }
                </button>
              </div>

              @if (!actOn().length) {
                <div class="empty" style="padding:2px;">
                  Nothing selected, so there is nothing to size. Tick stocks above, or leave them
                  all unticked to allocate across the whole list.
                </div>
              } @else if (alloc) {
                <div style="display:grid; grid-template-columns:repeat(auto-fit,minmax(130px,1fr));
                            gap:9px; margin-bottom:9px;">
                  <div><div class="fld-l">Selected</div>
                       <div class="alloc-v">{{ actOn().length }}</div></div>
                  <div><div class="fld-l">Per Stock</div>
                       <div class="alloc-v">&#8377;{{ alloc.perStock | number:'1.0-2' }}</div></div>
                  <div><div class="fld-l">Available</div>
                       <div class="alloc-v">&#8377;{{ alloc.availableBalance | number:'1.0-0' }}</div></div>
                  <div><div class="fld-l">Deployed</div>
                       <div class="alloc-v" [style.color]="alloc.success === false ? '#f87171' : '#22c55e'">
                         &#8377;{{ alloc.deployedCapital | number:'1.0-0' }}</div></div>
                  <div><div class="fld-l">Residual</div>
                       <div class="alloc-v" style="color:#94a3b8;">&#8377;{{ alloc.residualCash | number:'1.0-2' }}</div></div>
                  <!-- The hurdle, before the order rather than after it. A one-session hold pays
                       this twice, in and out, so it has to clear it before it earns anything. -->
                  <div><div class="fld-l"
                            title="Brokerage, STT, exchange and SEBI charges, stamp duty, GST and the per-ISIN depository fee, for the round trip, at the broker's published rates.">Round-trip cost</div>
                       <div class="alloc-v" style="color:#fbbf24;">
                         &#8377;{{ alloc.estimatedRoundTripCharges | number:'1.0-2' }}
                         <span style="font-size:10px; font-weight:600; color:#94a3b8;">
                           ({{ alloc.estimatedChargesPct | number:'1.2-2' }}%)</span></div></div>
                </div>

                @if (alloc.message) {
                  <div class="note" style="margin-bottom:9px;"
                       [class.note-err]="alloc.success === false"
                       [class.note-warn]="alloc.success !== false">{{ alloc.message }}</div>
                }

                <!-- The per-stock figures are inline in the stock list above rather than
                     repeated here: one list of stocks, with the allocation on each row. The
                     summary tiles stay, because a total is not a second list. -->
                <div class="note note-dim">
                  Per-stock quantities are shown on each row of the list above.
                </div>
              } @else {
                <div class="empty" style="padding:2px;">
                  Press <strong>Recalculate</strong> to size the order against fresh prices.
                </div>
              }
            </div>

            <div style="display:flex; gap:8px; flex-wrap:wrap; align-items:center;">
              <button class="btn btn-buy" (click)="save()" [disabled]="saving">
                {{ saving ? 'Saving…' : 'Save ' + (mode === 'REAL' ? 'Real' : 'Paper') + ' settings' }}
              </button>
              <button class="btn" (click)="loadSettings()" [disabled]="saving">Discard changes</button>
              @if (saveMsg) {
                <span style="font-size:10.5px; line-height:1.5;"
                      [style.color]="saveOk ? '#86efac' : '#fca5a5'">{{ saveMsg }}</span>
              }
            </div>
          </div>
        </div>

        <!-- Act now has been removed.
             It was a second way to fire the same orders: its buttons posted to
             /trading/auto/strategies/run, which resolves the strategy and calls
             placeManual/sellManual - the very methods the scheduled engine uses. Two entry
             points to one execution path meant the screen could place a trade that ignored
             every Automation setting beside it: buy time, sell time, maximum stocks and
             maximum capital utilisation were all bypassed by a click.

             Buying and selling is now driven by the Automation settings above. The execution
             logic itself was NOT removed - it is reused by the engine, and remains reachable
             for the per-stock manual Buy/Sell actions on the strategy screens, which carry
             their own Real/Paper confirmation. -->
      }
    </div>
  </div>
</div>
  `
})
export class AutoTradeComponent implements OnInit {

  constructor(private api: ApiService) {}

  ctx: any = null;
  sources: any[] = [];
  selectedKey = '';
  stocks: any = null;
  loading = false;
  stocksLoading = false;
  saving = false;
  saveMsg = '';
  saveOk = false;

  mode: 'PAPER' | 'REAL' = 'PAPER';
  settings: any = null;
  picked = new Set<string>();

  form: any = {
    armed: false, autoBuy: false, autoSell: false,
    investmentAmount: 100000, maxCapitalPerStock: 0, maxOpenPositions: 5,
    maxDailyTrades: 0, maxDailyLoss: 0,
    stopLossPct: 0, trailingStopPct: 0, targetPct: 0,
    tradingStartTime: '09:15', tradingEndTime: '15:30',
    buyTime: '15:25', sellTime: '15:25', maxCapitalUtilizationPct: 100
  };

  ngOnInit(): void { this.loadAll(); this.loadBalance(); }

  get realReady(): boolean {
    return !!this.ctx?.realTradingAuthorized && !!this.ctx?.brokerReady;
  }

  loadAll(): void {
    this.loading = true;
    this.api.get<any>('/trading/auto/strategies').subscribe({
      next: r => {
        this.ctx = r?.data || null;
        this.sources = this.ctx?.sources || [];
        this.loading = false;
        if (this.ctx?.liveMode === 'REAL') this.mode = 'REAL';
        // Open on the first strategy rather than an empty screen: the commonest case is one
        // strategy being watched, and making them click it first is a step for nothing.
        if (!this.selectedKey && this.sources.length) {
          this.selectedKey = this.sources[0].key;
          this.onSelect();
        }
      },
      error: () => { this.loading = false; this.sources = []; }
    });
  }

  selectedName(): string {
    return this.sources.find(s => s.key === this.selectedKey)?.name || 'Strategy';
  }

  onSelect(): void {
    this.stocks = null;
    this.alloc = null;
    this.picked.clear();
    this.saveMsg = '';
    this.loadStocks();
    this.loadSettings();
  }

  loadStocks(): void {
    if (!this.selectedKey || this.stocksLoading) return;
    this.stocksLoading = true;
    this.api.get<any>('/trading/auto/strategies/stocks', { key: this.selectedKey }).subscribe({
      next: r => {
        this.stocks = r?.data || null;
        this.stocksLoading = false;
        // Keep ticks that still exist; drop the rest rather than carrying a selection of
        // stocks that are no longer in the list.
        const live = new Set(this.rows().map(x => x.symbol));
        for (const s of Array.from(this.picked)) if (!live.has(s)) this.picked.delete(s);
      },
      error: e => {
        this.stocksLoading = false;
        this.stocks = { rows: [], message: e?.error?.message || 'Could not run that strategy.' };
      }
    });
  }

  loadSettings(): void {
    if (!this.selectedKey) return;
    this.api.get<any>('/trading/auto/strategies/settings', { key: this.selectedKey }).subscribe({
      next: r => {
        this.settings = r?.data || null;
        if (this.ctx) {
          this.ctx.realTradingAuthorized = this.settings?.realTradingAuthorized;
          this.ctx.brokerReady = this.settings?.brokerReady;
        }
        this.applyForm();
      },
      error: () => { this.settings = null; }
    });
  }

  /** The one allocation method the application implements. */
  readonly allocationMethodLabel = 'Equal split (whole shares)';

  /**
   * The ceiling the utilisation limit puts on the investment amount, or null when it imposes
   * none - either because it is 100% or because no balance is known to take a share of.
   */
  utilizationCeiling(): number | null {
    const pct = Number(this.form?.maxCapitalUtilizationPct ?? 100);
    if (!(pct > 0) || pct >= 100) return null;
    const base = this.buyingPower ?? this.balance;
    if (base === null || !(base > 0)) return null;
    return Math.round(base * pct / 100);
  }

  private applyForm(): void {
    const s = (this.mode === 'REAL' ? this.settings?.real : this.settings?.paper) || {};
    this.form = {
      armed: !!s.armed, autoBuy: !!s.autoBuy, autoSell: !!s.autoSell,
      investmentAmount: Number(s.investmentAmount ?? 100000),
      maxCapitalPerStock: Number(s.maxCapitalPerStock ?? 0),
      maxOpenPositions: Number(s.maxOpenPositions ?? 5),
      maxDailyTrades: Number(s.maxDailyTrades ?? 0),
      maxDailyLoss: Number(s.maxDailyLoss ?? 0),
      stopLossPct: Number(s.stopLossPct ?? 0),
      trailingStopPct: Number(s.trailingStopPct ?? 0),
      targetPct: Number(s.targetPct ?? 0),
      tradingStartTime: s.tradingStartTime || '09:15',
      tradingEndTime: s.tradingEndTime || '15:30',
      buyTime: s.buyTime || '15:25',
      sellTime: s.sellTime || '15:25',
      maxCapitalUtilizationPct: Number(s.maxCapitalUtilizationPct ?? 100)
    };
  }

  setMode(m: 'PAPER' | 'REAL'): void {
    if (this.mode === m) return;
    this.mode = m;
    this.saveMsg = '';
    this.applyForm();
    // Real and paper have different money behind them, so the balance and any sizing done
    // against the old one are both void.
    this.loadBalance();
    this.onInvestmentChanged();
  }

  alloc: any = null;
  allocLoading = false;

  /** The rupee sign, bound rather than inlined so the template stays free of HTML entities. */
  readonly RUPEE = '\u20B9';

  /**
   * The allocation legs indexed by symbol, so a row can find its own without scanning.
   *
   * <p>Rebuilt whenever an allocation arrives. A linear search per cell would be four scans of
   * the whole leg list per row, which on a few hundred rows is work for nothing.
   */
  private legIndex = new Map<string, any>();

  /** This symbol's allocation, or null when nothing has been sized yet. */
  legFor(symbol: string): any { return this.legIndex.get(symbol) || null; }

  /**
   * What the order actually costs: the capital deployed plus the charges it will pay.
   *
   * <p>Null when no share was bought, so the column shows a dash instead of a cost for an order
   * that is not happening.
   */
  legCost(symbol: string): number | null {
    const l = this.legIndex.get(symbol);
    if (!l || !l.quantity) return null;
    return (Number(l.deployed) || 0) + (Number(l.estimatedCharges) || 0);
  }

  /** The amount changed, so any sizing on screen is now against the wrong figure. */
  onInvestmentChanged(): void {
    this.alloc = null;
    this.legIndex.clear();
  }

  // ── The selected account's balance ────────────────────────────────────────
  //
  // Read from the account the Mode selector names, never assumed. For REAL that is the broker's
  // own figure; a balance that is not the user's money would be worse than no balance, so when
  // it cannot be read the field says so instead of showing a number.

  balance: number | null = null;
  balanceError = '';
  balanceStale = false;
  balanceAsOf = '';
  balanceLoading = false;

  /** Buying power, which is what an order may actually use - not the same as cash on hand. */
  buyingPower: number | null = null;

  /** What the broker says about the account, kept as reported rather than reshaped. */
  acctSummary: {
    label: string; brokerName: string; clientId: string;
    connected: boolean; usedMargin: number | null;
  } = { label: '', brokerName: '', clientId: '', connected: false, usedMargin: null };

  /**
   * The smallest order this screen will try to place.
   *
   * <p>A few rupees spread across five stocks buys nothing and still pays charges on each leg, so
   * an investment below this is rejected with a reason rather than silently allocating zero
   * quantity everywhere.
   */
  readonly MIN_ORDER_VALUE = 100;

  /** Capital not committed to the current allocation. */
  unallocated(): number {
    const invested = Number(this.form.investmentAmount) || 0;
    const deployed = Number(this.alloc?.deployedCapital) || 0;
    // Before an allocation has been run, nothing is deployed, so all of it is unallocated.
    return Math.max(0, Math.round((invested - deployed) * 100) / 100);
  }

  /**
   * Why the investment amount cannot be used, or null when it can.
   *
   * <p>Returned as one message rather than a set of booleans so the field, the banner and any
   * future server-side check all state the same reason in the same words.
   */
  investmentIssue(): string | null {
    const amount = Number(this.form.investmentAmount) || 0;
    if (amount <= 0) return null;          // nothing to allocate is not an error
    if (amount < this.MIN_ORDER_VALUE) {
      return 'An investment of ' + this.RUPEE + amount + ' is below the minimum order value of '
           + this.RUPEE + this.MIN_ORDER_VALUE + ', so no whole share could be bought.';
    }
    if (this.buyingPower !== null && amount > this.buyingPower) {
      return 'The investment amount exceeds the available buying power of '
           + this.RUPEE + this.buyingPower.toFixed(2)
           + (this.mode === 'REAL' ? ' reported by the broker.' : ' in the demo account.')
           + ' Lower the amount, or nothing can be placed.';
    }
    if (this.buyingPower === null && this.mode === 'REAL') {
      return 'The broker balance could not be read, so an investment amount cannot be validated '
           + 'against it. Nothing will be placed until the balance is available.';
    }
    return null;
  }

  loadBalance(): void {
    this.balance = null;
    this.buyingPower = null;
    this.balanceError = '';
    this.balanceStale = false;
    this.balanceAsOf = '';
    this.balanceLoading = true;
    this.acctSummary = { label: '', brokerName: '', clientId: '',
                         connected: false, usedMargin: null };
    const real = this.mode === 'REAL';
    this.api.get<any>(real ? '/trading/broker-funds' : '/trading/demo-account').subscribe({
      next: r => {
        const d = r?.data || {};
        this.balanceLoading = false;
        const num = (v: any) => (v === null || v === undefined || v === '') ? null : Number(v);

        this.balance = num(d.available_balance);
        // Buying power where the account reports it, otherwise available cash. Never cash plus
        // collateral, which would overstate what an equity delivery order can actually use.
        this.buyingPower = num(d.buying_power) ?? this.balance;
        this.balanceStale = !!d.stale;
        this.balanceAsOf = d.fetched_at || '';

        this.acctSummary = {
          label: d.label || (real ? 'Real Account' : 'Demo Account'),
          brokerName: d.broker_name || '',
          clientId: d.client_id || '',
          // A demo account is always usable; a real one is only usable once a broker is attached.
          connected: real ? !!d.connected : true,
          usedMargin: num(d.used_margin)
        };

        if (this.balance === null) {
          this.balanceError = d.message || 'The balance could not be read.';
        } else if (this.balanceStale) {
          this.balanceError = d.message
            || ('The balance could not be refreshed. Showing the reading as at '
                + this.balanceAsOf + '.');
        }
      },
      error: e => {
        this.balanceLoading = false;
        this.balance = null;
        this.buyingPower = null;
        this.balanceError = e?.error?.message
          || (real ? 'The broker balance could not be read, so no real order can be sized.'
                   : 'The demo balance could not be read.');
      }
    });
  }

  /**
   * Size the selection against fresh prices.
   *
   * <p>Server-side, and re-asked rather than computed here, because the quantity depends on a
   * live price: sizing from the price in the table would commit money against whatever that
   * cell held when the page last painted.
   */
  /** Rebuild the symbol index from whatever legs the last allocation returned. */
  private reindexLegs(): void {
    this.legIndex.clear();
    for (const l of (this.alloc?.legs || [])) {
      if (l && l.symbol) this.legIndex.set(String(l.symbol), l);
    }
  }

  allocate(): void {
    const symbols = this.actOn();
    if (!symbols.length || this.allocLoading) { this.onInvestmentChanged(); return; }
    // Refuse to size an order the account cannot fund. Letting the server answer would produce
    // quantities against money that is not there.
    const issue = this.investmentIssue();
    if (issue) {
      this.alloc = { success: false, legs: [], message: issue };
      this.reindexLegs();
      return;
    }
    this.allocLoading = true;
    this.api.post<any>('/trading/allocate/preview', {
      symbols,
      totalInvestment: Number(this.form.investmentAmount) || 0,
      productType: 'DELIVERY',     // held overnight, so delivery charges apply - not intraday
      exchange: 'NSE',
      // Which account to size against. Without it the server fell back to a fixed notional that
      // had gone negative on the live system, so every basket came back unaffordable.
      accountType: this.mode,
      strategyKey: this.selectedKey,
      mode: this.mode
    }).subscribe({
      next: r => {
        this.alloc = r?.data || null;
        this.reindexLegs();
        this.allocLoading = false;
      },
      error: e => {
        this.alloc = { success: false, legs: [],
                       message: e?.error?.message || 'Could not calculate the allocation.' };
        this.reindexLegs();
        this.allocLoading = false;
      }
    });
  }

  /** Filters that narrow the list on screen without changing what the strategy selected. */
  rowFilter: 'ALL' | 'GAINERS' | 'LOSERS' | 'OPEN' = 'ALL';
  rowSearch = '';

  rows(): any[] { return this.stocks?.rows || []; }

  /**
   * The rows after the status and symbol filters.
   *
   * <p>Kept separate from {@link rows} on purpose: the actions act on what is SHOWN, and the
   * count beside them states both numbers when a filter is hiding something. A filter that
   * silently changes what a Buy button does is worse than no filter.
   */
  visibleRows(): any[] {
    const q = (this.rowSearch || '').trim().toUpperCase();
    return this.rows().filter(r => {
      if (q && !String(r.symbol || '').toUpperCase().includes(q)) return false;
      const ret = Number(r.return_pct);
      switch (this.rowFilter) {
        case 'GAINERS': return isFinite(ret) && ret > 0;
        case 'LOSERS':  return isFinite(ret) && ret < 0;
        case 'OPEN':    return String(r.status || '').toUpperCase() === 'OPEN';
        default:        return true;
      }
    });
  }

  /** The chosen strategy's own entry, for the description under the filter. */
  selectedSource(): any {
    return this.sources.find(x => x.key === this.selectedKey) || null;
  }

  /** Built-ins and saved builds, grouped so the dropdown says which is which. */
  sourceGroups(): Array<{ label: string; items: any[] }> {
    const built = this.sources.filter(x => x.builtIn);
    const saved = this.sources.filter(x => !x.builtIn);
    const out: Array<{ label: string; items: any[] }> = [];
    if (built.length) out.push({ label: 'Built-in strategies', items: built });
    if (saved.length) out.push({ label: 'Built in Strategy Builder', items: saved });
    return out;
  }


  /** The stocks an action applies to: the ticked ones, or all of them when none is ticked. */
  actOn(): string[] {
    const shown = this.visibleRows().map(r => r.symbol).filter((x: any) => !!x);
    return this.picked.size ? shown.filter((s: string) => this.picked.has(s)) : shown;
  }

  /**
   * Any change to what would be bought invalidates the sizing.
   *
   * <p>The investment amount, the account and the selection all change how much of each stock is
   * bought, so quantities computed before the change describe an order nobody asked for.
   *
   * <p>Clearing {@code alloc} alone is not enough now that the per-row figures are read from an
   * index: the index would go on answering with the previous basket's quantities while the
   * summary above showed nothing, which is the worst of both.
   */
  private invalidateSizing(): void { this.onInvestmentChanged(); }

  toggle(sym: string): void {
    if (this.picked.has(sym)) this.picked.delete(sym); else this.picked.add(sym);
    this.invalidateSizing();
  }
  allPicked(): boolean {
    const all = this.visibleRows();
    return all.length > 0 && all.every(r => this.picked.has(r.symbol));
  }
  toggleAll(ev: any): void {
    const all = this.visibleRows();
    if (ev?.target?.checked) all.forEach(r => this.picked.add(r.symbol));
    else all.forEach(r => this.picked.delete(r.symbol));
    // Was missing entirely: selecting or clearing every row changed the basket and left the
    // previous quantities on screen.
    this.invalidateSizing();
  }

  save(): void {
    if (this.saving || !this.selectedKey) return;
    if (this.mode === 'REAL' && (this.form.autoBuy || this.form.autoSell) && this.form.armed) {
      const ok = confirm(
        `Arm automated REAL trading for "${this.selectedName()}"?\n\n` +
        (this.form.autoBuy ? '• Auto Buy will place real orders.\n' : '') +
        (this.form.autoSell ? '• Auto Sell will place real exit orders.\n' : '') +
        `\nThey fire only while REAL is the live mode, with this account's real-trading ` +
        `authorization and a connected broker.\n\nSave these settings?`);
      if (!ok) return;
    }
    this.saving = true;
    this.saveMsg = '';
    this.api.post<any>('/trading/auto/strategies/settings',
                       { key: this.selectedKey, mode: this.mode, ...this.form }).subscribe({
      next: r => {
        this.saving = false;
        this.saveOk = true;
        this.saveMsg = r?.data?.message || 'Saved.';
        this.loadSettings();
      },
      error: e => {
        this.saving = false;
        this.saveOk = false;
        // The server names every value it refused; pass that through rather than replacing it
        // with a generic failure.
        this.saveMsg = e?.error?.message || 'Could not save.';
      }
    });
  }

  volFmt(v: any): string {
    const n = Number(v) || 0;
    if (n >= 1e7) return (n / 1e7).toFixed(2) + ' Cr';
    if (n >= 1e5) return (n / 1e5).toFixed(2) + ' L';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + ' K';
    return n ? String(Math.round(n)) : '—';
  }
}
