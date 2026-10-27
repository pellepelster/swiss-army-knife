import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { TimeSeries } from '../console/charts/chart-config';
import { TimeSeriesChart } from '../console/charts/time-series-chart';
import {
  BYTES_COLOR,
  STATUS_CLASS_COLORS,
  STATUS_CLASS_LABELS,
  StatusClassKey,
  statusCodeLines,
} from '../console/sites/metrics-chart';
import { SiteMetrics, SitesService } from '../console/sites/sites.service';
import { formatBytes, formatCount, formatPercent } from '../shared/format';
import { BucketStore } from './bucket-store';

// the logs tab of a bucket: its requests over a range, from the access logs of all the domains
// it is served at, which are one site named like the bucket
@Component({
  selector: 'app-bucket-logs',
  imports: [TimeSeriesChart],
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

    // the chart of the console, drawn on the sheet of the apps
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

      @if (failed() === 'unknown') {
        <p data-testid="logs-state">No requests were logged for this bucket yet.</p>
      } @else if (failed() === 'forbidden') {
        <p class="error" role="alert">Your role does not allow to see the logs.</p>
      } @else if (failed()) {
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
    </section>
  `,
})
export class BucketLogs {
  private readonly sites = inject(SitesService);
  private readonly store = inject(BucketStore);

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;
  protected readonly formatPercent = formatPercent;

  protected readonly range = signal<string | null>(null);
  protected readonly metrics = signal<SiteMetrics | null>(null);
  protected readonly failed = signal<'unknown' | 'forbidden' | 'unavailable' | null>(null);

  protected readonly ranges = computed(() => this.sites.sites()?.ranges ?? []);
  protected readonly selectedRange = computed(
    () => this.range() ?? this.sites.sites()?.defaultRange ?? null,
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
    this.sites.load();

    // reloads whenever the range changes, a late answer for a previous range never replaces the
    // current one; the site of a bucket is named like it
    effect((onCleanup) => {
      const site = this.store.name();
      const range = this.selectedRange();
      if (!site || range === null) {
        return;
      }
      this.failed.set(null);
      this.metrics.set(null);
      const subscription = this.sites.metrics(site, range).subscribe({
        next: (metrics) => this.metrics.set(metrics),
        error: (error: unknown) =>
          this.failed.set(
            !(error instanceof HttpErrorResponse)
              ? 'unavailable'
              : error.status === 404
                ? 'unknown'
                : error.status === 403
                  ? 'forbidden'
                  : 'unavailable',
          ),
      });
      onCleanup(() => subscription.unsubscribe());
    });
  }
}
