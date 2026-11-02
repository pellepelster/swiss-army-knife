package io.pelle.sak.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder

// the rate the recordings are stored at, enough for speech, as the web app (audio-recorder.ts)
const val RECORDING_RATE = 16_000

// a recording stops by itself after this long, so it stays below the backend's size of a file and
// its longest recording (FileService.MAX_RECORDING_SECONDS)
const val MAX_RECORDING_SECONDS = 300

// the samples, 16 bit mono little endian, as a wav file: its RIFF header of 44 bytes and the samples
fun wav(
    pcm: ByteArray,
    rate: Int = RECORDING_RATE,
): ByteArray =
    ByteBuffer
        .allocate(44 + pcm.size)
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
