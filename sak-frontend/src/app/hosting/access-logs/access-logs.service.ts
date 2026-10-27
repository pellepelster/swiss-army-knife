import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable } from 'rxjs';
import { AuthService } from '../../auth/auth.service';
import { apiUrl } from '../../shared/api-url';

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

interface UserAgentSeries {
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

// one request, with the domain it was made to
export interface LogEntry {
  time: string;
  host: string;
  path: string;
  // 0 for requests caddy closed without an answer
  status: number | null;
}

export class LiveLogError extends Error {
  constructor(readonly status: number) {
    super(`the live log answered ${status}`);
  }
}

// the access logs of a bucket
function accessLogsUrl(bucket: string): string {
  return `/api/v1/static-hosting/${encodeURIComponent(bucket)}/access-logs`;
}

// the data of the server-sent events in the text, and what is left of an incomplete event
function parseEvents(text: string): { data: string[]; rest: string } {
  const events = text.split('\n\n');
  const rest = events.pop() ?? '';
  const data = events.flatMap((event) =>
    event
      .split('\n')
      .filter((line) => line.startsWith('data: '))
      .map((line) => line.slice('data: '.length)),
  );
  return { data, rest };
}

// the access logs of the buckets, of all the domains each one is served at
@Injectable({ providedIn: 'root' })
export class AccessLogsService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  accessLogs(bucket: string): Observable<AccessLogs> {
    return this.http.get<AccessLogs>(accessLogsUrl(bucket));
  }

  metrics(bucket: string, range: string): Observable<SiteMetrics> {
    return this.http.get<SiteMetrics>(`${accessLogsUrl(bucket)}/metrics`, { params: { range } });
  }

  // the entries of the last minutes, then every new one until unsubscribed, oldest first and in
  // the chunks they arrive in, the backlog comes in bursts; fetch instead of an EventSource, which
  // can not send the session token
  follow(bucket: string): Observable<LogEntry[]> {
    return new Observable<LogEntry[]>((subscriber) => {
      const abort = new AbortController();
      const token = this.auth.token();

      fetch(apiUrl(`${accessLogsUrl(bucket)}/live`), {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        signal: abort.signal,
      })
        .then(async (response) => {
          if (response.status === 401) {
            this.auth.logout();
            void this.router.navigate(['/login'], { queryParams: { redirect: this.router.url } });
          }
          if (!response.ok || !response.body) {
            throw new LiveLogError(response.status);
          }

          const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
          let buffered = '';
          for (;;) {
            const { value, done } = await reader.read();
            if (done) {
              break;
            }
            const { data, rest } = parseEvents(buffered + value);
            buffered = rest;
            if (data.length > 0) {
              subscriber.next(data.map((entry) => JSON.parse(entry) as LogEntry));
            }
          }
          subscriber.complete();
        })
        .catch((error: unknown) => {
          if (!abort.signal.aborted) {
            subscriber.error(error);
          }
        });

      return () => abort.abort();
    });
  }
}
