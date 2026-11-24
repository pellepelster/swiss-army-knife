package io.pelle.sak.backend.files

import io.pelle.sak.api.storage.model.BlobContent
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.math.abs

// the recordings of the file storage: which ones it takes, how long one is and its picture, the
// waveform that is its thumbnail
object Recordings {
    // null when the header leaves the number or the rate of the frames open
    // (AudioSystem.NOT_SPECIFIED)
    fun recordingSeconds(format: AudioFileFormat): Float? =
        format.takeIf { it.frameLength >= 0 && it.format.frameRate > 0 }?.let { it.frameLength / it.format.frameRate }

    // whether the recording is one the apps record or close to it: pcm of 8 or 16 bit, mono or
    // stereo. The header is of the uploader; what reads a recording (the waveform) sizes its
    // buffers from it, so nothing else is stored
    fun isRecording(format: AudioFormat): Boolean =
        format.encoding in PCM &&
            format.sampleSizeInBits in RECORDING_BITS &&
            format.channels in 1..MAX_RECORDING_CHANNELS &&
            format.frameSize == format.channels * format.sampleSizeInBits / 8

    private val PCM = setOf(AudioFormat.Encoding.PCM_SIGNED, AudioFormat.Encoding.PCM_UNSIGNED)

    private val RECORDING_BITS = setOf(8, 16)

    private const val MAX_RECORDING_CHANNELS = 2

    private const val WAVEFORM_BARS = 48

    // the loudness of the recording in bars from its start to its end, on the 4:3 of the
    // photos; the loudest part reaches the full height, silence keeps a dot
    internal fun waveform(recording: BlobContent): BufferedImage {
        // the reader marks and resets the stream
        val peaks = AudioSystem.getAudioInputStream(BufferedInputStream(recording.open())).use { peaks(it) }
        val loudest = peaks.max().takeIf { it > 0f } ?: 1f
        val width = FileService.THUMBNAIL_SIZE
        val height = FileService.THUMBNAIL_SIZE * 3 / 4
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            color = WAVEFORM_BACKGROUND
            fillRect(0, 0, width, height)
            color = WAVEFORM_INK
            val step = width.toDouble() / (WAVEFORM_BARS + 2)
            val bar = step * 0.6
            peaks.forEachIndexed { index, peak ->
                val barHeight = maxOf(bar, peak / loudest * height * 0.7)
                val x = step * (index + 1) + (step - bar) / 2
                fill(RoundRectangle2D.Double(x, (height - barHeight) / 2, bar, barHeight, bar, bar))
            }
            dispose()
        }
        return image
    }

    // the largest absolute sample of the first channel (from 0 to 1, of 8 or 16 bit pcm) in
    // each of the WAVEFORM_BARS bars, read block by block; a bar is at least one frame, a
    // recording of fewer frames than bars has bars that share a frame
    private fun peaks(input: AudioInputStream): List<Float> {
        val format = input.format
        val frameSize = format.frameSize
        val bits = format.sampleSizeInBits
        // also of a recording stored before only these were taken
        if (!isRecording(format)) throw UnsupportedAudioFileException("only mono and stereo pcm of 8 and 16 bit is drawn, not $format")
        val frames = input.frameLength
        if (frames < 0) throw UnsupportedAudioFileException("the number of frames is not known")
        val from = LongArray(WAVEFORM_BARS) { frames * it / WAVEFORM_BARS }
        val to = LongArray(WAVEFORM_BARS) { maxOf(from[it] + 1, frames * (it + 1) / WAVEFORM_BARS) }
        val peaks = FloatArray(WAVEFORM_BARS)
        // the first bar that has not ended before the frame
        var first = 0
        var frame = 0L
        // whole frames; its size is bounded in bytes, the size of a frame is of the header
        val block = ByteArray(frameSize * maxOf(1, WAVEFORM_BLOCK_SIZE / frameSize))
        while (true) {
            val read = input.readNBytes(block, 0, block.size)
            if (read < frameSize) break
            for (offset in 0..read - frameSize step frameSize) {
                val sample =
                    if (bits == 16) {
                        val low = block[offset + if (format.isBigEndian) 1 else 0].toInt() and 0xFF
                        val high = block[offset + if (format.isBigEndian) 0 else 1].toInt()
                        ((high shl 8) or low) / 32768f
                    } else if (format.encoding == AudioFormat.Encoding.PCM_UNSIGNED) {
                        ((block[offset].toInt() and 0xFF) - 128) / 128f
                    } else {
                        block[offset] / 128f
                    }
                while (first < WAVEFORM_BARS && to[first] <= frame) first++
                var bar = first
                while (bar < WAVEFORM_BARS && from[bar] <= frame) {
                    if (frame < to[bar]) peaks[bar] = maxOf(peaks[bar], abs(sample))
                    bar++
                }
                frame++
            }
            if (read < block.size) break
        }
        return peaks.toList()
    }

    private const val WAVEFORM_BLOCK_SIZE = 64 * 1024

    private val WAVEFORM_BACKGROUND = Color(0xF7, 0xF5, 0xEF)

    private val WAVEFORM_INK = Color(0x2B, 0x2A, 0x27)
}
