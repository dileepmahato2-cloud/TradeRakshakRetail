import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { map, tap } from 'rxjs/operators';
import { Observable, shareReplay, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { environment } from '../../../environments/environment';

export interface AuthUser {
  token: string; refreshToken: string;
  trUserId: string; name: string; email: string;
  role: string; userType: string;
  plan: string; planExpiry: string; planStatus: string;
  sebiDisclaimer: string;
}

export interface ApiResponse<T> {
  success: boolean; message: string; data: T; errorCode: string; disclaimer: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly TOKEN_KEY   = 'tr_token';
  private readonly REFRESH_KEY = 'tr_refresh';
  private readonly USER_KEY  = 'tr_user';
  private readonly API       = environment.apiUrl;

  currentUser = signal<AuthUser | null>(this.loadUser());

  constructor(private http: HttpClient, private router: Router) {}

  login(email: string, password: string, consent: boolean) {
    return this.http.post<ApiResponse<AuthUser>>(`${this.API}/auth/login`,
      { email, password, consent }).pipe(
      tap(res => { if (res.success && res.data) this.storeUser(res.data); }));
  }

  register(payload: any) {
    return this.http.post<ApiResponse<AuthUser>>(`${this.API}/auth/register`, payload).pipe(
      tap(res => { if (res.success && res.data) this.storeUser(res.data); }));
  }

  logout() {
    this.http.post(`${this.API}/auth/logout`, {}).subscribe();
    localStorage.removeItem(this.TOKEN_KEY);
    localStorage.removeItem(this.REFRESH_KEY);
    localStorage.removeItem(this.USER_KEY);
    this.refresh$ = null;
    this.currentUser.set(null);
    this.router.navigate(['/login']);
  }

  getRefreshToken(): string | null { return localStorage.getItem(this.REFRESH_KEY); }

  /** In-flight refresh, shared so parallel 401s trigger only one /auth/refresh call. */
  private refresh$: Observable<string> | null = null;

  /**
   * Exchanges the stored refresh token for a new access token. Emits the new token, or
   * errors if there is nothing to refresh with (caller then logs out).
   */
  refreshSession(): Observable<string> {
    if (this.refresh$) return this.refresh$;
    const refreshToken = this.getRefreshToken();
    if (!refreshToken) return throwError(() => new Error('No refresh token'));

    this.refresh$ = this.http
      .post<ApiResponse<AuthUser>>(`${this.API}/auth/refresh`, { refreshToken })
      .pipe(
        map(res => {
          if (!res.success || !res.data?.token) throw new Error('Refresh rejected');
          this.storeUser(res.data);
          return res.data.token;
        }),
        catchError(err => { this.refresh$ = null; return throwError(() => err); }),
        tap({ complete: () => { this.refresh$ = null; } }),
        shareReplay({ bufferSize: 1, refCount: false })
      );
    return this.refresh$;
  }

  getToken(): string | null { return localStorage.getItem(this.TOKEN_KEY); }
  isLoggedIn(): boolean     { return !!this.getToken(); }

  // Hierarchy helpers
  getUserType(): string { return this.currentUser()?.userType || this.currentUser()?.role || ''; }

  isAdmin(): boolean {
    const t = this.getUserType().toLowerCase();
    return ['super_admin','platform_admin','institution_admin','admin'].includes(t);
  }
  isSuperAdmin(): boolean {
    const t = this.getUserType();
    return ['super_admin','platform_admin'].includes(t);
  }
  isComplianceOfficer(): boolean { return this.getUserType() === 'compliance_officer'; }
  isResearchAnalyst(): boolean   { return this.getUserType() === 'research_analyst'; }
  isTrainer(): boolean           { return this.getUserType() === 'trainer'; }
  isSalesManager(): boolean      { return this.getUserType() === 'sales_manager'; }
  isSupportExecutive(): boolean  { return this.getUserType() === 'support_executive'; }
  isAgent(): boolean             { return this.getUserType().startsWith('agent_'); }
  isLevel1(): boolean            { return this.getUserType() === 'level_1'; }
  isLevel2(): boolean            { return this.getUserType() === 'level_2'; }
  isLevel3(): boolean            { return this.getUserType() === 'level_3'; }

  canManageUsers(): boolean {
    const t = this.getUserType();
    return ['super_admin','platform_admin','admin','institution_admin',
            'sales_manager','level_1','level_2'].includes(t);
  }

  getInstitutionRole(): 'admin' | 'facilitator' | 'teacher' | 'student' {
    const t = this.getUserType().toLowerCase();
    if (['super_admin','platform_admin','admin','institution_admin'].includes(t)) return 'admin';
    if (['trainer','sales_manager'].includes(t)) return 'facilitator';
    if (['level_1','level_2','research_analyst','compliance_officer','support_executive'].includes(t)) return 'teacher';
    return 'student';
  }

  isFacilitator(): boolean { return this.getInstitutionRole() === 'facilitator'; }
  isStudentRole(): boolean { return this.getInstitutionRole() === 'student'; }
  isTeacherRole(): boolean { return this.getInstitutionRole() === 'teacher'; }

  private storeUser(u: AuthUser) {
    localStorage.setItem(this.TOKEN_KEY, u.token);
    // The refresh token used to be dropped on the floor, which is why every session died
    // at the 30-minute access-token expiry.
    if (u.refreshToken) localStorage.setItem(this.REFRESH_KEY, u.refreshToken);
    localStorage.setItem(this.USER_KEY, JSON.stringify(u));
    this.currentUser.set(u);
  }

  private loadUser(): AuthUser | null {
    try { const s = localStorage.getItem(this.USER_KEY); return s ? JSON.parse(s) : null; }
    catch { return null; }
  }
}
