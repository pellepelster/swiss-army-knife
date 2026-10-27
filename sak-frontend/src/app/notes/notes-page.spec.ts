import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotesPage } from './notes-page';

describe('NotesPage', () => {
  let fixture: ComponentFixture<NotesPage>;
  let page: HTMLElement;

  const button = (name: string, root: ParentNode = page) =>
    [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim().startsWith(name) || b.getAttribute('aria-label') === name,
    )!;
  const notes = () => [...page.querySelectorAll<HTMLElement>('app-note-card')];
  const texts = () => notes().map((note) => note.querySelector('textarea')!.value);

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    fixture = TestBed.createComponent(NotesPage);
    page = fixture.nativeElement;
    document.body.appendChild(page);
    await fixture.whenStable();
  });

  afterEach(() => page.remove());

  it('shows the notes as post-its in their colors', () => {
    expect(texts()).toEqual([
      'Prototype the note taking app',
      'Call the plumber about the kitchen sink',
      'Buy milk, bread and coffee',
    ]);
    expect(notes()[0].classList).toContain('note-blue');
  });

  it('adds a note on top, ready to be written on', async () => {
    button('+ New note').click();
    await fixture.whenStable();
    expect(texts()[0]).toBe('');
    expect(notes().length).toBe(4);
    expect(document.activeElement).toBe(notes()[0].querySelector('textarea'));
  });

  it('attaches a label to a note and filters by it', async () => {
    const note = notes()[2];
    const input = note.querySelector<HTMLInputElement>('.note-label-input')!;
    input.value = 'Groceries';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    input.form!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(input.value).toBe('');
    expect(button('#groceries', page.querySelector('.filters')!)).toBeTruthy();

    button('Show the notes labelled groceries').click();
    await fixture.whenStable();
    expect(texts()).toEqual(['Buy milk, bread and coffee']);

    // a note added while filtering gets the label and stays in sight
    button('+ New note').click();
    await fixture.whenStable();
    expect(texts()).toEqual(['', 'Buy milk, bread and coffee']);

    button('All').click();
    await fixture.whenStable();
    expect(notes().length).toBe(4);
  });

  it('removes a label and deletes a note', async () => {
    button('Remove label work').click();
    await fixture.whenStable();
    expect(page.querySelector('.filters')?.textContent).not.toContain('#work');

    button('Delete note 3').click();
    await fixture.whenStable();
    expect(texts()).not.toContain('Prototype the note taking app');
  });

  it('changes the color of a note', async () => {
    const note = notes()[0];
    button('green', note).click();
    await fixture.whenStable();
    expect(note.classList).toContain('note-green');
    expect(button('green', note).getAttribute('aria-checked')).toBe('true');
  });
});
