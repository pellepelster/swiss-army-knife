import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { TimeSeries } from '../charts/chart-config';
import { TimeSeriesChart } from '../charts/time-series-chart';
import { formatBytes, formatCount, formatPercent } from '../shared/format';
import {
  BYTES_COLOR,
  CLIENT_FAMILY_COLORS,
  CLIENT_FAMILY_LABELS,
  CLIENT_FAMILY_OF,
  CLIENT_FAMILY_ORDER,
  STATUS_CLASS_COLORS,
  STATUS_CLASS_LABELS,
  StatusClassKey,
  USER_AGENT_LABELS,
  statusCodeLines,
} from './metrics-chart';
import { SiteMetrics as Metrics, SitesService } from './sites.service';

interface AgentShare {
  key: string;
  label: string;
  requests: number;
  share: number;
  width: number;
}

// the requests of a site over a range, computed from the ingress access logs; the metrics
// view of the blcks pages
@Component({
  selector: 'app-site-metrics',
  imports: [TimeSeriesChart],
  template: `
    <div class="page">
      <div class="page-header d-flex align-items-end justify-content-between">
        <div>
          <h1 class="mb-1 font-mono" data-testid="site-title">{{ shownSite() }}</h1>
          @if (domains().length) {
            <div class="page-subtitle font-mono">{{ domains().join(', ') }}</div>
          }
        </div>
        <div class="d-flex flex-wrap align-items-center gap-2">
          <label for="metrics-site" class="form-label mb-0">Site</label>
          <select
            id="metrics-site"
            class="form-select form-select-sm w-auto font-mono"
            (change)="selectSite($any($event.target).value)"
          >
            @if (failed() === 'unknown') {
              <option [value]="shownSite()" [selected]="true" disabled>{{ shownSite() }}</option>
            }
            @for (choice of siteChoices(); track choice.id) {
              <option [value]="choice.id" [selected]="choice.id === shownSite()">
                {{ choice.id }}
              </option>
            }
          </select>
          <label for="metrics-range" class="form-label mb-0 ms-2">Range</label>
          <select
            id="metrics-range"
            class="form-select form-select-sm w-auto"
            (change)="range.set($any($event.target).value)"
          >
            @for (choice of ranges(); track choice.key) {
              <option [value]="choice.key" [selected]="choice.key === selectedRange()">
                {{ choice.label }}
              </option>
            }
          </select>
        </div>
      </div>

      @if (failed() === 'unknown') {
        <div class="alert alert-warning" role="alert">
          There is no site <span class="font-mono">{{ shownSite() }}</span>, choose one of the sites.
        </div>
      } @else if (failed() === 'forbidden') {
        <div class="alert alert-warning" role="alert">Your role does not allow to see the logs.</div>
      } @else if (failed()) {
        <div class="alert alert-danger" role="alert">
          The metrics could not be loaded, the logs may not be available right now.
        </div>
      } @else if (metrics(); as m) {
        <div class="metrics-kpis mb-3">
          <div class="card">
            <div class="card-body">
              <div class="label-mono">Requests</div>
              <div class="metrics-kpi-value font-mono" data-testid="kpi-requests">
                {{ formatCount(m.totalRequests) }}
              </div>
            </div>
          </div>
          <div class="card">
            <div class="card-body">
              <div class="label-mono">Error rate</div>
              <div class="metrics-kpi-value font-mono" data-testid="kpi-error-rate">
                {{ formatPercent(m.errorRate) }}
              </div>
              <div class="label-mono text-secondary">4xx + 5xx</div>
            </div>
          </div>
          <div class="card">
            <div class="card-body">
              <div class="label-mono">Transferred</div>
              <div class="metrics-kpi-value font-mono" data-testid="kpi-bytes">
                {{ formatBytes(m.totalBytes) }}
              </div>
            </div>
          </div>
        </div>

        @if (m.totalRequests === 0) {
          <div class="card">
            <div class="card-body text-center text-secondary">
              No requests in this range.
            </div>
          </div>
        } @else {
          <app-time-series-chart
            class="d-block mb-3"
            title="Requests by status"
            [buckets]="m.buckets"
            [stepSeconds]="m.stepSeconds"
            [series]="requestSeries()"
            [valueFormatter]="formatCount"
            [tooltipLines]="statusCodeLines"
          />
          <app-time-series-chart
            class="d-block mb-3"
            title="Transferred"
            [buckets]="m.buckets"
            [stepSeconds]="m.stepSeconds"
            [series]="bytesSeries()"
            [valueFormatter]="formatBytes"
          />
          @if (clientSeries().length > 0) {
            <app-time-series-chart
              class="d-block mb-3"
              title="Clients"
              [buckets]="m.buckets"
              [stepSeconds]="m.stepSeconds"
              [series]="clientSeries()"
              [valueFormatter]="formatCount"
            />
          }
          <div class="card">
            <div class="card-body">
              <h2 class="h6 mb-3">User agents</h2>
              <div class="agent-shares" data-testid="agent-shares">
                @for (agent of agentShares(); track agent.key) {
                  <div>{{ agent.label }}</div>
                  <div class="agent-share-track">
                    <div class="agent-share-bar" [style.width.%]="agent.width"></div>
                  </div>
                  <div class="font-mono">{{ formatCount(agent.requests) }}</div>
                  <div class="font-mono text-secondary">{{ formatPercent(agent.share) }}</div>
                }
              </div>
            </div>
          </div>
        }
      } @else {
        <p class="text-secondary">Loading…</p>
      }
    </div>
  `,
})
export class SiteMetrics {
  private readonly sites = inject(SitesService);

  // the route parameter, without one the first site is shown
  readonly site = input<string>();

  private readonly router = inject(Router);

  protected readonly siteChoices = computed(() => this.sites.sites()?.sites ?? []);

  // only once the site is in the url, /logs/statistics continues at the first one
  protected readonly shownSite = computed(() => this.site() ?? null);

  protected readonly range = signal<string | null>(null);

  protected readonly ranges = computed(() => this.sites.sites()?.ranges ?? []);

  protected readonly selectedRange = computed(
    () => this.range() ?? this.sites.sites()?.defaultRange ?? '24h',
  );

  protected readonly domains = computed(
    () => this.siteChoices().find((site) => site.id === this.shownSite())?.domains ?? [],
  );

  protected readonly metrics = signal<Metrics | null>(null);
  // why the metrics are missing, an unknown site or unavailable logs
  protected readonly failed = signal<'unknown' | 'forbidden' | 'unavailable' | null>(null);

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;
  protected readonly formatPercent = formatPercent;

  protected readonly statusCodeLines = (index: number) => statusCodeLines(this.metrics()!, index);

  protected readonly requestSeries = computed<TimeSeries[]>(() =>
    (this.metrics()?.series ?? []).map((series) => ({
      key: series.statusClass,
      label: STATUS_CLASS_LABELS[series.statusClass as StatusClassKey] ?? series.statusClass,
      color: STATUS_CLASS_COLORS[series.statusClass as StatusClassKey] ?? BYTES_COLOR,
      values: series.values,
    })),
  );

  protected readonly bytesSeries = computed<TimeSeries[]>(() => {
    const metrics = this.metrics();
    return metrics
      ? [{ key: 'bytes', label: 'Transferred', color: BYTES_COLOR, values: metrics.bytes }]
      : [];
  });

  protected readonly clientSeries = computed<TimeSeries[]>(() => {
    const metrics = this.metrics();
    if (!metrics || metrics.userAgents.length === 0) {
      return [];
    }
    return CLIENT_FAMILY_ORDER.map((family) => {
      const values = new Array<number>(metrics.buckets.length).fill(0);
      metrics.userAgents
        .filter((agent) => (CLIENT_FAMILY_OF[agent.group] ?? 'other') === family)
        .forEach((agent) => agent.values.forEach((value, index) => (values[index] += value)));
      return {
        key: family,
        label: CLIENT_FAMILY_LABELS[family],
        color: CLIENT_FAMILY_COLORS[family],
        values,
      };
    }).filter((series) => series.values.some((value) => value > 0));
  });

  protected readonly agentShares = computed<AgentShare[]>(() => {
    const agents = this.metrics()?.userAgents ?? [];
    const total = agents.reduce((sum, agent) => sum + agent.requests, 0);
    const largest = agents.reduce((max, agent) => Math.max(max, agent.requests), 0);
    return agents.map((agent) => ({
      key: agent.group,
      label: USER_AGENT_LABELS[agent.group] ?? agent.group,
      requests: agent.requests,
      share: total === 0 ? 0 : agent.requests / total,
      width: largest === 0 ? 0 : (100 * agent.requests) / largest,
    }));
  });

  constructor() {
    this.sites.load();

    // the shown site is always part of the url, /logs/statistics continues at the first one
    effect(() => {
      const first = this.siteChoices()[0]?.id;
      if (this.site() === undefined && first !== undefined) {
        void this.router.navigate(['/logs/statistics', first], { replaceUrl: true });
      }
    });

    // reloads whenever the site or the range changes, a late answer for a previous
    // selection never replaces the current one
    effect((onCleanup) => {
      const site = this.shownSite();
      const range = this.selectedRange();
      if (site === null) {
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

  // the site is part of the url, so a site can be linked and the back button returns to the last one
  protected selectSite(site: string): void {
    void this.router.navigate(['/logs/statistics', site]);
  }
}
