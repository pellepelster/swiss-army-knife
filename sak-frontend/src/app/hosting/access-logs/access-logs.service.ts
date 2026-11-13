import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AccessLogs, SiteMetrics } from '../../api/models';

// the access logs of a bucket: its site and the ranges of the metrics, and the metrics of a range
export type { AccessLogs, SiteMetrics } from '../../api/models';

function accessLogsUrl(bucket: string): string {
  return `/api/v1/static-hosting/${encodeURIComponent(bucket)}/access-logs`;
}
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
