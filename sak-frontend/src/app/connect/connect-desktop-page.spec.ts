import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { authInterceptor } from '../auth/auth.interceptor';
import { LEAVE_PAGE } from '../auth/auth.service';
import { pageHelpers, signedIn } from '../testing/page';
import { connectCode, desktopCallback, desktopRequest } from './connect-desktop-page';

const CHALLENGE = 'ZtNPunH49FD35FWYhT5Tv8I7vRKQJ8uxMaL0_9eHjNA';
const STATE = 'state-of-the-app-1234';
const URL_OF_APP = `/connect-desktop?port=49152&state=${STATE}&challenge=${CHALLENGE}`;
const params = (query: string) => new URLSearchParams(query);

describe('desktopRequest', () => {
  it('takes a port, a state and a challenge', () => {
    expect(desktopRequest(params(`port=49152&state=${STATE}&challenge=${CHALLENGE}`))).toEqual({
      port: 49152,
      state: STATE,
      challenge: CHALLENGE,
    });
  });

  it.each([
    `state=${STATE}&challenge=${CHALLENGE}`,
    `port=80&state=${STATE}&challenge=${CHALLENGE}`,
    `port=65536&state=${STATE}&challenge=${CHALLENGE}`,
    `port=4915a&state=${STATE}&challenge=${CHALLENGE}`,
    `port=49152@evil.example.org&state=${STATE}&challenge=${CHALLENGE}`,
    `port=49152&state=short&challenge=${CHALLENGE}`,
    `port=49152&state=${STATE}&challenge=short`,
    `port=49152&state=${STATE}`,
  ])('refuses %s', (query) => {
    expect(desktopRequest(params(query))).toBeNull();
  });

  it('always leads to the loopback', () => {
    const request = { port: 49152, state: STATE, challenge: CHALLENGE };
    expect(desktopCallback(request, 'a.b.c')).toBe(
      `http://127.0.0.1:49152/callback?state=${STATE}&token=a.b.c`,
    );
    expect(desktopCallback(request, null)).toBe(
      `http://127.0.0.1:49152/callback?state=${STATE}&error=cancelled`,
    );
  });
});

describe('connectCode', () => {
  it('is the code the desktop app computes', async () => {
    // the same vector as session.rs of sak-desktop-shared
    expect(await connectCode(CHALLENGE)).toBe('DCF-ACE');
  });
});

describe('ConnectDesktopPage', () => {
  let http: HttpTestingController;
  let left: string[];

  beforeEach(() => {
    localStorage.clear();
    left = [];
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: LEAVE_PAGE, useValue: (url: string) => left.push(url) },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  // opens the page and waits for its code
  async function open(url = URL_OF_APP) {
    const harness = await RouterTestingHarness.create(url);
    await connectCode(CHALLENGE);
    await harness.fixture.whenStable();
    return { harness, ...pageHelpers(() => harness) };
  }

  it('asks for nothing before the user confirms, then hands the token to the app', async () => {
    signedIn('desktop@example.org', ['user']);
    const { harness, button, text } = await open();
    http.expectNone('/api/v1/auth/connect');
    expect(text('warning')).toContain('Connect it only if you just started the sign-in');
    expect(text('code')).toBe('DCF-ACE');

    button('Connect').click();
    const request = http.expectOne('/api/v1/auth/connect');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ challenge: CHALLENGE });
    request.flush({
      link: 'https://sak.example.org/connect#token=a.b.c',
      id: 'code-1',
      expiresAt: '2099-01-01T00:00:00Z',
    });
    await harness.fixture.whenStable();
    expect(left).toEqual([`http://127.0.0.1:49152/callback?state=${STATE}&token=a.b.c`]);
  });

  it('tells the app when the user cancels', async () => {
    signedIn('desktop@example.org', ['user']);
    const { button } = await open();
    button('Cancel').click();
    http.expectNone('/api/v1/auth/connect');
    expect(left).toEqual([`http://127.0.0.1:49152/callback?state=${STATE}&error=cancelled`]);
  });

  it('shows a link that is not valid, without buttons', async () => {
    signedIn('desktop@example.org', ['user']);
    const { text, button } = await open('/connect-desktop?port=80');
    expect(text('error')).toContain('not valid');
    expect(button('Connect')).toBeUndefined();
  });

  it('shows a failed request next to the buttons', async () => {
    signedIn('desktop@example.org', ['user']);
    const { harness, button, text } = await open();
    button('Connect').click();
    http
      .expectOne('/api/v1/auth/connect')
      .flush(null, { status: 429, statusText: 'Too Many Requests' });
    await harness.fixture.whenStable();
    expect(text('error')).toBe('Too many codes, please try again later.');
    expect(left).toEqual([]);
  });

  it('sends a signed out user to the login, which comes back with the request of the app', async () => {
    await open();
    http.match(() => true).forEach((request) => request.flush({}));
    expect(TestBed.inject(Router).url).toBe(`/login?redirect=${encodeURIComponent(URL_OF_APP)}`);
  });
});
