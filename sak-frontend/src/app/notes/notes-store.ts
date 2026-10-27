import { HttpClient } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

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

// what the api answers, the labels are the shared ones
interface NoteResponse {
  id: number;
  text: string;
  color: NoteColor;
  labels: { id: number; name: string }[];
  createdAt: string;
  updatedAt: string;
}

interface Page<T> {
  items: T[];
  pagination: { offset: number; limit: number; total: number };
}

// the most a page has, the notes and labels are asked for in one
const MAX_LIMIT = 500;

// how long typing pauses before the text is saved
export const SAVE_TEXT_AFTER_MS = 600;

// labels are compared without case and surrounding blanks, `Work ` and `work` are one label; the
// backend stores them the same way
export function normalizeLabel(label: string): string {
  return label.trim().replace(/\s+/g, ' ').toLowerCase();
}

// the notes of the signed in user, kept by the backend; changes show at once and are saved in
// the background, the text once typing pauses
@Injectable({ providedIn: 'root' })
export class NotesStore {
  private readonly http = inject(HttpClient);

  private readonly all = signal<readonly Note[]>([]);
  private readonly shared = signal<readonly string[]>([]);
  private readonly pendingText = new Map<number, ReturnType<typeof setTimeout>>();

  // the newest first
  readonly notes = this.all.asReadonly();

  readonly loading = signal(false);

  // what went wrong last, null when everything is saved
  readonly error = signal<string | null>(null);

  // every label of a note, sorted
  readonly labels = computed(() =>
    [...new Set(this.all().flatMap((note) => note.labels))].sort((a, b) => a.localeCompare(b)),
  );

  // the labels of all the apps and of the notes, to suggest them
  readonly knownLabels = computed(() =>
    [...new Set([...this.shared(), ...this.labels()])].sort((a, b) => a.localeCompare(b)),
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => this.pendingText.forEach((timer) => clearTimeout(timer)));
  }

  load(): void {
    this.loading.set(true);
    this.http.get<Page<NoteResponse>>('/api/v1/notes', { params: { limit: MAX_LIMIT } }).subscribe({
      next: (page) => {
        this.all.set(page.items.map(toNote));
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('The notes could not be loaded.');
      },
    });
    this.http
      .get<Page<{ name: string }>>('/api/v1/labels', { params: { limit: MAX_LIMIT } })
      .subscribe({
        next: (page) => this.shared.set(page.items.map((label) => label.name)),
        // the labels are only suggestions
        error: () => this.shared.set([]),
      });
  }

  // null when it could not be created
  async add(
    text = '',
    color: NoteColor = 'yellow',
    labels: readonly string[] = [],
  ): Promise<Note | null> {
    try {
      const response = await firstValueFrom(
        this.http.post<NoteResponse>('/api/v1/notes', { text, color, labels }),
      );
      const note = toNote(response);
      this.all.update((notes) => [note, ...notes]);
      return note;
    } catch {
      this.error.set('The note could not be created.');
      return null;
    }
  }

  setText(id: number, text: string): void {
    if (!this.change(id, (note) => (note.text === text ? note : { ...note, text }))) return;
    clearTimeout(this.pendingText.get(id));
    this.pendingText.set(
      id,
      setTimeout(() => this.save(id), SAVE_TEXT_AFTER_MS),
    );
  }

  setColor(id: number, color: NoteColor): void {
    if (this.change(id, (note) => (note.color === color ? note : { ...note, color })))
      this.save(id);
  }

  // a blank label or one the note has already changes nothing
  addLabel(id: number, label: string): void {
    const normalized = normalizeLabel(label);
    if (!normalized) return;
    const changed = this.change(id, (note) =>
      note.labels.includes(normalized) ? note : { ...note, labels: [...note.labels, normalized] },
    );
    if (changed) this.save(id);
  }

  removeLabel(id: number, label: string): void {
    const changed = this.change(id, (note) =>
      note.labels.includes(label)
        ? { ...note, labels: note.labels.filter((l) => l !== label) }
        : note,
    );
    if (changed) this.save(id);
  }

  // gone at once, back when the backend could not delete it
  remove(id: number): void {
    const notes = this.all();
    clearTimeout(this.pendingText.get(id));
    this.pendingText.delete(id);
    this.all.set(notes.filter((note) => note.id !== id));
    this.http.delete(`/api/v1/notes/${id}`).subscribe({
      error: () => {
        this.all.set(notes);
        this.error.set('The note could not be deleted.');
      },
    });
  }

  // saves the note as it is now; the answer brings the time it was saved, what was changed
  // meanwhile stays and is saved on its own
  private save(id: number): void {
    clearTimeout(this.pendingText.get(id));
    this.pendingText.delete(id);
    const note = this.all().find((n) => n.id === id);
    if (!note) return;
    this.http
      .put<NoteResponse>(`/api/v1/notes/${id}`, {
        text: note.text,
        color: note.color,
        labels: note.labels,
      })
      .subscribe({
        next: (response) => {
          this.error.set(null);
          this.all.update((notes) =>
            notes.map((n) => (n.id === id ? { ...n, updated: new Date(response.updatedAt) } : n)),
          );
        },
        error: () => this.error.set('The note could not be saved.'),
      });
  }

  // false when the note is unknown or nothing changed
  private change(id: number, change: (note: Note) => Note): boolean {
    let changed = false;
    this.all.update((notes) =>
      notes.map((note) => {
        if (note.id !== id) return note;
        const result = change(note);
        changed = result !== note;
        return result;
      }),
    );
    return changed;
  }
}

function toNote(response: NoteResponse): Note {
  return {
    id: response.id,
    text: response.text,
    color: response.color,
    labels: response.labels.map((label) => label.name),
    created: new Date(response.createdAt),
    updated: new Date(response.updatedAt),
  };
}
