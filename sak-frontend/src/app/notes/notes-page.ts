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
  host: { class: 'd-block container-xl pb-md-5' },
  template: `
    <header class="toolbar d-flex flex-wrap align-items-center gap-2 py-3 sticky-top bg-body">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/"
        aria-label="All apps"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">Notes</h1>
      <input
        #camera
        class="d-none"
        type="file"
        accept="image/*"
        capture="environment"
        tabindex="-1"
        aria-hidden="true"
        (change)="addPhoto($any($event.target))"
      />
      <button
        type="button"
        class="btn btn-outline-secondary d-none d-md-inline-block"
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
        class="btn btn-outline-secondary d-none d-md-inline-block"
        aria-label="New note with a recording"
        title="New note with a recording"
        [disabled]="store.recordingFor() !== null"
        (click)="record()"
      >
        <i class="fa-solid fa-microphone" aria-hidden="true"></i>
      </button>
      <button
        type="button"
        class="btn btn-primary d-none d-md-inline-block"
        aria-label="New note"
        title="New note"
        (click)="add()"
      >
        <i class="fa-solid fa-plus" aria-hidden="true"></i>
      </button>
    </header>

    <nav class="d-flex gap-2 pb-3 overflow-x-auto" aria-label="Filter by label">
      <button
        type="button"
        class="btn btn-sm flex-shrink-0"
        [class.btn-primary]="filter() === null"
        [class.btn-outline-secondary]="filter() !== null"
        [attr.aria-pressed]="filter() === null"
        (click)="select(null)"
      >
        All
        @if (filter() === null && store.pagination(); as page) {
          <span class="opacity-75">{{ page.total }}</span>
        }
      </button>
      @for (label of store.knownLabels(); track label) {
        <button
          type="button"
          class="btn btn-sm flex-shrink-0"
          [class.btn-primary]="filter() === label"
          [class.btn-outline-secondary]="filter() !== label"
          [attr.aria-pressed]="filter() === label"
          (click)="toggle(label)"
        >
          #{{ label }}
        </button>
      }
    </nav>

    @if (store.error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    <datalist id="known-labels">
      @for (label of store.knownLabels(); track label) {
        <option [value]="label"></option>
      }
    </datalist>

    <main class="row row-cols-1 row-cols-md-2 row-cols-xl-3 g-4">
      @for (note of shown(); track note.id) {
        <div class="col">
          <app-note-card [note]="note" labelsList="known-labels" (labelSelected)="select($event)" />
        </div>
      } @empty {
        <p class="col-12 text-center text-body-secondary py-5">
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

    <div
      class="note-fabs d-md-none position-fixed bottom-0 end-0 m-3 d-flex flex-column align-items-center gap-2"
    >
      <button
        type="button"
        class="note-fab-small btn btn-primary rounded-circle shadow d-inline-flex align-items-center justify-content-center p-0"
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
        class="note-fab-small btn btn-primary rounded-circle shadow d-inline-flex align-items-center justify-content-center p-0"
        aria-label="New note with a recording"
        title="New note with a recording"
        [disabled]="store.recordingFor() !== null"
        (click)="record()"
      >
        <i class="fa-solid fa-microphone" aria-hidden="true"></i>
      </button>
      <button
        type="button"
        class="note-fab btn btn-primary rounded-circle shadow d-inline-flex align-items-center justify-content-center p-0 fs-4"
        aria-label="New note"
        title="New note"
        (click)="add()"
      >
        <i class="fa-solid fa-plus" aria-hidden="true"></i>
      </button>
    </div>

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
