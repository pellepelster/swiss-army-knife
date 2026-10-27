import {
  afterNextRender,
  Component,
  computed,
  inject,
  Injector,
  OnInit,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NoteCard } from './note-card';
import { AudioRecorder } from './audio-recorder';
import { Note, NotesStore } from './notes-store';

// the notes as a wall of post-its, filtered by one label at a time
@Component({
  selector: 'app-notes-page',
  imports: [NoteCard, RouterLink],
  styleUrl: './notes-page.scss',
  template: `
    <header class="toolbar">
      <a class="back" routerLink="/" aria-label="All apps"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="title">Notes</h1>
      <input
        #camera
        class="photo-input"
        type="file"
        accept="image/*"
        capture="environment"
        tabindex="-1"
        aria-hidden="true"
        (change)="addPhoto($any($event.target))"
      />
      <button
        type="button"
        class="add add-photo"
        aria-label="New note with a photo"
        title="New note with a photo"
        [disabled]="addingPhoto()"
        (click)="camera.click()"
      >
        @if (addingPhoto()) {
          <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
        } @else {
          <i class="fa-solid fa-camera" aria-hidden="true"></i>
        }
      </button>
      <button
        type="button"
        class="add add-photo"
        [class.recording-now]="store.recordingFor() === 'new'"
        [attr.aria-label]="
          store.recordingFor() === 'new'
            ? 'Stop the recording of the new note'
            : 'New note with a recording'
        "
        [title]="store.recordingFor() === 'new' ? 'Stop recording' : 'New note with a recording'"
        [disabled]="store.recordingFor() !== null && store.recordingFor() !== 'new'"
        (click)="record()"
      >
        @if (store.recordingFor() === 'new') {
          <i class="fa-solid fa-stop" aria-hidden="true"></i>
          <span class="recording-seconds">{{ recorder.seconds() ?? 0 }}s</span>
        } @else {
          <i class="fa-solid fa-microphone" aria-hidden="true"></i>
        }
      </button>
      <button type="button" class="add" (click)="add()">
        <i class="fa-solid fa-plus" aria-hidden="true"></i> New note
      </button>
    </header>

    <nav class="filters" aria-label="Filter by label">
      <button
        type="button"
        class="filter"
        [attr.aria-pressed]="filter() === null"
        (click)="filter.set(null)"
      >
        All <span class="count">{{ store.notes().length }}</span>
      </button>
      @for (label of store.labels(); track label) {
        <button
          type="button"
          class="filter"
          [attr.aria-pressed]="filter() === label"
          (click)="toggle(label)"
        >
          #{{ label }}
        </button>
      }
    </nav>

    @if (store.error(); as error) {
      <p class="error" role="alert">{{ error }}</p>
    }

    <datalist id="known-labels">
      @for (label of store.knownLabels(); track label) {
        <option [value]="label"></option>
      }
    </datalist>

    <main class="wall">
      @for (note of shown(); track note.id) {
        <app-note-card
          [note]="note"
          labelsList="known-labels"
          (labelSelected)="filter.set($event)"
        />
      } @empty {
        <p class="empty">
          @if (store.loading()) {
            Loading…
          } @else if (filter(); as label) {
            No note is labelled #{{ label }}.
          } @else {
            No notes yet, add one.
          }
        </p>
      }
    </main>
  `,
})
export class NotesPage implements OnInit {
  protected readonly store = inject(NotesStore);
  private readonly injector = inject(Injector);

  protected readonly filter = signal<string | null>(null);

  protected readonly addingPhoto = signal(false);

  protected readonly recorder = inject(AudioRecorder);

  protected readonly shown = computed(() => {
    const label = this.filter();
    const notes = this.store.notes();
    return label === null ? notes : notes.filter((note) => note.labels.includes(label));
  });

  ngOnInit(): void {
    this.store.load();
  }

  // a new note gets the label shown, so it stays in sight; its text is focused right away
  protected async add(): Promise<void> {
    const label = this.filter();
    this.focus(await this.store.add('', 'yellow', label === null ? [] : [label]));
  }

  // a new note of the photo taken or chosen, to be written on
  protected async addPhoto(input: HTMLInputElement): Promise<void> {
    const photo = input.files?.[0];
    input.value = '';
    if (!photo) return;
    const label = this.filter();
    this.addingPhoto.set(true);
    try {
      this.focus(await this.store.addWithPhoto(photo, label === null ? [] : [label]));
    } finally {
      this.addingPhoto.set(false);
    }
  }

  // records a new note, a second tap stops the recording; the note gets the label shown
  protected async record(): Promise<void> {
    const label = this.filter();
    this.focus(await this.store.record('new', label === null ? [] : [label]));
  }

  private focus(note: Note | null): void {
    if (!note) return;
    afterNextRender(
      () =>
        document
          .querySelector<HTMLTextAreaElement>(`[data-testid="note-${note.id}"] textarea`)
          ?.focus(),
      { injector: this.injector },
    );
  }

  protected toggle(label: string): void {
    this.filter.update((current) => (current === label ? null : label));
  }
}
