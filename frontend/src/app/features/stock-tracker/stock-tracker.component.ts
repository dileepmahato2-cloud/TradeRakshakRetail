import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { LivePriceService } from '../../core/services/live-price.service';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { Subscription, timeout } from 'rxjs';

/**
 * Uploaded Stock Details, cached outside the component.
 *
 * <p>Angular destroys a routed component when you navigate away, so anything held on the instance
 * is gone the moment the tracker is left. Kept at module scope the rows survive a trip to Broker
 * and back, and the section repaints from them instead of re-fetching every page again.
 */
const UPLOADED_PAGE_CACHE: Record<string, { stocks: any[], total: number, time: number }> = {};

/**
 * Width of a stored intraday candle, and the time the session's first bar starts.
 * Mirrors livecandle.interval.minutes and SESSION_OPEN in LiveCandleAggregator; stored bars run
 * 09:15, 10:15 ... 15:15. Any buy/sell time offered here must be one of those starts, because the
 * server seeks forward to the first candle at or after the time it is given.
 */
const CANDLE_INTERVAL_MINUTES = 60;
const SESSION_OPEN_MINUTES = 9 * 60 + 15;
const SESSION_CLOSE_MINUTES = 15 * 60 + 30;

@Component({
  selector: 'app-stock-tracker',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host { display:block; font-family:'Inter',sans-serif; }
    .card { background:#1e2a3b; border:1px solid #2d3f55; border-radius:8px; }
    .sb-hdr { background:#16202e; color:#94a3b8; font-size:9.5px; font-weight:700; padding:5px 9px;
      text-align:left; border-bottom:1px solid #1e2a3b; white-space:nowrap; }
    .sb-cell { font-size:11px; padding:6px 9px; border-bottom:1px solid #131c28; white-space:nowrap; }
    .score-btn { background:transparent; border:1px solid transparent; border-radius:4px; color:#60a5fa;
      font-size:11px; font-weight:700; padding:2px 5px; cursor:pointer; font-family:inherit;
      display:inline-flex; align-items:center; gap:4px; }
    .score-btn:hover { background:#16202e; border-color:#2d3f55; }
    .score-btn-open { background:#16202e; border-color:#3b82f6; }
    .score-caret { font-size:7px; color:#64748b; }
    .fx-hdr { background:#16202e; color:#94a3b8; font-size:9.5px; font-weight:700; padding:5px 8px;
      text-align:left; border-bottom:1px solid #1e2a3b; white-space:nowrap; }
    .fx-cell { font-size:10.5px; padding:5px 8px; border-bottom:1px solid #131c28; }
    .tbl-hdr { background:#1a3a5c; color:#fff; font-size:11px; font-weight:600; padding:8px 10px; text-align:left; border-bottom:1px solid #2d3f55; }
    .tbl-hdr-sort { background:#1a3a5c; color:#fff; font-size:11px; font-weight:600; padding:8px 10px; text-align:left; border-bottom:1px solid #2d3f55; cursor:pointer; user-select:none; white-space:nowrap; }
    .tbl-hdr-sort:hover { background:#1e4d7a; }
    .tbl-row { border-bottom:1px solid #1e2a3b; transition:background 0.1s; }
    .tbl-row:hover { background:#1a2535; }
    .row-flash { animation:rowFlash 1.5s ease-out; }
    @keyframes rowFlash { 0% { background:#1e40af; } 40% { background:#1e3a8a; } 100% { background:transparent; } }
    .tbl-cell { font-size:11px; padding:7px 10px; color:#cbd5e1; }
    .tbl-cell-num { font-size:11px; padding:7px 10px; color:#cbd5e1; text-align:right; font-family:monospace; }
    .pos { color:#22c55e; font-weight:600; }
    .neg { color:#ef4444; font-weight:600; }
    .neu { color:#94a3b8; }
    .badge-blue { background:#1e3a5f; color:#60a5fa; border:1px solid #2563eb; border-radius:4px; padding:2px 7px; font-size:10px; font-weight:600; }
    .badge-green { background:#14532d; color:#4ade80; border:1px solid #16a34a; border-radius:4px; padding:2px 7px; font-size:10px; font-weight:600; }
    .badge-red { background:#450a0a; color:#fca5a5; border:1px solid #dc2626; border-radius:4px; padding:2px 7px; font-size:10px; font-weight:600; }
    .badge-amber { background:#3a2608; color:#fde68a; border:1px solid #d97706; border-radius:4px; padding:2px 7px; font-size:10px; font-weight:600; display:inline-block; max-width:190px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; vertical-align:middle; }
    .btn-blue { background:#1d6adb; color:#fff; border:none; border-radius:6px; padding:8px 16px; font-size:12px; font-weight:600; cursor:pointer; display:flex; align-items:center; gap:6px; white-space:nowrap; }
    .btn-blue:hover { background:#1a5fc7; }
    .btn-green { background:#16a34a; color:#fff; border:none; border-radius:6px; padding:8px 16px; font-size:12px; font-weight:600; cursor:pointer; display:flex; align-items:center; gap:6px; white-space:nowrap; }
    .btn-green:hover { background:#15803d; }
    .btn-outline { background:transparent; color:#60a5fa; border:1px solid #2563eb; border-radius:6px; padding:5px 12px; font-size:11px; cursor:pointer; }
    .btn-outline:hover { background:#1e3a5f; }
    .section-title { color:#fff; font-size:13px; font-weight:700; }
    .metric-box { background:#0f172a; border-radius:6px; padding:10px 16px; text-align:center; }
    .metric-val { font-size:22px; font-weight:800; line-height:1.1; }
    .metric-lbl { font-size:10px; color:#94a3b8; margin-top:2px; }
    .bar-chart-bar { border-radius:3px 3px 0 0; min-height:2px; width:28px; }
    .donut-wrapper { position:relative; width:120px; height:120px; }
    .select-sm { background:#1e2a3b; border:1px solid #2d3f55; border-radius:5px; color:#94a3b8; font-size:11px; padding:3px 8px; cursor:pointer; }
    .spin { width:14px; height:14px; border:2px solid rgba(255,255,255,.3); border-top-color:#fff; border-radius:50%; animation:spin .7s linear infinite; display:inline-block; }
    @keyframes spin { to { transform:rotate(360deg); } }
    .upload-zone { border:2px dashed #2d4a6a; border-radius:8px; padding:32px; text-align:center; cursor:pointer; transition:border-color .2s; }
    .upload-zone:hover { border-color:#3b82f6; }
    .tab { padding:6px 16px; border-radius:6px; font-size:12px; font-weight:600; cursor:pointer; border:1px solid transparent; transition:all .15s; color:#64748b; }
    .tab.active { background:#1d6adb; color:#fff; border-color:#1d6adb; }
    .tab:not(.active):hover { color:#94a3b8; border-color:#2d3f55; }
    .trade-mini { border:0; border-radius:5px; padding:5px 9px; font-size:10px; font-weight:800; cursor:pointer; margin-left:4px; }
    .trade-mini.buy { background:#16a34a; color:#fff; }
    .trade-mini.sell { background:#dc2626; color:#fff; }
    .trade-mini:disabled { opacity:.38; cursor:not-allowed; }
    .pager { display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; padding:10px 14px; border-top:1px solid #2d3f55; }
    .pager-info { color:#94a3b8; font-size:11px; }
    .tracker-section { width:100%; box-sizing:border-box; margin-left:0; margin-right:0; }
    .summary-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(170px,1fr)); gap:10px; align-items:stretch; margin:0 0 14px 0; width:100%; box-sizing:border-box; }
    .summary-grid > .card { min-width:0; min-height:86px; box-sizing:border-box; }
    /* One line of headline figures. Seven equal columns on a wide screen; it folds to four
       then two rather than shrinking the numbers past legibility. */
    .summary-strip { display:grid; grid-template-columns:repeat(7,minmax(0,1fr)); gap:10px; align-items:stretch; margin:0 0 14px 0; width:100%; box-sizing:border-box; }
    .summary-strip > .card { min-width:0; min-height:82px; box-sizing:border-box; padding:10px 12px; display:flex; flex-direction:column; gap:2px; justify-content:center; }
    .stat-label { font-size:10px; color:#94a3b8; line-height:1.25; }
    .stat-value { font-size:22px; font-weight:800; line-height:1.15; font-variant-numeric:tabular-nums; }
    .stat-value-sm { font-size:14px; font-weight:700; line-height:1.2; color:#fff; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    .stat-sub { font-size:9.5px; color:#64748b; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    @media (max-width:1400px) { .summary-strip { grid-template-columns:repeat(4,minmax(0,1fr)); } }
    @media (max-width:820px)  { .summary-strip { grid-template-columns:repeat(2,minmax(0,1fr)); } }
    /* Trend panel controls: label above field, fields aligned across the row. auto-fit means
       they reflow onto a second line as whole pairs rather than splitting a label from its input. */
    .trend-controls { display:grid; gap:8px 12px; align-items:end; width:100%; margin:4px 0 8px;
      grid-template-columns:repeat(auto-fit,minmax(104px,max-content)); }
    .tc-field { display:flex; flex-direction:column; gap:3px; min-width:0; }
    .tc-label { font-size:9px; color:#64748b; text-transform:uppercase; letter-spacing:.04em;
      white-space:nowrap; }
    .tc-field .select-sm, .tc-field input { width:100%; font-size:10px; box-sizing:border-box; }
    .tc-divider { border-left:1px solid #2d3f55; padding-left:12px; }
    .tc-action { justify-content:flex-end; }
    .tc-btn { width:100%; font-size:9px; padding:4px 10px; white-space:nowrap; }
    .trend-caption { font-size:9px; color:#60a5fa; width:100%; margin:-2px 0 6px; }
    @media (max-width:720px) {
      .trend-controls { grid-template-columns:repeat(2,minmax(0,1fr)); }
      .tc-divider { border-left:0; padding-left:0; }
    }

    .uploaded-details-card { width:100%; box-sizing:border-box; margin:0 0 14px 0; }
    .uploaded-toolbar { background:#1e2a3b; padding:10px 14px; display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; border-bottom:1px solid #2d3f55; box-sizing:border-box; }
    .uploaded-toolbar-left, .uploaded-toolbar-actions { display:flex; align-items:center; gap:10px; flex-wrap:wrap; min-width:0; }
    .uploaded-toolbar-actions { gap:6px; justify-content:flex-end; }
  `],
  template: `
<div style="background:#0d1829; min-height:100vh; padding:16px 20px;">

  <!-- Top header bar -->
  <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:16px; flex-wrap:wrap; gap:10px;">
    <div style="display:flex; align-items:center; gap:10px;">
      <span style="color:#fff; font-size:18px; font-weight:700;">Stock Upload &amp; Performance Tracker</span>
      <span style="color:#60a5fa; font-size:14px; cursor:help; border:1px solid #2563eb; border-radius:50%; width:18px; height:18px; display:inline-flex; align-items:center; justify-content:center; font-size:10px;">i</span>
    </div>
    <div style="display:flex; gap:8px; flex-wrap:wrap;">
      <button class="btn-blue" (click)="showUploadModal.set(true)">
        Upload Stock
      </button>
      <button class="btn-green" (click)="refreshPrices()" [disabled]="refreshing() || refreshState==='updating'"
        title="Fetches fresh prices from the broker and refills any missing base prices. The Refresh button inside Uploaded Stock Details only re-reads the table from the database - it fetches nothing.">
        @if (refreshState==='updating') { <span class="spin"></span> Updating... }
        @else { Refresh - Live Price }
      </button>
      @if (refreshState==='completed') {
        <span style="font-size:11px; color:#4ade80; display:inline-flex; align-items:center; gap:6px;">
          Completed
          @if (refreshLastUpdatedLabel) {
            <span style="color:#94a3b8;">Last Updated: {{ refreshLastUpdatedLabel }}</span>
          }
        </span>
      }
      @if (refreshState==='failed') {
        <span style="font-size:11px; color:#fca5a5; display:inline-flex; align-items:center; gap:8px;">
          Failed
          <button class="btn-outline" style="font-size:10px; padding:3px 8px;" (click)="retryRefresh()">Retry</button>
        </span>
      }
      <button class="btn-blue" (click)="downloadEligible()">
        Eligible Stocks 0 to 5%
      </button>
      <button class="btn-blue" (click)="downloadAtrBaseCrossover()"
        title="First crossover up through the base price since it was set, with ATR14(D-1)/Close(D-1) at or above 3%. The export carries the entry, stop (-7%), target (+15%) and 15-session time exit.">
        ATR_Base_Price_Crossover
      </button>
      <button class="btn-outline" (click)="showProgressDashboard.set(true); loadJobHistory()" style="font-size:11px;">
        Processing Jobs
      </button>
    </div>
  </div>

  @if (refreshResult) {
    <div class="card" style="display:flex; align-items:center; justify-content:space-between; gap:12px; padding:10px 14px; margin-bottom:14px; flex-wrap:wrap;">
      <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
        <span [class]="refreshResult.dhan_connected ? 'badge-green' : 'badge-blue'">
          {{ refreshResult.dhan_connected ? 'Dhan Connected' : 'Dhan Required' }}
        </span>
        <span style="color:#cbd5e1; font-size:12px;">Updated: {{ refreshResult.updated || 0 }} / {{ refreshResult.total || 0 }}</span>
        <span style="color:#94a3b8; font-size:12px;">Dhan: {{ refreshResult.dhan_updated || 0 }}</span>
        <span style="color:#94a3b8; font-size:12px;">Fallback: {{ refreshResult.fallback_updated || 0 }}</span>
        @if ((refreshResult.dhan_missing_instruments || 0) > 0) {
          <span style="color:#fbbf24; font-size:12px;">Missing Dhan mapping: {{ refreshResult.dhan_missing_instruments }}</span>
        }
        @if ((refreshResult.base_price_retry_started || 0) > 0) {
          <span style="color:#60a5fa; font-size:12px;">Base Price retry: {{ refreshResult.base_price_retry_started }}</span>
        }
        @if (!refreshResult.dhan_connected) {
          <span style="color:#fca5a5; font-size:11px;">Broker connection unavailable. Using latest stored market data.</span>
        }
      </div>
      <div style="color:#94a3b8; font-size:11px;">{{ refreshResult.message }}</div>
    </div>
  }

  <!-- Pipeline Progress Bar (shown after upload) -->
  @if (pipelineActive || pipelineStages.length > 0) {
    <div class="card" style="padding:14px; margin-bottom:14px;">
      <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:10px;">
        <div style="display:flex; align-items:center; gap:8px;">
          <span class="section-title" style="font-size:13px;">Processing Pipeline</span>
          @if (pipelineActive) { <span class="spin"></span> }
          <span style="font-size:11px; color:#94a3b8;">{{ pipelineProgress | number:'1.0-0' }}% complete</span>
        </div>
        @if (!pipelineActive) {
          <button class="btn-outline" style="font-size:10px; padding:3px 8px;" (click)="pipelineStages = []">Dismiss</button>
        }
      </div>
      <!-- Overall progress bar -->
      <div style="background:#0f172a; border-radius:999px; height:6px; overflow:hidden; margin-bottom:12px;">
        <div style="height:100%; border-radius:999px; transition:width 0.4s ease;"
          [style.width]="pipelineProgress + '%'"
          [style.background]="pipelineActive ? '#1d6adb' : '#22c55e'"></div>
      </div>
      <!-- Stage indicators -->
      <div style="display:flex; gap:0; align-items:center;">
        @for (stage of pipelineStages; track stage.stage; let i = $index; let last = $last) {
          <div style="flex:1; display:flex; flex-direction:column; align-items:center; gap:4px; min-width:0;">
            <div style="width:28px; height:28px; border-radius:50%; display:flex; align-items:center; justify-content:center; font-size:12px; font-weight:800; border:2px solid; transition:all 0.3s;"
              [style.border-color]="pipelineStageColor(stage.status)"
              [style.background]="stage.status === 'COMPLETED' ? '#14532d' : stage.status === 'RUNNING' ? '#1e3a5f' : '#0f172a'"
              [style.color]="pipelineStageColor(stage.status)"
              [title]="stage.error || stage.label">
              @if (stage.status === 'RUNNING') { <span class="spin" style="width:12px; height:12px; border-width:2px;"></span> }
              @else { {{ pipelineStageIcon(stage.status) }} }
            </div>
            <div style="font-size:9px; font-weight:600; text-align:center; line-height:1.2; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; max-width:100%; padding:0 2px;"
              [style.color]="stage.status === 'COMPLETED' ? '#4ade80' : stage.status === 'RUNNING' ? '#60a5fa' : '#64748b'">
              {{ stage.label }}
            </div>
          </div>
          @if (!last) {
            <div style="width:40px; height:2px; flex:0 0 40px; margin-top:-16px;"
              [style.background]="stage.status === 'COMPLETED' ? '#22c55e' : '#1e2a3b'"></div>
          }
        }
      </div>
    </div>
  }

  <div class="card" style="display:grid; grid-template-columns:minmax(260px,1.1fr) minmax(420px,2fr); gap:14px; padding:14px; margin-bottom:14px;">
    <div style="background:#132237; border:1px solid #28415f; border-radius:8px; padding:14px; display:flex; flex-direction:column; gap:12px; min-width:0;">
      <div style="display:flex; align-items:center; justify-content:space-between; gap:10px;">
        <div style="display:flex; align-items:center; gap:10px; min-width:0;">
          <span style="width:9px; height:9px; border-radius:50%; display:inline-block; flex:0 0 auto;"
            [style.background]="toneColor(liveFeedTone())"
            [style.box-shadow]="toneShadow(liveFeedTone())"></span>
          <div style="min-width:0;">
            <div style="color:#fff; font-size:13px; font-weight:800;">Live Price Feed</div>
            <div style="color:#94a3b8; font-size:11px; line-height:1.45; margin-top:4px;">{{ liveFeedHelpText() }}</div>
          </div>
        </div>
        <span style="border-radius:999px; padding:4px 9px; font-size:10px; font-weight:800; white-space:nowrap;"
          [style.background]="toneBackground(liveFeedTone())"
          [style.color]="toneText(liveFeedTone())"
          [style.border]="toneBorder(liveFeedTone())">{{ liveFeedTitle() }}</span>
      </div>

      <div style="display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:8px;">
        <div style="background:#0f1b2d; border:1px solid #233852; border-radius:7px; padding:9px; min-width:0;">
          <div style="color:#8fb0d4; font-size:10px; margin-bottom:4px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">Connection</div>
          <div style="font-size:13px; font-weight:800; line-height:1.2; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;"
            [style.color]="toneText(connectionTone())">{{ friendlyConnection() }}</div>
        </div>
        <div style="background:#0f1b2d; border:1px solid #233852; border-radius:7px; padding:9px; min-width:0;">
          <div style="color:#8fb0d4; font-size:10px; margin-bottom:4px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">Dhan API</div>
          <div style="font-size:13px; font-weight:800; line-height:1.2; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;"
            [style.color]="toneText(apiTone())">{{ friendlyApiStatus() }}</div>
        </div>
        <div style="background:#0f1b2d; border:1px solid #233852; border-radius:7px; padding:9px; min-width:0;">
          <div style="color:#8fb0d4; font-size:10px; margin-bottom:4px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">WebSocket</div>
          <div style="font-size:13px; font-weight:800; line-height:1.2; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;"
            [style.color]="toneText(websocketTone())">{{ friendlyWebSocketStatus() }}</div>
        </div>
      </div>
    </div>

    <div style="display:grid; grid-template-columns:repeat(auto-fit,minmax(118px,1fr)); gap:8px; min-width:0;">
      <div style="background:#132237; border:1px solid #28415f; border-radius:8px; padding:10px; min-width:0;">
        <div style="color:#8fb0d4; font-size:10px; margin-bottom:5px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">Subscribed Symbols</div>
        <div style="color:#fff; font-size:15px; font-weight:800; line-height:1.15; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">{{ liveStatus.subscribedSymbols || 0 }}</div>
      </div>
      <div style="background:#132237; border:1px solid #28415f; border-radius:8px; padding:10px; min-width:0;">
        <div style="color:#8fb0d4; font-size:10px; margin-bottom:5px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">Live Ticks/sec</div>
        <div style="color:#fff; font-size:15px; font-weight:800; line-height:1.15; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">{{ liveStatus.ticksReceivedPerSecond || 0 }}</div>
      </div>
      <div style="background:#132237; border:1px solid #28415f; border-radius:8px; padding:10px; min-width:0;">
        <div style="color:#8fb0d4; font-size:10px; margin-bottom:5px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">DB Updates/sec</div>
        <div style="color:#fff; font-size:15px; font-weight:800; line-height:1.15; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">{{ liveStatus.databaseUpdatesPerSecond || 0 }}</div>
      </div>
      <div style="background:#132237; border:1px solid #28415f; border-radius:8px; padding:10px; min-width:0;">
        <div style="color:#8fb0d4; font-size:10px; margin-bottom:5px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">Screen Updates/sec</div>
        <div style="color:#fff; font-size:15px; font-weight:800; line-height:1.15; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">{{ liveStatus.uiUpdatesPerSecond || 0 }}</div>
      </div>
      <div style="background:#132237; border:1px solid #28415f; border-radius:8px; padding:10px; min-width:0;">
        <div style="color:#8fb0d4; font-size:10px; margin-bottom:5px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">Reconnects</div>
        <div style="color:#fff; font-size:15px; font-weight:800; line-height:1.15; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">{{ liveStatus.reconnectCount || 0 }}</div>
      </div>
      <div style="background:#132237; border:1px solid #28415f; border-radius:8px; padding:10px; min-width:0;">
        <div style="color:#8fb0d4; font-size:10px; margin-bottom:5px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">Last Tick</div>
        <div style="color:#fff; font-size:15px; font-weight:800; line-height:1.15; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;" [title]="liveStatus.lastTickTime || ''">{{ liveStatus.lastTickTime || 'Waiting' }}</div>
      </div>
      <div style="background:#132237; border:1px solid #28415f; border-radius:8px; padding:10px; min-width:0;">
        <div style="color:#8fb0d4; font-size:10px; margin-bottom:5px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">Processing</div>
        <div style="color:#fff; font-size:15px; font-weight:800; line-height:1.15; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;" [title]="liveStatus.currentProcessingSymbol || ''">{{ liveStatus.currentProcessingSymbol || '-' }}</div>
      </div>
      <div style="background:#132237; border:1px solid #28415f; border-radius:8px; padding:10px; min-width:0;">
        <div style="color:#8fb0d4; font-size:10px; margin-bottom:5px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">System Load</div>
        <div style="color:#fff; font-size:15px; font-weight:800; line-height:1.15; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">{{ liveStatus.memoryUsageMb || 0 }} MB / {{ liveStatus.cpuUsagePercent || 0 }}%</div>
      </div>
      @if (liveStatus.lastApiError) {
        <div style="grid-column:1/-1; color:#fca5a5; background:#3b1116; border:1px solid #7f1d1d; border-radius:7px; padding:9px 10px; font-size:11px; line-height:1.45; overflow-wrap:anywhere;">
          <strong>Needs attention:</strong> {{ friendlyLiveError() }}
        </div>
      }
      @if ((liveStatus.missingMappingCount || 0) > 0) {
        <div style="grid-column:1/-1; color:#fde68a; background:#3a2608; border:1px solid #d97706; border-radius:7px; padding:9px 10px; font-size:11px; line-height:1.45; overflow-wrap:anywhere;">
          <strong>Mapping needed:</strong>
          {{ liveStatus.missingMappingCount }} symbol{{ liveStatus.missingMappingCount === 1 ? '' : 's' }} cannot receive Dhan live price:
          {{ missingMappingText() }}.
          Refresh Dhan instrument master, or replace/remove inactive symbols from the uploaded list.
        </div>
      }
    </div>
  </div>

  <!-- Upload modal -->
  @if (showUploadModal()) {
    <div style="position:fixed; inset:0; background:rgba(0,0,0,.7); z-index:100; display:flex; align-items:center; justify-content:center;"
      (click)="showUploadModal.set(false)">
      <div class="card" style="width:min(520px,100%); padding:24px; position:relative;" (click)="$event.stopPropagation()">
        <button (click)="showUploadModal.set(false)"
          style="position:absolute; top:12px; right:12px; background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer;">x</button>
        <div class="section-title" style="margin-bottom:4px;">Upload Stocks</div>
        <p style="color:#64748b; font-size:11px; margin-bottom:12px;">
          Upload Excel (.xlsx) or CSV - Required columns:
          <code style="background:#0f172a; padding:2px 6px; border-radius:4px; color:#60a5fa;">Date | Symbol | MarketCapName | Sector</code>
        </p>
        <!-- Format example -->
        <div style="background:#0f172a; border-radius:6px; padding:10px; margin-bottom:12px; overflow-x:auto;">
          <table style="width:100%; border-collapse:collapse; font-size:11px; font-family:monospace;">
            <thead><tr>
              <th style="color:#64748b; text-align:left; padding:2px 10px 4px 0;">Date</th>
              <th style="color:#64748b; text-align:left; padding:2px 10px 4px 0;">Symbol</th>
              <th style="color:#64748b; text-align:left; padding:2px 10px 4px 0;">MarketCapName</th>
              <th style="color:#64748b; text-align:left; padding:2px 0 4px 0;">Sector</th>
            </tr></thead>
            <tbody>
              <tr><td style="color:#cbd5e1; padding:2px 10px 2px 0;">01-01-2026</td><td style="color:#60a5fa; padding:2px 10px 2px 0;">RELIANCE</td><td style="color:#cbd5e1; padding:2px 10px 2px 0;">Large Cap</td><td style="color:#cbd5e1;">Energy</td></tr>
              <tr><td style="color:#cbd5e1; padding:2px 10px 2px 0;">01-01-2026</td><td style="color:#60a5fa; padding:2px 10px 2px 0;">TCS</td><td style="color:#cbd5e1; padding:2px 10px 2px 0;">Large Cap</td><td style="color:#cbd5e1;">IT</td></tr>
            </tbody>
          </table>
        </div>
        <div style="background:#1e3a1e; border:1px solid #16a34a; border-radius:6px; padding:8px 12px; font-size:11px; color:#4ade80; margin-bottom:14px;">
          <strong>Duplicate Rule:</strong> If same symbol uploaded multiple times, only the <strong>latest date</strong> record is kept active.
        </div>
        <div class="upload-zone" (click)="fileInput.click()"
          (dragover)="$event.preventDefault()" (drop)="onDrop($event)">
          <div style="font-size:32px; margin-bottom:8px;">File</div>
          <div style="color:#fff; font-weight:600; margin-bottom:4px;">Drop file here or click to browse</div>
          <div style="color:#64748b; font-size:11px;">Supports .xlsx, .csv - Up to 50,000 stocks</div>
          <input #fileInput type="file" accept=".xlsx,.xls,.csv" style="display:none;" (change)="onFileSelected($event)">
        </div>
        @if (selectedFile) {
          <div style="display:flex; align-items:center; justify-content:space-between; background:#0f172a; border-radius:6px; padding:10px 14px; margin-top:10px;">
            <span style="color:#fff; font-size:12px;">{{ selectedFile.name }}</span>
            <button class="btn-blue" (click)="uploadFile()" [disabled]="uploading()" style="padding:6px 14px; font-size:11px;">
              @if (uploading()) { <span class="spin"></span> Uploading... } @else { Upload }
            </button>
          </div>
        }
        @if (uploadResult) {
          <div style="margin-top:10px; padding:10px 14px; border-radius:6px; font-size:12px;"
            [style.background]="uploadResult.success ? '#14532d' : '#450a0a'"
            [style.border]="uploadResult.success ? '1px solid #16a34a' : '1px solid #dc2626'"
            [style.color]="uploadResult.success ? '#4ade80' : '#f87171'">
            @if (uploadResult.success) {
              Stock file uploaded successfully. Base prices are being fetched in background.
              <div style="font-size:10px; color:#94a3b8; margin-top:4px;">
                Total: {{ uploadResult.total_rows }} | New: {{ uploadResult.new_records }} | Duplicates removed: {{ uploadResult.duplicates }}
              </div>
            } @else {
              {{ uploadResult.error }}
            }
          </div>
        }
      </div>
    </div>
  }

  <!-- Processing Progress Dashboard -->
  @if (showProgressDashboard()) {
    <div style="position:fixed; inset:0; background:rgba(0,0,0,.7); z-index:105; display:flex; align-items:center; justify-content:center; padding:16px;"
      (click)="showProgressDashboard.set(false); stopJobPolling()">
      <div class="card" style="width:min(720px,100%); max-height:90vh; overflow-y:auto; padding:20px; position:relative;" (click)="$event.stopPropagation()">
        <button (click)="showProgressDashboard.set(false); stopJobPolling()"
          style="position:absolute; top:10px; right:10px; background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer;">x</button>
        <div class="section-title" style="font-size:15px; margin-bottom:12px;">Processing Progress Dashboard</div>

        @if (activeJob) {
          <!-- Status header -->
          <div style="display:flex; align-items:center; gap:8px; margin-bottom:12px;">
            <span style="border-radius:999px; padding:4px 12px; font-size:11px; font-weight:700;"
              [style.background]="jobStatusBg(activeJob.status)"
              [style.color]="jobStatusColor(activeJob.status)"
              [style.border]="'1px solid ' + jobStatusColor(activeJob.status)">{{ activeJob.status }}</span>
            <span style="color:#94a3b8; font-size:11px;">{{ activeJob.file_name }}</span>
            <span style="color:#64748b; font-size:10px;">Job: {{ activeJob.job_id }}</span>
          </div>

          <!-- Progress bar -->
          <div style="background:#0f172a; border-radius:999px; height:18px; overflow:hidden; margin-bottom:12px; position:relative;">
            <div style="height:100%; border-radius:999px; transition:width 0.3s;"
              [style.width]="activeJob.progress_percentage + '%'"
              [style.background]="activeJob.status === 'FAILED' ? '#dc2626' : activeJob.status === 'PAUSED' ? '#d97706' : '#1d6adb'"></div>
            <span style="position:absolute; inset:0; display:flex; align-items:center; justify-content:center; color:#fff; font-size:10px; font-weight:700;">
              {{ activeJob.progress_percentage | number:'1.1-1' }}%
            </span>
          </div>

          <!-- Stats grid -->
          <div style="display:grid; grid-template-columns:repeat(4,1fr); gap:8px; margin-bottom:12px;">
            <div style="background:#0f172a; border-radius:6px; padding:8px; text-align:center;">
              <div style="font-size:9px; color:#94a3b8;">Total</div>
              <div style="font-size:16px; font-weight:800; color:#fff;">{{ activeJob.total_records }}</div>
            </div>
            <div style="background:#0f172a; border-radius:6px; padding:8px; text-align:center;">
              <div style="font-size:9px; color:#94a3b8;">Processed</div>
              <div style="font-size:16px; font-weight:800; color:#60a5fa;">{{ activeJob.processed_records }}</div>
            </div>
            <div style="background:#0f172a; border-radius:6px; padding:8px; text-align:center;">
              <div style="font-size:9px; color:#94a3b8;">Pending</div>
              <div style="font-size:16px; font-weight:800; color:#f59e0b;">{{ activeJob.pending_records }}</div>
            </div>
            <div style="background:#0f172a; border-radius:6px; padding:8px; text-align:center;">
              <div style="font-size:9px; color:#94a3b8;">Success</div>
              <div style="font-size:16px; font-weight:800; color:#22c55e;">{{ activeJob.success_records }}</div>
            </div>
            <div style="background:#0f172a; border-radius:6px; padding:8px; text-align:center;">
              <div style="font-size:9px; color:#94a3b8;">Failed</div>
              <div style="font-size:16px; font-weight:800; color:#ef4444;">{{ activeJob.failed_records }}</div>
            </div>
            <div style="background:#0f172a; border-radius:6px; padding:8px; text-align:center;">
              <div style="font-size:9px; color:#94a3b8;">Skipped</div>
              <div style="font-size:16px; font-weight:800; color:#f97316;">{{ activeJob.skipped_records }}</div>
            </div>
            <div style="background:#0f172a; border-radius:6px; padding:8px; text-align:center;">
              <div style="font-size:9px; color:#94a3b8;">Current Stock</div>
              <div style="font-size:12px; font-weight:700; color:#fff; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">{{ activeJob.current_symbol || '-' }}</div>
            </div>
            <div style="background:#0f172a; border-radius:6px; padding:8px; text-align:center;">
              <div style="font-size:9px; color:#94a3b8;">Last Updated</div>
              <div style="font-size:10px; font-weight:600; color:#94a3b8;">{{ activeJob.last_updated_at | slice:11:19 }}</div>
            </div>
          </div>

          @if (activeJob.error_message) {
            <div style="background:#450a0a; border:1px solid #dc2626; border-radius:6px; padding:8px; color:#fca5a5; font-size:11px; margin-bottom:12px;">
              {{ activeJob.error_message }}
            </div>
          }

          <!-- Action buttons -->
          <div style="display:flex; gap:8px; flex-wrap:wrap; margin-bottom:12px;">
            <button class="btn-blue" style="font-size:11px; padding:6px 12px;" (click)="pollJobStatus()">Refresh Status</button>
            @if (jobMessage) {
              <span style="font-size:11px; color:#93c5fd; align-self:center;">{{ jobMessage }}</span>
            }
            @if (activeJob.status === 'RUNNING') {
              <button class="btn-outline" style="font-size:11px; padding:6px 12px; color:#f59e0b; border-color:#d97706;" (click)="pauseJob()">Pause</button>
            }
            @if (activeJob.status === 'PAUSED' || activeJob.status === 'FAILED') {
              <button class="btn-outline" style="font-size:11px; padding:6px 12px; color:#22c55e; border-color:#16a34a;" (click)="resumeJob()">Resume</button>
            }
            @if (activeJob.status === 'RUNNING' || activeJob.status === 'PAUSED') {
              <button class="btn-outline" style="font-size:11px; padding:6px 12px; color:#ef4444; border-color:#dc2626;" (click)="cancelJob()">Cancel</button>
            }
            @if (activeJob.failed_records > 0) {
              <button class="btn-outline" style="font-size:11px; padding:6px 12px;" (click)="downloadJobRecords('FAILED')">Download Failed</button>
            }
            @if (activeJob.skipped_records > 0) {
              <button class="btn-outline" style="font-size:11px; padding:6px 12px;" (click)="downloadJobRecords('SKIPPED')">Download Skipped</button>
            }
          </div>

          <!-- Messages -->
          @if (activeJob.status === 'RUNNING') {
            <div style="background:#1e3a1e; border:1px solid #16a34a; border-radius:6px; padding:8px; color:#4ade80; font-size:11px;">
              Processing is running. You can continue using the application.
            </div>
          }
          @if (activeJob.status === 'COMPLETED') {
            <div style="background:#1e3a1e; border:1px solid #16a34a; border-radius:6px; padding:8px; color:#4ade80; font-size:11px;">
              Processing completed successfully.
              @if (activeJob.skipped_records > 0) {
                <span style="color:#fde68a;"> Some stocks were skipped. Please download skipped records for details.</span>
              }
            </div>
          }
        } @else {
          <div style="color:#64748b; font-size:12px; text-align:center; padding:20px;">No active processing job selected.</div>
        }

        <!-- Job History -->
        @if (jobHistory.length) {
          <div style="margin-top:14px;">
            <div style="font-size:12px; font-weight:700; color:#fff; margin-bottom:8px;">Recent Jobs</div>
            <div style="overflow-x:auto;">
              <table style="width:100%; border-collapse:collapse;">
                <thead><tr>
                  <th class="tbl-hdr">Job ID</th>
                  <th class="tbl-hdr">File</th>
                  <th class="tbl-hdr" style="text-align:right;">Total</th>
                  <th class="tbl-hdr" style="text-align:right;">Success</th>
                  <th class="tbl-hdr" style="text-align:right;">Failed</th>
                  <th class="tbl-hdr" style="text-align:right;">Progress</th>
                  <th class="tbl-hdr">Status</th>
                  <th class="tbl-hdr">Action</th>
                </tr></thead>
                <tbody>
                  @for (j of jobHistory; track j.job_id) {
                    <tr class="tbl-row" style="cursor:pointer;">
                      <td class="tbl-cell" style="font-size:10px; font-family:monospace;">{{ j.job_id }}</td>
                      <td class="tbl-cell">{{ j.file_name }}</td>
                      <td class="tbl-cell-num">{{ j.total_records }}</td>
                      <td class="tbl-cell-num pos">{{ j.success_records }}</td>
                      <td class="tbl-cell-num neg">{{ j.failed_records }}</td>
                      <td class="tbl-cell-num">{{ j.progress_percentage | number:'1.0-0' }}%</td>
                      <td class="tbl-cell">
                        <span style="font-size:10px; font-weight:600;"
                          [style.color]="jobStatusColor(j.status)">{{ j.status }}</span>
                      </td>
                      <td class="tbl-cell">
                        <button class="btn-outline" style="font-size:9px; padding:2px 8px;" (click)="selectJob(j.job_id)">View</button>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
      </div>
    </div>
  }

  @if (showTradeModal()) {
    <div style="position:fixed; inset:0; background:rgba(0,0,0,.7); z-index:110; display:flex; align-items:center; justify-content:center; padding:16px;"
      (click)="closeTradeModal()">
      <div class="card" style="width:min(440px,100%); padding:20px; position:relative;" (click)="$event.stopPropagation()">
        <button (click)="closeTradeModal()"
          style="position:absolute; top:10px; right:10px; background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer;">x</button>
        <div class="section-title" style="font-size:15px; margin-bottom:4px;">
          {{ tradeDraft.tradeType }} {{ tradeDraft.symbol }}
        </div>
        <p style="font-size:11px; color:#94a3b8; line-height:1.5; margin-bottom:14px;">
          This places a paper portfolio trade. Sell is available only when an open position exists for the symbol.
        </p>

        <label style="display:block; font-size:11px; color:#94a3b8; margin-bottom:4px;">Portfolio</label>
        <select class="select-sm" style="width:100%; padding:8px; margin-bottom:10px;" [(ngModel)]="tradeDraft.portfolioId">
          @for (p of portfolios; track p[0]) {
            <option [ngValue]="p[0]">{{ p[3] || ('Portfolio #' + p[0]) }}</option>
          }
        </select>

        <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px;">
          <div>
            <label style="display:block; font-size:11px; color:#94a3b8; margin-bottom:4px;">Quantity</label>
            <input class="select-sm" style="width:100%; padding:8px;" type="number" min="1" [(ngModel)]="tradeDraft.qty">
          </div>
          <div>
            <label style="display:block; font-size:11px; color:#94a3b8; margin-bottom:4px;">Price</label>
            <input class="select-sm" style="width:100%; padding:8px;" type="number" min="0.01" step="0.01" [(ngModel)]="tradeDraft.price">
          </div>
        </div>

        @if (tradeError()) {
          <div style="margin-top:12px; padding:9px 10px; border-radius:6px; background:#450a0a; border:1px solid #dc2626; color:#fca5a5; font-size:12px;">
            {{ tradeError() }}
          </div>
        }

        <div style="display:flex; justify-content:flex-end; gap:8px; margin-top:16px;">
          <button class="btn-outline" (click)="closeTradeModal()">Cancel</button>
          <button class="btn-green" (click)="submitTrackerTrade()" [disabled]="tradeSubmitting()">
            @if (tradeSubmitting()) { <span class="spin"></span> Saving... } @else { Add Paper Trade }
          </button>
        </div>
      </div>
    </div>
  }

  <!-- ── SUMMARY STRIP ─────────────────────────────────────────────────────────
       One row of headline figures. The six boxes that used to sit here (Current and
       Previous Top 10 Avg Return, Total Uploaded, Today's Upload, Deleted and Active
       Stocks) were removed: the two averages are shown in their own Top 10 panels, and
       the upload counts are all visible in Uploaded Stock Details directly below. -->
  <div class="summary-strip tracker-section">

    <div class="card" title="Every stock currently in your uploaded list.">
      <div class="stat-label">Total Uploaded Stocks</div>
      <div class="stat-value" style="color:#fff;">{{ dash.total | number }}</div>
      <div class="stat-sub">in your watchlist</div>
    </div>

    <div class="card" [title]="'Trading above their base price: ' + (dash.positive || 0) + ' of ' + (dash.total || 0) + ' stocks.'">
      <div class="stat-label">Stocks in Profit</div>
      <div class="stat-value" style="color:#22c55e;">{{ dash.positive | number }}</div>
      <div class="stat-sub" style="color:#22c55e;">{{ profitPct | number:'1.1-1' }}% of uploaded</div>
    </div>

    <div class="card" [title]="'Trading below their base price: ' + (dash.negative || 0) + ' of ' + (dash.total || 0) + ' stocks.'">
      <div class="stat-label">Stocks in Loss</div>
      <div class="stat-value" style="color:#ef4444;">{{ dash.negative | number }}</div>
      <div class="stat-sub" style="color:#ef4444;">{{ lossPct | number:'1.1-1' }}% of uploaded</div>
    </div>

    <div class="card" title="Stocks trading between 0% and 5% above their base price — the buy window.">
      <div class="stat-label">Eligible Stocks</div>
      <div class="stat-value" style="color:#60a5fa;">{{ dash.eligible | number }}</div>
      <div class="stat-sub" style="color:#60a5fa;">{{ eligiblePct | number:'1.1-1' }}% · 0% to 5% band</div>
    </div>

    <div class="card" title="How many separate upload runs make up your stock list.">
      <div class="stat-label">Upload Batches</div>
      <div class="stat-value" style="color:#a78bfa;">{{ uploadStats.total_batches | number }}</div>
      <div class="stat-sub">upload runs</div>
    </div>

    <div class="card" [title]="'Latest upload: ' + (uploadStats.latest_upload_time || 'none yet')">
      <div class="stat-label">Latest Upload</div>
      <div class="stat-value-sm">{{ formatLastUpdated(uploadStats.latest_upload_time) }}</div>
      <div class="stat-sub">last stock list added</div>
    </div>

    <div class="card" [title]="'Prices last refreshed: ' + (dash.last_refreshed || 'not yet')">
      <div class="stat-label">Last Refresh</div>
      <div class="stat-value-sm">{{ formatLastUpdated(dash.last_refreshed) }}</div>
      <div class="stat-sub">prices updated</div>
    </div>

  </div>


  <!-- Upload History Section -->
  @if (showUploadHistory) {
  <div class="card" style="overflow:hidden; margin-bottom:14px;">
    <div style="background:#173456; padding:10px 14px; display:flex; align-items:center; justify-content:space-between; gap:8px; border-bottom:1px solid #2d3f55;">
      <span class="section-title">Upload History</span>
      <div style="display:flex; gap:6px;">
        <button class="btn-outline" (click)="loadUploadHistory()" style="font-size:10px;">Refresh</button>
        <button class="btn-outline" (click)="showUploadHistory=false" style="font-size:10px;">Close</button>
      </div>
    </div>
    <div style="overflow-x:auto;">
      <table style="width:100%; border-collapse:collapse; min-width:1100px;">
        <thead><tr>
          <th class="tbl-hdr">Upload Date</th>
          <th class="tbl-hdr">File Name</th>
          <th class="tbl-hdr">Source</th>
          <th class="tbl-hdr" style="text-align:right;">Total</th>
          <th class="tbl-hdr" style="text-align:right;">Imported</th>
          <th class="tbl-hdr" style="text-align:right;">Duplicate</th>
          <th class="tbl-hdr" style="text-align:right;">Failed</th>
          <th class="tbl-hdr">Status</th>
          <th class="tbl-hdr">Batch ID</th>
          <th class="tbl-hdr" style="text-align:center;">Actions</th>
        </tr></thead>
        <tbody>
          @for (h of uploadHistory; track h.upload_id) {
            <tr class="tbl-row" [style.opacity]="h.is_deleted ? 0.5 : 1">
              <td class="tbl-cell">{{ formatDateDMY(h.upload_date || h.created_at) }}</td>
              <td class="tbl-cell" style="max-width:200px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" [title]="h.file_name">{{ h.file_name }}</td>
              <td class="tbl-cell">{{ h.source || 'CSV Upload' }}</td>
              <td class="tbl-cell-num">{{ h.total_records | number }}</td>
              <td class="tbl-cell-num">{{ h.imported | number }}</td>
              <td class="tbl-cell-num">{{ h.duplicates | number }}</td>
              <td class="tbl-cell-num">{{ h.failed | number }}</td>
              <td class="tbl-cell">
                <span [class]="h.status === 'completed' ? 'badge-green' : h.status === 'failed' ? 'badge-red' : 'badge-blue'">{{ h.status }}</span>
                @if (h.is_deleted) { <span class="badge-red" style="margin-left:4px;">Deleted</span> }
              </td>
              <td class="tbl-cell" style="font-family:monospace; font-size:10px;">{{ h.batch_id || '-' }}</td>
              <td style="padding:5px 8px; text-align:center;">
                @if (!h.is_deleted && h.batch_id) {
                  <button class="trade-mini sell" style="font-size:9px; padding:3px 8px;"
                    (click)="deleteBatch(h.batch_id, h.total_records || 0)"
                    [disabled]="deletingBatch === h.batch_id">
                    {{ deletingBatch === h.batch_id ? 'Deleting...' : 'Delete Batch' }}
                  </button>
                }
              </td>
            </tr>
          }
          @if (!uploadHistory.length) {
            <tr><td colspan="10" style="text-align:center; padding:18px; color:#64748b; font-size:12px;">No upload history found.</td></tr>
          }
        </tbody>
      </table>
    </div>
  </div>
  }

  <div class="card uploaded-details-card tracker-section" style="overflow:hidden;">
    <!-- Uploaded Stock Details Toolbar -->
    <div class="uploaded-toolbar">
      <div class="uploaded-toolbar-left">
        <span class="section-title">Uploaded Stock Details</span>
        <span style="font-size:10px; color:#64748b;">{{ uploadedPageSize }} per page</span>
        <span style="color:#64748b; font-size:10px;" [title]="'Last successful market data synchronization'">
          Last Updated: {{ formatTimestamp(dash.last_refreshed) }}
        </span>
      </div>
      <div class="uploaded-toolbar-actions">
        <input type="text" [(ngModel)]="uploadSearch" (ngModelChange)="onUploadSearchChange()" placeholder="Search symbol..."
          style="background:#0f172a; border:1px solid #2d3f55; border-radius:5px; color:#cbd5e1; font-size:11px; padding:5px 10px; width:140px;">
        @if (uploadedDetailsLoading) {
          <span style="color:#fbbf24; font-size:11px;">Updating...</span>
        }
        <span style="font-size:10px; color:#64748b;">Click column headers to sort</span>
        <button class="btn-outline" (click)="loadUploadedStocks(true, true)" [disabled]="uploadedLoading" style="font-size:10px;" title="Re-reads this table from the database and recalculates Signal and Trend. Does not fetch new prices - use Refresh - Live Price at the top for that.">Reload Table</button>
        <button class="btn-outline" (click)="downloadUploadedStocks()" [disabled]="!uploadedStocks.length" style="font-size:10px;">Export</button>
        <button class="btn-outline" (click)="loadStaleDiagnostic()" [disabled]="staleLoading" style="font-size:10px;" title="Find stocks whose Previous Close cannot update (stale/missing daily history)">
          @if (staleLoading) { Checking... } @else { Check Stale Prices }
        </button>
        <button class="btn-outline" (click)="showUploadHistory=true; loadUploadHistory()" style="font-size:10px;">Upload History</button>
        @if (selectedSymbols.size > 0) {
          <button style="background:#dc2626; color:#fff; border:none; border-radius:5px; padding:5px 10px; font-size:10px; font-weight:600; cursor:pointer;"
            (click)="deleteSelectedStocks()">Delete Selected ({{ selectedSymbols.size }})</button>
        }
        <button style="background:#7f1d1d; color:#fca5a5; border:1px solid #dc2626; border-radius:5px; padding:5px 10px; font-size:10px; font-weight:600; cursor:pointer;"
          (click)="deleteAllUploadedStocks()" title="Delete All Uploaded Stocks">Delete All</button>
      </div>
    </div>

    <!-- Stale Previous Close diagnostic result (BAJFINANCE-type stocks) -->
    @if (staleResult) {
      <div class="card" style="margin:0 0 12px 0; padding:12px 14px; border:1px solid #d97706;">
        <div style="display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:8px;">
          <div>
            <span style="font-size:12px; font-weight:800; color:#fbbf24;">Stale Previous Close — {{ staleResult.count }} stock(s)</span>
            <div style="font-size:10px; color:#94a3b8; margin-top:2px;">
              Latest daily candle older than {{ staleResult.threshold_date }} (or missing). Previous Close can't roll forward for these. Fix: re-fetch Stock List, then run the daily backfill.
            </div>
          </div>
          <button class="btn-outline" (click)="staleResult = null" style="font-size:10px;">Close</button>
        </div>
        @if (staleResult.count === 0) {
          <div style="font-size:11px; color:#4ade80; padding:6px 0;">No stale stocks found — all active stocks have fresh daily history.</div>
        } @else {
          <div style="overflow-x:auto; max-height:320px; overflow-y:auto;">
            <table style="width:100%; border-collapse:collapse; min-width:640px;">
              <thead>
                <tr>
                  <th class="tbl-hdr">Symbol</th>
                  <th class="tbl-hdr">Latest Daily Candle</th>
                  <th class="tbl-hdr" style="text-align:right;">Days Stale</th>
                  <th class="tbl-hdr" style="text-align:right;">Base Price</th>
                  <th class="tbl-hdr" style="text-align:right;">Current Price</th>
                  <th class="tbl-hdr">Last Refreshed</th>
                </tr>
              </thead>
              <tbody>
                @for (s of staleResult.stocks; track s.symbol) {
                  <tr class="tbl-row">
                    <td style="font-size:12px; padding:6px 10px; font-weight:700; color:#fff;">{{ s.symbol }}</td>
                    <td class="tbl-cell" [style.color]="s.latest_daily_candle === 'None' ? '#f87171' : '#cbd5e1'">{{ s.latest_daily_candle }}</td>
                    <td class="tbl-cell-num" style="color:#fbbf24;">{{ s.days_stale != null ? s.days_stale : '-' }}</td>
                    <td class="tbl-cell-num">{{ s.base_price != null ? (s.base_price | number:'1.2-2') : '-' }}</td>
                    <td class="tbl-cell-num">{{ s.current_price != null ? (s.current_price | number:'1.2-2') : '-' }}</td>
                    <!-- The session the price belongs to, not the moment it was fetched.
                         last_refreshed moves on every background refresh even when nothing
                         traded, so the column ticked forward against prices that had not
                         changed - and after the close it moves while the market is shut.
                         current_price_date is the price's own session; the fetch time stays
                         available on hover for anyone chasing a sync problem. -->
                    <td class="tbl-cell" style="font-size:10px;"
                        [title]="'Price is from the ' + (s.current_price_date || s.close_date || 'last stored') + ' session. Last fetched ' + (s.last_refreshed || '-') + '.'">
                      {{ s.current_price_date || s.close_date ? (s.current_price_date || s.close_date) : formatFetchDateTime(s.last_refreshed) }}
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </div>
    }

    <!-- No overflow wrapper and no min-width: the grid fits the page instead of
         scrolling sideways. table-layout:fixed keeps the columns from overflowing. -->
    <div>
      <table style="width:100%; border-collapse:collapse; table-layout:fixed;">
        <thead>
          <tr>
            <th class="tbl-hdr" style="width:32px; text-align:center;">
              <input type="checkbox" [checked]="allSelected()" (change)="toggleSelectAll($event)" style="cursor:pointer;">
            </th>
            <th class="tbl-hdr-sort" (click)="toggleUploadedSort('symbol')">Symbol {{ sortIcon('symbol') }}</th>
            <th class="tbl-hdr-sort" (click)="toggleUploadedSort('sector')">Sector {{ sortIcon('sector') }}</th>
            <th class="tbl-hdr-sort" (click)="toggleUploadedSort('market_cap')">Market Cap {{ sortIcon('market_cap') }}</th>
            <th class="tbl-hdr-sort" (click)="toggleUploadedSort('upload_date')">Upload Date {{ sortIcon('upload_date') }}</th>
            <th class="tbl-hdr-sort" style="text-align:right;" (click)="toggleUploadedSort('base_price')" title="Close price for the uploaded date">Base Price {{ sortIcon('base_price') }}</th>
            <th class="tbl-hdr-sort" style="text-align:right;" (click)="toggleUploadedSort('current_price')">Current Price {{ sortIcon('current_price') }}</th>
            <th class="tbl-hdr" style="text-align:center; width:60px;">Status</th>
            <th class="tbl-hdr-sort" style="text-align:right;" (click)="toggleUploadedSort('return_pct')">Return % {{ sortIcon('return_pct') }}</th>
            <th class="tbl-hdr-sort" (click)="toggleUploadedSort('buy_sell_signal')">Signal {{ sortIcon('buy_sell_signal') }}</th>
            <th class="tbl-hdr-sort" (click)="toggleUploadedSort('trend')">Trend {{ sortIcon('trend') }}</th>
            <th class="tbl-hdr-sort" style="text-align:right;" (click)="toggleUploadedSort('volume')">Volume {{ sortIcon('volume') }}</th>
            <th class="tbl-hdr-sort" style="text-align:right;" (click)="toggleUploadedSort('last_refreshed')">Last Update {{ sortIcon('last_refreshed') }}</th>
          </tr>
        </thead>
        <tbody>
          @for (s of uploadedStocks; track s.symbol) {
            <tr class="tbl-row">
              <td style="padding:7px 6px; text-align:center;">
                <input type="checkbox" [checked]="selectedSymbols.has(s.symbol)" (change)="toggleSelect(s.symbol)" style="cursor:pointer;">
              </td>
              <td style="font-size:12px; padding:7px 10px; font-weight:700; color:#fff;">{{ s.symbol }}</td>
              <td class="tbl-cell">{{ s.sector || '-' }}</td>
              <td class="tbl-cell">{{ s.market_cap || '-' }}</td>
              <td class="tbl-cell">{{ formatDateDMY(s.upload_date) }}</td>
              <td class="tbl-cell-num" [title]="s.base_price_error || ('Upload-date close: ' + (s.base_price_source || s.upload_date || ''))">
                @if (s.base_price != null && s.base_price > 0) {
                  {{ s.base_price | number:'1.2-2' }}
                } @else if (s.base_price_error) {
                  <span style="color:#fbbf24; font-size:9px;">{{ readableDhanError(s.base_price_error) }}</span>
                } @else {
                  <span style="color:#f59e0b; font-size:9px;">Waiting for broker price</span>
                }
              </td>
              <td class="tbl-cell-num" style="font-weight:600; position:relative;"
                [style.color]="priceChangeColor(s)"
                [title]="priceTooltip(s)">
                @if (s.current_price != null && s.current_price > 0) {
                  {{ s.current_price | number:'1.2-2' }}
                  @if (s._prev_price && s._prev_price !== s.current_price) {
                    <span style="font-size:8px; position:absolute; right:3px; top:2px;">{{ s.current_price > s._prev_price ? '▲' : '▼' }}</span>
                  }
                  <div style="font-size:8px; color:#64748b; font-weight:400; text-align:right;">{{ s.price_type || '' }}</div>
                } @else {
                  <span style="color:#f59e0b; font-size:9px;">Retry after sync</span>
                }
                @if (s.price_status && s.price_status !== 'OK') {
                  <div style="margin-top:2px; text-align:right;">
                    <span class="badge-amber" [title]="s.price_status_reason || s.price_status">{{ s.price_status }}</span>
                  </div>
                }
              </td>
              <td style="padding:5px 6px; text-align:center;">
                <span style="font-size:9px; font-weight:700; border-radius:3px; padding:2px 6px; white-space:nowrap;"
                  [style.background]="s.market_status === 'Live' ? '#14532d' : '#1e293b'"
                  [style.color]="s.market_status === 'Live' ? '#4ade80' : '#94a3b8'"
                  [style.border]="s.market_status === 'Live' ? '1px solid #16a34a' : '1px solid #334155'">{{ s.market_status || 'Closed' }}</span>
                <div style="font-size:7px; color:#475569; margin-top:1px;" [title]="s.current_price_source || ''">{{ priceSourceLabel(s.current_price_source) }}</div>
              </td>
              <td style="font-size:11px; padding:7px 10px; text-align:right; font-weight:700;" [class]="(s.return_pct || 0) >= 0 ? 'pos' : 'neg'">
                {{ s.return_pct | number:'1.2-2' }}%
              </td>
              <td class="tbl-cell">
                <span [class]="signalBadgeClass(s.buy_sell_signal)" [title]="s.signal_message || ''">{{ s.buy_sell_signal || 'DATA REQUIRED' }}</span>
              </td>
              <td class="tbl-cell">
                <span [class]="trendBadgeClass(s.trend)" [title]="'Trend score: ' + (s.trend_score || 0)">{{ s.trend || 'Data Required' }}</span>
              </td>
              <td class="tbl-cell-num" style="font-size:10px;" [title]="'Traded volume on ' + (s.close_date || s.market_price_date || 'the last stored session')">
                {{ s.volume ? (s.volume | number:'1.0-0') : '-' }}
              </td>
              <td class="tbl-cell" style="font-size:10px; white-space:nowrap; text-align:right;"
                  [title]="'When the price last moved. Live tick during market hours, otherwise the close of the last session (' + (s.close_date || s.market_price_date || 'n/a') + '). Last fetched ' + (s.last_refreshed || '-')">
                @if (s.last_update) {
                  <div style="color:#e2e8f0;">{{ formatFetchDateTime(s.last_update) }}</div>
                } @else {
                  <span style="color:#64748b;">-</span>
                }
              </td>
            </tr>
          }
          @if (!uploadedStocks.length && !uploadedLoading) {
            <tr><td colspan="13" style="text-align:center; padding:18px; color:#64748b; font-size:12px;">
              No uploaded stocks found yet.
            </td></tr>
          }
          @if (uploadedLoading) {
            <tr><td colspan="15" style="text-align:center; padding:18px; color:#94a3b8; font-size:12px;">
              Loading uploaded stocks...
            </td></tr>
          }
        </tbody>
      </table>
    </div>
    <div class="pager">
      <div class="pager-info">
        Showing {{ uploadedPageStart() }} - {{ uploadedPageEnd() }} of {{ uploadedTotal | number }}
      </div>
      <div style="display:flex; align-items:center; gap:8px;">
        <button class="btn-outline" (click)="setUploadedPage(uploadedPage - 1)" [disabled]="uploadedPage <= 0 || uploadedLoading">Previous</button>
        <span style="color:#cbd5e1; font-size:11px;">Page {{ uploadedPage + 1 }} of {{ uploadedTotalPages() }}</span>
        <button class="btn-outline" (click)="setUploadedPage(uploadedPage + 1)" [disabled]="uploadedPage + 1 >= uploadedTotalPages() || uploadedLoading">Next</button>
      </div>
    </div>
  </div>

  <!-- â”€â”€ MAIN 3-COLUMN LAYOUT â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ -->
  <div style="display:flex; flex-direction:column; gap:12px;">

    <!-- â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• LEFT COL â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• -->
    <!-- â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• CENTRE COL â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• -->
    <div style="display:flex; flex-direction:column; gap:12px;">

      @if (rd?.ranking_warning) {
        <div class="card" style="padding:10px 14px;" [style.border-color]="rd?.partial_ranking || rd?.fallback_ranking ? '#d97706' : '#16a34a'">
          <div style="display:flex; align-items:flex-start; justify-content:space-between; gap:12px; flex-wrap:wrap;">
            <div>
              <div style="font-size:12px; font-weight:800;" [style.color]="rd?.partial_ranking || rd?.fallback_ranking ? '#fbbf24' : '#4ade80'">
                {{ rd?.fallback_ranking ? 'Fallback Top 10 Active' : (rd?.partial_ranking ? 'Partial Ranking Active' : 'Ranking Components Ready') }}
              </div>
              <div style="color:#cbd5e1; font-size:11px; margin-top:4px;">{{ rd?.message || rd?.ranking_warning }}</div>
            </div>
            @if (rd?.considered_components?.length) {
              <div style="color:#94a3b8; font-size:11px;">Considering: {{ rd.considered_components.join(', ') }}</div>
            }
          </div>
          @if (rd?.skipped_components?.length) {
            <div style="display:flex; gap:6px; flex-wrap:wrap; margin-top:8px;">
              @for (c of rd.skipped_components; track c.key) {
                <span style="border:1px solid #d97706; color:#fde68a; background:#3a2608; border-radius:6px; padding:4px 7px; font-size:10px;">
                  {{ c.label }} skipped for {{ c.affected_stocks }} stocks: {{ c.reason }}
                </span>
              }
            </div>
          }
        </div>
      }

      <!-- CURRENT TOP 10 TABLE -->
      <div class="card" style="overflow:hidden;">
        <!-- Header row with metrics -->
        <div style="background:#1a3a5c; padding:10px 14px; display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:8px;">
          <div style="display:flex; align-items:center; gap:8px;">
            <span class="section-title" style="font-size:14px;">Today top 10 Ranking Stocks</span>
            <button class="btn-green" style="padding:4px 12px; font-size:10.5px;"
                    (click)="runRanking()" [disabled]="ranking()"
                    title="Score every uploaded stock now and rebuild this Top 10">
              @if (ranking()) { <span class="spin"></span> Running... } @else { Run Ranking Now }
            </button>
            @if (top10Refreshing) { <span class="spin"></span> }
          </div>
          @if (top10RefreshError) {
            <div style="color:#fca5a5; font-size:10px; background:#450a0a; border:1px solid #dc2626; border-radius:4px; padding:3px 8px;">
              Unable to refresh latest Top 10 Stocks. Showing last available data.
            </div>
          }
          <div style="display:flex; gap:8px; flex-wrap:wrap; align-items:center;">
            <button class="btn-outline" style="font-size:10px; padding:4px 10px;" (click)="toggleAutomationPanel()"
              [style.borderColor]="automation.prevtop10_auto_cycle_enabled ? '#22c55e' : '#334155'"
              title="Auto refresh + auto buy/sell settings">
              Automation {{ automation.prevtop10_auto_cycle_enabled ? 'ON' : 'OFF' }}
            </button>
            <button class="btn-outline" style="font-size:10px; padding:4px 10px;" (click)="refreshTop10()" [disabled]="top10Refreshing">
              @if (top10Refreshing) { Refreshing... } @else { Refresh Top 10 }
            </button>
            <select class="select-sm" [(ngModel)]="bulkOrderMode" style="min-width:92px;" [disabled]="true" title="Testing mode: Paper trade only">
              <option value="PAPER">Paper</option>
            </select>
            <button class="btn-green" style="padding:6px 12px; font-size:11px;" (click)="bulkTop10Order('BUY')" [disabled]="bulkTrading() || !(rd?.current_top10?.length)">
              @if (bulkTrading()) { <span class="spin"></span> } @else { Buy All 10 }
            </button>
            <button class="trade-mini sell" style="padding:7px 12px; font-size:11px;" (click)="bulkTop10Order('SELL')" [disabled]="bulkTrading() || !(rd?.current_top10?.length)">
              Sell All 10
            </button>
            <!-- Average Return, measured against the PREVIOUS CLOSE.
                 It used to show curr_avg_return, the average move from each stock's base price -
                 the price it was uploaded at, sometimes months and hundreds of percent ago. That
                 describes the stock's history rather than today, and it disagreed with the Change
                 Today column beneath it, which was already prev-close based. Same basis now, so
                 the headline and the column can no longer contradict each other. -->
            <div style="background:#0f172a; border-radius:6px; padding:6px 14px; text-align:center; min-width:104px;"
                 [title]="'Average of Change Today across the ' + filteredTop10().length + ' stocks shown: (Current - Previous Close) / Previous Close.'">
              <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; margin-bottom:2px;">Average Return</div>
              <div style="font-size:16px; font-weight:800;" [class]="top10AvgChange >= 0 ? 'pos' : 'neg'">{{ top10AvgChange | number:'1.2-2' }}%</div>
              <div style="font-size:8px; color:#64748b;">vs previous close</div>
            </div>
          </div>
        </div>

        <!-- The entry test as this section actually applies it. It differs from the crossing-entry
             panel below on purpose, so the wording must differ too - text that does not match the
             code is worse than no text, because it is believed. -->
        <div style="background:#16304d; border-bottom:1px solid #28415f; padding:6px 14px;
                    font-size:10px; color:#94a3b8; line-height:1.55;">
          <span style="color:#cbd5e1; font-weight:700;">Entry test:</span>
          Current Price &ge; Base Price <strong>and</strong> Current Volume &gt; 20-Day Average Volume.
          Then Rank, then Buy/Sell &mdash; in that order.
          <span style="color:#64748b;">(No crossing requirement here &mdash; that is the panel below.)</span>
        </div>

        <!-- Run meta: kept on its own narrow strip so the title row stays readable. Each value
             is a fixed-width labelled cell, so they line up instead of running together. -->
        <div style="background:#16304d; border-bottom:1px solid #28415f; padding:5px 14px;
                    display:flex; align-items:center; gap:18px; flex-wrap:wrap; font-size:10px;">
          @if (rd?.run_date) {
            <span style="display:inline-flex; align-items:baseline; gap:5px;">
              <span style="color:#7d92ab; min-width:52px;">Ranking</span>
              <span style="color:#cbd5e1; font-weight:600;">{{ rd.run_date }}</span>
            </span>
          }
          <!-- The time the price TRADED, not the time the page fetched it. This showed
               dash.last_refreshed - the server's last sync - or, failing that, the moment the
               browser received the response. Both move when nothing has traded, so after the
               close the header ticked forward against prices that had stopped changing. The
               server already returns the traded time; the screen simply was not reading it. -->
          <span style="display:inline-flex; align-items:baseline; gap:5px;"
                [title]="rd?.market_open
                  ? 'Live price, updating while the market is open.'
                  : 'The market is closed. This is when the last price actually traded, taken from the newest stored candle - not when the page was refreshed.'">
            <span style="color:#7d92ab; min-width:74px;">{{ rd?.market_open ? 'Last traded' : 'Last traded' }}</span>
            <span style="color:#cbd5e1; font-weight:600;">{{ formatTimestamp(rd?.last_updated || rd?.last_traded_at || dash.last_refreshed || currentTop10LastUpdated) }}</span>
          </span>
          @if (rd && rd.market_open === false) {
            <span style="color:#f59e0b; font-weight:600;">Market closed</span>
          } @else if (rd?.market_open) {
            <span style="color:#22c55e; font-weight:600;">Market open</span>
          }
        </div>

        <!-- Automation panel (auto refresh + auto buy/sell) -->
        @if (showAutomationPanel) {
          <div style="background:#0d1a2b; border-bottom:1px solid #28415f; padding:12px 14px; display:flex; flex-direction:column; gap:10px;">
            <div style="display:flex; align-items:center; gap:16px; flex-wrap:wrap;">
              <label style="font-size:11px; color:#e2e8f0; display:flex; align-items:center; gap:6px; cursor:pointer;">
                <input type="checkbox" [(ngModel)]="automation.top10_auto_refresh_enabled">
                Auto-refresh Current Top 10
              </label>
              <span style="font-size:11px; color:#94a3b8;">Run
                <select class="select-sm" [(ngModel)]="automation.top10_auto_refresh_mode" style="font-size:11px; margin-left:4px;"
                        title="Every N minutes while the market is open, or at clock times you choose.">
                  <option value="INTERVAL">every</option>
                  <option value="FIXED">at set times</option>
                </select>
              </span>
              @if (automation.top10_auto_refresh_mode !== 'FIXED') {
                <select class="select-sm" [(ngModel)]="automation.top10_auto_refresh_interval_sec" style="font-size:11px;"
                        title="How often the Top 10 is re-ranked and written to the database.">
                  @for (o of top10IntervalOptions; track o.sec) {
                    <option [ngValue]="o.sec">{{ o.label }}</option>
                  }
                </select>
                <span style="font-size:10px; color:#64748b;">while the market is open</span>
              } @else {
                <input class="select-sm" type="text" [(ngModel)]="automation.top10_auto_refresh_times"
                       placeholder="09:20,12:00,15:20" style="width:190px; font-size:11px;"
                       title="Comma-separated 24-hour times. Invalid entries are dropped when saved.">
                <span style="font-size:10px; color:#64748b;">on trading days</span>
              }
            </div>
            <div style="display:flex; align-items:center; gap:12px; flex-wrap:wrap;">
              <label style="font-size:11px; color:#e2e8f0; display:flex; align-items:center; gap:6px; cursor:pointer;"
                     title="Lock one run a day as that day's official Top 10. That locked list is what appears as Previous Top 10 tomorrow.">
                <input type="checkbox" [(ngModel)]="automation.top10_lock_enabled">
                Lock Current Top 10 at
              </label>
              <select class="select-sm" [(ngModel)]="automation.top10_lock_time" style="font-size:11px;"
                      [disabled]="!automation.top10_lock_enabled"
                      title="The Top 10 priced at this time becomes the locked list for the day.">
                @for (t of buyTimeOptions; track t) {
                  <option [value]="t">{{ t }}</option>
                }
              </select>
              <span style="font-size:10px; color:#64748b;">becomes tomorrow&rsquo;s Previous Top 10</span>
            </div>
            <div style="height:1px; background:#1e2f45;"></div>
            <div style="font-size:10px; color:#64748b; text-transform:uppercase; letter-spacing:.5px;">Previous Top 10 — Daily Auto Cycle</div>
            <div style="display:flex; align-items:center; gap:16px; flex-wrap:wrap;">
              <label style="font-size:11px; color:#e2e8f0; display:flex; align-items:center; gap:6px; cursor:pointer;">
                <input type="checkbox" [(ngModel)]="automation.prevtop10_auto_cycle_enabled">
                Enable daily cycle
              </label>
              <label style="font-size:11px; color:#e2e8f0; display:flex; align-items:center; gap:6px; cursor:pointer;">
                <input type="checkbox" [(ngModel)]="automation.prevtop10_auto_buy_enabled" [disabled]="!automation.prevtop10_auto_cycle_enabled">
                Auto Buy
              </label>
              <span style="font-size:11px; color:#94a3b8;">at
                <input class="select-sm" type="text" [(ngModel)]="automation.prevtop10_buy_time" placeholder="15:15" style="width:66px;">
              </span>
              <label style="font-size:11px; color:#e2e8f0; display:flex; align-items:center; gap:6px; cursor:pointer;">
                <input type="checkbox" [(ngModel)]="automation.prevtop10_auto_sell_enabled" [disabled]="!automation.prevtop10_auto_cycle_enabled">
                Auto Sell
              </label>
              <span style="font-size:11px; color:#94a3b8;">at
                <input class="select-sm" type="text" [(ngModel)]="automation.prevtop10_sell_time" placeholder="10:15" style="width:66px;">
              </span>
            </div>
            <div style="display:flex; align-items:center; gap:16px; flex-wrap:wrap;">
              <span style="font-size:11px; color:#94a3b8;">Execution mode:
                <select class="select-sm" [(ngModel)]="automation.prevtop10_execution_mode" (ngModelChange)="onLiveModeChange()" style="min-width:90px;">
                  <option value="PAPER">Paper</option>
                  <option value="LIVE">Live (real)</option>
                </select>
              </span>
              @if (automation.prevtop10_execution_mode==='LIVE') {
                <span style="font-size:10px; color:#fca5a5; background:#450a0a; border:1px solid #dc2626; border-radius:4px; padding:3px 8px;">
                  LIVE mode can place REAL orders automatically.
                </span>
              }
              <label style="font-size:11px; color:#e2e8f0; display:flex; align-items:center; gap:6px; cursor:pointer;">
                <input type="checkbox" [(ngModel)]="automation.prevtop10_use_real_price_if_executed">
                Use real executed price when available
              </label>
            </div>
            <div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap;">
              <span style="font-size:11px; color:#94a3b8;">Market holidays (yyyy-MM-dd, comma separated):</span>
              <input class="select-sm" type="text" [(ngModel)]="automation.market_holidays" placeholder="2026-08-15, 2026-10-02" style="min-width:260px; flex:1;">
            </div>
            <div style="display:flex; gap:8px;">
              <button class="btn-green" style="font-size:11px; padding:6px 14px;" (click)="saveAutomationConfig()" [disabled]="automationSaving">
                @if (automationSaving) { <span class="spin"></span> Saving... } @else { Save Automation }
              </button>
              <button class="btn-outline" style="font-size:11px; padding:6px 14px;" (click)="toggleAutomationPanel()">Close</button>
            </div>
          </div>
        }

        <!-- Total Trading Amount allocation -->
        <div style="background:#132237; border-bottom:1px solid #28415f; padding:8px 14px; display:flex; align-items:center; gap:12px; flex-wrap:wrap;">
          <div style="display:flex; align-items:center; gap:6px;">
            <span style="font-size:11px; color:#94a3b8;">Total Trading Amount (&#8377;):</span>
            <input class="select-sm" type="number" [(ngModel)]="totalTradingAmount" (ngModelChange)="recalcAllocation()" min="1" max="100000000" step="1000"
              style="width:130px; text-align:right;" placeholder="100000">
          </div>
          @if (totalTradingAmount <= 0) {
            <span style="font-size:10px; color:#fca5a5;">Amount must be greater than zero</span>
          }
          <div style="font-size:10px; color:#64748b;">
            Per Stock: <strong style="color:#60a5fa;">&#8377;{{ perStockAllocation | number:'1.0-0' }}</strong>
          </div>
          <div style="font-size:10px; color:#64748b;">
            Remaining: <strong style="color:#94a3b8;">&#8377;{{ remainingBalance | number:'1.0-0' }}</strong>
          </div>
          @if (!isMarketHours()) {
            <span style="font-size:10px; color:#fbbf24; background:#3a2608; border:1px solid #d97706; border-radius:4px; padding:3px 8px;">
              Market Closed — Paper trade only
            </span>
          }
          <!-- Buyer/Seller Availability Toggle -->
          <div style="display:flex; align-items:center; gap:6px; margin-left:auto;">
            <label style="font-size:10px; color:#94a3b8; cursor:pointer; display:flex; align-items:center; gap:4px;">
              <input type="checkbox" [(ngModel)]="filterBuyerSeller" (ngModelChange)="applyBuyerSellerFilter()">
              Buyer/Seller Available Only
            </label>
            @if (filterBuyerSeller && excludedCount > 0) {
              <span style="font-size:10px; color:#f59e0b;">{{ excludedCount }} excluded</span>
            }
          </div>
        </div>

        <!-- Ranking warnings / missing data messages -->
        @if (rd?.partial_ranking) {
          <div style="background:#3a2608; border-bottom:1px solid #d97706; padding:6px 14px; font-size:10px; color:#fde68a;">
            {{ rd.ranking_warning }}
            @if (rd.skipped_components?.length) {
              <span style="color:#94a3b8;"> Skipped:
                @for (sc of rd.skipped_components; track sc.key) {
                  {{ sc.label }} ({{ sc.reason }}){{ $last ? '' : ', ' }}
                }
              </span>
            }
          </div>
        }

        <div style="overflow-x:auto;">
          <table style="width:100%; border-collapse:collapse; min-width:900px;">
            <thead>
              <tr>
                <th class="tbl-hdr" style="width:36px; text-align:center;" title="Position in today's ranking. 1 is the highest-scoring stock.">Rank</th>
                <th class="tbl-hdr" title="Stock symbol. The smaller line below is the broker's trading symbol where it differs.">Symbol</th>
                <th class="tbl-hdr" style="text-align:right;" title="The price recorded when this stock was uploaded. All gain and loss figures are measured from here.">Base Price</th>
                <th class="tbl-hdr" style="text-align:right;" title="Closing price of the previous trading day.">Prev Close</th>
                <th class="tbl-hdr" style="text-align:right;" title="Latest traded price. Updates live while the market is open.">Current Price</th>
                <th class="tbl-hdr" style="text-align:right;" title="Move since the previous close: (Current - Prev Close) / Prev Close.">Change Today</th>
                <th class="tbl-hdr" style="text-align:right;" title="Ranking score out of 100, combining relative strength, volume growth, trend, RSI, moving-average position, momentum, 52-week position, breakout, institutional interest, liquidity and market-cap quality. Higher is better.">Score /100</th>
                <th class="tbl-hdr" style="text-align:right;" title="Today's traded volume against its recent average. A high positive figure means unusually heavy trading.">Volume Change</th>
                <th class="tbl-hdr" style="text-align:right;" title="Money allocated to each stock — your Total Trading Amount divided by 10.">Amount / Stock</th>
                <th class="tbl-hdr" style="text-align:right;" title="Whole shares that amount buys at the current price.">Qty</th>
                <th class="tbl-hdr" title="How long ago this row's price was last refreshed. Hover a value for the exact time.">Last Updated</th>
              </tr>
            </thead>
            <tbody>
              @for (s of filteredTop10(); track s.symbol) {
                <tr class="tbl-row" [class.row-flash]="rowFlash(s.symbol)">
                  <td style="font-size:12px; padding:7px 10px; text-align:center; font-weight:700;"
                    [style.color]="s.rank===1?'#facc15':s.rank<=3?'#94a3b8':'#64748b'">
                    {{ s.rank }}
                  </td>
                  <td style="font-size:12px; padding:7px 10px; font-weight:700; color:#fff;">
                    {{ s.symbol }}
                    @if (s.trading_symbol && s.trading_symbol !== s.symbol) {
                      <div style="font-size:9px; color:#64748b;">{{ s.trading_symbol }}</div>
                    }
                  </td>
                  <td class="tbl-cell-num">
                    @if (s.base_price > 0) { {{ s.base_price | number:'1.2-2' }} }
                    @else { <span style="color:#f59e0b; font-size:9px;" title="No base price recorded for this stock yet, so its gain cannot be measured.">Not set</span> }
                  </td>
                  <td class="tbl-cell-num">{{ s.previous_close != null ? (s.previous_close | number:'1.2-2') : '-' }}</td>
                  <td class="tbl-cell-num" style="font-weight:600; color:#fff;">
                    @if (s.current_price != null && s.current_price > 0) {
                      {{ s.current_price | number:'1.2-2' }}
                    } @else {
                      <span style="color:#f59e0b; font-size:9px;" title="No live or stored price available for this stock right now.">No price</span>
                    }
                  </td>
                  <td style="font-size:11px; padding:7px 10px; text-align:right; font-weight:700;"
                    [class]="(s.today_return_pct || 0) >= 0 ? 'pos' : 'neg'">
                    @if (s.previous_close) { {{ s.today_return_pct | number:'1.2-2' }}% } @else { - }
                  </td>
                  <td style="font-size:11px; padding:7px 10px; text-align:right; font-weight:700;">
                    <button type="button" class="score-btn"
                      [class.score-btn-open]="expandedFactors.has(s.symbol)"
                      (click)="toggleFactors(s.symbol)"
                      [title]="factorRows(s).length ? 'Show how this score was built' : 'No factor detail stored for this stock'">
                      {{ s.score | number:'1.2-2' }}
                      @if (factorRows(s).length) {
                        <span class="score-caret">{{ expandedFactors.has(s.symbol) ? '&#9650;' : '&#9660;' }}</span>
                      }
                    </button>
                  </td>
                  <td class="tbl-cell-num" [class]="(s.today_volume_change_pct || 0) >= 0 ? 'pos' : 'neg'">
                    {{ s.today_volume_change_pct != null ? ((s.today_volume_change_pct | number:'1.1-1') + '%') : '-' }}
                  </td>
                  <td class="tbl-cell-num" style="color:#60a5fa;">{{ perStockAllocation | number:'1.0-0' }}</td>
                  <td class="tbl-cell-num" style="color:#fff; font-weight:600;">{{ calcQty(s.current_price) }}</td>
                  <td class="tbl-cell" style="font-size:10px; white-space:nowrap;" [title]="s.last_updated || 'Never refreshed'">{{ formatLastUpdated(s.last_updated) }}</td>
                </tr>
                @if (expandedFactors.has(s.symbol)) {
                  <tr>
                    <td colspan="11" style="padding:0; background:#0d1520; border-bottom:1px solid #1e2a3b;">
                      <div style="padding:10px 14px;">
                        <div style="font-size:11px; font-weight:800; color:#e2e8f0; margin-bottom:7px;">
                          Score breakdown &mdash; {{ s.symbol }}
                          <span style="font-weight:500; color:#94a3b8; margin-left:8px;">
                            each component scores 0&ndash;10; the total is weighted by the points beside it,
                            divided by the weight that had data
                          </span>
                        </div>
                        <!-- Many columns, so the panel scrolls sideways rather than wrapping. -->
                        <div style="overflow-x:auto;">
                          <table style="border-collapse:collapse; white-space:nowrap;">
                            <thead>
                              <tr>
                                <th class="sb-hdr" title="Stock symbol">Symbol</th>
                                <th class="sb-hdr" title="Sector as uploaded">Sector</th>
                                <th class="sb-hdr" style="text-align:right;" title="Final weighted score out of 100">Score</th>
                                <th class="sb-hdr" style="text-align:right;" title="Last traded price">LTP</th>
                                <th class="sb-hdr" style="text-align:right;" title="Gain since the base price">Return %</th>
                                <th class="sb-hdr" style="text-align:right;" title="Best buy price on the book">Bid</th>
                                <th class="sb-hdr" style="text-align:right;" title="Best sell price on the book">Ask</th>
                                @for (c of components(s); track c.key) {
                                  <th class="sb-hdr" style="text-align:right;" [title]="componentHint(c)">
                                    {{ c.short_label }}
                                    <span style="color:#64748b; font-weight:500;">w{{ c.weight | number:'1.0-0' }}</span>
                                  </th>
                                }
                                <th class="sb-hdr" style="text-align:right;"
                                    title="Total weight of the components that had data. Skipped ones are removed from the divisor rather than counted as zero.">
                                  Weight Used
                                </th>
                              </tr>
                            </thead>
                            <tbody>
                              <tr>
                                <td class="sb-cell" style="color:#fff; font-weight:700;">{{ s.symbol }}</td>
                                <td class="sb-cell" style="color:#cbd5e1;">{{ s.sector || '-' }}</td>
                                <td class="sb-cell" style="text-align:right; color:#60a5fa; font-weight:800;">{{ s.score | number:'1.2-2' }}</td>
                                <td class="sb-cell" style="text-align:right; color:#fff;">{{ s.current_price | number:'1.2-2' }}</td>
                                <td class="sb-cell" style="text-align:right;" [class]="(s.return_pct || 0) >= 0 ? 'pos' : 'neg'">{{ s.return_pct | number:'1.2-2' }}%</td>
                                <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ s.bid ? (s.bid | number:'1.2-2') : '-' }}</td>
                                <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ s.ask ? (s.ask | number:'1.2-2') : '-' }}</td>
                                @for (c of components(s); track c.key) {
                                  <td class="sb-cell" style="text-align:right; font-weight:700;"
                                      [style.color]="scoreColour(c.score)"
                                      [title]="c.available ? c.label : (c.label + ' - ' + (c.reason || 'no data'))">
                                    {{ c.available ? (c.score | number:'1.1-1') : 'n/a' }}
                                  </td>
                                }
                                <td class="sb-cell" style="text-align:right; color:#e2e8f0; font-weight:800;">
                                  {{ weightUsed(s) | number:'1.0-0' }}
                                </td>
                              </tr>
                            </tbody>
                          </table>
                        </div>
                        @if (skippedFactors(s).length) {
                          <div style="margin-top:8px; font-size:10px; color:#94a3b8; line-height:1.6;">
                            <span style="color:#f59e0b; font-weight:700;">Not counted:</span>
                            @for (sk of skippedFactors(s); track sk.key) {
                              <span style="margin-left:6px;"><span style="color:#cbd5e1;">{{ sk.label }}</span> &mdash; {{ sk.reason }}</span>
                            }
                          </div>
                        }
                      </div>
                    </td>
                  </tr>
                }
              }
              @if (!rd?.current_top10?.length) {
                <tr><td colspan="11" style="text-align:center; padding:24px; color:#94a3b8; font-size:12px; line-height:1.7;">
                  <div style="font-weight:700; color:#e2e8f0;">No ranking yet.</div>
                  <div>Upload your stock list, then press <strong style="color:#cbd5e1;">Run Ranking Now</strong> in this
                  section's header to score every stock and pick today's Top 10.</div>
                </td></tr>
              }
            </tbody>
            @if (filteredTop10().length) {
              <tfoot>
                <tr style="background:#16202e; border-top:2px solid #2d3f55;">
                  <td colspan="5" style="font-size:10px; padding:8px 10px; color:#94a3b8;">
                    Totals across {{ filteredTop10().length }} stock{{ filteredTop10().length === 1 ? '' : 's' }}
                    @if (filterBuyerSeller && excludedCount) {
                      <span style="color:#f59e0b;"> · {{ excludedCount }} hidden by the buyer/seller filter</span>
                    }
                  </td>
                  <td style="font-size:11px; padding:8px 10px; text-align:right; font-weight:800;"
                      [class]="top10AvgChange >= 0 ? 'pos' : 'neg'"
                      title="Average change today across the stocks shown.">
                    {{ top10AvgChange | number:'1.2-2' }}%
                  </td>
                  <td style="font-size:11px; padding:8px 10px; text-align:right; font-weight:800; color:#60a5fa;"
                      title="Average ranking score across the stocks shown.">
                    {{ top10AvgScore | number:'1.2-2' }}
                  </td>
                  <td></td>
                  <td style="font-size:11px; padding:8px 10px; text-align:right; font-weight:800; color:#60a5fa;"
                      title="Total money committed if you bought every stock shown.">
                    {{ top10TotalAlloc | number:'1.0-0' }}
                  </td>
                  <td style="font-size:11px; padding:8px 10px; text-align:right; font-weight:800; color:#fff;"
                      title="Total shares across the stocks shown.">
                    {{ top10TotalQty | number }}
                  </td>
                  <td></td>
                </tr>
              </tfoot>
            }
          </table>
        </div>
      </div>

      <!-- PREVIOUS TOP 10 — TRADE BATCHES -->
      <div class="card" style="overflow:hidden;">
        <div style="background:#1e2a3b; padding:10px 14px; display:flex; align-items:center; justify-content:space-between; border-bottom:1px solid #2d3f55; flex-wrap:wrap; gap:8px;">
          <div style="display:flex; align-items:center; gap:8px;">
            <span class="section-title">Previous top 10 Ranking Stocks</span>
            <span style="color:#64748b; font-size:10px;">Trade Batches</span>
          </div>
          <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
            <!-- Day, Time and Sell Day removed. This section has one rule, not a set of
                 choices: bought at 15:20 on the previous trading day, sold at 11:00 on the next
                 one. Offering pickers implied the rule was negotiable and let the screen be put
                 into states the automation would never produce. -->
            <span style="font-size:10px; color:#64748b;"
                  title="Fixed for this section: bought at 15:20 on the previous trading day, sold at 11:00 on the next trading day. Weekends and market holidays are skipped - a Friday buy sells on Monday.">
              Buy {{ PREV_BUY_TIME }} previous session &middot; Sell {{ PREV_SELL_TIME }} next session
            </span>
            <button class="btn-outline" style="font-size:10px; padding:4px 10px;" (click)="loadPeriodPreview(); loadTradeBatches()" [disabled]="tradeBatchLoading || previewLoading">
              @if (tradeBatchLoading || previewLoading) { <span class="spin"></span> } @else { Refresh }
            </button>
            <!-- Back-dated download. Empty replays the section's current view; a date replays
                 that session instead, rebuilt from stored candles at this section's own times. -->
            <input type="date" class="select-sm" [(ngModel)]="prevExportDate" [max]="todayIso"
                   style="font-size:10px;" title="Optional: download a past session instead of the current view">
            <button class="btn-outline" style="font-size:10px; padding:4px 10px;"
                    (click)="downloadPrevTop10()"
                    title="Excel/CSV of exactly these rows - the same columns, buy and sell dates, times, prices and returns">
              Download stocks
            </button>
            <button class="btn-outline" style="font-size:10px; padding:4px 10px;" (click)="togglePrevAutomationPanel()"
              [style.borderColor]="automation.prevtop10_auto_refresh_enabled ? '#22c55e' : '#334155'"
              title="Auto-refresh settings for Previous Top 10 and its average-return bars">
              Automation {{ automation.prevtop10_auto_refresh_enabled ? 'ON' : 'OFF' }}
            </button>
            <!-- Disabled only while its own request is in flight. It used to grey out whenever
                 the Top 10 was empty, which through a whole stretch of this month was every
                 evening - a permanently dead button with nothing on screen explaining why. It
                 now stays live and says what is missing when pressed. -->
            <button class="btn-green" style="padding:6px 12px; font-size:11px;" (click)="createTradeBatch()" [disabled]="tradeBatchCreating">
              @if (tradeBatchCreating) { <span class="spin"></span> Creating... } @else { Buy All 10 }
            </button>
            <button class="trade-mini sell" style="padding:6px 12px; font-size:11px;" (click)="sellAllOpenBatch()" [disabled]="!!tradeBatchSelling"
              title="Close the active Previous Top 10 batch — records sell price, P/L, and updates history">
              @if (tradeBatchSelling) { <span class="spin"></span> Selling... } @else { Sell All 10 }
            </button>
            <select class="select-sm" [(ngModel)]="tradeBatchFilter" (ngModelChange)="loadTradeBatches()" style="min-width:80px; font-size:10px;">
              <option value="ALL">All</option>
              <option value="OPEN">Open</option>
              <option value="CLOSED">Closed</option>
            </select>
          </div>
        </div>
        @if (showPrevAutomationPanel) {
          <div style="background:#0d1a2b; border-bottom:1px solid #28415f; padding:12px 14px; display:flex; flex-direction:column; gap:10px;">
            <div style="display:flex; align-items:center; gap:12px; flex-wrap:wrap;">
              <label style="font-size:11px; color:#e2e8f0; display:flex; align-items:center; gap:6px; cursor:pointer;"
                     title="Re-price Previous Top 10 and redraw the average-return bars on a timer.">
                <input type="checkbox" [(ngModel)]="automation.prevtop10_auto_refresh_enabled" (ngModelChange)="restartPrevAutoRefresh()">
                Auto-refresh Previous Top 10
              </label>
              <span style="font-size:11px; color:#94a3b8;">every
                <select class="select-sm" [(ngModel)]="automation.prevtop10_auto_refresh_interval_sec" (ngModelChange)="restartPrevAutoRefresh()"
                        [disabled]="!automation.prevtop10_auto_refresh_enabled" style="font-size:11px; margin-left:4px;">
                  @for (o of prevRefreshOptions; track o.sec) {
                    <option [ngValue]="o.sec">{{ o.label }}</option>
                  }
                </select>
              </span>
              <span style="font-size:10px; color:#64748b;">the average-return bars refresh with it</span>
            </div>
            <div style="height:1px; background:#1e2f45;"></div>
            <div style="font-size:10px; color:#64748b;">
              Buy and sell automation for this list lives in the Current Top 10 automation panel, under
              &ldquo;Previous Top 10 &mdash; Daily Auto Cycle&rdquo;.
            </div>
            <div style="display:flex; gap:8px;">
              <button class="btn-green" style="font-size:11px; padding:6px 14px;" (click)="saveAutomation()" [disabled]="automationSaving">
                @if (automationSaving) { <span class="spin"></span> Saving... } @else { Save }
              </button>
              <button class="btn-outline" style="font-size:11px; padding:6px 14px;" (click)="togglePrevAutomationPanel()">Close</button>
            </div>
          </div>
        }
        <!-- Period preview (spec §2/§3) — actual buy vs current price, no ranking average -->
        @if (periodPreview?.stocks?.length) {
          <div style="padding:8px 14px; border-bottom:1px solid #2d3f55; display:flex; gap:14px; align-items:center; flex-wrap:wrap; font-size:11px;">
            <span style="color:#94a3b8;">
              {{ periodPreview.historical
                   ? 'Buy @ ' + periodPreview.buy_date + ' ' + (periodPreview.buy_time || '')
                   : 'Buy @ current price' }}
            </span>
            @if (periodPreview.run_date) {
              <span style="color:#64748b;" [title]="periodPreview.selection_basis || 'The ranking run that was current at the selected day and time'">
                Top 10 as of <strong style="color:#94a3b8;">{{ periodPreview.run_date }}</strong>
                @if (periodPreview.run_created_at) {
                  <span style="color:#475569;">({{ periodPreview.run_created_at }})</span>
                } @else {
                  <span style="color:#475569;">(current rules)</span>
                }
              </span>
            }
            <span style="display:none;">
            </span>
            <span style="color:#64748b;">Avg Return
              <strong [style.color]="(periodPreview.avg_return || 0) >= 0 ? '#22c55e' : '#ef4444'">{{ periodPreview.avg_return | number:'1.2-2' }}%</strong>
            </span>
            <span style="color:#64748b;">Win <strong style="color:#22c55e;">{{ periodPreview.winners }}</strong> / Lose <strong style="color:#ef4444;">{{ periodPreview.losers }}</strong></span>
            @if (periodPreview.missing_prices > 0) {
              <span style="color:#f59e0b;">{{ periodPreview.missing_prices }} stock(s) have no stored price on that date — excluded from the average.</span>
            }
          </div>
        }
        <!-- Previous Top 10 AT THE SELECTED TIME (spec §2/§3).
             The Time selector above re-prices the Current Top 10 as they would have been
             bought at the chosen date, so the result belongs in THIS section — it used to
             only update the one-line summary strip above. -->
        @if (periodPreview?.stocks?.length) {
          <div class="prev-top10-preview" style="overflow-x:auto;">
            <table style="width:100%; border-collapse:collapse; min-width:640px;">
              <thead>
                <tr style="background:#16202e;">
                  <th style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; padding:7px 10px; text-align:left;" title="Ranking position on the selected day.">Rank</th>
                  <th style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; padding:7px 10px; text-align:left;" title="Stock symbol.">Symbol</th>
                  <th style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; padding:7px 10px; text-align:left;" title="Industry the stock belongs to.">Sector</th>
                  <th style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; padding:7px 10px; text-align:left;" title="The exact moment this row was bought at. With no day selected this is the last working day's close.">Buy Date &amp; Time</th>
                  <th style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; padding:7px 10px; text-align:right;" [title]="'Traded price at ' + hhmmLabel(prevBuyTime) + ' on the selected day - the price you would have bought at.'">Buy Price</th>
                  <th style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; padding:7px 10px; text-align:left;" title="The exact moment this row was sold at. With no sell day selected this is the live price, or the last working day's close when the market is shut.">Sell Date &amp; Time</th>
                  <th style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; padding:7px 10px; text-align:right;"
                      [title]="periodPreview?.sell_historical ? ('Traded price at ' + hhmmLabel(prevSellTime) + ' on ' + periodPreview?.sell_date + ' - the price you would have sold at.') : 'Live price - the sell leg is marked to market.'">Sell Price</th>
                  <th style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; padding:7px 10px; text-align:right;" title="Latest traded price now.">Current</th>
                  <th style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; padding:7px 10px; text-align:right;" title="Gain or loss between the buy price and the sell price, as a percentage.">Return %</th>
                </tr>
              </thead>
              <tbody>
                @for (s of periodPreview.stocks; track s.symbol) {
                  <tr style="border-bottom:1px solid #22304a;">
                    <td style="font-size:11px; padding:7px 10px; color:#60a5fa; font-weight:700;">{{ s.rank }}</td>
                    <td style="font-size:11px; padding:7px 10px; color:#fff; font-weight:600;">{{ s.symbol }}</td>
                    <td style="font-size:10px; padding:7px 10px; color:#94a3b8;">{{ s.sector || '-' }}</td>
                    <td style="font-size:10px; padding:7px 10px; color:#94a3b8; white-space:nowrap;">{{ stampLabel(s.buy_date, s.buy_time) }}</td>
                    <td style="font-size:11px; padding:7px 10px; text-align:right; color:#e2e8f0;">
                      @if (s.buy_price != null) { {{ s.buy_price | number:'1.2-2' }} } @else { <span style="color:#f59e0b; font-size:9px;" title="No traded price is stored for this stock at that date and time, so its return cannot be worked out.">No price</span> }
                    </td>
                    <td style="font-size:10px; padding:7px 10px; color:#94a3b8; white-space:nowrap;">{{ stampLabel(s.sell_date, s.sell_time) }}</td>
                    <td style="font-size:11px; padding:7px 10px; text-align:right; color:#e2e8f0;">
                      @if (s.sell_price != null) { {{ s.sell_price | number:'1.2-2' }} } @else { <span style="color:#f59e0b; font-size:9px;" title="No traded price is stored for this stock at the chosen sell date and time.">No price</span> }
                    </td>
                    <td style="font-size:11px; padding:7px 10px; text-align:right; color:#e2e8f0;">{{ s.current_price | number:'1.2-2' }}</td>
                    <td style="font-size:11px; padding:7px 10px; text-align:right; font-weight:700;"
                        [style.color]="s.return_pct == null ? '#64748b' : (s.return_pct >= 0 ? '#22c55e' : '#ef4444')">
                      @if (s.return_pct != null) { {{ s.return_pct | number:'1.2-2' }}% } @else { - }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }

        <!-- RANKING CHART BAR - one bar per trading day, each the average of that day's ten.
             Every day is rebuilt by the same reconstruction the table above uses - same entry
             test, same buy and sell times - and the window ENDS on the day the table is showing,
             so the last bar is that table reduced to a single number. -->
        @if (periodPreview?.stocks?.length) {
          <div style="border-top:1px solid #2d3f55;">
            <div style="background:#16202e; padding:8px 14px; display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:8px;">
              <div style="display:flex; align-items:center; gap:8px;">
                <span style="font-size:12px; font-weight:800; color:#60a5fa;">Ranking Chart Bar</span>
                <span style="color:#64748b; font-size:9.5px;">One bar per day &mdash; average return of that day&rsquo;s 10 stocks</span>
              </div>
              <div style="display:flex; align-items:center; gap:12px; font-size:10px; flex-wrap:wrap;">
                <span style="color:#64748b;">Days</span>
                <select class="select-sm" [(ngModel)]="rankingTrendDays" (ngModelChange)="loadRankingChartBar()"
                        style="min-width:66px; font-size:10px;" title="How far back the chart runs. It always ends on the day the table above is showing.">
                  @for (n of rankingTrendDayOptions; track n) { <option [ngValue]="n">{{ n }}D</option> }
                </select>
                @if (rankingTrendLoading) {
                  <span style="color:#64748b;">Loading&hellip;</span>
                } @else if (rankingTrend.length) {
                  <span style="color:#64748b;">Avg
                    <strong [style.color]="rankingTrendAvg >= 0 ? '#22c55e' : '#ef4444'">{{ rankingTrendAvg | number:'1.2-2' }}%</strong>
                  </span>
                  <span style="color:#22c55e;">Up {{ rankingChartUp }}</span>
                  <span style="color:#ef4444;">Down {{ rankingChartDown }}</span>
                  @if (rankingChartUnsettled) {
                    <span style="color:#f59e0b;" title="Days with no next-day exit price stored yet, or where nothing met the entry test. They keep their slot but draw no bar.">Unsettled {{ rankingChartUnsettled }}</span>
                  }
                }
              </div>
            </div>

            <!-- Bars grow from a shared zero line so a losing day hangs below it, the way the
                 numbers read. An unsettled day keeps its slot and its date but draws no bar: a
                 gap says "not known yet", where a zero-height bar would say "flat" - a different
                 claim, and an untrue one. -->
            <div style="display:flex; align-items:center; gap:5px; height:210px; overflow-x:auto; padding:10px 14px 4px;">
              @for (d of rankingTrend; track d.date) {
                <div style="display:flex; flex-direction:column; align-items:center; min-width:40px; height:100%; justify-content:center;"
                     [title]="rankingBarTooltip(d)">
                  <div style="display:flex; flex-direction:column; align-items:center; justify-content:flex-end; flex:1; width:100%;">
                    @if (d.trade_avg_return != null && d.trade_avg_return >= 0) {
                      <div style="font-size:8px; font-weight:700; color:#22c55e; margin-bottom:2px;">{{ d.trade_avg_return | number:'1.1-1' }}</div>
                      <div class="bar-chart-bar" style="background:#1d6adb;"
                           [style.height]="barH(d.trade_avg_return, rankingChartMax)+'px'"></div>
                    }
                  </div>
                  <div style="height:1px; width:100%; background:#1e2a3b;"></div>
                  <div style="display:flex; flex-direction:column; align-items:center; justify-content:flex-start; flex:1; width:100%;">
                    @if (d.trade_avg_return != null && d.trade_avg_return < 0) {
                      <div class="bar-chart-bar" style="background:#dc2626; border-radius:0 0 3px 3px;"
                           [style.height]="barH(d.trade_avg_return, rankingChartMax)+'px'"></div>
                      <div style="font-size:8px; font-weight:700; color:#ef4444; margin-top:2px;">{{ d.trade_avg_return | number:'1.1-1' }}</div>
                    } @else if (d.trade_avg_return == null) {
                      <div style="font-size:8px; color:#f59e0b; margin-top:2px;">&mdash;</div>
                    }
                  </div>
                  <div style="font-size:8.5px; white-space:nowrap; text-align:center; letter-spacing:-.1px; margin-top:4px;"
                       [style.font-weight]="d.date === rankingTrendEndDate ? '700' : '400'"
                       [style.color]="d.date === rankingTrendEndDate ? '#e2e8f0' : '#94a3b8'">
                    {{ formatChartDate(d.date) }}
                  </div>
                </div>
              }
              @if (!rankingTrend.length && !rankingTrendLoading) {
                <div style="color:#94a3b8; font-size:11px; padding:16px 4px; line-height:1.6;">
                  No settled trading day in this window yet. Every bar needs a next trading day to
                  sell into, so a day only charts once that following session has prices stored.
                </div>
              }
            </div>
            <div style="font-size:9.5px; color:#64748b; padding:0 14px 10px;">
              {{ rankingTrendRule }}
              @if (rankingTrendEndDate) {
                <span style="margin-left:6px;">Last bar ({{ formatChartDate(rankingTrendEndDate) }}) is the table above, averaged.</span>
                @if (rankingTrendStale) {
                  <span style="margin-left:6px; color:#f59e0b;"
                        title="The chart ends on the newest session that has stored data. Later sessions will appear once market data for them is fetched.">
                    No market data after {{ formatChartDate(rankingTrendEndDate) }}.
                  </span>
                }
              }
            </div>
          </div>
        }

        <!-- Summary Cards -->
        @if (tradeBatchSummary?.total_batches > 0) {
          <div style="display:flex; gap:8px; padding:10px 14px; flex-wrap:wrap; border-bottom:1px solid #2d3f55;">
            <div style="background:#0f172a; border-radius:6px; padding:6px 12px; text-align:center; min-width:80px;">
              <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; margin-bottom:2px;">Total Batches</div>
              <div style="font-size:16px; font-weight:800; color:#60a5fa;">{{ tradeBatchSummary.total_batches }}</div>
            </div>
            <div style="background:#0f172a; border-radius:6px; padding:6px 12px; text-align:center; min-width:80px;">
              <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; margin-bottom:2px;">Winning</div>
              <div style="font-size:16px; font-weight:800; color:#22c55e;">{{ tradeBatchSummary.winning_batches }}</div>
            </div>
            <div style="background:#0f172a; border-radius:6px; padding:6px 12px; text-align:center; min-width:80px;">
              <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; margin-bottom:2px;">Losing</div>
              <div style="font-size:16px; font-weight:800; color:#ef4444;">{{ tradeBatchSummary.losing_batches }}</div>
            </div>
            <div style="background:#0f172a; border-radius:6px; padding:6px 12px; text-align:center; min-width:80px;">
              <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; margin-bottom:2px;">Avg Return</div>
              <div style="font-size:16px; font-weight:800;" [style.color]="tradeBatchSummary.avg_return >= 0 ? '#22c55e' : '#ef4444'">{{ tradeBatchSummary.avg_return | number:'1.2-2' }}%</div>
            </div>
            <div style="background:#0f172a; border-radius:6px; padding:6px 12px; text-align:center; min-width:80px;">
              <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; margin-bottom:2px;">Best</div>
              <div style="font-size:16px; font-weight:800; color:#22c55e;">{{ tradeBatchSummary.highest_return | number:'1.2-2' }}%</div>
            </div>
            <div style="background:#0f172a; border-radius:6px; padding:6px 12px; text-align:center; min-width:80px;">
              <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; margin-bottom:2px;">Worst</div>
              <div style="font-size:16px; font-weight:800; color:#ef4444;">{{ tradeBatchSummary.lowest_return | number:'1.2-2' }}%</div>
            </div>
          </div>
        }
        <!-- Batch List -->
        <div style="overflow-x:auto;">
          @for (b of tradeBatches; track b.batch_id) {
            <div style="border-bottom:1px solid #2d3f55;">
              <div style="display:flex; align-items:center; gap:10px; padding:8px 14px; cursor:pointer;"
                [style.background]="expandedBatchId === b.batch_id ? '#162032' : 'transparent'"
                (click)="expandedBatchId = expandedBatchId === b.batch_id ? '' : b.batch_id">
                <span style="font-size:10px; color:#64748b; min-width:20px;">{{ expandedBatchId === b.batch_id ? '▼' : '▶' }}</span>
                <span style="font-size:11px; font-weight:700; color:#60a5fa; min-width:180px;">{{ b.batch_id }}</span>
                <span style="font-size:10px; color:#94a3b8; min-width:90px;">{{ formatDateDMY(b.trade_date) }}</span>
                <span style="font-size:10px; color:#94a3b8; min-width:70px;">Buy: {{ b.buy_time }}</span>
                <span style="font-size:10px; color:#94a3b8; min-width:70px;">Sell: {{ b.sell_time || '-' }}</span>
                <span style="font-size:10px; padding:2px 8px; border-radius:4px; font-weight:700; min-width:55px; text-align:center;"
                  [style.background]="b.status === 'OPEN' ? '#1d4ed820' : '#16a34a20'"
                  [style.color]="b.status === 'OPEN' ? '#60a5fa' : '#22c55e'">{{ b.status }}</span>
                <span style="font-size:12px; font-weight:700; min-width:70px; text-align:right;"
                  [style.color]="b.avg_return_pct >= 0 ? '#22c55e' : '#ef4444'"
                  [title]="b.status === 'OPEN' ? 'Live unrealized average (current price vs buy price)' : 'Realized average at sell'">
                  {{ b.avg_return_pct | number:'1.2-2' }}%@if (b.status === 'OPEN') { <span style="color:#60a5fa; font-size:9px;">&nbsp;live</span> }
                </span>
                <span style="font-size:10px; color:#94a3b8; min-width:40px;">{{ b.trade_type }}</span>
                <div style="margin-left:auto; display:flex; gap:6px;">
                  @if (b.status === 'OPEN') {
                    <button class="trade-mini sell" style="padding:3px 10px; font-size:10px;" (click)="sellTradeBatch(b.batch_id); $event.stopPropagation()" [disabled]="tradeBatchSelling === b.batch_id">
                      @if (tradeBatchSelling === b.batch_id) { <span class="spin"></span> } @else { Sell All }
                    </button>
                  }
                  <button class="btn-outline" style="padding:3px 8px; font-size:9px; color:#ef4444; border-color:#ef444440;" (click)="deleteTradeBatch(b.batch_id); $event.stopPropagation()">Del</button>
                </div>
              </div>
              @if (expandedBatchId === b.batch_id) {
                <div style="padding:0 14px 10px 44px;">
                  <table style="width:100%; border-collapse:collapse; min-width:500px;">
                    <thead>
                      <tr>
                        <th class="tbl-hdr" style="width:36px; text-align:center;">Rank</th>
                        <th class="tbl-hdr">Symbol</th>
                        <th class="tbl-hdr">Sector</th>
                        <th class="tbl-hdr" style="text-align:right;">Buy Price</th>
                        <th class="tbl-hdr" style="text-align:right;">Sell Price</th>
                        <th class="tbl-hdr" style="text-align:right;">Return %</th>
                        <th class="tbl-hdr" style="width:60px; text-align:center;">Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (s of b.stocks || []; track s.symbol) {
                        <tr class="tbl-row">
                          <td style="font-size:11px; padding:5px 10px; text-align:center; color:#64748b;">{{ s.rank }}</td>
                          <td style="font-size:11px; padding:5px 10px; font-weight:700; color:#e2e8f0;">{{ s.symbol }}</td>
                          <td class="tbl-cell" style="font-size:11px;">{{ s.sector }}</td>
                          <td class="tbl-cell-num" style="font-size:11px;">{{ s.buy_price | number:'1.2-2' }}</td>
                          <td class="tbl-cell-num" style="font-size:11px;">
                            @if (s.sell_price != null) { {{ s.sell_price | number:'1.2-2' }} }
                            @else if (s.live && s.current_price != null) { <span [title]="'Current price (held)'">{{ s.current_price | number:'1.2-2' }}</span> }
                            @else { - }
                          </td>
                          <td style="font-size:11px; padding:5px 10px; text-align:right; font-weight:600;"
                            [style.color]="s.return_pct >= 0 ? '#22c55e' : '#ef4444'">
                            @if (s.status === 'CLOSED') { {{ s.return_pct | number:'1.2-2' }}% }
                            @else if (s.live && s.current_price != null) { {{ s.return_pct | number:'1.2-2' }}% }
                            @else { - }
                          </td>
                          <td style="font-size:10px; padding:5px 10px; text-align:center;"
                            [style.color]="s.status === 'OPEN' ? '#60a5fa' : '#22c55e'">{{ s.status }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </div>
          }
          @if (!tradeBatches.length) {
            <div style="text-align:center; padding:24px; color:#64748b; font-size:12px;">
              No trade batches yet. Click "Buy All 10" to create your first trade batch from Current Top 10.
            </div>
          }
        </div>
      </div>

    </div>

    <!-- â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• RIGHT COL â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• -->
    <div style="display:grid; grid-template-columns:minmax(0, 1.6fr) minmax(360px, .9fr); gap:12px; align-items:start;">



      <!-- Chart 2: Trade Batch Avg Return Trend -->
      <div class="card" style="padding:12px; grid-column:1 / -1;">
        <div style="display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:8px;">
          <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
            <div style="flex:1 1 400px;">
              <span style="font-size:13px; font-weight:800; color:#60a5fa;">Previous Top 10 Avg Return Trend (%)</span>
              <div style="font-size:10px; color:#94a3b8; margin-top:3px;">
                Entry test: Current Price &ge; Base Price <strong>and</strong> Current Volume &gt; 20-Day
                Average Volume. Then Rank, then Buy/Sell &mdash; in that order.
                <span style="color:#64748b;">(No crossing requirement &mdash; that is the panel below.)</span>
              </div>
            </div>

            <!-- Same two headline figures as the crossing panel, in the same place, so the two
                 sections can be compared without re-reading the layout. -->
            @if (mainTop?.top10?.length) {
              <div style="display:flex; gap:10px; align-items:stretch; flex-wrap:wrap;">
                <div style="background:#0f172a; border-radius:6px; padding:6px 14px; min-width:170px;
                            display:flex; flex-direction:column; justify-content:space-between;">
                  <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px;">Avg Return %</div>
                  <div style="font-size:17px; font-weight:800; line-height:1.2;"
                       [class]="(mainTop.avg_return_current || 0) >= 0 ? 'pos' : 'neg'">
                    {{ mainTop.avg_return_current | number:'1.2-2' }}%
                  </div>
                  <div style="font-size:9px; color:#64748b;">
                    {{ mainTop.count }} stock(s) &middot;
                    <span style="color:#cbd5e1;">frozen {{ mainTop.frozen_date }} {{ mainTop.frozen_time }}</span>
                    @if (mainTop.live && mainTop.live_priced) {
                      <span style="color:#22c55e; font-weight:700;">&middot; LIVE</span>
                    }
                  </div>
                </div>
                <div style="background:#0f172a; border-radius:6px; padding:6px 14px; min-width:200px;
                            display:flex; flex-direction:column; justify-content:space-between;">
                  <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px;">Trend %</div>
                  @if (mainTop.trend_direction === 'NEW') {
                    <div style="font-size:17px; font-weight:800; color:#94a3b8; line-height:1.2;">&mdash;</div>
                    <div style="font-size:9px; color:#64748b;">No previous run to compare</div>
                  } @else {
                    <div style="font-size:17px; font-weight:800; line-height:1.2;"
                         [style.color]="mainTop.trend_direction === 'UP' ? '#22c55e' : mainTop.trend_direction === 'DOWN' ? '#ef4444' : '#94a3b8'">
                      {{ mainTop.trend_direction === 'UP' ? '&#8593;' : mainTop.trend_direction === 'DOWN' ? '&#8595;' : '&#8594;' }}
                      {{ mainTop.trend_pct > 0 ? '+' : '' }}{{ mainTop.trend_pct | number:'1.2-2' }}%
                      <span style="font-size:11px; font-weight:600;">
                        {{ mainTop.trend_direction === 'UP' ? 'Improving' : mainTop.trend_direction === 'DOWN' ? 'Declining' : 'Unchanged' }}
                      </span>
                    </div>
                    <div style="font-size:9px; color:#64748b;">
                      vs {{ mainTop.avg_return_previous | number:'1.2-2' }}% frozen {{ mainTop.previous_day }}
                    </div>
                  }
                </div>
              </div>
            }
          </div>

          <!-- Controls on their own full-width row.
               They used to sit in the header flex beside the title and the two stat tiles, so a
               dozen inputs and their loose text labels wrapped wherever they happened to land -
               "Time 1 Month Buy 15:15 Sell 10:00 Days 30 From ... To ..." running together with
               nothing lining up. Each control is now a label-over-field pair on a grid, so the
               labels sit above their own inputs and the fields align down the row. -->
          <div class="trend-controls">
            @if (trendMode === 'closed') {
              <label class="tc-field tc-action">
                <span class="tc-label">&nbsp;</span>
                <button class="btn-outline tc-btn" (click)="loadTradeBatchTrend()" [disabled]="trendRefreshing">
                  @if (trendRefreshing) { <span class="spin"></span> } @else { Refresh }
                </button>
              </label>
            }

            <!-- Window for this panel only: the Previous Top 10 selection above does not change it. -->
            <label class="tc-field">
              <span class="tc-label">Time</span>
              <select class="select-sm" [(ngModel)]="trendPeriod" (ngModelChange)="onTrendPeriodChange()"
                      title="Trend window for this panel only - the Previous Top 10 selection above does not change it">
                @for (opt of prevPeriodOptions; track opt.value) {
                  <option [value]="opt.value">{{ opt.label }}</option>
                }
              </select>
            </label>
            @if (trendPeriod === 'CUSTOM') {
              <label class="tc-field">
                <span class="tc-label">Custom date</span>
                <input type="date" class="select-sm" [(ngModel)]="trendCustomDate"
                       (ngModelChange)="onTrendPeriodChange()" [max]="todayIso"
                       title="Trend from this date to today">
              </label>
            }

            <label class="tc-field">
              <span class="tc-label">Buy</span>
              <input type="text" class="select-sm" [(ngModel)]="trendBuyTime" placeholder="15:15"
                     title="Buy time (HH:mm) - the day's final ranking run">
            </label>
            <label class="tc-field">
              <span class="tc-label">Sell</span>
              <input type="text" class="select-sm" [(ngModel)]="trendSellTime" placeholder="10:00"
                     title="Sell time on the next trading day (HH:mm)">
            </label>
            <label class="tc-field">
              <span class="tc-label">Days</span>
              <input type="number" class="select-sm" [(ngModel)]="trendDays" min="1" max="90"
                     title="Number of days to back-test">
            </label>

            <label class="tc-field tc-divider">
              <span class="tc-label">From</span>
              <input type="date" class="select-sm" [(ngModel)]="rangeFrom" [max]="todayIso"
                     title="Replay the Current Top 10 from this date. Uses stored data, so it works outside market hours.">
            </label>
            <label class="tc-field">
              <span class="tc-label">To</span>
              <input type="date" class="select-sm" [(ngModel)]="rangeTo" [max]="todayIso"
                     title="...through to this date. Each trading day in between becomes one bar.">
            </label>

            <label class="tc-field tc-action">
              <span class="tc-label">&nbsp;</span>
              <button class="btn-outline tc-btn" (click)="downloadChartTrades(null)"
                      title="Excel/CSV of every stock behind these bars - buy and sell date, time, price and return">
                Download stocks
              </button>
            </label>
            <!-- One button instead of Run Range and Backtest. Both rebuilt each day's Top 10 and
                 measured it, differing only in where the window came from - which the controls
                 beside them already say. It reads From/To when filled, the Time selection when not. -->
            <label class="tc-field tc-action">
              <span class="tc-label">&nbsp;</span>
              <button class="btn-green tc-btn" (click)="runAndRefreshTop10()" [disabled]="trendRefreshing"
                      [title]="rangeFrom && rangeTo
                        ? ('Rebuild the Top 10 for every trading day from ' + rangeFrom + ' to ' + rangeTo + ', bought and sold at the times above.')
                        : 'Rebuild the Top 10 for each of the last ' + trendDays + ' days, bought and sold at the times above.'">
                @if (trendRefreshing) { <span class="spin"></span> Running... } @else { Run &amp; Refresh top 10 }
              </button>
            </label>
          </div>

          @if (trendMode === 'backtest') {
            <div class="trend-caption">
              Buy the Top 10 at {{ hhmmLabel(trendBuyTime) }}, sell the next trading day at
              {{ hhmmLabel(trendSellTime) }} &middot; real stored prices
            </div>
          }
          <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
            <div style="background:#0f172a; border-radius:6px; padding:6px 10px; display:flex; align-items:center; gap:6px;"
                 title="Average of the daily average returns across the window.">
              <span style="font-size:10px; color:#64748b; text-transform:uppercase;">Avg / Day</span>
              <span style="font-size:14px; font-weight:800;"
                [style.color]="batchTrendAvg >= 0 ? '#22c55e' : '#ef4444'">
                {{ batchTrendAvg | number:'1.2-2' }}%
              </span>
            </div>
            @if (trendMode === 'backtest' && tradeBatchTrend.length) {
              <div style="background:#0f172a; border-radius:6px; padding:6px 10px; display:flex; align-items:center; gap:6px;"
                   title="What the strategy would have returned over the whole window, compounding each day onto the last.">
                <span style="font-size:10px; color:#64748b; text-transform:uppercase;">Total</span>
                <span style="font-size:14px; font-weight:800;"
                  [style.color]="batchTrendCumulative >= 0 ? '#22c55e' : '#ef4444'">
                  {{ batchTrendCumulative | number:'1.2-2' }}%
                </span>
              </div>
              <div style="background:#0f172a; border-radius:6px; padding:6px 10px; display:flex; align-items:center; gap:6px;"
                   [title]="batchTrendWinDays + ' profitable days, ' + batchTrendLossDays + ' losing days.'">
                <span style="font-size:10px; color:#64748b; text-transform:uppercase;">Win Rate</span>
                <span style="font-size:14px; font-weight:800; color:#e2e8f0;">{{ batchTrendWinRate | number:'1.0-1' }}%</span>
                <span style="font-size:9px; color:#64748b;">({{ batchTrendWinDays }}W / {{ batchTrendLossDays }}L)</span>
              </div>
              @if (batchTrendBest) {
                <div style="background:#0f172a; border-radius:6px; padding:6px 10px; display:flex; align-items:center; gap:6px;"
                     [title]="'Best day: ' + batchTrendBest.trade_date">
                  <span style="font-size:10px; color:#64748b; text-transform:uppercase;">Best</span>
                  <span style="font-size:13px; font-weight:800; color:#22c55e;">{{ batchTrendBest.avg_return_pct | number:'1.2-2' }}%</span>
                  <span style="font-size:9px; color:#64748b;">{{ formatChartDate(batchTrendBest.trade_date) }}</span>
                </div>
              }
              @if (batchTrendWorst) {
                <div style="background:#0f172a; border-radius:6px; padding:6px 10px; display:flex; align-items:center; gap:6px;"
                     [title]="'Worst day: ' + batchTrendWorst.trade_date">
                  <span style="font-size:10px; color:#64748b; text-transform:uppercase;">Worst</span>
                  <span style="font-size:13px; font-weight:800; color:#ef4444;">{{ batchTrendWorst.avg_return_pct | number:'1.2-2' }}%</span>
                  <span style="font-size:9px; color:#64748b;">{{ formatChartDate(batchTrendWorst.trade_date) }}</span>
                </div>
              }
            }
            <div style="background:#0f172a; border-radius:6px; padding:6px 10px; display:flex; align-items:center; gap:6px;">
              <span style="font-size:10px; color:#64748b; text-transform:uppercase;">{{ trendMode === 'backtest' ? 'Days' : 'Batches' }}</span>
              <span style="font-size:14px; font-weight:800; color:#94a3b8;">{{ tradeBatchTrend.length }}</span>
            </div>
          </div>
        </div>
        <!-- Y axis label -->
        <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:4px;">
          <span style="font-size:9px; color:#64748b;">% return</span>
          @if (trendMode === 'backtest' && batchTrendIncompleteDays) {
            <span style="font-size:9px; color:#f59e0b;"
                  title="Those days are averaged over the stocks that did have prices, so they rest on a smaller sample.">
              {{ batchTrendIncompleteDays }} of {{ tradeBatchTrend.length }} days had fewer than 10 stocks priced
            </span>
          }
        </div>
        <!-- Identify -> Rank -> Buy/Sell for the two-condition test. -->
        @if (mainTop?.top10?.length) {
          <div style="overflow-x:auto; margin-bottom:10px;">
            <table style="width:100%; border-collapse:collapse; min-width:900px;">
              <thead>
                <tr>
                  <th class="sb-hdr" style="text-align:center;">Rank</th>
                  <th class="sb-hdr">Symbol</th>
                  <th class="sb-hdr">Sector</th>
                  <th class="sb-hdr" style="text-align:right;">Prev Close</th>
                  <th class="sb-hdr" style="text-align:right;">Base Price</th>
                  <!-- Bound, not hardcoded: this read 15:20 while the section's cut-off had
                       moved, so the header contradicted the price beneath it. -->
                  <th class="sb-hdr" style="text-align:right;" title="Traded price at the cut-off on the day this list was frozen - the price the position was opened at">Entry &#64; {{ mainTop?.top10?.[0]?.entry_time || '15:15' }}</th>
                  <th class="sb-hdr" style="text-align:right;" title="Must be at or above the base price">Current</th>
                  <th class="sb-hdr" style="text-align:right;" title="Move since the entry price above, not since the base price">Return %</th>
                  <th class="sb-hdr" style="text-align:right;" title="Had to beat the 20-day average to qualify">Volume</th>
                  <th class="sb-hdr" style="text-align:right;">20D Avg Vol</th>
                  <th class="sb-hdr" style="text-align:right;">Score</th>
                  <th class="sb-hdr" style="text-align:right;">Weight Used</th>
                  <th class="sb-hdr" style="text-align:center;">Signal</th>
                  <th class="sb-hdr">Trend</th>
                </tr>
              </thead>
              <tbody>
                @for (r of mainTop.top10; track r.symbol) {
                  <tr>
                    <td class="sb-cell" style="text-align:center; font-weight:700;"
                        [style.color]="r.rank === 1 ? '#facc15' : r.rank <= 3 ? '#94a3b8' : '#64748b'">{{ r.rank }}</td>
                    <td class="sb-cell" style="color:#fff; font-weight:700;">{{ r.symbol }}</td>
                    <td class="sb-cell" style="color:#cbd5e1;">{{ r.sector || '-' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ r.previous_close | number:'1.2-2' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ r.base_price | number:'1.2-2' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ r.entry_price | number:'1.2-2' }}</td>
                    <td class="sb-cell" style="text-align:right; font-weight:600;"
                        [style.color]="r.is_live ? '#22c55e' : '#fff'">{{ r.current_price | number:'1.2-2' }}</td>
                    <td class="sb-cell" style="text-align:right;" [class]="(r.return_pct || 0) >= 0 ? 'pos' : 'neg'">{{ r.return_pct | number:'1.2-2' }}%</td>
                    <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ r.volume | number:'1.0-0' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#64748b;">{{ r.avg_20d_volume | number:'1.0-0' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#60a5fa; font-weight:800;">{{ r.score | number:'1.2-2' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#e2e8f0;">{{ r.weight_used | number:'1.0-0' }}</td>
                    <td class="sb-cell" style="text-align:center;">
                      <span style="font-weight:800; padding:2px 8px; border-radius:4px;"
                            [style.color]="r.signal === 'BUY' ? '#22c55e' : r.signal === 'SELL' ? '#ef4444' : '#94a3b8'"
                            [style.background]="r.signal === 'BUY' ? '#0d2818' : r.signal === 'SELL' ? '#2a0f0f' : '#16202e'"
                            [title]="r.signal_message || ''">{{ r.signal }}</span>
                    </td>
                    <td class="sb-cell"
                        [style.color]="r.trend === 'Bullish' ? '#22c55e' : r.trend === 'Bearish' ? '#ef4444' : '#94a3b8'">{{ r.trend || '-' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>

          @if (mainProvisional) {
            <div style="border:1px dashed #3a4a5f; border-radius:6px; padding:9px 12px; margin-bottom:10px;">
              <div style="display:flex; align-items:baseline; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:6px;">
                <span style="font-size:11px; font-weight:800; color:#facc15;">Heading into tomorrow &mdash; provisional</span>
                <span style="font-size:9.5px; color:#94a3b8;">
                  {{ mainProvisional.note }}
                  @if (mainProvisional.pool_size) {
                    <span style="color:#64748b;">&middot; {{ mainProvisional.pool_size }} passed the entry test</span>
                  }
                </span>
              </div>
              @if (mainProvisional.stocks?.length) {
                <div style="overflow-x:auto;">
                  <table style="width:100%; border-collapse:collapse; min-width:720px;">
                    <thead>
                      <tr>
                        <th class="sb-hdr" style="text-align:center;">Rank</th>
                        <th class="sb-hdr">Symbol</th>
                        <th class="sb-hdr">Sector</th>
                        <th class="sb-hdr" style="text-align:right;">Prev Close</th>
                        <th class="sb-hdr" style="text-align:right;">Base Price</th>
                        <th class="sb-hdr" style="text-align:right;">Current</th>
                        <th class="sb-hdr" style="text-align:right;">Return %</th>
                        <th class="sb-hdr" style="text-align:right;">Score</th>
                        <th class="sb-hdr" style="text-align:right;">Weight Used</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (p of mainProvisional.stocks; track p.symbol) {
                        <tr>
                          <td class="sb-cell" style="text-align:center; font-weight:700; color:#facc15;">{{ p.rank }}</td>
                          <td class="sb-cell" style="color:#fff; font-weight:700;">{{ p.symbol }}</td>
                          <td class="sb-cell" style="color:#cbd5e1;">{{ p.sector || '-' }}</td>
                          <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ p.previous_close | number:'1.2-2' }}</td>
                          <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ p.base_price | number:'1.2-2' }}</td>
                          <td class="sb-cell" style="text-align:right; color:#fff; font-weight:600;">{{ p.current_price | number:'1.2-2' }}</td>
                          <td class="sb-cell" style="text-align:right;" [class]="(p.return_pct || 0) >= 0 ? 'pos' : 'neg'">{{ p.return_pct | number:'1.2-2' }}%</td>
                          <td class="sb-cell" style="text-align:right; color:#60a5fa; font-weight:800;">{{ p.score | number:'1.2-2' }}</td>
                          <td class="sb-cell" style="text-align:right; color:#e2e8f0;">{{ p.weight_used | number:'1.0-0' }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              } @else {
                <div style="font-size:10.5px; color:#94a3b8;">{{ mainProvisional.message || 'Nothing meets the entry test right now.' }}</div>
              }
            </div>
          }

          <div style="font-size:9.5px; color:#64748b; margin-bottom:10px;">
            {{ mainTop.pipeline }}
            @if (mainTop.price_basis) { <span style="margin-left:8px;">&middot; {{ mainTop.price_basis }}</span> }
          </div>
        }

        <!-- Bar chart -->
        <div style="display:flex; align-items:flex-end; gap:4px; height:220px; overflow-x:auto; padding-bottom:26px; position:relative;">
          <div style="position:absolute; bottom:24px; left:0; right:0; height:1px; background:#1e2a3b;"></div>
          @for (d of tradeBatchTrend; track d.batch_id) {
            <div style="display:flex; flex-direction:column; align-items:center; gap:2px; min-width:38px; cursor:pointer;"
              [title]="batchBarTooltip(d)">
              <div style="font-size:8px; font-weight:700;"
                [style.color]="d.avg_return_pct >= 0 ? '#22c55e' : '#ef4444'">
                {{ d.avg_return_pct | number:'1.1-1' }}
              </div>
              <div class="bar-chart-bar"
                [style.height]="barH(d.avg_return_pct, batchTrendMax)+'px'"
                [style.background]="d.avg_return_pct >= 0 ? '#1d6adb' : '#dc2626'">
              </div>
              <div style="font-size:8.5px; color:#94a3b8; white-space:nowrap; margin-top:7px; width:100%; text-align:center; letter-spacing:-.1px;">
                {{ formatChartDate(d.trade_date) }}
              </div>
            </div>
          }
          @if (trendMode === 'range' && rangeResult) {
            <div style="width:100%; display:flex; gap:14px; flex-wrap:wrap; align-items:center; padding:6px 10px; margin-bottom:6px; background:#0f172a; border-radius:6px; font-size:11px;">
              <span style="color:#94a3b8;">{{ rangeResult.from_date }} &rarr; {{ rangeResult.to_date }}</span>
              <span style="color:#94a3b8;">Avg
                <strong [style.color]="rangeResult.avg_return >= 0 ? '#22c55e' : '#ef4444'">{{ rangeResult.avg_return | number:'1.2-2' }}%</strong>
              </span>
              <span style="color:#94a3b8;">Days <strong style="color:#e2e8f0;">{{ rangeResult.days_with_data }}</strong></span>
              <span style="color:#22c55e;">Up {{ rangeResult.winning_days }}</span>
              <span style="color:#ef4444;">Down {{ rangeResult.losing_days }}</span>
              @if (rangeResult.days_skipped) {
                <span style="color:#f59e0b;" title="Trading days that could not be settled - no next-day close stored yet, or no stock met the entry rules.">Unsettled {{ rangeResult.days_skipped }}</span>
              }
            </div>
          }
          @if (!tradeBatchTrend.length) {
            @if (trendMode === 'range') {
              <div style="color:#94a3b8; font-size:11px; padding:18px 20px; width:100%; line-height:1.65;">
                <div style="font-weight:700; color:#e2e8f0; margin-bottom:6px;">No settled trading day in that range.</div>
                <div>Each day is rebuilt from stored daily prices, so the range needs trading days the
                candles actually cover, each followed by another trading day to sell into. Try a wider
                range, or fetch that period from Data Warehouse.</div>
              </div>
            } @else if (trendMode === 'backtest') {
              <div style="color:#94a3b8; font-size:11px; padding:18px 20px; width:100%; line-height:1.65;">
                <div style="font-weight:700; color:#e2e8f0; margin-bottom:6px;">
                  Nothing to show yet for the last {{ trendDays }} days.
                </div>
                <div>
                  This chart replays the strategy day by day. Each day&rsquo;s Top 10 is rebuilt from the
                  prices stored up to that date &mdash; the same ranking formula, never allowed to see a
                  price that had not happened yet &mdash; then measured against the next trading day&rsquo;s
                  close. No saved ranking is needed, so dates from before ranking was switched on
                  still chart.
                </div>
                <div style="margin-top:6px;">
                  An empty chart here means the daily candles do not cover this window. Widen the range,
                  or fetch the period from <strong style="color:#cbd5e1;">Data Warehouse</strong>.
                </div>
              </div>
            } @else {
              <div style="color:#64748b; font-size:11px; padding:20px; text-align:center; width:100%;">
                No completed trade batches yet. Create and close trade batches to see the return trend.
              </div>
            }
          }
        </div>
      </div>

      <!-- Chart 3: same strategy, restricted to low-priced stocks -->
      <div class="card" style="padding:12px; grid-column:1 / -1;">
        <div style="display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:8px;">
          <div style="flex:1 1 420px;">
            <span style="font-size:13px; font-weight:800; color:#60a5fa;">
              RSI 45 Previous Close Less the Base Price
            </span>
            <div style="font-size:10px; color:#94a3b8; margin-top:3px;">
              Entry test: Previous Close &lt; Base Price <strong>and</strong> Current Price &ge; Base Price
              (the crossing). <strong>No ranking</strong> &mdash; every stock that qualifies is listed,
              strongest move first. Volume and ATR are shown on each row; neither excludes a stock.
            </div>
          </div>

          <!-- The two headline figures sit on the title line, where they are read first, rather
               than in a band below the controls. Both are baseline-aligned and use the same
               label / value / caption shape so the eye can compare them at a glance. -->
          @if (lowTop?.top10?.length) {
            <div style="display:flex; gap:10px; align-items:stretch; flex-wrap:wrap;">
              <div style="background:#0f172a; border-radius:6px; padding:6px 14px; min-width:170px;
                          display:flex; flex-direction:column; justify-content:space-between;">
                <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px;">Avg Return %</div>
                <div style="font-size:17px; font-weight:800; line-height:1.2;"
                     [class]="(lowTop.avg_return_current || 0) >= 0 ? 'pos' : 'neg'">
                  {{ lowTop.avg_return_current | number:'1.2-2' }}%
                </div>
                <div style="font-size:9px; color:#64748b;">
                  {{ lowTop.count }} stock(s) &middot;
                  <span style="color:#cbd5e1;" [title]="'Frozen at the ' + lowTop.frozen_time + ' cut-off on ' + lowTop.frozen_date + '. The list does not change during the current session - only its prices move.'">
                    frozen {{ lowTop.frozen_date }} {{ lowTop.frozen_time }}
                  </span>
                  @if (lowTop.live && lowTop.live_priced) {
                    <span style="color:#22c55e; font-weight:700;">&middot; LIVE</span>
                  }
                </div>
              </div>

              <div style="background:#0f172a; border-radius:6px; padding:6px 14px; min-width:180px;
                          display:flex; flex-direction:column; justify-content:space-between;">
                <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px;"
                     title="Average move of these stocks from the first run of the day to now. Outside market hours 'now' is the last completed close, so the figure settles rather than drifting.">Today Avg Return</div>
                @if (lowTop.avg_return_from_first_run == null) {
                  <div style="font-size:17px; font-weight:800; color:#94a3b8; line-height:1.2;">&mdash;</div>
                  <div style="font-size:9px; color:#64748b;">No first-run price stored for today yet</div>
                } @else {
                  <div style="font-size:17px; font-weight:800; line-height:1.2;"
                       [class]="lowTop.avg_return_from_first_run >= 0 ? 'pos' : 'neg'">
                    {{ lowTop.avg_return_from_first_run > 0 ? '+' : '' }}{{ lowTop.avg_return_from_first_run | number:'1.2-2' }}%
                  </div>
                  <div style="font-size:9px; color:#64748b;">
                    first run &rarr; now &middot; {{ lowTop.first_run_priced }} of {{ lowTop.count }} priced
                  </div>
                }
              </div>

              <div style="background:#0f172a; border-radius:6px; padding:6px 14px; min-width:200px;
                          display:flex; flex-direction:column; justify-content:space-between;">
                <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px;">Trend %</div>
                @if (lowTop.trend_direction === 'NEW') {
                  <div style="font-size:17px; font-weight:800; color:#94a3b8; line-height:1.2;">&mdash;</div>
                  <div style="font-size:9px; color:#64748b;">No previous run to compare</div>
                } @else {
                  <div style="font-size:17px; font-weight:800; line-height:1.2;"
                       [style.color]="lowTop.trend_direction === 'UP' ? '#22c55e' : lowTop.trend_direction === 'DOWN' ? '#ef4444' : '#94a3b8'">
                    {{ lowTop.trend_direction === 'UP' ? '&#8593;' : lowTop.trend_direction === 'DOWN' ? '&#8595;' : '&#8594;' }}
                    {{ lowTop.trend_pct > 0 ? '+' : '' }}{{ lowTop.trend_pct | number:'1.2-2' }}%
                    <span style="font-size:11px; font-weight:600;">
                      {{ lowTop.trend_direction === 'UP' ? 'Improving' : lowTop.trend_direction === 'DOWN' ? 'Declining' : 'Unchanged' }}
                    </span>
                  </div>
                  <div style="font-size:9px; color:#64748b;">
                    vs {{ lowTop.avg_return_previous | number:'1.2-2' }}% frozen {{ lowTop.previous_day }}
                  </div>
                }
              </div>
            </div>
          }
          <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
            <span style="font-size:10px; color:#64748b;">From</span>
            <input type="date" class="select-sm" [(ngModel)]="lowFrom">
            <span style="font-size:10px; color:#64748b;">To</span>
            <input type="date" class="select-sm" [(ngModel)]="lowTo">
            <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                    (click)="runLowPriceTrend()" [disabled]="lowRefreshing">
              @if (lowRefreshing) { <span class="spin"></span> Running... } @else { Run }
            </button>
            <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                    (click)="loadBelowPriceTop10()" [disabled]="lowTopLoading"
                    title="Re-identify, re-rank and re-signal at the latest available prices">
              @if (lowTopLoading) { <span class="spin"></span> } @else { Refresh Top 10 }
            </button>
            <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                    (click)="downloadRsi45Section()"
                    title="Excel/CSV of exactly the stocks listed below, laid out like the ATR Base Price Crossover file">
              Download stocks
            </button>
          </div>
        </div>

        <!-- Identify -> Rank -> Buy/Sell, in that order. The list below is the Top 10 that
             survived identification, scored by the existing engine, with the existing signal. -->
        @if (lowTop?.top10?.length) {
          <div style="overflow-x:auto; margin-bottom:10px;">
            <table style="width:100%; border-collapse:collapse; min-width:900px;">
              <thead>
                <tr>
                  <th class="sb-hdr" style="text-align:center;">Rank</th>
                  <th class="sb-hdr">Symbol</th>
                  <th class="sb-hdr">Sector</th>
                  <th class="sb-hdr" style="text-align:right;" title="Price on the day before - must be below the base price">Prev Close</th>
                  <th class="sb-hdr" style="text-align:right;">Base Price</th>
                  <th class="sb-hdr" style="text-align:right;" title="Traded price at the cut-off on the day this list was frozen - the price the position was opened at">Entry &#64; {{ lowTop?.top10?.[0]?.entry_time || '15:15' }}</th>
                  <th class="sb-hdr" style="text-align:right;" title="Must be at or above the base price - together with the previous close below it, that is the crossing">Current</th>
                  <th class="sb-hdr" style="text-align:right;" title="Move since the entry price, not since the base price">Return %</th>
                  <th class="sb-hdr" style="text-align:right;" title="Traded volume, which had to beat the 20-day average to qualify">Volume</th>
                  <th class="sb-hdr" style="text-align:right;">20D Avg Vol</th>
                  <th class="sb-hdr" style="text-align:right;" title="Average True Range over the 14 sessions ending at the previous close">ATR14</th>
                  <th class="sb-hdr" style="text-align:right;" title="ATR14 as a percentage of the previous close - the same measure the ATR Base Price Crossover screen filters on">ATR %</th>
                  <th class="sb-hdr" style="text-align:center;" title="From the existing Buy/Sell logic, applied after ranking">Signal</th>
                  <th class="sb-hdr">Trend</th>
                </tr>
              </thead>
              <tbody>
                @for (r of lowTop.top10; track r.symbol) {
                  <tr>
                    <td class="sb-cell" style="text-align:center; font-weight:700;"
                        [style.color]="r.rank === 1 ? '#facc15' : r.rank <= 3 ? '#94a3b8' : '#64748b'">{{ r.rank }}</td>
                    <td class="sb-cell" style="color:#fff; font-weight:700;">{{ r.symbol }}</td>
                    <td class="sb-cell" style="color:#cbd5e1;">{{ r.sector || '-' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ r.previous_close | number:'1.2-2' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ r.base_price | number:'1.2-2' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ r.entry_price | number:'1.2-2' }}</td>
                    <td class="sb-cell" style="text-align:right; font-weight:600;"
                        [style.color]="r.is_live ? '#22c55e' : '#fff'"
                        [title]="r.is_live ? ('Live price. Close on ' + lowTop.as_of + ' was ' + (r.close_price | number:'1.2-2')) : 'Closing price'">
                      {{ r.current_price | number:'1.2-2' }}
                    </td>
                    <td class="sb-cell" style="text-align:right;" [class]="(r.return_pct || 0) >= 0 ? 'pos' : 'neg'">{{ r.return_pct | number:'1.2-2' }}%</td>
                    <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ r.volume | number:'1.0-0' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#64748b;">{{ r.avg_20d_volume | number:'1.0-0' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ r.atr14 ? (r.atr14 | number:'1.2-2') : '-' }}</td>
                    <td class="sb-cell" style="text-align:right; font-weight:600;"
                        [style.color]="(r.atr_pct || 0) >= atrHighlightPct ? '#22c55e' : '#94a3b8'"
                        [title]="(r.atr_pct || 0) >= atrHighlightPct ? 'At or above the ' + atrHighlightPct + '% mark' : 'Below the ' + atrHighlightPct + '% mark'">{{ r.atr_pct ? (r.atr_pct | number:'1.2-2') + '%' : '-' }}</td>
                    <td class="sb-cell" style="text-align:center;">
                      <span style="font-weight:800; padding:2px 8px; border-radius:4px;"
                            [style.color]="r.signal === 'BUY' ? '#22c55e' : r.signal === 'SELL' ? '#ef4444' : '#94a3b8'"
                            [style.background]="r.signal === 'BUY' ? '#0d2818' : r.signal === 'SELL' ? '#2a0f0f' : '#16202e'"
                            [title]="r.signal_message || ''">{{ r.signal }}</span>
                    </td>
                    <td class="sb-cell"
                        [style.color]="r.trend === 'Bullish' ? '#22c55e' : r.trend === 'Bearish' ? '#ef4444' : '#94a3b8'">{{ r.trend || '-' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <!-- Heading into tomorrow. Deliberately below the frozen list: the first question is
               what is currently held, the second is what replaces it. -->
          @if (provisional) {
            <div style="border:1px dashed #3a4a5f; border-radius:6px; padding:9px 12px; margin-bottom:10px;">
              <div style="display:flex; align-items:baseline; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:6px;">
                <span style="font-size:11px; font-weight:800; color:#facc15;">
                  Heading into tomorrow &mdash; provisional
                </span>
                <span style="font-size:9.5px; color:#94a3b8;">
                  {{ provisional.note }}
                  @if (provisional.pool_size) {
                    <span style="color:#64748b;">&middot; {{ provisional.pool_size }} passed the entry test</span>
                  }
                </span>
              </div>
              @if (provisional.stocks?.length) {
                <div style="overflow-x:auto;">
                  <table style="width:100%; border-collapse:collapse; min-width:720px;">
                    <thead>
                      <tr>
                        <th class="sb-hdr" style="text-align:center;">Rank</th>
                        <th class="sb-hdr">Symbol</th>
                        <th class="sb-hdr">Sector</th>
                        <th class="sb-hdr" style="text-align:right;">Prev Close</th>
                        <th class="sb-hdr" style="text-align:right;">Base Price</th>
                        <th class="sb-hdr" style="text-align:right;">Current</th>
                        <th class="sb-hdr" style="text-align:right;">Return %</th>
                        <th class="sb-hdr" style="text-align:right;">Score</th>
                        <th class="sb-hdr" style="text-align:right;">Weight Used</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (p of provisional.stocks; track p.symbol) {
                        <tr>
                          <td class="sb-cell" style="text-align:center; font-weight:700; color:#facc15;">{{ p.rank }}</td>
                          <td class="sb-cell" style="color:#fff; font-weight:700;">{{ p.symbol }}</td>
                          <td class="sb-cell" style="color:#cbd5e1;">{{ p.sector || '-' }}</td>
                          <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ p.previous_close | number:'1.2-2' }}</td>
                          <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ p.base_price | number:'1.2-2' }}</td>
                          <td class="sb-cell" style="text-align:right; color:#fff; font-weight:600;">{{ p.current_price | number:'1.2-2' }}</td>
                          <td class="sb-cell" style="text-align:right;" [class]="(p.return_pct || 0) >= 0 ? 'pos' : 'neg'">{{ p.return_pct | number:'1.2-2' }}%</td>
                          <td class="sb-cell" style="text-align:right; color:#60a5fa; font-weight:800;">{{ p.score | number:'1.2-2' }}</td>
                          <td class="sb-cell" style="text-align:right; color:#e2e8f0;">{{ p.weight_used | number:'1.0-0' }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              } @else {
                <div style="font-size:10.5px; color:#94a3b8;">{{ provisional.message || 'Nothing meets the entry test right now.' }}</div>
              }
            </div>
          }

          <div style="font-size:9.5px; color:#64748b; margin-bottom:10px;">
            {{ lowTop.pipeline }}
            @if (lowTop.price_basis) { <span style="margin-left:8px;">&middot; {{ lowTop.price_basis }}</span> }
          </div>
        } @else if (lowTopLoading) {
          <div style="font-size:11px; color:#94a3b8; padding:10px 0;">Identifying and ranking&hellip;</div>
        } @else if (lowTop && !lowTop.top10?.length) {
          <div style="font-size:11px; color:#94a3b8; padding:10px 0;">
            {{ lowTop.message || 'No stock met the entry rules.' }}
          </div>
        }

        @if (lowResult && lowTrend.length) {
          <div style="display:flex; gap:14px; flex-wrap:wrap; align-items:center; padding:6px 10px;
                      margin-bottom:8px; background:#0f172a; border-radius:6px; font-size:11px;">
            <span style="color:#94a3b8;">{{ lowResult.from_date }} &rarr; {{ lowResult.to_date }}</span>
            <span style="color:#94a3b8;">Avg
              <strong [style.color]="lowResult.avg_return >= 0 ? '#22c55e' : '#ef4444'">{{ lowResult.avg_return | number:'1.2-2' }}%</strong>
            </span>
            <span style="color:#94a3b8;">Days <strong style="color:#e2e8f0;">{{ lowResult.days_with_data }}</strong></span>
            <span style="color:#22c55e;">Up {{ lowResult.winning_days }}</span>
            <span style="color:#ef4444;">Down {{ lowResult.losing_days }}</span>
            @if (lowResult.days_skipped) {
              <span style="color:#f59e0b;" title="Trading days that could not be settled - no next-day close stored yet, or no stock under the price met the entry rules.">Unsettled {{ lowResult.days_skipped }}</span>
            }
          </div>
        }

        @if (lowTrend.length) {
          <div style="display:flex; gap:6px; align-items:center; flex-wrap:wrap; margin-bottom:8px;">
            <span style="font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.4px;">Holding period</span>
            @for (dnum of holdingDayChoices; track dnum) {
              <button class="btn-outline" style="font-size:10px; padding:4px 11px;"
                      [style.background]="lowHoldingDay === dnum ? '#1d6adb' : 'transparent'"
                      [style.color]="lowHoldingDay === dnum ? '#fff' : '#cbd5e1'"
                      [style.borderColor]="lowHoldingDay === dnum ? '#1d6adb' : '#334155'"
                      (click)="setHoldingDay(dnum)"
                      [title]="'Return ' + dnum + ' trading day(s) after entry, measured from the entry price'">
                Day {{ dnum }}
              </button>
            }
          </div>

          <div style="display:flex; gap:14px; flex-wrap:wrap; align-items:center; padding:6px 10px;
                      margin-bottom:8px; background:#0f172a; border-radius:6px; font-size:11px;">
            <span style="color:#94a3b8;">Holding <strong style="color:#e2e8f0;">Day {{ lowHoldingDay }}</strong></span>
            <span style="color:#94a3b8;">Trades <strong style="color:#e2e8f0;">{{ lowHoldingStats.trades }}</strong></span>
            <span style="color:#22c55e;">Win {{ lowHoldingStats.wins }}</span>
            <span style="color:#ef4444;">Loss {{ lowHoldingStats.losses }}</span>
            <span style="color:#94a3b8;">Win Rate
              <strong [style.color]="lowHoldingStats.winRate >= 60 ? '#22c55e' : '#e2e8f0'">{{ lowHoldingStats.winRate | number:'1.1-1' }}%</strong>
            </span>
            <span style="color:#94a3b8;">Avg
              <strong [style.color]="lowHoldingStats.avg >= 0 ? '#22c55e' : '#ef4444'">{{ lowHoldingStats.avg | number:'1.2-2' }}%</strong>
            </span>
            <span style="color:#94a3b8;">Median
              <strong [style.color]="lowHoldingStats.median >= 0 ? '#22c55e' : '#ef4444'">{{ lowHoldingStats.median | number:'1.2-2' }}%</strong>
            </span>
            <span style="color:#94a3b8;" title="Gross profit divided by gross loss across every trade at this holding day">Profit Factor
              <strong style="color:#e2e8f0;">{{ lowHoldingStats.profitFactor == null ? 'no losses' : (lowHoldingStats.profitFactor | number:'1.2-2') }}</strong>
            </span>
          </div>
        }

        <div style="display:flex; align-items:flex-end; gap:4px; height:200px; overflow-x:auto; padding-bottom:26px; position:relative;">
          <div style="position:absolute; bottom:24px; left:0; right:0; height:1px; background:#1e2a3b;"></div>
          @for (d of lowHoldingBars; track d.batch_id) {
            <div style="display:flex; flex-direction:column; align-items:center; gap:2px; min-width:38px;"
                 [title]="d.no_stock ? (d.trade_date + ' - no stock met the entry rules' + (d.note ? ': ' + d.note : ''))
                          : d.is_open ? (d.trade_date + ' - OPEN position, ' + d.open_priced + ' stock(s) valued live. ' + (d.note || ''))
                          : batchBarTooltip(d)">
              @if (d.no_stock) {
                <div style="font-size:7px; font-weight:700; color:#f59e0b; writing-mode:vertical-rl;
                            text-orientation:mixed; height:64px; display:flex; align-items:flex-end;
                            justify-content:center; letter-spacing:.3px;">No Stock</div>
                <div style="width:16px; height:2px; background:#3a4a5f; border-radius:1px;"></div>
              } @else if (d.is_open) {
                <div style="font-size:8px; font-weight:700;"
                     [style.color]="(d.open_return || 0) >= 0 ? '#22c55e' : '#ef4444'">
                  {{ d.open_return != null ? (d.open_return | number:'1.1-1') : '' }}
                </div>
                <!-- Hollow, because it is not a result yet: the position is still running and this
                     number will keep moving until it settles. -->
                <div [style.height]="barH(d.open_return || 0, lowTrendMax)+'px'"
                     [style.border]="'1.5px dashed ' + ((d.open_return || 0) >= 0 ? '#22c55e' : '#ef4444')"
                     style="width:22px; border-radius:2px; background:transparent;"></div>
                <div style="font-size:6.5px; font-weight:700; color:#facc15; letter-spacing:.2px;">OPEN</div>
              } @else if (d.day_return == null) {
                <!-- The trades exist but this holding day has not traded yet. Shown as absent
                     rather than as 0%, which would read as a flat day that never happened. -->
                <div style="font-size:7px; font-weight:700; color:#64748b; writing-mode:vertical-rl;
                            text-orientation:mixed; height:56px; display:flex; align-items:flex-end;
                            justify-content:center;">Day {{ lowHoldingDay }} pending</div>
                <div style="width:16px; height:2px; background:#3a4a5f; border-radius:1px;"></div>
              } @else {
                <div style="font-size:8px; font-weight:700;"
                     [style.color]="d.day_return >= 0 ? '#22c55e' : '#ef4444'">
                  {{ d.day_return | number:'1.1-1' }}
                </div>
                <div class="bar-chart-bar"
                     [style.height]="barH(d.day_return, lowHoldingMax)+'px'"
                     [style.background]="d.day_return >= 0 ? '#1d6adb' : '#dc2626'"
                     [title]="d.trade_date + ' - Day ' + lowHoldingDay + ': average of ' + d.day_trades + ' trade(s)'"></div>
              }
              <div style="font-size:8.5px; color:#94a3b8; white-space:nowrap; margin-top:7px; width:100%; text-align:center; letter-spacing:-.1px;">
                {{ formatChartDate(d.trade_date) }}
              </div>
            </div>
          }
          @if (!lowTrend.length && !lowRefreshing) {
            <div style="color:#94a3b8; font-size:11px; padding:18px 20px; width:100%; line-height:1.65;">
              <div style="font-weight:700; color:#e2e8f0; margin-bottom:6px;">Nothing to show for this basket yet.</div>
              <div>Pick a date range and press <strong style="color:#cbd5e1;">Run</strong>. Each day is
              rebuilt from stored prices, keeping the stocks that met the entry rules that day,
              then measured at the next trading day&rsquo;s sell time.</div>
            </div>
          }
          @if (lowRefreshing) {
            <div style="color:#94a3b8; font-size:11px; padding:20px; width:100%; text-align:center;">
              Rebuilding each trading day&hellip;
            </div>
          }
        </div>
      </div>

      <!-- Chart 4: the crossing, with the volume test applied as well. -->
      <div class="card" style="padding:12px; grid-column:1 / -1;">
        <div style="display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:8px;">
          <div style="flex:1 1 420px;">
            <span style="font-size:13px; font-weight:800; color:#60a5fa;">
              RSI 45 Previous Close Less the Base Price with Volume
            </span>
            <div style="font-size:10px; color:#94a3b8; margin-top:3px;">
              Entry test: Previous Close &lt; Base Price <strong>and</strong> Current Price &ge; Base Price
              (the crossing), <strong>and</strong> Current Volume &gt; the average volume of the last
              20 working days. <strong>No ranking</strong> &mdash; every stock that qualifies is listed,
              strongest move first. Bought at the closing price and sold at the next trading day's
              close; when that day is not a working day, the next working day's close.
            </div>
          </div>

          <!-- The two headline figures sit on the title line, where they are read first, rather
               than in a band below the controls. Both are baseline-aligned and use the same
               label / value / caption shape so the eye can compare them at a glance. -->
          @if (volTop?.top10?.length) {
            <div style="display:flex; gap:10px; align-items:stretch; flex-wrap:wrap;">
              <div style="background:#0f172a; border-radius:6px; padding:6px 14px; min-width:170px;
                          display:flex; flex-direction:column; justify-content:space-between;">
                <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px;">Avg Return %</div>
                <div style="font-size:17px; font-weight:800; line-height:1.2;"
                     [class]="(volTop.avg_return_current || 0) >= 0 ? 'pos' : 'neg'">
                  {{ volTop.avg_return_current | number:'1.2-2' }}%
                </div>
                <div style="font-size:9px; color:#64748b;">
                  {{ volTop.count }} stock(s) &middot;
                  <span style="color:#cbd5e1;" [title]="'Frozen at the ' + volTop.frozen_time + ' cut-off on ' + volTop.frozen_date + '. The list does not change during the current session - only its prices move.'">
                    frozen {{ volTop.frozen_date }} {{ volTop.frozen_time }}
                  </span>
                  @if (volTop.live && volTop.live_priced) {
                    <span style="color:#22c55e; font-weight:700;">&middot; LIVE</span>
                  }
                </div>
              </div>

              <div style="background:#0f172a; border-radius:6px; padding:6px 14px; min-width:180px;
                          display:flex; flex-direction:column; justify-content:space-between;">
                <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px;"
                     title="Average move of these stocks from the first run of the day to now. Outside market hours 'now' is the last completed close, so the figure settles rather than drifting.">Today Avg Return</div>
                @if (volTop.avg_return_from_first_run == null) {
                  <div style="font-size:17px; font-weight:800; color:#94a3b8; line-height:1.2;">&mdash;</div>
                  <div style="font-size:9px; color:#64748b;">No first-run price stored for today yet</div>
                } @else {
                  <div style="font-size:17px; font-weight:800; line-height:1.2;"
                       [class]="volTop.avg_return_from_first_run >= 0 ? 'pos' : 'neg'">
                    {{ volTop.avg_return_from_first_run > 0 ? '+' : '' }}{{ volTop.avg_return_from_first_run | number:'1.2-2' }}%
                  </div>
                  <div style="font-size:9px; color:#64748b;">
                    first run &rarr; now &middot; {{ volTop.first_run_priced }} of {{ volTop.count }} priced
                  </div>
                }
              </div>

              <div style="background:#0f172a; border-radius:6px; padding:6px 14px; min-width:200px;
                          display:flex; flex-direction:column; justify-content:space-between;">
                <div style="font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px;">Trend %</div>
                @if (volTop.trend_direction === 'NEW') {
                  <div style="font-size:17px; font-weight:800; color:#94a3b8; line-height:1.2;">&mdash;</div>
                  <div style="font-size:9px; color:#64748b;">No previous run to compare</div>
                } @else {
                  <div style="font-size:17px; font-weight:800; line-height:1.2;"
                       [style.color]="volTop.trend_direction === 'UP' ? '#22c55e' : volTop.trend_direction === 'DOWN' ? '#ef4444' : '#94a3b8'">
                    {{ volTop.trend_direction === 'UP' ? '&#8593;' : volTop.trend_direction === 'DOWN' ? '&#8595;' : '&#8594;' }}
                    {{ volTop.trend_pct > 0 ? '+' : '' }}{{ volTop.trend_pct | number:'1.2-2' }}%
                    <span style="font-size:11px; font-weight:600;">
                      {{ volTop.trend_direction === 'UP' ? 'Improving' : volTop.trend_direction === 'DOWN' ? 'Declining' : 'Unchanged' }}
                    </span>
                  </div>
                  <div style="font-size:9px; color:#64748b;">
                    vs {{ volTop.avg_return_previous | number:'1.2-2' }}% frozen {{ volTop.previous_day }}
                  </div>
                }
              </div>
            </div>
          }
          <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
            <span style="font-size:10px; color:#64748b;">From</span>
            <input type="date" class="select-sm" [(ngModel)]="volFrom">
            <span style="font-size:10px; color:#64748b;">To</span>
            <input type="date" class="select-sm" [(ngModel)]="volTo">
            <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                    (click)="runVolumeTrend()" [disabled]="volRefreshing">
              @if (volRefreshing) { <span class="spin"></span> Running... } @else { Run }
            </button>
            <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                    (click)="loadVolumeTop10()" [disabled]="volTopLoading"
                    title="Re-identify, re-rank and re-signal at the latest available prices">
              @if (volTopLoading) { <span class="spin"></span> } @else { Refresh Top 10 }
            </button>
            <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                    (click)="downloadRsi45VolumeSection()"
                    title="Excel/CSV of exactly the stocks listed below, laid out like the ATR Base Price Crossover file">
              Download stocks
            </button>
          </div>
        </div>

        <!-- Identify -> Rank -> Buy/Sell, in that order. The list below is the Top 10 that
             survived identification, scored by the existing engine, with the existing signal. -->
        @if (volTop?.top10?.length) {
          <div style="overflow-x:auto; margin-bottom:10px;">
            <table style="width:100%; border-collapse:collapse; min-width:900px;">
              <thead>
                <tr>
                  <th class="sb-hdr" style="text-align:center;">Rank</th>
                  <th class="sb-hdr">Symbol</th>
                  <th class="sb-hdr">Sector</th>
                  <th class="sb-hdr" style="text-align:right;" title="Price on the day before - must be below the base price">Prev Close</th>
                  <th class="sb-hdr" style="text-align:right;">Base Price</th>
                  <th class="sb-hdr" style="text-align:right;" title="Traded price at the cut-off on the day this list was frozen - the price the position was opened at">Entry &#64; {{ volTop?.top10?.[0]?.entry_time || '15:15' }}</th>
                  <th class="sb-hdr" style="text-align:right;" title="Must be at or above the base price - together with the previous close below it, that is the crossing">Current</th>
                  <th class="sb-hdr" style="text-align:right;" title="Move since the entry price, not since the base price">Return %</th>
                  <th class="sb-hdr" style="text-align:right;" title="Traded volume, which had to beat the 20-day average to qualify">Volume</th>
                  <th class="sb-hdr" style="text-align:right;">20D Avg Vol</th>
                  <th class="sb-hdr" style="text-align:right;" title="Average True Range over the 14 sessions ending at the previous close">ATR14</th>
                  <th class="sb-hdr" style="text-align:right;" title="ATR14 as a percentage of the previous close - the same measure the ATR Base Price Crossover screen filters on">ATR %</th>
                  <th class="sb-hdr" style="text-align:center;" title="From the existing Buy/Sell logic, applied after ranking">Signal</th>
                  <th class="sb-hdr">Trend</th>
                </tr>
              </thead>
              <tbody>
                @for (r of volTop.top10; track r.symbol) {
                  <tr>
                    <td class="sb-cell" style="text-align:center; font-weight:700;"
                        [style.color]="r.rank === 1 ? '#facc15' : r.rank <= 3 ? '#94a3b8' : '#64748b'">{{ r.rank }}</td>
                    <td class="sb-cell" style="color:#fff; font-weight:700;">{{ r.symbol }}</td>
                    <td class="sb-cell" style="color:#cbd5e1;">{{ r.sector || '-' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ r.previous_close | number:'1.2-2' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ r.base_price | number:'1.2-2' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ r.entry_price | number:'1.2-2' }}</td>
                    <td class="sb-cell" style="text-align:right; font-weight:600;"
                        [style.color]="r.is_live ? '#22c55e' : '#fff'"
                        [title]="r.is_live ? ('Live price. Close on ' + volTop.as_of + ' was ' + (r.close_price | number:'1.2-2')) : 'Closing price'">
                      {{ r.current_price | number:'1.2-2' }}
                    </td>
                    <td class="sb-cell" style="text-align:right;" [class]="(r.return_pct || 0) >= 0 ? 'pos' : 'neg'">{{ r.return_pct | number:'1.2-2' }}%</td>
                    <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ r.volume | number:'1.0-0' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#64748b;">{{ r.avg_20d_volume | number:'1.0-0' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ r.atr14 ? (r.atr14 | number:'1.2-2') : '-' }}</td>
                    <td class="sb-cell" style="text-align:right; font-weight:600;"
                        [style.color]="(r.atr_pct || 0) >= atrHighlightPct ? '#22c55e' : '#94a3b8'"
                        [title]="(r.atr_pct || 0) >= atrHighlightPct ? 'At or above the ' + atrHighlightPct + '% mark' : 'Below the ' + atrHighlightPct + '% mark'">{{ r.atr_pct ? (r.atr_pct | number:'1.2-2') + '%' : '-' }}</td>
                    <td class="sb-cell" style="text-align:center;">
                      <span style="font-weight:800; padding:2px 8px; border-radius:4px;"
                            [style.color]="r.signal === 'BUY' ? '#22c55e' : r.signal === 'SELL' ? '#ef4444' : '#94a3b8'"
                            [style.background]="r.signal === 'BUY' ? '#0d2818' : r.signal === 'SELL' ? '#2a0f0f' : '#16202e'"
                            [title]="r.signal_message || ''">{{ r.signal }}</span>
                    </td>
                    <td class="sb-cell"
                        [style.color]="r.trend === 'Bullish' ? '#22c55e' : r.trend === 'Bearish' ? '#ef4444' : '#94a3b8'">{{ r.trend || '-' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <!-- Heading into tomorrow. Deliberately below the frozen list: the first question is
               what is currently held, the second is what replaces it. -->
          @if (volProvisional) {
            <div style="border:1px dashed #3a4a5f; border-radius:6px; padding:9px 12px; margin-bottom:10px;">
              <div style="display:flex; align-items:baseline; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:6px;">
                <span style="font-size:11px; font-weight:800; color:#facc15;">
                  Heading into tomorrow &mdash; volProvisional
                </span>
                <span style="font-size:9.5px; color:#94a3b8;">
                  {{ volProvisional.note }}
                  @if (volProvisional.pool_size) {
                    <span style="color:#64748b;">&middot; {{ volProvisional.pool_size }} passed the entry test</span>
                  }
                </span>
              </div>
              @if (volProvisional.stocks?.length) {
                <div style="overflow-x:auto;">
                  <table style="width:100%; border-collapse:collapse; min-width:720px;">
                    <thead>
                      <tr>
                        <th class="sb-hdr" style="text-align:center;">Rank</th>
                        <th class="sb-hdr">Symbol</th>
                        <th class="sb-hdr">Sector</th>
                        <th class="sb-hdr" style="text-align:right;">Prev Close</th>
                        <th class="sb-hdr" style="text-align:right;">Base Price</th>
                        <th class="sb-hdr" style="text-align:right;">Current</th>
                        <th class="sb-hdr" style="text-align:right;">Return %</th>
                        <th class="sb-hdr" style="text-align:right;">Score</th>
                        <th class="sb-hdr" style="text-align:right;">Weight Used</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (p of volProvisional.stocks; track p.symbol) {
                        <tr>
                          <td class="sb-cell" style="text-align:center; font-weight:700; color:#facc15;">{{ p.rank }}</td>
                          <td class="sb-cell" style="color:#fff; font-weight:700;">{{ p.symbol }}</td>
                          <td class="sb-cell" style="color:#cbd5e1;">{{ p.sector || '-' }}</td>
                          <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ p.previous_close | number:'1.2-2' }}</td>
                          <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ p.base_price | number:'1.2-2' }}</td>
                          <td class="sb-cell" style="text-align:right; color:#fff; font-weight:600;">{{ p.current_price | number:'1.2-2' }}</td>
                          <td class="sb-cell" style="text-align:right;" [class]="(p.return_pct || 0) >= 0 ? 'pos' : 'neg'">{{ p.return_pct | number:'1.2-2' }}%</td>
                          <td class="sb-cell" style="text-align:right; color:#60a5fa; font-weight:800;">{{ p.score | number:'1.2-2' }}</td>
                          <td class="sb-cell" style="text-align:right; color:#e2e8f0;">{{ p.weight_used | number:'1.0-0' }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              } @else {
                <div style="font-size:10.5px; color:#94a3b8;">{{ volProvisional.message || 'Nothing meets the entry test right now.' }}</div>
              }
            </div>
          }

          <div style="font-size:9.5px; color:#64748b; margin-bottom:10px;">
            {{ volTop.pipeline }}
            @if (volTop.price_basis) { <span style="margin-left:8px;">&middot; {{ volTop.price_basis }}</span> }
          </div>
        } @else if (volTopLoading) {
          <div style="font-size:11px; color:#94a3b8; padding:10px 0;">Identifying and ranking&hellip;</div>
        } @else if (volTop && !volTop.top10?.length) {
          <div style="font-size:11px; color:#94a3b8; padding:10px 0;">
            {{ volTop.message || 'No stock met the entry rules.' }}
          </div>
        }

        @if (volResult && volTrend.length) {
          <div style="display:flex; gap:14px; flex-wrap:wrap; align-items:center; padding:6px 10px;
                      margin-bottom:8px; background:#0f172a; border-radius:6px; font-size:11px;">
            <span style="color:#94a3b8;">{{ volResult.from_date }} &rarr; {{ volResult.to_date }}</span>
            <span style="color:#94a3b8;">Avg
              <strong [style.color]="volResult.avg_return >= 0 ? '#22c55e' : '#ef4444'">{{ volResult.avg_return | number:'1.2-2' }}%</strong>
            </span>
            <span style="color:#94a3b8;">Days <strong style="color:#e2e8f0;">{{ volResult.days_with_data }}</strong></span>
            <span style="color:#22c55e;">Up {{ volResult.winning_days }}</span>
            <span style="color:#ef4444;">Down {{ volResult.losing_days }}</span>
            @if (volResult.days_skipped) {
              <span style="color:#f59e0b;" title="Trading days that could not be settled - no next-day close stored yet, or no stock under the price met the entry rules.">Unsettled {{ volResult.days_skipped }}</span>
            }
          </div>
        }

        @if (volTrend.length) {
          <div style="display:flex; gap:6px; align-items:center; flex-wrap:wrap; margin-bottom:8px;">
            <span style="font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.4px;">Holding period</span>
            @for (dnum of holdingDayChoices; track dnum) {
              <button class="btn-outline" style="font-size:10px; padding:4px 11px;"
                      [style.background]="volHoldingDay === dnum ? '#1d6adb' : 'transparent'"
                      [style.color]="volHoldingDay === dnum ? '#fff' : '#cbd5e1'"
                      [style.borderColor]="volHoldingDay === dnum ? '#1d6adb' : '#334155'"
                      (click)="setVolHoldingDay(dnum)"
                      [title]="'Return ' + dnum + ' trading day(s) after entry, measured from the entry price'">
                Day {{ dnum }}
              </button>
            }
          </div>

          <div style="display:flex; gap:14px; flex-wrap:wrap; align-items:center; padding:6px 10px;
                      margin-bottom:8px; background:#0f172a; border-radius:6px; font-size:11px;">
            <span style="color:#94a3b8;">Holding <strong style="color:#e2e8f0;">Day {{ volHoldingDay }}</strong></span>
            <span style="color:#94a3b8;">Trades <strong style="color:#e2e8f0;">{{ volHoldingStats.trades }}</strong></span>
            <span style="color:#22c55e;">Win {{ volHoldingStats.wins }}</span>
            <span style="color:#ef4444;">Loss {{ volHoldingStats.losses }}</span>
            <span style="color:#94a3b8;">Win Rate
              <strong [style.color]="volHoldingStats.winRate >= 60 ? '#22c55e' : '#e2e8f0'">{{ volHoldingStats.winRate | number:'1.1-1' }}%</strong>
            </span>
            <span style="color:#94a3b8;">Avg
              <strong [style.color]="volHoldingStats.avg >= 0 ? '#22c55e' : '#ef4444'">{{ volHoldingStats.avg | number:'1.2-2' }}%</strong>
            </span>
            <span style="color:#94a3b8;">Median
              <strong [style.color]="volHoldingStats.median >= 0 ? '#22c55e' : '#ef4444'">{{ volHoldingStats.median | number:'1.2-2' }}%</strong>
            </span>
            <span style="color:#94a3b8;" title="Gross profit divided by gross loss across every trade at this holding day">Profit Factor
              <strong style="color:#e2e8f0;">{{ volHoldingStats.profitFactor == null ? 'no losses' : (volHoldingStats.profitFactor | number:'1.2-2') }}</strong>
            </span>
          </div>
        }

        <div style="display:flex; align-items:flex-end; gap:4px; height:200px; overflow-x:auto; padding-bottom:26px; position:relative;">
          <div style="position:absolute; bottom:24px; left:0; right:0; height:1px; background:#1e2a3b;"></div>
          @for (d of volHoldingBars; track d.batch_id) {
            <div style="display:flex; flex-direction:column; align-items:center; gap:2px; min-width:38px;"
                 [title]="d.no_stock ? (d.trade_date + ' - no stock met the entry rules' + (d.note ? ': ' + d.note : ''))
                          : d.is_open ? (d.trade_date + ' - OPEN position, ' + d.open_priced + ' stock(s) valued live. ' + (d.note || ''))
                          : batchBarTooltip(d)">
              @if (d.no_stock) {
                <div style="font-size:7px; font-weight:700; color:#f59e0b; writing-mode:vertical-rl;
                            text-orientation:mixed; height:64px; display:flex; align-items:flex-end;
                            justify-content:center; letter-spacing:.3px;">No Stock</div>
                <div style="width:16px; height:2px; background:#3a4a5f; border-radius:1px;"></div>
              } @else if (d.is_open) {
                <div style="font-size:8px; font-weight:700;"
                     [style.color]="(d.open_return || 0) >= 0 ? '#22c55e' : '#ef4444'">
                  {{ d.open_return != null ? (d.open_return | number:'1.1-1') : '' }}
                </div>
                <!-- Hollow, because it is not a result yet: the position is still running and this
                     number will keep moving until it settles. -->
                <div [style.height]="barH(d.open_return || 0, volTrendMax)+'px'"
                     [style.border]="'1.5px dashed ' + ((d.open_return || 0) >= 0 ? '#22c55e' : '#ef4444')"
                     style="width:22px; border-radius:2px; background:transparent;"></div>
                <div style="font-size:6.5px; font-weight:700; color:#facc15; letter-spacing:.2px;">OPEN</div>
              } @else if (d.day_return == null) {
                <!-- The trades exist but this holding day has not traded yet. Shown as absent
                     rather than as 0%, which would read as a flat day that never happened. -->
                <div style="font-size:7px; font-weight:700; color:#64748b; writing-mode:vertical-rl;
                            text-orientation:mixed; height:56px; display:flex; align-items:flex-end;
                            justify-content:center;">Day {{ volHoldingDay }} pending</div>
                <div style="width:16px; height:2px; background:#3a4a5f; border-radius:1px;"></div>
              } @else {
                <div style="font-size:8px; font-weight:700;"
                     [style.color]="d.day_return >= 0 ? '#22c55e' : '#ef4444'">
                  {{ d.day_return | number:'1.1-1' }}
                </div>
                <div class="bar-chart-bar"
                     [style.height]="barH(d.day_return, volHoldingMax)+'px'"
                     [style.background]="d.day_return >= 0 ? '#1d6adb' : '#dc2626'"
                     [title]="d.trade_date + ' - Day ' + volHoldingDay + ': average of ' + d.day_trades + ' trade(s)'"></div>
              }
              <div style="font-size:8.5px; color:#94a3b8; white-space:nowrap; margin-top:7px; width:100%; text-align:center; letter-spacing:-.1px;">
                {{ formatChartDate(d.trade_date) }}
              </div>
            </div>
          }
          @if (!volTrend.length && !volRefreshing) {
            <div style="color:#94a3b8; font-size:11px; padding:18px 20px; width:100%; line-height:1.65;">
              <div style="font-weight:700; color:#e2e8f0; margin-bottom:6px;">Nothing to show for this basket yet.</div>
              <div>Pick a date range and press <strong style="color:#cbd5e1;">Run</strong>. Each day is
              rebuilt from stored prices, keeping the stocks that met the entry rules that day,
              then measured at the next trading day&rsquo;s sell time.</div>
            </div>
          }
          @if (volRefreshing) {
            <div style="color:#94a3b8; font-size:11px; padding:20px; width:100%; text-align:center;">
              Rebuilding each trading day&hellip;
            </div>
          }
        </div>
      </div>

      <!-- Performance Distribution section removed -->

      <!-- Download / Export Section -->
      <div class="card" style="padding:12px; grid-column:1 / -1;">
        <div class="section-title" style="margin-bottom:10px;">Download / Export</div>
        <div style="display:grid; grid-template-columns:1fr 1fr; gap:7px;">
          <button class="btn-outline" style="display:flex; align-items:center; gap:6px; padding:7px 10px;"
            (click)="downloadRankingCurrent()">
            <span style="font-size:10.5px; color:#cbd5e1;">Download Top 10 (Current)</span>
          </button>
          <button class="btn-outline" style="display:flex; align-items:center; gap:6px; padding:7px 10px;"
            (click)="downloadChartTrades(null)"
            title="Last 30 days, one row per stock per chart bar: buy and sell date, time, price and return">
            <span style="font-size:10.5px; color:#cbd5e1;">Download Chart Bar Stocks (30 days)</span>
          </button>
          <button class="btn-outline" style="display:flex; align-items:center; gap:6px; padding:7px 10px;"
            (click)="downloadHistorical()">
            <span style="font-size:10.5px; color:#cbd5e1;">Download Historical Ranking</span>
          </button>
          <button class="btn-outline" style="display:flex; align-items:center; gap:6px; padding:7px 10px;"
            (click)="downloadRankingPrevious()">
            <span style="font-size:10.5px; color:#cbd5e1;">Download Top 10 (Previous)</span>
          </button>
          <button class="btn-outline" style="display:flex; align-items:center; gap:6px; padding:7px 10px;"
            (click)="exportCsv()">
            <span style="font-size:10.5px; color:#cbd5e1;">Export to CSV</span>
          </button>
          <button class="btn-outline" style="display:flex; align-items:center; gap:6px; padding:7px 10px; grid-column:1/-1;"
            (click)="exportPdf()">
            <span style="font-size:10.5px; color:#cbd5e1;">Download Performance Report</span>
          </button>
          <button class="btn-outline" style="display:flex; align-items:center; gap:6px; padding:7px 10px; grid-column:1/-1;"
            (click)="exportPdf()">
            <span style="font-size:10.5px; color:#cbd5e1;">Export to PDF</span>
          </button>
        </div>
      </div>

      @if (isAdmin()) {
        <button class="btn-outline" style="padding:8px; text-align:center; font-size:11px; display:block; width:100%; grid-column:1 / -1;"
          (click)="goToConfig()">Configure Ranking Factors (Admin)</button>
      }

      @if (showConfigPanel) {
        <div class="card" style="grid-column:1 / -1; padding:12px;">
          <div style="display:flex; justify-content:space-between; align-items:center; gap:10px; margin-bottom:10px;">
            <div>
              <div class="section-title">Ranking Factor Weights</div>
              <div style="font-size:10px; color:#94a3b8; margin-top:3px;">Changes apply to the next ranking run.</div>
            </div>
            <button class="btn-outline" (click)="showConfigPanel=false">Close</button>
          </div>
          @if (configLoading) {
            <div style="display:flex; align-items:center; gap:8px; color:#94a3b8; font-size:12px;"><span class="spin"></span> Loading factors...</div>
          } @else {
            <div style="overflow-x:auto;">
              <table style="width:100%; border-collapse:collapse; min-width:760px;">
                <thead>
                  <tr>
                    <th class="tbl-hdr" style="width:70px;">On</th>
                    <th class="tbl-hdr">Factor</th>
                    <th class="tbl-hdr">Description</th>
                    <th class="tbl-hdr" style="width:120px; text-align:right;">Weight</th>
                  </tr>
                </thead>
                <tbody>
                  @for (factor of weightConfigs; track factor.key) {
                    <tr class="tbl-row">
                      <td class="tbl-cell" style="text-align:center;">
                        <input type="checkbox" [ngModel]="factorEnabled(factor)" (ngModelChange)="factor.is_enabled=$event">
                      </td>
                      <td class="tbl-cell" style="font-weight:700; color:#fff;">{{ factor.label }}</td>
                      <td class="tbl-cell">{{ factor.description || '-' }}</td>
                      <td class="tbl-cell-num">
                        <input class="select-sm" style="width:86px; text-align:right;" type="number" min="0" max="30" step="0.5" [(ngModel)]="factor.weight">
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <div style="display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap; margin-top:12px;">
              <div style="font-size:11px;" [style.color]="configTotal() >= 90 && configTotal() <= 115 ? '#94a3b8' : '#fca5a5'">
                Total weight: <strong>{{ configTotal() | number:'1.1-1' }}</strong> (allowed 90-115)
                @if (configError) { <span style="color:#fca5a5; margin-left:10px;">{{ configError }}</span> }
                @if (configSuccess) { <span style="color:#4ade80; margin-left:10px;">{{ configSuccess }}</span> }
              </div>
              <div style="display:flex; gap:8px;">
                <button class="btn-outline" (click)="loadConfig()">Reset</button>
                <button class="btn-green" (click)="saveConfig()" [disabled]="configSaving || configTotal() < 90 || configTotal() > 115">
                  @if (configSaving) { <span class="spin"></span> Saving... } @else { Save Factors }
                </button>
              </div>
            </div>
          }
        </div>
      }
    </div>
  </div>

</div>
  `
})
export class StockTrackerComponent implements OnInit, OnDestroy {

  showUploadModal = signal(false);
  uploading       = signal(false);
  refreshing      = signal(false);
  ranking         = signal(false);
  showTradeModal  = signal(false);
  tradeSubmitting = signal(false);
  bulkTrading     = signal(false);
  tradeError      = signal('');
  showProgressDashboard = signal(false);
  top10Refreshing = false;
  /**
   * True only while the user's own "Refresh Top 10" is in flight.
   *
   * <p>loadRanking() clears {@link top10Refreshing}, and it is also called by the 5-minute and
   * 2-minute auto-refresh timers. Without this guard a timer firing mid-refresh cleared the flag
   * and un-froze the button while the ranking was still running, letting the user fire a second
   * one on top of the first.
   */
  private manualTop10Refresh = false;
  top10RefreshError = false;
  prevTop10Refreshing = false;
  trendRefreshing = false;
  // Kept only as a fallback for the first paint before the dashboard payload arrives.
  // It used to be stamped with the browser clock on every screen refresh, so the header
  // reported when you last looked at the page rather than when prices were actually
  // refreshed - it read "just now" on data that was hours old. The header now prefers
  // dash.last_refreshed, the server's last successful market-data sync, which is the same
  // value Uploaded Stock Details shows.
  currentTop10LastUpdated = '';
  previousTop10LastUpdated = '';
  trendLastUpdated = '';
  private top10Timer: any;

  // ── Manual refresh state machine (spec §3) ───────────────────────────────
  refreshState: 'idle' | 'updating' | 'completed' | 'failed' = 'idle';
  refreshLastUpdatedLabel = '';   // "HH:MM:SS DD-MMM-YYYY" from server

  // ── Current Top 10 live diff (SSE-driven, spec §2) ───────────────────────
  private top10Sub: any;
  changedTop10Symbols = new Set<string>();   // rows to flash on next change
  private flashTimer: any;

  // ── Automation config panel (spec §1, §4, §12) ───────────────────────────
  showAutomationPanel = false;
  showPrevAutomationPanel = false;
  private prevAutoTimer: any = null;
  prevRefreshOptions = [
    { sec: 30,   label: '30 seconds' },
    { sec: 60,   label: '1 minute' },
    { sec: 300,  label: '5 minutes' },
    { sec: 600,  label: '10 minutes' },
    { sec: 900,  label: '15 minutes' },
    { sec: 1800, label: '30 minutes' },
  ];
  automationSaving = false;
  /** Cadences offered for the Current Top 10 run. 60s is the floor: the scheduler ticks once a minute. */
  top10IntervalOptions = [
    { sec: 60,   label: '1 minute' },
    { sec: 300,  label: '5 minutes' },
    { sec: 600,  label: '10 minutes' },
    { sec: 900,  label: '15 minutes' },
    { sec: 1800, label: '30 minutes' },
    { sec: 3600, label: '1 hour' },
  ];
  automation: any = {
    top10_auto_refresh_enabled: true,
    top10_auto_refresh_interval_sec: 300,
    top10_auto_refresh_mode: 'INTERVAL',
    top10_auto_refresh_times: '09:20,15:20',
    top10_lock_enabled: false,
    prevtop10_auto_refresh_enabled: false,
    prevtop10_auto_refresh_interval_sec: 300,
    top10_lock_time: '15:20',
    prevtop10_auto_cycle_enabled: false,
    prevtop10_auto_buy_enabled: false,
    prevtop10_auto_sell_enabled: false,
    prevtop10_buy_time: '15:15',
    prevtop10_sell_time: '10:15',
    prevtop10_execution_mode: 'PAPER',
    prevtop10_use_real_price_if_executed: true,
    market_holidays: ''
  };

  // Processing job state
  activeJobId = '';
  jobMessage = '';
  activeJob: any = null;
  jobHistory: any[] = [];
  private jobPollTimer: any = null;

  // Pipeline state
  pipelineJobId = '';
  pipelineStages: any[] = [];
  pipelineActive = false;
  pipelineProgress = 0;
  private pipelinePollTimer: any = null;
  private pipelineCompletedStages = new Set<string>();

  // Data
  dash: any = { total:0, positive:0, negative:0, eligible:0, avg_return:0, eligible_pct:0, last_refreshed:'-', market_status:'CLOSED' };
  rd:   any = null;
  dist: any[] = [];
  chartData:  any[] = [];          // full dataset fetched (up to 90 rows)
  chart1Data: any[] = [];          // sliced to chart1Period
  chart2Data: any[] = [];          // sliced to chart2Period
  selectedFile: File | null = null;
  uploadResult: any = null;
  refreshResult: any = null;
  uploadedStocks: any[] = [];
  removingUploadedSymbols = new Set<string>();
  uploadedTotal = 0;
  uploadedPage = 0;
  uploadedPageSize = 10;
  uploadedSortBy = 'symbol';
  uploadedSortDir: 'asc' | 'desc' = 'asc';
  uploadedLoading = false;
  uploadedDetailsLoading = false;
  portfolios: any[] = [];
  positionsBySymbol: Record<string, any> = {};
  tradeDraft: any = { portfolioId:null, symbol:'', tradeType:'BUY', qty:1, price:0 };
  bulkOrderMode: 'PAPER' | 'REAL' = 'PAPER';
  bulkTradeTime = 'NOW';
  totalTradingAmount = 100000;
  perStockAllocation = 10000;
  filterBuyerSeller = false;
  excludedCount = 0;
  // 15:15, matching top10.lock.time - the day's final ranking run is what the panel buys at.
  trendBuyTime = '15:15';
  // 15:00 on the next trading day - fifteen minutes before the 15:15 that buys the next
  // position, so a sell and a buy never land on the same minute.
  trendSellTime = '15:00';
  trendHoldDays = 1;
  trendDays = 30;                        // number of days to back-test (spec §7)
  trendPeriod = '1M';                    // Time selection for the Avg Return Trend panel
  trendCustomDate = '';
  // The panel opens on the back-test: thirty bars of Top 10 bought at 15:20 and sold the next
  // trading day at 10:00. Thirty rather than sixty so the chart fills from a month of history
  // instead of a quarter. The closed-batch view stays available from the Time selector's Paper.
  trendMode: 'closed' | 'backtest' | 'range' = 'backtest';

  // Low-priced basket: the same strategy measured over stocks under a price ceiling.
  lowFrom = '';
  lowTo = '';
  lowTrend: any[] = [];
  lowTop: any = null;
  lowTopLoading = false;
  /** Same two payloads for the main trend, on the two-condition entry test. */
  mainTop: any = null;
  mainProvisional: any = null;
  /** The list as it stands right now - what would be frozen if the cut-off were this minute. */
  provisional: any = null;
  lowResult: any = null;
  lowRefreshing = false;

  // "RSI 45 ... with Volume": the same crossing, with the 20-day volume test applied as well.
  // Separate state rather than a flag on the section above, because both are on screen at once
  // and each keeps its own window, holding day and results.
  volFrom = '';
  volTo = '';
  volTrend: any[] = [];
  volTop: any = null;
  volTopLoading = false;
  volProvisional: any = null;
  volResult: any = null;
  volRefreshing = false;
  volHoldingDay = 1;
  // Historical Trade Analysis (BUYER / SELLER)
  htaDirection: 'BUY' | 'SELL' = 'BUY';
  htaPrimaryDate = '';
  // A bar start. Candles are hourly from the 09:15 open, so the last one is 15:15 - and the
  // EXACT price rule matches inside a single minute, so an off-grid time resolves to nothing.
  htaPrimaryTime = '15:15';
  htaSecondaryDate = '';
  htaSecondaryTime = '10:00';
  htaCapital = 100000;
  htaPriceRule = 'NEXT';
  htaResult: any = null;
  htaError = '';
  htaLoading = false;
  htaSelected: Record<string, boolean> = {};
  rangeFrom = '';
  rangeTo = '';
  rangeResult: any = null;

  // Trade Batch state
  tradeBatches: any[] = [];
  tradeBatchSummary: any = {};
  tradeBatchTrend: any[] = [];
  tradeBatchLoading = false;
  tradeBatchCreating = false;
  tradeBatchSelling = '';
  tradeBatchFilter = 'ALL';
  expandedBatchId = '';

  // Stale Previous Close diagnostic
  staleLoading = false;
  staleResult: any = null;

  // Time Selection (spec §2) — named period drives DB-only historical buy pricing
  // '1D', not 'NOW'. NOW resolves to TODAY, so before 15:20 the panel asked for a cut-off price
  // that has not happened yet and every row showed "No price" - which reads as broken data rather
  // than as a time that has not arrived. 1D is the last completed cut-off: the same frozen list the
  // trend section below shows, so the two agree instead of contradicting each other.
  /**
   * This section's trading rule, fixed rather than chosen.
   *
   * <p>Buy at 15:20 on the previous trading day, sell at 11:00 on the next. Deliberately NOT
   * the 15:15/15:00 the RSI 45 section uses - these are two different strategies that happen to
   * share a screen, and tying them to one global setting made a change to either silently move
   * the other.
   */
  readonly PREV_BUY_TIME  = '15:15';
  readonly PREV_SELL_TIME = '10:15';

  prevPeriod = '1D';
  prevCustomDate = '';
  prevBuyTime = '15:15';                 // fixed - see PREV_BUY_TIME
  /**
   * The times a stored candle actually starts at: 09:15 → 15:15, one per hour.
   *
   * This listed 09:00 → 15:30 in 5-minute steps, which was right while candles were 5 minutes
   * wide. Candles are now hourly and anchored on the 09:15 open (livecandle.interval.minutes),
   * so 66 of the 79 options it offered no longer correspond to any bar - and picking one did not
   * fail visibly, it silently priced off the NEXT bar up to 59 minutes later. Offer only real
   * bar starts. Keep in step with livecandle.interval.minutes if that is ever changed again.
   */
  readonly CANDLE_INTERVAL_MINUTES = CANDLE_INTERVAL_MINUTES;
  buyTimeOptions: string[] = (() => {
    const out: string[] = [];
    // mins < close, not <= : a bar that would start exactly at the close is not a bar. The last
    // one is 15:15, which is short (15 minutes) but real, and is the buy leg's time.
    for (let mins = SESSION_OPEN_MINUTES; mins < SESSION_CLOSE_MINUTES; mins += CANDLE_INTERVAL_MINUTES) {
      out.push(`${String(Math.floor(mins / 60)).padStart(2, '0')}:${String(mins % 60).padStart(2, '0')}`);
    }
    return out;
  })();
  // Sell leg of the Previous Top 10 back-test. NOW keeps the original
  // mark-to-market behaviour; any other value prices the sell from history.
  // The sell leg is the NEXT trading day, never "now" - a position bought yesterday at 15:20
  // is closed at 11:00 today, and the server skips weekends and holidays when resolving it.
  prevSellPeriod = '1D';
  prevSellCustomDate = '';
  prevSellTime = '10:15';                // fixed - see PREV_SELL_TIME
  previewLoading = false;
  periodPreview: any = null;
  todayIso = new Date().toISOString().slice(0, 10);
  prevPeriodOptions = [
    { value: 'PAPER',  label: 'Paper' },
    { value: 'NOW',    label: 'Now' },
    { value: '1D',     label: '1 Day' },
    { value: '2D',     label: '2 Days' },
    { value: '3D',     label: '3 Days' },
    { value: '5D',     label: '5 Days' },
    { value: '1W',     label: '1 Week' },
    { value: '2W',     label: '2 Weeks' },
    { value: '1M',     label: '1 Month' },
    { value: '60D',    label: '60 Days' },
    { value: 'CUSTOM', label: 'Custom Date' },
  ];
  /** Same days as the buy side minus Paper, which has no historical sell price. */
  prevSellPeriodOptions = [
    { value: 'NOW',    label: 'Now (live)' },
    { value: '1D',     label: '1 Day' },
    { value: '2D',     label: '2 Days' },
    { value: '3D',     label: '3 Days' },
    { value: '5D',     label: '5 Days' },
    { value: '1W',     label: '1 Week' },
    { value: '2W',     label: '2 Weeks' },
    { value: '1M',     label: '1 Month' },
    { value: '60D',    label: '60 Days' },
    { value: 'CUSTOM', label: 'Custom Date' },
  ];
  chart1Period = 10;               // currently selected period for Chart 1
  chart2Period = 10;               // currently selected period for Chart 2
  showConfigPanel = false;
  weightConfigs: any[] = [];
  configLoading = false;
  configSaving = false;
  configError = '';
  configSuccess = '';

  // Upload Management
  uploadStats: any = { total_uploaded:0, today_upload:0, total_batches:0, deleted_stocks:0, active_stocks:0, failed_uploads:0, duplicate_total:0, latest_upload_time:'-' };
  uploadHistory: any[] = [];
  showUploadHistory = false;
  selectedSymbols = new Set<string>();
  deletingBatch = '';
  uploadSearch = '';
  private searchDebounce: any;

  // Available period options â€” 5, 10, 20, 30, 60, 90
  readonly chartPeriods = [5, 10, 15, 20, 25, 30, 60, 90];
  readonly hardcodedReturnRanges = [
    'Below -15%',
    '-15% to -10%',
    '-10% to -5%',
    '-5% to 0%',
    '0% to 5%',
    '5% to 10%',
    '10% to 15%',
    '15% to 30%',
    'Above 30%'
  ];

  readonly Math = Math;
  private timer: any;
  private uploadedRequestId = 0;
  private destroyed = false;
  liveStatus: any = {};
  private liveSub?: Subscription;
  private statusSub?: Subscription;

  // Donut segments for sector
  donutSegments: any[] = [];
  distDonutSegments: any[] = [];
  distPositiveLine: any[] = [];
  distNegativeLine: any[] = [];

  readonly SECTOR_COLORS = ['#3b82f6','#22c55e','#f59e0b','#ef4444','#8b5cf6','#06b6d4','#ec4899','#14b8a6','#f97316','#84cc16'];
  readonly DIST_COLORS   = ['#1e3a5f','#1e4d2b','#16a34a','#4ade80','#60a5fa','#f59e0b','#ef4444','#dc2626','#7f1d1d'];

  constructor(private api: ApiService, public auth: AuthService, private http: HttpClient, private livePrices: LivePriceService) {}

  ngOnInit() {
    this.destroyed = false;
    this.loadAll();
    this.timer = setInterval(() => {
      this.loadDashboard();
      // Uploaded Stock Details was missing from this cycle entirely - it was only ever loaded
      // by loadAll() on init, so its Last Update column sat at the page-load time until the
      // whole page was reloaded, however often prices actually moved behind it. forceReload
      // skips the 2-minute page cache; silent keeps the pager usable while it refreshes.
      // Keeps whatever view is on screen - passing false here would strip the signal
      // columns out from under the reader every five minutes.
      this.loadUploadedStocks(true, this.uploadedWithSignals, true);
      // Skip while the user's own refresh is running, so the two cannot interleave.
      if (!this.manualTop10Refresh) this.loadRanking();
      this.loadChartData();
      // Refresh Previous Top 10 (live held-batch returns) every cycle.
      this.loadTradeBatches();
      // Only auto-refresh the trend when showing the closed-batch view; don't clobber
      // a back-test the user is currently viewing.
      if (this.trendMode === 'closed') this.loadTradeBatchTrend();
    }, 5 * 60 * 1000);
    this.top10Timer = setInterval(() => {
      // Only the Current Top 10 auto-refreshes, and only when the toggle is on.
      if (this.manualTop10Refresh) return;
      if (this.automation.top10_auto_refresh_enabled && this.dash.market_status === 'OPEN') this.loadRanking();
      // Keep the crossing-entry panel current too, but only once it has been loaded - refreshing a
      // section the user has never opened would run the whole identify/rank/signal pass for nothing.
      if (this.lowTop && this.dash.market_status === 'OPEN') this.loadBelowPriceTop10();
      if (this.volTop && this.dash.market_status === 'OPEN') this.loadVolumeTop10();
      if (this.mainTop && this.dash.market_status === 'OPEN') this.loadMainTop10();
    }, 2 * 60 * 1000);
    this.livePrices.connect();
    this.liveSub = this.livePrices.quote$.subscribe(q => this.applyLiveQuote(q));
    this.statusSub = this.livePrices.status$.subscribe(s => this.liveStatus = s || {});
    // Server-pushed Top 10 diff: animate only changed rows, else just move the timestamp.
    this.top10Sub = this.livePrices.top10$.subscribe(p => this.applyTop10Update(p));
    this.loadAutomationConfig();
  }
  ngOnDestroy() {
    this.destroyed = true;
    this.uploadedRequestId++;
    this.uploadedLoading = false;
    this.uploadedDetailsLoading = false;
    if (this.timer) clearInterval(this.timer);
    if (this.top10Timer) clearInterval(this.top10Timer);
    if (this.prevAutoTimer) clearInterval(this.prevAutoTimer);
    this.stopJobPolling();
    this.stopPipelinePolling();
    this.liveSub?.unsubscribe();
    this.statusSub?.unsubscribe();
    this.top10Sub?.unsubscribe();
    if (this.flashTimer) clearTimeout(this.flashTimer);
    this.livePrices.release();
  }

  liveFeedTone(): 'good' | 'warn' | 'bad' {
    if (this.hasDhanCredentialIssue()) return 'bad';
    if (this.isMarketClosedLivePause()) return 'warn';
    if (this.liveStatus?.lastApiError) return 'bad';
    if ((this.liveStatus?.missingMappingCount || 0) > 0) return 'warn';
    if (this.liveStatus?.connectionStatus === 'CONNECTED' && this.liveStatus?.webSocketStatus === 'OPEN') return 'good';
    if (this.liveStatus?.connectionStatus === 'DISCONNECTED' || this.liveStatus?.webSocketStatus === 'ERROR') return 'bad';
    return 'warn';
  }

  connectionTone(): 'good' | 'warn' | 'bad' {
    const status = String(this.liveStatus?.connectionStatus || '').toUpperCase();
    if (status === 'CONNECTED') return 'good';
    if (status === 'MARKET_CLOSED') return 'warn';
    if (status === 'DISCONNECTED') return 'bad';
    return 'warn';
  }

  apiTone(): 'good' | 'warn' | 'bad' {
    const status = String(this.liveStatus?.apiStatus || '').toUpperCase();
    if (status === 'AUTHENTICATION_SUCCESS') return 'good';
    if (status === 'READY') return 'warn';
    if (status.includes('FAILED') || status.includes('EXPIRED')) return 'bad';
    return 'warn';
  }

  websocketTone(): 'good' | 'warn' | 'bad' {
    const status = String(this.liveStatus?.webSocketStatus || '').toUpperCase();
    if (status === 'OPEN') return 'good';
    if (status === 'PAUSED') return 'warn';
    if (status === 'ERROR' || status === 'CLOSED') return 'bad';
    return 'warn';
  }

  liveFeedTitle(): string {
    if (this.hasDhanCredentialIssue()) return 'Action Required';
    if (this.isMarketClosedLivePause()) return 'Market Closed';
    if (this.liveStatus?.lastApiError) return 'Action Required';
    if ((this.liveStatus?.missingMappingCount || 0) > 0) return 'Live with gaps';
    if (this.liveStatus?.connectionStatus === 'CONNECTED' && this.liveStatus?.webSocketStatus === 'OPEN') return 'Live';
    if (this.liveStatus?.connectionStatus === 'RECONNECTING') return 'Reconnecting';
    if (this.liveStatus?.connectionStatus === 'CONNECTING') return 'Connecting';
    return 'Waiting';
  }

  liveFeedHelpText(): string {
    if (this.hasDhanCredentialIssue()) return 'Dhan broker API is not connected. Reconnect Dhan from Broker settings, then refresh prices.';
    if (this.isMarketClosedLivePause()) return 'Live streaming is paused after market close. Stock Tracker uses the latest saved LTP and daily close.';
    if (this.liveStatus?.lastApiError) return 'Live prices are not flowing. Check the message below.';
    if ((this.liveStatus?.missingMappingCount || 0) > 0) return 'Live prices are flowing for mapped symbols. Some symbols need Dhan mapping.';
    if (this.liveStatus?.lastTickTime) return 'Current Price uses Dhan LTP and updates automatically.';
    if (this.liveStatus?.connectionStatus === 'CONNECTED') return 'Connected to Dhan. Waiting for the next market tick.';
    if (this.liveStatus?.connectionStatus === 'RECONNECTING') return 'Connection dropped. The app is reconnecting automatically.';
    return 'Preparing Dhan live price connection.';
  }

  friendlyConnection(): string {
    const status = String(this.liveStatus?.connectionStatus || '').toUpperCase();
    if (status === 'CONNECTED') return 'Connected';
    if (status === 'CONNECTING') return 'Connecting';
    if (status === 'RECONNECTING') return 'Reconnecting';
    if (status === 'MARKET_CLOSED') return 'Market closed';
    if (status === 'DISCONNECTED') return 'Disconnected';
    return 'Checking';
  }

  friendlyApiStatus(): string {
    const status = String(this.liveStatus?.apiStatus || '').toUpperCase();
    if (status === 'AUTHENTICATION_SUCCESS') return 'Authenticated';
    if (status === 'AUTHENTICATION_FAILED') return 'Not connected';
    if (status === 'TOKEN_EXPIRED') return 'Token expired';
    if (status === 'READY') return 'Ready';
    if (status === 'FAILED') return 'Failed';
    if (status === 'UNKNOWN') return 'Checking';
    return this.titleCaseStatus(status || 'Checking');
  }

  friendlyWebSocketStatus(): string {
    const status = String(this.liveStatus?.webSocketStatus || '').toUpperCase();
    if (status === 'OPEN') return 'Streaming';
    if (status === 'OPENING') return 'Opening';
    if (status === 'PAUSED') return 'Paused';
    if (status === 'NO_SYMBOLS') return 'No symbols';
    if (status === 'CLOSED') return 'Closed';
    if (status === 'ERROR') return 'Error';
    return this.titleCaseStatus(status || 'Checking');
  }

  friendlyLiveError(): string {
    const error = String(this.liveStatus?.lastApiError || '');
    if (error.includes('WebSocketHandshakeException')) return 'Dhan rejected the live feed connection. This usually happens after market close or when live feed access is not enabled for the Dhan account.';
    if (error.includes('Permission denied')) return 'Dhan WebSocket cannot be reached from this network/session. Check internet, firewall, or broker access.';
    if (error.toLowerCase().includes('expired')) return 'Dhan token expired. Reconnect Dhan from Broker settings.';
    if (error.toLowerCase().includes('no mapped')) return 'No active Stock Tracker symbols have a Dhan Security ID mapping.';
    return error;
  }

  isMarketClosedLivePause(): boolean {
    return String(this.liveStatus?.connectionStatus || '').toUpperCase() === 'MARKET_CLOSED'
      || String(this.liveStatus?.webSocketStatus || '').toUpperCase() === 'PAUSED';
  }

  hasDhanCredentialIssue(): boolean {
    const status = String(this.liveStatus?.apiStatus || '').toUpperCase();
    return status === 'AUTHENTICATION_FAILED' || status === 'TOKEN_EXPIRED';
  }

  missingMappingText(): string {
    const symbols = Array.isArray(this.liveStatus?.missingMappingSymbols) ? this.liveStatus.missingMappingSymbols : [];
    if (!symbols.length) return 'details loading';
    const extra = Math.max(0, (this.liveStatus?.missingMappingCount || 0) - symbols.length);
    return symbols.join(', ') + (extra ? `, +${extra} more` : '');
  }

  toneColor(tone: 'good' | 'warn' | 'bad'): string {
    return tone === 'good' ? '#22c55e' : tone === 'bad' ? '#ef4444' : '#f59e0b';
  }

  toneText(tone: 'good' | 'warn' | 'bad'): string {
    return tone === 'good' ? '#4ade80' : tone === 'bad' ? '#fca5a5' : '#fde68a';
  }

  toneBackground(tone: 'good' | 'warn' | 'bad'): string {
    return tone === 'good' ? '#123a28' : tone === 'bad' ? '#450a0a' : '#3a2608';
  }

  toneBorder(tone: 'good' | 'warn' | 'bad'): string {
    return tone === 'good' ? '1px solid #15803d' : tone === 'bad' ? '1px solid #dc2626' : '1px solid #d97706';
  }

  toneShadow(tone: 'good' | 'warn' | 'bad'): string {
    return tone === 'good' ? '0 0 0 4px rgba(34,197,94,.14)' : tone === 'bad' ? '0 0 0 4px rgba(239,68,68,.16)' : '0 0 0 4px rgba(245,158,11,.16)';
  }

  private titleCaseStatus(status: string): string {
    return status
      .toLowerCase()
      .split('_')
      .filter(Boolean)
      .map(part => part.charAt(0).toUpperCase() + part.slice(1))
      .join(' ');
  }

  /**
   * Fills the crossing-entry panel on first load.
   *
   * <p>It used to appear blank until Run was pressed, which reads as broken rather than as waiting -
   * every other panel on this screen populates itself. The date range defaults to the last 30 days
   * so there is something to show immediately; the user can narrow it afterwards.
   */
  private initBelowPricePanel() {
    if (!this.lowTo) this.lowTo = new Date().toISOString().slice(0, 10);
    if (!this.lowFrom) this.lowFrom = new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);
    this.loadBelowPriceTop10();   // crossing panel table
    this.runLowPriceTrend();      // crossing panel bars
    this.loadVolumeTop10();       // crossing + volume panel table
    this.runVolumeTrend();        // crossing + volume panel bars
    this.loadMainTop10();         // main trend table + provisional
  }

  /** ATR % at or above this is highlighted - the same 3% the ATR crossover screen filters on. */
  atrHighlightPct = 3.0;

  /**
   * The RSI 45 section's own export. It used to call downloadChartTrades, which fetches the
   * bar-chart dataset over the OTHER section's date range - a different set of rows entirely,
   * which is why the button appeared to do nothing useful. This asks the server for exactly the
   * list on screen, with the same parameters the panel was loaded with.
   */
  downloadRsi45Section() {
    this.downloadCsv('/tracker/export/rsi45-section?requireVolume=false&applyRanking=false',
                     'TradeRakshak_RSI45_Previous_Close_Less_Base_Price.csv');
  }

  loadAll() { this.initBelowPricePanel(); this.loadDashboard(); this.loadRanking(); this.loadChartData(); this.loadPositions(); this.loadPortfolios(); this.loadUploadedStocks(); this.recalcAllocation(); this.loadUploadStats(); this.loadTradeBatches(); this.runBacktestTrend(); this.loadPeriodPreview(); }

  loadDashboard() {
    this.api.get<any>('/tracker/dashboard').subscribe({ next: r => {
      const d = r.data || {};
      this.dash = {
        total:   d.total_stocks   || 0,
        positive:d.positive_stocks|| 0,
        negative:d.negative_stocks|| 0,
        eligible:d.eligible_stocks|| 0,
        avg_return: d.avg_return  || 0,
        last_refreshed: d.last_refreshed || '-',
        market_status:  d.market_status  || 'CLOSED'
      };
    }});
  }

  loadRanking() {
    this.api.get<any>('/tracker/ranking/latest').subscribe({ next: r => {
      // The banner means "the refresh call failed", so a call that SUCCEEDED must always clear it -
      // including one that succeeded and found nothing. Previously it was only cleared when the
      // response carried no message, so a legitimate "no stock met the entry rules" answer left
      // "Unable to refresh latest Top 10 Stocks" on screen indefinitely, blaming a fetch that had
      // in fact worked. With identification now able to return zero candidates on a quiet day,
      // that became a likely state rather than a rare one.
      this.top10RefreshError = false;
      if (r.data) {
        this.rd = r.data;
        this.buildSectorDonut();
        const now = new Date().toISOString();
        this.currentTop10LastUpdated = now;
        this.previousTop10LastUpdated = now;
      }
      if (!this.manualTop10Refresh) this.top10Refreshing = false;
      this.prevTop10Refreshing = false;
    }, error: () => {
      if (!this.manualTop10Refresh) this.top10Refreshing = false;
      this.prevTop10Refreshing = false;
      this.top10RefreshError = true;
    }});
  }

  /**
   * Re-runs the full ranking and reloads the panel. The button stays frozen for the whole
   * round trip - scoring on the server, then the reload - and a repeat click while it is
   * running is ignored rather than queued.
   */
  refreshTop10() {
    if (this.top10Refreshing) return;
    this.top10Refreshing = true;
    this.manualTop10Refresh = true;
    this.top10RefreshError = false;
    this.ranking.set(true);
    this.api.post<any>('/tracker/rank', {}).subscribe({
      next: r => {
        if (r.data) this.rd = r.data;
        this.ranking.set(false);
        this.finishTop10Refresh();
      },
      error: () => {
        this.ranking.set(false);
        this.top10RefreshError = true;
        this.finishTop10Refresh();
      }
    });
  }

  /** Reloads the panel, then releases the button - never before. */
  private finishTop10Refresh(): void {
    this.api.get<any>('/tracker/ranking/latest').subscribe({
      next: r => {
        if (r.data && !r.data.message) {
          this.rd = r.data;
          this.buildSectorDonut();
          const now = new Date().toISOString();
          this.currentTop10LastUpdated = now;
          this.previousTop10LastUpdated = now;
        }
        this.manualTop10Refresh = false;
        this.top10Refreshing = false;
      },
      error: () => {
        this.manualTop10Refresh = false;
        this.top10Refreshing = false;
        this.top10RefreshError = true;
      }
    });
  }

  // ── Server-pushed Current Top 10 diff (SSE) ──────────────────────────────
  applyTop10Update(p: any): void {
    if (!p) return;
    // Always refresh the "Last Updated" label.
    if (p.last_updated) this.currentTop10LastUpdated = p.last_updated;
    if (p.no_change) return;                      // no redraw — timestamp only
    // Patch only changed rows in place; flash them.
    const changed: any[] = Array.isArray(p.changed) ? p.changed : [];
    if (this.rd && Array.isArray(this.rd.current_top10) && changed.length) {
      const bySym: Record<string, any> = {};
      for (const c of changed) bySym[c.symbol] = c;
      this.rd.current_top10 = (p.top10 && p.top10.length) ? p.top10 : this.rd.current_top10.map((row: any) =>
        bySym[row.symbol] ? { ...row, ...bySym[row.symbol] } : row);
      this.flashRows(changed.map(c => c.symbol));
    } else if (p.top10 && p.top10.length && this.rd) {
      this.rd.current_top10 = p.top10;
      this.flashRows(p.top10.map((c: any) => c.symbol));
    }
  }

  private flashRows(symbols: string[]): void {
    this.changedTop10Symbols = new Set(symbols);
    if (this.flashTimer) clearTimeout(this.flashTimer);
    this.flashTimer = setTimeout(() => { this.changedTop10Symbols = new Set(); }, 1500);
  }

  rowFlash(symbol: string): boolean { return this.changedTop10Symbols.has(symbol); }

  // ── Automation config (auto-refresh + auto buy/sell toggles) ─────────────
  loadAutomationConfig(): void {
    this.api.get<any>('/tracker/automation/config').subscribe({
      next: r => {
        if (r.data) this.automation = { ...this.automation, ...r.data };
        // Start (or stop) the Previous Top 10 timer to match the saved setting.
        this.restartPrevAutoRefresh();
      },
      error: () => {}
    });
  }

  toggleAutomationPanel(): void { this.showAutomationPanel = !this.showAutomationPanel; }
  togglePrevAutomationPanel(): void { this.showPrevAutomationPanel = !this.showPrevAutomationPanel; }

  /**
   * Previous Top 10 and its average-return bars are one view of the same numbers, so they
   * refresh together - a bar chart showing a different average from the table above it is
   * worse than no chart. Works outside market hours: the preview reads stored prices.
   */
  restartPrevAutoRefresh(): void {
    if (this.prevAutoTimer) { clearInterval(this.prevAutoTimer); this.prevAutoTimer = null; }
    if (!this.automation.prevtop10_auto_refresh_enabled) return;
    const secs = Math.max(30, Number(this.automation.prevtop10_auto_refresh_interval_sec) || 300);
    this.prevAutoTimer = setInterval(() => {
      if (this.destroyed) return;
      this.loadPeriodPreview();
      this.runBacktestTrend();
    }, secs * 1000);
  }

  onLiveModeChange(): void {
    // Guard: switching to LIVE (real orders) requires explicit confirmation.
    if (this.automation.prevtop10_execution_mode === 'LIVE') {
      const ok = confirm(
        'Enable LIVE execution mode?\n\nWhen the auto buy/sell cycle runs in LIVE mode it can place REAL broker orders using real money. ' +
        'Leave this on PAPER unless you fully understand the risk. Continue with LIVE?');
      if (!ok) { this.automation.prevtop10_execution_mode = 'PAPER'; }
    }
  }

  saveAutomationConfig(): void {
    this.automationSaving = true;
    this.api.put<any>('/tracker/automation/config', this.automation).subscribe({
      next: r => { if (r.data) this.automation = { ...this.automation, ...r.data }; this.automationSaving = false; },
      error: () => { this.automationSaving = false; }
    });
  }

  loadDist() {
    this.api.get<any>('/tracker/distribution').subscribe({ next: r => {
      this.dist = r.data?.distribution || [];
      this.distPositiveLine = r.data?.positive_line || [];
      this.distNegativeLine = r.data?.negative_line || [];
      this.buildDistDonut();
    }});
  }

  loadChartData() {
    this.api.get<any>('/tracker/ranking/history', { days: 90 }).subscribe({
      next: r => {
        this.chartData = r.data || [];
        this.sliceChartData();
        this.trendLastUpdated = new Date().toISOString();
        this.trendRefreshing = false;
      },
      error: () => { this.trendRefreshing = false; }
    });
  }

  loadPositions() {
    this.api.get<any>('/tracker/positions/summary').subscribe({
      next: r => this.positionsBySymbol = r.data?.positionsBySymbol || {},
      error: () => this.positionsBySymbol = {}
    });
  }

  loadPortfolios() {
    this.api.get<any>('/enterprise/portfolios').subscribe({
      next: r => {
        this.portfolios = r.data || [];
        if (!this.tradeDraft.portfolioId && this.portfolios.length) {
          this.tradeDraft.portfolioId = this.portfolios[0][0];
        }
      },
      error: () => this.portfolios = []
    });
  }

  hasPosition(symbol: string): boolean {
    const pos = this.positionsBySymbol[(symbol || '').toUpperCase()];
    return !!pos && Number(pos.qty || 0) > 0;
  }

  isMarketHours(): boolean {
    const now = new Date();
    const h = now.getHours(), m = now.getMinutes();
    const mins = h * 60 + m;
    return mins >= 555 && mins <= 930; // 9:15 AM to 3:30 PM IST
  }

  refreshPreviousTop10(): void {
    this.prevTop10Refreshing = true;
    this.ranking.set(true);
    this.api.post<any>('/tracker/rank', {}).subscribe({
      next: r => {
        if (r.data) this.rd = r.data;
        this.ranking.set(false);
        this.loadRanking();
        this.loadChartData();
      },
      error: () => {
        this.ranking.set(false);
        this.prevTop10Refreshing = false;
        this.loadRanking();
      }
    });
  }

  bulkPrevTop10Order(side: 'BUY' | 'SELL'): void {
    const rows = this.rd?.previous_top10 || [];
    if (!rows.length) {
      alert('Previous Top 10 is not available yet.');
      return;
    }
    if (!this.isMarketHours()) {
      alert('Market is closed. Bulk trading is available only during market hours (9:15 AM - 3:30 PM IST). Paper trades will be logged.');
    }
    const ok = confirm(`${side} all Previous Top 10 stocks?\n\nThis will place ${side === 'BUY' ? 'buy' : 'sell'} orders for all 10 previous top stocks using Paper trading.\n\nAllocation: ₹${this.perStockAllocation}/stock.`);
    if (!ok) return;
    this.bulkTrading.set(true);
    this.api.post<any>('/trading/stock-tracker/bulk-order', {
      confirmed: true,
      side,
      mode: 'PAPER',
      source: 'PREVIOUS_TOP10',
      investmentAmountPerStock: this.perStockAllocation,
      tradeTime: 'NOW',
      exchange: 'NSE',
      segment: 'EQ-SWG',
      productType: 'CNC',
      orderType: 'MARKET'
    }).subscribe({
      next: r => {
        this.bulkTrading.set(false);
        const data = r.data || r;
        alert(`${side} All 10 completed.\nSuccess: ${data.success_count || 0}\nFailed: ${data.failed_count || 0}`);
        this.loadPositions();
      },
      error: e => {
        this.bulkTrading.set(false);
        alert(e.error?.message || `${side} All 10 failed.`);
      }
    });
  }

  // Trade Batch methods
  loadTradeBatches() {
    this.tradeBatchLoading = true;
    this.api.get<any>('/tracker/trade-batches', { status: this.tradeBatchFilter, limit: 50 }).subscribe({
      next: r => {
        const data = r.data || {};
        this.tradeBatches = data.batches || [];
        this.tradeBatchSummary = data.summary || {};
        this.tradeBatchLoading = false;
      },
      error: () => { this.tradeBatches = []; this.tradeBatchLoading = false; }
    });
  }

  loadTradeBatchTrend() {
    this.trendMode = 'closed';
    this.trendRefreshing = true;
    // One bar per real trading day, from that day's official Top 10 ranking.
    //
    // This used to read /trade-batches/trend, which only returns batches created by hand with
    // Buy All 10 / Sell All 10. With none ever created the chart drew nothing at all, while the
    // daily rankings had returns stored the whole time. The daily endpoint takes one run per day
    // - the 15:20 locked snapshot where there is one - and omits days whose return could not be
    // computed rather than plotting them as zero.
    this.api.get<any>('/tracker/daily-ranking-trend', { limit: 90 }).subscribe({
      next: r => { this.tradeBatchTrend = r.data || []; this.trendRefreshing = false; },
      error: () => { this.tradeBatchTrend = []; this.trendRefreshing = false; }
    });
  }

  // Spec §7: configurable back-test using actual stored intraday prices.
  /**
   * Replay the Current Top 10 across a date range. Rows come back carrying trade_date and
   * avg_return_pct, the two fields the bars already read, so the same chart renders the
   * range. Stored data only - no live feed - so this works outside market hours.
   */
  /** Buyer opens with a buy, seller opens with a sell - the primary leg is the opening one. */
  htaPrimaryLabel(): string { return this.htaDirection === 'BUY' ? 'Buy' : 'Sell'; }
  htaSecondaryLabel(): string { return this.htaDirection === 'BUY' ? 'Sell' : 'Buy'; }

  /** Switching direction invalidates every number on screen, so clear rather than keep stale. */
  onHtaDirectionChange(): void {
    this.htaResult = null;
    this.htaError = '';
    this.htaSelected = {};
  }

  htaToggle(sym: string): void {
    this.htaSelected[sym] = this.htaSelected[sym] === false;
    this.runHistoricalTrade();
  }
  htaSelectAll(on: boolean): void {
    for (const s of (this.htaResult?.stocks || [])) this.htaSelected[s.symbol] = on;
    this.runHistoricalTrade();
  }

  htaBarColor(v: any): string {
    const n = Number(v);
    if (!isFinite(n) || n === 0) return '#94a3b8';
    return n > 0 ? '#22c55e' : '#ef4444';
  }
  /** Full width at 10% either way - beyond that the bar simply saturates. */
  htaBarWidth(v: any): string {
    const n = Math.min(Math.abs(Number(v) || 0), 10);
    return Math.round((n / 10) * 100) + '%';
  }

  /**
   * Every figure is recalculated on the backend from stored data; nothing on this screen is
   * trusted as an input to the maths. The button stays disabled while a call is in flight so
   * repeated clicks cannot stack duplicate requests.
   */
  runHistoricalTrade(): void {
    if (this.htaLoading) return;
    if (!this.htaDirection) { this.htaError = 'Please select a Trade Direction (Buyer or Seller).'; return; }
    if (!this.htaPrimaryDate || !this.htaPrimaryTime) {
      this.htaError = 'Please select all required Date and Time fields.'; return;
    }
    const picked = Object.keys(this.htaSelected).filter(k => this.htaSelected[k] !== false);
    const anyDeselected = Object.values(this.htaSelected).some(v => v === false);
    this.htaLoading = true;
    this.htaError = '';
    this.api.post<any>('/tracker/historical-trade', {
      trade_direction: this.htaDirection,
      primary_date: this.htaPrimaryDate,
      primary_time: this.htaPrimaryTime,
      secondary_date: this.htaSecondaryDate || null,
      secondary_time: this.htaSecondaryDate ? this.htaSecondaryTime : null,
      capital: Number(this.htaCapital) || 0,
      price_rule: this.htaPriceRule,
      symbols: anyDeselected ? picked : null
    }).subscribe({
      next: r => {
        const d = r.data || {};
        this.htaLoading = false;
        if (d.success === false) { this.htaResult = null; this.htaError = d.error || 'Calculation failed.'; return; }
        this.htaResult = d;
      },
      error: (e: any) => {
        this.htaLoading = false;
        this.htaResult = null;
        // An expired session used to surface as "unable to retrieve historical market
        // data", which sends you looking for a data problem that does not exist.
        const status = e?.status;
        if (status === 401 || status === 403) {
          this.htaError = 'Your session has expired. Please sign in again.';
        } else if (status === 0) {
          this.htaError = 'Cannot reach the server. Check that the application is running.';
        } else {
          this.htaError = e?.error?.message
            || 'Unable to retrieve historical market data. Please try again.';
        }
      }
    });
  }

  /**
   * The single Run & Refresh top 10 action.
   *
   * A filled From/To pair is an explicit instruction and wins; with the dates empty it falls back
   * to the Time selection. That keeps both windows reachable without asking which of two buttons
   * corresponds to which control.
   */
  runAndRefreshTop10(): void {
    if (this.rangeFrom && this.rangeTo) this.runRangeBacktest();
    else this.runBacktestTrend();
  }

  runRangeBacktest() {
    if (!this.rangeFrom || !this.rangeTo) return;
    this.trendMode = 'range';
    this.loadReconstructedTrend(this.rangeFrom, this.rangeTo);
  }

  runBacktestTrend() {
    this.trendMode = 'backtest';
    const to = new Date();
    const from = new Date(to.getTime() - (Number(this.trendDays) || 30) * 86400000);
    this.loadReconstructedTrend(from.toISOString().slice(0, 10), to.toISOString().slice(0, 10));
  }

  /** Which holding day the chart is showing. Day 1 is the first trading session after entry. */
  lowHoldingDay = 1;
  readonly holdingDayChoices = [1, 2, 3, 4, 5];

  setHoldingDay(d: number): void { this.lowHoldingDay = d; }
  setVolHoldingDay(d: number): void { this.volHoldingDay = d; }

  get volHoldingBars(): any[] {
    return (this.volTrend || []).map(d => {
      const rs = (d.trades || [])
        .map((t: any) => this.tradeDayReturn(t, this.volHoldingDay))
        .filter((v: number | null) => v != null) as number[];
      const avg = rs.length ? rs.reduce((a, b) => a + b, 0) / rs.length : null;
      return { ...d, day_return: avg, day_trades: rs.length };
    });
  }

  get volHoldingMax(): number {
    const vals = this.volHoldingBars.map(b => Math.abs(b.day_return || 0));
    return Math.max(0.1, ...vals);
  }

  get volHoldingStats(): any {
    const rs: number[] = [];
    for (const d of this.volTrend || []) {
      for (const t of (d.trades || [])) {
        const v = this.tradeDayReturn(t, this.volHoldingDay);
        if (v != null) rs.push(v);
      }
    }
    if (!rs.length) {
      return { trades: 0, wins: 0, losses: 0, winRate: 0, avg: 0, median: 0, profitFactor: 0 };
    }
    const wins = rs.filter(v => v > 0), losses = rs.filter(v => v <= 0);
    const gain = wins.reduce((a, b) => a + b, 0);
    const pain = Math.abs(losses.reduce((a, b) => a + b, 0));
    const sorted = [...rs].sort((a, b) => a - b);
    const mid = Math.floor(sorted.length / 2);
    return {
      trades: rs.length,
      wins: wins.length,
      losses: losses.length,
      winRate: (wins.length / rs.length) * 100,
      avg: rs.reduce((a, b) => a + b, 0) / rs.length,
      median: sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2,
      profitFactor: pain === 0 ? null : gain / pain
    };
  }

  get volTrendMax(): number {
    const measured = this.volTrend.filter(d => !d.no_stock);
    return Math.max(0.1, ...measured.map(d => Math.abs(Number(d.avg_return_pct) || 0)));
  }

  /** Export for the volume-filtered section; same shape as the section above it. */
  downloadRsi45VolumeSection(): void { this.downloadRsi45Section(); }

  /**
   * The crossing plus the volume test, priced close-to-close.
   *
   * <p>Identical to {@link loadBelowPriceTop10} but with requireVolume=true, so the only
   * difference between the two sections on screen is the one condition their headings name.
   */
  loadVolumeTop10() {
    if (this.volTopLoading) return;
    this.volTopLoading = true;
    this.api.get<any>('/tracker/ranking/provisional-top10',
                      { requireCrossing: 'true', applyRanking: 'false' }).subscribe({
      next: r => { this.volProvisional = r?.data || null; },
      error: () => { this.volProvisional = null; }
    });
    this.api.get<any>('/tracker/ranking/below-price-top10',
                      { requireVolume: 'true', applyRanking: 'false',
                        priceAtClose: 'true' }).subscribe({
      next: r => { this.volTop = r?.data || null; this.volTopLoading = false; },
      error: () => {
        this.volTop = { top10: [], message: 'Could not build the list. Check that prices are loaded.' };
        this.volTopLoading = false;
      }
    });
  }

  /** Bars for the volume-filtered section - same population as its table, same pricing. */
  runVolumeTrend() {
    if (!this.volTopLoading) this.loadVolumeTop10();
    if (!this.volTo) this.volTo = new Date().toISOString().slice(0, 10);
    this.volRefreshing = true;
    this.api.get<any>('/tracker/ranking/reconstruct-trend', {
      ...(this.volFrom ? { from: this.volFrom } : {}),
      to: this.volTo, applyReturnBand: 'false', requireCrossing: 'true',
      applyRanking: 'false', requireVolume: 'true', priceAtClose: 'true'
    }).subscribe({
      next: r => {
        const d = r?.data || {};
        if (!this.volFrom && d.coverage_from) this.volFrom = d.coverage_from;
        this.volTrend = (d.days || [])
          .map((x: any) => ({
            no_stock:       x.trade_avg_return == null && x.trade_source !== 'open',
            is_open:        x.trade_source === 'open',
            open_return:    x.open_return_pct,
            open_priced:    x.open_priced,
            note:           x.note,
            batch_id:       'VOL-' + x.date,
            trade_date:     x.date,
            avg_return_pct: Number(x.trade_avg_return) || 0,
            winning_stocks: x.trade_winners ?? 0,
            losing_stocks:  x.trade_losers ?? 0,
            total_stocks:   x.trade_symbol_count ?? 0,
            symbols:        x.symbols || [],
            sell_date:      x.sell_date,
            trades:         x.priced || [],
            strategy:       'Top 10'
          }));
        this.volResult = {
          from_date:      this.volFrom,
          to_date:        this.volTo,
          avg_return:     d.average_return_pct ?? 0,
          days_with_data: d.measured_days ?? 0,
          winning_days:   d.positive_days ?? 0,
          losing_days:    Math.max(0, (d.measured_days ?? 0) - (d.positive_days ?? 0)),
          days_skipped:   Math.max(0, (d.trading_days ?? 0) - (d.measured_days ?? 0))
        };
        this.volRefreshing = false;
      },
      error: () => { this.volTrend = []; this.volResult = null; this.volRefreshing = false; }
    });
  }

  /** The selected day's return for one trade, or null when that day has not traded yet. */
  private tradeDayReturn(t: any, day: number): number | null {
    const v = t ? t['day' + day + '_return'] : null;
    return v == null ? null : Number(v);
  }

  /**
   * One bar per trading date, valued at the selected holding day.
   *
   * <p>The average is taken only across trades that actually have that day's price, and only
   * after each trade's own return has been computed. A missing day is left out of the average
   * rather than counted as 0%, which would pull the bar towards flat.
   */
  get lowHoldingBars(): any[] {
    return (this.lowTrend || []).map(d => {
      const rs = (d.trades || [])
        .map((t: any) => this.tradeDayReturn(t, this.lowHoldingDay))
        .filter((v: number | null) => v != null) as number[];
      const avg = rs.length ? rs.reduce((a, b) => a + b, 0) / rs.length : null;
      return { ...d, day_return: avg, day_trades: rs.length };
    });
  }

  /** Scale from the bars actually drawn, so switching day rescales the axis with it. */
  get lowHoldingMax(): number {
    const vals = this.lowHoldingBars.map(b => Math.abs(b.day_return || 0));
    return Math.max(0.1, ...vals);
  }

  /**
   * Win rate, profit factor and the rest for the selected holding day.
   *
   * <p>Computed from individual trade returns across every date - not from the per-date averages,
   * which would weight a day holding one trade the same as a day holding eight.
   */
  get lowHoldingStats(): any {
    const rs: number[] = [];
    for (const d of this.lowTrend || []) {
      for (const t of (d.trades || [])) {
        const v = this.tradeDayReturn(t, this.lowHoldingDay);
        if (v != null) rs.push(v);
      }
    }
    if (!rs.length) {
      return { trades: 0, wins: 0, losses: 0, winRate: 0, avg: 0, median: 0, profitFactor: 0 };
    }
    const wins = rs.filter(v => v > 0), losses = rs.filter(v => v <= 0);
    const gain = wins.reduce((a, b) => a + b, 0);
    const pain = Math.abs(losses.reduce((a, b) => a + b, 0));
    const sorted = [...rs].sort((a, b) => a - b);
    const mid = Math.floor(sorted.length / 2);
    return {
      trades: rs.length,
      wins: wins.length,
      losses: losses.length,
      winRate: wins.length / rs.length * 100,
      avg: rs.reduce((a, b) => a + b, 0) / rs.length,
      median: sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2,
      // No losing trade means there is nothing to divide by; report it as such rather than
      // printing Infinity or a silent 0.
      profitFactor: pain > 0 ? gain / pain : (gain > 0 ? null : 0)
    };
  }

  get lowTrendMax(): number {
    // Only measured days set the scale; a no-stock day has no magnitude to contribute.
    const measured = this.lowTrend.filter(d => !d.no_stock);
    return Math.max(0.1, ...measured.map(d => Math.abs(Number(d.avg_return_pct) || 0)));
  }

  /**
   * Runs the same rebuild as the main trend, with a price ceiling applied per day.
   *
   * <p>The ceiling is applied to each day's own close rather than today's price, so a stock that
   * has since risen above the limit still counts on the days it genuinely qualified - otherwise the
   * basket would be defined by hindsight.
   */
  /**
   * Loads the below-price Top 10 in the required order: identify, rank, then signal.
   *
   * <p>All three steps happen server-side in one call so the sequence cannot be reordered by the
   * screen, and so the table and the chart below it are always built from the same evaluation.
   */
  /**
   * The main trend's frozen list and its provisional successor.
   *
   * <p>Identical calls to the crossing panel's, with requireCrossing=false. The server runs one
   * pipeline for both, so the sections cannot drift apart as the logic changes - only the entry
   * test differs, and it differs in exactly one parameter.
   */
  loadMainTop10() {
    // priceAtClose: bought at the session's closing price and sold at the next trading day's
    // close, which is what this section states on screen.
    this.api.get<any>('/tracker/ranking/below-price-top10',
                      { requireCrossing: 'false', priceAtClose: 'true' }).subscribe({
      next: r => { this.mainTop = r?.data || null; },
      error: () => { this.mainTop = null; }
    });
    // mainProvisional belongs to the Avg Return Trend panel, so it must use THAT panel's
    // entry test - requireCrossing=false, exactly as mainTop above. It was briefly given the
    // RSI 45 section's rules by mistake, which is why the list headed "tomorrow" here stopped
    // resembling the ranked list it is supposed to precede.
    this.api.get<any>('/tracker/ranking/provisional-top10',
                      { requireCrossing: 'false' }).subscribe({
      next: r => { this.mainProvisional = r?.data || null; },
      error: () => { this.mainProvisional = null; }
    });
  }

  loadBelowPriceTop10() {
    if (this.lowTopLoading) return;
    this.lowTopLoading = true;
    // THIS is the provisional that renders inside the RSI 45 section, so it takes that
    // section's rules: the crossing required, ranking off, shortlisted by volume to six. It was
    // running on endpoint defaults - ranked, top ten - so the preview never matched the holding
    // it was previewing.
    this.api.get<any>('/tracker/ranking/provisional-top10',
                      { requireCrossing: 'true', applyRanking: 'false' }).subscribe({
      next: r => { this.provisional = r?.data || null; },
      error: () => { this.provisional = null; }
    });
    // requireVolume=false: this section is defined by its price rules alone. Volume is still
    // reported on every row, it just no longer excludes a stock.
    // applyRanking=false: it is a screen, not a Top 10. Scoring would cut qualifying stocks out
    // of a list whose whole purpose is to show which ones qualify.
    this.api.get<any>('/tracker/ranking/below-price-top10',
                      { requireVolume: 'false', applyRanking: 'false',
                        priceAtClose: 'true' }).subscribe({
      next: r => { this.lowTop = r?.data || null; this.lowTopLoading = false; },
      error: () => {
        this.lowTop = { top10: [], message: 'Could not build the list. Check that prices are loaded.' };
        this.lowTopLoading = false;
      }
    });
  }

  runLowPriceTrend() {
    // The table and the bars describe the same basket, so refresh them together - unless a load is
    // already in flight, which is the case on first paint where both are kicked off at once.
    if (!this.lowTopLoading) this.loadBelowPriceTop10();
    // No default window. This used to force the last 30 calendar days, so the chart drew about
    // nine bars while the database held 301 sessions and an upload history reaching back to
    // January - the back-dated bars were never missing, they were never asked for. Leaving the
    // range empty makes the server return everything it can honestly rebuild, and the dates it
    // used come back as coverage_from for the picker below.
    if (!this.lowTo) this.lowTo = new Date().toISOString().slice(0, 10);
    this.lowRefreshing = true;
    this.api.get<any>('/tracker/ranking/reconstruct-trend', {
      // applyReturnBand=false is sent explicitly. The server turns the 0-5% band back ON when no
      // price ceiling is supplied, so removing the ceiling alone would quietly change this
      // section's entry test away from the three stated conditions.
      // This panel's bars must use the same three-condition entry test as its table.
      // applyRanking=false so the bars average the same population the table lists. With
      // ranking on, each bar was the average of a ranked ten while the table showed every match.
      ...(this.lowFrom ? { from: this.lowFrom } : {}),
      // requireVolume=false so the bars average the population this table lists. The endpoint
      // applied the volume test unconditionally, so these bars were drawn from a filtered basket
      // under a heading whose table shows every crossing - the same mismatch applyRanking=false
      // was added to prevent.
      to: this.lowTo, applyReturnBand: 'false', requireCrossing: 'true',
      applyRanking: 'false', requireVolume: 'false', priceAtClose: 'true'
    }).subscribe({
      next: r => {
        const d = r?.data || {};
        // Show the picker the span actually drawn, so "from" is not left blank after a full load.
        if (!this.lowFrom && d.coverage_from) this.lowFrom = d.coverage_from;
        // Days where nothing qualified are KEPT and flagged. Dropping them left an unexplained
        // hole in the run of dates, which reads as missing data rather than as the real and
        // meaningful outcome it is: no stock met the entry rules that day.
        this.lowTrend = (d.days || [])
          .map((x: any) => ({
            // Three distinct states, not two: settled, still open, or nothing qualified. Collapsing
            // the last two into "No Stock" mislabelled a live position as an empty day.
            no_stock:       x.trade_avg_return == null && x.trade_source !== 'open',
            is_open:        x.trade_source === 'open',
            open_return:    x.open_return_pct,
            open_priced:    x.open_priced,
            note:           x.note,
            batch_id:       'LOW-' + x.date,
            trade_date:     x.date,
            avg_return_pct: Number(x.trade_avg_return) || 0,
            winning_stocks: x.trade_winners ?? 0,
            losing_stocks:  x.trade_losers ?? 0,
            total_stocks:   x.trade_symbol_count ?? 0,
            symbols:        x.symbols || [],
            sell_date:      x.sell_date,
            // Each trade kept whole, with its own Day 1-5 returns as the backtest recorded them.
            // The bar is derived from these, never the other way round: averaging first and then
            // trying to split the result by holding day is what mixed the days together.
            trades:         x.priced || [],
            strategy:       'Top 10'
          }));
        this.lowResult = {
          from_date:      this.lowFrom,
          to_date:        this.lowTo,
          avg_return:     d.average_return_pct ?? 0,
          days_with_data: d.measured_days ?? 0,
          winning_days:   d.positive_days ?? 0,
          losing_days:    Math.max(0, (d.measured_days ?? 0) - (d.positive_days ?? 0)),
          days_skipped:   Math.max(0, (d.trading_days ?? 0) - (d.measured_days ?? 0))
        };
        this.lowRefreshing = false;
      },
      error: () => { this.lowTrend = []; this.lowResult = null; this.lowRefreshing = false; }
    });
  }

  /**
   * Fills the trend chart by rebuilding each trading day from stored candles.
   *
   * <p>This used to ask for saved ranking runs, so it could only ever show days the app happened
   * to be running - which meant an empty chart for every date before ranking was switched on, even
   * though the prices for those days were sitting in the database the whole time. Rebuilding needs
   * no saved run, so any range the candles cover can be charted.
   *
   * <p>Only settled days become bars. A day still waiting on its sell price is reported in the
   * summary rather than drawn as zero, which would read as a flat day rather than an unknown one.
   */
  private loadReconstructedTrend(from: string, to: string) {
    this.trendRefreshing = true;
    this.api.get<any>('/tracker/ranking/reconstruct-trend',
                      { from, to, priceAtClose: 'true' }).subscribe({
      next: r => {
        const d = r?.data || {};
        const days = (d.days || []).filter((x: any) => x.trade_avg_return != null);
        this.tradeBatchTrend = days.map((x: any) => ({
          batch_id:       'DAY-' + x.date,
          trade_date:     x.date,
          avg_return_pct: Number(x.trade_avg_return) || 0,
          total_return_pct: Number(x.trade_avg_return) || 0,
          winning_stocks: x.trade_winners ?? 0,
          losing_stocks:  x.trade_losers ?? 0,
          total_stocks:   x.trade_symbol_count ?? 0,
          symbols:        x.symbols || [],
          sell_date:      x.sell_date,
          buy_time:       'close',
          sell_time:      'next close',
          strategy:       'Rebuilt Top 10'
        }));
        this.rangeResult = {
          from_date:      from,
          to_date:        to,
          avg_return:     d.average_return_pct ?? 0,
          days_with_data: d.measured_days ?? 0,
          winning_days:   d.positive_days ?? 0,
          losing_days:    Math.max(0, (d.measured_days ?? 0) - (d.positive_days ?? 0)),
          days_skipped:   Math.max(0, (d.trading_days ?? 0) - (d.measured_days ?? 0)),
          best_day:       d.best_day,
          worst_day:      d.worst_day
        };
        this.trendRefreshing = false;
      },
      error: () => {
        this.rangeResult = null;
        this.tradeBatchTrend = [];
        // No toast on this screen; the empty state below the chart explains what to do.
        this.trendRefreshing = false;
      }
    });
  }

  // Spec §2: named time selection — refresh the preview immediately on change.
  //
  // This used to call syncTrendToPeriod(), which copied the Day, buy time and sell time into the
  // Avg Return Trend panel and re-ran it. The two are separate questions - one is "what did this
  // basket do", the other is "how has the strategy performed over a window" - and tying them
  // meant the trend's own Time control silently moved whenever the table above it changed, so a
  // range chosen on the trend could not be kept. The trend now holds whatever you set on it.
  //
  // The Ranking Chart Bar stays anchored to this table on purpose: it lives inside this section
  // and is that table drawn day by day.
  onPeriodChange() {
    if (this.prevPeriod === 'CUSTOM' && !this.prevCustomDate) return; // wait for a date
    this.loadPeriodPreview();
  }

  /**
   * Days represented by a named period. Shared by the Previous Top 10 selector and the Avg
   * Return Trend selector so both read a time selection the same way.
   *
   * NOW maps to 1 day because "Now" on the Previous Top 10 panel means the previous trading
   * day's list. PAPER buys at the live price and has no historical window, so it keeps
   * whatever range the user last set.
   */
  periodDays(period: string, customDate: string): number {
    switch (period) {
      case 'NOW': return 1;
      case '1D': return 1;
      case '2D': return 2;
      case '3D': return 3;
      case '5D': return 5;
      case '1W': return 7;
      case '2W': return 14;
      case '1M': return 30;
      case '60D': return 60;
      case 'CUSTOM': {
        if (!customDate) return this.trendDays;
        const days = Math.round((Date.parse(this.todayIso) - Date.parse(customDate)) / 86400000);
        return Math.min(90, Math.max(1, days));
      }
      default: return this.trendDays;
    }
  }

  /** Time selection on the Avg Return Trend panel — same options and logic as Previous Top 10. */
  onTrendPeriodChange(): void {
    if (this.trendPeriod === 'CUSTOM' && !this.trendCustomDate) return;   // wait for a date
    if (this.trendPeriod === 'PAPER') { this.loadTradeBatchTrend(); return; }
    this.trendDays = this.periodDays(this.trendPeriod, this.trendCustomDate);
    this.runBacktestTrend();
  }

  // ---- Ranking Chart Bar: one bar per trading day, average of that day's ten ----
  rankingTrend: any[] = [];
  rankingTrendLoading = false;
  rankingTrendDays = 30;
  rankingTrendDayOptions = [10, 15, 30, 60, 90];
  rankingTrendEndDate = '';
  /** True when the newest bar predates today - the feed is behind, not the chart. */
  rankingTrendStale = false;
  rankingTrendRule = '';

  /**
   * Rebuilds the day-by-day average for the window ending on the day the table is showing.
   *
   * Deliberately the SAME endpoint and the same arguments the table's own list comes from - no
   * price ceiling, no return band, no crossing requirement - so the chart and the table cannot
   * drift apart the way the stored ranking runs did. Each day is rebuilt from prices stored up
   * to that date and settled against the next trading day, so no saved ranking is needed and
   * days from before ranking was switched on still chart.
   */
  loadRankingChartBar(endDate?: string): void {
    const end = endDate || this.rankingTrendEndDate
      || this.periodPreview?.run_date || this.periodPreview?.buy_date;
    if (!end) { this.rankingTrend = []; return; }
    this.rankingTrendEndDate = end;

    // Calendar days back, so weekends and holidays return fewer bars rather than quietly
    // stretching the window further into the past than the label says.
    const from = new Date(end + 'T00:00:00');
    from.setDate(from.getDate() - Number(this.rankingTrendDays));

    const params: any = {
      from: from.toISOString().slice(0, 10),
      to: end,
      buyTime: this.prevBuyTime,
      applyReturnBand: 'false',
      requireCrossing: 'false',
      // Selected the way Today top 10 Ranking Stocks selects - max 2 per sector - so this chart
      // and the ranking table above it describe the same basket.
      capPerSector: 'true'
    };
    // Pin the exit time only when a historical sell day is actually selected; otherwise leave the
    // backend on its own next-day exit, which is what the table above is doing.
    if (this.prevSellPeriod && this.prevSellPeriod !== 'NOW') params.sellTime = this.prevSellTime;

    this.rankingTrendLoading = true;
    this.api.get<any>('/tracker/ranking/reconstruct-trend', params).subscribe({
      next: r => {
        this.rankingTrend = r?.data?.days || [];
        this.rankingTrendRule = r?.data?.rule || '';

        // Name the bar that was actually drawn, not the day that was asked for.
        //
        // The window is requested against the calendar, but the rebuild only returns sessions
        // that have stored data - so when the feed is behind, the newest bar is older than the
        // requested end. The caption said "Last bar (4 Sep) is the table above" while the last
        // bar on screen was 28 Aug, which reads as a chart showing stale figures rather than a
        // chart that is honestly out of data. Re-anchoring to the last returned day makes the
        // caption true, and makes the gap between it and today visible instead of hidden.
        const last = this.rankingTrend.length
          ? this.rankingTrend[this.rankingTrend.length - 1]?.date : null;
        if (last) this.rankingTrendEndDate = last;
        this.rankingTrendStale = !!(last && last < this.todayIso);

        this.rankingTrendLoading = false;
      },
      error: () => {
        this.rankingTrend = [];
        this.rankingTrendRule = '';
        this.rankingTrendLoading = false;
      }
    });
  }

  loadPeriodPreview() {
    this.previewLoading = true;
    const params: any = { period: this.prevPeriod, time: this.prevBuyTime };
    if (this.prevPeriod === 'CUSTOM' && this.prevCustomDate) params.customDate = this.prevCustomDate;
    if (this.prevSellPeriod && this.prevSellPeriod !== 'NOW') {
      params.sellPeriod = this.prevSellPeriod;
      params.sellTime = this.prevSellTime;
      if (this.prevSellPeriod === 'CUSTOM' && this.prevSellCustomDate) params.sellCustomDate = this.prevSellCustomDate;
    }
    this.api.get<any>('/tracker/trade-batches/preview', params).subscribe({
      next: r => {
        this.periodPreview = r.data || null;
        this.previewLoading = false;
        // Anchor the chart to the day the table actually resolved to, not the day requested -
        // "1 Day" can land on an earlier session, and the last bar must be the table on screen.
        const end = this.periodPreview?.run_date || this.periodPreview?.buy_date;
        if (end) this.loadRankingChartBar(end); else this.rankingTrend = [];
      },
      error: () => { this.periodPreview = null; this.previewLoading = false; this.rankingTrend = []; }
    });
  }

  /** "22 Aug 15:30" for a stored stamp, "22 Aug - Live" when the leg is marked to market. */
  stampLabel(date?: string, time?: string): string {
    if (!date) return '-';
    const d = new Date(date + 'T00:00:00');
    const day = isNaN(d.getTime())
      ? date
      : d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short' });
    return time ? `${day} ${time}` : day;
  }

  periodLabel(): string {
    return this.prevPeriodOptions.find(o => o.value === this.prevPeriod)?.label || this.prevPeriod;
  }

  createTradeBatch() {
    // The button no longer greys out when there is nothing to buy, so the reason has to be
    // stated here instead - a live button that does nothing is worse than a dead one.
    if (!(this.rd?.current_top10?.length)) {
      alert('There is no Current Top 10 to buy yet.\n\nNothing met the entry test on the last '
          + 'completed session, or prices have not been loaded. Refresh the Top 10 and try again.');
      return;
    }
    if (this.prevPeriod === 'CUSTOM' && !this.prevCustomDate) { alert('Please pick a custom date first.'); return; }
    const when = this.prevPeriod === 'NOW' || this.prevPeriod === 'PAPER'
      ? 'at the current market price'
      : 'using stored prices from ' + (this.periodPreview?.buy_date || this.periodLabel());
    const ok = confirm('Buy All 10 from Current Top 10 (' + this.periodLabel() + ')?\n\nThis records buy prices for all 10 stocks ' + when + ' as a Paper trade batch.');
    if (!ok) return;
    this.tradeBatchCreating = true;
    const body: any = { trade_type: 'PAPER', period: this.prevPeriod };
    if (this.prevPeriod === 'CUSTOM' && this.prevCustomDate) body.custom_date = this.prevCustomDate;
    this.api.post<any>('/tracker/trade-batches/buy', body).subscribe({
      next: r => {
        this.tradeBatchCreating = false;
        const data = r.data || r;
        if (data.success) {
          alert((data.message) || ('Trade batch ' + (data.batch_id || '') + ' created with ' + (data.stocks || 10) + ' stocks.'));
          this.loadTradeBatches();
          this.loadTradeBatchTrend();
        } else {
          alert(data.error || 'Failed to create trade batch.');
        }
      },
      error: e => { this.tradeBatchCreating = false; alert(e.error?.message || 'Failed to create trade batch.'); }
    });
  }

  // Spec §2: is there an active (OPEN) batch to sell?
  hasOpenBatch(): boolean {
    return (this.tradeBatches || []).some((b: any) => b?.status === 'OPEN');
  }

  // Spec §2: section-level "Sell All 10" — closes the active Previous Top 10 batch(es).
  sellAllOpenBatch() {
    if (this.tradeBatchSelling) return;                       // already closing one
    const open = (this.tradeBatches || []).filter((b: any) => b?.status === 'OPEN');
    if (!open.length) { alert('No active (open) batch to sell. Use "Buy All 10" first.'); return; }
    const ok = confirm('Sell All 10 — close ' + (open.length === 1 ? 'the active batch' : open.length + ' active batches') +
      '?\n\nThis records sell prices at current market prices, calculates P/L, and updates Trade History.');
    if (!ok) return;
    // Close them one at a time. The old code fired every batch in parallel through a single
    // `tradeBatchSelling` string: the first response cleared the flag while the rest were
    // still in flight, leaving the button stuck (or clearing it early and double-firing).
    this.sellBatchesSequentially(open.map((b: any) => b.batch_id));
  }

  private sellBatchesSequentially(batchIds: string[]): void {
    const next = batchIds.shift();
    if (!next) { this.loadTradeBatches(); this.loadTradeBatchTrend(); return; }
    this.sellTradeBatch(next, true, () => this.sellBatchesSequentially(batchIds));
  }

  sellTradeBatch(batchId: string, skipConfirm = false, done?: () => void) {
    const ok = skipConfirm || confirm('Close trade batch ' + batchId + '?\n\nThis will record sell prices at current market prices for all stocks in this batch.');
    if (!ok) { done?.(); return; }
    this.tradeBatchSelling = batchId;
    // 60s ceiling: without it a request that never completes leaves the button showing
    // "Selling..." forever with no way back.
    this.api.post<any>(`/tracker/trade-batches/${batchId}/sell`, {}).pipe(timeout(60000)).subscribe({
      next: r => {
        this.tradeBatchSelling = '';
        const data = r.data || r;
        if (data.success) {
          alert('Batch closed. Avg return: ' + (data.avg_return || 0) + '% | Winners: ' + (data.winning || 0) + ' | Losers: ' + (data.losing || 0));
          this.loadTradeBatches();
          this.loadTradeBatchTrend();
        } else {
          alert(data.error || 'Failed to close batch.');
        }
        done?.();
      },
      error: e => {
        this.tradeBatchSelling = '';
        alert(e?.name === 'TimeoutError'
          ? 'Selling timed out after 60s. The batch may still have closed — press Refresh to check.'
          : (e.error?.message || 'Failed to close batch.'));
        done?.();
      }
    });
  }

  deleteTradeBatch(batchId: string) {
    const ok = confirm('Delete trade batch ' + batchId + '? This cannot be undone.');
    if (!ok) return;
    this.api.delete<any>(`/tracker/trade-batches/${batchId}`).subscribe({
      next: () => { this.loadTradeBatches(); this.loadTradeBatchTrend(); },
      error: () => alert('Failed to delete batch.')
    });
  }

  get batchTrendAvg(): number {
    if (!this.tradeBatchTrend.length) return 0;
    const sum = this.tradeBatchTrend.reduce((s: number, d: any) => s + (Number(d.avg_return_pct) || 0), 0);
    return Math.round((sum / this.tradeBatchTrend.length) * 100) / 100;
  }

  /**
   * Tallest bar in the Ranking Chart Bar, so every bar is scaled against the biggest day on
   * screen. Floored at 1 so a quiet window still draws readable bars rather than a row of
   * full-height ones, and never divides by zero.
   */
  get rankingChartMax(): number {
    return Math.max(1, ...this.rankingTrend
      .map((d: any) => Math.abs(Number(d?.trade_avg_return) || 0)));
  }

  /** Days that closed in profit. An unsettled day counts as neither, which is why it is shown. */
  get rankingChartUp(): number {
    return this.rankingTrend
      .filter((d: any) => d?.trade_avg_return != null && Number(d.trade_avg_return) >= 0).length;
  }

  get rankingChartDown(): number {
    return this.rankingTrend
      .filter((d: any) => d?.trade_avg_return != null && Number(d.trade_avg_return) < 0).length;
  }

  /** Days in the window with no settled return - still on the axis, but with no bar. */
  get rankingChartUnsettled(): number {
    return this.rankingTrend.filter((d: any) => d?.trade_avg_return == null).length;
  }

  /**
   * Mean of the settled days only. Counting an unsettled day as 0% would drag this toward zero
   * and read as a flat day that never actually happened.
   */
  get rankingTrendAvg(): number {
    const v = this.rankingTrend
      .map((d: any) => d?.trade_avg_return)
      .filter((x: any) => x != null)
      .map((x: any) => Number(x));
    if (!v.length) return 0;
    return Math.round((v.reduce((s: number, x: number) => s + x, 0) / v.length) * 100) / 100;
  }

  rankingBarTooltip(d: any): string {
    if (!d) return '';
    const ret = d.trade_avg_return == null
      ? 'not settled yet'
      : Number(d.trade_avg_return).toFixed(2) + '%';
    const parts = [
      String(d.date),
      'Average of that day\'s Top 10: ' + ret,
      'Stocks ranked: ' + (d.stocks_ranked ?? '-')
        + (d.trade_symbol_count != null ? '  (priced: ' + d.trade_symbol_count + ')' : '')
    ];
    if (d.trade_winners != null) {
      parts.push('Winners: ' + d.trade_winners + ' | Losers: ' + d.trade_losers);
    }
    if (d.sell_date) parts.push('Bought ' + d.date + ' ' + this.prevBuyTime + ', sold ' + d.sell_date);
    if (d.note) parts.push(String(d.note));
    return parts.join('\n');
  }

  get batchTrendMax(): number {
    return Math.max(1, ...this.tradeBatchTrend.map((d: any) => Math.abs(Number(d.avg_return_pct) || 0)));
  }

  /** Days in the window that closed in profit. */
  get batchTrendWinDays(): number {
    return this.tradeBatchTrend.filter((d: any) => Number(d.avg_return_pct) > 0).length;
  }

  get batchTrendLossDays(): number {
    return this.tradeBatchTrend.filter((d: any) => Number(d.avg_return_pct) < 0).length;
  }

  /** Share of days that closed in profit, as a percentage. */
  get batchTrendWinRate(): number {
    if (!this.tradeBatchTrend.length) return 0;
    return Math.round((this.batchTrendWinDays / this.tradeBatchTrend.length) * 1000) / 10;
  }

  get batchTrendBest(): any {
    if (!this.tradeBatchTrend.length) return null;
    return this.tradeBatchTrend.reduce((a: any, b: any) =>
      Number(b.avg_return_pct) > Number(a.avg_return_pct) ? b : a);
  }

  get batchTrendWorst(): any {
    if (!this.tradeBatchTrend.length) return null;
    return this.tradeBatchTrend.reduce((a: any, b: any) =>
      Number(b.avg_return_pct) < Number(a.avg_return_pct) ? b : a);
  }

  /**
   * What the window would have returned end to end, compounding each day's average onto the
   * last. This is the number that answers "what would the strategy have made?" - the plain
   * average of the daily figures does not, because gains and losses compound.
   */
  get batchTrendCumulative(): number {
    if (!this.tradeBatchTrend.length) return 0;
    let equity = 1;
    for (const d of this.tradeBatchTrend) equity *= 1 + (Number(d.avg_return_pct) || 0) / 100;
    return Math.round((equity - 1) * 100 * 100) / 100;
  }

  /** Days where at least one of the ten stocks had no stored price on one of the two legs. */
  get batchTrendIncompleteDays(): number {
    return this.tradeBatchTrend.filter((d: any) => Number(d.missing_prices) > 0).length;
  }

  /** 24-hour "15:20" as a plain "3:20 PM", for labels a non-technical reader scans. */
  hhmmLabel(t: string): string {
    const m = /^(\d{1,2}):(\d{2})$/.exec((t || '').trim());
    if (!m) return t || '-';
    const h = Number(m[1]);
    const suffix = h >= 12 ? 'PM' : 'AM';
    const h12 = h % 12 === 0 ? 12 : h % 12;
    return h12 + ':' + m[2] + ' ' + suffix;
  }

  /** Symbols whose ranking-component breakdown is open. */
  expandedFactors = new Set<string>();

  toggleFactors(symbol: string) {
    if (this.expandedFactors.has(symbol)) this.expandedFactors.delete(symbol);
    else this.expandedFactors.add(symbol);
  }

  /**
   * The ranking components the engine actually used for one stock, strongest contribution first.
   *
   * <p>The stored map mixes real factors with bookkeeping entries (_skipped_components,
   * _available_weight and so on), so anything prefixed with an underscore is filtered out rather
   * than rendered as if it were a factor.
   */
  factorRows(s: any): any[] {
    const fs = s?.factor_scores;
    if (!fs || typeof fs !== 'object') return [];
    return Object.keys(fs)
      .filter(k => !k.startsWith('_'))
      .map(k => {
        const f = fs[k] || {};
        const score = Number(f.score ?? 0) || 0;
        const weight = Number(f.weight ?? 0) || 0;
        return {
          key: k,
          label: f.label || k,
          score,
          weight,
          // What this factor added to the score: its 0-10 result scaled by the weight it carries.
          contribution: Math.round((score / 10) * weight * 10) / 10,
        };
      })
      .sort((a, b) => b.contribution - a.contribution);
  }

  /** Components the engine could not score, with the reason it gave. */
  skippedFactors(s: any): any[] {
    const arr = s?.factor_scores?._skipped_components;
    return Array.isArray(arr) ? arr : [];
  }

  /**
   * The eleven ranking components in their fixed display order, straight from the API.
   *
   * <p>The server sends every component whether it scored or not, so a stock missing one shows
   * n/a in that column instead of the column vanishing and silently shifting the others.
   */
  components(s: any): any[] {
    const c = s?.factor_scores?._components;
    return Array.isArray(c) ? c : [];
  }

  /** Weight that actually had data - the divisor behind the score, and 100 minus what was skipped. */
  weightUsed(s: any): number {
    const w = Number(s?.factor_scores?._weight_used ?? 0) || 0;
    if (w > 0) return w;
    return this.components(s).filter(c => c.available)
      .reduce((t: number, c: any) => t + (Number(c.weight) || 0), 0);
  }

  scoreColour(score: any): string {
    const v = Number(score);
    if (score == null || isNaN(v)) return '#64748b';   // n/a reads as absent, not as bad
    return v >= 7 ? '#22c55e' : v >= 4 ? '#eab308' : '#ef4444';
  }

  componentHint(c: any): string {
    const what: any = {
      relative_strength:      'performance against the other stocks ranked in this run',
      volume_growth:          'today\'s volume against its recent average',
      trend_strength:         'how consistently price has moved in one direction',
      rsi_strength:           'RSI(14) - momentum without over-extension',
      ma_position:            'where price sits against its moving averages',
      price_momentum:         'rate of recent price change',
      week52_position:        'position within the 52-week range',
      breakout_strength:      'proximity to a breakout above recent highs',
      institutional_interest: 'delivery percentage as a proxy for institutional buying',
      liquidity:              'traded value - how easily a position can be entered and exited',
      market_cap_quality:     'size and stability of the company'
    };
    const base = c.label + ' - ' + c.weight + '% weight - ' + (what[c.key] || '');
    return c.available ? base : base + ' - NOT COUNTED: ' + (c.reason || 'no source data');
  }

  /** Total weight of the components that could be scored - the divisor behind the final score. */
  availableWeight(s: any): number {
    const w = Number(s?.factor_scores?._available_weight ?? 0) || 0;
    if (w > 0) return w;
    return this.factorRows(s).reduce((t: number, f: any) => t + f.weight, 0);
  }

  factorContribTotal(s: any): number {
    return Math.round(this.factorRows(s).reduce((t: number, f: any) => t + f.contribution, 0) * 10) / 10;
  }

  /** Bar width: this factor's contribution against the largest one on the same stock. */
  factorBarPct(s: any, f: any): number {
    const rows = this.factorRows(s);
    const max = Math.max(0.1, ...rows.map((r: any) => r.contribution));
    return Math.max(0, Math.min(100, (f.contribution / max) * 100));
  }

  /** Money committed across the Top 10 rows actually on screen. */
  get top10TotalAlloc(): number {
    return this.filteredTop10().length * (Number(this.perStockAllocation) || 0);
  }

  get top10TotalQty(): number {
    return this.filteredTop10().reduce((t: number, s: any) => t + this.calcQty(s.current_price), 0);
  }

  get top10AvgScore(): number {
    const rows = this.filteredTop10();
    if (!rows.length) return 0;
    const sum = rows.reduce((t: number, s: any) => t + (Number(s.score) || 0), 0);
    return Math.round((sum / rows.length) * 100) / 100;
  }

  /** Average move today, over the rows that actually have a previous close to compare against. */
  get top10AvgChange(): number {
    const rows = this.filteredTop10().filter((s: any) => s.previous_close);
    if (!rows.length) return 0;
    const sum = rows.reduce((t: number, s: any) => t + (Number(s.today_return_pct) || 0), 0);
    return Math.round((sum / rows.length) * 100) / 100;
  }

  batchBarTooltip(d: any): string {
    if (d?.source === 'INTRADAY') {
      const priced = Number(d.total_stocks) || 0;
      const missing = Number(d.missing_prices) || 0;
      const shortfall = missing
        ? '\n' + missing + ' of the 10 had no stored price, so this day is averaged over ' + priced + '.'
        : '';
      return `${this.formatChartDate(d.trade_date)}  \u2014  average ${d.avg_return_pct}%\n`
        + `Bought ${d.trade_date} at ${this.hhmmLabel(d.buy_time)}\n`
        + `Sold ${d.sell_date || 'the next trading day'} at ${this.hhmmLabel(d.sell_time)}\n`
        + `${d.winning_stocks} gained, ${d.losing_stocks} lost, out of ${priced} stocks priced`
        + shortfall;
    }
    return `Batch: ${d.batch_id}\nDate: ${d.trade_date}\nBuy: ${d.buy_time} | Sell: ${d.sell_time || '-'}\nAvg Return: ${d.avg_return_pct}%\nWinners: ${d.winning_stocks} | Losers: ${d.losing_stocks}\nType: ${d.trade_type}`;
  }

  gainLoss(s: any): number {
    const base = Number(s?.base_price || 0);
    const curr = Number(s?.current_price || 0);
    return base > 0 && curr > 0 ? curr - base : 0;
  }

  get prevReturnAvg(): number {
    const rows = this.rd?.previous_top10 || [];
    if (!rows.length) return 0;
    const sum = rows.reduce((s: number, r: any) => s + (Number(r.current_return) || 0), 0);
    return Math.round((sum / rows.length) * 100) / 100;
  }

  get remainingBalance(): number {
    const count = (this.rd?.current_top10?.length) || 10;
    const total = this.totalTradingAmount || 0;
    const allocated = this.perStockAllocation * count;
    return Math.max(0, total - allocated);
  }

  formatTimestamp(val: any): string {
    if (!val) return '-';
    try {
      const d = new Date(String(val));
      if (isNaN(d.getTime())) return '-';
      const day = d.getDate().toString().padStart(2, '0');
      const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
      const mon = months[d.getMonth()];
      const year = d.getFullYear();
      const h = d.getHours().toString().padStart(2, '0');
      const m = d.getMinutes().toString().padStart(2, '0');
      const s = d.getSeconds().toString().padStart(2, '0');
      return `${day}-${mon}-${year} ${h}:${m}:${s}`;
    } catch { return '-'; }
  }

  recalcAllocation(): void {
    if (this.totalTradingAmount < 0) this.totalTradingAmount = 0;
    const count = (this.rd?.current_top10?.length) || 10;
    this.perStockAllocation = Math.floor((this.totalTradingAmount || 0) / count);
  }

  calcQty(price: number): number {
    if (!price || price <= 0) return 0;
    return Math.floor(this.perStockAllocation / price);
  }

  filteredTop10(): any[] {
    const rows = this.rd?.current_top10 || [];
    if (!this.filterBuyerSeller) return rows;
    const filtered = rows.filter((s: any) => {
      const signal = String(s.buy_sell_signal || '').toUpperCase();
      return signal === 'BUY' || signal === 'SELL';
    });
    this.excludedCount = rows.length - filtered.length;
    return filtered;
  }

  applyBuyerSellerFilter(): void {
    this.excludedCount = 0;
    if (this.filterBuyerSeller) {
      const rows = this.rd?.current_top10 || [];
      const filtered = rows.filter((s: any) => {
        const signal = String(s.buy_sell_signal || '').toUpperCase();
        return signal === 'BUY' || signal === 'SELL';
      });
      this.excludedCount = rows.length - filtered.length;
    }
  }

  recalcTrend(): void {
    this.sliceChartData();
  }

  openTrackerTrade(stock: any, tradeType: 'BUY' | 'SELL') {
    if (!this.isMarketHours()) {
      alert('Market is closed. Trading is available only during market hours (9:15 AM - 3:30 PM IST). Paper trades are logged for review.');
    }
    const symbol = String(stock?.symbol || '').toUpperCase();
    if (tradeType === 'BUY' && this.hasPosition(symbol)) {
      alert(`${symbol} already has an open position. Use Sell for this symbol.`);
      return;
    }
    if (tradeType === 'SELL' && !this.hasPosition(symbol)) {
      alert(`${symbol} has no open position, so Sell is not available.`);
      return;
    }
    if (!this.portfolios.length) {
      alert('Create a portfolio first, then place Buy/Sell trades from Stock Tracker.');
      return;
    }
    this.tradeDraft = {
      portfolioId: this.tradeDraft.portfolioId || this.portfolios[0][0],
      symbol,
      tradeType,
      qty: tradeType === 'SELL' ? Math.max(1, Number(this.positionsBySymbol[symbol]?.qty || 1)) : 1,
      price: Number(stock?.current_price || stock?.base_price || 0)
    };
    this.tradeError.set('');
    this.showTradeModal.set(true);
  }

  closeTradeModal() {
    this.showTradeModal.set(false);
    this.tradeError.set('');
  }

  submitTrackerTrade() {
    const pid = this.tradeDraft.portfolioId;
    if (!pid) { this.tradeError.set('Portfolio is required.'); return; }
    if (!this.tradeDraft.symbol || !this.tradeDraft.qty || !this.tradeDraft.price) {
      this.tradeError.set('Symbol, quantity, and price are required.');
      return;
    }
    if (this.tradeDraft.tradeType === 'SELL' && !this.hasPosition(this.tradeDraft.symbol)) {
      this.tradeError.set('Sell is available only when this symbol has an open position.');
      return;
    }
    this.tradeSubmitting.set(true);
    this.api.post<any>(`/enterprise/portfolios/${pid}/trade`, {
      symbol: this.tradeDraft.symbol,
      tradeType: this.tradeDraft.tradeType,
      qty: Number(this.tradeDraft.qty),
      price: Number(this.tradeDraft.price),
      exchange: 'NSE',
      segment: 'EQ-SWG'
    }).subscribe({
      next: () => {
        this.tradeSubmitting.set(false);
        this.closeTradeModal();
        this.loadPositions();
      },
      error: e => {
        this.tradeSubmitting.set(false);
        this.tradeError.set(e.error?.message || 'Trade failed. Please try again.');
      }
    });
  }

  bulkTop10Order(side: 'BUY' | 'SELL') {
    const rows = this.rd?.current_top10 || [];
    if (!rows.length) {
      alert('Current Top 10 is not available yet.');
      return;
    }
    if (!this.isMarketHours()) {
      alert('Market is closed. Bulk trading is available only during market hours (9:15 AM - 3:30 PM IST). Paper trades will be logged.');
    }
    this.bulkOrderMode = 'PAPER';
    const label = 'Paper trading';
    const timeLabel = this.bulkTradeTime === 'NOW' ? 'now' : `at ${this.bulkTradeTime}`;
    const ok = confirm(`${side} all Current Top 10 stocks using ${label} (${timeLabel})? Allocation: ₹${this.perStockAllocation}/stock. Every success or failure will be saved in the Trading Journal.`);
    if (!ok) return;
    this.bulkTrading.set(true);
    this.api.post<any>('/trading/stock-tracker/bulk-order', {
      confirmed: true,
      side,
      mode: 'PAPER',
      investmentAmountPerStock: this.perStockAllocation,
      tradeTime: this.bulkTradeTime,
      exchange: 'NSE',
      segment: 'EQ-SWG',
      productType: 'CNC',
      orderType: 'MARKET'
    }).subscribe({
      next: r => {
        this.bulkTrading.set(false);
        this.refreshResult = r.data || r;
        this.loadPositions();
      },
      error: e => {
        this.bulkTrading.set(false);
        alert(e.error?.message || `${side} All 10 failed.`);
      }
    });
  }

  /** Slice the full dataset to the selected periods â€” called on period change */
  sliceChartData() {
    this.chart1Data = this.chartData.slice(-this.chart1Period);
    this.chart2Data = this.chartData.slice(-this.chart2Period);
  }

  /** Set Chart 1 period, reslice immediately â€” no API call needed */
  setChart1Period(p: number) {
    this.chart1Period = p;
    this.sliceChartData();
  }

  /** Set Chart 2 period, reslice immediately â€” no API call needed */
  setChart2Period(p: number) {
    this.chart2Period = p;
    this.sliceChartData();
  }

  // â”€â”€ Stats computed â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  get profitPct()   { return this.dash.total > 0 ? (this.dash.positive / this.dash.total)*100 : 0; }
  get lossPct()     { return this.dash.total > 0 ? (this.dash.negative / this.dash.total)*100 : 0; }
  get eligiblePct() { return this.dash.total > 0 ? (this.dash.eligible / this.dash.total)*100 : 0; }
  get totalDist()   { return this.distributionRows().reduce((s: number, b: any) => s + (b.count||0), 0); }
  distributionRows() {
    return this.hardcodedReturnRanges.map(label => {
      const found = this.dist.find((b: any) => String(b.label || '') === label);
      return {
        label,
        count: Number(found?.count || 0),
        pct: Number(found?.pct || 0)
      };
    });
  }

  private applyLiveQuote(q: any): void {
    const symbol = this.normalizeSymbol(q?.symbol);
    const ltp = Number(q?.currentPrice || q?.ltp || 0);
    const quoteClose = Number(q?.close || q?.closePrice || 0);
    const marketOpen = this.dash.market_status === 'OPEN';
    const price = marketOpen ? ltp : (quoteClose > 0 ? quoteClose : ltp);
    if (!symbol || price <= 0) return;
    const updatedAt = q?.tickReceivedAt || q?.lastTradeTime || new Date().toISOString();
    this.uploadedStocks = this.uploadedStocks.map(s => {
      if (this.normalizeSymbol(s.symbol) !== symbol) return s;
      const base = Number(s.base_price || 0);
      const prevPrice = s.current_price > 0 ? s.current_price : undefined;
      return {
        ...s,
        _prev_price: prevPrice,
        current_price: price,
        close_price: Number(quoteClose || s.close_price || 0),
        open_price: Number(q?.open || q?.openPrice || s.open_price || 0),
        high_price: Number(q?.high || q?.highPrice || s.high_price || 0),
        low_price: Number(q?.low || q?.lowPrice || s.low_price || 0),
        volume: Number(q?.volume || s.volume || 0),
        last_trade_time: q?.lastTradeTime || s.last_trade_time,
        return_pct: base > 0 ? ((price - base) / base) * 100 : s.return_pct,
        last_refreshed: updatedAt,
        market_status: marketOpen ? 'Live' : 'Closed',
        price_type: marketOpen ? 'LTP' : 'Closing Price',
        current_price_source: marketOpen ? 'DHAN_LTP' : 'LAST_MARKET_CLOSE'
      };
    });
    this.updateRankingRowsFromQuote(symbol, price, updatedAt);
    if (this.tradeDraft?.symbol && this.normalizeSymbol(this.tradeDraft.symbol) === symbol) {
      this.tradeDraft.price = price;
    }
    this.dash.last_refreshed = updatedAt;
  }

  private updateRankingRowsFromQuote(symbol: string, price: number, updatedAt: string): void {
    if (!this.rd || !symbol || price <= 0) return;
    const updateCurrent = (row: any) => {
      if (this.normalizeSymbol(row?.symbol) !== symbol) return row;
      const base = Number(row.base_price || 0);
      const prevClose = Number(row.previous_close || 0);
      const todayRet = prevClose > 0 ? Math.round(((price - prevClose) / prevClose) * 10000) / 100 : row.today_return_pct;
      return {
        ...row,
        current_price: price,
        return_pct: base > 0 ? Math.round(((price - base) / base) * 10000) / 100 : row.return_pct,
        today_return_pct: todayRet,
        last_refreshed: updatedAt,
        last_updated: updatedAt
      };
    };
    const updatePrevious = (row: any) => {
      if (this.normalizeSymbol(row?.symbol) !== symbol) return row;
      const base = Number(row.base_price || 0);
      return {
        ...row,
        current_price: price,
        current_return: base > 0 ? Math.round(((price - base) / base) * 10000) / 100 : row.current_return,
        last_refreshed: updatedAt
      };
    };
    this.rd = {
      ...this.rd,
      current_top10: (this.rd.current_top10 || []).map(updateCurrent),
      previous_top10: (this.rd.previous_top10 || []).map(updatePrevious)
    };
    const currentRows = this.rd.current_top10 || [];
    if (currentRows.length) {
      const sum = currentRows.reduce((s: number, r: any) => s + (Number(r.return_pct) || 0), 0);
      const positive = currentRows.filter((r: any) => (Number(r.return_pct) || 0) >= 0).length;
      this.rd.curr_avg_return = Math.round((sum / currentRows.length) * 100) / 100;
      this.rd.curr_positive = positive;
      this.rd.curr_negative = currentRows.length - positive;
      this.rd.curr_win_ratio = Math.round((positive / currentRows.length) * 1000) / 10;
    }
    const now = new Date().toISOString();
    this.currentTop10LastUpdated = now;
    this.previousTop10LastUpdated = now;
  }

  private normalizeSymbol(value: any): string {
    return String(value || '').toUpperCase().replace('.NS', '').replace('.BO', '').replace(/-(EQ|BE|BZ|SM|ST)$/,'').replace(/\s+/g, '');
  }

  signalBadgeClass(signal: string): string {
    const value = String(signal || '').toUpperCase();
    if (value === 'BUY') return 'badge-green';
    if (value === 'SELL') return 'badge-red';
    if (value === 'HOLD') return 'badge-blue';
    return 'badge-amber';
  }

  trendBadgeClass(trend: string): string {
    const value = String(trend || '').toLowerCase();
    if (value === 'bullish') return 'badge-green';
    if (value === 'bearish') return 'badge-red';
    if (value === 'neutral') return 'badge-blue';
    return 'badge-amber';
  }

  readableDhanError(error: string): string {
    const text = String(error || '').replace(/^DHAN_ERROR:\s*/i, '').trim();
    const lower = text.toLowerCase();
    if (lower.includes('dh-904') || lower.includes('rate_limit') || lower.includes('too many requests')) return 'Dhan rate limit reached. Will retry later.';
    if (lower.includes('token')) return 'Token required';
    if (lower.includes('not mapped')) return 'Symbol not mapped';
    if (lower.includes('closing price not available')) return 'No Dhan close for upload date';
    if (lower.includes('upload date')) return 'Upload date missing';
    return text || 'Dhan error';
  }

  priceChangeColor(s: any): string {
    if (!s._prev_price || !s.current_price) return '#fff';
    if (s.current_price > s._prev_price) return '#4ade80';
    if (s.current_price < s._prev_price) return '#f87171';
    return '#94a3b8';
  }

  priceTooltip(s: any): string {
    const type = s.price_type || '';
    const src = s.current_price_source || '';
    const status = s.market_status || 'Closed';
    return `${type} | Source: ${src} | Market: ${status}`;
  }

  priceSourceLabel(source: string): string {
    if (!source) return '';
    const s = source.toLowerCase();
    if (s.includes('live') || s.includes('ltp') || s.includes('websocket')) return 'Live';
    if (s.includes('dhan') || s.includes('api')) return 'API';
    if (s.includes('daily') || s.includes('close')) return 'Close';
    if (s.includes('cache')) return 'Cache';
    if (s.includes('db') || s.includes('database') || s.includes('stored')) return 'DB';
    return source.length > 6 ? source.substring(0, 6) : source;
  }

  // Explicit fetch date & time (e.g. "12 Jul 2026, 15:42") for the Last Updated column.
  formatFetchDateTime(val: any): string {
    if (!val) return '-';
    const str = String(val);
    const d = new Date(str.length <= 10 ? str + 'T00:00:00' : str);
    if (isNaN(d.getTime())) return str;
    const datePart = d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
    if (str.length <= 10) return datePart;
    const timePart = d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false });
    return `${datePart}, ${timePart}`;
  }

  formatLastUpdated(val: any): string {
    if (!val) return '-';
    const str = String(val);
    if (str.length <= 10) return str;
    try {
      const d = new Date(str);
      if (isNaN(d.getTime())) return str;
      const now = new Date();
      const diff = now.getTime() - d.getTime();
      if (diff < 60000) return 'Just now';
      if (diff < 3600000) return Math.floor(diff / 60000) + 'm ago';
      if (diff < 86400000) return Math.floor(diff / 3600000) + 'h ago';
      return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' });
    } catch { return str; }
  }

  private isSignalSortColumn(col: string): boolean {
    return ['buy_sell_signal','trend','today_volume_change_pct','below_52wh_pct','above_52wl_pct','pe_ratio'].includes(col);
  }

  // Module scope on purpose: the router destroys this component when you leave the tracker, so
  // an instance field took the whole table with it. Coming back rebuilt every page from scratch -
  // which is why the section took just as long on the second visit as on the first. Held here it
  // survives navigation, and a return paints from it immediately.
  private uploadedPageCache = UPLOADED_PAGE_CACHE;

  /** Empties the shared cache in place - reassigning it would silently unshare it. */
  private clearUploadedCache(): void {
    for (const k of Object.keys(this.uploadedPageCache)) delete this.uploadedPageCache[k];
  }

  // withSignals=true → full enriched fetch so Signal (Sell) and Trend update.
  /**
   * @param silent skip the loading state. A background refresh must not flash a loading row or
   *        disable the pager under someone who is reading the table.
   */
  /**
   * Whether the table is currently showing the signal columns.
   *
   * <p>Every caller used to decide this for itself, and all of them but "Reload Table" defaulted
   * to false. So loading the table with signals and then turning a page silently dropped back to
   * the lite view - Signal, Trend and the 52-week columns vanishing mid-read, which is the
   * section "changing on its own" when nothing was asked of it. The choice is remembered here
   * and every later load keeps it.
   */
  uploadedWithSignals = false;

  loadUploadedStocks(forceReload = false, withSignals = this.uploadedWithSignals, silent = false) {
    this.uploadedWithSignals = withSignals;
    const cacheKey = `${this.uploadedPage}_${this.uploadedPageSize}_${this.uploadedSortBy}_${this.uploadedSortDir}_${withSignals ? 'full' : 'lite'}`;
    if (!forceReload) {
      const cached = this.uploadedPageCache[cacheKey];
      if (cached) {
        // Paint what we already have, always - including when it is past its 2 minutes. Returning
        // to the section then shows the table instantly instead of an empty loading state, and
        // anything stale is replaced quietly underneath. Blocking on the network to re-fetch rows
        // that are already on hand is the wait being complained about.
        this.uploadedStocks = cached.stocks;
        this.uploadedTotal = cached.total;
        if ((Date.now() - cached.time) < 120000) return;
        silent = true;           // revalidate without a spinner or a disabled pager
      }
    }
    const requestId = ++this.uploadedRequestId;
    this.uploadedLoading = !silent;
    this.uploadedDetailsLoading = false;
    this.api.get<any>('/tracker/stocks', {
      page: this.uploadedPage,
      size: this.uploadedPageSize,
      sortBy: this.uploadedSortBy,
      sortDir: this.uploadedSortDir,
      includeSignals: withSignals ? 'true' : 'false',
      lite: withSignals ? 'false' : 'true'
    }).subscribe({
      next: r => {
        if (this.destroyed || requestId !== this.uploadedRequestId) return;
        const data = r.data || {};
        this.uploadedStocks = data.stocks || [];
        this.uploadedTotal = Number(data.total || 0);
        this.uploadedLoading = false;
        this.uploadedPageCache[cacheKey] = { stocks: this.uploadedStocks, total: this.uploadedTotal, time: Date.now() };
      },
      error: () => {
        if (this.destroyed || requestId !== this.uploadedRequestId) return;
        this.uploadedStocks = [];
        this.uploadedTotal = 0;
        this.uploadedLoading = false;
      }
    });
  }

  // Diagnostic: find stocks whose Previous Close cannot update (stale/missing daily history).
  loadStaleDiagnostic() {
    this.staleLoading = true;
    this.api.get<any>('/tracker/diagnostics/stale-prev-close').subscribe({
      next: r => { this.staleResult = r.data || { count: 0, stocks: [] }; this.staleLoading = false; },
      error: e => { this.staleLoading = false; alert(e.error?.message || 'Diagnostic failed. Please try again.'); }
    });
  }

  isRemovingUploadedStock(symbol: string): boolean {
    return this.removingUploadedSymbols.has(String(symbol || '').toUpperCase());
  }

  removeUploadedStock(stock: any): void {
    const symbol = String(stock?.symbol || '').trim().toUpperCase();
    if (!symbol) {
      alert('Symbol is missing for this row.');
      return;
    }
    const ok = confirm(`Remove ${symbol} from Uploaded Stock Details?\n\nThis will hide it from Stock Upload, dashboard counts, rankings, and exports. Historical records are kept for audit.`);
    if (!ok) return;
    this.removingUploadedSymbols.add(symbol);
    this.api.delete<any>(`/tracker/stocks/${encodeURIComponent(symbol)}`).subscribe({
      next: r => {
        this.removingUploadedSymbols.delete(symbol);
        if (r?.success === false) {
          alert(r.message || 'Stock could not be removed.');
          return;
        }
        this.uploadedStocks = this.uploadedStocks.filter(s => String(s.symbol || '').toUpperCase() !== symbol);
        this.uploadedTotal = Math.max(0, this.uploadedTotal - 1);
        this.selectedSymbols.delete(symbol);
        if (!this.uploadedStocks.length && this.uploadedPage > 0) {
          this.uploadedPage -= 1;
        }
        this.loadUploadedStocks();
        this.loadDashboard();
        this.loadRanking();
        this.loadChartData();
        this.loadUploadStats();
      },
      error: e => {
        this.removingUploadedSymbols.delete(symbol);
        alert(e?.error?.message || 'Stock could not be removed. Please try again.');
      }
    });
  }

  // ── Upload Management Methods ──────────────────────────────────────────────

  loadUploadStats(): void {
    this.api.get<any>('/tracker/upload-stats').subscribe({
      next: r => { if (r?.data) this.uploadStats = r.data; },
      error: () => {}
    });
  }

  loadUploadHistory(): void {
    this.api.get<any>('/tracker/upload-history').subscribe({
      next: r => { this.uploadHistory = r?.data || []; },
      error: () => { this.uploadHistory = []; }
    });
  }

  toggleSelect(symbol: string): void {
    if (this.selectedSymbols.has(symbol)) this.selectedSymbols.delete(symbol);
    else this.selectedSymbols.add(symbol);
  }

  toggleSelectAll(event: any): void {
    if (event.target.checked) {
      this.uploadedStocks.forEach(s => this.selectedSymbols.add(s.symbol));
    } else {
      this.selectedSymbols.clear();
    }
  }

  allSelected(): boolean {
    return this.uploadedStocks.length > 0 && this.uploadedStocks.every(s => this.selectedSymbols.has(s.symbol));
  }

  deleteSelectedStocks(): void {
    const symbols = Array.from(this.selectedSymbols);
    if (!symbols.length) return;
    const ok = confirm(`Delete ${symbols.length} selected stock${symbols.length === 1 ? '' : 's'}?\n\nThis action will soft-delete the selected stocks from the uploaded list.`);
    if (!ok) return;
    this.api.post<any>('/tracker/stocks/delete-selected', { symbols }).subscribe({
      next: r => {
        alert(r?.message || `${r?.data?.deleted || 0} stock(s) removed successfully.`);
        this.selectedSymbols.clear();
        this.refreshAfterDelete();
      },
      error: e => alert(e?.error?.message || 'Unable to delete uploaded stocks. Please try again.')
    });
  }

  deleteBatch(batchId: string, totalRecords: number): void {
    const ok = confirm(`Delete complete upload batch?\n\nBatch ID: ${batchId}\nTotal Stocks: ${totalRecords}\n\nThis will remove all stocks uploaded in this batch.`);
    if (!ok) return;
    this.deletingBatch = batchId;
    this.api.delete<any>(`/tracker/batch/${encodeURIComponent(batchId)}`).subscribe({
      next: r => {
        this.deletingBatch = '';
        alert(r?.message || `Batch deleted successfully. Total records removed: ${r?.data?.deleted || 0}`);
        this.refreshAfterDelete();
        this.loadUploadHistory();
      },
      error: e => {
        this.deletingBatch = '';
        alert(e?.error?.message || 'Unable to delete batch. Please try again.');
      }
    });
  }

  deleteAllUploadedStocks(): void {
    const first = confirm('Delete All Uploaded Stocks?\n\nThis action will permanently remove all uploaded stocks from the Uploaded Stock module.\n\nThis action cannot be undone.');
    if (!first) return;
    const second = confirm('FINAL CONFIRMATION\n\nAre you absolutely sure you want to delete ALL uploaded stocks?\n\nType OK to confirm.');
    if (!second) return;
    this.api.delete<any>('/tracker/stocks/all').subscribe({
      next: r => {
        alert(r?.message || 'All uploaded stocks removed successfully.');
        this.refreshAfterDelete();
      },
      error: e => alert(e?.error?.message || 'Unable to delete all stocks. Please try again.')
    });
  }

  onUploadSearchChange(): void {
    if (this.searchDebounce) clearTimeout(this.searchDebounce);
    this.searchDebounce = setTimeout(() => { this.clearUploadedCache(); this.loadUploadedStocks(true); }, 400);
  }

  private refreshAfterDelete(): void {
    this.clearUploadedCache();
    this.loadUploadedStocks(true);
    this.loadDashboard();
    this.loadRanking();
    this.loadChartData();
    this.loadUploadStats();
    this.selectedSymbols.clear();
  }

  uploadedTotalPages(): number {
    return Math.max(1, Math.ceil(this.uploadedTotal / this.uploadedPageSize));
  }

  uploadedPageStart(): number {
    if (!this.uploadedTotal) return 0;
    return (this.uploadedPage * this.uploadedPageSize) + 1;
  }

  uploadedPageEnd(): number {
    return Math.min((this.uploadedPage + 1) * this.uploadedPageSize, this.uploadedTotal);
  }

  setUploadedPage(page: number): void {
    const nextPage = Math.min(Math.max(0, page), this.uploadedTotalPages() - 1);
    if (nextPage === this.uploadedPage) return;
    this.uploadedPage = nextPage;
    this.loadUploadedStocks();
  }

  setUploadedSort(sortBy: string, sortDir: string): void {
    this.uploadedSortBy = sortBy || 'symbol';
    this.uploadedSortDir = sortDir === 'desc' ? 'desc' : 'asc';
    this.uploadedPage = 0;
    this.loadUploadedStocks();
  }

  toggleUploadedSort(col: string): void {
    if (this.uploadedSortBy === col) {
      this.uploadedSortDir = this.uploadedSortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this.uploadedSortBy = col;
      this.uploadedSortDir = 'desc';
    }
    this.uploadedPage = 0;
    this.clearUploadedCache();
    this.loadUploadedStocks(true);
  }

  formatDateDMY(dateStr: string): string {
    if (!dateStr || dateStr === '-') return '-';
    const d = dateStr.trim();
    const m = d.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (m) return `${m[3]}-${m[2]}-${m[1]}`;
    return d;
  }

  sortIcon(col: string): string {
    if (this.uploadedSortBy !== col) return '';
    return this.uploadedSortDir === 'asc' ? '▲' : '▼';
  }
  get distChartPoints() {
    const rows = this.distributionRows();
    if (!rows.length) return [];
    const left = 30, right = 500, top = 14, bottom = 126;
    const max = Math.max(1, ...rows.map((b: any) => Number(b.count) || 0));
    const step = rows.length > 1 ? (right - left) / (rows.length - 1) : 0;
    return rows.map((b: any, i: number) => {
      const count = Number(b.count) || 0;
      const x = left + (step * i);
      const y = bottom - ((count / max) * (bottom - top));
      return { label: b.label, x, y, count };
    });
  }
  get distLinePoints() {
    return this.distChartPoints.map(p => `${p.x},${p.y}`).join(' ');
  }
  private distSeriesPoints(rows: any[], positive: boolean) {
    const data = Array.isArray(rows) && rows.length ? rows : [{ label: '0', value: 0, count: 0 }];
    const left = 30, right = 500, zeroY = 75, top = 18, bottom = 126;
    const maxCount = Math.max(1, ...data.map((b: any) => Number(b.count) || 0));
    const step = data.length > 1 ? (right - left) / (data.length - 1) : 0;
    return data.map((b: any, i: number) => {
      const count = Number(b.count) || 0;
      const x = left + (step * i);
      const height = (count / maxCount) * (positive ? (zeroY - top) : (bottom - zeroY));
      const y = positive ? zeroY - height : zeroY + height;
      return { label: b.label, x, y, count };
    });
  }
  get positiveDistChartPoints() {
    return this.distSeriesPoints(this.distPositiveLine, true);
  }
  get negativeDistChartPoints() {
    return this.distSeriesPoints(this.distNegativeLine, false);
  }
  get positiveDistLinePoints() {
    return this.positiveDistChartPoints.map(p => `${p.x},${p.y}`).join(' ');
  }
  get negativeDistLinePoints() {
    return this.negativeDistChartPoints.map(p => `${p.x},${p.y}`).join(' ');
  }
  get prevRunDate() {
    if (this.rd?.prev_run_date) return this.rd.prev_run_date;
    if (!this.rd?.run_date) return '';
    try {
      const d = new Date(this.rd.run_date);
      d.setDate(d.getDate() - 1);
      return d.toISOString().slice(0,10);
    } catch { return ''; }
  }

  get compMetrics() {
    if (!this.rd) return [];
    return [
      { label:'Average Return',  curr: this.rd.curr_avg_return||0, prev: this.rd.prev_avg_return||0, unit:'%', max:15 },
      { label:'Win Ratio',       curr: this.rd.curr_win_ratio||0,  prev: this.rd.prev_win_ratio||0,  unit:'%', max:100 },
      { label:'Positive Stocks', curr: this.rd.curr_positive||0,   prev: this.rd.prev_positive||0,   unit:'',  max:10 },
      { label:'Negative Stocks', curr: this.rd.curr_negative||0,   prev: this.rd.prev_negative||0,   unit:'',  max:10 },
      { label:'Average Score',   curr: this.avgScore('curr'),       prev: this.avgScore('prev'),       unit:'',  max:100 },
    ];
  }

  avgScore(which: 'curr' | 'prev'): number {
    const arr = which === 'curr' ? this.rd?.current_top10 : this.rd?.previous_top10;
    if (!arr?.length) return 0;
    return arr.reduce((s: number, x: any) => s + (x.score||0), 0) / arr.length;
  }

  /** Max absolute value in Chart 1 dataset â€” used to scale bar heights */
  get chartMaxCurr(): number {
    return Math.max(1, ...this.chart1Data.map((d: any) => Math.abs(d.curr_avg_return || 0)));
  }

  /** Max absolute value in Chart 2 dataset â€” used to scale bar heights */
  get chartMaxPrev(): number {
    return Math.max(1, ...this.chart2Data.map((d: any) => Math.abs(this.dayAvgReturn(d))));
  }

  /** Average of curr_avg_return over chart1Period â€” shown in summary box */
  get chart1Avg(): number {
    if (!this.chart1Data.length) return 0;
    const sum = this.chart1Data.reduce((s: number, d: any) => s + (d.curr_avg_return || 0), 0);
    return Math.round((sum / this.chart1Data.length) * 100) / 100;
  }

  /** Average of prev_avg_return over chart2Period â€” shown in summary box */
  get chart2Avg(): number {
    if (!this.chart2Data.length) return 0;
    const sum = this.chart2Data.reduce((s: number, d: any) => s + this.dayAvgReturn(d), 0);
    return Math.round((sum / this.chart2Data.length) * 100) / 100;
  }

  get chart2SymbolCount(): number {
    return this.chart2Data.reduce((s: number, d: any) => s + (Number(d?.trade_symbol_count) || 0), 0);
  }

  get chart2Winners(): number {
    return this.chart2Data.reduce((s: number, d: any) => s + (Number(d?.trade_winners) || 0), 0);
  }

  get chart2Losers(): number {
    return this.chart2Data.reduce((s: number, d: any) => s + (Number(d?.trade_losers) || 0), 0);
  }

  get chart2Source(): string {
    if (!this.chart2Data.length) return '-';
    const counts = this.chart2Data.reduce((m: Record<string, number>, d: any) => {
      const key = String(d?.trade_source || 'missing');
      m[key] = (m[key] || 0) + 1;
      return m;
    }, {});
    return (Object.entries(counts) as [string, number][]).sort((a, b) => b[1] - a[1])[0]?.[0] || '-';
  }

  get chart2FallbackCount(): number {
    return this.chart2Data.filter((d: any) => d?.trade_source === 'ranking_fallback').length;
  }

  get chart2MissingCount(): number {
    return this.chart2Data.filter((d: any) => d?.trade_source === 'missing').length;
  }

  dayAvgReturn(d: any): number {
    return Number(d?.curr_avg_return ?? d?.trade_avg_return ?? d?.day_avg_return ?? 0) || 0;
  }

  barH(val: number, max: number): number {
    return Math.max(2, Math.round((Math.abs(val) / max) * 72));
  }

  chartBarTooltip(d: any): string {
    const ret = this.dayAvgReturn(d);
    const date = d?.date || '-';
    const winners = d?.trade_winners ?? '-';
    const losers = d?.trade_losers ?? '-';
    const symbols = d?.trade_symbol_count ?? '-';
    const source = d?.trade_source || '-';
    return `Date: ${date}\nAvg Return: ${ret.toFixed(2)}%\nSymbols: ${symbols}\nWinners: ${winners} | Losers: ${losers}\nSource: ${source}\nStrategy: Buy ${this.trendBuyTime}, Sell ${this.trendSellTime} +${this.trendHoldDays}D`;
  }

  formatChartDate(dateStr: string): string {
    if (!dateStr) return '-';
    try {
      const parts = dateStr.split('-');
      if (parts.length >= 3) {
        const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
        const mon = months[parseInt(parts[1], 10) - 1] || parts[1];
        return `${parts[2]}-${mon}`;
      }
      return dateStr.slice(5);
    } catch { return dateStr.slice(5); }
  }

  // â”€â”€ Donut builders â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  buildSectorDonut() {
    const dist = this.rd?.sector_distribution;
    if (!dist) return;
    const total = Object.values(dist).reduce((s: number, v: any) => s + v, 0) || 1;
    let startAngle = -Math.PI / 2;
    this.donutSegments = Object.entries(dist).map(([label, count]: [string, any], i) => {
      const pct  = Math.round((count / (total as number)) * 100);
      const angle = (count / (total as number)) * 2 * Math.PI;
      const path  = this.donutPath(60, 60, 50, 28, startAngle, startAngle + angle);
      startAngle += angle;
      return { label, pct, color: this.SECTOR_COLORS[i % this.SECTOR_COLORS.length], path };
    });
  }

  buildDistDonut() {
    const nonZero = this.distributionRows().filter((b: any) => b.count > 0);
    const total   = nonZero.reduce((s: number, b: any) => s + b.count, 0) || 1;
    let startAngle = -Math.PI / 2;
    this.distDonutSegments = nonZero.map((b: any, i: number) => {
      const angle = (b.count / total) * 2 * Math.PI;
      const path  = this.donutPath(55, 55, 44, 24, startAngle, startAngle + angle);
      startAngle += angle;
      return { label: b.label, color: this.DIST_COLORS[i % this.DIST_COLORS.length], path };
    });
  }

  donutPath(cx: number, cy: number, r: number, ir: number, a1: number, a2: number): string {
    const x1 = cx + r  * Math.cos(a1), y1 = cy + r  * Math.sin(a1);
    const x2 = cx + r  * Math.cos(a2), y2 = cy + r  * Math.sin(a2);
    const x3 = cx + ir * Math.cos(a2), y3 = cy + ir * Math.sin(a2);
    const x4 = cx + ir * Math.cos(a1), y4 = cy + ir * Math.sin(a1);
    const lg = (a2 - a1) > Math.PI ? 1 : 0;
    return `M ${x1} ${y1} A ${r} ${r} 0 ${lg} 1 ${x2} ${y2} L ${x3} ${y3} A ${ir} ${ir} 0 ${lg} 0 ${x4} ${y4} Z`;
  }

  // â”€â”€ Actions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  onFileSelected(e: Event) {
    const f = (e.target as HTMLInputElement).files?.[0];
    if (f) { this.selectedFile = f; this.uploadResult = null; }
  }

  onDrop(e: DragEvent) {
    e.preventDefault();
    const f = e.dataTransfer?.files?.[0];
    if (f) { this.selectedFile = f; this.uploadResult = null; }
  }

  uploadFile() {
    if (!this.selectedFile) return;
    this.uploading.set(true);
    const fd = new FormData();
    fd.append('file', this.selectedFile);
    this.http.post<any>(`${environment.apiUrl}/tracker/upload`, fd).subscribe({
      next: r => {
        this.uploadResult = r.data || r;
        this.uploading.set(false);
        this.selectedFile = null;
        this.loadUploadedStocks(true);
        this.loadUploadStats();
        if (this.uploadResult?.job_id) {
          this.activeJobId = this.uploadResult.job_id;
          this.pollJobStatus();
        }
      },
      error: e => { this.uploadResult = { success:false, error: e.message||'Upload failed' }; this.uploading.set(false); }
    });
  }

  refreshPrices() {
    // Prevent duplicate concurrent refresh requests while one is processing.
    if (this.refreshing() || this.refreshState === 'updating') return;
    this.refreshing.set(true);
    this.refreshState = 'updating';
    this.api.post<any>('/tracker/refresh', {}).subscribe({
      next: r => {
        const data = r.data || r;
        // Backend returns success:false on failure (never crashes the UI).
        if (data && data.success === false) {
          this.refreshState = 'failed';
          this.refreshing.set(false);
          return;
        }
        this.refreshResult = data;
        if (data && data.last_updated) this.refreshLastUpdatedLabel = data.last_updated;
        else this.refreshLastUpdatedLabel = this.nowStamp();
        this.refreshState = 'completed';
        this.refreshing.set(false);
        this.autoRefreshAll('broker_sync');
      },
      error: () => {
        this.refreshState = 'failed';
        this.refreshing.set(false);
      }
    });
  }

  retryRefresh(): void { this.refreshState = 'idle'; this.refreshPrices(); }

  private nowStamp(): string {
    const d = new Date();
    const p = (n: number) => String(n).padStart(2, '0');
    const mon = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][d.getMonth()];
    return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())} ${p(d.getDate())}-${mon}-${d.getFullYear()}`;
  }

  autoRefreshAll(trigger: string = 'manual'): void {
    const now = new Date().toISOString();
    this.currentTop10LastUpdated = now;
    this.previousTop10LastUpdated = now;
    this.trendLastUpdated = now;
    this.clearUploadedCache();
    this.loadDashboard();
    this.loadRanking();
    this.loadChartData();
    this.loadUploadedStocks(true);
    this.loadUploadStats();
    this.loadPositions();
    this.recalcAllocation();
  }

  // ── Pipeline sequential module polling ──────────────────────────────────

  startPipelinePolling(jobId: string): void {
    this.stopPipelinePolling();
    this.pipelineJobId = jobId;
    this.pipelineActive = true;
    this.pipelineProgress = 0;
    this.pipelineStages = [];
    this.pipelineCompletedStages.clear();
    this.showUploadModal.set(false);

    const poll = () => {
      if (!this.pipelineJobId || this.destroyed) return;
      this.api.get<any>(`/tracker/jobs/${this.pipelineJobId}/pipeline`).subscribe({
        next: r => {
          const data = r.data || r;
          this.pipelineStages = data.stages || [];
          this.pipelineProgress = data.pipeline_progress || 0;

          for (const stage of this.pipelineStages) {
            if (stage.status === 'COMPLETED' && !this.pipelineCompletedStages.has(stage.stage)) {
              this.pipelineCompletedStages.add(stage.stage);
              this.onPipelineStageCompleted(stage.stage);
            }
          }

          const jobStatus = data.job_status || '';
          if (jobStatus === 'COMPLETED' || jobStatus === 'CANCELLED' || jobStatus === 'FAILED') {
            this.pipelineActive = false;
            this.stopPipelinePolling();
          }
        },
        error: () => {}
      });
    };
    poll();
    this.pipelinePollTimer = setInterval(poll, 2500);
  }

  stopPipelinePolling(): void {
    if (this.pipelinePollTimer) {
      clearInterval(this.pipelinePollTimer);
      this.pipelinePollTimer = null;
    }
  }

  private onPipelineStageCompleted(stage: string): void {
    const now = new Date().toISOString();
    switch (stage) {
      case 'UPLOAD':
        this.loadUploadStats();
        this.loadDashboard();
        break;
      case 'STOCK_DETAILS':
        this.clearUploadedCache();
        this.loadUploadedStocks(true);
        break;
      case 'CURRENT_TOP10':
        this.currentTop10LastUpdated = now;
        this.loadRanking();
        this.recalcAllocation();
        break;
      case 'PREVIOUS_TOP10':
        this.previousTop10LastUpdated = now;
        this.loadRanking();
        break;
      case 'AVG_TREND':
        this.trendLastUpdated = now;
        this.loadChartData();
        break;
    }
  }

  pipelineStageIcon(status: string): string {
    switch (status) {
      case 'COMPLETED': return '✓';
      case 'RUNNING':   return '●';
      case 'FAILED':    return '✗';
      default:          return '○';
    }
  }

  pipelineStageColor(status: string): string {
    switch (status) {
      case 'COMPLETED': return '#22c55e';
      case 'RUNNING':   return '#60a5fa';
      case 'FAILED':    return '#ef4444';
      default:          return '#475569';
    }
  }

  runRanking() {
    this.ranking.set(true);
    this.api.post<any>('/tracker/rank', {}).subscribe({
      next: r => { this.rd = r.data; this.ranking.set(false); this.autoRefreshAll('ranking'); },
      error: () => this.ranking.set(false)
    });
  }

  // â”€â”€ All downloads use authenticated HTTP (JWT token passed in header) â”€â”€â”€â”€â”€â”€â”€
  // window.open() cannot pass Authorization header â€” replaced with HttpClient blob
  downloadEligible()        { this.downloadCsv('/tracker/export/eligible',    'TradeRakshak_Eligible_Stocks.csv'); }
  /** First touch of the base price since it was set, ATR14(D-1)/Close(D-1) >= 3%. No volume or
   *  ADX filter - both failed out-of-sample. The CSV carries the exit bracket with each row. */
  downloadAtrBaseCrossover() {
    this.downloadCsv('/tracker/export/atr-base-crossover?minAtrPct=3.0',
                     'TradeRakshak_ATR_Base_Price_Crossover.csv');
  }
  downloadRankingCurrent()  { this.downloadCsv('/tracker/export/ranking',     'TradeRakshak_Current_Top10.csv'); }
  downloadRankingPrevious() { this.downloadCsv('/tracker/export/previous',    'TradeRakshak_Previous_Top10.csv'); }
  downloadHistorical()      { this.downloadCsv('/tracker/export/historical',  'TradeRakshak_Historical_Rankings.csv'); }
  exportCsv()               { this.downloadCsv('/tracker/export/all-stocks',  'TradeRakshak_All_Stocks.csv'); }
  exportDistribution()      { this.downloadCsv('/tracker/export/distribution','TradeRakshak_Distribution.csv'); }

  downloadUploadedStocks() {
    const params = new URLSearchParams({
      sortBy: this.uploadedSortBy || 'symbol',
      sortDir: this.uploadedSortDir || 'asc'
    });
    this.downloadCsv(`/tracker/export/all-stocks?${params.toString()}`, 'TradeRakshak_Uploaded_Stocks.csv');
  }

  /**
   * Authenticated CSV download â€” fetches with JWT token then triggers browser download.
   * This correctly passes the Authorization header which window.open() cannot do.
   */
  /**
   * Downloads the stocks behind the chart bars, one row per stock per day.
   *
   * <p>Deliberately exports the same figures the chart was drawn from rather than recomputing
   * them, so a number in the spreadsheet can never disagree with the bar above it. Stocks that
   * could not be priced are included with the reason, because a day showing four rows when ten
   * were ranked is otherwise indistinguishable from a day that only ranked four.
   *
   * @param maxPrice price ceiling for the low-priced basket, or null for the full Top 10
   */
  /** Optional past session for the Previous Top 10 download; empty means the current view. */
  prevExportDate = '';

  downloadPrevTop10() {
    const q = this.prevExportDate ? `?runDate=${this.prevExportDate}` : '';
    this.downloadCsv('/tracker/export/prev-top10' + q,
      `TradeRakshak_Previous_Top10_${this.prevExportDate || 'current'}.csv`);
  }

  downloadChartTrades(maxPrice: number | null) {
    // Follow whichever range the matching section is showing, so the file always matches
    // the bars on screen.
    let from: string, to: string;
    if (maxPrice != null) {
      to = this.lowTo || new Date().toISOString().slice(0, 10);
      from = this.lowFrom || new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);
    } else {
      to = this.rangeTo || new Date().toISOString().slice(0, 10);
      from = this.rangeFrom || new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);
    }
    const q = `?from=${from}&to=${to}` + (maxPrice != null ? `&maxPrice=${maxPrice}` : '');
    const tag = maxPrice != null ? `_below${maxPrice}` : '';
    this.downloadCsv('/tracker/export/chart-trades' + q,
                     `TradeRakshak_ChartBar_Stocks_${from}_to_${to}${tag}.csv`);
  }

  downloadCsv(endpoint: string, filename: string) {
    this.http.get(`${environment.apiUrl}${endpoint}`, {
      responseType: 'blob',
      observe: 'response'
    }).subscribe({
      next: (response) => {
        // Extract filename from Content-Disposition header if present
        const contentDisp = response.headers.get('Content-Disposition');
        if (contentDisp) {
          const match = contentDisp.match(/filename=\"?([^\"]+)\"?/);
          if (match) filename = match[1];
        }
        const blob = response.body!;
        const url  = window.URL.createObjectURL(blob);
        const a    = document.createElement('a');
        a.href     = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        window.URL.revokeObjectURL(url);
        document.body.removeChild(a);
      },
      error: (err) => {
        console.error('Download failed:', err);
        // Say what actually went wrong. "Check your connection" was shown even when the server had
        // simply restarted or the session had expired, sending people to look at their network for
        // a problem that was never there.
        const s = err?.status;
        alert(
          s === 0 || s === undefined
            ? 'Could not reach TradeRakshak. It may be restarting - wait a few seconds and try again.'
            : s === 401 || s === 403
              ? 'Your session has expired. Sign in again, then retry the download.'
              : s === 404
                ? 'That download is not available on this server build.'
                : 'The server could not build this file (HTTP ' + s + '). Nothing was downloaded.');
      }
    });
  }

  exportPdf() {
    const rd    = this.rd;
    const top10 = rd?.current_top10 || [];
    const prev10= rd?.previous_top10 || [];

    const f = (v: any, dec = 2) => (v == null || v === '') ? 'â€”' : Number(v).toFixed(dec);
    const p = (v: any) => v == null ? 'â€”' : String(v);
    const retStyle = (v: any) => Number(v) >= 0 ? 'color:green;font-weight:bold' : 'color:red;font-weight:bold';

    const html = `<!DOCTYPE html><html><head><meta charset="UTF-8">
    <title>TradeRakshakâ„¢ Performance Report</title>
    <style>
      body{font-family:Arial,sans-serif;font-size:11px;margin:20px;color:#1e293b}
      h1{color:#1e3a5c;font-size:16px;margin-bottom:4px}
      h2{color:#1e3a5c;font-size:13px;margin:16px 0 6px}
      table{border-collapse:collapse;width:100%;margin-bottom:16px}
      th{background:#1e3a5c;color:#fff;padding:6px 8px;text-align:left;font-size:10px;white-space:nowrap}
      td{border:1px solid #e2e8f0;padding:5px 8px;font-size:10px}
      tr:nth-child(even) td{background:#f8fafc}
      .summary{display:flex;gap:20px;flex-wrap:wrap;background:#f1f5f9;padding:10px;border-radius:6px;margin-bottom:16px}
      .sm{text-align:center}.sm .lbl{font-size:9px;color:#64748b}.sm .val{font-size:16px;font-weight:bold}
      .disclaimer{font-size:9px;color:red;border:1px solid red;padding:6px;border-radius:4px;margin-bottom:12px}
      @media print{body{margin:10px}.no-print{display:none}}
    </style></head><body>
    <h1>TradeRakshakâ„¢ â€” Stock Tracker Performance Report</h1>
    <p style="font-size:10px;color:#64748b">
      Generated: ${new Date().toLocaleString('en-IN')} |
      Run Date: ${p(rd?.run_date)} |
      Eligible Stocks: ${p(rd?.eligible_count)} |
      Run ID: ${p(rd?.run_id)}
    </p>
    <div class="disclaimer">
      âš  TradeRakshakâ„¢ is for EDUCATIONAL purposes only. Not SEBI-registered investment advice.
      Past performance does not guarantee future results.
    </div>

    <!-- Summary stats -->
    <div class="summary">
      <div class="sm"><div class="lbl">Current Top-10 Avg Return</div>
        <div class="val" style="${retStyle(rd?.curr_avg_return)}">${f(rd?.curr_avg_return)}%</div></div>
      <div class="sm"><div class="lbl">Previous Top-10 Avg Return</div>
        <div class="val" style="${retStyle(rd?.prev_avg_return)}">${f(rd?.prev_avg_return)}%</div></div>
      <div class="sm"><div class="lbl">Current Win Ratio</div>
        <div class="val" style="color:#2563eb">${f(rd?.curr_win_ratio)}%</div></div>
      <div class="sm"><div class="lbl">Previous Win Ratio</div>
        <div class="val" style="color:#2563eb">${f(rd?.prev_win_ratio)}%</div></div>
      <div class="sm"><div class="lbl">Current Positive</div>
        <div class="val" style="color:green">${p(rd?.curr_positive)}</div></div>
      <div class="sm"><div class="lbl">Current Negative</div>
        <div class="val" style="color:red">${p(rd?.curr_negative)}</div></div>
    </div>

    <!-- Current Top-10 table -->
    <h2>Today top 10 Ranking Stocks</h2>
    <table>
      <thead><tr>
        <th>Rank</th><th>Symbol</th><th>Sector</th><th>Market Cap</th><th>Score</th>
        <th>Upload Date</th><th>Base Price (INR)</th><th>Current Price (INR)</th>
        <th>Return %</th><th>Days Since Upload</th>
      </tr></thead>
      <tbody>
        ${top10.map((s: any) => `<tr>
          <td style="text-align:center;font-weight:bold">${p(s.rank)}</td>
          <td style="font-weight:bold">${p(s.symbol)}</td>
          <td>${p(s.sector)}</td>
          <td>${p(s.market_cap)}</td>
          <td style="text-align:right;color:#2563eb">${f(s.score)}</td>
          <td>${p(s.upload_date)}</td>
          <td style="text-align:right">${f(s.base_price)}</td>
          <td style="text-align:right;font-weight:bold">${f(s.current_price)}</td>
          <td style="text-align:right;${retStyle(s.return_pct)}">${f(s.return_pct)}%</td>
          <td style="text-align:center">${p(s.days_since_upload)}</td>
        </tr>`).join('')}
      </tbody>
    </table>

    <!-- Previous Top-10 table -->
    <h2>Previous top 10 Ranking Stocks</h2>
    <table>
      <thead><tr>
        <th>Rank</th><th>Symbol</th><th>Sector</th><th>Score</th>
        <th>Buy Price (INR)</th><th>Current Price (INR)</th>
        <th>Return %</th><th>Gain/Loss (â‚¹)</th><th>Days</th>
      </tr></thead>
      <tbody>
        ${prev10.map((s: any, i: number) => {
          const gl = (Number(s.current_price)||0) - (Number(s.base_price)||0);
          return `<tr>
            <td style="text-align:center">${i+1}</td>
            <td style="font-weight:bold">${p(s.symbol)}</td>
            <td>${p(s.sector)}</td>
            <td style="text-align:right;color:#2563eb">${f(s.score)}</td>
            <td style="text-align:right">${f(s.base_price)}</td>
            <td style="text-align:right;font-weight:bold">${f(s.current_price)}</td>
            <td style="text-align:right;${retStyle(s.current_return)}">${f(s.current_return)}%</td>
            <td style="text-align:right;${retStyle(gl)}">${gl >= 0 ? '+' : ''}${f(gl)}</td>
            <td style="text-align:center">${p(s.days_since_upload)}</td>
          </tr>`;
        }).join('')}
      </tbody>
    </table>

    <p style="font-size:9px;color:#94a3b8;margin-top:20px;border-top:1px solid #e2e8f0;padding-top:8px">
      TradeRakshakâ„¢ v11.6 | Report generated on ${new Date().toLocaleString('en-IN')} |
      For educational purposes only | Not SEBI investment advice
    </p>
    </body></html>`;

    const w = window.open('', '_blank');
    if (w) {
      w.document.write(html);
      w.document.close();
      setTimeout(() => w.print(), 500);
    }
  }

  goToConfig() {
    this.showConfigPanel = !this.showConfigPanel;
    this.configError = '';
    this.configSuccess = '';
    if (this.showConfigPanel && !this.weightConfigs.length) this.loadConfig();
  }

  loadConfig() {
    this.configLoading = true;
    this.configError = '';
    this.configSuccess = '';
    this.api.get<any>('/tracker/config').subscribe({
      next: r => {
        this.weightConfigs = r.data?.factors || [];
        this.configLoading = false;
      },
      error: e => {
        this.configError = e.error?.message || 'Could not load ranking factors.';
        this.configLoading = false;
      }
    });
  }

  saveConfig() {
    this.configSaving = true;
    this.configError = '';
    this.configSuccess = '';
    const factors = this.weightConfigs.map(f => ({
      key: f.key,
      weight: Number(f.weight) || 0,
      is_enabled: this.factorEnabled(f)
    }));
    this.api.put<any>('/tracker/config', factors).subscribe({
      next: r => {
        this.configSaving = false;
        if (r?.data?.success === false) {
          this.configError = r.data.error || 'Could not save ranking factors.';
          return;
        }
        this.configSuccess = r.data?.message || 'Ranking factors saved.';
        this.loadConfig();
      },
      error: e => {
        this.configSaving = false;
        this.configError = e.error?.message || 'Could not save ranking factors.';
      }
    });
  }

  configTotal(): number {
    return this.weightConfigs.reduce((s, f) => s + (Number(f.weight) || 0), 0);
  }

  factorEnabled(factor: any): boolean {
    return factor?.is_enabled === true || factor?.is_enabled === 1 || factor?.is_enabled === '1';
  }

  isAdmin(): boolean {
    return this.auth.isAdmin();
  }

  bucketLabelColor(label: string): string {
    if (label.includes('More than 30') || label.includes('> 30')) return '#4ade80';
    if (label.includes('15% to 30'))   return '#86efac';
    if (label.includes('10% to 15'))   return '#a3e635';
    if (label.includes('5% to 10'))    return '#fbbf24';
    if (label.includes('0% to 5'))     return '#93c5fd';
    if (label.includes('-5% to 0'))    return '#94a3b8';
    if (label.includes('-10% to -5'))  return '#f97316';
    if (label.includes('-15% to -10')) return '#ef4444';
    if (label.includes('More than -15') || label.includes('< -15')) return '#dc2626';
    return '#94a3b8';
  }

  // ── Processing Job Dashboard ──

  pollJobStatus() {
    this.stopJobPolling();
    const fetch = () => {
      if (!this.activeJobId) return;
      this.api.get<any>(`/tracker/jobs/${this.activeJobId}`).subscribe({
        next: r => {
          this.activeJob = r.data || r;
          if (this.activeJob.status === 'COMPLETED' || this.activeJob.status === 'CANCELLED') {
            this.stopJobPolling();
          }
        },
        error: () => {}
      });
    };
    fetch();
    this.jobPollTimer = setInterval(fetch, 3000);
    this.loadJobHistory();
  }

  stopJobPolling() {
    if (this.jobPollTimer) {
      clearInterval(this.jobPollTimer);
      this.jobPollTimer = null;
     }
  }

  loadJobHistory() {
    this.api.get<any>('/tracker/jobs').subscribe({
      next: r => this.jobHistory = r.data || [],
      error: () => {}
    });
  }

  selectJob(jobId: string) {
    this.activeJobId = jobId;
    this.pollJobStatus();
  }

  // These used to swallow their errors, so a failed Pause looked exactly like a working one:
  // the button did nothing and said nothing. Report the outcome either way.
  pauseJob() {
    if (!this.activeJobId) return;
    this.api.post<any>(`/tracker/jobs/${this.activeJobId}/pause`, {}).subscribe({
      next: r => { this.jobMessage = r?.data?.message || 'Upload paused.'; this.pollJobStatus(); },
      error: () => { this.jobMessage = 'The upload could not be paused. It may have already finished.'; }
    });
  }

  resumeJob() {
    if (!this.activeJobId) return;
    this.api.post<any>(`/tracker/jobs/${this.activeJobId}/resume`, {}).subscribe({
      next: r => { this.jobMessage = r?.data?.message || 'Upload resumed.'; this.pollJobStatus(); },
      error: () => { this.jobMessage = 'The upload could not be resumed. It may have already finished.'; }
    });
  }

  cancelJob() {
    if (!this.activeJobId) return;
    this.api.post<any>(`/tracker/jobs/${this.activeJobId}/cancel`, {}).subscribe({
      next: () => this.pollJobStatus(),
      error: () => {}
    });
  }

  downloadJobRecords(type: string) {
    if (!this.activeJobId) return;
    const url = `${environment.apiUrl}/tracker/jobs/${this.activeJobId}/export/${type}`;
    const token = this.auth.getToken();
    this.http.get(url, {
      headers: { Authorization: `Bearer ${token}` },
      responseType: 'blob',
      observe: 'response'
    }).subscribe({
      next: (response) => {
        const contentDisp = response.headers.get('Content-Disposition') || '';
        let filename = `${this.activeJobId}_${type}.csv`;
        const match = contentDisp.match(/filename=\"?([^\"]+)\"?/);
        if (match) filename = match[1];
        const blob = response.body!;
        const a = document.createElement('a');
        a.href = window.URL.createObjectURL(blob);
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        window.URL.revokeObjectURL(a.href);
        document.body.removeChild(a);
      },
      error: () => alert('Download failed.')
    });
  }

  jobStatusColor(status: string): string {
    switch (status) {
      case 'RUNNING': return '#60a5fa';
      case 'COMPLETED': return '#4ade80';
      case 'FAILED': return '#ef4444';
      case 'PAUSED': return '#fbbf24';
      case 'CANCELLED': return '#94a3b8';
      default: return '#94a3b8';
    }
  }

  jobStatusBg(status: string): string {
    switch (status) {
      case 'RUNNING': return '#1e3a5f';
      case 'COMPLETED': return '#14532d';
      case 'FAILED': return '#450a0a';
      case 'PAUSED': return '#3a2608';
      case 'CANCELLED': return '#1e293b';
      default: return '#1e293b';
    }
  }
}
