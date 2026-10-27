import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService, PUBLIC_API_URLS } from './auth.service';

// sends the session token with requests to the backend's api only, a rejected token
// ends the session and leads back to the login
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/v1/') || PUBLIC_API_URLS.includes(request.url)) {
    return next(request);
  }

  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.token();
  if (!token) {
    return next(request);
  }

  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })).pipe(
    catchError((error: unknown) => {
      // a page asks for several things at once, the first rejection leads to the login
      if (error instanceof HttpErrorResponse && error.status === 401 && auth.token()) {
        auth.logout();
        void router.navigate(['/login'], { queryParams: { redirect: router.url } });
      }
      return throwError(() => error);
    }),
  );
};
