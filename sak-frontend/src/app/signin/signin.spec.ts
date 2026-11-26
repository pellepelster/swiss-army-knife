import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService, LOGIN_REQUEST_KEY, SESSION_STORAGE_KEY } from '@sak/core';
import { AndroidClient, Signin, androidAppLink } from './signin';

@Component({ template: 'notes' })
class Notes {}

describe('Signin', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'signin', component: Signin },
          { path: 'notes', component: Notes },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('exchanges the login token and continues where the login started', async () => {
    const harness = await RouterTestingHarness.create(
      '/signin?token=login-token&redirect=%2Fignored',
    );

    const request = http.expectOne('/api/v1/auth/login/exchange');
    expect(request.request.body).toEqual({ token: 'login-token' });
    request.flush({
      sessionToken: 'session-token',
      email: 'admin@example.org',
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      redirect: '/notes',
    });
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/notes');
    expect(TestBed.inject(AuthService).token()).toBe('session-token');
  });

  for (const [client, pkg, title] of [
    ['grocery-android', 'io.pelle.sak.grocery', 'Sign in to the grocery app'],
    ['notes-android', 'io.pelle.sak.notes', 'Sign in to the notes app'],
  ]) {
    it(`hands the token of a login of ${client} on to its app, without using it`, async () => {
      const harness = await RouterTestingHarness.create(`/signin?token=a+token/1&client=${client}`);

      expect(TestBed.inject(Location).path()).toBe('/signin');
      http.expectNone('/api/v1/auth/login/exchange');
      expect(harness.routeNativeElement!.querySelector('h1')!.textContent).toContain(title);
      const link = harness.routeNativeElement!.querySelector<HTMLAnchorElement>('a.btn')!;
      expect(link.textContent).toContain('Open in the app');
      expect(link.getAttribute('href')).toBe(androidAppLink(client as AndroidClient, 'a token/1'));
      expect(link.getAttribute('href')).toBe(
        `intent://signin?token=a%20token%2F1#Intent;scheme=${pkg};package=${pkg};end`,
      );
      expect(TestBed.inject(AuthService).token()).toBeNull();
    });
  }

  // a login token as the backend makes it, for anna; its signature is checked by the backend only
  const annaToken = `e30.${btoa(JSON.stringify({ sub: 'anna@example.org' })).replace(/=+$/, '')}.sig`;
  const asked = (email: string, ago = 0) =>
    localStorage.setItem(LOGIN_REQUEST_KEY, JSON.stringify({ email, at: Date.now() - ago }));
  const signedInAs = (email: string) =>
    localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({
        token: 'old',
        email,
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
        roles: ['admin'],
      }),
    );
  const exchanged = async (harness: RouterTestingHarness) => {
    http.expectOne('/api/v1/auth/login/exchange').flush({
      sessionToken: 'anna-session',
      email: 'anna@example.org',
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      redirect: '/notes',
    });
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/notes');
  };

  it('a link this browser asked for signs in at once (SEC-7)', async () => {
    asked(' Anna@Example.org ');
    const harness = await RouterTestingHarness.create(`/signin?token=${annaToken}`);
    await exchanged(harness);
    expect(localStorage.getItem(LOGIN_REQUEST_KEY)).toBeNull();
  });

  it('a link asked for elsewhere, or too long ago, asks whom it signs in (SEC-7)', async () => {
    asked('anna@example.org', 16 * 60 * 1000);
    const harness = await RouterTestingHarness.create(`/signin?token=${annaToken}`);
    http.expectNone('/api/v1/auth/login/exchange');
    const page = harness.routeNativeElement!;
    expect(page.querySelector('h1')!.textContent).toContain('Sign in as anna@example.org?');
    expect(page.textContent).toContain('This link was not asked for in this browser.');
    expect(page.querySelector('[data-testid=replaces]')).toBeNull();
    expect(page.querySelector('a.btn')!.getAttribute('href')).toBe('/login');

    page.querySelector<HTMLButtonElement>('button.btn-primary')!.click();
    await exchanged(harness);
  });

  it('a link of another address than the one signed in asks, also when it was asked for (SEC-7)', async () => {
    signedInAs('admin@example.org');
    asked('anna@example.org');
    const harness = await RouterTestingHarness.create(`/signin?token=${annaToken}`);
    http.expectNone('/api/v1/auth/login/exchange');
    const page = harness.routeNativeElement!;
    expect(page.querySelector('[data-testid=replaces]')!.textContent).toContain(
      'You are signed in as admin@example.org now, that session ends.',
    );
    // cancel stays signed in as before
    expect(page.querySelector('a.btn')!.getAttribute('href')).toBe('/');
    expect(TestBed.inject(AuthService).token()).toBe('old');
  });

  it('signs in itself when the client names no android app', async () => {
    await RouterTestingHarness.create('/signin?token=a-token&client=android');

    http.expectOne('/api/v1/auth/login/exchange');
  });

  it('drops the login token from the address bar and reports a rejected link', async () => {
    const harness = await RouterTestingHarness.create('/signin?token=used-token');

    expect(TestBed.inject(Location).path()).toBe('/signin');
    http
      .expectOne('/api/v1/auth/login/exchange')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    await harness.fixture.whenStable();

    expect(harness.routeNativeElement?.querySelector('[role=alert]')?.textContent).toContain(
      'invalid, expired or was already used',
    );
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);
  });

  it('reports links without a token', async () => {
    const harness = await RouterTestingHarness.create('/signin');
    await harness.fixture.whenStable();

    expect(harness.routeNativeElement?.querySelector('[role=alert]')).not.toBeNull();
  });
});
