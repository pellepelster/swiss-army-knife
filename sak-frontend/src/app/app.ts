import { afterNextRender, Component, computed, inject, Injector, signal } from '@angular/core';
import { NoteCard } from './notes/note-card';
import { NotesStore } from './notes/notes-store';

// the notes as a wall of post-its, filtered by one label at a time
@Component({
  selector: 'app-root',
  imports: [NoteCard],
  styleUrl: './app.scss',
  template: `
    <header class="toolbar">
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

    <datalist id="known-labels">
      @for (label of store.labels(); track label) {
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
          @if (filter(); as label) {
            No note is labelled #{{ label }}.
          } @else {
            No notes yet, add one.
          }
        </p>
      }
    </main>
  `,
})
export class App {
  protected readonly store = inject(NotesStore);
  private readonly injector = inject(Injector);

  protected readonly filter = signal<string | null>(null);

  protected readonly shown = computed(() => {
    const label = this.filter();
    const notes = this.store.notes();
    return label === null ? notes : notes.filter((note) => note.labels.includes(label));
  });

  // a new note gets the label shown, so it stays in sight; its text is focused right away
  protected add(): void {
    const label = this.filter();
    const note = this.store.add('', 'yellow', label === null ? [] : [label]);
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
