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
import { I18n, TranslatePipe } from '../i18n/i18n';
import { PaginationControl } from '../shared/pagination';
import { NoteCard } from './note-card';
import { UndoToast } from '../shared/undo-toast';
import { Note, NotesStore, Removal } from './notes-store';
import { RecordingOverlay } from './recording-overlay';
import { PageHeader } from '../shared/page-header';

// the notes as a wall of post-its, a page at a time, filtered by one label at a time
@Component({
  selector: 'app-notes-page',
  imports: [PageHeader, NoteCard, PaginationControl, RecordingOverlay, TranslatePipe, UndoToast],
  host: { class: 'd-block container-xl pb-5' },
  template: `
    <header
      appPageHeader
      class="toolbar sticky-top bg-body"
      back="/"
      [backLabel]="'shared.all_apps' | t"
    >
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'app.notes' | t }}</h1>
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
      <!-- the actions of the page, the main create action rightmost (UX-2) -->
      <div class="btn-group" role="group" [attr.aria-label]="'notes.page.create_notes' | t">
        <button
          type="button"
          class="btn btn-outline-secondary btn-field-primary"
          [attr.aria-label]="'notes.page.create_with_photo' | t"
          [title]="'notes.page.create_with_photo' | t"
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
          class="btn btn-outline-secondary btn-field-primary"
          [attr.aria-label]="'notes.page.create_with_recording' | t"
          [title]="'notes.page.create_with_recording' | t"
          [disabled]="store.recordingFor() !== null"
          (click)="record()"
        >
          <i class="fa-solid fa-microphone" aria-hidden="true"></i>
        </button>
        <button
          type="button"
          class="btn btn-primary"
          [attr.aria-label]="'notes.page.create_note' | t"
          [title]="'notes.page.create_note' | t"
          (click)="add()"
        >
          <i class="sak-icon-plus" aria-hidden="true"></i>
        </button>
      </div>
    </header>

    <!-- the error of creating a note, right under the buttons that create one (UX-15) -->
    @if (store.errorAt('new'); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    <nav class="d-flex gap-2 pb-3 overflow-x-auto" [attr.aria-label]="'notes.page.filter' | t">
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary flex-shrink-0"
        [class.active]="filter() === null"
        [attr.aria-pressed]="filter() === null"
        (click)="select(null)"
      >
        {{ 'notes.page.all' | t }}
        @if (filter() === null && store.pagination(); as page) {
          <span class="opacity-75">{{ page.total }}</span>
        }
      </button>
      @for (label of chips(); track label) {
        <button
          type="button"
          class="btn btn-sm btn-outline-secondary flex-shrink-0"
          [class.active]="filter() === label"
          [attr.aria-pressed]="filter() === label"
          (click)="toggle(label)"
        >
          #{{ label }}
        </button>
      }
    </nav>

    <!-- the notes could not be loaded, or what was done to a note that is not shown went wrong -->
    @if (store.errorAt('page') ?? strayError(); as error) {
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
        <!-- the whole width of the row, which gives each column half or a third of it -->
        <p class="col w-100 text-center text-body-secondary fs-4 py-5">
          @if (store.loading()) {
            {{ 'shared.loading' | t }}
          } @else if (filter(); as label) {
            {{ 'notes.page.none_labelled' | t: { label } }}
          } @else {
            {{ 'notes.page.empty' | t }}
          }
        </p>
      }
    </main>

    <app-pagination
      [label]="'notes.page.pages' | t"
      [pagination]="store.pagination()"
      (offsetChange)="goTo($event)"
    />

    @for (removal of removed(); track removal) {
      <app-undo-toast [text]="removedText[removal.kind]" (undo)="store.undoRemove()" />
    }

    <app-recording-overlay />
  `,
})
export class NotesPage {
  protected readonly store = inject(NotesStore);
  private readonly injector = inject(Injector);

  protected readonly filter = signal<string | null>(null);

  // the labels the notes filter by: the ones a note has, and the active filter while it is on,
  // also when no note has it any more, so it can be turned off
  protected readonly chips = computed(() => {
    const used = this.store.usedLabels();
    const filter = this.filter();
    return filter === null || used.includes(filter)
      ? used
      : [...used, filter].sort((a, b) => a.localeCompare(b));
  });

  // where the page shown starts
  protected readonly offset = signal(0);

  protected readonly addingPhoto = signal(false);

  // what was removed last as long as it can be brought back; a list, so the bar showing the time
  // left starts anew for the next removal
  protected readonly removed = computed(() => {
    const removal = this.store.removed();
    return removal ? [removal] : [];
  });

  private readonly i18n = inject(I18n);

  protected readonly removedText: Record<Removal['kind'], string> = {
    note: this.i18n.t('notes.removed.note'),
    photo: this.i18n.t('notes.removed.photo'),
    recording: this.i18n.t('notes.removed.recording'),
  };

  protected readonly shown = computed(() => {
    const label = this.filter();
    const notes = this.store.notes();
    return label === null ? notes : notes.filter((note) => note.labels.includes(label));
  });

  // the error of a note that is not shown, e.g. one deleted on another page
  protected strayError(): string | null {
    const failure = this.store.failure();
    if (failure === null || typeof failure.at !== 'number') return null;
    const at = failure.at;
    return this.shown().some((note) => note.id === at) ? null : failure.message;
  }

  constructor() {
    // whether the photos and recordings offer to discover their text
    this.store.loadSettings();
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
