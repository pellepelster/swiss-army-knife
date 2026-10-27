import { Injectable, signal } from '@angular/core';

// the rate the recordings are stored at, enough for speech
const RECORDING_RATE = 16_000;

// a recording stops by itself after this long, so it stays below the backend's 10 MB per file
export const MAX_RECORDING_SECONDS = 300;

// records the microphone as 16 kHz mono 16 bit wav, the format every speech to text model takes;
// the samples come from an audio worklet, the browser's own recorder only writes compressed formats
@Injectable({ providedIn: 'root' })
export class AudioRecorder {
  // how long the recording runs, null when there is none or it is not started yet
  readonly seconds = signal<number | null>(null);

  // while the microphone and the worklet are set up, before the recording runs
  readonly starting = signal(false);

  private finish: (() => void) | null = null;

  // stop() was called before the recording ran
  private stopped = false;

  // the recording once it is stopped, null when it is stopped before it runs; rejects when the
  // microphone can not be used
  async record(): Promise<Blob | null> {
    if (this.starting() || this.finish) throw new Error('a recording runs already');
    this.stopped = false;
    this.starting.set(true);
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
      });
    } catch (error) {
      this.starting.set(false);
      throw error;
    }
    const context = new AudioContext();
    const chunks: Float32Array[] = [];
    let timer: ReturnType<typeof setInterval> | undefined;
    try {
      if (this.stopped) return null;
      await context.audioWorklet.addModule('recorder-worklet.js');
      if (this.stopped) return null;
      const source = context.createMediaStreamSource(stream);
      const recorder = new AudioWorkletNode(context, 'recorder');
      recorder.port.onmessage = (event: MessageEvent<Float32Array>) => chunks.push(event.data);
      source.connect(recorder);

      const started = Date.now();
      this.starting.set(false);
      this.seconds.set(0);
      await new Promise<void>((resolve) => {
        this.finish = resolve;
        timer = setInterval(() => {
          const seconds = Math.floor((Date.now() - started) / 1000);
          this.seconds.set(seconds);
          if (seconds >= MAX_RECORDING_SECONDS) resolve();
        }, 250);
      });
      source.disconnect();
      recorder.disconnect();
      return wav(downsample(join(chunks), context.sampleRate, RECORDING_RATE), RECORDING_RATE);
    } finally {
      clearInterval(timer);
      this.finish = null;
      this.starting.set(false);
      this.seconds.set(null);
      stream.getTracks().forEach((track) => track.stop());
      void context.close();
    }
  }

  // ends the recording, record() hands it over; while it is set up, it does not start
  stop(): void {
    if (this.starting()) this.stopped = true;
    this.finish?.();
  }
}

function join(chunks: Float32Array[]): Float32Array {
  const samples = new Float32Array(chunks.reduce((length, chunk) => length + chunk.length, 0));
  let offset = 0;
  for (const chunk of chunks) {
    samples.set(chunk, offset);
    offset += chunk.length;
  }
  return samples;
}

// to the lower rate, each sample the average of the ones it stands for
export function downsample(samples: Float32Array, from: number, to: number): Float32Array {
  if (from <= to) return samples;
  const ratio = from / to;
  const result = new Float32Array(Math.floor(samples.length / ratio));
  for (let index = 0; index < result.length; index++) {
    const start = Math.floor(index * ratio);
    const end = Math.min(samples.length, Math.floor((index + 1) * ratio));
    let sum = 0;
    for (let at = start; at < end; at++) sum += samples[at];
    result[index] = sum / Math.max(1, end - start);
  }
  return result;
}

// a wav file of the samples, mono 16 bit pcm
export function wav(samples: Float32Array, rate: number): Blob {
  const view = new DataView(new ArrayBuffer(44 + samples.length * 2));
  const text = (offset: number, value: string) =>
    [...value].forEach((char, index) => view.setUint8(offset + index, char.charCodeAt(0)));
  text(0, 'RIFF');
  view.setUint32(4, 36 + samples.length * 2, true);
  text(8, 'WAVE');
  text(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, rate, true);
  view.setUint32(28, rate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  text(36, 'data');
  view.setUint32(40, samples.length * 2, true);
  samples.forEach((sample, index) => {
    const clamped = Math.max(-1, Math.min(1, sample));
    view.setInt16(44 + index * 2, clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff, true);
  });
  return new Blob([view.buffer], { type: 'audio/wav' });
}
