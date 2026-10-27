import { Component, inject, input, output, signal } from '@angular/core';
import { LocalDateTimePipe } from '../shared/local-date-time.pipe';
import { MAX_LABELS, Note, NOTE_COLORS, NotesStore } from './notes-store';

// one note as a post-it: the thumbnails of its photos on top, each leads to the whole photo, its text is edited in place, labels are added below
// it; a photo is taken with the camera of a phone or chosen from the files elsewhere
@Component({
  selector: 'app-note-card',
  imports: [LocalDateTimePipe],
  host: {
    '[class]': "'note note-' + note().color",
    '[attr.data-testid]': "'note-' + note().id",
  },
  template: `
    @if (note().photos.length) {
      <ul class="note-photos" aria-label="Photos">
        @for (photo of note().photos; track photo; let index = $index) {
          <li class="photo">
            <a [href]="photoUrl(photo)" target="_blank" rel="noopener">
              <img
                [src]="photoUrl(photo) + '/thumbnail'"
                [alt]="'Photo ' + (index + 1) + ' of note ' + note().id"
                loading="lazy"
              />
            </a>
            @if (confirmingPhoto() === photo) {
              <div
                class="photo-confirm"
                role="group"
                [attr.aria-label]="'Remove photo ' + (index + 1) + '?'"
              >
                <span>Remove photo?</span>
                <button type="button" class="photo-confirm-remove" (click)="removePhoto(photo)">
                  Remove
                </button>
                <button
                  type="button"
                  class="photo-confirm-keep"
                  (click)="confirmingPhoto.set(null)"
                >
                  Keep
                </button>
              </div>
            } @else {
              <button
                type="button"
                class="photo-remove"
                [attr.aria-label]="'Remove photo ' + (index + 1)"
                (click)="confirmingPhoto.set(photo)"
              >
                <i class="fa-solid fa-trash" aria-hidden="true"></i>
              </button>
            }
          </li>
        }
      </ul>
    }

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
            <i class="fa-solid fa-xmark" aria-hidden="true"></i>
          </button>
        </li>
      }
    </ul>

    @if (note().labels.length < maxLabels) {
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
    } @else {
      <p class="note-labels-full">A note has at most {{ maxLabels }} labels.</p>
    }

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
        {{ note().updated | localDateTime }}
      </time>
      <input
        #camera
        class="photo-input"
        type="file"
        accept="image/*"
        capture="environment"
        tabindex="-1"
        aria-hidden="true"
        (change)="takePhoto($any($event.target))"
      />
      <button
        type="button"
        class="note-photo"
        [attr.aria-label]="'Add a photo to note ' + note().id"
        title="Add a photo"
        [disabled]="store.uploading().has(note().id)"
        (click)="camera.click()"
      >
        @if (store.uploading().has(note().id)) {
          <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
        } @else {
          <i class="fa-solid fa-camera" aria-hidden="true"></i>
        }
      </button>
      <button
        type="button"
        class="note-delete"
        [attr.aria-label]="'Delete note ' + note().id"
        title="Delete"
        (click)="store.remove(note().id)"
      >
        <i class="fa-solid fa-trash" aria-hidden="true"></i>
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
  protected readonly maxLabels = MAX_LABELS;
  protected readonly newLabel = signal('');

  // the photo whose removal is asked for, removed only once it is confirmed
  protected readonly confirmingPhoto = signal<string | null>(null);

  protected removePhoto(sha256: string): void {
    this.confirmingPhoto.set(null);
    this.store.removePhoto(this.note().id, sha256);
  }

  // the whole photo, its thumbnail is below it
  protected photoUrl(sha256: string): string {
    return `/api/v1/files/${sha256}`;
  }

  protected takePhoto(input: HTMLInputElement): void {
    const photo = input.files?.[0];
    // the same photo can be chosen again
    input.value = '';
    if (photo) void this.store.addPhoto(this.note().id, photo);
  }

  protected addLabel(): void {
    this.store.addLabel(this.note().id, this.newLabel());
    this.newLabel.set('');
  }
}
