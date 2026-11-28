import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';

/**
 * Profit and Loss dashboard — specification section 10.
 *
 * Everything here is reported gross AND net, never only one. For a strategy that holds a single
 * session, the gap between them is the whole question: a delivery round trip costs roughly 0.30%
 * of the position, so a basket averaging 0.25% gross is a loss-maker that a gross-only page shows
 * as a winner.
 *
 * Realised and unrealised are likewise kept apart. A paper gain on an open position is not money
 * taken, and one combined headline would overstate what the account holds.
 */
@Component({
  selector: 'app-pnl-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host{display:block}
    .btn{border:1px solid #334865;border-radius:7px;padding:8px 13px;font-size:12px;font-weight:800;cursor:pointer;color:#e5eefb;background:#1b2940}
    .btn:disabled{opacity:.5;cursor:default}
    .primary{background:#2563eb;border-color:#2563eb;color:#fff}
    .input{background:#071122;border:1px solid #314761;border-radius:6px;color:#fff;padding:9px 10px;font-size:12px;width:100%}
    label{color:#9eb8dd;font-size:10px;font-weight:800;display:block;margin-bottom:3px}
    .card{background:#0d1829;border:1px solid #22344d;border-radius:10px}
    .good{color:#5ff5cd}.danger{color:#ff6b6b}
    th,td{padding:8px 10px;border-bottom:1px solid #22344d;text-align:left;font-size:11.5px;white-space:nowrap}
    th{color:#c8d8ef;background:#142034;font-size:10px;text-transform:uppercase;letter-spacing:.4px}
    .kpi{background:#0d1829;border:1px solid #22344d;border-radius:8px;padding:9px 11px;min-width:0}
    .kpi-l{font-size:9px;color:#8fb0d4;text-transform:uppercase;letter-spacing:.4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
    .kpi-v{font-size:clamp(14px,1.3vw,19px);font-weight:800;line-height:1.2;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
    /* One line, shrinking to share the width rather than scrolling. */
    .kpis{display:grid;grid-auto-flow:column;grid-auto-columns:minmax(0,1fr);gap:8px}
    @media (max-width:1250px){ .kpis{grid-auto-flow:row;grid-template-columns:repeat(auto-fit,minmax(130px,1fr))} }
    .filters{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px}
    .tbl-wrap{width:100%;overflow-x:auto}
  `],
  template: `
<div style="min-height:100vh;background:#071122;color:#e5eefb;padding:20px;">

  <section style="background:linear-gradient(120deg,#102447,#0d2e37);border:1px solid #284466;
                  border-top:3px solid #16d6a4;border-radius:8px;padding:18px 22px;margin-bottom:14px;">
    <div style="color:#5ff5cd;font-size:10.5px;font-weight:800;text-transform:uppercase;letter-spacing:.08em;">
      Profit &amp; Loss
    </div>
    <h1 style="color:#fff;font-size:23px;font-weight:800;margin:5px 0 7px;">What the trading actually made</h1>
    <p style="color:#9eb8dd;font-size:12.5px;margin:0;line-height:1.6;max-width:900px;">
      Gross and net are shown side by side throughout. The difference is brokerage, STT, exchange
      and SEBI charges, stamp duty, GST and the depository fee &mdash; about
      <strong style="color:#fbbf24;">0.30% of a delivery round trip</strong>, which is more than a
      one-session hold typically makes. Realised and unrealised are kept separate: a paper gain is
      not money taken.
    </p>
  </section>

  <div class="card" style="padding:12px;margin-bottom:12px;">
    <div class="filters">
      <div><label>From</label><input class="input" type="date" [(ngModel)]="f.from"></div>
      <div><label>To</label><input class="input" type="date" [(ngModel)]="f.to"></div>
      <div><label>Symbol</label><input class="input" [(ngModel)]="f.symbol" placeholder="Any"></div>
      <div><label>Strategy</label><input class="input" [(ngModel)]="f.strategy" placeholder="Any"></div>
      <div><label>Mode</label>
        <select class="input" [(ngModel)]="f.mode">
          <option value="">Paper and Real</option>
          <option value="PAPER">Paper</option>
          <option value="REAL">Real</option>
        </select></div>
      <div style="display:flex;align-items:flex-end;gap:7px;">
        <button class="btn primary" (click)="load()" [disabled]="loading()">
          {{ loading() ? 'Loading...' : 'Apply' }}</button>
        <button class="btn" (click)="clear()">Clear</button>
      </div>
    </div>
  </div>

  @if (d) {
    <div class="kpis" style="margin-bottom:10px;">
      <div class="kpi"><div class="kpi-l">Total Investment</div>
        <div class="kpi-v" style="color:#e2e8f0;">{{ money(d.totalInvestment) }}</div></div>
      <div class="kpi"><div class="kpi-l">Capital Deployed</div>
        <div class="kpi-v" style="color:#e2e8f0;">{{ money(d.capitalDeployed) }}</div></div>
      <div class="kpi"><div class="kpi-l">Open Positions</div>
        <div class="kpi-v" style="color:#e2e8f0;">{{ d.openPositions || 0 }}</div></div>
      <div class="kpi"><div class="kpi-l">Closed</div>
        <div class="kpi-v" style="color:#e2e8f0;">{{ d.closedPositions || 0 }}</div></div>
      <div class="kpi"><div class="kpi-l" title="Closed trades, before charges.">Realised Gross</div>
        <div class="kpi-v" [class]="(d.realizedGrossPnl||0) >= 0 ? 'good' : 'danger'">
          {{ money(d.realizedGrossPnl) }}</div></div>
      <div class="kpi"><div class="kpi-l" title="Brokerage, STT, exchange and SEBI charges, stamp duty, GST and the depository fee.">Charges</div>
        <div class="kpi-v" style="color:#fbbf24;">{{ money(d.totalCharges) }}</div></div>
      <div class="kpi" style="border-color:#2b4a6f;">
        <div class="kpi-l" title="Closed trades, after charges. This is the money.">Realised Net</div>
        <div class="kpi-v" [class]="(d.realizedNetPnl||0) >= 0 ? 'good' : 'danger'">
          {{ money(d.realizedNetPnl) }}</div></div>
      <div class="kpi"><div class="kpi-l" title="Open positions marked to market. Not money taken.">Unrealised</div>
        <div class="kpi-v" [class]="(d.unrealizedPnl||0) >= 0 ? 'good' : 'danger'">
          {{ money(d.unrealizedPnl) }}</div></div>
    </div>

    <div class="kpis" style="margin-bottom:12px;">
      <div class="kpi"><div class="kpi-l">Win Rate</div>
        <div class="kpi-v" [style.color]="(d.winRatePct||0) >= 50 ? '#5ff5cd' : '#e2e8f0'">
          {{ d.winRatePct || 0 | number:'1.0-1' }}%</div>
        <div style="font-size:8.5px;color:#64748b;">{{ d.winningTrades || 0 }}W / {{ d.losingTrades || 0 }}L</div></div>
      <div class="kpi"><div class="kpi-l">Avg Profit</div>
        <div class="kpi-v good">{{ money(d.avgProfitPerWin) }}</div></div>
      <div class="kpi"><div class="kpi-l">Avg Loss</div>
        <div class="kpi-v danger">{{ money(d.avgLossPerLoss) }}</div></div>
      <div class="kpi"><div class="kpi-l" title="Gross profit divided by gross loss. Above 1 means the winners outweigh the losers.">Profit Factor</div>
        <div class="kpi-v" style="color:#e2e8f0;">
          {{ d.profitFactor == null ? 'no losses' : (d.profitFactor | number:'1.2-2') }}</div></div>
      <div class="kpi"><div class="kpi-l" title="Average net result per closed trade.">Expectancy</div>
        <div class="kpi-v" [class]="(d.expectancyPerTrade||0) >= 0 ? 'good' : 'danger'">
          {{ money(d.expectancyPerTrade) }}</div></div>
      <div class="kpi"><div class="kpi-l" title="Largest peak-to-trough fall of the cumulative curve - not the worst single trade.">Max Drawdown</div>
        <div class="kpi-v danger">{{ money(d.maxDrawdown) }}</div>
        <div style="font-size:8.5px;color:#64748b;">{{ d.maxDrawdownPct || 0 | number:'1.1-1' }}%</div></div>
      <div class="kpi"><div class="kpi-l">Partial / Rejected</div>
        <div class="kpi-v" style="color:#e2e8f0;">{{ d.partialTrades || 0 }} / {{ d.rejectedTrades || 0 }}</div></div>
      <div class="kpi" style="border-color:#2b4a6f;"><div class="kpi-l">Net + Unrealised</div>
        <div class="kpi-v" [class]="(d.netPnl||0) >= 0 ? 'good' : 'danger'">{{ money(d.netPnl) }}</div></div>
    </div>

    @if (d.chargeDataNote) {
      <div style="color:#fde68a;background:#3a2608;border:1px solid #d97706;border-radius:7px;
                  padding:9px 11px;font-size:11px;line-height:1.55;margin-bottom:12px;">
        {{ d.chargeDataNote }}
      </div>
    }

    <!-- Capital curve. Cumulative net by day, with the peak-to-trough drawdown implied by the
         shape rather than stated twice. -->
    <div class="card" style="padding:14px;margin-bottom:12px;">
      <div style="font-size:11px;font-weight:800;color:#60a5fa;margin-bottom:9px;">
        Capital Curve &mdash; cumulative net P&amp;L
      </div>
      @if (curve().length > 1) {
        <svg viewBox="0 0 900 200" style="width:100%;height:200px;display:block;">
          <!-- Zero line, so a curve below water is obvious at a glance. -->
          <line x1="0" [attr.y1]="zeroY()" x2="900" [attr.y2]="zeroY()"
                stroke="#334155" stroke-width="1" stroke-dasharray="4 3"></line>
          <text x="4" [attr.y]="zeroY() - 4" fill="#64748b" font-size="9">0</text>
          <polyline [attr.points]="curvePoints()" fill="none" stroke="#5ff5cd" stroke-width="2"></polyline>
        </svg>
        <div style="display:flex;justify-content:space-between;font-size:9px;color:#64748b;">
          <span>{{ curve()[0].date }}</span>
          <span>peak {{ money(curveMax()) }} &middot; trough {{ money(curveMin()) }}</span>
          <span>{{ curve()[curve().length-1].date }}</span>
        </div>
      } @else {
        <div style="color:#94a3b8;font-size:11px;padding:16px;text-align:center;">
          No closed trades in this range yet, so there is no curve to draw.
        </div>
      }
    </div>

    <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(330px,1fr));gap:12px;">
      <div class="card tbl-wrap">
        <div style="padding:11px 12px 0;font-size:11px;font-weight:800;color:#fbbf24;">By Strategy</div>
        <table style="width:100%;border-collapse:collapse;margin-top:8px;">
          <thead><tr><th>Strategy</th><th style="text-align:right">Net</th>
            <th style="text-align:right">Trades</th><th style="text-align:right">Win %</th></tr></thead>
          <tbody>
            @for (r of d.byStrategy || []; track r.name) {
              <tr><td>{{ r.name }}</td>
                <td style="text-align:right" [class]="r.netPnl >= 0 ? 'good' : 'danger'">{{ money(r.netPnl) }}</td>
                <td style="text-align:right">{{ r.trades }}</td>
                <td style="text-align:right">{{ r.winRatePct | number:'1.0-1' }}%</td></tr>
            }
            @if (!(d.byStrategy || []).length) {
              <tr><td colspan="4" style="text-align:center;color:#94a3b8;padding:18px;">Nothing yet.</td></tr>
            }
          </tbody>
        </table>
      </div>

      <div class="card tbl-wrap">
        <div style="padding:11px 12px 0;font-size:11px;font-weight:800;color:#fbbf24;">By Stock</div>
        <table style="width:100%;border-collapse:collapse;margin-top:8px;">
          <thead><tr><th>Stock</th><th style="text-align:right">Net</th>
            <th style="text-align:right">Trades</th><th style="text-align:right">Win %</th></tr></thead>
          <tbody>
            @for (r of (d.byStock || []).slice(0, 12); track r.name) {
              <tr><td style="font-weight:700;color:#fff;">{{ r.name }}</td>
                <td style="text-align:right" [class]="r.netPnl >= 0 ? 'good' : 'danger'">{{ money(r.netPnl) }}</td>
                <td style="text-align:right">{{ r.trades }}</td>
                <td style="text-align:right">{{ r.winRatePct | number:'1.0-1' }}%</td></tr>
            }
            @if (!(d.byStock || []).length) {
              <tr><td colspan="4" style="text-align:center;color:#94a3b8;padding:18px;">Nothing yet.</td></tr>
            }
          </tbody>
        </table>
      </div>

      <div class="card tbl-wrap">
        <div style="padding:11px 12px 0;font-size:11px;font-weight:800;color:#fbbf24;">Paper vs Real</div>
        <table style="width:100%;border-collapse:collapse;margin-top:8px;">
          <thead><tr><th>Mode</th><th style="text-align:right">Net</th>
            <th style="text-align:right">Trades</th><th style="text-align:right">Win %</th></tr></thead>
          <tbody>
            @for (r of d.byMode || []; track r.name) {
              <tr><td>{{ r.name }}</td>
                <td style="text-align:right" [class]="r.netPnl >= 0 ? 'good' : 'danger'">{{ money(r.netPnl) }}</td>
                <td style="text-align:right">{{ r.trades }}</td>
                <td style="text-align:right">{{ r.winRatePct | number:'1.0-1' }}%</td></tr>
            }
            @if (!(d.byMode || []).length) {
              <tr><td colspan="4" style="text-align:center;color:#94a3b8;padding:18px;">Nothing yet.</td></tr>
            }
          </tbody>
        </table>
      </div>

      <!-- Which rules are actually closing trades. Without this, "sold" is the only answer the
           journal can give and no rule can be judged. -->
      <div class="card tbl-wrap">
        <div style="padding:11px 12px 0;font-size:11px;font-weight:800;color:#fbbf24;">Exit Reasons</div>
        <table style="width:100%;border-collapse:collapse;margin-top:8px;">
          <thead><tr><th>Reason</th><th style="text-align:right">Trades</th></tr></thead>
          <tbody>
            @for (k of exitReasonKeys(); track k) {
              <tr><td>{{ k }}</td><td style="text-align:right">{{ d.byExitReason[k] }}</td></tr>
            }
            @if (!exitReasonKeys().length) {
              <tr><td colspan="2" style="text-align:center;color:#94a3b8;padding:18px;">Nothing yet.</td></tr>
            }
          </tbody>
        </table>
      </div>
    </div>
  }

  <!-- Open positions with their live levels: section 6's order-status tracking. -->
  <div class="card tbl-wrap" style="margin-top:12px;">
    <div style="padding:11px 12px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
      <span style="font-size:11px;font-weight:800;color:#60a5fa;">Open Positions</span>
      <span style="font-size:10px;color:#64748b;">
        Stop, trailing stop and target as prices, not percentages &mdash; a percentage is what was
        configured, a price is what will happen.
      </span>
      <span style="flex:1 1 auto;"></span>
      <button class="btn" style="font-size:10px;padding:5px 10px;" (click)="loadOpen()">Refresh</button>
      <button class="btn" style="font-size:10px;padding:5px 10px;" (click)="sweep()" [disabled]="sweeping()"
              title="Runs the stop-loss, trailing-stop, target and time-exit rules now instead of waiting for the next minute.">
        {{ sweeping() ? 'Running...' : 'Run Exit Sweep' }}</button>
    </div>
    <table style="width:100%;border-collapse:collapse;min-width:1100px;">
      <thead><tr>
        <th>Symbol</th><th>Strategy</th><th style="text-align:right">Qty</th>
        <th style="text-align:right">Entry</th><th style="text-align:right">Current</th>
        <th style="text-align:right">High Water</th><th style="text-align:right">Stop</th>
        <th style="text-align:right">Trailing</th><th style="text-align:right">Target</th>
        <th style="text-align:right">Unrealised</th><th style="text-align:right">Net</th><th>Entered</th>
      </tr></thead>
      <tbody>
        @for (p of open; track p.id) {
          <tr>
            <td style="font-weight:700;color:#fff;">{{ p.symbol }}</td>
            <td>{{ p.strategyKey || '—' }}</td>
            <td style="text-align:right">{{ p.quantity }}</td>
            <td style="text-align:right">{{ p.entryPrice | number:'1.2-2' }}</td>
            <td style="text-align:right;color:#fff;font-weight:700;">
              {{ p.currentPrice ? (p.currentPrice | number:'1.2-2') : '—' }}</td>
            <td style="text-align:right;color:#8fb0d4;"
                title="Highest price seen since entry. The trailing stop is measured from this.">
              {{ p.highWaterMark | number:'1.2-2' }}</td>
            <td style="text-align:right;color:#fca5a5;">{{ p.stopLossPrice ? (p.stopLossPrice | number:'1.2-2') : '—' }}</td>
            <td style="text-align:right;color:#fca5a5;">{{ p.trailingStopPrice ? (p.trailingStopPrice | number:'1.2-2') : '—' }}</td>
            <td style="text-align:right;color:#5ff5cd;">{{ p.targetPrice ? (p.targetPrice | number:'1.2-2') : '—' }}</td>
            <td style="text-align:right" [class]="(p.unrealizedPnl||0) >= 0 ? 'good' : 'danger'">
              {{ money(p.unrealizedPnl) }}
              <div style="font-size:8.5px;color:#64748b;">{{ p.unrealizedPct | number:'1.2-2' }}%</div></td>
            <td style="text-align:right;font-weight:800;" [class]="(p.netUnrealizedPnl||0) >= 0 ? 'good' : 'danger'"
                title="After the round-trip charges.">{{ money(p.netUnrealizedPnl) }}</td>
            <td style="color:#64748b;font-size:10px;">{{ p.entryDate }}</td>
          </tr>
        }
        @if (!open.length) {
          <tr><td colspan="12" style="text-align:center;color:#94a3b8;padding:22px;">
            No open positions.
          </td></tr>
        }
      </tbody>
    </table>
  </div>

  <!-- ─────────────── AUDIT TRAIL (spec section 11) ─────────────── -->
  <div class="card tbl-wrap" style="margin-top:12px;">
    <div style="padding:11px 12px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
      <span style="font-size:11px;font-weight:800;color:#a78bfa;">Audit Trail</span>
      <span style="font-size:10px;color:#64748b;">
        Stock selection and deselection, capital allocation, and every order event &mdash;
        newest first. Selections are recorded only when they change, so this is a history of
        decisions rather than of clicks.
      </span>
      <span style="flex:1 1 auto;"></span>
      <select class="input" style="width:170px;padding:5px 8px;" [(ngModel)]="auditEvent"
              (ngModelChange)="loadAudit()">
        <option value="">All events</option>
        <option value="SELECTION">Selection / allocation</option>
        <option value="BUY_EXECUTION">Buy execution</option>
        <option value="SELL_EXECUTION">Sell execution</option>
        <option value="STRATEGY_CONFIG">Configuration change</option>
        <option value="GATE_BLOCKED">Blocked by gate</option>
      </select>
      <button class="btn" style="font-size:10px;padding:5px 10px;" (click)="loadAudit()">Refresh</button>
    </div>
    <table style="width:100%;border-collapse:collapse;min-width:980px;">
      <thead><tr>
        <th>When</th><th>Event</th><th>Strategy</th><th>Mode</th>
        <th>Symbol(s)</th><th>Status</th><th>Detail</th>
      </tr></thead>
      <tbody>
        @for (a of audit; track a.id) {
          <tr>
            <td style="color:#64748b;font-size:10px;white-space:nowrap;">{{ a.at }}</td>
            <td><span class="pill" style="background:#132237;color:#8fb0d4;">{{ a.eventType }}</span></td>
            <td>{{ a.strategyKey || '—' }}</td>
            <td>{{ a.mode || '—' }}</td>
            <td style="max-width:240px;overflow:hidden;text-overflow:ellipsis;color:#fff;font-weight:600;"
                [title]="a.symbol">{{ a.symbol || '—' }}</td>
            <td>{{ a.status || '—' }}</td>
            <!-- The full JSON is on hover rather than on screen: the per-leg quantities are what
                 make a past allocation checkable, and they do not fit a column. -->
            <td style="white-space:normal;min-width:260px;color:#9eb8dd;" [title]="a.detail">
              {{ a.message || '—' }}
            </td>
          </tr>
        }
        @if (!audit.length) {
          <tr><td colspan="7" style="text-align:center;color:#94a3b8;padding:22px;">
            Nothing recorded yet. Selections and allocations appear here as you make them.
          </td></tr>
        }
      </tbody>
    </table>
  </div>

</div>
  `
})
export class PnlDashboardComponent implements OnInit {

  d: any = null;
  open: any[] = [];
  loading = signal(false);
  sweeping = signal(false);
  f = { from: '', to: '', symbol: '', strategy: '', mode: '' };

  constructor(private api: ApiService) {}

  ngOnInit(): void { this.load(); this.loadOpen(); this.loadAudit(); }

  load(): void {
    this.loading.set(true);
    const p: any = {};
    for (const [k, v] of Object.entries(this.f)) if (v) p[k] = v;
    this.api.get<any>('/trading/pnl/dashboard', p).subscribe({
      next: r => { this.d = r?.data || null; this.loading.set(false); },
      error: () => { this.d = null; this.loading.set(false); }
    });
  }

  loadOpen(): void {
    this.api.get<any>('/trading/positions/open').subscribe({
      next: r => { this.open = r?.data || []; },
      error: () => { this.open = []; }
    });
  }

  sweep(): void {
    if (this.sweeping()) return;
    this.sweeping.set(true);
    this.api.post<any>('/trading/positions/sweep', {}).subscribe({
      next: r => {
        this.sweeping.set(false);
        const d = r?.data || {};
        const closed = (d.closed || []).length;
        alert(d.message || (closed
          ? `${closed} position(s) closed.`
          : `Checked ${d.examined || 0} position(s); none met an exit rule.`));
        this.loadOpen();
        this.load();
      },
      error: e => { this.sweeping.set(false); alert(e?.error?.message || 'Sweep failed.'); }
    });
  }


  // ── Audit trail (spec section 11) ───────────────────────────────────────
  audit: any[] = [];
  auditEvent = '';

  loadAudit(): void {
    const p: any = { limit: '200' };
    if (this.auditEvent) p.eventType = this.auditEvent;
    this.api.get<any>('/trading/audit-trail', p).subscribe({
      next: r => { this.audit = r?.data || []; },
      error: () => { this.audit = []; }
    });
  }

  clear(): void {
    this.f = { from: '', to: '', symbol: '', strategy: '', mode: '' };
    this.load();
  }

  curve(): any[] { return this.d?.capitalCurve || []; }
  curveMax(): number { return Math.max(...this.curve().map((p: any) => p.cumulativePnl), 0); }
  curveMin(): number { return Math.min(...this.curve().map((p: any) => p.cumulativePnl), 0); }

  /** Zero always inside the drawn range, so "below water" is visible rather than inferred. */
  zeroY(): number {
    const max = this.curveMax(), min = this.curveMin();
    const span = (max - min) || 1;
    return 185 - ((0 - min) / span) * 170;
  }

  curvePoints(): string {
    const c = this.curve();
    if (c.length < 2) return '';
    const max = this.curveMax(), min = this.curveMin();
    const span = (max - min) || 1;
    return c.map((p: any, i: number) => {
      const x = (i / (c.length - 1)) * 894 + 3;
      const y = 185 - ((p.cumulativePnl - min) / span) * 170;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(' ');
  }

  exitReasonKeys(): string[] {
    return this.d?.byExitReason ? Object.keys(this.d.byExitReason) : [];
  }

  /** Rupees, with a dash for nothing — zero and "not applicable" read differently. */
  money(v: any): string {
    const n = Number(v);
    if (v == null || isNaN(n)) return '—';
    return '₹' + n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
}
