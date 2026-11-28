import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/services/api.service';

/**
 * One condition row: left operand, comparator, right operand, on a bar width.
 *
 * <p>Structured rather than free text. The previous builder took the whole condition as typed
 * strings and re-parsed them, which meant a typo in an indicator name produced a rule that
 * saved, validated as prose, and matched nothing. Here the left and right sides are picked from
 * the server's own field catalogue, so a condition cannot name something the engine does not
 * have.
 */
interface Cond {
  id: number;
  timeframe: string;
  leftKey: string;
  leftParams: Record<string, number>;
  op: string;
  /** 'value' compares against a number, 'field' against another indicator. */
  rightKind: 'value' | 'field';
  rightValue: number | null;
  /** BETWEEN's upper bound; ignored by every other operator. */
  rightValue2: number | null;
  rightKey: string;
  rightParams: Record<string, number>;
  /**
   * How many bars back each side is read, on the condition's own timeframe.
   *
   * <p>0 is the bar being evaluated. 1 on a daily condition is the previous session; 1 on a
   * weekly one is last week. This is what lets a crossing be written the long way - "%K above
   * %D now, and %K at or below %D last session" - which is how a screen translated from
   * somewhere else usually reads. The CROSSES ABOVE operator says the same thing in one row.
   */
  leftOffset: number;
  rightOffset: number;
}

/**
 * A group of conditions and how they combine.
 *
 * <p>Groups are the reason this screen exists. A flat list folded left to right reads
 * "a AND b OR c" as "(a AND b) OR c" because of the order the rows happen to be in, so the same
 * conditions rearranged quietly become a different strategy. A group says "all of these" or "any
 * one of these" explicitly, and groups are then joined - which is what the rule tree has always
 * been able to express and the builder could not.
 */
interface Group {
  id: number;
  mode: 'ALL' | 'ANY';
  conditions: Cond[];
  /** Connector to the next group. Ignored on the last one. */
  joinNext: 'AND' | 'OR';
}

interface FieldDef {
  key: string;
  label: string;
  category: string;
  unit: string;
  availability: string;
  params: Array<{ name: string; type: string; default: number; min: number; max: number }>;
}

@Component({
  selector: 'app-strategy-canvas',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host { display:block; }
    .wrap { background:#0a1422; min-height:100vh; padding:14px 18px 26px; color:#e2e8f0;
            font-family:'Segoe UI',system-ui,-apple-system,sans-serif; }

    /* ── header ─────────────────────────────────────────────────────────── */
    .hdr { display:flex; align-items:center; gap:14px; flex-wrap:wrap; margin-bottom:14px; }
    .hdr-mark { width:42px; height:42px; border-radius:11px; display:flex; align-items:center;
                justify-content:center; font-size:21px;
                background:linear-gradient(135deg,#1d4ed8,#7c3aed); flex:0 0 auto; }
    .hdr-title { font-size:23px; font-weight:800; color:#fff; line-height:1.15; }
    .hdr-sub { font-size:11.5px; color:#8fa3bd; margin-top:2px; }
    .hdr-right { margin-left:auto; display:flex; align-items:center; gap:9px; flex-wrap:wrap; }

    /* The mode switch is a segmented control, not two buttons: the two are mutually exclusive
       and only one can be in force, which a pair of buttons does not say. */
    .seg { display:flex; background:#132033; border:1px solid #223449; border-radius:9px;
           padding:3px; gap:3px; }
    .seg button { background:transparent; border:none; color:#8fa3bd; font-size:12px;
                  font-weight:700; padding:7px 15px; border-radius:7px; cursor:pointer;
                  display:flex; align-items:center; gap:7px; white-space:nowrap; }
    .seg button.on { background:#1d4ed8; color:#fff; }
    .seg button.on.real { background:#b91c1c; }
    .seg button:hover:not(.on) { color:#cbd5e1; }

    .btn { border-radius:8px; font-size:12px; font-weight:700; padding:8px 15px; cursor:pointer;
           border:1px solid transparent; white-space:nowrap; }
    .btn-ghost { background:#132033; border-color:#223449; color:#cbd5e1; }
    .btn-ghost:hover { background:#1b2c44; }
    .btn-go { background:#16a34a; color:#fff; }
    .btn-go:hover { background:#15803d; }
    .btn[disabled] { opacity:.5; cursor:not-allowed; }

    .menu-host { position:relative; }
    .menu { position:absolute; right:0; top:calc(100% + 5px); z-index:40; min-width:216px;
            background:#132033; border:1px solid #2a3f58; border-radius:9px; padding:5px;
            box-shadow:0 14px 34px rgba(0,0,0,.55); }
    .menu button { display:block; width:100%; text-align:left; background:transparent;
                   border:none; color:#cbd5e1; font-size:11.5px; padding:8px 10px;
                   border-radius:6px; cursor:pointer; }
    .menu button:hover { background:#1d3050; color:#fff; }
    .menu .mi-hint { display:block; font-size:9.5px; color:#64748b; margin-top:2px; }

    /* ── three-column body ──────────────────────────────────────────────── */
    .cols { display:grid; grid-template-columns:234px minmax(0,1fr) 340px; gap:13px;
            align-items:start; }
    .panel { background:#0f1b2d; border:1px solid #1d2c42; border-radius:12px; }
    .panel-hd { display:flex; align-items:center; gap:9px; padding:12px 14px;
                border-bottom:1px solid #1d2c42; }
    .panel-ttl { font-size:14px; font-weight:800; color:#fff; }
    .panel-bd { padding:12px 13px; }

    /* ── indicator library ──────────────────────────────────────────────── */
    .search { width:100%; background:#0b1626; border:1px solid #243750; border-radius:8px;
              color:#e2e8f0; font-size:11.5px; padding:8px 10px 8px 28px; }
    .search-host { position:relative; margin-bottom:9px; }
    .search-ico { position:absolute; left:9px; top:50%; transform:translateY(-50%);
                  font-size:11px; color:#64748b; pointer-events:none; }
    .cat { border-radius:8px; margin-bottom:2px; }
    .cat-hd { display:flex; align-items:center; gap:8px; padding:7px 9px; cursor:pointer;
              border-radius:8px; font-size:12px; font-weight:700; color:#cbd5e1; }
    .cat-hd:hover { background:#16263d; }
    .cat-hd.open { background:#1a3a5c; color:#fff; }
    .cat-ico { font-size:13px; width:16px; text-align:center; }
    .cat-chev { margin-left:auto; font-size:9px; color:#64748b; }
    .cat-items { padding:2px 0 5px 0; }
    .lib-item { display:block; width:100%; text-align:left; background:transparent; border:none;
                color:#9eb8dd; font-size:11.5px; padding:6px 9px 6px 33px; cursor:pointer;
                border-radius:7px; }
    .lib-item:hover { background:#1d4ed8; color:#fff; }
    .lib-item.on { background:#1d4ed8; color:#fff; font-weight:700; }
    .lib-item.off { color:#5a6b82; cursor:not-allowed; }
    .lib-item.off:hover { background:transparent; color:#5a6b82; }

    /* ── condition builder ──────────────────────────────────────────────── */
    .grp { border-radius:11px; padding:11px 12px; margin-bottom:11px; }
    .grp-1 { background:#122744; border:1px solid #1e3a66; }
    .grp-2 { background:#1d1a3f; border:1px solid #352f6b; }
    .grp-hd { display:flex; align-items:center; gap:9px; flex-wrap:wrap; margin-bottom:9px; }
    .grp-name { font-size:15px; font-weight:800; color:#fff; }
    .grp-mode { background:transparent; border:none; color:#9eb8dd; font-size:11.5px;
                cursor:pointer; padding:2px 4px; border-radius:5px; }
    .grp-mode:hover { color:#fff; background:rgba(255,255,255,.07); }
    .grp-del { margin-left:auto; background:transparent; border:none; color:#7f8ea6;
               cursor:pointer; font-size:12px; padding:3px 6px; border-radius:6px; }
    .grp-del:hover { color:#fca5a5; background:rgba(239,68,68,.12); }

    .row { display:flex; align-items:center; gap:7px; margin-bottom:7px; flex-wrap:wrap; }
    .row-no { width:23px; height:23px; flex:0 0 auto; border-radius:50%; background:#1d4ed8;
              color:#fff; font-size:11px; font-weight:800; display:flex; align-items:center;
              justify-content:center; }
    .grp-2 .row-no { background:#6d28d9; }
    .sel, .num { background:#0e1d31; border:1px solid #2a3f58; border-radius:8px; color:#e2e8f0;
                 font-size:11.5px; padding:7px 9px; min-height:34px; }
    .sel { cursor:pointer; }
    .sel:focus, .num:focus { outline:none; border-color:#3b82f6; }
    .sel-tf { flex:0 0 92px; }
    .sel-ind { flex:1 1 150px; min-width:0; }
    .sel-op { flex:0 0 136px; }
    .operand { flex:1 1 170px; display:flex; gap:5px; min-width:0; }
    .operand .num, .operand .sel { flex:1 1 auto; min-width:0; width:100%; }
    .kind { flex:0 0 34px; background:#0e1d31; border:1px solid #2a3f58; border-radius:8px;
            color:#8fa3bd; font-size:10px; font-weight:800; cursor:pointer; min-height:34px; }
    .kind:hover { color:#fff; border-color:#3b82f6; }
    .row-del { flex:0 0 auto; background:transparent; border:none; color:#7f8ea6; cursor:pointer;
               font-size:13px; padding:5px 7px; border-radius:6px; }
    .row-del:hover { color:#fca5a5; background:rgba(239,68,68,.12); }

    .dashed { width:100%; background:transparent; border:1px dashed #33507c; border-radius:9px;
              color:#7fa9e8; font-size:11.5px; font-weight:700; padding:8px; cursor:pointer; }
    .dashed:hover { background:rgba(59,130,246,.09); color:#bfdbfe; }

    .joiner { display:flex; justify-content:center; margin:0 0 11px; }
    .joiner select { background:#0e1d31; border:1px solid #2a3f58; border-radius:999px;
                     color:#e2e8f0; font-size:11.5px; font-weight:800; padding:6px 17px;
                     cursor:pointer; }

    .foot { display:flex; gap:9px; flex-wrap:wrap; margin-top:3px; }
    .foot .dashed { width:auto; flex:1 1 auto; padding:9px 13px; }

    /* ── preview ────────────────────────────────────────────────────────── */
    .kv { display:flex; align-items:center; gap:9px; margin-bottom:9px; font-size:12px; }
    .kv .k { color:#8fa3bd; flex:0 0 112px; }
    .kv .v { color:#fff; font-weight:700; }
    .name-in { background:transparent; border:none; border-bottom:1px dashed #33507c;
               color:#fff; font-weight:700; font-size:12px; padding:2px 1px; min-width:0;
               flex:1 1 auto; }
    .name-in:focus { outline:none; border-bottom-color:#3b82f6; }
    .pill { font-size:10.5px; font-weight:800; border-radius:999px; padding:3px 11px; }
    .pill-paper { background:#0e3a5e; color:#7dd3fc; }
    .pill-real { background:#4c1118; color:#fca5a5; }

    .logic { background:#0b1626; border:1px solid #223449; border-radius:9px; padding:10px 11px;
             font-size:11px; line-height:1.75; }
    .logic-g { display:flex; gap:8px; margin-bottom:3px; }
    .logic-g .lg-k { color:#7fa9e8; font-weight:800; flex:0 0 66px; }
    .logic-g .lg-v { color:#dbeafe; overflow-wrap:anywhere; }
    .logic-join { text-align:center; color:#a78bfa; font-weight:800; font-size:10.5px;
                  margin:3px 0; }

    .sub-ttl { font-size:12.5px; font-weight:800; color:#fff; margin:13px 0 8px; }
    .issue { font-size:10.5px; line-height:1.6; border-radius:8px; padding:7px 10px;
             margin-top:9px; overflow-wrap:anywhere; }
    .issue-err { background:#3b1116; border:1px solid #7f1d1d; color:#fca5a5; }
    .issue-warn { background:#3a2608; border:1px solid #d97706; color:#fde68a; }
    .issue-ok { background:#0b3b37; border:1px solid #16d6a4; color:#86efac; }

    /* ── bottom tabs ────────────────────────────────────────────────────── */
    .tabs { display:flex; gap:3px; flex-wrap:wrap; margin:14px 0 0; }
    .tabs button { background:#0f1b2d; border:1px solid #1d2c42; border-bottom:none;
                   border-radius:9px 9px 0 0; color:#8fa3bd; font-size:12px; font-weight:700;
                   padding:9px 15px; cursor:pointer; display:flex; align-items:center; gap:7px; }
    .tabs button.on { background:#16263d; color:#fff; border-color:#2a3f58; }
    .tab-bd { background:#0f1b2d; border:1px solid #1d2c42; border-radius:0 12px 12px 12px;
              padding:13px 15px; }

    .perf { display:flex; align-items:center; justify-content:space-between; gap:12px;
            font-size:12.5px; margin-bottom:9px; }
    .perf .pl { color:#cbd5e1; }
    .perf .pv { font-weight:800; font-size:15px; }

    table { width:100%; border-collapse:collapse; }
    th { font-size:10px; text-transform:uppercase; letter-spacing:.5px; color:#8fa3bd;
         text-align:left; padding:6px 8px; border-bottom:1px solid #223449; font-weight:700; }
    td { font-size:11.5px; color:#cbd5e1; padding:6px 8px; border-bottom:1px solid #16263d; }
    .num-col { text-align:right; }
    .scroll-x { width:100%; overflow-x:auto; }

    .empty { font-size:11.5px; color:#8fa3bd; line-height:1.7; padding:10px 2px; }
    .spin { width:12px; height:12px; border:2px solid rgba(255,255,255,.25); border-top-color:#fff;
            border-radius:50%; animation:sp .7s linear infinite; display:inline-block; }
    @keyframes sp { to { transform:rotate(360deg); } }

    /* ── responsive. The canvas is a three-column tool; below 1150px the library and the
       preview stack above and below the builder rather than being squeezed to unusable
       widths, and the condition row wraps onto two lines instead of scrolling. ────────── */
    @media (max-width:1150px) {
      .cols { grid-template-columns:minmax(0,1fr); }
      .panel-lib .cat-items { columns:2; }
    }
    @media (max-width:720px) {
      .wrap { padding:12px 12px 22px; }
      .hdr-right { width:100%; margin-left:0; }
      .sel-tf, .sel-op { flex:1 1 130px; }
      .sel-ind { flex:1 1 100%; }
      .operand { flex:1 1 100%; }
      .panel-lib .cat-items { columns:1; }
      .kv .k { flex:0 0 96px; }
      /* Touch targets. 34px controls are a mouse target, not a thumb. */
      .sel, .num, .kind { min-height:40px; font-size:12.5px; }
      .btn { padding:10px 15px; }
    }
  `],
  template: `
<div class="wrap">

  <!-- ══ header ══════════════════════════════════════════════════════════ -->
  <div class="hdr">
    <div class="hdr-mark">&#128200;</div>
    <div>
      <div class="hdr-title">Strategy Builder</div>
      <div class="hdr-sub">Create your own trading strategy using indicators and conditions</div>
    </div>

    <div class="hdr-right">
      <!-- Paper is first and is the default, which is also what the engine ships with.
           Switching to Real here only labels the strategy: it does not arm anything, and the
           preview says so rather than letting the word "Real" imply more than it does. -->
      <div class="seg">
        <button [class.on]="mode === 'PAPER'" (click)="mode = 'PAPER'"
                title="Simulated execution. Nothing reaches a broker.">
          <span>&#128187;</span> Paper Trading
        </button>
        <button class="real" [class.on]="mode === 'REAL'" (click)="setRealMode()"
                title="Marks this strategy for real execution. Real orders still require account authorization and a connected broker.">
          <span>&#9889;</span> Real Trading
        </button>
      </div>

      <div class="menu-host">
        <button class="btn btn-ghost" (click)="saveMenu = !saveMenu">Save &#9662;</button>
        @if (saveMenu) {
          <div class="menu">
            <button (click)="save(false)">
              Save strategy
              <span class="mi-hint">{{ strategyId ? 'Updates the saved copy and keeps a version.' : 'Creates it as a draft.' }}</span>
            </button>
            @if (strategyId) {
              <button (click)="save(true)">
                Save as a new strategy
                <span class="mi-hint">Leaves the existing one untouched.</span>
              </button>
            }
            <button (click)="openClassic()">
              Open the text builder
              <span class="mi-hint">Paste Chartink, TradingView or plain English instead.</span>
            </button>
          </div>
        }
      </div>

      <button class="btn btn-go" (click)="build()" [disabled]="building || !conditionCount">
        @if (building) { <span class="spin"></span> Building&hellip; }
        @else { Build Strategy &#9654; }
      </button>
    </div>
  </div>

  <!-- ══ body ════════════════════════════════════════════════════════════ -->
  <div class="cols">

    <!-- ── indicator library ───────────────────────────────────────────── -->
    <div class="panel panel-lib">
      <div class="panel-hd"><span class="panel-ttl">Indicator Library</span></div>
      <div class="panel-bd">
        <div class="search-host">
          <span class="search-ico">&#128269;</span>
          <input class="search" [(ngModel)]="libSearch" placeholder="Search indicators..."
                 title="Matches the name and the label, so 'stoch' finds Stochastic %K.">
        </div>

        @if (fieldsLoading) {
          <div class="empty"><span class="spin"></span> Reading the field catalogue&hellip;</div>
        } @else if (!fields.length) {
          <div class="empty">
            Could not read the field catalogue.
            <button class="dashed" style="margin-top:7px;" (click)="loadFields()">Try again</button>
          </div>
        }

        @for (cat of visibleCategories(); track cat.name) {
          <div class="cat">
            <div class="cat-hd" [class.open]="isOpen(cat.name)" (click)="toggleCat(cat.name)">
              <span class="cat-ico">{{ cat.icon }}</span>
              <span>{{ cat.name }}</span>
              <span class="cat-chev">{{ isOpen(cat.name) ? '&#9650;' : '&#9660;' }}</span>
            </div>
            @if (isOpen(cat.name)) {
              <div class="cat-items">
                @for (f of cat.items; track f.key) {
                  <!-- Unavailable fields are listed and greyed rather than hidden. A financial
                       metric missing from the list looks like the engine cannot do it; greyed
                       with a reason says the data has not been loaded yet. -->
                  <button class="lib-item"
                          [class.off]="f.availability !== 'AVAILABLE'"
                          [disabled]="f.availability !== 'AVAILABLE'"
                          (click)="useField(f)"
                          [title]="f.availability === 'AVAILABLE'
                                   ? ('Add a condition on ' + f.label + (f.unit ? ' (' + f.unit + ')' : ''))
                                   : (f.label + ' has no data stored yet, so a rule using it would match nothing.')">
                    {{ f.label }}
                  </button>
                }
              </div>
            }
          </div>
        }
      </div>
    </div>

    <!-- ── condition builder ───────────────────────────────────────────── -->
    <div class="panel">
      <div class="panel-hd">
        <span class="panel-ttl">Condition Builder</span>
        <button class="btn btn-ghost" style="margin-left:auto; padding:5px 11px; font-size:11px;"
                (click)="clearAll()" [disabled]="!conditionCount"
                title="Remove every condition and start again.">&#128465; Clear All</button>
      </div>
      <div class="panel-bd">

        @for (g of groups; track g.id; let gi = $index) {
          <div class="grp" [class.grp-1]="gi % 2 === 0" [class.grp-2]="gi % 2 === 1">
            <div class="grp-hd">
              <span class="grp-name">Group {{ gi + 1 }}</span>
              <!-- The mode is the group's meaning, so it sits in the heading where it is read
                   with the group name rather than in a settings panel somewhere else. -->
              <select class="grp-mode" [(ngModel)]="g.mode"
                      title="ALL: every condition in this group must hold. ANY: at least one must.">
                <option value="ALL">(All conditions must be true)</option>
                <option value="ANY">(Any one condition must be true)</option>
              </select>
              @if (groups.length > 1) {
                <button class="grp-del" (click)="removeGroup(gi)"
                        title="Remove this group and its conditions">&#128465;</button>
              }
            </div>

            @for (c of g.conditions; track c.id; let ci = $index) {
              <div class="row">
                <span class="row-no">{{ ci + 1 }}</span>

                <select class="sel sel-tf" [(ngModel)]="c.timeframe"
                        title="The bar width this condition is measured on.">
                  @for (tf of timeframes; track tf.key) {
                    <option [value]="tf.key" [disabled]="!tf.available">
                      {{ tf.label }}{{ tf.available ? '' : ' (no data)' }}
                    </option>
                  }
                </select>

                <select class="sel sel-ind" [(ngModel)]="c.leftKey"
                        (ngModelChange)="onFieldChanged(c, 'left')"
                        [title]="describeField(c.leftKey)">
                  @for (f of selectableFields; track f.key) {
                    <option [value]="f.key" [disabled]="f.availability !== 'AVAILABLE'">
                      {{ labelWithParams(f.key, c.leftParams) }}
                    </option>
                  }
                </select>

                <select class="sel sel-op" [(ngModel)]="c.op" [title]="operatorHint(c.op)">
                  @for (o of operators; track o.key) {
                    <option [value]="o.key">{{ o.label }}</option>
                  }
                </select>

                <div class="operand">
                  <!-- One control, two meanings: compare against a number, or against another
                       indicator. The toggle is next to it rather than a third dropdown, because
                       which of the two it is changes what the box should even accept. -->
                  <button class="kind" (click)="toggleRightKind(c)"
                          [title]="c.rightKind === 'value'
                                   ? 'Comparing against a number. Click to compare against an indicator instead.'
                                   : 'Comparing against an indicator. Click to compare against a number instead.'">
                    {{ c.rightKind === 'value' ? '123' : 'f(x)' }}
                  </button>

                  @if (c.rightKind === 'value') {
                    <input class="num" type="number" [(ngModel)]="c.rightValue"
                           [placeholder]="unitHint(c.leftKey)"
                           [title]="'A number on the same scale as ' + describeField(c.leftKey)">
                    @if (c.op === 'BETWEEN') {
                      <input class="num" type="number" [(ngModel)]="c.rightValue2"
                             placeholder="upper" title="The upper bound. BETWEEN is inclusive.">
                    }
                  } @else {
                    <select class="sel" [(ngModel)]="c.rightKey"
                            (ngModelChange)="onFieldChanged(c, 'right')"
                            [title]="describeField(c.rightKey)">
                      @for (f of selectableFields; track f.key) {
                        <option [value]="f.key" [disabled]="f.availability !== 'AVAILABLE'">
                          {{ labelWithParams(f.key, c.rightParams) }}
                        </option>
                      }
                    </select>
                  }
                </div>

                <button class="row-del" (click)="removeCondition(g, ci)"
                        title="Remove this condition">&#128465;</button>
              </div>

              <!-- Parameters, only when the chosen field has any. A period box permanently on
                   screen for Close, which has no period, is a box that invites a wrong answer. -->
              <div class="row" style="padding-left:30px; margin-top:-3px;">
                  @for (p of paramsOf(c.leftKey); track p.name) {
                    <label style="font-size:10px; color:#8fa3bd; display:flex; align-items:center; gap:5px;">
                      {{ shortKey(c.leftKey) }} {{ p.name }}
                      <input class="num" type="number" style="width:66px; min-height:28px; padding:4px 7px;"
                             [ngModel]="c.leftParams[p.name]"
                             (ngModelChange)="setParam(c, 'left', p, $event)"
                             [min]="p.min" [max]="p.max"
                             [title]="p.name + ' between ' + p.min + ' and ' + p.max + '. Default ' + p.default + '.'">
                    </label>
                  }
                  @if (c.rightKind === 'field') {
                    @for (p of paramsOf(c.rightKey); track p.name) {
                      <label style="font-size:10px; color:#8fa3bd; display:flex; align-items:center; gap:5px;">
                        {{ shortKey(c.rightKey) }} {{ p.name }}
                        <input class="num" type="number" style="width:66px; min-height:28px; padding:4px 7px;"
                               [ngModel]="c.rightParams[p.name]"
                               (ngModelChange)="setParam(c, 'right', p, $event)"
                               [min]="p.min" [max]="p.max"
                               [title]="p.name + ' between ' + p.min + ' and ' + p.max + '. Default ' + p.default + '.'">
                      </label>
                    }
                  }

                  <!-- Bars ago, per side. Kept on this second line rather than the main row:
                       it is zero on almost every condition, and a box that is almost always
                       zero should not be taking width from the ones that are not. -->
                  <label style="font-size:10px; color:#8fa3bd; display:flex; align-items:center; gap:5px;"
                         [title]="offsetHint(c, c.leftOffset)">
                    {{ shortKey(c.leftKey) }} bars ago
                    <input class="num" type="number" style="width:56px; min-height:28px; padding:4px 7px;"
                           [(ngModel)]="c.leftOffset" (ngModelChange)="revalidate()" min="0" max="500">
                  </label>
                  @if (c.rightKind === 'field') {
                    <label style="font-size:10px; color:#8fa3bd; display:flex; align-items:center; gap:5px;"
                           [title]="offsetHint(c, c.rightOffset)">
                      {{ shortKey(c.rightKey) }} bars ago
                      <input class="num" type="number" style="width:56px; min-height:28px; padding:4px 7px;"
                             [(ngModel)]="c.rightOffset" (ngModelChange)="revalidate()" min="0" max="500">
                    </label>
                  }
                </div>
            }

            @if (!g.conditions.length) {
              <div class="empty" style="padding:4px 2px 9px;">
                No conditions in this group yet &mdash; pick an indicator on the left, or add one below.
              </div>
            }

            <button class="dashed" (click)="addCondition(g)">+ Add Condition</button>
          </div>

          @if (gi < groups.length - 1) {
            <div class="joiner">
              <select [(ngModel)]="g.joinNext"
                      title="How this group combines with the next one.">
                <option value="AND">AND</option>
                <option value="OR">OR</option>
              </select>
            </div>
          }
        }

        <div class="foot">
          <button class="dashed" (click)="addGroup()">+ Add Condition Group</button>
          <button class="dashed" (click)="addCustomIndicator()"
                  title="Compare an indicator against a multiple of another one, such as Volume above 2x its 20-day average.">
            + Add Custom Indicator
          </button>
          <button class="dashed" (click)="addFormula()"
                  title="Write the rule as text and convert it, in the text builder.">
            + Add Formula
          </button>
        </div>
      </div>
    </div>

    <!-- ── strategy preview ────────────────────────────────────────────── -->
    <div class="panel">
      <div class="panel-hd">
        <span style="color:#22c55e; font-size:15px;">&#10003;</span>
        <span class="panel-ttl">Strategy Preview</span>
      </div>
      <div class="panel-bd">
        <div class="kv">
          <span class="k">Strategy Name</span>
          <input class="name-in" [(ngModel)]="name" placeholder="My Custom Strategy"
                 title="Shown in the strategy list and on every backtest run.">
          <span style="color:#64748b; font-size:11px;">&#9998;</span>
        </div>
        <div class="kv">
          <span class="k">Stock Universe</span>
          <select class="sel" style="flex:1 1 auto; min-width:0;" [(ngModel)]="universe"
                  title="Which stocks the screener walks.">
            <option value="NSE_50">Nifty 50</option>
            <option value="NSE_500">Nifty 500</option>
            <option value="ALL_CASH">All NSE equities</option>
          </select>
        </div>
        <div class="kv">
          <span class="k">Timeframe</span>
          <span class="v">{{ dominantTimeframe() }}</span>
        </div>
        <div class="kv">
          <span class="k">Trading Mode</span>
          <span class="pill" [class.pill-paper]="mode === 'PAPER'" [class.pill-real]="mode === 'REAL'">
            {{ mode === 'REAL' ? 'Real Trading' : 'Paper Trading' }}
          </span>
        </div>

        <div class="sub-ttl">Strategy Logic</div>
        <div class="logic">
          @if (!conditionCount) {
            <span style="color:#64748b;">Nothing yet. Pick an indicator from the library to start.</span>
          }
          @for (g of groups; track g.id; let gi = $index) {
            @if (g.conditions.length) {
              <div class="logic-g">
                <span class="lg-k">Group {{ gi + 1 }} :</span>
                <span class="lg-v">{{ groupText(g) }}</span>
              </div>
              @if (gi < groups.length - 1 && groups[gi + 1].conditions.length) {
                <div class="logic-join">{{ g.joinNext }}</div>
              }
            }
          }
        </div>

        <!-- Validation is the server's, not a second opinion written here. The builder and the
             engine disagreeing about whether a rule is valid is worse than no check at all. -->
        @if (validation) {
          <div class="issue"
               [class.issue-ok]="validation.ok && !validation.issues?.length"
               [class.issue-warn]="validation.ok && validation.issues?.length"
               [class.issue-err]="!validation.ok">
            @if (validation.ok && !validation.issues?.length) {
              <strong>Valid.</strong> Every field and parameter is one the engine can evaluate.
            } @else {
              <strong>{{ validation.ok ? 'Check these:' : 'Cannot run yet:' }}</strong>
              @for (i of betweenIssues(); track $index) {
                <li style="color:#fca5a5;">{{ i }}</li>
              }
              @for (i of validation.issues || []; track $index) {
                <div style="margin-top:3px;">&bull; {{ i.message }}</div>
              }
            }
          </div>
        }

        <div class="sub-ttl">Key Settings</div>
        <div class="kv"><span class="k">Top Stocks</span>
          <span class="v">
            <input class="num" type="number" style="width:52px; min-height:28px; padding:4px 7px;"
                   [(ngModel)]="topStocks" min="1" max="50">
            <span style="color:#8fa3bd; font-weight:600; font-size:11px;"> by volume ratio</span>
          </span>
        </div>
        <div class="kv"><span class="k">Capital Allocation</span>
          <select class="sel" style="flex:1 1 auto; min-width:0;" [(ngModel)]="allocation">
            <option value="EQUAL">Equal Distribution</option>
            <option value="RISK">Risk-weighted</option>
          </select>
        </div>
        <div class="kv"><span class="k">Stop Loss</span>
          <span class="v"><input class="num" type="number" style="width:62px; min-height:28px; padding:4px 7px;"
                                 [(ngModel)]="stopLossPct" min="0" max="100" step="0.5"> %</span>
        </div>
        <div class="kv"><span class="k">Target Price</span>
          <span class="v"><input class="num" type="number" style="width:62px; min-height:28px; padding:4px 7px;"
                                 [(ngModel)]="targetPct" min="0" max="500" step="0.5"> %</span>
        </div>
        <div class="kv"><span class="k">Trading Time</span>
          <span class="v" style="display:flex; gap:5px; align-items:center;">
            <input class="num" type="time" style="min-height:28px; padding:4px 7px;" [(ngModel)]="windowStart">
            <span style="color:#8fa3bd;">&ndash;</span>
            <input class="num" type="time" style="min-height:28px; padding:4px 7px;" [(ngModel)]="windowEnd">
          </span>
        </div>

        <button class="dashed" style="margin-top:7px;" (click)="showDetails = !showDetails">
          {{ showDetails ? 'Hide Details' : 'View Details' }} {{ showDetails ? '&#9650;' : '&#9660;' }}
        </button>
        @if (showDetails) {
          <div class="logic" style="margin-top:8px;">
            <div style="color:#8fa3bd; margin-bottom:5px;">Saved as</div>
            <div style="color:#dbeafe; overflow-wrap:anywhere; font-family:ui-monospace,monospace;
                        font-size:10px; max-height:200px; overflow:auto;">{{ astPreview() }}</div>
            <div style="color:#64748b; margin-top:7px; line-height:1.6;">
              This is the rule tree the validator reads and the screener evaluates. Groups are
              preserved in it, which is what stops the conditions being re-folded in row order.
            </div>
          </div>
        }
      </div>
    </div>
  </div>

  <!-- ══ bottom tabs ═════════════════════════════════════════════════════ -->
  <div class="tabs">
    @for (t of tabs; track t.key) {
      <button [class.on]="tab === t.key" (click)="tab = t.key">
        <span>{{ t.icon }}</span> {{ t.label }}
      </button>
    }
  </div>
  <div class="tab-bd">

    @if (tab === 'preview') {
      <div style="display:grid; grid-template-columns:repeat(auto-fit,minmax(260px,1fr)); gap:16px;">
        <div>
          <div class="sub-ttl" style="margin-top:0;">&#128200; Expected Performance (Backtest)</div>
          @if (backtest) {
            <div class="perf"><span class="pl">Win Rate</span>
              <span class="pv" style="color:#22c55e;">{{ backtest.winRate | number:'1.1-1' }}%</span></div>
            <div class="perf"><span class="pl">Total Return</span>
              <span class="pv" [style.color]="(backtest.totalReturn || 0) >= 0 ? '#22c55e' : '#ef4444'">
                {{ backtest.totalReturn | number:'1.1-1' }}%</span></div>
            <div class="perf"><span class="pl">Max Drawdown</span>
              <span class="pv" style="color:#ef4444;">{{ backtest.maxDrawdown | number:'1.1-1' }}%</span></div>
            <div style="font-size:9.5px; color:#64748b; line-height:1.6; margin-top:6px;">
              From the run on {{ backtest.ranAt || 'this strategy' }}. Past performance is not a
              forecast, and a backtest flatters a rule that was chosen after seeing the data.
            </div>
          } @else {
            <div class="empty">
              No backtest has been run for this strategy yet. Save it, then press
              <strong>Build Strategy</strong> &mdash; that saves, validates and runs the screener,
              and the backtest can be run from the Backtest Results tab.
            </div>
          }
        </div>

        <div style="grid-column:span 2; min-width:0;">
          <div class="sub-ttl" style="margin-top:0;">Top {{ topStocks }} Stocks (Current Day)</div>
          @if (scanRows.length) {
            <div class="scroll-x">
              <table>
                <thead><tr>
                  <th>#</th><th>Symbol</th>
                  <th class="num-col">CMP (&#8377;)</th>
                  <th class="num-col">Change %</th>
                  <th class="num-col">Volume</th>
                  <th class="num-col">Stop Loss</th>
                  <th class="num-col">Target</th>
                  <th class="num-col">R:R</th>
                </tr></thead>
                <tbody>
                  @for (r of scanRows.slice(0, topStocks); track r.symbol; let i = $index) {
                    <tr>
                      <td>{{ i + 1 }}</td>
                      <td style="color:#fff; font-weight:700;">{{ r.symbol }}</td>
                      <td class="num-col">{{ r.cmp | number:'1.2-2' }}</td>
                      <td class="num-col" [style.color]="(r.changePct || 0) >= 0 ? '#22c55e' : '#ef4444'">
                        {{ r.changePct > 0 ? '+' : '' }}{{ r.changePct | number:'1.2-2' }}%</td>
                      <td class="num-col">{{ volFmt(r.volume) }}</td>
                      <td class="num-col">{{ r.stopLoss | number:'1.2-2' }}</td>
                      <td class="num-col">{{ r.target | number:'1.2-2' }}</td>
                      <td class="num-col" style="color:#fbbf24;">{{ r.rrRatio | number:'1.2-2' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <div class="empty">
              {{ scanMessage || 'Press Build Strategy to screen the universe against these conditions.' }}
            </div>
          }
        </div>
      </div>
    }

    @if (tab === 'screener') {
      <div style="display:flex; align-items:center; gap:9px; flex-wrap:wrap; margin-bottom:10px;">
        <button class="btn btn-ghost" (click)="runScan()" [disabled]="scanning || !strategyId">
          @if (scanning) { <span class="spin"></span> Screening&hellip; } @else { Run Screener }
        </button>
        <span style="font-size:10.5px; color:#64748b;">
          @if (!strategyId) { Save the strategy first &mdash; the screener runs against a saved rule. }
          @else { Walks the {{ universeLabel() }} universe and applies every group above. }
        </span>
      </div>
      @if (scanRows.length) {
        <div class="scroll-x">
          <table>
            <thead><tr>
              <th>#</th><th>Symbol</th>
              <th class="num-col">CMP (&#8377;)</th><th class="num-col">Change %</th>
              <th class="num-col">Volume</th><th class="num-col">Delivery %</th>
              <th class="num-col">Stop Loss</th><th class="num-col">Target</th>
              <th class="num-col">R:R</th>
            </tr></thead>
            <tbody>
              @for (r of scanRows; track r.symbol; let i = $index) {
                <tr>
                  <td>{{ i + 1 }}</td>
                  <td style="color:#fff; font-weight:700;">{{ r.symbol }}</td>
                  <td class="num-col">{{ r.cmp | number:'1.2-2' }}</td>
                  <td class="num-col" [style.color]="(r.changePct || 0) >= 0 ? '#22c55e' : '#ef4444'">
                    {{ r.changePct > 0 ? '+' : '' }}{{ r.changePct | number:'1.2-2' }}%</td>
                  <td class="num-col">{{ volFmt(r.volume) }}</td>
                  <td class="num-col">{{ r.deliveryPct | number:'1.1-1' }}</td>
                  <td class="num-col">{{ r.stopLoss | number:'1.2-2' }}</td>
                  <td class="num-col">{{ r.target | number:'1.2-2' }}</td>
                  <td class="num-col" style="color:#fbbf24;">{{ r.rrRatio | number:'1.2-2' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      } @else {
        <div class="empty">{{ scanMessage || 'No scan has been run yet.' }}</div>
      }
    }

    @if (tab === 'chart') {
      <div style="display:flex; align-items:center; gap:9px; flex-wrap:wrap; margin-bottom:11px;">
        <label style="font-size:10.5px; color:#8fa3bd;">Symbol</label>
        @if (scanRows.length) {
          <select class="sel" [(ngModel)]="chartSymbol" (ngModelChange)="loadChart()"
                  title="Any stock the screener selected.">
            @for (r of scanRows; track r.symbol) { <option [value]="r.symbol">{{ r.symbol }}</option> }
          </select>
        } @else {
          <input class="num" [(ngModel)]="chartSymbol" (keyup.enter)="loadChart()"
                 placeholder="TCS" style="width:120px; text-transform:uppercase;">
        }
        <label style="font-size:10.5px; color:#8fa3bd;">Sessions</label>
        <select class="sel" [(ngModel)]="chartDays" (ngModelChange)="loadChart()">
          <option [ngValue]="40">40</option>
          <option [ngValue]="90">90</option>
          <option [ngValue]="180">180</option>
        </select>
        <button class="btn btn-ghost" (click)="loadChart()" [disabled]="chartLoading">
          @if (chartLoading) { <span class="spin"></span> Loading&hellip; } @else { Load }
        </button>
        <span style="margin-left:auto; display:flex; gap:12px; font-size:10.5px; color:#8fa3bd;">
          <span><span style="color:#38bdf8;">&#9679;</span> Close</span>
          <span><span style="color:#f59e0b;">&#9679;</span> EMA({{ chartEmaPeriod }})</span>
          <span><span style="color:#2563eb;">&#9679;</span> Volume</span>
        </span>
      </div>

      @if (chartCandles.length >= 2) {
        <!-- Inline SVG rather than a charting library. The page already loads nothing for this
             tab, a line, an average and a volume histogram are three path elements, and a
             dependency that has to be code-split and themed is a poor trade for that. -->
        <div style="font-size:12px; font-weight:700; color:#fff; margin-bottom:5px;">
          {{ chartSymbol }} &ndash; Daily Chart
          <span style="font-weight:500; color:#64748b; font-size:10.5px; margin-left:7px;">
            {{ chartCandles[0].trade_date }} to {{ chartCandles[chartCandles.length - 1].trade_date }}
          </span>
        </div>
        <div class="scroll-x">
          <svg [attr.viewBox]="'0 0 ' + CW + ' ' + CH" width="100%" [attr.height]="CH"
               preserveAspectRatio="none" style="min-width:420px; display:block;">
            <!-- price gridlines, labelled, so the line has a scale rather than a shape -->
            @for (g of chartGrid(); track g.y) {
              <line [attr.x1]="CPAD" [attr.x2]="CW - 44" [attr.y1]="g.y" [attr.y2]="g.y"
                    stroke="#1b2c44" stroke-width="1"></line>
              <text [attr.x]="CW - 40" [attr.y]="g.y + 3" fill="#64748b" font-size="9">{{ g.label }}</text>
            }
            <!-- volume, on its own scale along the bottom -->
            @for (b of chartVolumeBars(); track b.x) {
              <rect [attr.x]="b.x" [attr.y]="b.y" [attr.width]="b.w" [attr.height]="b.h"
                    fill="#2563eb" opacity="0.5"></rect>
            }
            <path [attr.d]="chartPath('close')" fill="none" stroke="#38bdf8" stroke-width="1.7"
                  stroke-linejoin="round"></path>
            <path [attr.d]="chartPath('ema')" fill="none" stroke="#f59e0b" stroke-width="1.5"
                  stroke-dasharray="4 3"></path>
            <!-- date ticks: first, middle and last, which is as many as fit honestly -->
            @for (t of chartDateTicks(); track t.x) {
              <text [attr.x]="t.x" [attr.y]="CH - 3" fill="#64748b" font-size="9"
                    [attr.text-anchor]="t.anchor">{{ t.label }}</text>
            }
          </svg>
        </div>
        <div style="font-size:9.5px; color:#64748b; line-height:1.6; margin-top:7px;">
          Stored daily closes, oldest first. The EMA is computed in the browser from the same
          series, so it matches what a condition on EMA({{ chartEmaPeriod }}) is measured against
          &mdash; it is not a second opinion from somewhere else.
        </div>
      } @else {
        <div class="empty">
          {{ chartMessage || 'Pick a symbol and press Load, or run the screener first to chart what it selected.' }}
        </div>
      }
    }

    @if (tab === 'backtest') {
      <div style="display:flex; align-items:center; gap:9px; flex-wrap:wrap; margin-bottom:10px;">
        <label style="font-size:10.5px; color:#8fa3bd;">From</label>
        <input class="num" type="date" [(ngModel)]="btFrom">
        <label style="font-size:10.5px; color:#8fa3bd;">To</label>
        <input class="num" type="date" [(ngModel)]="btTo">
        <button class="btn btn-ghost" (click)="runBacktest()" [disabled]="btRunning || !strategyId">
          @if (btRunning) { <span class="spin"></span> Running&hellip; } @else { Run Backtest }
        </button>
        @if (!strategyId) {
          <span style="font-size:10.5px; color:#64748b;">Save the strategy first.</span>
        }
      </div>
      @if (backtest) {
        <div style="display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:9px;">
          @for (m of backtestMetrics(); track m.label) {
            <div style="background:#0b1626; border:1px solid #223449; border-radius:9px; padding:9px 11px;">
              <div style="font-size:9px; color:#8fa3bd; text-transform:uppercase; letter-spacing:.5px;">{{ m.label }}</div>
              <div style="font-size:16px; font-weight:800; margin-top:2px;" [style.color]="m.color">{{ m.value }}</div>
            </div>
          }
        </div>
      } @else {
        <div class="empty">{{ btMessage || 'No backtest yet. Pick a range and run one.' }}</div>
      }
    }

    @if (tab === 'settings') {
      <div style="display:grid; grid-template-columns:repeat(auto-fit,minmax(230px,1fr)); gap:11px;">
        <label style="display:flex; flex-direction:column; gap:4px;">
          <span style="font-size:9px; color:#8fa3bd; text-transform:uppercase; letter-spacing:.5px; font-weight:700;">Description</span>
          <input class="num" [(ngModel)]="description" placeholder="What this strategy is for">
        </label>
        <label style="display:flex; flex-direction:column; gap:4px;">
          <span style="font-size:9px; color:#8fa3bd; text-transform:uppercase; letter-spacing:.5px; font-weight:700;">Max open positions</span>
          <input class="num" type="number" [(ngModel)]="maxPositions" min="1" max="50">
        </label>
        <label style="display:flex; flex-direction:column; gap:4px;">
          <span style="font-size:9px; color:#8fa3bd; text-transform:uppercase; letter-spacing:.5px; font-weight:700;">Minimum reward:risk</span>
          <input class="num" type="number" [(ngModel)]="rrMin" min="0" max="20" step="0.1">
          <span style="font-size:9px; color:#64748b;">Target is raised to meet this if it falls short.</span>
        </label>
        <label style="display:flex; flex-direction:column; gap:4px;">
          <span style="font-size:9px; color:#8fa3bd; text-transform:uppercase; letter-spacing:.5px; font-weight:700;">Holding days</span>
          <input class="num" type="number" [(ngModel)]="holdingDays" min="1" max="250">
        </label>
        <label style="display:flex; flex-direction:column; gap:4px;">
          <span style="font-size:9px; color:#8fa3bd; text-transform:uppercase; letter-spacing:.5px; font-weight:700;">Risk per trade %</span>
          <input class="num" type="number" [(ngModel)]="riskPerTradePct" min="0" max="100" step="0.1">
        </label>
        <label style="display:flex; flex-direction:column; gap:4px;">
          <span style="font-size:9px; color:#8fa3bd; text-transform:uppercase; letter-spacing:.5px; font-weight:700;">Tags</span>
          <input class="num" [(ngModel)]="tags" placeholder="swing, momentum">
        </label>
      </div>
      <div style="font-size:10px; color:#64748b; line-height:1.7; margin-top:11px;">
        Stop loss, target, universe and the trading window are on the preview panel, beside the
        logic they qualify.
      </div>
    }
  </div>

  @if (toast) {
    <div class="issue" [class.issue-ok]="toastOk" [class.issue-err]="!toastOk"
         style="margin-top:12px;">{{ toast }}</div>
  }
</div>
  `
})
export class StrategyCanvasComponent implements OnInit {

  constructor(private api: ApiService, private router: Router,
              private route: ActivatedRoute) {}

  // ── catalogue ────────────────────────────────────────────────────────────
  fields: FieldDef[] = [];
  fieldsLoading = false;
  timeframes: Array<{ key: string; label: string; available: boolean }> = [
    { key: 'DAILY', label: 'Daily', available: true }
  ];

  /**
   * The library's headings.
   *
   * <p>Finer than the server's five categories on purpose - PRICE / VOLUME / INDICATOR /
   * DERIVED / FUNDAMENTAL puts RSI, ADX, Bollinger and the moving averages in one undifferentiated
   * list of twenty-odd entries. The split below is by what a reader is looking for. Membership is
   * by key, and every catalogued field that matches no list falls through to Reference, so a
   * field added on the server appears here without a frontend change rather than vanishing.
   */
  private readonly CATEGORY_MAP: Array<{ name: string; icon: string; keys: string[] }> = [
    { name: 'Price',           icon: '\u{1F4CA}', keys: ['close','open','high','low','prev_close','prev_high','prev_low'] },
    { name: 'Moving Averages', icon: '\u{1F4C8}', keys: ['EMA','SMA','WMA','VWAP'] },
    { name: 'Momentum',        icon: '⚡',    keys: ['RSI','MACD','MACD_SIGNAL','MACD_HIST','STOCH_K','STOCH_D','CCI','MFI','ROC','WILLIAMS_R'] },
    { name: 'Volume',          icon: '\u{1F4D1}', keys: ['volume','avg_volume','volume_ratio','OBV'] },
    { name: 'Trend',           icon: '\u{1F4C9}', keys: ['ADX','SUPERTREND'] },
    { name: 'Volatility',      icon: '\u{1F3AF}', keys: ['ATR','BB_UPPER','BB_MIDDLE','BB_LOWER','BB_WIDTH','BB_PERCENT_B'] },
    { name: 'Price Action',    icon: '\u{1F4C8}', keys: ['pct_change','gap_pct'] },
  ];

  libSearch = '';
  openCats: Record<string, boolean> = { 'Volume': true };

  // ── the strategy ─────────────────────────────────────────────────────────
  strategyId: number | null = null;
  name = 'My Custom Strategy';
  description = '';
  universe = 'NSE_500';
  mode: 'PAPER' | 'REAL' = 'PAPER';
  topStocks = 5;
  allocation = 'EQUAL';
  stopLossPct = 5;
  targetPct = 15;
  windowStart = '09:15';
  windowEnd = '15:30';
  maxPositions = 5;
  rrMin = 2;
  holdingDays = 22;
  riskPerTradePct = 2;
  tags = '';

  groups: Group[] = [];
  private nextId = 1;

  // ── ui state ─────────────────────────────────────────────────────────────
  saveMenu = false;
  showDetails = false;
  building = false;
  scanning = false;
  btRunning = false;
  toast = '';
  toastOk = true;
  validation: any = null;
  scanRows: any[] = [];
  scanMessage = '';
  backtest: any = null;
  btMessage = '';
  btFrom = '';
  btTo = '';

  tab: 'preview' | 'screener' | 'chart' | 'backtest' | 'settings' = 'preview';
  readonly tabs = [
    { key: 'preview',  icon: '\u{1F4CA}', label: 'Strategy Preview' },
    { key: 'screener', icon: '\u{1F50D}', label: 'Stock Screener' },
    { key: 'chart',    icon: '\u{1F4C8}', label: 'Chart' },
    { key: 'backtest', icon: '\u{1F5D3}', label: 'Backtest Results' },
    { key: 'settings', icon: '⚙',    label: 'Strategy Settings' }
  ] as const;

  /**
   * The comparators, labelled as they read rather than as they are stored.
   *
   * <p>Keys are the engine's own {@code CompareOp} names, so nothing has to be translated on the
   * way in or out. "Greater Than" and ">" are the same operator and the screen should not make
   * anyone learn which spelling this box wants.
   */
  readonly operators = [
    { key: 'GT',            label: 'Greater Than' },
    { key: 'GTE',           label: 'Greater or Equal' },
    { key: 'LT',            label: 'Less Than' },
    { key: 'LTE',           label: 'Less or Equal' },
    { key: 'EQ',            label: 'Equal To' },
    { key: 'NEQ',           label: 'Not Equal To' },
    { key: 'BETWEEN',       label: 'Between' },
    { key: 'CROSSES_ABOVE', label: 'Crosses Above' },
    { key: 'CROSSES_BELOW', label: 'Crosses Below' }
  ];

  ngOnInit(): void {
    this.loadFields();
    this.loadTimeframes();
    const today = new Date();
    const year = new Date(today.getFullYear() - 1, today.getMonth(), today.getDate());
    this.btTo = today.toISOString().slice(0, 10);
    this.btFrom = year.toISOString().slice(0, 10);
    // Two groups from the start, because one group is a flat list and would teach the wrong
    // model of the screen. They open empty, not pre-filled with someone else's strategy.
    this.groups = [this.newGroup('ALL'), this.newGroup('ANY')];

    // ...unless the route names one to open. /strategy-builder?id=12 reopens that strategy,
    // which is what makes a saved rule correctable rather than only re-creatable.
    const id = Number(this.route.snapshot.queryParamMap.get('id'));
    if (Number.isFinite(id) && id > 0) this.loadSavedStrategy(id);
  }

  // ── catalogue loading ────────────────────────────────────────────────────

  loadFields(): void {
    this.fieldsLoading = true;
    this.api.get<any>('/strategy-builder/fields').subscribe({
      next: r => {
        this.fields = (r?.data?.fields || []) as FieldDef[];
        this.fieldsLoading = false;
      },
      error: () => { this.fields = []; this.fieldsLoading = false; }
    });
  }

  private loadTimeframes(): void {
    this.api.get<any>('/strategy-builder/timeframes').subscribe({
      next: r => {
        const list = (r?.data?.timeframes || r?.data?.list || []) as any[];
        if (list.length) {
          this.timeframes = list.map(t => ({
            key: t.key, label: t.label || t.key, available: t.available !== false
          }));
        }
      },
      error: () => { /* the Daily fallback already in place is correct */ }
    });
  }

  /** Fields that may be picked in a condition, availability included so they can be greyed. */
  get selectableFields(): FieldDef[] {
    return this.fields.filter(f => f.category !== 'FUNDAMENTAL' || f.availability === 'AVAILABLE');
  }

  /** The library, grouped and filtered by the search box. */
  visibleCategories(): Array<{ name: string; icon: string; items: FieldDef[] }> {
    const q = this.libSearch.trim().toLowerCase();
    const match = (f: FieldDef) =>
      !q || f.key.toLowerCase().includes(q) || (f.label || '').toLowerCase().includes(q);

    const taken = new Set<string>();
    const out: Array<{ name: string; icon: string; items: FieldDef[] }> = [];

    for (const cat of this.CATEGORY_MAP) {
      const items: FieldDef[] = [];
      for (const key of cat.keys) {
        const f = this.fields.find(x => x.key === key);
        if (!f) continue;
        taken.add(key);
        if (match(f)) items.push(f);
      }
      if (items.length) out.push({ name: cat.name, icon: cat.icon, items });
    }

    // Everything the map did not claim. Named Reference rather than Other so it reads as a
    // place to look rather than a leftovers bin.
    const rest = this.fields.filter(f => !taken.has(f.key) && match(f));
    if (rest.length) out.push({ name: 'Reference', icon: '\u{1F3AF}', items: rest });

    return out;
  }

  isOpen(cat: string): boolean {
    // A search opens everything: hunting for "stoch" and being shown nine collapsed headings is
    // the search not working, as far as the reader is concerned.
    return !!this.libSearch.trim() || !!this.openCats[cat];
  }
  toggleCat(cat: string): void { this.openCats[cat] = !this.openCats[cat]; }

  // ── the model ────────────────────────────────────────────────────────────

  private newGroup(mode: 'ALL' | 'ANY'): Group {
    return { id: this.nextId++, mode, conditions: [], joinNext: 'AND' };
  }

  private newCondition(leftKey?: string): Cond {
    const key = leftKey || this.fields.find(f => f.key === 'close')?.key || 'close';
    return {
      id: this.nextId++,
      timeframe: 'DAILY',
      leftKey: key,
      leftParams: this.defaultParams(key),
      op: 'GT',
      rightKind: 'value',
      rightValue: null,
      rightValue2: null,
      rightKey: 'close',
      rightParams: this.defaultParams('close'),
      leftOffset: 0,
      rightOffset: 0
    };
  }

  private defaultParams(key: string): Record<string, number> {
    const out: Record<string, number> = {};
    for (const p of this.paramsOf(key)) out[p.name] = Number(p.default);
    return out;
  }

  paramsOf(key: string): Array<{ name: string; type: string; default: number; min: number; max: number }> {
    return this.fields.find(f => f.key === key)?.params || [];
  }

  addGroup(): void {
    // A second group defaults to ANY and a third to ALL: a new group that behaves identically to
    // the one above it is the one thing a group cannot usefully be.
    this.groups.push(this.newGroup(this.groups.length % 2 === 1 ? 'ANY' : 'ALL'));
  }

  removeGroup(i: number): void {
    const g = this.groups[i];
    if (g.conditions.length &&
        !confirm(`Remove Group ${i + 1} and its ${g.conditions.length} condition(s)?`)) return;
    this.groups.splice(i, 1);
    if (!this.groups.length) this.groups.push(this.newGroup('ALL'));
    this.revalidate();
  }

  addCondition(g: Group): void { g.conditions.push(this.newCondition()); this.revalidate(); }

  removeCondition(g: Group, i: number): void { g.conditions.splice(i, 1); this.revalidate(); }

  /** Clicking a library entry adds a condition on it, to the last group that has room. */
  useField(f: FieldDef): void {
    if (f.availability !== 'AVAILABLE') return;
    const g = this.groups[0] || this.newGroup('ALL');
    if (!this.groups.length) this.groups.push(g);
    g.conditions.push(this.newCondition(f.key));
    this.revalidate();
  }

  onFieldChanged(c: Cond, side: 'left' | 'right'): void {
    // Parameters belong to the field, so switching RSI for Close must not leave a period of 14
    // attached to something that has no period.
    if (side === 'left') c.leftParams = this.defaultParams(c.leftKey);
    else c.rightParams = this.defaultParams(c.rightKey);
    this.revalidate();
  }

  setParam(c: Cond, side: 'left' | 'right', p: any, value: any): void {
    const target = side === 'left' ? c.leftParams : c.rightParams;
    const n = Number(value);
    target[p.name] = isFinite(n) ? n : Number(p.default);
    this.revalidate();
  }

  toggleRightKind(c: Cond): void {
    c.rightKind = c.rightKind === 'value' ? 'field' : 'value';
    this.revalidate();
  }

  clearAll(): void {
    if (!confirm('Remove every condition from every group?')) return;
    this.groups = [this.newGroup('ALL'), this.newGroup('ANY')];
    this.validation = null;
    this.toast = '';
  }

  /** "Volume greater than 2x its 20-day average" in one click: the commonest two-field rule. */
  addCustomIndicator(): void {
    const g = this.groups[0] || this.newGroup('ALL');
    if (!this.groups.length) this.groups.push(g);
    const c = this.newCondition('volume');
    c.op = 'GT';
    c.rightKind = 'field';
    c.rightKey = this.fields.some(f => f.key === 'avg_volume') ? 'avg_volume' : 'volume';
    c.rightParams = this.defaultParams(c.rightKey);
    g.conditions.push(c);
    this.revalidate();
    this.say('Added Volume above its average. Set the period beneath the row, or switch the '
           + 'right-hand side with the f(x) button.', true);
  }

  addFormula(): void {
    this.router.navigate(['/strategy-builder/classic']);
  }

  openClassic(): void {
    this.saveMenu = false;
    this.router.navigate(['/strategy-builder/classic']);
  }

  setRealMode(): void {
    if (this.mode === 'REAL') return;
    if (!confirm('Mark this strategy for Real Trading?\n\n' +
                 'This records the intent on the strategy. It does not place orders: real ' +
                 'execution still needs the account\'s real-trading authorization and a ' +
                 'connected broker, and is armed on the trading screen.')) return;
    this.mode = 'REAL';
  }

  get conditionCount(): number {
    return this.groups.reduce((n, g) => n + g.conditions.length, 0);
  }

  // ── rendering helpers ────────────────────────────────────────────────────

  labelOf(key: string): string {
    return this.fields.find(f => f.key === key)?.label || key;
  }

  /** "RSI(14)", "MACD(12,26,9)", "Close" - the label with its parameters, as the mockup reads. */
  labelWithParams(key: string, params: Record<string, number>): string {
    const defs = this.paramsOf(key);
    if (!defs.length) return this.labelOf(key);
    const vals = defs.map(p => {
      const v = params ? params[p.name] : undefined;
      return v === undefined || v === null || !isFinite(Number(v)) ? p.default : v;
    });
    return `${this.labelOf(key)}(${vals.join(',')})`;
  }

  shortKey(key: string): string {
    const l = this.labelOf(key);
    return l.length > 14 ? key : l;
  }

  describeField(key: string): string {
    const f = this.fields.find(x => x.key === key);
    if (!f) return key;
    return `${f.label}${f.unit ? ' — measured in ' + f.unit : ''}`;
  }

  unitHint(key: string): string {
    const u = this.fields.find(f => f.key === key)?.unit || '';
    if (u === '0-100') return '0-100';
    if (u === '%') return '%';
    if (u === 'price') return 'price';
    if (u === 'shares') return 'shares';
    return 'value';
  }

  /** What "N bars ago" means on this condition's own timeframe. */
  offsetHint(c: Cond, n: number): string {
    const bars = Number(n) || 0;
    if (bars <= 0) return 'The bar being evaluated. Raise it to read an earlier one.';
    const unit = c.timeframe === 'WEEKLY' ? 'week'
               : c.timeframe === 'MONTHLY' ? 'month'
               : c.timeframe === 'H1' ? 'hour' : 'session';
    return bars + ' ' + unit + (bars === 1 ? '' : 's') + ' before the one being evaluated.';
  }

  operatorHint(op: string): string {
    switch (op) {
      case 'CROSSES_ABOVE':
        return 'True only on the bar it crosses: below on the previous bar, at or above on this one.';
      case 'CROSSES_BELOW':
        return 'True only on the bar it crosses downwards.';
      case 'BETWEEN':
        return 'Inclusive of both bounds.';
      default:
        return 'Compared on the bar being evaluated.';
    }
  }

  /** One group, in words, for the preview panel. */
  groupText(g: Group): string {
    const join = g.mode === 'ANY' ? ' OR ' : ' AND ';
    return g.conditions.map(c => this.conditionText(c)).join(join);
  }

  conditionText(c: Cond): string {
    const left = this.labelWithParams(c.leftKey, c.leftParams) + this.agoText(c, c.leftOffset);
    const op = this.operators.find(o => o.key === c.op);
    const sym = this.opSymbol(c.op);
    const right = c.rightKind === 'value'
      ? (c.op === 'BETWEEN'
          ? `${c.rightValue ?? '?'} and ${c.rightValue2 ?? '?'}`
          : `${c.rightValue ?? '?'}`)
      : this.labelWithParams(c.rightKey, c.rightParams) + this.agoText(c, c.rightOffset);
    const tf = c.timeframe && c.timeframe !== 'DAILY' ? `${this.tfLabel(c.timeframe)} ` : '';
    return `${tf}${left} ${sym || op?.label || c.op} ${right}`;
  }

  /** " [1 session ago]" for the preview, or nothing when the offset is zero. */
  private agoText(c: Cond, n: number): string {
    const bars = Math.max(0, Math.floor(Number(n) || 0));
    if (!bars) return '';
    const unit = c.timeframe === 'WEEKLY' ? 'week'
               : c.timeframe === 'MONTHLY' ? 'month'
               : c.timeframe === 'H1' ? 'hour' : 'session';
    return ` [${bars} ${unit}${bars === 1 ? '' : 's'} ago]`;
  }

  private opSymbol(op: string): string {
    switch (op) {
      case 'GT': return '>';
      case 'GTE': return '>=';
      case 'LT': return '<';
      case 'LTE': return '<=';
      case 'EQ': return '=';
      case 'NEQ': return '!=';
      default: return '';
    }
  }

  private tfLabel(key: string): string {
    return this.timeframes.find(t => t.key === key)?.label || key;
  }

  /** The bar width most conditions use, which is what the preview calls the strategy's. */
  dominantTimeframe(): string {
    const counts: Record<string, number> = {};
    for (const g of this.groups) {
      for (const c of g.conditions) counts[c.timeframe] = (counts[c.timeframe] || 0) + 1;
    }
    const keys = Object.keys(counts);
    if (!keys.length) return 'Daily';
    keys.sort((a, b) => counts[b] - counts[a]);
    const mixed = keys.length > 1;
    return this.tfLabel(keys[0]) + (mixed ? ' (mixed)' : '');
  }

  universeLabel(): string {
    return this.universe === 'NSE_50' ? 'Nifty 50'
         : this.universe === 'NSE_500' ? 'Nifty 500' : 'All NSE equities';
  }

  volFmt(v: any): string {
    const n = Number(v) || 0;
    if (n >= 1e7) return (n / 1e7).toFixed(2) + ' Cr';
    if (n >= 1e5) return (n / 1e5).toFixed(2) + ' L';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + ' K';
    return String(Math.round(n));
  }

  backtestMetrics(): Array<{ label: string; value: string; color: string }> {
    const b = this.backtest || {};
    const pct = (v: any) => (v == null ? '—' : Number(v).toFixed(2) + '%');
    const sign = (v: any) => (Number(v) >= 0 ? '#22c55e' : '#ef4444');
    return [
      { label: 'Win Rate',     value: pct(b.winRate),      color: '#22c55e' },
      { label: 'Total Return', value: pct(b.totalReturn),  color: sign(b.totalReturn) },
      { label: 'Max Drawdown', value: pct(b.maxDrawdown),  color: '#ef4444' },
      { label: 'Trades',       value: b.totalTrades == null ? '—' : String(b.totalTrades), color: '#e2e8f0' },
      { label: 'Profit Factor',value: b.profitFactor == null ? '—' : Number(b.profitFactor).toFixed(2), color: '#e2e8f0' },
      { label: 'Avg Return',   value: pct(b.avgReturn),    color: sign(b.avgReturn) }
    ];
  }

  // ── serialisation ────────────────────────────────────────────────────────

  /**
   * The rule tree, in the shape the server's AstParser reads.
   *
   * <p>Each group becomes a logical node of its own - AND for ALL, OR for ANY - and the groups
   * are wrapped in an outer node. That outer node is where the grouping actually lives: flatten
   * it and "all of these AND any one of those" collapses into a list whose meaning depends on
   * row order.
   *
   * <p>The group connectors are folded left to right, which is the same order the preview panel
   * reads them in, so the tree and the sentence above it cannot disagree.
   */
  buildAst(): any | null {
    const nodes = this.groups
      .filter(g => g.conditions.length)
      .map(g => ({
        type: 'logical',
        op: g.mode === 'ANY' ? 'OR' : 'AND',
        children: g.conditions.map(c => this.conditionAst(c))
      }));
    if (!nodes.length) return null;
    if (nodes.length === 1) return nodes[0];

    let tree: any = nodes[0];
    for (let i = 1; i < nodes.length; i++) {
      const join = this.groups.filter(g => g.conditions.length)[i - 1].joinNext;
      tree = { type: 'logical', op: join, children: [tree, nodes[i]] };
    }
    return tree;
  }

  private conditionAst(c: Cond): any {
    const right = c.rightKind === 'value'
      ? (c.op === 'BETWEEN'
          ? [{ type: 'literal', value: Number(c.rightValue) || 0 },
             { type: 'literal', value: Number(c.rightValue2) || 0 }]
          : { type: 'literal', value: Number(c.rightValue) || 0 })
      : this.operandAst(c.rightKey, c.rightParams, c.rightOffset);
    return {
      type: 'compare',
      op: c.op,
      timeframe: c.timeframe || 'DAILY',
      left: this.operandAst(c.leftKey, c.leftParams, c.leftOffset),
      right
    };
  }

  private operandAst(key: string, params: Record<string, number>, offset = 0): any {
    const f = this.fields.find(x => x.key === key);
    // PRICE and VOLUME are raw columns on the bar; everything else is computed. Getting this
    // wrong is not cosmetic - the engine looks up a field and an indicator in different places.
    const isField = !f || f.category === 'PRICE' || f.category === 'VOLUME';
    const back = Math.max(0, Math.floor(Number(offset) || 0));
    const node: any = (isField && !this.paramsOf(key).length)
      ? { type: 'field', name: key }
      : { type: 'indicator', name: key, params: { ...(params || {}) } };
    // The parser reads "offset" as sugar for a Shifted wrapper, so one row with a bars-ago box
    // stays one node rather than becoming something the author has to reason about.
    if (back > 0) node.offset = back;
    return node;
  }

  astPreview(): string {
    const ast = this.buildAst();
    return ast ? JSON.stringify(ast, null, 1) : '(nothing yet)';
  }

  /** The flat rows, carrying their group so the saved strategy keeps its structure. */
  private buildConditions(): any[] {
    const out: any[] = [];
    const live = this.groups.filter(g => g.conditions.length);
    live.forEach((g, gi) => {
      g.conditions.forEach(c => {
        out.push({
          group: 'entry',
          groupIndex: String(gi + 1),
          groupMode: g.mode,
          groupJoin: gi < live.length - 1 ? g.joinNext : 'AND',
          groupOperator: g.mode === 'ANY' ? 'OR' : 'AND',
          operator: g.mode === 'ANY' ? 'OR' : 'AND',
          indicatorLeft: c.leftKey,
          paramsLeft: c.leftParams,
          comparator: this.legacyComparator(c.op),
          indicatorRight: c.rightKind === 'field' ? c.rightKey : null,
          paramsRight: c.rightKind === 'field' ? c.rightParams : {},
          valueRight: c.rightKind === 'value' ? (Number(c.rightValue) || 0) : 0,
          // BETWEEN's upper bound. The row has carried rightValue2 and rendered an input for it
          // all along, and the AST payload sent it - but this legacy payload did not, and the
          // legacy payload is what StrategyConditionEvaluator reads. So a BETWEEN condition
          // arrived at the screener with a lower bound and no upper one, which it can only
          // skip. Sent as null rather than 0 for other operators, so an absent bound stays
          // absent instead of becoming a range ending at zero.
          valueRightUpper: c.op === 'BETWEEN' && c.rightKind === 'value'
            ? (c.rightValue2 === null || c.rightValue2 === undefined
                ? null : Number(c.rightValue2))
            : null,
          multiplier: 1.0,
          timeframe: c.timeframe,
          offsetLeft: Math.max(0, Math.floor(Number(c.leftOffset) || 0)),
          offsetRight: Math.max(0, Math.floor(Number(c.rightOffset) || 0))
        });
      });
    });
    return out;
  }

  /**
   * Reopen a saved strategy, so a rule can be read back and corrected rather than rebuilt.
   *
   * <p>The canvas could only ever start empty: strategyId was set from a save response and
   * nothing else, the route carried no id, and nothing navigated here with one. A saved
   * strategy therefore could not be opened at all - which also meant no operator's stored
   * values could be seen again, BETWEEN's two bounds among them.
   *
   * <p>Rehydrated from the strategy's own conditions_json, which holds the exact payload that
   * was sent. No new endpoint and no second format: the same list that was saved comes back.
   */
  private loadSavedStrategy(id: number): void {
    this.api.get<any>('/strategy-builder/strategies/' + id).subscribe({
      next: r => {
        const strat = r?.data?.strategy;
        if (!strat) { this.say('Could not open that strategy.', false); return; }
        this.strategyId = Number(strat.id) || id;
        this.name = strat.name || this.name;
        this.description = strat.description || '';
        if (strat.universe) this.universe = strat.universe;
        if (strat.mode) this.mode = strat.mode;

        let conds: any[] = [];
        try {
          const raw = strat.conditionsJson || strat.conditions_json;
          conds = typeof raw === 'string' ? JSON.parse(raw) : (raw || []);
        } catch { conds = []; }
        if (!Array.isArray(conds) || !conds.length) {
          this.say(`Opened "${this.name}", which has no stored conditions.`, false);
          return;
        }
        this.groups = this.groupsFromSaved(conds);
        this.say(`Opened "${this.name}" (#${this.strategyId}).`, true);
        this.revalidate();
      },
      error: () => this.say('Could not open that strategy.', false)
    });
  }

  /** Rebuild the canvas's groups from a saved legacy condition list. */
  private groupsFromSaved(conds: any[]): Group[] {
    const byIndex = new Map<string, Group>();
    conds.forEach(c => {
      const key = String(c.groupIndex ?? '1');
      if (!byIndex.has(key)) {
        const g = this.newGroup(String(c.groupMode).toUpperCase() === 'ANY' ? 'ANY' : 'ALL');
        g.joinNext = String(c.groupJoin).toUpperCase() === 'OR' ? 'OR' : 'AND';
        byIndex.set(key, g);
      }
      byIndex.get(key)!.conditions.push(this.conditionFromSaved(c));
    });
    const out = Array.from(byIndex.values());
    return out.length ? out : [this.newGroup('ALL'), this.newGroup('ANY')];
  }

  /** One saved condition back into a canvas row. */
  private conditionFromSaved(c: any): Cond {
    const row = this.newCondition(c.indicatorLeft || undefined);
    row.timeframe = c.timeframe || 'DAILY';
    row.leftKey = c.indicatorLeft || row.leftKey;
    row.leftParams = c.paramsLeft || this.defaultParams(row.leftKey);
    row.op = this.canvasOperator(c.comparator);
    row.rightKind = c.indicatorRight ? 'field' : 'value';
    row.rightKey = c.indicatorRight || row.rightKey;
    row.rightParams = c.paramsRight || this.defaultParams(row.rightKey);
    row.rightValue = c.valueRight === null || c.valueRight === undefined
      ? null : Number(c.valueRight);
    // BETWEEN's upper bound. Null for every other operator, so switching away from BETWEEN
    // hides the input without leaving a stale number behind it.
    row.rightValue2 = c.valueRightUpper === null || c.valueRightUpper === undefined
      ? null : Number(c.valueRightUpper);
    row.leftOffset = Math.max(0, Number(c.offsetLeft) || 0);
    row.rightOffset = Math.max(0, Number(c.offsetRight) || 0);
    return row;
  }

  /** The canvas's operator key for a stored comparator - the inverse of legacyComparator. */
  private canvasOperator(comparator: string): string {
    switch ((comparator || '').trim().toUpperCase()) {
      case '>': return 'GT';
      case '>=': return 'GTE';
      case '<': return 'LT';
      case '<=': return 'LTE';
      case '=': return 'EQ';
      case '!=': return 'NEQ';
      case 'CROSSABOVE': return 'CROSSES_ABOVE';
      case 'CROSSBELOW': return 'CROSSES_BELOW';
      case 'BETWEEN': return 'BETWEEN';
      // An operator this canvas does not offer is shown as-is rather than silently remapped,
      // so an unfamiliar saved rule is visible instead of quietly becoming something else.
      default: return comparator || 'GT';
    }
  }

  /** The spelling the existing screener expects, which is not the AST's. */
  private legacyComparator(op: string): string {
    switch (op) {
      case 'GT': return '>';
      case 'GTE': return '>=';
      case 'LT': return '<';
      case 'LTE': return '<=';
      case 'EQ': return '=';
      case 'NEQ': return '!=';
      case 'CROSSES_ABOVE': return 'CROSSABOVE';
      case 'CROSSES_BELOW': return 'CROSSBELOW';
      case 'BETWEEN': return 'BETWEEN';
      // Pass an unrecognised operator through unchanged rather than rewriting it as '>'.
      // Disguising it here made the backend evaluate a greater-than the author never wrote and
      // left nothing to report; passed through, the screener skips the condition and says why.
      default: return op;
    }
  }

  // ── validate / save / build ──────────────────────────────────────────────

  private revalidateTimer: any;

  /** Ask the server, debounced. Typing a period should not fire a request per keystroke. */
  revalidate(): void {
    clearTimeout(this.revalidateTimer);
    this.revalidateTimer = setTimeout(() => this.validateNow(), 450);
  }

  /**
   * BETWEEN's own validation, checked here rather than on the server.
   *
   * <p>The server validates the AST, which carries BETWEEN as a pair of literals and is happy
   * with any two numbers. The thing that goes wrong is upstream of that: a bound left empty, or
   * bounds entered the wrong way round. Both produce a rule the screener cannot honour - a
   * missing upper bound is skipped outright - so they are caught before the strategy is saved
   * rather than discovered as an empty result afterwards.
   */
  betweenIssues(): string[] {
    const out: string[] = [];
    this.groups.forEach((g, gi) => {
      g.conditions.forEach((c: any, ci: number) => {
        if (c.op !== 'BETWEEN' || c.rightKind !== 'value') return;
        const where = `Group ${gi + 1}, condition ${ci + 1}`;
        const lo = c.rightValue;
        const hi = c.rightValue2;
        const missing = (v: any) => v === null || v === undefined || v === '';
        if (missing(lo) && missing(hi)) {
          out.push(`${where}: BETWEEN needs a lower and an upper bound.`);
        } else if (missing(hi)) {
          out.push(`${where}: BETWEEN is missing its upper bound, so the condition would be skipped.`);
        } else if (missing(lo)) {
          out.push(`${where}: BETWEEN is missing its lower bound.`);
        } else if (Number(lo) > Number(hi)) {
          out.push(`${where}: the lower bound (${lo}) is above the upper bound (${hi}).`);
        }
      });
    });
    return out;
  }

  validateNow(): void {
    const ast = this.buildAst();
    if (!ast) { this.validation = null; return; }
    this.api.post<any>('/strategy-builder/validate', { rule_ast: ast }).subscribe({
      next: r => { this.validation = r?.data || null; },
      error: () => { this.validation = null; }
    });
  }

  private payload(): any {
    return {
      name: this.name?.trim() || 'My Custom Strategy',
      description: this.description,
      builderLevel: 2,
      universe: this.universe,
      timeframe: this.dominantTimeframe().replace(' (mixed)', ''),
      mode: this.mode,
      conditions: this.buildConditions(),
      ruleAst: this.buildAst(),
      stopLossPct: Number(this.stopLossPct) || 0,
      targetPct: Number(this.targetPct) || 0,
      rrMin: Number(this.rrMin) || 0,
      maxPositions: Number(this.maxPositions) || 5,
      holdingDays: Number(this.holdingDays) || 22,
      riskPerTradePct: Number(this.riskPerTradePct) || 0,
      tradeWindowStart: this.windowStart,
      tradeWindowEnd: this.windowEnd,
      tags: this.tags
    };
  }

  save(asNew: boolean): void {
    this.saveMenu = false;
    if (!this.conditionCount) { this.say('Add at least one condition first.', false); return; }
    // Refuse rather than save a BETWEEN the screener would skip; a saved rule that silently
    // does nothing is harder to notice than a save that explains itself.
    const bounds = this.betweenIssues();
    if (bounds.length) { this.say(bounds[0], false); return; }
    const body = this.payload();
    const req = (!asNew && this.strategyId)
      ? this.api.put<any>('/strategy-builder/strategies/' + this.strategyId, body)
      : this.api.post<any>('/strategy-builder/strategies', body);
    req.subscribe({
      next: r => {
        const s = r?.data;
        if (s?.id) this.strategyId = s.id;
        this.say(`Saved as "${body.name}"${this.strategyId ? ' (#' + this.strategyId + ')' : ''}.`, true);
      },
      error: e => this.say(e?.error?.message || 'Could not save the strategy.', false)
    });
  }

  /**
   * Build Strategy: validate, save, then screen.
   *
   * <p>In that order and stopping on the first failure, because a screener run against a rule
   * the validator has already refused produces an empty list that looks like "nothing qualifies"
   * rather than "this rule cannot be evaluated".
   */
  build(): void {
    if (!this.conditionCount) { this.say('Add at least one condition first.', false); return; }
    const ast = this.buildAst();
    this.building = true;
    this.api.post<any>('/strategy-builder/validate', { rule_ast: ast }).subscribe({
      next: v => {
        this.validation = v?.data || null;
        if (this.validation && this.validation.ok === false) {
          this.building = false;
          this.say('The rule has errors, so nothing was saved or screened. See the preview panel.',
                   false);
          return;
        }
        const body = this.payload();
        const req = this.strategyId
          ? this.api.put<any>('/strategy-builder/strategies/' + this.strategyId, body)
          : this.api.post<any>('/strategy-builder/strategies', body);
        req.subscribe({
          next: r => {
            if (r?.data?.id) this.strategyId = r.data.id;
            this.runScan(true);
          },
          error: e => {
            this.building = false;
            this.say(e?.error?.message || 'Could not save the strategy.', false);
          }
        });
      },
      error: () => {
        this.building = false;
        this.say('Could not reach the validator, so nothing was saved.', false);
      }
    });
  }

  runScan(fromBuild = false): void {
    if (!this.strategyId) { this.say('Save the strategy first.', false); this.building = false; return; }
    this.scanning = true;
    this.scanMessage = '';
    this.api.post<any>(`/strategy-builder/strategies/${this.strategyId}/scan?maxResults=50`, {})
      .subscribe({
        next: r => {
          const d = r?.data || {};
          this.scanRows = d.results || d.matches || [];
          this.scanning = false;
          this.building = false;
          this.scanMessage = this.scanRows.length
            ? ''
            : 'No stock in the ' + this.universeLabel() + ' universe met every group.';
          // Chart the top match straight away: a Chart tab that is empty until the reader
          // guesses a symbol is a tab nobody opens twice.
          if (this.scanRows.length && !this.chartSymbol) {
            this.chartSymbol = this.scanRows[0].symbol;
            this.loadChart();
          }
          this.tab = fromBuild ? 'preview' : this.tab;
          this.say(this.scanRows.length
            ? `${this.scanRows.length} stock(s) matched.`
            : 'Saved, and the screener found no match today.', true);
        },
        error: e => {
          this.scanning = false;
          this.building = false;
          this.scanMessage = e?.error?.message || 'The screener could not run.';
          this.say(this.scanMessage, false);
        }
      });
  }

  runBacktest(): void {
    if (!this.strategyId) { this.say('Save the strategy first.', false); return; }
    this.btRunning = true;
    this.btMessage = '';
    this.api.post<any>(`/strategy-builder/strategies/${this.strategyId}/backtest`,
                       { fromDate: this.btFrom, toDate: this.btTo }).subscribe({
      next: r => {
        const d = r?.data || {};
        const s = d.summary || d.report || d;
        this.backtest = {
          winRate: s.winRate ?? s.win_rate,
          totalReturn: s.totalReturn ?? s.total_return,
          maxDrawdown: s.maxDrawdown ?? s.max_drawdown,
          totalTrades: s.totalTrades ?? s.total_trades,
          profitFactor: s.profitFactor ?? s.profit_factor,
          avgReturn: s.avgReturn ?? s.avg_return,
          ranAt: new Date().toLocaleString()
        };
        this.btRunning = false;
      },
      error: e => {
        this.btRunning = false;
        this.backtest = null;
        this.btMessage = e?.error?.message || 'The backtest could not run.';
      }
    });
  }

  // ── chart ────────────────────────────────────────────────────────────────
  //
  // Drawn from /tracker/stock-history, which is the same stored daily series every condition on
  // this screen is evaluated against. Charting from anywhere else would let the picture and the
  // rule disagree about what a stock did.

  chartSymbol = '';
  chartDays = 90;
  chartCandles: Array<{ trade_date: string; close: number; volume: number }> = [];
  chartLoading = false;
  chartMessage = '';
  readonly chartEmaPeriod = 20;

  readonly CW = 900;   // viewBox units; the SVG scales to the panel
  readonly CH = 230;
  readonly CPAD = 8;
  private readonly CTOP = 10;
  private readonly CBOT = 172;   // price band ends here; volume occupies the strip below

  loadChart(): void {
    const sym = (this.chartSymbol || '').trim().toUpperCase();
    if (!sym) { this.chartMessage = 'Enter a symbol first.'; return; }
    this.chartSymbol = sym;
    this.chartLoading = true;
    this.chartMessage = '';
    this.api.get<any>('/tracker/stock-history', { symbol: sym, days: this.chartDays }).subscribe({
      next: r => {
        const c = (r?.data?.candles || []) as any[];
        this.chartCandles = c.filter(x => Number(x?.close) > 0);
        this.chartLoading = false;
        if (this.chartCandles.length < 2) {
          this.chartMessage = `Only ${this.chartCandles.length} stored session(s) for ${sym} — not enough to draw.`;
        }
      },
      error: e => {
        this.chartLoading = false;
        this.chartCandles = [];
        this.chartMessage = e?.error?.message || `No stored history for ${sym}.`;
      }
    });
  }

  /** Close and EMA share one scale, so crossings read correctly rather than decoratively. */
  private priceBand(): { lo: number; hi: number } {
    const vals: number[] = [];
    for (const c of this.chartCandles) vals.push(Number(c.close));
    for (const v of this.emaSeries()) if (v != null) vals.push(v);
    const lo = Math.min(...vals), hi = Math.max(...vals);
    const pad = (hi - lo) * 0.08 || 1;
    return { lo: lo - pad, hi: hi + pad };
  }

  private x(i: number): number {
    const n = Math.max(1, this.chartCandles.length - 1);
    return this.CPAD + (i / n) * (this.CW - this.CPAD - 48);
  }

  private y(v: number): number {
    const { lo, hi } = this.priceBand();
    const span = hi - lo || 1;
    return this.CBOT - ((v - lo) / span) * (this.CBOT - this.CTOP);
  }

  /** EMA over the same closes, seeded with the simple average of the first N. */
  private emaSeries(): Array<number | null> {
    const n = this.chartEmaPeriod;
    const closes = this.chartCandles.map(c => Number(c.close));
    const out: Array<number | null> = new Array(closes.length).fill(null);
    if (closes.length < n) return out;
    let sum = 0;
    for (let i = 0; i < n; i++) sum += closes[i];
    let prev = sum / n;
    out[n - 1] = prev;
    const k = 2 / (n + 1);
    for (let i = n; i < closes.length; i++) {
      prev = closes[i] * k + prev * (1 - k);
      out[i] = prev;
    }
    return out;
  }

  chartPath(which: 'close' | 'ema'): string {
    if (this.chartCandles.length < 2) return '';
    const vals: Array<number | null> = which === 'close'
      ? this.chartCandles.map(c => Number(c.close))
      : this.emaSeries();
    let d = '';
    let pen = false;
    vals.forEach((v, i) => {
      if (v == null) { pen = false; return; }
      const cmd = pen ? 'L' : 'M';
      d += `${cmd}${this.x(i).toFixed(1)},${this.y(v).toFixed(1)} `;
      pen = true;
    });
    return d.trim();
  }

  chartVolumeBars(): Array<{ x: number; y: number; w: number; h: number }> {
    const vols = this.chartCandles.map(c => Number(c.volume) || 0);
    const max = Math.max(...vols, 1);
    const strip = this.CH - 18 - this.CBOT - 4;
    const w = Math.max(1.5, (this.CW - this.CPAD - 48) / Math.max(1, vols.length) * 0.65);
    return vols.map((v, i) => {
      const h = Math.max(1, (v / max) * strip);
      return { x: this.x(i) - w / 2, y: this.CH - 18 - h, w, h };
    });
  }

  chartGrid(): Array<{ y: number; label: string }> {
    if (this.chartCandles.length < 2) return [];
    const { lo, hi } = this.priceBand();
    const out: Array<{ y: number; label: string }> = [];
    for (let i = 0; i <= 4; i++) {
      const v = lo + ((hi - lo) * i) / 4;
      out.push({ y: this.y(v), label: v >= 1000 ? Math.round(v).toString() : v.toFixed(1) });
    }
    return out;
  }

  chartDateTicks(): Array<{ x: number; label: string; anchor: string }> {
    const n = this.chartCandles.length;
    if (n < 2) return [];
    const at = (i: number, anchor: string) => ({
      x: this.x(i),
      label: this.shortDate(this.chartCandles[i].trade_date),
      anchor
    });
    return [at(0, 'start'), at(Math.floor((n - 1) / 2), 'middle'), at(n - 1, 'end')];
  }

  private shortDate(iso: string): string {
    if (!iso || iso.length < 10) return iso || '';
    const m = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    return `${Number(iso.slice(8, 10))} ${m[Number(iso.slice(5, 7)) - 1] || ''}`;
  }

  private say(msg: string, ok: boolean): void {
    this.toast = msg;
    this.toastOk = ok;
    setTimeout(() => { if (this.toast === msg) this.toast = ''; }, 7000);
  }
}
