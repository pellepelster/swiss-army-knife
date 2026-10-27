import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { COPIED_MS } from './bucket-credentials';
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

// what a list endpoint answers with the items
const pageOf = <T>(items: T[]) => ({
  items,
  pagination: { offset: 0, limit: 500, total: items.length },
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
  });

  const page = () => harness.routeNativeElement!;
  const text = (testId: string) =>
    page().querySelector(`[data-testid="${testId}"]`)?.textContent?.trim();
  const button = (name: string) =>
    [...page().querySelectorAll<HTMLButtonElement>('button, a')].find(
      (b) => b.textContent?.trim() === name || b.getAttribute('aria-label') === name,
    ) as HTMLButtonElement;
  const type = async (selector: string, value: string) => {
    const input = page().querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
  };
  const open = async (url: string) => {
    await harness.navigateByUrl(url);
    await harness.fixture.whenStable();
  };
  const url = () => TestBed.inject(Router).url;

  it('lists the buckets and asks again while one is still being counted', async () => {
    await open('/static-hosting');
    http
      .expectOne('/api/v1/static-hosting?limit=500')
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
      .expectOne('/api/v1/static-hosting?limit=500')
      .flush(pageOf([bucket('site', []), bucket('backups', [])]));
  });

  it('tells when there are no buckets yet', async () => {
    await open('/static-hosting');
    http.expectOne('/api/v1/static-hosting?limit=500').flush(pageOf([]));
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
    http.expectOne('/api/v1/static-hosting?limit=500').flush(pageOf([]));
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
    const tabs = [...page().querySelectorAll('.tabs a')];
    expect(tabs.map((tab) => tab.textContent?.trim())).toEqual([
      'Configuration',
      'Credentials',
      'Publishing',
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

  it('takes the endpoint from the host of the apps', () => {
    expect(s3Endpoint('apps.pelle.io')).toBe('https://s3.pelle.io');
  });
});
