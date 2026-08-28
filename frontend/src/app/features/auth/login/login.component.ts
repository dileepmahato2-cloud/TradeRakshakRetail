import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
<div class="min-h-screen bg-navy flex items-center justify-center px-4">
  <div class="w-full max-w-md">
    <!-- Logo -->
    <div class="text-center mb-8">
      <h1 class="text-3xl font-bold text-gold">TradeRakshak™</h1>
      <p class="text-slate-400 text-sm mt-1">Protecting Capital. Powering Decisions.</p>
    </div>

    <div class="bg-card rounded-2xl border border-slate-700 p-8 shadow-xl">
      <h2 class="text-xl font-semibold text-white mb-6">Sign In</h2>

      @if (error()) {
        <div class="bg-red-900/40 border border-red-700 text-red-300 rounded-lg px-4 py-3 mb-4 text-sm">
          {{ error() }}
        </div>
      }

      <form (ngSubmit)="onLogin()" #loginForm="ngForm">
        <div class="mb-4">
          <label class="block text-sm text-slate-400 mb-1">Email</label>
          <input type="email" name="email" [(ngModel)]="email" required
            class="w-full bg-navy border border-slate-600 rounded-lg px-4 py-2.5 text-white focus:outline-none focus:border-blue-500"
            placeholder="you@example.com" />
        </div>

        <div class="mb-4">
          <label class="block text-sm text-slate-400 mb-1">Password</label>
          <input type="password" name="password" [(ngModel)]="password" required
            class="w-full bg-navy border border-slate-600 rounded-lg px-4 py-2.5 text-white focus:outline-none focus:border-blue-500"
            placeholder="••••••••" />
        </div>

        <!-- R3: SEBI consent mandatory on every login -->
        <div class="mb-6">
          <label class="flex items-start gap-3 cursor-pointer">
            <input type="checkbox" name="consent" [(ngModel)]="consent"
              class="mt-0.5 accent-gold" />
            <span class="text-xs text-slate-400 leading-relaxed">
              I acknowledge that TradeRakshak™ is a Technology Tools Platform and
              <strong class="text-gold">NOT a SEBI-registered Investment Advisor</strong>.
              All content is for educational purposes only.
            </span>
          </label>
        </div>

        <button type="submit" [disabled]="loading() || !consent"
          class="w-full bg-blue-600 hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed
                 text-white font-semibold rounded-lg py-2.5 transition-colors">
          @if (loading()) { Signing in… } @else { Sign In }
        </button>
      </form>

      <p class="text-center text-sm text-slate-500 mt-4">
        New user? <a routerLink="/register" class="text-blue-400 hover:underline">Register with PAN</a>
      </p>
    </div>

    <!-- SEBI disclaimer footer -->
    <p class="text-center text-xs text-slate-600 mt-6 px-4 leading-relaxed">
      ⚠ TradeRakshak™ is NOT a SEBI-registered Research Analyst or Investment Advisor.
      For educational use only.
    </p>
  </div>
</div>
  `
})
export class LoginComponent {
  email    = '';
  password = '';
  consent  = false;
  loading  = signal(false);
  error    = signal('');

  constructor(private auth: AuthService, private router: Router) {}

  onLogin() {
    if (!this.consent) {
      this.error.set('Please accept the SEBI declaration to continue.');
      return;
    }
    this.loading.set(true);
    this.error.set('');
    this.auth.login(this.email, this.password, this.consent).subscribe({
      next: () => this.router.navigate(['/dashboard']),
      error: err => {
        this.error.set(err.error?.message || 'Login failed. Please try again.');
        this.loading.set(false);
      }
    });
  }
}
