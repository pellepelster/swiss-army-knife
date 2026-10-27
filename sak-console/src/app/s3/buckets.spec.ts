import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Component } from '@angular/core';
import { Router } from '@angular/router';
import { Buckets, POLL_INTERVAL_MS } from './buckets';

const CREATED = '2026-10-27T07:00:00Z';

@Component({ template: '' })
class Detail {}

const counted = (objects: number, bytes: number) => ({
  computing: false,
  failed: false,
  objects,
  bytes,
  computedAt: '2026-10-27T08:00:00Z',
});

// what a list endpoint answers with the items
const pageOf = <T>(items: T[]) => ({ items, pagination: { offset: 0, limit: 500, total: items.length } });

// removes the one blank domain the dialog starts with
const dialogRemove = (page: HTMLElement) => page.querySelector<HTMLButtonElement>('[aria-label="Remove domain 1"]')!.click();

describe('Buckets', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([
          { path: 's3', component: Buckets },
          { path: 's3/:name', component: Detail },
        ])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  const open = async () => {
    const harness = await RouterTestingHarness.create('/s3');
    const page = harness.routeNativeElement!;
    const row = (name: string) => page.querySelector(`[data-testid="bucket-${name}"]`)!;
    return { harness, page, row };
  };

  it('lists the buckets with their objects and size', async () => {
    const { harness, row } = await open();

    http.expectOne('/api/v1/buckets?limit=500').flush(pageOf([
      { name: 'pelle.io', domains: ['pelle.io'], createdAt: CREATED, statistics: counted(1234, 2_500_000) },
      { name: 'backups', domains: [], createdAt: CREATED, statistics: counted(0, 0) },
    ]));
    await harness.fixture.whenStable();

    expect(row('pelle.io').querySelector('[data-testid=objects]')?.textContent?.trim()).toBe('1.2K');
    expect(row('pelle.io').querySelector('[data-testid=bytes]')?.textContent?.trim()).toBe('2.5 MB');
    expect(row('backups').querySelector('[data-testid=objects]')?.textContent?.trim()).toBe('0');
    expect(row('backups').textContent).toContain('–');

    // nothing is still being counted, so nothing is asked again
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    http.expectNone('/api/v1/buckets?limit=500');
  });

  it('asks again while a bucket is still being counted', async () => {
    const { harness, row } = await open();

    http.expectOne('/api/v1/buckets?limit=500').flush(pageOf([
      { name: 'pelle.io', domains: [], createdAt: CREATED, statistics: { computing: true, failed: false } },
      { name: 'backups', domains: [], createdAt: CREATED, statistics: { computing: false, failed: true } },
    ]));
    await harness.fixture.whenStable();

    expect(row('pelle.io').textContent).toContain('counting');
    expect(row('backups').textContent).toContain('could not be counted');

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    http.expectOne('/api/v1/buckets?limit=500').flush(pageOf([
      { name: 'pelle.io', domains: [], createdAt: CREATED, statistics: counted(3, 300) },
      { name: 'backups', domains: [], createdAt: CREATED, statistics: { computing: false, failed: true } },
    ]));
    await harness.fixture.whenStable();

    expect(row('pelle.io').querySelector('[data-testid=objects]')?.textContent?.trim()).toBe('3');
  });

  it('reports a role that does not allow the buckets', async () => {
    const { harness, page } = await open();
    http.expectOne('/api/v1/buckets?limit=500').flush(null, { status: 403, statusText: 'Forbidden' });
    await harness.fixture.whenStable();

    expect(page.querySelector('[role=alert]')?.textContent).toContain('does not allow');
  });

  // opens the dialog of the toolbar, its inputs are typed into
  const openDialog = async (page: HTMLElement, harness: RouterTestingHarness) => {
    [...page.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === 'Add bucket')!.click();
    await harness.fixture.whenStable();
    const dialog = page.querySelector<HTMLElement>('[role=dialog]')!;
    const type = async (selector: string, value: string) => {
      const input = dialog.querySelector<HTMLInputElement>(selector)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      await harness.fixture.whenStable();
    };
    const button = (text: string) =>
      [...dialog.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === text)!;
    return { dialog, type, button };
  };

  it('creates a bucket with its domains in the dialog of the toolbar and opens it', async () => {
    const { harness, page } = await open();
    http.expectOne('/api/v1/buckets?limit=500').flush(pageOf([]));
    await harness.fixture.whenStable();

    const { dialog, type, button } = await openDialog(page, harness);
    expect(dialog.getAttribute('aria-label')).toBe('Add bucket');
    await type('#bucket-name', 'No_Bucket');
    expect(button('Add bucket').disabled).toBe(true);
    await type('#bucket-name', 'new-bucket');
    expect(button('Add bucket').disabled).toBe(false);

    await type('#domain-0', 'no domain');
    expect(button('Add bucket').disabled).toBe(true);
    await type('#domain-0', 'example.org');
    button('Add domain').click();
    await harness.fixture.whenStable();
    await type('#domain-1', 'www.example.org');
    button('Add domain').click();
    await harness.fixture.whenStable();
    expect(button('Add bucket').disabled).toBe(false);
    button('Add bucket').click();

    const request = http.expectOne({ method: 'POST', url: '/api/v1/buckets' });
    // the blank third domain is left out
    expect(request.request.body).toEqual({ name: 'new-bucket', domains: ['example.org', 'www.example.org'] });
    request.flush(
      { name: 'new-bucket', domains: ['example.org', 'www.example.org'], createdAt: CREATED, statistics: counted(0, 0) },
      { status: 201, statusText: 'Created' },
    );
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/s3/new-bucket');
  });

  it('shows what the api rejected the bucket for', async () => {
    const { harness, page } = await open();
    http.expectOne('/api/v1/buckets?limit=500').flush(pageOf([]));
    await harness.fixture.whenStable();

    const { type, button } = await openDialog(page, harness);
    await type('#bucket-name', 'backups');
    dialogRemove(page);
    await harness.fixture.whenStable();
    button('Add bucket').click();
    http
      .expectOne({ method: 'POST', url: '/api/v1/buckets' })
      .flush({ errors: [{ message: 'A bucket of the name exists.' }] }, { status: 409, statusText: 'Conflict' });
    await harness.fixture.whenStable();

    expect(page.querySelector('[data-testid=create-error]')?.textContent?.trim()).toBe('A bucket of the name exists.');
    expect(TestBed.inject(Router).url).toBe('/s3');
  });

  it('links every bucket to its page and shows none of its keys', async () => {
    const { harness, row } = await open();
    http.expectOne('/api/v1/buckets?limit=500').flush(pageOf([{ name: 'backups', domains: [], createdAt: CREATED, statistics: counted(0, 0) }]));
    await harness.fixture.whenStable();

    expect(row('backups').querySelector('a')?.getAttribute('href')).toBe('/s3/backups');
    expect(row('backups').textContent).not.toMatch(/key|secret/i);
  });
});
