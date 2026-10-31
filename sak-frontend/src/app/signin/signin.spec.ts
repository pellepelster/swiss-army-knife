import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../auth/auth.service';
import { Signin, groceryAppLink } from './signin';

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

  it('hands the token of a login of the android app on to the app, without using it', async () => {
    const harness = await RouterTestingHarness.create('/signin?token=a+token/1&client=android');

    expect(TestBed.inject(Location).path()).toBe('/signin');
    http.expectNone('/api/v1/auth/login/exchange');
    const link = harness.routeNativeElement!.querySelector<HTMLAnchorElement>('a.btn')!;
    expect(link.textContent).toContain('Open in the app');
    expect(link.getAttribute('href')).toBe(groceryAppLink('a token/1'));
    expect(link.getAttribute('href')).toBe(
      'intent://signin?token=a%20token%2F1#Intent;scheme=io.pelle.sak.grocery;package=io.pelle.sak.grocery;end',
    );
    expect(TestBed.inject(AuthService).token()).toBeNull();
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
