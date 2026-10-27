import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NotesStore, SAVE_TEXT_AFTER_MS } from './notes-store';
import { AudioRecorder } from './audio-recorder';
import { PhotoScaler } from './photo-scaler';
import { noteResponse, pageOf, scaler, SCALED, FILE_ID } from './notes-testing';
import { FakeRecorder, RECORDED } from './notes-testing';

describe('NotesStore', () => {
  let http: HttpTestingController;
  let store: NotesStore;

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PhotoScaler, useValue: scaler },
        { provide: AudioRecorder, useClass: FakeRecorder },
      ],
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
    expect(request.request.body).toEqual({
      text: '',
      color: 'green',
      labels: ['ideas'],
      photos: [],
      recordings: [],
    });
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
      photos: [],
      recordings: [],
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

  it('uploads a scaled photo to the file storage and attaches it to the note', async () => {
    const added = store.addPhoto(1, new Blob(['original']));
    await vi
      .waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/files' }), { timeout: 1000 })
      .then((upload) => {
        expect(upload.request.body).toBe(SCALED);
        expect(upload.request.headers.get('Content-Type')).toBe('image/jpeg');
        expect(store.uploading().has(1)).toBe(true);
        upload.flush({ id: FILE_ID, url: `/api/v1/files/${FILE_ID}` });
      });
    await added;

    expect(store.notes()[1].photos).toEqual([FILE_ID]);
    expect(store.uploading().size).toBe(0);
    expect(http.expectOne({ method: 'PUT', url: '/api/v1/notes/1' }).request.body.photos).toEqual([
      FILE_ID,
    ]);

    store.removePhoto(1, FILE_ID);
    expect(http.expectOne({ method: 'PUT', url: '/api/v1/notes/1' }).request.body.photos).toEqual(
      [],
    );
  });

  it('creates a note from a photo', async () => {
    const added = store.addWithPhoto(new Blob(['original']), ['holidays']);
    await vi
      .waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/files' }), { timeout: 1000 })
      .then((upload) => upload.flush({ id: FILE_ID, url: `/api/v1/files/${FILE_ID}` }));
    await vi
      .waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/notes' }), { timeout: 1000 })
      .then((created) => {
        expect(created.request.body).toEqual({
          text: '',
          color: 'yellow',
          labels: ['holidays'],
          photos: [FILE_ID],
          recordings: [],
        });
        created.flush(noteResponse(3, '', 'yellow', ['holidays'], [FILE_ID]));
      });

    expect((await added)?.photos).toEqual([FILE_ID]);
  });

  it('tells when a photo could not be stored', async () => {
    const added = store.addPhoto(1, new Blob(['original']));
    await vi
      .waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/files' }), { timeout: 1000 })
      .then((upload) =>
        upload.flush({ errors: [] }, { status: 413, statusText: 'Payload Too Large' }),
      );
    await added;

    expect(store.error()).toBe('The photo could not be stored.');
    expect(store.notes()[1].photos).toEqual([]);
  });

  it('attaches at most ten labels to a note', () => {
    for (let index = 2; index <= 10; index++) {
      store.addLabel(1, `label ${index}`);
      http.expectOne({ method: 'PUT', url: '/api/v1/notes/1' });
    }
    expect(store.notes()[1].labels.length).toBe(10);

    store.addLabel(1, 'label 11');
    http.expectNone({ method: 'PUT' });
    expect(store.notes()[1].labels).not.toContain('label 11');
  });

  it('adds the text discovered in a photo to the text of the note', async () => {
    const discovered = store.discoverText(1, FILE_ID);
    expect(store.discovering().has(FILE_ID)).toBe(true);
    http
      .expectOne({ method: 'POST', url: `/api/v1/files/${FILE_ID}/discover/text` })
      .flush({ text: 'Total 12.50' });
    await discovered;

    expect(store.notes()[1].text).toBe('milk\n\nTotal 12.50');
    expect(store.discovering().size).toBe(0);
    expect(http.expectOne({ method: 'PUT', url: '/api/v1/notes/1' }).request.body.text).toBe(
      'milk\n\nTotal 12.50',
    );
  });

  it('tells when a photo has no text or its text can not be discovered', async () => {
    let discovered = store.discoverText(1, FILE_ID);
    http.expectOne({ method: 'POST' }).flush({ text: '' });
    await discovered;
    expect(store.error()).toBe('There is no text in the photo.');
    expect(store.notes()[1].text).toBe('milk');

    discovered = store.discoverText(1, FILE_ID);
    http
      .expectOne({ method: 'POST' })
      .flush(
        { errors: [{ message: 'Text discovery is not configured.' }] },
        { status: 503, statusText: 'Service Unavailable' },
      );
    await discovered;
    expect(store.error()).toBe('Text discovery is not configured.');
    http.expectNone({ method: 'PUT' });
  });

  it('records for a note until it is stopped, keeps the recording and writes nothing down', async () => {
    const recorded = store.record(1);
    expect(store.recordingFor()).toBe(1);
    // a second tap stops it
    void store.record(1);
    await vi
      .waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/files' }), { timeout: 1000 })
      .then((upload) => {
        expect(upload.request.body).toBe(RECORDED);
        expect(upload.request.headers.get('Content-Type')).toBe('audio/wav');
        upload.flush({ id: FILE_ID });
      });
    await recorded;

    expect(store.recordingFor()).toBeNull();
    expect(store.notes()[1].recordings).toEqual([FILE_ID]);
    expect(
      http.expectOne({ method: 'PUT', url: '/api/v1/notes/1' }).request.body.recordings,
    ).toEqual([FILE_ID]);
    http.expectNone(`/api/v1/files/${FILE_ID}/discover/text`);

    store.removeRecording(1, FILE_ID);
    expect(http.expectOne({ method: 'PUT' }).request.body.recordings).toEqual([]);
  });

  it('records a new note', async () => {
    const recorded = store.record('new', ['ideas']);
    void store.record('new');
    await vi
      .waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/files' }), { timeout: 1000 })
      .then((upload) => upload.flush({ id: FILE_ID }));
    await vi
      .waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/notes' }), { timeout: 1000 })
      .then((created) => {
        expect(created.request.body.recordings).toEqual([FILE_ID]);
        expect(created.request.body.labels).toEqual(['ideas']);
        created.flush(noteResponse(3, '', 'yellow', ['ideas'], [], [FILE_ID]));
      });
    expect((await recorded)?.recordings).toEqual([FILE_ID]);
  });

  it('records nothing when it is stopped while the microphone is set up', async () => {
    const recorder = TestBed.inject(AudioRecorder) as unknown as FakeRecorder;
    recorder.slowStart = true;
    const recorded = store.record('new');
    store.stopRecording();

    expect(await recorded).toBeNull();
    expect(store.recordingFor()).toBeNull();
    expect(store.error()).toBeNull();
    http.expectNone({ method: 'POST' });
  });

  it('clears what went wrong once the next action starts', async () => {
    store.load();
    http.expectOne('/api/v1/notes?limit=500').flush(null, { status: 500, statusText: 'Error' });
    http.expectOne('/api/v1/labels?limit=500').flush(pageOf([]));
    expect(store.error()).toBe('The notes could not be loaded.');

    store.setColor(1, 'blue');
    expect(store.error()).toBeNull();
    http
      .expectOne({ method: 'PUT', url: '/api/v1/notes/1' })
      .flush(null, { status: 500, statusText: 'Error' });
    expect(store.error()).toBe('The note could not be saved.');

    const added = store.add();
    expect(store.error()).toBeNull();
    http
      .expectOne({ method: 'POST', url: '/api/v1/notes' })
      .flush(noteResponse(3, '', 'yellow', []));
    await added;
    expect(store.error()).toBeNull();
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

  describe('when the notes are loaded again', () => {
    // the notes the backend answers, the labels are flushed too
    const answer = (...notes: ReturnType<typeof noteResponse>[]) => {
      http.expectOne('/api/v1/notes?limit=500').flush(pageOf(notes));
      http.expectOne('/api/v1/labels?limit=500').flush(pageOf([]));
    };

    it('takes the notes of the backend, the ones changed elsewhere and the new ones', () => {
      store.load();
      answer(
        noteResponse(3, 'tickets', 'blue', []),
        noteResponse(1, 'milk and eggs', 'yellow', []),
      );

      expect(store.notes().map((note) => [note.id, note.text])).toEqual([
        [3, 'tickets'],
        [1, 'milk and eggs'],
      ]);
    });

    it('keeps a note changed while they are loaded', () => {
      store.load();
      store.setColor(1, 'blue');
      http
        .expectOne({ method: 'PUT', url: '/api/v1/notes/1' })
        .flush(noteResponse(1, 'milk', 'blue', ['shopping']));
      answer(
        noteResponse(2, 'plumber', 'pink', ['home', 'todo']),
        noteResponse(1, 'milk', 'yellow', ['shopping']),
      );

      expect(store.notes().map((note) => [note.id, note.color])).toEqual([
        [2, 'pink'],
        [1, 'blue'],
      ]);
    });

    it('keeps a change whose saving is not answered yet when they are loaded', () => {
      store.setColor(1, 'blue');
      store.load();
      answer(
        noteResponse(2, 'plumber', 'pink', ['home', 'todo']),
        noteResponse(1, 'milk', 'yellow', ['shopping']),
      );
      http
        .expectOne({ method: 'PUT', url: '/api/v1/notes/1' })
        .flush(noteResponse(1, 'milk', 'blue', ['shopping']));

      expect(store.notes()[1].color).toBe('blue');
    });

    it('keeps the text typed and not saved yet, and saves it once typing pauses', async () => {
      store.setText(1, 'milk and bread');
      store.load();
      answer(
        noteResponse(2, 'plumber', 'pink', ['home', 'todo']),
        noteResponse(1, 'milk', 'yellow', ['shopping']),
      );
      expect(store.notes()[1].text).toBe('milk and bread');

      await vi.advanceTimersByTimeAsync(SAVE_TEXT_AFTER_MS);
      const saved = http.expectOne({ method: 'PUT', url: '/api/v1/notes/1' });
      expect(saved.request.body.text).toBe('milk and bread');
      saved.flush(noteResponse(1, 'milk and bread', 'yellow', ['shopping']));
    });

    it('keeps a note deleted and one created while they are loaded, once each', async () => {
      store.load();
      store.remove(2);
      const added = store.add('tickets', 'blue');
      http
        .expectOne({ method: 'POST', url: '/api/v1/notes' })
        .flush(noteResponse(3, 'tickets', 'blue', []));
      await added;
      answer(
        noteResponse(3, 'tickets', 'blue', []),
        noteResponse(2, 'plumber', 'pink', ['home', 'todo']),
        noteResponse(1, 'milk', 'yellow', ['shopping']),
      );
      http.expectOne({ method: 'DELETE', url: '/api/v1/notes/2' }).flush(null);

      expect(store.notes().map((note) => note.id)).toEqual([3, 1]);
    });

    it('cancels a loading not answered yet', () => {
      store.load();
      store.load();
      const [first, second] = http.match('/api/v1/notes?limit=500');
      expect(first.cancelled).toBe(true);
      second.flush(pageOf([noteResponse(1, 'milk', 'yellow', ['shopping'])]));
      http.match('/api/v1/labels?limit=500').forEach((labels) => labels.flush(pageOf([])));

      expect(store.notes().map((note) => note.id)).toEqual([1]);
    });
  });
});
