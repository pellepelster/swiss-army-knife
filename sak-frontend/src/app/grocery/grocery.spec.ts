import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { pageHelpers, pageOf } from '../testing/page';
import { GROCERY_UNDO_MS } from './grocery-page';
import { groceryRoute } from './grocery.routes';

const categories = [
  { name: 'FRUIT_VEGETABLES', label: 'Fruit & vegetables' },
  { name: 'DAIRY', label: 'Dairy & eggs' },
];
const defaultList = { id: 1, name: 'Default', shared: false, mine: true };
const party = { id: 2, name: 'Party', shared: true, mine: false };
const item = (id: number, name: string, category: string | null) => ({
  id,
  listId: 1,
  name,
  amount: null,
  unit: null,
  category,
  // as the backend answers it
  createdAt: '2026-10-31T07:00:00.000Z',
  updatedAt: null,
});

describe('Grocery', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        // the guard of the app is left out, the tests are signed in
        provideRouter([{ ...groceryRoute, canActivate: [] }], withComponentInputBinding()),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  const { page, button, open, url } = pageHelpers(() => harness);
  const input = (id: string) => page().querySelector<HTMLInputElement>(`#${id}`)!;
  const type = async (id: string, value: string) => {
    input(id).value = value;
    input(id).dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
  };

  const load = async (lists: object[], items?: object[]) => {
    await open('/grocery');
    http.expectOne('/api/v1/grocery/categories?offset=0&limit=100').flush(pageOf(categories));
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf(lists));
    await harness.fixture.whenStable();
    if (items)
      http.expectOne('/api/v1/grocery/lists/1/items?offset=0&limit=100').flush(pageOf(items));
    await harness.fixture.whenStable();
  };

  it('without a list there is no form to add an item, only the way to add a list', async () => {
    await load([]);
    expect(page().textContent).toContain('You have no list yet, add one first.');
    expect(page().textContent).not.toContain('Nothing to buy yet');
    expect(page().querySelector('#item-name')).toBeNull();
    expect(button('Add list').getAttribute('href')).toBe('/grocery/lists/create');
  });

  it('adds an item to the list shown', async () => {
    await load([defaultList], []);
    expect(page().textContent).toContain('Nothing to buy yet, add something.');

    await type('item-name', 'Milk');
    http.expectOne((request) => request.url === '/api/v1/grocery/suggestions').flush(pageOf([]));
    // the name only, there is no field for a quantity or a category
    expect(page().querySelector('#item-amount, #item-unit, #item-category')).toBeNull();
    button('Add item').click();
    const request = http.expectOne({ method: 'POST', url: '/api/v1/grocery/items' });
    expect(request.request.body).toEqual({
      listId: 1,
      name: 'Milk',
      amount: null,
      unit: null,
      category: null,
    });
    request.flush(item(10, 'Milk', null));
    http
      .expectOne('/api/v1/grocery/lists/1/items?offset=0&limit=100')
      .flush(pageOf([item(10, 'Milk', null)]));
    await harness.fixture.whenStable();

    expect(input('item-name').value).toBe('');
    expect(page().querySelector('[data-testid=item-10]')?.textContent).toContain('Milk');
  });

  it('shows the items to buy by category', async () => {
    await load(
      [defaultList, party],
      [item(1, 'Apples', 'FRUIT_VEGETABLES'), item(2, 'Milk', 'DAIRY'), item(3, 'Candles', null)],
    );

    const groups = [...page().querySelectorAll('ul.list-group')].map((list) =>
      list.getAttribute('aria-label'),
    );
    expect(groups).toEqual(['Fruit & vegetables', 'Dairy & eggs', 'No category']);
    expect(button('Edit list').getAttribute('href')).toBe('/grocery/lists/1/edit');
  });

  it('strikes a checked item through until the time is up, undoes it, the backend learns it afterwards', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([defaultList], [item(2, 'Milk', 'DAIRY'), item(3, 'Candles', null)]);

    // bought: struck through while the bar runs, unstruck with undo or a second click, nothing sent
    const struck = () =>
      page()
        .querySelector('[data-testid=item-2] label')!
        .classList.contains('text-decoration-line-through');
    input('item-2').click();
    await harness.fixture.whenStable();
    expect(struck()).toBe(true);
    expect(page().querySelector('[role=status]')?.textContent).toContain('Milk bought');
    button('Undo').click();
    await harness.fixture.whenStable();
    expect(struck()).toBe(false);
    input('item-2').click();
    await harness.fixture.whenStable();
    input('item-2').click();
    await harness.fixture.whenStable();
    expect(struck()).toBe(false);
    expect(page().querySelector('[role=status]')).toBeNull();
    http.expectNone({ method: 'DELETE' });

    // bought for good once the time is up: the item is deleted
    input('item-2').click();
    await harness.fixture.whenStable();
    vi.advanceTimersByTime(GROCERY_UNDO_MS);
    http.expectOne({ method: 'DELETE', url: '/api/v1/grocery/items/2' }).flush(null);
    await harness.fixture.whenStable();
    // gone once the time is up
    expect(page().querySelector('[data-testid=item-2]')).toBeNull();

    // another check sends the one before at once
    input('item-3').click();
    await harness.fixture.whenStable();
    expect(page().querySelector('[role=status]')?.textContent).toContain('Candles bought');
    expect(page().querySelector('[aria-label="Remove Candles"]')).toBeNull();
    vi.advanceTimersByTime(GROCERY_UNDO_MS);
    http.expectOne({ method: 'DELETE', url: '/api/v1/grocery/items/3' }).flush(null);
    await harness.fixture.whenStable();
    expect(page().textContent).toContain('Nothing to buy yet, add something.');
    expect(page().querySelector('[role=status]')).toBeNull();
    vi.useRealTimers();
  });

  it('goes from list to list with the arrows at the sides', async () => {
    await load([defaultList, party], []);
    expect(page().querySelector('[data-testid=list-name]')?.textContent?.trim()).toBe('Default');
    expect(button('Previous list').disabled).toBe(true);

    button('Next list').click();
    await harness.fixture.whenStable();
    expect(url()).toBe('/grocery?list=2');
    http.expectOne('/api/v1/grocery/lists/2/items?offset=0&limit=100').flush(pageOf([]));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=list-name]')?.textContent).toContain('Party');
    expect(page().querySelector('[data-testid=list-name]')?.textContent).toContain('shared');
    expect(button('Next list').disabled).toBe(true);
    // not the owner's, no edit
    expect(button('Edit list')).toBeUndefined();
  });

  it('a swipe to the side goes to the next or the previous list, other touches do not', async () => {
    await load([defaultList, party], []);
    const dots = () =>
      [...page().querySelectorAll('[data-testid=list-dots] span')].map((dot) =>
        dot.classList.contains('active'),
      );
    expect(dots()).toEqual([true, false]);
    // a touch as the browser reports it, on the element
    const touch = (target: Element, type: string, x: number, y: number) => {
      const event = new Event(type, { bubbles: true });
      Object.defineProperty(event, 'touches', {
        value: type === 'touchend' ? [] : [{ clientX: x, clientY: y }],
      });
      Object.defineProperty(event, 'changedTouches', { value: [{ clientX: x, clientY: y }] });
      target.dispatchEvent(event);
    };
    const swipe = async (target: Element, from: [number, number], to: [number, number]) => {
      touch(target, 'touchstart', ...from);
      touch(target, 'touchend', ...to);
      await harness.fixture.whenStable();
    };
    const heading = page().querySelector('[data-testid=list-name]')!;

    // up and down, from the edge, on a field: no other list
    await swipe(heading, [300, 200], [280, 400]);
    await swipe(heading, [5, 200], [300, 200]);
    await swipe(input('item-name'), [300, 200], [100, 200]);
    expect(url()).toBe('/grocery');

    await swipe(heading, [300, 200], [150, 210]);
    expect(url()).toBe('/grocery?list=2');
    http.expectOne('/api/v1/grocery/lists/2/items?offset=0&limit=100').flush(pageOf([]));
    await harness.fixture.whenStable();
    expect(dots()).toEqual([false, true]);

    await swipe(page().querySelector('[data-testid=list-name]')!, [150, 200], [300, 190]);
    expect(url()).toBe('/grocery?list=1');
    http.expectOne('/api/v1/grocery/lists/1/items?offset=0&limit=100').flush(pageOf([]));
  });

  it('a suggestion chosen brings its quantity and category along', async () => {
    await load([defaultList], []);
    await type('item-name', 'mi');
    http
      .expectOne(
        (request) =>
          request.url === '/api/v1/grocery/suggestions' && request.params.get('listId') === '1',
      )
      .flush(pageOf([{ id: 5, name: 'Milk', amount: 2, unit: 'l', category: 'DAIRY' }]));
    await harness.fixture.whenStable();
    expect(page().querySelector('#grocery-suggestions option')?.getAttribute('value')).toBe('Milk');

    await type('item-name', 'Milk');
    button('Add item').click();
    expect(http.expectOne({ method: 'POST', url: '/api/v1/grocery/items' }).request.body).toEqual({
      listId: 1,
      name: 'Milk',
      amount: 2,
      unit: 'l',
      category: 'DAIRY',
    });
  });

  it('creates a shared list and shows it', async () => {
    await open('/grocery/lists/create');
    await type('list-name', 'Party');
    input('list-shared').click();
    await harness.fixture.whenStable();
    button('Create list').click();
    const request = http.expectOne({ method: 'POST', url: '/api/v1/grocery/lists' });
    expect(request.request.body).toEqual({ name: 'Party', shared: true });
    request.flush({ ...party, mine: true });
    await harness.fixture.whenStable();
    expect(url()).toBe('/grocery?list=2');
    http.match(() => true).forEach((pending) => pending.flush(pageOf([])));
  });

  it('the list of the sign-in is renamed and deleted like the others', async () => {
    await open('/grocery/lists/1/edit');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
    await harness.fixture.whenStable();
    expect(input('list-name').readOnly).toBe(false);

    // delete, cancel and save are one group; the delete asks in its place
    const group = () => page().querySelector('[role=group][aria-label="List actions"]');
    expect(
      [...group()!.querySelectorAll('a, button')].map((each) => each.textContent!.trim()),
    ).toEqual(['Delete list', 'Cancel', 'Save']);
    button('Delete list').click();
    await harness.fixture.whenStable();
    expect(group()).toBeNull();
    expect(page().textContent).toContain('Delete the list and its items?');
    button('Keep').click();
    await harness.fixture.whenStable();
    expect(group()).not.toBeNull();

    button('Delete list').click();
    await harness.fixture.whenStable();
    button('Delete').click();
    http.expectOne({ method: 'DELETE', url: '/api/v1/grocery/lists/1' }).flush(null);
    await harness.fixture.whenStable();
    expect(url()).toBe('/grocery');
    http.match(() => true).forEach((pending) => pending.flush(pageOf([])));
  });
});
