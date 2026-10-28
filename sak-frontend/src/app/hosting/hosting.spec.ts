import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { pageHelpers, pageOf } from '../testing/page';
import { COPIED_MS } from '../shared/copy-button';
import { POLL_INTERVAL_MS, s3Endpoint } from './buckets.service';
import { hostingRoutes } from './hosting.routes';

const CREATED = '2026-10-27T07:00:00Z';

const counted = (objects: number, bytes: number) => ({
  computing: false,
  failed: false,
  objects,
  bytes,
  computedAt: '2026-10-27T08:00:00Z',
});

const bucket = (
  name: string,
  domains: string[],
  statistics: object = counted(12, 2_500_000),
  subdomain?: string,
) => ({
  name,
  domains,
  subdomain,
  createdAt: CREATED,
  statistics,
});

const SECRET = {
  accessKeyId: 'AKIAEXAMPLEEXAMPLE23',
  secretAccessKey: 'c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0',
};

describe('Hosting', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [{ path: 'static-hosting', children: hostingRoutes }],
          withComponentInputBinding(),
        ),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  const { page, text, button, type, open, url } = pageHelpers(() => harness);

  it('lists the buckets a page at a time, the page is kept in the address', async () => {
    const pageAt = (offset: number, total: number, ...items: ReturnType<typeof bucket>[]) => ({
      items,
      pagination: { offset, limit: 50, total },
    });
    await open('/static-hosting');
    http
      .expectOne('/api/v1/static-hosting?offset=0&limit=50')
      .flush(pageAt(0, 51, bucket('a', [])));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=range]')?.textContent?.trim()).toBe('1–50 of 51');

    button('Next page').click();
    await harness.fixture.whenStable();
    expect(url()).toBe('/static-hosting?offset=50');
    http
      .expectOne('/api/v1/static-hosting?offset=50&limit=50')
      .flush(pageAt(50, 51, bucket('z', [])));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=bucket-z]')).not.toBeNull();

    // a page that is gone leads to the last one with buckets
    await open('/static-hosting?offset=100');
    http.expectOne('/api/v1/static-hosting?offset=100&limit=50').flush(pageAt(100, 51));
    await harness.fixture.whenStable();
    expect(url()).toBe('/static-hosting?offset=50');
    http
      .expectOne('/api/v1/static-hosting?offset=50&limit=50')
      .flush(pageAt(50, 51, bucket('z', [])));
  });

  it('tells it is loading until the buckets are there', async () => {
    await open('/static-hosting');
    expect(page().textContent).toContain('Loading…');
    http
      .expectOne('/api/v1/static-hosting?offset=0&limit=50')
      .flush({ items: [bucket('a', [])], pagination: { offset: 0, limit: 50, total: 1 } });
    await harness.fixture.whenStable();
    expect(page().textContent).not.toContain('Loading…');
  });

  it('lists the buckets and asks again while one is still being counted', async () => {
    await open('/static-hosting');
    http
      .expectOne('/api/v1/static-hosting?offset=0&limit=50')
      .flush(
        pageOf([
          bucket('site', ['example.org', 'www.example.org']),
          bucket('backups', [], { computing: true, failed: false }),
        ]),
      );
    await harness.fixture.whenStable();

    const site = page().querySelector('[data-testid="bucket-site"]')!;
    expect(site.getAttribute('href')).toBe('/static-hosting/site');
    expect(site.textContent).toContain('example.org, www.example.org');
    expect(site.querySelector('[data-testid=bytes]')?.textContent).toBe('2.5 MB');
    expect(page().querySelector('[data-testid="bucket-backups"]')?.textContent).toContain(
      'not served at a domain',
    );
    expect(button('Create bucket').getAttribute('href')).toBe('/static-hosting/create');

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    http
      .expectOne('/api/v1/static-hosting?offset=0&limit=50')
      .flush(pageOf([bucket('site', []), bucket('backups', [])]));
  });

  it('tells when there are no buckets yet', async () => {
    await open('/static-hosting');
    http.expectOne('/api/v1/static-hosting?offset=0&limit=50').flush(pageOf([]));
    await harness.fixture.whenStable();
    expect(page().textContent).toContain('No buckets yet.');
  });

  it('creates a bucket with its domains on its own page and opens it', async () => {
    await open('/static-hosting/create');
    expect(page().querySelector('[role=dialog]')).toBeNull();

    for (const name of ['No_Bucket', 'under_score', 'dot.ted']) {
      await type('#bucket-name', name);
      expect(button('Create bucket').disabled).toBe(true);
      expect(page().querySelector('#bucket-name')?.getAttribute('aria-invalid')).toBe('true');
    }
    await type('#bucket-name', 'my-site');
    expect(button('Create bucket').disabled).toBe(false);

    button('Add domain').click();
    await harness.fixture.whenStable();
    await type('#domain-0', 'no domain');
    expect(button('Create bucket').disabled).toBe(true);
    await type('#domain-0', 'example.org');
    button('Create bucket').click();

    const request = http.expectOne({ method: 'POST', url: '/api/v1/static-hosting' });
    expect(request.request.body).toEqual({ name: 'my-site', domains: ['example.org'] });
    request.flush(bucket('my-site', ['example.org']), { status: 201, statusText: 'Created' });
    await harness.fixture.whenStable();

    expect(url()).toBe('/static-hosting/my-site/configuration');
    http.expectOne('/api/v1/static-hosting/my-site').flush(bucket('my-site', ['example.org']));
  });

  it('shows what the backend rejected the bucket for and stays', async () => {
    await open('/static-hosting/create');
    await type('#bucket-name', 'backups');
    button('Create bucket').click();
    http
      .expectOne({ method: 'POST', url: '/api/v1/static-hosting' })
      .flush(
        { errors: [{ message: 'A bucket of the name exists.' }] },
        { status: 409, statusText: 'Conflict' },
      );
    await harness.fixture.whenStable();

    expect(text('create-errors')).toBe('A bucket of the name exists.');
    expect(url()).toBe('/static-hosting/create');
    button('Cancel').click();
    await harness.fixture.whenStable();
    expect(url()).toBe('/static-hosting');
    http.expectOne('/api/v1/static-hosting?offset=0&limit=50').flush(pageOf([]));
  });

  it('shows a bucket with its overview, its configuration first and asks again while it is counted', async () => {
    await open('/static-hosting/site');
    http
      .expectOne('/api/v1/static-hosting/site')
      .flush(bucket('site', ['example.org'], { computing: true, failed: false }));
    await harness.fixture.whenStable();

    expect(url()).toBe('/static-hosting/site/configuration');
    expect(page().querySelector('h1')?.textContent).toBe('site');
    expect(page().querySelector('[aria-label=Overview]')?.textContent).toContain('counting');
    expect(page().textContent).toContain(
      'Files uploaded to this bucket are served at the following addresses:',
    );
    expect(text('domains')).toBe('https://example.org/');
    expect(page().querySelector('[data-testid=domains] a')?.getAttribute('href')).toBe(
      'https://example.org/',
    );
    const tabs = [...page().querySelectorAll('nav[aria-label="Details"] a')];
    expect(tabs.map((tab) => tab.textContent?.trim())).toEqual([
      'Configuration',
      'Credentials',
      'Publishing',
      'Logs',
    ]);
    expect(tabs[0].classList).toContain('active');
    expect(button('Edit').getAttribute('href')).toBe('/static-hosting/site/edit');

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    http.expectOne('/api/v1/static-hosting/site').flush(bucket('site', ['example.org']));
    await harness.fixture.whenStable();
    expect(text('objects')).toBe('12');
    expect(text('bytes')).toBe('2.5 MB');
  });

  it('reports an unknown bucket', async () => {
    await open('/static-hosting/missing');
    http
      .expectOne('/api/v1/static-hosting/missing')
      .flush(null, { status: 404, statusText: 'Not Found' });
    await harness.fixture.whenStable();
    expect(page().querySelector('[role=alert]')?.textContent).toContain('There is no such bucket.');
  });

  it('deletes a bucket from its toolbar after a second click only', async () => {
    await open('/static-hosting/site');
    http.expectOne('/api/v1/static-hosting/site').flush(bucket('site', ['example.org']));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=updated]')).toBeNull();

    button('Delete the bucket site').click();
    await harness.fixture.whenStable();
    http.expectNone({ method: 'DELETE' });
    button('Keep').click();
    await harness.fixture.whenStable();
    button('Delete the bucket site').click();
    await harness.fixture.whenStable();
    button('Delete').click();

    http
      .expectOne({ method: 'DELETE', url: '/api/v1/static-hosting/site' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await harness.fixture.whenStable();
    expect(url()).toBe('/static-hosting');
    http.expectOne('/api/v1/static-hosting?offset=0&limit=50').flush(pageOf([]));
  });

  it('fetches the key pair once for the credentials and the publishing tab', async () => {
    await open('/static-hosting/site/credentials');
    http.expectOne('/api/v1/static-hosting/site').flush(bucket('site', ['example.org']));
    await harness.fixture.whenStable();
    button('Show the key pair').click();
    http.expectOne('/api/v1/static-hosting/site/secret').flush(SECRET);
    await harness.fixture.whenStable();

    button('Publishing').click();
    await harness.fixture.whenStable();
    expect(url()).toBe('/static-hosting/site/publishing');
    http.expectNone('/api/v1/static-hosting/site/secret');
    expect(text('hugo-deploy')).toContain(`AWS_ACCESS_KEY_ID=${SECRET.accessKeyId}`);
  });

  it('shows the endpoint and the key pair only when asked for, the secret hidden until shown', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    await open('/static-hosting/site/credentials');
    http.expectOne('/api/v1/static-hosting/site').flush(bucket('site', []));
    await harness.fixture.whenStable();

    expect(text('endpoint')).toBe(s3Endpoint());
    http.expectNone('/api/v1/static-hosting/site/secret');
    button('Show the key pair').click();
    http.expectOne('/api/v1/static-hosting/site/secret').flush(SECRET);
    await harness.fixture.whenStable();

    expect(text('access-key')).toBe(SECRET.accessKeyId);
    expect(text('secret-key')).not.toContain(SECRET.secretAccessKey);
    button('Show secret key').click();
    await harness.fixture.whenStable();
    expect(text('secret-key')).toBe(SECRET.secretAccessKey);

    button('Copy secret key').click();
    await harness.fixture.whenStable();
    expect(writeText).toHaveBeenCalledWith(SECRET.secretAccessKey);
    expect(button('Copy secret key').querySelector('.fa-check')).not.toBeNull();
    await vi.advanceTimersByTimeAsync(COPIED_MS);
    await harness.fixture.whenStable();
    expect(button('Copy secret key').querySelector('.fa-check')).toBeNull();
  });

  it('edits the domains on its own page, the name stays, and goes back to the bucket', async () => {
    await open('/static-hosting/site/edit');
    http.expectOne('/api/v1/static-hosting/site').flush(bucket('site', ['example.org']));
    await harness.fixture.whenStable();

    expect(page().querySelector<HTMLInputElement>('#edit-bucket-name')!.readOnly).toBe(true);
    await type('#domain-0', 'www.example.org');
    button('Save').click();
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/static-hosting/site/domains' });
    expect(request.request.body).toEqual({ domains: ['www.example.org'] });
    request.flush(
      { errors: [{ message: 'Another bucket is served at one of the domains.' }] },
      { status: 409, statusText: 'Conflict' },
    );
    await harness.fixture.whenStable();
    expect(text('save-errors')).toBe('Another bucket is served at one of the domains.');

    button('Remove domain 1').click();
    await harness.fixture.whenStable();
    button('Save').click();
    http
      .expectOne({ method: 'PUT', url: '/api/v1/static-hosting/site/domains' })
      .flush(bucket('site', []));
    await harness.fixture.whenStable();
    expect(url()).toBe('/static-hosting/site/configuration');
    http.expectOne('/api/v1/static-hosting/site').flush(bucket('site', []));
  });

  it('shows the hugo deployment target and the deploy command with the key pair when asked for', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    await open('/static-hosting/site/publishing');
    http
      .expectOne('/api/v1/static-hosting/site')
      .flush(bucket('site', ['example.org', 'www.example.org']));
    await harness.fixture.whenStable();

    expect(text('hugo-toml')).toBe(
      [
        'baseURL = "https://example.org/"',
        '',
        '[deployment]',
        '[[deployment.targets]]',
        'name = "site"',
        `URL = "s3://site?endpoint=${s3Endpoint()}&region=sak&use_path_style=true"`,
      ].join('\n'),
    );
    http.expectNone('/api/v1/static-hosting/site/secret');

    button('Show the deploy command with the key pair').click();
    http.expectOne('/api/v1/static-hosting/site/secret').flush(SECRET);
    await harness.fixture.whenStable();
    expect(text('hugo-deploy')).toContain(`AWS_ACCESS_KEY_ID=${SECRET.accessKeyId}`);
    expect(text('hugo-deploy')).not.toContain(SECRET.secretAccessKey);
    expect(text('hugo-deploy')).toContain('hugo deploy --target site');
    expect(page().querySelector('[data-testid=aws-sync]')).toBeNull();
    // a prompt before every command, not before the lines that continue it
    expect(page().querySelectorAll('[data-testid=hugo-deploy] .prompt').length).toBe(2);
    expect(page().textContent).toContain('withdeploy');

    // the copied command has the secret, shown or not
    button('Copy the deploy command').click();
    await harness.fixture.whenStable();
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining(SECRET.secretAccessKey));
    button('Show the secret key').click();
    await harness.fixture.whenStable();
    expect(text('hugo-deploy')).toContain(`AWS_SECRET_ACCESS_KEY=${SECRET.secretAccessKey}`);
  });

  it('tells where the files of a bucket without domains are not served yet', async () => {
    await open('/static-hosting/site');
    http.expectOne('/api/v1/static-hosting/site').flush(bucket('site', []));
    await harness.fixture.whenStable();
    expect(text('domains')).toContain('not served yet');
  });

  it('lists the subdomain of a bucket after its domains', async () => {
    await open('/static-hosting/site');
    http
      .expectOne('/api/v1/static-hosting/site')
      .flush(bucket('site', ['example.org'], counted(1, 1), 'site.pelle.io'));
    await harness.fixture.whenStable();
    const addresses = [...page().querySelectorAll('[data-testid=domains] li')].map((item) =>
      item.textContent?.trim(),
    );
    expect(addresses).toEqual(['https://example.org/', 'https://site.pelle.io/']);
  });

  it('shows the metrics of all the domains of a bucket in its logs tab', async () => {
    await open('/static-hosting/site/logs');
    http.expectOne('/api/v1/static-hosting/site').flush(bucket('site', ['example.org']));
    await harness.fixture.whenStable();
    http.expectOne('/api/v1/static-hosting/site/access-logs').flush({
      site: { id: 'site', domains: ['example.org', 'site.pelle.io'] },
      ranges: [
        { key: '24h', label: 'Last 24 hours' },
        { key: '7d', label: 'Last 7 days' },
      ],
      defaultRange: '24h',
    });
    await harness.fixture.whenStable();
    const metrics = (range: string, requests: number) => ({
      site: 'site',
      range,
      start: '2026-10-26T08:00:00Z',
      end: '2026-10-27T08:00:00Z',
      stepSeconds: 3600,
      buckets: ['2026-10-27T07:00:00Z'],
      series: [{ statusClass: '2xx', values: [requests], total: requests }],
      statusCodes: [{ status: '200', statusClass: '2xx', values: [requests], total: requests }],
      totalRequests: requests,
      errorRequests: 0,
      errorRate: 0,
      totalBytes: 2048,
    });
    http
      .expectOne('/api/v1/static-hosting/site/access-logs/metrics?range=24h')
      .flush(metrics('24h', 42));
    await harness.fixture.whenStable();
    expect(text('logs-requests')).toBe('42');
    expect(page().querySelector('app-time-series-chart')).not.toBeNull();
    expect(text('logs-domains')).toContain('example.org, site.pelle.io');

    const select = page().querySelector<HTMLSelectElement>('#logs-range')!;
    select.value = '7d';
    select.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();
    http
      .expectOne('/api/v1/static-hosting/site/access-logs/metrics?range=7d')
      .flush(metrics('7d', 0));
    await harness.fixture.whenStable();
    expect(text('logs-state')).toBe('No requests in this range.');
  });

  it('takes the endpoint from the host of the apps', () => {
    expect(s3Endpoint('apps.pelle.io')).toBe('https://api.pelle.io');
  });
});
