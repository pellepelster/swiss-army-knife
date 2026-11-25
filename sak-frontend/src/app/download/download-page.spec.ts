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

  // the label and the address of every file of an app
  const files = (app: string) =>
    [
      ...page().querySelectorAll<HTMLAnchorElement>(`[data-testid=app-${app}] [data-testid=file]`),
    ].map((link) => [link.textContent?.trim(), link.getAttribute('href')]);

  it('links the apk of each android app in the version the SAK instance runs', async () => {
    expect(TestBed.inject(Router).url).toBe('/download');
    http.expectOne('/api/v1/info').flush(info('1.4.2'));
    await harness.fixture.whenStable();

    expect(page().querySelector('h1')?.textContent).toContain('Download');
    // one list of the apps by their names inside SAK, only the cards: no heading, no text around them,
    // no version
    expect(
      [...page().querySelectorAll('[data-testid=app-name]')].map((it) => it.textContent?.trim()),
    ).toEqual(['Notes', 'Grocery', 'Todo', 'Restic Backup']);
    expect(page().querySelector('h2')).toBeNull();
    expect([...page().querySelectorAll('p')].length).toBe(0);
    // what each app is for, as the home page says it
    expect(
      [...page().querySelectorAll('[data-testid=app-description]')].map((it) =>
        it.textContent?.trim(),
      ),
    ).toEqual([
      'Post-its from text, images or audio',
      'Shopping lists, sharable or private',
      'Tasks to accomplish, together or alone',
      'Backs up your computer with restic',
    ]);
    expect(page().textContent).not.toContain('Version');
    const release = 'https://github.com/pellepelster/swiss-army-knife/releases/download/v1.4.2';
    expect(files('notes')).toEqual([['Android', `${release}/sak-notes-1.4.2.apk`]]);
    expect(files('grocery')).toEqual([['Android', `${release}/sak-grocery-1.4.2.apk`]]);
    expect(files('todo')).toEqual([['Android', `${release}/sak-todo-1.4.2.apk`]]);
  });

  it('links Restic Backup for every platform it is released for', async () => {
    http.expectOne('/api/v1/info').flush(info('1.4.2'));
    await harness.fixture.whenStable();

    const release = 'https://github.com/pellepelster/swiss-army-knife/releases/download/v1.4.2';
    expect(files('restic-backup')).toEqual([
      ['Windows', `${release}/sak-backup-windows-x86_64-1.4.2.zip`],
      ['macOS', `${release}/sak-backup-macos-universal-1.4.2.zip`],
      ['deb (Debian, Ubuntu)', `${release}/sak-backup-linux-x86_64-1.4.2.deb`],
      ['rpm (Fedora, openSUSE)', `${release}/sak-backup-linux-x86_64-1.4.2.rpm`],
      ['AppImage', `${release}/sak-backup-linux-x86_64-1.4.2.AppImage`],
      ['Program only (zip)', `${release}/sak-backup-linux-x86_64-1.4.2.zip`],
    ]);
  });

  it('offers the formats of Linux behind one button, its menu opens and closes', async () => {
    http.expectOne('/api/v1/info').flush(info('1.4.2'));
    await harness.fixture.whenStable();

    const formats = page().querySelector('[data-testid=app-restic-backup] [data-testid=formats]')!;
    const toggle = formats.querySelector<HTMLButtonElement>('button')!;
    const menu = formats.querySelector('[role=menu]')!;
    expect(toggle.textContent?.trim()).toBe('Linux');
    expect(menu.classList).not.toContain('show');
    toggle.click();
    await harness.fixture.whenStable();
    expect(menu.classList).toContain('show');
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    // a click elsewhere closes it
    page()
      .querySelector('h1')!
      .dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await harness.fixture.whenStable();
    expect(menu.classList).not.toContain('show');
  });

  it('a SAK instance without a release has no apps to download, it links the releases', async () => {
    http.expectOne('/api/v1/info').flush(info(null));
    await harness.fixture.whenStable();

    expect(page().querySelector('[data-testid=file]')).toBeNull();
    expect(page().textContent).toContain(
      'This SAK instance does not run a release, so there is no app to download.',
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
