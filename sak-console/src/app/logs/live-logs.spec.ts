import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, Subject } from 'rxjs';
import { LiveLogs, MAX_ENTRIES } from './live-logs';
import { LogEntry, LogsService, parseEvents } from './logs.service';

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
  let streams: { site: string; entries: Subject<LogEntry>; closed: boolean }[];

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    streams = [];
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [{ path: 'logs/live/:site', component: LiveLogs }],
          withComponentInputBinding(),
        ),
      ],
    });
    const logs = TestBed.inject(LogsService);
    vi.spyOn(logs, 'follow').mockImplementation(
      (site: string, opened: () => void = () => undefined) =>
        new Observable<LogEntry>((subscriber) => {
          const stream = { site, entries: new Subject<LogEntry>(), closed: false };
          streams.push(stream);
          opened();
          const subscription = stream.entries.subscribe(subscriber);
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

  it('keeps the newest entries only', async () => {
    const { harness, rows } = await open();

    for (let i = 0; i < MAX_ENTRIES + 5; i++) {
      streams[0].entries.next(entry(`/${i}`));
    }
    await harness.fixture.whenStable();

    expect(rows().length).toBe(MAX_ENTRIES);
    expect(rows()[0][2]).toBe(`/${MAX_ENTRIES + 4}`);
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
