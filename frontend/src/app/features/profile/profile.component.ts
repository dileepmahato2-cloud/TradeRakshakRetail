import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host { display:block; min-height:100vh; background:#0b1e3d; color:#e2e8f0; }
    .page { padding:20px; }
    .alert { border:1px solid #9a6a19; background:#283244; color:#fbbf24; border-radius:6px; padding:12px 16px; margin-bottom:18px; }
    .title { font-size:28px; font-weight:900; color:#fff; margin:0 0 24px; }
    .grid { display:grid; grid-template-columns:minmax(320px, 1fr) minmax(320px, 1fr); gap:20px; }
    .card { background:#10233d; border:1px solid #214467; border-radius:8px; padding:24px; }
    .card-title { color:#2f8dff; font-size:22px; font-weight:800; margin-bottom:16px; }
    label { display:block; color:#8fb4d8; font-size:14px; margin:12px 0 6px; }
    input { width:100%; background:#0b1829; border:1px solid #24507d; color:#fff; border-radius:6px; padding:12px; font-size:15px; }
    .btn { border:0; border-radius:7px; padding:12px 20px; font-weight:800; cursor:pointer; margin-top:14px; background:#1d6adb; color:#fff; }
    .btn:disabled { opacity:.5; cursor:not-allowed; }
    .detail-row { display:grid; grid-template-columns:140px 1fr; gap:12px; padding:12px 14px; border-bottom:1px solid #1f3957; align-items:center; }
    .detail-row span:first-child { color:#8fb4d8; }
    .badge { border-radius:6px; padding:5px 10px; font-weight:800; font-size:12px; display:inline-block; width:max-content; }
    .role { background:#3b1020; color:#ff8fbb; border:1px solid #be3158; }
    .active { background:#0d3a2b; color:#5eead4; border:1px solid #17806c; }
    .message { margin-top:12px; color:#5eead4; font-size:14px; }
    .empty { border:1px solid #24415f; border-radius:7px; color:#94a3b8; padding:18px; }
    @media (max-width: 900px) { .grid { grid-template-columns:1fr; } .detail-row { grid-template-columns:1fr; } }
  `],
  template: `
<div class="page">
  <div class="alert">Market closed - last data: {{ today }}</div>
  <h1 class="title">My Profile</h1>

  @if (loading()) {
    <div class="empty">Loading profile...</div>
  } @else {
    <div class="grid">
      <section class="card">
        <div class="card-title">Update Profile</div>
        <label>Full Name</label>
        <input [(ngModel)]="form.name" placeholder="Full name">

        <label>Email</label>
        <input [(ngModel)]="form.email" placeholder="Email">

        <label>Mobile</label>
        <input [(ngModel)]="form.mobile" placeholder="Mobile number">

        <label>New Password (leave blank to keep current)</label>
        <input type="password" [(ngModel)]="form.password" placeholder="New password">

        <button class="btn" (click)="update()" [disabled]="saving || !form.name.trim() || !form.email.trim()">
          {{ saving ? 'Updating...' : 'Update' }}
        </button>
        @if (message) { <div class="message">{{ message }}</div> }
      </section>

      <section class="card">
        <div class="card-title">Account Details</div>
        <div class="detail-row"><span>Role</span><strong><span class="badge role">{{ profile.role || '-' }}</span></strong></div>
        <div class="detail-row"><span>Level</span><strong>{{ profile.level || '-' }}</strong></div>
        <div class="detail-row"><span>Plan</span><strong>{{ profile.plan || '-' }}</strong></div>
        <div class="detail-row"><span>Status</span><strong><span class="badge active">{{ profile.status || 'active' }}</span></strong></div>
        <div class="detail-row"><span>PAN</span><strong>{{ profile.pan || 'Not provided' }}</strong></div>
        <div class="detail-row"><span>OTP Enabled</span><strong>{{ profile.otpEnabled ? 'Yes' : 'No' }}</strong></div>
        <div class="detail-row"><span>Registered</span><strong>{{ shortDate(profile.registered) }}</strong></div>
        <div class="detail-row"><span>Consent At</span><strong>{{ profile.consentAt || '-' }}</strong></div>
      </section>
    </div>
  }
</div>
  `
})
export class ProfileComponent implements OnInit {
  loading = signal(true);
  saving = false;
  message = '';
  today = new Date().toISOString().slice(0, 10);
  profile: any = {};
  form = { name: '', email: '', mobile: '', password: '' };

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.load();
  }

  load() {
    this.api.get<any>('/profile').subscribe({
      next: r => {
        this.profile = r.data || {};
        this.form.name = this.profile.name || '';
        this.form.email = this.profile.email || '';
        this.form.mobile = this.profile.mobile || '';
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  update() {
    this.saving = true;
    this.message = '';
    this.api.put<any>('/profile', this.form).subscribe({
      next: r => {
        this.profile = r.data || this.profile;
        this.form.password = '';
        this.message = 'Profile updated successfully.';
        this.saving = false;
      },
      error: e => {
        alert(e.error?.message || 'Profile could not be updated.');
        this.saving = false;
      }
    });
  }

  shortDate(value: string): string {
    return value ? String(value).slice(0, 10) : '-';
  }
}
