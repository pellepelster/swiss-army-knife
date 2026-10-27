import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, Subscription } from 'rxjs';
import { AudioRecorder } from './audio-recorder';
import { PhotoScaler } from './photo-scaler';

export const NOTE_COLORS = ['yellow', 'pink', 'blue', 'green', 'orange'] as const;

export type NoteColor = (typeof NOTE_COLORS)[number];

// photos and recordings are the ids of files of the backend's file storage, served with the
// session at /api/v1/files/<id>
export interface Note {
  readonly id: number;
  readonly text: string;
  readonly color: NoteColor;
  readonly labels: readonly string[];
  readonly photos: readonly number[];
  readonly recordings: readonly number[];
  readonly created: Date;
  readonly updated: Date;
}

// what the api answers, the labels are the shared ones
interface NoteResponse {
  id: number;
  text: string;
  color: NoteColor;
  labels: { id: number; name: string }[];
  photos: { id: number; url: string; thumbnailUrl: string }[];
  recordings: { id: number; url: string; thumbnailUrl: string }[];
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

  // the requests saving or deleting a note that are not answered yet, by note
  private readonly inFlight = new Map<number, number>();

  // while the notes are loaded: the notes changed here meanwhile and the ones whose change was
  // not saved yet when the loading started; the backend's answer may not know of these changes,
  // the notes are kept as they are here
  private changedWhileLoading: Set<number> | null = null;

  private loadingNotes?: Subscription;

  // the newest first
  readonly notes = this.all.asReadonly();

  readonly loading = signal(false);

  // the notes a photo is being uploaded for
  readonly uploading = signal<ReadonlySet<number>>(new Set());

  // what is being recorded for: a note, a new note (`new`) or nothing
  readonly recordingFor = signal<number | 'new' | null>(null);

  // the photos and recordings whose text is being discovered
  readonly discovering = signal<ReadonlySet<number>>(new Set());

  // what went wrong last, null when everything is saved; every action clears it when it starts
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
    inject(DestroyRef).onDestroy(() => {
      this.pendingText.forEach((timer) => clearTimeout(timer));
      this.loadingNotes?.unsubscribe();
    });
  }

  // a loading not answered yet is cancelled; the notes loaded take the place of the ones here,
  // except the ones changed here that the backend may not know of yet
  load(): void {
    this.loadingNotes?.unsubscribe();
    this.error.set(null);
    this.loading.set(true);
    this.changedWhileLoading = new Set([...this.pendingText.keys(), ...this.inFlight.keys()]);
    this.loadingNotes = this.http
      .get<Page<NoteResponse>>('/api/v1/notes', { params: { limit: MAX_LIMIT } })
      .subscribe({
        next: (page) => {
          const changed = this.changedWhileLoading ?? new Set<number>();
          this.changedWhileLoading = null;
          this.all.update((notes) =>
            [
              ...page.items.map(toNote).filter((note) => !changed.has(note.id)),
              ...notes.filter((note) => changed.has(note.id)),
            ].sort(newestFirst),
          );
          this.loading.set(false);
        },
        error: () => {
          this.changedWhileLoading = null;
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
    photos: readonly number[] = [],
    recordings: readonly number[] = [],
  ): Promise<Note | null> {
    this.error.set(null);
    try {
      const response = await firstValueFrom(
        this.http.post<NoteResponse>('/api/v1/notes', { text, color, labels, photos, recordings }),
      );
      const note = toNote(response);
      this.changedWhileLoading?.add(note.id);
      // the notes loaded meanwhile may have it already
      this.all.update((notes) => [note, ...notes.filter((other) => other.id !== note.id)]);
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
    this.error.set(null);
    const fileId = await this.uploadPhoto(photo);
    return fileId === null ? null : this.add('', 'yellow', labels, [fileId]);
  }

  // attached once it is uploaded
  async addPhoto(id: number, photo: Blob): Promise<void> {
    this.error.set(null);
    this.uploading.update((ids) => new Set([...ids, id]));
    try {
      const fileId = await this.uploadPhoto(photo);
      if (fileId === null) return;
      const changed = this.change(id, (note) =>
        note.photos.includes(fileId) ? note : { ...note, photos: [...note.photos, fileId] },
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
    this.error.set(null);
    this.recordingFor.set(target);
    let recording: Blob | null;
    try {
      recording = await this.recorder.record();
    } catch {
      this.error.set('The microphone could not be used.');
      return null;
    } finally {
      this.recordingFor.set(null);
    }
    // stopped before it ran, there is nothing to keep
    if (recording === null) return null;

    const id = target === 'new' ? null : target;
    if (id !== null) this.uploading.update((ids) => new Set([...ids, id]));
    try {
      const fileId = await this.uploadFile(recording, 'The recording could not be stored.');
      if (fileId === null) return null;
      if (id === null) return await this.add('', 'yellow', labels, [], [fileId]);
      const changed = this.change(id, (current) =>
        current.recordings.includes(fileId)
          ? current
          : { ...current, recordings: [...current.recordings, fileId] },
      );
      if (changed) this.save(id);
      return this.all().find((current) => current.id === id) ?? null;
    } finally {
      if (id !== null)
        this.uploading.update((ids) => new Set([...ids].filter((other) => other !== id)));
    }
  }

  // ends the recording, record() keeps it; while the microphone is set up, nothing is recorded
  stopRecording(): void {
    this.recorder.stop();
  }

  // the file stays in the storage, other notes may play it too
  removeRecording(id: number, fileId: number): void {
    const changed = this.change(id, (note) =>
      note.recordings.includes(fileId)
        ? { ...note, recordings: note.recordings.filter((recording) => recording !== fileId) }
        : note,
    );
    if (changed) this.save(id);
  }

  // the text the backend discovers in the photo or the recording is added to the text of the note
  async discoverText(id: number, fileId: number): Promise<void> {
    this.error.set(null);
    this.discovering.update((photos) => new Set([...photos, fileId]));
    try {
      const { text } = await firstValueFrom(
        this.http.post<{ text: string }>(`/api/v1/files/${fileId}/discover/text`, null),
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
      this.discovering.update((photos) => new Set([...photos].filter((photo) => photo !== fileId)));
    }
  }

  // the file stays in the storage, other notes may show it too
  removePhoto(id: number, fileId: number): void {
    const changed = this.change(id, (note) =>
      note.photos.includes(fileId)
        ? { ...note, photos: note.photos.filter((photo) => photo !== fileId) }
        : note,
    );
    if (changed) this.save(id);
  }

  // gone at once, back when the backend could not delete it
  remove(id: number): void {
    this.error.set(null);
    const notes = this.all();
    clearTimeout(this.pendingText.get(id));
    this.pendingText.delete(id);
    this.changedWhileLoading?.add(id);
    this.all.set(notes.filter((note) => note.id !== id));
    this.startRequest(id);
    this.http.delete(`/api/v1/notes/${id}`).subscribe({
      error: () => {
        this.finishRequest(id);
        this.all.set(notes);
        this.error.set('The note could not be deleted.');
      },
      complete: () => this.finishRequest(id),
    });
  }

  // saves the note as it is now; the answer brings the time it was saved, what was changed
  // meanwhile stays and is saved on its own
  private save(id: number): void {
    clearTimeout(this.pendingText.get(id));
    this.pendingText.delete(id);
    const note = this.all().find((n) => n.id === id);
    if (!note) return;
    this.startRequest(id);
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
        error: () => {
          this.finishRequest(id);
          this.error.set('The note could not be saved.');
        },
        complete: () => this.finishRequest(id),
      });
  }

  private startRequest(id: number): void {
    this.inFlight.set(id, (this.inFlight.get(id) ?? 0) + 1);
  }

  private finishRequest(id: number): void {
    const count = (this.inFlight.get(id) ?? 0) - 1;
    if (count > 0) this.inFlight.set(id, count);
    else this.inFlight.delete(id);
  }

  // the id of the scaled down photo in the file storage, null when it could not be stored
  private async uploadPhoto(photo: Blob): Promise<number | null> {
    let scaled: Blob;
    try {
      scaled = await this.scaler.scale(photo);
    } catch {
      this.error.set('The photo could not be stored.');
      return null;
    }
    return this.uploadFile(scaled, 'The photo could not be stored.');
  }

  // the id of the file in the file storage, null when it could not be stored
  private async uploadFile(file: Blob, failure: string): Promise<number | null> {
    try {
      const stored = await firstValueFrom(
        this.http.post<{ id: number }>('/api/v1/files', file, {
          headers: { 'Content-Type': file.type || 'application/octet-stream' },
        }),
      );
      return stored.id;
    } catch {
      this.error.set(failure);
      return null;
    }
  }

  // false when the note is unknown or nothing changed; a change is an action, it clears the error
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
    if (changed) {
      this.error.set(null);
      this.changedWhileLoading?.add(id);
    }
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

// as the backend sorts them
function newestFirst(a: Note, b: Note): number {
  return b.created.getTime() - a.created.getTime() || b.id - a.id;
}

function toNote(response: NoteResponse): Note {
  return {
    id: response.id,
    text: response.text,
    color: response.color,
    labels: response.labels.map((label) => label.name),
    photos: response.photos.map((photo) => photo.id),
    recordings: response.recordings.map((recording) => recording.id),
    created: new Date(response.createdAt),
    updated: new Date(response.updatedAt),
  };
}
