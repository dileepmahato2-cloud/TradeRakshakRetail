import { Injectable } from '@angular/core';
import { ApiService } from './api.service';
import { Observable } from 'rxjs';

export interface UserTypeDef {
  typeCode: string;
  displayName: string;
  hierarchyLevel: number;
  description: string;
}

export const USER_TYPE_META: Record<string, { icon: string; color: string; label: string }> = {
  super_admin:        { icon: '👑', color: 'text-red-400',    label: 'Super Admin' },
  platform_admin:     { icon: '🔧', color: 'text-orange-400', label: 'Platform Admin' },
  admin:              { icon: '⚙️', color: 'text-yellow-400', label: 'Admin' },
  institution_admin:  { icon: '🏛',  color: 'text-blue-400',   label: 'Institution Admin' },
  compliance_officer: { icon: '⚖️', color: 'text-purple-400', label: 'Compliance Officer' },
  research_analyst:   { icon: '🔬', color: 'text-cyan-400',   label: 'Research Analyst' },
  trainer:            { icon: '📚', color: 'text-green-400',  label: 'Trainer' },
  sales_manager:      { icon: '💼', color: 'text-indigo-400', label: 'Sales Manager' },
  support_executive:  { icon: '🎧', color: 'text-teal-400',   label: 'Support Executive' },
  level_1:            { icon: '🌟', color: 'text-amber-400',  label: 'Level 1 (Regional Head)' },
  level_2:            { icon: '⭐', color: 'text-amber-300',  label: 'Level 2 (Team Manager)' },
  level_3:            { icon: '✨', color: 'text-amber-200',  label: 'Level 3 (RM/Executive)' },
  agent_l1:           { icon: '🤝', color: 'text-lime-400',   label: 'Agent Level 1' },
  agent_l2:           { icon: '🤝', color: 'text-lime-500',   label: 'Agent Level 2 (Master)' },
  agent_l3:           { icon: '🤝', color: 'text-lime-600',   label: 'Agent Level 3 (Distributor)' },
  paid_subscriber:    { icon: '💎', color: 'text-sky-400',    label: 'Paid Subscriber' },
  free_trial:         { icon: '🆓', color: 'text-slate-400',  label: 'Free Trial' },
};

@Injectable({ providedIn: 'root' })
export class UserHierarchyService {
  constructor(private api: ApiService) {}

  getTypes(): Observable<any>         { return this.api.get('/user-hierarchy/types'); }
  getTree(): Observable<any>          { return this.api.get('/user-hierarchy/tree'); }
  getSummary(): Observable<any>       { return this.api.get('/user-hierarchy/summary'); }
  listUsers(params?: any): Observable<any> { return this.api.get('/user-hierarchy/users', params); }
  getUser(id: number): Observable<any> { return this.api.get(`/user-hierarchy/users/${id}`); }
  getMyPermissions(): Observable<any> { return this.api.get('/user-hierarchy/my-permissions'); }

  createUser(req: any): Observable<any>           { return this.api.post('/user-hierarchy/users', req); }
  bulkCreate(req: any[]): Observable<any>         { return this.api.post('/user-hierarchy/users/bulk', req); }
  suspendUser(id: number, reason: string)         { return this.api.post(`/user-hierarchy/users/${id}/suspend`, { reason }); }
  activateUser(id: number)                        { return this.api.post(`/user-hierarchy/users/${id}/activate`, {}); }
  blockUser(id: number, reason: string)           { return this.api.post(`/user-hierarchy/users/${id}/block`, { reason }); }
  unlockUser(id: number)                          { return this.api.post(`/user-hierarchy/users/${id}/unlock`, {}); }
  approveUser(id: number)                         { return this.api.post(`/user-hierarchy/users/${id}/approve`, {}); }
  resetPassword(id: number, pw?: string)          { return this.api.post(`/user-hierarchy/users/${id}/reset-password`, { newPassword: pw }); }
  changeType(id: number, userType: string)        { return this.api.post(`/user-hierarchy/users/${id}/change-type`, { userType }); }
  transferUser(id: number, newParentId: number)   { return this.api.post(`/user-hierarchy/users/${id}/transfer`, { newParentId }); }
  generateApiKey(id: number)                      { return this.api.post(`/user-hierarchy/users/${id}/generate-api-key`, {}); }
  bulkUpdatePlan(userIds: number[], plan: string) { return this.api.post('/user-hierarchy/users/bulk/plan', { userIds, plan }); }
  bulkSuspend(userIds: number[], reason: string)  { return this.api.post('/user-hierarchy/users/bulk/suspend', { userIds, reason }); }

  getTypeMeta(type: string) { return USER_TYPE_META[type] || { icon: '👤', color: 'text-slate-400', label: type }; }
}
