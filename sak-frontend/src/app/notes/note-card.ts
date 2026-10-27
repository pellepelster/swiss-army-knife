import { Component, computed, DestroyRef, inject, input, output, signal } from '@angular/core';
import { LocalDateTimePipe } from '../shared/local-date-time.pipe';
import { AudioRecorder } from './audio-recorder';
import { MAX_LABELS, Note, NOTE_COLORS, NotesStore } from './notes-store';

// a photo or a recording of a note, shown alike
interface Media {
  readonly sha256: string;
  readonly kind: 'photo' | 'recording';
  readonly label: string;
}

// one note as a post-it: the thumbnails of its photos and the waveforms of its recordings on top,
// a photo leads to the whole photo, a recording plays in place, the robot on each writes it into
// the text; the text is edited in place, labels are added below it. A photo is taken with the
// camera of a phone or chosen from the files elsewhere, a recording with the microphone
@Component({
  selector: 'app-note-card',
  imports: [LocalDateTimePipe],
  host: {
    '[class]': "'note note-' + note().color",
    '[attr.data-testid]': "'note-' + note().id",
  },
  template: `
    @if (media().length) {
      <ul class="note-photos" aria-label="Photos and recordings">
        @for (item of media(); track item.sha256) {
          <li class="photo">
            @if (item.kind === 'photo') {
              <a [href]="fileUrl(item.sha256)" target="_blank" rel="noopener">
                <img
                  [src]="fileUrl(item.sha256) + '/thumbnail'"
                  [alt]="item.label + ' of note ' + note().id"
                  loading="lazy"
                />
              </a>
            } @else {
              <button
                type="button"
                class="recording"
                [attr.aria-label]="(playing() === item.sha256 ? 'Pause ' : 'Play ') + item.label"
                [attr.aria-pressed]="playing() === item.sha256"
                (click)="play(item.sha256)"
              >
                <img [src]="fileUrl(item.sha256) + '/thumbnail'" alt="" loading="lazy" />
                <span class="recording-play" aria-hidden="true">
                  <i
                    class="fa-solid"
                    [class.fa-play]="playing() !== item.sha256"
                    [class.fa-pause]="playing() === item.sha256"
                  ></i>
                </span>
              </button>
            }
            <button
              type="button"
              class="photo-discover"
              [attr.aria-label]="'Discover the text in ' + item.label.toLowerCase()"
              title="Discover the text"
              [disabled]="store.discovering().has(item.sha256)"
              (click)="store.discoverText(note().id, item.sha256)"
            >
              @if (store.discovering().has(item.sha256)) {
                <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
              } @else {
                <i class="fa-solid fa-robot" aria-hidden="true"></i>
              }
            </button>
            @if (confirming() === item.sha256) {
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
                (click)="confirming.set(item.sha256)"
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
        class="note-photo"
        [class.recording-now]="store.recordingFor() === note().id"
        [attr.aria-label]="
          (store.recordingFor() === note().id ? 'Stop recording for note ' : 'Record for note ') +
          note().id
        "
        [title]="store.recordingFor() === note().id ? 'Stop recording' : 'Record'"
        [disabled]="store.recordingFor() !== null && store.recordingFor() !== note().id"
        (click)="store.record(note().id)"
      >
        @if (store.recordingFor() === note().id) {
          <i class="fa-solid fa-stop" aria-hidden="true"></i>
          <span class="recording-seconds">{{ recorder.seconds() ?? 0 }}s</span>
        } @else {
          <i class="fa-solid fa-microphone" aria-hidden="true"></i>
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
  protected readonly recorder = inject(AudioRecorder);

  // the photos first, then the recordings, as the backend keeps them
  protected readonly media = computed<readonly Media[]>(() => [
    ...this.note().photos.map((sha256, index) => ({
      sha256,
      kind: 'photo' as const,
      label: `Photo ${index + 1}`,
    })),
    ...this.note().recordings.map((sha256, index) => ({
      sha256,
      kind: 'recording' as const,
      label: `Recording ${index + 1}`,
    })),
  ]);

  // the photo or recording whose removal is asked for, removed only once it is confirmed
  protected readonly confirming = signal<string | null>(null);

  // the recording that plays, one at a time
  protected readonly playing = signal<string | null>(null);
  private audio: HTMLAudioElement | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.audio?.pause());
  }

  protected remove(item: Media): void {
    this.confirming.set(null);
    if (item.kind === 'photo') this.store.removePhoto(this.note().id, item.sha256);
    else this.store.removeRecording(this.note().id, item.sha256);
  }

  // plays the recording in the note, a second tap pauses it
  protected play(sha256: string): void {
    const playing = this.playing();
    this.audio?.pause();
    this.audio = null;
    this.playing.set(null);
    if (playing === sha256) return;
    const audio = new Audio(this.fileUrl(sha256));
    audio.addEventListener('ended', () => this.playing.set(null));
    this.audio = audio;
    this.playing.set(sha256);
    void audio.play().catch(() => this.playing.set(null));
  }

  // the whole photo or recording, its thumbnail or waveform is below it
  protected fileUrl(sha256: string): string {
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
