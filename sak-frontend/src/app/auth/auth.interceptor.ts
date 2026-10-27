import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService, PUBLIC_API_PREFIX } from './auth.service';

// sends the session token with requests to the backend's api only, a rejected token
// ends the session and leads back to the login
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/v1/') || request.url.startsWith(PUBLIC_API_PREFIX)) {
    return next(request);
  }

  const auth = inject(AuthService);
  const token = auth.token();
  if (!token) {
    return next(request);
  }

  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) auth.sessionRejected();
      return throwError(() => error);
    }),
  );
};
