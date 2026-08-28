import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { InrPipe } from '../../shared/pipes/inr.pipe';

@Component({
  selector: 'app-competitions',
  standalone: true,
  imports: [CommonModule, FormsModule, InrPipe],
  template: `
<div class="p-6">
  <h1 class="section-title">🏆 Competitions</h1>
  <p class="text-slate-400 text-sm mb-2">Win cash prizes, subscription extensions, certificates and digital badges.</p>
  <div class="bg-amber-900/20 border border-amber-700/40 rounded-lg px-4 py-2 mb-5 text-xs text-amber-300">
    ⚠ All competition trades are paper/virtual. TradeRakshak™ is NOT a SEBI-registered Investment Advisor.
  </div>
  @if (loading()) { <div class="text-center text-slate-400 py-12">Loading…</div> }
  @if (!loading()) {
    <div class="card mb-5">
      <div class="card-header">📋 Active Competitions</div>
      @for (c of competitions; track c; let i=$index) {
        <div class="border border-slate-700 rounded-xl p-4 mb-3">
          <div class="flex items-start justify-between flex-wrap gap-3">
            <div><div class="font-semibold text-white">{{ c[2] }}</div>
              <div class="text-xs text-slate-400 mt-1">{{ c[3] }}</div>
              <div class="text-xs text-slate-500 mt-1">{{ c[7] }} → {{ c[8] }} · Entry: ₹{{ c[9] | inr }} · Prize: ₹{{ c[10] | inr }}</div></div>
            <div class="flex gap-2">
              <button (click)="register(c[0])" class="btn-primary text-xs py-1 px-3">Register</button>
              <button (click)="viewLB(c[0])" class="btn-secondary text-xs py-1 px-3">Leaderboard</button>
            </div>
          </div>
        </div>
      }
      @if (!competitions.length) { <p class="text-slate-500 text-center py-8">No active competitions. Check back soon!</p> }
    </div>
    @if (lb) {
      <div class="card">
        <div class="card-header">🥇 Leaderboard <button (click)="lb=null" class="text-slate-400 ml-2">✕</button></div>
        <table class="table-base">
          <thead><tr><th>Rank</th><th>Trader</th><th class="text-right">Score</th><th class="text-right">Value</th></tr></thead>
          <tbody>
            @for (r of lb.board; track r; let i=$index) {
              <tr><td class="font-bold">{{i===0?'🥇':i===1?'🥈':i===2?'🥉':i+1}}</td>
                <td class="font-medium text-white">{{ r[1] }}</td>
                <td class="text-right text-gold">{{ r[3] | number:'1.1-1' }}</td>
                <td class="text-right text-green-400">₹{{ r[4] | inr }}</td></tr>
            }
          </tbody>
        </table>
      </div>
    }
    @if (msg()) { <div class="bg-green-900/40 border border-green-700 text-green-300 rounded-lg px-4 py-3 mt-4">{{ msg() }}</div> }
  }
</div>`
})
export class CompetitionsComponent implements OnInit {
  loading = signal(true); msg = signal('');
  competitions: any[] = []; lb: any = null;
  constructor(private api: ApiService) {}
  ngOnInit() { this.api.get<any>('/enterprise/competitions').subscribe({ next: r => { this.competitions = r.data||[]; this.loading.set(false); }, error: ()=>this.loading.set(false) }); }
  register(id:number) { this.api.post<any>(`/enterprise/competitions/${id}/register`,{}).subscribe({ next: r=>this.msg.set(r.message||'Registered!'), error: e=>this.msg.set(e.error?.message||'Failed.') }); }
  viewLB(id:number) { this.api.get<any>(`/enterprise/competitions/${id}/leaderboard`).subscribe({ next: r=>this.lb=r.data }); }
}
