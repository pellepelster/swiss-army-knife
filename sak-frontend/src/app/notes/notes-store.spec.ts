import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NotesStore, SAVE_TEXT_AFTER_MS } from './notes-store';

const CREATED = '2026-10-27T08:00:00Z';

export const noteResponse = (id: number, text: string, color: string, labels: string[]) => ({
  id,
  text,
  color,
  labels: labels.map((name, index) => ({ id: index + 1, name })),
  createdAt: CREATED,
  updatedAt: CREATED,
});

// what a list endpoint answers with the items
export const pageOf = <T>(items: T[]) => ({
  items,
  pagination: { offset: 0, limit: 500, total: items.length },
});

describe('NotesStore', () => {
  let http: HttpTestingController;
  let store: NotesStore;

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(NotesStore);
    store.load();
    http
      .expectOne('/api/v1/notes?limit=500')
      .flush(
        pageOf([
          noteResponse(2, 'plumber', 'pink', ['home', 'todo']),
          noteResponse(1, 'milk', 'yellow', ['shopping']),
        ]),
      );
    http.expectOne('/api/v1/labels?limit=500').flush(pageOf([{ name: 'ideas' }, { name: 'home' }]));
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('loads the notes of the user and suggests the labels of all apps', () => {
    expect(store.notes().map((note) => note.text)).toEqual(['plumber', 'milk']);
    expect(store.labels()).toEqual(['home', 'shopping', 'todo']);
    expect(store.knownLabels()).toEqual(['home', 'ideas', 'shopping', 'todo']);
  });

  it('creates notes on top', async () => {
    const added = store.add('', 'green', ['ideas']);
    const request = http.expectOne({ method: 'POST', url: '/api/v1/notes' });
    expect(request.request.body).toEqual({ text: '', color: 'green', labels: ['ideas'] });
    request.flush(noteResponse(3, '', 'green', ['ideas']));

    expect((await added)?.id).toBe(3);
    expect(store.notes()[0].id).toBe(3);
  });

  it('saves the text once typing pauses, everything else at once', async () => {
    store.setText(1, 'm');
    store.setText(1, 'milk and bread');
    expect(store.notes()[1].text).toBe('milk and bread');
    http.expectNone({ method: 'PUT' });

    await vi.advanceTimersByTimeAsync(SAVE_TEXT_AFTER_MS);
    const text = http.expectOne({ method: 'PUT', url: '/api/v1/notes/1' });
    expect(text.request.body).toEqual({
      text: 'milk and bread',
      color: 'yellow',
      labels: ['shopping'],
    });
    text.flush({
      ...noteResponse(1, 'milk and bread', 'yellow', ['shopping']),
      updatedAt: '2026-10-27T09:00:00Z',
    });
    expect(store.notes()[1].updated).toEqual(new Date('2026-10-27T09:00:00Z'));

    store.addLabel(1, ' Weekend ');
    store.addLabel(1, 'weekend');
    http
      .expectOne({ method: 'PUT', url: '/api/v1/notes/1' })
      .flush(noteResponse(1, 'milk and bread', 'yellow', ['shopping', 'weekend']));
    store.setColor(1, 'blue');
    expect(http.expectOne({ method: 'PUT' }).request.body.color).toBe('blue');
    store.removeLabel(1, 'weekend');
    expect(http.expectOne({ method: 'PUT' }).request.body.labels).toEqual(['shopping']);
  });

  it('deletes notes at once and brings them back when the backend can not', () => {
    store.remove(2);
    expect(store.notes().map((note) => note.id)).toEqual([1]);
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/notes/2' })
      .flush(null, { status: 500, statusText: 'Error' });

    expect(store.notes().map((note) => note.id)).toEqual([2, 1]);
    expect(store.error()).toBe('The note could not be deleted.');
  });
});
