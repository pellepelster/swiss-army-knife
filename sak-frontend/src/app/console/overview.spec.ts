import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { POLL_INTERVAL_MS } from '../hosting/buckets.service';
import { ConsoleOverview, METRICS_REFRESH_MS } from './overview';

const METRICS_URL = '/api/v1/logs/all/metrics?range=4h';

const metrics = (requests: number) => ({
  site: 'all',
  range: '4h',
  start: '2026-10-27T04:10:00Z',
  end: '2026-10-27T08:10:00Z',
  stepSeconds: 600,
  buckets: Array.from({ length: 24 }, (_, i) =>
    new Date(Date.parse('2026-10-27T04:10:00Z') + i * 600_000).toISOString(),
  ),
  series: [
    { statusClass: '2xx', values: [requests - 1, ...Array(23).fill(0)], total: requests - 1 },
    { statusClass: '3xx', values: Array(24).fill(0), total: 0 },
    { statusClass: '4xx', values: [1, ...Array(23).fill(0)], total: 1 },
    { statusClass: '5xx', values: Array(24).fill(0), total: 0 },
  ],
  statusCodes: [],
  bytes: Array(24).fill(0),
  totalRequests: requests,
  errorRequests: 1,
  errorRate: 1 / requests,
  totalBytes: 0,
  userAgents: [],
});

const bucket = (name: string, statistics: object) => ({
  name,
  domains: [],
  createdAt: '2026-10-27T07:00:00Z',
  statistics,
});

// what a list endpoint answers with the items
const pageOf = <T>(items: T[]) => ({
  items,
  pagination: { offset: 0, limit: 500, total: items.length },
});

describe('ConsoleOverview', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '', component: ConsoleOverview }]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  const open = async () => {
    const harness = await RouterTestingHarness.create('/');
    const page = harness.routeNativeElement!;
    const text = (testId: string) =>
      page.querySelector(`[data-testid="${testId}"]`)?.textContent?.trim();
    return { harness, page, text };
  };

  it('shows the requests of every site over 4 hours by status class and asks again every minute', async () => {
    const { harness, page, text } = await open();
    http.expectOne('/api/v1/static-hosting?limit=500').flush(pageOf([]));
    http.expectOne(METRICS_URL).flush(metrics(10));
    await harness.fixture.whenStable();

    expect(page.querySelector('[aria-label="Requests, last 4 hours"]')).toBeTruthy();
    expect(text('overview-requests')).toBe('10 requests');
    expect(page.textContent).toContain('Client error');
    // no boxes per site any more
    expect(page.querySelector('[data-testid^=site-]')).toBeNull();

    await vi.advanceTimersByTimeAsync(METRICS_REFRESH_MS);
    http.expectOne(METRICS_URL).flush(metrics(12));
    await harness.fixture.whenStable();
    expect(text('overview-requests')).toBe('12 requests');
  });

  it('lists the buckets with their size and the total', async () => {
    const { harness, text } = await open();
    http.expectOne(METRICS_URL).flush(metrics(1));
    http
      .expectOne('/api/v1/static-hosting?limit=500')
      .flush(
        pageOf([
          bucket('a', { computing: false, failed: false, objects: 1, bytes: 1_500_000 }),
          bucket('b', { computing: true, failed: false }),
        ]),
      );
    await harness.fixture.whenStable();

    expect(text('overview-total-bytes')).toBe('counting…');

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    http
      .expectOne('/api/v1/static-hosting?limit=500')
      .flush(
        pageOf([
          bucket('a', { computing: false, failed: false, objects: 1, bytes: 1_500_000 }),
          bucket('b', { computing: false, failed: false, objects: 2, bytes: 1_000_000 }),
        ]),
      );
    await harness.fixture.whenStable();

    expect(text('overview-bucket-b')).toContain('1 MB');
    expect(text('overview-total-bytes')).toBe('2.5 MB');
  });

  it('keeps the buckets when the requests cannot be loaded', async () => {
    const { harness, page, text } = await open();
    http.expectOne(METRICS_URL).flush(null, { status: 503, statusText: 'Service Unavailable' });
    http
      .expectOne('/api/v1/static-hosting?limit=500')
      .flush(pageOf([bucket('a', { computing: false, failed: false, objects: 0, bytes: 0 })]));
    await harness.fixture.whenStable();

    expect(page.querySelector('[role=alert]')?.textContent).toContain(
      'requests could not be loaded',
    );
    expect(text('overview-total-bytes')).toBe('0 B');
  });
});
