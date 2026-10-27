import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, map } from 'rxjs';
import { MAX_LIMIT, Page } from '../../shared/api';
import { AuthService } from '../../auth/auth.service';

export interface LogEntry {
  time: string;
  site: string;
  path: string;
  // 0 for requests caddy closed without an answer
  status: number | null;
}

// stands for every site
export const ALL_SITES = 'all';

export class LiveLogError extends Error {
  constructor(readonly status: number) {
    super(`the live log answered ${status}`);
  }
}

// the data of the server-sent events in the text, and what is left of an incomplete event
export function parseEvents(text: string): { data: string[]; rest: string } {
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

@Injectable({ providedIn: 'root' })
export class LogsService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  // the newest entries containing the text, as many as a page holds; the live view pages through
  // them itself
  search(site: string, text: string): Observable<LogEntry[]> {
    return this.http
      .get<Page<LogEntry>>(`/api/v1/logs/${encodeURIComponent(site)}/search`, {
        params: { q: text, limit: MAX_LIMIT },
      })
      .pipe(map((page) => page.items));
  }

  // the entries of the last minutes, then every new one until unsubscribed, oldest first and in
  // the chunks they arrive in, the backlog comes in bursts; fetch instead of an EventSource, which
  // can not send the session token; opened is called once the stream is open
  follow(site: string, opened: () => void = () => undefined): Observable<LogEntry[]> {
    return new Observable<LogEntry[]>((subscriber) => {
      const abort = new AbortController();
      const token = this.auth.token();

      fetch(`/api/v1/logs/${encodeURIComponent(site)}/live`, {
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

          opened();
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
