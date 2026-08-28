import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';

@Component({
  selector: 'app-project-health',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
<div class="p-6 min-h-screen bg-[#0d1829]">

  <!-- Header -->
  <div class="flex items-center justify-between mb-6">
    <div>
      <h1 class="text-2xl font-bold text-white">Project Health Dashboard</h1>
      <p class="text-slate-400 text-sm mt-1">Automated project analysis & pending work detection</p>
    </div>
    <div class="flex gap-2">
      <button (click)="loadFullReport()" class="btn-primary" [disabled]="loading">
        {{ loading ? 'Scanning...' : 'Run Full Scan' }}
      </button>
      <button (click)="downloadReport()" class="btn-secondary" [disabled]="!report">
        Export Report
      </button>
    </div>
  </div>

  <!-- Loading -->
  @if (loading) {
    <div class="flex items-center justify-center py-20">
      <div class="text-center">
        <div class="animate-spin w-12 h-12 border-4 border-blue-500 border-t-transparent rounded-full mx-auto mb-4"></div>
        <p class="text-slate-400">Scanning project... This may take a moment</p>
      </div>
    </div>
  }

  <!-- Report Content -->
  @if (report && !loading) {

    <!-- Health Score Banner -->
    <div class="grid grid-cols-1 md:grid-cols-4 gap-4 mb-6">
      <div class="card col-span-1 text-center py-8">
        <div class="text-6xl font-bold mb-2" [class]="scoreColor(report.health_score)">
          {{ report.health_score }}
        </div>
        <div class="text-slate-400 text-sm">Health Score</div>
        <div class="mt-2 px-4 py-1 rounded-full text-sm inline-block"
          [class]="'bg-opacity-20 ' + gradeClass(report.health_grade)">
          Grade: {{ report.health_grade }}
        </div>
      </div>

      <div class="card">
        <div class="text-slate-400 text-xs uppercase mb-3">Overall Status</div>
        <div class="text-xl font-bold text-white mb-2">{{ report.overall_status }}</div>
        <div class="text-slate-400 text-sm">Generated: {{ report.generated_at }}</div>
      </div>

      <div class="card">
        <div class="text-slate-400 text-xs uppercase mb-3">Git Changes</div>
        <div class="text-xl font-bold text-blue-400">{{ report.git_status?.total_files_changed || 0 }} files</div>
        <div class="flex gap-4 mt-2 text-sm">
          <span class="text-green-400">+{{ report.git_status?.total_lines_added || 0 }}</span>
          <span class="text-red-400">-{{ report.git_status?.total_lines_deleted || 0 }}</span>
        </div>
      </div>

      <div class="card">
        <div class="text-slate-400 text-xs uppercase mb-3">Pending Issues</div>
        <div class="text-xl font-bold text-amber-400">{{ report.pending_work?.total_pending_issues || 0 }}</div>
        <div class="text-slate-400 text-sm mt-2">
          {{ report.pending_work?.todo_count || 0 }} TODOs,
          {{ report.pending_work?.fixme_count || 0 }} FIXMEs
        </div>
      </div>
    </div>

    <!-- Tabs -->
    <div class="flex gap-1 mb-6 overflow-x-auto pb-2">
      @for (tab of tabs; track tab.key) {
        <button (click)="activeTab = tab.key"
          class="px-4 py-2 rounded-lg text-sm whitespace-nowrap transition-colors"
          [class]="activeTab === tab.key ? 'bg-blue-600 text-white' : 'bg-[#1e2a3b] text-slate-400 hover:text-white'">
          {{ tab.label }}
          @if (tab.badge && getCount(tab.badge) > 0) {
            <span class="ml-1 px-1.5 py-0.5 rounded-full text-xs bg-red-600 text-white">{{ getCount(tab.badge) }}</span>
          }
        </button>
      }
    </div>

    <!-- TAB: Git Status -->
    @if (activeTab === 'git') {
      <div class="card mb-4">
        <h3 class="text-white font-semibold mb-4">Repository Status</h3>
        <div class="grid grid-cols-2 md:grid-cols-5 gap-4 mb-6">
          <div class="stat-box">
            <div class="text-2xl font-bold text-white">{{ report.git_status?.total_files_changed || 0 }}</div>
            <div class="text-slate-400 text-xs">Files Changed</div>
          </div>
          <div class="stat-box">
            <div class="text-2xl font-bold text-green-400">+{{ report.git_status?.total_lines_added || 0 }}</div>
            <div class="text-slate-400 text-xs">Lines Added</div>
          </div>
          <div class="stat-box">
            <div class="text-2xl font-bold text-red-400">-{{ report.git_status?.total_lines_deleted || 0 }}</div>
            <div class="text-slate-400 text-xs">Lines Deleted</div>
          </div>
          <div class="stat-box">
            <div class="text-2xl font-bold text-amber-400">{{ report.git_status?.large_change_count || 0 }}</div>
            <div class="text-slate-400 text-xs">Large Changes</div>
          </div>
          <div class="stat-box">
            <div class="text-2xl font-bold" [class]="report.git_status?.has_conflicts ? 'text-red-500' : 'text-green-400'">
              {{ report.git_status?.has_conflicts ? 'YES' : 'None' }}
            </div>
            <div class="text-slate-400 text-xs">Merge Conflicts</div>
          </div>
        </div>
      </div>

      <div class="card">
        <h3 class="text-white font-semibold mb-4">Changed Files (by size)</h3>
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-slate-400 text-left border-b border-slate-700">
                <th class="pb-2 pr-4">File</th>
                <th class="pb-2 pr-4 text-right">Added</th>
                <th class="pb-2 pr-4 text-right">Deleted</th>
                <th class="pb-2 pr-4 text-right">Total</th>
                <th class="pb-2 pr-4">Risk</th>
                <th class="pb-2">Last Modified By</th>
              </tr>
            </thead>
            <tbody>
              @for (f of report.git_status?.changed_files || []; track f.file) {
                <tr class="border-b border-slate-800 hover:bg-[#1a2a3d]">
                  <td class="py-2 pr-4 text-slate-300 font-mono text-xs">{{ f.file }}</td>
                  <td class="py-2 pr-4 text-right text-green-400">+{{ f.lines_added }}</td>
                  <td class="py-2 pr-4 text-right text-red-400">-{{ f.lines_deleted }}</td>
                  <td class="py-2 pr-4 text-right text-white font-semibold">{{ f.total_changed }}</td>
                  <td class="py-2 pr-4">
                    <span class="px-2 py-0.5 rounded text-xs" [class]="riskClass(f.risk_level)">{{ f.risk_level }}</span>
                  </td>
                  <td class="py-2 text-slate-400 text-xs">{{ f.last_modified_by }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    }

    <!-- TAB: Pending Work -->
    @if (activeTab === 'pending') {
      <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
        <div class="stat-card" (click)="pendingFilter = 'todos'">
          <div class="text-2xl font-bold text-amber-400">{{ report.pending_work?.todo_count || 0 }}</div>
          <div class="text-slate-400 text-xs">TODOs</div>
        </div>
        <div class="stat-card" (click)="pendingFilter = 'fixmes'">
          <div class="text-2xl font-bold text-red-400">{{ report.pending_work?.fixme_count || 0 }}</div>
          <div class="text-slate-400 text-xs">FIXMEs</div>
        </div>
        <div class="stat-card" (click)="pendingFilter = 'hacks'">
          <div class="text-2xl font-bold text-orange-400">{{ report.pending_work?.hack_count || 0 }}</div>
          <div class="text-slate-400 text-xs">HACKs</div>
        </div>
        <div class="stat-card" (click)="pendingFilter = 'empty_catches'">
          <div class="text-2xl font-bold text-purple-400">{{ report.pending_work?.empty_catch_count || 0 }}</div>
          <div class="text-slate-400 text-xs">Empty Catches</div>
        </div>
      </div>

      <div class="card">
        <h3 class="text-white font-semibold mb-4">{{ pendingFilter | uppercase }} Items</h3>
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-slate-400 text-left border-b border-slate-700">
                <th class="pb-2 pr-4">File</th>
                <th class="pb-2 pr-4">Line</th>
                <th class="pb-2 pr-4">Type</th>
                <th class="pb-2">Content</th>
              </tr>
            </thead>
            <tbody>
              @for (item of getPendingItems(); track $index) {
                <tr class="border-b border-slate-800 hover:bg-[#1a2a3d]">
                  <td class="py-2 pr-4 text-slate-300 font-mono text-xs max-w-[300px] truncate">{{ item.file }}</td>
                  <td class="py-2 pr-4 text-blue-400">{{ item.line }}</td>
                  <td class="py-2 pr-4">
                    <span class="px-2 py-0.5 rounded text-xs bg-amber-900 text-amber-300">{{ item.type }}</span>
                  </td>
                  <td class="py-2 text-slate-400 text-xs font-mono max-w-[500px] truncate">{{ item.content }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    }

    <!-- TAB: Code Quality -->
    @if (activeTab === 'quality') {
      <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
        <div class="stat-card">
          <div class="text-2xl font-bold text-blue-400">{{ report.code_quality?.total_java_files || 0 }}</div>
          <div class="text-slate-400 text-xs">Java Files</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold text-cyan-400">{{ report.code_quality?.total_ts_files || 0 }}</div>
          <div class="text-slate-400 text-xs">TypeScript Files</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold text-amber-400">{{ report.code_quality?.large_file_count || 0 }}</div>
          <div class="text-slate-400 text-xs">Large Files (500+)</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold text-red-400">{{ report.code_quality?.large_method_count || 0 }}</div>
          <div class="text-slate-400 text-xs">Large Methods (80+)</div>
        </div>
      </div>

      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div class="card">
          <h3 class="text-white font-semibold mb-4">Largest Files</h3>
          @for (f of report.code_quality?.large_files || []; track f.file) {
            <div class="flex justify-between items-center py-2 border-b border-slate-800">
              <span class="text-slate-300 text-xs font-mono truncate mr-4">{{ f.file }}</span>
              <div class="flex items-center gap-2">
                <span class="text-white font-semibold text-sm">{{ f.lines }} lines</span>
                <span class="px-2 py-0.5 rounded text-xs" [class]="riskClass(f.risk)">{{ f.risk }}</span>
              </div>
            </div>
          }
        </div>

        <div class="card">
          <h3 class="text-white font-semibold mb-4">Largest Methods</h3>
          @for (m of report.code_quality?.large_methods || []; track $index) {
            <div class="flex justify-between items-center py-2 border-b border-slate-800">
              <div class="truncate mr-4">
                <span class="text-blue-400 text-sm">{{ m.method }}()</span>
                <span class="text-slate-500 text-xs ml-2">{{ m.file }}:{{ m.line }}</span>
              </div>
              <span class="text-amber-400 text-sm whitespace-nowrap">{{ m.lines }} lines</span>
            </div>
          }
        </div>
      </div>

      <div class="card mt-4">
        <h3 class="text-white font-semibold mb-4">Missing Tests</h3>
        <div class="text-slate-400 text-sm mb-4">
          Coverage Estimate: <span class="text-white font-semibold">{{ report.code_quality?.test_coverage_estimate }}</span>
        </div>
        <div class="grid grid-cols-1 md:grid-cols-2 gap-2">
          @for (t of report.code_quality?.missing_tests || []; track t.class) {
            <div class="flex items-center gap-2 py-1.5 px-3 bg-[#1a2538] rounded">
              <span class="px-2 py-0.5 text-xs rounded" [class]="t.type === 'Controller' ? 'bg-blue-900 text-blue-300' : 'bg-green-900 text-green-300'">
                {{ t.type }}
              </span>
              <span class="text-slate-300 text-sm">{{ t.class }}</span>
            </div>
          }
        </div>
      </div>
    }

    <!-- TAB: Security -->
    @if (activeTab === 'security') {
      <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
        <div class="stat-card">
          <div class="text-2xl font-bold" [class]="(report.security_review?.critical_issues || 0) > 0 ? 'text-red-500' : 'text-green-400'">
            {{ report.security_review?.critical_issues || 0 }}
          </div>
          <div class="text-slate-400 text-xs">Critical</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold text-orange-400">{{ report.security_review?.high_issues || 0 }}</div>
          <div class="text-slate-400 text-xs">High</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold text-white">{{ report.security_review?.total_security_issues || 0 }}</div>
          <div class="text-slate-400 text-xs">Total Issues</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold" [class]="gradeClass(report.security_review?.security_grade)">
            {{ report.security_review?.security_grade }}
          </div>
          <div class="text-slate-400 text-xs">Security Grade</div>
        </div>
      </div>

      @for (section of securitySections; track section.key) {
        @if ((report.security_review?.[section.key] || []).length > 0) {
          <div class="card mb-4">
            <h3 class="text-white font-semibold mb-4">{{ section.label }}</h3>
            <table class="w-full text-sm">
              <thead>
                <tr class="text-slate-400 text-left border-b border-slate-700">
                  <th class="pb-2 pr-4">File</th>
                  <th class="pb-2 pr-4">Line</th>
                  <th class="pb-2 pr-4">Issue</th>
                  <th class="pb-2 pr-4">Severity</th>
                  <th class="pb-2">Detail</th>
                </tr>
              </thead>
              <tbody>
                @for (item of report.security_review[section.key]; track $index) {
                  <tr class="border-b border-slate-800">
                    <td class="py-2 pr-4 text-slate-300 font-mono text-xs">{{ item.file }}</td>
                    <td class="py-2 pr-4 text-blue-400">{{ item.line || '-' }}</td>
                    <td class="py-2 pr-4 text-white">{{ item.issue }}</td>
                    <td class="py-2 pr-4">
                      <span class="px-2 py-0.5 rounded text-xs" [class]="severityClass(item.severity)">{{ item.severity }}</span>
                    </td>
                    <td class="py-2 text-slate-400 text-xs">{{ item.detail }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      }
    }

    <!-- TAB: Database -->
    @if (activeTab === 'database') {
      <div class="grid grid-cols-2 md:grid-cols-3 gap-4 mb-4">
        <div class="stat-card">
          <div class="text-2xl font-bold text-blue-400">{{ report.database_validation?.total_tables || 0 }}</div>
          <div class="text-slate-400 text-xs">Total Tables</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold text-amber-400">{{ (report.database_validation?.empty_tables || []).length }}</div>
          <div class="text-slate-400 text-xs">Empty Tables</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold text-green-400">{{ report.database_validation?.database_status }}</div>
          <div class="text-slate-400 text-xs">Status</div>
        </div>
      </div>

      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div class="card">
          <h3 class="text-white font-semibold mb-4">Table Sizes</h3>
          @for (t of report.database_validation?.table_sizes || []; track t.table) {
            <div class="flex justify-between items-center py-2 border-b border-slate-800">
              <span class="text-slate-300 text-sm font-mono">{{ t.table }}</span>
              <div class="flex gap-4">
                <span class="text-blue-400 text-sm">{{ t.size }}</span>
                <span class="text-slate-400 text-sm">{{ t.rows }} rows</span>
              </div>
            </div>
          }
        </div>

        <div class="card">
          <h3 class="text-white font-semibold mb-4">All Tables</h3>
          <div class="grid grid-cols-2 gap-1">
            @for (t of report.database_validation?.tables || []; track t) {
              <div class="text-slate-300 text-xs font-mono py-1 px-2 bg-[#1a2538] rounded">{{ t }}</div>
            }
          </div>
        </div>
      </div>

      @if ((report.database_validation?.missing_fk_indexes || []).length > 0) {
        <div class="card mt-4">
          <h3 class="text-amber-400 font-semibold mb-4">Missing FK Indexes</h3>
          @for (idx of report.database_validation.missing_fk_indexes; track $index) {
            <div class="py-2 border-b border-slate-800 text-sm">
              <span class="text-white">{{ idx.table }}</span>.<span class="text-amber-300">{{ idx.column }}</span>
            </div>
          }
        </div>
      }
    }

    <!-- TAB: APIs -->
    @if (activeTab === 'apis') {
      <div class="grid grid-cols-2 md:grid-cols-3 gap-4 mb-4">
        <div class="stat-card">
          <div class="text-2xl font-bold text-blue-400">{{ report.api_validation?.total_endpoints || 0 }}</div>
          <div class="text-slate-400 text-xs">Total Endpoints</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold text-cyan-400">{{ report.api_validation?.controller_count || 0 }}</div>
          <div class="text-slate-400 text-xs">Controllers</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold text-red-400">{{ (report.api_validation?.duplicate_apis || []).length }}</div>
          <div class="text-slate-400 text-xs">Duplicate APIs</div>
        </div>
      </div>

      <div class="card">
        <h3 class="text-white font-semibold mb-4">All Endpoints</h3>
        <div class="mb-3">
          <input type="text" [(ngModel)]="apiFilter" placeholder="Filter endpoints..." class="inp w-full md:w-80">
        </div>
        <div class="overflow-x-auto max-h-[500px] overflow-y-auto">
          <table class="w-full text-sm">
            <thead class="sticky top-0 bg-[#1e2a3b]">
              <tr class="text-slate-400 text-left border-b border-slate-700">
                <th class="pb-2 pr-4">Method</th>
                <th class="pb-2 pr-4">Path</th>
                <th class="pb-2 pr-4">Controller</th>
                <th class="pb-2">Auth</th>
              </tr>
            </thead>
            <tbody>
              @for (ep of filteredEndpoints(); track $index) {
                <tr class="border-b border-slate-800 hover:bg-[#1a2a3d]">
                  <td class="py-1.5 pr-4">
                    <span class="px-2 py-0.5 rounded text-xs font-mono" [class]="methodClass(ep.method)">{{ ep.method }}</span>
                  </td>
                  <td class="py-1.5 pr-4 text-slate-300 font-mono text-xs">{{ ep.path }}</td>
                  <td class="py-1.5 pr-4 text-slate-400 text-xs">{{ ep.controller }}</td>
                  <td class="py-1.5">
                    <span class="text-xs" [class]="ep.has_auth ? 'text-green-400' : 'text-red-400'">
                      {{ ep.has_auth ? 'Yes' : 'No' }}
                    </span>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    }

    <!-- TAB: Features -->
    @if (activeTab === 'features') {
      <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
        <div class="stat-card">
          <div class="text-2xl font-bold text-green-400">{{ report.feature_status?.completed || 0 }}</div>
          <div class="text-slate-400 text-xs">Completed</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold text-blue-400">{{ report.feature_status?.in_progress || 0 }}</div>
          <div class="text-slate-400 text-xs">In Progress</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold text-amber-400">{{ report.feature_status?.partially_implemented || 0 }}</div>
          <div class="text-slate-400 text-xs">Partial</div>
        </div>
        <div class="stat-card">
          <div class="text-2xl font-bold text-red-400">{{ report.feature_status?.not_started || 0 }}</div>
          <div class="text-slate-400 text-xs">Not Started</div>
        </div>
      </div>

      <div class="card">
        <h3 class="text-white font-semibold mb-2">
          Feature Completion ({{ report.feature_status?.completion_percentage || 0 }}%)
        </h3>
        <div class="w-full bg-slate-700 rounded-full h-3 mb-6">
          <div class="bg-green-500 h-3 rounded-full transition-all" [style.width.%]="report.feature_status?.completion_percentage || 0"></div>
        </div>

        <div class="space-y-2">
          @for (feat of report.feature_status?.features || []; track feat.feature) {
            <div class="flex items-center justify-between py-2 px-3 rounded bg-[#1a2538] hover:bg-[#1f3050]">
              <div class="flex items-center gap-3">
                <span class="w-3 h-3 rounded-full" [class]="statusDot(feat.status)"></span>
                <span class="text-white text-sm">{{ feat.feature }}</span>
              </div>
              <div class="flex items-center gap-4">
                <span class="text-slate-400 text-xs">{{ feat.total_lines }} lines</span>
                <span class="text-slate-400 text-xs">{{ feat.files_found }}/{{ feat.files_required }} files</span>
                <span class="px-2 py-0.5 rounded text-xs" [class]="statusClass(feat.status)">{{ feat.status }}</span>
              </div>
            </div>
          }
        </div>
      </div>
    }

    <!-- TAB: Build -->
    @if (activeTab === 'build') {
      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div class="card">
          <h3 class="text-white font-semibold mb-4">Backend</h3>
          <div class="space-y-3">
            <div class="flex justify-between"><span class="text-slate-400">Build Status</span><span class="text-white">{{ report.build_health?.backend_build_status }}</span></div>
            <div class="flex justify-between"><span class="text-slate-400">Spring Boot</span><span [class]="report.build_health?.has_spring_boot ? 'text-green-400' : 'text-red-400'">{{ report.build_health?.has_spring_boot ? 'Yes' : 'No' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-400">Spring Security</span><span [class]="report.build_health?.has_spring_security ? 'text-green-400' : 'text-red-400'">{{ report.build_health?.has_spring_security ? 'Yes' : 'No' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-400">JPA</span><span [class]="report.build_health?.has_jpa ? 'text-green-400' : 'text-red-400'">{{ report.build_health?.has_jpa ? 'Yes' : 'No' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-400">PostgreSQL</span><span [class]="report.build_health?.has_postgresql ? 'text-green-400' : 'text-red-400'">{{ report.build_health?.has_postgresql ? 'Yes' : 'No' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-400">Lombok</span><span [class]="report.build_health?.has_lombok ? 'text-green-400' : 'text-red-400'">{{ report.build_health?.has_lombok ? 'Yes' : 'No' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-400">Swagger</span><span [class]="report.build_health?.has_swagger ? 'text-green-400' : 'text-red-400'">{{ report.build_health?.has_swagger ? 'Yes' : 'No' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-400">Compiled Classes</span><span class="text-white">{{ report.build_health?.compiled_classes || 'N/A' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-400">Test Reports</span><span [class]="report.build_health?.test_reports_exist ? 'text-green-400' : 'text-amber-400'">{{ report.build_health?.test_reports_exist ? 'Exist' : 'None' }}</span></div>
          </div>
        </div>

        <div class="card">
          <h3 class="text-white font-semibold mb-4">Frontend</h3>
          <div class="space-y-3">
            <div class="flex justify-between"><span class="text-slate-400">Dist Build</span><span [class]="report.build_health?.frontend_dist_exists ? 'text-green-400' : 'text-amber-400'">{{ report.build_health?.frontend_dist_exists ? 'Built' : 'Not Built' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-400">Angular</span><span [class]="report.build_health?.has_angular ? 'text-green-400' : 'text-red-400'">{{ report.build_health?.has_angular ? 'Yes' : 'No' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-400">Tailwind CSS</span><span [class]="report.build_health?.has_tailwind ? 'text-green-400' : 'text-red-400'">{{ report.build_health?.has_tailwind ? 'Yes' : 'No' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-400">TypeScript</span><span [class]="report.build_health?.has_typescript ? 'text-green-400' : 'text-red-400'">{{ report.build_health?.has_typescript ? 'Yes' : 'No' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-400">Build Timestamp</span><span class="text-white text-xs">{{ report.build_health?.build_timestamp }}</span></div>
          </div>
        </div>
      </div>
    }

    <!-- TAB: Deployment -->
    @if (activeTab === 'deploy') {
      <div class="card mb-4">
        <div class="text-center py-8">
          <div class="text-3xl font-bold mb-2" [class]="deployStatusColor(report.deployment_readiness?.status)">
            {{ report.deployment_readiness?.status }}
          </div>
          <div class="flex justify-center gap-8 mt-4">
            <div><span class="text-3xl font-bold text-red-400">{{ report.deployment_readiness?.blocker_count || 0 }}</span><div class="text-slate-400 text-xs mt-1">Blockers</div></div>
            <div><span class="text-3xl font-bold text-amber-400">{{ report.deployment_readiness?.warning_count || 0 }}</span><div class="text-slate-400 text-xs mt-1">Warnings</div></div>
          </div>
        </div>
      </div>

      @if ((report.deployment_readiness?.blockers || []).length > 0) {
        <div class="card mb-4">
          <h3 class="text-red-400 font-semibold mb-4">Blockers</h3>
          @for (b of report.deployment_readiness.blockers; track $index) {
            <div class="flex items-start gap-3 py-2 border-b border-slate-800">
              <span class="px-2 py-0.5 rounded text-xs bg-red-900 text-red-300 mt-0.5">{{ b.severity }}</span>
              <div>
                <div class="text-white text-sm">{{ b.type }}</div>
                <div class="text-slate-400 text-xs">{{ b.message }}</div>
              </div>
            </div>
          }
        </div>
      }

      @if ((report.deployment_readiness?.warnings || []).length > 0) {
        <div class="card">
          <h3 class="text-amber-400 font-semibold mb-4">Warnings</h3>
          @for (w of report.deployment_readiness.warnings; track $index) {
            <div class="flex items-start gap-3 py-2 border-b border-slate-800">
              <span class="px-2 py-0.5 rounded text-xs bg-amber-900 text-amber-300 mt-0.5">{{ w.severity }}</span>
              <div>
                <div class="text-white text-sm">{{ w.type }}</div>
                <div class="text-slate-400 text-xs">{{ w.message }}</div>
              </div>
            </div>
          }
        </div>
      }
    }

    <!-- TAB: Recommendations -->
    @if (activeTab === 'recs') {
      <div class="space-y-3">
        @for (rec of report.recommendations || []; track $index) {
          <div class="card flex items-start gap-4">
            <span class="px-2 py-0.5 rounded text-xs mt-0.5 whitespace-nowrap" [class]="severityClass(rec.priority)">{{ rec.priority }}</span>
            <div>
              <div class="text-white font-semibold text-sm">{{ rec.title }}</div>
              <div class="text-slate-400 text-xs mt-1">Scope: {{ rec.scope }}</div>
              <div class="text-slate-300 text-sm mt-2">{{ rec.detail }}</div>
            </div>
          </div>
        }
        @if ((report.recommendations || []).length === 0) {
          <div class="card text-center py-8">
            <div class="text-green-400 text-xl mb-2">All Clear!</div>
            <div class="text-slate-400 text-sm">No recommendations at this time.</div>
          </div>
        }
      </div>
    }
  }

  <!-- No report loaded -->
  @if (!report && !loading) {
    <div class="card text-center py-20">
      <div class="text-slate-500 text-6xl mb-4">&#128269;</div>
      <div class="text-white text-xl mb-2">No Report Generated</div>
      <p class="text-slate-400 mb-6">Click "Run Full Scan" to analyze your project health</p>
      <button (click)="loadFullReport()" class="btn-primary">Run Full Scan</button>
    </div>
  }

  <!-- Toast -->
  @if (toast) {
    <div class="fixed bottom-6 right-6 px-4 py-3 rounded-lg shadow-lg z-50 text-sm"
      [class]="toast.type === 'error' ? 'bg-red-900 text-red-200' : 'bg-green-900 text-green-200'">
      {{ toast.message }}
    </div>
  }
</div>
  `,
  styles: [`
    .card { @apply bg-[#1e2a3b] rounded-xl p-5 border border-[#2d3f55]; }
    .stat-box { @apply bg-[#1a2538] rounded-lg p-4 text-center; }
    .stat-card { @apply bg-[#1e2a3b] rounded-xl p-5 border border-[#2d3f55] text-center cursor-pointer hover:border-blue-500 transition-colors; }
    .btn-primary { @apply px-5 py-2.5 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-sm font-medium disabled:opacity-50 transition-colors; }
    .btn-secondary { @apply px-5 py-2.5 bg-[#1e2a3b] hover:bg-[#2a3a50] text-slate-300 rounded-lg text-sm border border-[#2d3f55] disabled:opacity-50 transition-colors; }
    .inp { @apply bg-[#0d1829] border border-[#2d3f55] rounded-lg px-3 py-2 text-white text-sm focus:border-blue-500 focus:outline-none; }
  `]
})
export class ProjectHealthComponent implements OnInit {
  report: any = null;
  loading = false;
  activeTab = 'git';
  pendingFilter = 'todos';
  apiFilter = '';
  toast: { type: string; message: string } | null = null;

  tabs = [
    { key: 'git', label: 'Git Status', badge: 'git_status.total_files_changed' },
    { key: 'pending', label: 'Pending Work', badge: 'pending_work.total_pending_issues' },
    { key: 'quality', label: 'Code Quality', badge: 'code_quality.large_method_count' },
    { key: 'security', label: 'Security', badge: 'security_review.total_security_issues' },
    { key: 'database', label: 'Database', badge: '' },
    { key: 'apis', label: 'APIs', badge: '' },
    { key: 'features', label: 'Features', badge: '' },
    { key: 'build', label: 'Build', badge: '' },
    { key: 'deploy', label: 'Deploy', badge: '' },
    { key: 'recs', label: 'Recommendations', badge: 'recommendations.length' },
  ];

  securitySections = [
    { key: 'sql_injection_risks', label: 'SQL Injection Risks' },
    { key: 'hardcoded_secrets', label: 'Hardcoded Secrets' },
    { key: 'missing_auth_checks', label: 'Missing Auth Checks' },
    { key: 'sensitive_data_logging', label: 'Sensitive Data Logging' },
  ];

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.loadFullReport();
  }

  loadFullReport() {
    this.loading = true;
    this.api.get<any>('/api/admin/project-health/full-report').subscribe({
      next: (res: any) => {
        this.report = res?.data || res;
        this.loading = false;
        this.showToast('success', 'Project scan completed');
      },
      error: (err: any) => {
        this.loading = false;
        this.showToast('error', 'Failed to load report: ' + (err?.error?.message || err.message));
      }
    });
  }

  getCount(path: string): number {
    if (!this.report || !path) return 0;
    const parts = path.split('.');
    let val: any = this.report;
    for (const p of parts) {
      val = val?.[p];
    }
    return typeof val === 'number' ? val : 0;
  }

  scoreColor(score: number): string {
    if (score >= 80) return 'text-green-400';
    if (score >= 60) return 'text-amber-400';
    return 'text-red-400';
  }

  gradeClass(grade: string): string {
    switch (grade) {
      case 'A': return 'bg-green-900 text-green-300';
      case 'B': return 'bg-blue-900 text-blue-300';
      case 'C': return 'bg-amber-900 text-amber-300';
      case 'D': return 'bg-orange-900 text-orange-300';
      default: return 'bg-red-900 text-red-300';
    }
  }

  riskClass(risk: string): string {
    switch (risk) {
      case 'Critical': return 'bg-red-900 text-red-300';
      case 'High': return 'bg-orange-900 text-orange-300';
      case 'Medium': return 'bg-amber-900 text-amber-300';
      default: return 'bg-green-900 text-green-300';
    }
  }

  severityClass(sev: string): string {
    switch (sev) {
      case 'Critical': return 'bg-red-900 text-red-300';
      case 'High': return 'bg-orange-900 text-orange-300';
      case 'Medium': return 'bg-amber-900 text-amber-300';
      default: return 'bg-green-900 text-green-300';
    }
  }

  statusClass(status: string): string {
    switch (status) {
      case 'Completed': return 'bg-green-900 text-green-300';
      case 'In Progress': return 'bg-blue-900 text-blue-300';
      case 'Partially Implemented': return 'bg-amber-900 text-amber-300';
      default: return 'bg-red-900 text-red-300';
    }
  }

  statusDot(status: string): string {
    switch (status) {
      case 'Completed': return 'bg-green-400';
      case 'In Progress': return 'bg-blue-400';
      case 'Partially Implemented': return 'bg-amber-400';
      default: return 'bg-red-400';
    }
  }

  methodClass(method: string): string {
    switch (method) {
      case 'GET': return 'bg-green-900 text-green-300';
      case 'POST': return 'bg-blue-900 text-blue-300';
      case 'PUT': return 'bg-amber-900 text-amber-300';
      case 'DELETE': return 'bg-red-900 text-red-300';
      default: return 'bg-purple-900 text-purple-300';
    }
  }

  deployStatusColor(status: string): string {
    if (status === 'Ready for Production') return 'text-green-400';
    if (status === 'Needs Review') return 'text-amber-400';
    return 'text-red-400';
  }

  getPendingItems(): any[] {
    return this.report?.pending_work?.[this.pendingFilter] || [];
  }

  filteredEndpoints(): any[] {
    const eps = this.report?.api_validation?.endpoints || [];
    if (!this.apiFilter) return eps;
    const f = this.apiFilter.toLowerCase();
    return eps.filter((ep: any) => ep.path.toLowerCase().includes(f) || ep.controller.toLowerCase().includes(f) || ep.method.toLowerCase().includes(f));
  }

  downloadReport() {
    if (!this.report) return;
    const json = JSON.stringify(this.report, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'project-health-report.json';
    a.click();
    URL.revokeObjectURL(url);
    this.showToast('success', 'Report downloaded');
  }

  private showToast(type: string, message: string) {
    this.toast = { type, message };
    setTimeout(() => this.toast = null, 3000);
  }
}
