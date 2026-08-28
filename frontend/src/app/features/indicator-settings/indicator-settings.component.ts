import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-indicator-settings',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
<div class="p-6 text-slate-200 max-w-[1400px] mx-auto">
  <h1 class="text-2xl font-bold text-amber-400 mb-1">Technical Indicator Engine</h1>
  <p class="text-sm text-slate-400 mb-6">Centralized indicator computation, caching, and signal detection for all modules</p>

  <!-- Sub-menu -->
  <div class="flex gap-2 mb-6 flex-wrap">
    @for (tab of tabs; track tab.key) {
      <button (click)="activeTab = tab.key"
        [class]="activeTab === tab.key ? 'px-4 py-2 rounded text-sm font-medium bg-amber-500/20 text-amber-400 border border-amber-500/40' : 'px-4 py-2 rounded text-sm font-medium bg-slate-800/60 text-slate-400 border border-slate-700 hover:bg-slate-700/60'">
        {{ tab.label }}
      </button>
    }
  </div>

  <!-- Status Overview -->
  @if (activeTab === 'status') {
    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
      <div class="bg-[#10233d] border border-[#203650] rounded-lg p-4">
        <div class="text-xs text-slate-500 uppercase">Cached Indicators</div>
        <div class="text-2xl font-bold text-emerald-400 mt-1">{{ stats.total_cached || 0 }}</div>
      </div>
      <div class="bg-[#10233d] border border-[#203650] rounded-lg p-4">
        <div class="text-xs text-slate-500 uppercase">Expired Cache</div>
        <div class="text-2xl font-bold text-orange-400 mt-1">{{ stats.expired || 0 }}</div>
      </div>
      <div class="bg-[#10233d] border border-[#203650] rounded-lg p-4">
        <div class="text-xs text-slate-500 uppercase">Total Calculations</div>
        <div class="text-2xl font-bold text-blue-400 mt-1">{{ stats.total_calculations || 0 }}</div>
      </div>
      <div class="bg-[#10233d] border border-[#203650] rounded-lg p-4">
        <div class="text-xs text-slate-500 uppercase">Avg Duration</div>
        <div class="text-2xl font-bold text-purple-400 mt-1">{{ (stats.avg_duration_ms || 0) | number:'1.0-0' }}ms</div>
      </div>
    </div>

    <div class="bg-[#10233d] border border-[#203650] rounded-lg p-4 mb-6">
      <h3 class="text-sm font-semibold text-slate-300 mb-3">Cache Management</h3>
      <div class="flex gap-3 flex-wrap">
        <button (click)="clearExpiredCache()" class="px-4 py-2 bg-orange-600/20 text-orange-400 rounded border border-orange-600/40 text-sm hover:bg-orange-600/30">Clear Expired Cache</button>
        <button (click)="clearAllCache()" class="px-4 py-2 bg-red-600/20 text-red-400 rounded border border-red-600/40 text-sm hover:bg-red-600/30">Clear All Cache</button>
        <button (click)="loadStats()" class="px-4 py-2 bg-blue-600/20 text-blue-400 rounded border border-blue-600/40 text-sm hover:bg-blue-600/30">Refresh Stats</button>
      </div>
      @if (cacheMsg) {
        <div class="mt-3 text-sm text-emerald-400">{{ cacheMsg }}</div>
      }
    </div>

    <div class="bg-[#10233d] border border-[#203650] rounded-lg p-4">
      <h3 class="text-sm font-semibold text-slate-300 mb-3">Active Signals ({{ signals.length }})</h3>
      @if (signals.length === 0) {
        <p class="text-sm text-slate-500">No active signals detected</p>
      } @else {
        <div class="overflow-x-auto">
          <table class="w-full text-xs">
            <thead><tr class="text-slate-500 border-b border-slate-700">
              <th class="text-left py-2 px-2">Symbol</th>
              <th class="text-left py-2 px-2">Signal</th>
              <th class="text-left py-2 px-2">Value</th>
              <th class="text-left py-2 px-2">Indicator</th>
              <th class="text-right py-2 px-2">Confidence</th>
              <th class="text-left py-2 px-2">Time</th>
            </tr></thead>
            <tbody>
              @for (s of signals; track $index) {
                <tr class="border-b border-slate-800 hover:bg-slate-800/30">
                  <td class="py-2 px-2 text-amber-400 font-medium">{{ s.symbol }}</td>
                  <td class="py-2 px-2">{{ s.signal_type }}</td>
                  <td class="py-2 px-2" [class]="s.signal_value === 'BULLISH' || s.signal_value === 'BUY' ? 'text-emerald-400' : s.signal_value === 'BEARISH' || s.signal_value === 'SELL' ? 'text-red-400' : ''">{{ s.signal_value }}</td>
                  <td class="py-2 px-2 text-slate-400">{{ s.indicator_name }}</td>
                  <td class="py-2 px-2 text-right">{{ s.confidence }}%</td>
                  <td class="py-2 px-2 text-slate-500">{{ s.triggered_at }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  }

  <!-- Supported Indicators -->
  @if (activeTab === 'indicators') {
    <div class="mb-4 flex items-center gap-3">
      <input [(ngModel)]="searchTerm" placeholder="Search indicators..." class="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-sm text-slate-200 w-64">
      <span class="text-sm text-slate-500">{{ totalIndicators }} indicators across {{ categoryKeys.length }} categories</span>
    </div>

    @for (cat of categoryKeys; track cat) {
      @if (filteredIndicators(cat).length > 0) {
        <div class="mb-4">
          <h3 class="text-sm font-semibold text-amber-400/80 uppercase tracking-wider mb-2 px-1">{{ cat | titlecase }}</h3>
          <div class="bg-[#10233d] border border-[#203650] rounded-lg overflow-hidden">
            <table class="w-full text-xs">
              <thead><tr class="text-slate-500 border-b border-slate-700 bg-[#173456]/30">
                <th class="text-left py-2 px-3 w-40">Name</th>
                <th class="text-left py-2 px-3">Description</th>
                <th class="text-left py-2 px-3 w-56">Default Parameters</th>
              </tr></thead>
              <tbody>
                @for (ind of filteredIndicators(cat); track ind.name) {
                  <tr class="border-b border-slate-800/50 hover:bg-slate-800/20">
                    <td class="py-2 px-3 font-mono text-emerald-400">{{ ind.name }}</td>
                    <td class="py-2 px-3 text-slate-300">{{ ind.label }}</td>
                    <td class="py-2 px-3 text-slate-500 font-mono">{{ ind.default_params }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }
    }
  }

  <!-- Compute / Test -->
  @if (activeTab === 'compute') {
    <div class="bg-[#10233d] border border-[#203650] rounded-lg p-4 mb-6">
      <h3 class="text-sm font-semibold text-slate-300 mb-3">Compute Indicators for Symbol</h3>
      <div class="flex gap-3 items-end flex-wrap">
        <div>
          <label class="text-xs text-slate-500 block mb-1">Symbol</label>
          <input [(ngModel)]="testSymbol" placeholder="RELIANCE" class="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-sm text-slate-200 w-40 uppercase">
        </div>
        <div>
          <label class="text-xs text-slate-500 block mb-1">Exchange</label>
          <select [(ngModel)]="testExchange" class="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-sm text-slate-200">
            <option value="NSE">NSE</option>
            <option value="BSE">BSE</option>
          </select>
        </div>
        <div>
          <label class="text-xs text-slate-500 block mb-1">Timeframe</label>
          <select [(ngModel)]="testTimeframe" class="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-sm text-slate-200">
            @for (tf of timeframes; track tf) {
              <option [value]="tf">{{ tf }}</option>
            }
          </select>
        </div>
        <button (click)="computeIndicators()" [disabled]="computing" class="px-4 py-2 bg-amber-600/20 text-amber-400 rounded border border-amber-600/40 text-sm hover:bg-amber-600/30 disabled:opacity-50">
          {{ computing ? 'Computing...' : 'Compute All' }}
        </button>
      </div>
    </div>

    @if (computeError) {
      <div class="bg-red-900/20 border border-red-800/40 rounded-lg p-3 mb-4 text-sm text-red-400">{{ computeError }}</div>
    }

    @if (computeResult && computeResultKeys.length > 0) {
      <div class="bg-[#10233d] border border-[#203650] rounded-lg p-4">
        <div class="flex items-center justify-between mb-3">
          <h3 class="text-sm font-semibold text-slate-300">Results for {{ testSymbol.toUpperCase() }} ({{ computeResultKeys.length }} values)</h3>
          <span class="text-xs px-2 py-1 rounded" [class]="computeResult.signal === 'STRONG_BUY' || computeResult.signal === 'BUY' ? 'bg-emerald-900/30 text-emerald-400 border border-emerald-700/40' : computeResult.signal === 'STRONG_SELL' || computeResult.signal === 'SELL' ? 'bg-red-900/30 text-red-400 border border-red-700/40' : 'bg-slate-800 text-slate-400 border border-slate-700'">
            Signal: {{ computeResult.signal }} ({{ computeResult.signal_score }}%)
          </span>
        </div>
        <div class="overflow-x-auto max-h-[500px] overflow-y-auto">
          <table class="w-full text-xs">
            <thead class="sticky top-0 bg-[#173456]"><tr class="text-slate-500 border-b border-slate-700">
              <th class="text-left py-2 px-3 w-52">Indicator</th>
              <th class="text-left py-2 px-3">Value</th>
            </tr></thead>
            <tbody>
              @for (key of computeResultKeys; track key) {
                <tr class="border-b border-slate-800/50 hover:bg-slate-800/20">
                  <td class="py-1.5 px-3 font-mono text-slate-400">{{ key }}</td>
                  <td class="py-1.5 px-3 font-mono" [class]="getValueColor(key, computeResult[key])">{{ formatValue(computeResult[key]) }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    }
  }

  <!-- Settings -->
  @if (activeTab === 'settings') {
    <div class="bg-[#10233d] border border-[#203650] rounded-lg p-4 mb-6">
      <h3 class="text-sm font-semibold text-slate-300 mb-3">Default Indicator Periods</h3>
      <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        @for (s of defaultSettings; track s.key) {
          <div class="flex items-center gap-3">
            <label class="text-xs text-slate-400 w-40">{{ s.label }}</label>
            <input [(ngModel)]="s.value" class="bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-sm text-slate-200 w-20 text-center">
          </div>
        }
      </div>
      <div class="mt-4 flex gap-3">
        <button (click)="saveAllSettings()" class="px-4 py-2 bg-emerald-600/20 text-emerald-400 rounded border border-emerald-600/40 text-sm hover:bg-emerald-600/30">Save Settings</button>
        <button (click)="resetDefaults()" class="px-4 py-2 bg-slate-700/40 text-slate-400 rounded border border-slate-600 text-sm hover:bg-slate-700/60">Reset Defaults</button>
      </div>
      @if (settingsMsg) {
        <div class="mt-3 text-sm text-emerald-400">{{ settingsMsg }}</div>
      }
    </div>

    <div class="bg-[#10233d] border border-[#203650] rounded-lg p-4 mb-6">
      <h3 class="text-sm font-semibold text-slate-300 mb-3">Cache Configuration</h3>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div class="flex items-center gap-3">
          <label class="text-xs text-slate-400 w-40">Cache TTL (minutes)</label>
          <input [(ngModel)]="cacheTtl" type="number" class="bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-sm text-slate-200 w-20 text-center">
        </div>
        <div class="flex items-center gap-3">
          <label class="text-xs text-slate-400 w-40">Max Batch Size</label>
          <input [(ngModel)]="maxBatchSize" type="number" class="bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-sm text-slate-200 w-20 text-center">
        </div>
      </div>
    </div>

    <div class="bg-[#10233d] border border-[#203650] rounded-lg p-4">
      <h3 class="text-sm font-semibold text-slate-300 mb-3">Timeframe Support</h3>
      <div class="flex flex-wrap gap-2">
        @for (tf of timeframes; track tf) {
          <span class="px-3 py-1 rounded text-xs bg-slate-800 text-slate-300 border border-slate-700">{{ tf }}</span>
        }
      </div>
    </div>
  }

  <!-- Integration Map -->
  @if (activeTab === 'integration') {
    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
      @for (mod of integrationModules; track mod.name) {
        <div class="bg-[#10233d] border border-[#203650] rounded-lg p-4">
          <h3 class="text-sm font-semibold text-amber-400 mb-2">{{ mod.name }}</h3>
          <p class="text-xs text-slate-400 mb-3">{{ mod.description }}</p>
          <div class="flex flex-wrap gap-1">
            @for (ind of mod.indicators; track ind) {
              <span class="px-2 py-0.5 rounded text-[10px] bg-emerald-900/20 text-emerald-400 border border-emerald-800/30">{{ ind }}</span>
            }
          </div>
        </div>
      }
    </div>
  }
</div>
  `,
  styles: ``
})
export class IndicatorSettingsComponent implements OnInit {
  private http = inject(HttpClient);

  activeTab = 'status';
  tabs = [
    { key: 'status', label: 'Status & Cache' },
    { key: 'indicators', label: 'Supported Indicators' },
    { key: 'compute', label: 'Compute / Test' },
    { key: 'settings', label: 'Settings' },
    { key: 'integration', label: 'Integration Map' }
  ];

  stats: any = {};
  signals: any[] = [];
  cacheMsg = '';
  settingsMsg = '';
  searchTerm = '';

  categories: any = {};
  categoryKeys: string[] = [];
  totalIndicators = 0;
  timeframes = ['1MIN', '5MIN', '15MIN', '30MIN', '1HOUR', 'DAILY', 'WEEKLY', 'MONTHLY'];

  testSymbol = '';
  testExchange = 'NSE';
  testTimeframe = 'DAILY';
  computing = false;
  computeResult: any = null;
  computeResultKeys: string[] = [];
  computeError = '';

  cacheTtl = 15;
  maxBatchSize = 50;

  defaultSettings = [
    { key: 'rsi_period', label: 'RSI Period', value: '14' },
    { key: 'sma_short', label: 'SMA Short', value: '20' },
    { key: 'sma_long', label: 'SMA Long', value: '50' },
    { key: 'sma_trend', label: 'SMA Trend', value: '200' },
    { key: 'ema_fast', label: 'EMA Fast', value: '9' },
    { key: 'ema_medium', label: 'EMA Medium', value: '20' },
    { key: 'ema_slow', label: 'EMA Slow', value: '50' },
    { key: 'macd_fast', label: 'MACD Fast', value: '12' },
    { key: 'macd_slow', label: 'MACD Slow', value: '26' },
    { key: 'macd_signal', label: 'MACD Signal', value: '9' },
    { key: 'bb_period', label: 'Bollinger Period', value: '20' },
    { key: 'bb_std', label: 'Bollinger Std Dev', value: '2.0' },
    { key: 'atr_period', label: 'ATR Period', value: '14' },
    { key: 'adx_period', label: 'ADX Period', value: '14' },
    { key: 'stoch_k', label: 'Stochastic %K', value: '14' },
    { key: 'stoch_d', label: 'Stochastic %D', value: '3' },
    { key: 'supertrend_period', label: 'SuperTrend Period', value: '7' },
    { key: 'supertrend_mult', label: 'SuperTrend Mult', value: '3.0' },
    { key: 'volume_avg_period', label: 'Volume Avg Period', value: '20' },
    { key: 'volume_spike_threshold', label: 'Vol Spike Threshold', value: '2.0' },
    { key: 'cci_period', label: 'CCI Period', value: '20' },
    { key: 'mfi_period', label: 'MFI Period', value: '14' },
  ];

  integrationModules = [
    { name: 'Dashboard', description: 'Market overview with live indicator signals', indicators: ['RSI', 'MACD', 'EMA_CROSSOVER', 'SIGNAL_SCORE', 'VOLUME_SPIKE'] },
    { name: 'Stock Upload / Tracker', description: 'Uploaded stock analysis with indicator overlays', indicators: ['RSI', 'EMA_20', 'EMA_50', 'SMA_200', 'VWAP', 'VOLUME_SMA', 'ATR', 'SUPERTREND'] },
    { name: 'Current Top 10', description: 'Ranking with indicator-based scoring', indicators: ['RSI', 'EMA_20', 'EMA_50', 'MACD', 'ADX', 'VOLUME_CHANGE', 'SUPERTREND', 'BB_PERCENT_B'] },
    { name: 'Strategy Builder', description: 'Full indicator access for condition building', indicators: ['ALL 60+ INDICATORS', 'CROSSOVER_DETECTION', 'PATTERN_MATCHING', 'MULTI_TIMEFRAME'] },
    { name: 'Screener', description: 'Filter stocks by indicator conditions', indicators: ['RSI', 'MACD', 'BOLLINGER', 'STOCHASTIC', 'ADX', 'OBV', 'MFI', 'CCI', 'WILLIAMS_R'] },
    { name: 'Backtesting', description: 'Historical indicator computation for strategy testing', indicators: ['ALL SERIES METHODS', 'RSI_SERIES', 'EMA_SERIES', 'MACD_SERIES', 'ATR_SERIES'] },
    { name: 'Stock Ranking', description: 'Multi-factor ranking using indicators', indicators: ['RSI', 'EMA_20', 'EMA_50', 'SMA_200', 'ADX', 'VOLUME_SMA', 'SUPERTREND'] },
    { name: 'Trading & Alerts', description: 'Signal-based alerts and trade triggers', indicators: ['RSI_ZONE', 'MACD_CROSSOVER', 'EMA_CROSSOVER', 'VOLUME_SPIKE', 'BREAKOUT', 'PATTERNS'] },
    { name: 'Reports', description: 'Performance reports with indicator annotations', indicators: ['SIGNAL_SUMMARY', 'PIVOT_POINTS', 'SUPPORT_RESISTANCE', 'TREND_DIRECTION'] },
    { name: 'Data Warehouse', description: 'Bulk indicator computation and storage', indicators: ['BATCH_COMPUTE', 'CACHE_MANAGEMENT', 'INDICATOR_LOGS', 'SIGNAL_STORAGE'] },
  ];

  ngOnInit(): void {
    this.loadStats();
    this.loadSignals();
    this.loadSupported();
    this.loadSavedSettings();
  }

  loadStats(): void {
    this.http.get<any>('/api/indicators/cache/stats').subscribe({
      next: r => { if (r.success) this.stats = r.data; },
      error: () => {}
    });
  }

  loadSignals(): void {
    this.http.get<any>('/api/indicators/signals').subscribe({
      next: r => { if (r.success) this.signals = r.data || []; },
      error: () => {}
    });
  }

  loadSupported(): void {
    this.http.get<any>('/api/indicators/supported').subscribe({
      next: r => {
        if (r.success && r.data) {
          this.categories = r.data.categories || {};
          this.categoryKeys = Object.keys(this.categories);
          this.totalIndicators = r.data.total_indicators || 0;
          if (r.data.timeframes) this.timeframes = r.data.timeframes;
        }
      },
      error: () => {}
    });
  }

  loadSavedSettings(): void {
    this.http.get<any>('/api/indicators/settings').subscribe({
      next: r => {
        if (r.success && r.data) {
          for (const saved of r.data) {
            const match = this.defaultSettings.find(s => s.key === saved.key);
            if (match) match.value = saved.value;
            if (saved.key === 'cache_ttl_minutes') this.cacheTtl = parseInt(saved.value) || 15;
            if (saved.key === 'max_batch_size') this.maxBatchSize = parseInt(saved.value) || 50;
          }
        }
      },
      error: () => {}
    });
  }

  filteredIndicators(category: string): any[] {
    const list = this.categories[category] || [];
    if (!this.searchTerm) return list;
    const term = this.searchTerm.toLowerCase();
    return list.filter((i: any) => i.name.toLowerCase().includes(term) || i.label.toLowerCase().includes(term));
  }

  computeIndicators(): void {
    if (!this.testSymbol.trim()) return;
    this.computing = true;
    this.computeError = '';
    this.computeResult = null;
    this.computeResultKeys = [];
    this.http.get<any>(`/api/indicators/compute/${this.testSymbol.toUpperCase()}`, {
      params: { exchange: this.testExchange, timeframe: this.testTimeframe }
    }).subscribe({
      next: r => {
        this.computing = false;
        if (r.success) {
          this.computeResult = r.data;
          this.computeResultKeys = Object.keys(r.data);
        } else {
          this.computeError = r.message || 'Computation failed';
        }
      },
      error: err => {
        this.computing = false;
        this.computeError = err.error?.message || 'Failed to compute indicators';
      }
    });
  }

  clearExpiredCache(): void {
    this.http.delete<any>('/api/indicators/cache/expired').subscribe({
      next: r => {
        this.cacheMsg = `Cleared ${r.data?.cleared || 0} expired entries`;
        this.loadStats();
        setTimeout(() => this.cacheMsg = '', 3000);
      }
    });
  }

  clearAllCache(): void {
    this.http.delete<any>('/api/indicators/cache').subscribe({
      next: r => {
        this.cacheMsg = `Cleared ${r.data?.cleared || 0} cache entries`;
        this.loadStats();
        setTimeout(() => this.cacheMsg = '', 3000);
      }
    });
  }

  saveAllSettings(): void {
    const requests: any[] = [];
    for (const s of this.defaultSettings) {
      requests.push(this.http.post('/api/indicators/settings', {
        key: s.key, value: s.value, description: s.label
      }).toPromise());
    }
    requests.push(this.http.post('/api/indicators/settings', {
      key: 'cache_ttl_minutes', value: String(this.cacheTtl), description: 'Cache TTL in minutes'
    }).toPromise());
    requests.push(this.http.post('/api/indicators/settings', {
      key: 'max_batch_size', value: String(this.maxBatchSize), description: 'Maximum batch compute size'
    }).toPromise());

    Promise.all(requests).then(() => {
      this.settingsMsg = 'All settings saved successfully';
      setTimeout(() => this.settingsMsg = '', 3000);
    });
  }

  resetDefaults(): void {
    const defaults: Record<string, string> = {
      rsi_period: '14', sma_short: '20', sma_long: '50', sma_trend: '200',
      ema_fast: '9', ema_medium: '20', ema_slow: '50',
      macd_fast: '12', macd_slow: '26', macd_signal: '9',
      bb_period: '20', bb_std: '2.0', atr_period: '14', adx_period: '14',
      stoch_k: '14', stoch_d: '3', supertrend_period: '7', supertrend_mult: '3.0',
      volume_avg_period: '20', volume_spike_threshold: '2.0', cci_period: '20', mfi_period: '14'
    };
    for (const s of this.defaultSettings) {
      s.value = defaults[s.key] || s.value;
    }
    this.cacheTtl = 15;
    this.maxBatchSize = 50;
  }

  getValueColor(key: string, value: any): string {
    if (typeof value === 'boolean') return value ? 'text-emerald-400' : 'text-red-400';
    if (typeof value === 'string') {
      if (['BULLISH', 'BUY', 'STRONG_BUY', 'GOLDEN_CROSS', 'BULLISH_CROSSOVER'].includes(value)) return 'text-emerald-400';
      if (['BEARISH', 'SELL', 'STRONG_SELL', 'DEATH_CROSS', 'BEARISH_CROSSOVER'].includes(value)) return 'text-red-400';
      if (['OVERBOUGHT'].includes(value)) return 'text-orange-400';
      if (['OVERSOLD'].includes(value)) return 'text-cyan-400';
      return 'text-slate-300';
    }
    return 'text-slate-200';
  }

  formatValue(val: any): string {
    if (val === null || val === undefined) return '-';
    if (typeof val === 'boolean') return val ? 'TRUE' : 'FALSE';
    if (typeof val === 'number') return val % 1 === 0 ? val.toString() : val.toFixed(2);
    return String(val);
  }
}
