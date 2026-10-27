import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { MAX_LIMIT, Page } from '../shared/api';

// where a redirect leads to, told apart by its type
export type RedirectTarget = { type: 'domain'; domain: string } | { type: 'bucket'; bucket: string };

// location is the domain the redirect leads to now, missing for a bucket no longer served at a domain
export interface Redirect {
  sourceDomain: string;
  target: RedirectTarget;
  location?: string | null;
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class RedirectsService {
  private readonly http = inject(HttpClient);

  // all of them, the console has far less than a page of them
  redirects(): Observable<Redirect[]> {
    return this.http
      .get<Page<Redirect>>('/api/v1/redirects', { params: { limit: MAX_LIMIT } })
      .pipe(map((page) => page.items));
  }

  create(sourceDomain: string, target: RedirectTarget): Observable<Redirect> {
    return this.http.post<Redirect>('/api/v1/redirects', { sourceDomain, target });
  }

  delete(sourceDomain: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/redirects/${encodeURIComponent(sourceDomain)}`);
  }
}
