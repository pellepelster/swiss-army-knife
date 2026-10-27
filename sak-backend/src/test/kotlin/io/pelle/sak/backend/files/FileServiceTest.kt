package io.pelle.sak.backend.files

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.files.StoredFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
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
    fun `stores a file with its type, size and uploader, its content under its sha-256`() {
        val content = JPEG + "a photo".toByteArray()

        val file = service.store(content.inputStream(), "pelle@pelle.io").shouldBeInstanceOf<StoreFileResult.Stored>().file
        file shouldBe StoredFile(file.id, sha256(content), "image/jpeg", content.size.toLong(), "pelle@pelle.io", clock.instant(), clock.instant())
        service.path(file) shouldBe dir.resolve(file.sha256.take(2)).resolve(file.sha256)
        Files.readAllBytes(service.path(file)) shouldBe content
        service.byId("pelle@pelle.io", file.id) shouldBe file
        service.byIds("pelle@pelle.io", listOf(file.id, 4711)) shouldBe mapOf(file.id to file)
        // only to the address that uploaded it
        service.byId("other@example.org", file.id) shouldBe null
        service.byIds("other@example.org", listOf(file.id)) shouldBe emptyMap()
    }

    @Test
    fun `every upload is a file of its own, the same content is stored once`() {
        val content = PNG + "same".toByteArray()
        val first = (service.store(content.inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        val second = (service.store(content.inputStream(), "other@example.org") as StoreFileResult.Stored).file
        service.byId("pelle@pelle.io", second.id) shouldBe null
        service.byId("other@example.org", second.id) shouldBe second

        second.id shouldNotBe first.id
        second.uploadedBy shouldBe "other@example.org"
        service.path(second) shouldBe service.path(first)
        Files.list(dir).use { it.toList() }.map { it.fileName.toString() } shouldBe listOf(first.sha256.take(2))
    }

    @Test
    fun `takes images only, told by their first bytes, and nothing larger than the limit`() {
        service.store("<html>".toByteArray().inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.UnsupportedType
        service.store(ByteArray(0).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.UnsupportedType
        service.store((JPEG + ByteArray(1024)).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.TooLarge
        service.store(image(4, 3, "gif").inputStream(), "pelle@pelle.io").shouldBeInstanceOf<StoreFileResult.Stored>()
            .file.contentType shouldBe "image/gif"
        service.store(webp(4, 3).inputStream(), "pelle@pelle.io")
            .shouldBeInstanceOf<StoreFileResult.Stored>().file.contentType shouldBe "image/webp"
        // the first bytes of an image are not enough, it has to be one
        service.store(("GIF89a" + "x").toByteArray().inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.UnsupportedType
        service.store(JPEG.copyOf(4).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.UnsupportedType
        service.store("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray().inputStream(), "pelle@pelle.io") shouldBe
            StoreFileResult.UnsupportedType
        // nothing stays behind of the rejected ones
        Files.list(dir).use { it.toList() }.none { it.fileName.toString().startsWith("upload-") } shouldBe true
        service.byId("pelle@pelle.io", 4711) shouldBe null
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
        val file = (service.store(JPEG.inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        Files.write(service.path(file), JPEG.copyOf(4))
        service.thumbnail(file) shouldBe null
    }

    @Test
    fun `takes images of at most 2048 pixels on their longer side, as the apps scale them`() {
        val large = service(maxSize = 10_000_000)
        large.store(image(2048, 10, "png").inputStream(), "pelle@pelle.io").shouldBeInstanceOf<StoreFileResult.Stored>()
        large.store(image(10, 2049, "png").inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.ImageTooLarge
        large.store(image(2049, 10, "jpg").inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.ImageTooLarge
        large.store(webp(2049, 10).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.ImageTooLarge
    }

    @Test
    fun `takes no image with exif or xmp metadata, the apps drop it as it can tell where a photo was taken`() {
        val exif = "Exif\u0000\u0000".toByteArray()
        // an app1 segment right after the start of the image
        val jpeg = JPEG.copyOf(2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, (exif.size + 2).toByte()) + exif + JPEG.copyOfRange(2, JPEG.size)
        service.store(jpeg.inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.ImageWithMetadata

        // an exif chunk and an xmp text chunk right after the header chunk
        fun png(
            type: String,
            data: ByteArray,
        ) = PNG.copyOf(33) + ByteBuffer.allocate(4).putInt(data.size).array() + type.toByteArray() + data + ByteArray(4) +
            PNG.copyOfRange(33, PNG.size)
        service.store(png("eXIf", exif).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.ImageWithMetadata
        service.store(png("iTXt", "XML:com.adobe.xmp\u0000".toByteArray()).inputStream(), "pelle@pelle.io") shouldBe
            StoreFileResult.ImageWithMetadata
        service.store(png("tEXt", "Comment\u0000hello".toByteArray()).inputStream(), "pelle@pelle.io")
            .shouldBeInstanceOf<StoreFileResult.Stored>()

        service.store((webp(4, 3) + "EXIF".toByteArray() + ByteArray(4)).inputStream(), "pelle@pelle.io") shouldBe
            StoreFileResult.ImageWithMetadata
    }

    @Test
    fun `takes recordings of at most five minutes, as the apps record them`() {
        val recordings = service(maxSize = 10_000_000)
        recordings.store(wav(seconds = 300, rate = 100f).inputStream(), "pelle@pelle.io").shouldBeInstanceOf<StoreFileResult.Stored>()
        recordings.store(wav(seconds = 301, rate = 100f).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.RecordingTooLong
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

        private fun encoded(format: String) =
            ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), format, it) }.toByteArray()

        // small images as the apps upload them, without metadata
        val JPEG = encoded("jpg")

        val PNG = encoded("png")

        // the header of an extended webp of the size, which is all the storage reads of it
        fun webp(
            width: Int,
            height: Int,
        ): ByteArray {
            fun uint24(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte())
            return "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + byteArrayOf(10, 0, 0, 0) + ByteArray(4) +
                uint24(width - 1) + uint24(height - 1)
        }
    }
}
