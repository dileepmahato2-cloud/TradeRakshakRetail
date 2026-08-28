import { Component, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { UserHierarchyService, USER_TYPE_META } from '../../core/services/user-hierarchy.service';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-user-hierarchy',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
<div class="p-6">
  <!-- Header -->
  <div class="flex items-center justify-between mb-6">
    <div>
      <h1 class="section-title">👥 User Hierarchy Management</h1>
      <p class="text-slate-400 text-sm">
        Logged in as: <span class="text-gold font-semibold">{{ myType() }}</span>
        (Level {{ myLevel() }}) — Managing {{ manageable().length }} users
      </p>
    </div>
    <div class="flex gap-2">
      <button (click)="openCreate()" class="btn-primary text-sm">+ Create User</button>
      <button (click)="activeTab.set('tree')" [class]="tab('tree')" class="text-sm px-3 py-2 rounded-lg">🌳 Tree</button>
      <button (click)="activeTab.set('list')" [class]="tab('list')" class="text-sm px-3 py-2 rounded-lg">📋 List</button>
      <button (click)="activeTab.set('perms')" [class]="tab('perms')" class="text-sm px-3 py-2 rounded-lg">🔑 Perms</button>
    </div>
  </div>

  <!-- SEBI Disclaimer -->
  <div class="bg-amber-900/20 border border-amber-700/40 rounded-lg px-4 py-2 mb-5 text-xs text-amber-300">
    ⚠ TradeRakshak™ is NOT a SEBI-registered Investment Advisor. User management is for platform operations only.
  </div>

  @if (loading()) {
    <div class="text-slate-400 text-center py-16">Loading hierarchy data…</div>
  }

  <!-- Summary Cards -->
  @if (!loading() && summary()) {
    <div class="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-6 gap-3 mb-6">
      @for (entry of summaryEntries(); track entry.type) {
        <div class="card p-3 cursor-pointer hover:border-blue-600 transition-colors"
             [class.border-blue-500]="filterType() === entry.type"
             (click)="filterType.set(filterType() === entry.type ? '' : entry.type)">
          <div class="text-lg">{{ getMeta(entry.type).icon }}</div>
          <div class="text-xs text-slate-400 mt-1 truncate">{{ getMeta(entry.type).label }}</div>
          <div class="text-xl font-bold" [ngClass]="getMeta(entry.type).color">{{ entry.count }}</div>
        </div>
      }
    </div>
  }

  <!-- ── LIST TAB ── -->
  @if (activeTab() === 'list' && !loading()) {
    <!-- Filters -->
    <div class="flex flex-wrap gap-3 mb-4">
      <input type="text" [(ngModel)]="search" placeholder="Search name, email, TR ID…"
        class="input w-64 text-sm" />
      <select [(ngModel)]="filterStatus" class="input w-36 text-sm">
        <option value="">All Status</option>
        <option value="active">Active</option>
        <option value="suspended">Suspended</option>
        <option value="blocked">Blocked</option>
        <option value="pending">Pending</option>
      </select>
      @if (creatableTypes().length) {
        <select [(ngModel)]="filterType" class="input w-52 text-sm">
          <option value="">All Types</option>
          @for (t of creatableTypes(); track t) {
            <option [value]="t">{{ getMeta(t).icon }} {{ getMeta(t).label }}</option>
          }
        </select>
      }
      @if (selectedIds().size > 0) {
        <div class="flex gap-2 ml-auto">
          <button (click)="bulkSuspend()" class="btn-danger text-sm">
            Suspend {{ selectedIds().size }}
          </button>
          <button (click)="bulkPlanModal.set(true)" class="btn-secondary text-sm">
            Change Plan
          </button>
        </div>
      }
    </div>

    <!-- Users Table -->
    <div class="card overflow-x-auto">
      <table class="table-base w-full">
        <thead>
          <tr>
            <th class="w-8"><input type="checkbox" (change)="toggleAll($event)" class="accent-blue-500" /></th>
            <th>TR User ID</th>
            <th>Name</th>
            <th>Email</th>
            <th>Type</th>
            <th>Plan</th>
            <th>Status</th>
            <th>Created</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          @for (u of filteredUsers(); track u.id) {
            <tr>
              <td><input type="checkbox" [checked]="selectedIds().has(u.id)"
                (change)="toggleSelect(u.id, $event)" class="accent-blue-500" /></td>
              <td class="font-mono text-xs text-blue-300">{{ u.trUserId }}</td>
              <td class="font-medium text-white">{{ u.name }}</td>
              <td class="text-slate-400 text-xs">{{ u.email }}</td>
              <td>
                <span class="badge text-xs" [ngClass]="getMeta(u.userType).color">
                  {{ getMeta(u.userType).icon }} {{ getMeta(u.userType).label }}
                </span>
              </td>
              <td class="text-xs">
                <span class="badge-blue">{{ u.plan }}</span>
                @if (u.planExpiry) {
                  <div class="text-slate-500 text-xs mt-0.5">Until {{ u.planExpiry }}</div>
                }
              </td>
              <td>
                <span class="badge text-xs"
                  [ngClass]="u.status==='active'?'badge-green':u.status==='blocked'?'badge-red':'badge-yellow'">
                  {{ u.status }}
                </span>
              </td>
              <td class="text-xs text-slate-500">{{ u.createdAt | slice:0:10 }}</td>
              <td>
                <div class="flex gap-1">
                  <button (click)="openEdit(u)" title="Edit"
                    class="text-xs px-2 py-1 bg-blue-800/50 hover:bg-blue-700/50 rounded text-blue-300">✏️</button>
                  @if (u.status === 'active') {
                    <button (click)="suspendUser(u)" title="Suspend"
                      class="text-xs px-2 py-1 bg-yellow-800/50 hover:bg-yellow-700/50 rounded text-yellow-300">⏸</button>
                  } @else if (u.status !== 'blocked') {
                    <button (click)="activateUser(u)" title="Activate"
                      class="text-xs px-2 py-1 bg-green-800/50 hover:bg-green-700/50 rounded text-green-300">▶</button>
                  }
                  <button (click)="selectedUser.set(u); showResetPw.set(true)" title="Reset PW"
                    class="text-xs px-2 py-1 bg-slate-700 hover:bg-slate-600 rounded text-slate-300">🔑</button>
                  @if (u.status !== 'blocked') {
                    <button (click)="blockUser(u)" title="Block"
                      class="text-xs px-2 py-1 bg-red-800/50 hover:bg-red-700/50 rounded text-red-300">🚫</button>
                  }
                  @if (u.failedLoginCount > 0) {
                    <button (click)="unlockUser(u)" title="Unlock"
                      class="text-xs px-2 py-1 bg-purple-800/50 hover:bg-purple-700/50 rounded text-purple-300">🔓</button>
                  }
                </div>
              </td>
            </tr>
          }
          @if (!filteredUsers().length) {
            <tr><td colspan="9" class="text-center text-slate-500 py-8">No users found.</td></tr>
          }
        </tbody>
      </table>
    </div>
  }

  <!-- ── TREE TAB ── -->
  @if (activeTab() === 'tree' && !loading()) {
    <div class="card">
      <div class="card-header">🌳 Hierarchy Tree</div>
      <div class="overflow-x-auto">
        <div class="font-mono text-sm text-slate-300 whitespace-pre leading-relaxed p-4">
{{ treeText() }}
        </div>
      </div>
    </div>
  }

  <!-- ── PERMISSIONS TAB ── -->
  @if (activeTab() === 'perms' && !loading()) {
    <div class="card">
      <div class="card-header">🔑 My Permission Matrix ({{ myType() }})</div>
      @if (permissions()) {
        <div class="overflow-x-auto">
          <table class="table-base">
            <thead><tr>
              <th>Module</th>
              <th>View</th><th>Create</th><th>Edit</th>
              <th>Delete</th><th>Approve</th><th>Export</th>
            </tr></thead>
            <tbody>
              @for (entry of permEntries(); track entry.module) {
                <tr>
                  <td class="font-medium text-white capitalize">{{ entry.module }}</td>
                  @for (action of ['view','create','edit','delete','approve','export']; track action) {
                    <td class="text-center">
                      @if (entry.perms[action]) {
                        <span class="text-green-400">✓</span>
                      } @else {
                        <span class="text-slate-700">—</span>
                      }
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  }

  <!-- ══ Create / Edit Modal ══ -->
  @if (showModal()) {
    <div class="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4">
      <div class="bg-[#112138] rounded-2xl border border-slate-700 w-full max-w-2xl max-h-[90vh] overflow-y-auto p-6">
        <div class="flex justify-between items-center mb-5">
          <h2 class="text-lg font-semibold text-white">
            {{ editingUser() ? '✏️ Edit User' : '+ Create User' }}
          </h2>
          <button (click)="closeModal()" class="text-slate-400 hover:text-white text-xl">✕</button>
        </div>

        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="label">Full Name *</label>
            <input [(ngModel)]="form.name" class="input text-sm" placeholder="John Doe" />
          </div>
          <div>
            <label class="label">Email *</label>
            <input [(ngModel)]="form.email" type="email" class="input text-sm" [disabled]="!!editingUser" />
          </div>
          <div>
            <label class="label">Mobile</label>
            <input [(ngModel)]="form.mobile" class="input text-sm" placeholder="9876543210" />
          </div>
          <div>
            <label class="label">PAN</label>
            <input [(ngModel)]="form.pan" class="input text-sm uppercase" maxlength="10" placeholder="ABCDE1234F" />
          </div>
          <div>
            <label class="label">User Type *</label>
            <select [(ngModel)]="form.userType" class="input text-sm">
              @for (t of creatableTypes(); track t) {
                <option [value]="t">{{ getMeta(t).icon }} {{ getMeta(t).label }}</option>
              }
            </select>
          </div>
          <div>
            <label class="label">Plan</label>
            <select [(ngModel)]="form.plan" class="input text-sm">
              <option>Free Trial</option>
              <option>Basic</option>
              <option>Starter</option>
              <option>Premium</option>
              <option>Pro</option>
              <option>Elite</option>
              <option>Institutional</option>
              <option>Enterprise</option>
            </select>
          </div>
          <div>
            <label class="label">Status</label>
            <select [(ngModel)]="form.status" class="input text-sm">
              <option value="active">Active</option>
              <option value="pending">Pending Approval</option>
              <option value="suspended">Suspended</option>
            </select>
          </div>
          <div>
            <label class="label">Plan Expiry</label>
            <input [(ngModel)]="form.planExpiry" type="date" class="input text-sm" />
          </div>
          @if (!editingUser) {
            <div>
              <label class="label">Password</label>
              <input [(ngModel)]="form.password" type="password" class="input text-sm" placeholder="Min 8 chars" />
            </div>
            <div>
              <label class="label">Referred By Code</label>
              <input [(ngModel)]="form.referredByCode" class="input text-sm" placeholder="TRXXXXXX" />
            </div>
          }
          <div class="col-span-2">
            <label class="label">Notes</label>
            <textarea [(ngModel)]="form.notes" class="input text-sm" rows="2"></textarea>
          </div>
        </div>

        @if (formError()) {
          <div class="bg-red-900/40 border border-red-700 text-red-300 rounded px-3 py-2 mt-3 text-sm">
            {{ formError() }}
          </div>
        }

        <div class="flex justify-end gap-3 mt-5">
          <button (click)="closeModal()" class="btn-secondary">Cancel</button>
          <button (click)="submitForm()" [disabled]="formLoading()" class="btn-primary">
            @if (formLoading()) { Saving… } @else { {{ editingUser() ? 'Save Changes' : 'Create User' }} }
          </button>
        </div>
      </div>
    </div>
  }

  <!-- Reset Password Modal -->
  @if (showResetPw() && selectedUser()) {
    <div class="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4">
      <div class="bg-[#112138] rounded-xl border border-slate-700 w-full max-w-md p-6">
        <h2 class="text-lg font-semibold text-white mb-4">🔑 Reset Password — {{ selectedUser()?.name }}</h2>
        <label class="label">New Password (leave blank to auto-generate)</label>
        <input [(ngModel)]="newPassword" type="password" class="input text-sm mb-4" placeholder="Auto-generate if blank" />
        @if (resetResult()) {
          <div class="bg-green-900/40 border border-green-700 text-green-300 rounded px-3 py-2 mb-3 text-sm">
            ✅ New password: <strong class="font-mono">{{ resetResult() }}</strong> — share securely!
          </div>
        }
        <div class="flex justify-end gap-3">
          <button (click)="showResetPw.set(false); resetResult.set('')" class="btn-secondary">Close</button>
          <button (click)="doResetPw()" class="btn-primary">Reset Password</button>
        </div>
      </div>
    </div>
  }

  <!-- Bulk Plan Modal -->
  @if (bulkPlanModal()) {
    <div class="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4">
      <div class="bg-[#112138] rounded-xl border border-slate-700 w-full max-w-sm p-6">
        <h2 class="text-lg font-semibold text-white mb-4">Change Plan for {{ selectedIds().size }} users</h2>
        <select [(ngModel)]="bulkPlan" class="input text-sm mb-4">
          <option>Free Trial</option><option>Basic</option><option>Starter</option>
          <option>Premium</option><option>Pro</option><option>Elite</option>
        </select>
        <div class="flex justify-end gap-3">
          <button (click)="bulkPlanModal.set(false)" class="btn-secondary">Cancel</button>
          <button (click)="doBulkPlan()" class="btn-primary">Apply</button>
        </div>
      </div>
    </div>
  }
</div>
  `
})
export class UserHierarchyComponent implements OnInit {
  loading       = signal(true);
  activeTab     = signal<'list'|'tree'|'perms'>('list');
  manageable    = signal<any[]>([]);
  summary       = signal<Record<string, number> | null>(null);
  permissions   = signal<Record<string, any> | null>(null);
  creatableTypes = signal<string[]>([]);
  myType        = signal('');
  myLevel       = signal(0);
  treeData      = signal<any>(null);

  filterType    = signal('');
  filterStatus  = '';
  search        = '';
  selectedIds   = signal(new Set<number>());

  showModal     = signal(false);
  editingUser   = signal<any>(null);
  form: any     = {};
  formLoading   = signal(false);
  formError     = signal('');

  showResetPw   = signal(false);
  selectedUser  = signal<any>(null);
  newPassword   = '';
  resetResult   = signal('');

  bulkPlanModal = signal(false);
  bulkPlan      = 'Pro';

  summaryEntries = computed(() => {
    const s = this.summary();
    if (!s) return [];
    return Object.entries(s).map(([type, count]) => ({ type, count }))
      .filter(e => Number(e.count) > 0);
  });

  filteredUsers = computed(() => {
    let users = this.manageable();
    if (this.filterType()) users = users.filter(u => u.userType === this.filterType());
    if (this.filterStatus) users = users.filter(u => u.status === this.filterStatus);
    if (this.search) {
      const q = this.search.toLowerCase();
      users = users.filter(u =>
        (u.name||'').toLowerCase().includes(q) ||
        (u.email||'').toLowerCase().includes(q) ||
        (u.trUserId||'').toLowerCase().includes(q));
    }
    return users;
  });

  permEntries = computed(() => {
    const p = this.permissions();
    if (!p) return [];
    return Object.entries(p).map(([module, perms]) => ({ module, perms: perms as any }));
  });

  constructor(private svc: UserHierarchyService, public auth: AuthService) {}

  ngOnInit() {
    this.loadAll();
  }

  loadAll() {
    this.loading.set(true);
    // Types & creatable
    this.svc.getTypes().subscribe({
      next: res => {
        if (res.data) {
          this.creatableTypes.set(res.data.creatable_by_me || []);
          this.myType.set(res.data.my_type || '');
          this.myLevel.set(res.data.my_level || 0);
        }
      }
    });
    // User list
    this.svc.listUsers().subscribe({
      next: res => { this.manageable.set(res.data || []); this.loading.set(false); },
      error: () => this.loading.set(false)
    });
    // Summary
    this.svc.getSummary().subscribe({ next: res => this.summary.set(res.data || null) });
    // Permissions
    this.svc.getMyPermissions().subscribe({ next: res => this.permissions.set(res.data || null) });
    // Tree
    this.svc.getTree().subscribe({ next: res => this.treeData.set(res.data || null) });
  }

  treeText(): string {
    const data = this.treeData();
    if (!data || !data.tree) return 'Loading tree…';
    const lines: string[] = ['User Hierarchy Tree\n' + '═'.repeat(40)];
    const build = (parentId: number, depth: number) => {
      const children: any[] = data.tree[parentId] || [];
      children.forEach((u: any, i: number) => {
        const isLast = i === children.length - 1;
        const prefix = '  '.repeat(depth) + (isLast ? '└── ' : '├── ');
        const meta = USER_TYPE_META[u.userType] || { icon: '👤', label: u.userType };
        lines.push(`${prefix}${meta.icon} ${u.name} [${meta.label}] — ${u.plan} — ${u.status}`);
        build(u.id, depth + 1);
      });
    };
    build(0, 0);
    return lines.join('\n');
  }

  getMeta(type: string) { return this.svc.getTypeMeta(type); }

  tab(t: string) {
    return this.activeTab() === t
      ? 'bg-blue-700/40 text-blue-200 border border-blue-600'
      : 'bg-slate-800 text-slate-400 hover:bg-slate-700';
  }

  // ── Selection ──────────────────────────────────────────────────────────────
  toggleSelect(id: number, ev: Event) {
    const checked = (ev.target as HTMLInputElement).checked;
    const ids = new Set(this.selectedIds());
    checked ? ids.add(id) : ids.delete(id);
    this.selectedIds.set(ids);
  }
  toggleAll(ev: Event) {
    const checked = (ev.target as HTMLInputElement).checked;
    this.selectedIds.set(checked ? new Set(this.filteredUsers().map((u: any) => u.id)) : new Set());
  }

  // ── CRUD ───────────────────────────────────────────────────────────────────
  openCreate() {
    this.editingUser.set(null);
    this.form = { userType: this.creatableTypes()[this.creatableTypes().length - 1] || 'paid_subscriber',
                  plan: 'Free Trial', status: 'active' };
    this.formError.set('');
    this.showModal.set(true);
  }

  openEdit(u: any) {
    this.editingUser.set(u);
    this.form = { name: u.name, email: u.email, mobile: u.mobile, pan: u.pan,
                  userType: u.userType, plan: u.plan, status: u.status, planExpiry: u.planExpiry,
                  notes: u.notes };
    this.formError.set('');
    this.showModal.set(true);
  }

  closeModal() { this.showModal.set(false); this.editingUser.set(null); }

  submitForm() {
    if (!this.form.name || !this.form.email) {
      this.formError.set('Name and email are required.'); return;
    }
    this.formLoading.set(true); this.formError.set('');
    const action = this.editingUser()
      ? this.svc.changeType(this.editingUser().id, this.form.userType)
      : this.svc.createUser(this.form);
    action.subscribe({
      next: () => { this.closeModal(); this.loadAll(); this.formLoading.set(false); },
      error: (e: any) => { this.formError.set(e.error?.message || 'Failed.'); this.formLoading.set(false); }
    });
  }

  // ── Single-user actions ────────────────────────────────────────────────────
  suspendUser(u: any) {
    const reason = prompt(`Reason for suspending ${u.name}:`);
    if (!reason) return;
    this.svc.suspendUser(u.id, reason).subscribe({ next: () => this.loadAll() });
  }
  activateUser(u: any) {
    this.svc.activateUser(u.id).subscribe({ next: () => this.loadAll() });
  }
  blockUser(u: any) {
    const reason = prompt(`Reason for blocking ${u.name} (permanent):`);
    if (!reason) return;
    this.svc.blockUser(u.id, reason).subscribe({ next: () => this.loadAll() });
  }
  unlockUser(u: any) {
    this.svc.unlockUser(u.id).subscribe({ next: () => this.loadAll() });
  }
  doResetPw() {
    const u = this.selectedUser();
    if (!u) return;
    this.svc.resetPassword(u.id, this.newPassword || undefined).subscribe({
      next: (res: any) => { this.resetResult.set(res.data?.generatedPassword || ''); this.newPassword = ''; }
    });
  }

  // ── Bulk actions ───────────────────────────────────────────────────────────
  bulkSuspend() {
    const reason = prompt('Reason for bulk suspension:');
    if (!reason) return;
    this.svc.bulkSuspend([...this.selectedIds()], reason).subscribe({
      next: () => { this.selectedIds.set(new Set()); this.loadAll(); }
    });
  }
  doBulkPlan() {
    this.svc.bulkUpdatePlan([...this.selectedIds()], this.bulkPlan).subscribe({
      next: () => { this.selectedIds.set(new Set()); this.bulkPlanModal.set(false); this.loadAll(); }
    });
  }
}
