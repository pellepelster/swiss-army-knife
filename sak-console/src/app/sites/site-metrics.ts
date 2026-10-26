import { Component, computed, effect, inject, input, signal } from '@angular/core';
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
          <div class="label-mono">Site</div>
          <h1 class="mb-1 font-mono" data-testid="site-title">{{ site() }}</h1>
          @if (domains().length) {
            <div class="page-subtitle font-mono">{{ domains().join(', ') }}</div>
          }
        </div>
        <div class="d-flex align-items-center gap-2">
          <label for="metrics-range" class="form-label mb-0">Range</label>
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

      @if (failed()) {
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

  // the route parameter
  readonly site = input.required<string>();

  protected readonly range = signal<string | null>(null);

  protected readonly ranges = computed(() => this.sites.sites()?.ranges ?? []);

  protected readonly selectedRange = computed(
    () => this.range() ?? this.sites.sites()?.defaultRange ?? '24h',
  );

  protected readonly domains = computed(
    () => this.sites.sites()?.sites.find((site) => site.id === this.site())?.domains ?? [],
  );

  protected readonly metrics = signal<Metrics | null>(null);
  protected readonly failed = signal(false);

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

    // reloads whenever the site or the range changes, a late answer for a previous
    // selection never replaces the current one
    effect((onCleanup) => {
      const site = this.site();
      const range = this.selectedRange();
      this.failed.set(false);
      this.metrics.set(null);
      const subscription = this.sites.metrics(site, range).subscribe({
        next: (metrics) => this.metrics.set(metrics),
        error: () => this.failed.set(true),
      });
      onCleanup(() => subscription.unsubscribe());
    });
  }
}
