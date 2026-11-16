import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';

/**
 * Strategy Tester — run a saved strategy over a past period and see what it would have done.
 *
 * Separate page from Strategy Builder on purpose: building is about defining conditions, testing
 * is about a run with its own capital, costs and date range. Mixing them meant the test settings
 * lived inside an editor and were lost whenever the strategy was reopened.
 *
 * Everything here comes from the existing backtest API — no new engine, so a result on this page
 * and a result from the builder are the same computation.
 */
@Component({
  selector: 'app-strategy-tester',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
  <div class="p-4">
    <div class="flex items-start justify-between gap-3 flex-wrap mb-3">
      <div>
        <h1 class="text-xl font-bold text-white">Strategy Tester</h1>
        <p class="text-xs text-slate-400 mt-1 max-w-3xl leading-relaxed">
          Replay a saved strategy over a past period using stored prices. Costs are applied to every
          trade, so the result is what the strategy would have kept, not the gross move.
        </p>
      </div>
      <button class="btn-primary" (click)="loadStrategies()" [disabled]="loadingList">Reload strategies</button>
    </div>

    <!-- Settings -->
    <div class="panel p-4 mb-3">
      <div class="grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(150px,1fr));">
        <div>
          <label class="lbl">Strategy</label>
          <select class="sel" [(ngModel)]="strategyId" [disabled]="running">
            <option [ngValue]="null">— select —</option>
            @for (s of strategies; track s.id) {
              <option [ngValue]="s.id">{{ s.strategyName || s.name || ('Strategy ' + s.id) }}</option>
            }
          </select>
        </div>
        <div>
          <label class="lbl">From</label>
          <input class="inp" type="date" [(ngModel)]="fromDate" [disabled]="running">
        </div>
        <div>
          <label class="lbl">To</label>
          <input class="inp" type="date" [(ngModel)]="toDate" [disabled]="running">
        </div>
        <div>
          <label class="lbl">Capital (₹)</label>
          <input class="inp" type="number" [(ngModel)]="initialCapital" [disabled]="running" min="1000" step="10000">
        </div>
        <div>
          <label class="lbl" title="Gap between the price you expect and the price you get.">Slippage %</label>
          <input class="inp" type="number" [(ngModel)]="slippagePct" [disabled]="running" min="0" step="0.05">
        </div>
        <div>
          <label class="lbl" title="Broker charge per side, as a percentage of trade value.">Brokerage %</label>
          <input class="inp" type="number" [(ngModel)]="brokeragePct" [disabled]="running" min="0" step="0.01">
        </div>
      </div>

      <div class="flex items-center gap-3 mt-3 flex-wrap">
        <button class="btn-primary" (click)="runTest()" [disabled]="running || !strategyId">
          {{ running ? 'Testing…' : 'Run Test' }}
        </button>
        @if (!strategies.length && !loadingList) {
          <span class="text-xs text-amber-400">
            No saved strategies. Create one in Strategy Builder first.
          </span>
        }
        @if (error) { <span class="text-xs text-red-400">{{ error }}</span> }
      </div>
    </div>

    <!-- Result -->
    @if (result) {
      <div class="panel p-4 mb-3">
        <div class="flex items-baseline justify-between flex-wrap gap-2 mb-3">
          <h2 class="text-sm font-bold text-white">{{ result.strategy }}</h2>
          <span class="text-xs text-slate-400">
            {{ result.fromDate }} to {{ result.toDate }} · universe {{ result.universe || 'all' }}
          </span>
        </div>

        <div class="grid gap-2" style="grid-template-columns:repeat(auto-fit,minmax(130px,1fr));">
          @for (t of tiles(); track t.label) {
            <div class="tile">
              <div class="tile-lbl" [title]="t.hint">{{ t.label }}</div>
              <div class="tile-val" [style.color]="t.color">{{ t.value }}</div>
            </div>
          }
        </div>

        <!-- Equity curve. Plain SVG: one polyline over a zero-profit baseline, which is the only
             comparison that matters - above it the run made money, below it lost. -->
        @if (curve().length > 1) {
          <div class="mt-4">
            <div class="text-xs text-slate-400 mb-1">Equity curve</div>
            <svg [attr.viewBox]="'0 0 700 150'" preserveAspectRatio="none" style="width:100%; height:150px;">
              <line x1="0" [attr.y1]="baseY()" x2="700" [attr.y2]="baseY()" stroke="#334155" stroke-dasharray="3 3"/>
              <polyline [attr.points]="curvePoints()" fill="none"
                        [attr.stroke]="netPositive() ? '#22c55e' : '#ef4444'" stroke-width="1.6"/>
            </svg>
            <div class="flex justify-between text-[10px] text-slate-500">
              <span>{{ curve()[0].date }}</span>
              <span>start ₹{{ result.initialCapital | number:'1.0-0' }} · end ₹{{ result.finalCapital | number:'1.0-0' }}</span>
              <span>{{ curve()[curve().length - 1].date }}</span>
            </div>
          </div>
        }
      </div>

      <!-- Trades -->
      @if (result.trades?.length) {
        <div class="panel p-4 mb-3">
          <div class="text-sm font-bold text-white mb-2">
            Trades <span class="text-xs text-slate-400 font-normal">
              showing {{ result.trades.length }} of {{ result.totalTrades }}
            </span>
          </div>
          <div style="overflow-x:auto;">
            <table class="w-full text-xs" style="border-collapse:collapse; min-width:640px;">
              <thead>
                <tr class="text-slate-400" style="background:#16202e;">
                  <th class="th">Symbol</th><th class="th">Entry</th><th class="th">Exit</th>
                  <th class="th tr">Entry ₹</th><th class="th tr">Exit ₹</th>
                  <th class="th tr">Return %</th><th class="th tr">Net P&amp;L</th><th class="th">Result</th>
                </tr>
              </thead>
              <tbody>
                @for (t of result.trades; track $index) {
                  <tr style="border-bottom:1px solid #22304a;">
                    <td class="td text-white font-semibold">{{ t.symbol }}</td>
                    <td class="td text-slate-400">{{ t.entryDate }}</td>
                    <td class="td text-slate-400">{{ t.exitDate }}</td>
                    <td class="td tr">{{ t.entryPrice | number:'1.2-2' }}</td>
                    <td class="td tr">{{ t.exitPrice | number:'1.2-2' }}</td>
                    <td class="td tr" [style.color]="(t.returnPct || 0) >= 0 ? '#22c55e' : '#ef4444'">
                      {{ t.returnPct != null ? (t.returnPct | number:'1.2-2') + '%' : '—' }}
                    </td>
                    <td class="td tr" [style.color]="(t.netPnl || 0) >= 0 ? '#22c55e' : '#ef4444'">
                      {{ t.netPnl | number:'1.0-0' }}
                    </td>
                    <td class="td" [style.color]="t.result === 'Win' ? '#22c55e' : t.result === 'Loss' ? '#ef4444' : '#94a3b8'">
                      {{ t.result }}
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }
    }

    <!-- Past runs -->
    @if (pastRuns.length) {
      <div class="panel p-4">
        <div class="text-sm font-bold text-white mb-2">Earlier tests for this strategy</div>
        <div style="overflow-x:auto;">
          <table class="w-full text-xs" style="border-collapse:collapse; min-width:600px;">
            <thead>
              <tr class="text-slate-400" style="background:#16202e;">
                <th class="th">Period</th><th class="th tr">Trades</th><th class="th tr">Win %</th>
                <th class="th tr">Net P&amp;L</th><th class="th tr">Max DD</th><th class="th tr">Sharpe</th>
              </tr>
            </thead>
            <tbody>
              @for (r of pastRuns; track $index) {
                <tr style="border-bottom:1px solid #22304a;">
                  <td class="td text-slate-400">{{ r[2] }} → {{ r[3] }}</td>
                  <td class="td tr">{{ r[12] }}</td>
                  <td class="td tr">{{ r[4] | number:'1.1-1' }}</td>
                  <td class="td tr" [style.color]="(r[5] || 0) >= 0 ? '#22c55e' : '#ef4444'">{{ r[5] | number:'1.0-0' }}</td>
                  <td class="td tr text-amber-400">{{ r[7] | number:'1.2-2' }}</td>
                  <td class="td tr">{{ r[8] | number:'1.2-2' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    }
  </div>
  `,
  styles: [`
    .panel { background:#0f1c2e; border:1px solid #22304a; border-radius:10px; }
    .lbl { display:block; font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; margin-bottom:3px; }
    .sel, .inp { width:100%; background:#0b1a2d; border:1px solid #22304a; border-radius:6px;
                 color:#e2e8f0; font-size:12px; padding:6px 8px; }
    .btn-primary { background:#1d6adb; color:#fff; border:none; border-radius:6px;
                   font-size:12px; font-weight:600; padding:7px 14px; cursor:pointer; }
    .btn-primary:disabled { opacity:.55; cursor:not-allowed; }
    .tile { background:#0b1a2d; border:1px solid #203650; border-radius:8px; padding:8px 10px; }
    .tile-lbl { font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; }
    .tile-val { font-size:16px; font-weight:800; margin-top:2px; }
    .th { font-size:9px; text-transform:uppercase; letter-spacing:.5px; padding:6px 9px; text-align:left; }
    .td { padding:6px 9px; color:#e2e8f0; }
    .tr { text-align:right; }
  `]
})
export class StrategyTesterComponent implements OnInit {
  private api = inject(ApiService);

  strategies: any[] = [];
  loadingList = false;

  strategyId: number | null = null;
  fromDate = this.isoMonthsAgo(12);
  toDate = new Date().toISOString().slice(0, 10);
  initialCapital = 100000;
  slippagePct = 0.1;
  brokeragePct = 0.03;

  running = false;
  error = '';
  result: any = null;
  pastRuns: any[] = [];

  ngOnInit(): void { this.loadStrategies(); }

  private isoMonthsAgo(n: number): string {
    const d = new Date();
    d.setMonth(d.getMonth() - n);
    return d.toISOString().slice(0, 10);
  }

  loadStrategies(): void {
    this.loadingList = true;
    this.api.get<any>('/strategy-builder/strategies', { filter: 'mine' }).subscribe({
      next: r => {
        this.strategies = r?.data?.mine || [];
        this.loadingList = false;
        if (!this.strategyId && this.strategies.length) this.strategyId = this.strategies[0].id;
      },
      error: () => { this.strategies = []; this.loadingList = false; }
    });
  }

  runTest(): void {
    if (!this.strategyId || this.running) return;
    this.running = true;
    this.error = '';
    this.result = null;
    this.api.post<any>(`/strategy-builder/strategies/${this.strategyId}/backtest`, {
      fromDate: this.fromDate,
      toDate: this.toDate,
      initialCapital: this.initialCapital,
      slippagePct: this.slippagePct,
      brokeragePct: this.brokeragePct
    }).subscribe({
      next: r => {
        this.running = false;
        const d = r?.data || {};
        // The engine reports a refusal as a normal response with status=failed rather than an
        // HTTP error - no market data for the period, or an empty universe. Surfacing it as the
        // error line is more useful than rendering a report of zeros.
        if (d.status === 'failed') { this.error = d.error || 'Test could not run.'; return; }
        this.result = d;
        this.loadPastRuns();
      },
      error: e => {
        this.running = false;
        this.error = e?.error?.message || 'Test failed to run.';
      }
    });
  }

  loadPastRuns(): void {
    if (!this.strategyId) return;
    this.api.get<any>(`/strategy-builder/strategies/${this.strategyId}/backtests`).subscribe({
      next: r => this.pastRuns = r?.data || [],
      error: () => this.pastRuns = []
    });
  }

  // ---- presentation ----

  tiles(): Array<{ label: string; value: string; color: string; hint: string }> {
    const m = this.result?.metrics || {};
    const num = (v: any, s = '') => (v == null ? '—' : Number(v).toFixed(2) + s);
    const pos = (v: any) => (Number(v) >= 0 ? '#22c55e' : '#ef4444');
    return [
      { label: 'Net Profit', value: num(m.netProfitPct, '%'), color: pos(m.netProfitPct),
        hint: 'Change in capital over the period, after costs.' },
      { label: 'Win Rate', value: num(m.winRate, '%'), color: '#e2e8f0',
        hint: 'Share of trades that closed in profit.' },
      { label: 'Trades', value: String(m.totalTrades ?? '—'), color: '#60a5fa',
        hint: 'Completed round trips. A handful of trades is not evidence of anything.' },
      { label: 'Wins / Losses', value: `${m.wins ?? '—'} / ${m.losses ?? '—'}`, color: '#e2e8f0', hint: '' },
      { label: 'Max Drawdown', value: num(m.maxDrawdown, '%'), color: '#f59e0b',
        hint: 'Deepest fall from a peak. The loss you would have had to sit through.' },
      { label: 'Sharpe', value: num(m.sharpeRatio), color: '#e2e8f0',
        hint: 'Return per unit of volatility. Simplified, and unreliable on few trades.' },
      { label: 'Reward / Risk', value: num(m.rewardRisk), color: '#e2e8f0',
        hint: 'Average win divided by average loss.' },
      { label: 'Expectancy', value: num(m.expectancy), color: pos(m.expectancy),
        hint: 'Average result per trade. Negative means the strategy loses money on average.' }
    ];
  }

  curve(): any[] { return this.result?.equityCurve || []; }

  netPositive(): boolean {
    return Number(this.result?.finalCapital || 0) >= Number(this.result?.initialCapital || 0);
  }

  /** Y of the starting capital, so the dashed line marks break-even rather than zero. */
  baseY(): number {
    const c = this.curve();
    if (c.length < 2) return 145;
    const vals = c.map((p: any) => Number(p.equity) || 0);
    const lo = Math.min(...vals, Number(this.result.initialCapital));
    const hi = Math.max(...vals, Number(this.result.initialCapital));
    return this.scaleY(Number(this.result.initialCapital), lo, hi);
  }

  curvePoints(): string {
    const c = this.curve();
    if (c.length < 2) return '';
    const vals = c.map((p: any) => Number(p.equity) || 0);
    const lo = Math.min(...vals, Number(this.result.initialCapital));
    const hi = Math.max(...vals, Number(this.result.initialCapital));
    const dx = 700 / (c.length - 1);
    return c.map((p: any, i: number) =>
      `${(i * dx).toFixed(1)},${this.scaleY(Number(p.equity) || 0, lo, hi).toFixed(1)}`).join(' ');
  }

  private scaleY(v: number, lo: number, hi: number): number {
    if (hi <= lo) return 75;
    return 145 - ((v - lo) / (hi - lo)) * 140;
  }
}
