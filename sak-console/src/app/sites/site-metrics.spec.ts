import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { formatBytes, formatCount, formatPercent } from '../shared/format';
import { SiteMetrics } from './site-metrics';
import { SiteMetrics as Metrics } from './sites.service';

const buckets = ['2026-10-26T10:00:00Z', '2026-10-26T11:00:00Z'];

const metrics = (range: string): Metrics => ({
  site: 'pelle.io',
  range,
  start: buckets[0],
  end: '2026-10-26T12:00:00Z',
  stepSeconds: 3600,
  buckets,
  series: [
    { statusClass: '2xx', values: [2, 4], total: 6 },
    { statusClass: '3xx', values: [0, 0], total: 0 },
    { statusClass: '4xx', values: [0, 2], total: 2 },
    { statusClass: '5xx', values: [0, 0], total: 0 },
  ],
  statusCodes: [
    { status: '200', statusClass: '2xx', values: [2, 4], total: 6 },
    { status: '404', statusClass: '4xx', values: [0, 2], total: 2 },
  ],
  bytes: [1000, 1500],
  totalRequests: 8,
  errorRequests: 2,
  errorRate: 0.25,
  totalBytes: 2500,
  userAgents: [
    { group: 'chrome', values: [2, 4], requests: 6 },
    { group: 'openai', values: [0, 2], requests: 2 },
  ],
});

describe('SiteMetrics', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [
            { path: 'logs/statistics', component: SiteMetrics },
            { path: 'logs/statistics/:site', component: SiteMetrics },
          ],
          withComponentInputBinding(),
        ),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const open = async (url = '/logs/statistics/pelle.io') => {
    const harness = await RouterTestingHarness.create(url);
    http.expectOne('/api/v1/logs').flush({
      sites: [
        { id: 'pelle.io', domains: ['pelle.io', 'www.pelle.io'] },
        { id: 's3', domains: ['s3.pelle.io'] },
      ],
      ranges: [
        { key: '1h', label: '1 hour' },
        { key: '24h', label: '24 hours' },
      ],
      defaultRange: '24h',
    });
    await harness.fixture.whenStable();
    return harness;
  };

  it('shows the totals, charts and user agents of the default range', async () => {
    const harness = await open();
    const page = harness.routeNativeElement!;

    http.expectOne('/api/v1/logs/pelle.io/metrics?range=24h').flush(metrics('24h'));
    await harness.fixture.whenStable();

    expect(page.querySelector('[data-testid=site-title]')?.textContent).toBe('pelle.io');
    expect(page.textContent).toContain('pelle.io, www.pelle.io');
    expect(page.querySelector('[data-testid=kpi-requests]')?.textContent?.trim()).toBe('8');
    expect(page.querySelector('[data-testid=kpi-error-rate]')?.textContent?.trim()).toBe('25%');
    expect(page.querySelector('[data-testid=kpi-bytes]')?.textContent?.trim()).toBe('2.5 kB');
    expect(page.querySelectorAll('app-time-series-chart').length).toBe(3);
    expect(page.querySelector('[data-testid=agent-shares]')?.textContent).toContain('Chrome');
    expect(page.querySelector('[data-testid=agent-shares]')?.textContent).toContain('OpenAI');
  });

  it('loads the metrics of the selected range', async () => {
    const harness = await open();
    http.expectOne('/api/v1/logs/pelle.io/metrics?range=24h').flush(metrics('24h'));
    await harness.fixture.whenStable();

    const select = harness.routeNativeElement!.querySelector<HTMLSelectElement>('#metrics-range')!;
    select.value = '1h';
    select.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();

    http.expectOne('/api/v1/logs/pelle.io/metrics?range=1h').flush(metrics('1h'));
  });

  it('continues at the first site without one in the url', async () => {
    const harness = await open('/logs/statistics');

    http.expectOne('/api/v1/logs/pelle.io/metrics?range=24h').flush(metrics('24h'));
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/logs/statistics/pelle.io');
    expect(harness.routeNativeElement!.querySelector('[data-testid=site-title]')?.textContent).toBe(
      'pelle.io',
    );
  });

  it('switches to the site selected in the dropdown', async () => {
    const harness = await open();
    http.expectOne('/api/v1/logs/pelle.io/metrics?range=24h').flush(metrics('24h'));
    await harness.fixture.whenStable();

    const select = harness.routeNativeElement!.querySelector<HTMLSelectElement>('#metrics-site')!;
    expect([...select.options].map((option) => option.value)).toEqual(['pelle.io', 's3']);
    expect(select.value).toBe('pelle.io');
    select.value = 's3';
    select.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/logs/statistics/s3');
    http.expectOne('/api/v1/logs/s3/metrics?range=24h').flush({ ...metrics('24h'), site: 's3' });
    await harness.fixture.whenStable();
    expect(harness.routeNativeElement!.querySelector('[data-testid=site-title]')?.textContent).toBe('s3');
  });

  it('reports metrics that can not be loaded', async () => {
    const harness = await open();

    http
      .expectOne('/api/v1/logs/pelle.io/metrics?range=24h')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await harness.fixture.whenStable();

    expect(harness.routeNativeElement!.querySelector('[role=alert]')?.textContent).toContain(
      'could not be loaded',
    );
  });

  it('reports a site that does not exist', async () => {
    const harness = await open('/logs/statistics/nope');

    http
      .expectOne('/api/v1/logs/nope/metrics?range=24h')
      .flush(null, { status: 404, statusText: 'Not Found' });
    await harness.fixture.whenStable();

    const page = harness.routeNativeElement!;
    expect(page.querySelector('[role=alert]')?.textContent).toContain('There is no site nope');
    const select = page.querySelector<HTMLSelectElement>('#metrics-site')!;
    expect(select.value).toBe('nope');
    expect([...select.options].map((option) => option.value)).toEqual(['nope', 'pelle.io', 's3']);
  });
});

describe('format', () => {
  it('formats counts, bytes and rates', () => {
    expect(formatCount(8)).toBe('8');
    expect(formatCount(12345)).toBe('12.3K');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1500)).toBe('1.5 kB');
    expect(formatBytes(3_000_000_000)).toBe('3 GB');
    expect(formatPercent(0.25)).toBe('25%');
    expect(formatPercent(0.1234)).toBe('12.3%');
  });
});
