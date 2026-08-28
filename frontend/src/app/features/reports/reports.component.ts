import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { InrPipe } from '../../shared/pipes/inr.pipe';
@Component({ selector:'app-reports', standalone:true, imports:[CommonModule,FormsModule,InrPipe],
  template:`
<div class="p-6">
  <h1 class="section-title">📊 Reports & Analytics</h1>
  @if(loading()){<div class="text-center text-slate-400 py-12">Loading…</div>}
  @if(!loading()&&dashboard){
    <div class="grid grid-cols-2 sm:grid-cols-4 gap-4 mb-6">
      <div class="card text-center"><div class="text-2xl font-bold text-blue-400">{{dashboard.totalUsers|number}}</div><div class="text-xs text-slate-400 mt-1">Total Users</div></div>
      <div class="card text-center"><div class="text-2xl font-bold text-gold">₹{{dashboard.totalRevenue|inr:true}}</div><div class="text-xs text-slate-400 mt-1">Total Revenue</div></div>
      <div class="card text-center"><div class="text-2xl font-bold text-green-400">{{dashboard.totalSubscriptions|number}}</div><div class="text-xs text-slate-400 mt-1">Active Subs</div></div>
      <div class="card text-center"><div class="text-2xl font-bold text-red-400">{{dashboard.unusualAlerts|number}}</div><div class="text-xs text-slate-400 mt-1">Unusual Alerts</div></div>
    </div>
    <div class="card"><div class="card-header">👥 User Report</div>
      <div class="overflow-x-auto"><table class="table-base">
        <thead><tr><th>TR ID</th><th>Name</th><th>Type</th><th>Plan</th><th>Status</th><th>Created</th></tr></thead>
        <tbody>@for(u of users.slice(0,50);track u){
          <tr><td class="font-mono text-xs text-blue-300">{{u[1]}}</td>
            <td class="font-medium text-white">{{u[2]}}</td>
            <td class="text-xs capitalize">{{u[5]}}</td>
            <td><span class="badge badge-blue text-xs">{{u[6]}}</span></td>
            <td><span class="badge text-xs" [ngClass]="u[9]==='active'?'badge-green':'badge-yellow'">{{u[9]}}</span></td>
            <td class="text-xs text-slate-500">{{u[10]|slice:0:10}}</td></tr>
        }</tbody>
      </table></div>
    </div>
  }
</div>` })
export class ReportsComponent implements OnInit {
  loading=signal(true); dashboard:any=null; users:any[]=[];
  constructor(private api:ApiService){}
  ngOnInit(){
    this.api.get<any>('/enterprise/reports/dashboard').subscribe({next:r=>{this.dashboard=r.data;},error:()=>{}});
    this.api.get<any>('/enterprise/reports/user-report').subscribe({next:r=>{this.users=r.data||[];this.loading.set(false);},error:()=>this.loading.set(false)});
  }
}
