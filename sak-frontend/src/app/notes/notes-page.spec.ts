import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotesPage } from './notes-page';
import { NotesStore } from './notes-store';
import { AudioRecorder } from './audio-recorder';
import { PhotoScaler } from './photo-scaler';
import { noteResponse, pageOf, scaler, FILE_ID } from './notes-testing';
import { FakeRecorder, RECORDED } from './notes-testing';

describe('NotesPage', () => {
  let fixture: ComponentFixture<NotesPage>;
  let http: HttpTestingController;
  let page: HTMLElement;

  const button = (name: string, root: ParentNode = page) =>
    [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim().startsWith(name) || b.getAttribute('aria-label') === name,
    )!;
  const notes = () => [...page.querySelectorAll<HTMLElement>('app-note-card')];
  const texts = () => notes().map((note) => note.querySelector('textarea')!.value);
  const pagination = () => page.querySelector<HTMLElement>('.pagination');

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PhotoScaler, useValue: scaler },
        { provide: AudioRecorder, useClass: FakeRecorder },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    let objectUrls = 0;
    URL.createObjectURL = vi.fn(() => `blob:file-${++objectUrls}`);
    URL.revokeObjectURL = vi.fn();
    fixture = TestBed.createComponent(NotesPage);
    page = fixture.nativeElement;
    document.body.appendChild(page);
    await fixture.whenStable();
    http
      .expectOne('/api/v1/notes?offset=0&limit=50')
      .flush(
        pageOf([
          noteResponse(3, 'Prototype the note taking app', 'blue', ['todo', 'work']),
          noteResponse(2, 'Call the plumber', 'pink', ['home', 'todo']),
          noteResponse(1, 'Buy milk', 'yellow', ['shopping'], [FILE_ID], [8]),
        ]),
      );
    http.expectOne('/api/v1/labels?offset=0&limit=100').flush(pageOf([]));
    await fixture.whenStable();
  });

  // the thumbnails are fetched with the session and shown as object urls
  const flushThumbnails = async () => {
    http
      .match((request) => request.url.endsWith('/thumbnail'))
      .forEach((request) => request.flush(new Blob(['jpeg'], { type: 'image/jpeg' })));
    await fixture.whenStable();
  };

  afterEach(async () => {
    await flushThumbnails();
    http.verify();
    page.remove();
  });

  it('shows the notes of the user as post-its in their colors', () => {
    expect(texts()).toEqual(['Prototype the note taking app', 'Call the plumber', 'Buy milk']);
    expect(notes()[0].classList).toContain('note-blue');
  });

  it('adds a note on top, ready to be written on', async () => {
    button('New note').click();
    http
      .expectOne({ method: 'POST', url: '/api/v1/notes' })
      .flush(noteResponse(4, '', 'yellow', []));
    await fixture.whenStable();

    expect(texts()[0]).toBe('');
    expect(notes().length).toBe(4);
    expect(document.activeElement).toBe(notes()[0].querySelector('textarea'));
  });

  it('attaches a label to a note and filters by it', async () => {
    const input = notes()[2].querySelector<HTMLInputElement>('.note-label-input')!;
    input.value = 'Groceries';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    input.form!.dispatchEvent(new Event('submit'));
    expect(http.expectOne({ method: 'PUT', url: '/api/v1/notes/1' }).request.body.labels).toEqual([
      'shopping',
      'groceries',
    ]);
    await fixture.whenStable();
    expect(input.value).toBe('');

    // the notes of the label are asked for
    button('Show the notes labelled groceries').click();
    await fixture.whenStable();
    http
      .expectOne('/api/v1/notes?offset=0&limit=50&label=groceries')
      .flush(pageOf([noteResponse(1, 'Buy milk', 'yellow', ['shopping', 'groceries'])]));
    http.expectOne('/api/v1/labels?offset=0&limit=100').flush(pageOf([]));
    await fixture.whenStable();
    expect(texts()).toEqual(['Buy milk']);

    // a note added while filtering gets the label and stays in sight
    button('New note').click();
    const created = http.expectOne({ method: 'POST', url: '/api/v1/notes' });
    expect(created.request.body.labels).toEqual(['groceries']);
    created.flush(noteResponse(4, '', 'yellow', ['groceries']));
    await fixture.whenStable();
    expect(texts()).toEqual(['', 'Buy milk']);

    button('All').click();
    await fixture.whenStable();
    http
      .expectOne('/api/v1/notes?offset=0&limit=50')
      .flush(
        pageOf([
          noteResponse(4, '', 'yellow', ['groceries']),
          noteResponse(3, 'Prototype the note taking app', 'blue', ['todo', 'work']),
          noteResponse(2, 'Call the plumber', 'pink', ['home', 'todo']),
          noteResponse(1, 'Buy milk', 'yellow', ['shopping', 'groceries']),
        ]),
      );
    http.expectOne('/api/v1/labels?offset=0&limit=100').flush(pageOf([]));
    await fixture.whenStable();
    expect(notes().length).toBe(4);
    expect(button('All').textContent).toContain('4');
  });

  it('shows the notes a page at a time', async () => {
    const page = (offset: number, ...items: ReturnType<typeof noteResponse>[]) => ({
      items,
      pagination: { offset, limit: 50, total: 51 },
    });
    const answer = async (offset: number, note: ReturnType<typeof noteResponse>) => {
      http.expectOne(`/api/v1/notes?offset=${offset}&limit=50`).flush(page(offset, note));
      http.expectOne('/api/v1/labels?offset=0&limit=100').flush(pageOf([]));
      await fixture.whenStable();
    };
    // the three notes fit on one page
    expect(pagination()).toBeNull();

    fixture.componentInstance['goTo'](50);
    await fixture.whenStable();
    await answer(50, noteResponse(1, 'last', 'yellow', []));
    expect(texts()).toEqual(['last']);
    expect(pagination()?.textContent).toContain('51–51 of 51');
    expect(button('Next page').disabled).toBe(true);

    button('Previous page').click();
    await fixture.whenStable();
    await answer(0, noteResponse(3, 'first', 'blue', []));
    expect(texts()).toEqual(['first']);
    expect(pagination()?.textContent).toContain('1–50 of 51');
    expect(button('Previous page').disabled).toBe(true);
    expect(button('All').textContent).toContain('51');
  });

  it('deletes a note and changes the color of another', async () => {
    button('Delete note 3').click();
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/notes/3' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(texts()).not.toContain('Prototype the note taking app');

    const note = notes()[0];
    button('green', note).click();
    expect(http.expectOne({ method: 'PUT', url: '/api/v1/notes/2' }).request.body.color).toBe(
      'green',
    );
    await fixture.whenStable();
    expect(note.classList).toContain('note-green');
  });

  it('shows the thumbnails of the photos of a note, each opens the whole photo', async () => {
    const photo = notes()[2].querySelector<HTMLImageElement>('.note-photos img')!;
    expect(photo.getAttribute('src') ?? '').not.toMatch(/^blob:/);
    http
      .expectOne(`/api/v1/files/${FILE_ID}/thumbnail`)
      .flush(new Blob(['jpeg'], { type: 'image/jpeg' }));
    await vi.waitFor(
      async () => {
        await fixture.whenStable();
        expect(photo.getAttribute('src')).toMatch(/^blob:/);
      },
      { timeout: 1000 },
    );
    expect(notes()[0].querySelector('.note-photos')).toBeNull();

    // the tab opens right away, the photo follows once it is fetched with the session
    const tab = { location: { href: '' }, close: vi.fn() };
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    // a button, a link to the file would be opened without the session
    expect(page.querySelector('.note-photos a')).toBeNull();
    photo.closest('button')!.click();
    http.expectOne(`/api/v1/files/${FILE_ID}`).flush(new Blob(['whole'], { type: 'image/jpeg' }));
    await vi.waitFor(() => expect(tab.location.href).toMatch(/^blob:/), { timeout: 1000 });
  });

  it('offers no further label once a note has ten', async () => {
    const note = notes()[0];
    for (let index = 1; index <= 8; index++) {
      TestBed.inject(NotesStore).addLabel(3, `label ${index}`);
      http.expectOne({ method: 'PUT', url: '/api/v1/notes/3' });
    }
    await fixture.whenStable();

    expect(note.querySelector('.note-label-input')).toBeNull();
    expect(note.textContent).toContain('A note has at most 10 labels.');
  });

  it('removes a photo only once the removal is confirmed', async () => {
    const note = notes()[2];
    button('Remove photo 1', note).click();
    await fixture.whenStable();
    http.expectNone({ method: 'PUT' });
    expect(note.querySelector('[role=group]')?.textContent).toContain('Remove photo?');

    button('Keep', note).click();
    await fixture.whenStable();
    expect(note.querySelector('[role=group]')).toBeNull();
    expect(note.querySelector('.note-photos img')).not.toBeNull();

    button('Remove photo 1', note).click();
    await fixture.whenStable();
    button('Remove', note).click();
    expect(http.expectOne({ method: 'PUT', url: '/api/v1/notes/1' }).request.body.photos).toEqual(
      [],
    );
    await fixture.whenStable();
    // the recording of the note stays
    expect(note.querySelector('.note-photos a')).toBeNull();
    expect(note.querySelectorAll('.recording').length).toBe(1);
  });

  it('discovers the text in a photo into the note', async () => {
    button('Discover the text in photo 1', notes()[2]).click();
    http
      .expectOne({ method: 'POST', url: `/api/v1/files/${FILE_ID}/discover/text` })
      .flush({ text: 'Total 12.50' });
    await vi.waitFor(() => http.expectOne({ method: 'PUT', url: '/api/v1/notes/1' }), {
      timeout: 1000,
    });
    await fixture.whenStable();

    expect(texts()[2]).toBe('Buy milk\n\nTotal 12.50');
  });

  it('shows the waveform of a recording, plays it and discovers its text with the robot', async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue();
    const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});
    const note = notes()[2];
    const recording = note.querySelector<HTMLButtonElement>('.recording')!;
    await flushThumbnails();
    await vi.waitFor(
      async () => {
        await fixture.whenStable();
        expect(recording.querySelector('img')?.getAttribute('src')).toMatch(/^blob:/);
      },
      { timeout: 1000 },
    );

    button('Play Recording 1', note).click();
    http.expectOne('/api/v1/files/8').flush(new Blob(['RIFF'], { type: 'audio/wav' }));
    await vi.waitFor(() => expect(play).toHaveBeenCalled(), { timeout: 1000 });
    await fixture.whenStable();
    expect(recording.getAttribute('aria-pressed')).toBe('true');
    button('Pause Recording 1', note).click();
    await fixture.whenStable();
    expect(pause).toHaveBeenCalled();

    button('Discover the text in recording 1', note).click();
    http
      .expectOne({ method: 'POST', url: '/api/v1/files/8/discover/text' })
      .flush({ text: 'on the way home' });
    await vi.waitFor(() => http.expectOne({ method: 'PUT', url: '/api/v1/notes/1' }), {
      timeout: 1000,
    });
    await fixture.whenStable();
    expect(texts()[2]).toBe('Buy milk\n\non the way home');
  });

  it('lets a recording the browser did not play be played again', async () => {
    const play = vi
      .spyOn(HTMLMediaElement.prototype, 'play')
      .mockRejectedValueOnce(new DOMException('not allowed', 'NotAllowedError'))
      .mockResolvedValue();
    const note = notes()[2];
    await flushThumbnails();

    button('Play Recording 1', note).click();
    http.expectOne('/api/v1/files/8').flush(new Blob(['RIFF'], { type: 'audio/wav' }));
    await vi.waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalled(), { timeout: 1000 });
    await fixture.whenStable();
    expect(note.querySelector('.recording')?.getAttribute('aria-pressed')).toBe('false');

    button('Play Recording 1', note).click();
    http.expectOne('/api/v1/files/8').flush(new Blob(['RIFF'], { type: 'audio/wav' }));
    await vi.waitFor(() => expect(play).toHaveBeenCalledTimes(2), { timeout: 1000 });
    await fixture.whenStable();
    expect(note.querySelector('.recording')?.getAttribute('aria-pressed')).toBe('true');
  });

  const overlay = () => page.querySelector<HTMLElement>('.recording-overlay');

  it('records for a note with the microphone in its footer until the overlay stops it', async () => {
    const note = notes()[0];
    button('Record for note 3', note).click();
    await fixture.whenStable();
    expect(overlay()?.textContent).toContain('0:00 / 5:00');
    expect(overlay()?.textContent).toContain('note 3');
    expect(document.activeElement).toBe(button('Stop the recording of note 3'));
    expect(button('Record for note 3', note).disabled).toBe(true);

    button('Stop the recording of note 3').click();
    await vi
      .waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/files' }), { timeout: 1000 })
      .then((upload) => upload.flush({ id: 9 }));
    await vi.waitFor(() => http.expectOne({ method: 'PUT', url: '/api/v1/notes/3' }), {
      timeout: 1000,
    });
    await fixture.whenStable();
    expect(overlay()).toBeNull();
    expect(note.querySelectorAll('.recording').length).toBe(1);
  });

  it('records a new note until escape stops it', async () => {
    button('New note with a recording').click();
    await fixture.whenStable();
    expect(overlay()?.textContent).toContain('the new note');

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await vi
      .waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/files' }), { timeout: 1000 })
      .then((upload) => upload.flush({ id: 9 }));
    await vi
      .waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/notes' }), { timeout: 1000 })
      .then((created) => created.flush(noteResponse(4, '', 'yellow', [], [], [9])));
    await fixture.whenStable();
    expect(overlay()).toBeNull();
    expect(notes().length).toBe(4);
  });

  it('stops a recording while the microphone is set up, nothing is kept', async () => {
    const recorder = TestBed.inject(AudioRecorder) as unknown as FakeRecorder;
    recorder.slowStart = true;
    button('New note with a recording').click();
    await fixture.whenStable();
    expect(overlay()?.textContent).toContain('Starting…');

    button('Stop the recording of the new note').click();
    await fixture.whenStable();
    expect(overlay()).toBeNull();
    http.expectNone({ method: 'POST' });
    expect(notes().length).toBe(3);
  });

  it('creates a note from the photo taken in the toolbar', async () => {
    const input = page.querySelector<HTMLInputElement>('.toolbar input[type=file]')!;
    expect(input.getAttribute('capture')).toBe('environment');
    Object.defineProperty(input, 'files', { value: [new File(['original'], 'photo.jpg')] });
    input.dispatchEvent(new Event('change'));

    await vi
      .waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/files' }), { timeout: 1000 })
      .then((upload) => upload.flush({ id: 10, url: '/api/v1/files/10' }));
    await vi
      .waitFor(() => http.expectOne({ method: 'POST', url: '/api/v1/notes' }), { timeout: 1000 })
      .then((created) => created.flush(noteResponse(4, '', 'yellow', [], [10])));
    await fixture.whenStable();

    http.expectOne('/api/v1/files/10/thumbnail').flush(new Blob(['jpeg']));
    await vi.waitFor(
      async () => {
        await fixture.whenStable();
        expect(notes()[0].querySelector('img')?.getAttribute('src')).toMatch(/^blob:/);
      },
      { timeout: 1000 },
    );
    expect(document.activeElement).toBe(notes()[0].querySelector('textarea'));
  });

  it('tells when a change could not be saved', async () => {
    button('green', notes()[0]).click();
    http.expectOne({ method: 'PUT' }).flush(null, { status: 500, statusText: 'Error' });
    await fixture.whenStable();

    expect(page.querySelector('[role=alert]')?.textContent).toContain('could not be saved');
  });
});
