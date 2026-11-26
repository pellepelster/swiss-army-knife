import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService, LEAVE_PAGE, SESSION_STORAGE_KEY, safeRedirect } from './auth.service';

describe('safeRedirect', () => {
  it('keeps paths below the frontend', () => {
    expect(safeRedirect('/')).toBe('/');
    expect(safeRedirect('/notes?tab=1#top')).toBe('/notes?tab=1#top');
  });

  it('falls back to the frontend root for everything else', () => {
    for (const redirect of [
      null,
      undefined,
      '',
      'notes',
      'https://evil.example.com/',
      '//evil.example.com/',
      '/\\evil.example.com',
      '/ evil',
      '/\tevil',
    ]) {
      expect(safeRedirect(redirect)).toBe('/');
    }
  });
});

describe('AuthService', () => {
  let http: HttpTestingController;

  const create = () => TestBed.inject(AuthService);

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests a login link with a sanitized redirect', () => {
    let done = false;
    create()
      .requestLogin('admin@example.org', 'https://evil.example.com/')
      .subscribe(() => (done = true));

    const request = http.expectOne('/api/v1/auth/login/link');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      email: 'admin@example.org',
      redirect: '/',
    });
    request.flush(null, { status: 202, statusText: 'Accepted' });
    expect(done).toBe(true);
  });

  it('stores the session it exchanged the login token for', () => {
    const auth = create();
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    let redirect: string | undefined;

    auth.signIn('login-token').subscribe((value) => (redirect = value));
    const request = http.expectOne('/api/v1/auth/login/exchange');
    expect(request.request.body).toEqual({ token: 'login-token' });
    request.flush({
      sessionToken: 'session-token',
      email: 'admin@example.org',
      expiresAt,
      roles: ['admin'],
      redirect: '/notes',
    });

    expect(redirect).toBe('/notes');
    expect(auth.token()).toBe('session-token');
    expect(JSON.parse(localStorage.getItem(SESSION_STORAGE_KEY)!)).toEqual({
      token: 'session-token',
      email: 'admin@example.org',
      expiresAt,
      roles: ['admin'],
    });
  });

  it('restores the stored session', () => {
    localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({
        token: 'session-token',
        email: 'admin@example.org',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      }),
    );

    const auth = create();
    expect(auth.isLoggedIn()).toBe(true);
    expect(auth.token()).toBe('session-token');
  });

  it('treats expired and malformed sessions as signed out', () => {
    localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({
        token: 'session-token',
        email: 'admin@example.org',
        expiresAt: new Date(Date.now() - 1).toISOString(),
      }),
    );
    expect(create().token()).toBeNull();
    // and not kept where it was (SEC-46)
    expect(localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();

    TestBed.resetTestingModule();
    localStorage.setItem(SESSION_STORAGE_KEY, '{not json');
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);

    TestBed.resetTestingModule();
    localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ token: 'session-token', email: 'admin@example.org', expiresAt: 'never' }),
    );
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);
  });

  it('follows a sign out or another address of another tab, a new token of the same quietly (SEC-43)', () => {
    const leftTo: string[] = [];
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LEAVE_PAGE, useValue: (url: string) => leftTo.push(url) },
      ],
    });
    const session = (email: string, token: string) =>
      JSON.stringify({
        token,
        email,
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
        roles: ['user'],
      });
    localStorage.setItem(SESSION_STORAGE_KEY, session('anna@example.org', 'one'));
    const auth = create();
    // what the other tab wrote, then told to this one
    const otherTab = (value: string | null) => {
      if (value === null) localStorage.removeItem(SESSION_STORAGE_KEY);
      else localStorage.setItem(SESSION_STORAGE_KEY, value);
      window.dispatchEvent(
        new StorageEvent('storage', { key: SESSION_STORAGE_KEY, newValue: value }),
      );
    };

    otherTab(session('anna@example.org', 'two'));
    expect(auth.token()).toBe('two');
    expect(leftTo).toEqual([]);

    otherTab(session('bob@example.org', 'three'));
    expect(auth.token()).toBe('three');
    expect(leftTo).toHaveLength(1);

    otherTab(null);
    expect(auth.token()).toBeNull();
    expect(leftTo[1]).toBe('/login');

    // another key changes nothing
    window.dispatchEvent(new StorageEvent('storage', { key: 'other', newValue: 'x' }));
    expect(leftTo).toHaveLength(2);
  });

  it('forgets the session on logout', () => {
    localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({
        token: 'session-token',
        email: 'admin@example.org',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      }),
    );
    const auth = create();

    auth.logout();

    expect(auth.isLoggedIn()).toBe(false);
    expect(localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });
});
