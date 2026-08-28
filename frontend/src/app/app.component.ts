import { Component, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive, RouterOutlet, Router } from '@angular/router';
import { AuthService } from './core/services/auth.service';
import { LivePriceService } from './core/services/live-price.service';
import { SyncBannerComponent } from './shared/sync-banner.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive, SyncBannerComponent],
  template: `
<app-sync-banner />
@if (!isAuthPage()) {
  <div class="flex h-screen overflow-hidden">
    <aside class="w-56 flex-shrink-0 bg-[#0B1420] border-r border-slate-800 flex flex-col overflow-y-auto">
      <div class="px-4 py-5 border-b border-slate-800">
        <div class="text-lg font-bold text-gold">TradeRakshak</div>
        <div class="text-xs text-slate-500">v11.5</div>
      </div>
      <nav class="flex-1 px-2 py-3 space-y-0.5 text-sm">
        <a routerLink="/dashboard" routerLinkActive="active" class="nav-link">Dashboard</a>

        <div class="pt-2 pb-1 px-3 text-xs text-slate-600 uppercase tracking-wider">Market Analytics</div>
        <a routerLink="/market-breadth" routerLinkActive="active" class="nav-link">Stock Tracker Dashboard</a>
        <a routerLink="/stock-tracker" routerLinkActive="active" class="nav-link">Stock Upload</a>
        <a routerLink="/stock-ranking" routerLinkActive="active" class="nav-link">Stock Ranking</a>
        <a routerLink="/sector-intelligence" routerLinkActive="active" class="nav-link">Sector AI</a>
        <a routerLink="/trading" routerLinkActive="active" class="nav-link">Trading</a>
        <a routerLink="/paper" routerLinkActive="active" class="nav-link">Portfolio</a>
        <a routerLink="/watchlist" routerLinkActive="active" class="nav-link">Watchlist</a>
        <a routerLink="/options" routerLinkActive="active" class="nav-link">Options Chain</a>
        <a routerLink="/data" routerLinkActive="active" class="nav-link">Data Warehouse</a>
        <a routerLink="/events" routerLinkActive="active" class="nav-link">Events Calendar</a>
        <a routerLink="/broker" routerLinkActive="active" class="nav-link">Broker</a>
        <a routerLink="/competitions" routerLinkActive="active" class="nav-link">Competitions</a>
        <a routerLink="/reports" routerLinkActive="active" class="nav-link">Reports</a>

        <div class="pt-2 pb-1 px-3 text-xs text-slate-600 uppercase tracking-wider">Journal</div>
        <a routerLink="/journal" routerLinkActive="active" class="nav-link">Trade Journal</a>

        <div class="pt-2 pb-1 px-3 text-xs text-slate-600 uppercase tracking-wider">Strategy</div>
        <a routerLink="/strategy-builder" routerLinkActive="active" class="nav-link">Strategy Builder</a>

        <div class="pt-2 pb-1 px-3 text-xs text-slate-600 uppercase tracking-wider">Subscription</div>
        <a routerLink="/pricing" routerLinkActive="active" class="nav-link">Plans & Pricing</a>
        <a routerLink="/billing" routerLinkActive="active" class="nav-link">Billing History</a>

        <div class="pt-2 pb-1 px-3 text-xs text-slate-600 uppercase tracking-wider">User Management</div>
        <a routerLink="/user-hierarchy" routerLinkActive="active" class="nav-link">User Hierarchy</a>
        <a routerLink="/agents" routerLinkActive="active" class="nav-link">Agents</a>

        @if (auth.isAdmin()) {
          <div class="pt-2 pb-1 px-3 text-xs text-slate-600 uppercase tracking-wider">Administration</div>
          <a routerLink="/system-settings" routerLinkActive="active" class="nav-link">System Settings</a>
          <a routerLink="/admin/feature-config" routerLinkActive="active" class="nav-link">Feature Config</a>
          <a routerLink="/users" routerLinkActive="active" class="nav-link">Users</a>
          <a routerLink="/ml-engine" routerLinkActive="active" class="nav-link">ML Engine</a>
          <a routerLink="/admin/unusual-alerts" routerLinkActive="active" class="nav-link">Unusual Alerts</a>
          <a routerLink="/communication" routerLinkActive="active" class="nav-link">Communications</a>
          <a routerLink="/project-health" routerLinkActive="active" class="nav-link">Project Health</a>
        }

        <div class="pt-2 pb-1 px-3 text-xs text-slate-600 uppercase tracking-wider">Settings</div>
          <a routerLink="/indicator-settings" routerLinkActive="active" class="nav-link">Indicator Engine</a>
          <a routerLink="/notifications" routerLinkActive="active" class="nav-link">Notifications</a>
          <a routerLink="/profile" routerLinkActive="active" class="nav-link">Profile</a>
          <a routerLink="/issues" routerLinkActive="active" class="nav-link">Issues</a>
          <a routerLink="/feedback" routerLinkActive="active" class="nav-link">Feedback</a>
      </nav>
      <div class="px-2 py-3 border-t border-slate-800">
        <button (click)="logout()" class="nav-link w-full text-red-400 hover:text-red-300">Logout</button>
      </div>
    </aside>
    <main class="flex-1 overflow-y-auto bg-[#0B1E3D]">
      <router-outlet />
      <footer class="px-4 py-4 text-center text-[11px] leading-5 text-slate-400 border-t border-slate-800 bg-[#071425]">
        {{ sebiDisclaimer }}
      </footer>
    </main>
  </div>
} @else {
  <div class="min-h-screen bg-[#0B1E3D] flex flex-col">
    <div class="flex-1"><router-outlet /></div>
    <footer class="px-4 py-4 text-center text-[11px] leading-5 text-slate-300 border-t border-slate-800 bg-[#071425]">
      {{ sebiDisclaimer }}
    </footer>
  </div>
}
  `
})
export class AppComponent implements OnDestroy {
  sebiDisclaimer = '⚠ DISCLAIMER: TradeRakshak™ is a Technology Tools Platform. It is NOT a SEBI-registered Investment Advisor or Research Analyst. All content is for educational purposes only. Past performance does not guarantee future results. Consult a SEBI-empanelled advisor before trading.';

  constructor(public auth: AuthService, private router: Router, private livePrices: LivePriceService) {}
  ngOnDestroy(): void {
    this.livePrices.disconnect();
  }
  logout(): void {
    this.livePrices.disconnect();
    this.auth.logout();
  }
  isAuthPage(): boolean {
    const url = this.router.url;
    return url.startsWith('/login') || url.startsWith('/register');
  }
}
