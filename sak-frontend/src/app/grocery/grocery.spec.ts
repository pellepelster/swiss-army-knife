import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { pageHelpers, pageOf } from '../testing/page';
import { Subject } from 'rxjs';
import { CHANGES_DEBOUNCE_MS, EVERYTHING, GroceryChanges } from './grocery-changes';
import { GROCERY_UNDO_MS } from './grocery-page';
import { groceryRoute } from './grocery.routes';
import { PhotoScaler } from '../notes/photo-scaler';

// the categories of the list 1, in their order (GROCERY-13)
const categories = [
  { id: 11, name: 'Fruit & vegetables', icon: '🥦' },
  { id: 12, name: 'Dairy & eggs', icon: null },
];
const defaultList = { id: 1, name: 'Default', shared: false, mine: true, items: 3, icon: null };
const party = { id: 2, name: 'Party', shared: true, mine: false, items: 1, icon: '🎉' };
const item = (id: number, name: string, categoryId: number | null) => ({
  id,
  listId: 1,
  name,
  amount: null,
  unit: null,
  categoryId,
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

  // the settings the page asks for once, which most specs do not look at: the robot is not offered
  afterEach(() => {
    http
      .match('/api/v1/grocery/settings')
      .forEach((request) => request.cancelled || request.flush({ discoverItems: false }));
    http.verify();
  });

  const { page, button, open, url, rowMenu, deleteFromRow } = pageHelpers(() => harness);
  const input = (id: string) => page().querySelector<HTMLInputElement>(`#${id}`)!;
  // the entry of the menu of the row of the category, e.g. Move down
  const menuEntry = async (name: string, entry: string) => button(entry, await rowMenu(name));
  const type = async (id: string, value: string) => {
    input(id).value = value;
    input(id).dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
  };

  // the items and the categories of a list, as the page loads them together
  const flushItems = async (listId: number, items: object[], of: object[] = categories) => {
    http.expectOne(`/api/v1/grocery/lists/${listId}/items?offset=0&limit=100`).flush(pageOf(items));
    http
      .expectOne(`/api/v1/grocery/lists/${listId}/categories?offset=0&limit=100`)
      .flush(pageOf(of));
    await harness.fixture.whenStable();
  };

  // the page of the list 1, the robot offered or not (GROCERY-16)
  const load = async (lists: object[], items: object[], discoverItems = false) => {
    await open('/grocery/lists/1');
    http.expectOne('/api/v1/grocery/settings').flush({ discoverItems });
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf(lists));
    await harness.fixture.whenStable();
    await flushItems(1, items);
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
    expect(button('Create list').getAttribute('href')).toBe('/grocery/lists/create');
    expect(page().querySelector('#item-name')).toBeNull();
    // the emoji of a list, else the first letter of its name
    expect(
      [...page().querySelectorAll('app-list-icon')].map((icon) => icon.textContent!.trim()),
    ).toEqual(['D', '🎉', '🎉']);
    // the menu of a row leads to the settings, of a shared list of another user too (LIST-2)
    expect(button('Settings of Default').getAttribute('href')).toBe(
      '/grocery/lists/1/edit?from=lists',
    );
    expect(button('Settings of Party').getAttribute('href')).toBe(
      '/grocery/lists/2/edit?from=lists',
    );

    page().querySelector<HTMLAnchorElement>('[data-testid=list-2]')!.click();
    await harness.fixture.whenStable();
    expect(url()).toBe('/grocery/lists/2');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList, party]));
    await harness.fixture.whenStable();
    await flushItems(2, []);
    expect(page().querySelector('[data-testid=list-name]')?.textContent).toContain('Party');
    expect(button('All lists').getAttribute('href')).toBe('/grocery');
    // not the owner's: every user who sees it changes its settings (LIST-2)
    expect(button('Settings').getAttribute('href')).toBe('/grocery/lists/2/edit');
    // one list at a time, no way to the next one but the overview
    expect(button('Next list')).toBeUndefined();
  });

  it('without a list there is only the way to add one', async () => {
    await overview([]);
    expect(page().textContent).toContain('You have no list yet, create one first.');
    expect(button('Create list').getAttribute('href')).toBe('/grocery/lists/create');
  });

  it('a list that is gone leads to all lists', async () => {
    await open('/grocery/lists/7');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
    await harness.fixture.whenStable();
    expect(url()).toBe('/grocery');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
  });

  it('adds an item to the list shown', async () => {
    await load([defaultList], []);
    expect(page().textContent).toContain('Nothing to buy yet, add something.');

    await type('item-name', 'Milk');
    http
      .expectOne((request) => request.url === '/api/v1/grocery/lists/1/suggestions')
      .flush(pageOf([]));
    // the name only, there is no field for a quantity or a category
    expect(page().querySelector('#item-amount, #item-unit, #item-category')).toBeNull();
    button('Add item').click();
    const request = http.expectOne({ method: 'POST', url: '/api/v1/grocery/items' });
    expect(request.request.body).toEqual({
      listId: 1,
      name: 'Milk',
      amount: null,
      unit: null,
      categoryId: null,
    });
    request.flush(item(10, 'Milk', null));
    await flushItems(1, [item(10, 'Milk', null)]);

    expect(input('item-name').value).toBe('');
    expect(page().querySelector('[data-testid=item-10]')?.textContent).toContain('Milk');
  });

  it('shows the items to buy by category', async () => {
    await load(
      [defaultList, party],
      [item(1, 'Apples', 11), item(2, 'Milk', 12), item(3, 'Candles', null)],
    );

    const groups = [...page().querySelectorAll('ul.list-group')].map((list) =>
      list.getAttribute('aria-label'),
    );
    expect(groups).toEqual(['Fruit & vegetables', 'Dairy & eggs', 'No category']);
    // the icon of a category before its name
    expect(page().querySelector('[data-testid=group-11]')?.textContent?.trim()).toBe(
      '🥦 Fruit & vegetables',
    );
    expect(button('Settings').getAttribute('href')).toBe('/grocery/lists/1/edit');
  });

  it('categories that can not be loaded show the items without them and say so', async () => {
    await open('/grocery/lists/1');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
    await harness.fixture.whenStable();
    http
      .expectOne('/api/v1/grocery/lists/1/items?offset=0&limit=100')
      .flush(pageOf([item(2, 'Milk', 12)]));
    http
      .expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100')
      .flush(null, { status: 500, statusText: 'Server Error' });
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=item-2]')).not.toBeNull();
    expect(page().querySelector('[role=alert]')?.textContent).toContain(
      'The categories could not be loaded.',
    );
  });

  it('a category the backend does not set says why and loads the list again', async () => {
    await load([defaultList], [item(2, 'Milk', null)]);
    (await rowMenu('Milk')).querySelectorAll<HTMLButtonElement>('[role=menuitemradio]')[1].click();
    http.expectOne({ method: 'PUT', url: '/api/v1/grocery/items/2/category' }).flush(
      {
        errors: [{ code: 'grocery.unknown_category', message: 'The list has no such category.' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await flushItems(1, [item(2, 'Milk', null)]);
    expect(page().querySelector('[role=alert]')?.textContent).toContain(
      'The list has no such category.',
    );
  });

  it('deletes an item for good from the menu of its row after asking, a failure brings it back', async () => {
    await load([defaultList], [item(2, 'Milk', null), item(3, 'Bread', null)]);
    await deleteFromRow('Milk');
    http.expectOne({ method: 'DELETE', url: '/api/v1/grocery/items/2' }).flush(null);
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=item-2]')).toBeNull();
    http.expectNone({ method: 'POST' });

    await deleteFromRow('Bread');
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/grocery/items/3' })
      .flush(null, { status: 500, statusText: 'Server Error' });
    await flushItems(1, [item(3, 'Bread', null)]);
    // in the row of the item, which is back (UX-15)
    expect(page().querySelector('[data-testid=item-3] [role=alert]')?.textContent).toContain(
      'The item could not be deleted.',
    );
    expect(page().querySelector('[data-testid=item-3]')).not.toBeNull();
  });

  it('sets the category of an item from the menu of its row, one of the list or none', async () => {
    await load([defaultList], [item(2, 'Milk', null)]);
    const choices = async () => [
      ...(await rowMenu('Milk')).querySelectorAll<HTMLButtonElement>('[role=menuitemradio]'),
    ];
    const names = (each: HTMLButtonElement[]) =>
      each.map((choice) => choice.textContent!.replace(/\s+/g, ' ').trim());
    let menu = await choices();
    expect(names(menu)).toEqual(['🥦Fruit & vegetables', 'Dairy & eggs', 'No category']);
    expect(menu.map((choice) => choice.getAttribute('aria-checked'))).toEqual([
      'false',
      'false',
      'true',
    ]);

    menu[1].click();
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/grocery/items/2/category' });
    expect(request.request.body).toEqual({ categoryId: 12 });
    request.flush(item(2, 'Milk', 12));
    await harness.fixture.whenStable();
    expect(
      page().querySelector('ul[aria-label="Dairy & eggs"] [data-testid=item-2]'),
    ).not.toBeNull();

    // the one it has already sends nothing; none takes it away
    menu = await choices();
    menu[1].click();
    http.expectNone({ method: 'PUT' });
    (await choices())[2].click();
    expect(
      http.expectOne({ method: 'PUT', url: '/api/v1/grocery/items/2/category' }).request.body,
    ).toEqual({ categoryId: null });
  });

  it('the settings of a list add, rename, order and delete its categories, each at once', async () => {
    await open('/grocery/lists/1/edit');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
    http
      .expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100')
      .flush(pageOf(categories));
    await harness.fixture.whenStable();
    const field = (name: string) =>
      page().querySelector<HTMLInputElement>(`input[aria-label="Name of ${name}"]`)!;
    // the first can not go up, the last not down, each says why (UX-8, UX-12)
    expect((await menuEntry('Fruit & vegetables', 'Move up')).disabled).toBe(true);
    expect((await menuEntry('Fruit & vegetables', 'Move up')).title).toBe(
      'The category is the first.',
    );
    expect((await menuEntry('Fruit & vegetables', 'Move down')).disabled).toBe(false);
    expect((await menuEntry('Dairy & eggs', 'Move down')).disabled).toBe(true);
    expect((await menuEntry('Dairy & eggs', 'Move down')).title).toBe('The category is the last.');
    // each row has a grip to drag it by, named for a screen reader; no arrows in the row
    expect(
      page().querySelector(
        '[data-testid=category-11] [cdkDragHandle], [data-testid=category-11] .category-handle',
      )?.textContent,
    ).toContain('Drag to move Fruit & vegetables');
    expect(
      page().querySelector('[data-testid=category-11] .input-group .fa-arrow-down'),
    ).toBeNull();

    // a name left empty is not saved and says why (UX-6), a new one is saved when it is left
    field('Dairy & eggs').value = ' ';
    field('Dairy & eggs').dispatchEvent(new Event('blur'));
    await harness.fixture.whenStable();
    // the message is always in the group (has-validation), the invalid field shows it
    expect(field('Dairy & eggs').classList).toContain('is-invalid');
    expect(field('Dairy & eggs').parentElement?.textContent).toContain('A category needs a name.');
    http.expectNone({ method: 'PUT' });
    field('Dairy & eggs').value = 'Dairy';
    field('Dairy & eggs').dispatchEvent(new Event('input'));
    field('Dairy & eggs').dispatchEvent(new Event('blur'));
    await harness.fixture.whenStable();
    expect(field('Dairy & eggs').classList).not.toContain('is-invalid');
    const rename = http.expectOne({ method: 'PUT', url: '/api/v1/grocery/lists/1/categories/12' });
    expect(rename.request.body).toEqual({ name: 'Dairy', icon: null });
    rename.flush({ ...categories[1], name: 'Dairy' });
    await harness.fixture.whenStable();

    (await menuEntry('Fruit & vegetables', 'Move down')).click();
    const order = http.expectOne({
      method: 'PUT',
      url: '/api/v1/grocery/lists/1/categories/order',
    });
    expect(order.request.body).toEqual({ ids: [12, 11] });
    order.flush(pageOf([{ ...categories[1], name: 'Dairy' }, categories[0]]));
    await harness.fixture.whenStable();
    // the focus goes back to the menu of the moved row, so a keyboard moves it on
    expect(document.activeElement).toBe(button('Actions of Fruit & vegetables'));
    expect((await menuEntry('Dairy', 'Move up')).disabled).toBe(true);

    // the icon goes from the menu of its row
    button('No icon', await rowMenu('Fruit & vegetables')).click();
    const noIcon = http.expectOne({ method: 'PUT', url: '/api/v1/grocery/lists/1/categories/11' });
    expect(noIcon.request.body).toEqual({ name: 'Fruit & vegetables', icon: null });
    noIcon.flush({ ...categories[0], icon: null });
    await harness.fixture.whenStable();

    // a new one is the last; no plus, it is not the main action of the page (UX-2)
    expect(button('Add category').disabled).toBe(true);
    await type('new-category', 'Frozen');
    expect(button('Add category').querySelector('.sak-icon-plus')).toBeNull();
    button('Add category').click();
    const add = http.expectOne({ method: 'POST', url: '/api/v1/grocery/lists/1/categories' });
    expect(add.request.body).toEqual({ name: 'Frozen', icon: null });
    add.flush({ id: 13, name: 'Frozen', icon: null });
    await harness.fixture.whenStable();
    expect(input('new-category').value).toBe('');
    expect(page().querySelector('[data-testid=category-13]')).not.toBeNull();

    // the delete asks first (UX-12)
    await deleteFromRow('Frozen');
    http.expectOne({ method: 'DELETE', url: '/api/v1/grocery/lists/1/categories/13' }).flush(null);
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=category-13]')).toBeNull();
  });

  it('a category the backend refuses says why and keeps its name', async () => {
    await open('/grocery/lists/1/edit');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
    http
      .expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100')
      .flush(pageOf(categories));
    await harness.fixture.whenStable();
    const field = page().querySelector<HTMLInputElement>(
      'input[aria-label="Name of Dairy & eggs"]',
    )!;
    field.value = 'Fruit & vegetables';
    field.dispatchEvent(new Event('blur'));
    http.expectOne({ method: 'PUT', url: '/api/v1/grocery/lists/1/categories/12' }).flush(
      {
        errors: [
          { code: 'grocery.category_exists', message: 'The list has a category of the name.' },
        ],
      },
      { status: 409, statusText: 'Conflict' },
    );
    // what is stored comes again, the next changes start from it
    http
      .expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100')
      .flush(pageOf(categories));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=category-12] [role=alert]')?.textContent).toContain(
      'The list has a category of the name.',
    );
    expect(field.value).toBe('Dairy & eggs');

    // the same name with spaces around it is no change and shows without them
    field.value = ' Dairy & eggs ';
    field.dispatchEvent(new Event('blur'));
    http.expectNone({ method: 'PUT' });
    expect(field.value).toBe('Dairy & eggs');
  });

  // the settings of the list 1 with its categories
  const settings = async (of: object[] = categories) => {
    await open('/grocery/lists/1/edit');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
    http.expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100').flush(pageOf(of));
    await harness.fixture.whenStable();
  };
  const refused = { errors: [{ code: 'api.not_found', message: 'Not found.' }] };

  it('an order or a delete the backend refuses says why and loads the categories again', async () => {
    const frozen = { id: 13, name: 'Frozen', icon: null };
    await settings();
    (await menuEntry('Fruit & vegetables', 'Move down')).click();
    http
      .expectOne({ method: 'PUT', url: '/api/v1/grocery/lists/1/categories/order' })
      .flush(
        { errors: [{ code: 'grocery.invalid_category_order', message: 'The order is wrong.' }] },
        { status: 400, statusText: 'Bad Request' },
      );
    // another device added one meanwhile
    http
      .expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100')
      .flush(pageOf([...categories, frozen]));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=category-11] [role=alert]')?.textContent).toContain(
      'The order is wrong.',
    );
    expect(page().querySelector('[data-testid=category-13]')).not.toBeNull();

    await deleteFromRow('Dairy & eggs');
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/grocery/lists/1/categories/12' })
      .flush(refused, { status: 404, statusText: 'Not Found' });
    http
      .expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100')
      .flush(pageOf([categories[0], frozen]));
    await harness.fixture.whenStable();
    // its row is gone, so above the categories
    expect(page().querySelector('[role=alert]')?.textContent).toContain('Not found.');
    expect(page().querySelector('[data-testid=category-12]')).toBeNull();
  });

  it('changes of the categories go one after the other, a delete waits for the order before it', async () => {
    await settings();
    const field = page().querySelector<HTMLInputElement>(
      'input[aria-label="Name of Dairy & eggs"]',
    )!;
    // a rename, then a move at once: the move is not lost, it waits for the rename
    field.value = 'Dairy';
    field.dispatchEvent(new Event('blur'));
    await harness.fixture.whenStable();
    const down = await menuEntry('Fruit & vegetables', 'Move down');
    expect(down.disabled).toBe(false);
    down.click();
    http.expectNone({ method: 'PUT', url: '/api/v1/grocery/lists/1/categories/order' });
    // the delete of a row waits until the changes are saved (UX-8)
    const menu = await rowMenu('Dairy & eggs');
    expect(button('Delete', menu).disabled).toBe(true);
    expect(button('Delete', menu).title).toBe(
      'Wait until the changes of the categories are saved.',
    );

    http
      .expectOne({ method: 'PUT', url: '/api/v1/grocery/lists/1/categories/12' })
      .flush({ ...categories[1], name: 'Dairy' });
    const order = http.expectOne({
      method: 'PUT',
      url: '/api/v1/grocery/lists/1/categories/order',
    });
    expect(order.request.body).toEqual({ ids: [12, 11] });
    // an answer with the old name keeps the new one, it only orders
    order.flush(pageOf([categories[1], categories[0]]));
    await harness.fixture.whenStable();
    expect(
      page().querySelector<HTMLInputElement>('input[aria-label="Name of Dairy"]'),
    ).not.toBeNull();

    // the row of a deleted category goes by its id, also after the order replaced the rows
    await deleteFromRow('Dairy');
    http.expectOne({ method: 'DELETE', url: '/api/v1/grocery/lists/1/categories/12' }).flush(null);
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=category-12]')).toBeNull();
  });

  it('a category dropped at another place shows there at once and sends the new order', async () => {
    const frozen = { id: 13, name: 'Frozen', icon: '🧊' };
    await settings([...categories, frozen]);
    const list = harness.routeDebugElement!.query(By.css('ul[cdkDropList], ul.cdk-drop-list'));
    const ids = () =>
      [...page().querySelectorAll('[data-testid^=category-]')].map((row) =>
        row.getAttribute('data-testid'),
      );

    // dropped where it was: nothing
    list.triggerEventHandler('cdkDropListDropped', { previousIndex: 1, currentIndex: 1 });
    http.expectNone({ method: 'PUT' });

    // Frozen from the end to the top
    list.triggerEventHandler('cdkDropListDropped', { previousIndex: 2, currentIndex: 0 });
    await harness.fixture.whenStable();
    expect(ids()).toEqual(['category-13', 'category-11', 'category-12']);
    const order = http.expectOne({
      method: 'PUT',
      url: '/api/v1/grocery/lists/1/categories/order',
    });
    expect(order.request.body).toEqual({ ids: [13, 11, 12] });
    order.flush(pageOf([frozen, ...categories]));
    await harness.fixture.whenStable();
    expect(ids()).toEqual(['category-13', 'category-11', 'category-12']);

    // a drop while a rename waits: the drop waits too, the rename is kept
    const field = page().querySelector<HTMLInputElement>('input[aria-label="Name of Frozen"]')!;
    field.value = 'Ice';
    field.dispatchEvent(new Event('blur'));
    list.triggerEventHandler('cdkDropListDropped', { previousIndex: 0, currentIndex: 2 });
    http.expectNone({ method: 'PUT', url: '/api/v1/grocery/lists/1/categories/order' });
    http
      .expectOne({ method: 'PUT', url: '/api/v1/grocery/lists/1/categories/13' })
      .flush({ ...frozen, name: 'Ice' });
    const again = http.expectOne({
      method: 'PUT',
      url: '/api/v1/grocery/lists/1/categories/order',
    });
    expect(again.request.body).toEqual({ ids: [11, 12, 13] });
    again.flush(pageOf([...categories, frozen]));
    await harness.fixture.whenStable();
    expect(ids()).toEqual(['category-11', 'category-12', 'category-13']);
    expect(
      page().querySelector<HTMLInputElement>('input[aria-label="Name of Ice"]'),
    ).not.toBeNull();
  });

  it('a change of the list from elsewhere loads its categories in the settings again (LIST-4)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await settings();
    changes.next(2);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    http.expectNone('/api/v1/grocery/lists/1/categories?offset=0&limit=100');
    changes.next(1);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    http
      .expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100')
      .flush(pageOf([categories[1]]));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=category-11]')).toBeNull();
    vi.useRealTimers();
  });

  it('a new list has no categories to edit yet, the backend gives it its first ones', async () => {
    await open('/grocery/lists/create');
    expect(page().querySelector('#categories-title')).toBeNull();
  });

  it('shows the photo of an item scaled to its place, a tap on it shows it in full and does not check the item', async () => {
    await load([defaultList], [{ ...item(2, 'Milk', 12), photoId: 9 }, item(3, 'Candles', null)]);
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
    http.expectNone({ method: 'POST' });

    viewer.click();
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=photo-viewer]')).toBeNull();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:photo-2');
  });

  it('a photo that can not be loaded leaves the row without one, the phone offers its camera and its photos', async () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    await load([defaultList], [{ ...item(2, 'Milk', 12), photoId: 9 }]);
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
    await load([defaultList], [item(2, 'Milk', 12)]);
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
    attach.flush({ ...item(2, 'Milk', 12), photoId: 9 });
    await vi.waitFor(async () => {
      await harness.fixture.whenStable();
      http.expectOne('/api/v1/grocery/items/2/photos/9?size=128').flush(new Blob(['jpeg']));
    });
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=item-2] img')).not.toBeNull();

    button('Remove photo', await rowMenu('Milk')).click();
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/grocery/items/2/photo' })
      .flush(item(2, 'Milk', 12));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=item-2] img')).toBeNull();
  });

  it('shows the bought items with the eye, in their category, struck through, not checked by a tap, changed from their menu (GROCERY-14)', async () => {
    await load([defaultList], [item(2, 'Milk', 12)]);
    const toggle = page().querySelector<HTMLButtonElement>('[data-testid=toggle-bought]')!;
    expect(toggle.querySelector('i')!.classList.contains('fa-eye-slash')).toBe(true);
    expect(toggle.getAttribute('aria-pressed')).toBe('false');
    expect(toggle.getAttribute('aria-label')).toBe('Show bought items');

    toggle.click();
    await harness.fixture.whenStable();
    http.expectOne('/api/v1/grocery/lists/1/items?checked=true&offset=0&limit=100').flush(
      // the bought one first: the group puts the items to buy first all the same
      pageOf([
        { ...item(4, 'Cheese', 12), checkedAt: '2026-11-01T10:00:00Z' },
        item(2, 'Milk', 12),
      ]),
    );
    http
      .expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100')
      .flush(pageOf(categories));
    await harness.fixture.whenStable();
    expect(toggle.querySelector('i')!.classList.contains('fa-eye')).toBe(true);
    // one label, the state is aria-pressed; the tooltip says what a click does
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
    expect(toggle.getAttribute('aria-label')).toBe('Show bought items');
    expect(toggle.title).toBe('Hide bought items');

    // the bought one is in its category, struck through, a tap does not check it
    const bought = page().querySelector('ul[aria-label="Dairy & eggs"] [data-testid=item-4]')!;
    expect(bought).not.toBeNull();
    expect(
      [...page().querySelectorAll('ul[aria-label="Dairy & eggs"] li')].map((row) =>
        row.getAttribute('data-testid'),
      ),
    ).toEqual(['item-2', 'item-4']);
    expect(bought.querySelector('.item-name')!.classList.contains('item-struck')).toBe(true);
    expect(
      page().querySelector('[data-testid=item-2] .item-name')!.classList.contains('item-struck'),
    ).toBe(false);
    input('item-4').click();
    await harness.fixture.whenStable();
    expect(page().querySelector('[role=status]')).toBeNull();
    http.expectNone({ method: 'POST' });

    // its menu changes it as the one of an item to buy
    const choices = [
      ...(await rowMenu('Cheese')).querySelectorAll<HTMLButtonElement>('[role=menuitemradio]'),
    ];
    expect(choices.every((choice) => !choice.disabled)).toBe(true);
    choices[2].click();
    http
      .expectOne({ method: 'PUT', url: '/api/v1/grocery/items/4/category' })
      .flush({ ...item(4, 'Cheese', null), checkedAt: '2026-11-01T10:00:00Z' });
    await harness.fixture.whenStable();
    const none = page().querySelector('ul[aria-label="No category"] [data-testid=item-4]')!;
    expect(none.querySelector('.item-name')!.classList.contains('item-struck')).toBe(true);
    await deleteFromRow('Cheese');
    http.expectOne({ method: 'DELETE', url: '/api/v1/grocery/items/4' }).flush(null);
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=item-4]')).toBeNull();

    // hidden again: the items to buy only
    toggle.click();
    await harness.fixture.whenStable();
    await flushItems(1, [item(2, 'Milk', 12)]);
    expect(page().querySelector('[data-testid=item-4]')).toBeNull();
  });

  it('renames an item in its row from its menu, to buy or bought; Escape keeps the name, an empty one is not saved', async () => {
    await load([defaultList], [item(2, 'Mlik', 12)]);
    const rename = async (name: string) => {
      button('Rename', await rowMenu(name)).click();
      await harness.fixture.whenStable();
    };
    const field = (id: number) => page().querySelector<HTMLInputElement>(`#rename-${id}`)!;

    // Escape keeps the name, leaving the field afterwards saves nothing
    await rename('Mlik');
    expect(field(2).value).toBe('Mlik');
    // the Rename of the item renamed is disabled and says why (UX-12)
    const renameEntry = button('Rename', page().querySelector('[data-testid=item-2]')!);
    expect(renameEntry.disabled).toBe(true);
    expect(renameEntry.title).toBe('The name is changed in its row.');
    field(2).value = 'Milch';
    field(2).dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await harness.fixture.whenStable();
    expect(field(2)).toBeNull();
    http.expectNone({ method: 'PUT' });
    // the focus is back on the row, not lost to the page
    expect(document.activeElement?.id).toBe('item-2');

    // an empty name says so under the field when it is left, typing takes the error away
    await rename('Mlik');
    field(2).value = '  ';
    field(2).dispatchEvent(new Event('blur'));
    await harness.fixture.whenStable();
    expect(field(2).classList.contains('is-invalid')).toBe(true);
    expect(page().querySelector('#rename-error-2')?.textContent).toContain('An item needs a name.');
    http.expectNone({ method: 'PUT' });
    field(2).value = ' Milk ';
    field(2).dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
    expect(page().querySelector('#rename-error-2')).toBeNull();

    // Enter saves the name trimmed, with the quantity and the category the item has; the field
    // left afterwards saves it no second time
    const open = field(2);
    open.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await harness.fixture.whenStable();
    open.dispatchEvent(new Event('blur'));
    await harness.fixture.whenStable();
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/grocery/items/2' });
    expect(request.request.body).toEqual({
      name: 'Milk',
      amount: null,
      unit: null,
      categoryId: 12,
    });
    request.flush(item(2, 'Milk', 12));
    await harness.fixture.whenStable();
    expect(field(2)).toBeNull();
    expect(document.activeElement?.id).toBe('item-2');
    expect(page().querySelector('[data-testid=item-2] .item-name')?.textContent).toContain('Milk');
    // a tap on the row while the field is open does not check it: the row is the field
    http.expectNone({ method: 'POST' });

    // a bought item is renamed the same way; a refused name says why and loads the list again
    page().querySelector<HTMLButtonElement>('[data-testid=toggle-bought]')!.click();
    await harness.fixture.whenStable();
    http
      .expectOne('/api/v1/grocery/lists/1/items?checked=true&offset=0&limit=100')
      .flush(
        pageOf([
          item(2, 'Milk', 12),
          { ...item(4, 'Bred', null), checkedAt: '2026-11-01T10:00:00Z' },
        ]),
      );
    http
      .expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100')
      .flush(pageOf(categories));
    await harness.fixture.whenStable();
    await rename('Bred');
    field(4).value = 'Bread';
    field(4).dispatchEvent(new Event('blur'));
    await harness.fixture.whenStable();
    http
      .expectOne({ method: 'PUT', url: '/api/v1/grocery/items/4' })
      .flush(
        { errors: [{ code: 'grocery.invalid_item_name', message: 'An item name is too long.' }] },
        { status: 400, statusText: 'Bad Request' },
      );
    await harness.fixture.whenStable();
    expect(page().querySelector('[role=alert]')?.textContent).toContain(
      'An item name is too long.',
    );
    http
      .expectOne('/api/v1/grocery/lists/1/items?checked=true&offset=0&limit=100')
      .flush(
        pageOf([
          item(2, 'Milk', 12),
          { ...item(4, 'Bred', null), checkedAt: '2026-11-01T10:00:00Z' },
        ]),
      );
    http
      .expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100')
      .flush(pageOf(categories));
    await harness.fixture.whenStable();
  });

  it('strikes a checked item through until the time is up, undoes it, the backend learns it afterwards', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([defaultList], [item(2, 'Milk', 12), item(3, 'Candles', null)]);

    // bought: struck through while the bar runs, unstruck with undo or a second click, nothing sent
    const struck = () =>
      page().querySelector('[data-testid=item-2] .item-name')!.classList.contains('item-struck');
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
    http.expectNone({ method: 'POST' });

    // bought for good once the time is up: the item is checked
    input('item-2').click();
    await harness.fixture.whenStable();
    vi.advanceTimersByTime(GROCERY_UNDO_MS);
    http.expectOne({ method: 'POST', url: '/api/v1/grocery/items/2/check' }).flush(null);
    await harness.fixture.whenStable();
    // gone once the time is up
    expect(page().querySelector('[data-testid=item-2]')).toBeNull();

    // another check sends the one before at once
    input('item-3').click();
    await harness.fixture.whenStable();
    expect(page().querySelector('[role=status]')?.textContent).toContain('Candles bought');
    expect(page().querySelector('[aria-label="Delete Candles"]')).toBeNull();
    vi.advanceTimersByTime(GROCERY_UNDO_MS);
    http.expectOne({ method: 'POST', url: '/api/v1/grocery/items/3/check' }).flush(null);
    await harness.fixture.whenStable();
    expect(page().textContent).toContain('Nothing to buy yet, add something.');
    expect(page().querySelector('[role=status]')).toBeNull();
    vi.useRealTimers();
  });

  it('a delete or a rename in the time of the undo takes the check along; with the eye a check, a change and one bought elsewhere load the bought items too', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([defaultList], [item(2, 'Milk', 12), item(3, 'Bread', null), item(5, 'Eggs', 12)]);
    const field = (id: number) => page().querySelector<HTMLInputElement>(`#rename-${id}`);
    const flushBought = async (items: object[]) => {
      http
        .expectOne('/api/v1/grocery/lists/1/items?checked=true&offset=0&limit=100')
        .flush(pageOf(items));
      http
        .expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100')
        .flush(pageOf(categories));
      await harness.fixture.whenStable();
    };

    // deleted while it waits to be checked: its check and its toast go with it (GB-1)
    input('item-2').click();
    await harness.fixture.whenStable();
    await deleteFromRow('Milk');
    http.expectOne({ method: 'DELETE', url: '/api/v1/grocery/items/2' }).flush(null);
    await harness.fixture.whenStable();
    expect(page().querySelector('[role=status]')).toBeNull();
    vi.advanceTimersByTime(GROCERY_UNDO_MS);
    http.expectNone({ method: 'POST' });

    // renamed while it waits: the field closes once it is checked (GB-14)
    input('item-3').click();
    await harness.fixture.whenStable();
    button('Rename', await rowMenu('Bread')).click();
    await harness.fixture.whenStable();
    expect(field(3)).not.toBeNull();
    vi.advanceTimersByTime(GROCERY_UNDO_MS);
    http.expectOne({ method: 'POST', url: '/api/v1/grocery/items/3/check' }).flush(null);
    await harness.fixture.whenStable();
    expect(field(3)).toBeNull();

    // with the eye a check loads the list again, the item comes back bought in its place
    page().querySelector<HTMLButtonElement>('[data-testid=toggle-bought]')!.click();
    await harness.fixture.whenStable();
    const bread = { ...item(3, 'Bread', null), checkedAt: '2026-11-01T10:00:00Z' };
    await flushBought([item(5, 'Eggs', 12), bread]);
    input('item-5').click();
    await harness.fixture.whenStable();
    vi.advanceTimersByTime(GROCERY_UNDO_MS);
    http.expectOne({ method: 'POST', url: '/api/v1/grocery/items/5/check' }).flush(null);
    await harness.fixture.whenStable();
    const eggs = { ...item(5, 'Eggs', 12), checkedAt: '2026-11-01T11:00:00Z' };
    await flushBought([item(6, 'Butter', 12), eggs, bread]);

    // a change from elsewhere keeps the bought items (LIST-4)
    changes.next(1);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
    await flushBought([item(6, 'Butter', 12), eggs, bread]);

    // bought on another device while it waits: a second tap still undoes it (GB-6)
    input('item-6').click();
    await harness.fixture.whenStable();
    const butter = { ...item(6, 'Butter', 12), checkedAt: '2026-11-01T12:00:00Z' };
    const changed = async (items: object[]) => {
      changes.next(1);
      vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
      http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
      await flushBought(items);
    };
    await changed([item(7, 'Flour', null), butter, eggs, bread]);
    input('item-6').click();
    await harness.fixture.whenStable();
    expect(page().querySelector('[role=status]')).toBeNull();

    // its check is done already once the time is up: nothing is sent, no error
    input('item-7').click();
    await harness.fixture.whenStable();
    const flour = { ...item(7, 'Flour', null), checkedAt: '2026-11-01T13:00:00Z' };
    await changed([flour, butter, eggs, bread]);
    vi.advanceTimersByTime(GROCERY_UNDO_MS);
    http.expectNone({ method: 'POST' });
    await flushBought([flour, butter, eggs, bread]);
    expect(page().querySelector('[role=alert]')).toBeNull();
    vi.useRealTimers();
  });

  it('a check of an item the backend has bought already (404) is done, a page left while a check waits loads nothing again', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([defaultList], [item(2, 'Milk', 12), item(3, 'Bread', null)]);
    const flushBought = async (items: object[]) => {
      http
        .expectOne('/api/v1/grocery/lists/1/items?checked=true&offset=0&limit=100')
        .flush(pageOf(items));
      http
        .expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100')
        .flush(pageOf(categories));
      await harness.fixture.whenStable();
    };
    page().querySelector<HTMLButtonElement>('[data-testid=toggle-bought]')!.click();
    await harness.fixture.whenStable();
    await flushBought([item(2, 'Milk', 12), item(3, 'Bread', null)]);

    // checked on another device before the change came: no error, the list as the backend has it
    input('item-2').click();
    await harness.fixture.whenStable();
    vi.advanceTimersByTime(GROCERY_UNDO_MS);
    http
      .expectOne({ method: 'POST', url: '/api/v1/grocery/items/2/check' })
      .flush(null, { status: 404, statusText: 'Not Found' });
    await harness.fixture.whenStable();
    await flushBought([
      item(3, 'Bread', null),
      { ...item(2, 'Milk', 12), checkedAt: '2026-11-01T10:00:00Z' },
    ]);
    expect(page().querySelector('[role=alert]')).toBeNull();

    // the page left while Bread waits: its check goes out, nothing is loaded again (GB-5)
    input('item-3').click();
    await harness.fixture.whenStable();
    await open('/grocery');
    http.expectOne({ method: 'POST', url: '/api/v1/grocery/items/3/check' }).flush(null);
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
    await harness.fixture.whenStable();
    vi.useRealTimers();
  });

  it('a suggestion chosen brings its quantity and category along', async () => {
    await load([defaultList], []);
    await type('item-name', 'mi');
    http
      .expectOne((request) => request.url === '/api/v1/grocery/lists/1/suggestions')
      .flush(pageOf([{ id: 5, name: 'Milk', amount: 2, unit: 'l', categoryId: 12 }]));
    await harness.fixture.whenStable();
    expect(page().querySelector('#grocery-suggestions option')?.getAttribute('value')).toBe('Milk');

    await type('item-name', 'Milk');
    button('Add item').click();
    expect(http.expectOne({ method: 'POST', url: '/api/v1/grocery/items' }).request.body).toEqual({
      listId: 1,
      name: 'Milk',
      amount: 2,
      unit: 'l',
      categoryId: 12,
    });
  });

  it('sets the quantity of an item in its row from its menu, with a comma or a point; Escape and Cancel keep it, an invalid number is not saved (GROCERY-15)', async () => {
    await load([defaultList], [item(2, 'Flour', null), item(3, 'Milk', 12)]);
    const quantity = async (name: string) => {
      button('Quantity', await rowMenu(name)).click();
      await harness.fixture.whenStable();
    };
    const amount = () => input('amount-2');
    const unit = () => page().querySelector<HTMLSelectElement>('#unit-2')!;
    const row = () => page().querySelector('[data-testid=item-2]')!;

    // the fields in the row, the number focused; its menu entry disabled while they are open
    await quantity('Flour');
    expect(document.activeElement?.id).toBe('amount-2');
    expect(row().querySelector('[role=group]')?.getAttribute('aria-label')).toBe(
      'Quantity of Flour',
    );
    expect(amount().getAttribute('aria-label')).toBe('Amount');
    expect(unit().getAttribute('aria-label')).toBe('Unit');
    expect(button('Quantity', row()).disabled).toBe(true);
    expect([...unit().options].map((option) => option.text.trim())).toEqual([
      'No unit',
      'g',
      'kg',
      'ml',
      'l',
      'tbsp',
      'tsp',
      'pinch',
      'pack',
      'can',
      'bunch',
      'piece',
    ]);
    expect(unit().value).toBe('');

    // Escape keeps the quantity, the focus is back on the row
    amount().value = '3';
    amount().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await harness.fixture.whenStable();
    expect(page().querySelector('#amount-2')).toBeNull();
    expect(document.activeElement?.id).toBe('item-2');

    // so does Cancel; leaving a field saves nothing
    await quantity('Flour');
    amount().value = '3';
    amount().dispatchEvent(new Event('blur'));
    await harness.fixture.whenStable();
    button('Cancel', row()).click();
    await harness.fixture.whenStable();
    expect(page().querySelector('#amount-2')).toBeNull();
    http.expectNone({ method: 'PUT' });

    // a number that is not positive or has more than 3 decimal places says so when it is left,
    // typing takes the error away; Save keeps the fields open and sends nothing
    await quantity('Flour');
    for (const invalid of ['-1', '0', '1,2345', 'two']) {
      amount().value = invalid;
      amount().dispatchEvent(new Event('blur'));
      await harness.fixture.whenStable();
      expect(amount().classList.contains('is-invalid')).toBe(true);
      expect(page().querySelector('#amount-error-2')?.textContent).toContain(
        'Type a positive number with at most 3 decimal places.',
      );
      amount().dispatchEvent(new Event('input'));
      await harness.fixture.whenStable();
      expect(page().querySelector('#amount-error-2')).toBeNull();
    }
    amount().value = '1.2345';
    button('Save', row()).click();
    await harness.fixture.whenStable();
    expect(page().querySelector('#amount-error-2')).not.toBeNull();
    http.expectNone({ method: 'PUT' });

    // Enter saves, with a comma, the name and the category the item has
    amount().value = '0,5';
    amount().dispatchEvent(new Event('input'));
    unit().value = 'kg';
    amount().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await harness.fixture.whenStable();
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/grocery/items/2' });
    expect(request.request.body).toEqual({
      name: 'Flour',
      amount: 0.5,
      unit: 'kg',
      categoryId: null,
    });
    request.flush({ ...item(2, 'Flour', null), amount: 0.5, unit: 'kg' });
    await harness.fixture.whenStable();
    expect(document.activeElement?.id).toBe('item-2');
    expect(row().textContent).toContain('0.5 kg');

    // the quantity it has: nothing to send
    await quantity('Flour');
    expect(amount().value).toBe('0.5');
    expect(unit().value).toBe('kg');
    button('Save', row()).click();
    await harness.fixture.whenStable();
    http.expectNone({ method: 'PUT' });

    // one field in a row at a time: Rename closes the quantity of the item
    await quantity('Flour');
    button('Rename', row()).click();
    await harness.fixture.whenStable();
    expect(page().querySelector('#amount-2')).toBeNull();
    expect(page().querySelector('#rename-2')).not.toBeNull();
    page()
      .querySelector('#rename-2')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await harness.fixture.whenStable();
  });

  it('a unit an item has that is not one of the list stays one to choose, no number takes the unit away too (GROCERY-15)', async () => {
    await load([defaultList], [{ ...item(2, 'Garlic', null), amount: 2, unit: 'clove' }]);
    const quantity = async () => {
      button('Quantity', await rowMenu('Garlic')).click();
      await harness.fixture.whenStable();
    };
    const amount = () => input('amount-2');
    const unit = () => page().querySelector<HTMLSelectElement>('#unit-2')!;

    await quantity();
    expect(amount().value).toBe('2');
    expect(unit().value).toBe('clove');
    expect([...unit().options].map((option) => option.text.trim()).at(-1)).toBe('clove');
    amount().value = '3';
    button('Save').click();
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/grocery/items/2' });
    expect(request.request.body).toEqual({
      name: 'Garlic',
      amount: 3,
      unit: 'clove',
      categoryId: null,
    });
    request.flush({ ...item(2, 'Garlic', null), amount: 3, unit: 'clove' });
    await harness.fixture.whenStable();

    // no number: no unit either; a quantity the backend refuses says so and loads the list again
    await quantity();
    amount().value = ' ';
    button('Save').click();
    const cleared = http.expectOne({ method: 'PUT', url: '/api/v1/grocery/items/2' });
    expect(cleared.request.body).toEqual({
      name: 'Garlic',
      amount: null,
      unit: null,
      categoryId: null,
    });
    cleared.flush(null, { status: 500, statusText: 'Server Error' });
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=item-2] [role=alert]')?.textContent).toContain(
      'The quantity could not be saved.',
    );
    await flushItems(1, [{ ...item(2, 'Garlic', null), amount: 3, unit: 'clove' }]);
  });

  const robotLabel = 'Add items from a photo, read by the AI features at OpenRouter';

  it('the robot reads items in a photo only with the AI features turned on (GROCERY-16)', async () => {
    await load([defaultList], []);
    expect(button(robotLabel)).toBeUndefined();
  });

  it('settings that can not be loaded offer no robot (GROCERY-16)', async () => {
    await open('/grocery/lists/1');
    http
      .expectOne('/api/v1/grocery/settings')
      .flush(null, { status: 500, statusText: 'Server Error' });
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
    await harness.fixture.whenStable();
    await flushItems(1, []);
    expect(button(robotLabel)).toBeUndefined();
    expect(page().querySelector('[role=alert]')).toBeNull();
  });

  // the robot takes a photo, scaled and stored first; the request that reads the items in it
  const discoverPhoto = async (fileId = 21) => {
    const chooser = page().querySelector<HTMLInputElement>('[data-testid=discover-input]')!;
    const choose = vi.spyOn(chooser, 'click').mockImplementation(() => undefined);
    button(robotLabel).click();
    expect(choose).toHaveBeenCalled();
    const photo = new File(['jpeg'], 'recipe.jpg', { type: 'image/jpeg' });
    Object.defineProperty(chooser, 'files', { value: [photo], configurable: true });
    chooser.dispatchEvent(new Event('change'));
    await vi.waitFor(() =>
      http.expectOne({ method: 'POST', url: '/api/v1/files' }).flush({ id: fileId }),
    );
    const request = await vi.waitFor(() =>
      http.expectOne({ method: 'POST', url: `/api/v1/grocery/discover/${fileId}` }),
    );
    await harness.fixture.whenStable();
    return request;
  };
  // the panel of the items read, once it shows
  const discovered = () =>
    vi.waitFor(async () => {
      await harness.fixture.whenStable();
      const panel = page().querySelector<HTMLElement>('[data-testid=discovered]');
      expect(panel).not.toBeNull();
      return panel!;
    });
  const discoveredRow = (index: number) =>
    page().querySelector<HTMLElement>(`[data-testid=discovered-${index}]`)!;
  const rowField = (index: number, selector: string) =>
    discoveredRow(index).querySelector<HTMLInputElement & HTMLSelectElement>(selector)!;
  const edit = async (index: number, selector: string, value: string) => {
    const field = rowField(index, selector);
    field.value = value;
    field.dispatchEvent(new Event(field instanceof HTMLSelectElement ? 'change' : 'input'));
    field.dispatchEvent(new Event('blur'));
    await harness.fixture.whenStable();
  };
  const addedItem = () =>
    vi.waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/grocery/items' }));

  it('adds the items the robot reads in a photo once they are corrected, the ones left out not (GROCERY-16)', async () => {
    await load([defaultList], [], true);
    const robot = button(robotLabel);
    expect(robot.title).toBe(robotLabel);
    const chooser = page().querySelector<HTMLInputElement>('[data-testid=discover-input]')!;
    expect(chooser.accept).toBe('image/*');
    expect(chooser.hasAttribute('capture')).toBe(false);

    const request = await discoverPhoto();
    expect(page().querySelector('[data-testid=discover-reading]')?.textContent?.trim()).toBe(
      'Reading the photo…',
    );
    expect(robot.disabled).toBe(true);
    request.flush({
      items: [
        { name: 'Mehl', amount: 250, unit: 'g' },
        { name: 'Slaz' },
        { name: 'Knoblauch', amount: 2, unit: 'Zehe' },
        { name: 'Zucker', amount: null, unit: null },
      ],
    });
    const panel = await discovered();
    expect(robot.disabled).toBe(false);
    expect(page().querySelector('[data-testid=discover-reading]')).toBeNull();
    expect(panel.querySelector('h2')?.textContent).toContain('Items in the photo');
    expect(panel.textContent).toContain('Correct the items and leave out the ones not to buy.');
    expect(document.activeElement).toBe(panel);

    // every item in, with the quantity read; a unit not among the usual ones stays one to choose
    const checkbox = rowField(0, 'input[type=checkbox]');
    expect(checkbox.checked).toBe(true);
    expect(checkbox.getAttribute('aria-label')).toBe('Add Mehl');
    expect(rowField(0, '[id^=discovered-name]').getAttribute('aria-label')).toBe('Name of item 1');
    expect(rowField(0, '[id^=discovered-name]').value).toBe('Mehl');
    expect(rowField(0, '[id^=discovered-amount]').value).toBe('250');
    expect(rowField(0, 'select').value).toBe('g');
    expect(rowField(1, '[id^=discovered-amount]').value).toBe('');
    expect(rowField(1, 'select').value).toBe('');
    expect(rowField(2, 'select').value).toBe('Zehe');
    expect([...rowField(2, 'select').options].map((option) => option.text.trim()).at(-1)).toBe(
      'Zehe',
    );

    // corrected: an empty name and an invalid number say so when they are left
    await edit(1, '[id^=discovered-name]', ' ');
    expect(discoveredRow(1).textContent).toContain('An item needs a name.');
    await edit(0, '[id^=discovered-amount]', '0,2505');
    expect(discoveredRow(0).textContent).toContain(
      'Type a positive number with at most 3 decimal places.',
    );
    // the others can be added, an item left in that is not valid keeps them all from it
    const add = button('Add to the list', panel);
    expect(add.disabled).toBe(false);
    add.click();
    await harness.fixture.whenStable();
    http.expectNone({ method: 'POST', url: '/api/v1/grocery/items' });

    await edit(1, '[id^=discovered-name]', 'Salz');
    await edit(0, '[id^=discovered-amount]', '0,25');
    await edit(0, 'select', 'kg');
    expect(panel.querySelector('.invalid-feedback')).toBeNull();
    // left out
    rowField(3, 'input[type=checkbox]').click();
    await harness.fixture.whenStable();

    // one after the other, without a category: the backend learns it from the name
    button('Add to the list', panel).click();
    const flour = await addedItem();
    expect(flour.request.body).toEqual({ listId: 1, name: 'Mehl', amount: 0.25, unit: 'kg' });
    await harness.fixture.whenStable();
    expect(button('Add to the list', panel).matches(':disabled')).toBe(true);
    flour.flush(item(30, 'Mehl', null));
    const salt = await addedItem();
    expect(salt.request.body).toEqual({ listId: 1, name: 'Salz', amount: null, unit: null });
    salt.flush(item(31, 'Salz', null));
    const garlic = await addedItem();
    expect(garlic.request.body).toEqual({ listId: 1, name: 'Knoblauch', amount: 2, unit: 'Zehe' });
    garlic.flush(item(32, 'Knoblauch', null));
    await vi.waitFor(() =>
      flushItems(1, [item(30, 'Mehl', null), item(31, 'Salz', null), item(32, 'Knoblauch', null)]),
    );
    expect(page().querySelector('[data-testid=discovered]')).toBeNull();
    expect(page().querySelector('[data-testid=item-32]')?.textContent).toContain('Knoblauch');
  });

  it('the items the backend refuses stay to add again, cancel adds none (GROCERY-16)', async () => {
    await load([defaultList], [], true);
    (await discoverPhoto()).flush({ items: [{ name: 'Mehl' }, { name: 'Salz' }] });
    const panel = await discovered();
    button('Add to the list', panel).click();
    (await addedItem()).flush(null, { status: 500, statusText: 'Server Error' });
    (await addedItem()).flush(item(31, 'Salz', null));
    await vi.waitFor(async () => {
      await harness.fixture.whenStable();
      expect(page().querySelector('[data-testid=discovered-1]')).toBeNull();
    });
    expect(rowField(0, '[id^=discovered-name]').value).toBe('Mehl');
    expect(page().querySelector('[data-testid=discovered] [role=alert]')?.textContent).toContain(
      'Not every item could be added.',
    );
    await flushItems(1, [item(31, 'Salz', null)]);

    button('Cancel', page().querySelector('[data-testid=discovered]')!).click();
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=discovered]')).toBeNull();
    expect(document.activeElement?.id).toBe('item-name');
    http.expectNone({ method: 'POST', url: '/api/v1/grocery/items' });
  });

  it('a photo without items says so, one that can not be read says why (GROCERY-16)', async () => {
    await load([defaultList], [], true);
    (await discoverPhoto()).flush({ items: [] });
    const panel = await discovered();
    // only the warning, no card around it
    expect(panel.classList).toContain('alert-warning');
    expect(panel.textContent).toContain('Unable to detect grocery items in the photo.');
    expect(panel.querySelector('h2')).toBeNull();
    expect(button('Add to the list', panel)).toBeUndefined();
    expect(button('Cancel', panel)).toBeUndefined();
    button('OK', panel).click();
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=discovered]')).toBeNull();

    // the reason of the backend, e.g. the AI could not read it
    (await discoverPhoto(22)).flush(
      {
        errors: [{ code: 'grocery.discover_failed', message: 'The AI could not read the photo.' }],
      },
      { status: 502, statusText: 'Bad Gateway' },
    );
    await vi.waitFor(async () => {
      await harness.fixture.whenStable();
      expect(page().querySelector('[role=alert]')?.textContent).toContain(
        'The AI could not read the photo.',
      );
    });
    expect(page().querySelector('[data-testid=discovered]')).toBeNull();
    expect(button(robotLabel).disabled).toBe(false);

    // a photo that can not be stored, without a reason
    const chooser = page().querySelector<HTMLInputElement>('[data-testid=discover-input]')!;
    Object.defineProperty(chooser, 'files', {
      value: [new File(['jpeg'], 'list.jpg', { type: 'image/jpeg' })],
      configurable: true,
    });
    chooser.dispatchEvent(new Event('change'));
    await vi.waitFor(() =>
      http
        .expectOne({ method: 'POST', url: '/api/v1/files' })
        .flush(null, { status: 500, statusText: 'Server Error' }),
    );
    await vi.waitFor(async () => {
      await harness.fixture.whenStable();
      expect(page().querySelector('[role=alert]')?.textContent).toContain(
        'The photo could not be read.',
      );
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

  it('the only own list is renamed and deleted like the others (LIST-1)', async () => {
    await open('/grocery/lists/1/edit');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList, party]));
    http.expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100').flush(pageOf([]));
    await harness.fixture.whenStable();
    expect(input('list-name').readOnly).toBe(false);

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
    expect(button('Delete', menu).title).toBe('Only the owner of a list deletes it.');
    expect(button('Delete', await rowMenu('Weekly')).disabled).toBe(false);

    await deleteFromRow('Weekly');
    http.expectOne({ method: 'DELETE', url: '/api/v1/grocery/lists/3' }).flush(null);
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList, party]));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=list-3]')).toBeNull();

    // the only own list goes too (LIST-1)
    expect(button('Delete', await rowMenu('Default')).disabled).toBe(false);
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
    await flushItems(1, [item(10, 'Milk', null)]);
    expect(page().querySelector('[data-testid=item-10]')?.textContent).toContain('Milk');

    // the stream was away: everything again; the list is gone, all lists show
    changes.next(EVERYTHING);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([party]));
    await flushItems(1, []);
    expect(url()).toBe('/grocery');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([party]));
    vi.useRealTimers();
  });

  it('the lists loaded again do not send a check that can be undone (GROCERY-3, LIST-4)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await load([defaultList], [item(2, 'Milk', 12)]);
    input('item-2').click();
    await harness.fixture.whenStable();

    changes.next(1);
    vi.advanceTimersByTime(CHANGES_DEBOUNCE_MS);
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([{ ...defaultList }]));
    // the items load once, the check still waits
    await flushItems(1, [item(2, 'Milk', 12)]);
    http.expectNone({ method: 'POST' });
    expect(page().querySelector('[role=status]')?.textContent).toContain('Milk bought');
    button('Undo').click();
    await harness.fixture.whenStable();
    expect(page().querySelector('[role=status]')).toBeNull();
    vi.useRealTimers();
  });

  it('an app going into the background sends a check that can be undone at once (GROCERY-3)', async () => {
    await load([defaultList], [item(2, 'Milk', 12)]);
    input('item-2').click();
    await harness.fixture.whenStable();
    try {
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
      http.expectOne({ method: 'POST', url: '/api/v1/grocery/items/2/check' }).flush(null);
      await harness.fixture.whenStable();
      expect(page().querySelector('[data-testid=item-2]')).toBeNull();
      expect(page().querySelector('[role=status]')).toBeNull();
    } finally {
      // the document of jsdom again, the specs that follow may share it
      Reflect.deleteProperty(document, 'visibilityState');
    }
  });

  it('the settings change the icon of a list, no icon shows the first letter', async () => {
    await open('/grocery/lists/2/edit');
    http
      .expectOne('/api/v1/grocery/lists?offset=0&limit=100')
      .flush(pageOf([defaultList, { ...party, mine: true }]));
    http.expectOne('/api/v1/grocery/lists/2/categories?offset=0&limit=100').flush(pageOf([]));
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

  it("a shared list of another user: its name, icon and categories change, sharing and deleting are the owner's", async () => {
    await open('/grocery/lists/2/edit');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList, party]));
    http
      .expectOne('/api/v1/grocery/lists/2/categories?offset=0&limit=100')
      .flush(pageOf(categories));
    await harness.fixture.whenStable();

    // the switch and the delete stay in their place, disabled, and say why (UX-8)
    const shared = page().querySelector<HTMLInputElement>('#list-shared')!;
    expect(shared.checked).toBe(true);
    expect(shared.disabled).toBe(true);
    expect(shared.title).toBe('Only the owner of a list shares or unshares it.');
    expect(page().querySelector('[data-testid=shared-hint]')?.textContent?.trim()).toBe(
      'Only the owner of a list shares or unshares it. Only the owner of a list deletes it.',
    );
    expect(button('Delete list').disabled).toBe(true);
    expect(button('Delete list').title).toBe('Only the owner of a list deletes it.');

    // a category of the list changes as on an own list
    expect(page().querySelector('input[aria-label="Name of Fruit & vegetables"]')).not.toBeNull();
    await type('list-name', 'Garden party');
    button('No icon').click();
    await harness.fixture.whenStable();
    button('Save').click();
    // the shared flag goes back unchanged
    expect(http.expectOne({ method: 'PUT', url: '/api/v1/grocery/lists/2' }).request.body).toEqual({
      name: 'Garden party',
      shared: true,
      icon: null,
    });
    http.match(() => true).forEach((pending) => pending.flush(pageOf([])));
  });

  it('a list its owner unshared or deleted while its settings were open says so on save', async () => {
    await open('/grocery/lists/2/edit');
    http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList, party]));
    http
      .expectOne('/api/v1/grocery/lists/2/categories?offset=0&limit=100')
      .flush(pageOf(categories));
    await harness.fixture.whenStable();

    button('Save').click();
    http
      .expectOne({ method: 'PUT', url: '/api/v1/grocery/lists/2' })
      .flush(null, { status: 404, statusText: 'Not Found' });
    await harness.fixture.whenStable();
    expect(page().querySelector('[role=alert]')?.textContent).toContain(
      'The list is gone, or its owner no longer shares it.',
    );
    http.match(() => true).forEach((pending) => pending.flush(pageOf([])));
  });

  it('the settings lead back where they were opened, all lists or the list', async () => {
    for (const [settings, back] of [
      ['/grocery/lists/1/edit?from=lists', '/grocery'],
      ['/grocery/lists/1/edit', '/grocery/lists/1'],
    ]) {
      await open(settings);
      http.expectOne('/api/v1/grocery/lists?offset=0&limit=100').flush(pageOf([defaultList]));
      http.expectOne('/api/v1/grocery/lists/1/categories?offset=0&limit=100').flush(pageOf([]));
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
