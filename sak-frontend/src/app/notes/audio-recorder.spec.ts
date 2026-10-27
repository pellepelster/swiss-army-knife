import { downsample, wav } from './audio-recorder';

describe('AudioRecorder', () => {
  it('averages the samples down to the lower rate', () => {
    const [first, second] = downsample(new Float32Array([0, 1, 0.5, 0.5, -1, 0]), 48_000, 16_000);
    expect(first).toBeCloseTo(0.5);
    expect(second).toBeCloseTo(-1 / 6);
    const same = new Float32Array([0.1, 0.2]);
    expect(downsample(same, 16_000, 16_000)).toBe(same);
  });

  it('writes mono 16 bit pcm wav', async () => {
    const file = wav(new Float32Array([0, 1, -1, 2]), 16_000);
    expect(file.type).toBe('audio/wav');
    const view = new DataView(await file.arrayBuffer());
    const text = (offset: number) =>
      String.fromCharCode(...[0, 1, 2, 3].map((index) => view.getUint8(offset + index)));
    expect([text(0), text(8), text(12), text(36)]).toEqual(['RIFF', 'WAVE', 'fmt ', 'data']);
    expect(view.getUint16(22, true)).toBe(1);
    expect(view.getUint32(24, true)).toBe(16_000);
    expect(view.getUint16(34, true)).toBe(16);
    expect(view.getUint32(40, true)).toBe(8);
    // clipped to the loudest sample
    expect([0, 1, 2, 3].map((index) => view.getInt16(44 + index * 2, true))).toEqual([
      0, 32767, -32768, 32767,
    ]);
  });
});
