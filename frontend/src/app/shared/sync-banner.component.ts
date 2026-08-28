import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { LivePriceService } from '../core/services/live-price.service';
import { environment } from '../../environments/environment';

/**
 * Non-modal, dismissable banner that reflects the background data-synchronization
 * status. It reads the initial state once from /sync/status and then updates live
 * from the SSE "sync" stream. The user keeps working while it is shown.
 */
@Component({
  selector: 'app-sync-banner',
  standalone: true,
  imports: [CommonModule],
  template: `
    @if (visible()) {
      <div style="position:fixed; top:0; left:0; right:0; z-index:9999; display:flex; align-items:center; gap:12px;
                  padding:8px 16px; font-size:12px; color:#e5eefb;
                  background:linear-gradient(90deg,#0b2545,#123a63); border-bottom:1px solid #1d6adb;
                  box-shadow:0 2px 8px rgba(0,0,0,.35);">
        @if (phase() !== 'COMPLETED' && phase() !== 'ERROR') {
          <span style="width:12px;height:12px;border:2px solid #3b82f6;border-top-color:transparent;border-radius:50%;
                       display:inline-block;animation:sbspin .8s linear infinite;"></span>
        }
        <span style="font-weight:800;">{{ title() }}</span>
        <span style="opacity:.9;">{{ message() }}</span>
        @if (percent() > 0 && percent() < 100) {
          <span style="flex:0 0 140px; height:6px; background:#0a1a33; border-radius:4px; overflow:hidden; margin-left:auto;">
            <span [style.width.%]="percent()" style="display:block;height:100%;background:#1d6adb;transition:width .4s;"></span>
          </span>
          <span style="min-width:34px; text-align:right;">{{ percent() }}%</span>
        }
        <button (click)="dismiss()" title="Dismiss"
                style="margin-left:auto;background:none;border:none;color:#9db4d4;cursor:pointer;font-size:15px;line-height:1;">&times;</button>
      </div>
    }
    <style>@keyframes sbspin{to{transform:rotate(360deg)}}</style>
  `
})
export class SyncBannerComponent implements OnInit, OnDestroy {
  visible = signal(false);
  phase = signal('IDLE');
  title = signal('Synchronizing');
  message = signal('');
  percent = signal(0);

  private sub?: Subscription;
  private hideTimer: any;

  constructor(private http: HttpClient, private live: LivePriceService) {}

  ngOnInit(): void {
    this.fetchOnce();
    this.sub = this.live.sync$.subscribe(s => this.apply(s));
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
    if (this.hideTimer) clearTimeout(this.hideTimer);
  }

  private fetchOnce(): void {
    this.http.get<any>(`${environment.apiUrl}/sync/status`).subscribe({
      next: r => this.apply(r?.data ?? r),
      error: () => {}
    });
  }

  private apply(s: any): void {
    if (!s || !s.phase) return;
    const phase = String(s.phase);
    this.phase.set(phase);
    this.message.set(s.message || '');
    this.percent.set(Number(s.percent || 0));
    this.title.set(this.titleFor(phase, s.market_status));

    if (phase === 'IDLE') { this.visible.set(false); return; }
    this.visible.set(true);
    if (phase === 'COMPLETED' || phase === 'ERROR') {
      if (this.hideTimer) clearTimeout(this.hideTimer);
      this.hideTimer = setTimeout(() => this.visible.set(false), 6000);
    }
  }

  private titleFor(phase: string, market?: string): string {
    switch (phase) {
      case 'STARTED': return 'Starting synchronization';
      case 'CHECKING': return market === 'OPEN' ? 'Market open' : 'Market closed';
      case 'DOWNLOADING': return 'Downloading missing data';
      case 'UPDATING': return 'Updating database';
      case 'REFRESHING': return 'Refreshing dashboard';
      case 'COMPLETED': return 'Synchronization completed';
      case 'ERROR': return 'Synchronization error';
      default: return 'Synchronizing';
    }
  }

  dismiss(): void {
    this.visible.set(false);
  }
}
