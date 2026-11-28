import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ApiService } from '../../core/services/api.service';
import { environment } from '../../../environments/environment';

type JournalView = 'all' | 'open' | 'add' | 'import';

/**
 * Trade Journal — one record of every trade.
 *
 * Replaces two separate screens: the Trading page's "Trading Journal" tab, which read the
 * strategy-aware table, and this screen's old manual-entry form, which wrote to a different one.
 * Two journals meant "what did I make this month" had two answers.
 *
 * Charges are shown on every row because the application computes them now. Nothing did before,
 * so net P&L used to equal gross — and a delivery round trip costs roughly 0.30% of the position,
 * which is more than a one-session hold typically makes.
 */
@Component({
  selector: 'app-journal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host{display:block}
    .btn{border:1px solid #334865;border-radius:7px;padding:8px 13px;font-size:12px;font-weight:800;cursor:pointer;color:#e5eefb;background:#1b2940}
    .btn:disabled{opacity:.5;cursor:default}
    .active,.primary{background:#2563eb;border-color:#2563eb;color:#fff}
    .danger{color:#ff6b6b}.good{color:#5ff5cd}
    .input{background:#071122;border:1px solid #314761;border-radius:6px;color:#fff;padding:9px 10px;font-size:12px;width:100%}
    .card{background:#0d1829;border:1px solid #22344d;border-radius:10px;}
    th,td{padding:8px 10px;border-bottom:1px solid #22344d;text-align:left;font-size:11.5px;white-space:nowrap}
    th{color:#c8d8ef;background:#142034;font-size:10px;text-transform:uppercase;letter-spacing:.4px}
    tr:hover td{background:#111f33}
    .kpi{background:#0d1829;border:1px solid #22344d;border-radius:8px;padding:9px 13px;min-width:0}
    .kpi-l{font-size:9px;color:#8fb0d4;text-transform:uppercase;letter-spacing:.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
    .kpi-v{font-size:18px;font-weight:800;line-height:1.2}
    /* One line of KPIs that scrolls rather than wrapping into an uneven block. */
    .kpi-row{display:grid;grid-auto-flow:column;grid-auto-columns:minmax(128px,1fr);gap:8px;min-width:max-content}
    .kpi-wrap{overflow-x:auto;-webkit-overflow-scrolling:touch;padding-bottom:2px}
    /* Twenty-odd columns never fit a phone. Scroll sideways instead of crushing them. */
    .tbl-wrap{width:100%;overflow-x:auto;-webkit-overflow-scrolling:touch}
    .tbl-wrap table{width:100%;border-collapse:collapse;min-width:1580px}
    .pill{font-size:9px;font-weight:800;border-radius:999px;padding:2px 8px;white-space:nowrap}
    .grid-form{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:11px}
    /* Record a Trade: twelve fields in exactly two rows of six.
       A fixed six, not auto-fit, because auto-fit decides the column count from the available
       width and lands on five or seven depending on the window - which is what left the form as a
       ragged three-and-a-bit rows. minmax(0,1fr) lets the columns shrink to share the width
       instead of insisting on a minimum and wrapping. */
    .grid-form-6{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:9px}
    /* The remark is free text and needs room, so it takes the two cells left over
       on row two rather than a third row of its own. */
    .grid-form-6 .span2{grid-column:span 2}
    .grid-form-6 .input{padding:8px 8px;font-size:11.5px}
    .grid-form-6 label{font-size:10px}
    @media (max-width:1150px){
      /* Six columns of a 1150px window is ~150px before padding, and a date input stops being
         usable below that. Three rows of four is the next honest step down - still aligned,
         still readable. */
      .grid-form-6{grid-template-columns:repeat(4,minmax(0,1fr))}
      .grid-form-6 .span2{grid-column:span 4}
    }
    @media (max-width:760px){
      .grid-form-6{grid-template-columns:repeat(2,minmax(0,1fr))}
      .grid-form-6 .span2{grid-column:span 2}
    }
    label{color:#9eb8dd;font-size:10.5px;font-weight:800;display:block;margin-bottom:3px}
  `],
  template: `
<div style="min-height:100vh;background:#071122;color:#e5eefb;padding:20px;">

  <section style="background:linear-gradient(120deg,#102447,#0d2e37);border:1px solid #284466;
                  border-top:3px solid #16d6a4;border-radius:8px;padding:18px 22px;margin-bottom:14px;">
    <div style="color:#5ff5cd;font-size:10.5px;font-weight:800;text-transform:uppercase;letter-spacing:.08em;">
      Trade Journal
    </div>
    <h1 style="color:#fff;font-size:23px;font-weight:800;margin:5px 0 7px;">Every trade, one record</h1>
    <p style="color:#9eb8dd;font-size:12.5px;margin:0;line-height:1.6;max-width:900px;">
      Paper and real, manual and automated, including stop-loss and trailing-stop exits.
      Brokerage, STT, exchange and SEBI charges, stamp duty, GST and the depository fee are
      calculated at Dhan&rsquo;s published rates, so <strong>Net</strong> is what you keep &mdash;
      not the same number as Gross.
    </p>
  </section>

  <div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px;">
    <button class="btn" [class.active]="view()==='all'"  (click)="setView('all')">All Trades</button>
    <button class="btn" [class.active]="view()==='open'" (click)="setView('open')">
      Open Manual Positions @if (openCount) { ({{ openCount }}) }
    </button>
    <button class="btn" [class.active]="view()==='add'"  (click)="setView('add')">Record a Trade</button>
    <button class="btn" [class.active]="view()==='import'" (click)="setView('import')"
            title="Pull today's executed trades from Dhan and reconcile them against this journal.">
      Import from Broker
    </button>
    <span style="flex:1 1 auto;"></span>
    <button class="btn" (click)="exportCsv()">Export CSV</button>
  </div>

  <!-- ─────────────────────────────── ALL TRADES ─────────────────────────────── -->
  @if (view()==='all') {
    <div class="card" style="padding:12px;margin-bottom:12px;">
      <!-- The matrix over whatever is currently shown, and a warning when the headline
           figures mix real and simulated money. Real and paper totals must never be read as
           one number. -->
      @if (matrix && matrixKeys().length) {
        <div style="display:flex; gap:9px; flex-wrap:wrap; margin-bottom:10px;">
          @for (k of matrixKeys(); track k) {
            <div style="border:1px solid #233852; border-radius:7px; padding:6px 10px;
                        background:#0b1220; min-width:120px;">
              <div style="font-size:9px; color:#94a3b8; text-transform:uppercase;
                          letter-spacing:.5px;">{{ matrix[k].label }}</div>
              <div style="font-size:14px; font-weight:800; color:#e2e8f0;">
                {{ matrix[k].total }}
                <span style="font-size:9.5px; font-weight:600; color:#8fa3bd;">
                  @if (matrix[k].REAL) { {{ matrix[k].REAL }} real }
                  @if (matrix[k].REAL && matrix[k].PAPER) { &middot; }
                  @if (matrix[k].PAPER) { {{ matrix[k].PAPER }} paper }
                </span>
              </div>
            </div>
          }
        </div>
      }
      @if (mixesAccounts) {
        <div class="note note-warn" style="margin-bottom:10px;">
          These rows contain <strong>both real and simulated</strong> trades, so the totals below
          add two different kinds of money together. Filter by Account Type to read either on its
          own.
        </div>
      }

      <div class="grid-form">
        <div><label>From</label><input class="input" type="date" [(ngModel)]="f.from"></div>
        <div><label>To</label><input class="input" type="date" [(ngModel)]="f.to"></div>
        <div><label>Symbol</label><input class="input" [(ngModel)]="f.symbol" placeholder="Any"></div>
        <div><label>Strategy</label>
          <select class="input" [(ngModel)]="f.strategy">
            <option value="">All strategies</option>
            @for (s of strategies; track s) { <option [value]="s">{{ s }}</option> }
          </select></div>
        <!-- The classification matrix, filterable on both axes. "External Broker" is the
             option that was missing: a trade imported from the broker's own trade book is
             written with is_manual=1, so under a System/Manual choice alone it was
             indistinguishable from an entry the user typed here. -->
        <div><label>Account Type</label>
          <select class="input" [(ngModel)]="f.mode">
            <option value="">Real and Paper</option>
            <option value="REAL">Real Trade</option>
            <option value="PAPER">Paper Trade</option>
          </select></div>
        <div><label>Trade Type</label>
          <select class="input" [(ngModel)]="f.source">
            <option value="">All trade types</option>
            <option value="SYSTEM">System Trade</option>
            <option value="MANUAL">Manual Trade</option>
            <option value="EXTERNAL">External Broker Trade</option>
          </select></div>
        <div><label>Status</label>
          <select class="input" [(ngModel)]="f.status">
            <option value="">Any status</option>
            <option value="PLACED">Placed</option>
            <option value="PARTIAL">Partially filled</option>
            <option value="FILLED">Filled</option>
            <option value="OPEN">Open</option>
            <option value="CLOSED">Closed</option>
            <option value="REJECTED">Rejected</option>
          </select></div>
        <div style="display:flex;align-items:flex-end;gap:7px;">
          <button class="btn primary" (click)="load()" [disabled]="loading()">
            {{ loading() ? 'Loading...' : 'Apply' }}</button>
          <button class="btn" (click)="clearFilters()">Clear</button>
        </div>
      </div>
    </div>

    <!-- Totals over the FILTERED rows, so they always agree with the table below. -->
    <div class="kpi-wrap" style="margin-bottom:12px;">
      <div class="kpi-row">
        <div class="kpi"><div class="kpi-l">Trades</div>
          <div class="kpi-v" style="color:#e2e8f0;">{{ a.totalTrades || 0 }}</div></div>
        <div class="kpi"><div class="kpi-l">Closed</div>
          <div class="kpi-v" style="color:#e2e8f0;">{{ a.closedTrades || 0 }}</div></div>
        <div class="kpi"><div class="kpi-l">Open</div>
          <div class="kpi-v" style="color:#e2e8f0;">{{ a.openTrades || 0 }}</div></div>
        <div class="kpi"><div class="kpi-l">Win Rate</div>
          <div class="kpi-v" [style.color]="(a.winRate||0) >= 50 ? '#5ff5cd' : '#e2e8f0'">
            {{ a.winRate || 0 | number:'1.0-1' }}%</div></div>
        <div class="kpi"><div class="kpi-l">Gross P&amp;L</div>
          <div class="kpi-v" [class]="(a.grossPnl||0) >= 0 ? 'good' : 'danger'">
            {{ money(a.grossPnl) }}</div></div>
        <!-- Called out because it was invisible before: nothing computed charges, so net equalled
             gross and every strategy looked better than it was. -->
        <div class="kpi"><div class="kpi-l" title="Brokerage, STT, exchange and SEBI charges, stamp duty, GST and the depository fee.">Charges</div>
          <div class="kpi-v" style="color:#fbbf24;">{{ money(a.totalCharges) }}</div></div>
        <div class="kpi" style="border-color:#2b4a6f;"><div class="kpi-l" title="Realised only - closed trades.">Net P&amp;L</div>
          <div class="kpi-v" [class]="(a.netPnl||0) >= 0 ? 'good' : 'danger'">
            {{ money(a.netPnl) }}</div></div>
        <!-- Separate from realised on purpose: a paper gain on an open position is not money
             taken, and one combined figure would overstate what the account holds. -->
        <div class="kpi"><div class="kpi-l" title="Open positions marked to the current price, net of the exit charges still to pay.">Unrealised</div>
          <div class="kpi-v" [class]="(a.unrealizedPnl||0) >= 0 ? 'good' : 'danger'">
            {{ money(a.unrealizedPnl) }}</div>
          @if (a.unpricedOpenPositions > 0) {
            <div style="font-size:8.5px;color:#fbbf24;"
                 title="These open positions had no price available, so they are not in the figure above.">
              {{ a.unpricedOpenPositions }} unpriced</div>
          }</div>
        <div class="kpi"><div class="kpi-l">Avg Win</div>
          <div class="kpi-v good" style="font-size:15px;">{{ money(a.averageProfit) }}</div></div>
        <div class="kpi"><div class="kpi-l">Avg Loss</div>
          <div class="kpi-v danger" style="font-size:15px;">{{ money(a.averageLoss) }}</div></div>
        <div class="kpi"><div class="kpi-l">Profit Factor</div>
          <div class="kpi-v" style="color:#e2e8f0;font-size:15px;">
            {{ a.profitFactor == null ? 'no losses' : (a.profitFactor | number:'1.2-2') }}</div></div>
      </div>
    </div>

    <div class="card tbl-wrap">
      <table>
        <thead><tr>
          <th>Trade Ref</th><th>Symbol</th><th>Strategy</th>
          <th title="How the trade was placed, and whose money it used">Classification</th>
          <th>Side</th>
          <th>Status</th><th style="text-align:right">Qty</th><th style="text-align:right">Entry</th>
          <th style="text-align:right">Current</th><th style="text-align:right">Exit</th><th style="text-align:right">Stop</th>
          <th style="text-align:right">Target</th><th style="text-align:right">Capital</th>
          <th style="text-align:right">Charges</th><th style="text-align:right">Gross</th>
          <th style="text-align:right">Net</th><th style="text-align:right">Return</th>
          <th>Exit Reason</th><th>Signal</th><th>Placed</th><th>Executed</th><th>Remark</th>
        </tr></thead>
        <tbody>
          @for (r of rows; track r.source + r.id) {
            <tr>
              <td style="color:#8fb0d4;">
                {{ r.tradeRef }}
                <!-- The MANUAL pill was removed: it was driven by is_manual alone, so a
                     broker import carried it too. The Classification column states this
                     properly, including the External case the pill could not express. -->
                @if (r.legacy) { <span class="pill" style="background:#2a2335;color:#c4b5fd;margin-left:4px;"
                       title="From the older journal table. It has no strategy, mode or lifecycle timestamps - that is why those columns are blank, not a fault.">LEGACY</span> }
              </td>
              <td style="font-weight:800;color:#fff;">{{ r.symbol }}</td>
              <td>{{ r.strategyName || r.strategyKey || '—' }}</td>
              <!-- Both halves of the classification on one cell. Red is real money: the
                   distinction has to be visible at a glance, because every other figure on the
                   row means something different depending on it. -->
              <td>
                <span class="pill"
                      [style.background]="r.accountType === 'REAL' ? '#3b1116' : (r.accountType === 'PAPER' ? '#132237' : '#2a2335')"
                      [style.color]="r.accountType === 'REAL' ? '#fca5a5' : (r.accountType === 'PAPER' ? '#8fb0d4' : '#c4b5fd')"
                      [title]="r.classification || 'Not classified'">
                  {{ r.accountTypeLabel || r.tradingMode || 'Unclassified' }}
                </span>
                <span style="display:block; font-size:9.5px; margin-top:2px;"
                      [style.color]="r.tradeOrigin === 'EXTERNAL' ? '#fbbf24' : (r.tradeOrigin === 'SYSTEM' ? '#5ff5cd' : '#94a3b8')">
                  {{ r.tradeTypeLabel || "—" }}
                </span>
              </td>
              <td [style.color]="r.tradeType === 'SELL' ? '#fca5a5' : '#5ff5cd'">{{ r.tradeType }}</td>
              <td><span class="pill" [style.background]="statusBg(r.tradeStatus)" [style.color]="statusFg(r.tradeStatus)">
                {{ r.tradeStatus }}</span>
                @if (r.pendingQty > 0) {
                  <span style="color:#fde68a;font-size:9px;margin-left:4px;"
                        [title]="r.filledQty + ' filled, ' + r.pendingQty + ' still pending at the exchange'">
                    {{ r.filledQty }}/{{ r.quantity }}</span>
                }
              </td>
              <td style="text-align:right">{{ r.quantity }}</td>
              <td style="text-align:right">{{ r.buyPrice | number:'1.2-2' }}</td>
              <!-- Live mark for an open position. A dash means no price was found, which is a
                   different fact from a flat position and must not look the same. -->
              <td style="text-align:right;font-weight:700;"
                  [style.color]="r.priceStale ? '#fbbf24' : '#fff'"
                  [title]="r.priceAsOf ? (r.priceStale ? 'Close from ' + r.priceAsOf + ' - not a live price' : 'Live price') : ''">
                {{ r.currentPrice ? (r.currentPrice | number:'1.2-2') : '—' }}
                @if (r.priceStale) {
                  <div style="font-size:8.5px;color:#fbbf24;font-weight:600;">{{ r.priceAsOf }}</div>
                }</td>
              <td style="text-align:right">{{ r.sellPrice ? (r.sellPrice | number:'1.2-2') : '—' }}</td>
              <td style="text-align:right;color:#fca5a5;">{{ r.stopLoss ? (r.stopLoss | number:'1.2-2') : '—' }}</td>
              <td style="text-align:right;color:#5ff5cd;">{{ r.targetPrice ? (r.targetPrice | number:'1.2-2') : '—' }}</td>
              <td style="text-align:right">{{ money(r.capitalUtilized) }}</td>
              <td style="text-align:right;color:#fbbf24;" [title]="r.chargesBreakdown || ''">{{ money(r.charges) }}</td>
              <!-- For an open trade the stored gross/net are legitimately 0 - they are written
                   at exit - so the live mark is shown instead and flagged as unrealised. -->
              <td style="text-align:right" [class]="(r.marked ? r.unrealizedGross : r.grossPnl) >= 0 ? 'good' : 'danger'">
                {{ money(r.marked ? r.unrealizedGross : r.grossPnl) }}</td>
              <td style="text-align:right;font-weight:800;" [class]="(r.marked ? r.unrealizedNet : r.netPnl) >= 0 ? 'good' : 'danger'">
                {{ money(r.marked ? r.unrealizedNet : r.netPnl) }}
                @if (r.marked) { <span style="font-size:8.5px;color:#64748b;" title="Unrealised: marked to the current price, net of the exit charges still to pay.">unrl</span> }</td>
              <td style="text-align:right" [class]="(r.returnPct||0) >= 0 ? 'good' : 'danger'">
                {{ r.returnPct | number:'1.2-2' }}%</td>
              <td>{{ r.exitReason || '—' }}</td>
              <td style="color:#64748b;font-size:10px;">{{ shortTime(r.signalAt) }}</td>
              <td style="color:#64748b;font-size:10px;">{{ shortTime(r.orderPlacedAt) }}</td>
              <td style="color:#64748b;font-size:10px;">{{ shortTime(r.executedAt) }}</td>
              <td style="white-space:normal;min-width:180px;color:#9eb8dd;">{{ r.userRemarks || '—' }}</td>
            </tr>
          }
          @if (!rows.length && !loading()) {
            <tr><td colspan="22" style="text-align:center;color:#94a3b8;padding:26px;">
              No trades match these filters.
            </td></tr>
          }
        </tbody>
      </table>
    </div>
    @if (note) { <div style="color:#64748b;font-size:10px;margin-top:8px;line-height:1.6;">{{ note }}</div> }
  }

  <!-- ───────────────────────── OPEN MANUAL POSITIONS ───────────────────────── -->
  @if (view()==='open') {
    <div class="kpi-wrap" style="margin-bottom:12px;">
      <div class="kpi-row">
        <div class="kpi"><div class="kpi-l">Open Positions</div>
          <div class="kpi-v" style="color:#e2e8f0;">{{ openCount }}</div></div>
        <div class="kpi"><div class="kpi-l">Invested</div>
          <div class="kpi-v" style="color:#e2e8f0;">{{ money(openTotals.totalInvested) }}</div></div>
        <div class="kpi" style="border-color:#2b4a6f;">
          <div class="kpi-l" title="After the exit charges still to be paid.">Net Unrealised</div>
          <div class="kpi-v" [class]="(openTotals.totalNetUnrealized||0) >= 0 ? 'good' : 'danger'">
            {{ money(openTotals.totalNetUnrealized) }}</div>
          <!-- Said once, loudly, rather than left for the reader to infer from the per-row dates:
               a total built on stale closes is not today's P&L. -->
          @if (stalePriceCount()) {
            <div style="font-size:8.5px;color:#fbbf24;font-weight:700;"
                 title="These positions are valued at an older session's close because no newer price has been fetched. Re-authenticate Dhan and run a backfill to bring them up to date.">
              {{ stalePriceCount() }} of {{ openCount }} priced from an older session
            </div>
          }</div>
      </div>
    </div>

    <div class="card tbl-wrap">
      <table style="min-width:1200px;">
        <thead><tr>
          <th>Trade Ref</th><th>Symbol</th><th style="text-align:right">Qty</th>
          <th style="text-align:right">Entry</th><th style="text-align:right">Current</th>
          <th style="text-align:right">Invested</th><th style="text-align:right">Unrealised</th>
          <th style="text-align:right">Exit Charges</th><th style="text-align:right">Net Unrealised</th>
          <th style="text-align:right">%</th><th>Entered</th><th>Close</th>
        </tr></thead>
        <tbody>
          @for (p of openPositions; track p.id) {
            <tr>
              <td style="color:#8fb0d4;">{{ p.tradeRef }}</td>
              <td style="font-weight:800;color:#fff;">{{ p.symbol }}</td>
              <td style="text-align:right">{{ p.quantity }}</td>
              <td style="text-align:right">{{ p.buyPrice | number:'1.2-2' }}</td>
              <!-- Dash, not zero: "no live price" and "worth nothing" must not look alike. -->
              <td style="text-align:right;color:#fff;font-weight:700;">
                {{ p.currentPrice == null ? '—' : (p.currentPrice | number:'1.2-2') }}
                <!-- The session the price belongs to. Without it a four-day-old close reads as a
                     live quote, which is exactly how 208.05 got taken for SEIL's current price
                     while it was trading at 200.60. -->
                @if (p.priceStale) {
                  <div style="font-size:8.5px;color:#fbbf24;font-weight:700;"
                       [title]="'This is the close from ' + p.priceAsOf + ', not a live price. No newer data has been fetched for ' + p.symbol + '.'">
                    as of {{ p.priceAsOf }}
                  </div>
                } @else if (p.priceAsOf) {
                  <div style="font-size:8.5px;color:#5ff5cd;">live</div>
                }</td>
              <td style="text-align:right">{{ money(p.invested) }}</td>
              <td style="text-align:right" [class]="(p.unrealizedPnl||0) >= 0 ? 'good' : 'danger'">
                {{ p.unrealizedPnl == null ? '—' : money(p.unrealizedPnl) }}</td>
              <td style="text-align:right;color:#fbbf24;">
                {{ p.estimatedExitCharges == null ? '—' : money(p.estimatedExitCharges) }}</td>
              <td style="text-align:right;font-weight:800;" [class]="(p.netUnrealizedPnl||0) >= 0 ? 'good' : 'danger'">
                {{ p.netUnrealizedPnl == null ? '—' : money(p.netUnrealizedPnl) }}</td>
              <td style="text-align:right" [class]="(p.unrealizedPct||0) >= 0 ? 'good' : 'danger'">
                {{ p.unrealizedPct == null ? '—' : ((p.unrealizedPct | number:'1.2-2') + '%') }}</td>
              <td style="color:#64748b;font-size:10px;">{{ shortTime(p.entryDate) }}</td>
              <td>
                <div style="display:flex;gap:5px;align-items:center;">
                  <input class="input" style="width:88px;padding:5px 7px;" type="number"
                         [(ngModel)]="closePrice[p.id]" [placeholder]="p.currentPrice || 'Exit'">
                  <button class="btn primary" style="padding:5px 9px;font-size:10px;"
                          (click)="closePosition(p)" [disabled]="closing()">Close</button>
                </div>
              </td>
            </tr>
          }
          @if (!openPositions.length && !loading()) {
            <tr><td colspan="12" style="text-align:center;color:#94a3b8;padding:26px;">
              No open manual positions. Trades recorded without an exit price appear here.
            </td></tr>
          }
        </tbody>
      </table>
    </div>
  }

  <!-- ───────────────────────── BROKER IMPORT (spec section 9) ───────────────────────── -->
  @if (view()==='import') {
    <div class="card" style="padding:16px;margin-bottom:12px;">
      <h2 style="color:#fff;font-size:17px;font-weight:800;margin:0 0 4px;">Import from broker</h2>
      <p style="color:#9eb8dd;font-size:11.5px;margin:0 0 13px;line-height:1.6;max-width:900px;">
        Reads today&rsquo;s executed trades from Dhan and reconciles them against this journal.
        A trade placed in Dhan&rsquo;s own app never passes through TradeRakshak, so the journal
        has no idea it happened and every P&amp;L figure is short by it.
        Matching is on <strong>broker order ID</strong> &mdash; the only identifier both sides
        genuinely share; symbol and quantity collide constantly.
        <strong style="color:#fde68a;">Preview first: nothing is written until you press Import.</strong>
      </p>

      <div style="display:flex;gap:9px;flex-wrap:wrap;align-items:center;">
        <button class="btn primary" (click)="previewImport()" [disabled]="importing()">
          {{ importing() ? 'Reading...' : 'Preview today\\'s trades' }}</button>
        @if (importRows.length) {
          <button class="btn" (click)="importSelected()" [disabled]="importing() || !importableCount()">
            Import {{ importableCount() }} selected
          </button>
          <span style="font-size:10.5px;color:#64748b;">
            {{ importAlreadyKnown }} already in the journal &middot; {{ importRows.length }} found
          </span>
        }
      </div>

      @if (importMsg) {
        <div style="margin-top:12px;border-radius:7px;padding:9px 11px;font-size:11.5px;line-height:1.55;"
             [style.color]="importOk ? '#5ff5cd' : '#fca5a5'"
             [style.background]="importOk ? '#0b3b37' : '#3b1116'"
             [style.border]="'1px solid ' + (importOk ? '#16d6a4' : '#7f1d1d')">{{ importMsg }}</div>
      }
    </div>

    @if (importRows.length) {
      <div class="card tbl-wrap">
        <table style="min-width:1050px;">
          <thead><tr>
            <th style="width:30px;">
              <input type="checkbox" [checked]="allImportSelected()" (change)="toggleAllImport($event)"
                     style="cursor:pointer;" title="Select every trade not already in the journal">
            </th>
            <th>Broker Order ID</th><th>Symbol</th><th>Side</th>
            <th style="text-align:right">Qty</th><th style="text-align:right">Price</th>
            <th>Product</th><th style="text-align:right">Charges</th>
            <th>Traded At</th><th>Status</th>
          </tr></thead>
          <tbody>
            @for (t of importRows; track t.brokerOrderId + t.exchangeTradeId) {
              <tr>
                <td>
                  <!-- A trade already in the journal cannot be ticked: importing it again would
                       double-count a position, which is the one outcome worth making impossible
                       rather than merely warning about. -->
                  <input type="checkbox" [disabled]="t.alreadyInJournal"
                         [checked]="importPicked.has(t.brokerOrderId)"
                         (change)="toggleImport(t)" style="cursor:pointer;">
                </td>
                <td style="color:#8fb0d4;">{{ t.brokerOrderId || '—' }}</td>
                <td style="font-weight:800;color:#fff;">{{ t.symbol }}</td>
                <td [style.color]="t.side === 'SELL' ? '#fca5a5' : '#5ff5cd'">{{ t.side }}</td>
                <td style="text-align:right">{{ t.quantity }}</td>
                <td style="text-align:right">{{ t.price | number:'1.2-2' }}</td>
                <td>{{ t.productType }}</td>
                <!-- The trade book does not carry charges, so these are calculated at Dhan's
                     published rates rather than left at zero. -->
                <td style="text-align:right;color:#fbbf24;"
                    title="Calculated at Dhan's published rates - the broker's trade book does not include charges.">
                  {{ money(t.estimatedCharges) }}</td>
                <td style="color:#64748b;font-size:10px;">{{ shortTime(t.tradedAt) }}</td>
                <td>
                  <span class="pill"
                        [style.background]="t.alreadyInJournal ? '#0b3b37' : '#3a2608'"
                        [style.color]="t.alreadyInJournal ? '#5ff5cd' : '#fde68a'">
                    {{ t.alreadyInJournal ? 'IN JOURNAL' : 'NEW' }}
                  </span>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    } @else if (importPreviewed && !importing()) {
      <div class="card" style="padding:22px;text-align:center;color:#94a3b8;font-size:11.5px;">
        The broker reported no trades for today.
      </div>
    }
  }

  <!-- ─────────────────────────────── RECORD A TRADE ─────────────────────────── -->
  @if (view()==='add') {
    <div class="card" style="padding:18px;max-width:1180px;">
      <h2 style="color:#fff;font-size:17px;font-weight:800;margin:0 0 4px;">Record a trade</h2>
      <p style="color:#9eb8dd;font-size:11.5px;margin:0 0 15px;line-height:1.6;">
        Charges are calculated for you &mdash; brokerage, STT, exchange and SEBI charges, stamp
        duty, GST and the depository fee, at Dhan&rsquo;s published rates. Leave the exit price
        blank to track this as an open position.
        <strong style="color:#fde68a;">No broker order is placed.</strong> Recording that you
        bought something is not an instruction to buy it.
      </p>

      <!-- Two rows of six. Row 1 is what and when you entered; row 2 is size, exit and risk.
           The order is unchanged, so tabbing through still follows the order you would type in. -->
      <div class="grid-form-6">
        <!-- Type-ahead over the instrument master. Searches every NSE equity, not just the
             uploaded list: a trade can legitimately be recorded for a stock never uploaded, and
             limiting the search would stop the journal describing trades actually taken. -->
        <div style="position:relative;">
          <label>Symbol *</label>
          <input class="input" [(ngModel)]="form.symbol" autocomplete="off"
                 (ngModelChange)="onSymbolInput($event)"
                 (focus)="symbolFocused = true"
                 (blur)="onSymbolBlur()"
                 (keydown)="onSymbolKey($event)"
                 placeholder="Type to search">
          @if (symbolFocused && symbolSuggestions.length) {
            <div style="position:absolute; z-index:40; top:100%; left:0; right:0; margin-top:3px;
                        background:#0a1626; border:1px solid #314761; border-radius:6px;
                        max-height:230px; overflow-y:auto; box-shadow:0 8px 22px rgba(0,0,0,.5);">
              @for (sug of symbolSuggestions; track sug.symbol; let i = $index) {
                <!-- mousedown, not click: blur fires first on click and would close the list
                     before the selection landed. -->
                <div (mousedown)="pickSymbol(sug)"
                     [style.background]="i === symbolHighlight ? '#1d3a5f' : 'transparent'"
                     style="padding:6px 9px; cursor:pointer; border-bottom:1px solid #16263c;">
                  <div style="font-size:12px; font-weight:800; color:#fff;">{{ sug.symbol }}</div>
                  <div style="font-size:9.5px; color:#8fb0d4; white-space:nowrap;
                              overflow:hidden; text-overflow:ellipsis;">{{ sug.name }}</div>
                </div>
              }
            </div>
          }
          @if (symbolSearching) {
            <div style="position:absolute; right:8px; top:26px; font-size:9px; color:#64748b;">...</div>
          }
        </div>
        <div><label>Sector</label><input class="input" [(ngModel)]="form.sector" placeholder="Optional"></div>
        <div><label>Mode</label>
          <select class="input" [(ngModel)]="form.tradingMode">
            <option value="PAPER">Paper</option><option value="REAL">Real</option>
          </select></div>
        <!-- The explanation moves to the label's tooltip: in a 160px column the full option text
             truncated to "Delivery (held overn...", which told the reader less than nothing. The
             distinction still matters - the two charge schedules differ by about a third of the
             total - so it is kept on hover rather than dropped. -->
        <div><label title="Delivery is held overnight and settles: zero brokerage, but STT on both legs and a depository fee. Intraday is squared off the same day: brokerage applies, STT on the sell leg only, no DP fee. A round trip differs by roughly a third between them.">Product</label>
          <select class="input" [(ngModel)]="form.productType" (ngModelChange)="previewCharges()">
            <!-- Delivery first: it is the correct default for anything held overnight. -->
            <option value="DELIVERY">Delivery</option>
            <option value="INTRADAY">Intraday</option>
          </select></div>
        <div><label>Entry Date</label><input class="input" type="date" [(ngModel)]="form.entryDate"></div>
        <div><label>Entry Price *</label><input class="input" type="number" [(ngModel)]="form.buyPrice"
                 (ngModelChange)="previewCharges()" placeholder="0.00"></div>
        <div><label>Quantity *</label><input class="input" type="number" [(ngModel)]="form.quantity"
                 (ngModelChange)="previewCharges()" placeholder="0"></div>
        <!-- Short placeholder for a narrow column; the full rule is on the label. -->
        <div><label title="Leave blank to track this as an open position - it then appears under Open Manual Positions and can be closed later.">Exit Price</label>
          <input class="input" type="number" [(ngModel)]="form.sellPrice"
                 (ngModelChange)="previewCharges()" placeholder="blank = open"></div>
        <div><label>Exit Date</label><input class="input" type="date" [(ngModel)]="form.exitDate"></div>
        <div><label>Stop Loss</label><input class="input" type="number" [(ngModel)]="form.stopLoss" placeholder="Optional"></div>
        <div><label>Target</label><input class="input" type="number" [(ngModel)]="form.targetPrice" placeholder="Optional"></div>
        <div><label>Trailing Stop</label><input class="input" type="number" [(ngModel)]="form.trailingStopLoss" placeholder="Optional"></div>
        <div class="span2"><label>Entry Remark * &mdash; why?</label>
          <input class="input" [(ngModel)]="form.entryRemark"
                 title="A journal that does not say why a trade was taken cannot be reviewed later."
                 placeholder="Why this trade was taken"></div>
      </div>



      @if (preview) {
        <div style="margin-top:14px;background:#0a1626;border:1px solid #22344d;border-radius:8px;padding:12px;">
          <div style="font-size:10px;color:#8fb0d4;text-transform:uppercase;letter-spacing:.5px;margin-bottom:8px;">
            Calculated charges
          </div>
          <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(108px,1fr));gap:9px;font-size:11px;">
            <div><div style="color:#64748b;">Brokerage</div><div style="font-weight:800;">{{ money(preview.brokerage) }}</div></div>
            <div><div style="color:#64748b;">STT</div><div style="font-weight:800;">{{ money(preview.stt) }}</div></div>
            <div><div style="color:#64748b;">Exchange</div><div style="font-weight:800;">{{ money(preview.transactionCharges) }}</div></div>
            <div><div style="color:#64748b;">SEBI + IPFT</div><div style="font-weight:800;">{{ money((preview.sebiTurnoverFee||0)+(preview.ipft||0)) }}</div></div>
            <div><div style="color:#64748b;">Stamp Duty</div><div style="font-weight:800;">{{ money(preview.stampDuty) }}</div></div>
            <div><div style="color:#64748b;">GST</div><div style="font-weight:800;">{{ money(preview.gst) }}</div></div>
            <div><div style="color:#64748b;">DP Charge</div><div style="font-weight:800;">{{ money(preview.dpCharges) }}</div></div>
            <div style="border-left:2px solid #2b4a6f;padding-left:9px;">
              <div style="color:#8fb0d4;">Total</div>
              <div style="font-weight:800;color:#fbbf24;font-size:14px;">{{ money(preview.total) }}
                <span style="font-size:10px;color:#64748b;">({{ preview.totalPct | number:'1.2-2' }}%)</span></div></div>
          </div>
          @if (form.sellPrice) {
            <div style="margin-top:9px;font-size:11px;color:#9eb8dd;">
              Gross {{ money(grossPreview()) }} &minus; charges {{ money(preview.total) }} =
              <strong [class]="netPreview() >= 0 ? 'good' : 'danger'">net {{ money(netPreview()) }}</strong>
            </div>
          } @else {
            <div style="margin-top:9px;font-size:11px;color:#64748b;">
              Entry leg only. The exit charge is added when you close the position.
            </div>
          }
        </div>
      }

      @if (msg) {
        <div style="margin-top:12px;border-radius:7px;padding:9px 11px;font-size:11.5px;line-height:1.55;"
             [style.color]="msgOk ? '#5ff5cd' : '#fca5a5'"
             [style.background]="msgOk ? '#0b3b37' : '#3b1116'"
             [style.border]="'1px solid ' + (msgOk ? '#16d6a4' : '#7f1d1d')">{{ msg }}</div>
      }

      <div style="display:flex;gap:9px;margin-top:15px;">
        <button class="btn primary" (click)="save()" [disabled]="saving()">
          {{ saving() ? 'Recording...' : 'Record Trade' }}</button>
        <button class="btn" (click)="resetForm()">Clear</button>
      </div>
    </div>
  }
</div>
  `
})
export class JournalComponent implements OnInit {

  view = signal<JournalView>('all');
  loading = signal(false);
  saving = signal(false);
  closing = signal(false);

  rows: any[] = [];
  a: any = {};
  strategies: string[] = [];
  note = '';

  openPositions: any[] = [];
  openTotals: any = {};
  openCount = 0;
  closePrice: Record<number, number | null> = {};

  f = { from: '', to: '', symbol: '', strategy: '', mode: '', source: '', status: '' };

  /** The origin x account breakdown of the rows on screen, as the server counted them. */
  matrix: any = null;
  /** True when the rows shown mix real and simulated money, so the totals are a mixture. */
  mixesAccounts = false;

  matrixKeys(): string[] {
    return this.matrix ? Object.keys(this.matrix) : [];
  }
  form = this.emptyForm();
  preview: any = null;
  msg = '';
  msgOk = false;

  constructor(private api: ApiService, private http: HttpClient) {}

  ngOnInit(): void {
    this.load();
    this.loadOpen();
  }

  setView(v: JournalView): void {
    this.view.set(v);
    if (v === 'open') this.loadOpen();
    if (v === 'all') this.load();
  }

  private params(): any {
    const p: any = {};
    for (const [k, v] of Object.entries(this.f)) if (v) p[k] = v;
    return p;
  }

  load(): void {
    this.loading.set(true);
    this.api.get<any>('/journal/v2', this.params()).subscribe({
      next: r => {
        const d = r?.data || {};
        this.rows = d.rows || [];
        this.a = d.analytics || {};
        this.matrix = d.classificationMatrix || null;
        this.mixesAccounts = !!d.mixesAccounts;
        this.strategies = d.strategies || [];
        this.note = d.note || '';
        this.loading.set(false);
      },
      error: () => { this.rows = []; this.a = {}; this.loading.set(false); }
    });
  }

  loadOpen(): void {
    this.api.get<any>('/journal/v2/open').subscribe({
      next: r => {
        const d = r?.data || {};
        this.openPositions = d.positions || [];
        this.openTotals = d;
        this.openCount = d.count || 0;
      },
      error: () => { this.openPositions = []; this.openCount = 0; }
    });
  }

  /** How many open positions are valued at an older session's close rather than a live price. */
  stalePriceCount(): number {
    return this.openPositions.filter(p => p.priceStale).length;
  }

  // ── Broker trade import (spec section 9) ────────────────────────────────

  importRows: any[] = [];
  importPicked = new Set<string>();
  importing = signal(false);
  importPreviewed = false;
  importAlreadyKnown = 0;
  importMsg = '';
  importOk = false;

  /** Reads the broker's trade book without writing anything. */
  previewImport(): void {
    if (this.importing()) return;
    this.importing.set(true);
    this.importMsg = '';
    this.api.post<any>('/trading/broker-trades/import?dryRun=true', {}).subscribe({
      next: r => {
        const d = r?.data || {};
        this.importRows = d.trades || [];
        this.importAlreadyKnown = d.alreadyInJournal || 0;
        this.importPreviewed = true;
        // Pre-tick everything importable. The common case is "bring in what is missing", and
        // leaving all boxes empty makes the user click five times to say so.
        this.importPicked = new Set(
          this.importRows.filter(t => !t.alreadyInJournal && t.brokerOrderId)
                         .map(t => t.brokerOrderId));
        this.importOk = true;
        this.importMsg = d.message || '';
        this.importing.set(false);
      },
      error: e => {
        this.importRows = [];
        this.importPreviewed = true;
        this.importOk = false;
        this.importMsg = e?.error?.message || 'Could not read the broker trade book.';
        this.importing.set(false);
      }
    });
  }

  importableCount(): number { return this.importPicked.size; }

  allImportSelected(): boolean {
    const avail = this.importRows.filter(t => !t.alreadyInJournal && t.brokerOrderId);
    return avail.length > 0 && avail.every(t => this.importPicked.has(t.brokerOrderId));
  }

  toggleImport(t: any): void {
    if (t.alreadyInJournal || !t.brokerOrderId) return;
    if (this.importPicked.has(t.brokerOrderId)) this.importPicked.delete(t.brokerOrderId);
    else this.importPicked.add(t.brokerOrderId);
  }

  toggleAllImport(ev: any): void {
    const avail = this.importRows.filter(t => !t.alreadyInJournal && t.brokerOrderId);
    if (ev?.target?.checked) avail.forEach(t => this.importPicked.add(t.brokerOrderId));
    else avail.forEach(t => this.importPicked.delete(t.brokerOrderId));
  }

  /**
   * Writes the ticked trades into the journal.
   *
   * The confirmation names the count because this is the one action here that changes the
   * journal, and an imported trade is indistinguishable from a real one afterwards except by its
   * broker_import tag.
   */
  importSelected(): void {
    const ids = Array.from(this.importPicked);
    if (!ids.length) { alert('Nothing ticked to import.'); return; }
    if (!confirm(`Import ${ids.length} broker trade(s) into the journal?\n\n` +
                 `They are recorded as REAL trades, tagged as broker-imported, with charges ` +
                 `calculated at Dhan's published rates. No broker order is placed.`)) return;

    this.importing.set(true);
    this.api.post<any>('/trading/broker-trades/import?dryRun=false', { orderIds: ids }).subscribe({
      next: r => {
        const d = r?.data || {};
        this.importOk = true;
        this.importMsg = d.message || 'Imported.';
        this.importing.set(false);
        // Re-preview so the rows flip to IN JOURNAL rather than still offering themselves.
        this.previewImport();
        this.load();
        this.loadOpen();
      },
      error: e => {
        this.importOk = false;
        this.importMsg = e?.error?.message || 'Import failed.';
        this.importing.set(false);
      }
    });
  }

  clearFilters(): void {
    this.f = { from: '', to: '', symbol: '', strategy: '', mode: '', source: '', status: '' };
    this.load();
  }

  /**
   * Charges from the server, not recomputed here.
   *
   * The rates live in Application Settings and the arithmetic has edge cases — a brokerage cap
   * that is a cap and not a flat fee, GST on services but not on taxes, per-rupee rounding on STT
   * and stamp duty. A second implementation in the browser would drift from the one that actually
   * gets stored.
   */
  // ── Symbol type-ahead ───────────────────────────────────────────────────
  symbolSuggestions: Array<{ symbol: string; name: string }> = [];
  symbolFocused = false;
  symbolSearching = false;
  symbolHighlight = -1;
  private symbolTimer: any = null;

  /**
   * Debounced, because this fires on every keystroke.
   *
   * 220ms is long enough that typing "RELIANCE" costs one request rather than eight, and short
   * enough that the list appears while you are still looking at the box. The in-flight request is
   * not cancelled - an older response can land after a newer one, so each response is checked
   * against the text now in the field before it is shown.
   */
  onSymbolInput(value: string): void {
    this.form.symbol = (value || '').toUpperCase();
    this.previewCharges();
    this.symbolHighlight = -1;
    const q = this.form.symbol.trim();
    if (this.symbolTimer) clearTimeout(this.symbolTimer);
    if (q.length < 1) { this.symbolSuggestions = []; return; }
    this.symbolSearching = true;
    this.symbolTimer = setTimeout(() => {
      this.api.get<any>('/journal/v2/symbols', { q, limit: '12' }).subscribe({
        next: r => {
          // Stale guard: discard a response for text the user has already moved past.
          if (this.form.symbol.trim() !== q) return;
          this.symbolSuggestions = r?.data || [];
          this.symbolSearching = false;
        },
        error: () => { this.symbolSuggestions = []; this.symbolSearching = false; }
      });
    }, 220);
  }

  pickSymbol(sug: { symbol: string; name: string }): void {
    this.form.symbol = sug.symbol;
    this.symbolSuggestions = [];
    this.symbolFocused = false;
    this.previewCharges();
  }

  /** Arrow keys and Enter, so the list is usable without reaching for the mouse. */
  onSymbolKey(ev: KeyboardEvent): void {
    if (!this.symbolSuggestions.length) return;
    if (ev.key === 'ArrowDown') {
      ev.preventDefault();
      this.symbolHighlight = Math.min(this.symbolHighlight + 1, this.symbolSuggestions.length - 1);
    } else if (ev.key === 'ArrowUp') {
      ev.preventDefault();
      this.symbolHighlight = Math.max(this.symbolHighlight - 1, 0);
    } else if (ev.key === 'Enter' && this.symbolHighlight >= 0) {
      ev.preventDefault();
      this.pickSymbol(this.symbolSuggestions[this.symbolHighlight]);
    } else if (ev.key === 'Escape') {
      this.symbolSuggestions = [];
    }
  }

  /**
   * Closing is deferred by a tick.
   *
   * Blur fires before the list item's own handler, so closing immediately would remove the item
   * under the pointer before the selection could register. The item uses mousedown for the same
   * reason; this delay covers the keyboard path.
   */
  onSymbolBlur(): void {
    setTimeout(() => { this.symbolFocused = false; }, 150);
  }

  previewCharges(): void {
    const qty = this.num(this.form.quantity);
    const buy = this.num(this.form.buyPrice);
    if (qty <= 0 || buy <= 0) { this.preview = null; return; }
    this.api.post<any>('/trading/charges/preview', {
      productType: this.form.productType,
      exchange: 'NSE',
      quantity: qty,
      buyPrice: buy,
      sellPrice: this.num(this.form.sellPrice),
      side: 'BUY'
    }).subscribe({
      next: r => {
        const d = r?.data || {};
        this.preview = d.roundTrip || d.leg || null;
      },
      error: () => { this.preview = null; }
    });
  }

  grossPreview(): number {
    const q = this.num(this.form.quantity);
    return Math.round((this.num(this.form.sellPrice) - this.num(this.form.buyPrice)) * q * 100) / 100;
  }

  netPreview(): number {
    return Math.round((this.grossPreview() - (this.preview?.total || 0)) * 100) / 100;
  }

  save(): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.msg = '';
    this.api.post<any>('/journal/v2/manual', {
      ...this.form,
      quantity: this.num(this.form.quantity),
      buyPrice: this.num(this.form.buyPrice),
      sellPrice: this.num(this.form.sellPrice),
      stopLoss: this.num(this.form.stopLoss),
      targetPrice: this.num(this.form.targetPrice),
      trailingStopLoss: this.num(this.form.trailingStopLoss)
    }).subscribe({
      next: r => {
        this.saving.set(false);
        this.msgOk = true;
        this.msg = r?.data?.message || 'Recorded.';
        this.resetForm();
        this.load();
        this.loadOpen();
      },
      error: e => {
        this.saving.set(false);
        this.msgOk = false;
        this.msg = e?.error?.message || 'Could not record the trade.';
      }
    });
  }

  closePosition(p: any): void {
    const price = this.closePrice[p.id] ?? p.currentPrice;
    if (!price || price <= 0) { alert('Enter an exit price above zero.'); return; }
    if (!confirm(`Close ${p.symbol}: ${p.quantity} at ${price}?`)) return;
    this.closing.set(true);
    this.api.post<any>(`/journal/v2/${p.id}/close`, { exitPrice: price }).subscribe({
      next: r => {
        this.closing.set(false);
        alert(r?.data?.message || 'Closed.');
        this.loadOpen();
        this.load();
      },
      error: e => {
        this.closing.set(false);
        alert(e?.error?.message || 'Could not close the position.');
      }
    });
  }

  exportCsv(): void {
    const q = new URLSearchParams(this.params()).toString();
    this.http.get(`${environment.apiUrl}/journal/v2/export${q ? '?' + q : ''}`,
                  { responseType: 'blob', observe: 'response' }).subscribe({
      next: res => {
        const url = window.URL.createObjectURL(res.body!);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'TradeRakshak_Trade_Journal.csv';
        document.body.appendChild(a);
        a.click();
        window.URL.revokeObjectURL(url);
        document.body.removeChild(a);
      },
      error: () => alert('Export failed.')
    });
  }

  resetForm(): void { this.form = this.emptyForm(); this.preview = null; }

  private emptyForm() {
    return {
      symbol: '', sector: '', tradingMode: 'PAPER', productType: 'DELIVERY',
      entryDate: new Date().toISOString().slice(0, 10), buyPrice: null as any,
      quantity: null as any, sellPrice: null as any, exitDate: '',
      stopLoss: null as any, targetPrice: null as any, trailingStopLoss: null as any,
      entryRemark: '', exitReason: ''
    };
  }

  statusBg(s: string): string {
    switch ((s || '').toUpperCase()) {
      case 'FILLED': case 'CLOSED': return '#0b3b37';
      case 'PARTIAL': return '#3a2608';
      case 'REJECTED': case 'CANCELLED': case 'EXPIRED': return '#3b1116';
      default: return '#132237';
    }
  }
  statusFg(s: string): string {
    switch ((s || '').toUpperCase()) {
      case 'FILLED': case 'CLOSED': return '#5ff5cd';
      case 'PARTIAL': return '#fde68a';
      case 'REJECTED': case 'CANCELLED': case 'EXPIRED': return '#fca5a5';
      default: return '#8fb0d4';
    }
  }

  /** Rupees, with a dash for nothing — zero and "not applicable" read differently. */
  money(v: any): string {
    const n = Number(v);
    if (v == null || isNaN(n)) return '—';
    return '₹' + n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  shortTime(v: any): string {
    const s = String(v || '');
    if (!s || s === 'null') return '—';
    return s.length > 16 ? s.slice(0, 16) : s;
  }

  private num(v: any): number { const n = Number(v); return isNaN(n) ? 0 : n; }
}
