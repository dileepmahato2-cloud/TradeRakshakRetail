import { Component, OnDestroy, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive, RouterOutlet, Router, NavigationEnd } from '@angular/router';
import { Subscription, filter } from 'rxjs';
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

    <!-- Backdrop: only exists while the drawer is open, and only below lg. -->
    @if (navOpen) {
      <div class="fixed inset-0 z-30 bg-black/60 lg:hidden"
           (click)="closeNav()" aria-hidden="true"></div>
    }

    <!--
      One element, two layouts. Below lg it is a fixed off-canvas drawer translated out of
      view; from lg up the translate and fixed positioning are overridden and it is the
      ordinary static column it has always been, so the desktop layout is unchanged.
    -->
    <aside class="fixed inset-y-0 left-0 z-40 w-64 max-w-[82vw] transform transition-transform
                  duration-200 ease-out flex flex-col overflow-y-auto overscroll-contain
                  bg-[#0B1420] border-r border-slate-800
                  lg:static lg:z-auto lg:w-56 lg:max-w-none lg:translate-x-0 lg:flex-shrink-0"
           [class.translate-x-0]="navOpen"
           [class.-translate-x-full]="!navOpen"
           [attr.aria-hidden]="navOpen ? null : 'true'">
      <div class="px-4 py-5 border-b border-slate-800 flex items-start justify-between gap-2">
        <div>
          <div class="text-lg font-bold text-gold">TradeRakshak</div>
          <div class="text-xs text-slate-500">v11.5</div>
        </div>
        <!-- Closing from inside the drawer: the backdrop is easy to miss on a small screen. -->
        <button type="button" (click)="closeNav()" aria-label="Close menu"
                class="lg:hidden -mr-1 -mt-1 p-2 rounded-lg text-slate-400 hover:text-white
                       hover:bg-slate-800/60 transition-colors">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               class="w-5 h-5" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" stroke-linecap="round"/>
          </svg>
        </button>
      </div>
      <!-- Tapping any link closes the drawer; the click bubbles up to here. -->
      <nav class="flex-1 px-2 py-3 space-y-0.5 text-sm" (click)="onNavClick($event)">
        <a routerLink="/dashboard" routerLinkActive="active" class="nav-link">Dashboard</a>

        <div class="pt-2 pb-1 px-3 text-xs text-slate-600 uppercase tracking-wider">Market Analytics</div>
        <a routerLink="/market-breadth" routerLinkActive="active" class="nav-link">Stock Tracker Dashboard</a>
        <a routerLink="/stock-tracker" routerLinkActive="active" class="nav-link">Stock Upload</a>
        <a routerLink="/trading" routerLinkActive="active" class="nav-link">Trading</a>
        <a routerLink="/data" routerLinkActive="active" class="nav-link">Data Warehouse</a>
        <a routerLink="/events" routerLinkActive="active" class="nav-link">Events Calendar</a>
        <a routerLink="/broker" routerLinkActive="active" class="nav-link">Broker</a>
        <a routerLink="/reports" routerLinkActive="active" class="nav-link">Reports</a>

        <div class="pt-2 pb-1 px-3 text-xs text-slate-600 uppercase tracking-wider">Strategy</div>
        <a routerLink="/strategy-builder" routerLinkActive="active" class="nav-link">Strategy Builder</a>
        <a routerLink="/strategy-tester" routerLinkActive="active" class="nav-link">Strategy Tester</a>

        <div class="pt-2 pb-1 px-3 text-xs text-slate-600 uppercase tracking-wider">User Management</div>
        <a routerLink="/user-hierarchy" routerLinkActive="active" class="nav-link">User Hierarchy</a>

        @if (auth.isAdmin()) {
          <div class="pt-2 pb-1 px-3 text-xs text-slate-600 uppercase tracking-wider">Administration</div>
          <a routerLink="/users" routerLinkActive="active" class="nav-link">Users</a>
          <a routerLink="/admin/unusual-alerts" routerLinkActive="active" class="nav-link">Unusual Alerts</a>
        }

        <div class="pt-2 pb-1 px-3 text-xs text-slate-600 uppercase tracking-wider">Settings</div>
          <a routerLink="/indicator-settings" routerLinkActive="active" class="nav-link">Indicator Engine</a>
          <a routerLink="/profile" routerLinkActive="active" class="nav-link">Profile</a>
          <a routerLink="/issues" routerLinkActive="active" class="nav-link">Issues</a>
          <a routerLink="/feedback" routerLinkActive="active" class="nav-link">Feedback</a>
      </nav>
      <div class="px-2 py-3 border-t border-slate-800">
        <button (click)="logout()" class="nav-link w-full text-red-400 hover:text-red-300">Logout</button>
      </div>
    </aside>

    <!--
      min-w-0 is load-bearing: without it this flex child refuses to shrink below the
      intrinsic width of its widest table, which pushes the whole page sideways instead of
      letting each table scroll inside its own container.
    -->
    <div class="flex-1 flex flex-col min-w-0">
      <!-- The only way to reach the nav below lg, where the drawer starts closed. -->
      <header class="lg:hidden flex items-center gap-2 px-2 py-2 bg-[#0B1420]
                     border-b border-slate-800 flex-shrink-0">
        <button type="button" (click)="toggleNav()" aria-label="Open menu"
                [attr.aria-expanded]="navOpen"
                class="p-2 rounded-lg text-slate-300 hover:text-white hover:bg-slate-800/60
                       transition-colors">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               class="w-6 h-6" aria-hidden="true">
            <path d="M4 7h16M4 12h16M4 17h16" stroke-linecap="round"/>
          </svg>
        </button>
        <span class="text-base font-bold text-gold">TradeRakshak</span>
      </header>

      <main class="flex-1 overflow-y-auto bg-[#0B1E3D]">
        <router-outlet />
        <footer class="px-4 py-4 text-center text-[11px] leading-5 text-slate-400 border-t border-slate-800 bg-[#071425]">
          {{ sebiDisclaimer }}
        </footer>
      </main>
    </div>
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

  /**
   * Whether the nav drawer is showing. Only consulted below lg, where the sidebar is an
   * overlay; from lg up the stylesheet pins it open regardless of this value, so the
   * desktop layout cannot be broken by leaving it false.
   */
  navOpen = false;

  private routerSub: Subscription;

  constructor(public auth: AuthService, private router: Router, private livePrices: LivePriceService) {
    // A drawer left open across a route change would cover the page the user just asked for.
    // Closing on NavigationEnd also covers navigation that did not come from a nav tap -
    // a redirect, a back gesture, or a link inside the page content.
    this.routerSub = this.router.events
      .pipe(filter(e => e instanceof NavigationEnd))
      .subscribe(() => { this.navOpen = false; });
  }

  ngOnDestroy(): void {
    this.routerSub.unsubscribe();
    this.livePrices.disconnect();
  }

  toggleNav(): void { this.navOpen = !this.navOpen; }
  closeNav(): void { this.navOpen = false; }

  /**
   * Closes the drawer when a link is tapped, but not when the section headings or the
   * padding around them are, so a stray tap inside the nav does not dismiss it.
   */
  onNavClick(event: Event): void {
    const el = event.target as HTMLElement | null;
    if (el && el.closest('a')) this.closeNav();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void { this.closeNav(); }

  logout(): void {
    this.livePrices.disconnect();
    this.auth.logout();
  }

  isAuthPage(): boolean {
    const url = this.router.url;
    return url.startsWith('/login') || url.startsWith('/register');
  }
}
