import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, Subject, map, merge } from 'rxjs';
import { LiveLogs, MAX_ENTRIES } from './live-logs';
import { LiveLogError, LogEntry, LogsService, parseEvents } from './logs.service';

const entry = (path: string, status = 200, site = 'pelle.io'): LogEntry => ({
  time: '2026-10-26T21:00:00Z',
  site,
  path,
  status,
});

describe('parseEvents', () => {
  it('returns the data of complete events and keeps the incomplete rest', () => {
    expect(parseEvents(': following\n\ndata: {"a":1}\n\ndata: {"b"')).toEqual({
      data: ['{"a":1}'],
      rest: 'data: {"b"',
    });
    expect(parseEvents(': keepalive\n\n')).toEqual({ data: [], rest: '' });
  });
});

describe('LiveLogs', () => {
  let http: HttpTestingController;
  // one stream per follow, with the site it follows
  let streams: { site: string; entries: Subject<LogEntry>; chunks: Subject<LogEntry[]>; closed: boolean }[];
  // how many of the next streams the server refuses
  let refusals: number;

  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    streams = [];
    refusals = 0;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [
            { path: 'logs/live/:site', component: LiveLogs },
            { path: 'elsewhere', children: [] },
          ],
          withComponentInputBinding(),
        ),
      ],
    });
    const logs = TestBed.inject(LogsService);
    vi.spyOn(logs, 'follow').mockImplementation(
      (site: string, opened: () => void = () => undefined) =>
        new Observable<LogEntry[]>((subscriber) => {
          const stream = {
            site,
            entries: new Subject<LogEntry>(),
            chunks: new Subject<LogEntry[]>(),
            closed: false,
          };
          streams.push(stream);
          // the server refuses the stream, it never opens
          if (refusals > 0) {
            refusals--;
            subscriber.error(new LiveLogError(503));
            return;
          }
          opened();
          const subscription = merge(stream.entries.pipe(map((entry) => [entry])), stream.chunks).subscribe(subscriber);
          return () => {
            stream.closed = true;
            subscription.unsubscribe();
          };
        }),
    );
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  const open = async (url = '/logs/live/all') => {
    const harness = await RouterTestingHarness.create(url);
    http.expectOne('/api/v1/logs').flush({
      sites: [
        { id: 'pelle.io', domains: ['pelle.io'] },
        { id: 's3', domains: ['s3.pelle.io'] },
      ],
      ranges: [],
      defaultRange: '24h',
    });
    await harness.fixture.whenStable();
    const page = harness.routeNativeElement!;
    const rows = () =>
      [...page.querySelectorAll('tbody tr')].map((row) =>
        [...row.querySelectorAll('td')].map((cell) => cell.textContent?.trim()),
      );
    const toggle = () => page.querySelector<HTMLButtonElement>('[data-testid=follow-toggle]')!;
    const state = () => page.querySelector('[data-testid=live-state]')?.textContent?.trim();
    return { harness, page, rows, toggle, state };
  };

  it('lists the followed entries newest first with time, site, path and status', async () => {
    const { harness, rows, state, toggle } = await open();

    expect(streams.map((stream) => stream.site)).toEqual(['all']);
    expect(state()).toBe('Live');
    expect(toggle().textContent).toContain('Pause');
    expect(rows()).toEqual([['Waiting for requests…']]);

    streams[0].entries.next(entry('/a'));
    streams[0].entries.next(entry('/missing', 404, 's3'));
    await harness.fixture.whenStable();

    expect(rows().map((row) => row.slice(1))).toEqual([
      ['s3', '/missing', '404'],
      ['pelle.io', '/a', '200'],
    ]);
    expect(rows()[0][0]).toMatch(/^2026-10-2[67] \d\d:00:00$/);
  });

  it('adds a chunk of entries at once, newest first', async () => {
    const { harness, rows } = await open();

    streams[0].chunks.next([entry('/1'), entry('/2'), entry('/3')]);
    await harness.fixture.whenStable();

    expect(rows().map((row) => row[2])).toEqual(['/3', '/2', '/1']);
  });

  it('keeps the newest entries only', async () => {
    const { harness, page, rows } = await open();

    for (let i = 0; i < MAX_ENTRIES + 5; i++) {
      streams[0].entries.next(entry(`/${i}`));
    }
    await harness.fixture.whenStable();

    expect(page.querySelector('[data-testid=live-range]')?.textContent?.trim()).toBe(
      `1–50 of ${MAX_ENTRIES}`,
    );
    expect(rows()[0][2]).toBe(`/${MAX_ENTRIES + 4}`);
  });

  it('shows the entries in pages of the selected size', async () => {
    const { harness, page, rows } = await open();
    for (let i = 1; i <= 120; i++) {
      streams[0].entries.next(entry(`/${i}`));
    }
    await harness.fixture.whenStable();
    const range = () => page.querySelector('[data-testid=live-range]')?.textContent?.trim();
    const pageButton = (label: string) =>
      [...page.querySelectorAll<HTMLButtonElement>('.pagination button')].find(
        (button) => button.getAttribute('aria-label') === label || button.textContent?.trim() === label,
      )!;
    const current = () => page.querySelector('[aria-current=page]')?.textContent?.trim();

    // 50 rows by default, newest first
    expect(rows().length).toBe(50);
    expect(rows()[0][2]).toBe('/120');
    expect(range()).toBe('1–50 of 120');
    expect([...page.querySelectorAll('.pagination button')].map((b) => b.textContent?.trim())).toEqual([
      '«', '‹', '1', '2', '3', '›', '»',
    ]);
    expect(pageButton('Previous page').disabled).toBe(true);

    pageButton('Next page').click();
    await harness.fixture.whenStable();
    expect(current()).toBe('2');
    expect(rows()[0][2]).toBe('/70');
    expect(range()).toBe('51–100 of 120');

    pageButton('Last page').click();
    await harness.fixture.whenStable();
    expect(rows().length).toBe(20);
    expect(range()).toBe('101–120 of 120');
    expect(pageButton('Next page').disabled).toBe(true);

    // a new size starts at the first page and is remembered
    const size = page.querySelector<HTMLSelectElement>('#live-page-size')!;
    expect([...size.options].map((option) => option.value)).toEqual(['25', '50', '100']);
    size.value = '25';
    size.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();
    expect(rows().length).toBe(25);
    expect(current()).toBe('1');
    expect(range()).toBe('1–25 of 120');
    expect(localStorage.getItem('sak.live.pageSize')).toBe('25');

    pageButton('5').click();
    await harness.fixture.whenStable();
    expect([...page.querySelectorAll('.pagination button')].map((b) => b.textContent?.trim())).toEqual([
      '«', '‹', '1', '2', '3', '4', '5', '›', '»',
    ]);
    expect(range()).toBe('101–120 of 120');
  });

  it('starts with the remembered page size and the first page of a new search', async () => {
    localStorage.setItem('sak.live.pageSize', '25');
    const { harness, page, rows } = await open('/logs/live/all?q=missing');

    http
      .expectOne('/api/v1/logs/all/search?q=missing')
      .flush(Array.from({ length: 60 }, (_, i) => entry(`/missing/${i}`, 404)));
    await harness.fixture.whenStable();
    expect(rows().length).toBe(25);
    page.querySelector<HTMLButtonElement>('[aria-label="Last page"]')!.click();
    await harness.fixture.whenStable();
    expect(rows().length).toBe(10);

    await TestBed.inject(Router).navigateByUrl('/logs/live/all?q=other');
    await harness.fixture.whenStable();
    http.expectOne('/api/v1/logs/all/search?q=other').flush(
      Array.from({ length: 60 }, (_, i) => entry(`/other/${i}`, 404)),
    );
    await harness.fixture.whenStable();

    expect(page.querySelector('[aria-current=page]')?.textContent?.trim()).toBe('1');
    expect(rows()[0][2]).toBe('/other/0');
  });

  it('pauses and follows again from the start', async () => {
    const { harness, rows, toggle, state } = await open();
    streams[0].entries.next(entry('/a'));
    await harness.fixture.whenStable();

    toggle().click();
    await harness.fixture.whenStable();

    expect(streams[0].closed).toBe(true);
    expect(state()).toBe('Paused');
    expect(toggle().textContent).toContain('Follow');
    expect(rows().length).toBe(1);

    toggle().click();
    await harness.fixture.whenStable();

    expect(streams.length).toBe(2);
    expect(state()).toBe('Live');
    expect(rows()).toEqual([['Waiting for requests…']]);
  });

  it('stops following and searches the stored entries while there is a search text', async () => {
    const { harness, page, rows, toggle, state } = await open();
    const search = page.querySelector<HTMLInputElement>('input[type=search]')!;

    search.value = 'missing';
    search.dispatchEvent(new Event('input'));
    await vi.advanceTimersByTimeAsync(300);
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/logs/live/all?q=missing');
    expect(streams[0].closed).toBe(true);
    http.expectOne('/api/v1/logs/all/search?q=missing').flush([entry('/missing', 404)]);
    await harness.fixture.whenStable();

    expect(state()).toBe('1 found');
    expect(rows().map((row) => row.slice(1))).toEqual([['pelle.io', '/missing', '404']]);
    expect(toggle().textContent).toContain('Follow');

    // follow drops the search
    toggle().click();
    await vi.advanceTimersByTimeAsync(0);
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/logs/live/all');
    expect(search.value).toBe('');
    expect(streams.length).toBe(2);
    expect(state()).toBe('Live');
  });

  it('drops a pending search when the view is left', async () => {
    const { page } = await open();
    const search = page.querySelector<HTMLInputElement>('input[type=search]')!;
    search.value = 'missing';
    search.dispatchEvent(new Event('input'));

    await TestBed.inject(Router).navigateByUrl('/elsewhere');
    await vi.advanceTimersByTimeAsync(300);

    expect(TestBed.inject(Router).url).toBe('/elsewhere');
  });

  it('searches the search text of the url', async () => {
    const { harness, page, rows } = await open('/logs/live/s3?q=missing');

    http.expectOne('/api/v1/logs/s3/search?q=missing').flush([]);
    await harness.fixture.whenStable();

    expect(streams).toEqual([]);
    expect(page.querySelector<HTMLInputElement>('input[type=search]')!.value).toBe('missing');
    expect(rows()).toEqual([['No matching entries.']]);
  });

  it('follows the site selected in the dropdown', async () => {
    const { harness, page } = await open();
    const select = page.querySelector<HTMLSelectElement>('#live-site')!;
    expect([...select.options].map((option) => option.value)).toEqual(['all', 'pelle.io', 's3']);

    select.value = 's3';
    select.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/logs/live/s3');
    expect(streams[0].closed).toBe(true);
    expect(streams.map((stream) => stream.site)).toEqual(['all', 's3']);
  });

  it('tells why it can not follow and waits longer after every failed attempt', async () => {
    refusals = 2;
    const { harness, page, state } = await open();
    const alert = () => page.querySelector('[role=alert]')?.textContent ?? null;

    expect(streams.length).toBe(1);
    expect(state()).toBe('Reconnecting');
    expect(alert()).toContain('can not be followed right now');

    // the first attempt after 3s is refused again, the next one waits 6s
    await vi.advanceTimersByTimeAsync(3000);
    expect(streams.length).toBe(2);
    await vi.advanceTimersByTimeAsync(5000);
    expect(streams.length).toBe(2);
    await vi.advanceTimersByTimeAsync(1000);
    await harness.fixture.whenStable();
    expect(streams.length).toBe(3);

    // an open stream clears the error
    expect(state()).toBe('Live');
    expect(alert()).toBeNull();
  });

  it('reconnects when the stream breaks', async () => {
    const { harness, state } = await open();

    streams[0].entries.error(new Error('gone'));
    await harness.fixture.whenStable();
    expect(state()).toBe('Reconnecting');

    await vi.advanceTimersByTimeAsync(3000);
    await harness.fixture.whenStable();

    expect(streams.length).toBe(2);
    expect(state()).toBe('Live');
  });
});
