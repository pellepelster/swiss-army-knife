import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { pageHelpers } from '@sak/testing';
import { healthRedirect } from './status.routes';
import { StatusPage } from './status-page';

describe('StatusPage', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'status', component: StatusPage }, healthRedirect]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  const { page, button, open, url } = pageHelpers(() => harness);

  const counted = '2026-11-24T10:00:00.000Z';
  const status = (health: string, storages: { name: string; bytes: number; items: number }[]) => ({
    health,
    storage: {
      storages: storages.map((storage) => ({ ...storage, computedAt: counted })),
      total: storages.length
        ? {
            bytes: storages.reduce((sum, each) => sum + each.bytes, 0),
            items: storages.reduce((sum, each) => sum + each.items, 0),
            computedAt: counted,
          }
        : null,
    },
  });

  const card = (name: string) => page().querySelector(`[data-testid="health-${name}"]`)!;

  it('shows the status of every health check and checks again on demand', async () => {
    await open('/status');
    expect(page().textContent).toContain('Checking…');
    expect(button('Check again').disabled).toBe(true);
    http.expectOne('/api/v1/status').flush(status('UNHEALTHY', []));
    http.expectOne('/api/v1/health').flush([
      {
        name: 'Bucket domains',
        state: 'HEALTHY',
        message: 'the 2 domains of the buckets point to the server',
        details: [
          { name: 'example.com', state: 'HEALTHY', message: null },
          { name: 'site.example.org', state: 'HEALTHY', message: 'no answer from 9.9.9.9' },
        ],
      },
      {
        name: 'Redirect domains',
        state: 'UNHEALTHY',
        message: '1 of 1 domains of the redirects do not point to the server',
        details: [
          { name: 'old.example.com', state: 'UNHEALTHY', message: '1.1.1.1 has no A, no AAAA' },
        ],
      },
    ]);
    await harness.fixture.whenStable();

    expect(card('Bucket domains').querySelector('[data-testid=state]')?.textContent?.trim()).toBe(
      'Healthy',
    );
    expect(card('Redirect domains').querySelector('[data-testid=state]')?.classList).toContain(
      'text-bg-danger',
    );
    expect(
      card('Redirect domains').querySelector('[data-testid=message]')?.textContent?.trim(),
    ).toBe('1 of 1 domains of the redirects do not point to the server');

    // every domain with its own state, and why when there is something to tell
    const detail = (name: string) => page().querySelector(`[data-testid="detail-${name}"]`)!;
    expect(
      [...card('Bucket domains').querySelectorAll('li')].map((row) => [
        row.querySelector('.font-monospace')?.textContent?.trim(),
        row.querySelector('[data-testid=state]')?.textContent?.trim(),
      ]),
    ).toEqual([
      ['example.com', 'Healthy'],
      ['site.example.org', 'Healthy'],
    ]);
    expect(detail('example.com').querySelector('[data-testid=message]')).toBeNull();
    expect(detail('old.example.com').querySelector('[data-testid=state]')?.classList).toContain(
      'text-bg-danger',
    );
    expect(
      detail('old.example.com').querySelector('[data-testid=message]')?.textContent?.trim(),
    ).toBe('1.1.1.1 has no A, no AAAA');

    button('Check again').click();
    await harness.fixture.whenStable();
    http.expectOne('/api/v1/status').flush(status('UNKNOWN', []));
    http
      .expectOne('/api/v1/health')
      .flush([{ name: 'Bucket domains', state: 'UNKNOWN', message: null, details: [] }]);
    await harness.fixture.whenStable();
    expect(card('Bucket domains').querySelector('[data-testid=state]')?.textContent?.trim()).toBe(
      'Unknown',
    );
    expect(card('Bucket domains').querySelector('[data-testid=message]')).toBeNull();
    expect(card('Redirect domains')).toBeNull();
  });

  it('tells when the health or the storage can not be loaded, each on its own', async () => {
    await open('/status');
    http.expectOne('/api/v1/health').flush(null, { status: 403, statusText: 'Forbidden' });
    http.expectOne('/api/v1/status').flush(null, { status: 500, statusText: 'Error' });
    await harness.fixture.whenStable();
    expect(
      [...page().querySelectorAll('[role=alert]')].map((it) => it.textContent?.trim()),
    ).toEqual([
      'Your role does not allow you to see the health.',
      'The storage could not be loaded.',
    ]);
    expect(button('Check again').disabled).toBe(false);
  });

  it('shows the health of the system and what every storage keeps, the largest first, and the total', async () => {
    await open('/status');
    http.expectOne('/api/v1/health').flush([]);
    http.expectOne('/api/v1/status').flush(
      status('HEALTHY', [
        { name: 'restic', bytes: 2_000_000, items: 40 },
        { name: 'files', bytes: 500_000, items: 12 },
        { name: 'other', bytes: 1_000, items: 1 },
      ]),
    );
    await harness.fixture.whenStable();

    expect(
      page().querySelector('[data-testid=overall] [data-testid=state]')?.textContent?.trim(),
    ).toBe('Healthy');
    const rows = [...page().querySelectorAll('[data-testid=storage] tbody tr')].map((row) =>
      [...row.querySelectorAll('td')].slice(0, 3).map((cell) => cell.textContent?.trim()),
    );
    expect(rows).toEqual([
      ['Restic Backups', '2 MB', '40'],
      ['Files', '500 kB', '12'],
      // a storage the page does not know shows by its name
      ['other', '1 kB', '1'],
    ]);
    expect(
      page().querySelector('[data-testid=total] [data-testid=size]')?.textContent?.trim(),
    ).toBe('2.5 MB');
    expect(page().querySelector('canvas')?.getAttribute('aria-label')).toBe('Storage usage');
  });

  it('says when the storages are not counted yet', async () => {
    await open('/status');
    http.expectOne('/api/v1/health').flush([]);
    http.expectOne('/api/v1/status').flush(status('HEALTHY', []));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=counting]')?.textContent?.trim()).toBe(
      'The storage is being counted, check again in a minute.',
    );
    expect(page().querySelector('canvas')).toBeNull();
  });

  it('leads from the address of the health to the status', async () => {
    await open('/health');
    http.expectOne('/api/v1/health').flush([]);
    http.expectOne('/api/v1/status').flush(status('HEALTHY', []));
    await harness.fixture.whenStable();
    expect(url()).toBe('/status');
  });
});
