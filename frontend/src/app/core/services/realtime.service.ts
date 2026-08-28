import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { filter } from 'rxjs/operators';
import { LivePriceService } from './live-price.service';

/**
 * Phase 2 — module-wide auto-refresh.
 *
 * The backend pushes a lightweight SSE "refresh" event ({ module }) whenever a
 * module's data changes. A component subscribes to on('<module>') and reloads
 * only its own data — no full-page reloads, no manual refresh button needed.
 */
@Injectable({ providedIn: 'root' })
export class RealtimeService {
  constructor(private live: LivePriceService) {}

  /** Emits each time the server signals that the given module has fresh data. */
  on(module: string): Observable<any> {
    return this.live.refresh$.pipe(filter((m: any) => !!m && m.module === module));
  }

  /** Raw stream of all module-refresh signals. */
  get any$(): Observable<any> {
    return this.live.refresh$;
  }
}
