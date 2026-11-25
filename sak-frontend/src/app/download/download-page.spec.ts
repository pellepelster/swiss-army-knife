import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { authInterceptor } from '@sak/core';

const info = (version: string | null) => ({
  name: 'alpha',
  dnsMode: 'domain',
  userMode: 'multi',
  version,
});

describe('DownloadPage', () => {
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
    harness = await RouterTestingHarness.create('/download');
  });

  afterEach(() => http.verify());

  const page = () => harness.routeNativeElement!;

  // the label and the address of every file of a group
  const files = (group: string) =>
    [
      ...page().querySelectorAll<HTMLAnchorElement>(
        `[data-testid=group-${group}] [data-testid=file]`,
      ),
    ].map((link) => [link.textContent?.trim(), link.getAttribute('href')]);

  it('links the apk of each android app in the version the SAK instance runs', async () => {
    expect(TestBed.inject(Router).url).toBe('/download');
    http.expectOne('/api/v1/info').flush(info('1.4.2'));
    await harness.fixture.whenStable();

    expect(page().querySelector('h1')?.textContent).toContain('Download');
    expect(page().querySelector('[data-testid=group-android] h2')?.textContent).toContain(
      'Android apps',
    );
    expect(
      [
        ...page().querySelectorAll<HTMLAnchorElement>(
          '[data-testid=group-android] [data-testid=file]',
        ),
      ].map((link) => [link.textContent?.trim(), link.getAttribute('href')]),
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

  it('links SAK Backup for every platform it is released for', async () => {
    http.expectOne('/api/v1/info').flush(info('1.4.2'));
    await harness.fixture.whenStable();

    expect(page().querySelector('[data-testid=group-desktop] h2')?.textContent).toContain(
      'Desktop apps',
    );
    expect(
      page().querySelector('[data-testid=app-sak-backup] [data-testid=app-name]')?.textContent,
    ).toContain('SAK Backup');
    const release = 'https://github.com/pellepelster/swiss-army-knife/releases/download/v1.4.2';
    expect(files('desktop')).toEqual([
      ['Windows', `${release}/sak-backup-windows-x86_64-1.4.2.zip`],
      ['macOS', `${release}/sak-backup-macos-universal-1.4.2.zip`],
      ['Linux (deb)', `${release}/sak-backup-linux-x86_64-1.4.2.deb`],
      ['Linux (rpm)', `${release}/sak-backup-linux-x86_64-1.4.2.rpm`],
      ['Linux (AppImage)', `${release}/sak-backup-linux-x86_64-1.4.2.AppImage`],
    ]);
  });

  it('a SAK instance without a release has no apps to download, it links the releases', async () => {
    http.expectOne('/api/v1/info').flush(info(null));
    await harness.fixture.whenStable();

    expect(page().querySelector('[data-testid=file]')).toBeNull();
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

  it('leads from the address of the android apps to the download', async () => {
    http.expectOne('/api/v1/info').flush(info('1.4.2'));
    await harness.fixture.whenStable();
    await harness.navigateByUrl('/downloads');
    expect(TestBed.inject(Router).url).toBe('/download');
  });
});
