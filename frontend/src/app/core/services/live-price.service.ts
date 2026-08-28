import { Injectable, NgZone, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';

@Injectable({ providedIn: 'root' })
export class LivePriceService {
  private source: EventSource | null = null;
  private reconnectTimer: any;
  private reconnectDelayMs = 3000;
  private refCount = 0;

  readonly quote$ = new Subject<any>();
  readonly status$ = new Subject<any>();
  readonly top10$ = new Subject<any>();
  readonly sync$ = new Subject<any>();
  readonly refresh$ = new Subject<any>();
  readonly connectionState = signal<'connected' | 'connecting' | 'disconnected'>('disconnected');

  constructor(private auth: AuthService, private zone: NgZone) {}

  connect(): void {
    this.refCount++;
    const token = this.auth.getToken();
    if (!token || this.source) return;
    this.connectionState.set('connecting');
    const url = `${environment.apiUrl}/live-prices/stream?token=${encodeURIComponent(token)}`;
    this.source = new EventSource(url);

    this.source.addEventListener('connected', () => {
      this.zone.run(() => this.connectionState.set('connected'));
    });
    this.source.addEventListener('quote', (event: MessageEvent) => {
      this.zone.run(() => this.quote$.next(this.parse(event.data)));
    });
    this.source.addEventListener('status', (event: MessageEvent) => {
      this.zone.run(() => this.status$.next(this.parse(event.data)));
    });
    this.source.addEventListener('top10', (event: MessageEvent) => {
      this.zone.run(() => this.top10$.next(this.parse(event.data)));
    });
    this.source.addEventListener('sync', (event: MessageEvent) => {
      this.zone.run(() => this.sync$.next(this.parse(event.data)));
    });
    this.source.addEventListener('refresh', (event: MessageEvent) => {
      this.zone.run(() => this.refresh$.next(this.parse(event.data)));
    });
    this.source.onerror = () => {
      this.zone.run(() => {
        this.connectionState.set('disconnected');
        this.disconnect(false);
        if (this.refCount > 0) {
          this.reconnectTimer = setTimeout(() => this.connect(), this.reconnectDelayMs);
          this.refCount--;
        }
      });
    };
  }

  release(): void {
    this.refCount = Math.max(0, this.refCount - 1);
    if (this.refCount === 0) this.disconnect();
  }

  disconnect(clearTimer = true): void {
    if (clearTimer && this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.source) this.source.close();
    this.source = null;
    this.connectionState.set('disconnected');
  }

  private parse(value: string): any {
    try { return JSON.parse(value); }
    catch { return {}; }
  }
}
