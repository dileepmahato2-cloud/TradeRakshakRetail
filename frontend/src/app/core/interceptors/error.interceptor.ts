import { HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);

  return next(req).pipe(
    catchError(err => {
      if (err.status !== 401 || !shouldAutoLogout(req.url)) {
        return throwError(() => err);
      }
      // A 401 now means "access token expired", not "session over": spend the refresh
      // token on a new access token and replay the request once. Only a failed refresh
      // (or no refresh token at all) ends the session.
      if (isAuthCall(req.url) || !auth.getRefreshToken()) {
        auth.logout();
        return throwError(() => err);
      }
      return auth.refreshSession().pipe(
        switchMap(token => next(withToken(req, token))),
        catchError(refreshErr => { auth.logout(); return throwError(() => refreshErr); })
      );
    })
  );
};

function withToken(req: HttpRequest<unknown>, token: string): HttpRequest<unknown> {
  return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

function localPath(url: string): string {
  return url.replace(/^https?:\/\/[^/]+/i, '');
}

/** /auth/* must never trigger a refresh-retry loop. */
function isAuthCall(url: string): boolean {
  return localPath(url).startsWith('/api/auth/');
}

function shouldAutoLogout(url: string): boolean {
  return !localPath(url).startsWith('/api/broker/');
}
