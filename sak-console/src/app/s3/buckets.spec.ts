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

  const typeName = async (page: HTMLElement, harness: RouterTestingHarness, name: string) => {
    const input = page.querySelector<HTMLInputElement>('#bucket-name')!;
    input.value = name;
    input.dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
    return page.querySelector<HTMLButtonElement>('form button[type=submit]')!;
  };

  it('creates a bucket and opens it', async () => {
    const { harness, page } = await open();
    http.expectOne('/api/v1/buckets?limit=500').flush(pageOf([]));
    await harness.fixture.whenStable();

    expect((await typeName(page, harness, 'No_Bucket')).disabled).toBe(true);
    const submit = await typeName(page, harness, 'new-bucket');
    expect(submit.disabled).toBe(false);
    submit.click();

    const request = http.expectOne({ method: 'POST', url: '/api/v1/buckets' });
    expect(request.request.body).toEqual({ name: 'new-bucket' });
    request.flush({ name: 'new-bucket', domains: [], createdAt: CREATED, statistics: counted(0, 0) }, { status: 201, statusText: 'Created' });
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/s3/new-bucket');
  });

  it('reports a name that is taken', async () => {
    const { harness, page } = await open();
    http.expectOne('/api/v1/buckets?limit=500').flush(pageOf([]));
    await harness.fixture.whenStable();

    (await typeName(page, harness, 'backups')).click();
    http.expectOne({ method: 'POST', url: '/api/v1/buckets' }).flush(null, { status: 409, statusText: 'Conflict' });
    await harness.fixture.whenStable();

    expect(page.querySelector('[data-testid=create-error]')?.textContent).toContain('exists already');
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
