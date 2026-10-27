import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { TimeSeriesChart } from '../charts/time-series-chart';
import { Bucket, BucketsService } from '../s3/buckets.service';
import { POLL_INTERVAL_MS } from '../s3/buckets';
import { formatBytes, formatCount } from '../shared/format';
import { BYTES_COLOR, STATUS_CLASS_COLORS, STATUS_CLASS_LABELS, StatusClassKey } from '../sites/metrics-chart';
import { SiteMetrics, SitesService } from '../sites/sites.service';

// the requests of every site over the range of the overview
export const OVERVIEW_RANGE = '4h';

// how often the requests are asked for again
export const METRICS_REFRESH_MS = 60_000;

// the requests of all sites by status class and the data the buckets hold
@Component({
  selector: 'app-home',
  imports: [RouterLink, TimeSeriesChart],
  template: `
    <div class="page">
      <div class="page-header">
        <h1 class="mb-1">Overview</h1>
        <div class="page-subtitle">The requests of every site over the last 4 hours and the data in the buckets.</div>
      </div>

      <div class="mb-3">
        @if (metricsError()) {
          <div class="alert alert-warning" role="alert">{{ metricsError() }}</div>
        } @else if (metrics(); as metrics) {
          <app-time-series-chart
            title="Requests, last 4 hours"
            [buckets]="metrics.buckets"
            [stepSeconds]="metrics.stepSeconds"
            [series]="requestSeries()"
            [valueFormatter]="formatCount"
          />
          <div class="text-secondary small mt-1" data-testid="overview-requests">
            {{ formatCount(metrics.totalRequests) }} requests
          </div>
        }
      </div>

      <div class="card">
        <div class="card-body pb-0">
          <div class="label-mono"><a routerLink="/s3">Buckets</a></div>
        </div>
        @if (bucketsError()) {
          <div class="card-body text-secondary">{{ bucketsError() }}</div>
        } @else {
          <table class="table mb-0" aria-label="Bucket sizes">
            <thead>
              <tr>
                <th scope="col">Bucket</th>
                <th scope="col" class="text-end">Size</th>
              </tr>
            </thead>
            <tbody>
              @for (bucket of buckets(); track bucket.name) {
                <tr [attr.data-testid]="'overview-bucket-' + bucket.name">
                  <td class="font-mono"><a [routerLink]="['/s3', bucket.name]">{{ bucket.name }}</a></td>
                  <td class="font-mono text-end" data-testid="bytes">{{ size(bucket) }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="2" class="text-center text-secondary py-4">No buckets.</td>
                </tr>
              }
            </tbody>
            @if (buckets().length) {
              <tfoot>
                <tr class="fw-semibold">
                  <td>Total</td>
                  <td class="font-mono text-end" data-testid="overview-total-bytes">{{ totalBytes() }}</td>
                </tr>
              </tfoot>
            }
          </table>
        }
      </div>
    </div>
  `,
})
export class Home {
  private readonly sitesService = inject(SitesService);
  private readonly bucketsService = inject(BucketsService);

  protected readonly metrics = signal<SiteMetrics | null>(null);
  protected readonly metricsError = signal<string | null>(null);
  protected readonly buckets = signal<Bucket[]>([]);
  protected readonly bucketsError = signal<string | null>(null);

  protected readonly formatCount = formatCount;

  protected readonly requestSeries = computed(() =>
    (this.metrics()?.series ?? []).map((series) => ({
      key: series.statusClass,
      label: STATUS_CLASS_LABELS[series.statusClass as StatusClassKey] ?? series.statusClass,
      color: STATUS_CLASS_COLORS[series.statusClass as StatusClassKey] ?? BYTES_COLOR,
      values: series.values,
    })),
  );

  // the sum of the counted buckets, while one is still being counted or failed it is incomplete
  protected readonly totalBytes = computed(() => {
    const buckets = this.buckets();
    if (buckets.some((bucket) => bucket.statistics.computing)) return 'counting…';
    const total = formatBytes(buckets.reduce((sum, bucket) => sum + (bucket.statistics.bytes ?? 0), 0));
    return buckets.some((bucket) => bucket.statistics.failed) ? `at least ${total}` : total;
  });

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
    this.loadMetrics();
    this.subscriptions.add(timer(METRICS_REFRESH_MS, METRICS_REFRESH_MS).subscribe(() => this.loadMetrics()));
    this.loadBuckets();
  }

  protected size(bucket: Bucket): string {
    if (bucket.statistics.failed) return 'could not be counted';
    if (bucket.statistics.computing) return 'counting…';
    return formatBytes(bucket.statistics.bytes!);
  }

  private loadMetrics(): void {
    this.subscriptions.add(
      this.sitesService.metrics('all', OVERVIEW_RANGE).subscribe({
        next: (metrics) => {
          this.metricsError.set(null);
          this.metrics.set(metrics);
        },
        error: (error: unknown) =>
          this.metricsError.set(
            error instanceof HttpErrorResponse && error.status === 403
              ? 'Your role does not allow to see the requests.'
              : 'The requests could not be loaded.',
          ),
      }),
    );
  }

  // asks again after a while as long as a bucket is still being counted
  private loadBuckets(): void {
    this.subscriptions.add(
      this.bucketsService.buckets().subscribe({
        next: (buckets) => {
          this.buckets.set(buckets);
          if (buckets.some((bucket) => bucket.statistics.computing)) {
            this.subscriptions.add(timer(POLL_INTERVAL_MS).subscribe(() => this.loadBuckets()));
          }
        },
        error: (error: unknown) =>
          this.bucketsError.set(
            error instanceof HttpErrorResponse && error.status === 403
              ? 'Your role does not allow to see the buckets.'
              : 'The buckets could not be loaded.',
          ),
      }),
    );
  }
}
