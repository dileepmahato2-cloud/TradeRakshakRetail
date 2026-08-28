import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { InrPipe, PctPipe, PnlClassPipe } from '../../shared/pipes/inr.pipe';

@Component({
  selector: 'app-portfolio',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, InrPipe, PctPipe, PnlClassPipe],
  styles: [`
    .card { background:#1e2a3b; border:1px solid #2d3f55; border-radius:10px; padding:16px; }
    .card-header { color:#fff; font-size:13px; font-weight:600; padding:12px 16px; border-bottom:1px solid #2d3f55; margin:-16px -16px 12px; }
    .input { background:#0f172a; border:1px solid #2d3f55; border-radius:6px; padding:8px 12px; color:#fff; font-size:13px; width:100%; }
    .input:focus { outline:none; border-color:#3b82f6; }
    .label { display:block; font-size:11px; color:#94a3b8; margin-bottom:4px; }
    .btn-primary   { background:#1d6adb; color:#fff; border:none; border-radius:7px; padding:9px 20px; font-size:13px; font-weight:600; cursor:pointer; }
    .btn-secondary { background:#1e2a3b; color:#94a3b8; border:1px solid #2d3f55; border-radius:7px; padding:8px 16px; font-size:13px; cursor:pointer; }
    .btn-danger    { background:#7f1d1d; color:#f87171; border:1px solid #dc2626; border-radius:7px; padding:8px 16px; font-size:13px; cursor:pointer; }
    .table-base { width:100%; border-collapse:collapse; font-size:12px; }
    .table-base th { background:#1a3a5c; color:#fff; padding:8px 12px; text-align:left; font-size:11px; font-weight:600; }
    .table-base td { padding:8px 12px; border-bottom:1px solid #1a2535; color:#cbd5e1; }
    .table-base tr:hover td { background:#1a2535; }
    .badge-green { background:#14532d; color:#4ade80; border:1px solid #16a34a; padding:2px 8px; border-radius:12px; font-size:10px; }
    .badge-red   { background:#450a0a; color:#f87171; border:1px solid #dc2626; padding:2px 8px; border-radius:12px; font-size:10px; }
    .badge-gold  { background:#451a03; color:#fcd34d; border:1px solid #d97706; padding:2px 8px; border-radius:12px; font-size:10px; }
    .section-title { color:#fff; font-size:18px; font-weight:700; }

    /* Trade mode selector */
    .trade-mode-card {
      border:2px solid #2d3f55; border-radius:10px; padding:16px; cursor:pointer;
      transition:all .15s; background:#1e2a3b;
    }
    .trade-mode-card.selected-virtual {
      border-color:#16a34a; background:#14532d22; box-shadow:0 0 0 3px rgba(22,163,74,.15);
    }
    .trade-mode-card.selected-real {
      border-color:#d97706; background:#78350f22; box-shadow:0 0 0 3px rgba(217,119,6,.15);
    }
  `],
  template: `
<div class="p-6">
  <div class="flex items-center justify-between mb-4">
    <div>
      <h1 class="section-title">💼 Trading — Virtual &amp; Real</h1>
      <p class="text-slate-400 text-sm">Choose virtual (paper) trading with simulated money, or real trading through your broker.</p>
    </div>
    <div class="flex gap-2">
      <button (click)="showCreate.set(true)" class="btn-secondary text-sm">+ Portfolio</button>
      <button (click)="openTradeModal()" class="btn-primary text-sm">+ New Trade</button>
    </div>
  </div>

  <!-- SEBI Disclaimer -->
  <div style="background:#451a03;border:1px solid #d97706;border-radius:8px;padding:8px 14px;margin-bottom:16px;font-size:11px;color:#fcd34d;">
    ⚠ <strong>SEBI Disclaimer:</strong> TradeRakshak™ is NOT a SEBI-registered Investment Advisor.
    For educational purposes only. Real trades require a connected broker and are your sole responsibility.
  </div>

  @if (loading()) { <div class="text-center text-slate-400 py-12">Loading…</div> }

  @if (!loading()) {
    <!-- Portfolio tabs -->
    <div class="flex gap-2 mb-4 overflow-x-auto">
      @for (p of portfolios; track p; let i=$index) {
        <button (click)="selectedIdx.set(i); loadDetail(p[0])"
          class="flex-shrink-0 px-4 py-2 rounded-lg text-sm transition-colors"
          [class]="selectedIdx()===i ? 'bg-blue-700/50 text-blue-200 border border-blue-600' : 'bg-slate-800 text-slate-400 hover:bg-slate-700'">
          {{ p[2] || 'Portfolio' }}
          <span class="text-xs ml-1 text-slate-500">{{ p[3] === 'paper' ? 'Paper' : 'Portfolio' }}</span>
        </button>
      }
    </div>

    @if (portfolios.length && detail) {
      <!-- P&L Summary -->
      <div class="grid grid-cols-2 sm:grid-cols-4 gap-4 mb-5">
        <div class="card text-center">
          <div class="text-2xl font-bold text-white">₹{{ detail.portfolio?.[7] | inr }}</div>
          <div class="text-xs text-slate-400 mt-1">Current Value</div>
        </div>
        <div class="card text-center">
          <div class="text-2xl font-bold" [class]="(detail.portfolio?.[10] || 0) >= 0 ? 'text-green-400' : 'text-red-400'">
            ₹{{ detail.portfolio?.[10] | inr }}
          </div>
          <div class="text-xs text-slate-400 mt-1">Unrealised P&L</div>
        </div>
        <div class="card text-center">
          <div class="text-2xl font-bold text-blue-400">{{ detail.openPositions }}</div>
          <div class="text-xs text-slate-400 mt-1">Open Positions</div>
        </div>
        <div class="card text-center">
          <div class="text-2xl font-bold text-yellow-400">₹{{ detail.portfolio?.[8] | inr }}</div>
          <div class="text-xs text-slate-400 mt-1">Cash Balance</div>
        </div>
      </div>

      <!-- Positions -->
      <div class="card mb-5">
        <div class="card-header">📊 Open Positions</div>
        @if (detail.positions?.length) {
          <div class="overflow-x-auto">
            <table class="table-base">
              <thead><tr>
                <th>Symbol</th><th>Sector</th><th>Qty</th>
                <th class="text-right">Avg Price</th><th class="text-right">Current</th>
                <th class="text-right">Invested</th><th class="text-right">P&L</th>
                <th class="text-right">Return</th><th>Mode</th>
              </tr></thead>
              <tbody>
                @for (pos of detail.positions; track pos) {
                  <tr>
                    <td class="font-bold text-white">{{ pos[6] }}</td>
                    <td class="text-xs text-slate-400">{{ pos[7] }}</td>
                    <td>{{ pos[11] }}</td>
                    <td class="text-right">₹{{ pos[12] | inr }}</td>
                    <td class="text-right">₹{{ pos[13] | inr }}</td>
                    <td class="text-right">₹{{ pos[14] | inr }}</td>
                    <td class="text-right font-bold" [class]="(pos[16]||0)>=0?'text-green-400':'text-red-400'">₹{{ pos[16] | inr }}</td>
                    <td class="text-right" [class]="(pos[17]||0)>=0?'text-green-400':'text-red-400'">{{ pos[17] | number:'1.1-1' }}%</td>
                    <td><span [class]="pos[18]==='real'?'badge-gold':'badge-green'" style="font-size:9px;">{{ pos[18]==='real' ? '💰 Real' : '🎭 Virtual' }}</span></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <p class="text-slate-500 text-center py-6">No open positions. Click "+ New Trade" to start.</p>
        }
      </div>

      <!-- Recent Trades -->
      <div class="card">
        <div class="card-header">📋 Recent Trades</div>
        @if (detail.recentTrades?.length) {
          <div class="overflow-x-auto">
            <table class="table-base">
              <thead><tr>
                <th>Trade ID</th><th>Symbol</th><th>Type</th><th>Qty</th>
                <th class="text-right">Price</th><th class="text-right">Amount</th>
                <th>Mode</th><th>Date</th>
              </tr></thead>
              <tbody>
                @for (t of detail.recentTrades; track t) {
                  <tr>
                    <td class="font-mono text-xs text-blue-300">{{ t[3] }}</td>
                    <td class="font-bold text-white">{{ t[6] }}</td>
                    <td><span class="badge text-xs" [ngClass]="t[8]==='BUY'?'badge-green':'badge-red'">{{ t[8] }}</span></td>
                    <td>{{ t[9] }}</td>
                    <td class="text-right">₹{{ t[10] | inr }}</td>
                    <td class="text-right">₹{{ t[11] | inr }}</td>
                    <td><span style="font-size:9px;" [class]="t[19]==='real'?'badge-gold':'badge-green'">{{ t[19]==='real' ? '💰 Real' : '🎭 Virtual' }}</span></td>
                    <td class="text-xs text-slate-500">{{ t[18] }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <p class="text-slate-500 text-center py-4">No trades yet.</p>
        }
      </div>
    }

    @if (!portfolios.length) {
      <div class="text-center py-16 text-slate-500">
        <div class="text-5xl mb-3">💼</div>
        <p>No portfolios yet. Create one to start trading.</p>
        <button (click)="showCreate.set(true)" class="btn-primary mt-4">Create Portfolio</button>
      </div>
    }
  }

  <!-- ── CREATE PORTFOLIO MODAL ────────────────────────────────────────────── -->
  @if (showCreate()) {
    <div class="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4">
      <div style="background:#112138;border-radius:12px;border:1px solid #2d3f55;width:100%;max-width:440px;padding:24px;">
        <div class="flex justify-between mb-5">
          <h2 class="text-lg font-semibold text-white">+ Create Portfolio</h2>
          <button (click)="showCreate.set(false)" class="text-slate-400 hover:text-white text-lg">✕</button>
        </div>
        <label class="label">Portfolio Name</label>
        <input [(ngModel)]="newPortfolio.name" class="input text-sm mb-4" placeholder="My Trading Portfolio" />
        <label class="label">Initial Capital (₹)</label>
        <input [(ngModel)]="newPortfolio.initialCapital" type="number" class="input text-sm mb-1" />
        <div style="font-size:10px;color:#64748b;margin-bottom:16px;">
          Used as virtual balance for paper trades. No real money deposited.
        </div>
        <div class="flex justify-end gap-3">
          <button (click)="showCreate.set(false)" class="btn-secondary">Cancel</button>
          <button (click)="createPortfolio()" class="btn-primary">Create</button>
        </div>
      </div>
    </div>
  }

  <!-- ════════════════════════════════════════════════════════════════════════ -->
  <!-- TRADE MODAL — Step 1: Choose trade mode (Virtual or Real)              -->
  <!-- ════════════════════════════════════════════════════════════════════════ -->
  @if (showTrade()) {
    <div class="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4">
      <div style="background:#0d1829;border-radius:14px;border:1px solid #2d3f55;width:100%;max-width:520px;max-height:92vh;overflow-y:auto;padding:24px;">

        <div class="flex justify-between mb-2">
          <h2 class="text-lg font-semibold text-white">
            {{ tradeStep() === 1 ? '💡 Choose Trade Type' : '📝 Enter Trade Details' }}
          </h2>
          <button (click)="closeTrade()" class="text-slate-400 hover:text-white text-lg">✕</button>
        </div>
        <p class="text-slate-500 text-xs mb-5">
          {{ tradeStep() === 1
            ? 'Select whether you want to simulate with virtual money, or place a real order through your broker.'
            : 'Enter trade details below.' }}
        </p>

        <!-- ── STEP 1: Trade Mode Selection ── -->
        @if (tradeStep() === 1) {
          <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:20px;">

            <!-- VIRTUAL option -->
            <div class="trade-mode-card"
              [class.selected-virtual]="newTrade.tradeMode === 'virtual'"
              (click)="newTrade.tradeMode = 'virtual'">
              <div style="font-size:32px;margin-bottom:8px;text-align:center;">🎭</div>
              <div style="color:#fff;font-size:13px;font-weight:700;text-align:center;margin-bottom:6px;">
                Virtual Currency
              </div>
              <div style="color:#86efac;font-size:11px;text-align:center;margin-bottom:10px;">
                Paper / Simulated Trading
              </div>
              <ul style="font-size:10px;color:#94a3b8;list-style:none;padding:0;display:flex;flex-direction:column;gap:4px;">
                <li>✓ No real money involved</li>
                <li>✓ Uses virtual balance only</li>
                <li>✓ No broker required</li>
                <li>✓ Safe for learning & testing</li>
                <li>✓ P&L is simulated</li>
              </ul>
              @if (newTrade.tradeMode === 'virtual') {
                <div style="margin-top:10px;background:#14532d;border-radius:6px;padding:6px;text-align:center;font-size:11px;color:#4ade80;font-weight:600;">
                  ✓ Selected
                </div>
              }
            </div>

            <!-- REAL MONEY option -->
            <div class="trade-mode-card"
              [class.selected-real]="newTrade.tradeMode === 'real'"
              (click)="selectRealMode()">
              <div style="font-size:32px;margin-bottom:8px;text-align:center;">💰</div>
              <div style="color:#fff;font-size:13px;font-weight:700;text-align:center;margin-bottom:6px;">
                Real Currency
              </div>
              <div style="color:#fcd34d;font-size:11px;text-align:center;margin-bottom:10px;">
                Live Market Order
              </div>
              <ul style="font-size:10px;color:#94a3b8;list-style:none;padding:0;display:flex;flex-direction:column;gap:4px;">
                <li>⚠ REAL money is used</li>
                <li>⚠ Requires broker connection</li>
                <li>⚠ Orders go to exchange</li>
                <li>⚠ Cannot be undone easily</li>
                <li>🔒 Broker auth required</li>
              </ul>
              @if (newTrade.tradeMode === 'real') {
                <div style="margin-top:10px;background:#78350f;border-radius:6px;padding:6px;text-align:center;font-size:11px;color:#fcd34d;font-weight:600;">
                  ⚠ Selected — Real Money
                </div>
              }
            </div>
          </div>

          <!-- Real mode: broker picker -->
          @if (newTrade.tradeMode === 'real') {
            <div style="background:#1e2a3b;border:1px solid #d97706;border-radius:10px;padding:14px;margin-bottom:16px;">
              <div style="color:#fcd34d;font-size:12px;font-weight:700;margin-bottom:10px;">
                🔗 Select Broker for Real Order
              </div>

              @if (activeBrokers.length === 0) {
                <!-- No broker connected -->
                <div style="background:#0f172a;border-radius:8px;padding:12px;text-align:center;">
                  <div style="font-size:24px;margin-bottom:6px;">🔌</div>
                  <div style="color:#fff;font-size:12px;font-weight:600;margin-bottom:4px;">No Verified Broker Connected</div>
                  <div style="color:#64748b;font-size:11px;margin-bottom:12px;">
                    You must add and verify a broker before placing real trades.
                  </div>
                  <a routerLink="/broker"
                    style="background:#1d6adb;color:#fff;padding:8px 18px;border-radius:7px;font-size:12px;font-weight:600;text-decoration:none;display:inline-block;">
                    🔗 Go to Broker Settings →
                  </a>
                </div>
              } @else {
                <!-- Broker selector -->
                <div style="display:flex;flex-direction:column;gap:8px;margin-bottom:12px;">
                  @for (b of activeBrokers; track b.id) {
                    <div (click)="newTrade.brokerCredentialId = b.id"
                      style="display:flex;align-items:center;justify-content:space-between;padding:10px 12px;border-radius:8px;cursor:pointer;transition:border-color .15s;"
                      [style.border]="newTrade.brokerCredentialId===b.id ? '2px solid #16a34a' : '1px solid #2d3f55'"
                      [style.background]="newTrade.brokerCredentialId===b.id ? '#14532d22' : '#0f172a'">
                      <div style="display:flex;align-items:center;gap:8px;">
                        <span style="font-size:18px;">{{ brokerEmoji(b.broker_name) }}</span>
                        <div>
                          <div style="color:#fff;font-size:12px;font-weight:600;">{{ b.broker_label }}</div>
                          <div style="color:#64748b;font-size:10px;">Client: {{ b.client_id }}</div>
                        </div>
                      </div>
                      <div style="display:flex;flex-direction:column;align-items:flex-end;gap:3px;">
                        @if (b.read_only) {
                          <span style="background:#172554;color:#93c5fd;border:1px solid #1d4ed8;padding:1px 6px;border-radius:10px;font-size:9px;">
                            🔒 Read-Only
                          </span>
                        } @else {
                          <span style="background:#14532d;color:#4ade80;border:1px solid #16a34a;padding:1px 6px;border-radius:10px;font-size:9px;">
                            ⚡ Trade Enabled
                          </span>
                        }
                        @if (newTrade.brokerCredentialId===b.id) {
                          <span style="color:#4ade80;font-size:10px;">✓ Selected</span>
                        }
                      </div>
                    </div>
                  }
                </div>

                <!-- Warn if selected broker is read-only -->
                @if (selectedBrokerReadOnly()) {
                  <div style="background:#172554;border:1px solid #1d4ed8;border-radius:8px;padding:10px;font-size:11px;color:#93c5fd;">
                    🔒 This broker is in <strong>Read-Only mode</strong>. To place real orders, go to
                    <a routerLink="/broker" style="color:#60a5fa;">Broker Settings</a>
                    and disable read-only mode first.
                  </div>
                }
              }
            </div>
          }

          <!-- Step 1 → Next button -->
          <div style="display:flex;justify-content:flex-end;gap:10px;">
            <button class="btn-secondary" (click)="closeTrade()">Cancel</button>
            <button class="btn-primary" (click)="goToStep2()"
              [disabled]="!canProceedStep1()"
              [style.opacity]="!canProceedStep1() ? '0.5' : '1'">
              Continue →
            </button>
          </div>
        }

        <!-- ── STEP 2: Trade Details ── -->
        @if (tradeStep() === 2) {

          <!-- Mode banner -->
          @if (newTrade.tradeMode === 'virtual') {
            <div style="background:#14532d;border:1px solid #16a34a;border-radius:8px;padding:10px 14px;margin-bottom:14px;display:flex;gap:8px;font-size:11px;color:#4ade80;">
              <span>🎭</span>
              <span><strong>Virtual Trade</strong> — This uses simulated balance only. No real money involved.</span>
            </div>
          } @else {
            <div style="background:#78350f;border:2px solid #d97706;border-radius:8px;padding:10px 14px;margin-bottom:14px;font-size:11px;color:#fcd34d;">
              <div style="font-weight:700;margin-bottom:4px;">⚠ REAL MONEY TRADE — {{ selectedBrokerName() }}</div>
              <div>This will place a REAL order through your broker. Real money will be spent.
                This action CANNOT be easily undone. Proceed only if you are certain.</div>
            </div>
          }

          <!-- Trade fields -->
          <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:12px;">
            <div>
              <label class="label">Symbol *</label>
              <input [(ngModel)]="newTrade.symbol" class="input text-sm uppercase" placeholder="RELIANCE" />
            </div>
            <div>
              <label class="label">Trade Type</label>
              <select [(ngModel)]="newTrade.tradeType" class="input text-sm">
                <option value="BUY">BUY</option>
                <option value="SELL">SELL</option>
              </select>
            </div>
            <div>
              <label class="label">Quantity *</label>
              <input [(ngModel)]="newTrade.qty" type="number" class="input text-sm" />
            </div>
            <div>
              <label class="label">Price (₹) *</label>
              <input [(ngModel)]="newTrade.price" type="number" step="0.05" class="input text-sm" />
            </div>
            @if (newTrade.tradeMode === 'real') {
              <div>
                <label class="label">Order Type</label>
                <select [(ngModel)]="newTrade.orderType" class="input text-sm">
                  <option value="MARKET">Market</option>
                  <option value="LIMIT">Limit</option>
                  <option value="SL">Stop Loss</option>
                </select>
              </div>
              <div>
                <label class="label">Product Type</label>
                <select [(ngModel)]="newTrade.productType" class="input text-sm">
                  <option value="CNC">CNC (Delivery)</option>
                  <option value="MIS">MIS (Intraday)</option>
                  <option value="NRML">NRML (F&O)</option>
                </select>
              </div>
            }
          </div>

          <!-- Amount summary -->
          <div style="background:#0f172a;border-radius:8px;padding:12px 14px;margin-bottom:12px;">
            <div style="display:flex;justify-content:space-between;font-size:13px;margin-bottom:4px;">
              <span style="color:#94a3b8;">{{ newTrade.tradeMode === 'real' ? 'Real Order Amount:' : 'Virtual Amount:' }}</span>
              <span style="color:#fff;font-weight:700;">₹{{ (newTrade.qty * newTrade.price) | number:'1.0-2' }}</span>
            </div>
            @if (newTrade.tradeMode === 'real') {
              <div style="font-size:10px;color:#fcd34d;display:flex;gap:4px;">
                <span>⚠</span><span>This amount will be deducted from your real broker account.</span>
              </div>
            } @else {
              <div style="font-size:10px;color:#4ade80;display:flex;gap:4px;">
                <span>✓</span><span>Virtual money only — no real funds used.</span>
              </div>
            }
          </div>

          <!-- Real money: explicit risk consent -->
          @if (newTrade.tradeMode === 'real') {
            <div style="background:#450a0a;border:2px solid #dc2626;border-radius:8px;padding:12px 14px;margin-bottom:14px;">
              <label style="display:flex;align-items:flex-start;gap:10px;cursor:pointer;">
                <input type="checkbox" [(ngModel)]="newTrade.riskConsent"
                  style="margin-top:2px;width:16px;height:16px;flex-shrink:0;accent-color:#dc2626;">
                <span style="font-size:11px;color:#f87171;line-height:1.5;">
                  <strong>I confirm I am placing a REAL MONEY trade.</strong>
                  I understand that ₹{{ (newTrade.qty * newTrade.price) | number:'1.0-2' }} of real money will be used.
                  TradeRakshak™ is NOT SEBI-registered and is NOT responsible for losses.
                  I am solely responsible for this trade decision.
                </span>
              </label>
            </div>
          }

          @if (tradeError()) {
            <div style="background:#450a0a;border:1px solid #dc2626;border-radius:6px;padding:10px;margin-bottom:10px;color:#f87171;font-size:12px;">
              ✗ {{ tradeError() }}
            </div>
          }

          <div style="display:flex;gap:10px;justify-content:space-between;">
            <button class="btn-secondary" (click)="tradeStep.set(1)">← Back</button>
            <div style="display:flex;gap:10px;">
              <button class="btn-secondary" (click)="closeTrade()">Cancel</button>
              <button (click)="addTrade()" [disabled]="!canSubmitTrade()"
                style="padding:9px 20px;border:none;border-radius:7px;font-size:13px;font-weight:700;cursor:pointer;transition:all .15s;"
                [style.background]="newTrade.tradeMode === 'real' ? '#dc2626' : '#1d6adb'"
                [style.color]="'#fff'"
                [style.opacity]="!canSubmitTrade() ? '0.5' : '1'">
                @if (submitting()) {
                  <span style="display:inline-block;width:14px;height:14px;border:2px solid rgba(255,255,255,.3);border-top-color:#fff;border-radius:50%;animation:spin .7s linear infinite;margin-right:6px;"></span>
                }
                {{ newTrade.tradeMode === 'real' ? '⚠ Place Real Order' : '✓ Add Virtual Trade' }}
              </button>
            </div>
          </div>
        }

      </div>
    </div>
  }
</div>

<style>
@keyframes spin { to { transform:rotate(360deg); } }
</style>
  `
})
export class PortfolioComponent implements OnInit {
  loading    = signal(true);
  submitting = signal(false);
  selectedIdx= signal(0);
  showCreate = signal(false);
  showTrade  = signal(false);
  tradeError = signal('');
  tradeStep  = signal<1|2>(1);

  portfolios:    any[] = [];
  detail:        any   = null;
  activeBrokers: any[] = [];

  newPortfolio = { name:'My Portfolio', portfolioType:'paper', initialCapital: 500000 };

  newTrade: any = {
    symbol:'', tradeType:'BUY', qty: 0, price: 0,
    tradeMode: 'virtual',       // 'virtual' | 'real'
    brokerCredentialId: null,
    orderType: 'MARKET',
    productType: 'CNC',
    riskConsent: false
  };

  constructor(private api: ApiService) {}

  ngOnInit() { this.loadPortfolios(); this.loadActiveBrokers(); }

  loadPortfolios() {
    this.loading.set(true);
    this.api.get<any>('/enterprise/portfolios').subscribe({
      next: r => {
        this.portfolios = r.data || [];
        if (this.portfolios.length) this.loadDetail(this.portfolios[0][0]);
        else this.loading.set(false);
      }, error: () => this.loading.set(false)
    });
  }

  loadDetail(id: number) {
    this.api.get<any>(`/enterprise/portfolios/${id}`).subscribe({
      next: r => { this.detail = r.data; this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }

  loadActiveBrokers() {
    this.api.get<any>('/broker/active').subscribe({
      next: r => this.activeBrokers = r.data || [],
      error: () => {}
    });
  }

  createPortfolio() {
    this.api.post<any>('/enterprise/portfolios', this.newPortfolio).subscribe({
      next: () => { this.showCreate.set(false); this.loadPortfolios(); }
    });
  }

  openTradeModal() {
    this.newTrade = { symbol:'', tradeType:'BUY', qty:0, price:0,
      tradeMode:'virtual', brokerCredentialId:null,
      orderType:'MARKET', productType:'CNC', riskConsent:false };
    this.tradeStep.set(1);
    this.tradeError.set('');
    this.showTrade.set(true);
  }

  closeTrade() {
    this.showTrade.set(false);
    this.tradeError.set('');
    this.tradeStep.set(1);
  }

  selectRealMode() {
    this.newTrade.tradeMode = 'real';
    this.loadActiveBrokers();
  }

  goToStep2() {
    if (!this.canProceedStep1()) return;
    this.tradeStep.set(2);
  }

  canProceedStep1(): boolean {
    if (this.newTrade.tradeMode === 'virtual') return true;
    if (this.newTrade.tradeMode === 'real') {
      if (this.activeBrokers.length === 0) return false;
      if (!this.newTrade.brokerCredentialId) return false;
      if (this.selectedBrokerReadOnly()) return false;
      return true;
    }
    return false;
  }

  canSubmitTrade(): boolean {
    if (!this.newTrade.symbol || !this.newTrade.qty || !this.newTrade.price) return false;
    if (this.newTrade.tradeMode === 'real' && !this.newTrade.riskConsent) return false;
    return true;
  }

  selectedBrokerReadOnly(): boolean {
    if (!this.newTrade.brokerCredentialId) return false;
    const b = this.activeBrokers.find(x => x.id === this.newTrade.brokerCredentialId);
    return b?.read_only === 1;
  }

  selectedBrokerName(): string {
    const b = this.activeBrokers.find(x => x.id === this.newTrade.brokerCredentialId);
    return b?.broker_label || 'Broker';
  }

  addTrade() {
    if (!this.newTrade.symbol || !this.newTrade.qty || !this.newTrade.price) {
      this.tradeError.set('Symbol, quantity, and price are required.'); return;
    }
    if (this.newTrade.tradeMode === 'real' && !this.newTrade.riskConsent) {
      this.tradeError.set('You must confirm the real money risk consent to place a real order.'); return;
    }

    this.submitting.set(true);
    const pid = this.portfolios[this.selectedIdx()]?.[0];

    const payload = {
      ...this.newTrade,
      portfolioId: pid
    };

    const endpoint = this.newTrade.tradeMode === 'real'
      ? `/enterprise/portfolios/${pid}/real-trade`
      : `/enterprise/portfolios/${pid}/trade`;

    this.api.post<any>(endpoint, payload).subscribe({
      next: () => {
        this.closeTrade();
        this.submitting.set(false);
        if (pid) this.loadDetail(pid);
      },
      error: e => {
        this.tradeError.set(e.error?.message || 'Trade failed. Please try again.');
        this.submitting.set(false);
      }
    });
  }

  brokerEmoji(id: string): string {
    const e: Record<string, string> = {
      zerodha:'🔵', upstox:'🟣', angel:'😇', fyers:'🟠',
      dhan:'🟡', shoonya:'⚫', kotak:'🔴', icici:'💛'
    };
    return e[id] || '🏦';
  }
}
