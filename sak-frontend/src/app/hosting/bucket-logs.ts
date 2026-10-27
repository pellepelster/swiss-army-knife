import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { LocalDateTimePipe } from '../shared/local-date-time.pipe';
import { formatBytes, formatCount, formatPercent } from '../shared/format';
import {
  AccessLogs,
  AccessLogsService,
  LiveLogError,
  LogEntry,
  SiteMetrics,
} from './access-logs/access-logs.service';
import { TimeSeries } from './access-logs/chart-config';
import {
  BYTES_COLOR,
  STATUS_CLASS_COLORS,
  STATUS_CLASS_LABELS,
  StatusClassKey,
  statusCodeLines,
} from './access-logs/metrics-chart';
import { TimeSeriesChart } from './access-logs/time-series-chart';
import { BucketStore } from './bucket-store';

// the newest requests the list keeps
export const MAX_REQUESTS = 200;

type Failure = 'unknown' | 'forbidden' | 'unavailable';

// the logs tab of a bucket: the requests to all the domains it is served at, which are one site
// named like the bucket; their totals and chart over a range, and the requests themselves, those
// of the last minutes and every new one as it arrives
@Component({
  selector: 'app-bucket-logs',
  imports: [LocalDateTimePipe, TimeSeriesChart],
  styleUrl: '../shared/app-parts.scss',
  styles: `
    .logs {
      display: grid;
      gap: 16px;
    }

    .range {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .range .input {
      width: auto;
    }

    .requests {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.9rem;

      th,
      td {
        padding: 6px 8px;
        border-bottom: 1px solid var(--line);
        text-align: left;
        vertical-align: top;
      }

      th {
        color: var(--muted);
        font-weight: 600;
      }

      .path {
        overflow-wrap: anywhere;
      }
    }

    // the chart drawn on the sheet of the apps
    :host ::ng-deep app-time-series-chart {
      .card {
        border: 0;
        background: transparent;
      }

      .card-body {
        padding: 0;
      }

      .label-mono {
        color: var(--muted);
        font-size: 0.85rem;
      }

      .metrics-chart {
        position: relative;
        height: 280px;
      }

      .metrics-legend-swatch {
        display: inline-block;
        width: 0.75rem;
        height: 0.75rem;
        border-radius: 2px;
      }
    }
  `,
  template: `
    <section class="sheet logs" aria-label="Logs">
      @if (failed() === 'unknown') {
        <p data-testid="logs-state">The bucket is not served at a domain, it has no access logs.</p>
      } @else if (failed() === 'forbidden') {
        <p class="error" role="alert">Your role does not allow to see the logs.</p>
      } @else {
        @if (domains().length) {
          <p class="muted" data-testid="logs-domains">
            The requests to <span class="mono">{{ domains().join(', ') }}</span>
          </p>
        }

        <div class="range">
          <label class="label" for="logs-range">Range</label>
          <select id="logs-range" class="input" (change)="range.set($any($event.target).value)">
            @for (choice of ranges(); track choice.key) {
              <option [value]="choice.key" [selected]="choice.key === selectedRange()">
                {{ choice.label }}
              </option>
            }
          </select>
        </div>

        @if (failed()) {
          <p class="error" role="alert">
            The logs could not be loaded, they may not be available right now.
          </p>
        } @else if (metrics(); as m) {
          <dl class="facts">
            <dt>Requests</dt>
            <dd class="mono" data-testid="logs-requests">{{ formatCount(m.totalRequests) }}</dd>
            <dt>Error rate</dt>
            <dd class="mono" data-testid="logs-error-rate">{{ formatPercent(m.errorRate) }}</dd>
            <dt>Transferred</dt>
            <dd class="mono" data-testid="logs-bytes">{{ formatBytes(m.totalBytes) }}</dd>
          </dl>
          @if (m.totalRequests === 0) {
            <p class="muted" data-testid="logs-state">No requests in this range.</p>
          } @else {
            <app-time-series-chart
              title="Requests by status"
              [buckets]="m.buckets"
              [stepSeconds]="m.stepSeconds"
              [series]="requestSeries()"
              [valueFormatter]="formatCount"
              [tooltipLines]="statusCodeLines"
            />
          }
        } @else {
          <p class="muted">Loading…</p>
        }

        <h2 class="label">Latest requests</h2>
        @if (followFailed()) {
          <p class="error" role="alert" data-testid="requests-state">
            The requests can not be followed right now.
          </p>
        } @else if (requests().length === 0) {
          <p class="muted" data-testid="requests-state">No requests in the last minutes yet.</p>
        } @else {
          <table class="requests" aria-label="Latest requests">
            <thead>
              <tr>
                <th scope="col">Time</th>
                <th scope="col">Domain</th>
                <th scope="col">Path</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              @for (request of requests(); track $index) {
                <tr>
                  <td>{{ request.time | localDateTime: 'short' : 'medium' }}</td>
                  <td class="mono">{{ request.host }}</td>
                  <td class="mono path">{{ request.path }}</td>
                  <td class="mono">{{ request.status || '–' }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      }
    </section>
  `,
})
export class BucketLogs {
  private readonly service = inject(AccessLogsService);
  private readonly store = inject(BucketStore);

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;
  protected readonly formatPercent = formatPercent;

  protected readonly range = signal<string | null>(null);
  protected readonly metrics = signal<SiteMetrics | null>(null);
  protected readonly failed = signal<Failure | null>(null);

  // the newest first
  protected readonly requests = signal<readonly LogEntry[]>([]);
  protected readonly followFailed = signal(false);

  // the domains of the site and the ranges of the metrics
  private readonly accessLogs = signal<AccessLogs | null>(null);
  protected readonly domains = computed(() => this.accessLogs()?.site.domains ?? []);
  protected readonly ranges = computed(() => this.accessLogs()?.ranges ?? []);
  protected readonly selectedRange = computed(
    () => this.range() ?? this.accessLogs()?.defaultRange ?? null,
  );

  protected readonly requestSeries = computed<TimeSeries[]>(() =>
    (this.metrics()?.series ?? []).map((series) => ({
      key: series.statusClass,
      label: STATUS_CLASS_LABELS[series.statusClass as StatusClassKey] ?? series.statusClass,
      color: STATUS_CLASS_COLORS[series.statusClass as StatusClassKey] ?? BYTES_COLOR,
      values: series.values,
    })),
  );

  protected readonly statusCodeLines = (index: number) => {
    const metrics = this.metrics();
    return metrics ? statusCodeLines(metrics, index) : [];
  };

  constructor() {
    effect((onCleanup) => {
      const name = this.store.name();
      if (!name) return;
      const subscription = this.service.accessLogs(name).subscribe({
        next: (accessLogs) => this.accessLogs.set(accessLogs),
        error: (error: unknown) => this.failed.set(failure(error)),
      });
      onCleanup(() => subscription.unsubscribe());
    });

    // reloads whenever the range changes, a late answer for a previous range never replaces the
    // current one
    effect((onCleanup) => {
      const name = this.store.name();
      const range = this.selectedRange();
      if (!name || range === null) {
        return;
      }
      this.failed.set(null);
      this.metrics.set(null);
      const subscription = this.service.metrics(name, range).subscribe({
        next: (metrics) => this.metrics.set(metrics),
        error: (error: unknown) => this.failed.set(failure(error)),
      });
      onCleanup(() => subscription.unsubscribe());
    });

    // the requests of the last minutes, then every new one while the tab shows
    effect((onCleanup) => {
      const name = this.store.name();
      if (!name || this.accessLogs() === null) return;
      this.requests.set([]);
      this.followFailed.set(false);
      const subscription = this.service.follow(name).subscribe({
        next: (entries) =>
          this.requests.update((requests) =>
            [...[...entries].reverse(), ...requests].slice(0, MAX_REQUESTS),
          ),
        error: (error: unknown) => {
          if (!(error instanceof LiveLogError && error.status === 401)) this.followFailed.set(true);
        },
      });
      onCleanup(() => subscription.unsubscribe());
    });
  }
}

// why the logs are missing: a bucket served at no domain, a role without access or unavailable logs
function failure(error: unknown): Failure {
  if (!(error instanceof HttpErrorResponse)) return 'unavailable';
  if (error.status === 404) return 'unknown';
  return error.status === 403 ? 'forbidden' : 'unavailable';
}
