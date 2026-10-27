import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AudioRecorder } from './audio-recorder';
import { PhotoScaler } from './photo-scaler';

export const NOTE_COLORS = ['yellow', 'pink', 'blue', 'green', 'orange'] as const;

export type NoteColor = (typeof NOTE_COLORS)[number];

// photos and recordings are the sha-256 of files of the backend's file storage, served at
// /api/v1/files/<sha256>
export interface Note {
  readonly id: number;
  readonly text: string;
  readonly color: NoteColor;
  readonly labels: readonly string[];
  readonly photos: readonly string[];
  readonly recordings: readonly string[];
  readonly created: Date;
  readonly updated: Date;
}

// what the api answers, the labels are the shared ones
interface NoteResponse {
  id: number;
  text: string;
  color: NoteColor;
  labels: { id: number; name: string }[];
  photos: { sha256: string; url: string; thumbnailUrl: string }[];
  recordings: { sha256: string; url: string; thumbnailUrl: string }[];
  createdAt: string;
  updatedAt: string;
}

interface Page<T> {
  items: T[];
  pagination: { offset: number; limit: number; total: number };
}

// the most a page has, the notes and labels are asked for in one
const MAX_LIMIT = 500;

// the most labels a note has, the backend takes no more
export const MAX_LABELS = 10;

// how long typing pauses before the text is saved
export const SAVE_TEXT_AFTER_MS = 600;

// labels are compared without case and surrounding blanks, `Work ` and `work` are one label; the
// backend stores them the same way
export function normalizeLabel(label: string): string {
  return label.trim().replace(/\s+/g, ' ').toLowerCase();
}

// the notes of the signed in user, kept by the backend; changes show at once and are saved in
// the background, the text once typing pauses. Photos are scaled down and uploaded to the file
// storage first, then attached; recordings are uploaded and attached
@Injectable({ providedIn: 'root' })
export class NotesStore {
  private readonly http = inject(HttpClient);
  private readonly scaler = inject(PhotoScaler);
  private readonly recorder = inject(AudioRecorder);

  private readonly all = signal<readonly Note[]>([]);
  private readonly shared = signal<readonly string[]>([]);
  private readonly pendingText = new Map<number, ReturnType<typeof setTimeout>>();

  // the newest first
  readonly notes = this.all.asReadonly();

  readonly loading = signal(false);

  // the notes a photo is being uploaded for
  readonly uploading = signal<ReadonlySet<number>>(new Set());

  // what is being recorded for: a note, a new note (`new`) or nothing
  readonly recordingFor = signal<number | 'new' | null>(null);

  // the photos and recordings whose text is being discovered
  readonly discovering = signal<ReadonlySet<string>>(new Set());

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
    photos: readonly string[] = [],
    recordings: readonly string[] = [],
  ): Promise<Note | null> {
    try {
      const response = await firstValueFrom(
        this.http.post<NoteResponse>('/api/v1/notes', { text, color, labels, photos, recordings }),
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

  // a blank label, one the note has already or one more than MAX_LABELS changes nothing
  addLabel(id: number, label: string): void {
    const normalized = normalizeLabel(label);
    if (!normalized) return;
    const changed = this.change(id, (note) =>
      note.labels.includes(normalized) || note.labels.length >= MAX_LABELS
        ? note
        : { ...note, labels: [...note.labels, normalized] },
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

  // a new note with the photo, null when the photo or the note could not be stored
  async addWithPhoto(photo: Blob, labels: readonly string[] = []): Promise<Note | null> {
    const sha256 = await this.uploadPhoto(photo);
    return sha256 === null ? null : this.add('', 'yellow', labels, [sha256]);
  }

  // attached once it is uploaded
  async addPhoto(id: number, photo: Blob): Promise<void> {
    this.uploading.update((ids) => new Set([...ids, id]));
    try {
      const sha256 = await this.uploadPhoto(photo);
      if (sha256 === null) return;
      const changed = this.change(id, (note) =>
        note.photos.includes(sha256) ? note : { ...note, photos: [...note.photos, sha256] },
      );
      if (changed) this.save(id);
    } finally {
      this.uploading.update((ids) => new Set([...ids].filter((other) => other !== id)));
    }
  }

  // starts recording for the note or for a new note (`new`), a second call stops it; the recording
  // is kept with the note, the robot on it writes it down
  async record(target: number | 'new', labels: readonly string[] = []): Promise<Note | null> {
    if (this.recordingFor() !== null) {
      this.recorder.stop();
      return null;
    }
    this.recordingFor.set(target);
    let recording: Blob;
    try {
      recording = await this.recorder.record();
    } catch {
      this.error.set('The microphone could not be used.');
      return null;
    } finally {
      this.recordingFor.set(null);
    }

    const id = target === 'new' ? null : target;
    if (id !== null) this.uploading.update((ids) => new Set([...ids, id]));
    try {
      const sha256 = await this.uploadFile(recording, 'The recording could not be stored.');
      if (sha256 === null) return null;
      if (id === null) return await this.add('', 'yellow', labels, [], [sha256]);
      const changed = this.change(id, (current) =>
        current.recordings.includes(sha256)
          ? current
          : { ...current, recordings: [...current.recordings, sha256] },
      );
      if (changed) this.save(id);
      return this.all().find((current) => current.id === id) ?? null;
    } finally {
      if (id !== null)
        this.uploading.update((ids) => new Set([...ids].filter((other) => other !== id)));
    }
  }

  // the file stays in the storage, other notes may play it too
  removeRecording(id: number, sha256: string): void {
    const changed = this.change(id, (note) =>
      note.recordings.includes(sha256)
        ? { ...note, recordings: note.recordings.filter((recording) => recording !== sha256) }
        : note,
    );
    if (changed) this.save(id);
  }

  // the text the backend discovers in the photo or the recording is added to the text of the note
  async discoverText(id: number, sha256: string): Promise<void> {
    this.discovering.update((photos) => new Set([...photos, sha256]));
    try {
      const { text } = await firstValueFrom(
        this.http.post<{ text: string }>(`/api/v1/files/${sha256}/discover/text`, null),
      );
      if (!text) {
        this.error.set('There is no text in the photo.');
        return;
      }
      const changed = this.change(id, (note) => ({
        ...note,
        text: note.text.trim() ? `${note.text.trimEnd()}\n\n${text}` : text,
      }));
      if (changed) this.save(id);
    } catch (error: unknown) {
      this.error.set(firstMessage(error) ?? 'The text could not be discovered.');
    } finally {
      this.discovering.update((photos) => new Set([...photos].filter((photo) => photo !== sha256)));
    }
  }

  // the file stays in the storage, other notes may show it too
  removePhoto(id: number, sha256: string): void {
    const changed = this.change(id, (note) =>
      note.photos.includes(sha256)
        ? { ...note, photos: note.photos.filter((photo) => photo !== sha256) }
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
        photos: note.photos,
        recordings: note.recordings,
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

  // the sha-256 of the scaled down photo in the file storage, null when it could not be stored
  private async uploadPhoto(photo: Blob): Promise<string | null> {
    let scaled: Blob;
    try {
      scaled = await this.scaler.scale(photo);
    } catch {
      this.error.set('The photo could not be stored.');
      return null;
    }
    return this.uploadFile(scaled, 'The photo could not be stored.');
  }

  // the sha-256 of the file in the file storage, null when it could not be stored
  private async uploadFile(file: Blob, failure: string): Promise<string | null> {
    try {
      const stored = await firstValueFrom(
        this.http.post<{ sha256: string }>('/api/v1/files', file, {
          headers: { 'Content-Type': file.type || 'application/octet-stream' },
        }),
      );
      return stored.sha256;
    } catch {
      this.error.set(failure);
      return null;
    }
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

// the first message of the api's error body, e.g. that the discovery is not configured
function firstMessage(error: unknown): string | null {
  if (!(error instanceof HttpErrorResponse)) return null;
  const message = (error.error as { errors?: { message?: unknown }[] } | null)?.errors?.[0]
    ?.message;
  return typeof message === 'string' ? message : null;
}

function toNote(response: NoteResponse): Note {
  return {
    id: response.id,
    text: response.text,
    color: response.color,
    labels: response.labels.map((label) => label.name),
    photos: response.photos.map((photo) => photo.sha256),
    recordings: response.recordings.map((recording) => recording.sha256),
    created: new Date(response.createdAt),
    updated: new Date(response.updatedAt),
  };
}
