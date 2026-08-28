import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ApiService } from '../../core/services/api.service';
import { environment } from '../../../environments/environment';

type WarehouseRow = {
  dataType: string;
  sources: string[];
  acceptedFiles: string;
};

type BackendTable = {
  key: string;
  label: string;
  tableName: string;
  description: string;
};

type StockMasterPreviewRow = {
  id: number;
  exchange: string;
  symbol: string;
  companyName: string;
  sector: string;
  industry: string;
  segment: string;
  status: string;
  source: string;
  series: string;
  isin: string;
  actionType: string;
  remarks: string;
  dateTime: string;
};

type DependentSection = {
  section: string;
  route: string;
  tables: string;
  usage: string;
};

@Component({
  selector: 'app-data-management',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host { display:block; min-height:100vh; background:#0b1e3d; color:#e2e8f0; }
    .page { padding:20px; max-width:100%; }
    .title { font-size:22px; font-weight:800; color:#fff; margin-bottom:4px; }
    .subtle { color:#94a3b8; font-size:12px; }
    .panel { background:#10233d; border:1px solid #223a56; border-radius:8px; overflow:hidden; }
    .table-wrap { overflow-x:auto; }
    table { width:100%; border-collapse:collapse; min-width:1240px; }
    th { background:#173456; color:#fff; font-size:13px; text-align:left; padding:10px; border-bottom:1px solid #2a4668; }
    td { padding:10px; border-bottom:1px solid #203650; font-size:12px; vertical-align:middle; }
    .warehouse-row { height:64px; }
    .warehouse-row td { border-bottom:0; vertical-align:middle; }
    .accepted-detail-row td { padding:0 10px 12px; border-bottom:1px solid #203650; }
    tr:hover td { background:#132942; }
    .select, .input { background:#0b1829; border:1px solid #2a4668; border-radius:6px; color:#e2e8f0; padding:7px 8px; font-size:12px; width:100%; }
    .btn { border:0; border-radius:6px; padding:8px 12px; font-size:12px; font-weight:700; cursor:pointer; white-space:nowrap; }
    .btn-primary { background:#1d6adb; color:#fff; }
    .btn-primary:hover { background:#1a5fc7; }
    .btn-secondary { background:#1f334c; color:#cbd5e1; border:1px solid #355274; }
    .btn-danger { background:#dc2626; color:#fff; }
    .btn:disabled { opacity:.45; cursor:not-allowed; }
    .chip { display:inline-flex; align-items:center; border-radius:999px; border:1px solid #355274; padding:3px 8px; color:#cbd5e1; font-size:11px; margin:0 4px 4px 0; }
    .note { color:#8fb4d8; font-size:11px; line-height:1.4; max-width:310px; }
    .data-type-cell { min-height:44px; display:flex; align-items:center; }
    .data-type-name { font-weight:800; color:#fff; font-size:14px; line-height:1.2; }
    .accepted-file { color:#8fb4d8; font-size:11px; line-height:1.25; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; max-width:720px; }
    .control-cell { vertical-align:middle; }
    .row-status { display:inline-flex; align-items:center; min-width:86px; justify-content:center; border-radius:999px; border:1px solid #355274; padding:4px 8px; font-size:11px; font-weight:800; color:#cbd5e1; text-transform:capitalize; }
    .row-status.running { color:#bfdbfe; border-color:#2563eb; background:rgba(37,99,235,.16); }
    .row-status.completed { color:#bbf7d0; border-color:#16a34a; background:rgba(22,163,74,.16); }
    .row-status.failed { color:#fecaca; border-color:#dc2626; background:rgba(220,38,38,.16); }
    .row-status.stopped { color:#fde68a; border-color:#d97706; background:rgba(217,119,6,.16); }
    .file { color:#cbd5e1; font-size:11px; max-width:220px; }
    .status-backdrop { position:fixed; inset:0; background:rgba(0,0,0,.58); display:flex; align-items:flex-start; justify-content:center; z-index:10000; padding:24px 16px; overflow:auto; }
    .status-dialog { width:min(680px, 100%); max-height:calc(100vh - 48px); overflow:auto; background:#10233d; border:1px solid #2a4668; border-radius:8px; padding:20px; box-shadow:0 18px 50px rgba(0,0,0,.4); }
    .backfill-sticky { position:sticky; top:0; z-index:20; margin-bottom:14px; background:#10233d; border:1px solid #2a4668; border-left:4px solid #60a5fa; border-radius:8px; padding:12px 14px; }
    .mini-progress { height:8px; background:#203650; border-radius:999px; overflow:hidden; margin-top:8px; }
    .status-title { color:#fff; font-weight:800; font-size:16px; margin-bottom:8px; }
    .status-line { display:flex; justify-content:space-between; gap:14px; padding:8px 0; border-bottom:1px solid #203650; font-size:12px; }
    .spinner { width:18px; height:18px; border:2px solid rgba(255,255,255,.3); border-top-color:#60a5fa; border-radius:50%; animation:spin .8s linear infinite; }
    .progress-grid { display:grid; grid-template-columns:112px 1fr; gap:12px; align-items:center; margin:10px 0; }
    /* Filled by a conic-gradient bound to the live percentage. It used to be a plain
       border with one edge coloured, which looked like a ring but could never move. */
    .progress-ring { width:96px; height:96px; border-radius:50%; display:grid; place-items:center; transition:background .4s ease; }
    .progress-ring-inner { width:78px; height:78px; border-radius:50%; background:#0b1a2d; display:grid; place-items:center; }
    .progress-bar { height:18px; border-radius:999px; background:#203650; overflow:hidden; }
    .progress-fill { height:100%; background:#60a5fa; }
    .progress-meta { display:grid; grid-template-columns:1fr 1fr; gap:8px; margin-top:10px; }
    .progress-meta-item,.step-item { background:#0b1a2d; border:1px solid #203650; border-radius:6px; padding:8px; }
    .step-track { display:grid; grid-template-columns:repeat(5,1fr); gap:8px; margin-top:12px; }
    .step-item.done { border-color:#22c55e; }
    .step-item.running { border-color:#60a5fa; }
    .step-item.failed { border-color:#ef4444; }
    .step-label { color:#cbd5e1; font-size:10px; font-weight:800; }
    .pager { display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; padding:10px 14px; border-top:1px solid #203650; }
    .pager-controls { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
    .dash-card { background:#10233d; border:1px solid #223a56; border-radius:8px; padding:12px; text-align:center; }
    .dash-val { font-size:18px; font-weight:900; line-height:1.2; }
    .dash-label { color:#94a3b8; font-size:10px; margin-top:4px; }
    .text-blue { color:#60a5fa; } .text-green { color:#34d399; } .text-red { color:#f87171; }
    .text-cyan { color:#22d3ee; } .text-amber { color:#fbbf24; } .text-purple { color:#a78bfa; }
    .text-slate { color:#94a3b8; }
    .tab-btn { background:#10233d; border:1px solid #223a56; color:#94a3b8; padding:10px 20px; font-size:13px; font-weight:700; cursor:pointer; border-radius:6px 6px 0 0; border-bottom:0; }
    .tab-btn.active { background:#173456; color:#fff; border-color:#2a4668; }
    .tab-btn:hover:not(.active) { background:#132942; color:#cbd5e1; }
    .smart-step { display:flex; align-items:flex-start; gap:8px; padding:6px 0; border-bottom:1px solid #203650; font-size:12px; }
    .smart-step-icon { width:18px; height:18px; flex-shrink:0; display:flex; align-items:center; justify-content:center; font-size:13px; }
    .error-code-badge { display:inline-block; background:rgba(245,158,11,.15); border:1px solid #78350f; border-radius:4px; padding:2px 8px; color:#fbbf24; font-family:monospace; font-size:11px; font-weight:700; }
    .resolution-box { background:#0b1a2d; border:1px solid #203650; border-radius:6px; padding:10px 12px; margin-top:8px; }
    .resolution-title { color:#60a5fa; font-size:11px; font-weight:700; margin-bottom:4px; }
    .resolution-text { color:#cbd5e1; font-size:11px; line-height:1.5; }
    .sm-stats { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:10px; padding:14px; }
    .sm-stat { background:#0b1a2d; border:1px solid #203650; border-radius:8px; padding:12px; text-align:center; }
    .sm-stat-val { font-size:20px; font-weight:900; line-height:1.2; }
    .sm-stat-label { color:#94a3b8; font-size:10px; margin-top:4px; text-transform:uppercase; letter-spacing:.5px; }
    .sm-actions { display:flex; gap:8px; flex-wrap:wrap; padding:0 14px 14px; }
    .sm-filters { display:flex; gap:8px; flex-wrap:wrap; align-items:center; padding:10px 14px; border-bottom:1px solid #203650; background:#0d1f38; }
    .sm-filters .input, .sm-filters .select { width:auto; min-width:140px; padding:6px 8px; font-size:11px; }
    .badge { display:inline-flex; align-items:center; border-radius:999px; padding:3px 10px; font-size:10px; font-weight:700; text-transform:uppercase; letter-spacing:.3px; }
    .badge-green { color:#bbf7d0; background:rgba(22,163,74,.18); border:1px solid #16a34a; }
    .badge-yellow { color:#fde68a; background:rgba(217,119,6,.14); border:1px solid #d97706; }
    .badge-red { color:#fecaca; background:rgba(220,38,38,.18); border:1px solid #dc2626; }
    .badge-orange { color:#fed7aa; background:rgba(234,88,12,.14); border:1px solid #ea580c; }
    .badge-blue { color:#bfdbfe; background:rgba(37,99,235,.16); border:1px solid #2563eb; }
    .badge-slate { color:#cbd5e1; background:rgba(100,116,139,.14); border:1px solid #64748b; }
    .sm-empty { text-align:center; padding:40px 20px; }
    .sm-empty-icon { font-size:40px; margin-bottom:12px; opacity:.4; }
    .sm-empty-title { color:#fff; font-size:15px; font-weight:700; margin-bottom:6px; }
    .sm-empty-text { color:#94a3b8; font-size:12px; line-height:1.5; max-width:420px; margin:0 auto; }
    .sm-skeleton { height:14px; background:#1a2e48; border-radius:4px; animation:pulse 1.5s ease-in-out infinite; }
    .sm-log-row { display:grid; grid-template-columns:90px 1fr 80px 70px 70px 70px 70px 70px 1fr; gap:8px; padding:8px 14px; border-bottom:1px solid #203650; font-size:11px; align-items:center; }
    .sm-log-header { background:#0d1f38; font-weight:700; color:#94a3b8; font-size:10px; text-transform:uppercase; }
    .sm-upload-preview { max-height:300px; overflow-y:auto; }
    .sm-success-msg { background:rgba(22,163,74,.12); border:1px solid #16a34a40; border-radius:8px; padding:12px 14px; margin:10px 14px; }
    .sm-error-msg { background:rgba(220,38,38,.12); border:1px solid #dc262640; border-radius:8px; padding:12px 14px; margin:10px 14px; }
    @keyframes pulse { 0%,100% { opacity:.4; } 50% { opacity:.8; } }
    @keyframes spin { to { transform:rotate(360deg); } }
  `],
  template: `
<div class="page">
  <div style="display:flex; align-items:flex-end; justify-content:space-between; gap:12px; margin-bottom:16px; flex-wrap:wrap;">
    <div>
      <h1 class="title">Data Warehouse</h1>
      <p class="subtle">Centralized Data Collection Engine — Manage all Technical, Fundamental, Market and Historical datasets. Supports automatic fetching from NSE, BSE, Dhan, Broker APIs and Manual Upload.</p>
    </div>
    <div style="display:flex; gap:8px; align-items:center;">
      <button class="btn btn-secondary" (click)="showFetchLogs = !showFetchLogs">{{ showFetchLogs ? 'Hide' : 'View' }} Fetch Logs</button>
    </div>
  </div>

  <!-- The sticky "Dhan Historical OHLCV Backfill" status banner was removed: every control it carried (Refresh Status, View Failed / Skipped, Retry, Stop) already exists in the main backfill panel below, so it duplicated buttons and screen space. Its two most-used buttons now sit in the Skipped & Failed Stocks panel, which is where you go when a fetch has gone wrong. -->

  <!-- Dashboard Cards -->
  <div style="display:grid; grid-template-columns:repeat(auto-fill, minmax(160px, 1fr)); gap:10px; margin-bottom:16px;">
    <div class="dash-card"><div class="dash-val text-blue">{{ dashStats?.todayFetchCount || 0 }}</div><div class="dash-label">Today's Fetches</div></div>
    <div class="dash-card"><div class="dash-val text-green">{{ dashStats?.completedJobs || 0 }}</div><div class="dash-label">Completed Jobs</div></div>
    <div class="dash-card"><div class="dash-val text-red">{{ dashStats?.failedJobs || 0 }}</div><div class="dash-label">Failed Jobs</div></div>
    <div class="dash-card"><div class="dash-val text-cyan">{{ (dashStats?.totalRows || 0) | number }}</div><div class="dash-label">Total Rows Imported</div></div>
    <div class="dash-card"><div class="dash-val text-amber">{{ dashStats?.marketDataCount || 0 | number }}</div><div class="dash-label">Market Data Rows</div></div>
    <div class="dash-card"><div class="dash-val text-purple">{{ dashStats?.databaseSize || 'N/A' }}</div><div class="dash-label">Database Size</div></div>
    <div class="dash-card"><div class="dash-val text-slate" style="font-size:11px;">{{ dashStats?.lastSync || 'Never' }}</div><div class="dash-label">Last Sync</div></div>
  </div>

  <!-- Live Fetch Progress -->
  @if (fetchProgress?.status === 'running') {
    <div class="panel" style="margin-bottom:16px; border-left:4px solid #60a5fa;">
      <div style="padding:14px;">
        <div style="display:flex; justify-content:space-between; align-items:center; gap:12px; flex-wrap:wrap;">
          <div>
            <div style="display:flex; align-items:center; gap:8px;">
              <div class="spinner"></div>
              <span style="font-weight:800; color:#fff; font-size:14px;">Parallel Fetch Engine Running</span>
            </div>
            <div class="subtle" style="margin-top:4px;">{{ fetchProgress.message }}</div>
          </div>
          <div style="display:flex; gap:12px; align-items:center;">
            <span class="text-blue" style="font-weight:900; font-size:20px;">{{ fetchProgress.percentage || 0 }}%</span>
            <span class="subtle">{{ fetchProgress.processed || 0 }}/{{ fetchProgress.total || 0 }} dates</span>
          </div>
        </div>
        <div class="progress-bar" style="margin-top:10px;">
          <div class="progress-fill" [style.width.%]="fetchProgress.percentage || 0" style="transition:width .5s;"></div>
        </div>
        <div style="display:grid; grid-template-columns:repeat(auto-fill, minmax(140px, 1fr)); gap:8px; margin-top:10px;">
          <div class="dash-card"><div class="dash-val text-blue" style="font-size:14px;">{{ fetchProgress.avgDownloadMs || 0 }}ms</div><div class="dash-label">Avg Download</div></div>
          <div class="dash-card"><div class="dash-val text-green" style="font-size:14px;">{{ formatBytes(fetchProgress.totalBytesDownloaded || 0) }}</div><div class="dash-label">Downloaded</div></div>
          <div class="dash-card"><div class="dash-val text-amber" style="font-size:14px;">{{ fetchProgress.dataType || '-' }}</div><div class="dash-label">Data Type</div></div>
          <div class="dash-card"><div class="dash-val text-cyan" style="font-size:14px;">{{ fetchProgress.source || '-' }}</div><div class="dash-label">Source</div></div>
        </div>
      </div>
    </div>
  }

  <!-- Engine Performance Stats -->
  @if (dashStats?.fetchThreadCount) {
    <div style="display:grid; grid-template-columns:repeat(auto-fill, minmax(140px, 1fr)); gap:8px; margin-bottom:16px;">
      <div class="dash-card"><div class="dash-val text-blue" style="font-size:14px;">{{ dashStats.fetchThreadCount }}</div><div class="dash-label">Parallel Threads</div></div>
      <div class="dash-card"><div class="dash-val text-green" style="font-size:14px;">{{ dashStats.avgDownloadMs || 0 }}ms</div><div class="dash-label">Avg Download</div></div>
      <div class="dash-card"><div class="dash-val text-cyan" style="font-size:14px;">{{ dashStats.avgImportMs || 0 }}ms</div><div class="dash-label">Avg Import</div></div>
      <div class="dash-card"><div class="dash-val text-amber" style="font-size:14px;">{{ dashStats.cacheSize || 0 }}</div><div class="dash-label">Cache Entries</div></div>
      <div class="dash-card"><div class="dash-val text-purple" style="font-size:14px;">{{ dashStats.cacheHitRate || '0%' }}</div><div class="dash-label">Cache Hit Rate</div></div>
      <div class="dash-card"><div class="dash-val" style="font-size:14px;" [class]="dashStats.skipExisting ? 'text-green' : 'text-red'">{{ dashStats.skipExisting ? 'ON' : 'OFF' }}</div><div class="dash-label">Skip Existing</div></div>
      <div class="dash-card">
        <button class="btn btn-secondary" (click)="clearFetchCache()" style="font-size:10px; padding:4px 8px;">Clear Cache</button>
        <div class="dash-label" style="margin-top:4px;">API Cache</div>
      </div>
    </div>
  }

  <!-- The "Processing Progress Dashboard" (last 5 fetch logs) was removed on request. Its data is still available: per-job progress is shown in the backfill panel, and the full history lives in the fetch log table further down this screen. loadRecentFetchLogs() is kept because other views call it. -->

  <!-- Fetch Logs Panel -->
  @if (showFetchLogs) {
    <div class="panel" style="margin-bottom:16px;">
      <div style="padding:12px 14px; border-bottom:1px solid #203650; display:flex; justify-content:space-between; align-items:center;">
        <div style="font-weight:800; color:#fff; font-size:14px;">Fetch History</div>
        <button class="btn btn-secondary" (click)="loadFetchLogs()" style="padding:4px 10px;">Refresh</button>
      </div>
      <div class="table-wrap">
        <table style="min-width:900px;">
          <thead>
            <tr>
              <th>Date/Time</th><th>Data Type</th><th>Source</th><th>Date Range</th>
              <th>Status</th><th>Rows</th><th>Skipped</th><th>Duration</th><th>Error Code</th><th>Message</th>
            </tr>
          </thead>
          <tbody>
            @for (lg of fetchLogs; track lg.id) {
              <tr>
                <td style="white-space:nowrap;">{{ lg.createdAt }}</td>
                <td>{{ lg.dataType }}</td>
                <td>{{ lg.source }}</td>
                <td style="white-space:nowrap;">{{ lg.fromDate }} → {{ lg.toDate }}</td>
                <td><span class="row-status" [class]="lg.status">{{ lg.status }}</span></td>
                <td style="text-align:right;">{{ lg.rowsImported }}</td>
                <td style="text-align:right;">{{ lg.skippedCount }}</td>
                <td>{{ lg.durationMs > 1000 ? (lg.durationMs / 1000 | number:'1.1-1') + 's' : lg.durationMs + 'ms' }}</td>
                <td><span style="color:#f59e0b; font-family:monospace;">{{ lg.errorCode || '-' }}</span></td>
                <td style="max-width:250px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">{{ lg.message }}</td>
              </tr>
            }
            @if (fetchLogs.length === 0) {
              <tr><td colspan="10" style="text-align:center; padding:20px; color:#64748b;">No fetch logs yet</td></tr>
            }
          </tbody>
        </table>
      </div>
    </div>
  }

  <!-- Tab Navigation -->
  <div style="display:flex; gap:2px; margin-bottom:14px;">
    <button class="tab-btn" [class.active]="warehouseTab === 'technical'" (click)="warehouseTab = 'technical'">Technical Data Fetch</button>
    <button class="tab-btn" [class.active]="warehouseTab === 'fundamental'" (click)="warehouseTab = 'fundamental'">Fundamental Data Fetch</button>
  </div>

  <div class="panel">
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th style="width:210px;">Data Type</th>
            <th style="width:210px;">Source</th>
            <th style="width:150px;">From Date</th>
            <th style="width:150px;">To Date</th>
            <th style="width:130px;">Fetch Data</th>
            <th style="width:150px;">Status</th>
            <th style="width:150px;">Format Download</th>
            <th style="width:260px;">Upload Data As Per Format</th>
          </tr>
        </thead>
        <tbody>
          @for (row of filteredRows(); track row.dataType) {
            <tr class="warehouse-row">
              <td>
                <div class="data-type-cell">
                  <div class="data-type-name">{{ row.dataType }}</div>
                </div>
              </td>
              <td class="control-cell">
                <select class="select" [(ngModel)]="selectedSources[row.dataType]" [disabled]="isActivityRunning()">
                  @for (src of row.sources; track src) {
                    <option [ngValue]="src">{{ src }}</option>
                  }
                </select>
                @if (isBroker(row)) {
                  <div style="margin-top:8px;">
                    <select class="select" [(ngModel)]="selectedBrokers[row.dataType]" [disabled]="isActivityRunning() || activeBrokers.length === 0">
                      @if (activeBrokers.length === 0) {
                        <option [ngValue]="null">No verified broker connected</option>
                      } @else {
                        @for (broker of activeBrokers; track broker.id) {
                          <option [ngValue]="broker.id">{{ broker.broker_label }} - {{ broker.client_id }}</option>
                        }
                      }
                    </select>
                    <div class="note" style="margin-top:6px;">
                      Connect and verify broker from Broker menu first.
                    </div>
                  </div>
                }
              </td>
              <td class="control-cell">
                @if (requiresDate(row)) {
                  <input class="input" type="date" [(ngModel)]="dateForms[row.dataType].fromDate" [disabled]="isActivityRunning()">
                } @else {
                  <span class="subtle">Not required</span>
                }
              </td>
              <td class="control-cell">
                @if (requiresDate(row)) {
                  <input class="input" type="date" [(ngModel)]="dateForms[row.dataType].toDate" [disabled]="isActivityRunning()">
                } @else {
                  <span class="subtle">Not required</span>
                }
              </td>
              <td class="control-cell">
                @if (!isManual(row)) {
                  <button class="btn btn-primary" (click)="fetchData(row)" [disabled]="isActivityRunning()">Fetch</button>
                } @else {
                  <span class="subtle">Manual only</span>
                }
              </td>
              <td class="control-cell">
                <span class="row-status" [ngClass]="rowStatus(row).toLowerCase()">{{ rowStatus(row) }}</span>
                @if (activeActivity?.dataType === row.dataType && activeActivity?.source) {
                  <div class="subtle" style="margin-top:5px;">{{ activeActivity.source }}</div>
                }
              </td>
              <td class="control-cell">
                @if (isManual(row)) {
                  <button class="btn btn-secondary" (click)="downloadFormat(row)" [disabled]="isActivityRunning()">Download</button>
                } @else {
                  <span class="subtle">Source format</span>
                }
              </td>
              <td class="control-cell">
                @if (isManual(row)) {
                  <input class="file" type="file" multiple
                    accept=".csv,.txt,.zip,.xlsx,.xls,.json,.dat"
                    [disabled]="isActivityRunning()"
                    (change)="manualUpload(row, $event)">
                } @else {
                  <span class="subtle">Select Manual source to upload</span>
                }
              </td>
            </tr>
            <tr class="accepted-detail-row">
              <td colspan="4">
                <div class="accepted-file" [title]="row.acceptedFiles">
                  Accepted file: {{ row.acceptedFiles }}
                </div>
              </td>
              <td colspan="4"></td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  </div>

  <div class="panel" style="margin-top:14px; padding:14px;">
    <div style="display:flex; justify-content:space-between; gap:12px; flex-wrap:wrap; align-items:flex-start;">
      <div>
        <div style="font-weight:800; color:#fff; margin-bottom:4px;">Dhan Historical OHLCV Backfill</div>
        <p class="subtle" style="line-height:1.6; max-width:940px;">
          This backfill uses the saved public Dhan Stock List to look up Dhan security IDs before fetching candles.
          Select Dhan exchange, segment, and instrument category, then fetch historical candles for all matching symbols.
        </p>
      </div>
      <button class="btn btn-secondary" (click)="refreshDhanInstruments()" [disabled]="isActivityRunning()">Fetch Public Stock List</button>
    </div>
    <div style="display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:10px; margin-top:12px;">
      <div>
        <div style="font-size:11px; color:#94a3b8; margin-bottom:3px;">Type of Data</div>
        <select class="select" [(ngModel)]="dhanBackfillDataType" (ngModelChange)="onDhanDataTypeChange()">
          @for (dt of enabledDhanDataTypes(); track dt.value) {
            <option [value]="dt.value">{{ dt.value }}</option>
          }
        </select>
        @if (dhanDataTypeMessage) {
          <div style="margin-top:4px; color:#fbbf24; font-size:10px;">{{ dhanDataTypeMessage }}</div>
        }
      </div>
      <div>
        <label class="subtle">Mode</label>
        <select class="select" [(ngModel)]="dhanBackfill.mode" [disabled]="isActivityRunning()">
          <option value="DAILY">Daily OHLCV</option>
          <option value="INTRADAY">Intraday Candles</option>
        </select>
      </div>
      <div>
        <label class="subtle">Broker Account</label>
        <select class="select" [(ngModel)]="dhanBackfill.brokerCredentialId" [disabled]="isActivityRunning() || activeDhanBrokers.length === 0">
          @if (activeDhanBrokers.length === 0) {
            <option [ngValue]="null">No verified Dhan broker connected</option>
          } @else {
            @for (broker of activeDhanBrokers; track broker.id) {
              <option [ngValue]="broker.id">{{ broker.broker_label }} - {{ broker.client_id }}</option>
            }
          }
        </select>
      </div>
      <div>
        <label class="subtle">Interval</label>
        <select class="select" [(ngModel)]="dhanBackfill.intervalMinutes" [disabled]="isActivityRunning() || dhanBackfill.mode === 'DAILY'">
          <option [ngValue]="1">1 minute</option>
          <option [ngValue]="5">5 minute</option>
          <option [ngValue]="15">15 minute</option>
          <option [ngValue]="25">25 minute</option>
          <option [ngValue]="60">60 minute</option>
        </select>
      </div>
      <div>
        <label class="subtle">From Date</label>
        <input class="input" type="date" [(ngModel)]="dhanBackfill.fromDate" [disabled]="isActivityRunning()">
      </div>
      <div>
        <label class="subtle">To Date</label>
        <input class="input" type="date" [(ngModel)]="dhanBackfill.toDate" [disabled]="isActivityRunning()">
      </div>
      <div>
        <label class="subtle">Exchanges</label>
        <select class="select" [(ngModel)]="dhanBackfill.exchanges" [disabled]="isActivityRunning()">
          @for (option of dhanExchangeOptions; track option.value) {
            <option [ngValue]="option.value">{{ option.label }}</option>
          }
        </select>
      </div>
      <div>
        <label class="subtle">Segments</label>
        <select class="select" [(ngModel)]="dhanBackfill.segments" [disabled]="isActivityRunning()">
          @for (option of dhanSegmentOptions; track option.value) {
            <option [ngValue]="option.value">{{ option.label }}</option>
          }
        </select>
      </div>
      <div>
        <label class="subtle">Instrument Filter</label>
        <select class="select" [(ngModel)]="dhanBackfill.instrumentFilter" [disabled]="isActivityRunning()">
          @for (option of dhanInstrumentOptions; track option.value) {
            <option [ngValue]="option.value">{{ option.label }}</option>
          }
        </select>
      </div>
    </div>
    <div style="display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap; margin-top:12px;">
      <div class="subtle">
        Fetch Stock List first, then run backfill. Default selects Dhan instruments where exchange is NSE, segment is NSE_EQ, and instrument is ES.
      </div>
      <div style="display:flex; gap:8px; flex-wrap:wrap;">
        <button class="btn btn-primary" (click)="startDhanBackfill()" [disabled]="isActivityRunning()">Start Backfill</button>
        @if (canResumeBackfill()) {
          <button class="btn btn-primary" (click)="resumeDhanBackfill()" [disabled]="isActivityRunning()" style="background:#d97706;">Resume</button>
        }
        <button class="btn btn-secondary" (click)="loadDhanBackfillJob()">Refresh Status</button>
        <button class="btn btn-secondary" (click)="loadSkippedStocks()" [disabled]="isDhanBackfillRunning()">View Skipped</button>
        <button class="btn btn-secondary" (click)="downloadDhanBackfillErrorLog()" [disabled]="isDhanBackfillRunning()">Download Error Log</button>
        <button class="btn btn-secondary" (click)="retryFailedDhanBackfill()" [disabled]="isActivityRunning()">Retry Failed & Skipped</button>
        <button class="btn btn-danger" (click)="stopDhanBackfill()" [disabled]="!isDhanBackfillRunning()">Stop Backfill</button>
      </div>
    </div>
    @if (dhanBackfillJob?.status && dhanBackfillJob.status !== 'idle') {
      <div style="margin-top:12px; display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:8px;">
        <div class="status-line"><span>Status</span><strong>{{ dhanBackfillJob.status }}</strong></div>
        <div class="status-line"><span>Progress</span><strong>{{ dhanBackfillProgressPct() }}%</strong></div>
        <div class="status-line"><span>Total Stocks</span><strong>{{ dhanBackfillJob.totalInstruments || 0 }}</strong></div>
        <div class="status-line"><span>Processed Count</span><strong>{{ dhanBackfillJob.processedInstruments || 0 }}</strong></div>
        <div class="status-line"><span>Successfully Updated</span><strong>{{ dhanBackfillJob.successfulInstruments || 0 }}</strong></div>
        <div class="status-line"><span>Failed Count</span><strong>{{ dhanBackfillJob.failedInstruments || dhanBackfillJob.failedChunks || 0 }}</strong></div>
        <div class="status-line"><span>Skipped Count</span><strong>{{ dhanBackfillJob.skippedInstruments || 0 }}</strong></div>
        <div class="status-line"><span>Chunks</span><strong>{{ dhanBackfillJob.processedChunks || 0 }} / {{ dhanBackfillJob.totalChunks || 0 }}</strong></div>
        <div class="status-line"><span>Candles</span><strong>{{ dhanBackfillJob.importedCandles || 0 }}</strong></div>
        <div class="status-line"><span>Current Symbol</span><strong>{{ dhanBackfillJob.currentSymbol || '-' }}</strong></div>
      </div>
      <p class="subtle" style="margin-top:10px; line-height:1.5;">{{ dhanBackfillJob.message }}</p>
      @if (canResumeBackfill()) {
        <div style="margin-top:10px; padding:8px 12px; background:#3a260830; border:1px solid #78350f60; border-radius:6px; font-size:12px; color:#fde68a;">
          This backfill was {{ dhanBackfillJob.status }}. Click <strong>Resume</strong> to continue from where it left off, or <strong>Retry Failed</strong> to re-process only the failed/skipped instruments.
        </div>
      }
    }
    @if (dhanBatches.length > 0) {
      <div style="margin-top:14px;">
        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
          <div style="font-weight:700; color:#fff; font-size:13px;">Monthly Batches
            <span class="subtle" style="margin-left:8px;">{{ dhanBatchCompletedCount() }} of {{ dhanBatches.length }} completed</span>
          </div>
          <button class="btn btn-secondary" style="font-size:10px; padding:3px 8px;" (click)="loadDhanBatches()">Refresh</button>
        </div>
        <div style="display:grid; grid-template-columns:repeat(auto-fill,minmax(220px,1fr)); gap:10px;">
          @for (batch of dhanBatches; track batch.id) {
            <div style="background:#0b1a2d; border:1px solid #203650; border-radius:8px; padding:12px; border-left:3px solid {{ batchBorderColor(batch.status) }};">
              <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
                <strong style="color:#fff; font-size:13px;">{{ batch.monthLabel }}</strong>
                <span [class]="'badge ' + batchBadgeClass(batch.status)">{{ batch.status }}</span>
              </div>
              <div style="font-size:11px; color:#94a3b8; margin-bottom:4px;">{{ batch.fromDate }} to {{ batch.toDate }}</div>
              @if (batch.status === 'running') {
                <div class="progress-bar" style="height:6px; margin:6px 0;">
                  <div class="progress-fill" [style.width.%]="batchProgressPct(batch)"></div>
                </div>
              }
              <div style="display:grid; grid-template-columns:1fr 1fr; gap:4px; font-size:10px; color:#94a3b8; margin-top:4px;">
                <div>Processed: <span style="color:#fff;">{{ batch.processedInstruments || 0 }}/{{ batch.totalInstruments || 0 }}</span></div>
                <div>Candles: <span style="color:#60a5fa;">{{ batch.importedCandles || 0 }}</span></div>
                <div title="Stocks that returned candles and were saved.">Saved: <span style="color:#34d399;">{{ batch.successfulInstruments || 0 }}</span></div>
                <div title="Stocks the source has no history for in this range. Not an error.">No data: <span style="color:#fbbf24;">{{ batch.skippedInstruments || 0 }}</span></div>
                <div title="Stocks that hit a real error. Use Retry This Batch.">Failed: <span style="color:#f87171;">{{ batch.failedInstruments || 0 }}</span></div>
              </div>
              @if (batch.status === 'failed' || batch.status === 'partially_completed' || batch.status === 'pending' || batch.status === 'stopped') {
                <button class="btn btn-primary" style="margin-top:8px; padding:4px 10px; font-size:10px; width:100%;" (click)="retryDhanBatch(batch.id)" [disabled]="isDhanBackfillRunning()">{{ batch.status === 'pending' ? 'Run This Month' : 'Retry This Batch' }}</button>
              }
            </div>
          }
        </div>
      </div>
    }
    <!-- always rendered: the buttons that load this list live in its own header -->
    @if (true) {
      <div style="margin-top:12px; border:1px solid #223a56; border-radius:6px; overflow:hidden;">
        <div style="padding:10px 14px; background:#10233d; border-bottom:1px solid #223a56; display:flex; justify-content:space-between; align-items:center;">
          <div style="font-weight:700; color:#fff; font-size:13px;">
            Skipped & Failed Stocks
            @if (skippedStocks.length || failedStocks.length) {
              <span class="subtle" style="margin-left:8px;">{{ skippedStocks.length }} skipped, {{ failedStocks.length }} failed</span>
            } @else {
              <span class="subtle" style="margin-left:8px;">nothing loaded yet</span>
            }
          </div>
          <div style="display:flex; gap:6px; align-items:center; flex-wrap:wrap;">
            <button class="btn btn-primary" style="font-size:10px; padding:3px 8px;"
                    (click)="loadSkippedStocks()" [disabled]="isDhanBackfillRunning()"
                    title="Load the stocks the last fetch skipped or failed on.">
              View Failed / Skipped
            </button>
            <button class="btn btn-secondary" style="font-size:10px; padding:3px 8px;"
                    (click)="loadDhanBackfillJob()"
                    title="Re-read the job state from the server. Never disabled - use this if controls look stuck.">
              Refresh Status
            </button>
            <button class="btn btn-secondary" style="font-size:10px; padding:3px 8px;"
                    (click)="downloadSkippedStocks()"
                    title="Download these stocks as a CSV with blank columns for the correct name, ISIN, or security ID.">
              Download List
            </button>
            <label class="btn btn-secondary" style="font-size:10px; padding:3px 8px; cursor:pointer; margin:0;"
                   title="Upload the same CSV once you have filled in the corrected details.">
              Upload Corrections
              <input type="file" accept=".csv,text/csv" hidden (change)="uploadCorrections($event)">
            </label>
            <button class="btn btn-secondary" style="font-size:10px; padding:3px 8px;"
                    (click)="downloadMissingData()"
                    title="Plain comma-separated list of every stock and date the fetch could not store.">
              Missing Data (CSV)
            </button>
            <label class="btn btn-primary" style="font-size:10px; padding:3px 8px; cursor:pointer; margin:0;"
                   title="Upload a symbol/date list and fetch exactly those rows into the database.">
              Upload &amp; Fetch
              <input type="file" accept=".csv,.txt,text/csv,text/plain" hidden (change)="uploadAndFetch($event)">
            </label>
            <button class="btn btn-secondary" style="font-size:10px; padding:3px 8px;" (click)="skippedStocks=[]; failedStocks=[]; overrideMessage='';">Close</button>
          </div>
        </div>

        <!-- How this works, plus the quick single-stock path. A stock is usually skipped because
             the broker lists it under a different name; supplying the right one is all it needs. -->
        <div style="padding:8px 14px; background:#0d1b2e; border-bottom:1px solid #223a56; font-size:11px; color:#94a3b8; line-height:1.6;">
          These stocks returned no history from the broker — usually because the broker lists them
          under a different name. Type the correct name, ISIN, or Dhan security ID against a stock
          below and press <strong style="color:#cbd5e1;">Save</strong>, or use
          <strong style="color:#cbd5e1;">Download List</strong> to fix many at once in a spreadsheet
          and upload it back. Then press <strong style="color:#cbd5e1;">Retry Failed &amp; Skipped</strong>
          to fetch them from the broker again.
        </div>
        @if (overrideMessage) {
          <div style="padding:8px 14px; font-size:11px; border-bottom:1px solid #223a56;"
               [style.background]="overrideOk ? '#06281a' : '#3a2608'"
               [style.color]="overrideOk ? '#86efac' : '#fde68a'">
            {{ overrideMessage }}
          </div>
        }
        <div style="max-height:280px; overflow-y:auto;">
          <table style="width:100%; min-width:500px;">
            <thead>
              <tr>
                <th style="padding:6px 10px; text-align:left; font-size:11px; color:#94a3b8; border-bottom:1px solid #223a56;" title="Ticker the fetch asked the broker for.">Symbol</th>
                <th style="padding:6px 10px; text-align:left; font-size:11px; color:#94a3b8; border-bottom:1px solid #223a56;" title="Company this ticker belongs to, as the broker lists it.">Stock Name</th>
                <th style="padding:6px 10px; text-align:left; font-size:11px; color:#94a3b8; border-bottom:1px solid #223a56;">Exchange</th>
                <th style="padding:6px 10px; text-align:left; font-size:11px; color:#94a3b8; border-bottom:1px solid #223a56;">Status</th>
                <th style="padding:6px 10px; text-align:left; font-size:11px; color:#94a3b8; border-bottom:1px solid #223a56; min-width:280px;" title="What happened and what will fix it.">Reason &amp; Remarks</th>
                <th style="padding:6px 10px; text-align:left; font-size:11px; color:#94a3b8; border-bottom:1px solid #223a56; min-width:210px;"
                    title="The name the broker knows this stock by. An ISIN or Dhan security ID works too.">Correct name / ISIN / ID</th>
              </tr>
            </thead>
            <tbody>
              @for (s of skippedStocks; track s.symbol) {
                <tr style="border-bottom:1px solid #1a2e48;">
                  <td style="padding:5px 10px; font-size:12px; font-weight:700; color:#fff; white-space:nowrap;">
                    {{ s.symbol }}
                    @if (s.securityId) {
                      <div style="font-size:9px; color:#64748b; font-weight:400;">ID {{ s.securityId }}</div>
                    }
                  </td>
                  <td style="padding:5px 10px; font-size:11px; color:#cbd5e1;">
                    {{ s.stockName }}
                    @if (s.isin) {
                      <div style="font-size:9px; color:#64748b;">ISIN {{ s.isin }}</div>
                    }
                  </td>
                  <td style="padding:5px 10px; font-size:11px; color:#94a3b8; white-space:nowrap;">{{ s.exchange }}</td>
                  <td style="padding:5px 10px; font-size:11px; color:#f59e0b; white-space:nowrap;">Skipped</td>
                  <td style="padding:5px 10px; font-size:11px; color:#cbd5e1; line-height:1.55;">
                    <div style="font-weight:600;">{{ s.reason }}</div>
                    @if (s.remark) {
                      <div style="color:#94a3b8; margin-top:2px;">{{ s.remark }}</div>
                    }
                    <div style="font-size:9px; color:#64748b; margin-top:2px;">
                      @if (s.dateRange) { <span>Period {{ s.dateRange }}</span> }
                      @if (s.errorCode) { <span> · Code {{ s.errorCode }}</span> }
                    </div>
                  </td>
                  <td style="padding:5px 10px;">{{ '' }}
                    <div style="display:flex; gap:4px; align-items:center;">
                      <input class="input" style="font-size:11px; padding:3px 6px; flex:1; min-width:110px;"
                             [(ngModel)]="correctionInput[s.symbol]"
                             [placeholder]="overrideFor(s.symbol)?.correctedSymbol || 'Correct name / ISIN / ID'">
                      <button class="btn btn-secondary" style="font-size:10px; padding:3px 8px;"
                              (click)="saveCorrection(s.symbol)"
                              [disabled]="overrideSaving || !(correctionInput[s.symbol] || '').trim()">Save</button>
                    </div>
                    @if (overrideFor(s.symbol); as ov) {
                      <div style="font-size:9.5px; margin-top:3px;" [style.color]="ov.resolved ? '#34d399' : '#f87171'">
                        {{ ov.resolved ? 'Matched: ' + (ov.matchedName || ov.correctedSymbol) : ov.note }}
                      </div>
                    }
                  </td>
                </tr>
              }
              @for (s of failedStocks; track s.symbol) {
                <tr style="border-bottom:1px solid #1a2e48;">
                  <td style="padding:5px 10px; font-size:12px; font-weight:700; color:#fff; white-space:nowrap;">
                    {{ s.symbol }}
                    @if (s.securityId) {
                      <div style="font-size:9px; color:#64748b; font-weight:400;">ID {{ s.securityId }}</div>
                    }
                  </td>
                  <td style="padding:5px 10px; font-size:11px; color:#cbd5e1;">
                    {{ s.stockName }}
                    @if (s.isin) {
                      <div style="font-size:9px; color:#64748b;">ISIN {{ s.isin }}</div>
                    }
                  </td>
                  <td style="padding:5px 10px; font-size:11px; color:#94a3b8; white-space:nowrap;">{{ s.exchange }}</td>
                  <td style="padding:5px 10px; font-size:11px; color:#ef4444; white-space:nowrap;">Failed</td>
                  <td style="padding:5px 10px; font-size:11px; color:#cbd5e1; line-height:1.55;">
                    <div style="font-weight:600;">{{ s.reason }}</div>
                    @if (s.remark) {
                      <div style="color:#94a3b8; margin-top:2px;">{{ s.remark }}</div>
                    }
                    <div style="font-size:9px; color:#64748b; margin-top:2px;">
                      @if (s.dateRange) { <span>Period {{ s.dateRange }}</span> }
                      @if (s.errorCode) { <span> · Code {{ s.errorCode }}</span> }
                    </div>
                  </td>
                  <td style="padding:5px 10px;">
                    <div style="display:flex; gap:4px; align-items:center;">
                      <input class="input" style="font-size:11px; padding:3px 6px; flex:1; min-width:110px;"
                             [(ngModel)]="correctionInput[s.symbol]"
                             [placeholder]="overrideFor(s.symbol)?.correctedSymbol || 'Correct name / ISIN / ID'">
                      <button class="btn btn-secondary" style="font-size:10px; padding:3px 8px;"
                              (click)="saveCorrection(s.symbol)"
                              [disabled]="overrideSaving || !(correctionInput[s.symbol] || '').trim()">Save</button>
                    </div>
                    @if (overrideFor(s.symbol); as ov) {
                      <div style="font-size:9.5px; margin-top:3px;" [style.color]="ov.resolved ? '#34d399' : '#f87171'">
                        {{ ov.resolved ? 'Matched: ' + (ov.matchedName || ov.correctedSymbol) : ov.note }}
                      </div>
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

  @if (warehouseTab === 'technical') {
  <div class="panel" style="margin-top:14px;">
    <div style="padding:14px; border-bottom:1px solid #203650; display:flex; justify-content:space-between; gap:12px; flex-wrap:wrap;">
      <div>
        <div style="font-weight:800; color:#fff; margin-bottom:4px;">Backend Data Tables</div>
        <p class="subtle">Download and upload full backend data table files. This section has no upload or download limit.</p>
      </div>
      <div class="subtle">Table-wise action</div>
    </div>
    <div class="table-wrap">
      <table style="min-width:1080px;">
        <thead>
          <tr>
            <th style="width:240px;">Data Table Name</th>
            <th style="width:210px;">Backend Table</th>
            <th>Description</th>
            <th style="width:150px;">Download</th>
            <th style="width:260px;">Upload</th>
            <th style="width:140px;">Clean / Delete</th>
          </tr>
        </thead>
        <tbody>
          @for (table of pagedBackendTables(); track table.key) {
            <tr>
              <td>
                <div style="font-weight:800; color:#fff;">{{ table.label }}</div>
              </td>
              <td><span class="chip">{{ table.tableName }}</span></td>
              <td><div class="note" style="max-width:none;">{{ table.description }}</div></td>
              <td>
                <button class="btn btn-primary" (click)="downloadBackendTable(table)">Download</button>
              </td>
              <td>
                <input class="file" type="file" multiple
                  accept=".csv,.txt,.zip,.xlsx,.xls,.json,.dat,.sql"
                  [disabled]="isActivityRunning()"
                  (change)="uploadBackendTable(table, $event)">
              </td>
              <td>
                <button class="btn btn-danger" (click)="cleanBackendTable(table)" [disabled]="isActivityRunning()">Clean</button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <div class="pager">
      <div class="subtle">
        Showing {{ backendTablePageStart() }}-{{ backendTablePageEnd() }} of {{ backendTables.length }} tables
      </div>
      <div class="pager-controls">
        <button class="btn btn-secondary" (click)="setBackendTablePage(backendTablePage - 1)" [disabled]="backendTablePage <= 1">Previous</button>
        <span class="subtle">Page {{ backendTablePage }} of {{ backendTableTotalPages() }}</span>
        <button class="btn btn-secondary" (click)="setBackendTablePage(backendTablePage + 1)" [disabled]="backendTablePage >= backendTableTotalPages()">Next</button>
      </div>
    </div>
  </div>
  }

  <!-- Stock Master Preview - Redesigned -->
  <div class="panel" style="margin-top:14px;">
    <div style="padding:14px; border-bottom:1px solid #203650;">
      <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:12px; flex-wrap:wrap;">
        <div>
          <div style="font-weight:800; color:#fff; font-size:16px; margin-bottom:4px;">Stock Master Preview</div>
          <p class="subtle">Latest uploaded and downloaded stock master records. Auto-refreshes after every fetch or upload.</p>
        </div>
        <div style="display:flex; gap:6px; flex-wrap:wrap; align-items:center;">
          <span class="badge badge-blue" style="font-size:9px;">{{ stockPreviewTotal | number }} total</span>
        </div>
      </div>
    </div>

    <!-- Summary Cards -->
    <div class="sm-stats">
      <div class="sm-stat">
        <div class="sm-stat-val text-blue">{{ smStats?.totalRecords || 0 | number }}</div>
        <div class="sm-stat-label">Total Records</div>
      </div>
      <div class="sm-stat">
        <div class="sm-stat-val text-green">{{ smStats?.importedToday || 0 | number }}</div>
        <div class="sm-stat-label">Imported Today</div>
      </div>
      <div class="sm-stat">
        <div class="sm-stat-val text-red">{{ smStats?.failedRecords || 0 | number }}</div>
        <div class="sm-stat-label">Failed Records</div>
      </div>
      <div class="sm-stat">
        <div class="sm-stat-val text-amber">{{ smStats?.duplicateRecords || 0 | number }}</div>
        <div class="sm-stat-label">Duplicate Records</div>
      </div>
      <div class="sm-stat">
        <div class="sm-stat-val text-slate" style="font-size:11px;">{{ smStats?.lastFetchTime || 'Never' }}</div>
        <div class="sm-stat-label">Last Fetch/Upload</div>
      </div>
      <div class="sm-stat">
        <div class="sm-stat-val text-cyan" style="font-size:13px;">{{ smStats?.lastSource || 'N/A' }}</div>
        <div class="sm-stat-label">Last Source</div>
      </div>
    </div>

    <!-- Quick Action Buttons -->
    <div class="sm-actions">
      <button class="btn btn-primary" (click)="loadStockPreview(); loadSmStats()">Refresh</button>
      <button class="btn btn-secondary" (click)="fetchStockMasterAction()" [disabled]="isActivityRunning()">Fetch Stock Master</button>
      <label class="btn btn-secondary" style="cursor:pointer; position:relative;">
        Upload Stock Master
        <input type="file" accept=".csv,.xlsx,.json" style="position:absolute; inset:0; opacity:0; cursor:pointer;" (change)="uploadStockMasterFile($event)" [disabled]="isActivityRunning()">
      </label>
      <button class="btn btn-secondary" (click)="downloadStockMasterSample()">Download Sample Format</button>
      <button class="btn btn-secondary" (click)="downloadStockMasterPreview()" [disabled]="!stockPreview.length">Download Preview</button>
      <button class="btn btn-secondary" (click)="showSmLogs = !showSmLogs; showSmLogs && loadSmLogs()">{{ showSmLogs ? 'Hide' : 'View' }} Logs</button>
    </div>

    <!-- Success/Error Messages -->
    @if (smSuccessMsg) {
      <div class="sm-success-msg">
        <div style="display:flex; justify-content:space-between; align-items:flex-start;">
          <div>
            <div style="color:#34d399; font-weight:700; font-size:13px; margin-bottom:4px;">Stock Master updated successfully.</div>
            <div style="color:#bbf7d0; font-size:12px; line-height:1.6;">{{ smSuccessMsg }}</div>
          </div>
          <button class="btn btn-secondary" style="padding:3px 8px; font-size:10px;" (click)="smSuccessMsg=''">Close</button>
        </div>
      </div>
    }
    @if (smErrorMsg) {
      <div class="sm-error-msg">
        <div style="display:flex; justify-content:space-between; align-items:flex-start;">
          <div>
            <div style="color:#f87171; font-weight:700; font-size:13px; margin-bottom:4px;">Stock Master Fetch Failed</div>
            <div style="color:#fecaca; font-size:12px; line-height:1.6; white-space:pre-line;">{{ smErrorMsg }}</div>
          </div>
          <div style="display:flex; gap:6px;">
            <button class="btn btn-primary" style="padding:4px 10px; font-size:11px;" (click)="fetchStockMasterAction()">Retry</button>
            <button class="btn btn-secondary" style="padding:3px 8px; font-size:10px;" (click)="smErrorMsg=''">Close</button>
          </div>
        </div>
      </div>
    }

    <!-- Fetch Progress -->
    @if (stockPreviewProgressVisible()) {
      <div style="margin:10px 14px; border:1px solid #203650; border-radius:8px; background:#0b1a2d; padding:12px;">
        <div style="display:flex; justify-content:space-between; gap:12px; align-items:center; margin-bottom:8px;">
          <div>
            <div style="font-weight:800; color:#fff;">Stock Master Fetch Progress</div>
            <div class="subtle">{{ activeActivity.source || 'Selected Source' }} · {{ currentProgressStep() }}</div>
          </div>
          <strong style="color:#fff;">{{ globalProgressPct() }}%</strong>
        </div>
        <div class="progress-bar">
          <div class="progress-fill" [style.width.%]="globalProgressPct()"></div>
        </div>
        <div class="progress-meta">
          <div class="progress-meta-item"><span>Processed</span><strong>{{ progressProcessed() | number }} / {{ progressTotal() | number }} {{ progressUnit() }}</strong></div>
          <div class="progress-meta-item"><span>Saved To</span><strong>{{ activeActivity.targetTable || activeActivity.tableName || 'stock_master' }}</strong></div>
          <div class="progress-meta-item"><span>Started</span><strong>{{ activeActivity.startedAt || '-' }}</strong></div>
          <div class="progress-meta-item"><span>Status</span><strong style="text-transform:capitalize;">{{ activeActivity.status }}</strong></div>
        </div>
      </div>
    }

    <!-- Logs Panel -->
    @if (showSmLogs) {
      <div style="margin:10px 14px; border:1px solid #203650; border-radius:8px; overflow:hidden;">
        <div style="padding:10px 14px; background:#0d1f38; border-bottom:1px solid #203650; display:flex; justify-content:space-between; align-items:center;">
          <div style="font-weight:700; color:#fff; font-size:13px;">Stock Master Logs</div>
          <button class="btn btn-secondary" style="padding:3px 8px; font-size:10px;" (click)="loadSmLogs()">Refresh</button>
        </div>
        @if (smLogs.length === 0) {
          <div style="padding:20px; text-align:center; color:#64748b; font-size:12px;">No stock master fetch or upload logs found.</div>
        } @else {
          <div style="max-height:300px; overflow-y:auto;">
            <table style="min-width:900px;">
              <thead>
                <tr>
                  <th style="font-size:10px;">Action</th>
                  <th style="font-size:10px;">Source</th>
                  <th style="font-size:10px;">Status</th>
                  <th style="font-size:10px;">Total</th>
                  <th style="font-size:10px;">Imported</th>
                  <th style="font-size:10px;">Failed</th>
                  <th style="font-size:10px;">Skipped</th>
                  <th style="font-size:10px;">Time</th>
                  <th style="font-size:10px;">Error</th>
                </tr>
              </thead>
              <tbody>
                @for (log of smLogs; track log.id) {
                  <tr>
                    <td><span class="badge" [ngClass]="log.actionType === 'Fetched' ? 'badge-blue' : 'badge-green'">{{ log.actionType }}</span></td>
                    <td>{{ log.source || '-' }}</td>
                    <td><span class="badge" [ngClass]="smLogStatusBadge(log.status)">{{ log.status || '-' }}</span></td>
                    <td style="text-align:right;">{{ log.totalRecords || 0 }}</td>
                    <td style="text-align:right;">{{ log.importedRecords || 0 }}</td>
                    <td style="text-align:right;">{{ log.failedRecords || 0 }}</td>
                    <td style="text-align:right;">{{ log.skippedRecords || 0 }}</td>
                    <td style="white-space:nowrap;">{{ log.createdAt || '-' }}</td>
                    <td style="max-width:200px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">{{ log.errorMessage || '-' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </div>
    }

    <!-- Filters -->
    <div class="sm-filters">
      <input class="input" style="min-width:200px;" [(ngModel)]="stockPreviewSearch" (ngModelChange)="stockPreviewPage=0; loadStockPreview()" placeholder="Search symbol, company or ISIN...">
      <select class="select" [(ngModel)]="smFilterSource" (ngModelChange)="stockPreviewPage=0; loadStockPreview()">
        <option value="">All Sources</option>
        <option value="NSE">NSE</option>
        <option value="BSE">BSE</option>
        <option value="DHAN">Dhan</option>
        <option value="MANUAL">Manual</option>
        <option value="BROKER">Broker</option>
      </select>
      <select class="select" [(ngModel)]="smFilterStatus" (ngModelChange)="stockPreviewPage=0; loadStockPreview()">
        <option value="">All Status</option>
        <option value="ACTIVE">Active</option>
        <option value="INACTIVE">Inactive</option>
        <option value="FAILED">Failed</option>
        <option value="DUPLICATE">Duplicate</option>
      </select>
      <select class="select" [(ngModel)]="smFilterExchange" (ngModelChange)="stockPreviewPage=0; loadStockPreview()">
        <option value="">All Exchanges</option>
        <option value="NSE">NSE</option>
        <option value="BSE">BSE</option>
      </select>
      <select class="select" [(ngModel)]="smFilterActionType" (ngModelChange)="stockPreviewPage=0; loadStockPreview()">
        <option value="">All Actions</option>
        <option value="FETCHED">Fetched</option>
        <option value="UPLOADED">Uploaded</option>
      </select>
      <input class="input" type="date" style="min-width:130px;" [(ngModel)]="smFilterDateFrom" (ngModelChange)="stockPreviewPage=0; loadStockPreview()" title="From Date">
      <input class="input" type="date" style="min-width:130px;" [(ngModel)]="smFilterDateTo" (ngModelChange)="stockPreviewPage=0; loadStockPreview()" title="To Date">
      @if (hasSmFilters()) {
        <button class="btn btn-secondary" style="padding:4px 10px; font-size:10px;" (click)="clearSmFilters()">Clear Filters</button>
      }
    </div>

    <!-- Loading State -->
    @if (smLoading) {
      <div style="padding:20px 14px;">
        <div style="color:#94a3b8; font-size:12px; margin-bottom:12px;">Loading latest stock master records...</div>
        @for (i of [1,2,3,4,5]; track i) {
          <div style="display:flex; gap:12px; padding:8px 0; border-bottom:1px solid #203650;">
            <div class="sm-skeleton" style="width:60px; height:12px;"></div>
            <div class="sm-skeleton" style="width:90px; height:12px;"></div>
            <div class="sm-skeleton" style="width:180px; height:12px;"></div>
            <div class="sm-skeleton" style="width:50px; height:12px;"></div>
            <div class="sm-skeleton" style="width:120px; height:12px;"></div>
            <div class="sm-skeleton" style="width:100px; height:12px;"></div>
          </div>
        }
      </div>
    }

    <!-- Error State -->
    @if (stockPreviewError && !smLoading) {
      <div class="sm-empty">
        <div class="sm-empty-icon">&#9888;</div>
        <div class="sm-empty-title">Unable to load Stock Master Preview</div>
        <div class="sm-empty-text">{{ stockPreviewError }}</div>
        <button class="btn btn-primary" style="margin-top:14px;" (click)="loadStockPreview()">Retry</button>
      </div>
    }

    <!-- Empty State -->
    @if (!smLoading && !stockPreviewError && stockPreview.length === 0) {
      <div class="sm-empty">
        <div class="sm-empty-icon">&#128203;</div>
        <div class="sm-empty-title">No Stock Master records found</div>
        <div class="sm-empty-text">Fetch stock master data from exchange/API source or upload stock master file manually.</div>
        <div style="display:flex; gap:8px; justify-content:center; margin-top:14px;">
          <button class="btn btn-primary" (click)="fetchStockMasterAction()" [disabled]="isActivityRunning()">Fetch Stock Master</button>
          <label class="btn btn-secondary" style="cursor:pointer; position:relative;">
            Upload File
            <input type="file" accept=".csv,.xlsx,.json" style="position:absolute; inset:0; opacity:0; cursor:pointer;" (change)="uploadStockMasterFile($event)">
          </label>
        </div>
      </div>
    }

    <!-- Data Table -->
    @if (!smLoading && !stockPreviewError && stockPreview.length > 0) {
      <div class="table-wrap">
        <table style="min-width:1400px;">
          <thead>
            <tr>
              <th style="width:140px;">Date & Time</th>
              <th style="width:100px;">Symbol</th>
              <th style="width:180px;">Company Name</th>
              <th style="width:60px;">Exchange</th>
              <th style="width:130px;">ISIN</th>
              <th style="width:110px;">Sector</th>
              <th style="width:110px;">Industry</th>
              <th style="width:60px;">Series</th>
              <th style="width:80px;">Source</th>
              <th style="width:80px;">Action Type</th>
              <th style="width:80px;">Status</th>
              <th>Remarks</th>
            </tr>
          </thead>
          <tbody>
            @for (row of stockPreview; track row.id) {
              <tr>
                <td style="white-space:nowrap; color:#94a3b8;">{{ row.dateTime || '-' }}</td>
                <td style="font-weight:800; color:#fff;">{{ row.symbol || '-' }}</td>
                <td>{{ row.companyName || '-' }}</td>
                <td><span class="badge badge-slate">{{ row.exchange || '-' }}</span></td>
                <td style="font-family:monospace; font-size:11px;">{{ row.isin || '-' }}</td>
                <td>{{ row.sector || '-' }}</td>
                <td>{{ row.industry || '-' }}</td>
                <td>{{ row.series || '-' }}</td>
                <td>{{ row.source || '-' }}</td>
                <td><span class="badge" [ngClass]="row.actionType === 'uploaded' ? 'badge-green' : 'badge-blue'">{{ row.actionType || 'fetched' }}</span></td>
                <td><span class="badge" [ngClass]="smStatusBadge(row.status)">{{ row.status || '-' }}</span></td>
                <td style="max-width:200px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">{{ row.remarks || '-' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; border-top:1px solid #203650; flex-wrap:wrap; gap:8px;">
        <span style="color:#94a3b8; font-size:11px;">Showing {{ stockPreviewPage * stockPreviewLimit + 1 }} - {{ stockPreviewPageEnd() }} of {{ stockPreviewTotal | number }} records</span>
        <div style="display:flex; align-items:center; gap:8px;">
          <select class="select" style="width:auto; min-width:80px; padding:4px 6px; font-size:11px;" [(ngModel)]="stockPreviewLimit" (ngModelChange)="stockPreviewPage=0; loadStockPreview()">
            <option [ngValue]="10">10 / page</option>
            <option [ngValue]="25">25 / page</option>
            <option [ngValue]="50">50 / page</option>
            <option [ngValue]="100">100 / page</option>
          </select>
          <button class="btn btn-secondary" (click)="setStockPreviewPage(stockPreviewPage - 1)" [disabled]="stockPreviewPage <= 0" style="padding:5px 10px; font-size:11px;">Previous</button>
          <span style="color:#cbd5e1; font-size:11px;">Page {{ stockPreviewPage + 1 }} of {{ stockPreviewTotalPages() }}</span>
          <button class="btn btn-secondary" (click)="setStockPreviewPage(stockPreviewPage + 1)" [disabled]="stockPreviewPage + 1 >= stockPreviewTotalPages()" style="padding:5px 10px; font-size:11px;">Next</button>
        </div>
      </div>
    }
  </div>

  <div class="panel" style="margin-top:14px; padding:14px;">
    <div style="font-weight:800; color:#fff; margin-bottom:4px;">Data Cleanup</div>
    <p class="subtle" style="line-height:1.6;">Review data quality and clean duplicate Stock Master records. Cleanup activity is limited to this user screen and shows a clear result after completion.</p>
    <div style="display:grid; grid-template-columns:repeat(auto-fit,minmax(170px,1fr)); gap:10px; margin-top:12px;">
      <div class="status-line"><span>Stock Master</span><strong>{{ cleanupSummary.stockMasterRows || 0 }}</strong></div>
      <div class="status-line"><span>Market Data</span><strong>{{ cleanupSummary.marketDataRows || 0 }}</strong></div>
      <div class="status-line"><span>Blank Symbols</span><strong>{{ cleanupSummary.blankStockSymbols || 0 }}</strong></div>
      <div class="status-line"><span>Duplicate Rows</span><strong>{{ cleanupSummary.duplicateStockMasterRows || 0 }}</strong></div>
      <div class="status-line"><span>Uploads</span><strong>{{ cleanupSummary.uploadHistoryRows || 0 }}</strong></div>
      <div class="status-line"><span>Broker Candles</span><strong>{{ cleanupSummary.brokerCandleRows || 0 }}</strong></div>
      <div class="status-line"><span>Broker Portfolio</span><strong>{{ cleanupSummary.brokerPortfolioRows || 0 }}</strong></div>
      <div class="status-line"><span>Option Chain</span><strong>{{ cleanupSummary.optionChainRows || 0 }}</strong></div>
      <div class="status-line"><span>FII/DII</span><strong>{{ cleanupSummary.fiiDiiRows || 0 }}</strong></div>
      <div class="status-line"><span>Fundamentals</span><strong>{{ cleanupSummary.fundamentalRows || 0 }}</strong></div>
      <div class="status-line"><span>Last Backup</span><strong>{{ cleanupSummary.lastBackup || '-' }}</strong></div>
    </div>
    <div style="display:flex; gap:8px; flex-wrap:wrap; justify-content:flex-end; margin-top:12px;">
      <button class="btn btn-secondary" (click)="loadCleanupSummary()">Refresh Summary</button>
      <button class="btn btn-primary" (click)="cleanupDuplicates()" [disabled]="isActivityRunning()">Clean Stock Master Duplicates</button>
    </div>
  </div>

  <div class="panel" style="margin-top:14px; padding:14px; border-color:#7f1d1d;">
    <div style="font-weight:800; color:#fff; margin-bottom:4px;">Database Backup, Upload and Removal</div>
    <p class="subtle" style="line-height:1.6;">
      Use Backup before risky work. Remove Database Data creates a backup first, then clears application data while keeping login users and backup records so the app can still open.
    </p>
    <div style="display:grid; grid-template-columns:repeat(auto-fit,minmax(240px,1fr)); gap:12px; margin-top:12px;">
      <div>
        <button class="btn btn-primary" (click)="createDatabaseBackup()" [disabled]="isActivityRunning()">Take Backup</button>
        @if (backupResult?.filename) {
          <div class="note" style="margin-top:8px; max-width:none;">Latest backup: {{ backupResult.filename }} ({{ backupResult.fileSizeKb }} KB)</div>
        }
      </div>
      <div>
        <label class="subtle">Upload Backup File</label>
        <input class="file" type="file" accept=".zip,.csv,.sql,.txt" (change)="uploadDatabaseBackup($event)" [disabled]="isActivityRunning()">
        <div class="note" style="margin-top:6px; max-width:none;">Uploaded backup files are stored safely for later restore planning.</div>
      </div>
      <div>
        @if (removalStatus && removalStatus.status !== 'idle') {
          <div style="grid-column:1/-1; margin-bottom:12px; padding:12px 14px; border-radius:6px;
                      border:1px solid #2d3f55; background:#0f172a;">
            <div style="display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:8px;">
              <span style="font-weight:700; color:#fff; font-size:13px;">
                Removing database data — {{ removalStatus.phase }}
              </span>
              <span style="font-size:12px; color:#94a3b8;">{{ removalStatus.percent || 0 }}%</span>
            </div>
            <div style="height:8px; border-radius:4px; background:#1e293b; overflow:hidden;">
              <div [style.width.%]="removalStatus.percent || 0"
                   [style.background]="removalStatus.status === 'failed' ? '#ef4444' : (removalStatus.status === 'completed' ? '#22c55e' : '#3b82f6')"
                   style="height:100%; transition:width .3s ease;"></div>
            </div>
            <div style="margin-top:8px; font-size:12px; color:#cbd5e1;">{{ removalStatus.message }}</div>
            @if (removalStatus.tablesTotal) {
              <div style="margin-top:4px; font-size:11px; color:#64748b;">
                {{ removalStatus.tablesDone || 0 }} of {{ removalStatus.tablesTotal }} tables cleared
                @if (removalStatus.currentTable) { · currently {{ removalStatus.currentTable }} }
              </div>
            }
          </div>
        }
        <label class="subtle">Type REMOVE DATABASE to enable removal</label>
        <input class="input" [(ngModel)]="databaseConfirmation" placeholder="REMOVE DATABASE">
        <button class="btn btn-danger" style="margin-top:8px;" (click)="removeDatabaseData()" [disabled]="isActivityRunning() || databaseConfirmation !== 'REMOVE DATABASE'">
          Remove Database Data
        </button>
      </div>
    </div>
  </div>

  @if (shouldShowProgressModal()) {
    <div class="status-backdrop">
      <div class="status-dialog">
        <div style="display:flex; align-items:center; justify-content:space-between; gap:12px;">
          <div class="status-title">{{ activeActivity.activityName || 'Data Import Progress' }}</div>
          @if (activeActivity.status === 'running') { <div class="spinner"></div> }
        </div>
        <div class="progress-grid">
          <div class="progress-ring"
               [style.background]="'conic-gradient(#60a5fa ' + globalProgressPct() + '%, #203650 ' + globalProgressPct() + '%)'">
            <div class="progress-ring-inner">
              <span style="color:#fff; font-size:20px; font-weight:900;">{{ globalProgressPct() }}%</span>
            </div>
          </div>
          <div>
            <div class="progress-bar">
              <div class="progress-fill" [style.width.%]="globalProgressPct()"></div>
            </div>
            <div style="color:#cbd5e1; font-size:13px; font-weight:800; margin-top:8px;">
              {{ progressProcessed() | number }} / {{ progressTotal() | number }} {{ progressUnit() }} Processed
            </div>
            <!-- Processed counts every stock attempted, so it is always saved + no-data + failed.
                 Without this line the gap between "3,313 processed" and "Success: 2,621" looks
                 like lost data when it is simply stocks the source has no history for. -->
            @if (dhanBackfillJob?.processedInstruments) {
              <div style="display:flex; gap:14px; flex-wrap:wrap; justify-content:center; margin-top:6px; font-size:11px;">
                <span style="color:#34d399;" title="Stocks that returned candles and were saved to the database.">
                  Saved <strong>{{ (dhanBackfillJob.successfulInstruments || 0) | number }}</strong>
                </span>
                <span style="color:#fbbf24;" title="Stocks the source has no history for in this date range - delisted, suspended, or listed later. Nothing went wrong and nothing was lost.">
                  No data <strong>{{ (dhanBackfillJob.skippedInstruments || 0) | number }}</strong>
                </span>
                <span [style.color]="(dhanBackfillJob.failedInstruments || 0) > 0 ? '#f87171' : '#64748b'"
                      title="Stocks that hit a real error and can be retried.">
                  Failed <strong>{{ (dhanBackfillJob.failedInstruments || 0) | number }}</strong>
                </span>
              </div>
              <div style="color:#64748b; font-size:10px; margin-top:4px;">
                Saved + No data + Failed = Processed. Stocks with no history at the source are skipped, not lost.
              </div>
            }
            <div class="progress-meta">
              <div class="progress-meta-item">
                <span style="display:block; color:#94a3b8; font-size:10px; margin-bottom:4px;">Current Step</span>
                <strong style="display:block; color:#fff; font-size:12px; line-height:1.35; word-break:break-word;">{{ currentProgressStep() }}</strong>
              </div>
              <div class="progress-meta-item">
                <span style="display:block; color:#94a3b8; font-size:10px; margin-bottom:4px;">Estimated Time Remaining</span>
                <strong style="display:block; color:#fff; font-size:12px; line-height:1.35; word-break:break-word;">{{ estimatedRemaining() }}</strong>
              </div>
              <div class="progress-meta-item">
                <span style="display:block; color:#94a3b8; font-size:10px; margin-bottom:4px;">Start Time</span>
                <strong style="display:block; color:#fff; font-size:12px; line-height:1.35; word-break:break-word;">{{ activeActivity.startedAt || '-' }}</strong>
              </div>
              <div class="progress-meta-item">
                <span style="display:block; color:#94a3b8; font-size:10px; margin-bottom:4px;">Status</span>
                <strong style="display:block; color:#fff; font-size:12px; line-height:1.35; word-break:break-word; text-transform:capitalize;">{{ activeActivity.status }}</strong>
              </div>
            </div>
          </div>
        </div>
        <div class="step-track">
          @for (step of progressSteps(); track step.label) {
            <div class="step-item" [class.done]="step.state === 'done'" [class.running]="step.state === 'running'" [class.failed]="step.state === 'failed'">
              <div class="step-label">{{ step.label }}</div>
            </div>
          }
        </div>

        <!-- Smart Fetch Steps -->
        @if (getSmartSteps().length > 0) {
          <div style="margin-top:12px; background:#0b1a2d; border:1px solid #203650; border-radius:6px; padding:10px;">
            <div style="color:#fff; font-size:12px; font-weight:700; margin-bottom:6px;">Execution Steps</div>
            @for (st of getSmartSteps(); track st.step) {
              <div class="smart-step">
                <div class="smart-step-icon">
                  @if (st.status === 'done' || st.status === 'success') { <span style="color:#34d399;">&#10003;</span> }
                  @else if (st.status === 'failed' || st.status === 'error') { <span style="color:#f87171;">&#10007;</span> }
                  @else if (st.status === 'skipped') { <span style="color:#fbbf24;">&#8722;</span> }
                  @else if (st.status === 'running') { <span style="color:#60a5fa;">&#9679;</span> }
                  @else { <span style="color:#64748b;">&#9675;</span> }
                </div>
                <div style="flex:1;">
                  <span style="color:#cbd5e1; font-weight:600;">{{ st.step || st.label }}</span>
                  @if (st.message) { <span style="color:#94a3b8; margin-left:6px;">{{ st.message }}</span> }
                </div>
              </div>
            }
          </div>
        }

        <div class="status-line"><span>Data Type</span><strong>{{ activeActivity.dataType || '-' }}</strong></div>
        <div class="status-line"><span>Source</span><strong>{{ activeActivity.source || '-' }}</strong></div>
        @if (activeActivity.targetTable || activeActivity.tableName) {
          <div class="status-line"><span>Saved To</span><strong>{{ activeActivity.targetTable || activeActivity.tableName }}</strong></div>
        }
        @if (activeActivity.details?.duration) {
          <div class="status-line"><span>Duration</span><strong>{{ activeActivity.details.duration }}</strong></div>
        }

        <!-- Error Code & Resolution -->
        @if (getErrorCode()) {
          <div style="margin-top:12px; display:flex; align-items:center; gap:10px;">
            <span class="error-code-badge">{{ getErrorCode() }}</span>
            <span style="color:#cbd5e1; font-size:12px;">{{ getErrorMessage() }}</span>
          </div>
          @if (getResolution()) {
            <div class="resolution-box">
              <div class="resolution-title">Recommended Resolution</div>
              <div class="resolution-text">{{ getResolution() }}</div>
            </div>
          }
        }

        <p class="subtle" style="margin-top:12px; line-height:1.5;">{{ activeActivity.message }}</p>

        @if (getSkippedDates().length > 0) {
          <div style="margin-top:12px; background:#1a1a2e; border:1px solid #3a2a10; border-radius:6px; padding:10px;">
            <div style="color:#fbbf24; font-size:11px; font-weight:700; margin-bottom:6px;">Skipped Dates ({{ getSkippedDates().length }})</div>
            @for (sk of getSkippedDates(); track sk.date) {
              <div style="display:flex; justify-content:space-between; font-size:11px; padding:3px 0; border-bottom:1px solid #203650;">
                <span style="color:#94a3b8;">{{ sk.date }}</span>
                <span style="color:#f59e0b; max-width:280px; text-align:right; word-break:break-word;">{{ sk.reason }}</span>
              </div>
            }
          </div>
        }
        @if (getFetchedDates().length > 0) {
          <div style="margin-top:8px; background:#0b1a2d; border:1px solid #203650; border-radius:6px; padding:10px;">
            <div style="color:#34d399; font-size:11px; font-weight:700; margin-bottom:6px;">Successfully Fetched ({{ getFetchedDates().length }})</div>
            @for (fd of getFetchedDates(); track fd.date) {
              <div style="display:flex; justify-content:space-between; font-size:11px; padding:3px 0; border-bottom:1px solid #203650;">
                <span style="color:#94a3b8;">{{ fd.date }}</span>
                <span style="color:#34d399;">{{ fd.rowsImported }} rows → {{ fd.targetTable }}</span>
              </div>
            }
          </div>
        }
        <div style="display:flex; justify-content:flex-end; gap:8px; margin-top:16px;">
          @if (activeActivity.status === 'running') {
            <button class="btn btn-danger" (click)="stopActivity()">Stop</button>
          } @else {
            <button class="btn btn-secondary" (click)="clearActivity()">Close</button>
          }
        </div>
      </div>
    </div>
  }
</div>
  `
})
export class DataManagementComponent implements OnInit, OnDestroy {
  loading = signal(true);
  rows: WarehouseRow[] = [];
  backendTables: BackendTable[] = [];
  activeBrokers: any[] = [];
  activeDhanBrokers: any[] = [];
  selectedSources: Record<string, string> = {};
  selectedBrokers: Record<string, number | null> = {};
  dateForms: Record<string, { fromDate: string; toDate: string }> = {};
  activeActivity: any = { status: 'idle' };
  dependentSections: DependentSection[] = [];
  stockPreview: StockMasterPreviewRow[] = [];
  stockPreviewTotal = 0;
  stockPreviewSearch = '';
  stockPreviewLimit = 10;
  stockPreviewPage = 0;
  stockPreviewError = '';
  smLoading = false;
  smStats: any = null;
  smLogs: any[] = [];
  showSmLogs = false;
  smSuccessMsg = '';
  smErrorMsg = '';
  smFilterSource = '';
  smFilterStatus = '';
  smFilterExchange = '';
  smFilterActionType = '';
  smFilterDateFrom = '';
  smFilterDateTo = '';
  fundamentals: any[] = [];
  fundamentalsTotal = 0;
  fundamentalsPage = 0;
  fundamentalsLimit = 10;
  fundamentalsSearch = '';
  fundamentalsLoading = false;
  skippedStocks: any[] = [];
  /** Corrections the user has typed but not yet saved, keyed by the skipped symbol. */
  correctionInput: Record<string, string> = {};
  symbolOverrides: any[] = [];
  overrideSaving = false;
  overrideMessage = '';
  overrideOk = true;
  failedStocks: any[] = [];
  backendTablePage = 1;
  backendTablePageSize = 10;
  cleanupSummary: any = {};
  backupResult: any = null;
  databaseConfirmation = '';
  dhanBackfillJob: any = { status: 'idle' };
  dhanBatches: any[] = [];
  private dhanBackfillPoller: any = null;
  private dhanInstrumentPoller: any = null;
  dhanBackfill = {
    brokerCredentialId: null as number | null,
    mode: 'DAILY',
    intervalMinutes: 1,
    fromDate: new Date(new Date().setDate(new Date().getDate() - 30)).toISOString().slice(0, 10),
    toDate: new Date().toISOString().slice(0, 10),
    exchanges: 'NSE',
    segments: 'NSE_EQ',
    instrumentFilter: 'ES'
  };
  dhanExchangeOptions = [
    { value: 'NSE', label: 'NSE' },
    { value: 'BSE', label: 'BSE' },
    { value: 'MCX', label: 'MCX' },
    { value: 'NSE,BSE', label: 'NSE + BSE' },
    { value: 'ALL', label: 'All Exchanges' }
  ];
  dhanSegmentOptions = [
    { value: 'NSE_EQ', label: 'NSE Equity' },
    { value: 'BSE_EQ', label: 'BSE Equity' },
    { value: 'NSE_EQ,BSE_EQ', label: 'NSE + BSE Equity' },
    { value: 'NSE_FNO', label: 'NSE F&O' },
    { value: 'BSE_FNO', label: 'BSE F&O' },
    { value: 'MCX_COMM', label: 'MCX Commodity' },
    { value: 'ALL', label: 'All Segments' }
  ];
  dhanInstrumentOptions = [
    { value: 'ES', label: 'ES - Equity Stock' },
    { value: 'EQUITY', label: 'Equity' },
    { value: 'INDEX', label: 'Index' },
    { value: 'FUTIDX', label: 'Futures Index' },
    { value: 'FUTSTK', label: 'Futures Stock' },
    { value: 'OPTIDX', label: 'Options Index' },
    { value: 'OPTSTK', label: 'Options Stock' },
    { value: 'FUT', label: 'All Futures' },
    { value: 'OPT', label: 'All Options' },
    { value: 'ALL', label: 'All Instruments' }
  ];
  dhanBackfillDataType = 'Historical OHLCV';
  dhanBackfillDataTypes = [
    { value: 'Historical OHLCV', enabled: true },
    { value: 'Intraday OHLCV', enabled: true },
    { value: 'Daily Bhavcopy', enabled: false, reason: 'Use manual upload or NSE source.' },
    { value: 'F&O Bhavcopy', enabled: false, reason: 'Use manual upload or NSE source.' },
    { value: 'Option Chain', enabled: false, reason: 'Not available from Dhan API. Use manual upload.' },
    { value: 'Futures Data', enabled: true },
    { value: 'Equity LTP', enabled: true },
    { value: 'Index Data', enabled: false, reason: 'Not available from Dhan API. Use manual upload.' },
    { value: 'Holdings', enabled: true },
    { value: 'Positions', enabled: true },
    { value: 'Orders', enabled: true },
    { value: 'Trades', enabled: true },
    { value: 'Funds / Margin', enabled: true },
    { value: 'Corporate Actions', enabled: false, reason: 'Not available from Dhan API.' },
    { value: 'Instrument Master', enabled: true },
    { value: 'FII/DII Data', enabled: false, reason: 'Not available from Dhan API. Use manual upload.' },
    { value: 'Delivery Volume', enabled: false, reason: 'Not available from Dhan API. Use manual upload.' },
    { value: 'Fundamentals', enabled: false, reason: 'Not available from Dhan API. Use manual upload.' },
  ];
  dhanDataTypeMessage = '';
  private dhanContinuePromptedFor: any = null;
  warehouseTab: 'technical' | 'fundamental' = 'technical';
  showFetchLogs = false;
  fetchLogs: any[] = [];
  recentFetchLogs: any[] = [];
  dashStats: any = null;
  fetchProgress: any = null;
  private fetchProgressPoller: any = null;

  constructor(private api: ApiService, private http: HttpClient) {}

  ngOnInit() {
    this.api.get<any>('/data').subscribe({
      next: r => {
        this.rows = r.data?.dataTypes || this.defaultRows();
        this.backendTables = r.data?.backendTables || this.defaultBackendTables();
        this.backendTablePage = 1;
        this.dependentSections = r.data?.dependentSections || this.defaultDependentSections();
        this.activeActivity = { status: 'idle' };
        this.initForms();
        this.loadActiveBrokers();
        this.loadDhanBackfillJob();
        this.loadStockPreview();
        this.loadSmStats();
        this.loadCleanupSummary();
        this.loadDashboardStats();
        this.loadRecentFetchLogs();
        this.loading.set(false);
      },
      error: () => {
        this.rows = this.defaultRows();
        this.backendTables = this.defaultBackendTables();
        this.backendTablePage = 1;
        this.dependentSections = this.defaultDependentSections();
        this.initForms();
        this.loadActiveBrokers();
        this.loadDhanBackfillJob();
        this.loadStockPreview();
        this.loadSmStats();
        this.loadCleanupSummary();
        this.loadDashboardStats();
        this.loading.set(false);
      }
    });
  }

  ngOnDestroy() {
    this.stopDhanBackfillPolling();
    this.stopDhanInstrumentPolling();
    this.stopFetchProgressPolling();
  }

  initForms() {
    const today = new Date().toISOString().slice(0, 10);
    for (const row of this.rows) {
      this.selectedSources[row.dataType] ||= row.sources[0] || 'Manual';
      this.selectedBrokers[row.dataType] ||= this.activeBrokers[0]?.id || null;
      this.dateForms[row.dataType] ||= { fromDate: today, toDate: today };
    }
  }

  isManual(row: WarehouseRow): boolean {
    return (this.selectedSources[row.dataType] || '').toLowerCase() === 'manual';
  }

  isBroker(row: WarehouseRow): boolean {
    return !this.isStockList(row) && (this.selectedSources[row.dataType] || '').toLowerCase() === 'broker';
  }

  requiresDate(row: WarehouseRow): boolean {
    return !this.isManual(row) && row.dataType.toLowerCase() !== 'stock list';
  }

  isActivityRunning(): boolean {
    return this.activeActivity?.status === 'running' || this.isDhanBackfillRunning();
  }

  rowStatus(row: WarehouseRow): string {
    if (this.activeActivity?.dataType !== row.dataType) return 'Ready';
    return this.activeActivity?.status || 'Ready';
  }

  fetchData(row: WarehouseRow) {
    const form = this.dateForms[row.dataType];
    if (this.isBroker(row) && !this.isStockList(row) && !this.selectedBrokers[row.dataType]) {
      alert('Please connect and verify a broker first, then select it here.');
      return;
    }
    const source = this.selectedSources[row.dataType];
    if (this.isStockList(row)) {
      this.refreshDhanInstruments();
      return;
    }
    this.beginActivity({
      activityName: `Fetch ${row.dataType}`,
      dataType: row.dataType,
      source,
      message: `Fetching ${row.dataType} from ${source}. Saving records to the database when the fetch completes.`
    });
    this.startFetchProgressPolling();
    this.api.post<any>('/data/activity/fetch', {
      dataType: row.dataType,
      source,
      brokerCredentialId: this.isStockList(row) ? null : this.selectedBrokers[row.dataType],
      fromDate: form.fromDate,
      toDate: form.toDate
    }).subscribe({
      next: r => {
        if (r?.success === false) {
          this.activeActivity = {
            ...this.activeActivity,
            status: 'failed',
            message: this.errorMessage({ error: r }, 'Fetch could not be started.'),
            errorCode: r.errorCode || '',
            details: r.data || {}
          };
          return;
        }
        const data = r.data || {};
        this.activeActivity = this.normalizeActivity(data, {
          status: data.status || 'completed',
          activityName: `Fetch ${row.dataType}`,
          dataType: row.dataType,
          source,
          message: data.message || r.message || `${row.dataType} fetch completed and database save finished.`,
          details: {
            steps: data.steps || [],
            fetchedDates: data.fetchedDates || data.details || [],
            skippedDates: data.skippedDates || [],
            errorCode: data.errorCode || '',
            duration: data.duration || '',
            ...data
          }
        });
        this.stopFetchProgressPolling();
        this.loadCleanupSummary();
        this.loadStockPreview();
        this.loadSmStats();
        this.loadDashboardStats();
      },
      error: e => {
        this.stopFetchProgressPolling();
        this.activeActivity = { ...this.activeActivity, status: 'failed', message: this.errorMessage(e, 'Fetch could not be started.') };
      }
    });
  }

  loadActiveBrokers() {
    this.api.get<any>('/broker/active').subscribe({
      next: r => {
        this.activeBrokers = r.data || [];
        this.activeDhanBrokers = this.activeBrokers.filter(broker =>
          String(broker.broker_name || '').toLowerCase() === 'dhan'
        );
        for (const row of this.rows) {
          this.selectedBrokers[row.dataType] ||= this.activeBrokers[0]?.id || null;
        }
        if (!this.activeDhanBrokers.some(broker => broker.id === this.dhanBackfill.brokerCredentialId)) {
          this.dhanBackfill.brokerCredentialId = this.activeDhanBrokers[0]?.id || null;
        }
      }
    });
  }

  manualUpload(row: WarehouseRow, event: Event) {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files || []);
    if (!files.length) return;
    if (files.length > 300) {
      alert('Maximum 300 files can be uploaded at a time.');
      input.value = '';
      return;
    }
    const large = files.find(file => file.size > 2 * 1024 * 1024);
    if (large) {
      alert(`${large.name} is larger than 2 MB.`);
      input.value = '';
      return;
    }

    const fd = new FormData();
    fd.append('dataType', row.dataType);
    fd.append('source', 'Manual');
    files.forEach(file => fd.append('files', file));
    this.beginActivity({
      activityName: `Upload ${row.dataType}`,
      dataType: row.dataType,
      source: 'Manual',
      message: `Uploading ${files.length} file(s) and saving parsed records to the database.`
    });
    this.api.upload<any>('/data/activity/manual-upload', fd).subscribe({
      next: r => {
        if (r?.success === false) {
          this.activeActivity = { ...this.activeActivity, status: 'failed', message: this.errorMessage({ error: r }, 'Upload could not be started.') };
          return;
        }
        this.activeActivity = this.normalizeActivity(r.data, {
          status: 'completed',
          activityName: `Upload ${row.dataType}`,
          dataType: row.dataType,
          source: 'Manual',
          message: `${row.dataType} upload completed and database save finished.`
        });
        input.value = '';
        this.loadCleanupSummary();
        this.loadStockPreview();
      },
      error: e => this.activeActivity = { ...this.activeActivity, status: 'failed', message: this.errorMessage(e, 'Upload could not be started.') }
    });
  }

  downloadBackendTable(table: BackendTable) {
    this.http.get(`${environment.apiUrl}/data/backend-tables/${table.key}/download`, {
      responseType: 'blob',
      observe: 'response'
    }).subscribe({
      next: response => {
        let filename = `${table.tableName}_download.csv`;
        const contentDisp = response.headers.get('Content-Disposition');
        const match = contentDisp?.match(/filename="?([^"]+)"?/);
        if (match?.[1]) filename = match[1];
        const blob = response.body!;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        URL.revokeObjectURL(url);
        document.body.removeChild(a);
      },
      error: e => alert(this.errorMessage(e, 'Table download could not be completed.'))
    });
  }

  uploadBackendTable(table: BackendTable, event: Event) {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files || []);
    if (!files.length) return;
    const fd = new FormData();
    files.forEach(file => fd.append('files', file));
    this.beginActivity({
      activityName: `Upload ${table.label}`,
      dataType: table.label,
      tableName: table.tableName,
      source: 'Backend Table Upload',
      message: `Uploading ${files.length} file(s) and saving rows into ${table.tableName}.`
    });
    this.api.upload<any>(`/data/backend-tables/${table.key}/upload`, fd).subscribe({
      next: r => {
        if (r?.success === false) {
          this.activeActivity = { ...this.activeActivity, status: 'failed', message: this.errorMessage({ error: r }, 'Backend table upload could not be started.') };
          return;
        }
        this.activeActivity = this.normalizeActivity(r.data, {
          status: 'completed',
          activityName: `Upload ${table.label}`,
          dataType: table.label,
          tableName: table.tableName,
          source: 'Backend Table Upload',
          message: `${table.tableName} upload completed and database save finished.`
        });
        input.value = '';
        this.loadCleanupSummary();
        this.loadStockPreview();
      },
      error: e => this.activeActivity = { ...this.activeActivity, status: 'failed', message: this.errorMessage(e, 'Backend table upload could not be started.') }
    });
  }

  cleanBackendTable(table: BackendTable) {
    const ok = confirm(`Clean/Delete all records from ${table.tableName}?\n\nThis removes table data and cannot be undone unless you restore from backup.`);
    if (!ok) return;
    this.beginActivity({
      activityName: `Clean ${table.label}`,
      dataType: table.label,
      tableName: table.tableName,
      source: 'Backend Table Clean',
      message: `Removing records from ${table.tableName}.`
    });
    this.api.post<any>(`/data/backend-tables/${table.key}/clean`, {}).subscribe({
      next: r => {
        this.activeActivity = this.normalizeActivity(r.data, {
          status: 'completed',
          activityName: `Clean ${table.label}`,
          dataType: table.label,
          tableName: table.tableName,
          source: 'Backend Table Clean',
          message: `${table.tableName} cleaned successfully.`
        });
        this.loadCleanupSummary();
        this.loadStockPreview();
        this.loadBackendTables();
      },
      error: e => this.activeActivity = { ...this.activeActivity, status: 'failed', message: this.errorMessage(e, 'Backend table could not be cleaned.') }
    });
  }

  loadBackendTables() {
    this.api.get<any>('/data/backend-tables').subscribe({
      next: r => {
        this.backendTables = r.data || this.defaultBackendTables();
        if (this.backendTablePage > this.backendTableTotalPages()) {
          this.backendTablePage = this.backendTableTotalPages();
        }
      },
      error: () => this.backendTables = this.defaultBackendTables()
    });
  }

  loadStockPreview() {
    this.stockPreviewError = '';
    this.smLoading = true;
    const params: any = {
      search: this.stockPreviewSearch || '',
      limit: this.stockPreviewLimit,
      page: this.stockPreviewPage
    };
    if (this.smFilterSource) params.source = this.smFilterSource;
    if (this.smFilterStatus) params.status = this.smFilterStatus;
    if (this.smFilterExchange) params.exchange = this.smFilterExchange;
    if (this.smFilterActionType) params.actionType = this.smFilterActionType;
    if (this.smFilterDateFrom) params.dateFrom = this.smFilterDateFrom;
    if (this.smFilterDateTo) params.dateTo = this.smFilterDateTo;
    this.api.get<any>('/data/stock-master/preview', params).subscribe({
      next: r => {
        this.stockPreview = r.data?.rows || [];
        this.stockPreviewTotal = r.data?.totalRows || 0;
        this.smLoading = false;
      },
      error: () => {
        this.stockPreview = [];
        this.stockPreviewTotal = 0;
        this.stockPreviewError = 'Unable to load Stock Master Preview. Please refresh again or check database connection.';
        this.smLoading = false;
      }
    });
  }

  loadSmStats() {
    this.api.get<any>('/data/stock-master/stats').subscribe({
      next: r => this.smStats = r.data || null,
      error: () => this.smStats = null
    });
  }

  loadSmLogs() {
    this.api.get<any>('/data/stock-master/logs', { limit: 20 }).subscribe({
      next: r => this.smLogs = r.data || [],
      error: () => this.smLogs = []
    });
  }

  hasSmFilters(): boolean {
    return !!(this.smFilterSource || this.smFilterStatus || this.smFilterExchange || this.smFilterActionType || this.smFilterDateFrom || this.smFilterDateTo);
  }

  clearSmFilters() {
    this.smFilterSource = '';
    this.smFilterStatus = '';
    this.smFilterExchange = '';
    this.smFilterActionType = '';
    this.smFilterDateFrom = '';
    this.smFilterDateTo = '';
    this.stockPreviewSearch = '';
    this.stockPreviewPage = 0;
    this.loadStockPreview();
  }

  smStatusBadge(status: string): string {
    if (!status) return 'badge-slate';
    const s = status.toLowerCase();
    if (['active','imported','completed'].includes(s)) return 'badge-green';
    if (['processing','pending'].includes(s)) return 'badge-yellow';
    if (['failed','invalid','invalid format','error'].includes(s)) return 'badge-red';
    if (['duplicate','skipped'].includes(s)) return 'badge-orange';
    if (['partially imported','partial'].includes(s)) return 'badge-blue';
    return 'badge-slate';
  }

  smLogStatusBadge(status: string): string {
    if (!status) return 'badge-slate';
    const s = status.toLowerCase();
    if (['completed','success'].includes(s)) return 'badge-green';
    if (['running','processing'].includes(s)) return 'badge-yellow';
    if (['failed','error'].includes(s)) return 'badge-red';
    return 'badge-slate';
  }

  fetchStockMasterAction() {
    const stockListRow = this.rows.find(r => r.dataType.toLowerCase() === 'stock list');
    if (stockListRow) {
      this.refreshDhanInstruments();
    } else {
      this.smErrorMsg = 'No Stock List data type configured. Please use the Dhan backfill section to fetch stock list.';
    }
  }

  uploadStockMasterFile(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    const ext = file.name.split('.').pop()?.toLowerCase() || '';
    if (!['csv', 'xlsx', 'json'].includes(ext)) {
      this.smErrorMsg = 'Invalid file type. Please upload CSV, XLSX, or JSON file.';
      input.value = '';
      return;
    }
    if (file.size > 2 * 1024 * 1024) {
      this.smErrorMsg = 'File size exceeds 2 MB limit. Please upload a smaller file.';
      input.value = '';
      return;
    }
    const fd = new FormData();
    fd.append('dataType', 'Stock List');
    fd.append('source', 'Manual');
    fd.append('files', file);
    this.smErrorMsg = '';
    this.smSuccessMsg = '';
    this.beginActivity({
      activityName: 'Upload Stock Master',
      dataType: 'Stock List',
      source: 'Manual',
      message: `Uploading ${file.name} and saving stock master records.`
    });
    this.api.upload<any>('/data/activity/manual-upload', fd).subscribe({
      next: r => {
        if (r?.success === false) {
          this.activeActivity = { ...this.activeActivity, status: 'failed', message: this.errorMessage({ error: r }, 'Upload failed.') };
          this.smErrorMsg = r.message || 'Upload failed.';
          return;
        }
        this.activeActivity = this.normalizeActivity(r.data, {
          status: 'completed', activityName: 'Upload Stock Master', dataType: 'Stock List', source: 'Manual',
          message: 'Stock master upload completed.'
        });
        const data = r.data || {};
        this.smSuccessMsg = `Imported: ${data.rowsImported || 0}\nFailed: ${data.filesFailed || 0}\nFiles: ${data.fileCount || 1}`;
        this.loadStockPreview();
        this.loadSmStats();
        this.loadCleanupSummary();
        input.value = '';
      },
      error: e => {
        this.activeActivity = { ...this.activeActivity, status: 'failed', message: this.errorMessage(e, 'Upload failed.') };
        this.smErrorMsg = this.errorMessage(e, 'Stock master file could not be uploaded.');
        input.value = '';
      }
    });
  }

  downloadStockMasterSample() {
    const csv = 'symbol,company_name,exchange,isin,series,sector,industry\nRELIANCE,Reliance Industries Limited,NSE,INE002A01018,EQ,Energy,Oil Gas & Consumable Fuels\nTCS,Tata Consultancy Services Limited,NSE,INE467B01029,EQ,Information Technology,IT Services\n';
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = 'stock_master_sample_format.csv';
    document.body.appendChild(a); a.click();
    URL.revokeObjectURL(url); document.body.removeChild(a);
  }

  downloadStockMasterPreview() {
    if (!this.stockPreview.length) return;
    const headers = ['Date & Time','Symbol','Company Name','Exchange','ISIN','Sector','Industry','Series','Source','Action Type','Status','Remarks'];
    const rows = this.stockPreview.map(r => [
      r.dateTime || '', r.symbol || '', r.companyName || '', r.exchange || '', r.isin || '',
      r.sector || '', r.industry || '', r.series || '', r.source || '', r.actionType || '',
      r.status || '', r.remarks || ''
    ]);
    const csv = [headers.join(','), ...rows.map(r => r.map(v => `"${String(v).replace(/"/g, '""')}"`).join(','))].join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = 'stock_master_preview.csv';
    document.body.appendChild(a); a.click();
    URL.revokeObjectURL(url); document.body.removeChild(a);
  }

  enabledDhanDataTypes() {
    return this.dhanBackfillDataTypes.filter(dt => dt.enabled);
  }

  loadCleanupSummary() {
    this.api.get<any>('/data/cleanup/summary').subscribe({
      next: r => this.cleanupSummary = r.data || {},
      error: () => this.cleanupSummary = {}
    });
  }

  cleanupDuplicates() {
    if (!confirm('Clean duplicate Stock Master records now?')) return;
    this.api.post<any>('/data/cleanup/stock-master-duplicates', {}).subscribe({
      next: r => {
        this.cleanupSummary = r.data?.summary || this.cleanupSummary;
        this.loadStockPreview();
        alert(r.message || 'Cleanup completed.');
      },
      error: e => alert(e.error?.message || 'Cleanup could not be completed.')
    });
  }

  createDatabaseBackup() {
    this.api.post<any>('/data/database/backup', {}).subscribe({
      next: r => {
        this.backupResult = r.data || null;
        this.loadCleanupSummary();
        alert(r.message || 'Backup completed.');
      },
      error: e => alert(e.error?.message || 'Backup could not be created.')
    });
  }

  uploadDatabaseBackup(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    const fd = new FormData();
    fd.append('file', file);
    this.api.upload<any>('/data/database/backup/upload', fd).subscribe({
      next: r => {
        alert(r.message || 'Backup file uploaded.');
        input.value = '';
        this.loadCleanupSummary();
      },
      error: e => alert(e.error?.message || 'Backup file could not be uploaded.')
    });
  }

  removalStatus: any = null;
  private removalTimer: any = null;

  removeDatabaseData() {
    if (this.databaseConfirmation !== 'REMOVE DATABASE') {
      alert('Type REMOVE DATABASE to confirm.');
      return;
    }
    if (!confirm('This takes a backup first, then clears application data. Continue?')) return;
    this.api.post<any>('/data/database/remove', { confirmation: this.databaseConfirmation }).subscribe({
      next: () => {
        this.databaseConfirmation = '';
        this.removalStatus = { status: 'running', phase: 'Starting', percent: 0,
                               message: 'Starting — a backup is taken before anything is removed.' };
        this.pollRemovalStatus();
      },
      error: e => alert(e.error?.message || 'The database data could not be removed.')
    });
  }

  /** Removal runs in the background, so the screen polls until it finishes. */
  private pollRemovalStatus(): void {
    this.stopRemovalPolling();
    this.removalTimer = setInterval(() => {
      this.api.get<any>('/data/database/remove/status').subscribe({
        next: r => {
          const st = r.data || {};
          this.removalStatus = st;
          if (st.status === 'completed' || st.status === 'failed') {
            this.stopRemovalPolling();
            if (st.status === 'completed') {
              this.backupResult = st.backup || null;
              this.loadCleanupSummary();
              this.loadStockPreview();
            }
            alert(st.message || 'Database removal finished.');
          }
        },
        error: () => this.stopRemovalPolling()
      });
    }, 1000);
  }

  private stopRemovalPolling(): void {
    if (this.removalTimer) { clearInterval(this.removalTimer); this.removalTimer = null; }
  }

  stopActivity() {
    this.api.post<any>('/data/activity/stop', {}).subscribe({
      next: r => {
        this.stopDhanBackfillPolling();
        this.stopDhanInstrumentPolling();
        this.dhanBackfillJob = { ...this.dhanBackfillJob, status: 'stopped', message: r.data?.message || 'Activity stopped by user.' };
        this.activeActivity = r.data || { status: 'stopped' };
      },
      error: () => {
        this.stopDhanBackfillPolling();
        this.stopDhanInstrumentPolling();
        this.dhanBackfillJob = { ...this.dhanBackfillJob, status: 'stopped' };
        this.activeActivity = { ...this.activeActivity, status: 'stopped', message: 'Activity stopped locally.' };
      }
    });
  }

  clearActivity() {
    this.stopDhanBackfillPolling();
    this.stopDhanInstrumentPolling();
    this.activeActivity = { status: 'idle' };
  }

  getSkippedDates(): any[] {
    const details = this.activeActivity?.details;
    return details?.skippedDates || details?.skipped_dates || [];
  }

  getFetchedDates(): any[] {
    const details = this.activeActivity?.details;
    return details?.details || [];
  }

  refreshDhanInstruments() {
    this.beginActivity({
      activityName: 'Fetch Public Dhan Stock List',
      dataType: 'Stock List',
      source: 'Dhan Public',
      targetTable: 'dhan_instruments / broker_instruments / stock_master',
      currentStep: 'Starting',
      processedRows: 0,
      totalRows: 100,
      message: 'Fetching Dhan public instrument master and saving stock list rows to the database.'
    });
    this.startDhanInstrumentPolling();
    this.api.post<any>('/data/dhan/instruments/refresh', {}).subscribe({
      next: r => {
        this.activeActivity = this.normalizeActivity(r.data, {
          status: r.data?.status || 'running',
          activityName: 'Fetch Public Dhan Stock List',
          dataType: 'Stock List',
          source: 'Dhan Public',
          targetTable: 'dhan_instruments / broker_instruments / stock_master',
          message: r.message || 'Public Dhan stock list refresh completed.'
        });
        if (String(this.activeActivity.status || '').toLowerCase() === 'running') {
          this.startDhanInstrumentPolling();
        } else {
          this.stopDhanInstrumentPolling();
          this.loadCleanupSummary();
          this.loadStockPreview();
          this.loadSmStats();
        }
      },
      error: e => {
        this.stopDhanInstrumentPolling();
        this.activeActivity = { ...this.activeActivity, status: 'failed', message: e.error?.message || 'Public Dhan stock list refresh could not be started.' };
      }
    });
  }

  loadDhanInstrumentRefreshStatus() {
    this.api.get<any>('/data/dhan/instruments/refresh/status').subscribe({
      next: r => {
        const status = r.data || { status: 'idle' };
        if (status.status && status.status !== 'idle') {
          this.activeActivity = this.normalizeActivity(status, {
            activityName: 'Fetch Public Dhan Stock List',
            dataType: 'Stock List',
            source: 'Dhan Public',
            targetTable: 'dhan_instruments / broker_instruments / stock_master',
            message: status.message || 'Fetching Dhan public instrument master.'
          });
        }
        if (['completed', 'failed', 'stopped'].includes(String(status.status || '').toLowerCase())) {
          this.stopDhanInstrumentPolling();
          this.loadCleanupSummary();
          this.loadStockPreview();
          this.loadSmStats();
        }
      },
      error: e => {
        this.stopDhanInstrumentPolling();
        this.activeActivity = { ...this.activeActivity, status: 'failed', message: this.errorMessage(e, 'Could not read Dhan Stock List fetch progress.') };
      }
    });
  }

  startDhanBackfill(continueOnMissingData = false) {
    if (!this.dhanBackfill.brokerCredentialId) {
      this.activeActivity = {
        status: 'failed',
        showModal: false,
        activityName: 'Dhan historical OHLCV backfill',
        dataType: 'Dhan Historical Candles',
        source: 'DHAN',
        message: 'Please connect and verify Dhan broker first. Stock List is public, but Dhan candle backfill needs your access token.'
      };
      return;
    }
    this.dhanContinuePromptedFor = null;
    this.beginActivity({
      activityName: 'Dhan historical OHLCV backfill',
      dataType: 'Dhan Historical Candles',
      source: 'DHAN',
      targetTable: 'broker_market_candles',
      message: 'Starting Dhan historical candle backfill and preparing progress tracking.'
    });
    this.api.post<any>('/data/dhan/historical-backfill', {
      ...this.dhanBackfill,
      dataType: this.dhanBackfillDataType,
      continueOnMissingData: true
    }).subscribe({
      next: r => {
        this.activeActivity = {
          ...this.activeActivity,
          status: 'running',
          activityName: 'Dhan historical OHLCV backfill',
          dataType: 'Dhan Historical Candles',
          source: 'DHAN',
          message: r.data?.message || 'Dhan historical backfill started.'
        };
        this.loadDhanBackfillJob();
      },
      error: e => this.activeActivity = { ...this.activeActivity, status: 'failed', message: e.error?.message || 'Dhan historical backfill could not be started.' }
    });
  }

  loadDhanBackfillJob() {
    this.api.get<any>('/data/dhan/historical-backfill/latest').subscribe({
      next: r => {
        this.dhanBackfillJob = r.data || { status: 'idle' };
        this.loadDhanBatches();
        if (this.isDhanBackfillRunning()) {
          this.startDhanBackfillPolling();
          this.activeActivity = {
            status: this.dhanBackfillJob.status,
            showModal: this.activeActivity?.showModal !== false,
            activityName: 'Dhan historical OHLCV backfill',
            dataType: 'Dhan Historical Candles',
            source: 'DHAN',
            startedAt: this.dhanBackfillJob.createdAt || this.activeActivity?.startedAt,
            startedAtMs: this.activeActivity?.startedAtMs,
            completedAt: this.dhanBackfillJob.completedAt || this.activeActivity?.completedAt,
            rowsImported: this.dhanBackfillJob.importedCandles || 0,
            targetTable: 'broker_market_candles / market_data',
            message: this.dhanBackfillJob.message || 'Dhan historical backfill is running.',
            details: this.dhanBackfillJob
          };
        } else {
          this.stopDhanBackfillPolling();
          if (this.dhanBackfillJob?.status && this.dhanBackfillJob.status !== 'idle') {
            this.activeActivity = {
              status: this.dhanBackfillJob.status,
              showModal: false,
              activityName: 'Dhan historical OHLCV backfill',
              dataType: 'Dhan Historical Candles',
              source: 'DHAN',
              startedAt: this.dhanBackfillJob.createdAt || this.activeActivity?.startedAt,
              completedAt: this.dhanBackfillJob.completedAt || this.activeActivity?.completedAt || new Date().toLocaleString('en-IN'),
              rowsImported: this.dhanBackfillJob.importedCandles || 0,
              targetTable: 'broker_market_candles / market_data',
              message: this.dhanBackfillJob.message || 'Dhan historical backfill finished.',
              details: this.dhanBackfillJob
            };
          }
        }
      }
    });
  }

  promptContinueDhanBackfill() {
    this.loadDhanBackfillJob();
  }

  stopDhanBackfill() {
    this.api.post<any>('/data/dhan/historical-backfill/stop', {}).subscribe({
      next: r => {
        this.dhanBackfillJob = r.data || this.dhanBackfillJob;
        this.stopDhanBackfillPolling();
        this.activeActivity = { ...this.activeActivity, status: 'stopped', message: 'Dhan historical backfill stopped by user.' };
      },
      error: e => this.activeActivity = { ...this.activeActivity, status: 'failed', message: e.error?.message || 'Dhan historical backfill stop could not be requested.' }
    });
  }

  overrideFor(symbol: string): any {
    const key = (symbol || '').trim().toUpperCase();
    return this.symbolOverrides.find(o => (o.originalSymbol || '').trim().toUpperCase() === key) || null;
  }

  loadSymbolOverrides(): void {
    this.api.get<any>('/data/dhan/historical-backfill/symbol-overrides').subscribe({
      next: r => { this.symbolOverrides = r.data || []; },
      error: () => { this.symbolOverrides = []; }
    });
  }

  /** One stock at a time. The server works out whether the text is a name, an ISIN, or an ID. */
  saveCorrection(symbol: string): void {
    const typed = (this.correctionInput[symbol] || '').trim();
    if (!typed) return;
    this.postCorrections([{ originalSymbol: symbol, correctedSymbol: typed }]);
  }

  private postCorrections(entries: any[]): void {
    this.overrideSaving = true;
    this.api.post<any>('/data/dhan/historical-backfill/symbol-overrides', entries).subscribe({
      next: r => {
        this.overrideSaving = false;
        const d = r.data || {};
        this.overrideOk = (d.unresolved || 0) === 0;
        this.overrideMessage = d.message || 'Corrections saved.';
        for (const e of entries) delete this.correctionInput[e.originalSymbol];
        this.loadSymbolOverrides();
      },
      error: e => {
        this.overrideSaving = false;
        this.overrideOk = false;
        this.overrideMessage = this.errorMessage(e, 'The correction could not be saved.');
      }
    });
  }

  /** Plain symbol,from,to,status,reason list - editable in Notepad, re-uploadable as-is. */
  downloadMissingData(): void {
    this.http.get(`${environment.apiUrl}/data/dhan/historical-backfill/missing-data/download`, {
      responseType: 'blob', observe: 'response'
    }).subscribe({
      next: response => {
        let filename = 'missing-data.csv';
        const match = response.headers.get('Content-Disposition')?.match(/filename="?([^"]+)"?/);
        if (match?.[1]) filename = match[1];
        const url = URL.createObjectURL(response.body!);
        const a = document.createElement('a');
        a.href = url; a.download = filename;
        document.body.appendChild(a); a.click();
        URL.revokeObjectURL(url); document.body.removeChild(a);
        this.overrideOk = true;
        this.overrideMessage = 'Downloaded. Edit if you like, then use "Upload & Fetch" to pull those stocks and dates.';
      },
      error: e => {
        this.overrideOk = false;
        this.overrideMessage = this.errorMessage(e, 'Could not download the missing-data list.');
      }
    });
  }

  /** Fetch exactly the stocks and dates in an uploaded list. */
  uploadAndFetch(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    this.overrideOk = true;
    this.overrideMessage = `Reading ${file.name}...`;
    this.http.post<any>(`${environment.apiUrl}/data/dhan/historical-backfill/fetch-from-file`, form).subscribe({
      next: r => {
        const d = r.data || {};
        this.overrideOk = true;
        this.overrideMessage = `Fetching ${d.symbols_requested ?? 0} stock(s), ${d.from_date} to ${d.to_date}.`
          + (d.lines_ignored ? ` ${d.lines_ignored} line(s) ignored.` : '');
        this.loadDhanBackfillJob();
      },
      error: e => {
        this.overrideOk = false;
        this.overrideMessage = this.errorMessage(e, 'Could not start the fetch from that file.');
      }
    });
    input.value = '';
  }

  downloadSkippedStocks(): void {
    this.http.get(`${environment.apiUrl}/data/dhan/historical-backfill/skipped-symbols/download`, {
      responseType: 'blob',
      observe: 'response'
    }).subscribe({
      next: response => {
        let filename = 'skipped-stocks.csv';
        const match = response.headers.get('Content-Disposition')?.match(/filename="?([^"]+)"?/);
        if (match?.[1]) filename = match[1];
        const url = URL.createObjectURL(response.body!);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        URL.revokeObjectURL(url);
        document.body.removeChild(a);
        this.overrideOk = true;
        this.overrideMessage = 'Downloaded. Fill in the "Corrected Symbol", "Corrected ISIN", or "Corrected Security ID" column, then use Upload Corrections.';
      },
      error: e => {
        this.overrideOk = false;
        this.overrideMessage = this.errorMessage(e, 'The skipped stock list could not be downloaded.');
      }
    });
  }

  uploadCorrections(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    this.overrideSaving = true;
    this.http.post<any>(`${environment.apiUrl}/data/dhan/historical-backfill/symbol-overrides/upload`, form).subscribe({
      next: r => {
        this.overrideSaving = false;
        const d = r.data || {};
        this.overrideOk = (d.unresolved || 0) === 0;
        this.overrideMessage = d.message || 'Corrections uploaded.';
        this.loadSymbolOverrides();
        input.value = '';
      },
      error: e => {
        this.overrideSaving = false;
        this.overrideOk = false;
        this.overrideMessage = this.errorMessage(e, 'That file could not be read. Use the CSV from Download List.');
        input.value = '';
      }
    });
  }

  downloadDhanBackfillErrorLog() {
    this.http.get(`${environment.apiUrl}/data/dhan/historical-backfill/errors/download`, {
      responseType: 'blob',
      observe: 'response'
    }).subscribe({
      next: response => {
        let filename = 'Dhan_Historical_Error_Log.csv';
        const contentDisp = response.headers.get('Content-Disposition');
        const match = contentDisp?.match(/filename="?([^"]+)"?/);
        if (match?.[1]) filename = match[1];
        const blob = response.body!;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        URL.revokeObjectURL(url);
        document.body.removeChild(a);
      },
      error: e => this.activeActivity = { ...this.activeActivity, status: 'failed', message: this.errorMessage(e, 'Dhan historical error log could not be downloaded.') }
    });
  }

  retryFailedDhanBackfill() {
    this.beginActivity({
      activityName: 'Retry failed Dhan historical symbols',
      dataType: 'Dhan Historical Candles',
      source: 'DHAN',
      targetTable: 'broker_market_candles',
      message: 'Starting retry for failed and skipped Dhan historical symbols.'
    });
    this.api.post<any>('/data/dhan/historical-backfill/retry-failed', {}).subscribe({
      next: r => {
        this.activeActivity = {
          ...this.activeActivity,
          status: 'running',
          message: r.data?.message || 'Retry for failed and skipped Dhan symbols started.'
        };
        this.loadDhanBackfillJob();
      },
      error: e => this.activeActivity = { ...this.activeActivity, status: 'failed', message: this.errorMessage(e, 'Retry failed Dhan symbols could not be started.') }
    });
  }

  isDhanBackfillRunning(): boolean {
    return ['queued', 'running'].includes((this.dhanBackfillJob?.status || '').toLowerCase());
  }

  dhanBackfillProgressPct(): number {
    const total = Number(this.dhanBackfillJob?.totalChunks || 0);
    const done = Number(this.dhanBackfillJob?.processedChunks || 0);
    if (!total) return 0;
    return Math.min(100, Math.round((done / total) * 100));
  }

  onDhanDataTypeChange() {
    const selected = this.dhanBackfillDataTypes.find(d => d.value === this.dhanBackfillDataType);
    if (selected && !selected.enabled) {
      this.dhanDataTypeMessage = (selected as any).reason || 'This data type is not available from Dhan API. Please use manual upload or another source.';
    } else {
      this.dhanDataTypeMessage = '';
      if (this.dhanBackfillDataType === 'Intraday OHLCV') {
        this.dhanBackfill.mode = 'INTRADAY';
      } else if (this.dhanBackfillDataType === 'Futures Data') {
        this.dhanBackfill.mode = 'DAILY';
        this.dhanBackfill.instrumentFilter = 'FUTSTK';
      } else {
        this.dhanBackfill.mode = 'DAILY';
      }
    }
  }

  canResumeBackfill(): boolean {
    const status = (this.dhanBackfillJob?.status || '').toLowerCase();
    return ['stopped', 'failed'].includes(status);
  }

  loadDhanBatches() {
    this.api.get<any>('/data/dhan/historical-backfill/batches').subscribe({
      next: r => this.dhanBatches = (r.data || []).map((b: any) => ({
        id: b.id, monthLabel: b.month_label || b.monthLabel || '-',
        fromDate: b.from_date || b.fromDate, toDate: b.to_date || b.toDate,
        status: b.status || 'pending',
        totalInstruments: b.total_instruments ?? b.totalInstruments ?? 0,
        processedInstruments: b.processed_instruments ?? b.processedInstruments ?? 0,
        importedCandles: b.imported_candles ?? b.importedCandles ?? 0,
        failedInstruments: b.failed_instruments ?? b.failedInstruments ?? 0,
        successfulInstruments: b.successful_instruments ?? b.successfulInstruments ?? 0,
      })),
      error: () => this.dhanBatches = []
    });
  }

  /**
   * Continue a stopped or failed backfill over the days it never reached.
   *
   * This used to call retryFailedDhanBackfill(), which only reprocesses symbols that
   * actually errored. A run killed part-way through has no failed symbols - it simply
   * never attempted the remaining months - so Resume reported "No failed, skipped, or
   * unprocessed symbols found" and did nothing, while the help text beside it promised
   * it would continue from where it left off.
   */
  resumeDhanBackfill() {
    const outstanding = this.dhanBatches.filter(b => b.status !== 'completed');
    if (!outstanding.length) {
      this.activeActivity = { ...this.activeActivity, status: 'idle',
        message: 'Every month in this range is already complete - nothing to resume.' };
      return;
    }
    const from = outstanding.map(b => b.fromDate).filter(Boolean).sort()[0];
    const to = outstanding.map(b => b.toDate).filter(Boolean).sort().slice(-1)[0];
    const job = this.dhanBackfillJob || {};
    this.activeActivity = {
      activityName: 'Dhan historical OHLCV backfill (resume)',
      dataType: 'Dhan Historical Candles', source: 'DHAN',
      targetTable: 'broker_market_candles', status: 'running',
      message: `Resuming ${from} to ${to} (${outstanding.length} month(s) outstanding).`
    };
    this.api.post<any>('/data/dhan/historical-backfill', {
      ...this.dhanBackfill,
      mode: job.mode || this.dhanBackfill.mode,
      intervalMinutes: job.intervalMinutes || this.dhanBackfill.intervalMinutes,
      fromDate: from,
      toDate: to,
      dataType: this.dhanBackfillDataType,
      continueOnMissingData: true
    }).subscribe({
      next: r => {
        this.activeActivity = { ...this.activeActivity, status: 'running',
          message: r.data?.message || r.message || `Resumed ${from} to ${to}.` };
        this.loadDhanBackfillJob();
        this.loadDhanBatches();
      },
      error: e => this.activeActivity = { ...this.activeActivity, status: 'failed',
        message: this.errorMessage(e, 'Could not resume the backfill.') }
    });
  }

  retryDhanBatch(batchId: number) {
    this.api.post<any>(`/data/dhan/historical-backfill/batches/${batchId}/retry`, {}).subscribe({
      next: r => {
        this.activeActivity = { ...this.activeActivity, status: 'running', message: r.message || 'Batch retry started.' };
        this.loadDhanBackfillJob();
      },
      error: e => this.activeActivity = { ...this.activeActivity, status: 'failed', message: this.errorMessage(e, 'Batch retry failed.') }
    });
  }

  dhanBatchCompletedCount(): number {
    return this.dhanBatches.filter(b => b.status === 'completed').length;
  }

  batchProgressPct(batch: any): number {
    const total = Number(batch.totalInstruments || 0);
    const done = Number(batch.processedInstruments || 0);
    if (!total) return 0;
    return Math.min(100, Math.round((done / total) * 100));
  }

  batchBorderColor(status: string): string {
    switch (status) {
      case 'completed': return '#22c55e';
      case 'running': return '#60a5fa';
      case 'failed': return '#ef4444';
      case 'partially_completed': return '#f59e0b';
      default: return '#64748b';
    }
  }

  batchBadgeClass(status: string): string {
    switch (status) {
      case 'completed': return 'badge-green';
      case 'running': return 'badge-blue';
      case 'failed': return 'badge-red';
      case 'partially_completed': return 'badge-orange';
      default: return 'badge-slate';
    }
  }

  loadSkippedStocks() {
    this.loadSymbolOverrides();
    this.api.get<any>('/data/dhan/historical-backfill/skipped').subscribe({
      next: r => {
        this.skippedStocks = r.data?.skipped || [];
        this.failedStocks = r.data?.failed || [];
        // Say something either way. With no rows the panel stays hidden, which used to look
        // like the button had done nothing at all.
        if (!this.skippedStocks.length && !this.failedStocks.length) {
          this.overrideOk = true;
          this.overrideMessage = '';
          this.activeActivity = { ...this.activeActivity, status: 'completed',
            message: 'No skipped or failed stocks on record for the latest fetch.' };
        }
      },
      error: e => {
        this.skippedStocks = [];
        this.failedStocks = [];
        this.activeActivity = { ...this.activeActivity, status: 'failed',
          message: this.errorMessage(e, 'The skipped stock list could not be loaded.') };
      }
    });
  }

  private startDhanBackfillPolling(): void {
    if (this.dhanBackfillPoller) return;
    this.dhanBackfillPoller = setInterval(() => this.loadDhanBackfillJob(), 5000);
  }

  private stopDhanBackfillPolling(): void {
    if (!this.dhanBackfillPoller) return;
    clearInterval(this.dhanBackfillPoller);
    this.dhanBackfillPoller = null;
  }

  private startDhanInstrumentPolling(): void {
    if (this.dhanInstrumentPoller) return;
    this.dhanInstrumentPoller = setInterval(() => this.loadDhanInstrumentRefreshStatus(), 1500);
  }

  private stopDhanInstrumentPolling(): void {
    if (!this.dhanInstrumentPoller) return;
    clearInterval(this.dhanInstrumentPoller);
    this.dhanInstrumentPoller = null;
  }

  beginActivity(activity: any): void {
    this.activeActivity = {
      status: 'running',
      showModal: true,
      startedAt: new Date().toLocaleString('en-IN'),
      startedAtMs: Date.now(),
      ...activity
    };
  }

  normalizeActivity(activity: any, fallback: any): any {
    return {
      ...fallback,
      ...(activity || {}),
      showModal: this.activeActivity?.showModal === true,
      startedAt: (activity || {}).startedAt || fallback.startedAt || this.activeActivity?.startedAt,
      startedAtMs: (activity || {}).startedAtMs || fallback.startedAtMs || this.activeActivity?.startedAtMs,
      completedAt: (activity || {}).completedAt || new Date().toLocaleString('en-IN')
    };
  }

  shouldShowProgressModal(): boolean {
    return !!this.activeActivity?.status &&
      this.activeActivity.status !== 'idle' &&
      this.activeActivity.showModal !== false;
  }

  globalProgressPct(): number {
    const status = String(this.activeActivity?.status || '').toLowerCase();
    if (status === 'completed') return 100;
    if (status === 'failed' || status === 'stopped') return Math.max(0, this.progressRatioPct());
    const pct = this.progressRatioPct();
    if (pct > 0) return Math.min(99, pct);
    return status === 'running' || status === 'queued' || status === 'stopping' ? 15 : 0;
  }

  progressProcessed(): number {
    const details = this.activeActivity?.details || {};
    return Number(
      details.processedChunks ??
      details.processedInstruments ??
      details.processedRows ??
      this.activeActivity?.processedRows ??
      this.activeActivity?.rowsImported ??
      details.importedCandles ??
      this.activeActivity?.rowsImported ??
      0
    ) || 0;
  }

  progressTotal(): number {
    const details = this.activeActivity?.details || {};
    const total = Number(
      details.totalChunks ??
      details.totalInstruments ??
      details.totalRows ??
      this.activeActivity?.totalRows ??
      this.activeActivity?.totalRows ??
      0
    ) || 0;
    return total > 0 ? total : Math.max(this.progressProcessed(), this.globalProgressPct() === 100 ? this.progressProcessed() : 100);
  }

  progressUnit(): string {
    const dataType = String(this.activeActivity?.dataType || '').toLowerCase();
    if (dataType.includes('candle')) return 'Chunks';
    if (dataType.includes('stock list')) return 'Records';
    if (this.activeActivity?.source === 'Manual') return 'Files/Rows';
    return 'Records';
  }

  currentProgressStep(): string {
    const status = String(this.activeActivity?.status || '').toLowerCase();
    const details = this.activeActivity?.details || {};
    if (status === 'completed') return 'Completed';
    if (status === 'failed') return 'Failed';
    if (status === 'stopped') return 'Stopped';
    if (this.activeActivity?.currentStep) return this.activeActivity.currentStep;
    if (details.currentStep) return details.currentStep;
    if (details.currentSymbol) return `Fetching ${details.currentSymbol}`;
    if (this.activeActivity?.source === 'Manual') return 'Validating Uploaded Data';
    if (this.activeActivity?.targetTable || this.activeActivity?.tableName) return 'Saving Records To Database';
    return this.activeActivity?.message || 'Processing Activity';
  }

  /**
   * Time left, from the raw processed/total counts.
   *
   * <p>This used to divide by the *rounded* display percentage. With 3,501 chunks one percent
   * is 35 chunks, so the percentage sat still for roughly a minute while elapsed time kept
   * growing — and (elapsed / pct) * (100 - pct) therefore climbed the whole time, dropping only
   * when the percentage ticked over. The estimate appeared to count upwards. Using the exact
   * counts makes it fall steadily whenever the fetch rate is steady.
   */
  estimatedRemaining(): string {
    const processed = this.progressProcessed();
    const total = this.progressTotalRaw();
    if (total > 0 && processed >= total) return '0 Seconds';
    if (!this.activeActivity?.startedAt) return 'Calculating';
    const started = Number(this.activeActivity.startedAtMs || 0) || new Date(this.activeActivity.startedAt).getTime();
    if (!Number.isFinite(started)) return 'Calculating';
    const elapsed = Date.now() - started;
    if (elapsed <= 0 || processed <= 0 || total <= 0) return 'Calculating';
    const remainingMs = Math.max(0, (elapsed * (total - processed)) / processed);
    return this.formatDuration(remainingMs);
  }

  progressSteps(): { label: string; state: string }[] {
    const status = String(this.activeActivity?.status || '').toLowerCase();
    const pct = this.globalProgressPct();
    const labels = ['Started', 'Validating', 'Fetching', 'Saving', 'Completed'];
    if (status === 'failed') {
      return labels.map((label, index) => ({ label, state: index <= 2 ? 'failed' : 'pending' }));
    }
    if (status === 'completed') {
      return labels.map(label => ({ label, state: 'done' }));
    }
    return labels.map((label, index) => {
      const threshold = index * 25;
      if (pct >= threshold + 25) return { label, state: 'done' };
      if (pct >= threshold || (index === 0 && pct > 0)) return { label, state: 'running' };
      return { label, state: 'pending' };
    });
  }

  private progressRatioPct(): number {
    const total = this.progressTotalRaw();
    const processed = this.progressProcessed();
    if (!total || total <= 0) return 0;
    return Math.max(0, Math.min(100, Math.round((processed / total) * 100)));
  }

  private progressTotalRaw(): number {
    const details = this.activeActivity?.details || {};
    return Number(
      details.totalChunks ??
      details.totalInstruments ??
      details.totalRows ??
      this.activeActivity?.totalRows ??
      this.activeActivity?.totalRows ??
      0
    ) || 0;
  }

  stockPreviewProgressVisible(): boolean {
    const dataType = String(this.activeActivity?.dataType || '').toLowerCase();
    if (!dataType || this.activeActivity?.status === 'idle') return false;
    return dataType.includes('stock list') ||
      dataType.includes('bhavcopy') ||
      dataType.includes('fundamental') ||
      dataType.includes('delivery') ||
      dataType.includes('corporate') ||
      dataType.includes('fii') ||
      dataType.includes('option');
  }

  private formatDuration(ms: number): string {
    const seconds = Math.max(0, Math.round(ms / 1000));
    const minutes = Math.floor(seconds / 60);
    const remSeconds = seconds % 60;
    if (minutes <= 0) return `${remSeconds} Seconds`;
    return `${minutes} Minutes ${remSeconds} Seconds`;
  }

  isStockList(row: WarehouseRow): boolean {
    return row.dataType.toLowerCase() === 'stock list';
  }

  pagedBackendTables(): BackendTable[] {
    const start = (this.backendTablePage - 1) * this.backendTablePageSize;
    return this.backendTables.slice(start, start + this.backendTablePageSize);
  }

  backendTableTotalPages(): number {
    return Math.max(1, Math.ceil(this.backendTables.length / this.backendTablePageSize));
  }

  backendTablePageStart(): number {
    if (this.backendTables.length === 0) return 0;
    return (this.backendTablePage - 1) * this.backendTablePageSize + 1;
  }

  backendTablePageEnd(): number {
    return Math.min(this.backendTablePage * this.backendTablePageSize, this.backendTables.length);
  }

  setBackendTablePage(page: number): void {
    this.backendTablePage = Math.min(Math.max(1, page), this.backendTableTotalPages());
  }

  stockPreviewTotalPages(): number {
    return Math.max(1, Math.ceil(this.stockPreviewTotal / this.stockPreviewLimit));
  }

  stockPreviewPageEnd(): number {
    return Math.min((this.stockPreviewPage + 1) * this.stockPreviewLimit, this.stockPreviewTotal);
  }

  setStockPreviewPage(page: number): void {
    if (page < 0 || page >= this.stockPreviewTotalPages()) return;
    this.stockPreviewPage = page;
    this.loadStockPreview();
  }

  loadFundamentals() {
    this.fundamentalsLoading = true;
    this.api.get<any>('/data/fundamentals/preview', {
      search: this.fundamentalsSearch || '',
      limit: this.fundamentalsLimit,
      page: this.fundamentalsPage
    }).subscribe({
      next: r => {
        this.fundamentals = r.data?.rows || [];
        this.fundamentalsTotal = r.data?.totalRows || 0;
        this.fundamentalsLoading = false;
      },
      error: () => {
        this.fundamentals = [];
        this.fundamentalsTotal = 0;
        this.fundamentalsLoading = false;
      }
    });
  }

  fundamentalsTotalPages(): number {
    return Math.max(1, Math.ceil(this.fundamentalsTotal / this.fundamentalsLimit));
  }

  setFundamentalsPage(page: number): void {
    if (page < 0 || page >= this.fundamentalsTotalPages()) return;
    this.fundamentalsPage = page;
    this.loadFundamentals();
  }

  downloadFundamentals() {
    if (!this.fundamentals.length) return;
    const headers = ['Symbol','Company Name','Sector','Industry','Market Cap','PE Ratio','PB Ratio','EPS','ROE','ROCE','Debt to Equity','Dividend Yield','Book Value','Face Value','52 Week High','52 Week Low','Last Updated'];
    const rows = this.fundamentals.map(f => [
      f.symbol || '', f.companyName || '', f.sector || '', f.industry || '', f.marketCap || '',
      f.peRatio ?? '', f.pbRatio ?? '', f.eps ?? '', f.roe ?? '', f.roce ?? '',
      f.debtToEquity ?? '', f.dividendYield ?? '', f.bookValue ?? '', f.faceValue ?? '',
      f.week52High ?? '', f.week52Low ?? '', f.lastUpdated || ''
    ]);
    const csv = [headers.join(','), ...rows.map(r => r.map((v: any) => `"${String(v).replace(/"/g, '""')}"`).join(','))].join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'fundamentals_data.csv';
    document.body.appendChild(a);
    a.click();
    URL.revokeObjectURL(url);
    document.body.removeChild(a);
  }

  filteredRows(): WarehouseRow[] {
    const technicalTypes = ['daily bhavcopy', 'f&o bhavcopy', 'delivery volume', 'option chain', 'stock list'];
    const fundamentalTypes = ['fundamentals', 'corporate actions', 'fii/dii', '52w/ath'];
    return this.rows.filter(row => {
      const dt = row.dataType.toLowerCase();
      if (this.warehouseTab === 'fundamental') {
        return fundamentalTypes.some(f => dt.includes(f));
      }
      return technicalTypes.some(t => dt.includes(t)) || !fundamentalTypes.some(f => dt.includes(f));
    });
  }

  loadDashboardStats() {
    this.api.get<any>('/data/dashboard-stats').subscribe({
      next: r => this.dashStats = r.data || null,
      error: () => this.dashStats = null
    });
  }

  startFetchProgressPolling() {
    this.stopFetchProgressPolling();
    this.pollFetchProgress();
    this.fetchProgressPoller = setInterval(() => this.pollFetchProgress(), 2000);
  }

  stopFetchProgressPolling() {
    if (this.fetchProgressPoller) {
      clearInterval(this.fetchProgressPoller);
      this.fetchProgressPoller = null;
    }
  }

  pollFetchProgress() {
    this.api.get<any>('/data/fetch-progress').subscribe({
      next: r => {
        this.fetchProgress = r.data || null;
        if (this.fetchProgress?.status !== 'running') {
          this.stopFetchProgressPolling();
          this.loadDashboardStats();
        }
      },
      error: () => this.stopFetchProgressPolling()
    });
  }

  clearFetchCache() {
    this.api.post<any>('/data/fetch-cache/clear', {}).subscribe({
      next: () => this.loadDashboardStats(),
      error: () => {}
    });
  }

  formatBytes(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(2) + ' MB';
  }

  loadFetchLogs() {
    this.api.get<any>('/data/fetch-logs', { page: 0, size: 50 }).subscribe({
      next: r => this.fetchLogs = r.data?.items || [],
      error: () => this.fetchLogs = []
    });
  }

  loadRecentFetchLogs() {
    this.api.get<any>('/data/fetch-logs', { page: 0, size: 5 }).subscribe({
      next: r => this.recentFetchLogs = (r.data?.items || []).slice(0, 5),
      error: () => this.recentFetchLogs = []
    });
  }

  getSmartSteps(): any[] {
    const steps = this.activeActivity?.details?.steps || [];
    return steps.map((s: any) => ({
      step: s.name || s.step || s.label || '',
      status: s.status || 'pending',
      message: s.detail || s.message || ''
    }));
  }

  getErrorCode(): string {
    return this.activeActivity?.details?.errorCode || this.activeActivity?.errorCode || '';
  }

  getErrorMessage(): string {
    const code = this.getErrorCode();
    if (!code) return '';
    const messages: Record<string, string> = {
      'DW001': 'Weekend — Exchanges do not publish data on Saturday or Sunday',
      'DW002': 'Market Holiday — No trading session was held on this date',
      'DW003': 'Archive Not Available — Data file not yet published by exchange',
      'DW004': 'Network Error — Unable to reach the exchange server',
      'DW005': 'Request Timeout — Server took too long to respond',
      'DW006': 'Invalid / Corrupt File — Downloaded file is corrupted or empty',
      'DW007': 'CSV Structure Invalid — File format doesn\'t match expected structure',
      'DW008': 'Symbol Mapping Failed — Some symbols could not be mapped',
      'DW009': 'Duplicate Records — Data already exists, skipping duplicates',
      'DW010': 'Database Error — Database write operation failed',
      'DW011': 'API Rate Limited — Exchange API rate limit reached (HTTP 429)',
      'DW012': 'Future Date — Selected date is in the future',
      'DW013': 'Partial Success — Some dates fetched, others unavailable',
      'DW014': 'All Sources Exhausted — All source URLs failed',
      'DW015': 'Market Closed — Today\'s data may not be available yet'
    };
    return messages[code] || 'Unexpected error';
  }

  getResolution(): string {
    const code = this.getErrorCode();
    if (!code) return '';
    const resolutions: Record<string, string> = {
      'DW001': 'Markets are closed on weekends. Select a weekday date range. The fetch engine automatically skips weekends.',
      'DW002': 'This date is a market holiday. The fetch engine will automatically skip holidays and continue to the next trading day.',
      'DW003': 'The data file has not been published yet. Try again after market hours (after 6 PM IST) or select an older date.',
      'DW004': 'Check your internet connection and try again. If using VPN, try disconnecting. The engine will retry automatically.',
      'DW005': 'The server took too long. The engine retries with alternate sources automatically. Wait a minute and retry.',
      'DW006': 'The downloaded file was corrupted. The engine will retry from an alternate source automatically.',
      'DW007': 'The file format may have changed. Try a different source or use Manual upload with the correct format.',
      'DW008': 'Update the Stock Master (fetch Stock List) first to improve symbol coverage.',
      'DW009': 'Existing records are preserved. No action needed — only new data is imported.',
      'DW010': 'Database connection issue. Check that PostgreSQL is running and restart START.bat if needed.',
      'DW011': 'The exchange is rate-limiting requests. The engine waits and retries automatically. Try again after a few minutes.',
      'DW012': 'Future dates cannot have market data. Set the To Date to today or earlier.',
      'DW013': 'Some dates were fetched successfully. The skipped dates are shown below. Retry or use Manual upload for missing dates.',
      'DW014': 'All automatic source URLs failed. Use Manual upload to import the data file directly.',
      'DW015': 'The market may still be open. Today\'s end-of-day data is usually available after 6 PM IST.'
    };
    return resolutions[code] || '';
  }

  errorMessage(error: any, fallback: string): string {
    const body = error?.error;
    if (body?.message) return body.message;
    if (typeof body === 'string') {
      try {
        const parsed = JSON.parse(body);
        if (parsed?.message) return parsed.message;
      } catch {}
      const text = body.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
      if (text) return text.length > 180 ? text.slice(0, 180) + '...' : text;
    }
    if (error?.status === 401) return 'Please login again, then retry Fetch.';
    if (error?.status === 403) return 'Your login does not have permission for this Data Warehouse action.';
    if (error?.status === 0) return 'The app server is not reachable. Please restart START.bat and retry.';
    if (error?.message) return `${fallback} ${error.message}`;
    return fallback;
  }

  downloadFormat(row: WarehouseRow) {
    const header = this.templateHeader(row.dataType);
    const csv = `${header}\n`;
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${row.dataType.replace(/[^a-z0-9]+/gi, '_')}_format.csv`;
    document.body.appendChild(a);
    a.click();
    URL.revokeObjectURL(url);
    document.body.removeChild(a);
  }

  templateHeader(dataType: string): string {
    const headers: Record<string, string> = {
      'FII/DII Data': 'trade_date,category,buy_value,sell_value,net_value,source',
      'Option Chain': 'trade_date,symbol,expiry,strike,option_type,open_interest,volume,ltp,source',
      'Corporate Actions': 'symbol,action_type,ex_date,record_date,ratio,remarks,source',
      'Fundamentals': 'symbol,quarter,revenue,net_profit,eps,pe,pb,debt_equity,source',
      '52W/ATH/Snapshot': 'symbol,snapshot_date,week_52_high,week_52_low,ath,latest_price,source',
      'F&O Bhavcopy': 'trade_date,symbol,expiry,instrument,open,high,low,close,volume,oi,source',
      'Delivery Volume': 'trade_date,symbol,traded_qty,deliverable_qty,delivery_pct,source',
      'Daily Bhavcopy (OHLCV)': 'trade_date,symbol,open,high,low,close,volume,source',
      'Stock List': 'security_id,exchange,exchange_segment,symbol,trading_symbol,display_name,isin,segment,instrument_type,lot_size,tick_size'
    };
    return headers[dataType] || 'trade_date,symbol,value,source';
  }

  defaultRows(): WarehouseRow[] {
    return [
      { dataType: 'FII/DII Data', sources: ['NSE', 'Broker', 'BSE', 'Manual'], acceptedFiles: 'CSV, XLSX from NSE/BSE or broker FII/DII flow format' },
      { dataType: 'Option Chain', sources: ['NSE', 'Broker', 'Manual'], acceptedFiles: 'CSV, XLSX, JSON option-chain snapshot in NSE or broker format' },
      { dataType: 'Corporate Actions', sources: ['NSE', 'Manual', 'Broker'], acceptedFiles: 'CSV, XLSX corporate-action file with symbol, action, ex-date, record-date' },
      { dataType: 'Fundamentals', sources: ['NSE', 'Broker', 'Quarterly', 'Manual'], acceptedFiles: 'CSV, XLSX quarterly fundamentals in exchange or broker format' },
      { dataType: '52W/ATH/Snapshot', sources: ['Manual', 'Broker'], acceptedFiles: 'CSV, XLSX snapshot with symbol, 52W high/low, ATH and latest price' },
      { dataType: 'F&O Bhavcopy', sources: ['NSE', 'Manual', 'Broker'], acceptedFiles: 'CSV, ZIP, XLSX derivative bhavcopy in exchange or broker format' },
      { dataType: 'Delivery Volume', sources: ['NSE MTO', 'Manual', 'Broker'], acceptedFiles: 'CSV, TXT, ZIP MTO delivery volume file' },
      { dataType: 'Daily Bhavcopy (OHLCV)', sources: ['NSE', 'Manual', 'Broker'], acceptedFiles: 'CSV, ZIP, XLSX daily OHLCV bhavcopy' },
      { dataType: 'Stock List', sources: ['Dhan Public'], acceptedFiles: 'Dhan public detailed instrument CSV with security ID, exchange, segment, symbol, ISIN, expiry, strike, lot size and raw details' }
    ];
  }

  defaultBackendTables(): BackendTable[] {
    return [
      { key: 'master', label: 'Master Table', tableName: 'stock_master', description: 'Stock symbol master and exchange metadata' },
      { key: 'market_data', label: 'Market Data Table', tableName: 'market_data', description: 'Daily OHLCV and market history' },
      { key: 'tracked_stocks', label: 'Tracked Stocks Table', tableName: 'tracked_stocks', description: 'Stock Tracker active uploaded symbols' },
      { key: 'stock_uploads', label: 'Stock Uploads Table', tableName: 'stock_uploads', description: 'Stock Tracker upload audit records' },
      { key: 'tracker_rankings', label: 'Tracker Rankings Table', tableName: 'tracker_rankings', description: 'Top 10 ranking run headers' },
      { key: 'tracker_ranking_items', label: 'Ranking Items Table', tableName: 'tracker_ranking_items', description: 'Top 10 ranking item details' },
      { key: 'positions', label: 'Portfolio Positions Table', tableName: 'positions', description: 'Open and closed portfolio positions' },
      { key: 'trades', label: 'Portfolio Trades Table', tableName: 'trades', description: 'Portfolio buy and sell trade ledger' }
    ];
  }

  defaultDependentSections(): DependentSection[] {
    return [
      { section: 'Dashboard', route: '/dashboard', tables: 'market_data, stock_master', usage: 'Market mood, stock count, latest market date' },
      { section: 'Backtesting', route: '/backtesting', tables: 'market_data', usage: 'Historical close series for strategy testing' },
      { section: 'Stock Ranking', route: '/ranking', tables: 'stock_master, market_data, sector_data', usage: 'Universe selection and ranking inputs' },
      { section: 'Strategy Builder', route: '/strategy-builder', tables: 'stock_master, market_data', usage: 'Symbols and latest OHLCV conditions' },
      { section: 'Stock Tracker', route: '/tracker', tables: 'stock_master, market_data, tracked_stocks', usage: 'CMP fallback, 52W/ATH and ranking support' },
      { section: 'Support / Resistance', route: '/sr/indices', tables: 'market_data, index_data', usage: 'Index levels and trend calculations' },
      { section: 'Broker / Trading', route: '/broker, /trading', tables: 'broker_credentials, broker_market_candles, broker_portfolio_snapshots', usage: 'Broker API data and real-trade readiness' }
    ];
  }
}
