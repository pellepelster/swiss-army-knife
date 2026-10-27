import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { BucketDetail, COPIED_MS } from './bucket';
import { POLL_INTERVAL_MS } from './buckets';

const SECRET = { accessKeyId: 'AKIAEXAMPLEEXAMPLE23', secretAccessKey: 'c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0' };

const bucket = (statistics: object) => ({
  name: 'backups',
  domains: ['backups.example.org'],
  createdAt: '2026-10-27T07:00:00Z',
  statistics,
});

describe('BucketDetail', () => {
  let http: HttpTestingController;
  let writeText: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 's3/:name', component: BucketDetail }], withComponentInputBinding()),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  const open = async () => {
    const harness = await RouterTestingHarness.create('/s3/backups');
    const page = harness.routeNativeElement!;
    const button = (name: string) =>
      [...page.querySelectorAll<HTMLButtonElement>('button')].find(
        (b) => b.textContent?.trim() === name || b.getAttribute('aria-label') === name,
      )!;
    const icon = (name: string) => button(name).querySelector('app-icon')?.getAttribute('data-icon');
    const text = (testId: string) => page.querySelector(`[data-testid="${testId}"]`)?.textContent?.trim();
    return { harness, page, button, icon, text };
  };

  it('shows the bucket and asks again while it is still being counted', async () => {
    const { harness, text } = await open();

    http.expectOne('/api/v1/buckets/backups').flush(bucket({ computing: true, failed: false }));
    await harness.fixture.whenStable();
    expect(text('domains')).toBe('backups.example.org');

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    http
      .expectOne('/api/v1/buckets/backups')
      .flush(bucket({ computing: false, failed: false, objects: 3, bytes: 2500, computedAt: '2026-10-27T08:00:00Z' }));
    await harness.fixture.whenStable();

    expect(text('objects')).toBe('3');
    expect(text('bytes')).toBe('2.5 kB');
  });

  it('retrieves the credentials only when asked, hides the secret and copies them', async () => {
    const { harness, button, icon, text } = await open();
    http.expectOne('/api/v1/buckets/backups').flush(bucket({ computing: false, failed: true }));
    await harness.fixture.whenStable();
    http.expectNone('/api/v1/buckets/backups/secret');

    button('Retrieve credentials').click();
    http.expectOne('/api/v1/buckets/backups/secret').flush(SECRET);
    await harness.fixture.whenStable();

    expect(text('access-key')).toBe(SECRET.accessKeyId);
    expect(text('secret-key')).not.toContain(SECRET.secretAccessKey);
    expect(icon('Show secret key')).toBe('eye');
    button('Show secret key').click();
    await harness.fixture.whenStable();
    expect(text('secret-key')).toBe(SECRET.secretAccessKey);
    expect(icon('Hide secret key')).toBe('eye-slash');

    button('Copy access key').click();
    await harness.fixture.whenStable();
    expect(writeText).toHaveBeenCalledWith(SECRET.accessKeyId);
    button('Copy secret key').click();
    await harness.fixture.whenStable();
    expect(writeText).toHaveBeenLastCalledWith(SECRET.secretAccessKey);
    expect(icon('Copy secret key')).toBe('check');
    expect(icon('Copy access key')).toBe('clipboard');

    await vi.advanceTimersByTimeAsync(COPIED_MS);
    await harness.fixture.whenStable();
    expect(icon('Copy secret key')).toBe('clipboard');
  });

  it('reports an unknown bucket', async () => {
    const { harness, page } = await open();
    http.expectOne('/api/v1/buckets/backups').flush(null, { status: 404, statusText: 'Not Found' });
    await harness.fixture.whenStable();

    expect(page.querySelector('[role=alert]')?.textContent).toContain('no such bucket');
  });

  it('changes the domains in a dialog, the name stays', async () => {
    const { harness, page, button, text } = await open();
    http.expectOne('/api/v1/buckets/backups').flush(bucket({ computing: false, failed: true }));
    await harness.fixture.whenStable();

    button('Edit domains').click();
    await harness.fixture.whenStable();
    const dialog = page.querySelector<HTMLElement>('[role=dialog]')!;
    expect(dialog.querySelector<HTMLInputElement>('#edit-bucket-name')!.readOnly).toBe(true);
    expect(dialog.querySelector<HTMLInputElement>('#edit-bucket-name')!.value).toBe('backups');
    const domain = dialog.querySelector<HTMLInputElement>('#domain-0')!;
    expect(domain.value).toBe('backups.example.org');
    domain.value = 'files.example.org';
    domain.dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
    [...dialog.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === 'Save')!.click();

    const request = http.expectOne({ method: 'PUT', url: '/api/v1/buckets/backups/domains' });
    expect(request.request.body).toEqual({ domains: ['files.example.org'] });
    request.flush({ ...bucket({ computing: false, failed: true }), domains: ['files.example.org'] });
    await harness.fixture.whenStable();

    expect(page.querySelector('[role=dialog]')).toBeNull();
    expect(text('domains')).toBe('files.example.org');
  });
});
