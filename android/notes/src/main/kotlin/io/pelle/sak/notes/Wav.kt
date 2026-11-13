package io.pelle.sak.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder

// enough for speech, as the web app (audio-recorder.ts)
const val RECORDING_RATE = 16_000

// a recording stops by itself after this long, below the limits of the backend
// (FileService.MAX_RECORDING_SECONDS)
const val MAX_RECORDING_SECONDS = 300

// the bytes of the RIFF header
const val WAV_HEADER = 44

// 16 bit mono little endian samples as a wav file
fun wav(
    pcm: ByteArray,
    rate: Int = RECORDING_RATE,
): ByteArray =
    ByteBuffer
        .allocate(WAV_HEADER + pcm.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put("RIFF".toByteArray())
            putInt(36 + pcm.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            // the size of the format, PCM, one channel, the rate, the bytes a second, the bytes a sample, its bits
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(rate)
            putInt(rate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(pcm.size)
            put(pcm)
        }.array()
