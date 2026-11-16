import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';

type JournalTab = 'taken' | 'ledger' | 'failed' | 'successful';
type JournalMode = 'system' | 'paper' | 'manual';

@Component({
  selector: 'app-journal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host{display:block}.btn{border:1px solid #334865;border-radius:7px;padding:9px 14px;font-size:12px;font-weight:800;cursor:pointer;color:#e5eefb;background:#1b2940}.active,.primary{background:#2563eb;border-color:#2563eb;color:#fff}.source-active{background:#0b3b37;border-color:#16d6a4;color:#d8fff5}.danger{color:#ff6b6b}.good{color:#5ff5cd}.input{background:#071122;border:1px solid #314761;border-radius:6px;color:#fff;padding:10px 11px;font-size:13px;width:100%}th,td{padding:10px 12px;border-bottom:1px solid #22344d;text-align:left;font-size:12px;white-space:nowrap}th{color:#c8d8ef;background:#142034;font-size:11px;text-transform:uppercase}
  `],
  template: `
<div style="min-height:100vh;background:#071122;color:#e5eefb;padding:22px;">
  <section style="background:linear-gradient(120deg,#102447,#0d2e37);border:1px solid #284466;border-top:3px solid #16d6a4;border-radius:8px;padding:22px 24px;margin-bottom:16px;">
    <div style="color:#5ff5cd;font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:.08em;">All Trades</div>
    <h1 style="color:#fff;font-size:30px;font-weight:800;margin:5px 0 8px;">Trade Journal</h1>
    <p style="color:#9eb8dd;font-size:13px;">Fetch system trades, fetch paper trades, or add manual trade entries from one screen.</p>
    <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px;margin-top:16px;">
      <div style="background:#0b1524;border:1px solid #2a4566;border-radius:7px;padding:12px;"><div style="color:#8db0df;font-size:11px;font-weight:800;">Records</div><div style="color:#fff;font-size:18px;font-weight:800;">{{ displayTrades().length }}</div></div>
      <div style="background:#20192f;border:1px solid #a53b45;border-radius:7px;padding:12px;"><div style="color:#ff7474;font-size:11px;font-weight:800;">Average Return</div><div style="color:#fff;font-size:18px;font-weight:800;">{{ pct(avgReturn()) }}</div></div>
      <div style="background:#0b1524;border:1px solid #2a4566;border-radius:7px;padding:12px;"><div style="color:#8db0df;font-size:11px;font-weight:800;">Total Invested</div><div style="color:#fff;font-size:18px;font-weight:800;">{{ inr(totalInvested()) }}</div></div>
      <div style="background:#20192f;border:1px solid #a53b45;border-radius:7px;padding:12px;"><div style="color:#ff7474;font-size:11px;font-weight:800;">Total Invested Return</div><div style="color:#fff;font-size:18px;font-weight:800;">{{ inr(netPnl()) }}</div></div>
      <div style="background:#0b2a26;border:1px solid #127a54;border-radius:7px;padding:12px;"><div style="color:#5ff5cd;font-size:11px;font-weight:800;">Win / Loss</div><div style="color:#fff;font-size:18px;font-weight:800;">{{ successfulTrades().length }} / {{ failedTrades().length }}</div></div>
      <div style="background:#0b1524;border:1px solid #2a4566;border-radius:7px;padding:12px;"><div style="color:#8db0df;font-size:11px;font-weight:800;">Status</div><div style="color:#fff;font-size:18px;font-weight:800;">Editable</div></div>
    </div>
  </section>

  <section style="background:#101b2d;border:1px solid #2b3d58;border-top:2px solid #2563eb;border-radius:7px;padding:18px;margin-bottom:16px;">
    <div style="display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;border-bottom:1px solid #2b3d58;padding-bottom:12px;margin-bottom:16px;">
      <div style="display:flex;gap:8px;flex-wrap:wrap;">
        <button class="btn" [class.active]="activeTab()==='taken'" (click)="activeTab.set('taken')">Open Trade</button>
        <button class="btn" [class.active]="activeTab()==='ledger'" (click)="activeTab.set('ledger')">Trade Ledger</button>
        <button class="btn" [class.active]="activeTab()==='failed'" (click)="activeTab.set('failed')">Failed Trades</button>
        <button class="btn" [class.active]="activeTab()==='successful'" (click)="activeTab.set('successful')">Successful Trades</button>
      </div>
      <button class="btn primary" (click)="load()">Refresh</button>
    </div>

    <div style="display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;background:#0b1524;border:1px solid #243853;border-radius:7px;padding:12px;margin-bottom:16px;">
      <div style="color:#9eb8dd;font-size:12px;font-weight:800;">Trade Source: {{ sourceLabel() }}</div>
      <div style="display:flex;gap:8px;flex-wrap:wrap;">
        <button class="btn" [class.source-active]="entryMode()==='system'" (click)="selectSource('system')">Fetch System Trade</button>
        <button class="btn" [class.source-active]="entryMode()==='paper'" (click)="selectSource('paper')">Fetch Paper Trade</button>
        <button class="btn" [class.source-active]="entryMode()==='manual'" (click)="selectSource('manual')">Manual Trade Entry</button>
      </div>
    </div>

    <div style="background:#141d2e;border:1px solid #2b3f5e;border-left:3px solid #f59e0b;border-radius:7px;padding:10px 12px;margin-bottom:16px;color:#c8d8ef;font-size:12px;">
      This page holds <strong>trade notes you write yourself</strong>. Trades the app placed for you &mdash;
      Buy All 10 from Today top 10 Ranking Stocks, and every other automated or bulk order &mdash; are recorded
      separately under <strong>Executed Trades</strong> in the sidebar (Trading &rarr; Trading Journal tab).
    </div>

    @if (loading()) {
      <div style="text-align:center;color:#94a3b8;padding:28px;">Loading...</div>
    } @else {
      @if (activeTab()==='taken') {
        @if (entryMode()==='manual') {
          <h2 style="color:#fff;font-size:18px;font-weight:800;margin:0 0 12px;">Manual Trade Entry</h2>
          <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:10px;background:#0b1524;border:1px solid #243853;border-radius:7px;padding:14px;margin-bottom:16px;">
            <div><label style="color:#9eb8dd;font-size:11px;font-weight:800;">Symbol</label><input class="input" [(ngModel)]="form.symbol" placeholder="Type symbol"></div>
            <div><label style="color:#9eb8dd;font-size:11px;font-weight:800;">Sector</label><input class="input" [(ngModel)]="form.sector" placeholder="Sector"></div>
            <div><label style="color:#9eb8dd;font-size:11px;font-weight:800;">Trade Date</label><input class="input" type="date" [(ngModel)]="form.tradeDate"></div>
            <div><label style="color:#9eb8dd;font-size:11px;font-weight:800;">Entry Price</label><input class="input" type="number" [(ngModel)]="form.purchasePrice" placeholder="Entry price"></div>
            <div><label style="color:#9eb8dd;font-size:11px;font-weight:800;">Quantity</label><input class="input" type="number" [(ngModel)]="form.qtyBuy" placeholder="Qty"></div>
            <div><label style="color:#9eb8dd;font-size:11px;font-weight:800;">Exit Price</label><input class="input" type="number" [(ngModel)]="form.sellPrice" placeholder="Optional exit price"></div>
            <div><label style="color:#9eb8dd;font-size:11px;font-weight:800;">Exit Date</label><input class="input" type="date" [(ngModel)]="form.saleDate"></div>
            <div style="grid-column:1/-1;"><label style="color:#9eb8dd;font-size:11px;font-weight:800;">Entry Remark *</label><input class="input" [(ngModel)]="form.entryRemark" placeholder="Why this trade was taken"></div>
            <div style="grid-column:1/-1;display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap;">
              <button class="btn" (click)="clearForm()">Clear</button>
              <button class="btn primary" (click)="saveTrade()" [disabled]="saving()">{{ saving() ? 'Saving...' : 'Add' }}</button>
            </div>
          </div>
        } @else {
          <div style="background:#0b1524;border:1px solid #243853;border-radius:7px;padding:12px;margin-bottom:16px;color:#9eb8dd;font-size:13px;">
            Showing {{ sourceLabel() }} records held in this notebook. This filters the notes on this page by
            their source tag &mdash; it does not pull in orders the app placed. Those live under Executed Trades.
          </div>
        }
        @if (message()) { <div style="color:#5ff5cd;font-size:12px;margin-bottom:10px;">{{ message() }}</div> }
        @if (error()) { <div style="color:#ff9292;font-size:12px;margin-bottom:10px;">{{ error() }}</div> }
        <ng-container *ngTemplateOutlet="tradeTable; context:{ rows: displayTrades(), title: sourceLabel() + ' Trades' }"></ng-container>
      }

      @if (activeTab()==='ledger') {
        <ng-container *ngTemplateOutlet="ledgerTable"></ng-container>
      }

      @if (activeTab()==='failed') {
        <div style="margin-bottom:12px;color:#9eb8dd;font-size:13px;">Failed Trades: {{ failedTrades().length }} records, Total Loss {{ inr(failedLoss()) }}</div>
        <ng-container *ngTemplateOutlet="tradeTable; context:{ rows: failedTrades(), title: 'Failed Trades' }"></ng-container>
      }

      @if (activeTab()==='successful') {
        <div style="margin-bottom:12px;color:#9eb8dd;font-size:13px;">Successful Trades: {{ successfulTrades().length }} records, Total Profit {{ inr(successProfit()) }}</div>
        <ng-container *ngTemplateOutlet="tradeTable; context:{ rows: successfulTrades(), title: 'Successful Trades' }"></ng-container>
      }
    }
  </section>

  <ng-template #tradeTable let-rows="rows" let-title="title">
    <h2 style="color:#fff;font-size:18px;font-weight:800;margin:0 0 12px;">{{ title }}</h2>
    <div style="overflow:auto;border:1px solid #2b3d58;border-radius:7px;">
      <table style="width:100%;border-collapse:collapse;min-width:1180px;">
        <thead>
          <tr>
            <th>#</th><th>Status</th><th>Symbol</th><th>Return %</th><th>Invested Return</th><th>Holding Days</th><th>Sector</th><th>Entry Price</th><th>Exit Price</th><th>Invested Amount</th><th>Entry Remark</th><th>Created Date</th>
          </tr>
        </thead>
        <tbody>
          @if (rows.length === 0) {
            <tr><td colspan="12" style="text-align:center;color:#94a3b8;">No records yet</td></tr>
          } @else {
            @for (trade of rows; track trade.id; let i = $index) {
              <tr>
                <td>{{ i + 1 }}</td>
                <td>{{ trade.resultStatus || statusFor(trade) }}</td>
                <td><strong>{{ trade.symbol || '-' }}</strong></td>
                <td [class]="num(trade.returnPct) < 0 ? 'danger' : 'good'">{{ pct(trade.returnPct) }}</td>
                <td [class]="num(trade.netPnl) < 0 ? 'danger' : 'good'">{{ inr(trade.netPnl) }}</td>
                <td>{{ holdingText(trade.holdingDays) }}</td>
                <td>{{ trade.sector || '-' }}</td>
                <td>{{ money(trade.purchasePrice) }}</td>
                <td>{{ money(trade.sellPrice) }}</td>
                <td>{{ inr(trade.totalInvested) }}</td>
                <td>{{ trade.entryRemark || '-' }}</td>
                <td>{{ trade.createdAt || trade.tradeDate || '-' }}</td>
              </tr>
            }
          }
        </tbody>
      </table>
    </div>
  </ng-template>

  <ng-template #ledgerTable>
    <h2 style="color:#fff;font-size:18px;font-weight:800;margin:0 0 12px;">Trade Ledger</h2>
    <div style="overflow:auto;border:1px solid #2b3d58;border-radius:7px;">
      <table style="width:100%;border-collapse:collapse;min-width:1040px;">
        <thead>
          <tr><th>#</th><th>Type</th><th>Trade ID</th><th>Amount</th><th>P/L</th><th>Available Before</th><th>Available After</th><th>Note</th><th>Date</th></tr>
        </thead>
        <tbody>
          @if (displayTrades().length === 0) {
            <tr><td colspan="9" style="text-align:center;color:#94a3b8;">No ledger records yet</td></tr>
          } @else {
            @for (trade of displayTrades(); track trade.id; let i = $index) {
              <tr>
                <td>{{ i + 1 }}</td>
                <td>{{ trade.sellPrice ? 'TRADE_CLOSED' : 'TRADE_OPENED' }}</td>
                <td>{{ trade.tradeId || trade.id }}</td>
                <td>{{ inr(trade.totalInvested) }}</td>
                <td [class]="num(trade.netPnl) < 0 ? 'danger' : 'good'">{{ inr(trade.netPnl) }}</td>
                <td>{{ inr(0) }}</td>
                <td>{{ inr(num(trade.netPnl)) }}</td>
                <td>{{ trade.sellPrice ? 'Trade closed: ' + trade.symbol : 'Trade opened: ' + trade.symbol }}</td>
                <td>{{ trade.createdAt || trade.tradeDate || '-' }}</td>
              </tr>
            }
          }
        </tbody>
      </table>
    </div>
  </ng-template>
</div>
  `
})
export class JournalComponent implements OnInit {
  loading = signal(true);
  saving = signal(false);
  activeTab = signal<JournalTab>('taken');
  entryMode = signal<JournalMode>('manual');
  message = signal('');
  error = signal('');
  trades: any[] = [];
  summary: any = {};
  form = this.emptyForm();

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.load();
  }

  load() {
    this.loading.set(true);
    this.api.get<any>('/journal').subscribe({
      next: r => {
        const data = r.data || {};
        this.trades = data.trades || [];
        this.summary = data.summary || {};
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Journal data could not be loaded.');
        this.loading.set(false);
      }
    });
  }

  saveTrade() {
    this.message.set('');
    this.error.set('');
    if (!this.form.symbol.trim() || !this.form.entryRemark.trim()) {
      this.error.set('Symbol and Entry Remark are required.');
      return;
    }
    this.saving.set(true);
    const qty = this.num(this.form.qtyBuy);
    this.api.post<any>('/journal', {
      tradeSource: 'Manual Trade',
      exchange: 'NSE',
      symbol: this.form.symbol.trim().toUpperCase(),
      sector: this.form.sector || 'Miscellaneous',
      segment: 'EQ-SWG',
      tradeDate: this.form.tradeDate,
      purchasePrice: this.num(this.form.purchasePrice),
      qtyBuy: qty,
      saleDate: this.form.saleDate || null,
      sellPrice: this.form.sellPrice ? this.num(this.form.sellPrice) : null,
      qtySell: this.form.sellPrice ? qty : null,
      entryRemark: this.form.entryRemark,
      closingRemark: this.form.closingRemark || ''
    }).subscribe({
      next: r => {
        this.saving.set(false);
        this.message.set(r.message || 'Trade added.');
        this.clearForm();
        this.load();
      },
      error: e => {
        this.saving.set(false);
        this.error.set(e.error?.message || 'Trade could not be saved.');
      }
    });
  }

  clearForm() {
    this.form = this.emptyForm();
  }

  failedTrades(): any[] {
    return this.displayTrades().filter(t => this.num(t.returnPct) < 0 || String(t.resultStatus || '').toLowerCase() === 'loss');
  }

  successfulTrades(): any[] {
    return this.displayTrades().filter(t => this.num(t.returnPct) >= 0 && String(t.resultStatus || '').toLowerCase() !== 'loss');
  }

  totalInvested(): number {
    return this.displayTrades().reduce((sum, t) => sum + this.num(t.totalInvested), 0);
  }

  netPnl(): number {
    return this.displayTrades().reduce((sum, t) => sum + this.num(t.netPnl), 0);
  }

  avgReturn(): number {
    const rows = this.displayTrades();
    return rows.length ? rows.reduce((sum, t) => sum + this.num(t.returnPct), 0) / rows.length : 0;
  }

  selectSource(mode: JournalMode) {
    this.entryMode.set(mode);
    this.activeTab.set('taken');
    this.message.set('');
    this.error.set('');
    this.load();
  }

  displayTrades(): any[] {
    return this.trades.filter(t => this.sourceMatches(t));
  }

  sourceLabel(): string {
    if (this.entryMode() === 'system') return 'System Trade';
    if (this.entryMode() === 'paper') return 'Paper Trade';
    return 'Manual Trade Entry';
  }

  private sourceMatches(trade: any): boolean {
    const source = String(trade.tradeSource || trade.source || trade.trade_source || '').toLowerCase();
    if (this.entryMode() === 'system') return source.includes('system');
    if (this.entryMode() === 'paper') return source.includes('paper');
    return !source || source.includes('manual') || source.includes('journal');
  }

  failedLoss(): number {
    return this.failedTrades().reduce((sum, t) => sum + this.num(t.netPnl), 0);
  }

  successProfit(): number {
    return this.successfulTrades().reduce((sum, t) => sum + this.num(t.netPnl), 0);
  }

  statusFor(trade: any): string {
    return this.num(trade.returnPct) < 0 ? 'Loss' : 'Win';
  }

  holdingText(days: any): string {
    const value = this.num(days);
    if (!value) return '-';
    if (value === 1) return '1 day';
    if (value < 7) return `${value} days`;
    const weeks = Math.floor(value / 7);
    const extra = value % 7;
    return extra ? `${weeks} weeks ${extra} days` : `${weeks} weeks`;
  }

  pct(value: any): string {
    return `${this.num(value).toFixed(2)}%`;
  }

  money(value: any): string {
    return this.num(value).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  inr(value: any): string {
    return `INR ${this.money(value)}`;
  }

  num(value: any): number {
    const n = Number(value);
    return Number.isFinite(n) ? n : 0;
  }

  private emptyForm() {
    const today = new Date().toISOString().slice(0, 10);
    return {
      symbol: '',
      sector: '',
      tradeDate: today,
      purchasePrice: null as number | null,
      qtyBuy: null as number | null,
      sellPrice: null as number | null,
      saleDate: '',
      entryRemark: '',
      closingRemark: ''
    };
  }
}
