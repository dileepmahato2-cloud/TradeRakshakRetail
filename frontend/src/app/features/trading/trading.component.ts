import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ApiService } from '../../core/services/api.service';
import { LivePriceService } from '../../core/services/live-price.service';
import { environment } from '../../../environments/environment';
import { ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-trading',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host{display:block;min-height:100vh;background:#0b1e3d;color:#e2e8f0}.page{padding:20px}.hero{background:linear-gradient(120deg,#102447,#0d2e37);border:1px solid #284466;border-top:3px solid #16d6a4;border-radius:8px;padding:20px;margin-bottom:14px}.title{font-size:28px;font-weight:900;color:#fff}.subtle{color:#93b4d8;font-size:12px}.tabs{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px}.tabs button{border:1px solid #31506f;background:#071426;color:#bcd2ea;border-radius:7px;padding:10px 14px;font-weight:900;cursor:pointer}.tabs button.active{background:#1d7be8;color:#fff}.grid{display:grid;gap:14px}.widgets{grid-template-columns:repeat(auto-fit,minmax(150px,1fr));margin-bottom:14px}.widget,.panel{background:#10233d;border:1px solid #243d5d;border-radius:8px}.widget{padding:12px}.widget .value{font-size:21px;font-weight:900;color:#fff;margin-top:6px}.panel{padding:16px}.panel-title{font-size:18px;font-weight:900;color:#fff;margin-bottom:12px}.trade-layout{display:grid;grid-template-columns:minmax(340px,1.05fr) minmax(320px,.95fr);gap:14px}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}label{display:block;color:#93b4d8;font-size:12px;margin-bottom:5px}.input,.select,.textarea{width:100%;background:#071426;border:1px solid #31506f;border-radius:6px;color:#fff;padding:9px 10px;min-height:38px}.textarea{min-height:90px;resize:vertical}.seg{display:flex;background:#071426;border:1px solid #31506f;border-radius:7px;overflow:hidden}.seg button{flex:1;border:0;background:transparent;color:#bcd2ea;padding:10px;font-weight:800;cursor:pointer}.seg button.active{background:#1d6adb;color:#fff}.seg button.sell.active{background:#dc2626}.btn{border:1px solid #334865;border-radius:7px;padding:10px 14px;font-size:12px;font-weight:900;cursor:pointer;color:#e5eefb;background:#1b2940}.btn.primary{background:#2563eb;border-color:#2563eb}.btn.green{background:#16803f;border-color:#22c55e}.btn.red{background:#b91c1c;border-color:#ef4444}.btn.warn{background:#f59e0b;border-color:#f59e0b;color:#111827}.btn:disabled{opacity:.5;cursor:not-allowed}.actions{display:flex;gap:8px;flex-wrap:wrap;align-items:center}.calc{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.calc div{border:1px solid #25425f;background:#0b1829;border-radius:7px;padding:9px}.calc strong{display:block;color:#fff;font-size:16px;margin-top:3px}.table-wrap{overflow:auto;border:1px solid #263f5d;border-radius:7px}table{width:100%;border-collapse:collapse;min-width:820px}th,td{padding:9px 10px;border-bottom:1px solid #203650;text-align:left;font-size:12px;white-space:nowrap}th{background:#173456;color:#fff;text-transform:uppercase;font-size:11px}.chip{display:inline-flex;border:1px solid #355274;border-radius:999px;padding:4px 8px;font-size:11px;color:#cbd5e1}.ok{border-color:#15803d;color:#86efac;background:#0d2b1d}.warnchip{border-color:#a16207;color:#facc15;background:#2a210b}.bad{border-color:#b91c1c;color:#fecaca;background:#2b1111}.two{display:grid;grid-template-columns:1fr 1fr;gap:14px}.confirm{position:fixed;inset:0;background:rgba(0,0,0,.58);display:flex;align-items:center;justify-content:center;z-index:80;padding:16px}.dialog{width:min(560px,100%);background:#10233d;border:1px solid #31506f;border-radius:8px;padding:20px;box-shadow:0 18px 50px rgba(0,0,0,.4)}.check-row{display:grid;grid-template-columns:auto 1fr auto;gap:8px;align-items:center;border:1px solid #263f5d;border-radius:7px;padding:8px;margin-bottom:8px}.mini{border:1px solid #263f5d;border-radius:8px;padding:12px;background:#0b1829}@media(max-width:1000px){.trade-layout,.two,.form-grid{grid-template-columns:1fr}}
  `],
  template: `
<div class="page">
  <section class="hero">
    <div class="title">Trading</div>
    <div class="subtle">Trade execution, strategy automation, pre-trade checklist validation, and complete trading journal.</div>
  </section>

  <div class="tabs">
    <button [class.active]="activeTab==='desk'" (click)="activeTab='desk'">Trade Execution</button>
    <button [class.active]="activeTab==='checklist'" (click)="activeTab='checklist'">Manage Checklist Items</button>
    <button [class.active]="activeTab==='journal'" (click)="activeTab='journal'">Trading Journal</button>
  </div>

  @if (loading()) {
    <div class="panel">Loading Trading module...</div>
  } @else {
    @if (activeTab==='desk') {
      <div class="grid widgets">
        <div class="widget"><div class="subtle">Today's P&L</div><div class="value" [style.color]="summary.todayPnl >= 0 ? '#5eead4' : '#ff6b6b'">{{ money(summary.todayPnl) }}</div></div>
        <div class="widget"><div class="subtle">Active Strategies</div><div class="value">{{ summary.activeStrategies || 0 }}</div></div>
        <div class="widget"><div class="subtle">Open Positions</div><div class="value">{{ summary.openPositions || 0 }}</div></div>
        <div class="widget"><div class="subtle">Auto Trade Status</div><div class="value">{{ summary.autoTradeStatus || 'Inactive' }}</div></div>
        <div class="widget"><div class="subtle">Available Margin</div><div class="value">{{ money(summary.availableMargin) }}</div></div>
        <div class="widget"><div class="subtle">Risk Utilization</div><div class="value">{{ summary.riskUtilizationPct || 0 }}%</div></div>
      </div>

      <div class="trade-layout">
        <section class="panel">
          <div class="panel-title">Trade Execution</div>
          <div class="form-grid">
            <div><label>Trading Mode</label><select class="select" [(ngModel)]="order.mode" (change)="preview()"><option value="PAPER">Paper Trading</option><option value="REAL">Real Trading</option></select></div>
            <div><label>Buy / Sell</label><div class="seg"><button [class.active]="order.side==='BUY'" (click)="order.side='BUY'; preview(); loadAssignedChecklist()">Buy</button><button class="sell" [class.active]="order.side==='SELL'" (click)="order.side='SELL'; preview(); loadAssignedChecklist()">Sell</button></div></div>
            <div><label>Strategy</label><select class="select" [(ngModel)]="selectedStrategyKey" (change)="selectStrategy()">@for (s of strategies; track s.key) { <option [ngValue]="s.key">{{ s.name }}</option> }</select></div>
            <div><label>Broker for Real Trading</label><select class="select" [(ngModel)]="order.brokerId" [disabled]="order.mode==='PAPER'"><option [ngValue]="null">Select broker</option>@for (b of brokers; track b.id) { <option [ngValue]="b.id">{{ b.brokerLabel }} {{ b.readOnly ? '(Read Only)' : '' }}</option> }</select></div>
            <div><label>Order Type</label><select class="select" [(ngModel)]="order.orderType" (change)="preview()"><option value="MARKET">Market Order</option><option value="LIMIT">Limit Order</option><option value="STOP_LOSS">Stop Loss Order</option></select></div>
            <div><label>Exchange</label><select class="select" [(ngModel)]="order.exchange"><option>NSE</option><option>BSE</option></select></div>
            <div><label>Symbol</label><input class="input" [(ngModel)]="order.symbol" (input)="preview()" placeholder="RELIANCE"></div>
            <div><label>Sector</label><input class="input" [(ngModel)]="order.sector" placeholder="Energy"></div>
            <div><label>Price</label><input class="input" type="number" [(ngModel)]="order.price" (input)="preview()"></div>
            <div><label>Investment Amount</label><input class="input" type="number" [(ngModel)]="order.investmentAmount" (input)="preview()"></div>
            <div><label>Quantity</label><input class="input" type="number" [(ngModel)]="order.quantity" (input)="preview()"></div>
            <div><label>Dhan Security ID</label><input class="input" [(ngModel)]="order.securityId" placeholder="Required for real Dhan orders"></div>
            <div><label>Stop Loss %</label><input class="input" type="number" [(ngModel)]="order.stopLossPct" (input)="preview()"></div>
            <div><label>Trailing Stop Loss %</label><input class="input" type="number" [(ngModel)]="order.trailingStopLossPct" (input)="preview()"></div>
            <div><label>Target %</label><input class="input" type="number" [(ngModel)]="order.targetPct" (input)="preview()"></div>
            <div><label>Available Cash</label><input class="input" type="number" [(ngModel)]="order.availableCash" (input)="preview()"></div>
          </div>
          <div class="actions" style="justify-content:flex-end;margin-top:12px">
            <button class="btn" (click)="preview()">Calculate</button>
            <button [class]="order.side==='BUY' ? 'btn green' : 'btn red'" (click)="openConfirm()">{{ order.side }} {{ order.mode }}</button>
          </div>
        </section>

        <section class="panel">
          <div class="panel-title">Order Calculation</div>
          <div class="calc">
            <div><span class="subtle">Quantity</span><strong>{{ calc.quantity || 0 }}</strong></div>
            <div><span class="subtle">Order Value</span><strong>{{ money(calc.orderValue) }}</strong></div>
            <div><span class="subtle">Margin Utilization</span><strong>{{ calc.marginUtilizationPct || 0 }}%</strong></div>
            <div><span class="subtle">Cash After Order</span><strong>{{ money(calc.cashAfterOrder) }}</strong></div>
            <div><span class="subtle">Stop Loss Price</span><strong>{{ number(calc.stopLossPrice) }}</strong></div>
            <div><span class="subtle">Trailing Stop</span><strong>{{ number(calc.trailingStopPrice) }}</strong></div>
            <div><span class="subtle">Target Price</span><strong>{{ number(calc.targetPrice) }}</strong></div>
            <div><span class="subtle">Risk Reward</span><strong>{{ calc.riskRewardRatio || 0 }}</strong></div>
          </div>
        </section>
      </div>

      <div class="two" style="margin-top:14px">
        <section class="panel">
          <div class="panel-title">Pre-Trade Checklist</div>
          @if (assignedChecklist.length) {
            @for (t of assignedChecklist; track t.id) {
              <div class="mini" style="margin-bottom:10px">
                <strong>{{ t.templateName }}</strong> <span class="chip">{{ t.strategyName }}</span> <span class="chip">{{ t.tradeType }}</span>
                @for (item of t.items; track item.id) {
                  <label class="check-row"><input type="checkbox" [checked]="isChecklistDone(item.id)" (change)="toggleChecklistDone(item.id)"><span>{{ item.itemText }}</span><span [class]="item.mandatory ? 'chip warnchip' : 'chip'">{{ item.mandatory ? 'Mandatory' : 'Optional' }}</span></label>
                }
                @if (t.overrideAllowed) { <input class="input" [(ngModel)]="order.overrideReason" placeholder="Admin override reason, if required"> }
              </div>
            }
          } @else {
            <div class="subtle">No checklist assigned to this strategy and trade type.</div>
          }
        </section>

        <section class="panel">
          <div class="panel-title">Strategy Trading Engine</div>
          <div class="form-grid">
            <div><label>Strategy</label><input class="input" [(ngModel)]="auto.strategyName"></div>
            <div><label>Mode</label><select class="select" [(ngModel)]="auto.mode"><option value="PAPER">Paper</option><option value="REAL">Real</option></select></div>
            <div><label>Buy Time</label><input class="input" type="time" [(ngModel)]="auto.buyTime"></div>
            <div><label>Sell Time</label><input class="input" type="time" [(ngModel)]="auto.sellTime"></div>
            <div><label>Max Capital Allocation</label><input class="input" type="number" [(ngModel)]="auto.maxCapitalAllocation"></div>
            <div><label>Maximum Daily Trades</label><input class="input" type="number" [(ngModel)]="auto.maxDailyTrades"></div>
            <div><label>Risk Per Trade %</label><input class="input" type="number" [(ngModel)]="auto.riskPerTradePct"></div>
            <div><label>Maximum Daily Loss</label><input class="input" type="number" [(ngModel)]="auto.maxDailyLossLimit"></div>
          </div>
          <div class="actions" style="margin-top:12px">
            <label><input type="checkbox" [(ngModel)]="auto.autoBuy"> Auto Buy</label>
            <label><input type="checkbox" [(ngModel)]="auto.autoSell"> Auto Sell</label>
            <label><input type="checkbox" [(ngModel)]="auto.automatedExecution"> Automated Trade Execution</label>
            <label><input type="checkbox" [(ngModel)]="auto.active"> Strategy Active</label>
          </div>
          <div class="actions" style="justify-content:flex-end;margin-top:12px">
            <button class="btn" (click)="runStockTracker('AUTO_BUY')">Review Stock Tracker Buy</button>
            <button class="btn" (click)="runStockTracker('AUTO_SELL')">Review Stock Tracker Sell</button>
            <button class="btn primary" (click)="saveAuto()">Save Strategy Settings</button>
          </div>
        </section>
      </div>

      <section class="panel" style="margin-top:14px">
        <div class="panel-title">Today top 10 Ranking Stocks</div>
        <div class="table-wrap"><table><thead><tr><th>Rank</th><th>Symbol</th><th>Sector</th><th>Price</th><th>Return</th><th>Action</th></tr></thead><tbody>
          @for (s of top10; track s.symbol) { <tr><td>{{ s.rank }}</td><td><strong>{{ s.symbol }}</strong></td><td>{{ s.sector || '-' }}</td><td>{{ number(s.price) }}</td><td>{{ number(s.returnPct) }}%</td><td><button class="btn" (click)="useTop10(s)">Use</button></td></tr> }
          @if (!top10.length) { <tr><td colspan="6" style="text-align:center;color:#94a3b8">No Top 10 data available yet.</td></tr> }
        </tbody></table></div>
      </section>
    }

    @if (activeTab==='checklist') {
      <div class="two">
        <section class="panel">
          <div class="panel-title">{{ checklistForm.id ? 'Edit Checklist Template' : 'Create Checklist Template' }}</div>
          <div class="form-grid">
            <div><label>Template Name</label><input class="input" [(ngModel)]="checklistForm.templateName" placeholder="Trend Confirmation"></div>
            <div><label>Strategy</label><select class="select" [(ngModel)]="checklistForm.strategyKey" (change)="syncChecklistStrategy()">@for (s of strategies; track s.key) { <option [ngValue]="s.key">{{ s.name }}</option> }<option value="ALL">All Strategies</option></select></div>
            <div><label>Trade Type</label><select class="select" [(ngModel)]="checklistForm.tradeType"><option>ALL</option><option>BUY</option><option>SELL</option></select></div>
            <div><label>Apply To</label><select class="select" [(ngModel)]="checklistForm.appliesTo"><option>Manual Trading</option><option>Semi-Automated Trading</option><option>Fully Automated Trading</option><option>ALL</option></select></div>
          </div>
          <div class="actions" style="margin:12px 0">
            <label><input type="checkbox" [(ngModel)]="checklistForm.enabled"> Enabled</label>
            <label><input type="checkbox" [(ngModel)]="checklistForm.overrideAllowed"> Admin Override Allowed</label>
          </div>
          @for (item of checklistForm.items; track $index) {
            <div class="check-row">
              <input type="checkbox" [(ngModel)]="item.mandatory">
              <input class="input" [(ngModel)]="item.itemText" placeholder="Checklist item">
              <button class="btn red" (click)="removeChecklistItem($index)">Delete</button>
            </div>
          }
          <div class="actions" style="justify-content:space-between;margin-top:12px">
            <button class="btn" (click)="addChecklistItem()">Add Item</button>
            <div class="actions"><button class="btn" (click)="resetChecklistForm()">Clear</button><button class="btn primary" (click)="saveChecklist()">Save Checklist</button></div>
          </div>
        </section>

        <section class="panel">
          <div class="panel-title">Checklist Templates</div>
          <div class="table-wrap"><table><thead><tr><th>Name</th><th>Strategy</th><th>Type</th><th>Items</th><th>Status</th><th>Actions</th></tr></thead><tbody>
            @for (t of checklists; track t.id) {
              <tr><td>{{ t.templateName }}</td><td>{{ t.strategyName }}</td><td>{{ t.tradeType }}</td><td>{{ t.items?.length || 0 }}</td><td><span [class]="t.enabled ? 'chip ok' : 'chip bad'">{{ t.enabled ? 'Enabled' : 'Disabled' }}</span></td><td class="actions"><button class="btn" (click)="editChecklist(t)">Edit</button><button class="btn" (click)="toggleChecklist(t)">{{ t.enabled ? 'Disable' : 'Enable' }}</button><button class="btn red" (click)="deleteChecklist(t)">Delete</button></td></tr>
            }
            @if (!checklists.length) { <tr><td colspan="6" style="text-align:center;color:#94a3b8">No checklist templates yet.</td></tr> }
          </tbody></table></div>
        </section>
      </div>
    }

    @if (activeTab==='journal') {
      <div class="grid widgets">
        <div class="widget"><div class="subtle">Total Trades</div><div class="value">{{ journalAnalytics.totalTrades || 0 }}</div></div>
        <div class="widget"><div class="subtle">Win Rate</div><div class="value">{{ journalAnalytics.winRate || 0 }}%</div></div>
        <div class="widget"><div class="subtle">Loss Rate</div><div class="value">{{ journalAnalytics.lossRate || 0 }}%</div></div>
        <div class="widget"><div class="subtle">Average Profit</div><div class="value">{{ money(journalAnalytics.averageProfit) }}</div></div>
        <div class="widget"><div class="subtle">Average Loss</div><div class="value">{{ money(journalAnalytics.averageLoss) }}</div></div>
        <div class="widget"><div class="subtle">Net P&L</div><div class="value">{{ money(journalAnalytics.netPnl) }}</div></div>
      </div>
      <section class="panel">
        <div class="actions" style="justify-content:space-between;margin-bottom:12px">
          <div class="panel-title" style="margin:0">Trading Journal</div>
          <div class="actions"><input class="input" style="width:220px" [(ngModel)]="journalSearch" placeholder="Search symbol"><button class="btn" (click)="loadJournal()">Filter</button><button class="btn primary" (click)="exportJournal()">Export Excel CSV</button><button class="btn" (click)="printPage()">Print</button></div>
        </div>
        <div class="table-wrap"><table><thead><tr><th>Date</th><th>Trade ID</th><th>Strategy</th><th>Mode</th><th>Type</th><th>Symbol</th><th>Qty</th><th>Buy</th><th>Sell</th><th>Current</th><th>SL</th><th>Trail SL</th><th>Target</th><th>Capital</th><th>Margin</th><th>P&L</th><th>Outcome</th><th>Exit Reason</th></tr></thead><tbody>
          @for (j of journal; track j.tradeRef + j.createdAt) {
            <tr><td>{{ j.createdAt }}</td><td>{{ j.tradeRef }}</td><td>{{ j.strategyName }}</td><td>{{ j.tradingMode }}</td><td>{{ j.tradeType }}</td><td><strong>{{ j.symbol }}</strong></td><td>{{ j.quantity }}</td><td>{{ number(j.buyPrice) }}</td><td>{{ number(j.sellPrice) }}</td><td [title]="j.livePnl ? 'Live price - this row is still open, so P&L moves with it.' : 'Trade is closed; P&L is the realised figure.'">{{ j.livePnl ? number(j.currentPrice) : '-' }}</td><td>{{ number(j.stopLoss) }}</td><td>{{ number(j.trailingStopLoss) }}</td><td>{{ number(j.targetPrice) }}</td><td>{{ money(j.capitalUtilized) }}</td><td>{{ j.marginUtilized }}%</td><td [style.color]="j.netProfitLoss >= 0 ? '#5eead4' : '#ff6b6b'">{{ money(j.netProfitLoss) }}</td><td><span class="chip">{{ j.outcome }}</span></td><td>{{ j.exitReason }}</td></tr>
          }
          @if (!journal.length) { <tr><td colspan="18" style="text-align:center;color:#94a3b8">No trading journal records yet.</td></tr> }
        </tbody></table></div>
      </section>
    }
  }

  @if (confirmOpen) {
    <div class="confirm">
      <div class="dialog">
        <div class="panel-title">Confirm {{ order.side }} Order</div>
        <p class="subtle">Review this order before placing it. Real Trading can use real money when broker setup allows it.</p>
        <div class="calc" style="margin:12px 0">
          <div><span class="subtle">Mode</span><strong>{{ order.mode }}</strong></div>
          <div><span class="subtle">Symbol</span><strong>{{ order.symbol }}</strong></div>
          <div><span class="subtle">Qty</span><strong>{{ calc.quantity }}</strong></div>
          <div><span class="subtle">Value</span><strong>{{ money(calc.orderValue) }}</strong></div>
        </div>
        <label><input type="checkbox" [(ngModel)]="order.confirmed"> I confirm this trading action.</label>
        <div class="actions" style="justify-content:flex-end;margin-top:14px">
          <button class="btn" (click)="confirmOpen=false">Cancel</button>
          <button [class]="order.side==='BUY' ? 'btn green' : 'btn red'" (click)="submitOrder()" [disabled]="!order.confirmed">Submit Order</button>
        </div>
      </div>
    </div>
  }
</div>
  `
})
export class TradingComponent implements OnInit, OnDestroy {
  loading = signal(true);
  activeTab = 'desk';
  summary: any = {};
  strategies: any[] = [];
  brokers: any[] = [];
  top10: any[] = [];
  openPositions: any[] = [];
  closedPositions: any[] = [];
  auditLogs: any[] = [];
  checklists: any[] = [];
  assignedChecklist: any[] = [];
  completedChecklistItemIds: number[] = [];
  journal: any[] = [];
  journalAnalytics: any = {};
  journalSearch = '';
  calc: any = {};
  confirmOpen = false;
  selectedStrategyKey = 'stock_tracker';
  auto: any = {};
  checklistForm: any = {};
  order: any = {
    mode: 'PAPER', side: 'BUY', orderType: 'MARKET', exchange: 'NSE', exchangeSegment: 'NSE_EQ',
    productType: 'CNC', symbol: '', sector: '', price: 0, investmentAmount: 50000, quantity: 0,
    stopLossPct: 3, trailingStopLossPct: 2, targetPct: 6, availableCash: 100000,
    brokerId: null, securityId: '', confirmed: false, strategyKey: 'stock_tracker', strategyName: 'Stock Tracker',
    completedChecklistItemIds: [], overrideReason: ''
  };
  private liveSub?: Subscription;

  constructor(private api: ApiService, private http: HttpClient, private livePrices: LivePriceService,
              private route: ActivatedRoute) {}
  ngOnInit() {
    // The sidebar's "Executed Trades" links straight here. Without this it landed on the
    // trade desk and the journal it promised was one unmarked click away.
    const tab = this.route.snapshot.queryParamMap.get('tab');
    if (tab === 'journal' || tab === 'checklist' || tab === 'desk') this.activeTab = tab;
    this.resetChecklistForm();
    this.load();
    this.livePrices.connect();
    this.liveSub = this.livePrices.quote$.subscribe(q => this.applyLiveQuote(q));
  }
  ngOnDestroy() { this.liveSub?.unsubscribe(); this.livePrices.release(); }

  load() {
    this.api.get<any>('/trading/dashboard').subscribe({
      next: r => {
        const d = r.data || {};
        this.summary = d.summary || {};
        this.strategies = d.strategies || [];
        this.brokers = d.brokers || [];
        this.top10 = d.top10 || [];
        this.openPositions = d.openPositions || [];
        this.closedPositions = d.closedPositions || [];
        this.auditLogs = d.auditLogs || [];
        this.checklists = d.checklists || [];
        this.journal = d.journal || [];
        this.journalAnalytics = d.journalAnalytics || {};
        this.auto = d.autoSettings || {};
        this.order.availableCash = this.summary.availableFunds || 100000;
        if (!this.order.symbol && this.top10.length) this.useTop10(this.top10[0]);
        this.selectStrategy();
        this.preview();
        this.loadAssignedChecklist();
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  selectStrategy() {
    const selected = this.strategies.find(s => s.key === this.selectedStrategyKey);
    this.order.strategyKey = selected?.key || 'stock_tracker';
    this.order.strategyName = selected?.name || 'Stock Tracker';
    this.auto.strategyKey = this.order.strategyKey;
    this.auto.strategyName = this.order.strategyName;
    this.loadAssignedChecklist();
  }

  useTop10(row: any) { this.order.symbol = row.symbol || ''; this.order.sector = row.sector || ''; this.order.price = Number(row.price || 0); this.preview(); }
  private applyLiveQuote(q: any) {
    const symbol = this.normalizeSymbol(q?.symbol);
    const price = Number(q?.currentPrice || q?.ltp || 0);
    if (!symbol || price <= 0) return;
    this.top10 = this.top10.map(s => this.normalizeSymbol(s.symbol) === symbol ? { ...s, price } : s);
    if (this.normalizeSymbol(this.order.symbol) === symbol) {
      this.order.price = price;
      this.preview();
    }
  }
  private normalizeSymbol(value: any): string {
    return String(value || '').toUpperCase().replace('.NS', '').replace('.BO', '').replace(/-(EQ|BE|BZ|SM|ST)$/,'').replace(/\s+/g, '');
  }
  preview() { this.api.post<any>('/trading/order/preview', this.order).subscribe({ next: r => { this.calc = r.data || {}; if (!this.order.quantity) this.order.quantity = this.calc.quantity || 0; } }); }
  openConfirm() { this.order.confirmed = false; this.confirmOpen = true; }
  submitOrder() {
    this.order.quantity = this.order.quantity || this.calc.quantity || 0;
    this.order.completedChecklistItemIds = this.completedChecklistItemIds;
    this.api.post<any>('/trading/order/submit', this.order).subscribe({
      next: r => { alert(r.message || 'Order submitted.'); this.confirmOpen = false; this.completedChecklistItemIds = []; this.load(); },
      error: e => alert(e.error?.message || 'Order could not be submitted.')
    });
  }

  saveAuto() {
    this.auto.strategyKey = this.order.strategyKey;
    this.auto.strategyName = this.order.strategyName;
    this.api.post<any>('/trading/auto-settings', this.auto).subscribe({
      next: r => { alert(r.message || 'Trading settings saved.'); this.auditLogs = r.data?.auditLogs || this.auditLogs; },
      error: e => alert(e.error?.message || 'Settings could not be saved.')
    });
  }

  runStockTracker(action: string) {
    this.api.post<any>('/trading/stock-tracker/run', { action, mode: this.auto.mode || 'PAPER' }).subscribe({
      next: r => { alert(r.message || 'Stock Tracker review completed.'); this.auditLogs = r.data?.auditLogs || this.auditLogs; },
      error: e => alert(e.error?.message || 'Stock Tracker review could not run.')
    });
  }

  loadAssignedChecklist() {
    const strategy = this.order.strategyKey || 'stock_tracker';
    const side = this.order.side || 'BUY';
    this.assignedChecklist = this.checklists.filter(t => t.enabled && (!t.strategyKey || t.strategyKey === 'ALL' || t.strategyKey === strategy) && (!t.tradeType || t.tradeType === 'ALL' || t.tradeType === side));
  }

  resetChecklistForm() {
    this.checklistForm = {
      templateName: '', strategyKey: 'stock_tracker', strategyName: 'Stock Tracker', tradeType: 'ALL', appliesTo: 'ALL',
      enabled: true, overrideAllowed: false,
      items: [
        { itemText: 'Trend Confirmation', mandatory: true },
        { itemText: 'Volume Confirmation', mandatory: true },
        { itemText: 'Risk-Reward Verification', mandatory: true }
      ]
    };
  }

  syncChecklistStrategy() {
    const selected = this.strategies.find(s => s.key === this.checklistForm.strategyKey);
    this.checklistForm.strategyName = selected?.name || (this.checklistForm.strategyKey === 'ALL' ? 'All Strategies' : 'Stock Tracker');
  }
  addChecklistItem() { this.checklistForm.items.push({ itemText: '', mandatory: true }); }
  removeChecklistItem(index: number) { this.checklistForm.items.splice(index, 1); }
  editChecklist(t: any) { this.checklistForm = { ...t, items: (t.items || []).map((i: any) => ({ ...i })) }; }
  saveChecklist() {
    this.syncChecklistStrategy();
    this.api.post<any>('/trading/checklists', this.checklistForm).subscribe({
      next: r => { alert(r.message || 'Checklist saved.'); this.checklists = r.data || []; this.resetChecklistForm(); this.loadAssignedChecklist(); },
      error: e => alert(e.error?.message || 'Checklist could not be saved.')
    });
  }
  toggleChecklist(t: any) {
    this.api.put<any>(`/trading/checklists/${t.id}/toggle`, { enabled: !t.enabled }).subscribe({ next: r => { this.checklists = r.data || []; this.loadAssignedChecklist(); } });
  }
  deleteChecklist(t: any) {
    if (!confirm('Delete this checklist template?')) return;
    this.api.delete<any>(`/trading/checklists/${t.id}`).subscribe({ next: r => { this.checklists = r.data || []; this.loadAssignedChecklist(); } });
  }
  isChecklistDone(id: any) { return this.completedChecklistItemIds.includes(Number(id)); }
  toggleChecklistDone(id: any) {
    const n = Number(id);
    this.completedChecklistItemIds = this.isChecklistDone(n) ? this.completedChecklistItemIds.filter(x => x !== n) : [...this.completedChecklistItemIds, n];
  }

  loadJournal() {
    this.api.get<any>('/trading/journal', { search: this.journalSearch }).subscribe({
      next: r => { this.journal = r.data?.rows || []; this.journalAnalytics = r.data?.analytics || this.journalAnalytics; },
      error: e => alert(e.error?.message || 'Journal could not be loaded.')
    });
  }
  exportJournal() { this.download('/trading/journal/export', 'trading_journal.csv'); }
  printPage() { window.print(); }
  private download(path: string, filename: string) {
    this.http.get(`${environment.apiUrl}${path}`, { responseType: 'blob' }).subscribe(blob => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = filename; a.click();
      URL.revokeObjectURL(url);
    });
  }

  money(value: any): string { return 'INR ' + this.number(value); }
  number(value: any): string { return Number(value || 0).toLocaleString('en-IN', { maximumFractionDigits: 2 }); }
}
