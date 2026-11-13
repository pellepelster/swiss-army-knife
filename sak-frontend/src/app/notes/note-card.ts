import { AsyncPipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, input, output, signal } from '@angular/core';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { LocalDateTimePipe } from '../shared/local-date-time.pipe';
import { MAX_LABELS, Note, NOTE_COLORS, NotesStore } from './notes-store';
import { StoredFiles, ThumbnailPipe } from './stored-files';

// how long an opened photo keeps its object url, its tab has loaded it by then
const OPENED_URL_LIFETIME_MS = 60_000;

// a photo or a recording of a note, shown alike
interface Media {
  readonly id: number;
  readonly kind: 'photo' | 'recording';
  // the texts naming it: a photo has no play and pause, a recording no alt
  readonly alt: string;
  readonly play: string;
  readonly pause: string;
  readonly discover: string;
  readonly remove: string;
}

// one note as a post-it: its photos and recordings on top, the text edited in place, the labels
// below it; a photo opens whole in a tab, a recording plays in place
@Component({
  selector: 'app-note-card',
  imports: [AsyncPipe, LocalDateTimePipe, ThumbnailPipe, TranslatePipe],
  host: {
    '[class]': "'note note-' + note().color",
    '[attr.data-testid]': "'note-' + note().id",
  },
  template: `
    @if (media().length) {
      <ul class="note-photos" [attr.aria-label]="'notes.card.media' | t">
        @for (item of media(); track item.id) {
          <li class="photo">
            @if (item.kind === 'photo') {
              <!-- no link, the file is only served with the session; it is opened as an object url -->
              <button type="button" class="photo-open" (click)="open(item.id)">
                <img [attr.src]="item.id | thumbnail | async" [alt]="item.alt" loading="lazy" />
              </button>
            } @else {
              <button
                type="button"
                class="recording"
                [attr.aria-label]="playing() === item.id ? item.pause : item.play"
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
            <!-- the text is only discovered while the backend says it can be (NOTES-5) -->
            @if (store.discoverAvailable()) {
              <button
                type="button"
                class="photo-discover"
                [attr.aria-label]="item.discover"
                [title]="'notes.card.discover' | t"
                [disabled]="store.discovering().has(item.id)"
                (click)="store.discoverText(note().id, item.id)"
              >
                @if (store.discovering().has(item.id)) {
                  <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
                } @else {
                  <i class="fa-solid fa-robot" aria-hidden="true"></i>
                }
              </button>
            }
            <button
              type="button"
              class="photo-remove"
              [attr.aria-label]="item.remove"
              (click)="remove(item)"
            >
              <i class="fa-solid fa-trash" aria-hidden="true"></i>
            </button>
          </li>
        }
      </ul>
    }

    <textarea
      class="note-text"
      [placeholder]="'notes.card.write' | t"
      [attr.aria-label]="'notes.card.text_of' | t: { note: note().id }"
      [value]="note().text"
      (input)="store.setText(note().id, $any($event.target).value)"
    ></textarea>

    <ul class="note-labels" [attr.aria-label]="'notes.card.labels' | t">
      @for (label of note().labels; track label) {
        <li class="note-label">
          <button
            type="button"
            class="label-name"
            [attr.aria-label]="'notes.card.show_labelled' | t: { label }"
            (click)="labelSelected.emit(label)"
          >
            #{{ label }}
          </button>
          <button
            type="button"
            class="label-remove"
            [attr.aria-label]="'notes.card.remove_label' | t: { label }"
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
          [placeholder]="'notes.card.add_label' | t"
          autocomplete="off"
          [attr.list]="labelsList()"
          [attr.aria-label]="'notes.card.add_label_to' | t: { note: note().id }"
          [value]="newLabel()"
          (input)="newLabel.set($any($event.target).value)"
        />
      </form>
    } @else {
      <p class="note-labels-full">
        {{ 'notes.card.labels_full' | t: { max: maxLabels } }}
      </p>
    }

    <!-- the error of what was done to the note, in its card (UX-15) -->
    @if (store.errorAt(note().id); as error) {
      <p class="alert alert-danger small py-1 px-2 mb-0" role="alert">{{ error }}</p>
    }

    <footer class="note-footer">
      <div class="note-colors" role="radiogroup" [attr.aria-label]="'notes.card.color' | t">
        @for (color of colors; track color) {
          <button
            type="button"
            role="radio"
            class="color-swatch"
            [class]="'color-swatch note-' + color"
            [attr.aria-checked]="note().color === color"
            [attr.aria-label]="colorNames[color]"
            [title]="colorNames[color]"
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
          [attr.aria-label]="'notes.card.add_photo_to' | t: { note: note().id }"
          [title]="'notes.card.add_photo' | t"
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
          class="note-photo"
          [attr.aria-label]="'notes.card.record_for' | t: { note: note().id }"
          [title]="'notes.card.record' | t"
          [disabled]="store.recordingFor() !== null"
          (click)="store.record(note().id)"
        >
          <i class="fa-solid fa-microphone" aria-hidden="true"></i>
        </button>
        <button
          type="button"
          class="note-delete"
          [attr.aria-label]="'notes.card.delete_note' | t: { note: note().id }"
          [title]="'shared.delete' | t"
          (click)="store.delete(note().id)"
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

  private readonly i18n = inject(I18n);

  protected readonly colors = NOTE_COLORS;
  // the names shown; the backend gets the color itself
  protected readonly colorNames: Record<(typeof NOTE_COLORS)[number], string> = {
    yellow: this.i18n.t('notes.colors.yellow'),
    pink: this.i18n.t('notes.colors.pink'),
    blue: this.i18n.t('notes.colors.blue'),
    green: this.i18n.t('notes.colors.green'),
    orange: this.i18n.t('notes.colors.orange'),
  };
  protected readonly maxLabels = MAX_LABELS;
  protected readonly newLabel = signal('');

  // the photos first, then the recordings, as the backend keeps them
  protected readonly media = computed<readonly Media[]>(() => [
    ...this.note().photos.map((id, index) => {
      const params = { number: index + 1, note: this.note().id };
      return {
        id,
        kind: 'photo' as const,
        alt: this.i18n.t('notes.card.photo.alt', params),
        play: '',
        pause: '',
        discover: this.i18n.t('notes.card.photo.discover', params),
        remove: this.i18n.t('notes.card.photo.remove', params),
      };
    }),
    ...this.note().recordings.map((id, index) => {
      const params = { number: index + 1 };
      return {
        id,
        kind: 'recording' as const,
        alt: '',
        play: this.i18n.t('notes.card.recording.play', params),
        pause: this.i18n.t('notes.card.recording.pause', params),
        discover: this.i18n.t('notes.card.recording.discover', params),
        remove: this.i18n.t('notes.card.recording.remove', params),
      };
    }),
  ]);

  // the recording that plays, one at a time
  protected readonly playing = signal<number | null>(null);
  private audio: HTMLAudioElement | null = null;

  private readonly files = inject(StoredFiles);

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopAudio());
  }

  // the page offers an undo for a while
  protected remove(item: Media): void {
    if (item.kind === 'photo') this.store.removePhoto(this.note().id, item.id);
    else this.store.removeRecording(this.note().id, item.id);
  }

  // a second tap pauses it; it is fetched with the session first
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
      // the browser did not play it: the url is revoked, the next tap starts over
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

  // the tab opens before the fetch: after it, the browser blocks the popup
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
