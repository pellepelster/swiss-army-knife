import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { pageHelpers, pageOf } from '../testing/page';
import { Subject } from 'rxjs';
import { CHANGES_DEBOUNCE_MS, EVERYTHING, GroceryChanges } from './grocery-changes';
import { GROCERY_UNDO_MS } from './grocery-page';
import { groceryRoute } from './grocery.routes';
import { PhotoScaler } from '../notes/photo-scaler';

const categories = [
  { name: 'FRUIT_VEGETABLES', label: 'Fruit & vegetables' },
  { name: 'DAIRY', label: 'Dairy & eggs' },
];
const defaultList = { id: 1, name: 'Default', shared: false, mine: true, items: 3, icon: null };
const party = { id: 2, name: 'Party', shared: true, mine: false, items: 1, icon: '🎉' };
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
  // the changes the backend sends, as the tests send them
  let changes: Subject<number | null>;

  beforeEach(async () => {
    changes = new Subject();
    TestBed.configureTestingModule({
      providers: [
        { provide: GroceryChanges, useValue: { changes: () => changes } },
        { provide: PhotoScaler, useValue: { scale: async (photo: Blob) => photo } },
        provideHttpClient(),
        provideHttpClientTesting(),
        // the guard of the app is left out, the tests are signed in
        provideRouter([{ ...groceryRoute, canActivate: [] }], withComponentInputBinding()),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    let objectUrls = 0;
    URL.createObjectURL = vi.fn(() => `blob:photo-${++objectUrls}`);
    URL.revokeObjectURL = vi.fn();
  });

  afterEach(() => http.verify());

  const { page, button, open, url, rowMenu, deleteFromRow } = pageHelpers(() => harness);
  const input = (id: string) => page().querySelector<HTMLInputElement>(`#${id}`)!;
  const type = async (id: string, value: string) => {
    input(id).value = value;
    input(id).dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
  };

  // the page of the list 1
  const load = async (lists: object[], items: object[]) => {
    await open('/grocery/lists/1');
    http.expectOne('/api/v1/grocery/categories?offset=0&limit=100').flush(pageOf(categories));
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf(lists));
    await harness.fixture.whenStable();
    http.expectOne('/api/v1/grocery/lists/1/items?offset=0&limit=100').flush(pageOf(items));
    await harness.fixture.whenStable();
  };

  const overview = async (lists: object[]) => {
    await open('/grocery');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf(lists));
    await harness.fixture.whenStable();
  };

  it('starts with all lists and how many items are to buy, a list opens and leads back', async () => {
    await overview([defaultList, party, { ...party, id: 3, name: 'Hardware', items: 0 }]);
    const rows = [...page().querySelectorAll('[data-testid^=list-]')].map((row) =>
      row.textContent!.replace(/\s+/g, ' ').trim(),
    );
    expect(rows).toEqual([
      'Default 3 items to buy',
      'Party 1 item to buy shared',
      'Hardware No items to buy shared',
    ]);
    expect(button('Add list').getAttribute('href')).toBe('/grocery/lists/create');
    expect(page().querySelector('#item-name')).toBeNull();
    // the emoji of a list, else the first letter of its name
    expect(
      [...page().querySelectorAll('app-list-icon')].map((icon) => icon.textContent!.trim()),
    ).toEqual(['D', '🎉', '🎉']);
    // the menu of a row leads to the settings, of a list of another user they are disabled (UX-8)
    expect(button('Settings of Default').getAttribute('href')).toBe(
      '/grocery/lists/1/edit?from=lists',
    );
    expect(button('Settings of Party').disabled).toBe(true);

    page().querySelector<HTMLAnchorElement>('[data-testid=list-2]')!.click();
    await harness.fixture.whenStable();
    expect(url()).toBe('/grocery/lists/2');
    http.expectOne('/api/v1/grocery/categories?offset=0&limit=100').flush(pageOf(categories));
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList, party]));
    await harness.fixture.whenStable();
    http.expectOne('/api/v1/grocery/lists/2/items?offset=0&limit=100').flush(pageOf([]));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=list-name]')?.textContent).toContain('Party');
    expect(button('All lists').getAttribute('href')).toBe('/grocery');
    // not the owner's: the edit stays in its place, disabled (UX-8)
    expect(button('Settings').tagName).toBe('BUTTON');
    expect(button('Settings').disabled).toBe(true);
    expect(button('Settings').title).toBe('Only the owner of a list changes it');
    // one list at a time, no way to the next one but the overview
    expect(button('Next list')).toBeUndefined();
  });

  it('without a list there is only the way to add one', async () => {
    await overview([]);
    expect(page().textContent).toContain('You have no list yet, add one first.');
    expect(button('Add list').getAttribute('href')).toBe('/grocery/lists/create');
  });

  it('a list that is gone leads to all lists', async () => {
    await open('/grocery/lists/7');
    http.expectOne('/api/v1/grocery/categories?offset=0&limit=100').flush(pageOf(categories));
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
    await harness.fixture.whenStable();
    expect(url()).toBe('/grocery');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
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
    expect(button('Settings').getAttribute('href')).toBe('/grocery/lists/1/edit');
  });

  it('shows the photo of an item scaled to its place, a tap on it shows it in full and does not check the item', async () => {
    await load(
      [defaultList],
      [{ ...item(2, 'Milk', 'DAIRY'), photoId: 9 }, item(3, 'Candles', null)],
    );
    // 48 css pixels at a pixel ratio of 1: the smallest scaled size, 128
    http.expectOne('/api/v1/grocery/items/2/photos/9?size=128').flush(new Blob(['jpeg']));
    await vi.waitFor(async () => {
      await harness.fixture.whenStable();
      expect(page().querySelector('[data-testid=item-2] img')?.getAttribute('src')).toBe(
        'blob:photo-1',
      );
    });
    expect(page().querySelector('[data-testid=item-3] img')).toBeNull();

    button('Photo of Milk').click();
    await harness.fixture.whenStable();
    // as large as the screen, 1024 by 768 in the tests
    http.expectOne('/api/v1/grocery/items/2/photos/9?size=1024').flush(new Blob(['jpeg']));
    await vi.waitFor(async () => {
      await harness.fixture.whenStable();
      expect(page().querySelector('[data-testid=photo-viewer] img')).not.toBeNull();
    });
    const viewer = page().querySelector<HTMLElement>('[data-testid=photo-viewer]')!;
    expect(viewer.querySelector('img')?.getAttribute('src')).toBe('blob:photo-2');
    expect(page().querySelector('[role=status]')).toBeNull();
    http.expectNone({ method: 'DELETE' });

    viewer.click();
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=photo-viewer]')).toBeNull();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:photo-2');
  });

  it('a photo that can not be loaded leaves the row without one, the phone offers its camera and its photos', async () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    await load([defaultList], [{ ...item(2, 'Milk', 'DAIRY'), photoId: 9 }]);
    http
      .expectOne('/api/v1/grocery/items/2/photos/9?size=128')
      .flush(null, { status: 404, statusText: 'Not Found' });
    await harness.fixture.whenStable();
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=item-2] img')?.getAttribute('src')).toBeFalsy();
    expect(errors).not.toHaveBeenCalled();
    // no capture: not the camera alone
    expect(page().querySelector('[data-testid=photo-input]')?.hasAttribute('capture')).toBe(false);
  });

  it('attaches a photo from the menu of an item, scaled down and stored first, and removes it again', async () => {
    await load([defaultList], [item(2, 'Milk', 'DAIRY')]);
    const menu = await rowMenu('Milk');
    expect(button('Remove photo', menu).disabled).toBe(true);
    const chooser = page().querySelector<HTMLInputElement>('[data-testid=photo-input]')!;
    const choose = vi.spyOn(chooser, 'click').mockImplementation(() => undefined);
    button('Attach photo', menu).click();
    expect(choose).toHaveBeenCalled();

    const photo = new File(['jpeg'], 'milk.jpg', { type: 'image/jpeg' });
    Object.defineProperty(chooser, 'files', { value: [photo], configurable: true });
    chooser.dispatchEvent(new Event('change'));
    await vi.waitFor(() =>
      http.expectOne({ method: 'POST', url: '/api/v1/files' }).flush({ id: 9 }),
    );
    const attach = await vi.waitFor(() =>
      http.expectOne({ method: 'PUT', url: '/api/v1/grocery/items/2/photo' }),
    );
    expect(attach.request.body).toEqual({ fileId: 9 });
    attach.flush({ ...item(2, 'Milk', 'DAIRY'), photoId: 9 });
    await vi.waitFor(async () => {
      await harness.fixture.whenStable();
      http.expectOne('/api/v1/grocery/items/2/photos/9?size=128').flush(new Blob(['jpeg']));
    });
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=item-2] img')).not.toBeNull();

    button('Remove photo', await rowMenu('Milk')).click();
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/grocery/items/2/photo' })
      .flush(item(2, 'Milk', 'DAIRY'));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=item-2] img')).toBeNull();
  });

  it('strikes a checked item through until the time is up, undoes it, the backend learns it afterwards', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([defaultList], [item(2, 'Milk', 'DAIRY'), item(3, 'Candles', null)]);

    // bought: struck through while the bar runs, unstruck with undo or a second click, nothing sent
    const struck = () =>
      page()
        .querySelector('[data-testid=item-2] .item-name')!
        .classList.contains('text-decoration-line-through');
    // a tap on the row, no checkbox; the row says it is pressed while it is struck through
    expect(page().querySelector('[data-testid=item-2] input[type=checkbox]')).toBeNull();
    input('item-2').click();
    await harness.fixture.whenStable();
    expect(struck()).toBe(true);
    expect(input('item-2').getAttribute('aria-pressed')).toBe('true');
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
    expect(page().querySelector('[aria-label="Delete Candles"]')).toBeNull();
    vi.advanceTimersByTime(GROCERY_UNDO_MS);
    http.expectOne({ method: 'DELETE', url: '/api/v1/grocery/items/3' }).flush(null);
    await harness.fixture.whenStable();
    expect(page().textContent).toContain('Nothing to buy yet, add something.');
    expect(page().querySelector('[role=status]')).toBeNull();
    vi.useRealTimers();
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
    expect(request.request.body).toEqual({ name: 'Party', shared: true, icon: null });
    request.flush({ ...party, mine: true });
    await harness.fixture.whenStable();
    expect(url()).toBe('/grocery/lists/2');
    http.match(() => true).forEach((pending) => pending.flush(pageOf([])));
  });

  it('the only own list can not be deleted, a shared list of another user does not count', async () => {
    await open('/grocery/lists/1/edit');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList, party]));
    await harness.fixture.whenStable();
    expect(button('Delete list').disabled).toBe(true);
    expect(page().querySelector('[data-testid=last-list]')?.textContent).toContain(
      'Your only list can not be deleted.',
    );
  });

  it('the list of the sign-in is renamed and deleted like the others', async () => {
    await open('/grocery/lists/1/edit');
    http
      .expectOne('/api/v1/grocery/lists?offset=0&limit=100')
      .flush(pageOf([defaultList, { id: 3, name: 'Weekly', shared: false, mine: true }]));
    await harness.fixture.whenStable();
    expect(input('list-name').readOnly).toBe(false);
    expect(page().querySelector('[data-testid=last-list]')).toBeNull();

    // delete, cancel and save are one group; the delete asks in its place
    const group = () => page().querySelector('[role=group][aria-label="Actions of the form"]');
    expect(
      [...group()!.querySelectorAll('a, button')].map((each) => each.textContent!.trim()),
    ).toEqual(['Delete list', 'Cancel', 'Save']);
    button('Delete list').click();
    await harness.fixture.whenStable();
    expect(group()).toBeNull();
    expect(page().textContent).toContain('Delete Default?');
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

  it('deletes a list from the menu of its row; of another user or the last own one it is disabled', async () => {
    const weekly = { ...defaultList, id: 3, name: 'Weekly' };
    await overview([defaultList, party, weekly]);
    const menu = await rowMenu('Party');
    expect(button('Delete', menu).disabled).toBe(true);
    expect(button('Delete', menu).title).toBe('Only the owner of a list changes it');
    expect(button('Delete', await rowMenu('Weekly')).disabled).toBe(false);

    await deleteFromRow('Weekly');
    http.expectOne({ method: 'DELETE', url: '/api/v1/grocery/lists/3' }).flush(null);
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList, party]));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=list-3]')).toBeNull();

    // the only own list stays (LIST-1)
    const last = await rowMenu('Default');
    expect(button('Delete', last).disabled).toBe(true);
    expect(button('Delete', last).title).toBe('Your only list can not be deleted.');
  });

  it('the delete in the menu of a row asks first, keep is the way back', async () => {
    await overview([defaultList, { ...defaultList, id: 3, name: 'Weekly' }]);
    button('Delete', await rowMenu('Weekly')).click();
    await harness.fixture.whenStable();
    expect(page().textContent).toContain('Delete Weekly?');
    button('Keep').click();
    await harness.fixture.whenStable();
    http.expectNone({ method: 'DELETE' });
    expect(button('Actions of Weekly').classList.contains('d-none')).toBe(false);
  });

  it('a change of a list loads all lists again (LIST-4)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await overview([defaultList]);
    changes.next(2);
    changes.next(1);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    // the changes that come together are loaded once
    http
      .expectOne('/api/v1/grocery/lists?offset=0&limit=100')
      .flush(pageOf([{ ...defaultList, items: 4 }]));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=list-1]')?.textContent).toContain('4 items to buy');
    vi.useRealTimers();
  });

  it('a change of the list shown loads it again, of another list not (LIST-4)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([defaultList, party], []);
    changes.next(2);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    http.expectNone('/api/v1/grocery/lists/1/items?offset=0&limit=100');

    changes.next(1);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList, party]));
    http
      .expectOne('/api/v1/grocery/lists/1/items?offset=0&limit=100')
      .flush(pageOf([item(10, 'Milk', null)]));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=item-10]')?.textContent).toContain('Milk');

    // the stream was away: everything again; the list is gone, all lists show
    changes.next(EVERYTHING);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([party]));
    http.expectOne('/api/v1/grocery/lists/1/items?offset=0&limit=100').flush(pageOf([]));
    await harness.fixture.whenStable();
    expect(url()).toBe('/grocery');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([party]));
    vi.useRealTimers();
  });

  it('the settings change the icon of a list, no icon shows the first letter', async () => {
    await open('/grocery/lists/2/edit');
    http
      .expectOne('/api/v1/grocery/lists?offset=0&limit=100')
      .flush(pageOf([defaultList, { ...party, mine: true }]));
    await harness.fixture.whenStable();
    expect(page().querySelector('h1')?.textContent?.trim()).toBe('Settings');
    const icon = () => page().querySelector('[data-testid=list-icon]')!.textContent!.trim();
    expect(icon()).toBe('🎉');

    button('No icon').click();
    await harness.fixture.whenStable();
    expect(icon()).toBe('P');
    expect(button('No icon').disabled).toBe(true);
    button('Save').click();
    expect(http.expectOne({ method: 'PUT', url: '/api/v1/grocery/lists/2' }).request.body).toEqual({
      name: 'Party',
      shared: true,
      icon: null,
    });
  });

  it('the settings lead back where they were opened, all lists or the list', async () => {
    for (const [settings, back] of [
      ['/grocery/lists/1/edit?from=lists', '/grocery'],
      ['/grocery/lists/1/edit', '/grocery/lists/1'],
    ]) {
      await open(settings);
      http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
      await harness.fixture.whenStable();
      expect(button('Back').getAttribute('href')).toBe(back);
      expect(button('Cancel').getAttribute('href')).toBe(back);
      button('Save').click();
      http.expectOne({ method: 'PUT', url: '/api/v1/grocery/lists/1' }).flush(defaultList);
      await harness.fixture.whenStable();
      expect(url()).toBe(back);
      http.match(() => true).forEach((pending) => pending.flush(pageOf([])));
      await harness.fixture.whenStable();
      http.match(() => true).forEach((pending) => pending.flush(pageOf([])));
    }
  });
});
