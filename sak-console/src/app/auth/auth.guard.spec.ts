import { provideHttpClient } from '@angular/common/http';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { authGuard } from './auth.guard';
import { SESSION_STORAGE_KEY } from './auth.service';

@Component({ template: 'protected' })
class Protected {}

@Component({ template: 'login' })
class Login {}

describe('authGuard', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideRouter([
          { path: 'protected', component: Protected, canActivate: [authGuard] },
          { path: 'login', component: Login },
        ]),
      ],
    });
  });

  it('sends signed out users to the login and remembers where they wanted to go', async () => {
    const harness = await RouterTestingHarness.create();

    await harness.navigateByUrl('/protected?tab=1');

    expect(harness.routeNativeElement?.textContent).toBe('login');
    expect(TestBed.inject(Router).url).toBe('/login?redirect=%2Fprotected%3Ftab%3D1');
  });

  it('lets signed in users pass', async () => {
    localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({
        token: 'session-token',
        email: 'admin@example.org',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      }),
    );
    const harness = await RouterTestingHarness.create();

    await harness.navigateByUrl('/protected');

    expect(harness.routeNativeElement?.textContent).toBe('protected');
  });
});
