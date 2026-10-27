import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SITES_RETRY_DELAY_MS, SitesService } from './sites.service';

describe('SitesService', () => {
  let http: HttpTestingController;
  let sites: SitesService;

  const response = {
    sites: [{ id: 's3', domains: ['s3.pelle.io'] }],
    ranges: [],
    defaultRange: '24h',
  };

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    sites = TestBed.inject(SitesService);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('loads the sites once', () => {
    sites.load();
    sites.load();
    http.expectOne('/api/v1/logs').flush(response);
    sites.load();

    expect(sites.sites()).toEqual(response);
  });

  it('loads the sites again after a while when that failed', () => {
    sites.load();
    http.expectOne('/api/v1/logs').flush(null, { status: 503, statusText: 'Service Unavailable' });
    expect(sites.sites()).toBeNull();

    vi.advanceTimersByTime(SITES_RETRY_DELAY_MS);
    http.expectOne('/api/v1/logs').flush(response);

    expect(sites.sites()).toEqual(response);
  });

  it('stops loading when the session is rejected', () => {
    sites.load();
    http.expectOne('/api/v1/logs').flush(null, { status: 401, statusText: 'Unauthorized' });

    vi.advanceTimersByTime(SITES_RETRY_DELAY_MS);
    http.expectNone('/api/v1/logs');
  });
});
