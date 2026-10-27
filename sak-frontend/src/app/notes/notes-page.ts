import {
  afterNextRender,
  Component,
  computed,
  effect,
  inject,
  Injector,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { PaginationControl } from '../shared/pagination';
import { NoteCard } from './note-card';
import { Note, NotesStore } from './notes-store';
import { RecordingOverlay } from './recording-overlay';

// the notes as a wall of post-its, a page at a time, filtered by one label at a time
@Component({
  selector: 'app-notes-page',
  imports: [NoteCard, PaginationControl, RecordingOverlay, RouterLink],
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
        aria-label="New note with a recording"
        title="New note with a recording"
        [disabled]="store.recordingFor() !== null"
        (click)="record()"
      >
        <i class="fa-solid fa-microphone" aria-hidden="true"></i>
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
        (click)="select(null)"
      >
        All
        @if (filter() === null && store.pagination(); as page) {
          <span class="count">{{ page.total }}</span>
        }
      </button>
      @for (label of store.knownLabels(); track label) {
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
        <app-note-card [note]="note" labelsList="known-labels" (labelSelected)="select($event)" />
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

    <app-pagination
      label="Pages of the notes"
      [pagination]="store.pagination()"
      (offsetChange)="goTo($event)"
    />

    <app-recording-overlay />
  `,
})
export class NotesPage {
  protected readonly store = inject(NotesStore);
  private readonly injector = inject(Injector);

  protected readonly filter = signal<string | null>(null);

  // where the page shown starts
  protected readonly offset = signal(0);

  protected readonly addingPhoto = signal(false);

  protected readonly shown = computed(() => {
    const label = this.filter();
    const notes = this.store.notes();
    return label === null ? notes : notes.filter((note) => note.labels.includes(label));
  });

  constructor() {
    effect(() => {
      const offset = this.offset();
      const label = this.filter();
      untracked(() => this.store.load(offset, label));
    });
  }

  // the notes of the label, or all of them, from their first page
  protected select(label: string | null): void {
    this.filter.set(label);
    this.offset.set(0);
  }

  protected goTo(offset: number): void {
    this.offset.set(offset);
    document.querySelector('.toolbar')?.scrollIntoView?.();
  }

  // a new note gets the label shown, so it stays in sight; its text is focused right away
  protected async add(): Promise<void> {
    this.offset.set(0);
    const label = this.filter();
    this.focus(await this.store.add('', 'yellow', label === null ? [] : [label]));
  }

  // a new note of the photo taken or chosen, to be written on
  protected async addPhoto(input: HTMLInputElement): Promise<void> {
    const photo = input.files?.[0];
    input.value = '';
    if (!photo) return;
    this.offset.set(0);
    const label = this.filter();
    this.addingPhoto.set(true);
    try {
      this.focus(await this.store.addWithPhoto(photo, label === null ? [] : [label]));
    } finally {
      this.addingPhoto.set(false);
    }
  }

  // records a new note until the overlay stops it; the note gets the label shown
  protected async record(): Promise<void> {
    this.offset.set(0);
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
    this.select(this.filter() === label ? null : label);
  }
}
