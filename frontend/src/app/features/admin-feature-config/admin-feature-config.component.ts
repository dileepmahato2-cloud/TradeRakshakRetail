import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';

interface FeatureConfig {
  feature_key: string;
  feature_name: string;
  feature_group: string;
  is_enabled: number;
  schedule_restricted: number;
  allowed_start_time: string;
  allowed_end_time: string;
  allowed_days: string;
  timezone: string;
  blocked_message: string;
  min_plan: string;
  allowed_roles: string;
  http_status: number;
  description: string;
  updated_at: string;
}

const GROUP_ICONS: Record<string, string> = {
  trading: '📈', lms: '🎓', subscription: '💎', admin: '⚙️', ai: '🤖'
};

const ALL_DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

@Component({
  selector: 'app-admin-feature-config',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
<div class="p-6">
  <div class="flex items-center justify-between mb-6">
    <div>
      <h1 class="section-title">⚙️ Feature Configuration</h1>
      <p class="text-slate-400 text-sm">
        Enable/disable features, configure schedules (e.g. backtesting blackout), set plan and role access.
      </p>
    </div>
  </div>

  <div class="bg-blue-900/20 border border-blue-700/40 rounded-lg px-4 py-2 mb-5 text-xs text-blue-300">
    ℹ️ Changes take effect immediately. No server restart required.
    Backtesting is blocked during market hours by default (09:00–16:00 IST Mon–Fri).
  </div>

  @if (loading()) { <div class="text-center text-slate-400 py-12">Loading feature configs…</div> }

  @if (!loading()) {
    <!-- Group tabs -->
    <div class="flex flex-wrap gap-2 mb-5">
      <button (click)="activeGroup.set('')"
        class="text-sm px-3 py-1.5 rounded-lg transition-colors"
        [class]="activeGroup()==='' ? 'bg-blue-700/50 text-blue-200 border border-blue-600' : 'bg-slate-800 text-slate-400'">
        All ({{ features.length }})
      </button>
      @for (g of groups; track g) {
        <button (click)="activeGroup.set(g)"
          class="text-sm px-3 py-1.5 rounded-lg transition-colors"
          [class]="activeGroup()===g ? 'bg-blue-700/50 text-blue-200 border border-blue-600' : 'bg-slate-800 text-slate-400'">
          {{ groupIcon(g) }} {{ g | titlecase }} ({{ groupCount(g) }})
        </button>
      }
    </div>

    <!-- Feature list -->
    <div class="space-y-3">
      @for (f of filteredFeatures(); track f[1]) {
        <div class="card border transition-all"
          [class]="f[4]===1 ? 'border-slate-700' : 'border-red-800/50 bg-red-950/10'">
          <div class="flex items-start gap-4">
            <!-- Enable toggle -->
            <div class="flex-shrink-0 mt-1">
              <button (click)="quickToggle(f, $event)"
                class="relative inline-flex w-11 h-6 rounded-full transition-colors focus:outline-none"
                [class]="f[4]===1 ? 'bg-green-600' : 'bg-slate-600'"
                [title]="f[4]===1 ? 'Click to DISABLE' : 'Click to ENABLE'">
                <span class="inline-block w-5 h-5 bg-white rounded-full shadow transform transition-transform mt-0.5"
                  [class]="f[4]===1 ? 'translate-x-5' : 'translate-x-0.5'"></span>
              </button>
            </div>

            <!-- Info -->
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 flex-wrap">
                <span class="font-semibold text-white">{{ f[2] }}</span>
                <span class="badge text-xs" [ngClass]="f[4]===1 ? 'badge-green' : 'badge-red'">
                  {{ f[4]===1 ? 'Enabled' : 'Disabled' }}
                </span>
                @if (f[5]===1) {
                  <span class="badge badge-yellow text-xs">⏰ Schedule Restricted</span>
                }
                <span class="text-xs text-slate-500 font-mono">{{ f[1] }}</span>
              </div>
              <p class="text-xs text-slate-400 mt-1">{{ f[16] }}</p>

              <!-- Schedule info -->
              @if (f[5]===1) {
                <div class="mt-2 text-xs bg-yellow-900/20 border border-yellow-700/30 rounded px-3 py-2">
                  ⏰ Allowed window: <strong class="text-white">{{ f[6] }} – {{ f[7] }}</strong>
                  on <strong class="text-white">{{ f[8] }}</strong> ({{ f[9] || 'Asia/Kolkata' }})
                  <br/>
                  Blocked outside this window — returns HTTP {{ f[15] }}
                </div>
              }
              <div class="flex flex-wrap gap-3 mt-2 text-xs text-slate-500">
                <span>Min plan: <strong class="text-slate-300">{{ f[12] }}</strong></span>
                <span>Roles: <strong class="text-slate-300">{{ f[13] }}</strong></span>
                <span>HTTP: <strong class="text-slate-300">{{ f[15] }}</strong></span>
                @if (f[18]) { <span class="text-slate-600">Updated: {{ f[18] | slice:0:10 }}</span> }
              </div>
            </div>

            <!-- Edit button -->
            <button (click)="openEdit(f)"
              class="flex-shrink-0 text-xs px-3 py-1 bg-blue-800/50 hover:bg-blue-700/50 rounded text-blue-300 transition-colors">
              ✏️ Edit
            </button>
          </div>
        </div>
      }
    </div>
  }

  @if (toast()) {
    <div class="fixed bottom-6 right-6 bg-green-800 text-green-200 rounded-xl px-5 py-3 shadow-xl text-sm z-50 transition-all">
      ✅ {{ toast() }}
    </div>
  }

  <!-- Edit Modal -->
  @if (editingFeature) {
    <div class="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4">
      <div class="bg-[#112138] rounded-2xl border border-slate-700 w-full max-w-2xl max-h-[90vh] overflow-y-auto p-6">
        <div class="flex justify-between items-center mb-5">
          <h2 class="text-lg font-semibold text-white">✏️ Edit Feature: {{ editingFeature[2] }}</h2>
          <button (click)="closeEdit()" class="text-slate-400 hover:text-white text-xl">✕</button>
        </div>

        <!-- Master Toggle -->
        <div class="flex items-center justify-between bg-slate-800 rounded-xl p-4 mb-4">
          <div>
            <div class="font-medium text-white">Feature Enabled</div>
            <div class="text-xs text-slate-400">Master ON/OFF switch. Disabled = all users blocked.</div>
          </div>
          <label class="flex items-center gap-2 cursor-pointer">
            <input type="checkbox" [(ngModel)]="editForm.isEnabled" class="sr-only" />
            <div class="relative w-12 h-6 rounded-full transition-colors"
              [class]="editForm.isEnabled ? 'bg-green-600' : 'bg-slate-600'"
              (click)="editForm.isEnabled = !editForm.isEnabled">
              <span class="absolute inset-y-0.5 w-5 h-5 bg-white rounded-full shadow transition-all"
                [class]="editForm.isEnabled ? 'left-6' : 'left-0.5'"></span>
            </div>
            <span class="text-sm font-medium" [class]="editForm.isEnabled?'text-green-400':'text-red-400'">
              {{ editForm.isEnabled ? 'ENABLED' : 'DISABLED' }}
            </span>
          </label>
        </div>

        <!-- Schedule Restriction -->
        <div class="bg-slate-800 rounded-xl p-4 mb-4">
          <div class="flex items-center justify-between mb-3">
            <div>
              <div class="font-medium text-white">⏰ Schedule Restriction</div>
              <div class="text-xs text-slate-400">Block feature outside allowed time window (e.g. backtesting 09:00–16:00 blocked)</div>
            </div>
            <label class="cursor-pointer">
              <input type="checkbox" [(ngModel)]="editForm.scheduleRestricted" class="accent-blue-500" />
              <span class="ml-2 text-sm text-slate-300">Enable</span>
            </label>
          </div>

          @if (editForm.scheduleRestricted) {
            <div class="grid grid-cols-2 gap-4 mt-3">
              <div>
                <label class="label">Allowed Start Time (24h IST)</label>
                <input [(ngModel)]="editForm.allowedStartTime" type="time" class="input text-sm"
                  title="Feature is allowed FROM this time" />
                <p class="text-xs text-slate-500 mt-1">Feature available FROM this time</p>
              </div>
              <div>
                <label class="label">Allowed End Time (24h IST)</label>
                <input [(ngModel)]="editForm.allowedEndTime" type="time" class="input text-sm"
                  title="Feature is allowed UNTIL this time" />
                <p class="text-xs text-slate-500 mt-1">Feature available UNTIL this time</p>
              </div>
              <div class="col-span-2">
                <label class="label">Allowed Days</label>
                <div class="flex flex-wrap gap-2">
                  @for (day of allDays; track day) {
                    <label class="flex items-center gap-1.5 cursor-pointer bg-slate-700 px-3 py-1.5 rounded-lg hover:bg-slate-600">
                      <input type="checkbox"
                        [checked]="isDaySelected(day)"
                        (change)="toggleDay(day, $event)"
                        class="accent-blue-500" />
                      <span class="text-sm text-slate-300">{{ day }}</span>
                    </label>
                  }
                </div>
              </div>
              <div class="col-span-2 bg-blue-900/20 border border-blue-700/30 rounded p-3 text-xs text-blue-300">
                ℹ️ Example for Backtesting: Start=<strong>16:01</strong> End=<strong>08:59</strong> — means allowed 4:01 PM to 8:59 AM (blocked 9:00 AM–4:00 PM)
              </div>
            </div>
          }
        </div>

        <!-- Plan & Role -->
        <div class="grid grid-cols-2 gap-4 mb-4">
          <div>
            <label class="label">Minimum Plan Required</label>
            <select [(ngModel)]="editForm.minPlan" class="input text-sm">
              <option>Free Trial</option><option>Basic</option><option>Starter</option>
              <option>Premium</option><option>Pro</option><option>Elite</option>
              <option>Institutional</option><option>Enterprise</option>
            </select>
          </div>
          <div>
            <label class="label">Allowed Roles (comma-separated or ALL)</label>
            <input [(ngModel)]="editForm.allowedRoles" class="input text-sm" placeholder="ALL" />
          </div>
        </div>

        <!-- Blocked Message -->
        <div class="mb-4">
          <label class="label">Message When Blocked</label>
          <textarea [(ngModel)]="editForm.blockedMessage" class="input text-sm" rows="2"
            placeholder="Feature is not available at this time…"></textarea>
        </div>

        <!-- HTTP Status -->
        <div class="mb-5">
          <label class="label">HTTP Status Code When Blocked</label>
          <select [(ngModel)]="editForm.httpStatus" class="input text-sm w-48">
            <option [value]="423">423 — Schedule Lock (market hours)</option>
            <option [value]="403">403 — Forbidden (role/plan)</option>
            <option [value]="503">503 — Service Unavailable (maintenance)</option>
          </select>
        </div>

        @if (editError()) {
          <div class="bg-red-900/40 border border-red-700 text-red-300 rounded px-3 py-2 mb-3 text-sm">{{ editError() }}</div>
        }

        <div class="flex justify-end gap-3">
          <button (click)="closeEdit()" class="btn-secondary">Cancel</button>
          <button (click)="saveEdit()" [disabled]="saving()" class="btn-primary">
            @if (saving()) { Saving… } @else { Save Configuration }
          </button>
        </div>
      </div>
    </div>
  }
</div>
  `
})
export class AdminFeatureConfigComponent implements OnInit {
  loading       = signal(true);
  saving        = signal(false);
  editError     = signal('');
  toast         = signal('');
  activeGroup   = signal('');
  features:     any[] = [];
  editingFeature: any = null;
  editForm: any = {};
  allDays       = ALL_DAYS;

  get groups(): string[] {
    const g = new Set(this.features.map((f: any[]) => f[3] || 'trading'));
    return [...g];
  }

  filteredFeatures() {
    if (!this.activeGroup()) return this.features;
    return this.features.filter((f: any[]) => (f[3] || 'trading') === this.activeGroup());
  }

  groupCount(g: string) { return this.features.filter((f: any[]) => (f[3] || 'trading') === g).length; }
  groupIcon(g: string)  { return GROUP_ICONS[g] || '⚙️'; }

  constructor(private api: ApiService, public auth: AuthService) {}

  ngOnInit() { this.load(); }

  load() {
    this.api.get<any>('/admin/features').subscribe({
      next: r => { this.features = r.data || []; this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }

  quickToggle(f: any[], event: Event) {
    event.stopPropagation();
    const newState = f[4] === 1 ? false : true;
    const confirmed = confirm(`${newState ? 'ENABLE' : 'DISABLE'} "${f[2]}"?\n\n${newState ? 'Users will be able to access this feature.' : 'All users will be blocked from this feature immediately.'}`);
    if (!confirmed) return;

    this.api.patch<any>(`/admin/features/${f[1]}/toggle`, { enabled: newState }).subscribe({
      next: () => {
        f[4] = newState ? 1 : 0;
        this.showToast(`"${f[2]}" ${newState ? 'enabled' : 'disabled'}.`);
      },
      error: e => alert(e.error?.message || 'Failed to toggle feature.')
    });
  }

  openEdit(f: any[]) {
    this.editingFeature = f;
    this.editForm = {
      isEnabled:          f[4] === 1,
      scheduleRestricted: f[5] === 1,
      allowedStartTime:   f[6] || '16:01',
      allowedEndTime:     f[7] || '08:59',
      allowedDays:        f[8] || 'Mon,Tue,Wed,Thu,Fri',
      timezone:           f[9] || 'Asia/Kolkata',
      blockedMessage:     f[10] || '',
      minPlan:            f[12] || 'Free Trial',
      allowedRoles:       f[13] || 'ALL',
      httpStatus:         f[15] || 423
    };
    this.editError.set('');
  }

  closeEdit() { this.editingFeature = null; this.editForm = {}; }

  isDaySelected(day: string): boolean {
    return (this.editForm.allowedDays || '').includes(day);
  }

  toggleDay(day: string, event: Event) {
    const checked = (event.target as HTMLInputElement).checked;
    let days: string[] = (this.editForm.allowedDays || '').split(',').filter((d: string) => d.trim());
    if (checked && !days.includes(day)) days.push(day);
    else if (!checked) days = days.filter((d: string) => d !== day);
    // Preserve order: Mon,Tue,Wed,Thu,Fri,Sat,Sun
    this.editForm.allowedDays = ALL_DAYS.filter(d => days.includes(d)).join(',');
  }

  saveEdit() {
    this.saving.set(true); this.editError.set('');
    const payload = {
      isEnabled:          this.editForm.isEnabled ? 1 : 0,
      scheduleRestricted: this.editForm.scheduleRestricted ? 1 : 0,
      allowedStartTime:   this.editForm.allowedStartTime,
      allowedEndTime:     this.editForm.allowedEndTime,
      allowedDays:        this.editForm.allowedDays,
      timezone:           this.editForm.timezone,
      blockedMessage:     this.editForm.blockedMessage,
      minPlan:            this.editForm.minPlan,
      allowedRoles:       this.editForm.allowedRoles,
      httpStatus:         Number(this.editForm.httpStatus)
    };

    this.api.put<any>(`/admin/features/${this.editingFeature[1]}`, payload).subscribe({
      next: () => {
        this.saving.set(false);
        this.closeEdit();
        this.load();
        this.showToast(`Feature "${this.editingFeature[2]}" configuration saved.`);
      },
      error: e => { this.editError.set(e.error?.message || 'Save failed.'); this.saving.set(false); }
    });
  }

  private showToast(msg: string) {
    this.toast.set(msg);
    setTimeout(() => this.toast.set(''), 3500);
  }
}
