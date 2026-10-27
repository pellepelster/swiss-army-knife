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
        provideRouter([{ path: 'ingress/redirects', component: Redirects }]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const open = async (redirects: object[] = [toExternal, toBucket]) => {
    const harness = await RouterTestingHarness.create('/ingress/redirects');
    const page = harness.routeNativeElement!;
    http.expectOne('/api/v1/redirects?limit=500').flush(pageOf(redirects));
    await harness.fixture.whenStable();
    const button = (text: string, root: ParentNode = page) =>
      [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === text)!;
    const type = async (selector: string, value: string) => {
      const input = page.querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
      input.value = value;
      input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
      await harness.fixture.whenStable();
    };
    const dialog = () => page.querySelector<HTMLElement>('[role=dialog]');
    const openDialog = async (buckets: object[] = []) => {
      button('Add redirect').click();
      await harness.fixture.whenStable();
      http.expectOne('/api/v1/buckets?limit=500').flush(pageOf(buckets));
      await harness.fixture.whenStable();
    };
    return { harness, page, button, type, dialog, openDialog };
  };

  it('lists the redirects with their targets and where they lead', async () => {
    const { page } = await open();

    const external = page.querySelector('[data-testid="redirect-example.com"]')!;
    expect(external.textContent).toContain('domain');
    expect(external.querySelector('[data-testid=location]')?.textContent?.trim()).toBe('https://example.org');
    const bucket = page.querySelector('[data-testid="redirect-example.de"]')!;
    expect(bucket.querySelector('a')?.getAttribute('href')).toBe('/s3/site');
    expect(bucket.querySelector('[data-testid=location]')?.textContent?.trim()).toBe('https://site.example.org');
  });

  it('redirects a domain to an external domain from the dialog', async () => {
    const { harness, button, type, dialog, openDialog } = await open([]);
    await openDialog();
    expect(dialog()?.getAttribute('aria-label')).toBe('Add redirect');

    const submit = () => button('Add redirect', dialog()!);
    await type('#source-domain', 'example.com');
    expect(submit().disabled).toBe(true);
    await type('#target-domain-name', 'example.org');
    expect(submit().disabled).toBe(false);
    submit().click();

    const request = http.expectOne({ method: 'POST', url: '/api/v1/redirects' });
    expect(request.request.body).toEqual({ sourceDomain: 'example.com', target: { type: 'domain', domain: 'example.org' } });
    request.flush(toExternal, { status: 201, statusText: 'Created' });
    await harness.fixture.whenStable();

    http.expectOne('/api/v1/redirects?limit=500').flush(pageOf([toExternal]));
    await harness.fixture.whenStable();
    expect(dialog()).toBeNull();
  });

  it('offers the buckets served at a domain as targets', async () => {
    const { harness, page, button, type, dialog, openDialog } = await open([]);
    await openDialog([
      { name: 'site', domains: ['site.example.org'], createdAt: CREATED, statistics: { computing: true, failed: false } },
      { name: 'private', domains: [], createdAt: CREATED, statistics: { computing: true, failed: false } },
    ]);

    page.querySelector<HTMLInputElement>('#target-bucket')!.click();
    await harness.fixture.whenStable();
    const options = [...page.querySelectorAll('#target-bucket-name option')].map((option) => option.textContent?.trim());
    expect(options).toEqual(['Choose a bucket', 'site (site.example.org)']);

    await type('#source-domain', 'example.de');
    await type('#target-bucket-name', 'site');
    button('Add redirect', dialog()!).click();

    const request = http.expectOne({ method: 'POST', url: '/api/v1/redirects' });
    expect(request.request.body).toEqual({ sourceDomain: 'example.de', target: { type: 'bucket', bucket: 'site' } });
    request.flush({ errors: [{ message: 'The domain is redirected already.' }] }, { status: 409, statusText: 'Conflict' });
    await harness.fixture.whenStable();

    expect(page.querySelector('[data-testid=create-error]')?.textContent?.trim()).toBe('The domain is redirected already.');
    expect(dialog()).not.toBeNull();
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
