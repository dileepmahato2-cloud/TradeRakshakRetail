import { Component, OnInit, OnDestroy, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { RealtimeService } from '../../core/services/realtime.service';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { Chart, registerables } from 'chart.js';
Chart.register(...registerables);

@Component({
  selector: 'app-market-breadth',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host { display:block; font-family:'Inter',sans-serif; }
    .card { background:#1e2a3b; border:1px solid #2d3f55; border-radius:12px; }
    .tbl-hdr { background:#1a3a5c; color:#fff; font-size:11px; font-weight:600; padding:8px 10px; text-align:left; border-bottom:1px solid #2d3f55; }
    .tbl-row { border-bottom:1px solid #1e2a3b; transition:background 0.1s; }
    .tbl-row:hover { background:#1a2535; }
    .tbl-cell { font-size:11px; padding:7px 10px; color:#cbd5e1; }
    .tbl-cell-num { font-size:11px; padding:7px 10px; color:#cbd5e1; text-align:right; font-family:monospace; }
    .pos { color:#22c55e; font-weight:600; }
    .neg { color:#ef4444; font-weight:600; }
    .neutral-c { color:#f59e0b; font-weight:600; }
    .badge-green { background:#14532d; color:#4ade80; border:1px solid #16a34a; border-radius:4px; padding:2px 7px; font-size:10px; font-weight:600; }
    .badge-amber { background:#3a2608; color:#fde68a; border:1px solid #d97706; border-radius:4px; padding:2px 7px; font-size:10px; font-weight:600; }
    .badge-red { background:#450a0a; color:#fca5a5; border:1px solid #dc2626; border-radius:4px; padding:2px 7px; font-size:10px; font-weight:600; }
    .badge-blue { background:#1e3a5f; color:#93c5fd; border:1px solid #2563eb; border-radius:4px; padding:2px 7px; font-size:10px; font-weight:600; }
    .btn-blue { background:#1d6adb; color:#fff; border:none; border-radius:6px; padding:8px 16px; font-size:12px; font-weight:600; cursor:pointer; }
    .btn-blue:hover { background:#1a5fc7; }
    .btn-outline { background:transparent; color:#60a5fa; border:1px solid #2563eb; border-radius:6px; padding:5px 12px; font-size:11px; cursor:pointer; }
    .btn-outline:hover { background:#1e3a5f; }
    .section-title { color:#fff; font-size:13px; font-weight:700; }
    .select-sm { background:#1e2a3b; border:1px solid #2d3f55; border-radius:5px; color:#94a3b8; font-size:11px; padding:3px 8px; cursor:pointer; }
    .spin { width:14px; height:14px; border:2px solid rgba(255,255,255,.3); border-top-color:#fff; border-radius:50%; animation:spin .7s linear infinite; display:inline-block; }
    @keyframes spin { to { transform:rotate(360deg); } }
    .meter-track { height:14px; background:linear-gradient(90deg,#dc2626 0%,#f59e0b 40%,#22c55e 60%,#16a34a 100%); border-radius:999px; position:relative; }
    .meter-ptr { width:18px; height:18px; background:#fff; border:3px solid #0f172a; border-radius:50%; position:absolute; top:-2px; transform:translateX(-50%); box-shadow:0 2px 8px rgba(0,0,0,.4); }
    .chart-wrap { position:relative; }
    canvas { width:100%!important; }
  `],
  template: `
<div style="background:#0d1829; min-height:100vh; padding:16px 20px;">
  <!-- Header -->
  <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:16px; flex-wrap:wrap; gap:10px;">
    <div>
      <span style="color:#fff; font-size:18px; font-weight:700;">Market Breadth Trend Dashboard</span>
      <div style="font-size:11px; color:#94a3b8; margin-top:2px;">Track stock return distribution, positive/negative breadth, sentiment, and trend strength.</div>
    </div>
    <div style="display:flex; gap:8px;">
      <button class="btn-blue" (click)="loadData()" [disabled]="loading">
        @if (loading) { <span class="spin"></span> Loading... } @else { Refresh Data }
      </button>
    </div>
  </div>

  <!-- ══ Market Trend Identification Engine ══════════════════════════════════
       First thing on the page, deliberately: every other number here describes the
       market, and this one says what to DO about it. -->
  @if (health?.success) {
    <div class="card" style="padding:0; margin-bottom:14px; overflow:hidden; border:1px solid #1e2a3b;">

      <!-- Status band. Colour carries the state, but never alone - the words say it too. -->
      <div style="display:flex; align-items:stretch; flex-wrap:wrap;"
           [style.background]="health.status_colour + '1a'"
           [style.borderLeft]="'5px solid ' + health.status_colour">

        <div style="flex:1 1 340px; padding:14px 18px;">
          <div style="font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.6px;">
            Market Status
          </div>
          <div style="font-size:24px; font-weight:800; line-height:1.2; margin-top:2px;"
               [style.color]="health.status_colour">
            {{ health.market_status }}
          </div>
          <div style="font-size:12px; color:#e2e8f0; margin-top:6px; font-weight:600;">
            {{ health.recommended_action }}
          </div>
          @if (health.strong_buy_blocked_by?.length) {
            <div style="font-size:10px; color:#facc15; margin-top:6px;">
              Score is 80+, but Strong Buy also needs: {{ health.strong_buy_blocked_by.join('; ') }}
            </div>
          }
        </div>

        <div style="flex:0 0 190px; padding:14px 18px; display:flex; flex-direction:column;
                    justify-content:center; align-items:center; border-left:1px solid #1e2a3b;">
          <div style="font-size:10px; color:#94a3b8; text-transform:uppercase; letter-spacing:.6px;">
            Health Score
          </div>
          <div style="font-size:42px; font-weight:800; line-height:1.05;"
               [style.color]="health.status_colour">
            {{ health.market_health_score | number:'1.0-1' }}
          </div>
          <div style="font-size:10px; color:#64748b;">out of 100</div>
          <div style="width:100%; height:6px; background:#0f172a; border-radius:3px; margin-top:8px;">
            <div style="height:6px; border-radius:3px;"
                 [style.width]="health.market_health_score + '%'"
                 [style.background]="health.status_colour"></div>
          </div>
        </div>
      </div>

      <!-- The readings the specification asks to display -->
      <div style="display:grid; grid-template-columns:repeat(5,1fr); gap:1px; background:#1e2a3b;">
        <div style="background:#0d1829; padding:10px 14px;">
          <div style="font-size:9.5px; color:#94a3b8;">% Above 20 DMA</div>
          <div style="font-size:19px; font-weight:800; color:#e2e8f0;">{{ health.pct_above_20dma | number:'1.1-1' }}%</div>
        </div>
        <div style="background:#0d1829; padding:10px 14px;">
          <div style="font-size:9.5px; color:#94a3b8;">% Above 50 DMA</div>
          <div style="font-size:19px; font-weight:800;"
               [style.color]="health.pct_above_50dma >= 70 ? '#22c55e' : '#e2e8f0'">
            {{ health.pct_above_50dma | number:'1.1-1' }}%
          </div>
        </div>
        <div style="background:#0d1829; padding:10px 14px;">
          <div style="font-size:9.5px; color:#94a3b8;">% Above 200 DMA</div>
          <div style="font-size:19px; font-weight:800;"
               [style.color]="health.pct_above_200dma >= 60 ? '#22c55e' : '#e2e8f0'">
            {{ health.pct_above_200dma | number:'1.1-1' }}%
          </div>
        </div>
        <div style="background:#0d1829; padding:10px 14px;"
             [title]="health.advancing + ' advancing vs ' + health.declining + ' declining'">
          <div style="font-size:9.5px; color:#94a3b8;">Advance / Decline</div>
          <div style="font-size:19px; font-weight:800;"
               [style.color]="(health.advance_decline_ratio || 0) > 1 ? '#22c55e' : '#ef4444'">
            {{ health.advance_decline_ratio == null ? 'n/a' : (health.advance_decline_ratio | number:'1.2-2') }}
          </div>
          <div style="font-size:9px; color:#64748b;">{{ health.advancing }} up &middot; {{ health.declining }} down</div>
        </div>
        <div style="background:#0d1829; padding:10px 14px;"
             [title]="health.new_highs + ' at a 52-week high vs ' + health.new_lows + ' at a 52-week low'">
          <div style="font-size:9.5px; color:#94a3b8;">New High / New Low</div>
          <div style="font-size:19px; font-weight:800;"
               [style.color]="(health.nh_nl_ratio || 0) >= 1 ? '#22c55e' : '#ef4444'">
            {{ health.nh_nl_ratio == null ? 'n/a' : (health.nh_nl_ratio | number:'1.2-2') }}
          </div>
          <div style="font-size:9px; color:#64748b;">{{ health.new_highs }} hi &middot; {{ health.new_lows }} lo</div>
        </div>

        <div style="background:#0d1829; padding:10px 14px;">
          <div style="font-size:9.5px; color:#94a3b8;">Volume Participation</div>
          <div style="font-size:19px; font-weight:800; color:#e2e8f0;">{{ health.volume_participation_pct | number:'1.1-1' }}%</div>
          <div style="font-size:9px; color:#64748b;">{{ health.stocks_above_avg_volume }} above 20-day avg</div>
        </div>
        <div style="background:#0d1829; padding:10px 14px;">
          <div style="font-size:9.5px; color:#94a3b8;">Composite RSI (14)</div>
          <div style="font-size:19px; font-weight:800;"
               [style.color]="health.composite_rsi_state === 'BULLISH' ? '#22c55e' : health.composite_rsi_state === 'BEARISH' ? '#ef4444' : '#facc15'">
            {{ health.composite_rsi == null ? 'n/a' : (health.composite_rsi | number:'1.1-1') }}
          </div>
          <div style="font-size:9px; color:#64748b;">{{ health.composite_rsi_state }}</div>
        </div>
        <div style="background:#0d1829; padding:10px 14px;" [title]="health.composite_note">
          <div style="font-size:9.5px; color:#94a3b8;">Composite Trend</div>
          <div style="font-size:19px; font-weight:800;"
               [style.color]="health.composite_trend === 'BULLISH' ? '#22c55e' : health.composite_trend === 'BEARISH' ? '#ef4444' : '#facc15'">
            {{ health.composite_trend }}
          </div>
          <div style="font-size:9px; color:#64748b;">close vs 50 vs 200 DMA</div>
        </div>
        <div style="background:#0d1829; padding:10px 14px;">
          <div style="font-size:9.5px; color:#94a3b8;">% Above 100 DMA</div>
          <div style="font-size:19px; font-weight:800; color:#e2e8f0;">{{ health.pct_above_100dma | number:'1.1-1' }}%</div>
        </div>
        <div style="background:#0d1829; padding:10px 14px;">
          <div style="font-size:9.5px; color:#94a3b8;">Universe</div>
          <div style="font-size:19px; font-weight:800; color:#e2e8f0;">{{ health.total_stocks | number }}</div>
          <div style="font-size:9px; color:#64748b;">{{ health.universe }}</div>
        </div>
      </div>

      <!-- Where the score came from. A single number nobody can take apart is a number
           nobody can argue with, which is the opposite of useful. -->
      <div style="padding:10px 16px; border-top:1px solid #1e2a3b;">
        <div style="font-size:9.5px; color:#94a3b8; text-transform:uppercase; letter-spacing:.5px; margin-bottom:7px;">
          Score breakdown
        </div>
        <div style="display:grid; grid-template-columns:repeat(auto-fit,minmax(190px,1fr)); gap:8px 16px;">
          @for (row of breakdownRows; track row.key) {
            <div>
              <div style="display:flex; justify-content:space-between; font-size:10px; margin-bottom:3px;">
                <span style="color:#cbd5e1;">{{ row.label }}</span>
                <span style="color:#94a3b8;">{{ row.earned | number:'1.0-1' }} / {{ row.max }}</span>
              </div>
              <div style="height:5px; background:#0f172a; border-radius:3px;">
                <div style="height:5px; border-radius:3px; background:#1d6adb;"
                     [style.width]="barPct(row) + '%'"></div>
              </div>
            </div>
          }
        </div>
        <div style="font-size:9.5px; color:#64748b; margin-top:9px; line-height:1.6;">
          As of {{ health.as_of }} &middot; {{ health.composite_note }}
          @if (health.universe_warning) {
            <div style="color:#facc15; margin-top:4px;">{{ health.universe_warning }}</div>
          }
        </div>
      </div>
    </div>
  } @else if (health && !health.success) {
    <div class="card" style="padding:14px 18px; margin-bottom:14px; border-left:4px solid #facc15;">
      <div style="font-size:13px; font-weight:700; color:#facc15;">Market Health Score unavailable</div>
      <div style="font-size:11px; color:#94a3b8; margin-top:4px;">{{ health.message }}</div>
    </div>
  }

  @if (loading && !data) {
    <div style="text-align:center; padding:60px; color:#94a3b8; font-size:14px;">
      <span class="spin" style="width:24px; height:24px; border-width:3px; margin-bottom:12px; display:block; margin:0 auto 12px;"></span>
      Loading market breadth data...
    </div>
  }

  @if (data) {
    <!-- KPI Cards (6 columns) -->
    <div style="display:grid; grid-template-columns:repeat(6,1fr); gap:8px; margin-bottom:14px;">
      <div class="card" style="padding:12px 14px;">
        <div style="font-size:10px; color:#94a3b8; margin-bottom:4px;">Total Stocks</div>
        <div style="font-size:26px; font-weight:800; color:#fff;">{{ data.total_stocks | number }}</div>
        <div style="font-size:9px; color:#64748b; margin-top:2px;">Universe tracked</div>
      </div>
      <div class="card" style="padding:12px 14px;">
        <div style="font-size:10px; color:#94a3b8; margin-bottom:4px;">Positive Stocks</div>
        <div class="pos" style="font-size:22px; font-weight:800;">{{ data.positive_stocks | number }}</div>
        <div style="font-size:9px; color:#64748b; margin-top:2px;">{{ positivePct | number:'1.1-1' }}% of universe</div>
      </div>
      <div class="card" style="padding:12px 14px;">
        <div style="font-size:10px; color:#94a3b8; margin-bottom:4px;">Negative Stocks</div>
        <div class="neg" style="font-size:22px; font-weight:800;">{{ data.negative_stocks | number }}</div>
        <div style="font-size:9px; color:#64748b; margin-top:2px;">{{ negativePct | number:'1.1-1' }}% of universe</div>
      </div>
      <div class="card" style="padding:12px 14px;">
        <div style="font-size:10px; color:#94a3b8; margin-bottom:4px;">Advance / Decline Ratio</div>
        <div [class]="data.ad_ratio >= 1 ? 'pos' : data.ad_ratio >= 0.8 ? 'neutral-c' : 'neg'" style="font-size:22px; font-weight:800;">{{ data.ad_ratio | number:'1.2-2' }}</div>
        <div style="font-size:9px; color:#64748b; margin-top:2px;">Above 1 is bullish</div>
      </div>
      <div class="card" style="padding:12px 14px;">
        <div style="font-size:10px; color:#94a3b8; margin-bottom:4px;">Breadth Momentum Score</div>
        <div [class]="data.breadth_score >= 60 ? 'pos' : data.breadth_score >= 40 ? 'neutral-c' : 'neg'" style="font-size:22px; font-weight:800;">{{ data.breadth_score }}</div>
        <div style="font-size:9px; color:#64748b; margin-top:2px;">0 bearish, 100 bullish</div>
      </div>
      <div class="card" style="padding:12px 14px;">
        <div style="font-size:10px; color:#94a3b8; margin-bottom:4px;">Market Sentiment</div>
        <div style="font-size:16px; font-weight:800;" [class]="sentimentClass">{{ data.sentiment }}</div>
        <div style="display:flex; align-items:center; gap:6px; margin-top:4px;">
          <span style="width:8px; height:8px; border-radius:50%; display:inline-block;"
            [style.background]="data.market_status==='OPEN' ? '#22c55e' : '#ef4444'"></span>
          <span style="font-size:10px; color:#94a3b8;">{{ data.market_status === 'OPEN' ? 'Market LIVE' : 'Market CLOSED' }}</span>
        </div>
      </div>
    </div>

    <!-- Row 1: Distribution Chart + Sentiment Meter -->
    <div style="display:grid; grid-template-columns:1.4fr 1fr; gap:14px; margin-bottom:14px;">
      <!-- Distribution Curve -->
      <div class="card" style="padding:14px;">
        <div class="section-title" style="margin-bottom:10px;">Return Distribution Curve</div>
        <div class="chart-wrap" style="height:280px;">
          <canvas #distributionCanvas></canvas>
        </div>
        <div style="margin-top:8px; padding:8px; background:#0f172a; border-radius:6px; font-size:10px; color:#64748b;">
          Distribution shows how your tracked stocks are spread across return ranges. Concentration at extremes indicates a polarized market.
        </div>
      </div>

      <!-- Sentiment Meter -->
      <div class="card" style="padding:14px;">
        <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:12px;">
          <span class="section-title">Market Sentiment Meter</span>
          <span [class]="sentimentBadge">{{ data.sentiment }}</span>
        </div>
        <div class="meter-track" style="margin:20px 0 14px;">
          <div class="meter-ptr" [style.left]="data.breadth_score + '%'"></div>
        </div>
        <div style="display:flex; justify-content:space-between; font-size:8px; color:#64748b; margin-bottom:16px; padding:0 2px;">
          <span>Extreme Bearish</span><span>Bearish</span><span>Neutral</span><span>Bullish</span><span>Extreme Bullish</span>
        </div>
        <div style="font-size:10px; color:#94a3b8; margin-bottom:12px;">
          Score is calculated using positive stock percentage of your uploaded universe.
        </div>
        <table style="width:100%; border-collapse:collapse;">
          <thead><tr>
            <th class="tbl-hdr">Zone</th><th class="tbl-hdr">Score</th>
          </tr></thead>
          <tbody>
            <tr class="tbl-row" [style.background]="data.breadth_score <= 20 ? '#1e1520' : ''"><td class="tbl-cell">Extreme Bearish</td><td class="tbl-cell">0 - 20</td></tr>
            <tr class="tbl-row" [style.background]="data.breadth_score > 20 && data.breadth_score <= 40 ? '#1e1520' : ''"><td class="tbl-cell">Bearish</td><td class="tbl-cell">21 - 40</td></tr>
            <tr class="tbl-row" [style.background]="data.breadth_score > 40 && data.breadth_score <= 60 ? '#1e1520' : ''"><td class="tbl-cell">Neutral</td><td class="tbl-cell">41 - 60</td></tr>
            <tr class="tbl-row" [style.background]="data.breadth_score > 60 && data.breadth_score <= 80 ? '#1e1520' : ''"><td class="tbl-cell">Bullish</td><td class="tbl-cell">61 - 80</td></tr>
            <tr class="tbl-row" [style.background]="data.breadth_score > 80 ? '#1e1520' : ''"><td class="tbl-cell">Extreme Bullish</td><td class="tbl-cell">81 - 100</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Row 2: Three charts -->
    <div style="display:grid; grid-template-columns:1fr 1fr 1fr; gap:14px; margin-bottom:14px;">
      <!-- Positive vs Negative Breadth -->
      <div class="card" style="padding:14px;">
        <div class="section-title" style="margin-bottom:10px;">Positive vs Negative Breadth</div>
        <div class="chart-wrap" style="height:240px;">
          <canvas #breadthCanvas></canvas>
        </div>
      </div>

      <!-- Rolling Breadth Trend -->
      <div class="card" style="padding:14px;">
        <div class="section-title" style="margin-bottom:10px;">Rolling Breadth Trend</div>
        <div class="chart-wrap" style="height:240px;">
          <canvas #rollingCanvas></canvas>
        </div>
      </div>

      <!-- Sector-wise Breadth -->
      <div class="card" style="padding:14px;">
        <div class="section-title" style="margin-bottom:10px;">Sector-wise Breadth</div>
        <div class="chart-wrap" style="height:240px;">
          <canvas #sectorCanvas></canvas>
        </div>
      </div>
    </div>

    <!-- Row 3: Return Range Table + Sector Breadth Table -->
    <div style="display:grid; grid-template-columns:1fr 1fr; gap:14px; margin-bottom:14px;">
      <!-- Return Range Data -->
      <div class="card" style="overflow:hidden;">
        <div style="background:#1a3a5c; padding:10px 12px;">
          <span class="section-title">Return Range Data</span>
        </div>
        <div style="overflow-x:auto;">
          <table style="width:100%; border-collapse:collapse;">
            <thead><tr>
              <th class="tbl-hdr">Return Range</th>
              <th class="tbl-hdr" style="text-align:right;">Positive Stock Count</th>
              <th class="tbl-hdr" style="text-align:right;">Negative Stock Count</th>
              <th class="tbl-hdr" style="text-align:right;">Positive %</th>
              <th class="tbl-hdr" style="text-align:right;">Negative %</th>
            </tr></thead>
            <tbody>
              @for (d of distributionCurveRows(); track d.label) {
                <tr class="tbl-row" [style.font-weight]="d.label === 'Total' ? '700' : ''"
                  [style.background]="d.label === 'Total' ? '#0f172a' : ''">
                  <td class="tbl-cell" style="font-weight:600;">{{ d.label }}</td>
                  <td class="tbl-cell-num pos">{{ d.positive_stock_count | number }}</td>
                  <td class="tbl-cell-num neg">{{ d.negative_stock_count | number }}</td>
                  <td class="tbl-cell-num" style="color:#60a5fa;">{{ d.positive_pct | number:'1.2-2' }}%</td>
                  <td class="tbl-cell-num" style="color:#f59e0b;">{{ d.negative_pct | number:'1.2-2' }}%</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>

      <!-- Sector Breadth Table -->
      <div class="card" style="overflow:hidden;">
        <div style="background:#1a3a5c; padding:10px 12px; display:flex; align-items:center; justify-content:space-between;">
          <span class="section-title">Sector Breadth Breakdown</span>
          <span style="font-size:10px; color:#94a3b8;">{{ (data.sector_breadth || []).length }} sectors</span>
        </div>
        <div style="overflow-x:auto; max-height:340px; overflow-y:auto;">
          <table style="width:100%; border-collapse:collapse;">
            <thead><tr>
              <th class="tbl-hdr">Sector</th>
              <th class="tbl-hdr" style="text-align:right;">Total</th>
              <th class="tbl-hdr" style="text-align:right;">Positive</th>
              <th class="tbl-hdr" style="text-align:right;">Negative</th>
              <th class="tbl-hdr" style="text-align:right;">Positive %</th>
              <th class="tbl-hdr" style="text-align:right;">Avg Return</th>
            </tr></thead>
            <tbody>
              @for (s of (data.sector_breadth || []); track s.sector) {
                <tr class="tbl-row">
                  <td class="tbl-cell" style="font-weight:600;">{{ s.sector }}</td>
                  <td class="tbl-cell-num">{{ s.total }}</td>
                  <td class="tbl-cell-num pos">{{ s.positive }}</td>
                  <td class="tbl-cell-num neg">{{ s.negative }}</td>
                  <td class="tbl-cell-num">
                    <span [class]="s.positive_pct >= 50 ? 'badge-green' : 'badge-red'">{{ s.positive_pct | number:'1.1-1' }}%</span>
                  </td>
                  <td class="tbl-cell-num" [class]="s.avg_return >= 0 ? 'pos' : 'neg'">{{ s.avg_return | number:'1.2-2' }}%</td>
                </tr>
              }
              @if (!(data.sector_breadth?.length)) {
                <tr><td colspan="6" style="text-align:center; padding:18px; color:#64748b; font-size:11px;">No sector data available.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Export Section -->
    <div class="card" style="padding:12px;">
      <div class="section-title" style="margin-bottom:10px;">Download / Export</div>
      <div style="display:grid; grid-template-columns:1fr 1fr 1fr; gap:7px;">
        <button class="btn-outline" style="padding:7px 10px; font-size:10.5px;" (click)="downloadCsv('/tracker/export/distribution','TradeRakshak_Distribution.csv')">Distribution Report</button>
        <button class="btn-outline" style="padding:7px 10px; font-size:10.5px;" (click)="downloadCsv('/tracker/export/all-stocks','TradeRakshak_All_Stocks.csv')">All Stocks</button>
        <button class="btn-outline" style="padding:7px 10px; font-size:10.5px;" (click)="downloadCsv('/tracker/export/eligible','TradeRakshak_Eligible.csv')">Eligible Stocks</button>
      </div>
    </div>
  }

  @if (!loading && !data) {
    <div class="card" style="padding:40px; text-align:center;">
      <div style="color:#64748b; font-size:13px; margin-bottom:12px;">No data available. Upload stocks in the Stock Upload section and run the ranking engine to see market breadth data.</div>
      <button class="btn-blue" (click)="loadData()">Try Loading</button>
    </div>
  }
</div>
  `
})
export class MarketBreadthComponent implements OnInit, OnDestroy {
  @ViewChild('distributionCanvas') distributionCanvas!: ElementRef<HTMLCanvasElement>;
  @ViewChild('breadthCanvas') breadthCanvas!: ElementRef<HTMLCanvasElement>;
  @ViewChild('rollingCanvas') rollingCanvas!: ElementRef<HTMLCanvasElement>;
  @ViewChild('sectorCanvas') sectorCanvas!: ElementRef<HTMLCanvasElement>;

  data: any = null;
  loading = false;
  private charts: Chart[] = [];
  private timer: any;

  private moduleSub?: { unsubscribe(): void };
  constructor(private api: ApiService, private http: HttpClient, private realtime: RealtimeService) {}

  ngOnInit() {
    this.loadData();
    this.timer = setInterval(() => this.loadData(), 5 * 60 * 1000);
    this.moduleSub = this.realtime.on('dashboard').subscribe(() => this.loadData());
  }

  ngOnDestroy() {
    if (this.timer) clearInterval(this.timer);
    this.moduleSub?.unsubscribe();
    this.destroyCharts();
  }

  /** Market Trend Identification Engine - the regime banner at the top of this page. */
  health: any = null;
  healthLoading = false;

  loadHealth() {
    this.healthLoading = true;
    this.api.get<any>('/tracker/market-health').subscribe({
      next: r => { this.health = r.data || null; this.healthLoading = false; },
      error: () => { this.health = null; this.healthLoading = false; }
    });
  }

  /** Width of a score-breakdown bar, as a percentage of that component's own maximum. */
  barPct(row: any): number {
    if (!row || !row.max) return 0;
    return Math.max(0, Math.min(100, (Number(row.earned) || 0) / Number(row.max) * 100));
  }

  /** The seven weighted components, in the order the specification lists them. */
  get breakdownRows(): any[] {
    const b = this.health?.score_breakdown;
    if (!b) return [];
    const labels: Record<string, string> = {
      above_20dma: 'Above 20 DMA',
      above_50dma: 'Above 50 DMA',
      above_200dma: 'Above 200 DMA',
      advance_decline: 'Advance / Decline',
      volume_participation: 'Volume Participation',
      composite_trend: 'Composite Trend',
      composite_rsi: 'Composite RSI'
    };
    return Object.keys(labels)
      .filter(k => b[k])
      .map(k => ({ key: k, label: labels[k], ...b[k] }));
  }

  loadData() {
    this.loading = true;
    this.loadHealth();
    this.api.get<any>('/tracker/breadth').subscribe({
      next: r => {
        this.data = r.data || {};
        this.loading = false;
        setTimeout(() => this.buildCharts(), 50);
      },
      error: () => {
        this.loading = false;
      }
    });
  }

  get positivePct(): number {
    if (!this.data || !this.data.total_stocks) return 0;
    return Math.round(this.data.positive_stocks / this.data.total_stocks * 1000) / 10;
  }

  get negativePct(): number {
    if (!this.data || !this.data.total_stocks) return 0;
    return Math.round(this.data.negative_stocks / this.data.total_stocks * 1000) / 10;
  }

  get sentimentClass(): string {
    if (!this.data) return '';
    const s = this.data.sentiment;
    if (s === 'Extreme Bullish' || s === 'Bullish') return 'pos';
    if (s === 'Extreme Bearish' || s === 'Bearish') return 'neg';
    return 'neutral-c';
  }

  get sentimentBadge(): string {
    if (!this.data) return 'badge-amber';
    const s = this.data.sentiment;
    if (s === 'Extreme Bullish' || s === 'Bullish') return 'badge-green';
    if (s === 'Extreme Bearish' || s === 'Bearish') return 'badge-red';
    return 'badge-amber';
  }

  isNegativeRange(label: string): boolean {
    return label.includes('Below') || label.includes('-');
  }

  distributionCurveRows(): any[] {
    const rows = this.data?.return_distribution_curve;
    if (Array.isArray(rows) && rows.length) return rows;
    const labels = ['0% to 5%', '5% to 10%', '10% to 15%', '15% to 30%', 'Above 30%', 'Total'];
    return labels.map(label => ({ label, positive_stock_count: 0, negative_stock_count: 0, positive_pct: 0, negative_pct: 0 }));
  }

  distributionCurveChartRows(): any[] {
    return this.distributionCurveRows().filter((d: any) => d.label !== 'Total');
  }

  private destroyCharts() {
    this.charts.forEach(c => c.destroy());
    this.charts = [];
  }

  private buildCharts() {
    this.destroyCharts();
    this.buildDistributionChart();
    this.buildBreadthChart();
    this.buildRollingChart();
    this.buildSectorChart();
  }

  private buildDistributionChart() {
    if (!this.distributionCanvas?.nativeElement) return;
    const dist = this.distributionCurveChartRows();
    const labels = dist.map((d: any) => d.label);
    const positiveCounts = dist.map((d: any) => Number(d.positive_stock_count || 0));
    const negativeCounts = dist.map((d: any) => Number(d.negative_stock_count || 0));

    const chart = new Chart(this.distributionCanvas.nativeElement, {
      type: 'line',
      data: {
        labels,
        datasets: [
          {
            label: 'Positive Stock Count',
            data: positiveCounts,
            tension: 0.25,
            fill: false,
            borderWidth: 4,
            borderColor: '#155f7f',
            backgroundColor: '#155f7f',
            pointBackgroundColor: '#155f7f',
            pointBorderColor: '#155f7f',
            pointRadius: 5,
            pointHoverRadius: 7
          },
          {
            label: 'Negative Stock Count',
            data: negativeCounts,
            tension: 0.25,
            fill: false,
            borderWidth: 4,
            borderColor: '#e86b2f',
            backgroundColor: '#e86b2f',
            pointBackgroundColor: '#e86b2f',
            pointBorderColor: '#e86b2f',
            pointRadius: 5,
            pointHoverRadius: 7
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: true, position: 'bottom', labels: { color: '#cbd5e1', boxWidth: 28, usePointStyle: true } },
          tooltip: {
            callbacks: {
              label: (ctx: any) => `${ctx.parsed.y} stocks`
            }
          }
        },
        scales: {
          y: {
            beginAtZero: true,
            title: { display: true, text: 'Stock Count', color: '#94a3b8', font: { size: 10 } },
            ticks: { color: '#94a3b8', font: { size: 9 } },
            grid: { color: '#d1d5db33' }
          },
          x: {
            title: { display: true, text: 'Return Range', color: '#94a3b8', font: { size: 10 } },
            ticks: { color: '#94a3b8', font: { size: 10 }, maxRotation: 0 },
            grid: { display: false }
          }
        }
      }
    });
    this.charts.push(chart);
  }

  private buildBreadthChart() {
    if (!this.breadthCanvas?.nativeElement || !this.data?.breadth_history) return;
    const history = this.data.breadth_history;
    if (history.length < 2) return;

    const labels = history.map((h: any) => h.date?.substring(5) || '');
    const posData = history.map((h: any) => h.positive || 0);
    const negData = history.map((h: any) => -(h.negative || 0));

    const chart = new Chart(this.breadthCanvas.nativeElement, {
      type: 'line',
      data: {
        labels,
        datasets: [
          {
            label: 'Positive Stocks',
            data: posData,
            tension: 0.35,
            borderWidth: 2,
            borderColor: '#22c55e',
            backgroundColor: 'rgba(34,197,94,0.1)',
            fill: true,
            pointRadius: 2
          },
          {
            label: 'Negative Stocks',
            data: negData,
            tension: 0.35,
            borderWidth: 2,
            borderColor: '#ef4444',
            backgroundColor: 'rgba(239,68,68,0.1)',
            fill: true,
            pointRadius: 2
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: true, labels: { color: '#94a3b8', font: { size: 9 } } }
        },
        scales: {
          y: {
            title: { display: true, text: 'Count from 0', color: '#94a3b8', font: { size: 9 } },
            ticks: { color: '#94a3b8', font: { size: 8 } },
            grid: { color: '#1e2a3b' }
          },
          x: {
            ticks: { color: '#94a3b8', font: { size: 8 }, maxRotation: 45 },
            grid: { color: '#1e2a3b' }
          }
        }
      }
    });
    this.charts.push(chart);
  }

  private buildRollingChart() {
    if (!this.rollingCanvas?.nativeElement || !this.data?.breadth_history) return;
    const history = this.data.breadth_history;
    if (history.length < 2) return;

    const labels = history.map((h: any) => h.date?.substring(5) || '');
    const scores = history.map((h: any) => h.breadth_score || 0);

    const chart = new Chart(this.rollingCanvas.nativeElement, {
      type: 'line',
      data: {
        labels,
        datasets: [{
          label: 'Breadth Score',
          data: scores,
          tension: 0.35,
          fill: true,
          borderWidth: 2,
          borderColor: '#60a5fa',
          backgroundColor: 'rgba(96,165,250,0.12)',
          pointRadius: 2,
          pointBackgroundColor: '#60a5fa'
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false }
        },
        scales: {
          y: {
            min: 0,
            max: 100,
            title: { display: true, text: 'Score', color: '#94a3b8', font: { size: 9 } },
            ticks: { color: '#94a3b8', font: { size: 8 } },
            grid: { color: '#1e2a3b' }
          },
          x: {
            ticks: { color: '#94a3b8', font: { size: 8 }, maxRotation: 45 },
            grid: { color: '#1e2a3b' }
          }
        }
      }
    });
    this.charts.push(chart);
  }

  private buildSectorChart() {
    if (!this.sectorCanvas?.nativeElement || !this.data?.sector_breadth?.length) return;
    const sectors = this.data.sector_breadth.slice(0, 12);
    const labels = sectors.map((s: any) => s.sector.length > 12 ? s.sector.substring(0, 12) + '..' : s.sector);
    const posPcts = sectors.map((s: any) => s.positive_pct || 0);
    const colors = sectors.map((s: any) => (s.positive_pct || 0) >= 50 ? 'rgba(34,197,94,0.7)' : 'rgba(239,68,68,0.7)');

    const chart = new Chart(this.sectorCanvas.nativeElement, {
      type: 'bar',
      data: {
        labels,
        datasets: [{
          label: 'Positive %',
          data: posPcts,
          backgroundColor: colors,
          borderColor: colors.map((c: string) => c.replace('0.7', '1')),
          borderWidth: 1,
          borderRadius: 3
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false }
        },
        scales: {
          y: {
            beginAtZero: true,
            max: 100,
            title: { display: true, text: 'Positive Stock %', color: '#94a3b8', font: { size: 9 } },
            ticks: { color: '#94a3b8', font: { size: 8 } },
            grid: { color: '#1e2a3b' }
          },
          x: {
            ticks: { color: '#94a3b8', font: { size: 8 }, maxRotation: 45 },
            grid: { display: false }
          }
        }
      }
    });
    this.charts.push(chart);
  }

  downloadCsv(endpoint: string, filename: string) {
    this.http.get(`${environment.apiUrl}${endpoint}`, {
      responseType: 'blob', observe: 'response'
    }).subscribe({
      next: (response) => {
        const contentDisp = response.headers.get('Content-Disposition');
        if (contentDisp) {
          const match = contentDisp.match(/filename="?([^"]+)"?/);
          if (match) filename = match[1];
        }
        const blob = response.body!;
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = filename;
        document.body.appendChild(a); a.click();
        window.URL.revokeObjectURL(url); document.body.removeChild(a);
      },
      error: () => alert('Download failed. Please try again.')
    });
  }
}
