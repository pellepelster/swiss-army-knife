import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// a bucket served at its domains, the site its access logs are kept under
interface Site {
  id: string;
  domains: string[];
}

interface MetricsRange {
  key: string;
  label: string;
}

// the access logs of a bucket: its site and the ranges of the metrics
export interface AccessLogs {
  site: Site;
  ranges: MetricsRange[];
  defaultRange: string;
}

interface StatusClassSeries {
  statusClass: string;
  values: number[];
  total: number;
}

interface StatusCodeSeries {
  status: string;
  statusClass: string;
  values: number[];
  total: number;
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
  totalRequests: number;
  errorRequests: number;
  errorRate: number;
  totalBytes: number;
}

// the access logs of a bucket
function accessLogsUrl(bucket: string): string {
  return `/api/v1/static-hosting/${encodeURIComponent(bucket)}/access-logs`;
}

// the access logs of the buckets, of all the domains each one is served at
@Injectable({ providedIn: 'root' })
export class AccessLogsService {
  private readonly http = inject(HttpClient);

  accessLogs(bucket: string): Observable<AccessLogs> {
    return this.http.get<AccessLogs>(accessLogsUrl(bucket));
  }

  metrics(bucket: string, range: string): Observable<SiteMetrics> {
    return this.http.get<SiteMetrics>(`${accessLogsUrl(bucket)}/metrics`, { params: { range } });
  }
}
