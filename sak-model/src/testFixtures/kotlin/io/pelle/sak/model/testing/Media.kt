package io.pelle.sak.model.testing

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

// the images and recordings the tests store, as the apps upload them: without metadata
object Media {
    // black, of the size
    fun jpeg(
        width: Int = 4,
        height: Int = 3,
    ) = encoded(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg")

    fun png(
        width: Int = 4,
        height: Int = 3,
    ) = encoded(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png")

    // filled with orange, in the format ImageIO writes (jpg, png, gif)
    fun image(
        width: Int,
        height: Int,
        format: String,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.ORANGE
            fillRect(0, 0, width, height)
            dispose()
        }
        return encoded(image, format)
    }

    val JPEG = jpeg()

    val PNG = png()

    // the header of an extended webp of the size, which is all the storage reads of it
    fun webp(
        width: Int,
        height: Int,
    ): ByteArray {
        fun uint24(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte())
        return "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + byteArrayOf(10, 0, 0, 0) + ByteArray(4) +
            uint24(width - 1) + uint24(height - 1)
    }

    // a mono 16 bit wav of a tone, quiet in the first half and loud in the second
    fun wav(
        seconds: Int = 1,
        rate: Float = 16_000f,
    ): ByteArray {
        val frames = (rate * seconds).toInt()
        val pcm =
            ByteArray(frames * 2).also { bytes ->
                for (frame in 0 until frames) {
                    val loudness = if (frame < frames / 2) 0.1 else 0.9
                    val sample = (sin(2 * PI * 440 * frame / rate) * loudness * Short.MAX_VALUE).toInt()
                    bytes[frame * 2] = (sample and 0xFF).toByte()
                    bytes[frame * 2 + 1] = (sample shr 8).toByte()
                }
            }
        val format = AudioFormat(rate, 16, 1, true, false)
        return ByteArrayOutputStream()
            .also { AudioSystem.write(AudioInputStream(pcm.inputStream(), format, frames.toLong()), AudioFileFormat.Type.WAVE, it) }
            .toByteArray()
    }

    private fun encoded(
        image: BufferedImage,
        format: String,
    ) = ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
}
