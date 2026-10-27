import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { redirectsRoute } from './redirects.routes';

const CREATED = '2026-10-27T08:00:00Z';

const toExternal = {
  sourceDomain: 'example.com',
  target: { type: 'domain', domain: 'example.org' },
  location: 'example.org',
  createdAt: CREATED,
  updatedAt: CREATED,
};
const toBucket = {
  sourceDomain: 'example.de',
  target: { type: 'bucket', bucket: 'site' },
  location: 'site.example.org',
  createdAt: CREATED,
  updatedAt: CREATED,
};

// what a list endpoint answers with the items
const pageOf = <T>(items: T[]) => ({
  items,
  pagination: { offset: 0, limit: 500, total: items.length },
});

const statistics = { computing: true, failed: false };

describe('Redirects', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        // the guard of the app is left out, the tests are signed in
        provideRouter([{ ...redirectsRoute, canActivate: [] }], withComponentInputBinding()),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  const page = () => harness.routeNativeElement!;
  const button = (name: string, root: ParentNode = page()) =>
    [...root.querySelectorAll<HTMLButtonElement>('button, a')].find(
      (b) => b.textContent?.trim() === name || b.getAttribute('aria-label') === name,
    ) as HTMLButtonElement;
  const type = async (selector: string, value: string) => {
    const input = page().querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
    await harness.fixture.whenStable();
  };
  const open = async (url: string) => {
    await harness.navigateByUrl(url);
    await harness.fixture.whenStable();
  };
  const url = () => TestBed.inject(Router).url;

  it('lists the redirects with their targets and where they lead', async () => {
    await open('/redirects');
    http.expectOne('/api/v1/redirects?offset=0&limit=50').flush(pageOf([toExternal, toBucket]));
    await harness.fixture.whenStable();

    const external = page().querySelector('[data-testid="redirect-example.com"]')!;
    expect(external.textContent).toContain('domain');
    expect(external.querySelector('[data-testid=location]')?.textContent?.trim()).toBe(
      'https://example.org',
    );
    const bucket = page().querySelector('[data-testid="redirect-example.de"]')!;
    expect(bucket.querySelector('.target a')?.getAttribute('href')).toBe('/static-hosting/site');
    expect(button('Create redirect').getAttribute('href')).toBe('/redirects/create');
    expect(button('example.com').getAttribute('href')).toBe('/redirects/example.com');
    expect(button('Delete the redirect of example.com')).toBeUndefined();
  });

  it('lists the redirects a page at a time', async () => {
    await open('/redirects?offset=50');
    http
      .expectOne('/api/v1/redirects?offset=50&limit=50')
      .flush({ items: [toBucket], pagination: { offset: 50, limit: 50, total: 51 } });
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=range]')?.textContent?.trim()).toBe('51–51 of 51');

    button('Previous page').click();
    await harness.fixture.whenStable();
    expect(url()).toBe('/redirects');
    http
      .expectOne('/api/v1/redirects?offset=0&limit=50')
      .flush({ items: [toExternal], pagination: { offset: 0, limit: 50, total: 51 } });
  });

  it('offers every bucket served at a domain, asked for page by page', async () => {
    await open('/redirects/create');
    const served = (index: number) => ({
      name: `site-${index}`,
      domains: [`site-${index}.example.org`],
      createdAt: CREATED,
      statistics,
    });
    http.expectOne('/api/v1/static-hosting?offset=0&limit=100').flush({
      items: Array.from({ length: 100 }, (_, index) => served(index)),
      pagination: { offset: 0, limit: 100, total: 101 },
    });
    http.expectOne('/api/v1/static-hosting?offset=100&limit=100').flush({
      items: [served(100)],
      pagination: { offset: 100, limit: 100, total: 101 },
    });
    await harness.fixture.whenStable();

    page().querySelector<HTMLInputElement>('#target-bucket')!.click();
    await harness.fixture.whenStable();
    expect(page().querySelectorAll('#target-bucket-name option').length).toBe(102);
  });

  it('shows a redirect with its overview and its configuration', async () => {
    await open('/redirects/example.de');
    http.expectOne('/api/v1/redirects/example.de').flush(toBucket);
    await harness.fixture.whenStable();

    expect(url()).toBe('/redirects/example.de/configuration');
    expect(page().querySelector('h1')?.textContent?.trim()).toBe('example.de');
    expect(page().querySelector('[data-testid=location]')?.textContent?.trim()).toBe(
      'https://site.example.org',
    );
    expect(page().querySelector('[data-testid=updated]')).not.toBeNull();
    const target = page().querySelector('[data-testid=target]')!;
    expect(target.querySelector('a')?.getAttribute('href')).toBe('/static-hosting/site');
    expect(button('Edit').getAttribute('href')).toBe('/redirects/example.de/edit');
  });

  it('deletes a redirect from its toolbar after a second click only', async () => {
    await open('/redirects/example.com');
    http.expectOne('/api/v1/redirects/example.com').flush(toExternal);
    await harness.fixture.whenStable();

    button('Delete the redirect of example.com').click();
    await harness.fixture.whenStable();
    http.expectNone({ method: 'DELETE' });
    button('Keep').click();
    await harness.fixture.whenStable();
    button('Delete the redirect of example.com').click();
    await harness.fixture.whenStable();
    button('Delete').click();

    http
      .expectOne({ method: 'DELETE', url: '/api/v1/redirects/example.com' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await harness.fixture.whenStable();
    expect(url()).toBe('/redirects');
    http.expectOne('/api/v1/redirects?offset=0&limit=50').flush(pageOf([toBucket]));
  });

  it('changes where a redirect leads to on its edit page', async () => {
    await open('/redirects/example.com/edit');
    http.expectOne('/api/v1/redirects/example.com').flush(toExternal);
    await harness.fixture.whenStable();
    http
      .expectOne('/api/v1/static-hosting?offset=0&limit=100')
      .flush(
        pageOf([{ name: 'site', domains: ['site.example.org'], createdAt: CREATED, statistics }]),
      );
    await harness.fixture.whenStable();

    expect(page().querySelector<HTMLInputElement>('#source-domain')!.readOnly).toBe(true);
    expect(page().querySelector<HTMLInputElement>('#target-domain-name')!.value).toBe(
      'example.org',
    );
    page().querySelector<HTMLInputElement>('#target-bucket')!.click();
    await harness.fixture.whenStable();
    expect(button('Save').disabled).toBe(true);
    await type('#target-bucket-name', 'site');
    button('Save').click();

    const rejected = http.expectOne({ method: 'PUT', url: '/api/v1/redirects/example.com' });
    expect(rejected.request.body).toEqual({ target: { type: 'bucket', bucket: 'site' } });
    rejected.flush(
      { errors: [{ message: 'The bucket is not served at a domain.' }] },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=save-error]')?.textContent?.trim()).toBe(
      'The bucket is not served at a domain.',
    );

    button('Save').click();
    http
      .expectOne({ method: 'PUT', url: '/api/v1/redirects/example.com' })
      .flush({ ...toExternal, target: { type: 'bucket', bucket: 'site' } });
    await harness.fixture.whenStable();
    expect(url()).toBe('/redirects/example.com/configuration');
    http.expectOne('/api/v1/redirects/example.com').flush(toExternal);
  });

  it('redirects a domain to an external domain on its own page', async () => {
    await open('/redirects/create');
    http.expectOne('/api/v1/static-hosting?offset=0&limit=100').flush(pageOf([]));
    expect(page().querySelector('[role=dialog]')).toBeNull();

    await type('#source-domain', 'example.com');
    expect(button('Create redirect').disabled).toBe(true);
    await type('#target-domain-name', 'example.org');
    expect(button('Create redirect').disabled).toBe(false);
    button('Create redirect').click();

    const request = http.expectOne({ method: 'POST', url: '/api/v1/redirects' });
    expect(request.request.body).toEqual({
      sourceDomain: 'example.com',
      target: { type: 'domain', domain: 'example.org' },
    });
    request.flush(toExternal, { status: 201, statusText: 'Created' });
    await harness.fixture.whenStable();
    expect(url()).toBe('/redirects/example.com/configuration');
    http.expectOne('/api/v1/redirects/example.com').flush(toExternal);
  });

  it('offers the buckets served at a domain and shows what the backend rejected', async () => {
    await open('/redirects/create');
    http.expectOne('/api/v1/static-hosting?offset=0&limit=100').flush(
      pageOf([
        { name: 'site', domains: ['site.example.org'], createdAt: CREATED, statistics },
        { name: 'private', domains: [], createdAt: CREATED, statistics },
      ]),
    );
    await harness.fixture.whenStable();

    page().querySelector<HTMLInputElement>('#target-bucket')!.click();
    await harness.fixture.whenStable();
    const options = [...page().querySelectorAll('#target-bucket-name option')].map((option) =>
      option.textContent?.trim(),
    );
    expect(options).toEqual(['Choose a bucket', 'site (site.example.org)']);

    await type('#source-domain', 'example.de');
    await type('#target-bucket-name', 'site');
    button('Create redirect').click();

    const request = http.expectOne({ method: 'POST', url: '/api/v1/redirects' });
    expect(request.request.body).toEqual({
      sourceDomain: 'example.de',
      target: { type: 'bucket', bucket: 'site' },
    });
    request.flush(
      { errors: [{ message: 'The domain is redirected already.' }] },
      { status: 409, statusText: 'Conflict' },
    );
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=create-error]')?.textContent?.trim()).toBe(
      'The domain is redirected already.',
    );
    expect(url()).toBe('/redirects/create');
  });
});
