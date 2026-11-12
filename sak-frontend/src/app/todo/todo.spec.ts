import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Subject } from 'rxjs';
import { pageHelpers, pageOf } from '../testing/page';
import { UNDO_DELETE_MS } from '../shared/undo-toast';
import { CHANGES_DEBOUNCE_MS, EVERYTHING, TodoChanges } from './todo-changes';
import { dayText } from './todo-page';
import { todoRoute } from './todo.routes';

const home = { id: 1, name: 'Home', shared: false, mine: true, items: 2, icon: null };
const party = { id: 2, name: 'Party', shared: true, mine: false, items: 1, icon: '🎉' };
const task = (
  id: number,
  title: string,
  dueDate: string | null = null,
  priority: 'high' | 'normal' | 'low' = 'normal',
) => ({
  id,
  listId: 1,
  title,
  priority,
  // as the backend answers it
  createdAt: '2026-11-12T07:00:00.000Z',
  updatedAt: null,
  ...(dueDate ? { dueDate } : {}),
});

describe('Todo', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  // the changes the backend sends, as the tests send them
  let changes: Subject<number | null>;

  beforeEach(async () => {
    changes = new Subject();
    TestBed.configureTestingModule({
      providers: [
        { provide: TodoChanges, useValue: { changes: () => changes } },
        provideHttpClient(),
        provideHttpClientTesting(),
        // the guard of the app is left out, the tests are signed in
        provideRouter([{ ...todoRoute, canActivate: [] }], withComponentInputBinding()),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  const { page, button, open, url, rowMenu } = pageHelpers(() => harness);
  const input = (id: string) => page().querySelector<HTMLInputElement>(`#${id}`)!;
  const type = async (id: string, value: string) => {
    input(id).value = value;
    input(id).dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
  };
  const titles = () =>
    [...page().querySelectorAll('[data-testid^=item-] .text-break')].map((each) =>
      each.textContent!.trim(),
    );

  const flushItems = async (listId: number, items: object[]) => {
    http.expectOne(`/api/v1/todo/lists/${listId}/items?offset=0&limit=100`).flush(pageOf(items));
    await harness.fixture.whenStable();
  };

  const load = async (lists: object[], items: object[]) => {
    await open('/todo/lists/1');
    http.expectOne('/api/v1/todo/lists?offset=0&limit=100').flush(pageOf(lists));
    await harness.fixture.whenStable();
    await flushItems(1, items);
  };

  const overview = async (lists: object[]) => {
    await open('/todo');
    http.expectOne('/api/v1/todo/lists?offset=0&limit=100').flush(pageOf(lists));
    await harness.fixture.whenStable();
  };

  it('starts with all lists and how many tasks are open, a list opens and leads back', async () => {
    await overview([home, party, { ...home, id: 3, name: 'Work', items: 1 }]);
    const rows = [...page().querySelectorAll('[data-testid^=list-]')].map((row) =>
      row.textContent!.replace(/\s+/g, ' ').trim(),
    );
    expect(rows).toEqual(['Home 2 open tasks', 'Party 1 open task shared', 'Work 1 open task']);
    expect(button('Create list').getAttribute('href')).toBe('/todo/lists/create');
    expect(button('Settings of Party').getAttribute('href')).toBe('/todo/lists/2/edit?from=lists');
    expect(button('Delete', await rowMenu('Party')).disabled).toBe(true);

    page().querySelector<HTMLAnchorElement>('[data-testid=list-1]')!.click();
    await harness.fixture.whenStable();
    expect(url()).toBe('/todo/lists/1');
    http.expectOne('/api/v1/todo/lists?offset=0&limit=100').flush(pageOf([home, party]));
    await harness.fixture.whenStable();
    await flushItems(1, []);
    expect(page().querySelector('[data-testid=list-name]')?.textContent).toContain('Home');
    expect(page().textContent).toContain('Nothing to do, add a task.');
    expect(button('All lists').getAttribute('href')).toBe('/todo');
  });

  it('without a list there is only the way to add one', async () => {
    await overview([]);
    expect(page().textContent).toContain('You have no list yet, create one first.');
  });

  it('adds a task with or without a due date, in the order they were added', async () => {
    await load([home], [task(10, 'Fix the door')]);

    await type('item-title', 'Paint the fence');
    await type('item-due', '2099-12-24');
    button('Add task').click();
    const request = http.expectOne({ method: 'POST', url: '/api/v1/todo/items' });
    expect(request.request.body).toEqual({
      listId: 1,
      title: 'Paint the fence',
      dueDate: '2099-12-24',
      priority: 'normal',
    });
    request.flush(task(11, 'Paint the fence', '2099-12-24'));
    await flushItems(1, [task(10, 'Fix the door'), task(11, 'Paint the fence', '2099-12-24')]);
    expect(input('item-title').value).toBe('');
    expect(input('item-due').value).toBe('');
    expect(titles()).toEqual(['Fix the door', 'Paint the fence']);

    // a priority chosen goes with the task, the next one is normal again (TODO-5)
    await type('item-title', 'Whenever');
    const priority = page().querySelector<HTMLSelectElement>('#item-priority')!;
    priority.value = 'high';
    priority.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();
    button('Add task').click();
    const second = http.expectOne({ method: 'POST', url: '/api/v1/todo/items' });
    expect(second.request.body).toEqual({
      listId: 1,
      title: 'Whenever',
      dueDate: null,
      priority: 'high',
    });
    second.flush(task(12, 'Whenever', null, 'high'));
    await flushItems(1, [task(10, 'Fix the door')]);
    expect(page().querySelector<HTMLSelectElement>('#item-priority')!.value).toBe('normal');
  });

  it('marks a high priority with an arrow up, a low one with an arrow down, a normal one not (TODO-5)', async () => {
    await load(
      [home],
      [task(10, 'Taxes', null, 'high'), task(11, 'Read'), task(12, 'Tidy', null, 'low')],
    );
    const marker = (id: number) => page().querySelector(`[data-testid=priority-${id}]`);
    expect(marker(10)!.classList.contains('fa-arrow-up')).toBe(true);
    // the name of the toggle says the priority, the arrow is hidden from screen readers
    expect(button('Done: Taxes, High priority')).toBeDefined();
    expect(button('Done: Read')).toBeDefined();
    expect(button('Done: Tidy, Low priority')).toBeDefined();
    expect(marker(11)).toBeNull();
    expect(marker(12)!.classList.contains('fa-arrow-down')).toBe(true);
  });

  it('changes the priority of a task from the menu of its row, the current one checked (TODO-5)', async () => {
    await load([home], [task(10, 'Taxes'), task(11, 'Read', null, 'low')]);
    const menu = await rowMenu('Taxes');
    expect(
      menu.querySelector('[data-testid=set-priority-normal]')!.getAttribute('aria-checked'),
    ).toBe('true');
    menu.querySelector<HTMLButtonElement>('[data-testid=set-priority-high]')!.click();
    await harness.fixture.whenStable();
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/todo/items/10/priority' });
    expect(request.request.body).toEqual({ priority: 'high' });
    request.flush(task(10, 'Taxes', null, 'high'));
    await flushItems(1, [task(10, 'Taxes', null, 'high'), task(11, 'Read', null, 'low')]);
    expect(
      page().querySelector('[data-testid=priority-10]')!.classList.contains('fa-arrow-up'),
    ).toBe(true);

    // a refused one says why in its row
    (await rowMenu('Read'))
      .querySelector<HTMLButtonElement>('[data-testid=set-priority-normal]')!
      .click();
    await harness.fixture.whenStable();
    http
      .expectOne({ method: 'PUT', url: '/api/v1/todo/items/11/priority' })
      .flush(null, { status: 500, statusText: 'Server Error' });
    await flushItems(1, [task(10, 'Taxes', null, 'high'), task(11, 'Read', null, 'low')]);
    expect(page().querySelector('[data-testid=item-11] [role=alert]')?.textContent).toContain(
      'The priority could not be changed.',
    );
  });

  it('removes the due date of a task from the menu of its row, disabled without one (TODO-4)', async () => {
    await load([home], [task(10, 'Taxes', '2020-05-31'), task(11, 'Read')]);
    const remove = (menu: HTMLElement) =>
      menu.querySelector<HTMLButtonElement>('[data-testid=remove-due-date]')!;
    expect(remove(await rowMenu('Read')).disabled).toBe(true);
    expect(remove(await rowMenu('Read')).title).toBe('The task has no due date.');

    remove(await rowMenu('Taxes')).click();
    await harness.fixture.whenStable();
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/todo/items/10/due-date' });
    expect(request.request.body).toEqual({ dueDate: null });
    request.flush(task(10, 'Taxes'));
    await flushItems(1, [task(10, 'Taxes'), task(11, 'Read')]);
    expect(page().querySelector('[data-testid=due-10]')).toBeNull();
  });

  it('adds a due date to a task without one in its row, Save waits for a day, Escape keeps it without (TODO-4)', async () => {
    await load([home], [task(10, 'Taxes', '2020-05-31'), task(11, 'Read')]);
    const add = (menu: HTMLElement) =>
      menu.querySelector<HTMLButtonElement>('[data-testid=add-due-date]')!;
    expect(add(await rowMenu('Taxes')).disabled).toBe(true);
    expect(add(await rowMenu('Taxes')).title).toBe('The task has a due date.');

    // the menu focuses its first entry, the first priority
    const menu = await rowMenu('Read');
    expect(document.activeElement).toBe(menu.querySelector('[data-testid=set-priority-high]'));
    add(menu).click();
    await harness.fixture.whenStable();
    const field = () => page().querySelector<HTMLInputElement>('#due-date-11');
    expect(document.activeElement).toBe(field());
    const menuButton = page().querySelector(
      '[data-testid=item-11] app-action-menu button[aria-haspopup]',
    );
    const save = () =>
      [...page().querySelectorAll<HTMLButtonElement>('[data-testid=item-11] form button')].find(
        (each) => each.textContent!.trim() === 'Save',
      )!;
    expect(save().disabled).toBe(true);
    field()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await harness.fixture.whenStable();
    expect(field()).toBeNull();
    // back to the menu of the row
    expect(document.activeElement).toBe(menuButton);
    http.expectNone({ method: 'PUT' });

    add(await rowMenu('Read')).click();
    await harness.fixture.whenStable();
    field()!.value = '2099-12-24';
    field()!.dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
    save().click();
    await harness.fixture.whenStable();
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/todo/items/11/due-date' });
    expect(request.request.body).toEqual({ dueDate: '2099-12-24' });
    request.flush(task(11, 'Read', '2099-12-24'));
    await flushItems(1, [task(10, 'Taxes', '2020-05-31'), task(11, 'Read', '2099-12-24')]);
    expect(field()).toBeNull();
    expect(document.activeElement).toBe(menuButton);
    expect(page().querySelector('[data-testid=due-11]')!.textContent).toContain('due');
    expect(button(`Done: Read, due ${dayText('2099-12-24')}`)).toBeDefined();
  });

  it('shows the due date in the locale of the user, the danger color once it passed (TODO-4)', async () => {
    await load(
      [home],
      [task(10, 'Taxes', '2020-05-31'), task(11, 'Gifts', '2099-12-24'), task(12, 'Read')],
    );
    const due = (id: number) => page().querySelector(`[data-testid=due-${id}]`);
    expect(due(10)!.textContent!.trim()).toBe(`overdue since ${dayText('2020-05-31')}`);
    expect(due(10)!.classList.contains('text-danger')).toBe(true);
    expect(due(11)!.textContent!.trim()).toBe(`due ${dayText('2099-12-24')}`);
    expect(due(11)!.classList.contains('text-danger')).toBe(false);
    expect(due(12)).toBeNull();
    // the name of the toggle says it too
    expect(button(`Done: Taxes, overdue since ${dayText('2020-05-31')}`)).toBeDefined();
    // a day, the same in every time zone
    expect(dayText('2026-01-01')).toContain('2026');
  });

  it('a task becomes overdue after midnight once the page shows again (TODO-4)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date(2026, 10, 12, 23, 59));
    await load([home], [task(10, 'Taxes', '2026-11-12')]);
    const due = () => page().querySelector('[data-testid=due-10]')!;
    expect(due().classList.contains('text-danger')).toBe(false);

    vi.setSystemTime(new Date(2026, 10, 13, 0, 1));
    document.dispatchEvent(new Event('visibilitychange'));
    await harness.fixture.whenStable();
    expect(due().classList.contains('text-danger')).toBe(true);
    expect(due().textContent!.trim()).toBe(`overdue since ${dayText('2026-11-12')}`);
  });

  // the titles of the tasks struck through, done
  const struck = () =>
    [...page().querySelectorAll('[data-testid^=item-] .item-struck')].map((each) =>
      each.textContent!.trim(),
    );
  const done = (item: object) => ({ ...item, doneAt: '2026-11-12T08:00:00.000Z' });

  it('a tap marks a task done, struck through, a second tap opens it again, the backend learns it at once (TODO-3)', async () => {
    await load([home], [task(10, 'Fix the door'), task(11, 'Paint the fence')]);

    button('Done: Fix the door').click();
    await harness.fixture.whenStable();
    expect(struck()).toEqual(['Fix the door']);
    expect(button('Done: Fix the door').getAttribute('aria-pressed')).toBe('true');
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/todo/items/10/done' });
    expect(request.request.body).toEqual({ done: true });
    request.flush(done(task(10, 'Fix the door')));
    // in its place, as the backend answers it
    await flushItems(1, [done(task(10, 'Fix the door')), task(11, 'Paint the fence')]);
    expect(titles()).toEqual(['Fix the door', 'Paint the fence']);
    expect(struck()).toEqual(['Fix the door']);
    // a done task stays, no toast
    expect(page().querySelector('[role=status]')).toBeNull();

    button('Done: Fix the door').click();
    await harness.fixture.whenStable();
    expect(struck()).toEqual([]);
    const again = http.expectOne({ method: 'PUT', url: '/api/v1/todo/items/10/done' });
    expect(again.request.body).toEqual({ done: false });
    again.flush(task(10, 'Fix the door'));
    await flushItems(1, [task(10, 'Fix the door'), task(11, 'Paint the fence')]);
    expect(struck()).toEqual([]);
  });

  it('a task the backend can not mark done says why and shows as the backend has it', async () => {
    await load([home], [task(10, 'Fix the door')]);
    button('Done: Fix the door').click();
    await harness.fixture.whenStable();
    http
      .expectOne({ method: 'PUT', url: '/api/v1/todo/items/10/done' })
      .flush(null, { status: 500, statusText: 'Server Error' });
    await flushItems(1, [task(10, 'Fix the door')]);
    expect(struck()).toEqual([]);
    expect(page().querySelector('[data-testid=item-10] [role=alert]')?.textContent).toContain(
      'The task could not be marked done.',
    );
  });

  it('purge takes the done tasks away at once, undo brings them back, the backend purges once the time is up (TODO-6, UX-4.2)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([home], [done(task(10, 'Fix the door')), task(11, 'Paint the fence')]);
    const purge = () => page().querySelector<HTMLButtonElement>('[data-testid=purge]')!;
    expect(purge().disabled).toBe(false);
    expect(purge().classList.contains('btn-outline-danger')).toBe(true);

    purge().click();
    await harness.fixture.whenStable();
    expect(titles()).toEqual(['Paint the fence']);
    expect(purge().disabled).toBe(true);
    expect(purge().title).toBe('The list has no done tasks.');
    expect(page().querySelector('[role=status]')?.textContent).toContain('Done tasks removed');
    button('Undo').click();
    await harness.fixture.whenStable();
    expect(titles()).toEqual(['Fix the door', 'Paint the fence']);
    http.expectNone({ method: 'POST' });

    purge().click();
    await harness.fixture.whenStable();
    vi.advanceTimersByTime(UNDO_DELETE_MS);
    const request = http.expectOne({ method: 'POST', url: '/api/v1/todo/lists/1/purge' });
    // only the done tasks that went
    expect(request.request.body).toEqual({ itemIds: [10] });
    request.flush(null);
    await flushItems(1, [task(11, 'Paint the fence')]);
    expect(titles()).toEqual(['Paint the fence']);
    expect(page().querySelector('[role=status]')).toBeNull();
  });

  it('a task done while the purge can be undone stays, struck through, and is not purged (TODO-6)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([home], [done(task(10, 'Fix the door')), task(11, 'Paint the fence')]);
    page().querySelector<HTMLButtonElement>('[data-testid=purge]')!.click();
    await harness.fixture.whenStable();

    button('Done: Paint the fence').click();
    await harness.fixture.whenStable();
    http
      .expectOne({ method: 'PUT', url: '/api/v1/todo/items/11/done' })
      .flush(done(task(11, 'Paint the fence')));
    // a load meanwhile still knows the purged one, it stays hidden
    await flushItems(1, [done(task(10, 'Fix the door')), done(task(11, 'Paint the fence'))]);
    expect(titles()).toEqual(['Paint the fence']);
    expect(struck()).toEqual(['Paint the fence']);

    vi.advanceTimersByTime(UNDO_DELETE_MS);
    const request = http.expectOne({ method: 'POST', url: '/api/v1/todo/lists/1/purge' });
    expect(request.request.body).toEqual({ itemIds: [10] });
    // hidden until the backend answered, also from a load started meanwhile
    expect(titles()).toEqual(['Paint the fence']);
    request.flush(null);
    await flushItems(1, [done(task(11, 'Paint the fence'))]);
    expect(struck()).toEqual(['Paint the fence']);
  });

  it('the lists loaded again do not end what can be undone (TODO-6, LIST-4)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([home], [done(task(10, 'Fix the door')), task(11, 'Paint the fence')]);
    page().querySelector<HTMLButtonElement>('[data-testid=purge]')!.click();
    await harness.fixture.whenStable();

    changes.next(1);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    http.expectOne('/api/v1/todo/lists?offset=0&limit=100').flush(pageOf([{ ...home }]));
    // the tasks load once, the purge still waits
    await flushItems(1, [done(task(10, 'Fix the door')), task(11, 'Paint the fence')]);
    http.expectNone({ method: 'POST' });
    expect(titles()).toEqual(['Paint the fence']);
    expect(page().querySelector('[role=status]')?.textContent).toContain('Done tasks removed');

    button('Undo').click();
    await harness.fixture.whenStable();
    expect(titles()).toEqual(['Fix the door', 'Paint the fence']);
  });

  it('a tap on a task whose request is on its way does nothing (TODO-3)', async () => {
    await load([home], [task(10, 'Fix the door')]);
    button('Done: Fix the door').click();
    await harness.fixture.whenStable();
    button('Done: Fix the door').click();
    await harness.fixture.whenStable();
    expect(struck()).toEqual(['Fix the door']);
    http
      .expectOne({ method: 'PUT', url: '/api/v1/todo/items/10/done' })
      .flush(done(task(10, 'Fix the door')));
    await flushItems(1, [done(task(10, 'Fix the door'))]);
    expect(struck()).toEqual(['Fix the door']);
  });

  it('a purge the backend refuses says why and loads the tasks again', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([home], [done(task(10, 'Fix the door'))]);
    page().querySelector<HTMLButtonElement>('[data-testid=purge]')!.click();
    await harness.fixture.whenStable();
    expect(page().textContent).toContain('Nothing to do, add a task.');
    vi.advanceTimersByTime(UNDO_DELETE_MS);
    http
      .expectOne({ method: 'POST', url: '/api/v1/todo/lists/1/purge' })
      .flush(null, { status: 500, statusText: 'Server Error' });
    await flushItems(1, [done(task(10, 'Fix the door'))]);
    expect(page().textContent).toContain('The done tasks could not be removed.');
    expect(struck()).toEqual(['Fix the door']);

    // the next purge that goes through takes the error away
    page().querySelector<HTMLButtonElement>('[data-testid=purge]')!.click();
    await harness.fixture.whenStable();
    expect(page().textContent).not.toContain('The done tasks could not be removed.');
    vi.advanceTimersByTime(UNDO_DELETE_MS);
    http.expectOne({ method: 'POST', url: '/api/v1/todo/lists/1/purge' }).flush(null);
    await flushItems(1, []);
    expect(page().textContent).toContain('Nothing to do, add a task.');
  });

  it('a page left while a purge waits sends it at once', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([home], [done(task(10, 'Fix the door'))]);
    page().querySelector<HTMLButtonElement>('[data-testid=purge]')!.click();
    await harness.fixture.whenStable();
    harness.fixture.destroy();
    const request = http.expectOne({ method: 'POST', url: '/api/v1/todo/lists/1/purge' });
    expect(request.request.body).toEqual({ itemIds: [10] });
    request.flush(null);
  });

  it('an app going into the background while a purge waits sends it with keepalive (TODO-6)', async () => {
    const fetch = vi.fn(async () => new Response(null, { status: 200 }));
    vi.stubGlobal('fetch', fetch);
    try {
      await load([home], [done(task(10, 'Fix the door')), task(11, 'Paint the fence')]);
      page().querySelector<HTMLButtonElement>('[data-testid=purge]')!.click();
      await harness.fixture.whenStable();
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));

      expect(fetch).toHaveBeenCalledTimes(1);
      const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
      expect(url).toBe('/api/v1/todo/lists/1/purge');
      expect(init.method).toBe('POST');
      expect(init.keepalive).toBe(true);
      expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
      expect(JSON.parse(init.body as string)).toEqual({ itemIds: [10] });
      await harness.fixture.whenStable();
      expect(page().querySelector('[role=status]')).toBeNull();
      expect(titles()).toEqual(['Paint the fence']);
      // once the backend answered, the tasks load again
      await vi.waitFor(() =>
        http
          .expectOne('/api/v1/todo/lists/1/items?offset=0&limit=100')
          .flush(pageOf([task(11, 'Paint the fence')])),
      );
      await harness.fixture.whenStable();
      expect(titles()).toEqual(['Paint the fence']);
    } finally {
      // the document of jsdom again, the specs that follow may share it
      Reflect.deleteProperty(document, 'visibilityState');
      vi.unstubAllGlobals();
    }
  });

  it('a change of the list shown loads it again, of another list not; a list gone leads to all lists (LIST-4)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([home, party], []);
    changes.next(2);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    http.expectNone('/api/v1/todo/lists/1/items?offset=0&limit=100');

    changes.next(1);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    http.expectOne('/api/v1/todo/lists?offset=0&limit=100').flush(pageOf([home, party]));
    await flushItems(1, [task(10, 'Fix the door')]);
    expect(titles()).toEqual(['Fix the door']);

    changes.next(EVERYTHING);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    http.expectOne('/api/v1/todo/lists?offset=0&limit=100').flush(pageOf([party]));
    await flushItems(1, []);
    expect(url()).toBe('/todo');
    http.expectOne('/api/v1/todo/lists?offset=0&limit=100').flush(pageOf([party]));
  });

  it('creates a shared list and shows it', async () => {
    await open('/todo/lists/create');
    await type('list-name', 'Party');
    input('list-shared').click();
    await harness.fixture.whenStable();
    button('Create list').click();
    const request = http.expectOne({ method: 'POST', url: '/api/v1/todo/lists' });
    expect(request.request.body).toEqual({ name: 'Party', shared: true, icon: null });
    request.flush({ ...party, mine: true });
    await harness.fixture.whenStable();
    expect(url()).toBe('/todo/lists/2');
    http.match(() => true).forEach((pending) => pending.flush(pageOf([])));
  });

  it("a shared list of another user: its name changes, sharing and deleting are the owner's", async () => {
    await open('/todo/lists/2/edit');
    http.expectOne('/api/v1/todo/lists?offset=0&limit=100').flush(pageOf([home, party]));
    await harness.fixture.whenStable();
    expect(input('list-shared').disabled).toBe(true);
    expect(button('Delete list').disabled).toBe(true);
    await type('list-name', 'Our party');
    button('Save').click();
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/todo/lists/2' });
    expect(request.request.body).toEqual({ name: 'Our party', shared: true, icon: '🎉' });
    request.flush({ ...party, name: 'Our party' });
    await harness.fixture.whenStable();
    expect(url()).toBe('/todo/lists/2');
    http.match(() => true).forEach((pending) => pending.flush(pageOf([])));
  });
});
