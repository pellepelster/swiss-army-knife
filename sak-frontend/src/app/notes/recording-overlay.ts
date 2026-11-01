import { Component, computed, effect, ElementRef, inject, viewChild } from '@angular/core';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { AudioRecorder, MAX_RECORDING_SECONDS } from './audio-recorder';
import { NotesStore } from './notes-store';

// minutes and seconds, e.g. 1:05
function clock(seconds: number): string {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

// covers the notes while a recording runs, for a note or a new note; the big button in its middle
// or escape stops it, also while the microphone is still set up
@Component({
  selector: 'app-recording-overlay',
  imports: [TranslatePipe],
  styleUrl: './recording-overlay.scss',
  host: { '(document:keydown.escape)': 'stop()' },
  template: `
    @if (subject(); as subject) {
      <div
        class="recording-overlay position-fixed top-0 start-0 w-100 h-100 d-flex flex-column align-items-center justify-content-center gap-3 p-3 bg-black bg-opacity-75 text-white"
        role="dialog"
        aria-modal="true"
        [attr.aria-label]="subject.dialog"
      >
        <button
          #stopButton
          type="button"
          class="recording-stop btn btn-danger rounded-circle"
          [attr.aria-label]="subject.stop"
          [title]="'notes.recording.stop' | t"
          (click)="stop()"
        >
          <i class="fa-solid fa-stop" aria-hidden="true"></i>
        </button>
        <p class="fs-4 font-monospace mb-0" role="timer">
          @if (recorder.starting()) {
            {{ 'notes.recording.starting' | t }}
          } @else {
            {{ elapsed() }} / {{ limit }}
          }
        </p>
        <p class="text-white-50 mb-0">{{ subject.name }}</p>
      </div>
    }
  `,
})
export class RecordingOverlay {
  protected readonly store = inject(NotesStore);
  protected readonly recorder = inject(AudioRecorder);

  private readonly stopButton = viewChild<ElementRef<HTMLButtonElement>>('stopButton');

  protected readonly limit = clock(MAX_RECORDING_SECONDS);

  private readonly i18n = inject(I18n);

  // what is recorded for, its name and the texts naming it, null when nothing is recorded
  protected readonly subject = computed(() => {
    const target = this.store.recordingFor();
    if (target === null) return null;
    if (target === 'new')
      return {
        name: this.i18n.t('notes.recording.subject_new'),
        dialog: this.i18n.t('notes.recording.dialog_new'),
        stop: this.i18n.t('notes.recording.stop_new'),
      };
    const note = { note: target };
    return {
      name: this.i18n.t('notes.recording.subject', note),
      dialog: this.i18n.t('notes.recording.dialog', note),
      stop: this.i18n.t('notes.recording.stop_of', note),
    };
  });

  protected readonly elapsed = computed(() => clock(this.recorder.seconds() ?? 0));

  constructor() {
    // the stop button takes the focus once it is shown
    effect(() => this.stopButton()?.nativeElement.focus());
  }

  protected stop(): void {
    if (this.store.recordingFor() !== null) this.store.stopRecording();
  }
}
