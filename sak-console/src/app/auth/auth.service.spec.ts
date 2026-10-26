import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService, SESSION_STORAGE_KEY, safeRedirect } from './auth.service';

describe('safeRedirect', () => {
  it('keeps paths below the console', () => {
    expect(safeRedirect('/')).toBe('/');
    expect(safeRedirect('/buckets?tab=1#top')).toBe('/buckets?tab=1#top');
  });

  it('falls back to the console root for everything else', () => {
    for (const redirect of [
      null,
      undefined,
      '',
      'buckets',
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

    const request = http.expectOne('/api/auth/login');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ email: 'admin@example.org', redirect: '/' });
    request.flush(null, { status: 202, statusText: 'Accepted' });
    expect(done).toBe(true);
  });

  it('stores the session it exchanged the login token for', () => {
    const auth = create();
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    let redirect: string | undefined;

    auth.signIn('login-token').subscribe((value) => (redirect = value));
    const request = http.expectOne('/api/auth/exchange');
    expect(request.request.body).toEqual({ token: 'login-token' });
    request.flush({
      sessionToken: 'session-token',
      email: 'admin@example.org',
      expiresAt,
      redirect: '/buckets',
    });

    expect(redirect).toBe('/buckets');
    expect(auth.token()).toBe('session-token');
    expect(auth.email()).toBe('admin@example.org');
    expect(JSON.parse(localStorage.getItem(SESSION_STORAGE_KEY)!)).toEqual({
      token: 'session-token',
      email: 'admin@example.org',
      expiresAt,
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

    expect(create().isLoggedIn()).toBe(true);
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

    TestBed.resetTestingModule();
    localStorage.setItem(SESSION_STORAGE_KEY, '{not json');
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);
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
