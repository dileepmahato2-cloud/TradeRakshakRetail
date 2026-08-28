import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';

type PaymentMode = 'test' | 'real';

interface PricingPlan {
  name: string;
  priceLabel: string;
  amount: number;
  validity: string;
  backtests: string;
  strategies: string;
  portfolios: string;
  segments: string;
  popular?: boolean;
  current?: boolean;
  features: { label: string; enabled: boolean }[];
}

@Component({
  selector: 'app-pricing',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
<div class="pricing-page">
  <header class="pricing-header">
    <div class="eyebrow">Choose Your Plan</div>
    <h1>Protecting Capital. Powering Decisions. Creating Wealth.</h1>
  </header>

  @if (message()) {
    <div class="notice success">{{ message() }}</div>
  }
  @if (error()) {
    <div class="notice error">{{ error() }}</div>
  }

  <section class="plan-grid">
    @for (plan of plans; track plan.name) {
      <article class="plan-card" [class.highlight]="plan.popular" [class.current]="plan.current">
        @if (plan.popular) { <span class="badge popular">MOST POPULAR</span> }
        @if (plan.current) { <span class="badge current-plan">YOUR PLAN</span> }

        <h2>{{ plan.name }}</h2>
        <div class="price">
          <span>{{ plan.priceLabel }}</span>
          @if (plan.amount > 0) { <small>/month + GST</small> }
        </div>

        <ul class="features">
          <li><span>🗓</span>{{ plan.validity }}</li>
          <li><span>📊</span>{{ plan.backtests }}</li>
          <li><span>💼</span>{{ plan.strategies }}</li>
          <li><span>📁</span>{{ plan.portfolios }}</li>
          @for (feature of plan.features; track feature.label) {
            <li [class.disabled]="!feature.enabled"><span>{{ feature.enabled ? '✅' : '❌' }}</span>{{ feature.label }}</li>
          }
          <li><span>🔰</span>{{ plan.segments }}</li>
        </ul>

        @if (plan.amount === 0) {
          <div class="no-pay">No payment required</div>
        } @else if (plan.current) {
          <div class="current-copy">✅ Current Plan</div>
        } @else {
          <div class="controls">
            <select [(ngModel)]="selectedCycle[plan.name]">
              <option value="Monthly">Monthly</option>
              <option value="Quarterly">Quarterly (save 10%)</option>
              <option value="Annual">Annual (save 25%)</option>
            </select>
            <select [(ngModel)]="selectedPayment[plan.name]">
              <option value="UPI">UPI</option>
              <option value="Net Banking">Net Banking</option>
              <option value="Card">Card</option>
              <option value="Razorpay">Razorpay</option>
            </select>
            <div class="mode-toggle">
              <label><input type="radio" [name]="'mode-' + plan.name" value="test" [(ngModel)]="selectedMode[plan.name]"> Test Mode</label>
              <label><input type="radio" [name]="'mode-' + plan.name" value="real" [(ngModel)]="selectedMode[plan.name]"> Real Money</label>
            </div>
            <label class="confirm">
              <input type="checkbox" [(ngModel)]="confirmed[plan.name]">
              <span>I confirm this payment and have read the SEBI disclaimer.</span>
            </label>
            <button class="subscribe" (click)="subscribe(plan)" [disabled]="loading()">
              {{ selectedMode[plan.name] === 'real' ? 'Proceed to Pay' : 'Activate Test Plan' }} →
            </button>
          </div>
        }
      </article>
    }
  </section>
</div>
  `,
  styles: [`
    .pricing-page { padding: 22px 28px 34px; color: #fff; min-height: calc(100vh - 56px); background: #0B1E3D; }
    .pricing-header { text-align: center; margin-bottom: 20px; }
    .pricing-header .eyebrow { color: #2f8cff; font-size: 18px; font-weight: 800; margin-bottom: 12px; }
    .pricing-header h1 { font-size: 16px; font-weight: 500; margin: 0; }
    .notice { max-width: 980px; margin: 0 auto 14px; border-radius: 8px; padding: 11px 14px; text-align: center; font-size: 13px; }
    .notice.success { background: rgba(22, 163, 74, .14); border: 1px solid rgba(34, 197, 94, .45); color: #86efac; }
    .notice.error { background: rgba(220, 38, 38, .14); border: 1px solid rgba(248, 113, 113, .5); color: #fecaca; }
    .plan-grid { display: grid; grid-template-columns: repeat(4, minmax(240px, 1fr)); gap: 26px; align-items: stretch; }
    .plan-card { position: relative; background: #132640; border: 2px solid #2d405f; border-radius: 10px; padding: 31px 25px 28px; min-height: 720px; display: flex; flex-direction: column; }
    .plan-card.highlight { border-color: #f6b900; }
    .plan-card h2 { margin: 0 0 18px; text-align: center; font-size: 25px; font-weight: 800; }
    .price { text-align: center; color: #ffbd19; margin-bottom: 26px; min-height: 48px; }
    .price span { font-size: 35px; font-weight: 900; letter-spacing: 0; }
    .price small { color: #9db6d9; font-size: 14px; margin-left: 4px; }
    .features { list-style: none; margin: 0; padding: 0; flex: 1; }
    .features li { display: flex; gap: 9px; align-items: flex-start; padding: 8px 0; border-bottom: 1px solid rgba(148, 163, 184, .09); line-height: 1.35; }
    .features li.disabled { color: #fff; }
    .features span { width: 16px; flex: 0 0 16px; }
    .badge { position: absolute; top: -14px; border-radius: 16px; padding: 5px 14px; font-size: 12px; font-weight: 900; white-space: nowrap; }
    .badge.popular { right: 120px; background: #ffbd19; color: #101827; }
    .badge.current-plan { right: 12px; background: #22c55e; color: #fff; }
    .no-pay, .current-copy { text-align: center; color: #86a3c9; font-size: 14px; margin-top: 22px; }
    .current-copy { color: #22c55e; font-weight: 800; }
    .controls { display: grid; gap: 10px; margin-top: 18px; }
    select, input[type="text"] { width: 100%; background: #071425; border: 1px solid #24446e; border-radius: 6px; color: #fff; padding: 11px 14px; font-size: 14px; }
    .mode-toggle { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .mode-toggle label, .confirm { border: 1px solid #24446e; border-radius: 6px; background: rgba(7, 20, 37, .7); padding: 9px 10px; color: #9db6d9; font-size: 12px; display: flex; align-items: center; gap: 8px; }
    .confirm { align-items: flex-start; line-height: 1.45; }
    .subscribe { border: 0; border-radius: 6px; background: #2f8cff; color: #fff; font-weight: 800; padding: 12px 16px; cursor: pointer; }
    .subscribe:disabled { opacity: .6; cursor: wait; }
    @media (max-width: 1280px) { .plan-grid { grid-template-columns: repeat(2, minmax(260px, 1fr)); } .plan-card { min-height: 620px; } }
    @media (max-width: 720px) { .pricing-page { padding: 18px 14px; } .plan-grid { grid-template-columns: 1fr; } .plan-card { min-height: auto; } }
  `]
})
export class PricingComponent implements OnInit {
  loading = signal(false);
  message = signal('');
  error = signal('');

  selectedCycle: Record<string, string> = {};
  selectedPayment: Record<string, string> = {};
  selectedMode: Record<string, PaymentMode> = {};
  confirmed: Record<string, boolean> = {};

  plans: PricingPlan[] = [
    {
      name: 'Free Trial', priceLabel: 'FREE', amount: 0, validity: '7 days validity',
      backtests: 'Backtests: 3/day', strategies: '3 Strategies', portfolios: '1 Paper Portfolio(s)',
      segments: 'EQ-INT,EQ-SWG',
      features: [
        { label: 'F&O Access', enabled: false },
        { label: 'Fundamental Data', enabled: false },
        { label: 'Competitions', enabled: false },
        { label: 'AI/ML Engine', enabled: false }
      ]
    },
    {
      name: 'Starter', priceLabel: '₹499', amount: 499, validity: '30 days validity',
      backtests: 'Backtests: 50/day', strategies: '10 Strategies', portfolios: '1 Paper Portfolio(s)',
      segments: 'EQ-INT,EQ-SWG,EQ-POS',
      features: [
        { label: 'F&O Access', enabled: false },
        { label: 'Fundamental Data', enabled: false },
        { label: 'Competitions', enabled: true },
        { label: 'AI/ML Engine', enabled: false }
      ]
    },
    {
      name: 'Pro', priceLabel: '₹999', amount: 999, validity: '90 days validity',
      backtests: 'Backtests: 200/day', strategies: '25 Strategies', portfolios: '3 Paper Portfolio(s)',
      segments: 'EQ-INT,EQ-SWG,EQ-POS,FUT,OPT-CE,OPT-PE,IDX',
      popular: true,
      features: [
        { label: 'F&O Access', enabled: true },
        { label: 'Fundamental Data', enabled: true },
        { label: 'Competitions', enabled: true },
        { label: 'AI/ML Engine', enabled: true }
      ]
    },
    {
      name: 'Elite', priceLabel: '₹9,999', amount: 9999, validity: '365 days validity',
      backtests: 'Backtests: 9999/day', strategies: '999 Strategies', portfolios: '5 Paper Portfolio(s)',
      segments: 'ALL',
      current: true,
      features: [
        { label: 'F&O Access', enabled: true },
        { label: 'Fundamental Data', enabled: true },
        { label: 'Competitions', enabled: true },
        { label: 'AI/ML Engine', enabled: true }
      ]
    }
  ];

  constructor(private api: ApiService) {}

  ngOnInit() {
    for (const plan of this.plans) {
      this.selectedCycle[plan.name] = 'Monthly';
      this.selectedPayment[plan.name] = 'UPI';
      this.selectedMode[plan.name] = 'test';
      this.confirmed[plan.name] = false;
    }
    this.loadCurrentPlan();
  }

  subscribe(plan: PricingPlan) {
    this.message.set('');
    this.error.set('');
    if (!this.confirmed[plan.name]) {
      this.error.set('Please confirm that you have read the SEBI disclaimer before continuing.');
      return;
    }
    if (this.selectedMode[plan.name] === 'real') {
      this.error.set('Real payment setup is pending. Please use Test Mode until the payment gateway is configured.');
      return;
    }
    this.loading.set(true);
    this.api.post<any>('/subscriptions/activate', {
      planName: plan.name,
      billingCycle: this.selectedCycle[plan.name],
      paymentMethod: 'testing',
      consentPayment: true
    }).subscribe({
      next: r => {
        this.message.set(r.message || `${plan.name} test plan activated.`);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(e.error?.message || 'Plan could not be activated.');
        this.loading.set(false);
      }
    });
  }

  private loadCurrentPlan() {
    this.api.get<any>('/subscriptions/my').subscribe({
      next: r => {
        const name = r.data?.plan?.name || r.data?.planName || r.data?.plan_name;
        if (!name) return;
        this.plans = this.plans.map(p => ({ ...p, current: p.name.toLowerCase() === String(name).toLowerCase() }));
      },
      error: () => {}
    });
  }
}
