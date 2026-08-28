import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';

@Component({
  selector: 'app-issues',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host { display:block; min-height:100vh; background:#0b1e3d; color:#e2e8f0; }
    .page { padding:20px; }
    .alert { border:1px solid #9a6a19; background:#283244; color:#fbbf24; border-radius:6px; padding:12px 16px; margin-bottom:18px; }
    .title { font-size:28px; font-weight:900; color:#fff; margin:0 0 18px; }
    .info { border:1px solid #1d5a9e; background:#10315f; color:#5fb0ff; padding:12px 16px; border-radius:7px; margin-bottom:20px; }
    .grid { display:grid; grid-template-columns:minmax(320px, 1fr) minmax(320px, 1fr); gap:20px; }
    .card { background:#10233d; border:1px solid #214467; border-radius:8px; padding:24px; min-height:430px; }
    .card-title { color:#2f8dff; font-size:22px; font-weight:800; margin-bottom:16px; }
    label { display:block; color:#8fb4d8; font-size:14px; margin:12px 0 6px; }
    select, textarea { width:100%; background:#0b1829; border:1px solid #24507d; color:#fff; border-radius:6px; padding:11px 12px; font-size:15px; }
    textarea { min-height:110px; resize:vertical; }
    .row { display:grid; grid-template-columns:1fr 1fr; gap:14px; }
    .btn { border:0; border-radius:7px; padding:12px 18px; font-weight:800; cursor:pointer; margin-top:14px; }
    .btn-submit { background:#dc2626; color:#fff; }
    .btn-submit:disabled { opacity:.5; cursor:not-allowed; }
    table { width:100%; border-collapse:collapse; overflow:hidden; border-radius:7px; }
    th { background:#17477d; color:#58a6ff; text-align:left; padding:12px; font-size:12px; text-transform:uppercase; }
    td { padding:12px; border-bottom:1px solid #1f3957; vertical-align:top; font-size:13px; }
    .badge { border-radius:6px; padding:5px 10px; font-weight:800; font-size:12px; display:inline-block; }
    .medium { background:#3b2d10; color:#fbbf24; border:1px solid #a87513; }
    .high { background:#3b1010; color:#ff8f8f; border:1px solid #dc2626; }
    .low { background:#0e3b2d; color:#5eead4; border:1px solid #17806c; }
    .status { background:#0f315d; color:#5fb0ff; border:1px solid #1c64b7; }
    .empty { border:1px solid #24415f; border-radius:7px; color:#94a3b8; padding:18px; }
    @media (max-width: 900px) { .grid, .row { grid-template-columns:1fr; } .card { min-height:auto; } }
  `],
  template: `
<div class="page">
  <div class="alert">Market closed - last data: {{ today }}</div>
  <h1 class="title">Raise an Issue</h1>
  <div class="info">Use this to report bugs, data errors, or any problems. Issues are logged and tracked for resolution.</div>

  @if (loading()) {
    <div class="empty">Loading issue page...</div>
  } @else {
    <div class="grid">
      <section class="card">
        <div class="card-title">Report New Issue</div>
        <div class="row">
          <div>
            <label>Module</label>
            <select [(ngModel)]="form.module">
              @for (module of modules; track module) { <option [ngValue]="module">{{ module }}</option> }
            </select>
          </div>
          <div>
            <label>Issue Type</label>
            <select [(ngModel)]="form.issueType">
              @for (type of issueTypes; track type) { <option [ngValue]="type">{{ type }}</option> }
            </select>
          </div>
        </div>

        <label>Description</label>
        <textarea [(ngModel)]="form.description" placeholder="Describe the issue in detail. Include steps to reproduce if possible."></textarea>

        <label>Severity</label>
        <select [(ngModel)]="form.severity">
          @for (severity of severities; track severity) { <option [ngValue]="severity">{{ severity }}</option> }
        </select>

        <button class="btn btn-submit" (click)="submit()" [disabled]="saving || !form.description.trim()">
          {{ saving ? 'Submitting...' : 'Submit Issue' }}
        </button>
      </section>

      <section class="card">
        <div class="card-title">My Issues</div>
        @if (issues.length === 0) {
          <div class="empty">No issues submitted yet.</div>
        } @else {
          <div style="overflow-x:auto;">
            <table>
              <thead>
                <tr>
                  <th>Module</th>
                  <th>Type</th>
                  <th>Description</th>
                  <th>Severity</th>
                  <th>Status</th>
                  <th>Date</th>
                </tr>
              </thead>
              <tbody>
                @for (issue of issues; track issue.id) {
                  <tr>
                    <td>{{ issue.module || '-' }}</td>
                    <td>{{ issue.issueType || '-' }}</td>
                    <td>{{ issue.description || '-' }}</td>
                    <td><span class="badge" [ngClass]="severityClass(issue.severity)">{{ issue.severity || 'Medium' }}</span></td>
                    <td><span class="badge status">{{ issue.status || 'Open' }}</span></td>
                    <td>{{ shortDate(issue.createdAt) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </div>
  }
</div>
  `
})
export class IssuesComponent implements OnInit {
  loading = signal(true);
  saving = false;
  today = new Date().toISOString().slice(0, 10);
  modules: string[] = [];
  issueTypes: string[] = [];
  severities: string[] = [];
  issues: any[] = [];
  form = { module: 'Strategy Builder', issueType: 'Bug', description: '', severity: 'Medium' };

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.load();
  }

  load() {
    this.api.get<any>('/issues').subscribe({
      next: r => {
        const data = r.data || {};
        this.modules = data.modules || ['Strategy Builder', 'Backtesting', 'Paper Trading', 'Trade Journal', 'Market Data', 'Data Warehouse', 'Broker API', 'Login/Auth', 'Other'];
        this.issueTypes = data.issueTypes || ['Bug', 'Data Error', 'Performance', 'UI Problem', 'Feature Request', 'Other'];
        this.severities = data.severities || ['High', 'Medium', 'Low'];
        this.issues = data.issues || [];
        this.form.module ||= this.modules[0];
        this.form.issueType ||= this.issueTypes[0];
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  submit() {
    this.saving = true;
    this.api.post<any>('/issues', this.form).subscribe({
      next: r => {
        this.issues = r.data?.issues || this.issues;
        this.form.description = '';
        this.saving = false;
      },
      error: e => {
        alert(e.error?.message || 'Issue could not be submitted.');
        this.saving = false;
      }
    });
  }

  severityClass(value: string): string {
    const v = (value || '').toLowerCase();
    if (v === 'high') return 'high';
    if (v === 'low') return 'low';
    return 'medium';
  }

  shortDate(value: string): string {
    return value ? String(value).slice(0, 10) : '-';
  }
}
