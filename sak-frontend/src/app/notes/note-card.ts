import { DatePipe } from '@angular/common';
import { Component, inject, input, output, signal } from '@angular/core';
import { Note, NOTE_COLORS, NotesStore } from './notes-store';

// one note as a post-it: its text is edited in place, labels are added below it
@Component({
  selector: 'app-note-card',
  imports: [DatePipe],
  host: {
    '[class]': "'note note-' + note().color",
    '[attr.data-testid]': "'note-' + note().id",
  },
  template: `
    <textarea
      class="note-text"
      placeholder="Write something…"
      [attr.aria-label]="'Text of note ' + note().id"
      [value]="note().text"
      (input)="store.setText(note().id, $any($event.target).value)"
    ></textarea>

    <ul class="note-labels" aria-label="Labels">
      @for (label of note().labels; track label) {
        <li class="label">
          <button
            type="button"
            class="label-name"
            [attr.aria-label]="'Show the notes labelled ' + label"
            (click)="labelSelected.emit(label)"
          >
            #{{ label }}
          </button>
          <button
            type="button"
            class="label-remove"
            [attr.aria-label]="'Remove label ' + label"
            (click)="store.removeLabel(note().id, label)"
          >
            ×
          </button>
        </li>
      }
    </ul>

    <form class="note-add-label" (submit)="$event.preventDefault(); addLabel()">
      <input
        class="note-label-input"
        placeholder="+ label"
        autocomplete="off"
        [attr.list]="labelsList()"
        [attr.aria-label]="'Add a label to note ' + note().id"
        [value]="newLabel()"
        (input)="newLabel.set($any($event.target).value)"
      />
    </form>

    <footer class="note-footer">
      <div class="note-colors" role="radiogroup" aria-label="Color">
        @for (color of colors; track color) {
          <button
            type="button"
            role="radio"
            class="color-swatch"
            [class]="'color-swatch note-' + color"
            [attr.aria-checked]="note().color === color"
            [attr.aria-label]="color"
            [title]="color"
            (click)="store.setColor(note().id, color)"
          ></button>
        }
      </div>
      <time class="note-time" [attr.datetime]="note().updated.toISOString()">
        {{ note().updated | date: 'short' }}
      </time>
      <button
        type="button"
        class="note-delete"
        [attr.aria-label]="'Delete note ' + note().id"
        title="Delete"
        (click)="store.remove(note().id)"
      >
        🗑
      </button>
    </footer>
  `,
})
export class NoteCard {
  protected readonly store = inject(NotesStore);

  readonly note = input.required<Note>();
  // the id of the datalist that suggests the known labels
  readonly labelsList = input<string | null>(null);
  readonly labelSelected = output<string>();

  protected readonly colors = NOTE_COLORS;
  protected readonly newLabel = signal('');

  protected addLabel(): void {
    this.store.addLabel(this.note().id, this.newLabel());
    this.newLabel.set('');
  }
}
