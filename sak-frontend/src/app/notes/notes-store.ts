import { computed, Injectable, signal } from '@angular/core';

export const NOTE_COLORS = ['yellow', 'pink', 'blue', 'green', 'orange'] as const;

export type NoteColor = (typeof NOTE_COLORS)[number];

export interface Note {
  readonly id: number;
  readonly text: string;
  readonly color: NoteColor;
  readonly labels: readonly string[];
  readonly created: Date;
  readonly updated: Date;
}

// labels are compared without case and surrounding blanks, `Work ` and `work` are one label
export function normalizeLabel(label: string): string {
  return label.trim().toLowerCase();
}

// the notes, kept in memory only: a reload starts again with the sample notes
@Injectable({ providedIn: 'root' })
export class NotesStore {
  private nextId = 1;
  private readonly now = () => new Date();
  private readonly all = signal<readonly Note[]>([]);

  // the newest first
  readonly notes = this.all.asReadonly();

  // every label of a note, sorted
  readonly labels = computed(() =>
    [...new Set(this.all().flatMap((note) => note.labels))].sort((a, b) => a.localeCompare(b)),
  );

  constructor() {
    this.add('Buy milk, bread and coffee', 'yellow', ['shopping']);
    this.add('Call the plumber about the kitchen sink', 'pink', ['home', 'todo']);
    this.add('Prototype the note taking app', 'blue', ['work', 'todo']);
  }

  add(text = '', color: NoteColor = 'yellow', labels: readonly string[] = []): Note {
    const created = this.now();
    const note: Note = {
      id: this.nextId++,
      text,
      color,
      labels: unique(labels),
      created,
      updated: created,
    };
    this.all.update((notes) => [note, ...notes]);
    return note;
  }

  setText(id: number, text: string): void {
    this.change(id, (note) => (note.text === text ? note : { ...note, text }));
  }

  setColor(id: number, color: NoteColor): void {
    this.change(id, (note) => (note.color === color ? note : { ...note, color }));
  }

  // a blank label or one the note has already changes nothing
  addLabel(id: number, label: string): void {
    const normalized = normalizeLabel(label);
    if (!normalized) return;
    this.change(id, (note) =>
      note.labels.includes(normalized) ? note : { ...note, labels: [...note.labels, normalized] },
    );
  }

  removeLabel(id: number, label: string): void {
    this.change(id, (note) =>
      note.labels.includes(label)
        ? { ...note, labels: note.labels.filter((l) => l !== label) }
        : note,
    );
  }

  remove(id: number): void {
    this.all.update((notes) => notes.filter((note) => note.id !== id));
  }

  // updated is only touched when the note really changed
  private change(id: number, change: (note: Note) => Note): void {
    this.all.update((notes) =>
      notes.map((note) => {
        if (note.id !== id) return note;
        const changed = change(note);
        return changed === note ? note : { ...changed, updated: this.now() };
      }),
    );
  }
}

function unique(labels: readonly string[]): string[] {
  return [...new Set(labels.map(normalizeLabel).filter((label) => label !== ''))];
}
