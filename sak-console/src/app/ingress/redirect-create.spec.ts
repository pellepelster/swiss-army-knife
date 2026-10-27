import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { RedirectCreate } from './redirect-create';

const CREATED = '2026-10-27T08:00:00Z';

@Component({ template: '' })
class Elsewhere {}

// what a list endpoint answers with the items
const pageOf = <T>(items: T[]) => ({ items, pagination: { offset: 0, limit: 500, total: items.length } });

describe('RedirectCreate', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'redirects', component: Elsewhere },
          { path: 'redirects/new', component: RedirectCreate },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const open = async (buckets: object[] = []) => {
    const harness = await RouterTestingHarness.create('/redirects/new');
    const page = harness.routeNativeElement!;
    http.expectOne('/api/v1/buckets?limit=500').flush(pageOf(buckets));
    await harness.fixture.whenStable();
    const button = (text: string) =>
      [...page.querySelectorAll<HTMLElement>('button, a')].find(
        (b) => b.textContent?.trim() === text,
      )! as HTMLButtonElement;
    const type = async (selector: string, value: string) => {
      const input = page.querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
      input.value = value;
      input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
      await harness.fixture.whenStable();
    };
    return { harness, page, button, type };
  };

  it('redirects a domain to an external domain and goes back to the redirects', async () => {
    const { harness, page, button, type } = await open();
    expect(page.querySelector('[role=dialog]')).toBeNull();

    await type('#source-domain', 'example.com');
    expect(button('Add redirect').disabled).toBe(true);
    await type('#target-domain-name', 'example.org');
    expect(button('Add redirect').disabled).toBe(false);
    button('Add redirect').click();

    const request = http.expectOne({ method: 'POST', url: '/api/v1/redirects' });
    expect(request.request.body).toEqual({
      sourceDomain: 'example.com',
      target: { type: 'domain', domain: 'example.org' },
    });
    request.flush(
      {
        sourceDomain: 'example.com',
        target: { type: 'domain', domain: 'example.org' },
        location: 'example.org',
        createdAt: CREATED,
      },
      { status: 201, statusText: 'Created' },
    );
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/redirects');
  });

  it('offers the buckets served at a domain as targets', async () => {
    const { harness, page, button, type } = await open([
      {
        name: 'site',
        domains: ['site.example.org'],
        createdAt: CREATED,
        statistics: { computing: true, failed: false },
      },
      { name: 'private', domains: [], createdAt: CREATED, statistics: { computing: true, failed: false } },
    ]);

    page.querySelector<HTMLInputElement>('#target-bucket')!.click();
    await harness.fixture.whenStable();
    const options = [...page.querySelectorAll('#target-bucket-name option')].map((option) =>
      option.textContent?.trim(),
    );
    expect(options).toEqual(['Choose a bucket', 'site (site.example.org)']);

    await type('#source-domain', 'example.de');
    await type('#target-bucket-name', 'site');
    button('Add redirect').click();

    const request = http.expectOne({ method: 'POST', url: '/api/v1/redirects' });
    expect(request.request.body).toEqual({ sourceDomain: 'example.de', target: { type: 'bucket', bucket: 'site' } });
    request.flush(
      { errors: [{ message: 'The domain is redirected already.' }] },
      { status: 409, statusText: 'Conflict' },
    );
    await harness.fixture.whenStable();

    expect(page.querySelector('[data-testid=create-error]')?.textContent?.trim()).toBe(
      'The domain is redirected already.',
    );
    expect(TestBed.inject(Router).url).toBe('/redirects/new');
  });

  it('goes back to the redirects on cancel', async () => {
    const { harness, button } = await open();
    button('Cancel').click();
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/redirects');
  });
});
