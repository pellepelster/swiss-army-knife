package io.pelle.sak.model.files

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

class FileServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC)

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val dir by lazy { tempDir.resolve("files") }

    private val service by lazy { FileService(database, dir, clock, maxSize = 1024) }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `stores a file under the sha-256 of its content with its type, size and uploader`() {
        val content = JPEG + "a photo".toByteArray()

        val file = service.store(content.inputStream(), "pelle@pelle.io").shouldBeInstanceOf<StoreFileResult.Stored>().file
        file shouldBe StoredFile(sha256(content), "image/jpeg", content.size.toLong(), "pelle@pelle.io", clock.instant(), clock.instant())
        service.path(file) shouldBe dir.resolve(file.sha256.take(2)).resolve(file.sha256)
        Files.readAllBytes(service.path(file)) shouldBe content
        service.bySha256(file.sha256) shouldBe file
        service.existing(listOf(file.sha256, "0".repeat(64), "no hash")) shouldBe setOf(file.sha256)
    }

    @Test
    fun `stores the same content once, it stays the file of the first uploader`() {
        val content = PNG + "same".toByteArray()
        val first = (service.store(content.inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file

        (service.store(content.inputStream(), "other@example.org") as StoreFileResult.Stored).file shouldBe first
        Files.list(dir).use { it.toList() }.map { it.fileName.toString() } shouldBe listOf(first.sha256.take(2))
    }

    @Test
    fun `takes images only, told by their first bytes, and nothing larger than the limit`() {
        service.store("<html>".toByteArray().inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.UnsupportedType
        service.store(ByteArray(0).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.UnsupportedType
        service.store((JPEG + ByteArray(1024)).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.TooLarge
        service.store(("GIF89a" + "x").toByteArray().inputStream(), "pelle@pelle.io").shouldBeInstanceOf<StoreFileResult.Stored>()
            .file.contentType shouldBe "image/gif"
        service.store("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray().inputStream(), "pelle@pelle.io")
            .shouldBeInstanceOf<StoreFileResult.Stored>().file.contentType shouldBe "image/webp"
        // nothing stays behind of the rejected ones
        Files.list(dir).use { it.toList() }.none { it.fileName.toString().startsWith("upload-") } shouldBe true
        service.bySha256("../../sak.db") shouldBe null
    }

    private fun image(
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
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    @Test
    fun `makes a thumbnail of an image when it is first asked for and keeps it`() {
        val file = (service(maxSize = 10_000_000).store(image(2048, 1365, "jpg").inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file

        val thumbnail = service(maxSize = 10_000_000).thumbnail(file)!!
        thumbnail.parent shouldBe service.path(file).parent
        ImageIO.read(thumbnail.toFile()).let {
            it.width shouldBe 480
            it.height shouldBe 319
            Color(it.getRGB(240, 160)).let { pixel -> (pixel.red > 200 && pixel.blue < 80) shouldBe true }
        }
        val modified = Files.getLastModifiedTime(thumbnail)
        service.thumbnail(file) shouldBe thumbnail
        Files.getLastModifiedTime(thumbnail) shouldBe modified
    }

    @Test
    fun `small images keep their size, transparent ones get a white background`() {
        val file = (service(maxSize = 10_000_000).store(image(200, 100, "png").inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        ImageIO.read(service.thumbnail(file)!!.toFile()).let {
            it.width shouldBe 200
            it.height shouldBe 100
        }
    }

    @Test
    fun `an image that can not be read has no thumbnail`() {
        val file = (service.store((JPEG + "broken".toByteArray()).inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        service.thumbnail(file) shouldBe null
    }

    @Test
    fun `stores wav recordings, their thumbnail is their waveform`() {
        val file =
            (service(maxSize = 10_000_000).store(wav(seconds = 2).inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        file.contentType shouldBe "audio/wav"

        ImageIO.read(service.thumbnail(file)!!.toFile()).let {
            it.width shouldBe 480
            it.height shouldBe 360
            // the loud second half has higher bars than the quiet first half
            fun inked(x: Int) = (0 until it.height).count { y -> Color(it.getRGB(x, y)).red < 128 }
            inked(it.width * 3 / 4) shouldBeGreaterThan inked(it.width / 4)
        }
    }

    private fun service(maxSize: Long) = FileService(database, dir, clock, maxSize)

    companion object {
        // a 16 kHz mono 16 bit wav of a tone, quiet in the first half and loud in the second
        fun wav(seconds: Int = 1): ByteArray {
            val rate = 16_000f
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

        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
