import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';

interface Setting {
  id: number;
  category: string;
  setting_key: string;
  setting_name: string;
  setting_value: string;
  data_type: string;
  default_value: string;
  min_value: string;
  max_value: string;
  description: string;
  is_editable: number;
  display_order: number;
  config_version: number;
  updated_at: string;
  editing?: boolean;
  editValue?: string;
}

interface AuditEntry {
  setting_key: string;
  category: string;
  old_value: string;
  new_value: string;
  changed_by: number;
  changed_by_name: string;
  ip_address: string;
  browser: string;
  reason: string;
  config_version: number;
  created_at: string;
}

@Component({
  selector: 'app-system-settings',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
<div class="p-4 md:p-6 space-y-4">
  <div class="flex items-center justify-between flex-wrap gap-2">
    <div>
      <h1 class="text-xl font-bold text-white">System Settings</h1>
      <p class="text-xs text-slate-400 mt-1">Centralized Configuration Management Engine &middot; v{{ configVersion }}</p>
    </div>
    <div class="flex gap-2 flex-wrap">
      <button (click)="refreshCache()" class="btn-sm bg-blue-600 hover:bg-blue-500">Refresh Cache</button>
      <button (click)="exportSettings()" class="btn-sm bg-green-600 hover:bg-green-500">Export</button>
      <label class="btn-sm bg-yellow-600 hover:bg-yellow-500 cursor-pointer">
        Import <input type="file" accept=".json" (change)="importFile($event)" class="hidden" />
      </label>
      @if (auth.isSuperAdmin()) {
        <button (click)="confirmResetAll()" class="btn-sm bg-red-600 hover:bg-red-500">Reset All</button>
      }
    </div>
  </div>

  @if (statusMsg) {
    <div class="px-3 py-2 rounded text-sm" [class]="statusType === 'success' ? 'bg-green-900/50 text-green-300 border border-green-700' : 'bg-red-900/50 text-red-300 border border-red-700'">
      {{ statusMsg }}
    </div>
  }

  <!-- Sub-menu tabs -->
  <div class="flex flex-wrap gap-1 border-b border-slate-700 pb-2">
    <button (click)="activeTab = 'settings'" [class.active-tab]="activeTab === 'settings'" class="tab-btn">Settings</button>
    <button (click)="activeTab = 'features'" [class.active-tab]="activeTab === 'features'" class="tab-btn">Feature Toggles</button>
    <button (click)="activeTab = 'audit'; loadAuditLog()" [class.active-tab]="activeTab === 'audit'" class="tab-btn">Audit Logs</button>
  </div>

  <!-- SETTINGS TAB -->
  @if (activeTab === 'settings') {
    <div class="flex flex-wrap gap-1 mb-3">
      <button (click)="selectedCategory = ''" [class.active-cat]="selectedCategory === ''" class="cat-btn">All</button>
      @for (cat of categories; track cat) {
        <button (click)="selectedCategory = cat" [class.active-cat]="selectedCategory === cat" class="cat-btn">
          {{ formatCategory(cat) }}
        </button>
      }
    </div>

    <div class="space-y-3">
      @for (cat of displayCategories; track cat) {
        <div class="panel">
          <div class="panel-header flex items-center justify-between">
            <span>{{ formatCategory(cat) }}</span>
            <div class="flex gap-2">
              <span class="text-xs text-slate-400">{{ getSettingsForCategory(cat).length }} settings</span>
              <button (click)="resetCategory(cat)" class="text-xs text-yellow-400 hover:text-yellow-300">Reset</button>
            </div>
          </div>
          <div class="overflow-x-auto">
            <table class="w-full text-xs">
              <thead>
                <tr class="text-slate-400 border-b border-slate-700">
                  <th class="text-left py-2 px-2 w-1/4">Setting</th>
                  <th class="text-left py-2 px-2 w-1/5">Value</th>
                  <th class="text-left py-2 px-2 w-1/12">Type</th>
                  <th class="text-left py-2 px-2 w-1/6">Range</th>
                  <th class="text-left py-2 px-2">Description</th>
                  <th class="text-center py-2 px-2 w-20">Action</th>
                </tr>
              </thead>
              <tbody>
                @for (s of getSettingsForCategory(cat); track s.setting_key) {
                  <tr class="border-b border-slate-800 hover:bg-slate-800/30">
                    <td class="py-2 px-2">
                      <div class="text-slate-200 font-medium">{{ s.setting_name }}</div>
                      <div class="text-slate-500 text-[10px] font-mono">{{ s.setting_key }}</div>
                    </td>
                    <td class="py-2 px-2">
                      @if (s.editing) {
                        @if (s.data_type === 'BOOLEAN') {
                          <select [(ngModel)]="s.editValue" class="input-sm w-full">
                            <option value="true">true</option>
                            <option value="false">false</option>
                          </select>
                        } @else {
                          <input [(ngModel)]="s.editValue" class="input-sm w-full"
                                 [type]="s.data_type === 'INTEGER' || s.data_type === 'DOUBLE' ? 'number' : 'text'"
                                 [step]="s.data_type === 'DOUBLE' ? '0.1' : '1'" />
                        }
                      } @else {
                        <span class="font-mono" [class]="s.setting_value !== s.default_value ? 'text-yellow-300' : 'text-slate-300'">
                          {{ s.setting_value }}
                        </span>
                        @if (s.setting_value !== s.default_value) {
                          <span class="text-[9px] text-slate-500 ml-1">(default: {{ s.default_value }})</span>
                        }
                      }
                    </td>
                    <td class="py-2 px-2 text-slate-500">{{ s.data_type }}</td>
                    <td class="py-2 px-2 text-slate-500">
                      @if (s.min_value || s.max_value) {
                        {{ s.min_value ?? '' }} – {{ s.max_value ?? '' }}
                      }
                    </td>
                    <td class="py-2 px-2 text-slate-500">{{ s.description }}</td>
                    <td class="py-2 px-2 text-center">
                      @if (s.is_editable) {
                        @if (s.editing) {
                          <div class="flex gap-1 justify-center">
                            <button (click)="saveSetting(s)" class="text-green-400 hover:text-green-300 text-xs">Save</button>
                            <button (click)="s.editing = false" class="text-slate-400 hover:text-slate-300 text-xs">Cancel</button>
                          </div>
                        } @else {
                          <button (click)="startEdit(s)" class="text-blue-400 hover:text-blue-300 text-xs">Edit</button>
                        }
                      } @else {
                        <span class="text-slate-600 text-[10px]">Locked</span>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }
    </div>
  }

  <!-- FEATURE TOGGLES TAB -->
  @if (activeTab === 'features') {
    <div class="panel">
      <div class="panel-header">Feature Toggles</div>
      <div class="overflow-x-auto">
        <table class="w-full text-xs">
          <thead>
            <tr class="text-slate-400 border-b border-slate-700">
              <th class="text-left py-2 px-3">Feature</th>
              <th class="text-center py-2 px-3 w-28">Status</th>
              <th class="text-center py-2 px-3 w-28">Action</th>
            </tr>
          </thead>
          <tbody>
            @for (ft of featureToggles; track ft.key) {
              <tr class="border-b border-slate-800">
                <td class="py-2 px-3 text-slate-200">{{ ft.label }}</td>
                <td class="py-2 px-3 text-center">
                  <span class="px-2 py-0.5 rounded text-[10px] font-bold"
                        [class]="ft.enabled ? 'bg-green-900/60 text-green-300' : 'bg-red-900/60 text-red-300'">
                    {{ ft.enabled ? 'ENABLED' : 'DISABLED' }}
                  </span>
                </td>
                <td class="py-2 px-3 text-center">
                  <button (click)="toggleFeature(ft)" class="text-xs"
                          [class]="ft.enabled ? 'text-red-400 hover:text-red-300' : 'text-green-400 hover:text-green-300'">
                    {{ ft.enabled ? 'Disable' : 'Enable' }}
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </div>
  }

  <!-- AUDIT LOG TAB -->
  @if (activeTab === 'audit') {
    <div class="panel">
      <div class="panel-header flex items-center justify-between">
        <span>Configuration Audit Log</span>
        <div class="flex gap-2 items-center">
          <input [(ngModel)]="auditFilter" placeholder="Filter by key..." class="input-sm w-40" />
          <button (click)="loadAuditLog()" class="text-xs text-blue-400 hover:text-blue-300">Refresh</button>
        </div>
      </div>
      <div class="overflow-x-auto max-h-[500px] overflow-y-auto">
        <table class="w-full text-xs">
          <thead class="sticky top-0 bg-[#10233d]">
            <tr class="text-slate-400 border-b border-slate-700">
              <th class="text-left py-2 px-2">Date</th>
              <th class="text-left py-2 px-2">Setting</th>
              <th class="text-left py-2 px-2">Old Value</th>
              <th class="text-left py-2 px-2">New Value</th>
              <th class="text-left py-2 px-2">Changed By</th>
              <th class="text-left py-2 px-2">Reason</th>
              <th class="text-left py-2 px-2">IP</th>
              <th class="text-center py-2 px-2">Version</th>
            </tr>
          </thead>
          <tbody>
            @for (a of filteredAuditLog; track $index) {
              <tr class="border-b border-slate-800 hover:bg-slate-800/30">
                <td class="py-1.5 px-2 text-slate-400 whitespace-nowrap">{{ a.created_at }}</td>
                <td class="py-1.5 px-2 text-slate-200 font-mono">{{ a.setting_key }}</td>
                <td class="py-1.5 px-2 text-red-400 font-mono">{{ a.old_value }}</td>
                <td class="py-1.5 px-2 text-green-400 font-mono">{{ a.new_value }}</td>
                <td class="py-1.5 px-2 text-slate-300">{{ a.changed_by_name }}</td>
                <td class="py-1.5 px-2 text-slate-500">{{ a.reason }}</td>
                <td class="py-1.5 px-2 text-slate-500 font-mono">{{ a.ip_address }}</td>
                <td class="py-1.5 px-2 text-center text-slate-400">v{{ a.config_version }}</td>
              </tr>
            }
            @if (filteredAuditLog.length === 0) {
              <tr><td colspan="8" class="py-4 text-center text-slate-500">No audit log entries found.</td></tr>
            }
          </tbody>
        </table>
      </div>
    </div>
  }
</div>
  `,
  styles: [`
    :host { display: block; }
    .btn-sm { @apply px-3 py-1.5 rounded text-xs font-medium text-white transition-colors; }
    .tab-btn { @apply px-3 py-1.5 rounded-t text-xs text-slate-400 hover:text-white transition-colors; }
    .tab-btn.active-tab { @apply text-white bg-[#173456] border-b-2 border-blue-400; }
    .cat-btn { @apply px-2 py-1 rounded text-[10px] text-slate-400 hover:text-white bg-[#10233d] border border-slate-700 transition-colors; }
    .cat-btn.active-cat { @apply text-white bg-blue-600 border-blue-500; }
    .panel { @apply bg-[#10233d] border border-[#203650] rounded-lg overflow-hidden; }
    .panel-header { @apply px-3 py-2 bg-[#173456] text-sm font-semibold text-slate-200 border-b border-[#203650]; }
    .input-sm { @apply px-2 py-1 rounded bg-[#0B1E3D] border border-slate-600 text-white text-xs focus:border-blue-400 focus:outline-none; }
  `]
})
export class SystemSettingsComponent implements OnInit {
  activeTab = 'settings';
  settings: Setting[] = [];
  categories: string[] = [];
  selectedCategory = '';
  configVersion = 0;
  statusMsg = '';
  statusType = 'success';
  featureToggles: { key: string; label: string; enabled: boolean }[] = [];
  auditLog: AuditEntry[] = [];
  auditFilter = '';

  constructor(private api: ApiService, public auth: AuthService) {}

  ngOnInit(): void {
    this.loadSettings();
  }

  loadSettings(): void {
    this.api.get<any>('/admin/config/settings').subscribe({
      next: (res) => {
        if (res?.data) {
          this.settings = res.data.settings || [];
          this.categories = res.data.categories || [];
          this.configVersion = res.data.config_version || 0;
          this.buildFeatureToggles();
        }
      },
      error: () => this.showStatus('Failed to load settings', 'error')
    });
  }

  get displayCategories(): string[] {
    if (this.selectedCategory) return [this.selectedCategory];
    return this.categories;
  }

  getSettingsForCategory(category: string): Setting[] {
    return this.settings.filter(s => s.category === category);
  }

  formatCategory(cat: string): string {
    return cat.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
  }

  startEdit(s: Setting): void {
    s.editing = true;
    s.editValue = s.setting_value;
  }

  saveSetting(s: Setting): void {
    if (s.editValue === s.setting_value) { s.editing = false; return; }
    const reason = prompt('Reason for change (optional):') || 'Admin update';
    this.api.put<any>(`/admin/config/settings/${s.setting_key}`, { value: s.editValue, reason }).subscribe({
      next: (res) => {
        if (res?.success) {
          s.setting_value = s.editValue!;
          s.editing = false;
          this.configVersion++;
          this.showStatus(`${s.setting_name} updated successfully.`, 'success');
        } else {
          this.showStatus(res?.message || 'Update failed', 'error');
        }
      },
      error: (err) => this.showStatus(err?.error?.message || 'Update failed', 'error')
    });
  }

  buildFeatureToggles(): void {
    this.featureToggles = this.settings
      .filter(s => s.category === 'FEATURE_TOGGLE')
      .map(s => ({
        key: s.setting_key,
        label: s.setting_name,
        enabled: s.setting_value === 'true' || s.setting_value === '1'
      }));
  }

  toggleFeature(ft: { key: string; label: string; enabled: boolean }): void {
    const newVal = ft.enabled ? 'false' : 'true';
    this.api.put<any>(`/admin/config/settings/${ft.key}`, { value: newVal, reason: 'Feature toggle' }).subscribe({
      next: (res) => {
        if (res?.success) {
          ft.enabled = !ft.enabled;
          const s = this.settings.find(x => x.setting_key === ft.key);
          if (s) s.setting_value = newVal;
          this.showStatus(`${ft.label} ${ft.enabled ? 'enabled' : 'disabled'}`, 'success');
        }
      },
      error: () => this.showStatus('Toggle failed', 'error')
    });
  }

  refreshCache(): void {
    this.api.post<any>('/admin/config/cache/refresh', {}).subscribe({
      next: (res) => {
        this.showStatus('Cache refreshed — ' + (res?.data?.cache_size || 0) + ' settings loaded', 'success');
        this.loadSettings();
      },
      error: () => this.showStatus('Cache refresh failed', 'error')
    });
  }

  exportSettings(): void {
    this.api.get<any>('/admin/config/export').subscribe({
      next: (res) => {
        if (res?.data) {
          const blob = new Blob([JSON.stringify(res.data, null, 2)], { type: 'application/json' });
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `traderakshak_config_${new Date().toISOString().slice(0, 10)}.json`;
          a.click();
          URL.revokeObjectURL(url);
          this.showStatus('Settings exported', 'success');
        }
      },
      error: () => this.showStatus('Export failed', 'error')
    });
  }

  importFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files?.length) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const settings = JSON.parse(reader.result as string);
        if (!confirm(`Import ${Array.isArray(settings) ? settings.length : 0} settings? This will overwrite current values.`)) return;
        this.api.post<any>('/admin/config/import', { settings }).subscribe({
          next: (res) => {
            this.showStatus('Imported ' + (res?.data?.imported_count || 0) + ' settings', 'success');
            this.loadSettings();
          },
          error: () => this.showStatus('Import failed', 'error')
        });
      } catch (e) {
        this.showStatus('Invalid JSON file', 'error');
      }
    };
    reader.readAsText(input.files[0]);
    input.value = '';
  }

  resetCategory(category: string): void {
    if (!confirm(`Reset all "${this.formatCategory(category)}" settings to defaults?`)) return;
    this.api.post<any>(`/admin/config/settings/reset/category/${category}`, {}).subscribe({
      next: (res) => {
        this.showStatus('Reset ' + (res?.data?.reset_count || 0) + ' settings to defaults', 'success');
        this.loadSettings();
      },
      error: () => this.showStatus('Reset failed', 'error')
    });
  }

  confirmResetAll(): void {
    if (!confirm('⚠ RESET ALL SETTINGS to defaults? This action cannot be undone.')) return;
    if (!confirm('Are you absolutely sure? Type context will be lost.')) return;
    this.api.post<any>('/admin/config/settings/reset/all', {}).subscribe({
      next: (res) => {
        this.showStatus('Reset ' + (res?.data?.reset_count || 0) + ' settings to defaults', 'success');
        this.loadSettings();
      },
      error: () => this.showStatus('Reset all failed', 'error')
    });
  }

  loadAuditLog(): void {
    this.api.get<any>('/admin/config/audit-log', { limit: 200 }).subscribe({
      next: (res) => { this.auditLog = res?.data || []; },
      error: () => { this.auditLog = []; }
    });
  }

  get filteredAuditLog(): AuditEntry[] {
    if (!this.auditFilter) return this.auditLog;
    const f = this.auditFilter.toLowerCase();
    return this.auditLog.filter(a =>
      a.setting_key?.toLowerCase().includes(f) ||
      a.category?.toLowerCase().includes(f) ||
      a.changed_by_name?.toLowerCase().includes(f)
    );
  }

  showStatus(msg: string, type: 'success' | 'error'): void {
    this.statusMsg = msg;
    this.statusType = type;
    setTimeout(() => this.statusMsg = '', 5000);
  }
}
