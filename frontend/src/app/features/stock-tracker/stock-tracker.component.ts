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
      <button class="btn-blue" (click)="downloadStochasticCrossover()"
        title="Base price crossover confirmed by volume above the 20-day average and a Stochastic %K/%D bullish cross, on stocks averaging over 100,000 shares a day. Entry is a buy-stop at the base price. The export carries the entry, stop (-5%), target (+15%) and 22-session time exit.">
        Stochastic_Crossover
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

          <div style="display:flex; gap:6px; align-items:center; flex-wrap:wrap; margin-bottom:8px;">
            @if (batchHoldingAvailable) {
              <span style="font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.4px;">Holding period</span>
              @for (dnum of holdingDayChoices; track dnum) {
                <button class="btn-outline" style="font-size:10px; padding:4px 9px;"
                        [style.background]="batchHoldingDay === dnum ? '#1d6adb' : 'transparent'"
                        [style.color]="batchHoldingDay === dnum ? '#fff' : '#cbd5e1'"
                        [style.borderColor]="batchHoldingDay === dnum ? '#1d6adb' : '#334155'"
                        (click)="setBatchHoldingDay(dnum)"
                        [title]="'Return ' + dnum + ' trading day(s) after entry, measured from the entry price'">
                  Day {{ dnum }}
                </button>
              }
            } @else if (trendMode === 'closed') {
              <span style="font-size:10px; color:#64748b;"
                    title="Closed days are stored as one settled next-day figure per ranking run, so there is nothing per-stock left to re-value at a longer hold. Use Backtest or a date range to choose a holding period.">
                Holding period: next close only &mdash; run a backtest or range to choose one
              </span>
            }
            <span style="width:1px; height:16px; background:#334155; margin:0 4px;"></span>
            <span style="font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.4px;">Bars on screen</span>
            @for (n of barsOnScreenChoices; track n) {
              <button class="btn-outline" style="font-size:10px; padding:4px 11px;"
                      [style.background]="barsOnScreen === n ? '#1d6adb' : 'transparent'"
                      [style.color]="barsOnScreen === n ? '#fff' : '#cbd5e1'"
                      [style.borderColor]="barsOnScreen === n ? '#1d6adb' : '#334155'"
                      (click)="setBarsOnScreen(n)"
                      [title]="n === 0 ? 'Draw every bar in the loaded range' : 'Draw the most recent ' + n + ' bars. The figures below follow the same window.'">
                {{ barsLabel(n) }}
              </button>
            }
          </div>

        <!-- Bar chart -->
        <div style="display:flex; align-items:flex-end; gap:4px; height:220px; overflow-x:auto; padding-bottom:26px; position:relative;">
          <div style="position:absolute; bottom:24px; left:0; right:0; height:1px; background:#1e2a3b;"></div>
          @for (d of batchHoldingBars; track d.batch_id) {
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
              (the crossing). <strong>No ranking</strong> &mdash; of the stocks that qualify, the
              <strong>{{ LOW_SECTION_STOCKS }} most heavily traded</strong> are kept, since this is the
              shortlist to actually hold and a position has to be openable. Volume and ATR are shown
              on each row; neither excludes a stock from qualifying. The bars below average these
              same {{ LOW_SECTION_STOCKS }}. To see every crossing instead, use the
              &ldquo;with Volume&rdquo; section beneath.
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
            <span style="width:1px; height:16px; background:#334155; margin:0 4px;"></span>
            <span style="font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.4px;">Bars on screen</span>
            @for (n of barsOnScreenChoices; track n) {
              <button class="btn-outline" style="font-size:10px; padding:4px 11px;"
                      [style.background]="barsOnScreen === n ? '#1d6adb' : 'transparent'"
                      [style.color]="barsOnScreen === n ? '#fff' : '#cbd5e1'"
                      [style.borderColor]="barsOnScreen === n ? '#1d6adb' : '#334155'"
                      (click)="setBarsOnScreen(n)"
                      [title]="n === 0 ? 'Draw every bar in the loaded range' : 'Draw the most recent ' + n + ' bars. The figures below follow the same window.'">
                {{ barsLabel(n) }}
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
            <span style="width:1px; height:16px; background:#334155; margin:0 4px;"></span>
            <span style="font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.4px;">Bars on screen</span>
            @for (n of barsOnScreenChoices; track n) {
              <button class="btn-outline" style="font-size:10px; padding:4px 11px;"
                      [style.background]="barsOnScreen === n ? '#1d6adb' : 'transparent'"
                      [style.color]="barsOnScreen === n ? '#fff' : '#cbd5e1'"
                      [style.borderColor]="barsOnScreen === n ? '#1d6adb' : '#334155'"
                      (click)="setBarsOnScreen(n)"
                      [title]="n === 0 ? 'Draw every bar in the loaded range' : 'Draw the most recent ' + n + ' bars. The figures below follow the same window.'">
                {{ barsLabel(n) }}
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

  private prevAutoTimer: any = null;
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

  pipelineStages: any[] = [];
  pipelineActive = false;
  pipelineProgress = 0;
  private pipelinePollTimer: any = null;

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
  totalTradingAmount = 100000;
  perStockAllocation = 10000;
  // 15:15, matching top10.lock.time - the day's final ranking run is what the panel buys at.
  trendBuyTime = '15:15';
  // 15:00 on the next trading day - fifteen minutes before the 15:15 that buys the next
  // position, so a sell and a buy never land on the same minute.
  trendSellTime = '15:00';
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
  rangeFrom = '';
  rangeTo = '';
  /** Upper bound for every date picker on this screen - no future dates to choose. */
  todayIso = new Date().toISOString().slice(0, 10);
  rangeResult: any = null;

  // Trade Batch state
  tradeBatches: any[] = [];
  tradeBatchSummary: any = {};
  tradeBatchTrend: any[] = [];
  tradeBatchLoading = false;
  tradeBatchSelling = '';
  tradeBatchFilter = 'ALL';

  // Stale Previous Close diagnostic
  staleLoading = false;
  staleResult: any = null;

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

  readonly Math = Math;
  private timer: any;
  private uploadedRequestId = 0;
  private destroyed = false;
  liveStatus: any = {};
  private liveSub?: Subscription;
  private statusSub?: Subscription;

  // Donut segments for sector
  donutSegments: any[] = [];

  readonly SECTOR_COLORS = ['#3b82f6','#22c55e','#f59e0b','#ef4444','#8b5cf6','#06b6d4','#ec4899','#14b8a6','#f97316','#84cc16'];

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

  loadAll() { this.initBelowPricePanel(); this.loadDashboard(); this.loadRanking(); this.loadChartData(); this.loadPositions(); this.loadPortfolios(); this.loadUploadedStocks(); this.recalcAllocation(); this.loadUploadStats(); this.loadTradeBatches(); this.runBacktestTrend(); }

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
      this.runBacktestTrend();
    }, secs * 1000);
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

  /**
   * How many stocks the plain RSI 45 section keeps - the heaviest traded five.
   *
   * This section is read as a shortlist to actually hold, so it stays capped; its sibling
   * "with Volume" lists every crossing and passes no limit. The same number is sent to the
   * chart endpoint, so the bars average these five and not the whole qualifying set.
   */
  readonly LOW_SECTION_STOCKS = 5;

  /** Which holding day the chart is showing. Day 1 is the first trading session after entry. */
  lowHoldingDay = 1;
  /**
   * Daily detail over the first two weeks, then the monthly checkpoints.
   *
   * Every day is measured from the ENTRY price, so Day 30 is entry-to-day-30 and not a chain of
   * thirty one-day moves. Must stay in step with HOLDING_DAY_CHOICES in StockTrackerService -
   * the server emits a dayN_return only for the days listed there, and a day offered here that
   * it does not emit would read as "pending" forever rather than as an error.
   */
  readonly holdingDayChoices = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30];

  /**
   * How many of the most recent bars to draw. 0 means every bar in the loaded range.
   *
   * This trims the chart only at the right-hand edge; it never refetches. The figures printed
   * under a chart are computed from the SAME trimmed set, so the win rate and average always
   * describe the bars actually on screen rather than a wider population behind them.
   */
  barsOnScreen = 30;
  readonly barsOnScreenChoices = [10, 20, 30, 50, 100, 0];

  setHoldingDay(d: number): void { this.lowHoldingDay = d; }
  setVolHoldingDay(d: number): void { this.volHoldingDay = d; }
  setBatchHoldingDay(d: number): void { this.batchHoldingDay = d; }
  setBarsOnScreen(n: number): void { this.barsOnScreen = n; }
  barsLabel(n: number): string { return n === 0 ? 'All' : String(n); }

  /** The newest n entries, or all of them when n is 0 or longer than the series. */
  private lastBars<T>(arr: T[]): T[] {
    const n = Number(this.barsOnScreen) || 0;
    if (!arr || n <= 0 || arr.length <= n) return arr || [];
    return arr.slice(arr.length - n);
  }

  /** Which holding day chart 2 is showing. Only its rebuilt modes carry per-trade prices. */
  batchHoldingDay = 1;

  /**
   * True when chart 2 is drawn from rebuilt trades, which carry each stock's forward closes.
   *
   * The "closed" mode reads tracker_rankings.trade_avg_return - one stored number per day,
   * settled next-day - so there is nothing in it to re-value at Day 30. Rather than show a
   * day-1 figure under a "Day 30" heading, the selector is hidden in that mode.
   */
  get batchHoldingAvailable(): boolean {
    return this.trendMode !== 'closed'
        && (this.tradeBatchTrend || []).some((d: any) => (d.trades || []).length > 0);
  }

  get batchHoldingBars(): any[] {
    const bars = this.lastBars(this.tradeBatchTrend || []);
    if (!this.batchHoldingAvailable) return bars;
    return bars.map((d: any) => {
      const rs = (d.trades || [])
        .map((t: any) => this.tradeDayReturn(t, this.batchHoldingDay))
        .filter((v: number | null) => v != null) as number[];
      const avg = rs.length ? rs.reduce((a, b) => a + b, 0) / rs.length : null;
      return { ...d, avg_return_pct: avg == null ? 0 : avg, day_return: avg, day_trades: rs.length };
    });
  }

  get volHoldingBars(): any[] {
    return this.lastBars(this.volTrend || []).map(d => {
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
    // The visible window, not the whole loaded range: a win rate printed under a chart has to
    // be the win rate OF that chart. Reading the full series here would put one population in
    // the bars and a different one in the line of figures below them.
    for (const d of this.lastBars(this.volTrend || [])) {
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
    // Top 10 by raw traded volume across every candidate, not the whole shortlist.
    // applyRanking=false already sorts the pool heaviest-traded first, so limit=10 takes the ten
    // most heavily traded - which is what "maximum volume compared to all stocks" asks for, and
    // deliberately not the volume-above-average test the table below applies.
    this.api.get<any>('/tracker/ranking/provisional-top10',
                      { requireCrossing: 'true', applyRanking: 'false', limit: '10' }).subscribe({
      next: r => { this.volProvisional = r?.data || null; },
      error: () => { this.volProvisional = null; }
    });
    // limit=10: of everything that qualified, show the ten most heavily traded. Applied after
    // identification, so it narrows the view rather than changing which stocks pass the test.
    this.api.get<any>('/tracker/ranking/below-price-top10',
                      { requireVolume: 'true', applyRanking: 'false',
                        priceAtClose: 'true', limit: '10' }).subscribe({
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
    return this.lastBars(this.lowTrend || []).map(d => {
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
    // Same window as the bars above - see volHoldingStats.
    for (const d of this.lastBars(this.lowTrend || [])) {
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
                      // Heading into tomorrow previews what this section will hold, so it
                      // carries the section's own five-stock cap. Without it the preview
                      // listed every crossing and promised a holding twice the size of the
                      // one the table beneath it would actually take.
                      { requireCrossing: 'true', applyRanking: 'false',
                        limit: String(this.LOW_SECTION_STOCKS) }).subscribe({
      next: r => { this.provisional = r?.data || null; },
      error: () => { this.provisional = null; }
    });
    // requireVolume=false: this section is defined by its price rules alone. Volume is still
    // reported on every row, it just no longer excludes a stock.
    // applyRanking=false: it is a screen, not a Top 10. Scoring would cut qualifying stocks out
    // of a list whose whole purpose is to show which ones qualify.
    this.api.get<any>('/tracker/ranking/below-price-top10',
                      { requireVolume: 'false', applyRanking: 'false',
                        priceAtClose: 'true', limit: String(this.LOW_SECTION_STOCKS) }).subscribe({
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
      // Same five stocks the table keeps, so a bar is the average of the rows above it.
      applyRanking: 'false', requireVolume: 'false', priceAtClose: 'true',
      limit: String(this.LOW_SECTION_STOCKS)
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
          // Carried through so the holding selector can re-value this chart. The server was
          // already sending these rows; this mapping simply dropped them on the floor.
          trades:         x.priced || [],
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

  get batchTrendAvg(): number {
    if (!this.tradeBatchTrend.length) return 0;
    const sum = this.tradeBatchTrend.reduce((s: number, d: any) => s + (Number(d.avg_return_pct) || 0), 0);
    return Math.round((sum / this.tradeBatchTrend.length) * 100) / 100;
  }

  get batchTrendMax(): number {
    return Math.max(1, ...this.batchHoldingBars.map((d: any) => Math.abs(Number(d.avg_return_pct) || 0)));
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

  /** Slice the full dataset to the selected periods â€” called on period change */
  sliceChartData() {
    this.chart1Data = this.chartData.slice(-this.chart1Period);
    this.chart2Data = this.chartData.slice(-this.chart2Period);
  }

  // â”€â”€ Stats computed â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  get profitPct()   { return this.dash.total > 0 ? (this.dash.positive / this.dash.total)*100 : 0; }
  get lossPct()     { return this.dash.total > 0 ? (this.dash.negative / this.dash.total)*100 : 0; }
  get eligiblePct() { return this.dash.total > 0 ? (this.dash.eligible / this.dash.total)*100 : 0; }

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

  barH(val: number, max: number): number {
    return Math.max(2, Math.round((Math.abs(val) / max) * 72));
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

  stopPipelinePolling(): void {
    if (this.pipelinePollTimer) {
      clearInterval(this.pipelinePollTimer);
      this.pipelinePollTimer = null;
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

  // â”€â”€ All downloads use authenticated HTTP (JWT token passed in header) â”€â”€â”€â”€â”€â”€â”€
  // window.open() cannot pass Authorization header â€” replaced with HttpClient blob
  downloadEligible()        { this.downloadCsv('/tracker/export/eligible',    'TradeRakshak_Eligible_Stocks.csv'); }
  /** First touch of the base price since it was set, ATR14(D-1)/Close(D-1) >= 3%. No volume or
   *  ADX filter - both failed out-of-sample. The CSV carries the exit bracket with each row. */
  downloadStochasticCrossover() {
    this.downloadCsv('/tracker/export/stochastic-crossover',
                     'TradeRakshak_Stochastic_Crossover.csv');
  }

  downloadUploadedStocks() {
    const params = new URLSearchParams({
      sortBy: this.uploadedSortBy || 'symbol',
      sortDir: this.uploadedSortDir || 'asc'
    });
    this.downloadCsv(`/tracker/export/all-stocks?${params.toString()}`, 'TradeRakshak_Uploaded_Stocks.csv');
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
