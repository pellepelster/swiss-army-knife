import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { authInterceptor } from '../auth/auth.interceptor';

const info = (version: string | null) => ({
  name: 'alpha',
  dnsMode: 'domain',
  userMode: 'multi',
  version,
});

describe('DownloadsPage', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  // without a session: the link opens on a phone
  beforeEach(async () => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create('/downloads');
  });

  afterEach(() => http.verify());

  const page = () => harness.routeNativeElement!;

  it('links the apk of each android app in the version the SAK instance runs', async () => {
    expect(TestBed.inject(Router).url).toBe('/downloads');
    http.expectOne('/api/v1/info').flush(info('1.4.2'));
    await harness.fixture.whenStable();

    expect(page().querySelector('h1')?.textContent).toContain('Android apps');
    expect(
      [...page().querySelectorAll<HTMLAnchorElement>('[data-testid=apk]')].map((link) => [
        link.textContent?.trim(),
        link.getAttribute('href'),
      ]),
    ).toEqual([
      [
        'Download Notes',
        'https://github.com/pellepelster/swiss-army-knife/releases/download/v1.4.2/sak-notes-1.4.2.apk',
      ],
      [
        'Download Grocery',
        'https://github.com/pellepelster/swiss-army-knife/releases/download/v1.4.2/sak-grocery-1.4.2.apk',
      ],
      [
        'Download Todo',
        'https://github.com/pellepelster/swiss-army-knife/releases/download/v1.4.2/sak-todo-1.4.2.apk',
      ],
    ]);
    expect(page().textContent).toContain('Version 1.4.2');
  });

  it('a SAK instance without a release has no apps to download, it links the releases', async () => {
    http.expectOne('/api/v1/info').flush(info(null));
    await harness.fixture.whenStable();

    expect(page().querySelector('[data-testid=apk]')).toBeNull();
    expect(page().textContent).toContain(
      'This SAK instance runs no release, there is no app to download.',
    );
    expect(
      page().querySelector('a[href="https://github.com/pellepelster/swiss-army-knife/releases"]'),
    ).not.toBeNull();
  });

  it('says when the version could not be loaded', async () => {
    http.expectOne('/api/v1/info').flush(null, { status: 502, statusText: 'Bad Gateway' });
    await harness.fixture.whenStable();

    expect(page().textContent).toContain('The version of the SAK instance could not be loaded.');
  });
});
