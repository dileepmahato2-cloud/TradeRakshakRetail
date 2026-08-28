import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ApiService } from '../../core/services/api.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-events',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host{display:block;min-height:100vh;background:#0b1e3d;color:#e2e8f0}.page{padding:20px}.hero{background:linear-gradient(120deg,#102447,#0d2e37);border:1px solid #284466;border-top:3px solid #16d6a4;border-radius:8px;padding:18px;margin-bottom:14px}.title{font-size:28px;font-weight:900;color:#fff}.subtle{color:#93b4d8;font-size:12px}.grid{display:grid;gap:14px}.widgets{grid-template-columns:repeat(auto-fit,minmax(150px,1fr));margin-bottom:14px}.widget,.panel{background:#10233d;border:1px solid #243d5d;border-radius:8px}.widget{padding:12px}.widget .value{font-size:22px;font-weight:900;color:#fff}.tabs{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px}.tabs button{border:1px solid #31506f;background:#071426;color:#bcd2ea;border-radius:7px;padding:9px 12px;font-weight:900;cursor:pointer}.tabs button.active{background:#1d7be8;color:#fff}.panel{padding:16px}.panel-title{font-size:18px;font-weight:900;color:#fff;margin-bottom:12px}.form-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}.edit-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}label{display:block;color:#93b4d8;font-size:12px;margin-bottom:5px}.input,.select,.textarea{width:100%;background:#071426;border:1px solid #31506f;border-radius:6px;color:#fff;padding:9px 10px;min-height:38px}.textarea{min-height:90px;resize:vertical}.btn{border:1px solid #334865;border-radius:7px;padding:9px 13px;font-size:12px;font-weight:900;cursor:pointer;color:#e5eefb;background:#1b2940}.btn.primary{background:#2563eb;border-color:#2563eb}.btn.green{background:#16803f;border-color:#22c55e}.btn.red{background:#b91c1c;border-color:#ef4444}.btn.warn{background:#f59e0b;border-color:#f59e0b;color:#111827}.actions{display:flex;gap:8px;flex-wrap:wrap;align-items:center}.impact{display:inline-flex;border-radius:999px;padding:4px 9px;font-size:11px;font-weight:900}.impact.high{background:#3b1212;color:#fecaca;border:1px solid #ef4444}.impact.medium{background:#35270a;color:#fde68a;border:1px solid #f59e0b}.impact.low{background:#0d2b1d;color:#86efac;border:1px solid #22c55e}.status{border:1px solid #2f4d6f;border-radius:7px;background:#071426;padding:10px;margin-bottom:14px}.calendar{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:12px}.card{background:#0b1829;border:1px solid #263f5d;border-radius:8px;padding:12px;border-left:4px solid #64748b}.card.high{border-left-color:#ef4444}.card.medium{border-left-color:#f59e0b}.card.low{border-left-color:#22c55e}.card-title{font-weight:900;color:#fff;margin-bottom:6px}.meta{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}.chip{display:inline-flex;border:1px solid #355274;border-radius:999px;padding:4px 8px;font-size:11px;color:#cbd5e1}.table-wrap{overflow:auto;border:1px solid #263f5d;border-radius:7px}table{width:100%;border-collapse:collapse;min-width:1100px}th,td{padding:9px 10px;border-bottom:1px solid #203650;text-align:left;font-size:12px;white-space:nowrap}th{background:#173456;color:#fff;text-transform:uppercase;font-size:11px}.split{display:grid;grid-template-columns:minmax(360px,.8fr) minmax(520px,1.2fr);gap:14px}.log{max-height:260px;overflow:auto}.empty{text-align:center;color:#94a3b8;padding:24px}@media(max-width:1100px){.form-grid,.edit-grid,.split{grid-template-columns:1fr}}
  `],
  template: `
<div class="page">
  <section class="hero">
    <div class="actions" style="justify-content:space-between">
      <div>
        <div class="title">Events Calendar</div>
        <div class="subtle">Automated finance and stock market event calendar for India, global macro events, and stock-specific events.</div>
      </div>
      <div class="actions">
        <button class="btn primary" (click)="fetchLatest()">Fetch Latest</button>
        <button class="btn" (click)="exportEvents()">Export Excel CSV</button>
        <button class="btn" (click)="printPage()">Print / PDF</button>
      </div>
    </div>
  </section>

  @if (status) {
    <div class="status">
      <strong>{{ status.message }}</strong>
      <span class="subtle" style="margin-left:8px">Last updated: {{ status.lastUpdated || 'Not available' }}</span>
    </div>
  }

  <div class="grid widgets">
    <div class="widget"><div class="subtle">Today</div><div class="value">{{ summary.today || 0 }}</div></div>
    <div class="widget"><div class="subtle">High Impact</div><div class="value">{{ summary.highImpact || 0 }}</div></div>
    <div class="widget"><div class="subtle">Upcoming 7 Days</div><div class="value">{{ summary.upcoming7Days || 0 }}</div></div>
    <div class="widget"><div class="subtle">Active Sources</div><div class="value">{{ summary.sources || 0 }}</div></div>
  </div>

  <div class="tabs">
    <button [class.active]="tab==='calendar'" (click)="tab='calendar'">Market Events</button>
    <button [class.active]="tab==='global'" (click)="tab='global'">Global Events</button>
    <button [class.active]="tab==='corporate'" (click)="tab='corporate'">Corporate Actions</button>
    <button [class.active]="tab==='economic'" (click)="tab='economic'">Economic Calendar</button>
    <button [class.active]="tab==='today'" (click)="tab='today'">Today's Important Events</button>
    <button [class.active]="tab==='alerts'" (click)="tab='alerts'">Event Alerts</button>
    <button [class.active]="tab==='sources'" (click)="tab='sources'">Event Source Settings</button>
  </div>

  <section class="panel" style="margin-bottom:14px">
    <div class="panel-title">Filters</div>
    <div class="form-grid">
      <div><label>Country</label><select class="select" [(ngModel)]="filters.country"><option value="">All</option><option>India</option><option>United States</option><option>Global</option></select></div>
      <div><label>Event Type</label><select class="select" [(ngModel)]="filters.eventType"><option value="">All</option>@for (t of eventTypes; track t) { <option>{{ t }}</option> }</select></div>
      <div><label>Stock</label><input class="input" [(ngModel)]="filters.stock" placeholder="RELIANCE"></div>
      <div><label>Sector</label><input class="input" [(ngModel)]="filters.sector" placeholder="Banking"></div>
      <div><label>From Date</label><input class="input" type="date" [(ngModel)]="filters.from"></div>
      <div><label>To Date</label><input class="input" type="date" [(ngModel)]="filters.to"></div>
      <div><label>Impact</label><select class="select" [(ngModel)]="filters.impact"><option value="">All</option><option>High</option><option>Medium</option><option>Low</option></select></div>
      <div><label>Search</label><input class="input" [(ngModel)]="filters.search" placeholder="RBI, CPI, dividend"></div>
    </div>
    <div class="actions" style="justify-content:flex-end;margin-top:12px"><button class="btn" (click)="resetFilters()">Clear</button><button class="btn primary" (click)="load()">Apply Filter</button></div>
  </section>

  @if (loading()) {
    <div class="panel">Loading events...</div>
  } @else {
    @if (tab==='calendar' || tab==='global' || tab==='corporate' || tab==='economic') {
      <section class="panel">
        <div class="actions" style="justify-content:space-between;margin-bottom:12px">
          <div class="panel-title" style="margin:0">{{ tabTitle() }}</div>
          <div class="actions"><button class="btn" (click)="viewMode='cards'" [class.primary]="viewMode==='cards'">Calendar View</button><button class="btn" (click)="viewMode='list'" [class.primary]="viewMode==='list'">List View</button></div>
        </div>
        @if (viewMode==='cards') {
          <div class="calendar">
            @for (e of visibleEvents(); track e.id) {
              <article [class]="'card ' + impactClass(e.impactLevel)">
                <div class="actions" style="justify-content:space-between">
                  <span class="chip">{{ e.eventDate }} {{ e.eventTime || '' }}</span>
                  <span [class]="'impact ' + impactClass(e.impactLevel)">{{ e.impactLevel }}</span>
                </div>
                <div class="card-title">{{ e.eventTitle }}</div>
                <div class="subtle">{{ e.description || '-' }}</div>
                <div class="meta">
                  <span class="chip">{{ e.eventType }}</span><span class="chip">{{ e.country }}</span>
                  <span class="chip">{{ displaySymbol(e) }}</span>
                  @if (e.sector) { <span class="chip">{{ e.sector }}</span> }
                </div>
                <div class="actions" style="margin-top:10px"><button class="btn" (click)="editEvent(e)">Edit</button><button class="btn red" (click)="deleteEvent(e)">Delete</button></div>
              </article>
            }
          </div>
        } @else {
          <ng-container *ngTemplateOutlet="eventTable; context:{ rows: visibleEvents() }"></ng-container>
        }
        @if (!visibleEvents().length) { <div class="empty">No events found for this section.</div> }
      </section>
    }

    @if (tab==='today') {
      <section class="panel">
        <div class="panel-title">Today's Important Events</div>
        <ng-container *ngTemplateOutlet="eventTable; context:{ rows: today }"></ng-container>
      </section>
      <section class="panel" style="margin-top:14px">
        <div class="panel-title">Upcoming 7 Days Events</div>
        <ng-container *ngTemplateOutlet="eventTable; context:{ rows: upcoming7Days }"></ng-container>
      </section>
      <section class="panel" style="margin-top:14px">
        <div class="panel-title">Events Affecting Watchlist Stocks</div>
        <ng-container *ngTemplateOutlet="eventTable; context:{ rows: watchlistEvents }"></ng-container>
      </section>
    }

    @if (tab==='alerts') {
      <div class="split">
        <section class="panel">
          <div class="panel-title">{{ eventForm.id ? 'Edit Event' : 'Manual Event Add' }}</div>
          <div class="edit-grid">
            <div><label>Title</label><input class="input" [(ngModel)]="eventForm.eventTitle"></div>
            <div><label>Event Type</label><select class="select" [(ngModel)]="eventForm.eventType">@for (t of eventTypes; track t) { <option>{{ t }}</option> }</select></div>
            <div><label>Country</label><input class="input" [(ngModel)]="eventForm.country"></div>
            <div><label>Market</label><input class="input" [(ngModel)]="eventForm.market"></div>
            <div><label>Stock Symbol</label><input class="input" [(ngModel)]="eventForm.stockSymbol"></div>
            <div><label>Sector</label><input class="input" [(ngModel)]="eventForm.sector"></div>
            <div><label>Date</label><input class="input" type="date" [(ngModel)]="eventForm.eventDate"></div>
            <div><label>Time</label><input class="input" type="time" [(ngModel)]="eventForm.eventTime"></div>
            <div><label>Impact</label><select class="select" [(ngModel)]="eventForm.impactLevel"><option>High</option><option>Medium</option><option>Low</option></select></div>
            <div><label>Source</label><input class="input" [(ngModel)]="eventForm.source"></div>
          </div>
          <div style="margin-top:10px"><label>Description</label><textarea class="textarea" [(ngModel)]="eventForm.description"></textarea></div>
          <div class="actions" style="margin-top:10px">
            <label><input type="checkbox" [(ngModel)]="eventForm.tradingLogicEnabled"> Trading Logic</label>
            <label><input type="checkbox" [(ngModel)]="eventForm.scannerEnabled"> Scanner</label>
            <label><input type="checkbox" [(ngModel)]="eventForm.rankingEnabled"> Stock Ranking</label>
          </div>
          <div class="actions" style="justify-content:flex-end;margin-top:12px"><button class="btn" (click)="resetEventForm()">Clear</button><button class="btn primary" (click)="saveEvent()">Save Event</button></div>
        </section>
        <section class="panel">
          <div class="panel-title">High Impact Alert Queue</div>
          <div class="calendar">
            @for (e of highImpact; track e.id) {
              <div class="card high"><span class="impact high">High</span><div class="card-title">{{ e.eventTitle }}</div><div class="subtle">{{ e.eventDate }} {{ e.eventTime || '' }} | {{ e.country }}</div><div class="meta"><span class="chip">In-App</span><span class="chip">Email</span><span class="chip">Telegram</span></div></div>
            }
          </div>
        </section>
      </div>
    }

    @if (tab==='sources') {
      <div class="split">
        <section class="panel">
          <div class="panel-title">Event Source Settings</div>
          <div class="edit-grid">
            <div><label>Source Name</label><input class="input" [(ngModel)]="sourceForm.sourceName"></div>
            <div><label>Source Type</label><select class="select" [(ngModel)]="sourceForm.sourceType"><option>Public API</option><option>RSS/Public Web</option><option>NSE/BSE Data</option><option>Manual Upload</option></select></div>
            <div><label>Country</label><input class="input" [(ngModel)]="sourceForm.country"></div>
            <div><label>Event Group</label><input class="input" [(ngModel)]="sourceForm.eventGroup"></div>
            <div><label>API URL</label><input class="input" [(ngModel)]="sourceForm.apiUrl"></div>
            <div><label>API Key</label><input class="input" [(ngModel)]="sourceForm.apiKey"></div>
            <div><label>Status</label><select class="select" [(ngModel)]="sourceForm.status"><option>active</option><option>inactive</option></select></div>
          </div>
          <div class="actions" style="justify-content:flex-end;margin-top:12px"><button class="btn" (click)="resetSourceForm()">Clear</button><button class="btn primary" (click)="saveSource()">Save Source</button></div>
        </section>
        <section class="panel">
          <div class="panel-title">Sources and Fetch Logs</div>
          <div class="table-wrap"><table><thead><tr><th>Source</th><th>Type</th><th>Country</th><th>Status</th><th>Last Fetch</th><th>Message</th><th>Action</th></tr></thead><tbody>
            @for (s of sources; track s.id) { <tr><td>{{ s.sourceName }}</td><td>{{ s.sourceType }}</td><td>{{ s.country }}</td><td>{{ s.status }}</td><td>{{ s.lastFetchTime || '-' }}</td><td>{{ s.lastMessage || '-' }}</td><td><button class="btn" (click)="editSource(s)">Edit</button></td></tr> }
          </tbody></table></div>
          <div class="log" style="margin-top:12px">
            @for (l of fetchLogs; track l.completedAt + l.sourceName) { <div class="chip" style="margin:0 6px 6px 0">{{ l.completedAt }} - {{ l.sourceName }} - {{ l.fetchStatus }} - {{ l.eventsFound }}</div> }
          </div>
        </section>
      </div>
    }
  }

  <ng-template #eventTable let-rows="rows">
    <div class="table-wrap"><table><thead><tr><th>Date</th><th>Time</th><th>Event</th><th>Type</th><th>Country</th><th>Market</th><th>Stock</th><th>Sector</th><th>Impact</th><th>Source</th><th>Trading Link</th><th>Actions</th></tr></thead><tbody>
      @for (e of rows; track e.id) {
        <tr><td>{{ e.eventDate }}</td><td>{{ e.eventTime || '-' }}</td><td><strong>{{ e.eventTitle }}</strong></td><td>{{ e.eventType }}</td><td>{{ e.country }}</td><td>{{ e.market || '-' }}</td><td>{{ displaySymbol(e) }}</td><td>{{ e.sector || '-' }}</td><td><span [class]="'impact ' + impactClass(e.impactLevel)">{{ e.impactLevel }}</span></td><td>{{ e.source || '-' }}</td><td>{{ e.tradingLogicEnabled ? 'Trading' : '' }} {{ e.scannerEnabled ? 'Scanner' : '' }} {{ e.rankingEnabled ? 'Ranking' : '' }}</td><td class="actions"><button class="btn" (click)="editEvent(e)">Edit</button><button class="btn red" (click)="deleteEvent(e)">Delete</button></td></tr>
      }
      @if (!rows?.length) { <tr><td colspan="12" class="empty">No records yet.</td></tr> }
    </tbody></table></div>
  </ng-template>
</div>
  `
})
export class EventsComponent implements OnInit {
  loading = signal(true);
  tab = 'calendar';
  viewMode: 'cards' | 'list' = 'cards';
  events: any[] = [];
  today: any[] = [];
  highImpact: any[] = [];
  upcoming7Days: any[] = [];
  watchlistEvents: any[] = [];
  sources: any[] = [];
  fetchLogs: any[] = [];
  summary: any = {};
  status: any = null;
  filters: any = {};
  eventForm: any = {};
  sourceForm: any = {};
  eventTypes = ['RBI Policy Meeting','SEBI Circular','Market Holiday','F&O Expiry','IPO','Corporate Results','Corporate Action','Budget / Election / Policy','US Fed Meeting','Economic Data','Crude Oil Inventory','Dollar Index','Geopolitical News','Board Meeting','Bulk / Block Deal','Credit Rating Update','Important Announcement'];

  constructor(private api: ApiService, private http: HttpClient) {}
  ngOnInit() { this.resetFilters(); this.resetEventForm(); this.resetSourceForm(); this.load(); }

  load() {
    this.loading.set(true);
    this.api.get<any>('/events', this.filters).subscribe({
      next: r => { this.apply(r.data || {}); this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }

  apply(d: any) {
    this.events = d.events || [];
    this.today = d.today || [];
    this.highImpact = d.highImpact || [];
    this.upcoming7Days = d.upcoming7Days || [];
    this.watchlistEvents = d.watchlistEvents || [];
    this.sources = d.sources || [];
    this.fetchLogs = d.fetchLogs || [];
    this.summary = d.summary || {};
    this.status = d.status || null;
  }

  visibleEvents() {
    if (this.tab === 'global') return this.events.filter(e => e.country !== 'India');
    if (this.tab === 'corporate') return this.events.filter(e => ['Corporate Results','Corporate Action','Board Meeting','Bulk / Block Deal','Credit Rating Update','Important Announcement'].includes(e.eventType));
    if (this.tab === 'economic') return this.events.filter(e => ['Economic Data','US Fed Meeting','RBI Policy Meeting','Crude Oil Inventory','Dollar Index'].includes(e.eventType));
    return this.events.filter(e => e.country === 'India' || ['Market Event','F&O Expiry','Market Holiday','SEBI Circular','IPO'].includes(e.eventType));
  }

  displaySymbol(e: any): string {
    return String(e?.symbol || e?.stockSymbol || '').trim() || 'Symbol Missing';
  }

  tabTitle() {
    return this.tab === 'global' ? 'Global Events' : this.tab === 'corporate' ? 'Corporate Actions' : this.tab === 'economic' ? 'Economic Calendar' : 'Market Events';
  }

  fetchLatest() {
    this.api.post<any>('/events/fetch-latest', {}).subscribe({
      next: r => { alert(r.message || 'Fetch completed.'); this.apply(r.data?.status ? { ...r.data, ...{} } : r.data || {}); this.load(); },
      error: e => alert(e.error?.message || 'Fetch failed. Data not updated.')
    });
  }

  saveEvent() {
    this.eventForm.symbol = String(this.eventForm.symbol || this.eventForm.stockSymbol || '').trim().toUpperCase();
    this.eventForm.stockSymbol = this.eventForm.symbol;
    const call = this.eventForm.id ? this.api.put<any>(`/events/${this.eventForm.id}`, this.eventForm) : this.api.post<any>('/events', this.eventForm);
    call.subscribe({
      next: r => { alert(r.message || 'Event saved.'); this.resetEventForm(); this.load(); },
      error: e => alert(e.error?.message || 'Event could not be saved.')
    });
  }

  editEvent(e: any) { this.tab = 'alerts'; this.eventForm = { ...e, tradingLogicEnabled: !!e.tradingLogicEnabled, scannerEnabled: !!e.scannerEnabled, rankingEnabled: !!e.rankingEnabled }; }
  deleteEvent(e: any) {
    if (!confirm('Delete this event?')) return;
    this.api.delete<any>(`/events/${e.id}`).subscribe({ next: () => this.load(), error: err => alert(err.error?.message || 'Delete failed.') });
  }

  saveSource() {
    const call = this.sourceForm.id ? this.api.put<any>(`/events/sources/${this.sourceForm.id}`, this.sourceForm) : this.api.post<any>('/events/sources', this.sourceForm);
    call.subscribe({ next: r => { alert(r.message || 'Source saved.'); this.sources = r.data || []; this.resetSourceForm(); }, error: e => alert(e.error?.message || 'Source could not be saved.') });
  }
  editSource(s: any) { this.sourceForm = { ...s }; }

  resetFilters() { this.filters = { country: '', eventType: '', stock: '', sector: '', from: '', to: '', impact: '', search: '' }; }
  resetEventForm() {
    const today = new Date().toISOString().slice(0, 10);
    this.eventForm = { eventTitle: '', eventType: 'Important Announcement', country: 'India', market: 'NSE/BSE', stockSymbol: '', sector: '', eventDate: today, eventTime: '09:00', impactLevel: 'Medium', source: 'Manual', sourceUrl: '', description: '', status: 'active', tradingLogicEnabled: false, scannerEnabled: false, rankingEnabled: false };
  }
  resetSourceForm() { this.sourceForm = { sourceName: '', sourceType: 'Public API', apiUrl: '', apiKey: '', country: 'Global', eventGroup: 'Market Events', status: 'active' }; }

  exportEvents() { this.download('/events/export', 'events_calendar.csv'); }
  printPage() { window.print(); }
  private download(path: string, filename: string) {
    this.http.get(`${environment.apiUrl}${path}`, { responseType: 'blob' }).subscribe(blob => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = filename; a.click();
      URL.revokeObjectURL(url);
    });
  }
  impactClass(v: string) { const s = String(v || 'medium').toLowerCase(); return s === 'high' ? 'high' : s === 'low' ? 'low' : 'medium'; }
}
