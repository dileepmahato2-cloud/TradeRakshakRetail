import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { LivePriceService } from '../../core/services/live-price.service';
import { RealtimeService } from '../../core/services/realtime.service';
import { InrPipe, PctPipe, PnlClassPipe } from '../../shared/pipes/inr.pipe';
import { Subscription } from 'rxjs';

// ── Types ─────────────────────────────────────────────────────────────────────
interface SRLevel {
  price: number;
  zone_type: 'support' | 'resistance';
  name: string;
  source: string;
  grade: 'VERY STRONG' | 'STRONG' | 'MODERATE' | 'WEAK';
  final_score: number;
  confluence: number;
  is_confluence: boolean;
  touch_count: number;
  dist_pct: number;
  dist_abs: number;
  proximity: string;
  is_active: boolean;
}

interface IndexSR {
  index: string;
  label: string;
  current_price: number;
  as_of_date: string;
  trend_bias: 'BULLISH' | 'BEARISH' | 'NEUTRAL';
  atr_14: number;
  supports: SRLevel[];
  resistances: SRLevel[];
  nearest_support: SRLevel | null;
  nearest_resistance: SRLevel | null;
  total_levels: number;
  error?: string;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, InrPipe, PctPipe, PnlClassPipe],
  template: `
<div class="p-4 md:p-6">
  <!-- SEBI Disclaimer Banner -->
  <div class="bg-amber-900/20 border border-amber-700/40 rounded-lg px-4 py-2 mb-5 text-xs text-amber-300 flex items-center gap-2">
    <span>⚠</span><span>{{ sebiDisclaimer }}</span>
  </div>

  <!-- Header -->
  <div class="flex items-center justify-between mb-5">
    <div>
      <h1 class="text-2xl font-bold text-white">Dashboard</h1>
      <p class="text-slate-400 text-sm">Welcome back, {{ user()?.name }}</p>
    </div>
    <div class="flex gap-2">
      <a routerLink="/data" class="btn-secondary text-sm">Import Data</a>
      <a routerLink="/strategy" class="btn-primary text-sm">+ Strategy</a>
    </div>
  </div>

  @if (loading()) {
    <div class="text-center text-slate-400 py-20">Loading dashboard…</div>
  } @else {

    <!-- Observation Gate -->
    @if (data?.gate) {
      <div class="bg-card rounded-xl border mb-5 p-5" [class]="gateColor(data.gate.gate_status)">
        <div class="flex items-center justify-between">
          <div>
            <div class="text-lg font-bold">
              🚦 Gate: {{ data.gate.gate_status }}
              <span class="text-2xl font-bold ml-2">{{ data.gate.score }}/100</span>
            </div>
            <p class="text-sm mt-1 opacity-80">{{ data.gate.recommendation }}</p>
          </div>
          <div class="text-right">
            <div class="text-sm opacity-70">Market Date: {{ data.market_date }}</div>
            <div class="text-sm opacity-70">{{ data.market_closed ? '🔴 Closed' : '🟢 Open' }}</div>
          </div>
        </div>
      </div>
    }

    <!-- Stats grid -->
    <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-5">
      <div class="stat-card"><div class="label">Stocks</div><div class="value">{{ data?.stats?.stocks | number }}</div></div>
      <div class="stat-card"><div class="label">Strategies</div><div class="value">{{ data?.stats?.strategies }}</div></div>
      <div class="stat-card"><div class="label">Open Trades</div><div class="value">{{ data?.stats?.paper_open }}</div></div>
      <div class="stat-card">
        <div class="label">Total P&amp;L</div>
        <div class="value" [ngClass]="data?.stats?.pnl | pnlClass">₹{{ data?.stats?.pnl | inr }}</div>
      </div>
      <div class="stat-card"><div class="label">Journal</div><div class="value">{{ data?.stats?.journal }}</div></div>
      <div class="stat-card"><div class="label">Watchlists</div><div class="value">{{ data?.stats?.watchlists }}</div></div>
      <div class="stat-card"><div class="label">Backtests</div><div class="value">{{ data?.stats?.backtests }}</div></div>
      <div class="stat-card"><div class="label">Screeners</div><div class="value">{{ data?.stats?.screeners }}</div></div>
    </div>

    <div class="grid grid-cols-1 xl:grid-cols-3 gap-4 mb-6">
      <div class="bg-card border border-slate-700/50 rounded-xl p-4">
        <div class="flex items-center justify-between mb-3">
          <h2 class="text-white font-bold">Today's High Impact Events</h2>
          <a routerLink="/events" class="text-xs text-blue-300 hover:text-blue-200">Open Calendar</a>
        </div>
        @if (eventData?.highImpact?.length) {
          <div class="space-y-2">
            @for (event of eventData.highImpact.slice(0, 4); track event.id) {
              <div class="rounded-lg border border-red-500/30 bg-red-500/10 p-3">
                <div class="text-sm font-semibold text-white">{{ event.eventTitle }}</div>
                <div class="text-xs text-slate-300 mt-1">{{ event.eventDate }} {{ event.eventTime || '' }} - {{ event.country }}</div>
              </div>
            }
          </div>
        } @else {
          <div class="text-sm text-slate-400 py-6 text-center">No high impact events found.</div>
        }
      </div>

      <div class="bg-card border border-slate-700/50 rounded-xl p-4">
        <div class="flex items-center justify-between mb-3">
          <h2 class="text-white font-bold">Upcoming 7 Days Events</h2>
          <span class="text-xs text-slate-400">{{ eventData?.upcoming7Days?.length || 0 }} events</span>
        </div>
        @if (eventData?.upcoming7Days?.length) {
          <div class="space-y-2">
            @for (event of eventData.upcoming7Days.slice(0, 4); track event.id) {
              <div class="rounded-lg border border-slate-700/50 bg-slate-900/40 p-3">
                <div class="text-sm font-semibold text-white">{{ event.eventTitle }}</div>
                <div class="text-xs text-slate-300 mt-1">{{ event.eventDate }} - {{ event.eventType }} - {{ event.impactLevel }}</div>
              </div>
            }
          </div>
        } @else {
          <div class="text-sm text-slate-400 py-6 text-center">No upcoming events found.</div>
        }
      </div>

      <div class="bg-card border border-slate-700/50 rounded-xl p-4">
        <div class="flex items-center justify-between mb-3">
          <h2 class="text-white font-bold">Watchlist Stock Events</h2>
          <span class="text-xs text-slate-400">{{ eventData?.watchlistEvents?.length || 0 }} matched</span>
        </div>
        @if (eventData?.watchlistEvents?.length) {
          <div class="space-y-2">
            @for (event of eventData.watchlistEvents.slice(0, 4); track event.id) {
              <div class="rounded-lg border border-emerald-500/30 bg-emerald-500/10 p-3">
                <div class="text-sm font-semibold text-white">{{ event.symbol || event.stockSymbol || 'Symbol Missing' }} - {{ event.eventTitle }}</div>
                <div class="text-xs text-slate-300 mt-1">{{ event.eventDate }} - {{ event.sector || event.country }}</div>
              </div>
            }
          </div>
        } @else {
          <div class="text-sm text-slate-400 py-6 text-center">No watchlist-linked events found.</div>
        }
      </div>
    </div>

    <!-- ═══════════════════════════════════════════════════════════════════════ -->
    <!-- SUPPORT & RESISTANCE WIDGET — SAHI APP LOGIC                          -->
    <!-- ═══════════════════════════════════════════════════════════════════════ -->
    <div class="mb-6">
      <!-- Widget Header -->
      <div class="flex flex-wrap items-center justify-between gap-3 mb-3">
        <div class="flex items-center gap-3">
          <h2 class="text-lg font-bold text-white">📊 Support &amp; Resistance</h2>
          <div class="flex items-center gap-1.5 text-xs text-slate-400 bg-slate-800/70 px-2.5 py-1 rounded-full border border-slate-700/40">
            <span class="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
            Sahi Logic · Pivot + Fib + Fractal + Volume
          </div>
        </div>

        <div class="flex items-center gap-2">
          <!-- View mode toggle -->
          <div class="flex bg-slate-800/60 p-0.5 rounded-lg border border-slate-700/40">
            <button (click)="srViewMode.set('ladder')"
              class="px-2.5 py-1 text-xs rounded-md transition-all"
              [class]="srViewMode() === 'ladder' ? 'bg-slate-600 text-white' : 'text-slate-400 hover:text-white'">
              ⚡ Ladder
            </button>
            <button (click)="srViewMode.set('cards')"
              class="px-2.5 py-1 text-xs rounded-md transition-all"
              [class]="srViewMode() === 'cards' ? 'bg-slate-600 text-white' : 'text-slate-400 hover:text-white'">
              ⊞ Cards
            </button>
            <button (click)="srViewMode.set('table')"
              class="px-2.5 py-1 text-xs rounded-md transition-all"
              [class]="srViewMode() === 'table' ? 'bg-slate-600 text-white' : 'text-slate-400 hover:text-white'">
              ☰ Table
            </button>
          </div>

          <!-- Index selector tabs -->
          <div class="flex gap-0.5 bg-slate-800/60 p-0.5 rounded-lg border border-slate-700/40">
            @for (key of srIndexKeys; track key) {
              <button (click)="selectedIndex.set(key)"
                class="px-3 py-1.5 text-xs font-medium rounded-md transition-all duration-150"
                [class]="selectedIndex() === key
                  ? 'bg-blue-600 text-white shadow-sm'
                  : 'text-slate-400 hover:text-white hover:bg-slate-700'">
                {{ srLabel(key) }}
              </button>
            }
          </div>
        </div>
      </div>

      <!-- ─── ALL-4 SUMMARY STRIP (always visible) ─── -->
      @if (srData) {
        <div class="grid grid-cols-2 md:grid-cols-4 gap-2 mb-3">
          @for (key of srIndexKeys; track key) {
            @if (srData[key] && !srData[key].error) {
              @let ix = srData[key];
              <button (click)="selectedIndex.set(key)"
                class="relative rounded-xl border p-3 text-left transition-all duration-150 overflow-hidden"
                [class]="selectedIndex() === key
                  ? 'border-blue-500/50 bg-blue-900/20 ring-1 ring-blue-500/30'
                  : 'border-slate-700/50 bg-slate-800/40 hover:border-slate-600/60 hover:bg-slate-800/60'">

                <!-- Trend color bar top -->
                <div class="absolute top-0 left-0 right-0 h-0.5 rounded-t-xl"
                  [class]="ix.trend_bias === 'BULLISH' ? 'bg-green-500' : ix.trend_bias === 'BEARISH' ? 'bg-red-500' : 'bg-yellow-500'">
                </div>

                <div class="flex items-start justify-between mb-1.5">
                  <div>
                    <div class="text-xs font-bold text-white">{{ ix.label }}</div>
                    <div class="text-xs text-slate-400">₹{{ ix.current_price | number:'1.0-2' }}</div>
                  </div>
                  <span class="text-xs px-1.5 py-0.5 rounded font-bold flex-shrink-0"
                    [class]="trendBadge(ix.trend_bias)">
                    {{ trendArrow(ix.trend_bias) }}
                  </span>
                </div>

                <!-- Mini S/R strip: resistance above, support below -->
                <div class="space-y-0.5">
                  @if (ix.nearest_resistance) {
                    <div class="flex items-center gap-1.5">
                      <div class="w-1 h-3 rounded-full flex-shrink-0" [class]="gradeBar(ix.nearest_resistance.grade)"></div>
                      <span class="text-xs font-mono text-red-300">₹{{ ix.nearest_resistance.price | number:'1.0-0' }}</span>
                      <span class="text-xs text-slate-500 truncate">+{{ ix.nearest_resistance.dist_pct | number:'1.1-1' }}%</span>
                      @if (ix.nearest_resistance.is_confluence) {
                        <span class="text-amber-400 text-xs">⚡</span>
                      }
                    </div>
                  }
                  <!-- CMP indicator -->
                  <div class="flex items-center gap-1.5">
                    <div class="w-1 h-2 rounded-full bg-blue-400 flex-shrink-0"></div>
                    <div class="flex-1 border-t border-blue-400/40 border-dashed"></div>
                    <span class="text-xs text-blue-400 font-bold">CMP</span>
                  </div>
                  @if (ix.nearest_support) {
                    <div class="flex items-center gap-1.5">
                      <div class="w-1 h-3 rounded-full flex-shrink-0" [class]="gradeBar(ix.nearest_support.grade)"></div>
                      <span class="text-xs font-mono text-green-300">₹{{ ix.nearest_support.price | number:'1.0-0' }}</span>
                      <span class="text-xs text-slate-500 truncate">−{{ ix.nearest_support.dist_pct | number:'1.1-1' }}%</span>
                      @if (ix.nearest_support.is_confluence) {
                        <span class="text-amber-400 text-xs">⚡</span>
                      }
                    </div>
                  }
                </div>

                <!-- Active levels count -->
                <div class="mt-2 text-xs text-slate-500">
                  {{ ix.total_levels }} levels · ATR {{ ix.atr_14 | number:'1.0-0' }}
                </div>
              </button>
            } @else {
              <div class="rounded-xl border border-slate-700/30 bg-slate-800/20 p-3 text-xs text-slate-500 flex items-center justify-center">
                {{ srLabel(key) }}: No data
              </div>
            }
          }
        </div>
      }

      <!-- ─── MAIN DETAIL PANEL ─── -->
      @if (srData && currentSR()) {
        @let sr = currentSR()!;

        @if (sr.error) {
          <!-- Error / No-data state -->
          <div class="bg-card rounded-xl border border-slate-700 p-8 text-center">
            <div class="text-3xl mb-3">📭</div>
            <p class="text-slate-300 font-medium mb-1">No market data for {{ sr.label }}</p>
            <p class="text-xs text-slate-500 mb-4">{{ sr.error }}</p>
            <a routerLink="/data"
              class="inline-flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white text-sm px-4 py-2 rounded-lg transition-colors">
              📥 Import Index Data
            </a>
          </div>

        } @else {
          <!-- Main SR Card -->
          <div class="bg-card rounded-xl border border-slate-700/60 overflow-hidden">

            <!-- ── Index Header Bar ── -->
            <div class="px-5 py-3.5 border-b border-slate-700/50 flex flex-wrap items-center justify-between gap-3"
              [class]="trendBg(sr.trend_bias)">
              <div class="flex items-center gap-4 flex-wrap">
                <div>
                  <span class="text-white font-bold text-lg">{{ sr.label }}</span>
                  <span class="text-slate-400 text-xs ml-2">{{ sr.as_of_date }}</span>
                </div>
                <div class="text-2xl font-bold text-white font-mono">
                  ₹{{ sr.current_price | number:'1.0-2' }}
                </div>
                <span class="px-2.5 py-1 rounded-full text-xs font-bold"
                  [class]="trendBadge(sr.trend_bias)">
                  {{ trendArrow(sr.trend_bias) }} {{ sr.trend_bias }}
                </span>
              </div>
              <div class="flex items-center gap-4 text-xs text-slate-400">
                <div>ATR(14): <span class="text-white font-semibold">{{ sr.atr_14 | number:'1.0-2' }}</span></div>
                <div>Levels: <span class="text-white font-semibold">{{ sr.total_levels }}</span></div>
                <div>Supports: <span class="text-green-400 font-semibold">{{ sr.supports.length }}</span></div>
                <div>Resistance: <span class="text-red-400 font-semibold">{{ sr.resistances.length }}</span></div>
              </div>
            </div>

            <!-- ── Nearest S&R Spotlight (Sahi-style highlight) ── -->
            <div class="grid grid-cols-1 md:grid-cols-2 divide-y md:divide-y-0 md:divide-x divide-slate-700/40 bg-slate-800/20">
              <!-- Nearest Resistance -->
              <div class="px-5 py-4">
                <div class="text-xs text-slate-400 mb-2 font-medium uppercase tracking-wider">
                  🔴 Nearest Resistance
                </div>
                @if (sr.nearest_resistance) {
                  @let r = sr.nearest_resistance;
                  <div class="flex items-center gap-3 flex-wrap">
                    <span class="text-red-300 font-bold text-xl font-mono">
                      ₹{{ r.price | number:'1.0-2' }}
                    </span>
                    <span class="text-xs px-2 py-0.5 rounded-full border font-medium"
                      [class]="gradeBadge(r.grade)">
                      {{ r.grade }}
                    </span>
                    <span class="text-xs text-slate-400 bg-slate-800/60 px-2 py-0.5 rounded">
                      +{{ r.dist_pct | number:'1.2-2' }}% away
                    </span>
                    <span class="text-xs font-bold px-2 py-0.5 rounded"
                      [class]="proximityBadge(r.proximity)">
                      {{ r.proximity }}
                    </span>
                  </div>
                  <div class="flex items-center gap-2 mt-1.5 text-xs text-slate-500">
                    <span>{{ r.name }}</span>
                    @if (r.is_confluence) {
                      <span class="text-amber-400 font-medium">⚡ Confluence ×{{ r.confluence }}</span>
                    }
                    <span>· {{ r.touch_count }} touches</span>
                  </div>
                  <!-- Score bar -->
                  <div class="mt-2 flex items-center gap-2">
                    <div class="flex-1 bg-slate-700/50 rounded-full h-1.5 max-w-32">
                      <div class="h-1.5 rounded-full bg-red-500/80 transition-all"
                        [style.width.%]="(r.final_score / 20) * 100"></div>
                    </div>
                    <span class="text-xs text-slate-500">Score: {{ r.final_score }}/20</span>
                  </div>
                } @else {
                  <span class="text-slate-500 text-sm">No resistance above CMP</span>
                }
              </div>

              <!-- Nearest Support -->
              <div class="px-5 py-4">
                <div class="text-xs text-slate-400 mb-2 font-medium uppercase tracking-wider">
                  🟢 Nearest Support
                </div>
                @if (sr.nearest_support) {
                  @let s = sr.nearest_support;
                  <div class="flex items-center gap-3 flex-wrap">
                    <span class="text-green-300 font-bold text-xl font-mono">
                      ₹{{ s.price | number:'1.0-2' }}
                    </span>
                    <span class="text-xs px-2 py-0.5 rounded-full border font-medium"
                      [class]="gradeBadge(s.grade)">
                      {{ s.grade }}
                    </span>
                    <span class="text-xs text-slate-400 bg-slate-800/60 px-2 py-0.5 rounded">
                      −{{ s.dist_pct | number:'1.2-2' }}% away
                    </span>
                    <span class="text-xs font-bold px-2 py-0.5 rounded"
                      [class]="proximityBadge(s.proximity)">
                      {{ s.proximity }}
                    </span>
                  </div>
                  <div class="flex items-center gap-2 mt-1.5 text-xs text-slate-500">
                    <span>{{ s.name }}</span>
                    @if (s.is_confluence) {
                      <span class="text-amber-400 font-medium">⚡ Confluence ×{{ s.confluence }}</span>
                    }
                    <span>· {{ s.touch_count }} touches</span>
                  </div>
                  <div class="mt-2 flex items-center gap-2">
                    <div class="flex-1 bg-slate-700/50 rounded-full h-1.5 max-w-32">
                      <div class="h-1.5 rounded-full bg-green-500/80 transition-all"
                        [style.width.%]="(s.final_score / 20) * 100"></div>
                    </div>
                    <span class="text-xs text-slate-500">Score: {{ s.final_score }}/20</span>
                  </div>
                } @else {
                  <span class="text-slate-500 text-sm">No support below CMP</span>
                }
              </div>
            </div>

            <!-- ══════════════════════════════════════════════════════════════ -->
            <!-- VIEW: LADDER (default) — Sahi-style price ladder             -->
            <!-- ══════════════════════════════════════════════════════════════ -->
            @if (srViewMode() === 'ladder') {
              <div class="px-5 py-5">
                <!-- Range bar — visual price zone -->
                @if (sr.nearest_resistance && sr.nearest_support) {
                  <div class="mb-5">
                    <div class="flex justify-between text-xs text-slate-500 mb-1">
                      <span>🟢 {{ sr.nearest_support.price | number:'1.0-0' }}</span>
                      <span class="text-blue-400 font-bold">CMP {{ sr.current_price | number:'1.0-0' }}</span>
                      <span>🔴 {{ sr.nearest_resistance.price | number:'1.0-0' }}</span>
                    </div>
                    <div class="relative h-3 bg-slate-700/50 rounded-full overflow-hidden">
                      <!-- Support zone (green left portion) -->
                      <div class="absolute left-0 top-0 h-full rounded-l-full bg-green-500/25"
                        [style.width.%]="srRangePosition(sr, 'support')"></div>
                      <!-- Resistance zone (red right portion) -->
                      <div class="absolute right-0 top-0 h-full rounded-r-full bg-red-500/25"
                        [style.width.%]="100 - srRangePosition(sr, 'resistance')"></div>
                      <!-- CMP marker -->
                      <div class="absolute top-0 h-full w-0.5 bg-blue-400"
                        [style.left.%]="srCmpPosition(sr)"></div>
                    </div>
                    <div class="flex justify-between text-xs text-slate-600 mt-1">
                      <span>−{{ sr.nearest_support.dist_pct | number:'1.1-1' }}%</span>
                      <span class="text-slate-400">Price Zone</span>
                      <span>+{{ sr.nearest_resistance.dist_pct | number:'1.1-1' }}%</span>
                    </div>
                  </div>
                }

                <!-- Ladder: Resistances → CMP → Supports -->
                <div class="space-y-1">
                  <!-- Up to 6 Resistances (ascending from CMP — farthest first) -->
                  @for (lvl of sr.resistances.slice(0, 6).reverse(); track lvl.price) {
                    <div class="flex items-center gap-2 group rounded-lg px-2 py-1 hover:bg-slate-800/40 transition-colors"
                      [class]="lvl.is_active ? 'opacity-100' : 'opacity-55'">
                      <div class="w-1.5 h-6 rounded-full flex-shrink-0" [class]="gradeBar(lvl.grade)"></div>
                      <!-- Price -->
                      <span class="text-sm font-mono font-bold text-red-300 w-24 flex-shrink-0">
                        ₹{{ lvl.price | number:'1.0-2' }}
                      </span>
                      <!-- Name -->
                      <span class="text-xs text-slate-400 flex-1 truncate">
                        {{ lvl.name }}
                        @if (lvl.is_confluence) {
                          <span class="text-amber-400 ml-1 font-medium">⚡×{{ lvl.confluence }}</span>
                        }
                      </span>
                      <!-- Source chip -->
                      <span class="text-xs text-slate-600 hidden lg:block flex-shrink-0">{{ lvl.source }}</span>
                      <!-- Distance -->
                      <span class="text-xs text-slate-500 w-12 text-right flex-shrink-0">
                        +{{ lvl.dist_pct | number:'1.1-1' }}%
                      </span>
                      <!-- Proximity badge -->
                      <span class="text-xs font-bold px-1.5 py-0.5 rounded w-16 text-center flex-shrink-0 hidden md:block"
                        [class]="proximityBadge(lvl.proximity)">{{ lvl.proximity }}</span>
                      <!-- Grade badge -->
                      <span class="text-xs px-1.5 py-0.5 rounded border w-20 text-center flex-shrink-0 hidden md:block"
                        [class]="gradeBadge(lvl.grade)">{{ lvl.grade }}</span>
                      <!-- Score bar -->
                      <div class="w-12 bg-slate-700/50 rounded-full h-1 flex-shrink-0 hidden lg:block">
                        <div class="h-1 rounded-full bg-red-500/70"
                          [style.width.%]="(lvl.final_score / 20) * 100"></div>
                      </div>
                    </div>
                  }

                  <!-- CMP Separator Line -->
                  <div class="flex items-center gap-3 py-2 my-1">
                    <div class="w-1.5 h-6 rounded-full bg-blue-500 flex-shrink-0 shadow-lg shadow-blue-500/30"></div>
                    <div class="flex-1 relative border-t-2 border-blue-500/60 border-dashed">
                      <span class="absolute -top-3 left-2 bg-blue-600 text-white text-xs px-2.5 py-0.5 rounded-full font-bold shadow">
                        ₹{{ sr.current_price | number:'1.0-2' }} CMP
                      </span>
                    </div>
                    <span class="text-xs text-blue-400 font-medium hidden md:block">Current Market Price</span>
                  </div>

                  <!-- Up to 6 Supports (descending from CMP — nearest first) -->
                  @for (lvl of sr.supports.slice(0, 6); track lvl.price) {
                    <div class="flex items-center gap-2 group rounded-lg px-2 py-1 hover:bg-slate-800/40 transition-colors"
                      [class]="lvl.is_active ? 'opacity-100' : 'opacity-55'">
                      <div class="w-1.5 h-6 rounded-full flex-shrink-0" [class]="gradeBar(lvl.grade)"></div>
                      <span class="text-sm font-mono font-bold text-green-300 w-24 flex-shrink-0">
                        ₹{{ lvl.price | number:'1.0-2' }}
                      </span>
                      <span class="text-xs text-slate-400 flex-1 truncate">
                        {{ lvl.name }}
                        @if (lvl.is_confluence) {
                          <span class="text-amber-400 ml-1 font-medium">⚡×{{ lvl.confluence }}</span>
                        }
                      </span>
                      <span class="text-xs text-slate-600 hidden lg:block flex-shrink-0">{{ lvl.source }}</span>
                      <span class="text-xs text-slate-500 w-12 text-right flex-shrink-0">
                        −{{ lvl.dist_pct | number:'1.1-1' }}%
                      </span>
                      <span class="text-xs font-bold px-1.5 py-0.5 rounded w-16 text-center flex-shrink-0 hidden md:block"
                        [class]="proximityBadge(lvl.proximity)">{{ lvl.proximity }}</span>
                      <span class="text-xs px-1.5 py-0.5 rounded border w-20 text-center flex-shrink-0 hidden md:block"
                        [class]="gradeBadge(lvl.grade)">{{ lvl.grade }}</span>
                      <div class="w-12 bg-slate-700/50 rounded-full h-1 flex-shrink-0 hidden lg:block">
                        <div class="h-1 rounded-full bg-green-500/70"
                          [style.width.%]="(lvl.final_score / 20) * 100"></div>
                      </div>
                    </div>
                  }
                </div>
              </div>
            }

            <!-- ══════════════════════════════════════════════════════════════ -->
            <!-- VIEW: CARDS — grouped by source method                       -->
            <!-- ══════════════════════════════════════════════════════════════ -->
            @if (srViewMode() === 'cards') {
              <div class="px-5 py-5">
                <div class="grid md:grid-cols-2 gap-4">
                  @for (group of srGroupBySource(sr); track group.source) {
                    <div class="bg-slate-800/40 rounded-xl border border-slate-700/40 p-4">
                      <div class="flex items-center gap-2 mb-3">
                        <span class="text-sm font-bold text-white">{{ group.source }}</span>
                        <span class="text-xs bg-slate-700 px-1.5 py-0.5 rounded text-slate-400">
                          {{ group.levels.length }} levels
                        </span>
                      </div>
                      <div class="space-y-1.5">
                        @for (lvl of group.levels; track lvl.price) {
                          <div class="flex items-center gap-2">
                            <div class="w-1.5 h-4 rounded-full" [class]="gradeBar(lvl.grade)"></div>
                            <span class="text-xs font-mono font-bold w-20"
                              [class]="lvl.zone_type === 'support' ? 'text-green-300' : 'text-red-300'">
                              ₹{{ lvl.price | number:'1.0-2' }}
                            </span>
                            <span class="text-xs" [class]="lvl.zone_type === 'support' ? 'text-green-600' : 'text-red-600'">
                              {{ lvl.zone_type === 'support' ? '▼S' : '▲R' }}
                            </span>
                            <span class="text-xs text-slate-500 flex-1 truncate">{{ lvl.name }}</span>
                            @if (lvl.is_confluence) {
                              <span class="text-amber-400 text-xs">⚡</span>
                            }
                            <span class="text-xs" [class]="gradeText(lvl.grade)">{{ gradeShort(lvl.grade) }}</span>
                          </div>
                        }
                      </div>
                    </div>
                  }
                </div>
              </div>
            }

            <!-- ══════════════════════════════════════════════════════════════ -->
            <!-- VIEW: TABLE — full tabular detail                             -->
            <!-- ══════════════════════════════════════════════════════════════ -->
            @if (srViewMode() === 'table') {
              <div class="px-5 py-5">
                <div class="grid md:grid-cols-2 gap-4">
                  <!-- Resistances table -->
                  <div>
                    <div class="text-xs font-bold text-red-400 mb-2 uppercase tracking-wider flex items-center gap-2">
                      🔴 Resistances
                      <span class="text-slate-500 font-normal normal-case">{{ sr.resistances.length }} levels</span>
                    </div>
                    <div class="overflow-x-auto">
                      <table class="w-full text-xs">
                        <thead>
                          <tr class="text-slate-500 border-b border-slate-700/50 text-left">
                            <th class="pb-1.5 pr-2">Price</th>
                            <th class="pb-1.5 pr-2">Method</th>
                            <th class="pb-1.5 pr-2 text-right">Dist%</th>
                            <th class="pb-1.5 pr-2">Proximity</th>
                            <th class="pb-1.5 text-right">Grade</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (lvl of sr.resistances; track lvl.price) {
                            <tr class="border-b border-slate-700/20 hover:bg-slate-800/30 transition-colors"
                              [class]="lvl.is_active ? '' : 'opacity-40'">
                              <td class="py-1.5 pr-2 font-mono font-bold text-red-300">
                                ₹{{ lvl.price | number:'1.0-2' }}
                                @if (lvl.is_confluence) { <span class="text-amber-400">⚡</span> }
                              </td>
                              <td class="py-1.5 pr-2 text-slate-400 max-w-24 truncate">{{ lvl.name }}</td>
                              <td class="py-1.5 pr-2 text-right text-slate-400">+{{ lvl.dist_pct | number:'1.1-1' }}%</td>
                              <td class="py-1.5 pr-2">
                                <span class="px-1 py-0.5 rounded text-xs font-bold"
                                  [class]="proximityBadge(lvl.proximity)">{{ lvl.proximity }}</span>
                              </td>
                              <td class="py-1.5 text-right">
                                <span [class]="gradeText(lvl.grade)">{{ gradeShort(lvl.grade) }}</span>
                              </td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                  </div>

                  <!-- Supports table -->
                  <div>
                    <div class="text-xs font-bold text-green-400 mb-2 uppercase tracking-wider flex items-center gap-2">
                      🟢 Supports
                      <span class="text-slate-500 font-normal normal-case">{{ sr.supports.length }} levels</span>
                    </div>
                    <div class="overflow-x-auto">
                      <table class="w-full text-xs">
                        <thead>
                          <tr class="text-slate-500 border-b border-slate-700/50 text-left">
                            <th class="pb-1.5 pr-2">Price</th>
                            <th class="pb-1.5 pr-2">Method</th>
                            <th class="pb-1.5 pr-2 text-right">Dist%</th>
                            <th class="pb-1.5 pr-2">Proximity</th>
                            <th class="pb-1.5 text-right">Grade</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (lvl of sr.supports; track lvl.price) {
                            <tr class="border-b border-slate-700/20 hover:bg-slate-800/30 transition-colors"
                              [class]="lvl.is_active ? '' : 'opacity-40'">
                              <td class="py-1.5 pr-2 font-mono font-bold text-green-300">
                                ₹{{ lvl.price | number:'1.0-2' }}
                                @if (lvl.is_confluence) { <span class="text-amber-400">⚡</span> }
                              </td>
                              <td class="py-1.5 pr-2 text-slate-400 max-w-24 truncate">{{ lvl.name }}</td>
                              <td class="py-1.5 pr-2 text-right text-slate-400">−{{ lvl.dist_pct | number:'1.1-1' }}%</td>
                              <td class="py-1.5 pr-2">
                                <span class="px-1 py-0.5 rounded text-xs font-bold"
                                  [class]="proximityBadge(lvl.proximity)">{{ lvl.proximity }}</span>
                              </td>
                              <td class="py-1.5 text-right">
                                <span [class]="gradeText(lvl.grade)">{{ gradeShort(lvl.grade) }}</span>
                              </td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                  </div>
                </div>
              </div>
            }

            <!-- ── Legend + Footer ── -->
            <div class="border-t border-slate-700/40 px-5 py-3 flex flex-wrap items-center justify-between gap-2">
              <!-- Legend -->
              <div class="flex flex-wrap items-center gap-3 text-xs text-slate-500">
                <span class="flex items-center gap-1.5">
                  <span class="w-2.5 h-2.5 rounded-full bg-emerald-500 inline-block"></span>VERY STRONG
                </span>
                <span class="flex items-center gap-1.5">
                  <span class="w-2.5 h-2.5 rounded-full bg-green-500 inline-block"></span>STRONG
                </span>
                <span class="flex items-center gap-1.5">
                  <span class="w-2.5 h-2.5 rounded-full bg-yellow-500 inline-block"></span>MODERATE
                </span>
                <span class="flex items-center gap-1.5">
                  <span class="w-2.5 h-2.5 rounded-full bg-slate-500 inline-block"></span>WEAK
                </span>
                <span class="flex items-center gap-1.5 ml-1">
                  <span class="text-amber-400">⚡</span>Confluence
                </span>
                <span class="flex items-center gap-1.5">
                  <span class="text-blue-400 font-bold">—</span>CMP
                </span>
              </div>
              <!-- Methods used -->
              <div class="text-xs text-slate-600 hidden md:flex items-center gap-1">
                Methods: Pivot (D/W/M) · Fibonacci · Fractal · Volume Profile · Psychological
              </div>
            </div>

          </div><!-- /Main SR Card -->

        }
      } @else if (!srData) {
        <!-- Loading S&R -->
        <div class="bg-card rounded-xl border border-slate-700/60 p-8 text-center">
          <div class="text-slate-400 text-sm animate-pulse">Loading S&amp;R levels…</div>
        </div>
      }

    </div>
    <!-- ═══════════════════════════════════════════════════════════════════════ -->
    <!-- END SUPPORT & RESISTANCE WIDGET                                        -->
    <!-- ═══════════════════════════════════════════════════════════════════════ -->

    <!-- Market Mood + Sector Signals -->
    <div class="grid md:grid-cols-2 gap-5 mb-5">
      <div class="bg-card rounded-xl border border-slate-700 p-5">
        <h3 class="font-semibold text-white mb-3">Market Breadth</h3>
        @if (data?.market_mood) {
          <div class="flex items-center gap-4">
            <div class="text-4xl font-bold" [ngClass]="moodColor(data.market_mood.color)">
              {{ data.market_mood.score }}%
            </div>
            <div>
              <div class="font-medium text-white">{{ data.market_mood.label }}</div>
              <div class="text-sm text-slate-400">stocks positive today</div>
            </div>
          </div>
        }
      </div>

      <div class="bg-card rounded-xl border border-slate-700 p-5">
        <h3 class="font-semibold text-white mb-3">
          Top Sectors
          <a routerLink="/sector-intelligence" class="text-xs text-blue-400 ml-2">View all →</a>
        </h3>
        @for (s of (data?.sector_signals || []).slice(0,5); track s) {
          <div class="flex items-center justify-between py-1.5 border-b border-slate-700/50 last:border-0">
            <span class="text-sm text-slate-300">{{ s[0] }}</span>
            <div class="flex items-center gap-2">
              <div class="w-16 bg-slate-700 rounded-full h-1.5">
                <div class="h-1.5 rounded-full" [style.width.%]="s[1]"
                  [class]="s[1] >= 65 ? 'bg-green-500' : s[1] >= 45 ? 'bg-yellow-500' : 'bg-red-500'"></div>
              </div>
              <span class="text-xs" [class]="signalColor(s[3])">{{ s[3] }}</span>
            </div>
          </div>
        }
      </div>
    </div>

    <!-- Recent Scanner Results -->
    @if (data?.recent_scans?.length) {
      <div class="bg-card rounded-xl border border-slate-700 p-5">
        <h3 class="font-semibold text-white mb-3">
          Recent Signals
          <a routerLink="/strategy" class="text-xs text-blue-400 ml-2">Strategy Builder →</a>
        </h3>
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-slate-400 text-left">
                <th class="pb-2">Symbol</th><th class="pb-2">Sector</th>
                <th class="pb-2 text-right">CMP</th><th class="pb-2 text-right">SL</th>
                <th class="pb-2 text-right">Target</th><th class="pb-2 text-right">R:R</th>
                <th class="pb-2 text-right">Score</th>
              </tr>
            </thead>
            <tbody>
              @for (r of data.recent_scans; track r) {
                <tr class="border-t border-slate-700/40 hover:bg-slate-800/30 transition-colors">
                  <td class="py-2 font-medium text-white">{{ r[1] }}</td>
                  <td class="py-2 text-slate-400">{{ r[4] }}</td>
                  <td class="py-2 text-right">₹{{ r[5] | inr }}</td>
                  <td class="py-2 text-right text-red-400">₹{{ r[12] | inr }}</td>
                  <td class="py-2 text-right text-green-400">₹{{ r[13] | inr }}</td>
                  <td class="py-2 text-right">{{ r[14] }}:1</td>
                  <td class="py-2 text-right font-bold"
                    [class]="r[11] >= 70 ? 'text-green-400' : 'text-yellow-400'">
                    {{ r[11] }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    }
  }
</div>
  `,
  styles: [`
    .stat-card { @apply bg-card rounded-xl border border-slate-700 p-4; }
    .stat-card .label { @apply text-xs text-slate-400 mb-1; }
    .stat-card .value { @apply text-2xl font-bold text-white; }
    .btn-primary   { @apply bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded-lg font-medium transition-colors; }
    .btn-secondary { @apply bg-slate-700 hover:bg-slate-600 text-white px-4 py-2 rounded-lg font-medium transition-colors; }
  `]
})
export class DashboardComponent implements OnInit, OnDestroy {

  loading    = signal(true);
  data: any  = null;
  eventData: any = null;
  user       = this.auth.currentUser;
  sebiDisclaimer = '';

  // ── S&R State ─────────────────────────────────────────────────────────────
  srData: Record<string, IndexSR> | null = null;
  srIndexKeys = ['NIFTY50', 'BANKNIFTY', 'FINNIFTY', 'NIFTY500'];
  selectedIndex = signal<string>('NIFTY50');
  srViewMode    = signal<'ladder' | 'cards' | 'table'>('ladder');

  currentSR = computed<IndexSR | null>(() => {
    if (!this.srData) return null;
    return this.srData[this.selectedIndex()] ?? null;
  });

  private refreshTimer: any;
  private liveSub?: Subscription;
  private moduleSub?: Subscription;
  private lastLiveRefresh = 0;

  constructor(private api: ApiService, private auth: AuthService, private livePrices: LivePriceService, private realtime: RealtimeService) {}

  ngOnInit() {
    this.loadDashboard();
    this.livePrices.connect();
    this.liveSub = this.livePrices.quote$.subscribe(() => this.refreshFromLiveTick());
    this.moduleSub = this.realtime.on('dashboard').subscribe(() => this.loadDashboard());
    // Auto-refresh S&R every 5 minutes during market hours
    this.refreshTimer = setInterval(() => this.loadSR(), 5 * 60 * 1000);
  }

  ngOnDestroy() {
    if (this.refreshTimer) clearInterval(this.refreshTimer);
    this.liveSub?.unsubscribe();
    this.moduleSub?.unsubscribe();
    this.livePrices.release();
  }

  private refreshFromLiveTick() {
    const now = Date.now();
    if (now - this.lastLiveRefresh < 3000) return;
    this.lastLiveRefresh = now;
    this.loadDashboard();
  }

  private loadDashboard() {
    this.api.get<any>('/dashboard').subscribe({
      next: res => {
        this.data = res.data;
        this.sebiDisclaimer = res.disclaimer || '';
        if (res.data?.support_resistance) {
          this.srData = res.data.support_resistance;
        } else {
          this.loadSR();
        }
        this.loadEventsWidget();
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadSR();
        this.loadEventsWidget();
      }
    });
  }

  private loadEventsWidget() {
    this.api.get<any>('/events').subscribe({
      next: res => { this.eventData = res.data || null; },
      error: () => { this.eventData = null; }
    });
  }

  private loadSR() {
    this.api.get<any>('/sr/indices').subscribe({
      next: res => { if (res?.data) this.srData = res.data; },
      error: err => console.warn('S&R fetch failed:', err)
    });
  }

  // ── Label helpers ─────────────────────────────────────────────────────────

  srLabel(key: string): string {
    const m: Record<string, string> = {
      'NIFTY50': 'Nifty 50', 'BANKNIFTY': 'Bank Nifty',
      'FINNIFTY': 'Fin Nifty', 'NIFTY500': 'Nifty 500'
    };
    return m[key] ?? key;
  }

  // ── Visual range helpers (price zone bar) ─────────────────────────────────

  srCmpPosition(sr: IndexSR): number {
    if (!sr.nearest_support || !sr.nearest_resistance) return 50;
    const lo  = sr.nearest_support.price;
    const hi  = sr.nearest_resistance.price;
    const cmp = sr.current_price;
    if (hi === lo) return 50;
    return Math.min(95, Math.max(5, ((cmp - lo) / (hi - lo)) * 100));
  }

  srRangePosition(sr: IndexSR, side: 'support' | 'resistance'): number {
    return side === 'support' ? this.srCmpPosition(sr) : this.srCmpPosition(sr);
  }

  // ── Cards view: group levels by source method ─────────────────────────────

  srGroupBySource(sr: IndexSR): { source: string; levels: SRLevel[] }[] {
    const all = [...(sr.supports || []), ...(sr.resistances || [])];
    const map = new Map<string, SRLevel[]>();
    for (const lvl of all) {
      const src = lvl.source || 'Other';
      if (!map.has(src)) map.set(src, []);
      map.get(src)!.push(lvl);
    }
    return Array.from(map.entries())
      .map(([source, levels]) => ({
        source,
        levels: levels.sort((a, b) => b.price - a.price)
      }))
      .sort((a, b) => b.levels.length - a.levels.length);
  }

  // ── Trend helpers ─────────────────────────────────────────────────────────

  trendBg(bias: string): string {
    if (bias === 'BULLISH') return 'bg-green-900/20';
    if (bias === 'BEARISH') return 'bg-red-900/20';
    return 'bg-slate-800/30';
  }

  trendBadge(bias: string): string {
    if (bias === 'BULLISH') return 'bg-green-900/60 text-green-400 border border-green-700/50';
    if (bias === 'BEARISH') return 'bg-red-900/60 text-red-400 border border-red-700/50';
    return 'bg-slate-700/60 text-slate-400 border border-slate-600/50';
  }

  trendArrow(bias: string): string {
    if (bias === 'BULLISH') return '▲';
    if (bias === 'BEARISH') return '▼';
    return '▶';
  }

  // ── Grade helpers ─────────────────────────────────────────────────────────

  gradeBadge(grade: string): string {
    switch (grade) {
      case 'VERY STRONG': return 'bg-emerald-900/40 text-emerald-400 border-emerald-700/40';
      case 'STRONG':      return 'bg-green-900/40 text-green-400 border-green-700/40';
      case 'MODERATE':    return 'bg-yellow-900/40 text-yellow-400 border-yellow-700/40';
      default:            return 'bg-slate-800/40 text-slate-400 border-slate-600/40';
    }
  }

  gradeBar(grade: string): string {
    switch (grade) {
      case 'VERY STRONG': return 'bg-emerald-500 shadow-sm shadow-emerald-500/40';
      case 'STRONG':      return 'bg-green-500';
      case 'MODERATE':    return 'bg-yellow-500';
      default:            return 'bg-slate-500';
    }
  }

  gradeText(grade: string): string {
    switch (grade) {
      case 'VERY STRONG': return 'text-emerald-400 font-bold';
      case 'STRONG':      return 'text-green-400 font-medium';
      case 'MODERATE':    return 'text-yellow-400';
      default:            return 'text-slate-500';
    }
  }

  gradeShort(grade: string): string {
    switch (grade) {
      case 'VERY STRONG': return 'VS';
      case 'STRONG':      return 'S';
      case 'MODERATE':    return 'M';
      default:            return 'W';
    }
  }

  // ── Proximity badge ───────────────────────────────────────────────────────

  proximityBadge(proximity: string): string {
    switch (proximity) {
      case 'AT LEVEL':  return 'bg-orange-600/60 text-orange-200 animate-pulse';
      case 'VERY NEAR': return 'bg-red-900/40 text-red-300';
      case 'NEAR':      return 'bg-yellow-900/40 text-yellow-400';
      default:          return 'text-slate-500';
    }
  }

  // ── Dashboard helpers ─────────────────────────────────────────────────────

  gateColor(status: string) {
    return {
      'border-green-600 bg-green-900/20':   status === 'GREEN',
      'border-red-600 bg-red-900/20':       status === 'RED',
      'border-yellow-600 bg-yellow-900/20': status !== 'GREEN' && status !== 'RED'
    };
  }

  moodColor(color: string) {
    return {
      'text-green-400':  color === 'green',
      'text-red-400':    color === 'red',
      'text-yellow-400': color === 'yellow'
    };
  }

  signalColor(sig: string) {
    if (!sig) return 'text-gray-400';
    if (sig.includes('BUY')) return 'text-green-400';
    if (sig === 'AVOID' || sig === 'CAUTION') return 'text-red-400';
    return 'text-yellow-400';
  }
}
