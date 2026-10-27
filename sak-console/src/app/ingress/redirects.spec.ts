import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Redirects } from './redirects';

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
const pageOf = <T>(items: T[]) => ({ items, pagination: { offset: 0, limit: 500, total: items.length } });

describe('Redirects', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'redirects', component: Redirects }]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const open = async (redirects: object[] = [toExternal, toBucket]) => {
    const harness = await RouterTestingHarness.create('/redirects');
    const page = harness.routeNativeElement!;
    http.expectOne('/api/v1/redirects?limit=500').flush(pageOf(redirects));
    await harness.fixture.whenStable();
    const button = (text: string, root: ParentNode = page) =>
      [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === text)!;
    return { harness, page, button };
  };

  it('lists the redirects with their targets and where they lead', async () => {
    const { page } = await open();

    const external = page.querySelector('[data-testid="redirect-example.com"]')!;
    expect(external.textContent).toContain('domain');
    expect(external.querySelector('[data-testid=location]')?.textContent?.trim()).toBe('https://example.org');
    const bucket = page.querySelector('[data-testid="redirect-example.de"]')!;
    expect(bucket.querySelector('a')?.getAttribute('href')).toBe('/s3/buckets/site');
    expect(bucket.querySelector('[data-testid=location]')?.textContent?.trim()).toBe('https://site.example.org');
  });

  it('adds redirects on their own page', async () => {
    const { page } = await open();

    const add = [...page.querySelectorAll<HTMLAnchorElement>('a')].find((a) => a.textContent?.trim() === 'Add redirect');
    expect(add?.getAttribute('href')).toBe('/redirects/new');
    expect(page.querySelector('[role=dialog]')).toBeNull();
  });

  it('deletes a redirect after a second click', async () => {
    const { harness, page, button } = await open();

    page.querySelector<HTMLButtonElement>('[aria-label="Delete the redirect of example.com"]')!.click();
    await harness.fixture.whenStable();
    http.expectNone({ method: 'DELETE' });
    button('Delete', page.querySelector('[data-testid="redirect-example.com"]')!).click();

    http.expectOne({ method: 'DELETE', url: '/api/v1/redirects/example.com' }).flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/v1/redirects?limit=500').flush(pageOf([toBucket]));
    await harness.fixture.whenStable();
    expect(page.querySelector('[data-testid="redirect-example.com"]')).toBeNull();
  });
});
