import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class ApiService {

  private readonly base = environment.apiUrl;

  constructor(private http: HttpClient) {}

  get<T = any>(path: string, params?: Record<string, string | number>): Observable<T> {
    let p = new HttpParams();
    if (params) Object.entries(params).forEach(([k, v]) => p = p.set(k, String(v)));
    return this.http.get<T>(`${this.base}${path}`, { params: p });
  }

  post<T = any>(path: string, body: unknown): Observable<T> {
    return this.http.post<T>(`${this.base}${path}`, body);
  }

  put<T = any>(path: string, body: unknown): Observable<T> {
    return this.http.put<T>(`${this.base}${path}`, body);
  }

  patch<T = any>(path: string, body: unknown): Observable<T> {
    return this.http.patch<T>(`${this.base}${path}`, body);
  }

  delete<T = any>(path: string): Observable<T> {
    return this.http.delete<T>(`${this.base}${path}`);
  }

  upload<T = any>(path: string, formData: FormData): Observable<T> {
    return this.http.post<T>(`${this.base}${path}`, formData);
  }
}
