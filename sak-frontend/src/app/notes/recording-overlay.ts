import { Component, computed, effect, ElementRef, inject, viewChild } from '@angular/core';
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
  styleUrl: './recording-overlay.scss',
  host: { '(document:keydown.escape)': 'stop()' },
  template: `
    @if (subject(); as subject) {
      <div
        class="recording-overlay"
        role="dialog"
        aria-modal="true"
        [attr.aria-label]="'Recording ' + subject"
      >
        <button
          #stopButton
          type="button"
          class="recording-stop"
          [attr.aria-label]="'Stop the recording of ' + subject"
          title="Stop recording"
          (click)="stop()"
        >
          <i class="fa-solid fa-stop" aria-hidden="true"></i>
        </button>
        <p class="recording-time" role="timer">
          @if (recorder.starting()) {
            Starting…
          } @else {
            {{ elapsed() }} / {{ limit }}
          }
        </p>
        <p class="recording-subject">{{ subject }}</p>
      </div>
    }
  `,
})
export class RecordingOverlay {
  protected readonly store = inject(NotesStore);
  protected readonly recorder = inject(AudioRecorder);

  private readonly stopButton = viewChild<ElementRef<HTMLButtonElement>>('stopButton');

  protected readonly limit = clock(MAX_RECORDING_SECONDS);

  // what is recorded for, null when nothing is recorded
  protected readonly subject = computed(() => {
    const target = this.store.recordingFor();
    if (target === null) return null;
    return target === 'new' ? 'the new note' : `note ${target}`;
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
