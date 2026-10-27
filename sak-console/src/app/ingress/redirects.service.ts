import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// where a redirect leads to, told apart by its type
export type RedirectTarget = { type: 'domain'; domain: string } | { type: 'bucket'; bucket: string };

// location is the domain the redirect leads to now, missing for a bucket no longer served at a domain
export interface Redirect {
  sourceDomain: string;
  target: RedirectTarget;
  location?: string | null;
  createdAt: string;
}

// labels of letters, digits and hyphens, at least two of them, as the controller accepts them
export const DOMAIN_PATTERN = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/i;

@Injectable({ providedIn: 'root' })
export class RedirectsService {
  private readonly http = inject(HttpClient);

  redirects(): Observable<Redirect[]> {
    return this.http.get<Redirect[]>('/api/v1/redirects');
  }

  create(sourceDomain: string, target: RedirectTarget): Observable<Redirect> {
    return this.http.post<Redirect>('/api/v1/redirects', { sourceDomain, target });
  }

  delete(sourceDomain: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/redirects/${encodeURIComponent(sourceDomain)}`);
  }
}
