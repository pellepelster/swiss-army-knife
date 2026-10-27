import { TestBed } from '@angular/core/testing';
import { NotesStore } from './notes-store';

describe('NotesStore', () => {
  let store: NotesStore;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-27T08:00:00Z'));
    store = TestBed.inject(NotesStore);
  });

  afterEach(() => vi.useRealTimers());

  it('starts with the sample notes and their sorted labels', () => {
    expect(store.notes().length).toBe(3);
    expect(store.labels()).toEqual(['home', 'shopping', 'todo', 'work']);
  });

  it('adds new notes first, with their labels normalized and unique', () => {
    const note = store.add('hello', 'green', [' Work', 'work', '']);
    expect(store.notes()[0]).toBe(note);
    expect(note.labels).toEqual(['work']);
    expect(note.updated).toEqual(note.created);
  });

  it('changes the text and the color and tells when', () => {
    const { id } = store.add();
    vi.setSystemTime(new Date('2026-10-27T09:00:00Z'));
    store.setText(id, 'changed');
    store.setColor(id, 'pink');

    const note = store.notes()[0];
    expect(note.text).toBe('changed');
    expect(note.color).toBe('pink');
    expect(note.updated).toEqual(new Date('2026-10-27T09:00:00Z'));
    expect(note.created).toEqual(new Date('2026-10-27T08:00:00Z'));
  });

  it('leaves a note untouched when nothing changes', () => {
    const note = store.add('same');
    vi.setSystemTime(new Date('2026-10-27T09:00:00Z'));
    store.setText(note.id, 'same');
    store.addLabel(note.id, '   ');
    expect(store.notes()[0]).toBe(note);
  });

  it('attaches and removes labels', () => {
    const { id } = store.add();
    store.addLabel(id, ' Ideas ');
    store.addLabel(id, 'ideas');
    expect(store.notes()[0].labels).toEqual(['ideas']);
    expect(store.labels()).toContain('ideas');

    store.removeLabel(id, 'ideas');
    expect(store.notes()[0].labels).toEqual([]);
    expect(store.labels()).not.toContain('ideas');
  });

  it('deletes notes', () => {
    const { id } = store.add();
    store.remove(id);
    expect(store.notes().some((note) => note.id === id)).toBe(false);
  });
});
