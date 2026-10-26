import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';

export interface Site {
  id: string;
  domains: string[];
}

export interface MetricsRange {
  key: string;
  label: string;
}

export interface SitesResponse {
  sites: Site[];
  ranges: MetricsRange[];
  defaultRange: string;
}

export interface StatusClassSeries {
  statusClass: string;
  values: number[];
  total: number;
}

export interface StatusCodeSeries {
  status: string;
  statusClass: string;
  values: number[];
  total: number;
}

export interface UserAgentSeries {
  group: string;
  values: number[];
  requests: number;
}

export interface SiteMetrics {
  site: string;
  range: string;
  start: string;
  end: string;
  stepSeconds: number;
  buckets: string[];
  series: StatusClassSeries[];
  statusCodes: StatusCodeSeries[];
  bytes: number[];
  totalRequests: number;
  errorRequests: number;
  errorRate: number;
  totalBytes: number;
  userAgents: UserAgentSeries[];
}

// after a failed load
export const SITES_RETRY_DELAY_MS = 5000;

// the sites only change with the controller configuration, they are loaded once, and again
// after a while when that failed; a rejected session ends the loading, the login follows
@Injectable({ providedIn: 'root' })
export class SitesService {
  private readonly http = inject(HttpClient);

  readonly sites = signal<SitesResponse | null>(null);

  private loading = false;

  load(): void {
    if (this.sites() || this.loading) {
      return;
    }
    this.loading = true;
    this.http.get<SitesResponse>('/api/v1/logs').subscribe({
      next: (sites) => {
        this.loading = false;
        this.sites.set(sites);
      },
      error: (error: unknown) => {
        this.loading = false;
        if (!(error instanceof HttpErrorResponse && error.status === 401)) {
          setTimeout(() => this.load(), SITES_RETRY_DELAY_MS);
        }
      },
    });
  }

  metrics(site: string, range: string): Observable<SiteMetrics> {
    return this.http.get<SiteMetrics>(`/api/v1/logs/${encodeURIComponent(site)}/metrics`, {
      params: { range },
    });
  }
}
