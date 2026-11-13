package io.pelle.sak.notes.ui

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.pelle.sak.notes.MAX_RECORDING_SECONDS
import io.pelle.sak.notes.RECORDING_RATE
import io.pelle.sak.notes.Recorder
import io.pelle.sak.notes.wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

// 16 kHz mono 16 bit, a wav when it stops; the ui asks for RECORD_AUDIO before
class MicrophoneRecorder : Recorder {
    @Volatile
    private var stopped = false

    @SuppressLint("MissingPermission")
    override suspend fun record(): ByteArray? =
        withContext(Dispatchers.IO) {
            stopped = false
            val buffer =
                AudioRecord.getMinBufferSize(RECORDING_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(RECORDING_RATE)
            // tuned for a voice, as the noise suppression of the web app
            val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RECORDING_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IOException("the microphone can not be used")
            }
            val most = RECORDING_RATE * 2 * MAX_RECORDING_SECONDS
            // sized for a minute up front, so it grows less often
            val pcm = ByteArrayOutputStream(minOf(most, RECORDING_RATE * 2 * 60))
            val chunk = ByteArray(buffer)
            try {
                record.startRecording()
                while (!stopped && coroutineContext.isActive && pcm.size() < most) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read < 0) throw IOException("the microphone failed: $read")
                    pcm.write(chunk, 0, minOf(read, most - pcm.size()))
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
            if (pcm.size() == 0) null else wav(pcm.toByteArray())
        }

    override fun stop() {
        stopped = true
    }
}
