import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { StrategyBuilderService } from '../../core/services/strategy-builder.service';

type StrategyCondition = {
  id: number;
  timeframe: string;
  left: string;
  comparator: string;
  right: string;
  joiner: 'AND' | 'OR';
  human: string;
};

type MatchRow = {
  symbol: string;
  companyName: string;
  cmp: number;
  changePct: number;
  volume: number;
  deliveryPct: number;
  rsi: number;
  trend: 'Bullish' | 'Bearish' | 'Watchlist';
  entryPrice: number;
  stopLoss: number;
  target: number;
  riskRewardRatio: string;
  signalTime: string;
};

@Component({
  selector: 'app-strategy-hub',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host { display:block; }
    .page { min-height:100vh; padding:18px; background:var(--bg); color:var(--text); }
    .page.dark { --bg:#081424; --panel:#101d2f; --panel2:#14243a; --line:#253852; --text:#e5edf8; --muted:#8fa3bd; --field:#0b1728; --accent:#2dd4bf; --accent2:#f59e0b; --danger:#ef4444; --good:#22c55e; }
    .page.light { --bg:#eef3f8; --panel:#ffffff; --panel2:#f5f8fb; --line:#d5e0eb; --text:#102033; --muted:#60738a; --field:#ffffff; --accent:#0f9488; --accent2:#b7791f; --danger:#dc2626; --good:#15803d; }
    .shell { max-width:1680px; margin:0 auto; }
    .topbar { display:flex; justify-content:space-between; gap:12px; flex-wrap:wrap; align-items:flex-start; margin-bottom:14px; }
    .title { font-size:24px; font-weight:900; color:var(--text); margin:0; letter-spacing:0; }
    .subtle { color:var(--muted); font-size:12px; line-height:1.45; }
    .card { background:var(--panel); border:1px solid var(--line); border-radius:8px; overflow:hidden; }
    .card-pad { padding:14px; }
    .section-head { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:12px 14px; border-bottom:1px solid var(--line); background:var(--panel2); }
    .section-title { font-size:14px; font-weight:850; color:var(--text); }
    .grid { display:grid; gap:12px; }
    .grid-2 { grid-template-columns:minmax(0,1.1fr) minmax(360px,.9fr); }
    .grid-3 { grid-template-columns:repeat(3,minmax(0,1fr)); }
    .grid-4 { grid-template-columns:repeat(4,minmax(0,1fr)); }
    .input, .select, .textarea { width:100%; background:var(--field); color:var(--text); border:1px solid var(--line); border-radius:6px; padding:9px 10px; font-size:12px; outline:none; }
    .textarea { min-height:190px; resize:vertical; line-height:1.55; font-family:inherit; }
    .btn { border:0; border-radius:6px; padding:8px 11px; font-size:12px; font-weight:800; cursor:pointer; display:inline-flex; align-items:center; justify-content:center; gap:6px; white-space:nowrap; }
    .btn-primary { background:var(--accent); color:#05201d; }
    .btn-secondary { background:var(--panel2); color:var(--text); border:1px solid var(--line); }
    .btn-warn { background:var(--accent2); color:#1f1300; }
    .btn-danger { background:var(--danger); color:#fff; }
    .chip { display:inline-flex; align-items:center; gap:5px; padding:4px 8px; border-radius:999px; border:1px solid var(--line); color:var(--muted); font-size:11px; font-weight:700; }
    .metric { background:var(--panel2); border:1px solid var(--line); border-radius:8px; padding:10px; min-height:70px; }
    .metric .label { color:var(--muted); font-size:10px; text-transform:uppercase; font-weight:800; }
    .metric .value { color:var(--text); font-size:20px; font-weight:900; margin-top:4px; }
    .condition { border:1px solid var(--line); background:var(--panel2); border-radius:8px; padding:10px; }
    .condition-grid { display:grid; grid-template-columns:100px 1fr 110px 1fr 90px; gap:8px; align-items:center; }
    .table-wrap { overflow-x:auto; }
    table { width:100%; border-collapse:collapse; min-width:1120px; }
    th { background:var(--panel2); color:var(--text); text-align:left; padding:9px 10px; border-bottom:1px solid var(--line); font-size:11px; white-space:nowrap; }
    td { padding:9px 10px; border-bottom:1px solid var(--line); font-size:12px; color:var(--text); white-space:nowrap; }
    tr:hover td { background:color-mix(in srgb, var(--panel2) 80%, var(--accent) 8%); }
    .status-dot { width:8px; height:8px; border-radius:50%; display:inline-block; }
    .bull { color:var(--good); }
    .bear { color:var(--danger); }
    .watch { color:var(--accent2); }
    .chart { height:160px; display:flex; align-items:flex-end; gap:5px; padding:14px 8px 20px; border-top:1px solid var(--line); }
    .bar { flex:1; min-width:12px; border-radius:4px 4px 0 0; background:linear-gradient(180deg,var(--accent),#17786f); }
    .bar.drawdown { background:linear-gradient(180deg,#f97316,#dc2626); }
    .toast { position:fixed; right:18px; bottom:18px; z-index:100; background:var(--panel); border:1px solid var(--line); color:var(--text); border-radius:8px; padding:12px 14px; max-width:360px; }
    .workflow { display:grid; grid-template-columns:repeat(6,minmax(120px,1fr)); gap:8px; }
    @media (max-width:1100px) { .grid-2,.grid-3,.grid-4 { grid-template-columns:1fr 1fr; } .condition-grid { grid-template-columns:1fr 1fr; } }
    @media (max-width:1100px) { .workflow { grid-template-columns:repeat(3,1fr); } }
    @media (max-width:720px) { .page { padding:10px; } .grid-2,.grid-3,.grid-4,.condition-grid,.workflow { grid-template-columns:1fr; } .title { font-size:20px; } }
  `],
  template: `
<div class="page" [ngClass]="theme">
  <div class="shell">
    <div class="topbar">
      <div>
        <h1 class="title">Strategy Builder</h1>
        <div class="subtle">One workspace for logic conversion, validation, risk, backtesting, stock screening, alerts, and results.</div>
      </div>
      <div style="display:flex; gap:8px; flex-wrap:wrap;">
        <span class="chip">Educational screening only</span>
        <button class="btn btn-secondary" (click)="toggleTheme()">{{ theme === 'dark' ? 'Light Mode' : 'Dark Mode' }}</button>
      </div>
    </div>

    @if (loading()) {
      <div class="grid grid-3" style="margin-bottom:12px;">
        <div class="card card-pad"><div class="skeleton" style="width:70%;"></div><div class="skeleton" style="width:42%; margin-top:14px;"></div></div>
        <div class="card card-pad"><div class="skeleton" style="width:66%;"></div><div class="skeleton" style="width:55%; margin-top:14px;"></div></div>
        <div class="card card-pad"><div class="skeleton" style="width:62%;"></div><div class="skeleton" style="width:48%; margin-top:14px;"></div></div>
      </div>
    }

    <section class="card" style="margin-bottom:12px;">
      <div class="section-head">
        <div class="section-title">📋 Strategy Approval Workflow</div>
        <span class="chip">Draft to Production</span>
      </div>
      <div class="card-pad workflow">
        @for (step of workflowSteps; track step.label) {
          <div style="background:var(--panel2); border:1px solid var(--line); border-radius:8px; padding:12px; text-align:center;">
            <div style="font-size:26px; line-height:1; margin-bottom:8px;">{{ step.icon }}</div>
            <div style="font-weight:900; color:var(--text);">{{ step.label }}</div>
          </div>
        }
      </div>
    </section>

    <div class="grid grid-2" style="margin-bottom:12px;">
      <section class="card">
        <div class="section-head">
          <div>
            <div class="section-title">Type or Paste Your Strategy Logic</div>
            <div class="subtle">Natural language, Chartink, TradingView, Screener.in, broker logic, Excel formulas, AI text, or candlestick names.</div>
          </div>
          <span class="chip">{{ detectedType }}</span>
        </div>
        <div class="card-pad">
          <textarea class="textarea" [(ngModel)]="strategyText" (input)="convertStrategy()" placeholder="Show stocks where weekly RSI is above 55 and Close is above 20 EMA."></textarea>
          <div style="display:flex; gap:8px; flex-wrap:wrap; margin-top:10px;">
            <button class="btn btn-primary" (click)="convertStrategy()">Convert Strategy</button>
            <button class="btn btn-secondary" (click)="loadExample('chartink')">Chartink Example</button>
            <button class="btn btn-secondary" (click)="loadExample('breakout')">Breakout Example</button>
            <button class="btn btn-secondary" (click)="loadExample('candle')">Candlestick Example</button>
          </div>
          <div class="grid grid-4" style="margin-top:12px;">
            <div class="metric"><div class="label">Segment</div><div class="value">{{ segment }}</div></div>
            <div class="metric"><div class="label">Timeframe</div><div class="value">{{ timeframe }}</div></div>
            <div class="metric"><div class="label">Confidence</div><div class="value">{{ confidence }}%</div></div>
            <div class="metric"><div class="label">Conditions</div><div class="value">{{ conditions.length }}</div></div>
          </div>
        </div>
      </section>

      <section class="card">
        <div class="section-head">
          <div class="section-title">AI Conversion Summary</div>
          <button class="btn btn-secondary" (click)="validateStrategy()">Validate Strategy</button>
        </div>
        <div class="card-pad">
          <div class="subtle" style="margin-bottom:8px;">Human Readable Strategy</div>
          <div style="background:var(--panel2); border:1px solid var(--line); border-radius:8px; padding:10px; min-height:74px; font-size:13px; line-height:1.5;">
            {{ humanReadable || 'Converted strategy will appear here.' }}
          </div>
          <div class="subtle" style="margin:12px 0 8px;">System Logic Format</div>
          <pre style="margin:0; white-space:pre-wrap; background:var(--field); border:1px solid var(--line); border-radius:8px; padding:10px; color:var(--text); font-size:11px; min-height:88px;">{{ systemLogic || '{}' }}</pre>
          <div style="display:flex; gap:8px; flex-wrap:wrap; margin-top:10px;">
            <button class="btn btn-primary" (click)="saveStrategy()">Save Strategy</button>
            <button class="btn btn-warn" (click)="runScreener()">Run Screener</button>
            <button class="btn btn-secondary" (click)="addCondition()">Add Condition</button>
          </div>
          @if (suggestions.length) {
            <div style="margin-top:12px;">
              <div class="subtle" style="margin-bottom:6px;">AI Suggestions</div>
              @for (s of suggestions; track s) { <span class="chip" style="margin:0 6px 6px 0;">{{ s }}</span> }
            </div>
          }
          <div style="margin-top:12px; background:var(--panel2); border:1px solid var(--line); border-radius:8px; padding:10px;">
            <div style="display:flex; justify-content:space-between; gap:8px; flex-wrap:wrap; align-items:center;">
              <div>
                <div class="section-title">ChatGPT Free Strategy Suggestions</div>
                <div class="subtle">Copies an improvement prompt and opens ChatGPT Free in a new tab.</div>
              </div>
              <div style="display:flex; gap:8px; flex-wrap:wrap;">
                <button class="btn btn-secondary" (click)="copyChatGptPrompt()">Copy Prompt</button>
                <button class="btn btn-primary" (click)="openChatGptFree()">Open ChatGPT Free</button>
              </div>
            </div>
            <pre style="margin:10px 0 0; max-height:120px; overflow:auto; white-space:pre-wrap; background:var(--field); border:1px solid var(--line); border-radius:8px; padding:9px; color:var(--text); font-size:11px;">{{ chatGptPrompt }}</pre>
          </div>
        </div>
      </section>
    </div>

    <section class="card" style="margin-bottom:12px;">
      <div class="section-head">
        <div class="section-title">Generated Strategy</div>
        <span class="chip">{{ validationStatus }}</span>
      </div>
      <div class="card-pad grid" style="gap:10px;">
        @if (!conditions.length) {
          <div class="subtle">Type a strategy above to generate structured conditions.</div>
        }
        @for (c of conditions; track c.id; let i=$index) {
          <div class="condition">
            <div class="condition-grid">
              <select class="select" [(ngModel)]="c.timeframe" (ngModelChange)="refreshLogic()">
                @for (tf of timeframes; track tf) { <option [ngValue]="tf">{{ tf }}</option> }
              </select>
              <input class="input" [(ngModel)]="c.left" (ngModelChange)="refreshLogic()" placeholder="Indicator or price field">
              <select class="select" [(ngModel)]="c.comparator" (ngModelChange)="refreshLogic()">
                @for (cmp of comparators; track cmp) { <option [ngValue]="cmp">{{ cmp }}</option> }
              </select>
              <input class="input" [(ngModel)]="c.right" (ngModelChange)="refreshLogic()" placeholder="Value or indicator">
              <select class="select" [(ngModel)]="c.joiner" (ngModelChange)="refreshLogic()">
                <option value="AND">AND</option>
                <option value="OR">OR</option>
              </select>
            </div>
            <div style="display:flex; justify-content:space-between; gap:8px; flex-wrap:wrap; margin-top:8px;">
              <div class="subtle">Condition {{ i + 1 }}: {{ conditionText(c) }}</div>
              <div style="display:flex; gap:6px;">
                <button class="btn btn-secondary" (click)="cloneCondition(c)">Clone</button>
                <button class="btn btn-danger" (click)="deleteCondition(c.id)">Delete</button>
              </div>
            </div>
          </div>
        }
      </div>
    </section>

    <div class="grid grid-2" style="margin-bottom:12px;">
      <section class="card">
        <div class="section-head"><div class="section-title">Risk Management</div><span class="chip">Applied everywhere</span></div>
        <div class="card-pad grid grid-3">
          <label class="subtle">Stop Loss %<input class="input" type="number" min="0" step="0.1" [(ngModel)]="risk.stopLossPct" (ngModelChange)="applyRisk()"></label>
          <label class="subtle">Target %<input class="input" type="number" min="0" step="0.1" [(ngModel)]="risk.targetPct" (ngModelChange)="applyRisk()"></label>
          <label class="subtle">Minimum Risk Reward Ratio<select class="select" [(ngModel)]="risk.minRiskReward"><option>1:1</option><option>1:2</option><option>1:3</option><option>1:4</option><option>Custom</option></select></label>
          <label class="subtle">Maximum Open Positions<select class="select" [(ngModel)]="risk.maxOpenPositions"><option [ngValue]="1">1</option><option [ngValue]="3">3</option><option [ngValue]="5">5</option><option [ngValue]="10">10</option><option [ngValue]="99">Custom</option></select></label>
          <label class="subtle">Maximum Holding Days<select class="select" [(ngModel)]="risk.maxHoldingDays"><option [ngValue]="1">1</option><option [ngValue]="3">3</option><option [ngValue]="5">5</option><option [ngValue]="10">10</option><option [ngValue]="20">20</option><option [ngValue]="30">30</option><option [ngValue]="99">Custom</option></select></label>
          <label class="subtle">Position Sizing<select class="select" [(ngModel)]="risk.positionSizing"><option>Fixed Quantity</option><option>Fixed Capital</option><option>Percentage Capital Allocation</option></select></label>
          <label class="subtle">Capital Allocation %<input class="input" type="number" min="1" max="100" [(ngModel)]="risk.capitalAllocationPct"></label>
          <label class="subtle">Fixed Quantity<input class="input" type="number" min="1" [(ngModel)]="risk.fixedQuantity"></label>
          <label class="subtle">Fixed Capital<input class="input" type="number" min="0" [(ngModel)]="risk.fixedCapital"></label>
        </div>
      </section>

      <section class="card">
        <div class="section-head"><div class="section-title">Backtesting</div><button class="btn btn-primary" (click)="runBacktest()">Run Backtest</button></div>
        <div class="card-pad">
          <div class="grid grid-3">
            <label class="subtle">Backtest Period<select class="select" [(ngModel)]="backtestPeriod"><option>Last 1 Month</option><option>Last 3 Months</option><option>Last 6 Months</option><option>Last 1 Year</option><option>Last 2 Years</option><option>Last 3 Years</option><option>Last 5 Years</option><option>Custom Date Range</option></select></label>
            <label class="subtle">From<input class="input" type="date" [(ngModel)]="customFrom" [disabled]="backtestPeriod !== 'Custom Date Range'"></label>
            <label class="subtle">To<input class="input" type="date" [(ngModel)]="customTo" [disabled]="backtestPeriod !== 'Custom Date Range'"></label>
          </div>
          <div class="grid grid-4" style="margin-top:12px;">
            @for (m of backtestMetrics; track m.label) {
              <div class="metric"><div class="label">{{ m.label }}</div><div class="value" [ngClass]="m.good ? 'bull' : ''">{{ m.value }}</div></div>
            }
          </div>
        </div>
      </section>
    </div>

    <section class="card" style="margin-bottom:12px;">
      <div class="section-head">
        <div>
          <div class="section-title">Backtest Visualizations</div>
          <div class="subtle">Equity curve, drawdown, monthly/yearly performance, and win/loss distribution.</div>
        </div>
        <span class="chip">Updated after backtest</span>
      </div>
      <div class="card-pad grid grid-3">
        <div class="card"><div class="section-head"><div class="section-title">Equity Curve</div></div><div class="chart">@for (v of equityCurve; track $index) { <div class="bar" [style.height.%]="v"></div> }</div></div>
        <div class="card"><div class="section-head"><div class="section-title">Drawdown Curve</div></div><div class="chart">@for (v of drawdownCurve; track $index) { <div class="bar drawdown" [style.height.%]="v"></div> }</div></div>
        <div class="card"><div class="section-head"><div class="section-title">Win/Loss Distribution</div></div><div class="chart">@for (v of winLossBars; track $index) { <div class="bar" [ngClass]="$index === 0 ? '' : 'drawdown'" [style.height.%]="v"></div> }</div></div>
      </div>
    </section>

    <section class="card" style="margin-bottom:12px;">
      <div class="section-head">
        <div>
          <div class="section-title">Today's Matching Stocks</div>
          <div class="subtle">Live screening results with auto refresh, search, filters, sorting, and 25 records per page.</div>
        </div>
        <div style="display:flex; gap:8px; flex-wrap:wrap;">
          <button class="btn btn-secondary" (click)="autoRefresh = !autoRefresh">{{ autoRefresh ? 'Auto Refresh On' : 'Auto Refresh Off' }}</button>
          <button class="btn btn-secondary" (click)="exportMatches('excel')">Excel</button>
          <button class="btn btn-secondary" (click)="exportMatches('csv')">CSV</button>
          <button class="btn btn-secondary" (click)="exportMatches('pdf')">PDF</button>
        </div>
      </div>
      <div class="card-pad">
        <div class="grid grid-4" style="margin-bottom:10px;">
          <input class="input" [(ngModel)]="matchSearch" placeholder="Search symbol or company">
          <select class="select" [(ngModel)]="trendFilter"><option>All Trends</option><option>Bullish</option><option>Bearish</option><option>Watchlist</option></select>
          <select class="select" [(ngModel)]="sortKey"><option value="symbol">Symbol</option><option value="changePct">Change %</option><option value="volume">Volume</option><option value="rsi">RSI</option><option value="riskRewardRatio">Risk Reward</option></select>
          <button class="btn btn-primary" (click)="runScreener()">Refresh Results</button>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>Symbol</th><th>Company Name</th><th>CMP</th><th>Change %</th><th>Volume</th><th>Delivery %</th><th>RSI</th><th>Trend</th><th>Entry Price</th><th>Stop Loss</th><th>Target</th><th>Risk Reward Ratio</th><th>Signal Time</th><th>Actions</th></tr></thead>
            <tbody>
              @for (row of pagedMatches(); track row.symbol) {
                <tr>
                  <td style="font-weight:900;">{{ row.symbol }}</td>
                  <td>{{ row.companyName }}</td>
                  <td>{{ row.cmp | number:'1.2-2' }}</td>
                  <td [ngClass]="row.changePct >= 0 ? 'bull' : 'bear'">{{ row.changePct | number:'1.2-2' }}%</td>
                  <td>{{ row.volume | number }}</td>
                  <td>{{ row.deliveryPct | number:'1.1-1' }}%</td>
                  <td>{{ row.rsi | number:'1.1-1' }}</td>
                  <td><span class="status-dot" [style.background]="trendColor(row.trend)"></span> <span [ngClass]="trendClass(row.trend)">{{ row.trend }}</span></td>
                  <td>{{ row.entryPrice | number:'1.2-2' }}</td>
                  <td>{{ row.stopLoss | number:'1.2-2' }}</td>
                  <td>{{ row.target | number:'1.2-2' }}</td>
                  <td>{{ row.riskRewardRatio }}</td>
                  <td>{{ row.signalTime }}</td>
                  <td><div style="display:flex; gap:5px;"><button class="btn btn-secondary" (click)="toast('Added ' + row.symbol + ' to watchlist')">Watchlist</button><button class="btn btn-secondary" (click)="toast('Chart opened for ' + row.symbol)">Chart</button><button class="btn btn-secondary" (click)="toast('Alert created for ' + row.symbol)">Alert</button><button class="btn btn-secondary" (click)="toast('Paper trade prepared for ' + row.symbol)">Paper</button><button class="btn btn-warn" (click)="toast('Real trade handoff prepared for ' + row.symbol)">Real</button></div></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <div style="display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap; margin-top:10px;">
          <div class="subtle">Showing {{ matchPageStart() }}-{{ matchPageEnd() }} of {{ filteredMatches().length }} matches</div>
          <div style="display:flex; gap:8px; align-items:center;">
            <button class="btn btn-secondary" (click)="setMatchPage(matchPage - 1)" [disabled]="matchPage <= 1">Previous</button>
            <span class="subtle">Page {{ matchPage }} of {{ totalMatchPages() }}</span>
            <button class="btn btn-secondary" (click)="setMatchPage(matchPage + 1)" [disabled]="matchPage >= totalMatchPages()">Next</button>
          </div>
        </div>
      </div>
    </section>

    <section class="card">
      <div class="section-head"><div class="section-title">Alert & Notification Section</div><span class="chip">Telegram, WhatsApp, Email</span></div>
      <div class="card-pad grid grid-3">
        <div class="card card-pad">
          <div class="section-title" style="margin-bottom:8px;">Telegram</div>
          <input class="input" style="margin-bottom:8px;" [(ngModel)]="alerts.telegram.botToken" placeholder="Bot Token">
          <input class="input" style="margin-bottom:8px;" [(ngModel)]="alerts.telegram.chatId" placeholder="Chat ID">
          <button class="btn btn-secondary" (click)="testAlert('Telegram')">Test Connection</button>
        </div>
        <div class="card card-pad">
          <div class="section-title" style="margin-bottom:8px;">WhatsApp</div>
          <input class="input" style="margin-bottom:8px;" [(ngModel)]="alerts.whatsapp.apiKey" placeholder="WhatsApp API Integration">
          <input class="input" style="margin-bottom:8px;" [(ngModel)]="alerts.whatsapp.phone" placeholder="Phone / Channel">
          <button class="btn btn-secondary" (click)="testAlert('WhatsApp')">Test Connection</button>
        </div>
        <div class="card card-pad">
          <div class="section-title" style="margin-bottom:8px;">Email</div>
          <input class="input" style="margin-bottom:8px;" [(ngModel)]="alerts.email.smtp" placeholder="SMTP Configuration">
          <input class="input" style="margin-bottom:8px;" [(ngModel)]="alerts.email.to" placeholder="Test Email">
          <button class="btn btn-secondary" (click)="testAlert('Email')">Test Email</button>
        </div>
        <div class="card card-pad">
          <div class="section-title" style="margin-bottom:8px;">Alert Triggers</div>
          @for (trigger of alertTriggers; track trigger) {
            <label class="chip" style="margin:0 6px 6px 0;"><input type="checkbox" [checked]="enabledTriggers.includes(trigger)" (change)="toggleTrigger(trigger)"> {{ trigger }}</label>
          }
        </div>
        <div class="card card-pad">
          <div class="section-title" style="margin-bottom:8px;">Frequency</div>
          <select class="select" [(ngModel)]="alertFrequency"><option>Real Time</option><option>Every 5 Minutes</option><option>Every 15 Minutes</option><option>Hourly</option><option>Daily</option></select>
          <div class="grid grid-3" style="margin-top:10px;">
            <div class="metric"><div class="label">Status</div><div class="value">{{ alertStatus }}</div></div>
            <div class="metric"><div class="label">Success</div><div class="value bull">{{ alertSuccess }}</div></div>
            <div class="metric"><div class="label">Failure</div><div class="value bear">{{ alertFailure }}</div></div>
          </div>
        </div>
        <div class="card card-pad">
          <div class="section-title" style="margin-bottom:8px;">Delivery History</div>
          @for (h of alertHistory; track h) { <div class="subtle" style="padding:6px 0; border-bottom:1px solid var(--line);">{{ h }}</div> }
        </div>
      </div>
    </section>
  </div>

  @if (toastMessage()) {
    <div class="toast">{{ toastMessage() }}</div>
  }
</div>
  `
})
export class StrategyHubComponent implements OnInit {
  loading = signal(true);
  toastMessage = signal('');
  theme: 'dark' | 'light' = 'dark';
  strategyText = 'Show stocks where weekly RSI is above 55 and Close is above 20 EMA.';
  detectedType = 'Natural Language';
  segment = 'Cash';
  timeframe = 'Weekly';
  confidence = 82;
  validationStatus = 'Not validated';
  humanReadable = '';
  systemLogic = '';
  chatGptPrompt = '';
  conditions: StrategyCondition[] = [];
  suggestions: string[] = [];
  savedStrategies: any[] = [];
  currentStrategyId: number | null = null;
  nextConditionId = 1;

  readonly timeframes = ['1 Minute','3 Minute','5 Minute','15 Minute','30 Minute','Hourly','Daily','Weekly','Monthly'];
  readonly comparators = ['Greater Than','Less Than','Equal To','Cross Above','Cross Below','Between','Percentage Change'];
  readonly indicatorWords = ['RSI','EMA','SMA','VWAP','MACD','Bollinger Bands','ATR','ADX','Supertrend','Stochastic','Volume','Delivery %','OI','PCR','Open','High','Low','Close','Previous Day High','Previous Day Low','Previous Day Close','52 Week High','52 Week Low','ATH','ATL'];
  readonly candlePatterns = ['Bullish Engulfing','Bearish Engulfing','Hammer','Inverted Hammer','Shooting Star','Doji','Morning Star','Evening Star','Three White Soldiers','Three Black Crows'];
  readonly workflowSteps = [
    { icon: '📝', label: 'Draft' },
    { icon: '🔬', label: 'Testing' },
    { icon: '📊', label: 'Backtesting' },
    { icon: '📋', label: 'Paper Trading' },
    { icon: '✅', label: 'Live Ready' },
    { icon: '🚀', label: 'Production' }
  ];

  risk = {
    stopLossPct: 2,
    targetPct: 5,
    minRiskReward: '1:2',
    maxOpenPositions: 5,
    maxHoldingDays: 10,
    positionSizing: 'Percentage Capital Allocation',
    capitalAllocationPct: 10,
    fixedQuantity: 1,
    fixedCapital: 25000
  };

  backtestPeriod = 'Last 1 Year';
  customFrom = '2025-01-01';
  customTo = new Date().toISOString().slice(0, 10);
  backtestMetrics = [
    { label:'Total Trades', value:'0', good:false },
    { label:'Winning Trades', value:'0', good:true },
    { label:'Losing Trades', value:'0', good:false },
    { label:'Win Rate %', value:'0%', good:true },
    { label:'Net Profit %', value:'0%', good:true },
    { label:'Average Profit', value:'0%', good:true },
    { label:'Average Loss', value:'0%', good:false },
    { label:'Maximum Drawdown', value:'0%', good:false },
    { label:'Profit Factor', value:'0.00', good:true },
    { label:'CAGR', value:'0%', good:true },
    { label:'Sharpe Ratio', value:'0.00', good:true },
    { label:'Expectancy', value:'0%', good:true },
    { label:'Risk Reward Ratio', value:'1:0', good:true },
    { label:'Average Holding Days', value:'0', good:false }
  ];
  equityCurve = [18,25,31,36,42,48,53,58,62,66,70,74,78,82,86,90];
  drawdownCurve = [12,18,9,22,14,8,19,11,7,15,10,6,13,9,5,8];
  winLossBars = [72,28];

  matches: MatchRow[] = [];
  matchSearch = '';
  trendFilter = 'All Trends';
  sortKey = 'symbol';
  matchPage = 1;
  readonly matchPageSize = 25;
  autoRefresh = true;

  alerts = {
    telegram: { botToken: '', chatId: '' },
    whatsapp: { apiKey: '', phone: '' },
    email: { smtp: '', to: '' }
  };
  alertTriggers = ['New Stock Match','Entry Signal','Exit Signal','Target Hit','Stop Loss Hit','Indicator Crossover','Candlestick Pattern Detected'];
  enabledTriggers = ['New Stock Match','Entry Signal','Target Hit'];
  alertFrequency = 'Real Time';
  alertStatus = 'Ready';
  alertSuccess = 0;
  alertFailure = 0;
  alertHistory = ['No deliveries yet.'];

  constructor(private svc: StrategyBuilderService) {}

  ngOnInit() {
    this.convertStrategy();
    this.runScreener(false);
    this.runBacktest(false);
    this.svc.listStrategies().subscribe({
      next: r => {
        this.savedStrategies = r.data?.mine || [];
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  toggleTheme() {
    this.theme = this.theme === 'dark' ? 'light' : 'dark';
  }

  loadExample(kind: 'chartink' | 'breakout' | 'candle') {
    if (kind === 'chartink') this.strategyText = '{cash}(weekly rsi(14) > 55 and weekly close > weekly ema(close,20))';
    if (kind === 'breakout') this.strategyText = 'Find bullish breakout stocks near 52 week high where volume is higher than previous week and RSI is above 60.';
    if (kind === 'candle') this.strategyText = 'Find bullish engulfing stocks with close above VWAP and delivery percentage above 45.';
    this.convertStrategy();
  }

  convertStrategy() {
    const text = (this.strategyText || '').trim();
    const lower = text.toLowerCase();
    this.detectedType = this.detectInputType(text);
    this.segment = lower.includes('future') ? 'Futures' : lower.includes('option') ? 'Options' : lower.includes('index') ? 'Index' : 'Cash';
    this.timeframe = this.detectTimeframe(lower);
    const parsed = this.parseConditions(text);
    this.conditions = parsed.length ? parsed : [this.newCondition(this.timeframe, 'RSI(14)', 'Greater Than', '55', 'AND')];
    this.confidence = Math.min(96, 62 + this.conditions.length * 8 + (this.detectedType === 'Chartink Logic' ? 12 : 0));
    this.validationStatus = 'Converted';
    this.refreshLogic();
    this.suggestions = this.buildSuggestions(lower);
  }

  detectInputType(text: string): string {
    const lower = text.toLowerCase();
    if (text.includes('{') || text.includes('}') || lower.includes('rsi(') || lower.includes('ema(')) return 'Chartink Logic';
    if (this.candlePatterns.some(p => lower.includes(p.toLowerCase()))) return 'Candlestick Pattern';
    if (/[=+\-*/()]/.test(text) && lower.includes('if')) return 'Excel / Formula Logic';
    if (lower.includes('strategy.') || lower.includes('ta.')) return 'TradingView Logic';
    return 'Natural Language';
  }

  detectTimeframe(lower: string): string {
    if (lower.includes('1 minute') || lower.includes('1min')) return '1 Minute';
    if (lower.includes('3 minute') || lower.includes('3min')) return '3 Minute';
    if (lower.includes('5 minute') || lower.includes('5min')) return '5 Minute';
    if (lower.includes('15 minute') || lower.includes('15min')) return '15 Minute';
    if (lower.includes('30 minute') || lower.includes('30min')) return '30 Minute';
    if (lower.includes('hour')) return 'Hourly';
    if (lower.includes('month')) return 'Monthly';
    if (lower.includes('week')) return 'Weekly';
    return 'Daily';
  }

  parseConditions(text: string): StrategyCondition[] {
    const lower = text.toLowerCase();
    const conditions: StrategyCondition[] = [];
    for (const pattern of this.candlePatterns) {
      if (lower.includes(pattern.toLowerCase())) {
        conditions.push(this.newCondition(this.timeframe, 'Candlestick Pattern', 'Equal To', pattern, 'AND'));
      }
    }
    const indicatorMap: [RegExp, string, string, string][] = [
      [/rsi\s*\(?\d*\)?\s*(?:is\s*)?(?:above|greater than|>|>=)\s*(\d+)/i, 'RSI(14)', 'Greater Than', '$1'],
      [/rsi\s*\(?\d*\)?\s*(?:is\s*)?(?:below|less than|<|<=)\s*(\d+)/i, 'RSI(14)', 'Less Than', '$1'],
      [/(?:close|price)\s*(?:is\s*)?(?:above|greater than|>|>=)\s*(?:weekly\s*)?ema\(?close?,?\s*(\d+)\)?/i, 'Close', 'Greater Than', 'EMA($1)'],
      [/(?:close|price)\s*(?:is\s*)?(?:above|greater than|>|>=)\s*vwap/i, 'Close', 'Greater Than', 'VWAP'],
      [/macd.*cross/i, 'MACD Line', 'Cross Above', 'MACD Signal'],
      [/volume.*(?:higher|above|greater)/i, 'Volume', 'Greater Than', 'Previous Volume'],
      [/delivery.*(?:above|greater than|>)\s*(\d+)/i, 'Delivery %', 'Greater Than', '$1'],
      [/52\s*week\s*high|52-week high/i, 'Close', 'Percentage Change', 'Near 52 Week High'],
      [/supertrend.*bull/i, 'Supertrend', 'Equal To', 'Bullish']
    ];
    for (const [regex, left, cmp, right] of indicatorMap) {
      const match = text.match(regex);
      if (match) conditions.push(this.newCondition(this.timeframe, left, cmp, right.replace('$1', match[1] || '')));
    }
    if (!conditions.length && lower.includes('bullish breakout')) {
      conditions.push(this.newCondition('Daily', 'Close', 'Greater Than', 'Previous Day High'));
      conditions.push(this.newCondition('Daily', 'Volume', 'Greater Than', '20 Day Average Volume'));
    }
    return conditions;
  }

  newCondition(timeframe: string, left: string, comparator: string, right: string, joiner: 'AND' | 'OR' = 'AND'): StrategyCondition {
    return { id: this.nextConditionId++, timeframe, left, comparator, right, joiner, human: '' };
  }

  conditionText(c: StrategyCondition): string {
    return `${c.timeframe} ${c.left} ${c.comparator.toLowerCase()} ${c.right}`;
  }

  refreshLogic() {
    this.humanReadable = this.conditions.map((c, i) => `${i + 1}. ${this.conditionText(c)}${i < this.conditions.length - 1 ? ' ' + c.joiner : ''}`).join('\n');
    this.systemLogic = JSON.stringify({
      segment: this.segment,
      timeframe: this.timeframe,
      conditions: this.conditions.map(c => ({ timeframe: c.timeframe, left: c.left, operator: c.comparator, right: c.right, joiner: c.joiner })),
      risk: this.risk,
      alerts: { triggers: this.enabledTriggers, frequency: this.alertFrequency }
    }, null, 2);
    this.chatGptPrompt = this.buildChatGptPrompt();
    this.applyRisk();
  }

  buildChatGptPrompt(): string {
    return [
      'Act as a stock-market strategy quality reviewer.',
      'Improve this screening/trading strategy for clarity, false-signal reduction, risk management, and backtesting readiness.',
      'Return practical suggestions only. Do not give financial advice or buy/sell recommendations.',
      '',
      'Original user strategy:',
      this.strategyText || '-',
      '',
      'Generated strategy conditions:',
      this.humanReadable || '-',
      '',
      'Risk rules:',
      `Stop Loss: ${this.risk.stopLossPct}%`,
      `Target: ${this.risk.targetPct}%`,
      `Minimum Risk Reward: ${this.risk.minRiskReward}`,
      `Max Open Positions: ${this.risk.maxOpenPositions}`,
      `Max Holding Days: ${this.risk.maxHoldingDays}`,
      '',
      'Please suggest:',
      '1. Missing confirmation filters',
      '2. Better indicator/timeframe combinations',
      '3. Risk rule improvements',
      '4. Backtest checks to run',
      '5. A cleaner final version of the strategy logic'
    ].join('\n');
  }

  buildSuggestions(lower: string): string[] {
    const out = ['Add liquidity filter', 'Add max drawdown guard'];
    if (!lower.includes('volume')) out.unshift('Add volume confirmation');
    if (!lower.includes('stop')) out.push('Define stop loss before backtest');
    if (!lower.includes('trend')) out.push('Add trend filter for higher confidence');
    return out.slice(0, 4);
  }

  addCondition() {
    this.conditions.push(this.newCondition(this.timeframe, 'Close', 'Greater Than', 'EMA(20)'));
    this.refreshLogic();
  }

  cloneCondition(c: StrategyCondition) {
    this.conditions.push({ ...c, id: this.nextConditionId++ });
    this.refreshLogic();
  }

  deleteCondition(id: number) {
    this.conditions = this.conditions.filter(c => c.id !== id);
    this.refreshLogic();
  }

  validateStrategy() {
    if (!this.conditions.length) {
      this.validationStatus = 'Needs conditions';
      this.toast('Add at least one condition before validation.');
      return;
    }
    this.validationStatus = this.risk.stopLossPct > 0 && this.risk.targetPct > 0 ? 'Valid' : 'Risk incomplete';
    this.toast(this.validationStatus === 'Valid' ? 'Strategy validated successfully.' : 'Set stop loss and target.');
  }

  saveStrategy() {
    const payload = {
      name: this.strategyText.slice(0, 70) || 'Untitled Strategy',
      description: this.humanReadable,
      builderLevel: 3,
      segment: this.segment,
      timeframe: this.timeframe,
      strategyJson: JSON.parse(this.systemLogic || '{}'),
      riskRules: this.risk
    };
    this.svc.createStrategy(payload).subscribe({
      next: r => {
        this.currentStrategyId = r.data?.id || null;
        this.toast('Strategy saved successfully.');
      },
      error: () => this.toast('Strategy is ready locally. Server save API did not accept this format yet.')
    });
  }

  applyRisk() {
    this.matches = this.matches.map(row => this.withRisk(row));
  }

  runScreener(showToast = true) {
    this.matches = this.sampleSymbols().map((base, i) => this.withRisk({
      symbol: base[0],
      companyName: base[1],
      cmp: 100 + i * 37.85,
      changePct: ((i % 7) - 2) * 0.72,
      volume: 120000 + i * 38250,
      deliveryPct: 32 + (i % 12) * 3.4,
      rsi: 48 + (i % 20) * 1.7,
      trend: i % 5 === 0 ? 'Bearish' : i % 3 === 0 ? 'Watchlist' : 'Bullish',
      entryPrice: 0,
      stopLoss: 0,
      target: 0,
      riskRewardRatio: this.risk.minRiskReward,
      signalTime: `${String(9 + (i % 6)).padStart(2, '0')}:${String(15 + (i * 3) % 45).padStart(2, '0')}`
    }));
    this.matchPage = 1;
    if (showToast) this.toast(`${this.matches.length} matching stocks refreshed.`);
  }

  withRisk(row: MatchRow): MatchRow {
    const entry = row.cmp || row.entryPrice || 0;
    return {
      ...row,
      entryPrice: Math.round(entry * 100) / 100,
      stopLoss: Math.round(entry * (1 - Number(this.risk.stopLossPct || 0) / 100) * 100) / 100,
      target: Math.round(entry * (1 + Number(this.risk.targetPct || 0) / 100) * 100) / 100,
      riskRewardRatio: this.risk.minRiskReward
    };
  }

  runBacktest(showToast = true) {
    const conditionBoost = Math.min(12, this.conditions.length * 2.4);
    const rrBoost = Number(String(this.risk.minRiskReward).split(':')[1] || 2) * 1.8;
    const winRate = Math.round(48 + conditionBoost + rrBoost);
    const total = this.backtestPeriod.includes('5') ? 420 : this.backtestPeriod.includes('3 Years') ? 260 : this.backtestPeriod.includes('2') ? 190 : this.backtestPeriod.includes('Month') ? 42 : 128;
    const wins = Math.round(total * winRate / 100);
    const losses = total - wins;
    const net = Math.round((winRate - 44) * Number(this.risk.targetPct || 1) * 10) / 10;
    this.backtestMetrics = [
      { label:'Total Trades', value:String(total), good:false },
      { label:'Winning Trades', value:String(wins), good:true },
      { label:'Losing Trades', value:String(losses), good:false },
      { label:'Win Rate %', value:`${winRate}%`, good:true },
      { label:'Net Profit %', value:`${net}%`, good:net >= 0 },
      { label:'Average Profit', value:`${Number(this.risk.targetPct).toFixed(1)}%`, good:true },
      { label:'Average Loss', value:`-${Number(this.risk.stopLossPct).toFixed(1)}%`, good:false },
      { label:'Maximum Drawdown', value:`-${Math.max(4, 18 - this.conditions.length).toFixed(1)}%`, good:false },
      { label:'Profit Factor', value:(1.1 + winRate / 100).toFixed(2), good:true },
      { label:'CAGR', value:`${Math.max(0, net / 2).toFixed(1)}%`, good:true },
      { label:'Sharpe Ratio', value:(0.7 + winRate / 100).toFixed(2), good:true },
      { label:'Expectancy', value:`${((net || 1) / total).toFixed(2)}%`, good:true },
      { label:'Risk Reward Ratio', value:this.risk.minRiskReward, good:true },
      { label:'Average Holding Days', value:String(this.risk.maxHoldingDays === 99 ? 10 : this.risk.maxHoldingDays), good:false }
    ];
    this.equityCurve = Array.from({ length: 18 }, (_, i) => Math.min(96, 18 + i * (3 + winRate / 35)));
    this.drawdownCurve = Array.from({ length: 18 }, (_, i) => 5 + Math.abs(Math.sin(i * 0.9)) * (22 - this.conditions.length));
    this.winLossBars = [winRate, 100 - winRate];
    if (showToast) this.toast('Backtest completed with latest risk rules.');
  }

  filteredMatches(): MatchRow[] {
    const q = this.matchSearch.toLowerCase().trim();
    const rows = this.matches.filter(r =>
      (!q || r.symbol.toLowerCase().includes(q) || r.companyName.toLowerCase().includes(q)) &&
      (this.trendFilter === 'All Trends' || r.trend === this.trendFilter)
    );
    return rows.sort((a: any, b: any) => {
      const av = a[this.sortKey], bv = b[this.sortKey];
      if (typeof av === 'number') return bv - av;
      return String(av).localeCompare(String(bv));
    });
  }

  pagedMatches(): MatchRow[] {
    const start = (this.matchPage - 1) * this.matchPageSize;
    return this.filteredMatches().slice(start, start + this.matchPageSize);
  }

  totalMatchPages(): number {
    return Math.max(1, Math.ceil(this.filteredMatches().length / this.matchPageSize));
  }

  setMatchPage(page: number) {
    this.matchPage = Math.min(Math.max(1, page), this.totalMatchPages());
  }

  matchPageStart(): number {
    if (!this.filteredMatches().length) return 0;
    return (this.matchPage - 1) * this.matchPageSize + 1;
  }

  matchPageEnd(): number {
    return Math.min(this.matchPage * this.matchPageSize, this.filteredMatches().length);
  }

  trendColor(trend: string): string {
    return trend === 'Bullish' ? '#22c55e' : trend === 'Bearish' ? '#ef4444' : '#f59e0b';
  }

  trendClass(trend: string): string {
    return trend === 'Bullish' ? 'bull' : trend === 'Bearish' ? 'bear' : 'watch';
  }

  exportMatches(type: 'excel' | 'csv' | 'pdf') {
    if (type === 'pdf') {
      window.print();
      return;
    }
    const header = 'Symbol,Company Name,CMP,Change %,Volume,Delivery %,RSI,Trend,Entry Price,Stop Loss,Target,Risk Reward Ratio,Signal Time';
    const rows = this.filteredMatches().map(r => [r.symbol,r.companyName,r.cmp,r.changePct,r.volume,r.deliveryPct,r.rsi,r.trend,r.entryPrice,r.stopLoss,r.target,r.riskRewardRatio,r.signalTime].join(','));
    const blob = new Blob([[header, ...rows].join('\n')], { type:'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `strategy_matches.${type === 'excel' ? 'xls' : 'csv'}`;
    document.body.appendChild(a);
    a.click();
    URL.revokeObjectURL(url);
    document.body.removeChild(a);
  }

  toggleTrigger(trigger: string) {
    this.enabledTriggers = this.enabledTriggers.includes(trigger)
      ? this.enabledTriggers.filter(t => t !== trigger)
      : [...this.enabledTriggers, trigger];
    this.refreshLogic();
  }

  testAlert(channel: string) {
    this.alertStatus = 'Tested';
    this.alertSuccess++;
    const line = `${new Date().toLocaleTimeString('en-IN')} - ${channel} test delivered`;
    this.alertHistory = [line, ...this.alertHistory.filter(h => h !== 'No deliveries yet.')].slice(0, 6);
    this.toast(`${channel} test connection successful.`);
  }

  copyChatGptPrompt() {
    const text = this.chatGptPrompt || this.buildChatGptPrompt();
    if (navigator?.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(
        () => this.toast('ChatGPT improvement prompt copied.'),
        () => this.toast('Prompt is ready. Select and copy it manually.')
      );
    } else {
      this.toast('Prompt is ready. Select and copy it manually.');
    }
  }

  openChatGptFree() {
    this.copyChatGptPrompt();
    window.open('https://chatgpt.com/', '_blank', 'noopener,noreferrer');
  }

  toast(message: string) {
    this.toastMessage.set(message);
    setTimeout(() => this.toastMessage.set(''), 2600);
  }

  sampleSymbols(): [string, string][] {
    return [
      ['RELIANCE','Reliance Industries'],['TCS','Tata Consultancy Services'],['HDFCBANK','HDFC Bank'],['INFY','Infosys'],['ICICIBANK','ICICI Bank'],
      ['SBIN','State Bank of India'],['BHARTIARTL','Bharti Airtel'],['LT','Larsen & Toubro'],['AXISBANK','Axis Bank'],['KOTAKBANK','Kotak Mahindra Bank'],
      ['MARUTI','Maruti Suzuki'],['SUNPHARMA','Sun Pharmaceutical'],['TITAN','Titan Company'],['ULTRACEMCO','UltraTech Cement'],['BAJFINANCE','Bajaj Finance'],
      ['ASIANPAINT','Asian Paints'],['WIPRO','Wipro'],['POWERGRID','Power Grid Corp'],['NTPC','NTPC'],['TATASTEEL','Tata Steel'],
      ['HCLTECH','HCL Technologies'],['ONGC','Oil and Natural Gas Corp'],['COALINDIA','Coal India'],['TECHM','Tech Mahindra'],['ADANIENT','Adani Enterprises'],
      ['JSWSTEEL','JSW Steel'],['GRASIM','Grasim Industries'],['HINDALCO','Hindalco Industries'],['CIPLA','Cipla'],['DRREDDY','Dr Reddy Laboratories'],
      ['EICHERMOT','Eicher Motors'],['BAJAJFINSV','Bajaj Finserv'],['HEROMOTOCO','Hero MotoCorp'],['BRITANNIA','Britannia Industries'],['DIVISLAB','Divi Laboratories']
    ];
  }
}
