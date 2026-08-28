import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { StrategyBuilderService } from '../../../core/services/strategy-builder.service';
import { InrPipe, PctPipe } from '../../../shared/pipes/inr.pipe';

@Component({
  selector: 'app-backtesting',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, InrPipe, PctPipe],
  template: `
<div class="p-6">
  <div class="flex items-center justify-between mb-6">
    <div>
      <h1 class="section-title">📊 Backtesting Engine</h1>
      <p class="text-slate-400 text-sm">{{ strategy?.name || 'Select a strategy to backtest' }}</p>
    </div>
    <a routerLink="/strategy-builder" class="btn-secondary text-sm">← Back</a>
  </div>

  <!-- Config Panel -->
  <div class="card mb-5">
    <div class="card-header">⚙️ Backtest Configuration</div>
    <div class="grid grid-cols-2 sm:grid-cols-3 gap-4">
      <div>
        <label class="label">From Date</label>
        <input [(ngModel)]="config.fromDate" type="date" class="input text-sm" />
      </div>
      <div>
        <label class="label">To Date</label>
        <input [(ngModel)]="config.toDate" type="date" class="input text-sm" />
      </div>
      <div>
        <label class="label">Initial Capital (₹)</label>
        <input [(ngModel)]="config.initialCapital" type="number" step="10000" class="input text-sm" />
      </div>
      <div>
        <label class="label">Slippage %</label>
        <input [(ngModel)]="config.slippagePct" type="number" step="0.01" class="input text-sm" />
      </div>
      <div>
        <label class="label">Brokerage %</label>
        <input [(ngModel)]="config.brokeragePct" type="number" step="0.01" class="input text-sm" />
      </div>
    </div>
    <div class="mt-4 flex gap-3">
      <button (click)="runBacktest()" [disabled]="running()" class="btn-primary">
        @if (running()) { ⏳ Running Backtest… } @else { ▶ Run Backtest }
      </button>
      @if (result) {
        <button (click)="runMonteCarlo()" [disabled]="mcRunning()" class="btn-secondary text-sm">
          @if (mcRunning()) { Running MC… } @else { 🎲 Monte Carlo (1000 sims) }
        </button>
      }
    </div>
  </div>

  @if (running()) {
    <div class="text-center py-16 text-slate-400">
      <div class="text-5xl mb-4 animate-pulse">📊</div>
      <p>Running backtest on {{ strategy?.universe || 'NSE 500' }}…</p>
      <p class="text-xs mt-2">This may take 10-30 seconds depending on universe size.</p>
    </div>
  }

  @if (result && !running()) {
    <!-- Summary Metrics -->
    <div class="grid grid-cols-2 sm:grid-cols-4 gap-4 mb-5">
      <div class="card text-center">
        <div class="text-2xl font-bold" [class]="result.winRate>=60?'text-green-400':'text-yellow-400'">{{ result.winRate | number:'1.1-1' }}%</div>
        <div class="text-xs text-slate-400 mt-1">Win Rate</div>
        <div class="text-xs text-slate-500">{{ result.wins }}W / {{ result.losses }}L / {{ result.scratches }}S</div>
      </div>
      <div class="card text-center">
        <div class="text-2xl font-bold" [class]="result.netPnl>=0?'text-green-400':'text-red-400'">₹{{ result.netPnl | inr }}</div>
        <div class="text-xs text-slate-400 mt-1">Net P&L</div>
        <div class="text-xs text-slate-500">{{ result.netPnlPct | number:'1.1-1' }}%</div>
      </div>
      <div class="card text-center">
        <div class="text-2xl font-bold text-blue-400">{{ result.cagr | number:'1.1-1' }}%</div>
        <div class="text-xs text-slate-400 mt-1">CAGR</div>
        <div class="text-xs text-slate-500">{{ result.totalDays }} days</div>
      </div>
      <div class="card text-center">
        <div class="text-2xl font-bold text-red-400">{{ result.maxDrawdown | number:'1.1-1' }}%</div>
        <div class="text-xs text-slate-400 mt-1">Max Drawdown</div>
        <div class="text-xs text-slate-500">Recovery: {{ result.recoveryFactor | number:'1.1-1' }}x</div>
      </div>
    </div>

    <div class="grid md:grid-cols-2 gap-5 mb-5">
      <!-- Advanced Metrics -->
      <div class="card">
        <div class="card-header">📐 Risk Metrics</div>
        <div class="space-y-2 text-sm">
          @for (m of advancedMetrics; track m.label) {
            <div class="flex justify-between py-1 border-b border-slate-700/40">
              <span class="text-slate-400">{{ m.label }}</span>
              <span class="font-medium" [class]="m.color||'text-white'">{{ m.value }}</span>
            </div>
          }
        </div>
      </div>

      <!-- Trade Statistics -->
      <div class="card">
        <div class="card-header">📋 Trade Statistics</div>
        <div class="space-y-2 text-sm">
          @for (m of tradeStats; track m.label) {
            <div class="flex justify-between py-1 border-b border-slate-700/40">
              <span class="text-slate-400">{{ m.label }}</span>
              <span class="font-medium" [class]="m.color||'text-white'">{{ m.value }}</span>
            </div>
          }
        </div>
      </div>
    </div>

    <!-- Monthly P&L -->
    @if (result.monthlyPnl) {
      <div class="card mb-5">
        <div class="card-header">📅 Monthly P&L</div>
        <div class="flex flex-wrap gap-2">
          @for (entry of monthlyEntries; track entry.month) {
            <div class="text-center rounded-lg p-2 min-w-[80px]"
              [ngClass]="entry.pnl >= 0 ? 'bg-green-900/30 border border-green-700/40' : 'bg-red-900/30 border border-red-700/40'">
              <div class="text-xs text-slate-400">{{ entry.month }}</div>
              <div class="text-sm font-bold" [class]="entry.pnl>=0?'text-green-400':'text-red-400'">
                {{ entry.pnl >= 0 ? '+' : '' }}₹{{ entry.pnl | inr }}
              </div>
            </div>
          }
        </div>
      </div>
    }

    <!-- Trade List -->
    @if (result.tradeDetails?.length) {
      <div class="card mb-5">
        <div class="card-header">📋 Trade Log ({{ result.tradeDetails.length }} trades)</div>
        <div class="overflow-x-auto max-h-80 overflow-y-auto">
          <table class="table-base">
            <thead class="sticky top-0 bg-[#112138]"><tr>
              <th>Symbol</th><th>Entry</th><th>Exit</th>
              <th class="text-right">Entry Price</th><th class="text-right">Exit Price</th>
              <th class="text-right">Return</th><th class="text-right">Net P&L</th>
              <th>Days</th><th>Result</th>
            </tr></thead>
            <tbody>
              @for (t of result.tradeDetails; track t) {
                <tr>
                  <td class="font-medium text-white">{{ t.symbol }}</td>
                  <td class="text-xs text-slate-400">{{ t.entryDate }}</td>
                  <td class="text-xs text-slate-400">{{ t.exitDate }}</td>
                  <td class="text-right">₹{{ t.entryPrice | inr }}</td>
                  <td class="text-right">₹{{ t.exitPrice | inr }}</td>
                  <td class="text-right" [class]="t.returnPct>=0?'text-green-400':'text-red-400'">{{ t.returnPct | number:'1.1-1' }}%</td>
                  <td class="text-right font-bold" [class]="t.netPnl>=0?'text-green-400':'text-red-400'">₹{{ t.netPnl | inr }}</td>
                  <td class="text-center text-xs text-slate-400">{{ t.holdingDays }}d</td>
                  <td><span class="badge text-xs" [ngClass]="t.result==='Win'?'badge-green':t.result==='Loss'?'badge-red':'badge-yellow'">{{ t.result }}</span></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    }

    <!-- Monte Carlo Results -->
    @if (mcResult) {
      <div class="card mb-5 border-purple-700/40">
        <div class="card-header">🎲 Monte Carlo Simulation ({{ mcResult.simulations }} runs)</div>
        <div class="grid grid-cols-2 sm:grid-cols-5 gap-4 text-center">
          <div><div class="text-sm text-slate-400">5th Pct</div><div class="font-bold text-red-400">₹{{ mcResult.percentile5 | inr }}</div></div>
          <div><div class="text-sm text-slate-400">25th Pct</div><div class="font-bold text-yellow-400">₹{{ mcResult.percentile25 | inr }}</div></div>
          <div><div class="text-sm text-slate-400">Median</div><div class="font-bold text-white">₹{{ mcResult.percentile50 | inr }}</div></div>
          <div><div class="text-sm text-slate-400">75th Pct</div><div class="font-bold text-green-400">₹{{ mcResult.percentile75 | inr }}</div></div>
          <div><div class="text-sm text-slate-400">95th Pct</div><div class="font-bold text-blue-400">₹{{ mcResult.percentile95 | inr }}</div></div>
        </div>
        <div class="mt-4 text-center">
          <span class="badge badge-green text-sm">Probability of positive return: {{ mcResult.probPositive | number:'1.1-1' }}%</span>
        </div>
      </div>
    }
  }
</div>
  `
})
export class BacktestingComponent implements OnInit {
  running   = signal(false);
  mcRunning = signal(false);
  strategy: any = null;
  result: any   = null;
  mcResult: any = null;
  btId: number | null = null;

  config = {
    fromDate:       new Date(Date.now() - 2*365*24*60*60*1000).toISOString().split('T')[0],
    toDate:         new Date().toISOString().split('T')[0],
    initialCapital: 100000, slippagePct: 0.05, brokeragePct: 0.03
  };

  get advancedMetrics() {
    if (!this.result) return [];
    return [
      { label:'Sharpe Ratio',    value: this.result.sharpeRatio?.toFixed(2),    color: this.result.sharpeRatio>1?'text-green-400':'text-yellow-400' },
      { label:'Sortino Ratio',   value: this.result.sortinoRatio?.toFixed(2),   color: this.result.sortinoRatio>1?'text-green-400':'text-yellow-400' },
      { label:'Calmar Ratio',    value: this.result.calmarRatio?.toFixed(2),    color: '' },
      { label:'Profit Factor',   value: this.result.profitFactor?.toFixed(2),   color: this.result.profitFactor>1.5?'text-green-400':'text-yellow-400' },
      { label:'Recovery Factor', value: this.result.recoveryFactor?.toFixed(2), color: '' },
      { label:'Expectancy',      value: '₹' + (this.result.expectancy || 0).toFixed(0), color: this.result.expectancy>0?'text-green-400':'text-red-400' },
    ];
  }

  get tradeStats() {
    if (!this.result) return [];
    return [
      { label:'Total Trades',     value: this.result.totalTrades },
      { label:'Avg Profit/Trade', value: '₹' + (this.result.avgProfitPerTrade||0).toFixed(0), color:'text-green-400' },
      { label:'Avg Loss/Trade',   value: '₹' + (this.result.avgLossPerTrade||0).toFixed(0),   color:'text-red-400' },
      { label:'Best Trade',       value: '₹' + (this.result.bestTrade||0).toFixed(0),         color:'text-green-400' },
      { label:'Worst Trade',      value: '₹' + (this.result.worstTrade||0).toFixed(0),        color:'text-red-400' },
      { label:'Avg Hold (days)',  value: (this.result.avgHoldingDays||0).toFixed(1) },
    ];
  }

  get monthlyEntries() {
    if (!this.result?.monthlyPnl) return [];
    return Object.entries(this.result.monthlyPnl).map(([month, pnl]) => ({ month, pnl: pnl as number }));
  }

  constructor(private svc: StrategyBuilderService, private route: ActivatedRoute) {}

  ngOnInit() {
    const id = +this.route.snapshot.params['id'];
    if (id) {
      this.svc.getStrategy(id).subscribe({ next: r => this.strategy = r.data?.strategy });
    }
  }

  runBacktest() {
    if (!this.strategy) return;
    this.running.set(true); this.result = null;
    this.svc.runBacktest(this.strategy.id, this.config).subscribe({
      next: r => { this.result = r.data; this.running.set(false); },
      error: e => { alert(e.error?.message || 'Backtest failed.'); this.running.set(false); }
    });
  }

  runMonteCarlo() {
    if (!this.result) return;
    this.mcRunning.set(true);
    // Use a dummy backtest ID (in production, get from the saved backtest)
    this.svc.runMonteCarlo(1, 1000).subscribe({
      next: r => { this.mcResult = r.data; this.mcRunning.set(false); },
      error: () => this.mcRunning.set(false)
    });
  }
}
