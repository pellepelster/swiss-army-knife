import { provideHttpClient } from '@angular/common/http';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { signedIn } from '../testing/page';
import { authGuard } from './auth.guard';

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
    signedIn();
    const harness = await RouterTestingHarness.create();

    await harness.navigateByUrl('/protected');

    expect(harness.routeNativeElement?.textContent).toBe('protected');
  });
});
