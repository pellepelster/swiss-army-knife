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
};
const toBucket = {
  sourceDomain: 'example.de',
  target: { type: 'bucket', bucket: 'site' },
  location: 'site.example.org',
  createdAt: CREATED,
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
    http.expectOne('/api/v1/redirects?limit=500').flush(pageOf([toExternal, toBucket]));
    await harness.fixture.whenStable();

    const external = page().querySelector('[data-testid="redirect-example.com"]')!;
    expect(external.textContent).toContain('domain');
    expect(external.querySelector('[data-testid=location]')?.textContent?.trim()).toBe(
      'https://example.org',
    );
    const bucket = page().querySelector('[data-testid="redirect-example.de"]')!;
    expect(bucket.querySelector('a')?.getAttribute('href')).toBe('/static-hosting/site');
    expect(button('Create redirect').getAttribute('href')).toBe('/redirects/create');
  });

  it('deletes a redirect after a second click only', async () => {
    await open('/redirects');
    http.expectOne('/api/v1/redirects?limit=500').flush(pageOf([toExternal, toBucket]));
    await harness.fixture.whenStable();

    const external = page().querySelector('[data-testid="redirect-example.com"]')!;
    button('Delete the redirect of example.com').click();
    await harness.fixture.whenStable();
    http.expectNone({ method: 'DELETE' });
    button('Keep', external).click();
    await harness.fixture.whenStable();
    button('Delete the redirect of example.com').click();
    await harness.fixture.whenStable();
    button('Delete', external).click();

    http
      .expectOne({ method: 'DELETE', url: '/api/v1/redirects/example.com' })
      .flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/v1/redirects?limit=500').flush(pageOf([toBucket]));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid="redirect-example.com"]')).toBeNull();
  });

  it('redirects a domain to an external domain on its own page', async () => {
    await open('/redirects/create');
    http.expectOne('/api/v1/static-hosting?limit=500').flush(pageOf([]));
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
    expect(url()).toBe('/redirects');
    http.expectOne('/api/v1/redirects?limit=500').flush(pageOf([toExternal]));
  });

  it('offers the buckets served at a domain and shows what the backend rejected', async () => {
    await open('/redirects/create');
    http.expectOne('/api/v1/static-hosting?limit=500').flush(
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
