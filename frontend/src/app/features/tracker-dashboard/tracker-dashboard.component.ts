import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { LivePriceService } from '../../core/services/live-price.service';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-tracker-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host { display:block; font-family:'Inter',sans-serif; }
    .card { background:#1e2a3b; border:1px solid #2d3f55; border-radius:8px; }
    .tbl-hdr { background:#1a3a5c; color:#fff; font-size:11px; font-weight:600; padding:8px 10px; text-align:left; border-bottom:1px solid #2d3f55; }
    .tbl-row { border-bottom:1px solid #1e2a3b; transition:background 0.1s; }
    .tbl-row:hover { background:#1a2535; }
    .tbl-cell { font-size:11px; padding:7px 10px; color:#cbd5e1; }
    .tbl-cell-num { font-size:11px; padding:7px 10px; color:#cbd5e1; text-align:right; font-family:monospace; }
    .pos { color:#22c55e; font-weight:600; }
    .neg { color:#ef4444; font-weight:600; }
    .badge-green { background:#14532d; color:#4ade80; border:1px solid #16a34a; border-radius:4px; padding:2px 7px; font-size:10px; font-weight:600; }
    .badge-amber { background:#3a2608; color:#fde68a; border:1px solid #d97706; border-radius:4px; padding:2px 7px; font-size:10px; font-weight:600; }
    .badge-red { background:#450a0a; color:#fca5a5; border:1px solid #dc2626; border-radius:4px; padding:2px 7px; font-size:10px; font-weight:600; }
    .btn-blue { background:#1d6adb; color:#fff; border:none; border-radius:6px; padding:8px 16px; font-size:12px; font-weight:600; cursor:pointer; }
    .btn-blue:hover { background:#1a5fc7; }
    .btn-green { background:#16a34a; color:#fff; border:none; border-radius:6px; padding:6px 12px; font-size:11px; font-weight:600; cursor:pointer; }
    .btn-green:hover { background:#15803d; }
    .btn-red { background:#dc2626; color:#fff; border:none; border-radius:6px; padding:6px 12px; font-size:11px; font-weight:600; cursor:pointer; }
    .btn-red:hover { background:#b91c1c; }
    .btn-outline { background:transparent; color:#60a5fa; border:1px solid #2563eb; border-radius:6px; padding:5px 12px; font-size:11px; cursor:pointer; }
    .btn-outline:hover { background:#1e3a5f; }
    .section-title { color:#fff; font-size:13px; font-weight:700; }
    .metric-box { background:#0f172a; border-radius:6px; padding:10px 16px; text-align:center; }
    .metric-val { font-size:22px; font-weight:800; line-height:1.1; }
    .metric-lbl { font-size:10px; color:#94a3b8; margin-top:2px; }
    .select-sm { background:#1e2a3b; border:1px solid #2d3f55; border-radius:5px; color:#94a3b8; font-size:11px; padding:3px 8px; cursor:pointer; }
    .spin { width:14px; height:14px; border:2px solid rgba(255,255,255,.3); border-top-color:#fff; border-radius:50%; animation:spin .7s linear infinite; display:inline-block; }
    @keyframes spin { to { transform:rotate(360deg); } }
    .bar-chart-bar { border-radius:3px 3px 0 0; min-height:2px; width:28px; }
    .toast { position:fixed; top:20px; right:20px; padding:10px 18px; border-radius:8px; font-size:12px; font-weight:600; z-index:9999; animation:slideIn .3s ease; }
    .toast-success { background:#14532d; color:#4ade80; border:1px solid #16a34a; }
    .toast-error { background:#450a0a; color:#fca5a5; border:1px solid #dc2626; }
    @keyframes slideIn { from { transform:translateX(100%); opacity:0; } to { transform:translateX(0); opacity:1; } }
  `],
  template: `
<div style="background:#0d1829; min-height:100vh; padding:16px 20px;">
  <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:16px; flex-wrap:wrap; gap:10px;">
    <div>
      <span style="color:#fff; font-size:18px; font-weight:700;">Stock Tracker Dashboard</span>
      <div style="font-size:11px; color:#94a3b8; margin-top:2px;">Top 10 ranking, trends, and market performance overview</div>
    </div>
    <div style="display:flex; gap:8px;">
      <button class="btn-blue" (click)="runRanking()" [disabled]="ranking()">
        @if (ranking()) { <span class="spin"></span> Running... } @else { Run Ranking Now }
      </button>
      <button class="btn-outline" (click)="loadAll()">Refresh</button>
    </div>
  </div>

  <!-- Stats Strip -->
  <div style="display:grid; grid-template-columns:repeat(6,1fr); gap:8px; margin-bottom:14px;">
    <div class="card" style="padding:12px 14px;">
      <div style="font-size:10px; color:#94a3b8; margin-bottom:4px;">Total Stocks</div>
      <div style="font-size:26px; font-weight:800; color:#fff;">{{ dash.total | number }}</div>
    </div>
    <div class="card" style="padding:12px 14px;">
      <div style="font-size:10px; color:#94a3b8; margin-bottom:4px;">Eligible (0-8%)</div>
      <div style="font-size:20px; font-weight:800; color:#60a5fa;">{{ dash.eligible | number }}</div>
    </div>
    <div class="card" style="padding:12px 14px;">
      <div style="font-size:10px; color:#94a3b8; margin-bottom:4px;">Current Top 10 Avg Return</div>
      <div style="font-size:22px; font-weight:800;" [class]="(rd?.curr_avg_return||0) >= 0 ? 'pos' : 'neg'">
        {{ rd?.curr_avg_return != null ? (rd.curr_avg_return | number:'1.2-2') + '%' : '-' }}
      </div>
    </div>
    <div class="card" style="padding:12px 14px;">
      <div style="font-size:10px; color:#94a3b8; margin-bottom:4px;">Previous Top 10 Avg Return</div>
      <div style="font-size:22px; font-weight:800;" [class]="prevBatchAvgReturn >= 0 ? 'pos' : 'neg'">
        {{ prevActiveBatch?.active ? (prevBatchAvgReturn | number:'1.2-2') + '%' : (rd?.prev_avg_return != null ? (rd.prev_avg_return | number:'1.2-2') + '%' : '-') }}
      </div>
    </div>
    <div class="card" style="padding:12px 14px;">
      <div style="font-size:10px; color:#94a3b8; margin-bottom:4px;">Last Refresh</div>
      <div style="font-size:13px; font-weight:700; color:#fff;">{{ dash.last_refreshed ? (dash.last_refreshed | slice:0:16) : '-' }}</div>
    </div>
    <div class="card" style="padding:12px 14px;">
      <div style="font-size:10px; color:#94a3b8; margin-bottom:4px;">Market Status</div>
      <div style="display:flex; align-items:center; gap:6px; margin-top:4px;">
        <span style="width:8px; height:8px; border-radius:50%; display:inline-block;"
          [style.background]="dash.market_status==='OPEN' ? '#22c55e' : '#ef4444'"></span>
        <span style="font-size:14px; font-weight:700; color:#fff;">{{ dash.market_status === 'OPEN' ? 'LIVE' : 'CLOSED' }}</span>
      </div>
    </div>
  </div>

  <!-- Ranking Quality Warning -->
  @if (rd?.ranking_warning) {
    <div class="card" style="padding:10px 14px; margin-bottom:14px;" [style.border-color]="rd?.partial_ranking ? '#d97706' : '#16a34a'">
      <div style="display:flex; align-items:flex-start; gap:12px;">
        <span [class]="rd?.partial_ranking ? 'badge-amber' : 'badge-green'">
          {{ rd?.partial_ranking ? 'Partial Ranking' : 'Full Ranking' }}
        </span>
        <div>
          <div style="color:#cbd5e1; font-size:12px;">{{ rd?.ranking_warning }}</div>
          @if (rd?.skipped_components?.length) {
            <div style="margin-top:6px;">
              @for (skip of rd.skipped_components; track skip.key) {
                <div style="color:#fbbf24; font-size:11px; margin-top:3px;">
                  {{ skip.label }}: {{ skip.reason }} ({{ skip.affected_stocks }} stocks affected)
                </div>
              }
            </div>
          }
          @if (rd?.considered_components?.length) {
            <div style="margin-top:6px; color:#94a3b8; font-size:10px;">
              Considered: {{ rd.considered_components.join(', ') }}
            </div>
          }
        </div>
      </div>
    </div>
  }

  <div style="display:grid; grid-template-columns:1fr 1fr; gap:14px; margin-bottom:14px;">
    <!-- Current Top 10 -->
    <div class="card" style="overflow:hidden;">
      <div style="background:#1a3a5c; padding:10px 12px; display:flex; align-items:center; justify-content:space-between;">
        <span class="section-title">Today top 10 Ranking Stocks</span>
        <div style="display:flex; gap:12px; font-size:11px;">
          <span [class]="(rd?.curr_avg_return||0) >= 0 ? 'pos' : 'neg'">Avg: {{ rd?.curr_avg_return | number:'1.2-2' }}%</span>
          <span style="color:#94a3b8;">Win: {{ rd?.curr_win_ratio | number:'1.0-0' }}%</span>
        </div>
      </div>
      <div style="overflow-x:auto;">
        <table style="width:100%; border-collapse:collapse; min-width:700px;">
          <thead><tr>
            <th class="tbl-hdr">#</th><th class="tbl-hdr">Symbol</th><th class="tbl-hdr">Sector</th>
            <th class="tbl-hdr" style="text-align:right;">Score</th>
            <th class="tbl-hdr" style="text-align:right;">Base</th>
            <th class="tbl-hdr" style="text-align:right;">CMP</th>
            <th class="tbl-hdr" style="text-align:right;">Return %</th>
            <th class="tbl-hdr">Signal</th>
          </tr></thead>
          <tbody>
            @for (s of (rd?.current_top10 || []); track s.symbol; let i = $index) {
              <tr class="tbl-row">
                <td class="tbl-cell" style="font-weight:700; color:#60a5fa;">{{ i + 1 }}</td>
                <td style="font-size:12px; padding:7px 10px; font-weight:700; color:#fff;">{{ s.symbol }}</td>
                <td class="tbl-cell">{{ s.sector || '-' }}</td>
                <td class="tbl-cell-num" style="font-weight:700; color:#60a5fa;">{{ s.composite_score | number:'1.1-1' }}</td>
                <td class="tbl-cell-num">{{ s.base_price | number:'1.2-2' }}</td>
                <td class="tbl-cell-num" style="color:#fff; font-weight:600;">{{ s.current_price | number:'1.2-2' }}</td>
                <td class="tbl-cell-num" [class]="(s.return_pct || 0) >= 0 ? 'pos' : 'neg'" style="font-weight:700;">{{ s.return_pct | number:'1.2-2' }}%</td>
                <td class="tbl-cell"><span [class]="(s.composite_score||0) >= 72 ? 'badge-green' : (s.composite_score||0) >= 55 ? 'badge-amber' : 'badge-red'">{{ s.signal || 'WATCH' }}</span></td>
              </tr>
            }
            @if (!(rd?.current_top10?.length)) {
              <tr><td colspan="8" style="text-align:center; padding:18px; color:#64748b; font-size:12px;">No ranking data available. Run ranking to generate Top 10.</td></tr>
            }
          </tbody>
        </table>
      </div>
    </div>

    <!-- Previous Top 10 -->
    <div class="card" style="overflow:hidden;">
      <div style="background:#1a3a5c; padding:10px 12px;">
        <div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:6px;">
          <span class="section-title">Previous top 10 Ranking Stocks</span>
          <div style="display:flex; align-items:center; gap:6px; flex-wrap:wrap;">
            <select class="select-sm" [(ngModel)]="prevTradeType" style="min-width:90px;">
              @for (t of prevTradeTypes; track t.value) {
                <option [ngValue]="t.value">{{ t.label }}</option>
              }
            </select>
            @if (!prevActiveBatch?.active) {
              <button class="btn-green" (click)="buyPrevTop10()" [disabled]="prevBuying()">
                @if (prevBuying()) { <span class="spin"></span> } @else { Buy All 10 }
              </button>
            } @else {
              <button class="btn-red" (click)="sellPrevTop10()" [disabled]="prevSelling()">
                @if (prevSelling()) { <span class="spin"></span> } @else { Sell All 10 }
              </button>
              <button class="btn-outline" style="padding:4px 8px; font-size:10px;" (click)="refreshPrevTop10()" [disabled]="prevRefreshing()">
                @if (prevRefreshing()) { <span class="spin"></span> } @else { Refresh }
              </button>
            }
          </div>
        </div>
        @if (prevActiveBatch?.active) {
          <div style="display:flex; gap:12px; font-size:11px; margin-top:6px;">
            <span [class]="prevBatchAvgReturn >= 0 ? 'pos' : 'neg'">Avg: {{ prevBatchAvgReturn | number:'1.2-2' }}%</span>
            <span style="color:#94a3b8;">W: {{ prevActiveBatch.winners || 0 }} | L: {{ prevActiveBatch.losers || 0 }}</span>
            <span style="color:#64748b; font-size:10px;">Batch: {{ prevActiveBatch.batch_id }}</span>
          </div>
        } @else {
          <div style="display:flex; gap:12px; font-size:11px; margin-top:6px;">
            <span [class]="(rd?.prev_avg_return||0) >= 0 ? 'pos' : 'neg'">Avg: {{ rd?.prev_avg_return | number:'1.2-2' }}%</span>
          </div>
        }
      </div>
      <div style="overflow-x:auto;">
        @if (prevActiveBatch?.active) {
          <table style="width:100%; border-collapse:collapse; min-width:700px;">
            <thead><tr>
              <th class="tbl-hdr">#</th><th class="tbl-hdr">Symbol</th><th class="tbl-hdr">Sector</th>
              <th class="tbl-hdr" style="text-align:right;">Buy Price</th>
              <th class="tbl-hdr" style="text-align:right;">CMP</th>
              <th class="tbl-hdr" style="text-align:right;">Return %</th>
              <th class="tbl-hdr">Status</th>
            </tr></thead>
            <tbody>
              @for (s of (prevActiveBatch.stocks || []); track s.symbol; let i = $index) {
                <tr class="tbl-row">
                  <td class="tbl-cell">{{ i + 1 }}</td>
                  <td style="font-size:12px; padding:7px 10px; font-weight:700; color:#fff;">{{ s.symbol }}</td>
                  <td class="tbl-cell">{{ s.sector || '-' }}</td>
                  <td class="tbl-cell-num">{{ s.buy_price | number:'1.2-2' }}</td>
                  <td class="tbl-cell-num" style="color:#fff; font-weight:600;">{{ s.current_price | number:'1.2-2' }}</td>
                  <td class="tbl-cell-num" [class]="(s.return_pct || 0) >= 0 ? 'pos' : 'neg'" style="font-weight:700;">{{ s.return_pct | number:'1.2-2' }}%</td>
                  <td class="tbl-cell"><span [class]="(s.return_pct||0) >= 0 ? 'badge-green' : 'badge-red'">{{ (s.return_pct||0) >= 0 ? 'GAIN' : 'LOSS' }}</span></td>
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <table style="width:100%; border-collapse:collapse; min-width:600px;">
            <thead><tr>
              <th class="tbl-hdr">#</th><th class="tbl-hdr">Symbol</th><th class="tbl-hdr">Sector</th>
              <th class="tbl-hdr" style="text-align:right;">Base Price</th>
              <th class="tbl-hdr" style="text-align:right;">CMP</th>
              <th class="tbl-hdr" style="text-align:right;">Return %</th>
            </tr></thead>
            <tbody>
              @for (s of (rd?.previous_top10 || []); track s.symbol; let i = $index) {
                <tr class="tbl-row">
                  <td class="tbl-cell">{{ i + 1 }}</td>
                  <td style="font-size:12px; padding:7px 10px; font-weight:700; color:#fff;">{{ s.symbol }}</td>
                  <td class="tbl-cell">{{ s.sector || '-' }}</td>
                  <td class="tbl-cell-num">{{ s.base_price | number:'1.2-2' }}</td>
                  <td class="tbl-cell-num" style="color:#fff; font-weight:600;">{{ s.current_price | number:'1.2-2' }}</td>
                  <td class="tbl-cell-num" [class]="(s.current_return || 0) >= 0 ? 'pos' : 'neg'" style="font-weight:700;">{{ s.current_return | number:'1.2-2' }}%</td>
                </tr>
              }
              @if (!(rd?.previous_top10?.length)) {
                <tr><td colspan="6" style="text-align:center; padding:18px; color:#64748b; font-size:12px;">No previous ranking data. Run ranking at least twice to see comparison.</td></tr>
              }
            </tbody>
          </table>
        }
      </div>
    </div>
  </div>

  <!-- Previous Top 10 Avg Return Trend Chart -->
  <div class="card" style="padding:12px; margin-bottom:14px;">
    <div style="display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:8px;">
      <div>
        <span style="font-size:13px; font-weight:800; color:#60a5fa;">Previous Top 10 Avg Return Trend (%)</span>
        <div style="font-size:10px; color:#94a3b8; margin-top:3px;">Based on actual trade performance only.</div>
      </div>
      <div style="display:flex; align-items:center; gap:8px;">
        @if (!chartUseRange) {
          <select class="select-sm" [(ngModel)]="chartPeriod" (ngModelChange)="sliceChartData()">
            @for (p of chartPeriods; track p) {
              <option [ngValue]="p">{{ p }} Days</option>
            }
          </select>
        } @else {
          <input type="date" class="select-sm" [(ngModel)]="chartFrom" (ngModelChange)="loadChartData()" title="First date to include">
          <span style="font-size:10px; color:#64748b;">to</span>
          <input type="date" class="select-sm" [(ngModel)]="chartTo" (ngModelChange)="loadChartData()" title="Last date to include">
        }
        <button class="btn-outline" style="padding:5px 9px; font-size:10px;"
                (click)="fillPastDays()" [disabled]="fillingDays()"
                title="Work out the return for any past ranking day that has none stored yet">
          {{ fillingDays() ? 'Filling...' : 'Fill past days' }}
        </button>
        <button class="btn-outline" style="padding:5px 9px; font-size:10px;"
                (click)="toggleChartRange()"
                [title]="chartUseRange ? 'Go back to a rolling window' : 'Pick an exact date range'">
          {{ chartUseRange ? 'Recent' : 'Date range' }}
        </button>
        <div style="background:#0f172a; border-radius:6px; padding:6px 10px; display:flex; align-items:center; gap:6px;">
          <span style="font-size:10px; color:#64748b;">Avg</span>
          <span style="font-size:14px; font-weight:800;" [style.color]="chartAvg >= 0 ? '#22c55e' : '#ef4444'">{{ chartAvg | number:'1.2-2' }}%</span>
        </div>
        <div style="background:#0f172a; border-radius:6px; padding:6px 10px; font-size:10px; color:#94a3b8;">
          {{ chartActualCount }} data points
        </div>
      </div>
    </div>
    <div style="display:flex; align-items:center; justify-content:space-between; gap:8px; margin-bottom:4px;">
      <span style="font-size:9px; color:#64748b;">%</span>
      @if (chartActualCount) {
        <span style="font-size:9.5px; color:#94a3b8;">
          Average of {{ chartActualCount }} measured day{{ chartActualCount === 1 ? '' : 's' }}
          @if (chartSkipped) { <span style="color:#f59e0b;">&middot; {{ chartSkipped }} day(s) not measurable</span> }
          @if (chartStats) {
            &middot; best <span style="color:#22c55e;">{{ chartStats.best_day | number:'1.2-2' }}%</span>
            &middot; worst <span style="color:#ef4444;">{{ chartStats.worst_day | number:'1.2-2' }}%</span>
            &middot; {{ chartStats.positive_days }} of {{ chartStats.measured_days }} up
          }
        </span>
      }
    </div>
    <div style="display:flex; align-items:flex-end; gap:3px; height:120px; overflow-x:auto; padding-bottom:22px; position:relative;">
      <div style="position:absolute; bottom:22px; left:0; right:0; height:1px; background:#1e2a3b;"></div>
      @for (d of chartSliced; track d.date) {
        @if (d.trade_source !== 'no_data') {
          <div style="display:flex; flex-direction:column; align-items:center; gap:2px; min-width:26px;">
            <div style="font-size:8px; font-weight:700;" [style.color]="dayAvg(d) >= 0 ? '#22c55e' : '#ef4444'">{{ dayAvg(d) | number:'1.1-1' }}</div>
            <div class="bar-chart-bar" [style.height]="barH(dayAvg(d))+'px'"
                 [style.background]="dayAvg(d) >= 0 ? '#1d6adb' : '#dc2626'"
                 [title]="barTooltip(d)"></div>
            <div style="font-size:7px; color:#64748b; transform:rotate(-45deg); transform-origin:top left; white-space:nowrap; margin-top:6px; margin-left:4px;">{{ d.date | slice:5 }}</div>
          </div>
        }
      }
      @if (chartLoading()) {
        <div style="color:#94a3b8; font-size:11px; padding:20px; text-align:center; width:100%;">
          Rebuilding each trading day from stored prices&hellip;
        </div>
      } @else if (!chartActualCount) {
        <div style="color:#64748b; font-size:11px; padding:16px; text-align:center; width:100%; line-height:1.7;">
          @if (!chartData.length && chartUseRange) {
            <div style="font-weight:700; color:#e2e8f0;">No trading days with stored prices in this range.</div>
            <div>Pick a range the daily candles cover, or fetch that period from Data Warehouse.</div>
          } @else if (!chartData.length) {
            <div style="font-weight:700; color:#e2e8f0;">No rankings were recorded in this period.</div>
            <div>Use <strong style="color:#cbd5e1;">Date range</strong> to rebuild past days
            directly from stored prices &mdash; that works for dates before ranking ever ran.</div>
          } @else {
            <div style="font-weight:700; color:#e2e8f0;">{{ chartData.length }} ranking day(s) found, none measurable yet.</div>
            <div>Each needs the next trading day&rsquo;s price to close the trade. Press
            <strong style="color:#cbd5e1;">Fill past days</strong> once that day exists.</div>
          }
        </div>
      }
    </div>
  </div>

  <!-- Download Section -->
  <div class="card" style="padding:12px;">
    <div class="section-title" style="margin-bottom:10px;">Download / Export</div>
    <div style="display:grid; grid-template-columns:1fr 1fr 1fr; gap:7px;">
      <button class="btn-outline" style="padding:7px 10px; font-size:10.5px;" (click)="downloadCsv('/tracker/export/ranking','TradeRakshak_Current_Top10.csv')">Current Top 10</button>
      <button class="btn-outline" style="padding:7px 10px; font-size:10.5px;" (click)="downloadCsv('/tracker/export/previous','TradeRakshak_Previous_Top10.csv')">Previous Top 10</button>
      <button class="btn-outline" style="padding:7px 10px; font-size:10.5px;" (click)="downloadCsv('/tracker/export/historical','TradeRakshak_Historical.csv')">Historical Rankings</button>
    </div>
  </div>
</div>

<!-- Toast Notification -->
@if (toastMsg) {
  <div class="toast" [class]="toastType === 'error' ? 'toast-error' : 'toast-success'">{{ toastMsg }}</div>
}
  `
})
export class TrackerDashboardComponent implements OnInit, OnDestroy {
  dash: any = { total:0, positive:0, negative:0, eligible:0, avg_return:0, last_refreshed:'-', market_status:'CLOSED' };
  rd: any = null;
  chartData: any[] = [];
  chartSliced: any[] = [];
  chartPeriod = 10;
  chartPeriods = [5, 10, 15, 20, 30, 60, 90];
  chartUseRange = false;
  chartFrom = '';
  chartTo = '';
  chartLoading = signal(false);
  /** Range-mode summary from the server: measured days, best, worst, average. */
  chartStats: any = null;
  ranking = signal(false);

  prevTradeType = 'PAPER';
  prevTradeTypes = [
    { value: 'PAPER', label: 'Paper' },
    { value: 'NOW', label: 'Now' },
    { value: '1D', label: '1 Day' },
    { value: '2D', label: '2 Days' },
    { value: '3D', label: '3 Days' },
    { value: '5D', label: '5 Days' },
    { value: '1W', label: '1 Week' },
    { value: '2W', label: '2 Weeks' },
    { value: '1M', label: '1 Month' },
    { value: 'CUSTOM', label: 'Custom Date' }
  ];
  prevActiveBatch: any = null;
  prevBuying = signal(false);
  prevSelling = signal(false);
  prevRefreshing = signal(false);

  toastMsg = '';
  toastType = 'success';
  private toastTimer: any;

  private liveSub?: Subscription;
  private timer: any;

  constructor(
    private api: ApiService,
    private livePrices: LivePriceService,
    private http: HttpClient
  ) {}

  ngOnInit() {
    this.loadAll();
    this.timer = setInterval(() => this.loadDashboard(), 5 * 60 * 1000);
    this.livePrices.connect();
    this.liveSub = this.livePrices.quote$.subscribe(q => this.applyLiveQuote(q));
  }

  ngOnDestroy() {
    if (this.timer) clearInterval(this.timer);
    if (this.toastTimer) clearTimeout(this.toastTimer);
    this.liveSub?.unsubscribe();
    this.livePrices.release();
  }

  loadAll() {
    this.loadDashboard();
    this.loadRanking();
    this.loadChartData();
    this.loadPrevActiveBatch();
  }

  loadDashboard() {
    this.api.get<any>('/tracker/dashboard').subscribe({
      next: r => {
        const d = r.data || {};
        this.dash = {
          total: d.total_stocks || 0, positive: d.positive_stocks || 0,
          negative: d.negative_stocks || 0, eligible: d.eligible_stocks || 0,
          avg_return: d.avg_return || 0, last_refreshed: d.last_refreshed || '-',
          market_status: d.market_status || 'CLOSED'
        };
      }
    });
  }

  loadRanking() {
    this.api.get<any>('/tracker/ranking/latest').subscribe({
      next: r => this.rd = r.data || {},
      error: () => this.rd = {}
    });
  }

  loadChartData() {
    if (this.chartUseRange && this.chartFrom && this.chartTo) {
      // A picked range is answered by rebuilding each day from stored candles, so it covers dates
      // from long before the ranking engine was ever run. Saved rankings are not consulted.
      this.chartLoading.set(true);
      this.api.get<any>('/tracker/ranking/reconstruct-trend',
                        { from: this.chartFrom, to: this.chartTo }).subscribe({
        next: r => {
          this.chartLoading.set(false);
          const d = r?.data || {};
          this.chartData = d.days || [];
          this.chartStats = d;
          this.sliceChartData();
        },
        error: () => {
          this.chartLoading.set(false);
          this.chartData = []; this.chartStats = null; this.sliceChartData();
          this.showToast('Could not rebuild that date range.', 'error');
        }
      });
      return;
    }
    this.chartStats = null;
    this.api.get<any>('/tracker/ranking/history', { days: 90 }).subscribe({
      next: r => { this.chartData = r.data || []; this.sliceChartData(); },
      error: () => { this.chartData = []; this.sliceChartData(); }
    });
  }

  fillingDays = signal(false);

  /**
   * Asks the server to work out returns for past ranking days that have none stored.
   *
   * <p>Reports the per-day reasons it sends back rather than only a count, because a day that is
   * missing because it was a holiday needs no action, while one waiting on tomorrow's close does.
   */
  fillPastDays() {
    if (this.fillingDays()) return;
    this.fillingDays.set(true);
    this.api.post<any>('/tracker/daily-ranking-trend/recompute?days=120', {}).subscribe({
      next: r => {
        this.fillingDays.set(false);
        const d = r?.data || {};
        this.showToast(d.message || 'Past days checked.', d.computed ? 'success' : 'error');
        this.loadChartData();
      },
      error: () => {
        this.fillingDays.set(false);
        this.showToast('Could not fill past days. Check that the application is running.', 'error');
      }
    });
  }

  /** What this day held and how it settled - shown on hover so a bar is never just a number. */
  barTooltip(d: any): string {
    if (!d) return '';
    const parts = [d.date];
    if (d.trade_avg_return != null) parts.push('return ' + Number(d.trade_avg_return).toFixed(2) + '%');
    if (d.trade_winners != null) parts.push(d.trade_winners + ' up / ' + d.trade_losers + ' down');
    if (d.sell_date) parts.push('sold ' + d.sell_date);
    if (d.symbols?.length) parts.push(d.symbols.join(', '));
    if (d.note) parts.push(d.note);
    return parts.join('  |  ');
  }

  toggleChartRange() {
    this.chartUseRange = !this.chartUseRange;
    if (this.chartUseRange && !this.chartFrom) {
      // Default to the last month, which is the span people ask for most often.
      const today = new Date();
      const past = new Date(today.getTime() - 30 * 86400000);
      this.chartTo = today.toISOString().slice(0, 10);
      this.chartFrom = past.toISOString().slice(0, 10);
    }
    this.loadChartData();
  }

  /** Ranking days in view whose return could not be worked out - shown so the gap is explained. */
  get chartSkipped(): number {
    return this.chartSliced.filter(d => d?.trade_source === 'no_data' || d?.trade_avg_return == null).length;
  }

  loadPrevActiveBatch() {
    this.api.get<any>('/tracker/prev-trade-batches/active').subscribe({
      next: r => this.prevActiveBatch = r.data || { active: false },
      error: () => this.prevActiveBatch = { active: false }
    });
  }

  runRanking() {
    this.ranking.set(true);
    this.api.post<any>('/tracker/rank', {}).subscribe({
      next: r => { this.rd = r.data; this.ranking.set(false); this.loadAll(); },
      error: () => this.ranking.set(false)
    });
  }

  // ── Previous Top 10 Trade Actions ──────────────────────────────────────────

  buyPrevTop10() {
    if (!(this.rd?.previous_top10?.length)) {
      this.showToast('No Previous Top 10 stocks available. Run ranking at least twice.', 'error');
      return;
    }
    this.prevBuying.set(true);
    this.api.post<any>('/tracker/prev-trade-batches/buy', {
      trade_type: this.prevTradeType
    }).subscribe({
      next: r => {
        this.prevBuying.set(false);
        const data = r.data || {};
        if (data.success === false) {
          this.showToast(data.error || 'Failed to create trade batch.', 'error');
        } else {
          this.showToast(data.message || 'Buy All 10 executed successfully.');
          this.loadPrevActiveBatch();
        }
      },
      error: (err) => {
        this.prevBuying.set(false);
        this.showToast('Buy All 10 failed: ' + (err?.error?.message || 'Network error'), 'error');
      }
    });
  }

  sellPrevTop10() {
    if (!this.prevActiveBatch?.batch_id) {
      this.showToast('No active batch to sell.', 'error');
      return;
    }
    this.prevSelling.set(true);
    this.api.post<any>(`/tracker/trade-batches/${this.prevActiveBatch.batch_id}/sell`, {}).subscribe({
      next: r => {
        this.prevSelling.set(false);
        const data = r.data || {};
        if (data.success === false) {
          this.showToast(data.error || 'Failed to sell batch.', 'error');
        } else {
          this.showToast(data.message || 'Sell All 10 completed.');
          this.prevActiveBatch = { active: false };
          this.loadChartData();
        }
      },
      error: (err) => {
        this.prevSelling.set(false);
        this.showToast('Sell All 10 failed: ' + (err?.error?.message || 'Network error'), 'error');
      }
    });
  }

  refreshPrevTop10() {
    this.prevRefreshing.set(true);
    this.api.post<any>('/tracker/prev-trade-batches/refresh', {}).subscribe({
      next: r => {
        this.prevRefreshing.set(false);
        const data = r.data || {};
        if (data.success === false) {
          this.showToast(data.error || 'Refresh failed.', 'error');
        } else {
          this.prevActiveBatch = data;
          this.showToast('Prices refreshed successfully.');
        }
      },
      error: (err) => {
        this.prevRefreshing.set(false);
        this.showToast('Refresh failed: ' + (err?.error?.message || 'Network error'), 'error');
      }
    });
  }

  // ── Chart Logic ────────────────────────────────────────────────────────────

  sliceChartData() {
    // A picked range is already exactly what was asked for; only the rolling window needs trimming.
    this.chartSliced = this.chartUseRange ? this.chartData : this.chartData.slice(-this.chartPeriod);
  }

  get chartAvg(): number {
    const actual = this.chartSliced.filter(d => d?.trade_source !== 'no_data' && d?.trade_avg_return != null);
    if (!actual.length) return 0;
    const sum = actual.reduce((acc: number, d: any) => acc + this.dayAvg(d), 0);
    return sum / actual.length;
  }

  get chartActualCount(): number {
    return this.chartSliced.filter(d => d?.trade_source !== 'no_data' && d?.trade_avg_return != null).length;
  }

  dayAvg(d: any): number {
    return Number(d?.trade_avg_return ?? 0) || 0;
  }

  get prevBatchAvgReturn(): number {
    if (!this.prevActiveBatch?.stocks?.length) return 0;
    return Number(this.prevActiveBatch.avg_return ?? 0) || 0;
  }

  barH(val: number): number {
    const actual = this.chartSliced.filter(d => d?.trade_source !== 'no_data' && d?.trade_avg_return != null);
    const max = Math.max(1, ...actual.map((d: any) => Math.abs(this.dayAvg(d))));
    return Math.max(2, Math.abs(val) / max * 80);
  }

  applyLiveQuote(q: any) {
    if (!q) return;
    const sym = (q.symbol || '').toUpperCase();

    if (this.rd) {
      for (const stock of (this.rd.current_top10 || [])) {
        if ((stock.symbol || '').toUpperCase() === sym && q.ltp > 0) {
          stock.current_price = q.ltp;
          if (stock.base_price > 0) {
            stock.return_pct = Math.round(((q.ltp - stock.base_price) / stock.base_price) * 10000) / 100;
          }
        }
      }
    }

    if (this.prevActiveBatch?.stocks) {
      for (const stock of this.prevActiveBatch.stocks) {
        if ((stock.symbol || '').toUpperCase() === sym && q.ltp > 0) {
          stock.current_price = q.ltp;
          if (stock.buy_price > 0) {
            stock.return_pct = Math.round(((q.ltp - stock.buy_price) / stock.buy_price) * 10000) / 100;
          }
        }
      }
    }
  }

  // ── Toast ──────────────────────────────────────────────────────────────────

  showToast(msg: string, type: string = 'success') {
    this.toastMsg = msg;
    this.toastType = type;
    if (this.toastTimer) clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => { this.toastMsg = ''; }, 4000);
  }

  // ── Download ───────────────────────────────────────────────────────────────

  downloadCsv(endpoint: string, filename: string) {
    this.http.get(`${environment.apiUrl}${endpoint}`, {
      responseType: 'blob', observe: 'response'
    }).subscribe({
      next: (response) => {
        const contentDisp = response.headers.get('Content-Disposition');
        if (contentDisp) {
          const match = contentDisp.match(/filename="?([^"]+)"?/);
          if (match) filename = match[1];
        }
        const blob = response.body!;
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = filename;
        document.body.appendChild(a); a.click();
        window.URL.revokeObjectURL(url); document.body.removeChild(a);
      },
      error: () => this.showToast('Download failed. Please try again.', 'error')
    });
  }
}
