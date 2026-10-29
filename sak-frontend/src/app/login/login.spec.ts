import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService, LoginMethods, SESSION_STORAGE_KEY } from '../auth/auth.service';
import { Login } from './login';

@Component({ template: 'notes' })
class Notes {}

describe('Login', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'login', component: Login },
          { path: 'notes', component: Notes },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  // the login page at `url`, the backend answering `methods`
  const open = async (url: string, methods: LoginMethods) => {
    const harness = await RouterTestingHarness.create(url);
    http.expectOne('/api/v1/auth/login/methods').flush(methods);
    await harness.fixture.whenStable();
    return harness;
  };

  const both: LoginMethods = { email: true, password: true };

  const fill = async (harness: RouterTestingHarness, selector: string, value: string) => {
    const page = harness.routeNativeElement!;
    const input = page.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();

    const event = new SubmitEvent('submit', { cancelable: true });
    page.querySelector('form')!.dispatchEvent(event);
    // a native submit would reload the page
    expect(event.defaultPrevented).toBe(true);
    return page;
  };

  const click = async (harness: RouterTestingHarness, testId: string) => {
    harness.routeNativeElement!.querySelector<HTMLAnchorElement>(`[data-testid=${testId}]`)!.click();
    await harness.fixture.whenStable();
  };

  it('requests a link for the address and the page the login started from', async () => {
    const harness = await open('/login?redirect=%2Fnotes', both);

    const page = await fill(harness, '#email', 'admin@example.org');
    const request = http.expectOne('/api/v1/auth/login/link');
    expect(request.request.body).toEqual({
      email: 'admin@example.org',
      redirect: '/notes',
    });
    request.flush(null, { status: 202, statusText: 'Accepted' });
    await harness.fixture.whenStable();

    expect(page.querySelector('[data-testid=login-sent]')?.textContent).toContain(
      'admin@example.org',
    );
  });

  it('reports too many attempts', async () => {
    const harness = await open('/login', both);

    const page = await fill(harness, '#email', 'admin@example.org');
    http
      .expectOne('/api/v1/auth/login/link')
      .flush(null, { status: 429, statusText: 'Too Many Requests' });
    await harness.fixture.whenStable();

    expect(page.querySelector('[role=alert]')?.textContent).toContain('Too many login attempts');
  });

  it('shows the e-mail login with a link to the password login when both are there', async () => {
    const harness = await open('/login', both);
    const page = harness.routeNativeElement!;

    expect(page.querySelector('#email')).not.toBeNull();
    expect(page.querySelector('#password')).toBeNull();

    await click(harness, 'use-password');
    expect(page.querySelector('#password')).not.toBeNull();
    expect(page.querySelector('#email')).toBeNull();

    // and back
    await click(harness, 'use-email');
    expect(page.querySelector('#email')).not.toBeNull();
  });

  it('shows only the password login without a mail server', async () => {
    const harness = await open('/login', { email: false, password: true });
    const page = harness.routeNativeElement!;

    expect(page.querySelector('#password')).not.toBeNull();
    expect(page.querySelector('#email')).toBeNull();
    expect(page.querySelector('[data-testid=use-email]')).toBeNull();
  });

  it('shows no password link without an admin password', async () => {
    const harness = await open('/login', { email: true, password: false });

    expect(harness.routeNativeElement!.querySelector('[data-testid=use-password]')).toBeNull();
  });

  it('signs the admin in with the password and goes where the login started', async () => {
    const harness = await open('/login?redirect=%2Fnotes', { email: false, password: true });

    await fill(harness, '#password', 'a password of sixteen');
    const request = http.expectOne('/api/v1/auth/login/password');
    expect(request.request.body).toEqual({ password: 'a password of sixteen', redirect: '/notes' });
    request.flush({
      sessionToken: 'session',
      email: 'admin@example.org',
      expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      roles: ['admin'],
      redirect: '/notes',
    });
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/notes');
    expect(TestBed.inject(AuthService).token()).toBe('session');
    expect(JSON.parse(localStorage.getItem(SESSION_STORAGE_KEY)!).email).toBe('admin@example.org');
  });

  it('says so when the password is wrong, and empties the field', async () => {
    const harness = await open('/login', { email: false, password: true });

    const page = await fill(harness, '#password', 'wrong');
    http
      .expectOne('/api/v1/auth/login/password')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    await harness.fixture.whenStable();

    expect(page.querySelector('[role=alert]')?.textContent).toContain('The password is not right');
    expect(page.querySelector<HTMLInputElement>('#password')!.value).toBe('');
  });

  it('says so when the login methods can not be loaded', async () => {
    const harness = await RouterTestingHarness.create('/login');
    http
      .expectOne('/api/v1/auth/login/methods')
      .flush(null, { status: 500, statusText: 'Internal Server Error' });
    await harness.fixture.whenStable();

    expect(harness.routeNativeElement!.querySelector('[role=alert]')?.textContent).toContain(
      'could not be loaded',
    );
  });
});
