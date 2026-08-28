import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../core/services/api.service';
import { InrPipe } from '../../shared/pipes/inr.pipe';
@Component({
  selector: 'app-communication',
  standalone: true,
  imports: [CommonModule, InrPipe],
  template: `<div class="p-6"><h1 class="section-title">📨 Communications</h1><p class="text-slate-400 text-sm mb-4">Connected to Java REST API at /api/communication</p>@if(loading()){<div class="text-slate-400 text-center py-12">Loading...</div>}@if(data){<pre class="text-xs text-slate-400 bg-slate-900 rounded p-4 overflow-auto max-h-96">{{data|json}}</pre>}</div>`
})
export class CommunicationComponent implements OnInit {
  loading = signal(true); data: any = null;
  constructor(private api: ApiService) {}
  ngOnInit() { this.api.get<any>('/communication').subscribe({ next: r => { this.data = r.data||r; this.loading.set(false); }, error: () => this.loading.set(false) }); }
}
