import { HttpClient } from '@angular/common/http';
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

// the sites only change with the controller configuration, they are loaded once
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
      next: (sites) => this.sites.set(sites),
      error: () => (this.loading = false),
      complete: () => (this.loading = false),
    });
  }

  metrics(site: string, range: string): Observable<SiteMetrics> {
    return this.http.get<SiteMetrics>(`/api/v1/logs/${encodeURIComponent(site)}/metrics`, {
      params: { range },
    });
  }
}
