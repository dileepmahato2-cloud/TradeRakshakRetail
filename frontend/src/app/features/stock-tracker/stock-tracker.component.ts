import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { LivePriceService } from '../../core/services/live-price.service';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { Subject, Subscription, timeout, takeUntil } from 'rxjs';

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

    /* ── Responsive ───────────────────────────────────────────────────────
       Data tables are NOT reflowed into cards: fourteen numbers per stock
       stacked vertically is five screens of scrolling to compare two rows,
       and comparing rows is the job. They scroll sideways with the symbol
       column pinned, so a number always has a name against it. Nothing is
       hidden on a phone that is shown on a desktop. */
    .rwd-scroll { width:100%; overflow-x:auto; -webkit-overflow-scrolling:touch; }
    .rwd-scroll > table { border-collapse:collapse; width:100%; }
    @media (max-width:1024px) { .page-shell { grid-template-columns:minmax(0,1fr) !important; } }
    @media (max-width:820px) {
      .sec-head { flex-direction:column !important; align-items:stretch !important; gap:9px !important; }
      .sec-head .kpi-row { width:100%; }
      .sec-head .kpi-row > div { flex:1 1 140px; min-width:0 !important; }
      /* 3px padding and a 9px font is a mouse target, not a thumb. */
      .sec-head .btn-outline, .ctl-row .btn-outline { padding:7px 12px !important; font-size:11px !important; min-height:32px; }
      .btn-row { padding:7px 11px !important; font-size:10.5px !important; min-height:32px; }
      .ctl-row .select-sm, .ctl-row input { min-height:32px; font-size:12px; }
      /* max-content keeps columns at their natural width; without it the
         browser squeezes them and figures wrap mid-number. */
      .rwd-scroll > table { min-width:max-content; white-space:nowrap; }
      .rwd-scroll > table th:first-child, .rwd-scroll > table td:first-child {
        position:sticky; left:0; z-index:2; box-shadow:1px 0 0 #22344d; }
      .rwd-scroll > table th:first-child { background:#1a3a5c; }
      .rwd-scroll > table td:first-child { background:#0d1829; }
    }
    @media (max-width:560px) {
      .card { padding:10px !important; }
      .sec-title { font-size:12.5px !important; }
      .sec-desc { font-size:10px !important; }
      .sec-head .kpi-row > div { flex:1 1 100% !important; }
      .rwd-scroll > table th, .rwd-scroll > table td { padding:6px 8px !important; }
      /* iOS zooms the page when a focused field is under 16px. */
      .ctl-row input, .ctl-row .select-sm { font-size:16px !important; }
    }
    @media (prefers-reduced-motion: reduce) {
      * { animation-duration:.01ms !important; transition-duration:.01ms !important; }
    }
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

    /* One settings field: label, control, and the sentence that says what it does. The hint is
       part of the field, not a tooltip, because a number whose meaning is only discoverable by
       hovering gets set wrong. */
    .auto-f { display:flex; flex-direction:column; gap:4px; background:#0f172a;
              border:1px solid #233852; border-radius:8px; padding:9px 11px; }
    .auto-l { font-size:9px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px;
              font-weight:700; }
    .auto-h { font-size:9px; color:#64748b; line-height:1.5; }
    .auto-f .select-sm { width:100%; }

    /* Buy and Sell are the only two controls on this screen that commit money, so they are the
       only two that carry colour. Green and red alone would fail for a red-green reader, so the
       label carries the meaning and the colour only reinforces it. */
    .btn-buy  { color:#5ff5cd; border-color:#16d6a4; background:#0b3b37; font-weight:800; }
    .btn-buy:hover  { background:#11564f; }
    .btn-sell { color:#fecaca; border-color:#ef4444; background:#3b1116; font-weight:800; }
    .btn-sell:hover { background:#58181f; }
    .btn-buy[disabled], .btn-sell[disabled] { opacity:.55; cursor:not-allowed; }

    /* Row-level Buy / Sell. Small enough to sit in a data row without stretching it, but still
       32px tall on a touch screen via the media query above. */
    .btn-row { font-size:9px; padding:3px 9px; border-radius:5px; cursor:pointer; font-weight:800;
               border:1px solid; background:transparent; }
    .btn-row.buy  { color:#5ff5cd; border-color:#16d6a4; }
    .btn-row.sell { color:#fca5a5; border-color:#b91c1c; }
    .btn-row.buy:hover  { background:#0b3b37; }
    .btn-row.sell:hover { background:#3b1116; }
    .btn-row[disabled] { opacity:.5; cursor:not-allowed; }
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
    /* See the comment on .uploaded-table-wrap in the template. */
    @media (max-width:900px) {
      .uploaded-table-wrap { overflow-x:auto; -webkit-overflow-scrolling:touch; }
      .uploaded-table-wrap > table { table-layout:auto !important; min-width:860px; }
      .uploaded-table-wrap th, .uploaded-table-wrap td { white-space:nowrap; }
    }
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
      <div class="ctl-row" style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
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

  <!-- Live Price Feed.
       The eight throughput tiles that used to sit beside this (Subscribed Symbols, Live
       Ticks/sec, DB Updates/sec, Screen Updates/sec, Reconnects, Last Tick, Processing,
       System Load) were instrumentation, not information: they told you how hard the feed
       was working, never whether your prices were current. With them gone the panel is a
       single column, so the status line and both alerts run the full width instead of being
       squeezed into a narrow left-hand third - and they stack rather than overflow on a phone. -->
  <div class="card" style="padding:14px; margin-bottom:14px;">
    <div style="background:#132237; border:1px solid #28415f; border-radius:8px; padding:14px;
                display:flex; flex-direction:column; gap:12px; min-width:0;">
      <div style="display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap;">
        <div style="display:flex; align-items:center; gap:10px; min-width:0; flex:1 1 240px;">
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

      <!-- auto-fit, not a fixed three columns: on a phone these become one per row
           instead of three unreadable slivers. -->
      <div style="display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:8px;">
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

      @if (liveStatus.lastApiError) {
        <div style="color:#fca5a5; background:#3b1116; border:1px solid #7f1d1d; border-radius:7px; padding:9px 10px; font-size:11px; line-height:1.5; overflow-wrap:anywhere;">
          <strong>Needs attention:</strong> {{ friendlyLiveError() }}
        </div>
      }
      @if ((liveStatus.missingMappingCount || 0) > 0) {
        <div style="color:#fde68a; background:#3a2608; border:1px solid #d97706; border-radius:7px; padding:9px 10px; font-size:11px; line-height:1.5; overflow-wrap:anywhere;">
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
            <div class="rwd-scroll">
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
    <div class="rwd-scroll">
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
        <div class="sec-head" style="display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:8px;">
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

    <!-- Fits the page on a desktop; scrolls sideways on a phone.
         table-layout:fixed divides the width equally between the columns, which is right on a
         wide screen and unusable on a narrow one - fourteen columns across 380px is 27px each,
         so every heading and price wrapped into an unreadable stack. Below 900px the wrapper
         turns into a scroller and the table is given a sensible minimum instead. -->
    <div class="uploaded-table-wrap">
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
    <div class="page-shell" style="display:grid; grid-template-columns:minmax(0, 1.6fr) minmax(360px, .9fr); gap:12px; align-items:start;">



      <!-- Chart 3: same strategy, restricted to low-priced stocks -->
      <!-- ══ Data availability ═══════════════════════════════════════════════
           The strategies below run whether or not the data behind them has arrived. When it
           has not, a section shows nothing and nothing explains why - and a day on which no
           stock qualified looks exactly like a day the platform could not evaluate. Those need
           opposite responses: one is patience, the other is a backfill. Every finding here says
           what is missing, what it stops the screens showing, and what fixes it. -->
      @if (dataHealth && !dataHealth.ok && dataHealth.issues?.length) {
        <div class="card" style="padding:12px 14px; grid-column:1 / -1; margin-bottom:2px;
                    border-left:4px solid #d97706;">
          <div style="display:flex; align-items:center; gap:9px; flex-wrap:wrap; margin-bottom:8px;">
            <span style="font-size:13px; font-weight:800; color:#fbbf24;">
              &#9888; Some data these sections need is missing
            </span>
            <span style="font-size:10.5px; color:#94a3b8;">{{ dataHealth.summary }}</span>
            <button class="btn-outline" style="font-size:9px; padding:3px 10px; margin-left:auto;"
                    (click)="loadDataHealth()" [disabled]="dataHealthLoading">
              @if (dataHealthLoading) { <span class="spin"></span> Checking... } @else { Re-check }
            </button>
          </div>
          @for (i of dataHealth.issues; track i.code) {
            <div style="background:#0f172a; border:1px solid #233852; border-radius:7px;
                        padding:8px 10px; margin-bottom:6px; font-size:10.5px; line-height:1.6;">
              <div style="color:#fde68a; font-weight:700;">{{ i.what }}</div>
              <div style="color:#cbd5e1; margin-top:2px;">
                <strong style="color:#94a3b8;">Effect:</strong> {{ i.blocks }}
              </div>
              <div style="color:#8fa3bd; margin-top:2px;">
                <strong style="color:#94a3b8;">Fix:</strong> {{ i.fix }}
              </div>
            </div>
          }
          <div style="font-size:9.5px; color:#64748b; line-height:1.6;">
            The entry logic below is unchanged and will populate on its own once the data is
            there &mdash; nothing needs re-configuring.
          </div>
        </div>
      }

      <div class="card" style="padding:12px; grid-column:1 / -1;">
        <div class="sec-head" style="display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:8px;">
          <div style="flex:1 1 420px;">
            <span class="sec-title" style="font-size:13px; font-weight:800; color:#60a5fa;">
              RSI 45 Previous Close Less the Base Price with Volume &amp; Stochastic
            </span>
            <div class="sec-desc" style="font-size:10px; color:#94a3b8; margin-top:3px; line-height:1.6;">
              Entry test, all of which must hold: Previous Close &lt; Base Price
              <strong>and</strong> Current Price &ge; Base Price (the crossing),
              <strong>and</strong> Current Day Volume &gt; the average volume of the previous
              <strong>20 completed trading sessions</strong> (the current session excluded),
              <strong>and</strong> daily <strong>RSI(14) at or above 45</strong>@if (lowRequireStoch) {,
              <strong>and</strong> stochastic <strong>%K crossed above %D</strong> on this session}.
              <strong>No ranking</strong> &mdash; the <strong>{{ LOW_SECTION_STOCKS }} highest volume
              ratios</strong> are shortlisted. The volume ratio orders the list; it never excludes
              a stock. The bars below average these same {{ LOW_SECTION_STOCKS }}, bought at the
              <strong>{{ lowTop?.frozen_time || '15:15' }}</strong> cut-off price and sold at the
              next trading day's close &mdash; when that day is not a working day, the next
              working day's close.
            </div>
          </div>

        </div>

        <!-- Capital Allocation moved to Strategy > Auto Buy & Sell, under Automation:
             that screen is where the money is committed, and its Capital to deploy is
             the same figure, so there is now one number rather than two. -->

        <!-- Which session this section is answering for, and how far behind today that is.
             This is the section people trade from, so a list built from a close several days
             old has to say so on its own face - the per-row "previous close" dates below show
             it too, but only once you are already reading a row. -->
        <!-- What the table below actually is.
             It lists the stocks that qualified on the last COMPLETED session, not today's.
             Today's candidates sit in the panel beneath and only become this list after the
             15:15 cut-off. Without a heading saying so, a stock crossing today looks as though
             it has been put in the wrong place.

             Outside the sec-head flex row on purpose: placed inside it, this rendered as a
             third flex item wedged between the description and the KPI boxes. -->
        <!-- Heading left, the section's own controls and headline figure right.
             They used to sit in a band above this heading, which put the controls for the
             "Yesterday Qualified Stocks" table further from it than the table's own caption. -->
        <div id="rsi45HeadControls"
             style="display:flex; align-items:center; justify-content:space-between; gap:12px;
                    flex-wrap:wrap; margin:10px 0 4px;">
          <span style="font-size:11px; font-weight:800; color:#facc15;">
            Yesterday Qualified Stocks
          </span>
          <div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap;">
            @if (lowTop?.top10?.length) {
              <!-- Today Avg Return: the arithmetic mean of the VALID rows' Return % for the
                   selected holding period - entry price to that session's close. A stock whose
                   exit session has not closed is excluded, never counted as 0%, and the counts
                   are shown so a thin average is not read as a full one. -->
              <span style="display:inline-flex; align-items:baseline; gap:6px;
                           background:#0f172a; border-radius:5px; padding:3px 9px;"
                    [title]="'Average of the return of each stock from its entry price to the Day '
                             + lowTop.holding_days + ' close'
                             + (lowTop.exit_trading_date ? ' (' + lowTop.exit_trading_date + ')' : '')
                             + '. Stocks with no entry or no settled exit are excluded.'">
                <span style="font-size:8.5px; color:#94a3b8; text-transform:uppercase;
                             letter-spacing:.5px;">Today Avg Return</span>
                @if (lowTop.holding_avg_return_pct == null) {
                  <span style="font-size:13px; font-weight:800; color:#94a3b8;">&mdash;</span>
                  <span style="font-size:8.5px; color:#64748b;">Average Return Unavailable</span>
                } @else {
                  <span style="font-size:13px; font-weight:800;"
                        [class]="lowTop.holding_avg_return_pct >= 0 ? 'pos' : 'neg'">
                    {{ lowTop.holding_avg_return_pct > 0 ? '+' : '' }}{{ lowTop.holding_avg_return_pct | number:'1.2-2' }}%
                  </span>
                  <span style="font-size:8.5px; color:#64748b;">
                    Day {{ lowTop.holding_days }}
                    @if (lowTop.exit_trading_date) { &middot; exit {{ lowTop.exit_trading_date }} }
                    &middot; {{ lowTop.holding_stocks_included }} in
                    @if (lowTop.holding_stocks_excluded) { , {{ lowTop.holding_stocks_excluded }} out }
                  </span>
                }
              </span>
            }
              <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                      (click)="strategyDetailsOpen = true"
                      title="The complete entry test, shortlist rule and bar-chart basis for this section.">
                Strategy details
              </button>
              <button class="btn-outline" style="font-size:9px; padding:3px 12px;"
                      (click)="refreshLowSection()" [disabled]="lowTopLoading || lowRefreshing"
                      title="Re-run the entry test at the latest available prices and re-read every figure in this section, including the bars.">
                @if (lowTopLoading || lowRefreshing) { <span class="spin"></span> Refreshing... } @else { Refresh }
              </button>
              <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                      (click)="toggleLowShowAll()" [disabled]="lowTopLoading"
                      title="The screen shows the top 5 by volume ratio. This lists every stock that passed the entry test.">
                {{ lowShowAll ? 'Show top ' + LOW_SECTION_STOCKS : 'View all qualifying' }}
              </button>
              <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                      (click)="downloadRsi45CurrentDaySection()"
                      title="Every stock that passed the entry test, not just the five on screen.">
                Download all qualifying
              </button>
          </div>
        </div>

        @if (lowTop?.as_of) {
          <div style="font-size:10px; line-height:1.6; border-radius:6px; padding:7px 10px;
                      margin-bottom:9px;"
               [style.color]="(lowTop.days_behind_today || 0) > 1 ? '#fde68a' : '#9eb8dd'"
               [style.background]="(lowTop.days_behind_today || 0) > 1 ? '#3a2608' : '#0f172a'"
               [style.border]="'1px solid ' + ((lowTop.days_behind_today || 0) > 1 ? '#d97706' : '#233852')">
            <!-- The session sentence changes; everything after it does not. Keeping the
                 count outside the branch matters most in the live case, where "how many
                 qualified" is exactly what someone is watching. -->
            @if (lowTop.live_session) {
              Entry test run on <strong>{{ lowTop.evaluating_session }}</strong>
              <strong style="color:#5ff5cd;">&middot; LIVE</strong>
            } @else {
              Entry test run on the <strong>{{ lowTop.as_of }}</strong> session
            }
            &middot; {{ lowTop.qualifying_count || lowTop.count || 0 }} qualified
            @if (!lowShowAll && (lowTop.qualifying_count || 0) > LOW_SECTION_STOCKS) {
              <span>, showing the top {{ LOW_SECTION_STOCKS }} by volume ratio &mdash;
              press <strong>View all qualifying</strong> for the rest</span>
            }
            @if (lowTop.live_session) {
              <span style="color:#9eb8dd;">. Today is measured from the running feed, so volume
              is a running total &mdash; a stock can qualify later in the day that did not
              earlier.</span>
            }
            @if ((lowTop.days_behind_today || 0) > 1) {
              <span>. <strong>{{ lowTop.days_behind_today }} days behind today.</strong>
              Later daily closes are not stored, so a stock crossing since then cannot appear
              here whatever the entry test says.</span>
            }
          </div>
        }

        <!-- Identify -> Rank -> Buy/Sell, in that order. The list below is the Top 10 that
             survived identification, scored by the existing engine, with the existing signal. -->
        @if (lowTop?.top10?.length) {
          <div style="overflow-x:auto; margin-bottom:10px;">
            <table style="width:100%; border-collapse:collapse; min-width:900px;">
              <thead>
                <tr>
                  <th class="sb-hdr" style="text-align:center;">Rank</th>
                  <th class="sb-hdr">Symbol</th><th class="sb-hdr">Stock Name</th>
                  <th class="sb-hdr">Sector</th>
                  <th class="sb-hdr" style="text-align:right;" title="Price on the day before - must be below the base price">Prev Close</th>
                  <th class="sb-hdr" style="text-align:right;">Base Price</th>
                  <th class="sb-hdr" style="text-align:right;" title="Traded price at the cut-off on the day this list was frozen - the price the position was opened at">Entry &#64; {{ lowTop?.top10?.[0]?.entry_time || '15:15' }}</th>
                  <th class="sb-hdr" style="text-align:right;" title="Must be at or above the base price - together with the previous close below it, that is the crossing">Current</th>
                  <th class="sb-hdr" style="text-align:right;" title="Move since the entry price, not since the base price">Return %</th>
                  <th class="sb-hdr" style="text-align:right;" title="Traded volume, which had to beat the 20-day average to qualify">Volume</th>
                  <th class="sb-hdr" style="text-align:right;">20D Avg Vol</th>
                  <th class="sb-hdr" style="text-align:right;" title="Current day volume divided by the 20-session average. The shortlist is ordered by this.">Ratio</th>
                  <th class="sb-hdr" style="text-align:right;">RSI</th>
                  <th class="sb-hdr" style="text-align:right;" title="Stochastic %K, with an arrow when %K crossed above %D on this session.">Stoch</th>
                  <th class="sb-hdr" style="text-align:center;" title="From the existing Buy/Sell logic, applied after ranking">Signal</th>
                  <th class="sb-hdr">Trend</th>
                  <!-- Per-row Buy / Sell, so one stock can be acted on without ticking it and
                       re-sizing the whole allocation. A row buy deploys the panel's amount into
                       that one stock; the panel above remains the way to split across several. -->
                  <th class="sb-hdr" style="text-align:center;" title="Buy or sell this one stock, on its own">Action</th>
                </tr>
              </thead>
              <tbody>
                @for (r of lowTop.top10; track r.symbol) {
                  <tr>
                    <td class="sb-cell" style="text-align:center; font-weight:700;"
                        [style.color]="r.rank === 1 ? '#facc15' : r.rank <= 3 ? '#94a3b8' : '#64748b'">{{ r.rank }}</td>
                    <td class="sb-cell" style="color:#fff; font-weight:700;">{{ r.symbol }}</td>
                    <td class="sb-cell" style="color:#9eb8dd; max-width:170px; overflow:hidden;
                        text-overflow:ellipsis; white-space:nowrap;"
                        [title]="r.company_name || r.symbol">{{ r.company_name || '-' }}</td>
                    <td class="sb-cell" style="color:#cbd5e1;">{{ r.sector || '-' }}</td>
                    <!-- The session this close belongs to, under the figure. Without it a
                         four-session-old close reads exactly like yesterday's. -->
                    <td class="sb-cell" style="text-align:right; color:#94a3b8;">
                      {{ r.previous_close | number:'1.2-2' }}
                      @if (r.previous_close_date) {
                        <div style="font-size:8.5px; color:#64748b;">{{ r.previous_close_date }}</div>
                      }
                    </td>
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
                    <!-- The selection key, beside the two figures it is built from. -->
                    <td class="sb-cell" style="text-align:right; font-weight:800;"
                        [style.color]="ratioColor(r.volume_ratio)"
                        [title]="'Current day volume over the average of ' +
                                 (r.volume_sessions || 20) + ' completed session(s).' +
                                 (r.volume_window_full === false
                                   ? ' Fewer than 20 - this stock has less history, so its ratio is not directly comparable with a full-window one.'
                                   : '')">
                      {{ r.volume_ratio ? (r.volume_ratio | number:'1.2-2') + 'x' : '-' }}
                      <!-- A short window is stated, not hidden and not used to exclude the
                           stock. Comparability is the reader's call to make, and they can only
                           make it if the number of sessions is on the row. -->
                      @if (r.volume_window_full === false) {
                        <div style="font-size:8px; font-weight:700; color:#fbbf24;">{{ r.volume_sessions }}d</div>
                      }</td>
                    <td class="sb-cell" style="text-align:right; color:#cbd5e1;">
                      {{ r.rsi14 ? (r.rsi14 | number:'1.1-1') : '-' }}</td>
                    <!-- The arrow marks a crossover TODAY, which is the signal. %K merely being
                         above %D is a state, and a stock that has held it for a week is not
                         signalling anything now. -->
                    <td class="sb-cell" style="text-align:right;"
                        [style.color]="r.stoch_cross_up ? '#22c55e' : '#94a3b8'"
                        [title]="r.stoch_k == null ? '' : ('%K ' + (r.stoch_k | number:'1.1-1') + ' / %D ' + (r.stoch_d | number:'1.1-1') + (r.stoch_cross_up ? ' - crossed above today' : ' - no crossover today'))">
                      @if (r.stoch_cross_up) { &#9650; } {{ r.stoch_k ? (r.stoch_k | number:'1.0-0') : '-' }}</td>
                    <td class="sb-cell" style="text-align:center;">
                      <span style="font-weight:800; padding:2px 8px; border-radius:4px;"
                            [style.color]="r.signal === 'BUY' ? '#22c55e' : r.signal === 'SELL' ? '#ef4444' : '#94a3b8'"
                            [style.background]="r.signal === 'BUY' ? '#0d2818' : r.signal === 'SELL' ? '#2a0f0f' : '#16202e'"
                            [title]="r.signal_message || ''">{{ r.signal }}</span>
                    </td>
                    <td class="sb-cell"
                        [style.color]="r.trend === 'Bullish' ? '#22c55e' : r.trend === 'Bearish' ? '#ef4444' : '#94a3b8'">{{ r.trend || '-' }}</td>
                    <td class="sb-cell" style="text-align:center; white-space:nowrap;">
                      <button class="btn-row buy" [disabled]="volBreakPlacing"
                              (click)="buyVolBreakoutSymbols([r.symbol], r.symbol + ' only, from Current Day Stocks.')"
                              title="Open a paper position in this stock alone">Buy</button>
                      <button class="btn-row sell" style="margin-left:5px;" [disabled]="volBreakSelling"
                              (click)="sellVolBreakoutSymbols([r.symbol], r.symbol + ' only, from Current Day Stocks.')"
                              title="Close the open paper position in this stock at the price read now">Sell</button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <!-- Heading into tomorrow - Provisional.
               This replaced a "provisional" preview, and the difference is the whole point. The
               preview recalculated the next selection every time it was asked, so its membership
               moved with prices. This reads the IMMUTABLE snapshot written at the cut-off on the
               previous trading day, so the five identities cannot change during a session - a
               stock that no longer qualifies is retained, and a newly qualifying one cannot
               enter until the next finalization replaces the whole list. Only the market data
               moves. -->
          <div style="border:1px dashed #3a4a5f; border-radius:6px; padding:9px 12px; margin-bottom:10px;">
            <div id="prevDayHeadControls"
                 style="display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:6px;">
              <span style="font-size:11px; font-weight:800; color:#facc15;">
                Heading into tomorrow &mdash; Provisional
              </span>
              <!-- This section's own controls, on the heading line.
                   Finalize lives here rather than on the section above: this IS the frozen
                   shortlist, so freezing a day is an action on this list, not on the table of
                   what already qualified. -->
              <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                <button class="btn-outline" style="font-size:9px; padding:3px 12px;"
                        (click)="loadVolumeBreakoutPrevious()" [disabled]="volPrevLoading"
                        title="Re-read the frozen shortlist and refresh its market data. The five identities cannot change - only their prices.">
                  @if (volPrevLoading) { <span class="spin"></span> Refreshing... } @else { Refresh }
                </button>
                <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                        (click)="openPrevDayAllQualified()" [disabled]="prevDayAllLoading"
                        title="Every stock that passed the entry test on that session, not just the five that were shortlisted.">
                  @if (prevDayAllLoading) { <span class="spin"></span> } @else { View all qualifying }
                </button>
                <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                        (click)="downloadVolumeBreakout()" [disabled]="!volPrev?.top10?.length"
                        title="Export this frozen shortlist with its entry, current and return figures.">
                  Download
                </button>
                <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                        (click)="finalizeVolumeBreakout()" [disabled]="volFinalizing"
                        title="Freeze today's shortlist now instead of waiting for the cut-off. A day already finalized is never recalculated - that is what makes it final.">
                  {{ volFinalizing ? 'Working...' : 'Finalize today' }}
                </button>
              </div>
            <!-- When this list was frozen, and when its prices were last read. Its own line,
                 between the heading and the figure below, so the heading row carries the
                 controls alone and the two timestamps are not read as part of the average. -->
            <div id="provisionalMeta" style="font-size:9.5px; color:#94a3b8; margin-bottom:6px;">
                @if (volPrev?.trade_date) {
                Finalized {{ volPrev.trade_date }}
                @if (volPrev.finalized_at) { at {{ volPrev.finalized_at }} }
                @if (volPrev.data_complete === false) {
                  <span style="color:#facc15;" title="Fewer than the full shortlist had a complete 20-session window that day. Recorded short rather than padded.">
                    &middot; stored short</span>
                }
              } @else { Identities frozen at the cut-off and fixed for the whole session }
              @if (volPrevReadAt) {
                <span style="margin-left:6px; color:#64748b;">&middot; prices read {{ volPrevReadAt }}</span>
              }
            </div>

            <!-- Today Avg Return, once. There were two on screen: this one and a full KPI tile
                 directly beneath it, both reporting the same number. -->
            @if (volPrev?.top10?.length) {
              <div style="margin-bottom:8px;">
                <span style="display:inline-flex; align-items:baseline; gap:6px;
                             background:#0f172a; border-radius:5px; padding:3px 9px;"
                      title="Arithmetic mean of the Return % of the stocks listed below. A stock already sold contributes its settled return, measured to the price it was actually sold at rather than to the price now.">
                  <span style="font-size:8.5px; color:#94a3b8; text-transform:uppercase;
                               letter-spacing:.5px;">Today Avg Return</span>
                  @if (volPrev?.avg_return_pct == null) {
                    <span style="font-size:14px; font-weight:800; color:#94a3b8;">&mdash;</span>
                    <span style="font-size:8.5px; color:#64748b;">no entry price stored yet</span>
                  } @else {
                    <span style="font-size:14px; font-weight:800;"
                          [class]="volPrev.avg_return_pct >= 0 ? 'pos' : 'neg'">
                      {{ volPrev.avg_return_pct > 0 ? '+' : '' }}{{ volPrev.avg_return_pct | number:'1.2-2' }}%
                    </span>
                    <span style="font-size:8.5px; color:#64748b;">
                      {{ volPrev.priced || 0 }} of {{ volPrev.count || 0 }} priced
                    </span>
                  }
                </span>
              </div>
            }
            </div>


            @if (volPrev?.top10?.length) {
              <div class="rwd-scroll">
                <table style="width:100%; border-collapse:collapse; min-width:860px;">
                  <thead>
                    <tr>
                      <th class="sb-hdr" style="text-align:center;">Rank</th>
                      <th class="sb-hdr">Symbol</th>
                      <th class="sb-hdr">Sector</th>
                      <th class="sb-hdr" style="text-align:right;" title="The close on the session before this list was frozen">Prev Close</th>
                      <th class="sb-hdr" style="text-align:right;" title="The uploaded base price this stock had to cross">Base Price</th>
                      <th class="sb-hdr" style="text-align:right;" title="The 15:15 cut-off price on the day this list was frozen - what the position was opened at">Entry &#64; 15:15</th>
                      <th class="sb-hdr" style="text-align:right;">Current</th>
                      <th class="sb-hdr" style="text-align:right;">Return %</th>
                      <th class="sb-hdr" style="text-align:right;">RSI</th>
                      <th class="sb-hdr" style="text-align:right;">Volume</th>
                      <th class="sb-hdr" style="text-align:right;">20D Avg</th>
                      <th class="sb-hdr" style="text-align:right;">Ratio</th>
                      <th class="sb-hdr">Status</th>
                      <th class="sb-hdr" style="text-align:center;" title="Buy or sell this one stock, on its own">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (p of volPrev.top10; track p.symbol) {
                      <tr>
                        <td class="sb-cell" style="text-align:center; font-weight:700; color:#facc15;">{{ p.rank }}</td>
                        <td class="sb-cell" style="color:#fff; font-weight:700;">{{ p.symbol }}</td>
                        <td class="sb-cell" style="color:#cbd5e1;">{{ p.sector || '-' }}</td>
                        <td class="sb-cell" style="text-align:right; color:#94a3b8;">
                          {{ p.previous_close ? (p.previous_close | number:'1.2-2') : '-' }}</td>
                        <td class="sb-cell" style="text-align:right; color:#cbd5e1;">
                          {{ p.base_price ? (p.base_price | number:'1.2-2') : '-' }}</td>
                        <!-- Dash, not zero: "the official close has not landed yet" and "the entry
                             price was zero" are different facts. The reference is in the header
                             rather than repeated on every row. -->
                        <td class="sb-cell" style="text-align:right; color:#cbd5e1;">
                          {{ p.entry_price ? (p.entry_price | number:'1.2-2') : '-' }}</td>
                        <td class="sb-cell" style="text-align:right; color:#fff; font-weight:600;">
                          {{ p.current_price | number:'1.2-2' }}</td>
                        <td class="sb-cell" style="text-align:right;"
                            [class]="(p.return_pct || 0) >= 0 ? 'pos' : 'neg'">
                          {{ p.return_pct == null ? '-' : ((p.return_pct > 0 ? '+' : '') + (p.return_pct | number:'1.2-2') + '%') }}</td>
                        <td class="sb-cell" style="text-align:right; color:#cbd5e1;">
                          {{ p.rsi14 ? (p.rsi14 | number:'1.1-1') : '-' }}</td>
                        <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ volFmt(p.volume) }}</td>
                        <td class="sb-cell" style="text-align:right; color:#64748b;">{{ volFmt(p.avg_20d_volume) }}</td>
                        <td class="sb-cell" style="text-align:right; font-weight:800;"
                            [style.color]="ratioColor(p.volume_ratio)">{{ p.volume_ratio | number:'1.2-2' }}x</td>
                        <td class="sb-cell">
                          <span style="font-size:9px; font-weight:800; border-radius:999px; padding:2px 8px;"
                                [style.background]="p.status === 'CLOSED' ? '#0b3b37' : p.status === 'OPEN' ? '#132237' : '#3a2608'"
                                [style.color]="p.status === 'CLOSED' ? '#5ff5cd' : p.status === 'OPEN' ? '#8fb0d4' : '#fde68a'">
                            {{ p.status === 'AWAITING_CLOSE' ? 'AWAITING CLOSE' : p.status }}</span>
                        </td>
                        <!-- Buy stays available on a CLOSED row: the list is frozen, and a stock
                             sold this morning may well be worth re-entering. The server is the
                             one that refuses a duplicate open position, not this button. -->
                        <td class="sb-cell" style="text-align:center; white-space:nowrap;">
                          <button class="btn-row buy" [disabled]="volBreakPlacing"
                                  (click)="buyVolBreakoutSymbols([p.symbol], p.symbol + ' only, from the frozen previous-day list.')"
                                  title="Open a paper position in this stock alone">Buy</button>
                          <button class="btn-row sell" style="margin-left:5px;"
                                  [disabled]="volBreakSelling || p.status === 'CLOSED'"
                                  (click)="sellVolBreakoutSymbols([p.symbol], p.symbol + ' only, from the frozen previous-day list.')"
                                  [title]="p.status === 'CLOSED' ? 'Already closed' : 'Close the open paper position in this stock at the price read now'">Sell</button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            } @else {
              <div style="font-size:10.5px; color:#94a3b8; line-height:1.6;">
                {{ volPrev?.message || 'No finalized shortlist yet. The first one is stored at the
                cut-off on the next trading day, and these five then stay fixed for the whole of
                the following session.' }}
              </div>
            }
          </div>
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

        <!-- Intraday candles only exist for sessions collected live, so most historical days
             have no 15:15 bar to enter at. Those entries are priced at that day's close
             instead, and saying how many keeps a measured entry distinguishable from a
             stood-in one rather than quietly averaging the two. -->
        @if (lowEntryFallbackDays() > 0) {
          <div style="font-size:10px; color:#fde68a; background:#3a2608; border:1px solid #d97706;
                      border-radius:6px; padding:6px 9px; margin-bottom:8px; line-height:1.5;">
            <strong>{{ lowEntryFallbackDays() }} of {{ lowTrend.length }} days</strong> had no
            15:15 intraday price, so those entries were priced at that day&rsquo;s close.
            Intraday candles exist only for sessions collected live &mdash; older days are
            daily-only.
          </div>
        }
        <!-- Chart header: the chart's own name on the left, and everything that describes
             or controls it on the right, in one line. The range and totals sat in one band and
             the two selectors in another directly beneath, so the controls read as belonging to
             whatever followed them rather than to the chart they drive. -->
        @if (lowTrend.length) {
          <div id="chartHeadRowLow"
               style="display:flex; align-items:center; justify-content:space-between; gap:12px;
                      flex-wrap:wrap; padding:6px 10px; margin-bottom:8px;
                      background:#0f172a; border-radius:6px; font-size:11px;">
            <span style="font-size:11.5px; font-weight:800; color:#e2e8f0; white-space:nowrap;">
              RSI 45 with Volume &amp; Stochastic
            </span>
            <div style="display:flex; align-items:center; gap:14px; flex-wrap:wrap;">
              @if (lowResult) {
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
              }
              <span style="width:1px; height:16px; background:#334155;"></span>
              <span style="display:inline-flex; align-items:center; gap:5px;">
                <label style="font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.4px;">Holding period</label>
                <select class="select-sm" [ngModel]="lowHoldingDay" (ngModelChange)="setHoldingDay($event)"
                        title="Return this many trading days after entry, measured from the entry price.">
                  @for (dnum of holdingDayChoices; track dnum) { <option [ngValue]="dnum">Day {{ dnum }}</option> }
                </select>
              </span>
              <span style="display:inline-flex; align-items:center; gap:5px;">
                <label style="font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.4px;">Bars on screen</label>
                <select class="select-sm" [ngModel]="barsOnScreen" (ngModelChange)="setBarsOnScreen($event)"
                        title="How many of the most recent bars to draw. The figures below follow the same window.">
                  @for (n of barsOnScreenChoices; track n) { <option [ngValue]="n">{{ barsLabel(n) }}</option> }
                </select>
              </span>
            </div>
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
            <div style="display:flex; flex-direction:column; align-items:center; gap:2px; min-width:38px; cursor:pointer; border-radius:3px;"
                 [title]="d.no_stock ? (d.trade_date + (d.no_price ? ' - could NOT be measured' : ' - no stock met the entry rules') + (d.note ? ': ' + d.note : ''))
                          : d.is_open ? (d.trade_date + ' - OPEN position, ' + d.open_priced + ' stock(s) valued live. ' + (d.note || ''))
                          : batchBarTooltip(d)">
              @if (d.no_stock) {
                <!-- Amber "No Match" is a RESULT: the strategy looked and found nothing, which
                     is a normal day. Red "No Price" is a DATA GAP: it found something and the
                     platform could not measure it. They were the same grey label, so a month of
                     missing candles looked exactly like a quiet month. -->
                <div style="font-size:7px; font-weight:700; writing-mode:vertical-rl;
                            text-orientation:mixed; height:64px; display:flex; align-items:flex-end;
                            justify-content:center; letter-spacing:.3px;"
                     [style.color]="d.no_price ? '#ef4444' : '#f59e0b'">{{ d.no_price ? 'No Price' : 'No Match' }}</div>
                <div style="width:16px; height:2px; border-radius:1px;"
                     [style.background]="d.no_price ? '#7f1d1d' : '#3a4a5f'"></div>
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

      <!-- The Auto Buy & Auto Sell panel moved to its own screen (Strategy > Auto Buy &
           Sell). It governs whichever strategy is selected there, including ones built
           in the Strategy Builder, so it no longer belongs inside one strategy's page. -->

      <!-- ══ RSI 45 Previous Close Less the Base Price with Volume ═════════════
           Restored. This was removed as a duplicate of the section above, which was a
           misreading: the two apply the same entry test but answer different questions.
           The section above shortlists the FIVE highest volume ratios and freezes them at
           the cut-off, because that is the basket the strategy trades. This one lists
           EVERY stock that qualifies, unranked and unfrozen, which is what you need to see
           when the shortlist looks wrong - whether a stock missed the cut or never
           qualified at all are different problems with different fixes, and the frozen
           five cannot tell them apart. ══════════════════════════════════════════════ -->
      <!-- Chart 4: the crossing, with the volume test applied as well. -->
      <div class="card" style="padding:12px; grid-column:1 / -1;">
        <div class="sec-head" style="display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; margin-bottom:8px;">
          <div style="flex:1 1 420px;">
            <span class="sec-title" style="font-size:13px; font-weight:800; color:#60a5fa;">
              RSI 45 Previous Close Less the Base Price with Volume
            </span>
            <div style="font-size:10px; color:#94a3b8; margin-top:3px;">
              Entry test, all of which must hold: Previous Close &lt; Base Price <strong>and</strong>
              Current Price &ge; Base Price (the crossing), <strong>and</strong> Current Volume &gt;
              the average volume of the last 20 working days, <strong>and</strong> daily
              <strong>RSI(14) at or above 45</strong>. <strong>No ranking</strong> &mdash; every stock that qualifies is listed,
              strongest move first. Bought at the closing price and sold at the next trading day's
              close; when that day is not a working day, the next working day's close.
            </div>
          </div>

          <!-- The two headline figures sit on the title line, where they are read first, rather
               than in a band below the controls. Both are baseline-aligned and use the same
               label / value / caption shape so the eye can compare them at a glance. -->
        </div>

        <!-- Same labelling as the section above: this table is the last completed
             session's qualifiers, not today's. -->
        <!-- Same shape as the & Stochastic section above: heading left, this section's own
             controls and headline figure right. Visualization only - this section's
             qualification, ranking, entry, exit and backtest logic are untouched, and the
             stochastic requirement is deliberately NOT applied here.

             No "view all qualifying" control: this section already loads with limit 0, so every
             stock that passes its entry test is listed and there is nothing further to reveal. -->
        <div id="volHeadControls"
             style="display:flex; align-items:center; justify-content:space-between; gap:12px;
                    flex-wrap:wrap; margin:10px 0 4px;">
          <span style="font-size:11px; font-weight:800; color:#facc15;">
            Yesterday Qualified Stocks
          </span>
          <div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap;">
            @if (volTop?.top10?.length) {
              <span style="display:inline-flex; align-items:baseline; gap:6px;
                           background:#0f172a; border-radius:5px; padding:3px 9px;"
                    [title]="volTop.first_run_is_today === false
                               ? ('Average move of these stocks from the ' + volTop.first_run_date
                                  + ' first run to the price now.')
                               : 'Average move of these stocks from the first run of the day to now.'">
                <span style="font-size:8.5px; color:#94a3b8; text-transform:uppercase;
                             letter-spacing:.5px;">Today Avg Return</span>
                @if (volTop.avg_return_from_first_run == null) {
                  <span style="font-size:13px; font-weight:800; color:#94a3b8;">&mdash;</span>
                } @else {
                  <span style="font-size:13px; font-weight:800;"
                        [class]="volTop.avg_return_from_first_run >= 0 ? 'pos' : 'neg'">
                    {{ volTop.avg_return_from_first_run > 0 ? '+' : '' }}{{ volTop.avg_return_from_first_run | number:'1.2-2' }}%
                  </span>
                  <span style="font-size:8.5px; color:#64748b;">
                    @if (volTop.first_run_is_today === false) { {{ volTop.first_run_date }} }
                    &middot; {{ volTop.first_run_priced }}/{{ volTop.count }}
                  </span>
                }
              </span>
            }
              <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                      (click)="volStrategyDetailsOpen = true"
                      title="The complete entry test for this section.">
                Strategy details
              </button>
              <button class="btn-outline" style="font-size:9px; padding:3px 12px;"
                      (click)="refreshVolSection()" [disabled]="volTopLoading || volRefreshing"
                      title="Re-run this section's entry test at the latest available prices and re-read every figure in it, including the bars.">
                @if (volTopLoading || volRefreshing) { <span class="spin"></span> Refreshing... } @else { Refresh }
              </button>
              <button class="btn-outline" style="font-size:9px; padding:3px 10px;"
                      (click)="downloadRsi45VolumeSection()"
                      title="Excel/CSV of exactly the stocks listed below, with the same columns.">
                Download stocks
              </button>
          </div>
        </div>

        <!-- Which session this screen is answering for. A screen built from a six-day-old
             close looks exactly like one built from this morning's until it says so, and the
             difference decides whether a stock that is crossing today appears at all. -->
        @if (volTop?.as_of) {
          <div style="font-size:10px; line-height:1.6; border-radius:6px; padding:7px 10px;
                      margin-bottom:9px;"
               [style.color]="(volTop.days_behind_today || 0) > 1 ? '#fde68a' : '#9eb8dd'"
               [style.background]="(volTop.days_behind_today || 0) > 1 ? '#3a2608' : '#0f172a'"
               [style.border]="'1px solid ' + ((volTop.days_behind_today || 0) > 1 ? '#d97706' : '#233852')">
            @if (volTop.live_session) {
              Screening <strong>{{ volTop.evaluating_session }}</strong>
              <strong style="color:#5ff5cd;">&middot; LIVE</strong> &mdash; today measured from
              the running feed, so volume is a running total.
            } @else {
              Screening the <strong>{{ volTop.as_of }}</strong> session
            @if (volTop.newest_stored_session && volTop.newest_stored_session !== volTop.as_of) {
              &mdash; the newest stored is {{ volTop.newest_stored_session }}
            }
            }
            @if ((volTop.days_behind_today || 0) > 1) {
              <span>&middot; <strong>{{ volTop.days_behind_today }} days behind today.</strong>
              Later daily closes have not been stored, so a stock crossing since then cannot
              appear here. Backfill the daily candles to bring this current.</span>
            }
          </div>
        }

        <!-- Identify -> Rank -> Buy/Sell, in that order. The list below is the Top 10 that
             survived identification, scored by the existing engine, with the existing signal. -->
        @if (volTop?.top10?.length) {
          <div style="overflow-x:auto; margin-bottom:10px;">
            <table style="width:100%; border-collapse:collapse; min-width:900px;">
              <thead>
                <tr>
                  <th class="sb-hdr" style="text-align:center;">Rank</th>
                  <th class="sb-hdr">Symbol</th><th class="sb-hdr">Stock Name</th>
                  <th class="sb-hdr">Sector</th>
                  <th class="sb-hdr" style="text-align:right;" title="Price on the day before - must be below the base price">Prev Close</th>
                  <th class="sb-hdr" style="text-align:right;">Base Price</th>
                  <th class="sb-hdr" style="text-align:right;" title="Traded price at the cut-off on the day this list was frozen - the price the position was opened at">Entry &#64; {{ volTop?.top10?.[0]?.entry_time || '15:15' }}</th>
                  <th class="sb-hdr" style="text-align:right;" title="Must be at or above the base price - together with the previous close below it, that is the crossing">Current</th>
                  <th class="sb-hdr" style="text-align:right;" title="Move since the entry price, not since the base price">Return %</th>
                  <th class="sb-hdr" style="text-align:right;" title="Traded volume, which had to beat the 20-day average to qualify">Volume</th>
                  <th class="sb-hdr" style="text-align:right;">20D Avg Vol</th>
                  <th class="sb-hdr" style="text-align:right;" title="Current day volume divided by the 20-session average. The shortlist is ordered by this.">Ratio</th>
                  <th class="sb-hdr" style="text-align:right;">RSI</th>
                  <th class="sb-hdr" style="text-align:center;" title="From the existing Buy/Sell logic, applied after ranking">Signal</th>
                  <th class="sb-hdr">Trend</th>
                  <th class="sb-hdr" style="text-align:center;" title="Buy or sell this one stock, on its own">Action</th></tr>
              </thead>
              <tbody>
                @for (r of volTop.top10; track r.symbol) {
                  <tr>
                    <td class="sb-cell" style="text-align:center; font-weight:700;"
                        [style.color]="r.rank === 1 ? '#facc15' : r.rank <= 3 ? '#94a3b8' : '#64748b'">{{ r.rank }}</td>
                    <td class="sb-cell" style="color:#fff; font-weight:700;">{{ r.symbol }}</td>
                    <td class="sb-cell" style="color:#9eb8dd; max-width:170px; overflow:hidden;
                        text-overflow:ellipsis; white-space:nowrap;"
                        [title]="r.company_name || r.symbol">{{ r.company_name || '-' }}</td>
                    <td class="sb-cell" style="color:#cbd5e1;">{{ r.sector || '-' }}</td>
                    <td class="sb-cell" style="text-align:right; color:#94a3b8;">
                      {{ r.previous_close | number:'1.2-2' }}
                      @if (r.previous_close_date) {
                        <div style="font-size:8.5px; color:#64748b;">{{ r.previous_close_date }}</div>
                      }
                    </td>
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
                    <td class="sb-cell" style="text-align:right; font-weight:800;"
                        [style.color]="ratioColor(r.volume_ratio)"
                        [title]="'Current day volume over the average of ' +
                                 (r.volume_sessions || 20) + ' completed session(s).' +
                                 (r.volume_window_full === false
                                   ? ' Fewer than 20 - this stock has less history, so its ratio is not directly comparable with a full-window one.'
                                   : '')">
                      {{ r.volume_ratio ? (r.volume_ratio | number:'1.2-2') + 'x' : '-' }}
                      <!-- A short window is stated, not hidden and not used to exclude the
                           stock. Comparability is the reader's call to make, and they can only
                           make it if the number of sessions is on the row. -->
                      @if (r.volume_window_full === false) {
                        <div style="font-size:8px; font-weight:700; color:#fbbf24;">{{ r.volume_sessions }}d</div>
                      }</td>
                    <td class="sb-cell" style="text-align:right; color:#cbd5e1;">
                      {{ r.rsi14 ? (r.rsi14 | number:'1.1-1') : '-' }}</td>
                    <td class="sb-cell" style="text-align:center;">
                      <span style="font-weight:800; padding:2px 8px; border-radius:4px;"
                            [style.color]="r.signal === 'BUY' ? '#22c55e' : r.signal === 'SELL' ? '#ef4444' : '#94a3b8'"
                            [style.background]="r.signal === 'BUY' ? '#0d2818' : r.signal === 'SELL' ? '#2a0f0f' : '#16202e'"
                            [title]="r.signal_message || ''">{{ r.signal }}</span>
                    </td>
                    <td class="sb-cell"
                        [style.color]="r.trend === 'Bullish' ? '#22c55e' : r.trend === 'Bearish' ? '#ef4444' : '#94a3b8'">{{ r.trend || '-' }}</td>
                    <td class="sb-cell" style="text-align:center; white-space:nowrap;">
                      <button class="btn-row buy" [disabled]="volBreakPlacing"
                              (click)="buyVolBreakoutSymbols([r.symbol], r.symbol + ' only, from the Volume section.')"
                              title="Open a paper position in this stock alone">Buy</button>
                      <button class="btn-row sell" style="margin-left:5px;" [disabled]="volBreakSelling"
                              (click)="sellVolBreakoutSymbols([r.symbol], r.symbol + ' only, from the Volume section.')"
                              title="Close the open paper position in this stock at the price read now">Sell</button>
                    </td></tr>
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
                  Heading into tomorrow &mdash; Provisional
                </span>
                <span style="font-size:9.5px; color:#94a3b8;">
                  {{ volProvisional.note }}
                  @if (volProvisional.pool_size) {
                    <span style="color:#64748b;">&middot; {{ volProvisional.pool_size }} passed the entry test</span>
                  }
                </span>
              </div>
              @if (volProvisional.stocks?.length) {
                <div class="rwd-scroll">
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

        <!-- Chart header: the chart's own name on the left, and everything that describes
             or controls it on the right, in one line. The range and totals sat in one band and
             the two selectors in another directly beneath, so the controls read as belonging to
             whatever followed them rather than to the chart they drive. -->
        @if (volTrend.length) {
          <div id="chartHeadRowVol"
               style="display:flex; align-items:center; justify-content:space-between; gap:12px;
                      flex-wrap:wrap; padding:6px 10px; margin-bottom:8px;
                      background:#0f172a; border-radius:6px; font-size:11px;">
            <span style="font-size:11.5px; font-weight:800; color:#e2e8f0; white-space:nowrap;">
              RSI 45 with Volume
            </span>
            <div style="display:flex; align-items:center; gap:14px; flex-wrap:wrap;">
              @if (volResult) {
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
              }
              <span style="width:1px; height:16px; background:#334155;"></span>
              <span style="display:inline-flex; align-items:center; gap:5px;">
                <label style="font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.4px;">Holding period</label>
                <select class="select-sm" [ngModel]="volHoldingDay" (ngModelChange)="setVolHoldingDay($event)"
                        title="Return this many trading days after entry, measured from the entry price.">
                  @for (dnum of holdingDayChoices; track dnum) { <option [ngValue]="dnum">Day {{ dnum }}</option> }
                </select>
              </span>
              <span style="display:inline-flex; align-items:center; gap:5px;">
                <label style="font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.4px;">Bars on screen</label>
                <select class="select-sm" [ngModel]="barsOnScreen" (ngModelChange)="setBarsOnScreen($event)"
                        title="How many of the most recent bars to draw. The figures below follow the same window.">
                  @for (n of barsOnScreenChoices; track n) { <option [ngValue]="n">{{ barsLabel(n) }}</option> }
                </select>
              </span>
            </div>
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
                 [title]="d.no_stock ? (d.trade_date + (d.no_price ? ' - could NOT be measured' : ' - no stock met the entry rules') + (d.note ? ': ' + d.note : ''))
                          : d.is_open ? (d.trade_date + ' - OPEN position, ' + d.open_priced + ' stock(s) valued live. ' + (d.note || ''))
                          : batchBarTooltip(d)">
              @if (d.no_stock) {
                <!-- Amber "No Match" is a RESULT: the strategy looked and found nothing, which
                     is a normal day. Red "No Price" is a DATA GAP: it found something and the
                     platform could not measure it. They were the same grey label, so a month of
                     missing candles looked exactly like a quiet month. -->
                <div style="font-size:7px; font-weight:700; writing-mode:vertical-rl;
                            text-orientation:mixed; height:64px; display:flex; align-items:flex-end;
                            justify-content:center; letter-spacing:.3px;"
                     [style.color]="d.no_price ? '#ef4444' : '#f59e0b'">{{ d.no_price ? 'No Price' : 'No Match' }}</div>
                <div style="width:16px; height:2px; border-radius:1px;"
                     [style.background]="d.no_price ? '#7f1d1d' : '#3a4a5f'"></div>
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

      <!-- Ranking configuration removed from this screen: the Stock Upload sections
           state their own entry tests and do not rank, so factor weights had no
           effect on anything shown here. They still live on the ranking screens. -->
    </div>
  </div>

</div>

<!-- ══ Full-screen strategy details ══════════════════════════════════════════
     The complete entry test in one readable place. It was only ever a compressed sentence in
     the section header, where the mandatory conditions ran together and the shortlist rule was
     easy to read as an eligibility filter. -->
@if (prevDayAllOpen) {
  <div style="position:fixed; inset:0; z-index:9000; background:rgba(4,8,15,.93);
              display:flex; align-items:flex-start; justify-content:center;
              padding:24px 16px; overflow-y:auto;"
       (click)="prevDayAllOpen = false">
    <div style="max-width:1000px; width:100%; background:#101a2b; border:1px solid #27415f;
                border-radius:10px; padding:20px 24px;"
         (click)="$event.stopPropagation()">
      <div style="display:flex; align-items:baseline; justify-content:space-between;
                  gap:12px; margin-bottom:10px;">
        <div style="font-size:14px; font-weight:800; color:#facc15;">
          All qualifying stocks &mdash; {{ prevDayAll?.as_of || 'session unknown' }}
        </div>
        <button class="btn-outline" style="font-size:10px; padding:3px 12px;"
                (click)="prevDayAllOpen = false">Close</button>
      </div>

      <!-- Said plainly: it is the one thing a reader could get wrong about this list. -->
      <div class="note note-warn" style="margin-bottom:12px; font-size:10.5px; line-height:1.7;">
        Re-derived from the stored candles, not read from the frozen snapshot &mdash; the snapshot
        keeps only the five that were shortlisted. The same entry test is applied, so this is the
        wider list those five came from, but it is computed from the data as it stands now and its
        membership can differ from what was frozen.
        @if (prevDayAllSessionMismatch()) {
          <div style="margin-top:6px; color:#fca5a5; font-weight:700;">
            This list is for the {{ prevDayAll?.as_of }} session, while the frozen list above was
            stored for {{ volPrev?.trade_date }}. They are different sessions.
          </div>
        }
      </div>

      @if (prevDayAll?.top10?.length) {
        <div style="font-size:10.5px; color:#94a3b8; margin-bottom:8px;">
          {{ prevDayAll.top10.length }} stock(s) passed the entry test
          @if (prevDayAll.qualifying_count) { &middot; {{ prevDayAll.qualifying_count }} qualified in total }
        </div>
        <div class="rwd-scroll">
          <table style="width:100%; border-collapse:collapse; min-width:820px;">
            <thead>
              <tr>
                <th class="sb-hdr" style="text-align:center;">#</th>
                <th class="sb-hdr">Symbol</th>
                <th class="sb-hdr">Sector</th>
                <th class="sb-hdr" style="text-align:right;">Prev Close</th>
                <th class="sb-hdr" style="text-align:right;">Base Price</th>
                <th class="sb-hdr" style="text-align:right;">Current</th>
                <th class="sb-hdr" style="text-align:right;">Return %</th>
                <th class="sb-hdr" style="text-align:right;">RSI</th>
                <th class="sb-hdr" style="text-align:right;">Stoch K/D</th>
                <th class="sb-hdr" style="text-align:right;">Ratio</th>
              </tr>
            </thead>
            <tbody>
              @for (q of prevDayAll.top10; track q.symbol; let i = $index) {
                <tr>
                  <td class="sb-cell" style="text-align:center; color:#64748b;">{{ i + 1 }}</td>
                  <td class="sb-cell" style="color:#fff; font-weight:700;">{{ q.symbol }}</td>
                  <td class="sb-cell" style="color:#cbd5e1;">{{ q.sector || '-' }}</td>
                  <td class="sb-cell" style="text-align:right; color:#94a3b8;">{{ q.previous_close | number:'1.2-2' }}</td>
                  <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ q.base_price | number:'1.2-2' }}</td>
                  <td class="sb-cell" style="text-align:right; color:#fff; font-weight:600;">{{ q.current_price | number:'1.2-2' }}</td>
                  <td class="sb-cell" style="text-align:right;"
                      [class]="(q.return_pct || 0) >= 0 ? 'pos' : 'neg'">{{ q.return_pct | number:'1.2-2' }}%</td>
                  <td class="sb-cell" style="text-align:right; color:#cbd5e1;">{{ q.rsi14 ? (q.rsi14 | number:'1.1-1') : '-' }}</td>
                  <td class="sb-cell" style="text-align:right; color:#94a3b8;">
                    {{ q.stoch_k == null ? '-' : (q.stoch_k | number:'1.1-1') }} /
                    {{ q.stoch_d == null ? '-' : (q.stoch_d | number:'1.1-1') }}
                  </td>
                  <td class="sb-cell" style="text-align:right; font-weight:800; color:#fbbf24;">
                    {{ q.volume_ratio ? (q.volume_ratio | number:'1.2-2') + 'x' : '-' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      } @else {
        <div class="empty">
          {{ prevDayAll?.message || 'No stock passed the entry test on that session.' }}
        </div>
      }
    </div>
  </div>
}
@if (volStrategyDetailsOpen) {
  <div style="position:fixed; inset:0; z-index:9000; background:rgba(4,8,15,.93);
              display:flex; align-items:flex-start; justify-content:center;
              padding:24px 16px; overflow-y:auto;"
       (click)="volStrategyDetailsOpen = false">
    <div style="max-width:860px; width:100%; background:#101a2b; border:1px solid #27415f;
                border-radius:10px; padding:22px 26px;"
         (click)="$event.stopPropagation()">
      <div style="display:flex; align-items:baseline; justify-content:space-between;
                  gap:12px; margin-bottom:14px;">
        <div style="font-size:15px; font-weight:800; color:#facc15;">
          RSI 45 Previous Close Less the Base Price with Volume
        </div>
        <button class="btn-outline" style="font-size:10px; padding:3px 12px;"
                (click)="volStrategyDetailsOpen = false">Close</button>
      </div>

      <div style="font-size:11.5px; font-weight:800; color:#e2e8f0; margin-bottom:6px;">
        Entry test &mdash; all of which must hold
      </div>
      <ol style="margin:0 0 16px 18px; padding:0; font-size:12px; line-height:1.85; color:#cbd5e1;">
        <li>Previous Close &lt; Base Price <strong>and</strong> Current Price &ge; Base Price
            <span style="color:#94a3b8;">(the crossing)</span></li>
        <li>Current Day Volume &gt; the average volume of the previous 20 completed trading
            sessions <span style="color:#94a3b8;">(the current session excluded)</span></li>
        <li>Daily RSI(14) at or above 45</li>
        <li>Current Price no more than 10% above the Base Price</li>
      </ol>

      <!-- Said explicitly. The two sections sit next to each other and differ by exactly one
           condition, so a reader comparing them needs to be told which, rather than inferring
           it from a clause that is simply absent. -->
      <div class="note" style="margin:0 0 16px; font-size:11px; line-height:1.8; color:#94a3b8;
                  border-left:2px solid #27415f; padding-left:10px;">
        This section does <strong style="color:#cbd5e1;">not</strong> require the stochastic
        crossover. That is the single difference between it and &ldquo;with Volume &amp;
        Stochastic&rdquo; above, and it is why this list is the longer of the two.
      </div>

      <div style="font-size:11.5px; font-weight:800; color:#e2e8f0; margin-bottom:6px;">Listing</div>
      <p style="margin:0 0 16px; font-size:12px; line-height:1.85; color:#cbd5e1;">
        No ranking and no shortlist: <strong>every</strong> stock that passes the conditions above
        is listed, strongest move first. Where the section above freezes five, this one shows the
        whole qualifying population &mdash; which is what tells you whether a stock missed the
        shortlist or never qualified at all.
      </p>

      <div style="font-size:11.5px; font-weight:800; color:#e2e8f0; margin-bottom:6px;">The bars below</div>
      <p style="margin:0 0 16px; font-size:12px; line-height:1.85; color:#cbd5e1;">
        Each bar averages the stocks that qualified on that session, entered at that day&rsquo;s
        close and exited at the close of the selected holding period &mdash; counted in exchange
        trading sessions, so weekends and holidays are skipped.
      </p>

      <div style="font-size:10.5px; line-height:1.8; color:#64748b;
                  border-top:1px solid #1d2c42; padding-top:11px;">
        Every condition above is evaluated by the backend, not merely displayed here. The table,
        the bars, the &ldquo;view all&rdquo; list and the export all apply the same test, so the
        four cannot describe different baskets.
      </div>
    </div>
  </div>
}
@if (strategyDetailsOpen) {
  <div style="position:fixed; inset:0; z-index:9000; background:rgba(4,8,15,.93);
              display:flex; align-items:flex-start; justify-content:center;
              padding:24px 16px; overflow-y:auto;"
       (click)="strategyDetailsOpen = false">
    <div style="max-width:860px; width:100%; background:#101a2b; border:1px solid #27415f;
                border-radius:10px; padding:22px 26px;"
         (click)="$event.stopPropagation()">
      <div style="display:flex; align-items:baseline; justify-content:space-between;
                  gap:12px; margin-bottom:14px;">
        <div style="font-size:15px; font-weight:800; color:#facc15;">
          RSI 45 Previous Close Less the Base Price with Volume &amp; Stochastic
        </div>
        <button class="btn-outline" style="font-size:10px; padding:3px 12px;"
                (click)="strategyDetailsOpen = false">Close</button>
      </div>

      <div style="font-size:11.5px; font-weight:800; color:#e2e8f0; margin-bottom:6px;">
        Entry test &mdash; all of which must hold
      </div>
      <ol style="margin:0 0 16px 18px; padding:0; font-size:12px; line-height:1.85; color:#cbd5e1;">
        <li>Previous Close &lt; Base Price <strong>and</strong> Current Price &ge; Base Price
            <span style="color:#94a3b8;">(the crossing)</span></li>
        <li>Current Day Volume &gt; the average volume of the previous 20 completed trading
            sessions <span style="color:#94a3b8;">(the current session excluded)</span></li>
        <li>Daily RSI(14) at or above 45</li>
        <li>Stochastic %K crossed above %D on this session</li>
      </ol>

      <div style="font-size:11.5px; font-weight:800; color:#e2e8f0; margin-bottom:6px;">Shortlist</div>
      <p style="margin:0 0 16px; font-size:12px; line-height:1.85; color:#cbd5e1;">
        No ranking &mdash; the 5 highest volume ratios are shortlisted. The volume ratio orders
        the list; it never excludes a stock.
      </p>
      <p style="margin:0 0 16px; font-size:11px; line-height:1.8; color:#94a3b8;
                border-left:2px solid #27415f; padding-left:10px;">
        &ldquo;No ranking&rdquo; means no ranking-based eligibility filter. Every stock that
        passes the four conditions above qualifies. Only then does the volume ratio come in, to
        order the qualifiers and take the top five &mdash; it can never remove a stock that
        failed no condition.
      </p>

      <div style="font-size:11.5px; font-weight:800; color:#e2e8f0; margin-bottom:6px;">The bars below</div>
      <p style="margin:0 0 16px; font-size:12px; line-height:1.85; color:#cbd5e1;">
        The bars average these same 5, bought at the 15:15 cut-off price and sold at the next
        trading day&rsquo;s close &mdash; when that day is not a working day, the next working
        day&rsquo;s close.
      </p>

      <div style="font-size:10.5px; line-height:1.8; color:#64748b;
                  border-top:1px solid #1d2c42; padding-top:11px;">
        Every condition above is evaluated by the backend, not merely displayed here. The
        stochastic crossover is mandatory and has no switch: it is tested as
        %K(prev) &le; %D(prev) and %K &gt; %D on the evaluated session. The displayed Return %
        is measured from the previous close to the current price; a position&rsquo;s
        entry-to-exit performance is a separate figure and is not shown in that column.
      </div>
    </div>
  </div>
}
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
   * Vestigial: it guarded a race between the user's own "Refresh Top 10" and the two auto-refresh
   * timers, all three of which called loadRanking(). That fetch is gone, so nothing sets this any
   * more. Kept only because the flags it travelled with are still read elsewhere on this screen;
   * it is safe to delete along with them.
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
  /** Where the drawn range actually starts, reported by the server. */
  lowCoverageFrom = '';
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
  /** Where the drawn range actually starts, reported by the server. */
  volCoverageFrom = '';
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

  /**
   * The last painted state of this screen, kept across navigation.
   *
   * <p>Leaving Stock Upload destroys the component, so coming back re-ran ngOnInit and with it
   * loadAll() - eleven API calls, including the universe scan and both bar-chart rebuilds - for
   * data that was on screen seconds earlier and had not changed. Every return to the menu paid
   * the full first-load cost, which is the "takes just as long the second time" complaint.
   *
   * <p>Static, because it has to outlive the component instance; that is the same approach
   * MarketBreadthComponent already uses for its own payload. Holds only the rendered payloads -
   * never loading flags, which must always start false or the screen comes back stuck on a
   * spinner that nothing will clear.
   *
   * <p>Two minutes: long enough to cover a trip to another menu and back, short enough that a
   * revisit after a real interval still rebuilds. Within the window the screen paints instantly
   * from the cache and then revalidates quietly behind it, so what is shown is never older than
   * one refresh cycle even though it appeared immediately.
   */
  private static screenCache: { at: number; data: Record<string, any> } | null = null;
  private static readonly SCREEN_CACHE_TTL_MS = 120_000;
  private static readonly CACHED_FIELDS = [
    'dash', 'lowTop', 'volTop', 'lowTrend', 'volTrend', 'volProvisional', 'volPrev',
    'mainTop', 'uploadedStocks', 'uploadedTotal', 'volCoverageFrom', 'lowCoverageFrom',
    'positions', 'portfolios', 'uploadStats', 'chartData'
  ];

  /** Paint the previous visit's data, if it is recent enough to stand in. */
  private restoreScreen(): boolean {
    const c = StockTrackerComponent.screenCache;
    if (!c) return false;
    if (Date.now() - c.at > StockTrackerComponent.SCREEN_CACHE_TTL_MS) return false;
    for (const k of StockTrackerComponent.CACHED_FIELDS) {
      if (k in c.data && c.data[k] !== undefined) (this as any)[k] = c.data[k];
    }
    return true;
  }

  /** Keep what is on screen, so returning to this menu does not start from nothing. */
  private saveScreen(): void {
    const data: Record<string, any> = {};
    for (const k of StockTrackerComponent.CACHED_FIELDS) data[k] = (this as any)[k];
    StockTrackerComponent.screenCache = { at: Date.now(), data };
  }

  /**
   * Fires when this screen is left, cancelling its in-flight requests.
   *
   * The `destroyed` flag below stops stale responses from writing to component state, but it
   * does NOT stop the transfer - the browser goes on downloading every request this page
   * started. That is what made leaving Stock Upload slow: opening it fires eleven requests, and
   * every menu in this app is a lazily loaded route, so the next screen's JavaScript chunk had
   * to queue behind them on the browser's ~6-connections-per-host limit. The page you were
   * leaving was still holding the pipe.
   *
   * Piped into the heavy calls only. The quick ones finish before anyone can navigate, and
   * cancelling a 50 ms request buys nothing.
   */
  private pageGone$ = new Subject<void>();
  liveStatus: any = {};
  private liveSub?: Subscription;
  private statusSub?: Subscription;

  // Donut segments for sector
  donutSegments: any[] = [];

  readonly SECTOR_COLORS = ['#3b82f6','#22c55e','#f59e0b','#ef4444','#8b5cf6','#06b6d4','#ec4899','#14b8a6','#f97316','#84cc16'];

  constructor(private api: ApiService, public auth: AuthService, private http: HttpClient, private livePrices: LivePriceService) {}

  ngOnInit() {
    this.destroyed = false;
    // Paint the previous visit first, then refresh behind it. A cold visit still loads
    // everything; a return within the window shows the screen immediately instead of eleven
    // requests' worth of empty panels.
    const painted = this.restoreScreen();
    if (painted) {
      // Quietly bring the two cheap, most time-sensitive panels up to date. The heavy ones -
      // the universe scan and the bar-chart rebuilds - are left to the timers below rather
      // than fired again the instant the user arrives.
      this.loadDashboard();
      this.loadVolumeBreakoutPrevious();
    } else {
      this.loadAll();
    }
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
      this.loadChartData();
      // Previous Top 10 trend panel removed - nothing on this screen reads either feed now.
    }, 5 * 60 * 1000);
    this.top10Timer = setInterval(() => {
      // Only the Current Top 10 auto-refreshes, and only when the toggle is on.
      if (this.manualTop10Refresh) return;
      // Keep the crossing-entry panel current too, but only once it has been loaded - refreshing a
      // section the user has never opened would run the whole identify/rank/signal pass for nothing.
      if (this.lowTop && this.dash.market_status === 'OPEN') this.loadBelowPriceTop10();
      if (this.mainTop && this.dash.market_status === 'OPEN') this.loadMainTop10();
      // The frozen list needs this as much as the live one: its identities cannot change, but
      // its prices, returns and order status all move through the session. Live ticks cover the
      // price; this is what picks up an exit, a fill, and the official close.
      if (this.volPrev && this.dash.market_status === 'OPEN') this.loadVolumeBreakoutPrevious();
      if (this.volTop && this.dash.market_status === 'OPEN') this.loadVolumeTop10();
    }, 2 * 60 * 1000);
    this.livePrices.connect();
    this.liveSub = this.livePrices.quote$.subscribe(q => this.applyLiveQuote(q));
    this.statusSub = this.livePrices.status$.subscribe(s => this.liveStatus = s || {});
    // Server-pushed Top 10 diff: animate only changed rows, else just move the timestamp.
    this.top10Sub = this.livePrices.top10$.subscribe(p => this.applyTop10Update(p));
    this.loadAutomationConfig();
    this.loadDataHealth();
  }
  ngOnDestroy() {
    // Before anything is torn down, so returning to this menu has something to paint.
    this.saveScreen();
    this.destroyed = true;
    // Cancel before anything else: this is what frees the connections the next screen needs.
    this.pageGone$.next();
    this.pageGone$.complete();
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
    // No start date. The pickers are gone, so the bars draw the whole span the server can
    // honestly rebuild and "Bars on screen" decides how much of it is visible. The 30-day
    // default existed only to prefill the From box and, left in place, would have silently
    // capped every chart at 30 days with no control to widen it again.
    if (!this.lowTo) this.lowTo = new Date().toISOString().slice(0, 10);
    this.loadBelowPriceTop10();   // crossing panel table
    this.runLowPriceTrend();      // crossing panel bars
    this.loadMainTop10();         // main trend table + provisional
  }


  /**
   * The export URL for one RSI 45 section, built from THAT section's own parameters.
   *
   * <p>The comment here used to claim "the same parameters the panel was loaded with" while the
   * call sent `requireVolume=false&applyRanking=false` and nothing else. Measured against the
   * live server on 2026-11-27, the file and the screen were two different answers:
   *
   * <ul>
   *   <li>No `useNewestSession`, so the export answered for the session BEFORE the newest -
   *       23 November against the screen's 25th.</li>
   *   <li>`requireVolume=false` switched off a condition the section's own heading requires, so
   *       the file listed AMBER with "Volume Above 20D Avg = No".</li>
   *   <li>No `requireRsiLevel` and no `requireStochCross`, so it exported INGERRAND at RSI 36.8 -
   *       a stock nowhere near crossing 45.</li>
   * </ul>
   *
   * <p>So a screen correctly showing nothing produced a download of five stocks, none of which
   * passed the rules printed at the top of that same file. Passing the section's flags is what
   * makes the file mean what the button says.
   */
  private rsi45SectionUrl(params: Record<string, string>): string {
    const q = new URLSearchParams({
      requireCrossing: 'true',
      requireVolume: 'true',
      applyRanking: 'false',
      useNewestSession: 'true',
      requireRsiLevel: 'true',
      maxPctAboveBase: String(this.MAX_PCT_ABOVE_BASE),
      ...params
    });
    return '/tracker/export/rsi45-section?' + q.toString();
  }

  /** "RSI 45 Previous Close Less the Base Price with Volume" - every qualifying stock. */
  downloadRsi45Section() {
    const title = 'RSI 45 Previous Close Less the Base Price with Volume';
    this.downloadCsv(this.rsi45SectionUrl({
      limit: '0',
      sortByReturn: 'true',
      title
    }), 'TradeRakshak_RSI45_Previous_Close_Less_Base_Price_with_Volume.csv');
  }

  /**
   * "RSI 45 - Current Day Stocks" - the five-stock shortlist by volume ratio.
   *
   * <p>This button used to call downloadVolumeBreakout(), which exports the Volume Breakout
   * strategy. That is a different entry test, and its own file says so: "The crossing requirement
   * used by the other two sections (Previous Close < Base Price) is NOT applied here." So the
   * Current Day section downloaded stocks chosen without the base-price crossing, without the
   * RSI 45 crossover and without the stochastic cross.
   */
  downloadRsi45CurrentDaySection() {
    const title = 'RSI 45 Current Day Stocks';
    this.downloadCsv(this.rsi45SectionUrl({
      limit: this.lowShowAll ? '0' : String(this.LOW_SECTION_STOCKS),
      sortByVolumeRatio: 'true',
      requireFullVolumeWindow: 'false',
      requireStochCross: String(this.lowRequireStoch),
      title
    }), 'TradeRakshak_RSI45_Current_Day_Stocks.csv');
  }

  // loadTradeBatches() and runBacktestTrend() are gone from here with the Previous Top 10 Avg
  // Return Trend panel they fed. runBacktestTrend in particular rebuilt every ranking day from
  // stored candles on EVERY page load - the single most expensive call this screen made - to
  // draw a chart that is no longer on it.
  loadAll() { this.loadRealBrokers(); this.initBelowPricePanel(); this.loadDashboard(); this.loadChartData(); this.loadPositions(); this.loadPortfolios(); this.loadUploadedStocks(); this.recalcAllocation(); this.loadUploadStats(); this.loadVolumeBreakoutPrevious();
              // The "with Volume" section's own table and bars. Restored with the
              // section: without these it renders its empty state forever.
              this.loadVolumeTop10(); this.runVolumeTrend(); }

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
        market_status:  d.market_status  || 'DATA_UNAVAILABLE',
        market_status_at: d.market_status_at || ''
      };
    },
    // There was no error handler here at all. A failed or timed-out /tracker/dashboard left the
    // previous values on screen with nothing to say they were old - which is precisely the
    // "Market Status not updating" report: the request had stopped succeeding and the page went
    // on showing the last answer it got. A provider or server failure is not a closed market, so
    // it is named as its own state rather than defaulting to CLOSED.
    error: () => {
      this.dash = { ...this.dash, market_status: 'DATA_UNAVAILABLE' };
    }});
  }

    // loadRanking() is gone. It fetched /tracker/ranking/latest, which runs the live Top 10
    // calculation over the whole uploaded universe - on every page load, on a 5-minute timer
    // and on a 2-minute auto-refresh while the market was open. Nothing on this screen
    // rendered the result: `rd` had no template binding left after the Top 10 panel was
    // removed, so the most expensive call here was computing a ranking for no reader.
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
      // Was runBacktestTrend(), which redrew the removed trend panel.
    }, secs * 1000);
  }

  loadChartData() {
    this.api.get<any>('/tracker/ranking/history', { days: 90 }).pipe(takeUntil(this.pageGone$)).subscribe({
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

  // ── Volume Breakout Strategy ─────────────────────────────────────────────
  //
  // Entry: Current Price >= Base Price AND today's volume > the average of the previous 20
  // completed sessions. Shortlisted by volume ratio. A third section alongside the two RSI 45
  // ones, sharing their engine and differing only in that the crossing is not required.

  /**
   * Whether the table is listing every qualifying stock or only the shortlist.
   *
   * Off by default: the screen's job is the five that would be taken, and the full list runs to a
   * couple of hundred rows on an ordinary session - roughly 211 on a recent one. "View all" is
   * there for checking what the shortlist was drawn from.
   */
  /** Mirrors volbreakout.shortlist.size on the server; replaced by the first response. */
  volBreakShortlistSize = 5;
  /**
   * How many stocks qualified in total, which is NOT the row count when the shortlist is capped.
   *
   * Fetched separately and cached, because a list of five above a heading that says nothing about
   * the pool it came from is the kind of number that gets over-read. Null until known - shown as a
   * dash rather than as 0, since 0 is itself a meaningful answer.
   */

  // ── Volume Breakout: selection, allocation, Previous Stocks ──────────────

  /**
   * Which rows are ticked. A Set of symbols rather than a flag on each row, so the selection
   * survives a refresh that replaces the row objects - otherwise every auto-refresh would silently
   * clear the user's ticks while they were entering an amount.
   */
  volBreakSelected = new Set<string>();
  volBreakInvestment: number | null = 100000;
  volBreakAlloc: any = null;
  volBreakAllocLoading = false;

  volPrev: any = null;
  volPrevLoading = false;
  volFinalizing = false;

  volBreakToggle(symbol: string): void {
    if (this.volBreakSelected.has(symbol)) this.volBreakSelected.delete(symbol);
    else this.volBreakSelected.add(symbol);
    this.volBreakAllocate();
  }

  volBreakToggleAll(ev: any): void {
    const rows: any[] = this.lowTop?.top10 || [];
    if (ev?.target?.checked) rows.forEach(r => this.volBreakSelected.add(r.symbol));
    else rows.forEach(r => this.volBreakSelected.delete(r.symbol));
    this.volBreakAllocate();
  }

  // ══ Auto Buy & Auto Sell ═════════════════════════════════════════════════
  //
  // Settings live in app_config under volbreakout.<mode>.*, which is what the engine actually
  // reads. There is a second, older store - the trading_auto_settings table behind
  // POST /trading/auto-settings - that the Trading screen writes. This panel deliberately does
  // NOT use it: that table is not what VolumeBreakoutAutoTradeService reads, so a panel wired to
  // it would save successfully, show a green message, and change nothing about what the engine
  // does. Saving and having no effect is the worst of the three possible outcomes.

  autoCfg: any = null;
  autoCfgLoading = false;
  autoCfgSaving = false;
  autoRunning = false;
  autoCfgMessage = '';
  autoCfgOk = false;
  autoRunResult = '';

  /** Which mode's settings are being edited. Paper first, matching the engine's own default. */
  autoTab: 'PAPER' | 'REAL' = 'PAPER';

  /** The form is a copy, so an unsaved edit never looks like a stored value. */
  autoForm: any = {
    autoBuy: false, autoSell: false, investmentAmount: 100000, maxCapitalPerStock: 0,
    maxOpenPositions: 5, maxDailyTrades: 0, maxDailyLoss: 0,
    stopLossPct: 0, trailingStopPct: 0, targetPct: 0,
    tradingStartTime: '09:15', tradingEndTime: '15:30'
  };

  /** Both gates that a real order has to pass, beyond the toggles on this screen. */
  get autoReady(): boolean {
    return !!this.autoCfg?.realTradingAuthorized && !!this.autoCfg?.brokerReady;
  }

  loadAutoCfg(): void {
    if (this.autoCfgLoading) return;
    this.autoCfgLoading = true;
    this.api.get<any>('/trading/volbreakout/settings').subscribe({
      next: r => {
        this.autoCfg = r?.data || null;
        this.autoCfgLoading = false;
        // Follow the live mode on first read, so the panel opens on the settings that are in
        // force rather than on Paper while Real is the one running.
        if (this.autoCfg?.liveMode === 'REAL') this.autoTab = 'REAL';
        this.resetAutoForm();
      },
      error: () => { this.autoCfg = null; this.autoCfgLoading = false; }
    });
  }

  setAutoTab(tab: 'PAPER' | 'REAL'): void {
    if (this.autoTab === tab) return;
    this.autoTab = tab;
    this.autoCfgMessage = '';
    this.resetAutoForm();
  }

  /** Re-copy the stored values for the selected mode into the form. */
  resetAutoForm(): void {
    const src = (this.autoTab === 'REAL' ? this.autoCfg?.real : this.autoCfg?.paper) || {};
    this.autoForm = {
      autoBuy: !!src.autoBuy,
      autoSell: !!src.autoSell,
      investmentAmount: Number(src.investmentAmount ?? 100000),
      maxCapitalPerStock: Number(src.maxCapitalPerStock ?? 0),
      maxOpenPositions: Number(src.maxOpenPositions ?? 5),
      maxDailyTrades: Number(src.maxDailyTrades ?? 0),
      maxDailyLoss: Number(src.maxDailyLoss ?? 0),
      stopLossPct: Number(src.stopLossPct ?? 0),
      trailingStopPct: Number(src.trailingStopPct ?? 0),
      targetPct: Number(src.targetPct ?? 0),
      tradingStartTime: src.tradingStartTime || '09:15',
      tradingEndTime: src.tradingEndTime || '15:30'
    };
  }

  /**
   * Save the selected mode's settings.
   *
   * <p>Confirmed when it turns on automation for REAL, and not otherwise. A confirmation on
   * every save would be noise, and noise is what gets clicked through.
   */
  saveAutoCfg(): void {
    if (this.autoCfgSaving) return;
    if (this.autoTab === 'REAL' && (this.autoForm.autoBuy || this.autoForm.autoSell)) {
      const ok = confirm(
        'Enable automated REAL trading for this strategy?\n\n' +
        (this.autoForm.autoBuy ? '\u2022 Auto Buy will place real orders.\n' : '') +
        (this.autoForm.autoSell ? '\u2022 Auto Sell will place real exit orders.\n' : '') +
        '\nThey fire only while REAL is the live mode, and only with this account\'s ' +
        'real-trading authorization and a connected broker.\n\nSave these settings?');
      if (!ok) return;
    }
    this.autoCfgSaving = true;
    this.autoCfgMessage = '';
    this.api.post<any>('/trading/volbreakout/settings',
                       { mode: this.autoTab, ...this.autoForm }).subscribe({
      next: r => {
        this.autoCfgSaving = false;
        const d = r?.data || {};
        this.autoCfgOk = true;
        this.autoCfgMessage = d.message || 'Saved.';
        this.loadAutoCfg();
      },
      error: e => {
        this.autoCfgSaving = false;
        this.autoCfgOk = false;
        // The server names every value it refused, so pass that through rather than replacing
        // it with a generic failure - "stop loss must be between 0 and 100" is actionable and
        // "could not save" is not.
        this.autoCfgMessage = e?.error?.message || 'Could not save the settings.';
      }
    });
  }

  toggleStrategyArmed(): void {
    if (this.autoCfgSaving) return;
    const next = !this.autoCfg?.strategyEnabled;
    if (next && this.autoCfg?.liveMode === 'REAL' &&
        !confirm('Arm the strategy while REAL is the live mode?\n\n' +
                 'Automated entries and exits will place real broker orders.')) return;
    this.autoCfgSaving = true;
    this.api.post<any>('/trading/volbreakout/mode',
                       { enabled: next, confirmed: true }).subscribe({
      next: () => { this.autoCfgSaving = false; this.loadAutoCfg(); },
      error: e => {
        this.autoCfgSaving = false;
        this.autoCfgOk = false;
        this.autoCfgMessage = e?.error?.message || 'Could not change the strategy state.';
      }
    });
  }

  /**
   * Switch which mode is live.
   *
   * <p>Separate from saving settings. Editing a Real stop loss and arming Real execution are
   * different decisions, and one button doing both would make the second a side effect.
   */
  switchLiveMode(mode: 'PAPER' | 'REAL'): void {
    if (this.autoCfgSaving) return;
    if (mode === 'REAL') {
      if (!this.autoReady) {
        alert('Real trading is not available on this account yet.\n\n' +
              (!this.autoCfg?.realTradingAuthorized ? '\u2022 Real trading is not enabled.\n' : '') +
              (!this.autoCfg?.brokerReady ? '\u2022 No broker is connected and authorized.\n' : '') +
              '\nNeither can be granted from this screen.');
        return;
      }
      if (!confirm('Make REAL the live mode?\n\n' +
                   'From then on, automated and manual orders from this strategy are sent to ' +
                   'the broker and use real money. Paper is the default and you can switch ' +
                   'back at any time.')) return;
    }
    this.autoCfgSaving = true;
    this.api.post<any>('/trading/volbreakout/mode',
                       { mode, confirmed: true }).subscribe({
      next: r => {
        this.autoCfgSaving = false;
        this.autoCfgOk = true;
        this.autoCfgMessage = r?.data?.message || ('Live mode is now ' + mode + '.');
        this.autoTab = mode;
        this.loadAutoCfg();
      },
      error: e => {
        this.autoCfgSaving = false;
        this.autoCfgOk = false;
        this.autoCfgMessage = e?.error?.message || 'Could not switch the mode.';
      }
    });
  }

  /**
   * Run the automation now.
   *
   * <p>Auto Buy has a dry run and Auto Sell does not, which is deliberate: a preview of the
   * exit sweep would have to re-read every position's high water mark and would still not tell
   * you what the sweep does a minute later, whereas not taking a stop loss because someone was
   * previewing it is a real loss.
   */
  runAuto(which: 'buy' | 'sell', dryRun: boolean): void {
    if (this.autoRunning) return;
    const live = this.autoCfg?.liveMode || 'PAPER';
    if (!dryRun) {
      const what = which === 'buy' ? 'Auto Buy' : 'Auto Sell';
      if (!confirm(`Run ${what} now in ${live} mode?\n\n` +
                   (live === 'REAL'
                     ? 'This sends real broker orders.'
                     : 'This affects simulated positions only.'))) return;
    }
    this.autoRunning = true;
    this.autoRunResult = '';
    const url = which === 'buy'
      ? '/trading/volbreakout/auto-buy?dryRun=' + (dryRun ? 'true' : 'false')
      : '/trading/volbreakout/auto-sell?dryRun=false';
    this.api.post<any>(url, {}).subscribe({
      next: r => {
        this.autoRunning = false;
        this.autoRunResult = this.describeAutoRun(
          (dryRun ? 'Preview - ' : '') + (which === 'buy' ? 'Auto Buy' : 'Auto Sell'), r?.data);
        this.loadBelowPriceTop10();
        this.loadVolumeBreakoutPrevious();
      },
      error: e => {
        this.autoRunning = false;
        this.autoRunResult = e?.error?.message || 'The run failed.';
      }
    });
  }

  runReconcile(): void {
    if (this.autoRunning) return;
    this.autoRunning = true;
    this.autoRunResult = '';
    this.api.post<any>('/trading/reconcile', {}).subscribe({
      next: r => {
        this.autoRunning = false;
        this.autoRunResult = this.describeAutoRun('Reconciliation', r?.data);
      },
      error: e => {
        this.autoRunning = false;
        this.autoRunResult = e?.error?.message || 'Reconciliation failed.';
      }
    });
  }

  /**
   * Turn a run's result into something readable.
   *
   * <p>The engine answers with a message plus a per-symbol list, and the per-symbol list is the
   * part worth reading: it says which stock was skipped and why. Dumping the raw JSON would hide
   * that in punctuation, so each line is named.
   */
  private describeAutoRun(label: string, d: any): string {
    if (!d) return label + ': no response.';
    const lines: string[] = [label + ': ' + (d.message || (d.success ? 'done.' : 'nothing done.'))];
    const orders: any[] = d.orders || d.closed || d.placed || d.results || [];
    if (Array.isArray(orders)) {
      for (const o of orders.slice(0, 25)) {
        if (!o) continue;
        const bits = [o.symbol || '?'];
        if (o.quantity != null) bits.push('qty ' + o.quantity);
        if (o.price != null && Number(o.price) > 0) bits.push('@ ' + o.price);
        if (o.status) bits.push(String(o.status));
        if (o.reason || o.note) bits.push('- ' + (o.reason || o.note));
        lines.push('  ' + bits.join('  '));
      }
      if (orders.length > 25) lines.push('  ...and ' + (orders.length - 25) + ' more.');
    }
    if (d.blockedBy) lines.push('Blocked by: ' + d.blockedBy);
    return lines.join('\n');
  }

  /** The ticked symbols, as an array - templates cannot iterate a Set directly. */
  volBreakSelectedList(): string[] { return Array.from(this.volBreakSelected); }

  volBreakAllSelected(): boolean {
    const rows: any[] = this.lowTop?.top10 || [];
    return rows.length > 0 && rows.every(r => this.volBreakSelected.has(r.symbol));
  }

  /**
   * Ask the server to split the amount across the current selection.
   *
   * <p>Server-side rather than in the browser, and re-asked on every tick, because the quantity
   * depends on a live price. Computing it here from the price in the table would size an order
   * against whatever that cell held when the page last painted.
   */
  volBreakAllocate(): void {
    const symbols = Array.from(this.volBreakSelected);
    if (!symbols.length || !this.volBreakInvestment) { this.volBreakAlloc = null; return; }
    this.volBreakAllocLoading = true;
    this.api.post<any>('/trading/allocate/preview', {
      symbols,
      totalInvestment: this.volBreakInvestment,
      productType: 'DELIVERY',   // held overnight, so delivery charges apply - not intraday
      exchange: 'NSE'
    }).subscribe({
      next: r => { this.volBreakAlloc = r?.data || null; this.volBreakAllocLoading = false; },
      error: () => {
        this.volBreakAlloc = { success: false, legs: [],
                               message: 'Could not calculate the allocation.' };
        this.volBreakAllocLoading = false;
      }
    });
  }

  volBreakPlacing = false;

  /**
   * Why Buy cannot proceed, or '' when it can.
   *
   * <p>The button stays live whenever a selection exists, and this reason is shown beside it.
   * A disabled control with nothing explaining it is indistinguishable from a broken one -
   * which is how this screen was reported - so the block is always stated, never silent.
   */
  volBuyBlockedReason(): string {
    if (!this.volBreakSelected.size) return 'Tick at least one stock to buy.';
    if (!this.volBreakInvestment || this.volBreakInvestment <= 0)
      return 'Enter the total amount to deploy.';
    if (this.volBreakAllocLoading) return 'Sizing the order - one moment.';
    if (!this.volBreakAlloc) return 'Press Recalculate to size the order first.';
    if (this.volBreakAlloc.success === false)
      return this.volBreakAlloc.message || 'The allocation does not fit the available funds.';
    if (!(this.volBreakAlloc.legs || []).some((l: any) => Number(l?.quantity) > 0))
      return 'Every quantity came out at zero - the amount is too small for these prices.';
    return '';
  }

  /**
   * Buy from the allocation panel: the ticked stocks, at the sized quantities.
   *
   * <p>Gated by {@link volBuyBlockedReason} rather than by a disabled attribute, and a block
   * re-asks the server for a fresh allocation on the way out, because the commonest cause is
   * a stale one.
   */
  placeVolBreakoutOrders(): void {
    if (this.volBreakPlacing) return;
    const why = this.volBuyBlockedReason();
    if (why) {
      alert(why);
      if (this.volBreakSelected.size && this.volBreakInvestment) this.volBreakAllocate();
      return;
    }
    const a = this.volBreakAlloc || {};
    const symbols = Array.from(this.volBreakSelected);
    const ok = confirm(
      `Open paper positions in ${symbols.length} stock(s)?

` +
      `Capital deployed: ₹${Number(a.deployedCapital || 0).toLocaleString('en-IN')}
` +
      `Round-trip charges: ₹${Number(a.estimatedRoundTripCharges || 0).toFixed(2)} ` +
      `(${Number(a.estimatedChargesPct || 0).toFixed(2)}%)

` +
      `These are simulated positions. No broker order is placed.`);
    if (!ok) return;
    this.submitVolBreakoutBuy(symbols, this.volBreakInvestment);
  }

  /**
   * Buy a set of stocks that were not sized through the allocation panel - a single row, or the
   * whole frozen list. The amount falls back to the panel's figure so one number governs both
   * paths, and the server sizes the quantities against a fresh price either way.
   */
  buyVolBreakoutSymbols(symbols: string[], what: string): void {
    if (this.volBreakPlacing || this.realPlacing) return;
    const list = (symbols || []).filter(x => !!x);
    if (!list.length) { alert('Nothing to buy.'); return; }
    // Real and paper are different code paths on purpose. Paper is simulated here; a real order
    // goes to /trading/order/submit, which is the only path that runs the pre-trade compliance
    // gate and the broker adapter. Routing a real order through the paper endpoint would reach
    // around that gate - the backend refuses REAL there for exactly this reason.
    if (this.manualTradeMode === 'REAL') { this.placeRealOrders(list, 'BUY', what); return; }
    const amt = this.volBreakInvestment && this.volBreakInvestment > 0
      ? this.volBreakInvestment : 100000;
    const ok = confirm(
      `Open paper positions in ${list.length} stock(s)?

` +
      `${what}
` +
      `₹${Number(amt).toLocaleString('en-IN')} split equally, in whole shares, sized ` +
      `against a fresh price on the server.

` +
      `These are simulated positions. No broker order is placed.`);
    if (!ok) return;
    this.submitVolBreakoutBuy(list, amt);
  }

  /** The single POST both buy paths end in, so they cannot drift apart. */
  private submitVolBreakoutBuy(symbols: string[], amount: number | null): void {
    this.volBreakPlacing = true;
    this.api.post<any>('/trading/volbreakout/place', {
      symbols,
      totalInvestment: amount,
      mode: 'PAPER',
      confirmed: true
    }).subscribe({
      next: r => {
        this.volBreakPlacing = false;
        alert(r?.data?.message || 'Done.');
        // Refresh both lists: the signal and order columns in each are now out of date.
        this.loadBelowPriceTop10();
        this.loadVolumeBreakoutPrevious();
      },
      error: e => {
        this.volBreakPlacing = false;
        alert(e?.error?.message || 'Could not place the orders.');
      }
    });
  }

  volBreakSelling = false;

  /**
   * Close open paper positions in the named stocks at the price read now.
   *
   * <p>Deliberately not the automated sweep: that one exits on its own rules. This is the
   * manual leg of the strategy - the 3:30 PM close-out - and it sells exactly what is asked.
   */
  sellVolBreakoutSymbols(symbols: string[], what: string): void {
    if (this.volBreakSelling || this.realPlacing) return;
    const list = (symbols || []).filter(x => !!x);
    if (!list.length) { alert('Nothing to sell - no stock is selected.'); return; }
    if (this.manualTradeMode === 'REAL') { this.placeRealOrders(list, 'SELL', what); return; }
    const ok = confirm(
      `Close paper positions in ${list.length} stock(s)?

` +
      `${what}
` +
      `Each is sold at the price read now, and the round-trip charges are applied to the ` +
      `realised P&L.

` +
      `Simulated positions only. Nothing reaches the broker.`);
    if (!ok) return;
    this.volBreakSelling = true;
    this.api.post<any>('/trading/volbreakout/sell', {
      symbols: list, mode: 'PAPER', confirmed: true
    }).subscribe({
      next: r => {
        this.volBreakSelling = false;
        alert(r?.data?.message || 'Done.');
        this.loadBelowPriceTop10();
        this.loadVolumeBreakoutPrevious();
      },
      error: e => {
        this.volBreakSelling = false;
        alert(e?.error?.message || 'Could not close the positions.');
      }
    });
  }

  // ── Manual Buy / Sell: Real or Paper ──────────────────────────────────────
  //
  // Both modes are offered and are never mixed. PAPER keeps the existing simulated path
  // (/trading/volbreakout/place and /sell), which states plainly that nothing reaches the
  // broker. REAL goes to /trading/order/submit, the per-order endpoint that runs the mandatory
  // checklist, the pre-trade compliance gate (TradingGateClient.checkRealOrder) and the broker
  // adapter. Nothing here decides an order succeeded: the result reported is whatever the
  // server said, per symbol, and a real order is described as SENT rather than filled because
  // only the reconciler, reading the broker's own order book, can call something filled.

  /** Which account manual Buy/Sell acts on. Paper by default - the safe one. */
  manualTradeMode: 'PAPER' | 'REAL' = 'PAPER';

  /** Verified, trading-enabled broker connections. Empty means no real order can be placed. */
  realBrokers: any[] = [];
  realBrokerId: number | null = null;
  realPlacing = false;
  realOutcome = '';

  /**
   * Load the broker connections a real order could use.
   *
   * <p>Read from the trading dashboard rather than a new endpoint, so this screen and the
   * Trading screen agree on which connections are usable. Read-only connections are excluded:
   * they can report but not trade, and offering one here would produce a refusal at submit time
   * instead of at the point of choosing.
   */
  loadRealBrokers(): void {
    this.api.get<any>('/trading/dashboard').pipe(takeUntil(this.pageGone$)).subscribe({
      next: r => {
        const all: any[] = (r?.data?.brokers || []);
        this.realBrokers = all.filter(b => b && !b.readOnly);
        if (this.realBrokerId == null && this.realBrokers.length === 1) {
          this.realBrokerId = this.realBrokers[0].id;
        }
      },
      error: () => { this.realBrokers = []; }
    });
  }

  /** The price this screen is currently showing for a symbol, across either section. */
  private shownPriceFor(symbol: string): number {
    const pools: any[][] = [this.lowTop?.top10 || [], this.volPrev?.top10 || [],
                            this.volTop?.top10 || []];
    for (const pool of pools) {
      const hit = pool.find((x: any) => x && x.symbol === symbol);
      const px = Number(hit?.current_price);
      if (isFinite(px) && px > 0) return px;
    }
    return 0;
  }

  /**
   * Place real orders, one per symbol, through the gated submit endpoint.
   *
   * <p>Sequential rather than parallel: each order passes the compliance gate on its own, and a
   * burst of concurrent real orders against one broker account is how a partially-placed basket
   * becomes impossible to reason about afterwards.
   */
  private placeRealOrders(symbols: string[], side: 'BUY' | 'SELL', what: string): void {
    if (!this.realBrokers.length) {
      alert('No verified, trading-enabled broker connection is available, so no real order can '
          + 'be placed. Connect and verify a broker with trading enabled first.');
      return;
    }
    if (this.realBrokerId == null) {
      alert('Choose which broker connection to place these real orders through.');
      return;
    }
    const amt = this.volBreakInvestment && this.volBreakInvestment > 0
      ? this.volBreakInvestment : 0;
    const perStock = amt > 0 ? amt / symbols.length : 0;

    // Quantities are worked out and SHOWN before anything is sent. A real order confirmed
    // without a quantity in front of the user is a confirmation of nothing.
    const legs: { symbol: string; price: number; qty: number }[] = [];
    for (const sym of symbols) {
      const px = this.shownPriceFor(sym);
      const qty = side === 'BUY'
        ? (perStock > 0 && px > 0 ? Math.floor(perStock / px) : 0)
        : 0;
      legs.push({ symbol: sym, price: px, qty });
    }
    const unpriced = legs.filter(l => l.price <= 0).map(l => l.symbol);
    if (unpriced.length) {
      alert('No current price is on screen for ' + unpriced.join(', ')
          + ', so a real order cannot be sized. Refresh the section and try again.');
      return;
    }
    if (side === 'BUY' && legs.some(l => l.qty <= 0)) {
      alert('The investment amount is too small to buy a whole share of '
          + legs.filter(l => l.qty <= 0).map(l => l.symbol).join(', ')
          + '. Raise the amount or deselect those stocks.');
      return;
    }

    const broker = this.realBrokers.find(b => b.id === this.realBrokerId);
    const lines = legs.map(l => '  ' + l.symbol + '  ' + (side === 'BUY' ? l.qty + ' sh' : 'full position')
                                + '  @ ~' + l.price.toFixed(2)).join('\n');
    const ok = confirm(
      'REAL ' + side + ' - this sends live orders to your broker.\n\n'
      + what + '\n\n'
      + lines + '\n\n'
      + 'Broker: ' + (broker?.brokerLabel || this.realBrokerId) + '\n'
      + 'Each order must pass the pre-trade compliance gate. An order that is sent is not yet '
      + 'filled - the journal shows it as sent until the broker confirms execution.\n\n'
      + 'Place these real orders?');
    if (!ok) return;

    this.realPlacing = true;
    this.realOutcome = '';
    const results: string[] = [];
    let i = 0;
    const next = () => {
      if (i >= legs.length) {
        this.realPlacing = false;
        this.realOutcome = results.join('\n');
        alert('Real ' + side + ' results:\n\n' + this.realOutcome);
        this.loadBelowPriceTop10();
        this.loadVolumeBreakoutPrevious();
        return;
      }
      const leg = legs[i++];
      this.api.post<any>('/trading/order/submit', {
        mode: 'REAL',
        side,
        symbol: leg.symbol,
        quantity: leg.qty > 0 ? leg.qty : undefined,
        price: leg.price,
        brokerId: this.realBrokerId,
        strategyKey: 'rsi45_manual',
        strategyName: 'RSI 45 manual order',
        confirmed: true
      }).subscribe({
        next: r => {
          // Reported exactly as the server described it. No success is assumed.
          const d = r?.data || {};
          results.push(leg.symbol + ': ' + (d.message || d.status || 'sent to the broker'));
          next();
        },
        error: e => {
          results.push(leg.symbol + ': NOT placed - '
                     + (e?.error?.message || 'the order was refused.'));
          next();
        }
      });
    };
    next();
  }

  // "View all qualifying" for the frozen previous-day list.
  //
  // The snapshot stores only the shortlist, so the full qualifying set of that session is not on
  // record and has to be re-derived. below-price-top10 with useNewestSession=false evaluates the
  // session before the newest - normally the previous trading day - and limit=0 returns every
  // stock that passed, so the same entry test yields the wider list those five came from.
  //
  // It is a RE-DERIVATION, and the overlay says so: computed from the data as it stands now, its
  // membership can differ from what was frozen. Presenting it as the stored record would be the
  // one genuinely misleading way to show it.

  prevDayAllOpen = false;
  prevDayAllLoading = false;
  prevDayAll: any = null;

  openPrevDayAllQualified(): void {
    if (this.prevDayAllLoading) return;
    this.prevDayAllLoading = true;
    this.prevDayAll = null;
    this.api.get<any>('/tracker/ranking/below-price-top10',
                      { requireCrossing: 'true', requireVolume: 'true', applyRanking: 'false',
                        priceAtClose: 'true', limit: '0',
                        requireFullVolumeWindow: 'false', sortByVolumeRatio: 'true',
                        requireRsiLevel: 'true',
                        maxPctAboveBase: String(this.MAX_PCT_ABOVE_BASE),
                        requireStochCross: String(this.lowRequireStoch),
                        useNewestSession: 'false' })
      .pipe(takeUntil(this.pageGone$)).subscribe({
        next: r => {
          this.prevDayAll = r?.data || null;
          this.prevDayAllLoading = false;
          this.prevDayAllOpen = true;
        },
        error: e => {
          this.prevDayAllLoading = false;
          alert(e?.error?.message || 'Could not rebuild the qualifying list for that session.');
        }
      });
  }

  /** True when the re-derived session is not the one the frozen list was stored for. */
  prevDayAllSessionMismatch(): boolean {
    const a = String(this.prevDayAll?.as_of || '').slice(0, 10);
    const b = String(this.volPrev?.trade_date || '').slice(0, 10);
    return !!a && !!b && a !== b;
  }

  /** Every symbol in the frozen previous-day list, in rank order. */
  volPrevSymbols(): string[] {
    return (this.volPrev?.top10 || []).map((p: any) => p.symbol).filter((x: any) => !!x);
  }

  // ── Volume Breakout: daily performance bars ──────────────────────────────
  //
  // Same engine the RSI 45 sections use - /tracker/ranking/reconstruct-trend - with this
  // section's own entry rules passed through: no crossing, volume required, full 20-session
  // window, shortlisted by ratio. Those last two matter: without them a bar would average the
  // five most heavily TRADED qualifiers while the table lists the five most UNUSUAL, which is
  // two different baskets under one heading.



  loadVolumeBreakoutPrevious(): void {
    if (this.volPrevLoading) return;
    this.volPrevLoading = true;
    this.api.get<any>('/tracker/volbreakout/previous').subscribe({
      next: r => {
        this.volPrev = r?.data || null;
        this.volPrevReadAt = new Date().toLocaleTimeString();
        this.volPrevLoading = false;
      },
      error: () => {
        this.volPrev = { top10: [], message: 'Could not read the finalized shortlist.' };
        this.volPrevLoading = false;
      }
    });
  }

  /**
   * Freeze today's shortlist now.
   *
   * <p>Safe to press twice: the server treats a day already finalized as a no-op and says so,
   * rather than recalculating it. Recalculation is precisely what the stored snapshot exists to
   * prevent.
   */
  finalizeVolumeBreakout(): void {
    if (this.volFinalizing) return;
    this.volFinalizing = true;
    this.api.post<any>('/tracker/volbreakout/finalize', {}).subscribe({
      next: r => {
        this.volFinalizing = false;
        const d = r?.data;
        if (d?.message) alert(d.message);
        this.loadVolumeBreakoutPrevious();
      },
      error: () => {
        this.volFinalizing = false;
        alert('Could not finalize the shortlist. Check that daily prices are loaded.');
      }
    });
  }

  // ── Volume Breakout: filters, trading state, charts (spec section 3, 6, 7) ──

  /** Holding period in sessions. Same choices the other sections offer. */
  /** How many stocks the bar chart draws. 0 = every row currently listed. */

  /** Live automation state, so the screen never disagrees with what the scheduler will do. */


  /**
   * Just the clock from a stored timestamp.
   *
   * The column is narrow and every signal on screen is from the same session, so the date would
   * be eleven identical characters repeated down the page. The full value stays in the title.
   */
  shortClock(v: any): string {
    const s = String(v || '');
    if (!s || s === 'null' || s === 'undefined') return '';
    const m = s.match(/(\d{2}:\d{2})/);
    return m ? m[1] : s.slice(0, 10);
  }

  /** Signal and order state, for the table's own columns. */
  vbOrderBg(status: string): string {
    switch ((status || '').toUpperCase()) {
      case 'OPEN': return '#132237';
      case 'CLOSED': return '#0b3b37';
      case 'SIGNAL_ONLY': return '#3a2608';
      default: return '#1b2940';
    }
  }
  vbOrderFg(status: string): string {
    switch ((status || '').toUpperCase()) {
      case 'OPEN': return '#8fb0d4';
      case 'CLOSED': return '#5ff5cd';
      case 'SIGNAL_ONLY': return '#fde68a';
      default: return '#64748b';
    }
  }
  vbOrderLabel(status: string): string {
    switch ((status || '').toUpperCase()) {
      case 'SIGNAL_ONLY': return 'SIGNAL';
      case 'NO_ORDER': return 'NO ORDER';
      default: return status || 'NO ORDER';
    }
  }

  /**
   * Every qualifying stock, not the five on screen - the server builds the file uncapped and with
   * the buyer/seller gate off, so a stock that qualified is in the record whether or not it had a
   * bid at the moment someone opened the page.
   */
  downloadVolumeBreakout(): void {
    this.downloadCsv('/tracker/export/volbreakout',
                     'TradeRakshak_Volume_Breakout_All_Qualifying.csv');
  }

  /**
   * Share counts, abbreviated. A raw 112970400 in a narrow column is read wrongly more often than
   * it is read at all, and the figure that matters next to it is the ratio.
   */
  volFmt(v: any): string {
    const n = Number(v) || 0;
    if (n <= 0) return '—';
    if (n >= 10000000) return (n / 10000000).toFixed(2) + ' Cr';
    if (n >= 100000)   return (n / 100000).toFixed(2) + ' L';
    if (n >= 1000)     return (n / 1000).toFixed(1) + ' K';
    return String(Math.round(n));
  }

  /**
   * Colour by how extreme the ratio is, because the extremes are the ones to look at twice.
   *
   * A ratio above about 20x is almost never an organic breakout - it is a block deal, an index
   * rebalance, an expiry or a corporate action. Those can be real signals, but they are a
   * different kind of event from a stock quietly trading at three times its average, and ranking
   * by ratio puts them at the top of the list every time. The colour is a prompt to check what
   * happened, not a judgement: nothing is excluded on it.
   */
  ratioColor(ratio: any): string {
    const r = Number(ratio) || 0;
    if (r >= 20) return '#f87171';   // extreme - check for a block deal or corporate action
    if (r >= 5)  return '#fbbf24';   // strong
    if (r >= 2)  return '#22c55e';   // a clear breakout
    return '#cbd5e1';
  }

  /**
   * How many stocks the plain RSI 45 section keeps - the heaviest traded five.
   *
   * This section is read as a shortlist to actually hold, so it stays capped; its sibling
   * "with Volume" lists every crossing and passes no limit. The same number is sent to the
   * chart endpoint, so the bars average these five and not the whole qualifying set.
   */
  readonly LOW_SECTION_STOCKS = 5;
  /**
   * Current Price must be above Base Price, but by no more than this percentage.
   *
   * <p>The crossing needs the base to sit between the previous close and the current price, so a
   * stock far above its base can never produce one. On 26 Nov 2026, 378 of 626 candidates were
   * more than 10% above base. It costs no signal - with the ceiling removed entirely the same
   * single stock qualified - but it keeps "tested" meaning reachable candidates, and it must be
   * sent by the tables AND the bars or the two describe different populations.
   */
  readonly MAX_PCT_ABOVE_BASE = 10;

  /**
   * Whether stochastic %K crossing above %D is also required to qualify.
   *
   * On by default because it was asked for as an entry condition, and switchable because it is a
   * second filter on top of the volume test: it will cut the qualifying count, and a user should
   * be able to see the list with and without it rather than wonder where the stocks went.
   */
  /**
   * The stochastic crossover is a MANDATORY part of this section's entry test, not a
   * preference. It was a checkbox, which meant the section could be made to list stocks
   * that had not crossed while still being headed as though they had. Readonly so it cannot
   * drift back: the backend enforces it (StockTrackerService excludes on
   * stochKPrev <= stochDPrev && stochK > stochD), and the table, the "view all" list and
   * the bars all keep sending it, so the three can no longer describe different baskets.
   */
  readonly lowRequireStoch = true;

  /** The full-screen strategy description for this section. */
  strategyDetailsOpen = false;

  /** The full-screen strategy description for the Volume-only section. */
  volStrategyDetailsOpen = false;


  /**
   * Whether the table lists every qualifying stock or only the shortlist.
   *
   * Off by default: the screen's job is the five that would be taken. The full list runs to a
   * couple of hundred rows on an ordinary session, which is what the download is for.
   */
  lowShowAll = false;

  toggleLowShowAll(): void {
    this.lowShowAll = !this.lowShowAll;
    this.loadBelowPriceTop10();
  }

  /** Which holding day the chart is showing. Day 1 is the first trading session after entry. */
  lowHoldingDay = 1;

  // ── Per-stock holding panel (RSI 45 with Volume & Stochastic) ─────────────────────────
  /** Entry day the per-stock panel shows. Defaults to the newest day with stocks. */
  lowEntryDay: string | null = null;
  /** Sessions whose close is the last stored intraday candle, not the official 15:30 close. */
  lowIntradayCloseDates: string[] = [];

  /** Every chart day that has stocks in it, newest first. */
  get lowEntryDays(): string[] {
    return (this.lowTrend || [])
      .filter((d: any) => (d.trades?.length || 0) + (d.unpriced?.length || 0) + (d.open_rows?.length || 0) > 0)
      .map((d: any) => d.trade_date as string)
      .sort((a: string, b: string) => b.localeCompare(a));
  }

  /**
   * One row per stock for the selected entry day at the selected holding period.
   * Return % = (Day N close - entry) / entry x 100, from the same fixed entry price for every
   * holding period. Nothing is substituted for a missing entry or exit: the row says which one
   * is missing and stays out of the average.
   */
  get lowEntryRows(): any[] {
    const day = (this.lowTrend || []).find((d: any) => d.trade_date === this.lowEntryDay);
    if (!day) return [];
    const n = Number(this.lowHoldingDay) || 1;
    const all = [...(day.trades || []), ...(day.unpriced || []), ...(day.open_rows || [])]
      .sort((a: any, b: any) => (a.rank ?? 99) - (b.rank ?? 99));
    return all.map((t: any) => {
      const entry = Number(t.buy_price) > 0 ? Number(t.buy_price) : null;
      const exitRaw = t['day' + n + '_price'];
      const exit = entry != null && exitRaw != null && Number(exitRaw) > 0 ? Number(exitRaw) : null;
      const exitDate: string | null = exit != null ? t['day' + n + '_date'] : null;
      const retRaw = t['day' + n + '_return'];
      const ret = entry != null && exit != null && retRaw != null ? Number(retRaw) : null;
      // A later holding day already priced means this session traded but its close is missing -
      // a data gap, not a session still to come.
      const laterPriced = this.holdingDayChoices.some(k => k > n && t['day' + k + '_price'] != null);
      const status = entry == null ? 'Entry Price Unavailable'
                   : exit == null ? (laterPriced ? 'Exit Price Unavailable' : 'Exit Price Pending')
                   : 'Included';
      const entryDate = t.buy_date || day.trade_date;
      const entryIsClose = t.entry_source === 'close';
      const exitIsCandle = !!exitDate && this.lowIntradayCloseDates.includes(exitDate);
      return {
        symbol: t.symbol, entry, exit, ret, status, entryIsClose, exitIsCandle,
        prev: t.prev_close ?? null,
        prevLabel: t.prev_close_date ? this.fmtLongDate(t.prev_close_date) + ' · 15:30 IST close' : 'Previous close unavailable',
        entryLabel: entry == null ? 'Entry Price Unavailable'
                  : this.fmtLongDate(entryDate) + (entryIsClose
                      ? ' · day close (no ' + (t.buy_time || '15:15') + ' candle)'
                      : ' · ' + (t.buy_time || '15:15') + ' IST'),
        exitLabel: exit == null ? status
                 : this.fmtLongDate(exitDate) + (exitIsCandle
                     ? ' · last intraday candle (no official close stored)'
                     : ' · 15:30 IST close')
      };
    });
  }

  /** Today Avg Return: plain average of the valid stock returns, nothing else. */
  get lowEntrySummary(): { avg: number | null; included: number; excluded: number; exitDates: string[] } {
    const rows = this.lowEntryRows;
    const valid = rows.filter(r => r.ret != null);
    const avg = valid.length ? valid.reduce((s, r) => s + r.ret, 0) / valid.length : null;
    const day = (this.lowTrend || []).find((d: any) => d.trade_date === this.lowEntryDay);
    const n = Number(this.lowHoldingDay) || 1;
    const exitDates = Array.from(new Set<string>(
      [...(day?.trades || []), ...(day?.unpriced || []), ...(day?.open_rows || [])]
        .map((t: any) => t['day' + n + '_date']).filter((x: any) => !!x))).sort();
    return { avg, included: valid.length, excluded: rows.length - valid.length, exitDates };
  }

  get lowEntryMax(): number {
    return Math.max(0.1, ...this.lowEntryRows.filter(r => r.ret != null).map(r => Math.abs(r.ret)));
  }

  /** '2026-11-26' -> '26 Nov 2026'. */
  fmtLongDate(iso: string | null | undefined): string {
    if (!iso) return '-';
    const [y, m, d] = String(iso).slice(0, 10).split('-').map(Number);
    const mon = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][(m || 1) - 1];
    return (d || '') + ' ' + mon + ' ' + (y || '');
  }
  readonly fmtLongDateFn = (iso: string) => this.fmtLongDate(iso);
  /**
   * Daily detail over the first two weeks, then the monthly checkpoints.
   *
   * Every day is measured from the ENTRY price, so Day 30 is entry-to-day-30 and not a chain of
   * thirty one-day moves. Must stay in step with HOLDING_DAY_CHOICES in StockTrackerService -
   * the server emits a dayN_return only for the days listed there, and a day offered here that
   * it does not emit would read as "pending" forever rather than as an error.
   */
  readonly holdingDayChoices = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30];

  // ── Holding-period view for "RSI 45 ... with Volume" ──────────────────────
  //
  // The same shape as the "& Stochastic" section above, deliberately: one row per stock for a
  // chosen entry day, priced from that day's fixed entry to the close of the selected holding
  // period. It reads volTrend instead of lowTrend, so it describes THIS section's basket - the
  // one qualified WITHOUT the stochastic crossover. Nothing about that qualification, its
  // ranking, its entry or its backtest changes here; this is the same view over a different
  // population, which is what makes the two sections comparable at a glance.
  //
  // volHoldingDay and setVolHoldingDay already existed for the day-level bars; they are reused
  // rather than duplicated, so this section has one holding-period control, not two.

  volEntryDay: string | null = null;
  volIntradayCloseDates: string[] = [];

  /** Every charted day for this section that has stocks in it, newest first. */
  get volEntryDays(): string[] {
    return (this.volTrend || [])
      .filter((d: any) => (d.trades?.length || 0) + (d.unpriced?.length || 0) + (d.open_rows?.length || 0) > 0)
      .map((d: any) => d.trade_date as string)
      .sort((a: string, b: string) => b.localeCompare(a));
  }

  /**
   * One row per stock for the selected entry day at the selected holding period.
   * Return % = (Day N close - entry) / entry x 100, measured from the same fixed entry price
   * whatever the holding period. A missing entry or exit is named, not substituted, and keeps
   * the row out of the average.
   */
  get volEntryRows(): any[] {
    const day = (this.volTrend || []).find((d: any) => d.trade_date === this.volEntryDay);
    if (!day) return [];
    const n = Number(this.volHoldingDay) || 1;
    const all = [...(day.trades || []), ...(day.unpriced || []), ...(day.open_rows || [])]
      .sort((a: any, b: any) => (a.rank ?? 99) - (b.rank ?? 99));
    return all.map((t: any) => {
      const entry = Number(t.buy_price) > 0 ? Number(t.buy_price) : null;
      const exitRaw = t['day' + n + '_price'];
      const exit = entry != null && exitRaw != null && Number(exitRaw) > 0 ? Number(exitRaw) : null;
      const exitDate: string | null = exit != null ? t['day' + n + '_date'] : null;
      const retRaw = t['day' + n + '_return'];
      const ret = entry != null && exit != null && retRaw != null ? Number(retRaw) : null;
      // A later holding day already priced means this session traded but its close is missing -
      // a data gap, not a session still to come.
      const laterPriced = this.holdingDayChoices.some(k => k > n && t['day' + k + '_price'] != null);
      const status = entry == null ? 'Entry Price Unavailable'
                   : exit == null ? (laterPriced ? 'Exit Price Unavailable' : 'Exit Price Pending')
                   : 'Included';
      const entryDate = t.buy_date || day.trade_date;
      const entryIsClose = t.entry_source === 'close';
      const exitIsCandle = !!exitDate && this.volIntradayCloseDates.includes(exitDate);
      return {
        symbol: t.symbol, entry, exit, ret, status, entryIsClose, exitIsCandle,
        prev: t.prev_close ?? null,
        prevLabel: t.prev_close_date ? this.fmtLongDate(t.prev_close_date) + ' \u00b7 15:30 IST close' : 'Previous close unavailable',
        entryLabel: entry == null ? 'Entry Price Unavailable'
                  : this.fmtLongDate(entryDate) + (entryIsClose
                      ? ' \u00b7 day close (no ' + (t.buy_time || '15:15') + ' candle)'
                      : ' \u00b7 ' + (t.buy_time || '15:15') + ' IST'),
        exitLabel: exit == null ? status
                 : this.fmtLongDate(exitDate) + (exitIsCandle
                     ? ' \u00b7 last intraday candle (no official close stored)'
                     : ' \u00b7 15:30 IST close')
      };
    });
  }

  /** Today Avg Return for this section: plain average of the valid stock returns. */
  get volEntrySummary(): { avg: number | null; included: number; excluded: number; exitDates: string[] } {
    const rows = this.volEntryRows;
    const valid = rows.filter(r => r.ret != null);
    const avg = valid.length ? valid.reduce((s, r) => s + r.ret, 0) / valid.length : null;
    const day = (this.volTrend || []).find((d: any) => d.trade_date === this.volEntryDay);
    const n = Number(this.volHoldingDay) || 1;
    const exitDates = Array.from(new Set<string>(
      [...(day?.trades || []), ...(day?.unpriced || []), ...(day?.open_rows || [])]
        .map((t: any) => t['day' + n + '_date']).filter((x: any) => !!x))).sort();
    return { avg, included: valid.length, excluded: rows.length - valid.length, exitDates };
  }

  get volEntryMax(): number {
    return Math.max(0.1, ...this.volEntryRows.filter(r => r.ret != null).map(r => Math.abs(r.ret)));
  }

  /**
   * How many of the most recent bars to draw. 0 means every bar in the loaded range.
   *
   * This trims the chart only at the right-hand edge; it never refetches. The figures printed
   * under a chart are computed from the SAME trimmed set, so the win rate and average always
   * describe the bars actually on screen rather than a wider population behind them.
   */
  barsOnScreen = 30;
  readonly barsOnScreenChoices = [10, 20, 30, 50, 100, 0];

  /**
   * How far back the bars need rebuilding, given how many are shown.
   *
   * <p>Removing the From/To pickers made both charts ask for the whole rebuildable history -
   * 222 sessions on the server, taking about twelve seconds - to draw the thirty bars that fit
   * on screen. The window is now sized to the drawing: the bars actually shown, plus the
   * holding period they are measured over, plus slack for weekends and holidays.
   *
   * <p>"All" (0) still means everything; that is the one case where the wait is the point.
   * Returns null when no limit should be sent.
   */
  private trendFromDate(holdingDay: number): string | null {
    const bars = Number(this.barsOnScreen) || 0;
    if (bars <= 0) return null;                       // "All" - ask for the whole history
    const tradingDays = bars + (Number(holdingDay) || 1) + 10;   // +10 so the edge is never thin
    const calendarDays = Math.ceil(tradingDays * 1.5) + 7;       // weekends and holidays
    return new Date(Date.now() - calendarDays * 86400000).toISOString().slice(0, 10);
  }

  setHoldingDay(d: number): void {
    const widened = Number(d) > (Number(this.lowHoldingDay) || 1);
    this.lowHoldingDay = d;
    // The bars only need refetching when the window widens - a narrower period is already
    // inside the data on hand. The TABLE always does: its Return % is measured server-side to
    // the selected exit session, so leaving it would show Day 1 returns under a Day 3 heading.
    this.loadBelowPriceTop10();
    if (widened) this.runLowPriceTrend();
  }
  setVolHoldingDay(d: number): void {
    const widened = Number(d) > (Number(this.volHoldingDay) || 1);
    this.volHoldingDay = d;
    // The table as well as the bars, for the same reason as setHoldingDay: this table's
    // Return % is measured server-side to the selected exit session.
    this.loadVolumeTop10();
    if (widened) this.runVolumeTrend();
  }
  setBatchHoldingDay(d: number): void { this.batchHoldingDay = d; }
  /** The fetch window is sized from this, so widening it has to go back to the server. */
  setBarsOnScreen(n: number): void {
    const widened = (n === 0) || (Number(n) > (Number(this.barsOnScreen) || 0));
    this.barsOnScreen = n;
    if (widened) { this.runLowPriceTrend(); this.runVolumeTrend(); }
  }
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
                      // requireRsiLevel: the same mandatory condition the table above applies.
                      // Without it this panel screened on the crossing and volume only, so two
                      // lists under one heading answered different questions - on 26 Nov 2026 the
                      // table left 1 stock and this panel 13.
                      { requireCrossing: 'true', applyRanking: 'false', limit: '10',
                        requireRsiLevel: 'true',
                        // This section's own entry test, so the preview screens the same stocks
                        // the section does. Without these it previewed stocks below their
                        // 20-session average volume, or sitting far above their base price -
                        // neither of which this section would ever list.
                        requireVolume: 'true',
                        maxPctAboveBase: String(this.MAX_PCT_ABOVE_BASE) }).pipe(takeUntil(this.pageGone$)).subscribe({
      next: r => { this.volProvisional = r?.data || null; },
      error: () => { this.volProvisional = null; }
    });
    // limit=10: of everything that qualified, show the ten most heavily traded. Applied after
    // identification, so it narrows the view rather than changing which stocks pass the test.
    // useNewestSession: this section is a SCREEN - "every stock that qualifies" - so it has to
    // answer for the newest session with data, not the one before it. The holding panels above
    // keep the previous-session reading, which is right for them: what you hold today was
    // selected at yesterday's cut-off. Evaluating a session back here is why a stock crossing
    // today read as not qualifying.
    this.api.get<any>('/tracker/ranking/below-price-top10',
                      // limit 0, not 10. The heading says "every stock that qualifies is
                      // listed" and a cap of ten silently contradicted it - and left the bars
                      // below averaging a larger population than the table showed, which is two
                      // different answers under one heading.
                      // sortByReturn: the same heading says "strongest move first". The list was
                      // ordered by raw traded volume instead.
                      // requireRsiLevel: the RSI 45 crossover is a MANDATORY entry
                      // condition for this section, and it was not being applied. The backend
                      // computed rsi14, displayed it on the row, and never tested it - so a
                      // section headed "RSI 45" listed stocks that had not crossed 45.
                      { requireCrossing: 'true', requireVolume: 'true', applyRanking: 'false',
                        priceAtClose: 'true', limit: '0',
                        sortByReturn: 'true',
                        // RSI 45 crossover switched off by the user's decision (27 Nov 2026).
                        // The values stay on every row; only the exclusion is lifted.
                        requireRsiLevel: 'true',
                        maxPctAboveBase: String(this.MAX_PCT_ABOVE_BASE),
                        useNewestSession: 'true',
                        // The selected exit session, as the & Stochastic section sends. Without
                        // it this table's Return % stayed on the server's Day 1 default while
                        // the section's own Holding period selector said otherwise - so the two
                        // sections reported different Return % for the same holding period, and
                        // this table disagreed with the bars directly beneath it.
                        holdingDays: String(this.volHoldingDay) }).pipe(takeUntil(this.pageGone$)).subscribe({
      next: r => { this.volTop = r?.data || null; this.volTopLoading = false; },
      error: () => {
        this.volTop = { top10: [], message: 'Could not build the list. Check that prices are loaded.' };
        this.volTopLoading = false;
      }
    });
  }

  /** Bars for the volume-filtered section - same population as its table, same pricing. */
  /**
   * Refresh this section: its table and the bars beneath it.
   *
   * <p>Mirrors refreshLowSection for the same reason - with the Run button gone, Refresh is
   * the only control that redraws the bars, and bars describing an older basket than the
   * table above them is a disagreement nothing on the page would reveal.
   */
  refreshVolSection(): void {
    this.loadVolumeTop10();
    this.runVolumeTrend();
  }

  runVolumeTrend() {
    if (!this.volTopLoading) this.loadVolumeTop10();
    if (!this.volTo) this.volTo = new Date().toISOString().slice(0, 10);
    this.volRefreshing = true;
    this.api.get<any>('/tracker/ranking/reconstruct-trend', {
      ...(this.trendFromDate(this.volHoldingDay) ? { from: this.trendFromDate(this.volHoldingDay)! } : {}),
      to: this.volTo, applyReturnBand: 'false', requireCrossing: 'true',
      applyRanking: 'false', requireVolume: 'true', priceAtClose: 'true',
      // The same mandatory RSI crossover the table applies. Without it the bars measured a
      // different strategy from the one the section screens for.
      requireRsiLevelBars: 'true',
      // The same ceiling the table applies. Without it the bars averaged 626 stocks where the
      // table listed 248, so a bar described a population that was not on screen.
      maxPctAboveBaseBars: String(this.MAX_PCT_ABOVE_BASE)
    }).pipe(takeUntil(this.pageGone$)).subscribe({
      next: r => {
        const d = r?.data || {};
        if (d.coverage_from) this.volCoverageFrom = d.coverage_from;
        this.volTrend = (d.days || [])
          .map((x: any) => ({
            no_stock:       x.trade_avg_return == null && x.trade_source !== 'open',
            is_open:        x.trade_source === 'open',
            // A data gap, not an outcome: stocks qualified and none could be priced.
            no_price:       x.trade_source === 'no_price',
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
            // Carried so this section's holding-period view can report the stocks it could NOT
            // price, rather than silently listing fewer rows than qualified. The & Stochastic
            // section has always carried both; omitting them here is why only priced rows
            // would have appeared.
            unpriced:       x.unpriced || [],
            open_rows:      x.open_rows || [],
            strategy:       'Top 10'
          }));
        this.volIntradayCloseDates = d.intraday_close_dates || [];
        // Keep the chosen entry day if it is still on the chart, else the newest one.
        if (!this.volEntryDays.includes(this.volEntryDay || '')) {
          this.volEntryDay = this.volEntryDays[0] || null;
        }
        this.volResult = {
          from_date:      this.volCoverageFrom || this.volFrom,
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
                      { requireCrossing: 'false', priceAtClose: 'true' }).pipe(takeUntil(this.pageGone$)).subscribe({
      next: r => { this.mainTop = r?.data || null; },
      error: () => { this.mainTop = null; }
    });
    // mainProvisional belongs to the Avg Return Trend panel, so it must use THAT panel's
    // entry test - requireCrossing=false, exactly as mainTop above. It was briefly given the
    // RSI 45 section's rules by mistake, which is why the list headed "tomorrow" here stopped
    // resembling the ranked list it is supposed to precede.
    this.api.get<any>('/tracker/ranking/provisional-top10',
                      { requireCrossing: 'false' }).pipe(takeUntil(this.pageGone$)).subscribe({
      next: r => { this.mainProvisional = r?.data || null; },
      error: () => { this.mainProvisional = null; }
    });
  }

  loadBelowPriceTop10() {
    // Section B reads the immutable snapshot, so it loads with this section.
    this.loadVolumeBreakoutPrevious();
    if (this.lowTopLoading) return;
    this.lowTopLoading = true;
    // The provisional preview this section used to show is gone: section B now reads the
    // immutable previous-day snapshot instead, so there is nothing to recalculate here. Its
    // fetch is removed rather than left running for a panel that no longer exists.
    // requireVolume=false: this section is defined by its price rules alone. Volume is still
    // reported on every row, it just no longer excludes a stock.
    // applyRanking=false: it is a screen, not a Top 10. Scoring would cut qualifying stocks out
    // of a list whose whole purpose is to show which ones qualify.
    // Entry test: the crossing (Previous Close < Base Price AND Current Price >= Base Price)
    // AND the volume condition AND, optionally, the stochastic cross. The volume test was added
    // to the crossing, not substituted for it. Shortlisted by volume ratio and held to a full
    // 20-session window, because the ratio is the selection key and a ratio measured over six
    // sessions is not comparable with one measured over twenty.
    //
    // useNewestSession: this is "Current Day Stocks", so it has to answer for the current
    // session. The default is the session BEFORE the newest, which is right for a panel showing
    // what is already HELD - that framing belonged to the older design, where the panel below
    // was a provisional preview. It now reads the immutable previous-day snapshot instead, so
    // the two sections are "today" and "yesterday" and this one must be today. Left on the
    // default it showed the previous session's qualifiers under a heading saying current day.
    this.api.get<any>('/tracker/ranking/below-price-top10',
                      { requireCrossing: 'true', requireVolume: 'true', applyRanking: 'false',
                        priceAtClose: 'true',
                        limit: this.lowShowAll ? '0' : String(this.LOW_SECTION_STOCKS),
                        // Show what the data supports rather than hiding a stock for having
                        // less of it. A stock with twelve sessions of history was dropped
                        // outright, so the screen reported "nothing qualified" when it meant
                        // "nothing qualified with twenty sessions behind it" - and nothing on
                        // the page said so. The window length is now shown per row instead, so
                        // a six-session ratio is visibly not a twenty-session one.
                        requireFullVolumeWindow: 'false', sortByVolumeRatio: 'true',
                        useNewestSession: 'true',
                        // Mandatory here too, and equally absent before: this section's entry
                        // test is the RSI 45 crossover AND the base-price crossing AND volume
                        // AND the stochastic cross. Only the last three were enforced.
                        requireRsiLevel: 'true',
                        maxPctAboveBase: String(this.MAX_PCT_ABOVE_BASE),
                        requireStochCross: String(this.lowRequireStoch),
                        // The same exit session the bars use. Sent on both calls so the table's
                        // Return % and the chart's bars can never describe different holding
                        // periods - the two disagreeing silently is the failure mode here.
                        holdingDays: String(this.lowHoldingDay) }).pipe(takeUntil(this.pageGone$)).subscribe({
      next: r => { this.lowTop = r?.data || null; this.lowTopLoading = false; },
      error: () => {
        this.lowTop = { top10: [], message: 'Could not build the list. Check that prices are loaded.' };
        this.lowTopLoading = false;
      }
    });
  }

  /**
   * Refresh this whole section: the table and the bars beneath it.
   *
   * <p>The Run button used to redraw the bars and Refresh only re-read the table. With Run gone
   * (it offered no action the section could not do for itself), Refresh has to cover both -
   * otherwise the bars would keep describing the basket from whenever the page was opened while
   * the table above them moved, which is exactly the kind of silent disagreement between the two
   * that this screen has had before.
   */
  refreshLowSection(): void {
    this.loadBelowPriceTop10();
    this.runLowPriceTrend();
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
      // requireVolume=false so the bars average the population this table lists. The endpoint
      // applied the volume test unconditionally, so these bars were drawn from a filtered basket
      // under a heading whose table shows every crossing - the same mismatch applyRanking=false
      // was added to prevent.
      // priceAtClose is OFF and sellAtClose is ON: bought at the 15:15 cut-off on the
      // selection day, sold at the NEXT trading day's official close - which is the trade this
      // section describes. Priced close-to-close the bars measured an entry half an hour after
      // the one the shortlist is frozen at, on a strategy where that half hour is the entry.
      // buyTime is left unset so it follows the configured cut-off rather than a second copy
      // of it that could drift.
      ...(this.trendFromDate(this.lowHoldingDay) ? { from: this.trendFromDate(this.lowHoldingDay)! } : {}),
      to: this.lowTo, applyReturnBand: 'false', requireFullVolumeWindow: 'false', sortByVolumeRatio: 'true', requireCrossing: 'true',
      sellAtClose: 'true',
      // Same five stocks the table keeps, so a bar is the average of the rows above it.
      applyRanking: 'false', requireVolume: 'true', priceAtClose: 'false',
      // The stochastic condition the table is using. Without it the bars averaged a basket
      // chosen WITHOUT the crossing while the table listed one chosen WITH it - two different
      // strategies under one heading, each looking correct on its own.
      requireStochCrossBars: String(this.lowRequireStoch),
      holdingDays: String(this.lowHoldingDay),
      requireRsiLevelBars: 'true',
      limit: String(this.LOW_SECTION_STOCKS),
      maxPctAboveBaseBars: String(this.MAX_PCT_ABOVE_BASE)
    }).pipe(takeUntil(this.pageGone$)).subscribe({
      next: r => {
        const d = r?.data || {};
        // Show the picker the span actually drawn, so "from" is not left blank after a full load.
        if (d.coverage_from) this.lowCoverageFrom = d.coverage_from;
        // Days where nothing qualified are KEPT and flagged. Dropping them left an unexplained
        // hole in the run of dates, which reads as missing data rather than as the real and
        // meaningful outcome it is: no stock met the entry rules that day.
        this.lowTrend = (d.days || [])
          .map((x: any) => ({
            // Three distinct states, not two: settled, still open, or nothing qualified. Collapsing
            // the last two into "No Stock" mislabelled a live position as an empty day.
            no_stock:       x.trade_avg_return == null && x.trade_source !== 'open',
            is_open:        x.trade_source === 'open',
            // A data gap, not an outcome: stocks qualified and none could be priced.
            no_price:       x.trade_source === 'no_price',
            entry_fell_back: x.entry_fell_back_to_close || 0,
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
            // Stocks with no Day 1 price, and the still-open day's stocks. The per-stock holding
            // panel needs them: a missing Day 1 does not mean a missing Day 2, and an open day's
            // stocks must show as "Exit Price Pending", not disappear.
            unpriced:       x.unpriced || [],
            open_rows:      x.open_rows || [],
            strategy:       'Top 10'
          }));
        this.lowIntradayCloseDates = d.intraday_close_dates || [];
        // Keep the user's chosen entry day if it is still on the chart, else the newest one.
        if (!this.lowEntryDays.includes(this.lowEntryDay || '')) {
          this.lowEntryDay = this.lowEntryDays[0] || null;
        }
        this.lowResult = {
          from_date:      this.lowCoverageFrom || this.lowFrom,
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
                      { from, to, priceAtClose: 'true' }).pipe(takeUntil(this.pageGone$)).subscribe({
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
    // The two RSI 45 sections were never in this path. Their rows only ever changed when the
    // whole section was re-fetched - every two minutes for Current Day, and never at all for
    // Previous Day - so a price on screen could be minutes old while the same price in the
    // Uploaded Stock Details table above it was current.
    this.applyQuoteToRsi45(symbol, price, updatedAt, Number(q?.volume || 0));
    if (this.tradeDraft?.symbol && this.normalizeSymbol(this.tradeDraft.symbol) === symbol) {
      this.tradeDraft.price = price;
    }
    this.dash.last_refreshed = updatedAt;
  }

  dataHealth: any = null;
  dataHealthLoading = false;

  /**
   * What the platform can measure right now.
   *
   * <p>Read once per page load and on demand. Cheap - a handful of counts - and it is the only
   * thing that can tell a reader whether an empty section is an answer or an outage.
   */
  loadDataHealth(): void {
    if (this.dataHealthLoading) return;
    this.dataHealthLoading = true;
    this.api.get<any>('/tracker/data-health').pipe(takeUntil(this.pageGone$)).subscribe({
      next: r => { this.dataHealth = r?.data || null; this.dataHealthLoading = false; },
      error: () => { this.dataHealth = null; this.dataHealthLoading = false; }
    });
  }

  /** Days whose entry had to be priced at the close for want of a 15:15 intraday bar. */
  lowEntryFallbackDays(): number {
    return (this.lowTrend || []).filter((d: any) => (d.entry_fell_back || 0) > 0).length;
  }

  /** When the previous-day section last read prices, for the line in its header. */
  volPrevReadAt = '';

  /**
   * Mark both RSI 45 sections to a live tick.
   *
   * <p>Only the figures that genuinely follow the last traded price are touched: the price
   * itself, the return measured from whatever that row's reference price is, and the
   * section-level averages built from those returns. Volume, the 20-session average, the ratio
   * and the indicators are NOT touched - a quote packet's volume is a running total for the
   * session and the ratio behind this shortlist was computed against completed sessions, so
   * recomputing it here from a partial figure would show a number the screen never selected on.
   *
   * <p>The averages are recomputed with the same formula the server uses, from the same
   * reference prices it sends, so the header cannot drift away from the rows beneath it.
   */
  private applyQuoteToRsi45(symbol: string, price: number, updatedAt: string,
                            dayVolume: number): void {
    if (!symbol || price <= 0) return;

    const cur = this.lowTop?.top10;
    if (Array.isArray(cur)) {
      let hit = false;
      this.lowTop.top10 = cur.map((r: any) => {
        if (this.normalizeSymbol(r?.symbol) !== symbol) return r;
        hit = true;
        const entry = Number(r.entry_price || 0);
        const first = Number(r.first_run_price || 0);
        return {
          ...r,
          _prev_price: Number(r.current_price) > 0 ? Number(r.current_price) : undefined,
          current_price: price,
          is_live: true,
          return_pct: entry > 0
            ? Math.round(((price - entry) / entry) * 10000) / 100 : r.return_pct,
          return_from_first_run: first > 0
            ? Math.round(((price - first) / first) * 10000) / 100 : r.return_from_first_run,
          // Volume and the ratio move with the tick too.
          //
          // These were deliberately left alone when the live feed was first wired in, on the
          // grounds that a quote's volume is a running session total while the ratio behind the
          // shortlist was measured over completed sessions - two different things to show side
          // by side. That is no longer true: during a live session the server now builds
          // today's bar from this same cumulative figure, so the number on screen and the
          // number the stock was selected on are one and the same. Leaving them frozen was the
          // thing making them disagree.
          ...this.liveVolumeFields(r, dayVolume),
          last_refreshed: updatedAt
        };
      });
      if (hit) this.recomputeLowAverages();
    }

    const prev = this.volPrev?.top10;
    if (Array.isArray(prev)) {
      let hit = false;
      this.volPrev.top10 = prev.map((p: any) => {
        if (this.normalizeSymbol(p?.symbol) !== symbol) return p;
        // A closed row is settled. Marking it to a later price would overwrite the return the
        // position actually realised with one it never saw.
        if (p.status === 'CLOSED') return p;
        hit = true;
        const entry = Number(p.entry_price || 0);
        return {
          ...p,
          current_price: price,
          return_pct: entry > 0
            ? Math.round(((price - entry) / entry) * 10000) / 100 : p.return_pct,
          ...this.liveVolumeFields(p, dayVolume)
        };
      });
      if (hit) {
        this.recomputePrevAverage();
        this.volPrevReadAt = new Date().toLocaleTimeString();
      }
    }
  }

  /**
   * Today's volume and ratio from the running quote.
   *
   * <p>The ratio is recomputed against the row's OWN 20-session average - the same denominator
   * the server used - so the figure on screen is the server's arithmetic with a fresher
   * numerator, not a second opinion. A quote with no volume yet changes nothing: before the
   * first trade the cumulative total is zero, and showing that would read as a stock that had
   * stopped trading rather than one that had not started.
   */
  private liveVolumeFields(row: any, dayVolume: number): any {
    const vol = Number(dayVolume) || 0;
    if (vol <= 0) return {};
    const avg = Number(row?.avg_20d_volume) || 0;
    const out: any = { volume: vol, volume_is_live: true };
    if (avg > 0) out.volume_ratio = Math.round((vol / avg) * 100) / 100;
    return out;
  }

  /** Today Avg Return for Current Day Stocks, from each row's own first-run reference price. */
  private recomputeLowAverages(): void {
    const rows: any[] = this.lowTop?.top10 || [];
    let sum = 0, n = 0;
    for (const r of rows) {
      const v = Number(r?.return_from_first_run);
      if (!isFinite(v)) continue;
      sum += v; n++;
    }
    this.lowTop.avg_return_from_first_run = n === 0 ? null : Math.round((sum / n) * 100) / 100;
    this.lowTop.first_run_priced = n;
  }

  /** Today Avg Return for the frozen list: the same average of return_pct the server takes. */
  private recomputePrevAverage(): void {
    const rows: any[] = this.volPrev?.top10 || [];
    let sum = 0, n = 0;
    for (const p of rows) {
      const v = Number(p?.return_pct);
      if (p?.return_pct == null || !isFinite(v)) continue;
      sum += v; n++;
    }
    this.volPrev.avg_return_pct = n === 0 ? null : Math.round((sum / n) * 100) / 100;
    this.volPrev.priced = n;
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
    }).pipe(takeUntil(this.pageGone$)).subscribe({
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
