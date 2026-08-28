import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';

// =============================================================================
// SubscriptionComponent — TradeRakshak™ v11.6
//
// TESTING MODE — ALL PLANS ARE FREE. NO REAL MONEY REQUIRED.
//
// Enterprise Policy:
//   • No payment gateway (Razorpay) is triggered in any flow
//   • All plans activate instantly with zero amount
//   • "Live Trading" option is fully hidden — only Paper/Virtual trading
//   • SEBI disclaimer shown prominently on every screen
//   • Super Admin can switch to paid mode via feature_config (no code change)
// =============================================================================

const PLANS = [
  {
    name: 'Free Trial',
    validity: 7,
    color: 'border-slate-600',
    badge: 'bg-slate-700 text-white',
    highlight: false,
    features: ['Limited stocks', 'Basic screener', '3 backtests/day', '7-day validity', 'Paper trading only']
  },
  {
    name: 'Basic',
    validity: 30,
    color: 'border-blue-700',
    badge: 'bg-blue-800 text-white',
    highlight: false,
    features: ['All cash segments', 'Screeners', 'Portfolio tracking', 'Watchlist', 'Basic reports', 'Paper trading only']
  },
  {
    name: 'Starter',
    validity: 30,
    color: 'border-green-700',
    badge: 'bg-green-800 text-white',
    highlight: false,
    features: ['EQ + F&O segments', 'Advanced screener', '50 backtests/day', 'Price alerts', 'Competitions', 'Paper trading only']
  },
  {
    name: 'Premium',
    validity: 90,
    color: 'border-purple-700',
    badge: 'bg-purple-800 text-white',
    highlight: false,
    features: ['All Starter features', 'Backtesting', 'AI recommendations', 'Strategy builder', 'Sector AI', 'Paper trading only']
  },
  {
    name: 'Pro',
    validity: 90,
    color: 'border-yellow-600',
    badge: 'bg-yellow-600 text-black',
    highlight: true,
    features: ['All Premium features', 'PPT reports', 'API access (limited)', 'Multiple portfolios', 'Advanced analytics', 'Paper trading only']
  },
  {
    name: 'Elite',
    validity: 365,
    color: 'border-red-600',
    badge: 'bg-red-700 text-white',
    highlight: false,
    features: ['Full access', 'Unlimited AI', 'Full API access', 'Advanced reports', 'Priority support', 'Paper trading only']
  },
  {
    name: 'Institutional',
    validity: 365,
    color: 'border-cyan-600',
    badge: 'bg-cyan-700 text-white',
    highlight: false,
    features: ['Institute branding', 'Multi-user', 'Dedicated reports', 'User management', 'White-label lite', 'Paper trading only']
  },
  {
    name: 'Enterprise',
    validity: 365,
    color: 'border-amber-500',
    badge: 'bg-amber-600 text-black',
    highlight: false,
    features: ['Full white-label', 'Own branding & URL', 'Own database', 'Franchise module', 'Custom rules', 'Paper trading only']
  },
];

@Component({
  selector: 'app-subscription',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
<div class="p-6">

  <!-- ── TESTING MODE BANNER ──────────────────────────────────────────────── -->
  <div style="background:linear-gradient(135deg,#14532d,#166534);border:2px solid #16a34a;border-radius:12px;padding:16px 20px;margin-bottom:24px;display:flex;align-items:center;gap:14px;">
    <div style="font-size:36px;flex-shrink:0;">🎓</div>
    <div>
      <div style="color:#4ade80;font-size:15px;font-weight:800;margin-bottom:4px;">
        TESTING MODE — ALL PLANS ARE 100% FREE
      </div>
      <div style="color:#86efac;font-size:12px;line-height:1.5;">
        No real money is required. No payment gateway. No credit card.
        All plans activate instantly for testing and educational purposes.
        This application uses <strong>VIRTUAL/PAPER TRADING ONLY</strong> — no real market orders are placed.
      </div>
    </div>
  </div>

  <!-- ── SEBI DISCLAIMER ─────────────────────────────────────────────────── -->
  <div style="background:#451a03;border:1px solid #d97706;border-radius:8px;padding:10px 16px;margin-bottom:20px;font-size:11px;color:#fcd34d;display:flex;gap:8px;align-items:flex-start;">
    <span style="flex-shrink:0;">⚠</span>
    <span>
      <strong>SEBI Disclaimer:</strong> TradeRakshak™ is NOT a SEBI-registered Investment Advisor.
      This application is for EDUCATIONAL and RESEARCH purposes only.
      All trades shown are VIRTUAL/PAPER trades — no real money is invested or at risk.
      Past performance does not guarantee future results.
      Do NOT make real investment decisions based on this application.
    </span>
  </div>

  <!-- ── HEADING ──────────────────────────────────────────────────────────── -->
  <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:6px;flex-wrap:wrap;gap:8px;">
    <h1 style="color:#fff;font-size:22px;font-weight:700;">💎 Plan Selection</h1>
    <div style="display:flex;align-items:center;gap:8px;">
      <span style="background:#14532d;border:1px solid #16a34a;color:#4ade80;font-size:11px;font-weight:700;padding:4px 12px;border-radius:20px;">
        ✓ All Plans FREE in Testing Mode
      </span>
    </div>
  </div>
  <p style="color:#94a3b8;font-size:13px;margin-bottom:20px;">
    Current plan: <strong style="color:#facc15;">{{ currentPlan() }}</strong>
    @if (planExpiry()) { <span style="color:#64748b;"> — expires {{ planExpiry() }}</span> }
  </p>

  <!-- ── ALERTS ──────────────────────────────────────────────────────────── -->
  @if (success()) {
    <div style="background:#14532d;border:1px solid #16a34a;color:#4ade80;border-radius:8px;padding:12px 16px;margin-bottom:16px;display:flex;align-items:center;gap:8px;">
      🎉 {{ success() }}
    </div>
  }
  @if (errorMsg()) {
    <div style="background:#450a0a;border:1px solid #dc2626;color:#f87171;border-radius:8px;padding:12px 16px;margin-bottom:16px;">
      ⚠ {{ errorMsg() }}
    </div>
  }

  <!-- ── PLANS GRID ─────────────────────────────────────────────────────── -->
  <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:14px;margin-bottom:28px;">
    @for (plan of plans; track plan.name) {
      <div style="background:#1e2a3b;border-radius:12px;padding:18px;cursor:pointer;transition:all .15s;position:relative;overflow:hidden;"
        [style.border]="selectedPlan()===plan.name ? '2px solid #3b82f6' : '2px solid #2d3f55'"
        [style.transform]="selectedPlan()===plan.name ? 'scale(1.02)' : 'scale(1)'"
        [style.boxShadow]="selectedPlan()===plan.name ? '0 0 0 3px rgba(59,130,246,.2)' : 'none'"
        (click)="selectedPlan.set(plan.name)">

        <!-- Most Popular badge -->
        @if (plan.highlight) {
          <div style="position:absolute;top:0;left:0;right:0;background:#1d6adb;color:#fff;font-size:9px;font-weight:700;text-align:center;padding:3px;letter-spacing:.5px;text-transform:uppercase;">
            ★ Most Popular
          </div>
          <div style="height:16px;"></div>
        }

        <!-- Plan name badge -->
        <div style="display:inline-flex;align-items:center;padding:3px 10px;border-radius:4px;font-size:11px;font-weight:700;margin-bottom:10px;"
          [style.background]="plan.badge.includes('yellow') ? '#ca8a04' : plan.badge.includes('cyan') ? '#0891b2' : plan.badge.includes('purple') ? '#7c3aed' : plan.badge.includes('green') ? '#15803d' : plan.badge.includes('red') ? '#b91c1c' : plan.badge.includes('amber') ? '#d97706' : plan.badge.includes('blue') ? '#1d4ed8' : '#374151'"
          style="color:#fff;">
          {{ plan.name }}
        </div>

        <!-- FREE price — no real money -->
        <div style="margin-bottom:4px;">
          <span style="font-size:28px;font-weight:800;color:#4ade80;">FREE</span>
          <span style="font-size:11px;color:#94a3b8;margin-left:4px;">for testing</span>
        </div>
        <div style="font-size:10px;color:#64748b;margin-bottom:12px;">{{ plan.validity }}-day access</div>

        <!-- Features -->
        <ul style="list-style:none;margin:0 0 12px;padding:0;display:flex;flex-direction:column;gap:5px;">
          @for (f of plan.features; track f) {
            <li style="display:flex;align-items:flex-start;gap:6px;font-size:11px;">
              <span style="color:#22c55e;margin-top:1px;flex-shrink:0;">✓</span>
              <span [style.color]="f.includes('Paper trading') ? '#60a5fa' : '#cbd5e1'"
                    [style.fontWeight]="f.includes('Paper trading') ? '600' : 'normal'">
                {{ f }}
              </span>
            </li>
          }
        </ul>

        <!-- Action button -->
        @if (plan.name === currentPlan()) {
          <div style="background:#14532d;border:1px solid #16a34a;color:#4ade80;border-radius:8px;padding:8px;text-align:center;font-size:12px;font-weight:600;">
            ✓ Current Plan
          </div>
        } @else {
          <button
            style="width:100%;padding:8px;border-radius:8px;font-size:12px;font-weight:600;cursor:pointer;border:none;transition:background .15s;"
            [style.background]="selectedPlan()===plan.name ? '#1d6adb' : '#1e3a5f'"
            [style.color]="selectedPlan()===plan.name ? '#fff' : '#93c5fd'"
            (click)="selectedPlan.set(plan.name); $event.stopPropagation()">
            {{ selectedPlan()===plan.name ? '✓ Selected' : 'Select Plan' }}
          </button>
        }
      </div>
    }
  </div>

  <!-- ── ACTIVATE PANEL ─────────────────────────────────────────────────── -->
  @if (selectedPlan() && selectedPlan() !== currentPlan()) {
    <div style="background:#1e2a3b;border:2px solid #3b82f6;border-radius:12px;padding:20px;max-width:460px;margin-bottom:24px;">

      <h3 style="color:#fff;font-size:15px;font-weight:700;margin-bottom:16px;">
        🚀 Activate {{ selectedPlan() }} Plan
      </h3>

      <!-- Order summary — showing FREE, no payment -->
      <div style="background:#0f172a;border-radius:8px;padding:14px;margin-bottom:16px;">
        <div style="display:flex;justify-content:space-between;font-size:12px;color:#94a3b8;margin-bottom:8px;">
          <span>{{ selectedPlan() }} plan ({{ getPlanValidity() }} days)</span>
          <span style="color:#4ade80;font-weight:700;">FREE</span>
        </div>
        <div style="display:flex;justify-content:space-between;font-size:12px;color:#94a3b8;margin-bottom:8px;">
          <span>Payment amount</span>
          <span style="color:#4ade80;font-weight:700;">₹0.00</span>
        </div>
        <div style="border-top:1px solid #2d3f55;padding-top:8px;display:flex;justify-content:space-between;font-size:13px;font-weight:700;">
          <span style="color:#fff;">Total</span>
          <span style="color:#4ade80;">₹0.00 — No Payment Required</span>
        </div>
      </div>

      <!-- NO PAYMENT GATEWAY — testing mode info box -->
      <div style="background:#172554;border:1px solid #1d4ed8;border-radius:8px;padding:12px;margin-bottom:16px;font-size:11px;color:#93c5fd;display:flex;gap:8px;">
        <span style="flex-shrink:0;font-size:16px;">ℹ</span>
        <span>
          <strong>Testing Mode:</strong> No payment gateway is used.
          Clicking "Activate" will instantly enable the <strong>{{ selectedPlan() }}</strong> plan
          on your account for {{ getPlanValidity() }} days — completely free.
          <br><br>
          <strong>Important:</strong> All trades in this application use
          <span style="color:#4ade80;font-weight:700;">VIRTUAL MONEY only</span>.
          No real money is invested at any point.
        </span>
      </div>

      <!-- Confirm & Activate — no payment consent needed -->
      <button (click)="activatePlan()"
        [disabled]="loading()"
        style="width:100%;padding:12px;border-radius:8px;font-size:13px;font-weight:700;border:none;cursor:pointer;background:#1d6adb;color:#fff;display:flex;align-items:center;justify-content:center;gap:8px;"
        [style.opacity]="loading() ? '0.6' : '1'">
        @if (loading()) {
          <span style="width:14px;height:14px;border:2px solid rgba(255,255,255,.3);border-top-color:#fff;border-radius:50%;animation:spin .7s linear infinite;display:inline-block;"></span>
          Activating...
        } @else {
          ✅ Activate {{ selectedPlan() }} Plan — FREE
        }
      </button>

    </div>
  }

  <!-- ── ACTIVE SUBSCRIPTION DETAILS ───────────────────────────────────── -->
  @if (activeSub()) {
    <div style="background:#1e2a3b;border:1px solid #2d3f55;border-radius:12px;padding:16px;max-width:480px;margin-bottom:20px;">
      <h3 style="color:#fff;font-size:14px;font-weight:700;margin-bottom:12px;">📋 Active Subscription</h3>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:6px 12px;font-size:12px;">
        <span style="color:#94a3b8;">Plan</span>
        <span style="color:#fff;font-weight:600;">{{ activeSub().planName }}</span>
        <span style="color:#94a3b8;">Start Date</span>
        <span style="color:#fff;">{{ activeSub().startDate }}</span>
        <span style="color:#94a3b8;">End Date</span>
        <span style="color:#fff;">{{ activeSub().endDate }}</span>
        <span style="color:#94a3b8;">Amount Paid</span>
        <span style="color:#4ade80;font-weight:600;">₹0 (Testing Mode)</span>
      </div>
    </div>
  }

  <!-- ── TESTING MODE FOOTER NOTE ────────────────────────────────────────── -->
  <div style="background:#1e2a3b;border:1px solid #2d3f55;border-radius:10px;padding:14px 18px;font-size:11px;color:#64748b;line-height:1.7;max-width:720px;">
    <div style="color:#94a3b8;font-weight:600;margin-bottom:6px;">📌 About This Application</div>
    <ul style="list-style:none;padding:0;margin:0;display:flex;flex-direction:column;gap:4px;">
      <li>✅ <strong style="color:#94a3b8;">Completely free</strong> — no real money is ever charged</li>
      <li>✅ <strong style="color:#94a3b8;">Paper trading only</strong> — all buy/sell orders use virtual balance, not real money</li>
      <li>✅ <strong style="color:#94a3b8;">Educational purpose</strong> — designed to learn trading strategies, not for actual investment</li>
      <li>✅ <strong style="color:#94a3b8;">No broker connection</strong> — no orders are sent to any stock broker or exchange</li>
      <li>⚠ <strong style="color:#fcd34d;">Not SEBI registered</strong> — TradeRakshak™ is not a registered investment advisor</li>
      <li>⚠ <strong style="color:#fcd34d;">Do not invest real money</strong> based on signals or rankings shown in this application</li>
    </ul>
  </div>

</div>

<style>
@keyframes spin { to { transform:rotate(360deg); } }
</style>
  `
})
export class SubscriptionComponent implements OnInit {
  plans        = PLANS;
  loading      = signal(false);
  selectedPlan = signal('');
  currentPlan  = signal('Free Trial');
  planExpiry   = signal('');
  activeSub    = signal<any>(null);
  success      = signal('');
  errorMsg     = signal('');

  constructor(private api: ApiService, public auth: AuthService) {}

  ngOnInit() {
    const u = this.auth.currentUser();
    if (u) {
      this.currentPlan.set(u.plan || 'Free Trial');
      this.planExpiry.set(u.planExpiry || '');
    }
    this.api.get<any>('/subscriptions/my').subscribe({
      next: r => this.activeSub.set(r.data),
      error: () => {}
    });
  }

  getPlanValidity(): number {
    return PLANS.find(p => p.name === this.selectedPlan())?.validity || 30;
  }

  activatePlan() {
    if (!this.selectedPlan()) return;
    this.loading.set(true);
    this.errorMsg.set('');
    this.success.set('');

    // Send activation request — backend auto-activates free in testing mode
    // No payment gateway, no real money, no consentPayment required
    this.api.post<any>('/subscriptions/activate', {
      planName:       this.selectedPlan(),
      billingCycle:   'monthly',
      paymentMethod:  'testing',       // signals backend: testing mode, no payment
      gatewayPaymentId: 'TESTING-FREE-' + Date.now(),
      couponCode:     null,
      consentPayment: true             // auto-consent: no real money involved
    }).subscribe({
      next: r => {
        this.success.set(
          `✅ ${this.selectedPlan()} plan activated successfully! (Testing Mode — No charge) ` +
          `Ref: ${r.data?.invoiceNumber || 'TEST-' + Date.now()}`
        );
        this.currentPlan.set(this.selectedPlan());
        this.activeSub.set({ planName: this.selectedPlan(),
          startDate: new Date().toLocaleDateString('en-IN'),
          endDate: new Date(Date.now() + this.getPlanValidity()*86400000).toLocaleDateString('en-IN')
        });
        this.selectedPlan.set('');
        this.loading.set(false);
      },
      error: e => {
        this.errorMsg.set(e.error?.message || 'Activation failed. Please try again.');
        this.loading.set(false);
      }
    });
  }
}
