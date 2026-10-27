import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { authInterceptor } from '../auth/auth.interceptor';
import { SESSION_STORAGE_KEY } from '../auth/auth.service';

const signIn = () =>
  localStorage.setItem(
    SESSION_STORAGE_KEY,
    JSON.stringify({
      token: 'session-token',
      email: 'pelle@pelle.io',
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      roles: ['admin'],
    }),
  );

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
    const page = harness.routeNativeElement!;
    expect(page.querySelector('[data-testid=email]')?.textContent).toBe('pelle@pelle.io');
    const tiles = (section: string) =>
      [...page.querySelectorAll<HTMLAnchorElement>(`nav[aria-label="${section}"] a`)].map(
        (link) => [link.querySelector('.name')?.textContent, link.getAttribute('href')],
      );
    expect(tiles('Apps')).toEqual([
      ['Notes', '/notes'],
      ['Static hosting', '/static-hosting'],
      ['Redirects', '/redirects'],
    ]);
    // the console is gone
    expect(page.querySelector('a[href="/console"]')).toBeNull();
    const links = [...page.querySelectorAll<HTMLAnchorElement>('nav a')];

    links[0].click();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/notes');
    expect(harness.routeNativeElement!.querySelector('h1')?.textContent).toBe('Notes');
    const http = TestBed.inject(HttpTestingController);
    expect(http.expectOne('/api/v1/notes?limit=500').request.headers.get('Authorization')).toBe(
      'Bearer session-token',
    );
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
