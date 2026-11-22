import { inject, Injectable } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { apiUrl, SERVICE_WORKER_BYPASS } from './api-url';

// requests the browser finishes after the page is gone (fetch with keepalive), e.g. what is pending
// when the tab closes; HttpClient requests end with the page
@Injectable({ providedIn: 'root' })
export class Keepalive {
  private readonly auth = inject(AuthService);

  // the body goes as json; resolves whether the backend took it, false also when the request did not
  // reach it. The answer often comes too late for the page, nothing has to wait for it
  async send(path: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<boolean> {
    const token = this.auth.token();
    try {
      const response = await fetch(apiUrl(path), {
        method,
        keepalive: true,
        headers: {
          ...SERVICE_WORKER_BYPASS,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      return response.ok;
    } catch {
      return false;
    }
  }
}
