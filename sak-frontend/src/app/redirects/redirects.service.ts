import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Redirect, RedirectTarget } from '../api/models';
import { Page, PAGE_SIZE } from '../shared/api';

// a target is told apart by its type; location is the domain it leads to now, missing for a
// bucket served at no domain
export type { Redirect, RedirectTarget } from '../api/models';

// the domains caddy redirects
@Injectable({ providedIn: 'root' })
export class RedirectsService {
  private readonly http = inject(HttpClient);

  page(offset: number): Observable<Page<Redirect>> {
    return this.http.get<Page<Redirect>>('/api/v1/redirects', {
      params: { offset, limit: PAGE_SIZE },
    });
  }

  redirect(sourceDomain: string): Observable<Redirect> {
    return this.http.get<Redirect>(`/api/v1/redirects/${encodeURIComponent(sourceDomain)}`);
  }

  // only the target, the source domain can not be changed
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
