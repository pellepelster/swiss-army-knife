import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService, SESSION_STORAGE_KEY } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;

  beforeEach(() => {
    localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({
        token: 'session-token',
        email: 'admin@example.org',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      }),
    );
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('sends the session token to the api', () => {
    client.get('/api/v1/auth/me').subscribe();

    expect(http.expectOne('/api/v1/auth/me').request.headers.get('Authorization')).toBe(
      'Bearer session-token',
    );
  });

  it('keeps the session token from the public api and other origins', () => {
    for (const url of [
      '/api/v1/auth/login',
      '/api/v1/auth/exchange',
      'https://evil.example.com/api/x',
      '/assets/x',
    ]) {
      client.post(url, {}).subscribe();
      expect(http.expectOne(url).request.headers.has('Authorization')).toBe(false);
    }
  });

  it('ends the session and leads to the login when the api rejects the token', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    client.get('/api/v1/auth/me').subscribe({ error: () => undefined });
    http.expectOne('/api/v1/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { redirect: '/' } });
  });

  it('leads to the login once when several requests are rejected', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    client.get('/api/v1/notes').subscribe({ error: () => undefined });
    client.get('/api/v1/labels').subscribe({ error: () => undefined });
    http.expectOne('/api/v1/notes').flush(null, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/labels').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(navigate).toHaveBeenCalledTimes(1);
  });
});
