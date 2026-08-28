import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ApiService } from '../../core/services/api.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-feedback',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host { display:block; min-height:100vh; background:#0b1e3d; color:#e2e8f0; }
    .page { padding:20px; }
    .alert { border:1px solid #9a6a19; background:#283244; color:#fbbf24; border-radius:6px; padding:12px 16px; margin-bottom:18px; }
    .title { font-size:28px; font-weight:900; color:#fff; margin:0 0 24px; }
    .grid { display:grid; grid-template-columns:minmax(320px, 1fr) minmax(320px, 1fr); gap:20px; }
    .card { background:#10233d; border:1px solid #214467; border-radius:8px; padding:24px; min-height:430px; }
    .card-title { color:#2f8dff; font-size:22px; font-weight:800; margin-bottom:16px; }
    label { display:block; color:#8fb4d8; font-size:14px; margin:12px 0 6px; }
    select, input, textarea { width:100%; background:#0b1829; border:1px solid #24507d; color:#fff; border-radius:6px; padding:11px 12px; font-size:15px; }
    textarea { min-height:110px; resize:vertical; }
    .row { display:grid; grid-template-columns:1fr 1fr; gap:14px; }
    .btn { border:0; border-radius:7px; padding:12px 18px; font-weight:800; cursor:pointer; }
    .btn-primary { background:#1d6adb; color:#fff; }
    .btn-secondary { background:#1f334c; color:#cbd5e1; border:1px solid #355274; }
    .btn:disabled { opacity:.5; cursor:not-allowed; }
    .feedback-item { border:1px solid #24415f; border-radius:7px; padding:14px; margin-bottom:10px; background:#0d1d33; }
    .muted { color:#94a3b8; font-size:13px; }
    .badge { border-radius:6px; padding:4px 8px; font-weight:800; font-size:12px; display:inline-block; background:#10315f; color:#5fb0ff; border:1px solid #1c64b7; }
    .empty { border:1px solid #24415f; border-radius:7px; color:#94a3b8; padding:18px; }
    @media (max-width: 900px) { .grid, .row { grid-template-columns:1fr; } .card { min-height:auto; } }
  `],
  template: `
<div class="page">
  <div class="alert">Market closed - last data: {{ today }}</div>
  <h1 class="title">Feedback</h1>

  @if (loading()) {
    <div class="empty">Loading feedback page...</div>
  } @else {
    <div class="grid">
      <section class="card">
        <div class="card-title">Submit Feedback</div>
        <div class="row">
          <div>
            <label>Section</label>
            <select [(ngModel)]="form.section">
              @for (section of sections; track section) { <option [ngValue]="section">{{ section }}</option> }
            </select>
          </div>
          <div>
            <label>Sub-Section</label>
            <input [(ngModel)]="form.subSection" placeholder="Example: Filter logic, UI, performance">
          </div>
        </div>

        <label>Feedback / Suggestion</label>
        <textarea [(ngModel)]="form.message" placeholder="Share your thoughts, suggestions, or improvements..."></textarea>

        <label>Priority</label>
        <select [(ngModel)]="form.priority">
          @for (priority of priorities; track priority) { <option [ngValue]="priority">{{ priority }}</option> }
        </select>

        <div style="margin-top:14px;">
          <button class="btn btn-primary" (click)="submit()" [disabled]="saving || !form.message.trim()">
            {{ saving ? 'Submitting...' : 'Submit' }}
          </button>
        </div>
      </section>

      <section class="card">
        <div style="display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:14px;">
          <div class="card-title" style="margin:0;">My Feedback</div>
          <button class="btn btn-secondary" (click)="downloadFeedback()">Download</button>
        </div>
        @if (feedback.length === 0) {
          <div class="empty">No feedback submitted yet.</div>
        } @else {
          @for (item of feedback; track item.id) {
            <div class="feedback-item">
              <div style="display:flex; justify-content:space-between; gap:10px; flex-wrap:wrap;">
                <strong>{{ item.section || '-' }}</strong>
                <span class="badge">{{ item.priority || 'Medium' }}</span>
              </div>
              <div class="muted" style="margin:5px 0;">{{ item.subSection || 'No sub-section' }} | {{ shortDate(item.createdAt) }}</div>
              <div>{{ item.message || '-' }}</div>
            </div>
          }
        }
      </section>
    </div>
  }
</div>
  `
})
export class FeedbackComponent implements OnInit {
  loading = signal(true);
  saving = false;
  today = new Date().toISOString().slice(0, 10);
  sections: string[] = [];
  priorities: string[] = [];
  feedback: any[] = [];
  form = { section: 'Strategy Builder', subSection: '', message: '', priority: 'Medium' };

  constructor(private api: ApiService, private http: HttpClient) {}

  ngOnInit() {
    this.load();
  }

  load() {
    this.api.get<any>('/feedback').subscribe({
      next: r => {
        const data = r.data || {};
        this.sections = data.sections || ['Strategy Builder', 'Backtesting', 'Paper Trading', 'Trade Journal', 'Market Data', 'Data Warehouse', 'Checklist', 'Overall App', 'Other'];
        this.priorities = data.priorities || ['High', 'Medium', 'Low'];
        this.feedback = data.feedback || [];
        this.form.section ||= this.sections[0];
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  submit() {
    this.saving = true;
    this.api.post<any>('/feedback', this.form).subscribe({
      next: r => {
        this.feedback = r.data?.feedback || this.feedback;
        this.form.subSection = '';
        this.form.message = '';
        this.saving = false;
      },
      error: e => {
        alert(e.error?.message || 'Feedback could not be submitted.');
        this.saving = false;
      }
    });
  }

  downloadFeedback() {
    this.http.get(`${environment.apiUrl}/feedback/download`, { responseType: 'blob', observe: 'response' }).subscribe({
      next: response => {
        const blob = response.body!;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'feedback_details.csv';
        document.body.appendChild(a);
        a.click();
        URL.revokeObjectURL(url);
        document.body.removeChild(a);
      },
      error: () => alert('Feedback download could not be completed.')
    });
  }

  shortDate(value: string): string {
    return value ? String(value).slice(0, 10) : '-';
  }
}
