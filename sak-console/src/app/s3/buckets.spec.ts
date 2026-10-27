import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Buckets, POLL_INTERVAL_MS } from './buckets';

const counted = (objects: number, bytes: number) => ({
  computing: false,
  failed: false,
  objects,
  bytes,
  computedAt: '2026-10-27T08:00:00Z',
});

describe('Buckets', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: 's3', component: Buckets }])],
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

    http.expectOne('/api/v1/buckets').flush([
      { name: 'pelle.io', domains: ['pelle.io'], statistics: counted(1234, 2_500_000) },
      { name: 'backups', domains: [], statistics: counted(0, 0) },
    ]);
    await harness.fixture.whenStable();

    expect(row('pelle.io').querySelector('[data-testid=objects]')?.textContent?.trim()).toBe('1.2K');
    expect(row('pelle.io').querySelector('[data-testid=bytes]')?.textContent?.trim()).toBe('2.5 MB');
    expect(row('backups').querySelector('[data-testid=objects]')?.textContent?.trim()).toBe('0');
    expect(row('backups').textContent).toContain('–');

    // nothing is still being counted, so nothing is asked again
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    http.expectNone('/api/v1/buckets');
  });

  it('asks again while a bucket is still being counted', async () => {
    const { harness, row } = await open();

    http.expectOne('/api/v1/buckets').flush([
      { name: 'pelle.io', domains: [], statistics: { computing: true, failed: false } },
      { name: 'backups', domains: [], statistics: { computing: false, failed: true } },
    ]);
    await harness.fixture.whenStable();

    expect(row('pelle.io').textContent).toContain('counting');
    expect(row('backups').textContent).toContain('could not be counted');

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    http.expectOne('/api/v1/buckets').flush([
      { name: 'pelle.io', domains: [], statistics: counted(3, 300) },
      { name: 'backups', domains: [], statistics: { computing: false, failed: true } },
    ]);
    await harness.fixture.whenStable();

    expect(row('pelle.io').querySelector('[data-testid=objects]')?.textContent?.trim()).toBe('3');
  });

  it('reports a role that does not allow the buckets', async () => {
    const { harness, page } = await open();
    http.expectOne('/api/v1/buckets').flush(null, { status: 403, statusText: 'Forbidden' });
    await harness.fixture.whenStable();

    expect(page.querySelector('[role=alert]')?.textContent).toContain('does not allow');
  });
});
