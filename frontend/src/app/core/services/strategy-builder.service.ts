import { Injectable } from '@angular/core';
import { ApiService } from './api.service';
import { Observable } from 'rxjs';

export const COMPARATORS = ['>', '>=', '<', '<=', '=', '!=', 'CROSSABOVE', 'CROSSBELOW'];
export const OPERATORS   = ['AND', 'OR', 'NOT'];
export const TIMEFRAMES  = ['1min','5min','15min','30min','1hour','Daily','Weekly','Monthly'];
export const UNIVERSES   = ['NSE_50','NSE_500','ALL_CASH','NIFTY_MIDCAP','FNO_STOCKS'];
export const CATEGORIES  = ['swing','intraday','positional','options_buy','options_sell','futures','commodity','currency'];
export const SEGMENTS    = ['EQ-SWG','EQ-INT','EQ-POS','FUT','OPT-CE','OPT-PE','IDX'];

export const WORKFLOW_STEPS = [
  { key:'draft',        label:'Draft',        icon:'📝', color:'text-slate-400' },
  { key:'testing',      label:'Testing',      icon:'🔬', color:'text-blue-400'  },
  { key:'backtesting',  label:'Backtesting',  icon:'📊', color:'text-yellow-400'},
  { key:'paper',        label:'Paper Trading',icon:'📋', color:'text-orange-400'},
  { key:'live_ready',   label:'Live Ready',   icon:'✅', color:'text-green-400' },
  { key:'production',   label:'Production',   icon:'🚀', color:'text-gold'      },
];

@Injectable({ providedIn: 'root' })
export class StrategyBuilderService {
  constructor(private api: ApiService) {}

  getIndicators(): Observable<any>             { return this.api.get('/strategy-builder/indicators'); }
  listStrategies(filter='mine',params?:any)    { return this.api.get('/strategy-builder/strategies', { filter, ...params }); }
  getStrategy(id: number): Observable<any>     { return this.api.get(`/strategy-builder/strategies/${id}`); }
  createStrategy(data: any): Observable<any>   { return this.api.post('/strategy-builder/strategies', data); }
  updateStrategy(id: number, data: any)        { return this.api.put(`/strategy-builder/strategies/${id}`, data); }
  deleteStrategy(id: number)                   { return this.api.delete(`/strategy-builder/strategies/${id}`); }
  advanceWorkflow(id: number, status: string)  { return this.api.put(`/strategy-builder/strategies/${id}/workflow`, { status }); }

  runScan(id: number, maxResults=50)           { return this.api.post(`/strategy-builder/strategies/${id}/scan?maxResults=${maxResults}`,{}); }
  getLastScan(id: number)                      { return this.api.get(`/strategy-builder/strategies/${id}/last-scan`); }

  runBacktest(id: number, data: any)           { return this.api.post(`/strategy-builder/strategies/${id}/backtest`, data); }
  listBacktests(id: number)                    { return this.api.get(`/strategy-builder/strategies/${id}/backtests`); }
  getBacktest(id: number)                      { return this.api.get(`/strategy-builder/backtests/${id}`); }
  runMonteCarlo(btId: number, sims=1000)       { return this.api.post(`/strategy-builder/backtests/${btId}/monte-carlo`, { simulations: sims }); }
  runWalkForward(id: number, data: any)        { return this.api.post(`/strategy-builder/strategies/${id}/walk-forward`, data); }

  generateAi(data: any): Observable<any>       { return this.api.post('/strategy-builder/ai/generate', data); }
  saveAiStrategy(data: any): Observable<any>   { return this.api.post('/strategy-builder/ai/save', data); }
  getNextDayModel(): Observable<any>           { return this.api.get('/strategy-builder/ai/next-day/model'); }
  runNextDayPrediction(data: any): Observable<any> { return this.api.post('/strategy-builder/ai/next-day/generate', data); }
  getLatestNextDayPrediction(): Observable<any> { return this.api.get('/strategy-builder/ai/next-day/latest'); }
  backtestNextDayPrediction(data: any): Observable<any> { return this.api.post('/strategy-builder/ai/next-day/backtest', data); }

  listAlerts(): Observable<any>                { return this.api.get('/strategy-builder/alerts'); }
  createAlert(id: number, data: any)           { return this.api.post(`/strategy-builder/strategies/${id}/alerts`, data); }
  toggleAlert(id: number, status: string)      { return this.api.put(`/strategy-builder/alerts/${id}/pause`, { status }); }
  getAlertHistory(id: number)                  { return this.api.get(`/strategy-builder/alerts/${id}/history`); }

  getMarketplace(params?: any)                 { return this.api.get('/strategy-builder/marketplace', params); }
  publishToMarketplace(id: number, data: any)  { return this.api.post(`/strategy-builder/strategies/${id}/marketplace/publish`, data); }
  copyStrategy(listingId: number)              { return this.api.post(`/strategy-builder/marketplace/${listingId}/copy`, {}); }
  rateStrategy(listingId: number, data: any)   { return this.api.post(`/strategy-builder/marketplace/${listingId}/rate`, data); }

  getHealth(id: number)                        { return this.api.get(`/strategy-builder/strategies/${id}/health`); }
  getPerformance(id: number)                   { return this.api.get(`/strategy-builder/strategies/${id}/performance`); }
  getVersions(id: number)                      { return this.api.get(`/strategy-builder/strategies/${id}/versions`); }
  restoreVersion(id: number, v: number)        { return this.api.post(`/strategy-builder/strategies/${id}/restore/${v}`, {}); }
}
