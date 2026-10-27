import { signal } from '@angular/core';

// what the backend answers in the tests of the notes
const CREATED = '2026-10-27T08:00:00Z';

export const noteResponse = (
  id: number,
  text: string,
  color: string,
  labels: string[],
  photos: number[] = [],
  recordings: number[] = [],
) => ({
  id,
  text,
  color,
  labels: labels.map((name, index) => ({ id: index + 1, name })),
  photos: photos.map((id) => ({
    id,
    url: `/api/v1/files/${id}`,
    thumbnailUrl: `/api/v1/files/${id}/thumbnail`,
  })),
  recordings: recordings.map((id) => ({
    id,
    url: `/api/v1/files/${id}`,
    thumbnailUrl: `/api/v1/files/${id}/thumbnail`,
  })),
  createdAt: CREATED,
  updatedAt: null,
});

// what a list endpoint answers with the items
export const pageOf = <T>(items: T[]) => ({
  items,
  pagination: { offset: 0, limit: 500, total: items.length },
});

// the photo as the browser would scale it, scaling needs a real canvas
export const SCALED = new Blob(['scaled'], { type: 'image/jpeg' });
export const scaler = { scale: async () => SCALED };

export const FILE_ID = 7;

// a recording as the browser would make it, recording needs a microphone; stop() ends it. With
// slowStart the microphone is set up until started() is called, a stop before records nothing
export const RECORDED = new Blob(['RIFF'], { type: 'audio/wav' });
export class FakeRecorder {
  readonly seconds = signal<number | null>(null);
  readonly starting = signal(false);
  slowStart = false;
  private finish: ((recording: Blob | null) => void) | null = null;

  record(): Promise<Blob | null> {
    this.starting.set(this.slowStart);
    if (!this.slowStart) this.seconds.set(0);
    return new Promise((resolve) => {
      this.finish = (recording) => {
        this.starting.set(false);
        this.seconds.set(null);
        this.finish = null;
        resolve(recording);
      };
    });
  }

  started(): void {
    this.starting.set(false);
    this.seconds.set(0);
  }

  stop(): void {
    this.finish?.(this.starting() ? null : RECORDED);
  }
}
