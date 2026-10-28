import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { pageHelpers } from '../testing/page';
import { HealthPage } from './health-page';

describe('HealthPage', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'health', component: HealthPage }]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  const { page, button, open } = pageHelpers(() => harness);

  const card = (name: string) => page().querySelector(`[data-testid="health-${name}"]`)!;

  it('shows the status of every health check and checks again on demand', async () => {
    await open('/health');
    expect(page().textContent).toContain('Checking…');
    expect(button('Check again').disabled).toBe(true);
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

  it('tells when the health can not be checked', async () => {
    await open('/health');
    http.expectOne('/api/v1/health').flush(null, { status: 403, statusText: 'Forbidden' });
    await harness.fixture.whenStable();
    expect(page().querySelector('[role=alert]')?.textContent?.trim()).toBe(
      'Your role does not allow to see the health.',
    );
    expect(button('Check again').disabled).toBe(false);
  });
});
