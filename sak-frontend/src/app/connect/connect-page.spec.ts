import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import jsQR from 'jsqr';
import { routes } from '../app.routes';
import { authInterceptor } from '../auth/auth.interceptor';
import { pageHelpers, signedIn } from '../testing/page';
import { POLL_MS } from './connect-page';

const LINK =
  'https://sak.example.org/connect#token=eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiJjb25uZWN0In0.c2lnbmF0dXJl';
const T0 = Date.parse('2026-11-12T10:00:00Z');
const code = (id = 'code-1', link = LINK) => ({
  link,
  id,
  expiresAt: new Date(T0 + 120_000).toISOString(),
});

// decodes the qr code the page shows, as a scanner sees it: the modules of its svg painted dark on
// light, 4 pixels each
function decode(svg: SVGElement): string | null {
  const modules = Number(svg.getAttribute('viewBox')!.split(' ')[2]);
  const scale = 4;
  const width = modules * scale;
  const pixels = new Uint8ClampedArray(width * width * 4).fill(255);
  const path = svg.querySelector('path')!.getAttribute('d')!;
  for (const [, x, y] of path.matchAll(/M(\d+) (\d+)h1v1h-1z/g)) {
    for (let dy = 0; dy < scale; dy++) {
      for (let dx = 0; dx < scale; dx++) {
        const offset = ((Number(y) * scale + dy) * width + Number(x) * scale + dx) * 4;
        pixels.fill(0, offset, offset + 3);
      }
    }
  }
  return jsQR(pixels, width, width)?.data ?? null;
}

describe('ConnectPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(T0);
    localStorage.clear();
    signedIn('connect@example.org', ['user']);
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
    vi.useRealTimers();
  });

  // opens the page, its request of a code still open
  async function open() {
    const harness = await RouterTestingHarness.create('/connect-app');
    return { harness, ...pageHelpers(() => harness) };
  }

  const answer = (body = code(), headers: Record<string, string> = {}) => {
    const request = http.expectOne('/api/v1/auth/connect');
    expect(request.request.method).toBe('POST');
    request.flush(body, { headers });
  };

  async function advance(harness: RouterTestingHarness, ms: number) {
    vi.advanceTimersByTime(ms);
    harness.fixture.detectChanges();
    await harness.fixture.whenStable();
  }

  it('is in the menu of the index page and leads to the page', async () => {
    const harness = await RouterTestingHarness.create('/');
    http.expectOne('/api/v1/auth/who-am-i').flush({
      username: 'connect@example.org',
      expiresAt: '2099-01-01T00:00:00Z',
      roles: ['user'],
    });
    http.expectOne('/api/v1/info').flush({ name: 'alpha', dnsMode: 'domain', userMode: 'multi' });
    await harness.fixture.whenStable();
    // the index page asks for no code
    http.expectNone('/api/v1/auth/connect');

    const entries = [
      ...harness.routeNativeElement!.querySelectorAll<HTMLElement>('[role=menu] [role=menuitem]'),
    ];
    expect(entries.map((entry) => entry.textContent?.trim())).toEqual([
      'Connect an app',
      'Sign out',
    ]);
    expect(entries[0].querySelector('i')?.className).toContain('fa-qrcode');
    entries[0].click();
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/connect-app');
    answer();
    await harness.fixture.whenStable();
    expect(harness.routeNativeElement!.querySelector('h1')?.textContent).toBe('Connect an app');
    harness.fixture.destroy();
  });

  it('shows the qr code of the link, never the link, with the instructions and the warning', async () => {
    const { harness, page, text } = await open();
    expect(text('qr')).toBeUndefined();
    expect(page().textContent).toContain('Loading…');
    answer();
    await harness.fixture.whenStable();

    const svg = page().querySelector<SVGElement>('[data-testid=qr]')!;
    expect(svg.getAttribute('aria-label')).toBe('QR code to connect an app');
    expect(decode(svg)).toBe(LINK);
    expect(page().innerHTML).not.toContain('token=');
    expect(page().textContent).toContain('tap “Scan QR code”');
    expect(text('warning')).toBe(
      'Whoever scans this code signs in as you. Show it only to your own phone.',
    );
    expect(text('countdown')).toBe('Valid for 2:00');
    harness.fixture.destroy();
  });

  it('counts down in the time of the backend and expires without a new code', async () => {
    const { harness, text, button } = await open();
    // the clock of the browser is 30 seconds ahead of the one of the backend
    answer(
      { ...code(), expiresAt: new Date(T0 - 30_000 + 120_000).toISOString() },
      { Date: new Date(T0 - 30_000).toUTCString() },
    );
    await harness.fixture.whenStable();
    expect(text('countdown')).toBe('Valid for 2:00');

    await advance(harness, 1_000);
    expect(text('countdown')).toBe('Valid for 1:59');
    http.match(() => true).forEach((poll) => poll.flush({ used: false }));

    for (let second = 2; second <= 120; second++) {
      await advance(harness, 1_000);
      // the poll of the last second is cancelled with the expiry
      http
        .match('/api/v1/auth/connect/code-1')
        .filter((poll) => !poll.cancelled)
        .forEach((poll) => poll.flush({ used: false }));
    }
    expect(text('qr')).toBeUndefined();
    expect(text('expired')).toBe('The code expired.');

    // nothing more: no polling, no new code
    await advance(harness, 60_000);
    http.expectNone(() => true);
    expect(button('Show another code').disabled).toBe(false);
  });

  it('shows another code on request', async () => {
    const { harness, page, button } = await open();
    answer();
    await harness.fixture.whenStable();

    button('Show another code').click();
    await harness.fixture.whenStable();
    expect(button('Show another code').disabled).toBe(true);
    const other = 'https://sak.example.org/connect#token=another';
    answer(code('code-2', other));
    await harness.fixture.whenStable();
    expect(decode(page().querySelector<SVGElement>('[data-testid=qr]')!)).toBe(other);

    // only the new code is polled
    await advance(harness, POLL_MS);
    http.expectOne('/api/v1/auth/connect/code-2').flush({ used: false });
    harness.fixture.destroy();
  });

  it('polls until an app used the code, then says it is connected', async () => {
    const { harness, text } = await open();
    answer();
    await harness.fixture.whenStable();

    await advance(harness, POLL_MS);
    http.expectOne('/api/v1/auth/connect/code-1').flush({ used: false });
    await advance(harness, POLL_MS);
    // a failed poll tries again
    http
      .expectOne('/api/v1/auth/connect/code-1')
      .flush(null, { status: 503, statusText: 'Unavailable' });
    await harness.fixture.whenStable();
    expect(text('qr')).toBeDefined();
    await advance(harness, POLL_MS);
    http.expectOne('/api/v1/auth/connect/code-1').flush({ used: true });
    await harness.fixture.whenStable();

    expect(text('qr')).toBeUndefined();
    expect(text('connected')).toBe('Connected');
    await advance(harness, 10 * POLL_MS);
    http.expectNone(() => true);
  });

  it('expires the code the backend no longer has', async () => {
    const { harness, text } = await open();
    answer();
    await harness.fixture.whenStable();
    await advance(harness, POLL_MS);
    http
      .expectOne('/api/v1/auth/connect/code-1')
      .flush(null, { status: 404, statusText: 'Not Found' });
    await harness.fixture.whenStable();

    expect(text('expired')).toBe('The code expired.');
    await advance(harness, 10 * POLL_MS);
    http.expectNone(() => true);
  });

  it('stops polling when the page is left', async () => {
    const { harness } = await open();
    answer();
    await harness.fixture.whenStable();
    await harness.navigateByUrl('/downloads');
    http.expectOne('/api/v1/info').flush({ name: 'alpha', dnsMode: 'domain', userMode: 'multi' });

    await advance(harness, 10 * POLL_MS);
    http.expectNone(() => true);
  });

  it('says when there were too many codes, next to the toolbar', async () => {
    const { harness, page, text } = await open();
    http
      .expectOne('/api/v1/auth/connect')
      .flush(null, { status: 429, statusText: 'Too Many Requests' });
    await harness.fixture.whenStable();

    expect(text('error')).toBe('Too many codes, please try again later.');
    expect(page().querySelector('header')!.nextElementSibling?.getAttribute('data-testid')).toBe(
      'error',
    );
    expect(text('qr')).toBeUndefined();
  });

  it('says when the code could not be created, and tries again on request', async () => {
    const { harness, text, button } = await open();
    http.expectOne('/api/v1/auth/connect').flush(null, { status: 500, statusText: 'Error' });
    await harness.fixture.whenStable();
    expect(text('error')).toBe('The code could not be created, please try again.');

    button('Show another code').click();
    answer();
    await harness.fixture.whenStable();
    expect(text('error')).toBeUndefined();
    expect(text('qr')).toBeDefined();
    harness.fixture.destroy();
  });
});
