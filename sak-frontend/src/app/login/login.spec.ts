import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Login } from './login';

describe('Login', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'login', component: Login }]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const submit = async (harness: RouterTestingHarness, email: string) => {
    const page = harness.routeNativeElement!;
    const input = page.querySelector<HTMLInputElement>('#email')!;
    input.value = email;
    input.dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();

    const event = new SubmitEvent('submit', { cancelable: true });
    page.querySelector('form')!.dispatchEvent(event);
    // a native submit would reload the page
    expect(event.defaultPrevented).toBe(true);
    return page;
  };

  it('requests a link for the address and the page the login started from', async () => {
    const harness = await RouterTestingHarness.create('/login?redirect=%2Fnotes');

    const page = await submit(harness, 'admin@example.org');
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
    const harness = await RouterTestingHarness.create('/login');

    const page = await submit(harness, 'admin@example.org');
    http
      .expectOne('/api/v1/auth/login/link')
      .flush(null, { status: 429, statusText: 'Too Many Requests' });
    await harness.fixture.whenStable();

    expect(page.querySelector('[role=alert]')?.textContent).toContain('Too many login attempts');
  });
});
