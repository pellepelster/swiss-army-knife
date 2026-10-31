import { DOCUMENT } from '@angular/common';
import { inject, Injectable } from '@angular/core';
import {
  distinctUntilChanged,
  EMPTY,
  fromEvent,
  map,
  Observable,
  retry,
  startWith,
  switchMap,
  timer,
} from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { apiUrl, SERVICE_WORKER_BYPASS } from '../shared/api-url';

// a change of everything shown: the stream was away, while the tab was hidden or the connection lost,
// changes may have been missed
export const EVERYTHING = null;

// the longest wait before the stream is opened again
const MAX_RETRY_DELAY_MS = 30_000;

// changes that come together are loaded once, a page waits this long after the last one
export const CHANGES_DEBOUNCE_MS = 300;

// the changes of the grocery lists the user sees, as the backend sends them (LIST-4): the id of a list
// that changed, EVERYTHING after the stream was away. It listens only while the tab is visible; a
// fetch and not an EventSource, the session goes in the header, never in the url
@Injectable({ providedIn: 'root' })
export class GroceryChanges {
  private readonly auth = inject(AuthService);
  private readonly document = inject(DOCUMENT);

  // the ids of the lists that changed, or EVERYTHING
  changes(): Observable<number | null> {
    // after the first connection, every new one may have missed changes
    let connected = false;
    return fromEvent(this.document, 'visibilitychange').pipe(
      startWith(null),
      map(() => this.document.visibilityState === 'visible'),
      distinctUntilChanged(),
      switchMap((visible) =>
        visible
          ? this.stream(() => {
              const missed = connected;
              connected = true;
              return missed;
            }).pipe(
              retry({
                delay: (_, count) => timer(Math.min(MAX_RETRY_DELAY_MS, 1000 * 2 ** (count - 1))),
                resetOnSuccess: true,
              }),
            )
          : EMPTY,
      ),
    );
  }

  // one connection; it fails when it ends, so it is opened again. reconnected tells, once it is
  // open, whether changes may have been missed
  private stream(reconnected: () => boolean): Observable<number | null> {
    return new Observable<number | null>((subscriber) => {
      const abort = new AbortController();
      const token = this.auth.token();
      const read = async () => {
        const response = await fetch(apiUrl('/api/v1/grocery/changes'), {
          headers: {
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
            ...SERVICE_WORKER_BYPASS,
          },
          signal: abort.signal,
        });
        if (response.status === 401) {
          this.auth.sessionRejected();
          subscriber.complete();
          return;
        }
        if (!response.ok || !response.body)
          throw new Error(`the changes answered ${response.status}`);
        if (reconnected()) subscriber.next(EVERYTHING);
        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        let buffer = '';
        for (;;) {
          const { value, done } = await reader.read();
          if (done) throw new Error('the changes ended');
          buffer += value;
          // an event ends with an empty line, a comment only keeps the connection open
          let end: number;
          while ((end = buffer.indexOf('\n\n')) >= 0) {
            const lines = buffer.slice(0, end).split('\n');
            buffer = buffer.slice(end + 2);
            const data = lines.find((line) => line.startsWith('data:'));
            if (lines.includes('event: changed') && data) {
              subscriber.next(
                (JSON.parse(data.slice('data:'.length)) as { listId: number }).listId,
              );
            }
          }
        }
      };
      read().catch((error: unknown) => {
        if (!abort.signal.aborted) subscriber.error(error);
      });
      return () => abort.abort();
    });
  }
}
