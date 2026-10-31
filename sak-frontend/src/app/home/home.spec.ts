import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { signedIn } from '../testing/page';
import { routes } from '../app.routes';
import { authInterceptor } from '../auth/auth.interceptor';
import { SESSION_STORAGE_KEY } from '../auth/auth.service';

const signIn = () => signedIn('pelle@pelle.io');

describe('Home', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
  });

  afterEach(() => localStorage.clear());

  it('shows the login without a session and comes back afterwards', async () => {
    const harness = await RouterTestingHarness.create('/notes');
    expect(TestBed.inject(Router).url).toBe('/login?redirect=%2Fnotes');
    expect(harness.routeNativeElement!.querySelector('h1')?.textContent).toBe('Sign in');
  });

  it('lists the apps, and opens the notes at /notes', async () => {
    signIn();
    const harness = await RouterTestingHarness.create('/');
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/auth/who-am-i')
      .flush({ username: 'pelle@pelle.io', expiresAt: '2099-01-01T00:00:00Z', roles: ['admin'] });
    await harness.fixture.whenStable();
    const page = harness.routeNativeElement!;
    expect(page.querySelector('[data-testid=username]')?.textContent).toBe('pelle@pelle.io');
    const tiles = (section: string) =>
      [...page.querySelectorAll<HTMLAnchorElement>(`nav[aria-label="${section}"] a`)].map(
        (link) => [
          link.querySelector('[data-testid=app-name]')?.textContent,
          link.getAttribute('href'),
        ],
      );
    expect(tiles('Apps')).toEqual([
      ['Notes', '/notes'],
      ['Static hosting', '/static-hosting'],
      ['Redirects', '/redirects'],
    ]);
    expect(tiles('Maintenance')).toEqual([
      ['Health', '/health'],
      ['Settings', '/settings'],
    ]);
    // the console is gone
    expect(page.querySelector('a[href="/console"]')).toBeNull();
    const links = [...page.querySelectorAll<HTMLAnchorElement>('nav a')];

    links[0].click();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/notes');
    expect(harness.routeNativeElement!.querySelector('h1')?.textContent).toBe('Notes');
    const http = TestBed.inject(HttpTestingController);
    expect(
      http.expectOne('/api/v1/notes?offset=0&limit=50').request.headers.get('Authorization'),
    ).toBe('Bearer session-token');
  });

  it('shows the address the backend takes the session for, not the stored one', async () => {
    signIn();
    const harness = await RouterTestingHarness.create('/');
    const page = harness.routeNativeElement!;
    // nothing until the backend answered
    expect(page.querySelector('[data-testid=username]')?.textContent).toBe('');

    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/who-am-i');
    expect(request.request.headers.get('Authorization')).toBe('Bearer session-token');
    request.flush({ username: 'admin', expiresAt: '2099-01-01T00:00:00Z', roles: ['admin'] });
    await harness.fixture.whenStable();

    expect(page.querySelector('[data-testid=username]')?.textContent).toBe('admin');
  });

  it('signs out', async () => {
    signIn();
    const harness = await RouterTestingHarness.create('/');
    [...harness.routeNativeElement!.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === 'Sign out')!
      .click();
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/login');
    expect(localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('leads unknown paths to the apps', async () => {
    signIn();
    await RouterTestingHarness.create('/unknown');
    expect(TestBed.inject(Router).url).toBe('/');
  });
});
