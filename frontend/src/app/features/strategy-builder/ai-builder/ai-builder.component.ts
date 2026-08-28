import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { StrategyBuilderService } from '../../../core/services/strategy-builder.service';

const EXAMPLE_PROMPTS = [
  'I want a swing trading strategy with 60%+ win rate for Nifty 500 stocks',
  'Create an intraday momentum strategy for Bank Nifty using RSI and VWAP',
  'Build a sector rotation strategy targeting stocks in strong uptrend sectors',
  'Generate a breakout strategy with high volume confirmation for positional trading',
  'I want a trend-following strategy with EMA alignment and MACD confirmation',
  'Build the 3:15 PM next-day market direction and stock scoring engine',
];

@Component({
  selector: 'app-ai-builder',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
<div class="p-6 max-w-4xl mx-auto">
  <div class="flex items-center justify-between mb-6">
    <div>
      <h1 class="section-title">🤖 AI Strategy Generator</h1>
      <p class="text-slate-400 text-sm">Describe your goal in plain English. AI generates complete entry/exit/risk rules instantly.</p>
    </div>
  </div>

  <div class="bg-amber-900/20 border border-amber-700/40 rounded-lg px-4 py-2 mb-5 text-xs text-amber-300">
    ⚠ AI-generated strategies are for educational screening purposes only. NOT investment advice. Always backtest before use.
  </div>

  <!-- Input Panel -->
  <div class="card mb-5">
    <div class="card-header">💬 Describe Your Strategy</div>

    <!-- Example prompts -->
    <div class="flex flex-wrap gap-2 mb-4">
      @for (p of examples; track p) {
        <button (click)="prompt = p" class="text-xs px-3 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-full border border-slate-700 hover:border-blue-600 transition-colors">
          {{ p | slice:0:50 }}…
        </button>
      }
    </div>

    <textarea [(ngModel)]="prompt" rows="3" class="input text-sm mb-4 w-full"
      placeholder="e.g. I want a swing trading strategy with 60%+ win rate for Nifty 500 stocks focusing on sector rotation..."></textarea>

    <div class="grid grid-cols-2 sm:grid-cols-4 gap-4 mb-4">
      <div>
        <label class="label">Segment</label>
        <select [(ngModel)]="segment" class="input text-sm">
          <option value="EQ-SWG">Equity Swing</option>
          <option value="EQ-INT">Equity Intraday</option>
          <option value="FUT">Futures</option>
          <option value="OPT-CE">Options CE</option>
        </select>
      </div>
      <div>
        <label class="label">Category</label>
        <select [(ngModel)]="category" class="input text-sm">
          <option value="swing">Swing</option>
          <option value="intraday">Intraday</option>
          <option value="positional">Positional</option>
          <option value="options_buy">Options Buying</option>
        </select>
      </div>
      <div>
        <label class="label">Target Win Rate</label>
        <input [(ngModel)]="targetWinRate" type="number" step="5" class="input text-sm" placeholder="60" />
      </div>
      <div>
        <label class="label">Universe</label>
        <select [(ngModel)]="universe" class="input text-sm">
          <option value="NSE_500">NSE 500</option>
          <option value="NSE_50">NSE 50</option>
          <option value="ALL_CASH">All Cash</option>
          <option value="FNO_STOCKS">F&O Stocks</option>
        </select>
      </div>
    </div>

    <button (click)="generate()" [disabled]="generating() || !prompt"
      class="btn-gold px-8">
      @if (generating()) { 🤖 Generating… (AI is thinking) } @else { 🤖 Generate Strategy }
    </button>
  </div>

  <!-- Generated Result -->
  @if (result) {
    <div class="card mb-5 border-blue-700/40">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-bold text-white">✅ Generated: {{ result.strategyName }}</h2>
        <div class="flex gap-2">
          <button (click)="saveStrategy()" [disabled]="saving()" class="btn-primary text-sm">
            @if (saving()) { Saving… } @else { 💾 Save to My Strategies }
          </button>
        </div>
      </div>

      <div class="grid md:grid-cols-3 gap-4 mb-5">
        <div class="bg-slate-800 rounded-lg p-3">
          <div class="text-xs text-slate-400 mb-1">Category</div>
          <div class="font-semibold text-white capitalize">{{ result.category }}</div>
        </div>
        <div class="bg-slate-800 rounded-lg p-3">
          <div class="text-xs text-slate-400 mb-1">Timeframe</div>
          <div class="font-semibold text-white">{{ result.timeframe }}</div>
        </div>
        <div class="bg-slate-800 rounded-lg p-3">
          <div class="text-xs text-slate-400 mb-1">Estimated Win Rate</div>
          <div class="font-semibold text-green-400">{{ result.estimatedWinRate }}%</div>
        </div>
        <div class="bg-slate-800 rounded-lg p-3">
          <div class="text-xs text-slate-400 mb-1">Stop Loss</div>
          <div class="font-semibold text-red-400">{{ result.stopLossPct }}%</div>
        </div>
        <div class="bg-slate-800 rounded-lg p-3">
          <div class="text-xs text-slate-400 mb-1">Target</div>
          <div class="font-semibold text-green-400">{{ result.targetPct }}%</div>
        </div>
        <div class="bg-slate-800 rounded-lg p-3">
          <div class="text-xs text-slate-400 mb-1">Min R:R</div>
          <div class="font-semibold text-blue-400">{{ result.rrMin | number:'1.1-1' }}:1</div>
        </div>
      </div>

      @if (result.nextDayPredictionModel) {
        <div class="mb-5 bg-slate-900 border border-blue-700/40 rounded-lg p-4">
          <div class="flex flex-col md:flex-row md:items-start md:justify-between gap-3 mb-4">
            <div>
              <h3 class="text-base font-semibold text-white">3:15 PM Next-Day Prediction Model</h3>
              <p class="text-xs text-slate-400 mt-1">{{ result.nextDayPredictionModel.purpose }}</p>
            </div>
            <button (click)="runBlankNextDayPreview()" class="btn-primary text-sm">Run Preview</button>
          </div>

          <div class="grid md:grid-cols-4 gap-3 mb-4">
            <div class="bg-slate-800 rounded-lg p-3">
              <div class="text-xs text-slate-400 mb-1">Index Score</div>
              <div class="text-lg font-semibold text-white">{{ result.sampleRun?.index?.score ?? 0 }} / 8</div>
              <div class="text-xs text-blue-300">{{ result.sampleRun?.index?.classification || 'Neutral' }}</div>
            </div>
            <div class="bg-slate-800 rounded-lg p-3">
              <div class="text-xs text-slate-400 mb-1">Confidence</div>
              <div class="text-lg font-semibold text-white">{{ result.sampleRun?.index?.confidence ?? 0 }}%</div>
              <div class="text-xs text-slate-400">{{ result.sampleRun?.index?.confidenceBand || 'Low' }}</div>
            </div>
            <div class="bg-slate-800 rounded-lg p-3">
              <div class="text-xs text-slate-400 mb-1">Gap Up</div>
              <div class="text-lg font-semibold text-green-400">{{ result.sampleRun?.index?.gapUpProbability ?? 0 }}%</div>
            </div>
            <div class="bg-slate-800 rounded-lg p-3">
              <div class="text-xs text-slate-400 mb-1">Gap Down</div>
              <div class="text-lg font-semibold text-red-400">{{ result.sampleRun?.index?.gapDownProbability ?? 0 }}%</div>
            </div>
          </div>

          <div class="grid lg:grid-cols-2 gap-4">
            <div>
              <div class="text-sm font-semibold text-white mb-2">Index Scoring</div>
              <div class="space-y-2">
                @for (rule of result.nextDayPredictionModel.indexScoring; track rule.factor) {
                  <div class="bg-slate-800 rounded-lg p-3 text-xs">
                    <div class="flex items-center justify-between gap-2">
                      <span class="font-semibold text-white">{{ rule.factor }}</span>
                      <span class="text-blue-300">{{ rule.points }}</span>
                    </div>
                    <div class="text-slate-400 mt-1">{{ rule.rule }}</div>
                  </div>
                }
              </div>
            </div>
            <div>
              <div class="text-sm font-semibold text-white mb-2">Stock Scoring</div>
              <div class="space-y-2">
                @for (rule of result.nextDayPredictionModel.stockScoring; track rule.factor) {
                  <div class="bg-slate-800 rounded-lg p-3 text-xs flex items-center justify-between gap-2">
                    <span class="font-semibold text-white">{{ rule.factor }}</span>
                    <span class="text-blue-300">{{ rule.points }}</span>
                  </div>
                }
              </div>
            </div>
          </div>

          <div class="grid md:grid-cols-3 gap-3 mt-4">
            <div class="bg-slate-800 rounded-lg p-3 text-xs">
              <div class="font-semibold text-white mb-1">Backtesting</div>
              <div class="text-slate-400">250, 500, and 1250 trading days with monthly, yearly, sector, and stock accuracy.</div>
            </div>
            <div class="bg-slate-800 rounded-lg p-3 text-xs">
              <div class="font-semibold text-white mb-1">Admin View</div>
              <div class="text-slate-400">Top 20 stocks, strong buy/sell, confidence, heat map, distribution, and downloads.</div>
            </div>
            <div class="bg-slate-800 rounded-lg p-3 text-xs">
              <div class="font-semibold text-white mb-1">Data Status</div>
              <div class="text-amber-300">{{ result.sampleRun?.dataQuality?.message }}</div>
            </div>
          </div>
        </div>
      }

      <!-- Entry Rules -->
      <div class="mb-4">
        <h3 class="text-sm font-semibold text-white mb-2">🟢 Entry Rules</h3>
        <div class="bg-slate-900 rounded-lg p-4">
          @for (rule of result.entryRules; track rule; let i=$index) {
            <div class="flex items-start gap-2 mb-1.5 text-sm">
              <span class="text-green-400 mt-0.5 flex-shrink-0">{{ i+1 }}.</span>
              <span class="text-slate-300">{{ rule }}</span>
            </div>
          }
        </div>
      </div>

      <!-- Exit Rules -->
      <div class="mb-4">
        <h3 class="text-sm font-semibold text-white mb-2">🔴 Exit Rules</h3>
        <div class="bg-slate-900 rounded-lg p-4">
          @for (rule of result.exitRules; track rule; let i=$index) {
            <div class="flex items-start gap-2 mb-1.5 text-sm">
              <span class="text-red-400 mt-0.5 flex-shrink-0">{{ i+1 }}.</span>
              <span class="text-slate-300">{{ rule }}</span>
            </div>
          }
        </div>
      </div>

      <!-- Risk Management -->
      <div class="mb-4">
        <h3 class="text-sm font-semibold text-white mb-2">🛡️ Risk Management</h3>
        <div class="bg-slate-900 rounded-lg p-4 text-sm text-slate-300">
          Risk per trade: <strong class="text-white">{{ result.riskManagement?.riskPerTrade }}%</strong> ·
          Max positions: <strong class="text-white">{{ result.riskManagement?.maxPositions }}</strong> ·
          Daily loss limit: <strong class="text-white">{{ result.riskManagement?.dailyLossLimit }}%</strong>
        </div>
      </div>

      <!-- AI Explanation -->
      <div class="bg-blue-900/20 border border-blue-700/40 rounded-lg p-4 text-xs text-slate-400 whitespace-pre-wrap">
        {{ result.explanation }}
      </div>
    </div>

    @if (saved()) {
      <div class="bg-green-900/40 border border-green-700 text-green-300 rounded-lg px-4 py-3 text-sm">
        🎉 Strategy saved! Now you can run a scan or backtest from My Strategies.
      </div>
    }
  }
</div>
  `
})
export class AiBuilderComponent {
  prompt         = '';
  segment        = 'EQ-SWG';
  category       = 'swing';
  targetWinRate  = 60;
  universe       = 'NSE_500';
  generating     = signal(false);
  saving         = signal(false);
  saved          = signal(false);
  result: any    = null;
  examples       = EXAMPLE_PROMPTS;

  constructor(private svc: StrategyBuilderService, private router: Router) {}

  generate() {
    this.generating.set(true); this.result = null;
    this.svc.generateAi({ prompt: this.prompt, segment: this.segment, category: this.category,
                           targetWinRate: this.targetWinRate, universe: this.universe })
      .subscribe({
        next: r => { this.result = r.data; this.generating.set(false); },
        error: () => this.generating.set(false)
      });
  }

  runBlankNextDayPreview() {
    if (!this.result?.nextDayPredictionModel) return;
    this.generating.set(true);
    this.svc.runNextDayPrediction({ universe: this.universe }).subscribe({
      next: r => {
        this.result = { ...this.result, sampleRun: r.data };
        this.generating.set(false);
      },
      error: () => this.generating.set(false)
    });
  }

  saveStrategy() {
    if (!this.result) return;
    this.saving.set(true);
    this.svc.saveAiStrategy({ ...this.result, name: this.result.strategyName }).subscribe({
      next: () => { this.saved.set(true); this.saving.set(false); },
      error: () => this.saving.set(false)
    });
  }
}
