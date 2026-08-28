import { Component, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';

// ── Types ─────────────────────────────────────────────────────────────────────
interface ScoreBreakdown {
  label: string;
  raw: number;
  weight: number;
  weighted: number;
}

interface RankedStock {
  rank: number;
  symbol: string;
  sector: string;
  cmp: number;
  trade_type: string;
  signal: string;
  entry_price: number;
  stop_loss: number;
  target: number;
  rr_ratio: number;
  composite_score: number;
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
  backtest_win_rate: number;
  passes_logic: boolean;
  reasons: string[];
  score_breakdown: Record<string, ScoreBreakdown>;
}

interface RankingSummary {
  total_ranked: number;
  high_priority: number;
  medium_priority: number;
  low_priority: number;
  avg_score: number;
  top_score: number;
  top_symbol: string;
  by_sector: Record<string, number>;
}

interface WeightConfig {
  key: string;
  label: string;
  description: string;
  weight: number;
  default: number;
  min: number;
  max: number;
}

@Component({
  selector: 'app-stock-ranking',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
<div class="p-4 md:p-6">

  <!-- SEBI Disclaimer -->
  <div class="bg-amber-900/20 border border-amber-700/40 rounded-lg px-4 py-2 mb-5 text-xs text-amber-300">
    ⚠ TradeRakshak™ Stock Ranking is for EDUCATIONAL purposes only. Not SEBI-registered investment advice. Past performance does not guarantee future results.
  </div>

  <!-- Header -->
  <div class="flex flex-wrap items-start justify-between gap-3 mb-5">
    <div>
      <h1 class="text-2xl font-bold text-white flex items-center gap-2">
        🏆 Stock Ranking Engine
      </h1>
      <p class="text-slate-400 text-sm mt-0.5">
        Multi-parameter scoring · 11 weighted factors · Strategy-based logic filter
      </p>
    </div>
    <div class="flex gap-2 flex-wrap">
      @if (isAdmin()) {
        <button (click)="showWeightConfig.set(!showWeightConfig())"
          class="btn-secondary text-sm flex items-center gap-1.5">
          ⚙️ Configure Weights
        </button>
      }
      @if (lastRunId) {
        <button (click)="exportCsv()" class="btn-secondary text-sm flex items-center gap-1.5">
          📥 Export CSV
        </button>
        <button (click)="exportPdf()" class="btn-secondary text-sm flex items-center gap-1.5">
          📄 PDF
        </button>
      }
    </div>
  </div>

  <!-- ══════════════════════════════════════════════════════════════════════ -->
  <!-- ADMIN: WEIGHT CONFIGURATION PANEL                                    -->
  <!-- ══════════════════════════════════════════════════════════════════════ -->
  @if (showWeightConfig() && isAdmin()) {
    <div class="bg-card rounded-xl border border-blue-700/40 p-5 mb-5">
      <div class="flex items-center justify-between mb-4">
        <div>
          <h3 class="font-bold text-white">⚙️ Ranking Weight Configuration</h3>
          <p class="text-xs text-slate-400 mt-0.5">
            Adjust the contribution of each parameter. Changes apply immediately — no coding required.
            Total should equal 100.
          </p>
        </div>
        <div class="text-right">
          <div class="text-sm font-bold" [class]="weightTotal() === 100 ? 'text-green-400' : 'text-amber-400'">
            Total: {{ weightTotal() }}
          </div>
          <div class="text-xs text-slate-500">Target: 100</div>
        </div>
      </div>

      <div class="grid md:grid-cols-2 lg:grid-cols-3 gap-3 mb-4">
        @for (cfg of weightConfigs; track cfg.key) {
          <div class="bg-slate-800/50 rounded-lg p-3 border border-slate-700/40">
            <div class="flex items-center justify-between mb-1.5">
              <span class="text-sm font-medium text-white">{{ cfg.label }}</span>
              <span class="text-xs text-slate-500">default: {{ cfg.default }}</span>
            </div>
            <p class="text-xs text-slate-500 mb-2">{{ cfg.description }}</p>
            <div class="flex items-center gap-2">
              <input type="range" [min]="cfg.min" [max]="cfg.max" step="1"
                [(ngModel)]="cfg.weight"
                (ngModelChange)="onWeightChange()"
                class="flex-1 h-1.5 appearance-none rounded cursor-pointer accent-blue-500">
              <input type="number" [min]="cfg.min" [max]="cfg.max"
                [(ngModel)]="cfg.weight"
                (ngModelChange)="onWeightChange()"
                class="w-14 bg-slate-700 border border-slate-600 rounded px-2 py-1 text-xs text-white text-center">
            </div>
          </div>
        }
      </div>

      <div class="flex items-center gap-3">
        <button (click)="saveWeights()"
          [disabled]="weightTotal() < 90 || weightTotal() > 110 || savingWeights()"
          class="btn-primary text-sm"
          [class.opacity-50]="weightTotal() < 90 || weightTotal() > 110">
          {{ savingWeights() ? 'Saving…' : '💾 Save Weights' }}
        </button>
        <button (click)="resetWeights()" class="btn-secondary text-sm">
          ↺ Reset to Defaults
        </button>
        @if (weightTotal() < 90 || weightTotal() > 110) {
          <span class="text-amber-400 text-xs">
            ⚠ Total must be between 90–110 to save
          </span>
        }
        @if (weightSaveMsg) {
          <span class="text-sm" [class]="weightSaveSuccess ? 'text-green-400' : 'text-red-400'">
            {{ weightSaveMsg }}
          </span>
        }
      </div>
    </div>
  }

  <!-- ══════════════════════════════════════════════════════════════════════ -->
  <!-- RANKING CONTROLS                                                     -->
  <!-- ══════════════════════════════════════════════════════════════════════ -->
  <div class="bg-card rounded-xl border border-slate-700/60 p-5 mb-5">
    <h3 class="font-semibold text-white mb-4">🔧 Ranking Parameters</h3>

    <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3 mb-4">
      <!-- Strategy selector -->
      <div class="col-span-2 md:col-span-2">
        <label class="block text-xs text-slate-400 mb-1">Strategy (Logic Builder)</label>
        <select [(ngModel)]="selectedStrategyId" class="select-input w-full">
          <option [ngValue]="null">— Select Strategy —</option>
          @for (s of strategies; track s.id) {
            <option [ngValue]="s.id">{{ s.name }} ({{ s.category }})</option>
          }
        </select>
      </div>

      <!-- Sector filter -->
      <div>
        <label class="block text-xs text-slate-400 mb-1">Sector</label>
        <select [(ngModel)]="filters.sector" class="select-input w-full">
          <option value="ALL">All Sectors</option>
          @for (s of sectors; track s) {
            <option [value]="s">{{ s }}</option>
          }
        </select>
      </div>

      <!-- Trade type -->
      <div>
        <label class="block text-xs text-slate-400 mb-1">Trade Type</label>
        <select [(ngModel)]="filters.tradeType" class="select-input w-full">
          <option value="ALL">All Types</option>
          <option value="swing">Swing</option>
          <option value="intraday">Intraday</option>
          <option value="positional">Positional</option>
        </select>
      </div>

      <!-- Min confidence -->
      <div>
        <label class="block text-xs text-slate-400 mb-1">Min Score</label>
        <input type="number" [(ngModel)]="filters.minConfidence" min="0" max="100"
          class="select-input w-full" placeholder="0">
      </div>

      <!-- Risk level -->
      <div>
        <label class="block text-xs text-slate-400 mb-1">Risk Level</label>
        <select [(ngModel)]="filters.riskLevel" class="select-input w-full">
          <option value="ALL">All</option>
          <option value="LOW_RISK">Low Risk (High Priority only)</option>
        </select>
      </div>
    </div>

    <div class="flex flex-wrap items-center gap-3">
      <!-- Logic filter toggle -->
      <label class="flex items-center gap-2 cursor-pointer">
        <input type="checkbox" [(ngModel)]="filters.passesLogicOnly"
          class="w-4 h-4 accent-blue-500 rounded">
        <span class="text-sm text-slate-300">Show only stocks that pass strategy logic</span>
      </label>

      <!-- Sort control -->
      <div class="flex items-center gap-2 ml-auto">
        <span class="text-xs text-slate-400">Sort:</span>
        <select [(ngModel)]="sortOrder" class="select-input text-xs py-1">
          <option value="desc">Highest Score First</option>
          <option value="asc">Lowest Score First</option>
        </select>
      </div>

      <!-- Run Button -->
      <button (click)="runRanking()"
        [disabled]="!selectedStrategyId || running()"
        class="btn-primary flex items-center gap-2"
        [class.opacity-50]="!selectedStrategyId || running()">
        @if (running()) {
          <span class="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></span>
          Ranking…
        } @else {
          🚀 Run Ranking
        }
      </button>
    </div>
  </div>

  <!-- ══════════════════════════════════════════════════════════════════════ -->
  <!-- RESULTS                                                              -->
  <!-- ══════════════════════════════════════════════════════════════════════ -->
  @if (rankingResult) {

    <!-- Summary cards -->
    @let s = rankingResult.summary;
    <div class="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-7 gap-3 mb-5">
      <div class="stat-card">
        <div class="label">Scanned</div>
        <div class="value text-slate-300">{{ rankingResult.total_scanned }}</div>
      </div>
      <div class="stat-card">
        <div class="label">Ranked</div>
        <div class="value">{{ s.total_ranked }}</div>
      </div>
      <div class="stat-card border-green-700/50 bg-green-900/10">
        <div class="label">🔴 High Priority</div>
        <div class="value text-green-400">{{ s.high_priority }}</div>
      </div>
      <div class="stat-card border-yellow-700/50 bg-yellow-900/10">
        <div class="label">🟡 Medium</div>
        <div class="value text-yellow-400">{{ s.medium_priority }}</div>
      </div>
      <div class="stat-card">
        <div class="label">⚪ Low</div>
        <div class="value text-slate-400">{{ s.low_priority }}</div>
      </div>
      <div class="stat-card">
        <div class="label">Avg Score</div>
        <div class="value text-blue-400">{{ s.avg_score }}</div>
      </div>
      <div class="stat-card border-emerald-700/50 bg-emerald-900/10">
        <div class="label">🏆 Top Stock</div>
        <div class="value text-emerald-400 text-base">{{ s.top_symbol }}</div>
        <div class="text-xs text-slate-500 mt-0.5">Score: {{ s.top_score }}</div>
      </div>
    </div>

    <!-- Strategy + run info bar -->
    <div class="flex flex-wrap items-center gap-3 mb-3 text-xs text-slate-400">
      <span>Strategy: <span class="text-white font-medium">{{ rankingResult.strategy_name }}</span></span>
      <span>·</span>
      <span>Category: <span class="text-white">{{ rankingResult.strategy_category }}</span></span>
      <span>·</span>
      <span>Timeframe: <span class="text-white">{{ rankingResult.timeframe }}</span></span>
      <span>·</span>
      <span>Universe: <span class="text-white">{{ rankingResult.universe }}</span></span>
      <span>·</span>
      <span>Date: <span class="text-white">{{ rankingResult.scan_date }}</span></span>
      <span>·</span>
      <span>Time: <span class="text-white">{{ rankingResult.scan_time_ms }}ms</span></span>
    </div>

    <!-- View toggle -->
    <div class="flex items-center gap-2 mb-3">
      <div class="flex bg-slate-800/60 p-0.5 rounded-lg border border-slate-700/40">
        <button (click)="viewMode.set('table')"
          class="px-3 py-1.5 text-xs rounded-md transition-all"
          [class]="viewMode() === 'table' ? 'bg-blue-600 text-white' : 'text-slate-400 hover:text-white'">
          ☰ Table
        </button>
        <button (click)="viewMode.set('cards')"
          class="px-3 py-1.5 text-xs rounded-md transition-all"
          [class]="viewMode() === 'cards' ? 'bg-blue-600 text-white' : 'text-slate-400 hover:text-white'">
          ⊞ Cards
        </button>
        <button (click)="viewMode.set('scorecard')"
          class="px-3 py-1.5 text-xs rounded-md transition-all"
          [class]="viewMode() === 'scorecard' ? 'bg-blue-600 text-white' : 'text-slate-400 hover:text-white'">
          📊 Scorecard
        </button>
      </div>
      <span class="text-xs text-slate-500 ml-auto">
        {{ sortedResults().length }} stocks displayed
        @if (filters.passesLogicOnly) {
          <span class="text-blue-400 ml-1">(logic-filtered)</span>
        }
      </span>
    </div>

    <!-- ─────────────────────────────────────────────────────────────────── -->
    <!-- TABLE VIEW                                                         -->
    <!-- ─────────────────────────────────────────────────────────────────── -->
    @if (viewMode() === 'table') {
      <div class="bg-card rounded-xl border border-slate-700/60 overflow-hidden">
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="bg-slate-800/60 text-left text-xs text-slate-400 border-b border-slate-700/50">
                <th class="px-3 py-3 font-medium w-10">#</th>
                <th class="px-3 py-3 font-medium">Symbol</th>
                <th class="px-3 py-3 font-medium">Sector</th>
                <th class="px-3 py-3 font-medium text-right">CMP</th>
                <th class="px-3 py-3 font-medium">Type</th>
                <th class="px-3 py-3 font-medium">Signal</th>
                <th class="px-3 py-3 font-medium text-right">Entry</th>
                <th class="px-3 py-3 font-medium text-right">SL</th>
                <th class="px-3 py-3 font-medium text-right">Target</th>
                <th class="px-3 py-3 font-medium text-right">R:R</th>
                <th class="px-3 py-3 font-medium text-center">Score</th>
                <th class="px-3 py-3 font-medium text-center">Priority</th>
                <th class="px-3 py-3 font-medium text-right">Backtest WR%</th>
                <th class="px-3 py-3 font-medium w-8"></th>
              </tr>
            </thead>
            <tbody>
              @for (stock of sortedResults(); track stock.symbol) {
                <tr class="border-b border-slate-700/30 hover:bg-slate-800/30 transition-colors cursor-pointer"
                  (click)="toggleExpand(stock.symbol)">
                  <!-- Rank -->
                  <td class="px-3 py-3">
                    <span class="text-xs font-bold text-slate-400">{{ stock.rank }}</span>
                  </td>
                  <!-- Symbol -->
                  <td class="px-3 py-3">
                    <div class="flex items-center gap-1.5">
                      @if (stock.passes_logic) {
                        <span class="w-1.5 h-1.5 rounded-full bg-blue-400 flex-shrink-0" title="Passes strategy logic"></span>
                      }
                      <span class="font-semibold text-white">{{ stock.symbol }}</span>
                    </div>
                  </td>
                  <!-- Sector -->
                  <td class="px-3 py-3 text-slate-400 text-xs max-w-24 truncate">{{ stock.sector }}</td>
                  <!-- CMP -->
                  <td class="px-3 py-3 text-right font-mono text-white">₹{{ stock.cmp | number:'1.0-2' }}</td>
                  <!-- Trade type -->
                  <td class="px-3 py-3">
                    <span class="text-xs bg-slate-700/60 px-1.5 py-0.5 rounded text-slate-300 capitalize">
                      {{ stock.trade_type }}
                    </span>
                  </td>
                  <!-- Signal -->
                  <td class="px-3 py-3">
                    <span class="text-xs font-bold px-2 py-0.5 rounded-full" [class]="signalClass(stock.signal)">
                      {{ stock.signal }}
                    </span>
                  </td>
                  <!-- Entry -->
                  <td class="px-3 py-3 text-right font-mono text-xs text-slate-300">
                    ₹{{ stock.entry_price | number:'1.0-2' }}
                  </td>
                  <!-- SL -->
                  <td class="px-3 py-3 text-right font-mono text-xs text-red-400">
                    ₹{{ stock.stop_loss | number:'1.0-2' }}
                  </td>
                  <!-- Target -->
                  <td class="px-3 py-3 text-right font-mono text-xs text-green-400">
                    ₹{{ stock.target | number:'1.0-2' }}
                  </td>
                  <!-- R:R -->
                  <td class="px-3 py-3 text-right">
                    <span class="text-xs font-bold" [class]="rrClass(stock.rr_ratio)">
                      {{ stock.rr_ratio | number:'1.1-1' }}:1
                    </span>
                  </td>
                  <!-- Score -->
                  <td class="px-3 py-3 text-center">
                    <div class="flex flex-col items-center gap-0.5">
                      <span class="text-base font-bold" [class]="scoreClass(stock.composite_score)">
                        {{ stock.composite_score | number:'1.0-1' }}
                      </span>
                      <div class="w-14 bg-slate-700/50 rounded-full h-1">
                        <div class="h-1 rounded-full transition-all" [class]="scoreBarClass(stock.composite_score)"
                          [style.width.%]="stock.composite_score"></div>
                      </div>
                    </div>
                  </td>
                  <!-- Priority -->
                  <td class="px-3 py-3 text-center">
                    <span class="text-xs font-bold px-2 py-0.5 rounded" [class]="priorityClass(stock.priority)">
                      {{ stock.priority }}
                    </span>
                  </td>
                  <!-- WR% -->
                  <td class="px-3 py-3 text-right text-xs"
                    [class]="stock.backtest_win_rate >= 60 ? 'text-green-400' : stock.backtest_win_rate >= 50 ? 'text-yellow-400' : 'text-slate-500'">
                    {{ stock.backtest_win_rate > 0 ? (stock.backtest_win_rate | number:'1.0-1') + '%' : '—' }}
                  </td>
                  <!-- Expand arrow -->
                  <td class="px-3 py-3 text-center text-slate-500 text-xs">
                    {{ expandedSymbol() === stock.symbol ? '▲' : '▼' }}
                  </td>
                </tr>

                <!-- Expanded reason row -->
                @if (expandedSymbol() === stock.symbol) {
                  <tr class="bg-slate-800/40 border-b border-slate-700/30">
                    <td colspan="14" class="px-4 py-4">
                      <div class="grid md:grid-cols-2 gap-4">
                        <!-- Score breakdown -->
                        <div>
                          <div class="text-xs font-bold text-slate-300 mb-2 uppercase tracking-wider">
                            📊 Score Breakdown
                          </div>
                          <div class="space-y-1.5">
                            @for (entry of getBreakdownEntries(stock); track entry.key) {
                              <div class="flex items-center gap-2">
                                <span class="text-xs text-slate-400 w-36 truncate">{{ entry.value.label }}</span>
                                <div class="flex-1 bg-slate-700/50 rounded-full h-1.5">
                                  <div class="h-1.5 rounded-full bg-blue-500/70"
                                    [style.width.%]="(entry.value.raw / 10) * 100"></div>
                                </div>
                                <span class="text-xs text-slate-300 w-8 text-right font-mono">
                                  {{ entry.value.raw | number:'1.0-1' }}/10
                                </span>
                                <span class="text-xs text-slate-500 w-10 text-right">
                                  wt:{{ entry.value.weight }}
                                </span>
                              </div>
                            }
                          </div>
                        </div>
                        <!-- Reasons -->
                        <div>
                          <div class="text-xs font-bold text-slate-300 mb-2 uppercase tracking-wider">
                            💡 Why This Ranking
                          </div>
                          <div class="space-y-1">
                            @for (reason of stock.reasons; track reason) {
                              <div class="text-xs text-slate-400 flex items-start gap-1.5">
                                <span class="text-blue-400 flex-shrink-0 mt-0.5">•</span>
                                <span>{{ reason }}</span>
                              </div>
                            }
                          </div>
                        </div>
                      </div>
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>

        @if (sortedResults().length === 0) {
          <div class="text-center py-12 text-slate-400">
            <div class="text-3xl mb-3">🔍</div>
            <p>No stocks matched your filters.</p>
            <p class="text-xs mt-1">Try lowering minimum score or removing sector filter.</p>
          </div>
        }
      </div>
    }

    <!-- ─────────────────────────────────────────────────────────────────── -->
    <!-- CARDS VIEW                                                         -->
    <!-- ─────────────────────────────────────────────────────────────────── -->
    @if (viewMode() === 'cards') {
      <div class="grid sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-3">
        @for (stock of sortedResults().slice(0, 40); track stock.symbol) {
          <div class="bg-card rounded-xl border p-4 hover:border-slate-600 transition-colors cursor-pointer"
            [class]="cardBorderClass(stock.priority)"
            (click)="toggleExpand(stock.symbol)">

            <!-- Top row: rank + symbol + priority -->
            <div class="flex items-start justify-between mb-3">
              <div class="flex items-center gap-2">
                <span class="text-xs font-bold text-slate-500 bg-slate-800 px-1.5 py-0.5 rounded">
                  #{{ stock.rank }}
                </span>
                <div>
                  <div class="font-bold text-white">{{ stock.symbol }}</div>
                  <div class="text-xs text-slate-500 truncate max-w-24">{{ stock.sector }}</div>
                </div>
              </div>
              <div class="flex flex-col items-end gap-1">
                <span class="text-xs font-bold px-2 py-0.5 rounded" [class]="priorityClass(stock.priority)">
                  {{ stock.priority }}
                </span>
                <span class="text-xs px-1.5 py-0.5 rounded-full" [class]="signalClass(stock.signal)">
                  {{ stock.signal }}
                </span>
              </div>
            </div>

            <!-- Score circle + price -->
            <div class="flex items-center gap-3 mb-3">
              <div class="relative w-14 h-14 flex-shrink-0">
                <svg class="w-14 h-14 -rotate-90" viewBox="0 0 56 56">
                  <circle cx="28" cy="28" r="24" fill="none" stroke-width="4" class="stroke-slate-700/60"/>
                  <circle cx="28" cy="28" r="24" fill="none" stroke-width="4"
                    [attr.stroke]="scoreStroke(stock.composite_score)"
                    [attr.stroke-dasharray]="'150.8'"
                    [attr.stroke-dashoffset]="150.8 - (stock.composite_score / 100 * 150.8)"
                    stroke-linecap="round"/>
                </svg>
                <div class="absolute inset-0 flex items-center justify-center">
                  <span class="text-xs font-bold" [class]="scoreClass(stock.composite_score)">
                    {{ stock.composite_score | number:'1.0-0' }}
                  </span>
                </div>
              </div>
              <div>
                <div class="text-lg font-bold text-white font-mono">₹{{ stock.cmp | number:'1.0-0' }}</div>
                <div class="text-xs text-slate-400 capitalize">{{ stock.trade_type }}</div>
                <div class="text-xs text-blue-400">WR: {{ stock.backtest_win_rate > 0 ? (stock.backtest_win_rate | number:'1.0-0') + '%' : 'N/A' }}</div>
              </div>
            </div>

            <!-- Trade levels -->
            <div class="grid grid-cols-3 gap-1 text-center mb-3">
              <div class="bg-slate-800/50 rounded p-1.5">
                <div class="text-xs text-slate-500">Entry</div>
                <div class="text-xs font-mono text-slate-300">₹{{ stock.entry_price | number:'1.0-0' }}</div>
              </div>
              <div class="bg-red-900/20 rounded p-1.5">
                <div class="text-xs text-red-500">SL</div>
                <div class="text-xs font-mono text-red-400">₹{{ stock.stop_loss | number:'1.0-0' }}</div>
              </div>
              <div class="bg-green-900/20 rounded p-1.5">
                <div class="text-xs text-green-500">Target</div>
                <div class="text-xs font-mono text-green-400">₹{{ stock.target | number:'1.0-0' }}</div>
              </div>
            </div>

            <!-- R:R and logic badge -->
            <div class="flex items-center justify-between text-xs">
              <span [class]="rrClass(stock.rr_ratio)">R:R {{ stock.rr_ratio | number:'1.1-1' }}:1</span>
              @if (stock.passes_logic) {
                <span class="text-blue-400 bg-blue-900/20 px-1.5 py-0.5 rounded">✓ Logic</span>
              }
            </div>

            <!-- Expanded reasons -->
            @if (expandedSymbol() === stock.symbol) {
              <div class="mt-3 pt-3 border-t border-slate-700/40 space-y-1">
                @for (reason of stock.reasons.slice(0, 4); track reason) {
                  <p class="text-xs text-slate-400">• {{ reason }}</p>
                }
              </div>
            }
          </div>
        }
      </div>
    }

    <!-- ─────────────────────────────────────────────────────────────────── -->
    <!-- SCORECARD VIEW — parameter heatmap                                -->
    <!-- ─────────────────────────────────────────────────────────────────── -->
    @if (viewMode() === 'scorecard') {
      <div class="bg-card rounded-xl border border-slate-700/60 overflow-hidden">
        <div class="overflow-x-auto">
          <table class="text-xs w-full">
            <thead>
              <tr class="bg-slate-800/60 border-b border-slate-700/50">
                <th class="px-3 py-2.5 text-left text-slate-400 sticky left-0 bg-slate-800/80 z-10 w-28">#  Symbol</th>
                <th class="px-2 py-2.5 text-center text-slate-400 min-w-16">Score</th>
                @for (key of paramKeys; track key) {
                  <th class="px-2 py-2.5 text-center text-slate-500 min-w-14">
                    {{ paramShort(key) }}
                  </th>
                }
                <th class="px-2 py-2.5 text-center text-slate-400 min-w-12">Priority</th>
                <th class="px-2 py-2.5 text-center text-slate-400 min-w-12">WR%</th>
              </tr>
            </thead>
            <tbody>
              @for (stock of sortedResults().slice(0,30); track stock.symbol) {
                <tr class="border-b border-slate-700/20 hover:bg-slate-800/20">
                  <td class="px-3 py-2 sticky left-0 bg-[#0B1E3D] z-10 border-r border-slate-700/30">
                    <span class="text-slate-500 mr-1">{{ stock.rank }}.</span>
                    <span class="font-semibold text-white">{{ stock.symbol }}</span>
                  </td>
                  <td class="px-2 py-2 text-center font-bold" [class]="scoreClass(stock.composite_score)">
                    {{ stock.composite_score | number:'1.0-0' }}
                  </td>
                  @for (key of paramKeys; track key) {
                    <td class="px-1 py-2 text-center">
                      @let rawScore = getParamScore(stock, key);
                      <span class="inline-block w-8 py-0.5 rounded text-center text-xs font-mono"
                        [style.background-color]="heatmapBg(rawScore)"
                        [style.color]="rawScore >= 7 ? '#fff' : rawScore >= 4 ? '#cbd5e1' : '#94a3b8'">
                        {{ rawScore | number:'1.0-0' }}
                      </span>
                    </td>
                  }
                  <td class="px-2 py-2 text-center">
                    <span class="font-bold" [class]="priorityClass(stock.priority)">
                      {{ stock.priority === 'HIGH' ? '🔴' : stock.priority === 'MEDIUM' ? '🟡' : '⚪' }}
                    </span>
                  </td>
                  <td class="px-2 py-2 text-center"
                    [class]="stock.backtest_win_rate >= 60 ? 'text-green-400' : 'text-slate-500'">
                    {{ stock.backtest_win_rate > 0 ? (stock.backtest_win_rate | number:'1.0-0') + '%' : '—' }}
                  </td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr class="border-t border-slate-700/50 bg-slate-800/30">
                <td class="px-3 py-2 sticky left-0 bg-slate-800/60 text-xs text-slate-500 font-medium" colspan="2">
                  Heat: 🟢 High · 🟡 Mid · ⚫ Low
                </td>
                @for (key of paramKeys; track key) {
                  <td class="px-2 py-2 text-center text-xs text-slate-600">
                    wt:{{ getParamWeight(key) }}
                  </td>
                }
                <td colspan="2"></td>
              </tr>
            </tfoot>
          </table>
        </div>
      </div>

      <!-- Scorecard legend -->
      <div class="mt-2 flex flex-wrap gap-4 text-xs text-slate-500 px-1">
        @for (key of paramKeys; track key) {
          <span class="flex items-center gap-1">
            <span class="font-bold text-slate-400">{{ paramShort(key) }}</span>
            = {{ paramLabel(key) }}
          </span>
        }
      </div>
    }

  } @else if (!running() && !rankingResult) {
    <!-- Empty state -->
    <div class="bg-card rounded-xl border border-slate-700/60 py-16 text-center">
      <div class="text-5xl mb-4">🏆</div>
      <h3 class="text-white font-semibold mb-2">Ready to Rank Stocks</h3>
      <p class="text-slate-400 text-sm mb-4">
        Select a strategy from the Logic Builder and click Run Ranking.<br>
        The engine will score all stocks across 11 parameters and rank the best trade opportunities.
      </p>
      @if (strategies.length === 0) {
        <a routerLink="/strategy-builder" class="btn-primary text-sm inline-flex items-center gap-2">
          ⚙️ Create a Strategy First
        </a>
      }
    </div>
  }

</div>
  `,
  styles: [`
    .stat-card  { @apply bg-card rounded-xl border border-slate-700 p-3; }
    .stat-card .label { @apply text-xs text-slate-400 mb-1; }
    .stat-card .value { @apply text-xl font-bold text-white; }
    .btn-primary   { @apply bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded-lg font-medium transition-colors; }
    .btn-secondary { @apply bg-slate-700 hover:bg-slate-600 text-white px-3 py-2 rounded-lg font-medium transition-colors text-sm; }
    .select-input  { @apply bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-blue-500; }
  `]
})
export class StockRankingComponent implements OnInit {

  // ── State ─────────────────────────────────────────────────────────────────
  running        = signal(false);
  showWeightConfig = signal(false);
  savingWeights  = signal(false);
  expandedSymbol = signal<string | null>(null);
  viewMode       = signal<'table' | 'cards' | 'scorecard'>('table');

  strategies:    any[]   = [];
  sectors:       string[]= [];
  weightConfigs: WeightConfig[] = [];
  rankingResult: any     = null;
  lastRunId:     string  = '';

  selectedStrategyId: number | null = null;
  sortOrder = 'desc';
  weightSaveMsg     = '';
  weightSaveSuccess = false;

  filters = {
    sector:          'ALL',
    tradeType:       'ALL',
    minConfidence:   0,
    riskLevel:       'ALL',
    passesLogicOnly: false
  };

  readonly paramKeys = [
    'price_action','volume_strength','delivery_pct','rsi','ema_trend',
    'breakout','sr_proximity','risk_reward','backtest_win_rate',
    'sector_strength','market_trend'
  ];

  private readonly paramMeta: Record<string, { short: string; label: string }> = {
    price_action:      { short: 'PA',   label: 'Price Action' },
    volume_strength:   { short: 'VOL',  label: 'Volume Strength' },
    delivery_pct:      { short: 'DEL',  label: 'Delivery%' },
    rsi:               { short: 'RSI',  label: 'RSI(14)' },
    ema_trend:         { short: 'EMA',  label: 'EMA Trend' },
    breakout:          { short: 'BO',   label: 'Breakout' },
    sr_proximity:      { short: 'S/R',  label: 'S&R Proximity' },
    risk_reward:       { short: 'RR',   label: 'Risk-Reward' },
    backtest_win_rate: { short: 'WR',   label: 'Backtest WR%' },
    sector_strength:   { short: 'SEC',  label: 'Sector Strength' },
    market_trend:      { short: 'MKT',  label: 'Market Trend' }
  };

  constructor(private api: ApiService, public auth: AuthService) {}

  ngOnInit() {
    this.loadStrategies();
    this.loadSectors();
    this.loadWeights();
  }

  // ── API calls ────────────────────────────────────────────────────────────

  private loadStrategies() {
    this.api.get<any>('/ranking/strategies').subscribe({
      next: r => this.strategies = r.data || [],
      error: () => {}
    });
  }

  private loadSectors() {
    this.api.get<any>('/ranking/sectors').subscribe({
      next: r => this.sectors = r.data || [],
      error: () => {}
    });
  }

  private loadWeights() {
    this.api.get<any>('/ranking/weights').subscribe({
      next: r => {
        const params = r.data?.parameters || [];
        this.weightConfigs = params.map((p: any) => ({ ...p }));
      },
      error: () => {}
    });
  }

  runRanking() {
    if (!this.selectedStrategyId) return;
    this.running.set(true);
    this.rankingResult = null;

    const body = {
      sector:          this.filters.sector === 'ALL' ? null : this.filters.sector,
      tradeType:       this.filters.tradeType === 'ALL' ? null : this.filters.tradeType,
      minConfidence:   this.filters.minConfidence || 0,
      riskLevel:       this.filters.riskLevel,
      passesLogicOnly: this.filters.passesLogicOnly,
      maxResults:      200
    };

    this.api.post<any>(`/ranking/run/${this.selectedStrategyId}`, body).subscribe({
      next: r => {
        this.rankingResult = r.data;
        this.lastRunId     = r.data?.run_id || '';
        this.running.set(false);
      },
      error: () => this.running.set(false)
    });
  }

  saveWeights() {
    if (this.weightTotal() < 90 || this.weightTotal() > 110) return;
    this.savingWeights.set(true);
    this.weightSaveMsg = '';

    const payload: Record<string, number> = {};
    this.weightConfigs.forEach(c => payload[c.key] = c.weight);

    this.api.put<any>('/ranking/weights', payload).subscribe({
      next: r => {
        this.weightSaveSuccess = true;
        this.weightSaveMsg = '✓ Weights saved successfully!';
        this.savingWeights.set(false);
        setTimeout(() => this.weightSaveMsg = '', 3000);
      },
      error: () => {
        this.weightSaveSuccess = false;
        this.weightSaveMsg = 'Failed to save weights.';
        this.savingWeights.set(false);
      }
    });
  }

  resetWeights() {
    this.weightConfigs.forEach(c => c.weight = c.default);
    this.onWeightChange();
  }

  exportCsv() {
    if (!this.lastRunId) return;
    window.open(`/api/ranking/export/${this.lastRunId}`, '_blank');
  }

  exportPdf() {
    if (!this.rankingResult) return;
    // Build simple printable HTML
    const rows = this.sortedResults();
    const html = `
      <html><head><title>TradeRakshak Stock Ranking</title>
      <style>body{font-family:Arial,sans-serif;font-size:11px}
      table{border-collapse:collapse;width:100%}
      th,td{border:1px solid #ddd;padding:4px 6px}th{background:#1e293b;color:#fff}
      .high{color:green}.med{color:orange}.low{color:#999}
      </style></head><body>
      <h2>TradeRakshak™ Stock Ranking — ${this.rankingResult.strategy_name}</h2>
      <p>Date: ${this.rankingResult.scan_date} | Universe: ${this.rankingResult.universe}</p>
      <p style="font-size:10px;color:red">⚠ For educational purposes only. Not SEBI investment advice.</p>
      <table><tr>
        <th>#</th><th>Symbol</th><th>Sector</th><th>CMP</th><th>Signal</th>
        <th>Entry</th><th>SL</th><th>Target</th><th>R:R</th>
        <th>Score</th><th>Priority</th><th>WR%</th>
      </tr>
      ${rows.map(s => `<tr>
        <td>${s.rank}</td><td><b>${s.symbol}</b></td><td>${s.sector}</td>
        <td>₹${s.cmp}</td><td>${s.signal}</td>
        <td>₹${s.entry_price}</td><td>₹${s.stop_loss}</td><td>₹${s.target}</td>
        <td>${s.rr_ratio}:1</td>
        <td class="${s.priority==='HIGH'?'high':s.priority==='MEDIUM'?'med':'low'}">${s.composite_score}</td>
        <td>${s.priority}</td><td>${s.backtest_win_rate > 0 ? s.backtest_win_rate + '%' : '—'}</td>
      </tr>`).join('')}
      </table></body></html>`;

    const win = window.open('', '_blank')!;
    win.document.write(html);
    win.document.close();
    win.print();
  }

  // ── Computed ──────────────────────────────────────────────────────────────

  sortedResults(): RankedStock[] {
    if (!this.rankingResult?.results) return [];
    const items = [...this.rankingResult.results] as RankedStock[];
    return this.sortOrder === 'asc'
      ? items.sort((a, b) => a.composite_score - b.composite_score)
      : items.sort((a, b) => b.composite_score - a.composite_score);
  }

  weightTotal(): number {
    return this.weightConfigs.reduce((sum, c) => sum + (c.weight || 0), 0);
  }

  onWeightChange() { /* triggers angular change detection */ }

  isAdmin(): boolean {
    const role = this.auth.currentUser()?.role?.toLowerCase() || '';
    return role === 'admin' || role === 'super_admin';
  }

  toggleExpand(symbol: string) {
    this.expandedSymbol.set(this.expandedSymbol() === symbol ? null : symbol);
  }

  getBreakdownEntries(stock: RankedStock): { key: string; value: ScoreBreakdown }[] {
    if (!stock.score_breakdown) return [];
    return Object.entries(stock.score_breakdown).map(([key, value]) => ({ key, value }));
  }

  getParamScore(stock: RankedStock, key: string): number {
    return stock.score_breakdown?.[key]?.raw ?? 0;
  }

  getParamWeight(key: string): number {
    const cfg = this.weightConfigs.find(c => c.key === key);
    return cfg?.weight ?? 10;
  }

  paramShort(key: string): string { return this.paramMeta[key]?.short ?? key; }
  paramLabel(key: string): string { return this.paramMeta[key]?.label ?? key; }

  // ── Style helpers ─────────────────────────────────────────────────────────

  signalClass(signal: string): string {
    if (signal === 'STRONG BUY') return 'bg-emerald-900/50 text-emerald-300 border border-emerald-700/40';
    if (signal === 'BUY')        return 'bg-green-900/40 text-green-400 border border-green-700/40';
    if (signal === 'WATCH')      return 'bg-yellow-900/30 text-yellow-400 border border-yellow-700/30';
    return 'bg-slate-800/50 text-slate-400 border border-slate-700/40';
  }

  priorityClass(priority: string): string {
    if (priority === 'HIGH')   return 'bg-green-900/50 text-green-400 border border-green-700/40';
    if (priority === 'MEDIUM') return 'bg-yellow-900/40 text-yellow-400 border border-yellow-700/40';
    return 'bg-slate-800/40 text-slate-500 border border-slate-700/40';
  }

  scoreClass(score: number): string {
    if (score >= 72) return 'text-emerald-400';
    if (score >= 55) return 'text-green-400';
    if (score >= 40) return 'text-yellow-400';
    return 'text-slate-500';
  }

  scoreBarClass(score: number): string {
    if (score >= 72) return 'bg-emerald-500';
    if (score >= 55) return 'bg-green-500';
    if (score >= 40) return 'bg-yellow-500';
    return 'bg-slate-500';
  }

  scoreStroke(score: number): string {
    if (score >= 72) return '#10b981';
    if (score >= 55) return '#22c55e';
    if (score >= 40) return '#eab308';
    return '#64748b';
  }

  rrClass(rr: number): string {
    if (rr >= 3)   return 'text-emerald-400 font-bold';
    if (rr >= 2)   return 'text-green-400';
    if (rr >= 1.5) return 'text-yellow-400';
    return 'text-red-400';
  }

  cardBorderClass(priority: string): string {
    if (priority === 'HIGH')   return 'border-green-700/50 bg-green-900/5';
    if (priority === 'MEDIUM') return 'border-yellow-700/40 bg-yellow-900/5';
    return 'border-slate-700/40';
  }

  heatmapBg(score: number): string {
    // Green → Yellow → Dark based on score 0-10
    if (score >= 9)  return 'rgba(16,185,129,0.8)';
    if (score >= 7)  return 'rgba(34,197,94,0.6)';
    if (score >= 5)  return 'rgba(234,179,8,0.5)';
    if (score >= 3)  return 'rgba(239,68,68,0.3)';
    return 'rgba(100,116,139,0.25)';
  }
}
