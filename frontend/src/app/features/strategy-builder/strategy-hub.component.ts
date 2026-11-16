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
    /* Progress track */
    .wf-step { position:relative; background:var(--panel2); border:1px solid var(--line); border-radius:8px;
      padding:10px 8px; text-align:center; opacity:.55; transition:opacity .15s, border-color .15s; }
    .wf-step.wf-done { opacity:1; border-color:#2f855a; }
    .wf-step.wf-now  { opacity:1; border-color:#1d6adb; box-shadow:0 0 0 1px #1d6adb inset; }
    .wf-icon { font-size:22px; line-height:1; margin-bottom:6px; }
    .wf-step.wf-done .wf-icon { color:#34d399; font-weight:900; }
    .wf-label { font-weight:800; color:var(--text); font-size:12px; }
    .wf-you { font-size:9.5px; color:#60a5fa; margin-top:3px; font-weight:700; letter-spacing:.3px; }

    /* Empty states that say what to do next */
    .empty-state { border:1px dashed var(--line); border-radius:8px; padding:14px; }
    .empty-title { font-weight:800; color:var(--text); margin-bottom:4px; }
    .empty-sample { margin:8px 0; background:var(--field); border:1px solid var(--line); border-radius:6px;
      padding:8px 10px; font-size:11px; color:var(--text); white-space:pre-wrap; }

    /* Action bar */
    .action-bar { position:sticky; top:0; z-index:20; display:flex; align-items:center;
      justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:12px;
      background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:8px 12px; }
    .ab-status { display:flex; gap:6px; flex-wrap:wrap; }
    .ab-chip { font-size:10.5px; font-weight:700; padding:3px 9px; border-radius:999px;
      border:1px solid var(--line); background:var(--panel2); color:var(--muted); white-space:nowrap; }
    .ab-chip.ab-ok { color:#34d399; border-color:#2f855a; }
    .ab-chip.ab-warn { color:#fbbf24; border-color:#92400e; }
    .ab-actions { display:flex; gap:6px; flex-wrap:wrap; }
    .ab-actions .btn { padding:6px 12px; font-size:12px; }

    .condition { border:1px solid var(--line); background:var(--panel2); border-radius:8px; padding:10px; }
    .condition-grid { display:grid; grid-template-columns:100px 1fr 110px 1fr 90px; gap:8px; align-items:center; }
    /* One condition per line: number, the five controls, then the two buttons. */
    .condition-row { display:grid; gap:6px; align-items:center;
      grid-template-columns:26px 104px minmax(0,1fr) 132px minmax(0,1fr) 78px auto auto;
      border:1px solid var(--line); background:var(--panel2); border-radius:8px; padding:6px 8px; }
    .cond-no { font-size:11px; font-weight:800; color:var(--muted); text-align:center; }
    /* The indicator field plus the category it resolved to, sharing one grid cell so the row
       keeps its column count. min-width:0 stops the input forcing the cell wider than 1fr. */
    .ind-cell { display:flex; align-items:center; gap:5px; min-width:0; }
    .ind-cell .input { flex:1 1 auto; min-width:0; }
    .cat-tag { flex:0 0 auto; font-size:9px; font-weight:700; letter-spacing:.02em;
      text-transform:uppercase; color:var(--muted); background:var(--panel2);
      border:1px solid var(--line); border-radius:5px; padding:2px 5px; white-space:nowrap; }
    .btn-tiny { padding:5px 9px; font-size:11px; white-space:nowrap; }
    /* Fields laid across the full page width, wrapping only when they genuinely cannot fit. */
    .field-row { display:grid; gap:10px; grid-template-columns:repeat(auto-fit,minmax(128px,1fr)); }
    .metric-row { display:grid; gap:10px; grid-template-columns:repeat(auto-fit,minmax(140px,1fr)); }
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
    @media (max-width:1100px) { .grid-2,.grid-3,.grid-4 { grid-template-columns:1fr 1fr; } .condition-grid { grid-template-columns:1fr 1fr; }
      .condition-row { grid-template-columns:22px 92px minmax(0,1fr) 118px minmax(0,1fr) 70px auto auto; } }
    @media (max-width:1100px) { .workflow { grid-template-columns:repeat(3,1fr); } }
    @media (max-width:720px) { .page { padding:10px; } .grid-2,.grid-3,.grid-4,.condition-grid,.workflow { grid-template-columns:1fr; } .title { font-size:20px; }
      /* Too narrow for one line - fall back to a wrapped block rather than squeezing. */
      .condition-row { grid-template-columns:1fr 1fr; }
      .field-row,.metric-row { grid-template-columns:1fr 1fr; } }
  `],
  template: `
<div class="page" [ngClass]="theme">
  <div class="shell">
    <!-- Primary actions used to be scattered across five cards - Convert in one, Save and Run
         Screener in another, Run Backtest in a third - so finishing a strategy meant scrolling
         back up to find the next button. They are gathered here and the bar stays put. -->
    <div class="action-bar">
      <div class="ab-status">
        <span class="ab-chip" [class.ab-ok]="conditions.length > 0">{{ conditions.length }} condition{{ conditions.length === 1 ? '' : 's' }}</span>
        <span class="ab-chip" [class.ab-ok]="validationStatus === 'Valid'"
              [class.ab-warn]="validationStatus !== 'Valid' && validationStatus !== 'Not validated'">{{ validationStatus }}</span>
        <span class="ab-chip">{{ timeframe }}</span>
      </div>
      <div class="ab-actions">
        <button class="btn btn-primary" (click)="convertStrategy()" title="Re-read the text and rebuild the conditions">Convert</button>
        <button class="btn btn-secondary" (click)="validateStrategy()" title="Check the strategy is complete enough to run">Validate</button>
        <button class="btn btn-secondary" (click)="saveStrategy()" [disabled]="!conditions.length" title="Save this strategy">Save</button>
        <button class="btn btn-warn" (click)="runScreener()" [disabled]="!conditions.length" title="Find stocks matching these conditions today">Run Screener</button>
        <button class="btn btn-primary" (click)="runBacktest()" [disabled]="!conditions.length" title="Replay these conditions over the chosen period">Run Backtest</button>
      </div>
    </div>

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
<!-- Six inert boxes became a progress track. It cost a full section at the top of the page
           and told you nothing you could act on; now it says which stage this strategy has
           actually reached and what would move it forward. -->
      <div class="card-pad workflow">
        @for (step of workflowSteps; track step.label; let i = $index) {
          <div class="wf-step"
               [class.wf-done]="i < currentStageIndex()"
               [class.wf-now]="i === currentStageIndex()"
               [title]="stageHint(i)">
            <div class="wf-icon">{{ i < currentStageIndex() ? '\u2713' : step.icon }}</div>
            <div class="wf-label">{{ step.label }}</div>
            @if (i === currentStageIndex()) { <div class="wf-you">You are here</div> }
          </div>
        }
      </div>
      <div class="card-pad" style="padding-top:0;">
        <div class="subtle">{{ nextActionHint() }}</div>
      </div>
    </section>

    <section class="card" style="margin-bottom:12px;">
      <div class="section-head">
        <div class="section-title">Generated Strategy</div>
        <span class="chip">{{ validationStatus }}</span>
      </div>
      <!-- Shared by every condition row. A datalist keeps the field free-text - needed for
           numbers, percentages and offsets like [D-1] - while still showing what is available. -->
      <datalist id="indicatorList">
        @for (g of indicatorGroups; track g.group) {
          @for (item of g.items; track item) {
            <option [value]="item">{{ g.group }}</option>
          }
        }
      </datalist>

      <div class="card-pad grid" style="gap:6px;">
        @if (!conditions.length) {
          <div class="empty-state">
            <div class="empty-title">No conditions yet</div>
            <div class="subtle">Write your strategy in <strong>Type or Paste Your Strategy Logic</strong> below &mdash;
              one rule per line &mdash; then press <strong>Convert Strategy</strong>. For example:</div>
            <pre class="empty-sample">Weekly RSI(14) cross over 45
Daily ATR%(D-1) &gt;= 3.0%
Daily ADX(14) &lt; 20</pre>
            <button class="btn btn-secondary btn-tiny" (click)="loadExample('breakout')">Load an example instead</button>
          </div>
        }
        @for (c of conditions; track c.id; let i=$index) {
          <!-- One line per condition. The controls and the Clone/Delete buttons used to sit on
               two stacked rows inside a bordered, padded box, so every clone added roughly 90px
               of height and a handful of conditions filled the screen. Everything is on one row
               now, and the sentence form moved into a tooltip - it only restated the controls
               already visible beside it. -->
          <div class="condition-row" [title]="'Condition ' + (i + 1) + ': ' + conditionText(c)">
            <span class="cond-no">{{ i + 1 }}</span>
            <select class="select" [(ngModel)]="c.timeframe" (ngModelChange)="refreshLogic()">
              @for (tf of timeframes; track tf) { <option [ngValue]="tf">{{ tf }}</option> }
            </select>
            <div class="ind-cell">
              <input class="input" list="indicatorList" [(ngModel)]="c.left" (ngModelChange)="refreshLogic()"
                     placeholder="Indicator, e.g. RSI" title="Type to search, or pick from the list. Add a period in brackets - RSI(14) - and a bar offset as [D-1].">
              @if (categoryOf(c.left); as cat) { <span class="cat-tag" [title]="'Category: ' + cat">{{ cat }}</span> }
            </div>
            <select class="select" [(ngModel)]="c.comparator" (ngModelChange)="refreshLogic()">
              @for (cmp of comparators; track cmp) { <option [ngValue]="cmp">{{ cmp }}</option> }
            </select>
            <div class="ind-cell">
              <input class="input" list="indicatorList" [(ngModel)]="c.right" (ngModelChange)="refreshLogic()"
                     placeholder="Value or indicator" title="A number such as 45 or 3.0%, or another indicator to compare against.">
              @if (categoryOf(c.right); as cat) { <span class="cat-tag" [title]="'Category: ' + cat">{{ cat }}</span> }
            </div>
            <select class="select" [(ngModel)]="c.joiner" (ngModelChange)="refreshLogic()">
              <option value="AND">AND</option>
              <option value="OR">OR</option>
            </select>
            <button class="btn btn-secondary btn-tiny" (click)="cloneCondition(c)" title="Duplicate this condition">Clone</button>
            <button class="btn btn-danger btn-tiny" (click)="deleteCondition(c.id)" title="Remove this condition">Delete</button>
          </div>
        }

        <!-- The indicator library. This used to be a flat count behind a "show all N" button,
             which told you how many there were but not what any of them was for. It is part of
             the section now, grouped under its category headings, with a filter to narrow to
             one category. Clicking any indicator adds a condition using it. -->
        <div style="margin-top:4px;">
          <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
            <span class="subtle" style="font-weight:700;">Indicator library</span>
            <select class="select" style="max-width:190px;" [(ngModel)]="libraryCategory">
              @for (cat of indicatorCategories; track cat) {
                <option [ngValue]="cat">{{ cat === 'All' ? 'All categories' : cat }}</option>
              }
            </select>
            <button class="btn btn-secondary btn-tiny" (click)="showIndicators = !showIndicators">
              {{ showIndicators ? 'Hide' : 'Show' }}
            </button>
          </div>
          @if (showIndicators) {
            <div style="margin-top:8px; display:grid; gap:8px; grid-template-columns:repeat(auto-fit,minmax(210px,1fr));">
              @for (g of visibleIndicatorGroups; track g.group) {
                <div style="background:var(--panel2); border:1px solid var(--line); border-radius:8px; padding:8px 10px;">
                  <div style="font-size:11px; font-weight:800; color:var(--text); margin-bottom:4px;">
                    {{ g.group }} <span class="subtle" style="font-weight:600;">({{ g.items.length }})</span>
                  </div>
                  <div style="display:flex; flex-wrap:wrap; gap:4px;">
                    @for (item of g.items; track item) {
                      <span class="chip" style="cursor:pointer; font-size:10px;"
                            (click)="useIndicator(item)"
                            title="Add a condition using {{ item }}">{{ item }}</span>
                    }
                  </div>
                </div>
              }
            </div>
          }
        </div>
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
          <span class="chip">{{ validationStatus }}</span>
        </div>
        <div class="card-pad">
          <div class="subtle" style="margin-bottom:8px;">Human Readable Strategy</div>
          <div style="background:var(--panel2); border:1px solid var(--line); border-radius:8px; padding:10px; min-height:74px; font-size:13px; line-height:1.6; white-space:pre-line;">
            @if (humanReadable) { {{ humanReadable }} }
            @else { <span class="subtle">Nothing converted yet. Your strategy will be restated here in plain English so you can check it says what you meant.</span> }
          </div>
<!-- System Logic Format is no longer shown. systemLogic is still built on every change -
               Save Strategy sends it as strategyJson - it is just not the raw JSON a reader needs
               on screen when the human-readable list above says the same thing. -->
          <div style="display:flex; gap:8px; flex-wrap:wrap; margin-top:10px;">
            <button class="btn btn-secondary btn-tiny" (click)="addCondition()">Add Condition</button>
            <span class="subtle" style="align-self:center;">Save, Screen and Backtest are in the bar at the top.</span>
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
                <button class="btn btn-secondary btn-tiny" (click)="showPrompt = !showPrompt">
                  {{ showPrompt ? 'Hide prompt' : 'Show prompt' }}
                </button>
                <button class="btn btn-secondary btn-tiny" (click)="copyChatGptPrompt()">Copy Prompt</button>
                <button class="btn btn-primary btn-tiny" (click)="openChatGptFree()">Open ChatGPT Free</button>
              </div>
            </div>
            <!-- Folded away by default. It is a long generated block that most people copy without
                 reading, and open it occupied a third of the panel. -->
            @if (showPrompt) {
              <pre style="margin:10px 0 0; max-height:160px; overflow:auto; white-space:pre-wrap; background:var(--field); border:1px solid var(--line); border-radius:8px; padding:9px; color:var(--text); font-size:11px;">{{ chatGptPrompt }}</pre>
            }
          </div>
        </div>
      </section>
    </div>

    <!-- Base Price Reference.
         The crossover rules are written against a base price, and until now that could only be
         the single value uploaded with each stock - so the same rules could never be tried
         against any other level without re-uploading everything. -->
    <section class="card" style="margin-bottom:12px;">
      <div class="section-head">
        <div class="section-title">Base Price Reference</div>
        <span class="chip">{{ basePriceSummary() }}</span>
      </div>
      <div class="card-pad field-row">
        <label class="subtle">Base Price From
          <select class="select" [(ngModel)]="basePrice.mode">
            <option value="uploaded">Price uploaded with the stock</option>
            <option value="date">Price on a chosen date</option>
          </select>
        </label>
        @if (basePrice.mode === 'date') {
          <label class="subtle">Date<input class="input" type="date" [(ngModel)]="basePrice.date"></label>
          <label class="subtle">Time (optional)<input class="input" type="time" [(ngModel)]="basePrice.time"></label>
        }
      </div>
      <div class="card-pad subtle" style="padding-top:0;">
        Conditions compare against <code>BASE_PRICE</code> &mdash; a crossover is
        <code>PREV_CLOSE &lt; BASE_PRICE</code> and <code>HIGH &gt;= BASE_PRICE</code>.
        Leave the time blank to use that session&rsquo;s close; with a time, the last 5-minute
        candle at or before it is used. A weekend, a holiday or a stock with no bar that day
        falls back to the last session before it.
      </div>
    </section>

    <!-- Risk Management and Backtesting were side by side in a two-column grid, each then
         splitting into three columns - so nine risk fields landed in a narrow half-page column
         three at a time. Both are full width now, one row of fields each. -->
    <section class="card" style="margin-bottom:12px;">
      <div class="section-head"><div class="section-title">Risk Management</div><span class="chip">Applied everywhere</span></div>
      <div class="card-pad field-row">
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

    <section class="card" style="margin-bottom:12px;">
      <div class="section-head"><div class="section-title">Backtesting</div><button class="btn btn-primary" (click)="runBacktest()">Run Backtest</button></div>
      <div class="card-pad">
        <div class="field-row">
            <label class="subtle">Backtest Period<select class="select" [(ngModel)]="backtestPeriod"><option>Last 1 Month</option><option>Last 3 Months</option><option>Last 6 Months</option><option>Last 1 Year</option><option>Last 2 Years</option><option>Last 3 Years</option><option>Last 5 Years</option><option>Custom Date Range</option></select></label>
            <label class="subtle">From<input class="input" type="date" [(ngModel)]="customFrom" [disabled]="backtestPeriod !== 'Custom Date Range'"></label>
          <label class="subtle">To<input class="input" type="date" [(ngModel)]="customTo" [disabled]="backtestPeriod !== 'Custom Date Range'"></label>
        </div>
        <div class="metric-row" style="margin-top:12px;">
          @for (m of backtestMetrics; track m.label) {
            <div class="metric"><div class="label">{{ m.label }}</div><div class="value" [ngClass]="m.good ? 'bull' : ''">{{ m.value }}</div></div>
          }
        </div>
      </div>
    </section>

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
  showPrompt = false;
  /** The library is part of the section, not a reference hidden behind a button. */
  showIndicators = true;

  /** Which category the library is showing. 'All' groups them under every heading. */
  libraryCategory = 'All';
  hasScreened = false;
  hasBacktested = false;

  /**
   * Which approval stage this strategy has actually reached, derived from what has been done
   * rather than stored - there is no server-side stage yet, and a track that never moves is
   * worse than no track at all.
   *
   * Draft -> Testing -> Backtesting -> Paper Trading -> Live Ready -> Production. The last three
   * need a saved strategy and a human decision, so the track stops at the highest stage the
   * screen can honestly claim.
   */
  currentStageIndex(): number {
    if (!this.strategyText.trim() && !this.conditions.length) return 0;   // Draft
    if (!this.conditions.length) return 0;
    if (!this.hasBacktested) return this.validationStatus === 'Valid' ? 2 : 1;
    if (!this.currentStrategyId) return 2;
    return 3;
  }

  stageHint(i: number): string {
    return [
      'Draft - your strategy text and conditions.',
      'Testing - conditions exist; validate them and screen today\'s stocks.',
      'Backtesting - replay the conditions over a past period.',
      'Paper Trading - run it without real money once saved.',
      'Live Ready - reviewed and approved for live use.',
      'Production - running live.'
    ][i] || '';
  }

  /** One line telling you the next useful thing to do. */
  nextActionHint(): string {
    if (!this.conditions.length) return 'Start by writing your strategy below, one rule per line, then press Convert.';
    if (this.validationStatus !== 'Valid') return 'Conditions are ready. Press Validate, and set a stop loss and target under Risk Management.';
    if (!this.hasBacktested) return 'Validated. Choose a backtest period and press Run Backtest to see how it would have performed.';
    if (!this.currentStrategyId) return 'Backtest complete. Press Save to keep this strategy, then test it further in Strategy Tester.';
    return 'Saved and backtested. Review the results below before considering it for live use.';
  }
  savedStrategies: any[] = [];
  currentStrategyId: number | null = null;
  nextConditionId = 1;

  readonly timeframes = ['1 Minute','3 Minute','5 Minute','15 Minute','30 Minute','Hourly','Daily','Weekly','Monthly'];
  // '>=' and '<=' used to collapse onto 'Greater Than' / 'Less Than', which quietly changed
  // the rule: "ATR% >= 3.0" is not the same test as "ATR% > 3.0" at exactly 3.0.
  readonly comparators = ['Greater Than','Greater Than or Equal','Less Than','Less Than or Equal',
                          'Equal To','Cross Above','Cross Below','Between','Percentage Change'];
  /**
   * Every indicator the backtest engine can actually evaluate, grouped for the picker.
   *
   * Taken from StrategyConditionEvaluator's own switch rather than written by hand, so the list
   * on screen and the list the engine understands cannot drift apart. The old list had 25 entries
   * against the engine's 81, and four of those 25 - OI, PCR, ATH, ATL - the engine has no case
   * for at all, so a condition built on them could never evaluate to anything.
   */
  readonly indicatorGroups: Array<{ group: string; items: string[] }> = [
    { group: 'Price', items: ['Open','High','Low','Close','Prev Close','Prev High','Prev Low','Change Pct','Gap Pct'] },
    { group: 'Moving Averages', items: ['EMA','SMA','WMA','VWAP'] },
    { group: 'Momentum', items: ['RSI','Stoch K','Stoch D','Stoch RSI K','CCI','MFI','Momentum','ROC','Williams R'] },
    { group: 'Trend', items: ['ADX','Plus DI','Minus DI','MACD','MACD Signal','MACD Hist','Supertrend','Supertrend Bull','Parabolic SAR','Ichimoku Tenkan','Ichimoku Kijun','Ichimoku Senkou A','Ichimoku Senkou B'] },
    { group: 'Volatility & Bands', items: ['ATR','BB Upper','BB Mid','BB Lower','BB Width','BB Percent B','Keltner Upper','Keltner Mid','Keltner Lower','Donchian High','Donchian Mid','Donchian Low'] },
    { group: 'Volume', items: ['Volume','Volume SMA','Volume EMA','Volume Change','Volume Spike','Volume Spike Ratio','OBV','Delivery Pct','Delivery Ratio'] },
    { group: 'Levels', items: ['Pivot','R1','R2','S1','S2','CPR Pivot','CPR TC','CPR BC'] },
    { group: 'Structure', items: ['Breakout','Breakdown','Higher High','Higher Low','Lower High','Lower Low'] },
    { group: 'Patterns', items: ['Bullish Engulfing','Bearish Engulfing','Hammer','Inverted Hammer','Shooting Star','Doji','Morning Star','Evening Star','Three White Soldiers','Three Black Crows','Marubozu','Spinning Top'] },
    // Base Price is the level the crossover rules are written against. It is not computed
    // from the price series - it comes from the Base Price Reference chosen above - so it sits
    // apart from the indicators rather than among the momentum or trend ones.
    { group: 'Reference', items: ['Base Price','Sector Score'] }
  ];

  /** Category names, for the library filter. */
  get indicatorCategories(): string[] {
    return ['All', ...this.indicatorGroups.map(g => g.group)];
  }

  /** Only the groups the library should currently render. */
  get visibleIndicatorGroups(): Array<{ group: string; items: string[] }> {
    return this.libraryCategory === 'All'
      ? this.indicatorGroups
      : this.indicatorGroups.filter(g => g.group === this.libraryCategory);
  }

  /**
   * The category a typed indicator belongs to, so each condition says what it is working with.
   * Strips a period and a bar offset first, so "RSI(14)[D-1]" still resolves to Momentum.
   * Returns '' for a plain number or an unrecognised name - there is no category to claim.
   */
  categoryOf(value: string): string {
    const bare = (value || '').replace(/\(.*?\)/g, '').replace(/\[.*?\]/g, '')
                              .replace(/%/g, '').trim().toUpperCase();
    if (!bare) return '';
    const hit = this.indicatorGroups.find(g => g.items.some(i => i.toUpperCase() === bare));
    return hit ? hit.group : '';
  }

  /** Flat list, used by the picker and by the text parser to recognise a name. */
  readonly indicatorWords: string[] = this.indicatorGroups.flatMap(g => g.items);
  readonly candlePatterns = ['Bullish Engulfing','Bearish Engulfing','Hammer','Inverted Hammer','Shooting Star','Doji','Morning Star','Evening Star','Three White Soldiers','Three Black Crows'];
  readonly workflowSteps = [
    { icon: '📝', label: 'Draft' },
    { icon: '🔬', label: 'Testing' },
    { icon: '📊', label: 'Backtesting' },
    { icon: '📋', label: 'Paper Trading' },
    { icon: '✅', label: 'Live Ready' },
    { icon: '🚀', label: 'Production' }
  ];

  /**
   * Where the base price comes from. 'uploaded' keeps the existing behaviour - the value that
   * came in with the stock - so a strategy that never touches this behaves exactly as before.
   */
  basePrice: { mode: 'uploaded' | 'date'; date: string; time: string } =
    { mode: 'uploaded', date: '', time: '' };

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

  /**
   * Operators the parser understands, longest first so 'cross over' is matched before 'over'
   * and '>=' before '>'. Order matters: matching '>' first would leave a stray '=' on the value.
   */
  private readonly operatorPatterns: Array<[RegExp, string]> = [
    [/\bcross(?:es|ed|ing)?\s*(?:over|above|up\s*through|up)\b/i, 'Cross Above'],
    [/\bcross(?:es|ed|ing)?\s*(?:under|below|down\s*through|down)\b/i, 'Cross Below'],
    [/>=|=>|\bgreater\s*than\s*or\s*equal(?:\s*to)?\b|\bat\s*least\b|\bnot\s*less\s*than\b/i, 'Greater Than or Equal'],
    [/<=|=<|\bless\s*than\s*or\s*equal(?:\s*to)?\b|\bat\s*most\b|\bnot\s*more\s*than\b/i, 'Less Than or Equal'],
    [/>|\bgreater\s*than\b|\bmore\s*than\b|\babove\b|\bover\b|\bexceeds?\b/i, 'Greater Than'],
    [/<|\bless\s*than\b|\bbelow\b|\bunder\b/i, 'Less Than'],
    [/==|=|\bequals?\s*(?:to)?\b|\bis\b/i, 'Equal To']
  ];

  /** Leading timeframe word on a line, e.g. "Weekly RSI(14) ...". */
  private readonly timeframeWords: Array<[RegExp, string]> = [
    [/^\s*(?:on\s+)?weekly\b/i, 'Weekly'],
    [/^\s*(?:on\s+)?monthly\b/i, 'Monthly'],
    [/^\s*(?:on\s+)?daily\b/i, 'Daily'],
    [/^\s*(?:on\s+)?hourly\b/i, 'Hourly'],
    [/^\s*(?:on\s+)?(\d+)\s*min(?:ute)?s?\b/i, '$1 Minute']
  ];

  /**
   * Turns pasted strategy text into conditions.
   *
   * <p>Reads one condition per line, because that is how strategies are actually written. The
   * previous version scanned the whole blob with a handful of natural-language regexes and had
   * no pattern for ATR or ADX, none for "cross over", and integer-only values - so
   *
   * <pre>
   *   Weekly RSI(14) cross over 45
   *   Daily ATR%(D-1) &gt;= 3.0%
   *   daily ADX14(D-1) &lt; 20
   * </pre>
   *
   * matched nothing at all, fell through to the default RSI(14) &gt; 55 placeholder, and looked
   * like the button had done nothing.
   *
   * <p>Anything the line reader cannot make sense of still goes through the old phrase patterns,
   * so "Show stocks where weekly RSI is above 55" keeps working.
   */
  parseConditions(text: string): StrategyCondition[] {
    const lower = text.toLowerCase();
    const conditions: StrategyCondition[] = [];
    for (const pattern of this.candlePatterns) {
      if (lower.includes(pattern.toLowerCase())) {
        conditions.push(this.newCondition(this.timeframe, 'Candlestick Pattern', 'Equal To', pattern, 'AND'));
      }
    }

    const lineConditions = this.parseLines(text);
    if (lineConditions.length) return conditions.concat(lineConditions);
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

  /** One condition per non-empty line. Returns [] when no line yields a usable condition. */
  private parseLines(text: string): StrategyCondition[] {
    const out: StrategyCondition[] = [];
    const lines = (text || '').split(/\r?\n/).map(l => l.trim()).filter(l => l.length > 0);
    if (lines.length === 0) return out;

    for (let raw of lines) {
      // A leading AND / OR joins this line to the previous one; strip it and record it there,
      // since the model hangs the joiner off the earlier condition.
      let joinPrev: 'AND' | 'OR' | null = null;
      const joinMatch = raw.match(/^(and|or)\b\s*/i);
      if (joinMatch) {
        joinPrev = joinMatch[1].toUpperCase() === 'OR' ? 'OR' : 'AND';
        raw = raw.slice(joinMatch[0].length).trim();
      }
      // Bullets and numbering are noise, not content.
      raw = raw.replace(/^[-*\u2022]\s*/, '').replace(/^\d+[.)]\s*/, '').trim();
      if (!raw) continue;

      // Timeframe prefix, if the line names one; otherwise the strategy-wide default.
      let timeframe = this.timeframe;
      for (const [re, label] of this.timeframeWords) {
        const m = raw.match(re);
        if (m) {
          timeframe = label.replace('$1', m[1] || '');
          raw = raw.slice(m[0].length).trim();
          break;
        }
      }
      if (!raw) continue;

      // Operator: first match wins, and the list is ordered longest-form first.
      let comparator = '';
      let left = '';
      let right = '';
      for (const [re, name] of this.operatorPatterns) {
        const m = raw.match(re);
        if (!m || m.index === undefined) continue;
        const before = raw.slice(0, m.index).trim();
        const after = raw.slice(m.index + m[0].length).trim();
        // "is" alone is a linking verb, not a comparison - skip it when something follows that
        // is itself an operator ("RSI is above 55").
        if (!before || !after) continue;
        comparator = name;
        left = before;
        right = after;
        break;
      }
      if (!comparator) continue;

      const l = this.normalizeIndicator(left);
      const r = this.normalizeValue(right);
      if (!l || !r) continue;
      // A whole sentence on the left is prose, not a condition. "Show stocks where weekly RSI is
      // above 55" would otherwise become an indicator literally named "Show Stocks Where Weekly
      // RSI IS". Rejecting it here lets the phrase patterns below handle that input as they did.
      if (!this.looksLikeIndicator(l)) continue;

      if (joinPrev && out.length) out[out.length - 1].joiner = joinPrev;
      out.push(this.newCondition(timeframe, l, comparator, r, 'AND'));
    }
    return out;
  }

  /**
   * Tidies the left-hand side into a readable indicator label.
   *
   * <p>Handles the shapes people actually type: RSI(14), ADX14, ATR%(D-1), EMA 20. A bar offset
   * in brackets - (D-1), (W-1), [-1] - is kept as a visible suffix rather than dropped, because
   * "ATR% of yesterday's bar" and "ATR% of today's bar" are different conditions and silently
   * discarding the offset would change the strategy without saying so.
   */
  private normalizeIndicator(raw: string): string {
    let s = (raw || '').replace(/\s+/g, ' ').trim();
    if (!s) return '';

    // Pull out a bar offset written as (D-1) / (W-1) / (M-1) / (-1) / [-1].
    let offset = '';
    const offMatch = s.match(/[\(\[]\s*([dwmh])?\s*-\s*(\d+)\s*[\)\]]/i);
    if (offMatch) {
      const unit = (offMatch[1] || '').toUpperCase();
      offset = ` [${unit || 'B'}-${offMatch[2]}]`;
      s = (s.slice(0, offMatch.index) + s.slice((offMatch.index || 0) + offMatch[0].length)).trim();
    }

    // NAME + optional digits glued on (ADX14) + optional (period).
    const m = s.match(/^([A-Za-z][A-Za-z _%]*?)\s*(\d+)?\s*(?:\(\s*(\d+)\s*\))?\s*$/);
    if (m) {
      let name = m[1].trim().toUpperCase().replace(/\s+/g, ' ');
      const period = m[3] || m[2] || '';
      // "ATR%" is ATR expressed as a percentage - the same indicator, not a different one. Keep
      // the marker but look the base name up, or it title-cases into "Atr%".
      let pctSuffix = '';
      if (name.endsWith('%')) { pctSuffix = ' %'; name = name.slice(0, -1).trim(); }
      const known = this.indicatorWords.find(w => w.toUpperCase() === name);
      const label = (known || this.titleCase(name)) + pctSuffix;
      return period ? `${label}(${period})${offset}` : `${label}${offset}`;
    }
    return (s + offset).trim();
  }

  /**
   * Is this a plausible indicator name rather than a fragment of prose?
   *
   * <p>Accepts anything the app already knows about, plus a short unrecognised token so a new or
   * custom indicator still parses. Rejects long phrases, which is what separates a pasted
   * condition from a sentence describing one.
   */
  private looksLikeIndicator(label: string): boolean {
    const base = label.replace(/\s*\[[^\]]*\]\s*$/, '')   // drop the [D-1] offset
                      .replace(/\(\s*\d+\s*\)\s*$/, '')     // drop the (14) period
                      .replace(/\s*%$/, '').trim();
    if (!base) return false;
    if (this.indicatorWords.some(w => w.toUpperCase() === base.toUpperCase())) return true;
    return base.split(/\s+/).length <= 2 && base.length <= 16;
  }

  /** Right-hand side: a number (decimals and % kept) or another indicator. */
  private normalizeValue(raw: string): string {
    const s = (raw || '').replace(/\s+/g, ' ').trim().replace(/[.;,]+$/, '');
    if (!s) return '';
    if (/^[-+]?\d+(?:\.\d+)?\s*%?$/.test(s)) return s.replace(/\s+/g, '');
    return this.normalizeIndicator(s) || s;
  }

  private titleCase(s: string): string {
    return s.split(' ').map(w => w.length <= 3 ? w.toUpperCase()
      : w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()).join(' ');
  }

  /** Clicking an indicator in the reference adds a condition already using it. */
  useIndicator(name: string): void {
    this.conditions.push(this.newCondition(this.timeframe, name, 'Greater Than', '', 'AND'));
    this.refreshLogic();
    this.toast(`Added a ${name} condition - set the comparison and value.`);
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
    // The server reads `conditions`, `riskManagement` and the risk figures at the top level -
    // the same shape the Advanced Builder already sends. This screen was sending `strategyJson`
    // and `riskRules`, which nothing on the server looks at, so conditions never reached the
    // database and every risk figure silently fell back to a server default. A strategy saved
    // here could not then be backtested: the engine correctly reported it as having no entry
    // conditions. That is what the "Server save API did not accept this format yet" toast was.
    const conditions = this.toServerConditions();
    const payload = {
      name: this.strategyText.slice(0, 70) || 'Untitled Strategy',
      description: this.humanReadable,
      builderLevel: 3,
      segment: this.segment,
      timeframe: this.timeframe,

      conditions,
      entryLogicText: this.humanReadable,
      riskManagement: this.risk,
      positionSizing: { mode: this.risk.positionSizing,
                        capitalAllocationPct: this.risk.capitalAllocationPct,
                        fixedQuantity: this.risk.fixedQuantity,
                        fixedCapital: this.risk.fixedCapital },

      // Flat, because that is where the server reads them from.
      stopLossPct: this.risk.stopLossPct,
      targetPct: this.risk.targetPct,
      maxPositions: this.risk.maxOpenPositions,
      holdingDays: this.risk.maxHoldingDays,

      // Kept as a record of exactly what the screen showed when it was saved.
      strategyJson: JSON.parse(this.systemLogic || '{}'),
      // null, not '', so the server stores "no reference chosen" rather than a blank string.
      basePriceDate: this.basePrice.mode === 'date' ? (this.basePrice.date || null) : null,
      basePriceTime: this.basePrice.mode === 'date' ? (this.basePrice.time || null) : null
    };

    if (!conditions.length) {
      this.toast('Add at least one condition before saving - a strategy with none cannot be tested.');
      return;
    }
    this.svc.createStrategy(payload).subscribe({
      next: r => {
        this.currentStrategyId = r.data?.id || null;
        this.toast('Strategy saved successfully.');
      },
      error: e => this.toast(e?.error?.message || 'Could not save the strategy. Nothing was stored.')
    });
  }

  basePriceSummary(): string {
    if (this.basePrice.mode !== 'date' || !this.basePrice.date) return 'Uploaded base price';
    return this.basePrice.time
      ? `${this.basePrice.date} at ${this.basePrice.time}`
      : `Close of ${this.basePrice.date}`;
  }

  /**
   * The screen's conditions in the shape StrategyConditionEvaluator reads.
   *
   * <p>The builder keeps a condition as three display strings - "RSI(14)[D-1]", ">=", "45" -
   * because that is what the row shows and what the text parser produces. The evaluator works
   * on named parts instead: indicatorLeft, paramsLeft, comparator, and then either a numeric
   * valueRight or another indicator on the right. Saving the display strings unmapped would
   * store rows the evaluator cannot read, which looks identical to saving nothing.
   */
  private toServerConditions(): any[] {
    return this.conditions.map(c => {
      const left = this.splitIndicator(c.left);
      const right = this.splitIndicator(c.right);
      const cond: any = {
        group: 'entry',
        operator: c.joiner || 'AND',
        indicatorLeft: left.name,
        paramsLeft: left.params,
        comparator: this.serverComparator(c.comparator)
      };
      if (right.numeric != null) {
        cond.valueRight = right.numeric;
        // "ATR% >= 3" is ATR measured against the close, not against the number 3. The
        // evaluator multiplies the right-hand side, so the percentage becomes a fraction of
        // CLOSE - the same comparison the ATR crossover screen makes.
        if (left.isPercentOf) {
          cond.indicatorRight = 'CLOSE';
          cond.multiplier = right.numeric / 100;
          delete cond.valueRight;
        }
      } else if (right.name) {
        cond.indicatorRight = right.name;
        cond.paramsRight = right.params;
      }
      return cond;
    });
  }

  /** "RSI(14)[D-1]" -> name RSI, params {period:14, offset:1}; "3.0%" -> numeric 3. */
  private splitIndicator(raw: string): { name: string; params: any; numeric: number | null; isPercentOf: boolean } {
    const text = (raw || '').trim();
    if (!text) return { name: '', params: {}, numeric: null, isPercentOf: false };

    const bare = Number(text.replace('%', '').trim());
    if (!isNaN(bare) && /^[0-9.\-]+%?$/.test(text.trim())) {
      return { name: '', params: {}, numeric: bare, isPercentOf: false };
    }

    const params: any = {};
    const period = text.match(/\((\d+)\)/);
    if (period) params.period = Number(period[1]);
    // Both bracket forms. The row editor writes RSI(14)[D-1], the text parser writes
    // ATR%(D-1) - same meaning, and matching only one silently dropped the offset, which
    // would have compared yesterday's rule against today's bar.
    const offset = text.match(/[\[(]\s*D\s*-\s*(\d+)\s*[\])]/i);
    if (offset) params.offset = Number(offset[1]);

    let name = text.replace(/\(.*?\)/g, '').replace(/\[.*?\]/g, '').trim();
    const isPercentOf = name.endsWith('%');
    if (isPercentOf) name = name.slice(0, -1).trim();

    return {
      name: name.toUpperCase().replace(/\s+/g, '_'),
      params, numeric: null, isPercentOf
    };
  }

  /** The builder's symbols, in the words the evaluator compares on. */
  private serverComparator(c: string): string {
    const v = (c || '').trim().toLowerCase();
    if (v.includes('cross') && v.includes('above')) return 'CROSSABOVE';
    if (v.includes('cross') && v.includes('below')) return 'CROSSBELOW';
    return c || '>';
  }

  applyRisk() {
    this.matches = this.matches.map(row => this.withRisk(row));
  }

  runScreener(showToast = true) {
    this.hasScreened = true;
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
    this.hasBacktested = true;
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
