import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { StrategyBuilderService, COMPARATORS, OPERATORS, TIMEFRAMES, UNIVERSES, CATEGORIES, SEGMENTS } from '../../../core/services/strategy-builder.service';

interface Condition {
  id: string;
  group: string;
  operator: string;
  indicatorLeft: string;
  paramsLeft: { period?: number; timeframe?: string };
  comparator: string;
  indicatorRight: string | null;
  paramsRight: { period?: number; value?: number };
  valueRight: number | null;
  multiplier: number;
  isGroupStart: boolean;
  isGroupEnd: boolean;
}

@Component({
  selector: 'app-advanced-builder',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host{display:block;background:#f4f7fb;color:#101828;min-height:100vh;font-family:Inter,Arial,sans-serif}
    .shell{max-width:1380px;margin:0 auto;padding:22px}
    .topbar{height:64px;background:#fff;border-bottom:1px solid #e5e7eb;display:flex;align-items:center;gap:22px;padding:0 26px;position:sticky;top:0;z-index:5}
    .logo{font-size:24px;font-weight:900;color:#1d9bf0}.search{width:300px;padding:11px 14px;border:1px solid #d8e2ef;border-radius:9px;color:#344054;background:#fff}
    .nav{margin-left:auto;display:flex;gap:18px;color:#475467;font-size:13px;font-weight:700}.avatar{width:36px;height:36px;border-radius:50%;background:#2563eb;color:#fff;display:grid;place-items:center;font-weight:900}
    .notice{background:#efe7ff;color:#7c2dff;border-radius:10px;padding:14px 20px;margin-bottom:18px;font-weight:800;display:flex;justify-content:space-between;gap:14px;flex-wrap:wrap}
    .card{background:#fff;border:1px solid #e6edf5;border-radius:12px;box-shadow:0 8px 24px rgba(16,24,40,.04);padding:20px;margin-bottom:18px}
    .magic{background:#eef9ff;border:1px solid #ccecff;border-radius:11px;padding:15px;margin-bottom:18px}.magic-title{color:#168bdf;font-weight:900;margin-bottom:12px}
    .magic-row,.actions,.tags{display:flex;gap:10px;flex-wrap:wrap}.input,.select{border:1px solid #cbd7e6;border-radius:8px;padding:10px 12px;background:#fff;color:#101828;font-size:13px}.input{flex:1;min-width:220px}
    button{border:0;border-radius:9px;padding:10px 15px;font-weight:900;cursor:pointer}.btn{background:#fff;border:1px solid #d8e2ef;color:#344054}.btn:hover{background:#f8fbff}.primary{background:#39aef6;color:#fff}.success{background:#ecfff5;border:1px solid #22c55e;color:#15803d}.pink{background:#fff;border:1px solid #ff4be3;color:#c026d3}.orange{background:#fff;border:1px solid #fb923c;color:#ea580c}
    .tag{background:#eef8ff;border:1px solid #d7ecff;border-radius:8px;padding:8px 12px;color:#315d92;font-size:12px;cursor:pointer}.builder-title{font-weight:900;margin:8px 0 14px;color:#1f2937}
    .condition{display:grid;grid-template-columns:74px minmax(190px,1.2fr) 78px 120px minmax(170px,1fr) 80px 34px;align-items:center;gap:8px;margin:10px 0;padding:10px 0 10px 16px;border-left:2px dashed #d7e1ed}
    .pill{background:#f8fbff;border:1px solid #d9e5f2;border-radius:7px;padding:9px 10px;color:#2b4a7f;font-size:12px}.operator{color:#b620ff;font-weight:900;font-size:12px;text-align:center}.num{background:#061b4e;color:#fff;border-radius:6px;padding:9px 10px;font-weight:900;font-size:12px}
    .meta-grid,.risk-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px}.label{display:block;color:#667085;font-size:11px;font-weight:800;margin-bottom:4px}
    .results-head{display:flex;justify-content:space-between;align-items:center;gap:12px;margin:26px 0 10px}.table-wrap{background:#fff;border:1px solid #e4eaf2;border-radius:12px;overflow:hidden}.toolbar{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;padding:12px 16px;background:#fff}.tool{background:#6495f9;color:#fff;padding:9px 12px;border-radius:8px;font-weight:900;font-size:12px}
    table{width:100%;border-collapse:collapse}th{background:#eaf2ff;text-align:left;padding:13px;font-size:13px;color:#1f2937}td{padding:13px;border-top:1px solid #edf2f7;font-size:13px}tr:nth-child(even){background:#f7fbff}.green{color:#16a34a;font-weight:900}.link{color:#2176ff;font-weight:900}
    .preview{background:#0b1220;color:#86efac;border-radius:9px;padding:14px;font-family:Consolas,monospace;font-size:12px;white-space:pre-wrap}
    @media(max-width:900px){.topbar{padding:0 12px}.nav{display:none}.search{width:180px}.condition{grid-template-columns:1fr}.operator{text-align:left}.results-head{flex-direction:column;align-items:stretch}}
  `],
  template: `
<div class="topbar">
  <div class="logo">TradeScan</div>
  <input class="search" placeholder="Chart Search..." />
  <div class="nav"><span>Atlas</span><span>Screens</span><span>Charts</span><span>Dashboard</span><span>Help</span></div>
  <div class="avatar">TR</div>
</div>

<main class="shell">
  <div class="notice">
    <span>Scanner workspace ready</span>
    <span>Scan Examples | Strategy Library | Feedback</span>
  </div>

  <h1 style="font-size:24px;font-weight:900;margin:0 0 16px;">Advanced Strategy Builder</h1>

  @if (error()) {
    <div style="background:#fee2e2;border:1px solid #fca5a5;color:#991b1b;border-radius:9px;padding:11px 14px;margin-bottom:14px;font-size:13px;">{{ error() }}</div>
  }

  <section class="card">
    <div class="magic">
      <div class="magic-title">MAGIC FILTERS</div>
      <div class="magic-row">
        <button class="btn" [style.border-color]="magicMode==='append' ? '#39aef6' : '#d8e2ef'" (click)="magicMode='append'">Append</button>
        <button class="btn" [style.border-color]="magicMode==='replace' ? '#39aef6' : '#d8e2ef'" (click)="magicMode='replace'">Replace</button>
        <button class="btn" (click)="addCondition('entry')">Draw</button>
        <input class="input" [(ngModel)]="magicPrompt" placeholder="Scan stocks using simple language like stocks up by 4% and rising volume" />
        <button class="primary" (click)="generateMagic()">Generate</button>
      </div>
      <div class="tags" style="margin-top:12px;">
        @for (tag of magicTags; track tag) {
          <span class="tag" (click)="applyMagicTag(tag)">{{ tag }}</span>
        }
      </div>
    </div>

    <div class="meta-grid" style="margin-bottom:16px;">
      <div><label class="label">Strategy Name</label><input class="input" style="width:100%;" [(ngModel)]="meta.name" placeholder="Strong Stock Identifier"></div>
      <div><label class="label">Category</label><select class="select" style="width:100%;" [(ngModel)]="meta.strategyCategory">@for (c of categories; track c) { <option [value]="c">{{ c }}</option> }</select></div>
      <div><label class="label">Segment</label><select class="select" style="width:100%;" [(ngModel)]="meta.segment">@for (s of segments; track s) { <option [value]="s">{{ s }}</option> }</select></div>
      <div><label class="label">Timeframe</label><select class="select" style="width:100%;" [(ngModel)]="meta.timeframe">@for (t of timeframes; track t) { <option [value]="t">{{ t }}</option> }</select></div>
      <div><label class="label">Universe</label><select class="select" style="width:100%;" [(ngModel)]="meta.universe">@for (u of universes; track u) { <option [value]="u">{{ u }}</option> }</select></div>
    </div>

    <div class="builder-title">Stock passes all of the below filters in cash segment:</div>

    @for (cond of entryConds; track cond.id; let i=$index) {
      <div class="condition">
        <select class="select" [(ngModel)]="cond.operator" [disabled]="i===0">
          @for (op of operators; track op) { <option [value]="op">{{ i === 0 ? 'IF' : op }}</option> }
        </select>
        <select class="pill" [(ngModel)]="cond.indicatorLeft">
          @for (ind of allIndicators; track ind.id) { <option [value]="ind.id">{{ ind.name }}</option> }
        </select>
        @if (needsPeriod(cond.indicatorLeft)) {
          <input class="select" type="number" [(ngModel)]="cond.paramsLeft.period" placeholder="Period">
        } @else {
          <span></span>
        }
        <select class="select" [(ngModel)]="cond.comparator">
          @for (c of comparators; track c) { <option [value]="c">{{ c }}</option> }
        </select>
        <select class="pill" [(ngModel)]="cond.indicatorRight">
          <option [ngValue]="null">Number</option>
          @for (ind of allIndicators; track ind.id) { <option [value]="ind.id">{{ ind.name }}</option> }
        </select>
        @if (cond.indicatorRight) {
          <input class="select" type="number" step="0.1" [(ngModel)]="cond.multiplier" title="Multiplier">
        } @else {
          <input class="num" type="number" step="0.1" [(ngModel)]="cond.valueRight">
        }
        <button class="btn" style="padding:9px 0;" (click)="removeCond('entry', i)">x</button>
      </div>
    }

    @if (!entryConds.length) {
      <div style="border:2px dashed #d7e1ed;border-radius:10px;padding:26px;text-align:center;color:#667085;margin:12px 0;">Add a condition or generate one from Magic Filters.</div>
    }

    <div class="actions" style="margin-top:18px;">
      <button class="success" (click)="preview()">Run Scan</button>
      <button class="success" (click)="save()" [disabled]="saving()">{{ saving() ? 'Saving...' : 'Save Scan' }}</button>
      <button class="pink" (click)="saveAndBacktest()" [disabled]="saving()">Backtest Results</button>
      <button class="orange" (click)="saveAndScan()" [disabled]="saving()">Create Alert</button>
      <button class="btn" (click)="addCondition('entry')">More</button>
    </div>
  </section>

  <section class="card">
    <div style="font-weight:900;margin-bottom:12px;">Risk Management</div>
    <div class="risk-grid">
      <div><label class="label">Stop Loss %</label><input class="input" style="width:100%;" type="number" step="0.5" [(ngModel)]="risk.stopLossPct"></div>
      <div><label class="label">Target %</label><input class="input" style="width:100%;" type="number" step="0.5" [(ngModel)]="risk.targetPct"></div>
      <div><label class="label">Min R:R</label><input class="input" style="width:100%;" type="number" step="0.5" [(ngModel)]="risk.rrMin"></div>
      <div><label class="label">Max Positions</label><input class="input" style="width:100%;" type="number" [(ngModel)]="risk.maxPositions"></div>
      <div><label class="label">Holding Days</label><input class="input" style="width:100%;" type="number" [(ngModel)]="risk.holdingDays"></div>
    </div>
  </section>

  @if (showPreview()) {
    <section class="card">
      <div style="display:flex;justify-content:space-between;gap:10px;align-items:center;margin-bottom:10px;">
        <strong>Logic Preview</strong>
        <button class="btn" (click)="showPreview.set(false)">Close</button>
      </div>
      <div class="preview">{{ fullLogic }}</div>
    </section>
  }

  <div class="results-head">
    <h2 style="margin:0;font-size:22px;font-weight:900;">Stocks</h2>
    <input class="search" placeholder="Search stocks" />
  </div>

  <section class="table-wrap">
    <div class="toolbar">
      <div style="display:flex;gap:8px;flex-wrap:wrap;"><span class="tool">Customize columns</span><span class="tool">Copy</span><span class="tool">CSV</span><span class="tool">Excel</span></div>
      <button class="btn">Settings</button>
    </div>
    <table>
      <thead><tr><th>Sr.</th><th>Stock Name</th><th>Symbol</th><th>Close</th><th>% Change</th><th>Volume</th></tr></thead>
      <tbody>
        @for (row of scanRows; track row.symbol; let i=$index) {
          <tr><td>{{ i + 1 }}</td><td class="link">{{ row.name }}</td><td class="link">{{ row.symbol }}</td><td>{{ row.close | number:'1.2-2' }}</td><td class="green">{{ row.change }}%</td><td>{{ row.volume }}</td></tr>
        }
      </tbody>
    </table>
  </section>
</main>
  `
})
export class AdvancedBuilderComponent implements OnInit {
  saving = signal(false);
  error = signal('');
  showPreview = signal(false);
  fullLogic = '';
  magicPrompt = '';
  magicMode: 'append' | 'replace' = 'append';

  meta = { name:'Strong Stock Identifier', strategyCategory:'swing', segment:'EQ-SWG', timeframe:'Daily', universe:'NSE_500', description:'' };
  risk = { stopLossPct:5, targetPct:10, rrMin:2, trailingSL:'', maxPositions:5, maxPerSector:2, riskPerTradePct:2, dailyLossLimit:3, monthlyDrawdown:10, holdingDays:22 };

  entryConds: Condition[] = [];
  exitConds: Condition[] = [];

  comparators = COMPARATORS;
  operators = OPERATORS;
  timeframes = TIMEFRAMES;
  universes = UNIVERSES;
  categories = CATEGORIES;
  segments = SEGMENTS;

  indicatorGroups: any = {};
  allIndicators: any[] = [];

  readonly magicTags = [
    'RSI crossed above 55',
    'latest close between 100 and 2000',
    'volume above 20 day average',
    'close above weekly high',
    'market cap above 1000 crore'
  ];

  readonly scanRows = [
    { name:'Nocil Limited', symbol:'NOCIL', close:190.82, change:20.00, volume:'2,65,19,893' },
    { name:'Subros Limited', symbol:'SUBROS', close:864.30, change:15.63, volume:'62,10,667' },
    { name:'EMS Ltd', symbol:'EMSLIMITED', close:402.80, change:15.04, volume:'59,35,625' },
    { name:'Igarashi Motors India Limited', symbol:'IGARASHI', close:472.25, change:13.99, volume:'36,13,665' }
  ];

  constructor(private svc: StrategyBuilderService, private router: Router) {}

  ngOnInit() {
    this.svc.getIndicators().subscribe({ next: r => {
      this.indicatorGroups = r.data || {};
      this.allIndicators = Object.values(this.indicatorGroups).flat() as any[];
      if (!this.entryConds.length) {
        this.generateStarterConditions();
      }
    }});
  }

  generateStarterConditions() {
    this.entryConds = [];
    this.addMagicCondition('RSI', '>', 55, null, 14, 'Weekly');
    this.addMagicCondition('CLOSE', '>', null, 'EMA', undefined, 'Weekly', 1);
    this.addMagicCondition('VOLUME', '>', null, 'VOLUME_SMA', undefined, 'Weekly', 1.3);
  }

  generateMagic() {
    if (this.magicMode === 'replace') this.entryConds = [];
    const prompt = this.magicPrompt.toLowerCase();
    if (!this.meta.name.trim()) this.meta.name = 'Magic Filter Strategy';

    if (prompt.includes('volume')) this.addMagicCondition('VOLUME', '>', null, 'VOLUME_SMA', undefined, this.meta.timeframe, 1.3);
    if (prompt.includes('rsi')) this.addMagicCondition('RSI', '>', 55, null, 14, this.meta.timeframe);
    if (prompt.includes('close') || prompt.includes('price')) this.addMagicCondition('CLOSE', '>', 100, null, undefined, this.meta.timeframe);
    if (prompt.includes('market cap')) this.addMagicCondition('MARKET_CAP', '>=', 1000, null, undefined, this.meta.timeframe);
    if (!prompt.trim() || !this.entryConds.length) this.addMagicCondition('RSI', '>', 55, null, 14, this.meta.timeframe);
    this.preview();
  }

  applyMagicTag(tag: string) {
    this.magicPrompt = tag;
    this.generateMagic();
  }

  addCondition(group: 'entry'|'exit', isGroupStart=false, isGroupEnd=false) {
    const cond: Condition = {
      id: Date.now() + Math.random() + '',
      group, operator: 'AND',
      indicatorLeft: this.allIndicators[0]?.id || 'RSI',
      paramsLeft: { period: 14, timeframe: this.meta.timeframe },
      comparator: '>', indicatorRight: null, paramsRight: {}, valueRight: 60,
      multiplier: 1.0, isGroupStart, isGroupEnd
    };
    if (group === 'entry') this.entryConds.push(cond);
    else this.exitConds.push(cond);
  }

  private addMagicCondition(left: string, comparator: string, value: number | null, right: string | null, period?: number, timeframe?: string, multiplier = 1) {
    const cond: Condition = {
      id: Date.now() + Math.random() + '',
      group: 'entry',
      operator: 'AND',
      indicatorLeft: this.findIndicator(left),
      paramsLeft: { period, timeframe: timeframe || this.meta.timeframe },
      comparator,
      indicatorRight: right ? this.findIndicator(right) : null,
      paramsRight: right ? { period: period || 20 } : {},
      valueRight: value,
      multiplier,
      isGroupStart: false,
      isGroupEnd: false
    };
    this.entryConds.push(cond);
  }

  private findIndicator(id: string): string {
    const wanted = id.toUpperCase();
    return this.allIndicators.find(ind => String(ind.id).toUpperCase() === wanted || String(ind.name).toUpperCase().includes(wanted))?.id || id;
  }

  removeCond(group: 'entry'|'exit', i: number) {
    if (group === 'entry') this.entryConds.splice(i, 1);
    else this.exitConds.splice(i, 1);
  }

  needsPeriod(ind: string | null): boolean {
    if (!ind) return false;
    return ['RSI','EMA','SMA','WMA','ATR','ADX','VOLUME_SMA','STOCH_RSI_K','ROC','BB_UPPER','BB_LOWER','BB_MID'].includes(String(ind).toUpperCase());
  }

  getLogicPreview(group: 'entry'|'exit'): string {
    const conds = group === 'entry' ? this.entryConds : this.exitConds;
    return conds.map((c, i) => {
      const op = i > 0 ? c.operator + ' ' : '';
      const left = c.indicatorLeft + (c.paramsLeft.period ? `(${c.paramsLeft.period})` : '') +
        (c.paramsLeft.timeframe ? `[${c.paramsLeft.timeframe}]` : '');
      const right = c.indicatorRight
        ? (c.multiplier !== 1 ? `${c.multiplier}x ` : '') + c.indicatorRight + (c.paramsRight.period ? `(${c.paramsRight.period})` : '')
        : String(c.valueRight ?? '');
      return `${op}${left} ${c.comparator} ${right}`;
    }).join('\n');
  }

  preview() {
    this.fullLogic = `STRATEGY: ${this.meta.name || 'Unnamed'}\n` +
      `Segment: ${this.meta.segment} | TF: ${this.meta.timeframe} | Universe: ${this.meta.universe}\n\n` +
      `ENTRY:\n${this.getLogicPreview('entry') || '(none)'}\n\n` +
      `EXIT:\n${this.getLogicPreview('exit') || '(SL: ' + this.risk.stopLossPct + '% | Target: ' + this.risk.targetPct + '%)'}\n\n` +
      `RISK: SL ${this.risk.stopLossPct}% | Target ${this.risk.targetPct}% | R:R ${this.risk.rrMin} | Max Positions ${this.risk.maxPositions}`;
    this.showPreview.set(true);
  }

  save(navigateAfter?: string) {
    if (!this.meta.name) { this.error.set('Strategy name required.'); return; }
    this.saving.set(true);
    this.error.set('');
    const conditions = [
      ...this.entryConds.map(c => ({...c, group:'entry'})),
      ...this.exitConds.map(c => ({...c, group:'exit'}))
    ];
    this.svc.createStrategy({
      ...this.meta, ...this.risk, builderLevel: 2,
      conditions, entryLogicText: this.getLogicPreview('entry'),
      exitLogicText: this.getLogicPreview('exit'),
      riskManagement: this.risk
    }).subscribe({
      next: r => {
        this.saving.set(false);
        const id = r.data?.id;
        if (navigateAfter === 'scan') this.router.navigate(['/strategy-builder/my-strategies']);
        else if (navigateAfter === 'backtest') this.router.navigate(['/strategy-builder/backtest', id]);
        else this.router.navigate(['/strategy-builder/my-strategies']);
      },
      error: e => { this.error.set(e.error?.message || 'Save failed.'); this.saving.set(false); }
    });
  }

  saveAndScan() { this.save('scan'); }
  saveAndBacktest() { this.save('backtest'); }
}
