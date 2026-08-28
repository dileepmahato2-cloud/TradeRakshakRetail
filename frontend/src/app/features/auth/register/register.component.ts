import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
<div class="min-h-screen bg-navy flex items-center justify-center px-4 py-8">
  <div class="w-full max-w-md">
    <div class="text-center mb-8">
      <h1 class="text-3xl font-bold text-gold">TradeRakshak™</h1>
      <p class="text-slate-400 text-sm mt-1">Free Trial — PAN Registration</p>
    </div>

    <div class="bg-card rounded-2xl border border-slate-700 p-8 shadow-xl">
      <h2 class="text-xl font-semibold text-white mb-6">Create Account</h2>

      @if (error()) {
        <div class="bg-red-900/40 border border-red-700 text-red-300 rounded-lg px-4 py-3 mb-4 text-sm">{{ error() }}</div>
      }
      @if (success()) {
        <div class="bg-green-900/40 border border-green-700 text-green-300 rounded-lg px-4 py-3 mb-4 text-sm">{{ success() }}</div>
      }

      <form (ngSubmit)="onRegister()">
        <div class="grid grid-cols-2 gap-4 mb-4">
          <div class="col-span-2">
            <label class="block text-sm text-slate-400 mb-1">Full Name *</label>
            <input type="text" [(ngModel)]="form.name" name="name" required
              class="w-full bg-navy border border-slate-600 rounded-lg px-4 py-2.5 text-white focus:outline-none focus:border-blue-500" />
          </div>
          <div class="col-span-2">
            <label class="block text-sm text-slate-400 mb-1">Email *</label>
            <input type="email" [(ngModel)]="form.email" name="email" required
              class="w-full bg-navy border border-slate-600 rounded-lg px-4 py-2.5 text-white focus:outline-none focus:border-blue-500" />
          </div>
          <div>
            <label class="block text-sm text-slate-400 mb-1">Mobile</label>
            <input type="tel" [(ngModel)]="form.mobile" name="mobile"
              class="w-full bg-navy border border-slate-600 rounded-lg px-4 py-2.5 text-white focus:outline-none focus:border-blue-500" />
          </div>
          <div>
            <label class="block text-sm text-slate-400 mb-1">PAN *</label>
            <input type="text" [(ngModel)]="form.pan" name="pan" required maxlength="10"
              class="w-full bg-navy border border-slate-600 rounded-lg px-4 py-2.5 text-white focus:outline-none focus:border-blue-500 uppercase"
              placeholder="ABCDE1234F" />
          </div>
          <div class="col-span-2">
            <label class="block text-sm text-slate-400 mb-1">Password *</label>
            <input type="password" [(ngModel)]="form.password" name="password" required minlength="8"
              class="w-full bg-navy border border-slate-600 rounded-lg px-4 py-2.5 text-white focus:outline-none focus:border-blue-500" />
          </div>
        </div>

        <div class="mb-6">
          <label class="flex items-start gap-3 cursor-pointer">
            <input type="checkbox" [(ngModel)]="form.consent" name="consent" class="mt-0.5 accent-gold" />
            <span class="text-xs text-slate-400 leading-relaxed">
              I confirm that TradeRakshak™ is <strong class="text-gold">NOT a SEBI-registered Investment Advisor</strong>.
              I will not treat any content as investment advice.
            </span>
          </label>
        </div>

        <button type="submit" [disabled]="loading() || !form.consent"
          class="w-full bg-blue-600 hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed
                 text-white font-semibold rounded-lg py-2.5 transition-colors">
          @if (loading()) { Registering… } @else { Register Free }
        </button>
      </form>

      <p class="text-center text-sm text-slate-500 mt-4">
        Already registered? <a routerLink="/login" class="text-blue-400 hover:underline">Sign in</a>
      </p>
    </div>
  </div>
</div>
  `
})
export class RegisterComponent {
  form = { name: '', email: '', mobile: '', pan: '', password: '', consent: false };
  loading = signal(false);
  error   = signal('');
  success = signal('');

  constructor(private auth: AuthService, private router: Router) {}

  onRegister() {
    this.loading.set(true); this.error.set(''); this.success.set('');
    this.auth.register({ ...this.form, pan: this.form.pan.toUpperCase() }).subscribe({
      next: res => {
        this.success.set('Registration successful! Redirecting…');
        setTimeout(() => this.router.navigate(['/dashboard']), 1500);
      },
      error: err => { this.error.set(err.error?.message || 'Registration failed.'); this.loading.set(false); }
    });
  }
}
