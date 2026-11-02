import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { authInterceptor } from '../auth/auth.interceptor';
import { PAGE_LOADER } from '../service-worker-updates';

const info = (version: string | null) => ({
  name: 'alpha',
  dnsMode: 'domain',
  userMode: 'multi',
  version,
});

describe('AppDownload', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  const page = { reload: vi.fn(), load: vi.fn() };

  // without a session: the page is public
  beforeEach(async () => {
    localStorage.clear();
    page.load.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: PAGE_LOADER, useValue: page },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create('/grocery/download/app');
  });

  afterEach(() => http.verify());

  const text = () => harness.routeNativeElement!.textContent!;

  it('downloads the apk of the release the deployment runs, without a session', async () => {
    expect(TestBed.inject(Router).url).toBe('/grocery/download/app');
    http.expectOne('/api/v1/info').flush(info('1.4.2'));
    await harness.fixture.whenStable();

    const apk =
      'https://github.com/pellepelster/swiss-army-knife/releases/download/v1.4.2/sak-grocery-1.4.2.apk';
    expect(page.load).toHaveBeenCalledWith(apk);
    expect(text()).toContain('The download of the app has started.');
    expect(
      harness.routeNativeElement!.querySelector('[data-testid=apk]')!.getAttribute('href'),
    ).toBe(apk);
  });

  it('downloads the apk of the notes app on its own page', async () => {
    http.expectOne('/api/v1/info').flush(info('1.4.2'));
    await harness.fixture.whenStable();
    page.load.mockReset();

    await harness.navigateByUrl('/notes/download/app');
    http.expectOne('/api/v1/info').flush(info('1.4.2'));
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/notes/download/app');
    expect(page.load).toHaveBeenCalledWith(
      'https://github.com/pellepelster/swiss-army-knife/releases/download/v1.4.2/sak-notes-1.4.2.apk',
    );
    expect(text()).toContain('The notes app for Android');
    expect(harness.routeNativeElement!.querySelector('img')!.getAttribute('src')).toBe(
      'icons/notes.svg',
    );
  });

  it('a deployment without a release has no app to download', async () => {
    http.expectOne('/api/v1/info').flush(info(null));
    await harness.fixture.whenStable();
    expect(page.load).not.toHaveBeenCalled();
    expect(text()).toContain('This deployment runs no release, there is no app to download.');
  });

  it('says when the version could not be loaded and links the releases', async () => {
    http.expectOne('/api/v1/info').flush(null, { status: 502, statusText: 'Bad Gateway' });
    await harness.fixture.whenStable();
    expect(page.load).not.toHaveBeenCalled();
    expect(text()).toContain('The version of the deployment could not be loaded.');
    expect(harness.routeNativeElement!.querySelector('a')!.getAttribute('href')).toBe(
      'https://github.com/pellepelster/swiss-army-knife/releases',
    );
  });
});
