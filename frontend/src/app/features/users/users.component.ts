import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ApiService } from '../../core/services/api.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host{display:block;min-height:100vh;background:#0b1e3d;color:#e2e8f0}
    .page{padding:20px;max-width:100%}
    .hero{background:linear-gradient(120deg,#102447,#0d2e37);border:1px solid #284466;border-top:3px solid #16d6a4;border-radius:8px;padding:18px;margin-bottom:14px}
    .title{font-size:24px;font-weight:900;color:#fff}
    .subtle{color:#94a3b8;font-size:12px}
    .layout{display:grid;grid-template-columns:220px 1fr;gap:0;min-height:calc(100vh - 140px)}
    .sidebar{background:#10233d;border:1px solid #223a56;border-radius:8px 0 0 8px;padding:8px 0}
    .sidebar-item{display:flex;align-items:center;gap:8px;padding:10px 16px;font-size:12px;font-weight:700;color:#94a3b8;cursor:pointer;border-left:3px solid transparent;transition:all .15s}
    .sidebar-item:hover{color:#e2e8f0;background:#132942}
    .sidebar-item.active{color:#60a5fa;border-left-color:#60a5fa;background:#0d2148}
    .sidebar-icon{width:18px;text-align:center;font-size:14px}
    .content{background:#10233d;border:1px solid #223a56;border-left:0;border-radius:0 8px 8px 0;padding:20px;overflow-y:auto}
    .panel{background:#0b1a2d;border:1px solid #203650;border-radius:8px;padding:16px;margin-bottom:14px}
    .panel-title{font-size:16px;font-weight:800;color:#fff;margin-bottom:12px}
    .form-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px}
    label{display:block;color:#94a3b8;font-size:11px;margin-bottom:4px}
    .input,.select{width:100%;background:#071426;border:1px solid #31506f;border-radius:6px;color:#fff;padding:8px 10px;font-size:12px}
    .btn{border:0;border-radius:6px;padding:8px 14px;font-size:12px;font-weight:700;cursor:pointer;white-space:nowrap}
    .btn-primary{background:#1d6adb;color:#fff}.btn-primary:hover{background:#1a5fc7}
    .btn-secondary{background:#1f334c;color:#cbd5e1;border:1px solid #355274}
    .btn-danger{background:#dc2626;color:#fff}
    .btn:disabled{opacity:.45;cursor:not-allowed}
    .btn-sm{padding:5px 10px;font-size:11px}
    .table-wrap{overflow-x:auto;border:1px solid #203650;border-radius:6px}
    table{width:100%;border-collapse:collapse;min-width:700px}
    th{background:#173456;color:#fff;font-size:11px;text-align:left;padding:8px 10px;border-bottom:1px solid #2a4668;text-transform:uppercase;letter-spacing:.3px}
    td{padding:8px 10px;border-bottom:1px solid #203650;font-size:12px}
    tr:hover td{background:#132942}
    .badge{display:inline-flex;align-items:center;border-radius:999px;padding:3px 10px;font-size:10px;font-weight:700;text-transform:uppercase}
    .badge-green{color:#bbf7d0;background:rgba(22,163,74,.18);border:1px solid #16a34a}
    .badge-red{color:#fecaca;background:rgba(220,38,38,.18);border:1px solid #dc2626}
    .badge-yellow{color:#fde68a;background:rgba(217,119,6,.14);border:1px solid #d97706}
    .badge-blue{color:#bfdbfe;background:rgba(37,99,235,.16);border:1px solid #2563eb}
    .badge-slate{color:#cbd5e1;background:rgba(100,116,139,.14);border:1px solid #64748b}
    .badge-purple{color:#d8b4fe;background:rgba(147,51,234,.14);border:1px solid #9333ea}
    .stat-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px;margin-bottom:14px}
    .stat{background:#0b1a2d;border:1px solid #203650;border-radius:8px;padding:14px;text-align:center}
    .stat-val{font-size:22px;font-weight:900;color:#fff}
    .stat-label{color:#94a3b8;font-size:10px;margin-top:2px;text-transform:uppercase;letter-spacing:.4px}
    .search-bar{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:12px}
    .search-bar .input,.search-bar .select{width:auto;min-width:150px}
    .empty{text-align:center;padding:40px 20px;color:#64748b}
    .pager{display:flex;align-items:center;justify-content:space-between;padding:10px 0;font-size:11px;color:#94a3b8}
    .pager button{background:#1f334c;color:#cbd5e1;border:1px solid #355274;border-radius:4px;padding:4px 10px;font-size:11px;cursor:pointer}
    .pager button:disabled{opacity:.4;cursor:not-allowed}
    .actions{display:flex;gap:8px;flex-wrap:wrap}
    .text-blue{color:#60a5fa}.text-green{color:#34d399}.text-red{color:#f87171}.text-amber{color:#fbbf24}.text-purple{color:#a78bfa}
    .gw-toggle{display:flex;gap:0;border:1px solid #355274;border-radius:6px;overflow:hidden}
    .gw-toggle button{border:0;padding:8px 16px;font-size:11px;font-weight:700;cursor:pointer;color:#94a3b8;background:#0b1a2d}
    .gw-toggle button.active{color:#fff;background:#1d6adb}
    .file{display:none}
    @media(max-width:900px){.layout{grid-template-columns:1fr}.sidebar{display:flex;flex-wrap:wrap;gap:0;border-radius:8px;margin-bottom:8px}.sidebar-item{border-left:0;border-bottom:2px solid transparent;padding:8px 12px}.sidebar-item.active{border-bottom-color:#60a5fa}.content{border-radius:8px;border:1px solid #223a56}}
  `],
  template: `
<div class="page">
  <section class="hero">
    <div style="display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;align-items:center">
      <div>
        <div class="title">User & Subscription Management</div>
        <div class="subtle">Manage users by subscription plan, payments, permissions, and reporting.</div>
      </div>
      <div class="actions">
        <button class="btn btn-secondary" (click)="bulkInput.click()">Bulk Upload</button>
        <button class="btn btn-secondary" (click)="downloadUsers()">Export Users</button>
        <input #bulkInput class="file" type="file" accept=".csv" (change)="uploadBulk($event)">
      </div>
    </div>
  </section>

  @if (loading()) {
    <div class="panel">Loading user data...</div>
  } @else {
    <div class="layout">
      <nav class="sidebar">
        @for (m of menus; track m.key) {
          <div [class.active]="activeMenu === m.key" class="sidebar-item" (click)="activeMenu = m.key; onMenuChange()">
            <span class="sidebar-icon">{{ m.icon }}</span> {{ m.label }}
          </div>
        }
      </nav>

      <div class="content">

        <!-- 1. User Dashboard -->
        @if (activeMenu === 'dashboard') {
          <div class="panel-title">User Dashboard</div>
          <div class="stat-grid">
            <div class="stat"><div class="stat-val text-blue">{{ summary.totalUsers || 0 }}</div><div class="stat-label">Total Users</div></div>
            <div class="stat"><div class="stat-val text-green">{{ summary.freeUsers || 0 }}</div><div class="stat-label">Free Users</div></div>
            <div class="stat"><div class="stat-val text-amber">{{ summary.basicUsers || 0 }}</div><div class="stat-label">Basic Users</div></div>
            <div class="stat"><div class="stat-val text-purple">{{ summary.proUsers || 0 }}</div><div class="stat-label">Pro Users</div></div>
            <div class="stat"><div class="stat-val text-red">{{ summary.eliteUsers || 0 }}</div><div class="stat-label">Elite Users</div></div>
            <div class="stat"><div class="stat-val">{{ summary.activeSubscriptions || 0 }}</div><div class="stat-label">Active Subs</div></div>
            <div class="stat"><div class="stat-val text-green">{{ formatMoney(summary.totalRevenue) }}</div><div class="stat-label">Total Revenue</div></div>
            <div class="stat"><div class="stat-val text-amber">{{ summary.pendingPayments || 0 }}</div><div class="stat-label">Pending Payments</div></div>
          </div>
          <div class="panel">
            <div class="panel-title">Subscription Plans</div>
            <div class="table-wrap">
              <table>
                <thead><tr><th>Plan</th><th>Category</th><th>Monthly</th><th>Quarterly</th><th>Half-Yearly</th><th>Yearly</th><th>Trial Days</th><th>Status</th></tr></thead>
                <tbody>
                  @for (p of plans; track p.id) {
                    <tr>
                      <td style="color:#fff;font-weight:700">{{ p.planName }}</td>
                      <td><span [class]="categoryBadge(p.planCategory)">{{ p.planCategory }}</span></td>
                      <td>{{ formatMoney(p.monthlyPrice) }}</td>
                      <td>{{ formatMoney(p.quarterlyPrice) }}</td>
                      <td>{{ formatMoney(p.halfYearlyPrice) }}</td>
                      <td>{{ formatMoney(p.yearlyPrice) }}</td>
                      <td>{{ p.trialDays || 0 }}</td>
                      <td><span [class]="statusBadge(p.status)">{{ p.status }}</span></td>
                    </tr>
                  }
                  @if (!plans.length) { <tr><td colspan="8" class="empty">No plans configured yet.</td></tr> }
                </tbody>
              </table>
            </div>
          </div>
        }

        <!-- 2-5. User Management by Plan Category -->
        @if (activeMenu === 'free' || activeMenu === 'basic' || activeMenu === 'pro' || activeMenu === 'elite') {
          <div class="panel-title">{{ planLabel() }} User Management</div>
          @if (activeMenu === 'elite') {
            <div style="padding:8px 12px;background:#3a260830;border:1px solid #78350f60;border-radius:6px;font-size:12px;color:#fde68a;margin-bottom:12px">
              Elite users have access to all Pro features + Stock Upload, Advanced Dashboard, Data Warehouse, and Advanced Reports.
            </div>
          }
          @if (activeMenu === 'free') {
            <div style="padding:8px 12px;background:#1e3a5f40;border:1px solid #2563eb60;border-radius:6px;font-size:12px;color:#93c5fd;margin-bottom:12px">
              Free users have limited dashboard access, limited strategy view, limited data preview. No stock upload or advanced reports.
            </div>
          }
          <div class="search-bar">
            <input class="input" placeholder="Search by name or email..." [(ngModel)]="userSearch" (keyup.enter)="loadPlanUsers()">
            <button class="btn btn-primary btn-sm" (click)="loadPlanUsers()">Search</button>
            <button class="btn btn-secondary btn-sm" (click)="showUserForm = !showUserForm">{{ showUserForm ? 'Hide Form' : 'Add ' + planLabel() + ' User' }}</button>
          </div>
          @if (showUserForm) {
            <div class="panel">
              <div style="font-weight:700;color:#fff;margin-bottom:10px">{{ userForm.id ? 'Edit' : 'Create' }} {{ planLabel() }} User</div>
              <div class="form-grid">
                <div><label>Name *</label><input class="input" [(ngModel)]="userForm.name"></div>
                <div><label>Email *</label><input class="input" [(ngModel)]="userForm.email"></div>
                <div><label>Mobile</label><input class="input" [(ngModel)]="userForm.mobile"></div>
                <div><label>Password</label><input class="input" type="password" [(ngModel)]="userForm.password" placeholder="Keep blank when editing"></div>
                <div><label>Plan Expiry</label><input class="input" type="date" [(ngModel)]="userForm.planExpiry"></div>
                <div><label>Status</label><select class="select" [(ngModel)]="userForm.status"><option>active</option><option>inactive</option><option>suspended</option></select></div>
              </div>
              <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:12px">
                <button class="btn btn-secondary" (click)="resetUserForm()">Clear</button>
                <button class="btn btn-primary" (click)="saveUser()">{{ userForm.id ? 'Update' : 'Create' }} User</button>
              </div>
            </div>
          }
          <div class="table-wrap">
            <table>
              <thead><tr><th>TR ID</th><th>Name</th><th>Email</th><th>Plan</th><th>Expiry</th><th>Status</th><th>Actions</th></tr></thead>
              <tbody>
                @for (u of planUsers; track u.id) {
                  <tr>
                    <td class="text-blue" style="font-size:11px;font-family:monospace">{{ u.trUserId }}</td>
                    <td style="color:#fff;font-weight:700">{{ u.name }}</td>
                    <td>{{ u.email }}</td>
                    <td><span [class]="categoryBadge(u.plan)">{{ u.plan }}</span></td>
                    <td>{{ u.planExpiry || '-' }}</td>
                    <td><span [class]="statusBadge(u.status)">{{ u.status }}</span></td>
                    <td>
                      <button class="btn btn-secondary btn-sm" (click)="editUser(u)">Edit</button>
                      @if (u.status === 'active') {
                        <button class="btn btn-danger btn-sm" style="margin-left:4px" (click)="toggleStatus(u, 'inactive')">Deactivate</button>
                      } @else {
                        <button class="btn btn-primary btn-sm" style="margin-left:4px" (click)="toggleStatus(u, 'active')">Activate</button>
                      }
                    </td>
                  </tr>
                }
                @if (!planUsers.length) { <tr><td colspan="7" class="empty">No {{ planLabel().toLowerCase() }} users found.</td></tr> }
              </tbody>
            </table>
          </div>
          @if (planUserTotal > pageSize) {
            <div class="pager">
              <span>{{ page * pageSize + 1 }}-{{ Math.min((page + 1) * pageSize, planUserTotal) }} of {{ planUserTotal }}</span>
              <div style="display:flex;gap:4px">
                <button (click)="page = page - 1; loadPlanUsers()" [disabled]="page <= 0">Prev</button>
                <button (click)="page = page + 1; loadPlanUsers()" [disabled]="(page + 1) * pageSize >= planUserTotal">Next</button>
              </div>
            </div>
          }
        }

        <!-- 6. Subscription Management -->
        @if (activeMenu === 'subscriptions') {
          <div class="panel-title">Subscription Management</div>
          <div class="search-bar">
            <select class="select" [(ngModel)]="subStatusFilter" (ngModelChange)="loadSubscriptions()">
              <option value="">All Status</option>
              <option value="active">Active</option>
              <option value="expired">Expired</option>
              <option value="cancelled">Cancelled</option>
              <option value="pending">Pending</option>
            </select>
            <input class="input" placeholder="Search user..." [(ngModel)]="subSearch" (keyup.enter)="loadSubscriptions()">
            <button class="btn btn-primary btn-sm" (click)="loadSubscriptions()">Search</button>
            <button class="btn btn-secondary btn-sm" (click)="showPlanForm = !showPlanForm">{{ showPlanForm ? 'Hide' : 'Manage Plans' }}</button>
          </div>
          @if (showPlanForm) {
            <div class="panel">
              <div style="font-weight:700;color:#fff;margin-bottom:10px">{{ planForm.id ? 'Edit' : 'Create' }} Subscription Plan</div>
              <div class="form-grid">
                <div><label>Plan Name *</label><input class="input" [(ngModel)]="planForm.planName"></div>
                <div><label>Category *</label>
                  <select class="select" [(ngModel)]="planForm.planCategory">
                    <option value="free">Free</option><option value="basic">Basic</option>
                    <option value="pro">Pro</option><option value="elite">Elite</option>
                  </select>
                </div>
                <div><label>Monthly Price</label><input class="input" type="number" [(ngModel)]="planForm.monthlyPrice"></div>
                <div><label>Quarterly Price</label><input class="input" type="number" [(ngModel)]="planForm.quarterlyPrice"></div>
                <div><label>Half-Yearly Price</label><input class="input" type="number" [(ngModel)]="planForm.halfYearlyPrice"></div>
                <div><label>Yearly Price</label><input class="input" type="number" [(ngModel)]="planForm.yearlyPrice"></div>
                <div><label>Trial Days</label><input class="input" type="number" [(ngModel)]="planForm.trialDays"></div>
                <div><label>Status</label><select class="select" [(ngModel)]="planForm.status"><option>active</option><option>inactive</option></select></div>
                <div style="grid-column:1/-1"><label>Description</label><input class="input" [(ngModel)]="planForm.description"></div>
              </div>
              <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:12px">
                <button class="btn btn-secondary" (click)="resetPlanForm()">Clear</button>
                <button class="btn btn-primary" (click)="savePlan()">{{ planForm.id ? 'Update' : 'Create' }} Plan</button>
              </div>
            </div>
            <div class="panel">
              <div class="panel-title">All Plans</div>
              <div class="table-wrap">
                <table>
                  <thead><tr><th>Plan</th><th>Category</th><th>Monthly</th><th>Quarterly</th><th>Half-Yearly</th><th>Yearly</th><th>Trial</th><th>Status</th><th>Actions</th></tr></thead>
                  <tbody>
                    @for (p of plans; track p.id) {
                      <tr>
                        <td style="color:#fff;font-weight:700">{{ p.planName }}</td>
                        <td><span [class]="categoryBadge(p.planCategory)">{{ p.planCategory }}</span></td>
                        <td>{{ formatMoney(p.monthlyPrice) }}</td>
                        <td>{{ formatMoney(p.quarterlyPrice) }}</td>
                        <td>{{ formatMoney(p.halfYearlyPrice) }}</td>
                        <td>{{ formatMoney(p.yearlyPrice) }}</td>
                        <td>{{ p.trialDays || 0 }}d</td>
                        <td><span [class]="statusBadge(p.status)">{{ p.status }}</span></td>
                        <td>
                          <button class="btn btn-secondary btn-sm" (click)="editPlan(p)">Edit</button>
                          <button class="btn btn-sm" [class]="p.status === 'active' ? 'btn-danger' : 'btn-primary'" style="margin-left:4px"
                            (click)="togglePlanStatus(p)">{{ p.status === 'active' ? 'Deactivate' : 'Activate' }}</button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          }
          <div class="table-wrap">
            <table>
              <thead><tr><th>User</th><th>Plan</th><th>Cycle</th><th>Price</th><th>Start</th><th>End</th><th>Renewal</th><th>Sub Status</th><th>Payment</th><th>Auto</th><th>Actions</th></tr></thead>
              <tbody>
                @for (s of subscriptions; track s.id) {
                  <tr>
                    <td><strong style="color:#fff">{{ s.userName }}</strong><div class="subtle">{{ s.userEmail }}</div></td>
                    <td>{{ s.planName || '-' }}</td>
                    <td>{{ s.billingCycle }}</td>
                    <td>{{ formatMoney(s.price) }}</td>
                    <td>{{ s.startDate || '-' }}</td>
                    <td>{{ s.endDate || '-' }}</td>
                    <td>{{ s.renewalDate || '-' }}</td>
                    <td><span [class]="statusBadge(s.subscriptionStatus)">{{ s.subscriptionStatus }}</span></td>
                    <td><span [class]="paymentBadge(s.paymentStatus)">{{ s.paymentStatus }}</span></td>
                    <td>{{ s.autoRenew == 1 ? 'Yes' : 'No' }}</td>
                    <td>
                      @if (s.subscriptionStatus === 'active') {
                        <button class="btn btn-danger btn-sm" (click)="cancelSubscription(s.id)">Cancel</button>
                      }
                    </td>
                  </tr>
                }
                @if (!subscriptions.length) { <tr><td colspan="11" class="empty">No subscriptions found.</td></tr> }
              </tbody>
            </table>
          </div>
        }

        <!-- 7. Payment Management -->
        @if (activeMenu === 'payments') {
          <div class="panel-title">Payment Management</div>
          <div class="panel" style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px">
            <div>
              <div style="font-weight:700;color:#fff;font-size:13px;margin-bottom:4px">Payment Gateway Mode</div>
              <div class="gw-toggle">
                <button [class.active]="gatewayMode === 'test'" (click)="gatewayMode = 'test'">Test / Sandbox</button>
                <button [class.active]="gatewayMode === 'live'" (click)="gatewayMode = 'live'">Live / Production</button>
              </div>
            </div>
            <div>
              <div style="font-weight:700;color:#fff;font-size:13px;margin-bottom:4px">Gateway</div>
              <div class="gw-toggle">
                <button [class.active]="selectedGateway === 'razorpay'" (click)="selectedGateway = 'razorpay'">Razorpay</button>
                <button [class.active]="selectedGateway === 'billdesk'" (click)="selectedGateway = 'billdesk'">BillDesk</button>
              </div>
            </div>
            <div>
              @if (gatewayMode === 'test') {
                <span class="badge badge-yellow">TEST MODE - No real charges</span>
              } @else {
                <span class="badge badge-green">LIVE MODE - Real payments</span>
              }
            </div>
          </div>
          <div class="search-bar">
            <select class="select" [(ngModel)]="payStatusFilter" (ngModelChange)="loadPayments()">
              <option value="">All Status</option>
              <option value="pending">Pending</option>
              <option value="success">Success</option>
              <option value="failed">Failed</option>
              <option value="cancelled">Cancelled</option>
              <option value="refunded">Refunded</option>
            </select>
            <input class="input" placeholder="Search user, txn, or invoice..." [(ngModel)]="paySearch" (keyup.enter)="loadPayments()">
            <button class="btn btn-primary btn-sm" (click)="loadPayments()">Search</button>
            <button class="btn btn-secondary btn-sm" (click)="downloadPayments()">Export CSV</button>
          </div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>User</th><th>Cycle</th><th>Amount</th><th>GST</th><th>Discount</th><th>Net</th><th>Gateway</th><th>Mode</th><th>Txn ID</th><th>Status</th><th>Date</th><th>Invoice</th><th>Actions</th></tr></thead>
              <tbody>
                @for (p of payments; track p.id) {
                  <tr>
                    <td><strong style="color:#fff">{{ p.userName }}</strong></td>
                    <td>{{ p.billingCycle }}</td>
                    <td>{{ formatMoney(p.amount) }}</td>
                    <td>{{ formatMoney(p.gst) }}</td>
                    <td>{{ formatMoney(p.discount) }}</td>
                    <td style="color:#60a5fa;font-weight:700">{{ formatMoney(p.netAmount) }}</td>
                    <td>{{ p.gatewayName || '-' }}</td>
                    <td><span [class]="p.gatewayMode === 'live' ? 'badge badge-green' : 'badge badge-yellow'">{{ p.gatewayMode }}</span></td>
                    <td style="font-size:10px;font-family:monospace">{{ p.transactionId || '-' }}</td>
                    <td><span [class]="paymentBadge(p.paymentStatus)">{{ p.paymentStatus }}</span></td>
                    <td>{{ p.paymentDate || '-' }}</td>
                    <td style="font-size:10px">{{ p.invoiceNumber || '-' }}</td>
                    <td>
                      @if (p.paymentStatus === 'pending') {
                        <button class="btn btn-primary btn-sm" (click)="markPaymentSuccess(p.id)">Mark Success</button>
                      }
                      @if (p.paymentStatus === 'success') {
                        <button class="btn btn-danger btn-sm" (click)="markPaymentRefunded(p.id)">Refund</button>
                      }
                    </td>
                  </tr>
                }
                @if (!payments.length) { <tr><td colspan="13" class="empty">No payments found.</td></tr> }
              </tbody>
            </table>
          </div>
        }

        <!-- 8. Role & Permission Mapping -->
        @if (activeMenu === 'permissions') {
          <div class="panel-title">Role & Permission Mapping</div>
          <div style="padding:8px 12px;background:#1e3a5f40;border:1px solid #2563eb60;border-radius:6px;font-size:12px;color:#93c5fd;margin-bottom:12px">
            Rules: Expired subscription restricts paid features. Failed payment keeps user pending. Successful payment auto-activates. Cancelled subscription disables paid access. Elite active = Stock Upload + Dashboard access.
          </div>
          <div class="search-bar">
            <select class="select" [(ngModel)]="permCategoryFilter" (ngModelChange)="loadPermissions()">
              <option value="">All Categories</option>
              <option value="free">Free</option>
              <option value="basic">Basic</option>
              <option value="pro">Pro</option>
              <option value="elite">Elite</option>
            </select>
          </div>
          <div class="panel">
            <div style="font-weight:700;color:#fff;margin-bottom:10px">Add / Update Permission</div>
            <div class="form-grid">
              <div><label>Plan Category *</label>
                <select class="select" [(ngModel)]="permForm.planCategory">
                  <option value="free">Free</option><option value="basic">Basic</option>
                  <option value="pro">Pro</option><option value="elite">Elite</option>
                </select>
              </div>
              <div><label>Billing Cycle</label>
                <select class="select" [(ngModel)]="permForm.billingCycle">
                  <option value="">Any</option>
                  <option value="monthly">Monthly</option><option value="quarterly">Quarterly</option>
                  <option value="half_yearly">Half-Yearly</option><option value="yearly">Yearly</option>
                </select>
              </div>
              <div><label>Menu / Feature *</label>
                <select class="select" [(ngModel)]="permForm.menuKey">
                  @for (mk of permMenuKeys; track mk) { <option [value]="mk">{{ mk }}</option> }
                </select>
              </div>
              <div><label>Action</label>
                <select class="select" [(ngModel)]="permForm.actionKey">
                  <option value="view">View</option><option value="create">Create</option>
                  <option value="edit">Edit</option><option value="export">Export</option>
                </select>
              </div>
              <div><label>Allowed</label>
                <select class="select" [(ngModel)]="permForm.allowed">
                  <option [ngValue]="true">Yes</option><option [ngValue]="false">No</option>
                </select>
              </div>
            </div>
            <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:12px">
              <button class="btn btn-primary" (click)="savePermission()">Save Permission</button>
            </div>
          </div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>Category</th><th>Cycle</th><th>Menu/Feature</th><th>Action</th><th>Allowed</th></tr></thead>
              <tbody>
                @for (p of permissions; track p.id) {
                  <tr>
                    <td><span [class]="categoryBadge(p.planCategory)">{{ p.planCategory }}</span></td>
                    <td>{{ p.billingCycle || 'Any' }}</td>
                    <td style="color:#fff;font-weight:700">{{ p.menuKey }}</td>
                    <td>{{ p.actionKey || '-' }}</td>
                    <td><span [class]="p.allowed == 1 || p.allowed === true ? 'badge badge-green' : 'badge badge-red'">{{ p.allowed == 1 || p.allowed === true ? 'Yes' : 'No' }}</span></td>
                  </tr>
                }
                @if (!permissions.length) { <tr><td colspan="5" class="empty">No permissions configured.</td></tr> }
              </tbody>
            </table>
          </div>
        }

        <!-- 9. User Reports -->
        @if (activeMenu === 'reports') {
          <div class="panel-title">User Reports</div>
          <div class="stat-grid">
            <div class="stat"><div class="stat-val text-blue">{{ summary.totalUsers || 0 }}</div><div class="stat-label">Total Users</div></div>
            <div class="stat"><div class="stat-val text-green">{{ summary.freeUsers || 0 }}</div><div class="stat-label">Free</div></div>
            <div class="stat"><div class="stat-val text-amber">{{ summary.basicUsers || 0 }}</div><div class="stat-label">Basic</div></div>
            <div class="stat"><div class="stat-val text-purple">{{ summary.proUsers || 0 }}</div><div class="stat-label">Pro</div></div>
            <div class="stat"><div class="stat-val text-red">{{ summary.eliteUsers || 0 }}</div><div class="stat-label">Elite</div></div>
            <div class="stat"><div class="stat-val text-green">{{ formatMoney(summary.totalRevenue) }}</div><div class="stat-label">Revenue</div></div>
          </div>
          <div class="panel">
            <div class="panel-title">Download Reports</div>
            <div class="actions">
              <button class="btn btn-primary" (click)="downloadUserReport('')">All Users CSV</button>
              <button class="btn btn-secondary" (click)="downloadUserReport('free')">Free Users CSV</button>
              <button class="btn btn-secondary" (click)="downloadUserReport('basic')">Basic Users CSV</button>
              <button class="btn btn-secondary" (click)="downloadUserReport('pro')">Pro Users CSV</button>
              <button class="btn btn-secondary" (click)="downloadUserReport('elite')">Elite Users CSV</button>
              <button class="btn btn-secondary" (click)="downloadPayments()">Payments CSV</button>
            </div>
          </div>
        }

      </div>
    </div>
  }
</div>
  `
})
export class UsersComponent implements OnInit {
  Math = Math;
  loading = signal(true);
  activeMenu = 'dashboard';

  menus = [
    { key: 'dashboard', label: 'User Dashboard', icon: '\u{1F4CA}' },
    { key: 'free', label: 'Free Users', icon: '\u{1F465}' },
    { key: 'basic', label: 'Basic Users', icon: '\u{1F464}' },
    { key: 'pro', label: 'Pro Users', icon: '\u{2B50}' },
    { key: 'elite', label: 'Elite Users', icon: '\u{1F451}' },
    { key: 'subscriptions', label: 'Subscription Mgmt', icon: '\u{1F4B3}' },
    { key: 'payments', label: 'Payment Mgmt', icon: '\u{1F4B0}' },
    { key: 'permissions', label: 'Role & Permissions', icon: '\u{1F512}' },
    { key: 'reports', label: 'User Reports', icon: '\u{1F4C4}' },
  ];

  summary: any = {};
  plans: any[] = [];

  planUsers: any[] = [];
  planUserTotal = 0;
  page = 0;
  pageSize = 20;
  userSearch = '';
  showUserForm = false;
  userForm: any = {};

  subscriptions: any[] = [];
  subStatusFilter = '';
  subSearch = '';
  showPlanForm = false;
  planForm: any = {};

  payments: any[] = [];
  payStatusFilter = '';
  paySearch = '';
  gatewayMode = 'test';
  selectedGateway = 'razorpay';

  permissions: any[] = [];
  permCategoryFilter = '';
  permForm: any = { planCategory: 'free', billingCycle: '', menuKey: 'dashboard', actionKey: 'view', allowed: true };
  permMenuKeys = ['dashboard', 'watchlist', 'screener', 'strategy_builder', 'backtesting', 'reports', 'data_warehouse', 'stock_upload', 'advanced_dashboard', 'paper_trading', 'options', 'data_preview'];

  institutions: any[] = [];
  roles: string[] = [];
  allPlans: string[] = [];
  segments: string[] = [];
  audit: any[] = [];

  constructor(private api: ApiService, private http: HttpClient) {}

  ngOnInit() {
    this.resetUserForm();
    this.resetPlanForm();
    this.load();
  }

  load() {
    this.api.get<any>('/admin/subscriptions/dashboard').subscribe({
      next: r => {
        const d = r.data || {};
        this.summary = d.summary || {};
        this.plans = d.plans || [];
        this.loading.set(false);
      },
      error: () => {
        this.api.get<any>('/admin/management/users/dashboard').subscribe({
          next: r => {
            const d = r.data || {};
            this.summary = d.summary || {};
            this.institutions = d.institutions || [];
            this.roles = d.roles || [];
            this.allPlans = d.plans || [];
            this.segments = d.segments || [];
            this.audit = d.audit || [];
            this.loading.set(false);
          },
          error: () => this.loading.set(false)
        });
      }
    });
  }

  onMenuChange() {
    if (['free', 'basic', 'pro', 'elite'].includes(this.activeMenu)) {
      this.page = 0;
      this.loadPlanUsers();
    } else if (this.activeMenu === 'subscriptions') {
      this.loadSubscriptions();
      this.loadPlans();
    } else if (this.activeMenu === 'payments') {
      this.loadPayments();
    } else if (this.activeMenu === 'permissions') {
      this.loadPermissions();
    }
  }

  planLabel(): string {
    return ({ free: 'Free', basic: 'Basic', pro: 'Pro', elite: 'Elite' } as any)[this.activeMenu] || 'User';
  }

  private planCategory(): string {
    return this.activeMenu;
  }

  private planForUser(): string {
    return ({ free: 'Free Trial', basic: 'Basic', pro: 'Pro', elite: 'Elite' } as any)[this.activeMenu] || 'Free Trial';
  }

  // --- User Management by Plan ---
  resetUserForm() {
    this.userForm = { name: '', email: '', mobile: '', password: '', role: 'user', plan: this.planForUser(), status: 'active', planExpiry: '', institutionId: 1 };
  }

  loadPlanUsers() {
    let url = `/admin/subscriptions/users?planCategory=${this.planCategory()}&page=${this.page}&size=${this.pageSize}`;
    if (this.userSearch) url += `&search=${encodeURIComponent(this.userSearch)}`;
    this.api.get<any>(url).subscribe({
      next: r => {
        this.planUsers = r.data?.users || [];
        this.planUserTotal = r.data?.total || 0;
      },
      error: () => { this.planUsers = []; this.planUserTotal = 0; }
    });
  }

  editUser(u: any) {
    this.userForm = { ...u, password: '' };
    this.showUserForm = true;
  }

  saveUser() {
    if (!this.userForm.name?.trim() || !this.userForm.email?.trim()) { alert('Name and email are required.'); return; }
    this.userForm.role = 'user';
    this.userForm.plan = this.planForUser();
    this.api.post<any>('/admin/management/users', this.userForm).subscribe({
      next: r => { alert(r.message || 'Saved.'); this.resetUserForm(); this.showUserForm = false; this.loadPlanUsers(); },
      error: e => alert(e.error?.message || 'Could not save user.')
    });
  }

  toggleStatus(u: any, status: string) {
    this.api.put<any>(`/admin/management/users/${u.id}/status`, { status }).subscribe({
      next: () => this.loadPlanUsers(),
      error: e => alert(e.error?.message || 'Could not update status.')
    });
  }

  // --- Subscription Management ---
  resetPlanForm() {
    this.planForm = { planName: '', planCategory: 'basic', monthlyPrice: 0, quarterlyPrice: 0, halfYearlyPrice: 0, yearlyPrice: 0, trialDays: 0, description: '', status: 'active' };
  }

  loadPlans() {
    this.api.get<any>('/admin/subscriptions/plans').subscribe({
      next: r => this.plans = r.data || [],
      error: () => this.plans = []
    });
  }

  editPlan(p: any) {
    this.planForm = { ...p };
    this.showPlanForm = true;
  }

  savePlan() {
    if (!this.planForm.planName?.trim()) { alert('Plan name is required.'); return; }
    this.api.post<any>('/admin/subscriptions/plans', this.planForm).subscribe({
      next: r => { alert(r.message || 'Saved.'); this.resetPlanForm(); this.loadPlans(); },
      error: e => alert(e.error?.message || 'Could not save plan.')
    });
  }

  togglePlanStatus(p: any) {
    const newStatus = p.status === 'active' ? 'inactive' : 'active';
    this.api.put<any>(`/admin/subscriptions/plans/${p.id}/status`, { status: newStatus }).subscribe({
      next: () => this.loadPlans(),
      error: e => alert(e.error?.message || 'Could not update plan status.')
    });
  }

  loadSubscriptions() {
    let url = '/admin/subscriptions/subscriptions?page=0&size=50';
    if (this.subStatusFilter) url += `&status=${this.subStatusFilter}`;
    if (this.subSearch) url += `&search=${encodeURIComponent(this.subSearch)}`;
    this.api.get<any>(url).subscribe({
      next: r => this.subscriptions = r.data?.subscriptions || [],
      error: () => this.subscriptions = []
    });
  }

  cancelSubscription(id: number) {
    if (!confirm('Cancel this subscription?')) return;
    this.api.post<any>(`/admin/subscriptions/subscriptions/${id}/cancel`, {}).subscribe({
      next: () => this.loadSubscriptions(),
      error: e => alert(e.error?.message || 'Could not cancel.')
    });
  }

  // --- Payment Management ---
  loadPayments() {
    let url = '/admin/subscriptions/payments?page=0&size=50';
    if (this.payStatusFilter) url += `&status=${this.payStatusFilter}`;
    if (this.paySearch) url += `&search=${encodeURIComponent(this.paySearch)}`;
    url += `&mode=${this.gatewayMode}`;
    this.api.get<any>(url).subscribe({
      next: r => this.payments = r.data?.payments || [],
      error: () => this.payments = []
    });
  }

  markPaymentSuccess(id: number) {
    this.api.put<any>(`/admin/subscriptions/payments/${id}/status`, { paymentStatus: 'success' }).subscribe({
      next: () => this.loadPayments(),
      error: e => alert(e.error?.message || 'Could not update.')
    });
  }

  markPaymentRefunded(id: number) {
    if (!confirm('Mark this payment as refunded?')) return;
    this.api.put<any>(`/admin/subscriptions/payments/${id}/status`, { paymentStatus: 'refunded' }).subscribe({
      next: () => this.loadPayments(),
      error: e => alert(e.error?.message || 'Could not update.')
    });
  }

  // --- Permissions ---
  loadPermissions() {
    let url = '/admin/subscriptions/permissions';
    if (this.permCategoryFilter) url += `?planCategory=${this.permCategoryFilter}`;
    this.api.get<any>(url).subscribe({
      next: r => this.permissions = r.data || [],
      error: () => this.permissions = []
    });
  }

  savePermission() {
    if (!this.permForm.planCategory || !this.permForm.menuKey) { alert('Category and menu key required.'); return; }
    this.api.post<any>('/admin/subscriptions/permissions', this.permForm).subscribe({
      next: r => { alert(r.message || 'Saved.'); this.loadPermissions(); },
      error: e => alert(e.error?.message || 'Could not save.')
    });
  }

  // --- Reports & Downloads ---
  downloadUsers() { this.download('/admin/management/users/download', 'users_download.csv'); }

  downloadUserReport(category: string) {
    const url = category ? `/admin/subscriptions/reports/users?planCategory=${category}` : '/admin/subscriptions/reports/users';
    this.download(url, `users_${category || 'all'}_report.csv`);
  }

  downloadPayments() {
    this.download('/admin/subscriptions/reports/payments', 'payments_report.csv');
  }

  uploadBulk(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    const fd = new FormData();
    fd.append('file', file);
    this.api.upload<any>('/admin/management/users/bulk', fd).subscribe({
      next: r => { alert(r.message || 'Bulk upload completed.'); this.load(); input.value = ''; },
      error: e => alert(e.error?.message || 'Bulk upload failed.')
    });
  }

  private download(path: string, filename: string) {
    this.http.get(`${environment.apiUrl}${path}`, { responseType: 'blob' }).subscribe(blob => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = filename; a.click();
      URL.revokeObjectURL(url);
    });
  }

  // --- Helpers ---
  formatMoney(v: any): string {
    const n = Number(v || 0);
    return n === 0 ? '0' : n.toLocaleString('en-IN', { maximumFractionDigits: 0 });
  }

  statusBadge(s: string): string {
    switch ((s || '').toLowerCase()) {
      case 'active': case 'paid': case 'success': return 'badge badge-green';
      case 'inactive': case 'expired': case 'cancelled': return 'badge badge-red';
      case 'pending': return 'badge badge-yellow';
      default: return 'badge badge-slate';
    }
  }

  categoryBadge(c: string): string {
    switch ((c || '').toLowerCase()) {
      case 'free': case 'free trial': return 'badge badge-slate';
      case 'basic': case 'starter': return 'badge badge-blue';
      case 'pro': case 'premium': return 'badge badge-purple';
      case 'elite': case 'enterprise': case 'institutional': return 'badge badge-green';
      default: return 'badge badge-slate';
    }
  }

  paymentBadge(s: string): string {
    switch ((s || '').toLowerCase()) {
      case 'success': return 'badge badge-green';
      case 'failed': return 'badge badge-red';
      case 'pending': return 'badge badge-yellow';
      case 'cancelled': return 'badge badge-slate';
      case 'refunded': return 'badge badge-purple';
      default: return 'badge badge-slate';
    }
  }
}
