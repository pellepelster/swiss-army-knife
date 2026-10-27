import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { MAX_LIMIT, Page } from '../shared/api';

// where a redirect leads to, told apart by its type
export type RedirectTarget =
  { type: 'domain'; domain: string } | { type: 'bucket'; bucket: string };

// location is the domain the redirect leads to now, missing for a bucket no longer served at a
// domain
export interface Redirect {
  sourceDomain: string;
  target: RedirectTarget;
  location?: string | null;
  createdAt: string;
  updatedAt: string;
}

// the domains caddy redirects
@Injectable({ providedIn: 'root' })
export class RedirectsService {
  private readonly http = inject(HttpClient);

  // all of them, there are far less than a page of them
  redirects(): Observable<Redirect[]> {
    return this.http
      .get<Page<Redirect>>('/api/v1/redirects', { params: { limit: MAX_LIMIT } })
      .pipe(map((page) => page.items));
  }

  redirect(sourceDomain: string): Observable<Redirect> {
    return this.http.get<Redirect>(`/api/v1/redirects/${encodeURIComponent(sourceDomain)}`);
  }

  // where it leads to, the redirected domain can not be changed
  update(sourceDomain: string, target: RedirectTarget): Observable<Redirect> {
    return this.http.put<Redirect>(`/api/v1/redirects/${encodeURIComponent(sourceDomain)}`, {
      target,
    });
  }

  create(sourceDomain: string, target: RedirectTarget): Observable<Redirect> {
    return this.http.post<Redirect>('/api/v1/redirects', { sourceDomain, target });
  }

  delete(sourceDomain: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/redirects/${encodeURIComponent(sourceDomain)}`);
  }
}
