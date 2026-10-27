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
import { NotesStore } from './notes-store';

// the notes as a wall of post-its, filtered by one label at a time
@Component({
  selector: 'app-notes-page',
  imports: [NoteCard, RouterLink],
  styleUrl: './notes-page.scss',
  template: `
    <header class="toolbar">
      <a class="back" routerLink="/" aria-label="All apps">←</a>
      <h1 class="title">Notes</h1>
      <button type="button" class="add" (click)="add()">+ New note</button>
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
    const note = await this.store.add('', 'yellow', label === null ? [] : [label]);
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
