import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
@Component({ selector:'app-unusual-alerts', standalone:true, imports:[CommonModule,FormsModule],
  template:`
<div class="p-6">
  <h1 class="section-title">🚨 Unusual Activity Detection</h1>
  <p class="text-slate-400 text-sm mb-5">18 detection types: volume spike, delivery spike, OI change, circuit, bulk/block deals, promoter activity.</p>
  <div class="flex gap-3 mb-5">
    <select [(ngModel)]="days" (ngModelChange)="load()" class="input w-24 text-sm"><option [value]="1">1d</option><option [value]="7">7d</option><option [value]="30">30d</option></select>
    <select [(ngModel)]="severity" (ngModelChange)="load()" class="input w-28 text-sm"><option value="">All</option><option value="critical">Critical</option><option value="high">High</option><option value="medium">Medium</option></select>
  </div>
  @if(loading()){<div class="text-center text-slate-400 py-12">Loading…</div>}
  @if(!loading()&&data){
    <div class="grid sm:grid-cols-3 gap-4 mb-5">
      @for(entry of byTypeEntries;track entry){
        <div class="card text-center"><div class="text-xl font-bold text-orange-400">{{entry.count}}</div><div class="text-xs text-slate-400 mt-1 capitalize">{{entry.type.replace('_',' ')}}</div></div>
      }
    </div>
    <div class="card"><div class="card-header">🚨 Unusual Activity ({{data.total}} alerts, {{data.period}})</div>
      <div class="overflow-x-auto"><table class="table-base">
        <thead><tr><th>Symbol</th><th>Type</th><th>Severity</th><th>Value</th><th>Ratio</th><th>Message</th><th>Date</th></tr></thead>
        <tbody>@for(a of data.activities.slice(0,50);track a){
          <tr>
            <td class="font-bold text-white">{{a[3]}}</td>
            <td class="text-xs capitalize">{{a[4]?.replace('_',' ')}}</td>
            <td><span class="badge text-xs" [ngClass]="a[5]==='critical'?'badge-red':a[5]==='high'?'badge-yellow':'badge-blue'">{{a[5]}}</span></td>
            <td class="text-right">{{a[6]|number:'1.0-0'}}</td>
            <td class="text-right text-orange-400">{{a[8]|number:'1.1-1'}}×</td>
            <td class="text-xs text-slate-400">{{a[9]|slice:0:60}}</td>
            <td class="text-xs text-slate-500">{{a[11]}}</td>
          </tr>
        }</tbody>
      </table></div>
    </div>
  }
</div>` })
export class UnusualAlertsComponent implements OnInit {
  loading=signal(true); data:any=null; days=7; severity='';
  get byTypeEntries(){return Object.entries(this.data?.byType||{}).map(([type,count])=>({type,count}));}
  constructor(private api:ApiService){}
  ngOnInit(){this.load();}
  load(){this.loading.set(true);this.api.get<any>('/enterprise/compliance/unusual-activity',{days:this.days,severity:this.severity||''}).subscribe({next:r=>{this.data=r.data;this.loading.set(false);},error:()=>this.loading.set(false)});}
}
