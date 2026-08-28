import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { RealtimeService } from '../../core/services/realtime.service';
import { InrPipe } from '../../shared/pipes/inr.pipe';
@Component({
  selector: 'app-screener',
  standalone: true,
  imports: [CommonModule, InrPipe],
  template: `<div class="p-6"><h1 class="section-title">🔍 Screener</h1><p class="text-slate-400 text-sm mb-4">Connected to Java REST API at /api/screener</p>@if(loading()){<div class="text-slate-400 text-center py-12">Loading...</div>}@if(data){<pre class="text-xs text-slate-400 bg-slate-900 rounded p-4 overflow-auto max-h-96">{{data|json}}</pre>}</div>`
})
export class ScreenerComponent implements OnInit, OnDestroy {
  loading = signal(true); data: any = null;
  private sub?: Subscription;
  constructor(private api: ApiService, private realtime: RealtimeService) {}
  ngOnInit() {
    this.load();
    this.sub = this.realtime.on('scanner').subscribe(() => this.load());
  }
  ngOnDestroy() { this.sub?.unsubscribe(); }
  load() { this.api.get<any>('/screener').subscribe({ next: r => { this.data = r.data || r; this.loading.set(false); }, error: () => this.loading.set(false) }); }
}
