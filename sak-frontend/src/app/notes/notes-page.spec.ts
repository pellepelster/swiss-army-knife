import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotesPage } from './notes-page';
import { noteResponse, pageOf } from './notes-store.spec';

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

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NotesPage);
    page = fixture.nativeElement;
    document.body.appendChild(page);
    await fixture.whenStable();
    http
      .expectOne('/api/v1/notes?limit=500')
      .flush(
        pageOf([
          noteResponse(3, 'Prototype the note taking app', 'blue', ['todo', 'work']),
          noteResponse(2, 'Call the plumber', 'pink', ['home', 'todo']),
          noteResponse(1, 'Buy milk', 'yellow', ['shopping']),
        ]),
      );
    http.expectOne('/api/v1/labels?limit=500').flush(pageOf([]));
    await fixture.whenStable();
  });

  afterEach(() => {
    http.verify();
    page.remove();
  });

  it('shows the notes of the user as post-its in their colors', () => {
    expect(texts()).toEqual(['Prototype the note taking app', 'Call the plumber', 'Buy milk']);
    expect(notes()[0].classList).toContain('note-blue');
  });

  it('adds a note on top, ready to be written on', async () => {
    button('+ New note').click();
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

    button('Show the notes labelled groceries').click();
    await fixture.whenStable();
    expect(texts()).toEqual(['Buy milk']);

    // a note added while filtering gets the label and stays in sight
    button('+ New note').click();
    const created = http.expectOne({ method: 'POST', url: '/api/v1/notes' });
    expect(created.request.body.labels).toEqual(['groceries']);
    created.flush(noteResponse(4, '', 'yellow', ['groceries']));
    await fixture.whenStable();
    expect(texts()).toEqual(['', 'Buy milk']);

    button('All').click();
    await fixture.whenStable();
    expect(notes().length).toBe(4);
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

  it('tells when a change could not be saved', async () => {
    button('green', notes()[0]).click();
    http.expectOne({ method: 'PUT' }).flush(null, { status: 500, statusText: 'Error' });
    await fixture.whenStable();

    expect(page.querySelector('[role=alert]')?.textContent).toContain('could not be saved');
  });
});
