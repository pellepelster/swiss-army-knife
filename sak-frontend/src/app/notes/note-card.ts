import { AsyncPipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, input, output, signal } from '@angular/core';
import { LocalDateTimePipe } from '../shared/local-date-time.pipe';
import { MAX_LABELS, Note, NOTE_COLORS, NotesStore } from './notes-store';
import { StoredFiles, ThumbnailPipe } from './stored-files';

// how long an opened photo keeps its object url, its tab has loaded it by then
const OPENED_URL_LIFETIME_MS = 60_000;

// a photo or a recording of a note, shown alike
interface Media {
  readonly id: number;
  readonly kind: 'photo' | 'recording';
  readonly label: string;
}

// one note as a post-it: the thumbnails of its photos and the waveforms of its recordings on top,
// a photo leads to the whole photo, a recording plays in place, the robot on each writes it into
// the text; the text is edited in place, labels are added below it. A photo is taken with the
// camera of a phone or chosen from the files elsewhere, a recording with the microphone
@Component({
  selector: 'app-note-card',
  imports: [AsyncPipe, LocalDateTimePipe, ThumbnailPipe],
  host: {
    '[class]': "'note h-100 note-' + note().color",
    '[attr.data-testid]': "'note-' + note().id",
  },
  template: `
    @if (media().length) {
      <ul class="note-photos" aria-label="Photos and recordings">
        @for (item of media(); track item.id) {
          <li class="photo">
            @if (item.kind === 'photo') {
              <!-- no link, the file is only served with the session; it is opened as an object url -->
              <button type="button" class="photo-open" (click)="open(item.id)">
                <img
                  [attr.src]="item.id | thumbnail | async"
                  [alt]="item.label + ' of note ' + note().id"
                  loading="lazy"
                />
              </button>
            } @else {
              <button
                type="button"
                class="recording"
                [attr.aria-label]="(playing() === item.id ? 'Pause ' : 'Play ') + item.label"
                [attr.aria-pressed]="playing() === item.id"
                (click)="play(item.id)"
              >
                <img [attr.src]="item.id | thumbnail | async" alt="" loading="lazy" />
                <span class="recording-play" aria-hidden="true">
                  <i
                    class="fa-solid"
                    [class.fa-play]="playing() !== item.id"
                    [class.fa-pause]="playing() === item.id"
                  ></i>
                </span>
              </button>
            }
            <button
              type="button"
              class="photo-discover"
              [attr.aria-label]="'Discover the text in ' + item.label.toLowerCase()"
              title="Discover the text"
              [disabled]="store.discovering().has(item.id)"
              (click)="store.discoverText(note().id, item.id)"
            >
              @if (store.discovering().has(item.id)) {
                <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
              } @else {
                <i class="fa-solid fa-robot" aria-hidden="true"></i>
              }
            </button>
            @if (confirming() === item.id) {
              <div
                class="photo-confirm"
                role="group"
                [attr.aria-label]="'Remove ' + item.label.toLowerCase() + '?'"
              >
                <span>Remove {{ item.kind }}?</span>
                <button type="button" class="photo-confirm-remove" (click)="remove(item)">
                  Remove
                </button>
                <button type="button" class="photo-confirm-keep" (click)="confirming.set(null)">
                  Keep
                </button>
              </div>
            } @else {
              <button
                type="button"
                class="photo-remove"
                [attr.aria-label]="'Remove ' + item.label.toLowerCase()"
                (click)="confirming.set(item.id)"
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
        <li class="note-label">
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
      <time
        class="note-time flex-grow-1"
        [attr.datetime]="(note().updated ?? note().created).toISOString()"
      >
        {{ note().updated ?? note().created | localDateTime }}
      </time>
      <div class="d-flex align-items-center">
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
            <i class="sak-icon-plus" aria-hidden="true"></i><i class="fa-solid fa-camera" aria-hidden="true"></i>
          }
        </button>
        <button
          type="button"
          class="note-photo"
          [attr.aria-label]="'Record for note ' + note().id"
          title="Record"
          [disabled]="store.recordingFor() !== null"
          (click)="store.record(note().id)"
        >
          <i class="sak-icon-plus" aria-hidden="true"></i><i class="fa-solid fa-microphone" aria-hidden="true"></i>
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
      </div>
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

  // the photos first, then the recordings, as the backend keeps them
  protected readonly media = computed<readonly Media[]>(() => [
    ...this.note().photos.map((id, index) => ({
      id,
      kind: 'photo' as const,
      label: `Photo ${index + 1}`,
    })),
    ...this.note().recordings.map((id, index) => ({
      id,
      kind: 'recording' as const,
      label: `Recording ${index + 1}`,
    })),
  ]);

  // the photo or recording whose removal is asked for, removed only once it is confirmed
  protected readonly confirming = signal<number | null>(null);

  // the recording that plays, one at a time
  protected readonly playing = signal<number | null>(null);
  private audio: HTMLAudioElement | null = null;

  private readonly files = inject(StoredFiles);

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopAudio());
  }

  protected remove(item: Media): void {
    this.confirming.set(null);
    if (item.kind === 'photo') this.store.removePhoto(this.note().id, item.id);
    else this.store.removeRecording(this.note().id, item.id);
  }

  // plays the recording in the note, a second tap pauses it; it is fetched with the session first
  protected async play(id: number): Promise<void> {
    const playing = this.playing();
    this.stopAudio();
    if (playing === id) return;
    this.playing.set(id);
    try {
      const url = await this.files.file(id);
      // tapped again or another one while it was fetched
      if (this.playing() !== id) {
        URL.revokeObjectURL(url);
        return;
      }
      const audio = new Audio(url);
      audio.addEventListener('ended', () => this.playing.set(null));
      this.audio = audio;
      await audio.play();
    } catch {
      // the browser did not play it: the url is revoked, the next tap starts anew
      if (this.playing() === id) this.stopAudio();
    }
  }

  private stopAudio(): void {
    if (this.audio) {
      this.audio.pause();
      URL.revokeObjectURL(this.audio.src);
    }
    this.audio = null;
    this.playing.set(null);
  }

  // the whole photo in a tab of its own; the tab is opened right away, a browser blocks it once
  // the photo has been fetched
  protected async open(id: number): Promise<void> {
    const tab = window.open('', '_blank');
    try {
      const url = await this.files.file(id);
      if (tab) tab.location.href = url;
      setTimeout(() => URL.revokeObjectURL(url), OPENED_URL_LIFETIME_MS);
    } catch {
      tab?.close();
    }
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
