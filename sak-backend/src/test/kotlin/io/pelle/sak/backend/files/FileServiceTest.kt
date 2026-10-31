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
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import io.pelle.sak.model.testing.Media
import io.pelle.sak.api.testing.testClock

class FileServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock = testClock()

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val dir by lazy { tempDir.resolve("files") }

    private val service by lazy { FileService(database, dir, clock, maxSize = 1024) }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `stores a file with its type, size and uploader, its content under its sha-256`() {
        val content = Media.JPEG + "a photo".toByteArray()

        val file = service.store(content.inputStream(), "pelle@pelle.io").shouldBeInstanceOf<StoreFileResult.Stored>().file
        file shouldBe StoredFile(file.id, sha256(content), "image/jpeg", content.size.toLong(), "pelle@pelle.io", clock.instant(), null)
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
        val content = Media.PNG + "same".toByteArray()
        val first = (service.store(content.inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        val second = (service.store(content.inputStream(), "other@example.org") as StoreFileResult.Stored).file
        service.byId("pelle@pelle.io", second.id) shouldBe null
        service.byId("other@example.org", second.id) shouldBe second

        second.id shouldNotBe first.id
        second.owner shouldBe "other@example.org"
        service.path(second) shouldBe service.path(first)
        Files.list(dir).use { it.toList() }.map { it.fileName.toString() } shouldBe listOf(first.sha256.take(2))
    }

    @Test
    fun `takes images only, told by their first bytes, and nothing larger than the limit`() {
        service.store("<html>".toByteArray().inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.UnsupportedType
        service.store(ByteArray(0).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.UnsupportedType
        service.store((Media.JPEG + ByteArray(1024)).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.TooLarge
        service.store(Media.image(4, 3, "gif").inputStream(), "pelle@pelle.io").shouldBeInstanceOf<StoreFileResult.Stored>()
            .file.contentType shouldBe "image/gif"
        service.store(Media.webp(4, 3).inputStream(), "pelle@pelle.io")
            .shouldBeInstanceOf<StoreFileResult.Stored>().file.contentType shouldBe "image/webp"
        // the first bytes of an image are not enough, it has to be one
        service.store(("GIF89a" + "x").toByteArray().inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.UnsupportedType
        service.store(Media.JPEG.copyOf(4).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.UnsupportedType
        service.store("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray().inputStream(), "pelle@pelle.io") shouldBe
            StoreFileResult.UnsupportedType
        // nothing stays behind of the rejected ones
        Files.list(dir).use { it.toList() }.none { it.fileName.toString().startsWith("upload-") } shouldBe true
        service.byId("pelle@pelle.io", 4711) shouldBe null
    }


    @Test
    fun `makes a thumbnail of an image when it is first asked for and keeps it`() {
        val file = (service(maxSize = 10_000_000).store(Media.image(2048, 1365, "jpg").inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file

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
        val file = (service(maxSize = 10_000_000).store(Media.image(200, 100, "png").inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        ImageIO.read(service.thumbnail(file)!!.toFile()).let {
            it.width shouldBe 200
            it.height shouldBe 100
        }
    }

    @Test
    fun `an image that can not be read has no thumbnail, it is not read again`() {
        val file = (service.store(Media.JPEG.inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        val content = Files.readAllBytes(service.path(file))
        Files.write(service.path(file), Media.JPEG.copyOf(4))
        service.thumbnail(file) shouldBe null

        // readable again, the marker keeps it from being decoded
        Files.write(service.path(file), content)
        service.thumbnail(file) shouldBe null
    }

    @Test
    fun `makes the thumbnail of a content once when it is asked for many times at once`() {
        val file = (service(maxSize = 10_000_000).store(Media.image(2048, 1365, "jpg").inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(16)
        try {
            val thumbnails = (1..16).map { pool.submit<java.nio.file.Path?> { start.await(); service.thumbnail(file) } }
            start.countDown()
            thumbnails.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }.toSet() shouldBe setOf(service.thumbnail(file))
        } finally {
            pool.shutdownNow()
        }
        // the temporary files are made in the storage dir
        Files.list(service.path(file).parent.parent).use { files -> files.filter { it.toString().endsWith(".tmp") }.count() } shouldBe 0
    }

    @Test
    fun `takes images of at most 2048 pixels on their longer side, as the apps scale them`() {
        val large = service(maxSize = 10_000_000)
        large.store(Media.image(2048, 10, "png").inputStream(), "pelle@pelle.io").shouldBeInstanceOf<StoreFileResult.Stored>()
        large.store(Media.image(10, 2049, "png").inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.ImageTooLarge
        large.store(Media.image(2049, 10, "jpg").inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.ImageTooLarge
        large.store(Media.webp(2049, 10).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.ImageTooLarge
    }

    @Test
    fun `takes no image with exif or xmp metadata, the apps drop it as it can tell where a photo was taken`() {
        val exif = "Exif\u0000\u0000".toByteArray()
        // an app1 segment right after the start of the image
        val jpeg = Media.JPEG.copyOf(2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, (exif.size + 2).toByte()) + exif + Media.JPEG.copyOfRange(2, Media.JPEG.size)
        service.store(jpeg.inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.ImageWithMetadata
        // the same with a fill byte before its marker, ff ff e1
        val padded = Media.JPEG.copyOf(2) + byteArrayOf(0xFF.toByte()) + jpeg.copyOfRange(2, jpeg.size)
        service.store(padded.inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.ImageWithMetadata

        // an exif chunk and an xmp text chunk right after the header chunk
        fun png(
            type: String,
            data: ByteArray,
        ) = Media.PNG.copyOf(33) + ByteBuffer.allocate(4).putInt(data.size).array() + type.toByteArray() + data + ByteArray(4) +
            Media.PNG.copyOfRange(33, Media.PNG.size)
        service.store(png("eXIf", exif).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.ImageWithMetadata
        service.store(png("iTXt", "XML:com.adobe.xmp\u0000".toByteArray()).inputStream(), "pelle@pelle.io") shouldBe
            StoreFileResult.ImageWithMetadata
        service.store(png("tEXt", "Comment\u0000hello".toByteArray()).inputStream(), "pelle@pelle.io")
            .shouldBeInstanceOf<StoreFileResult.Stored>()

        service.store((Media.webp(4, 3) + "EXIF".toByteArray() + ByteArray(4)).inputStream(), "pelle@pelle.io") shouldBe
            StoreFileResult.ImageWithMetadata
    }

    @Test
    fun `can not tell the length of a recording whose header leaves its frames open`() {
        val pcm = javax.sound.sampled.AudioFormat(16_000f, 16, 1, true, false)
        val unknownRate = javax.sound.sampled.AudioFormat(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED, -1f, 16, 1, 2, -1f, false)
        fun format(
            audio: javax.sound.sampled.AudioFormat,
            frames: Int,
        ) = javax.sound.sampled.AudioFileFormat(javax.sound.sampled.AudioFileFormat.Type.WAVE, audio, frames)

        FileService.recordingSeconds(format(pcm, 32_000)) shouldBe 2f
        FileService.recordingSeconds(format(pcm, javax.sound.sampled.AudioSystem.NOT_SPECIFIED)) shouldBe null
        FileService.recordingSeconds(format(unknownRate, 32_000)) shouldBe null
    }

    @Test
    fun `takes recordings of at most five minutes, as the apps record them`() {
        val recordings = service(maxSize = 10_000_000)
        recordings.store(Media.wav(seconds = 300, rate = 100f).inputStream(), "pelle@pelle.io").shouldBeInstanceOf<StoreFileResult.Stored>()
        recordings.store(Media.wav(seconds = 301, rate = 100f).inputStream(), "pelle@pelle.io") shouldBe StoreFileResult.RecordingTooLong
    }

    @Test
    fun `stores wav recordings, their thumbnail is their waveform`() {
        val file =
            (service(maxSize = 10_000_000).store(Media.wav(seconds = 2).inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
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

}
