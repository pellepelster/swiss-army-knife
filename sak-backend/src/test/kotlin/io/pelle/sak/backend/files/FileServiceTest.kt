package io.pelle.sak.backend.files

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.files.StoredFile
import io.pelle.sak.storage.BlobPath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
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

    // where the storage keeps a content on disk, the layout of the deployments that are there
    private fun stored(sha256: String, suffix: String = "") = dir.resolve(sha256.take(2)).resolve(sha256 + suffix)

    private fun stored(file: StoredFile) = stored(file.sha256)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `stores a file with its type, size and uploader, its content under its sha-256`() {
        val content = Media.JPEG + "a photo".toByteArray()

        val file = service.store(content.inputStream(), "pelle@pelle.io").shouldBeInstanceOf<StoreFileResult.Stored>().file
        file shouldBe StoredFile(file.id, sha256(content), "image/jpeg", content.size.toLong(), "pelle@pelle.io", clock.instant(), null)
        Files.readAllBytes(stored(file)) shouldBe content
        service.content(file)!!.let { it.size shouldBe content.size.toLong(); it.open().use { stream -> stream.readAllBytes() } shouldBe content }
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
        second.sha256 shouldBe first.sha256
        // the upload of the second went, nothing but the content and the empty staging directory stay
        Files.list(dir).use { it.toList() }.map { it.fileName.toString() }.toSet() shouldBe setOf(first.sha256.take(2), BlobPath.STAGING_DIRECTORY)
        Files.list(dir.resolve(BlobPath.STAGING_DIRECTORY)).use { it.count() } shouldBe 0
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

        val thumbnail = stored(file.sha256, ".thumbnail.jpg")
        service(maxSize = 10_000_000).thumbnail(file)!!.open().use { ImageIO.read(it) }.let {
            it.width shouldBe 480
            it.height shouldBe 319
            Color(it.getRGB(240, 160)).let { pixel -> (pixel.red > 200 && pixel.blue < 80) shouldBe true }
        }
        val modified = Files.getLastModifiedTime(thumbnail)
        service.thumbnail(file)!!.size shouldBe Files.size(thumbnail)
        Files.getLastModifiedTime(thumbnail) shouldBe modified
    }

    @Test
    fun `small images keep their size, transparent ones get a white background`() {
        val file = (service(maxSize = 10_000_000).store(Media.image(200, 100, "png").inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        service.thumbnail(file)!!.open().use { ImageIO.read(it) }.let {
            it.width shouldBe 200
            it.height shouldBe 100
        }
    }

    @Test
    fun `an image that can not be read has no thumbnail, it is not read again`() {
        val file = (service.store(Media.JPEG.inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        val content = Files.readAllBytes(stored(file))
        Files.write(stored(file), Media.JPEG.copyOf(4))
        service.thumbnail(file) shouldBe null

        // readable again, the marker keeps it from being decoded
        Files.write(stored(file), content)
        service.thumbnail(file) shouldBe null
    }

    @Test
    fun `makes the thumbnail of a content once when it is asked for many times at once`() {
        val file = (service(maxSize = 10_000_000).store(Media.image(2048, 1365, "jpg").inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(16)
        try {
            val thumbnails = (1..16).map { pool.submit<Long?> { start.await(); service.thumbnail(file)?.size } }
            start.countDown()
            thumbnails.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }.toSet() shouldBe setOf(Files.size(stored(file.sha256, ".thumbnail.jpg")))
        } finally {
            pool.shutdownNow()
        }
        // nothing is left staged
        Files.list(dir.resolve(BlobPath.STAGING_DIRECTORY)).use { it.count() } shouldBe 0
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

        service.thumbnail(file)!!.open().use { ImageIO.read(it) }.let {
            it.width shouldBe 480
            it.height shouldBe 360
            // the loud second half has higher bars than the quiet first half
            fun inked(x: Int) = (0 until it.height).count { y -> Color(it.getRGB(x, y)).red < 128 }
            inked(it.width * 3 / 4) shouldBeGreaterThan inked(it.width / 4)
        }
    }

    @Test
    fun `reads large images with fewer pixels, at least four times the size`() {
        FileService.subsampling(2048, 128) shouldBe 4
        FileService.subsampling(2048, 256) shouldBe 2
        FileService.subsampling(2048, 480) shouldBe 1
        FileService.subsampling(100, 128) shouldBe 1

        val file = (service(maxSize = 10_000_000).store(Media.image(2048, 1365, "jpg").inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file
        FileService.decode(service.content(file)!!, 128)!!.width shouldBe 512
        service.thumbnail(file, 128)!!.open().use { ImageIO.read(it) }.let {
            it.width shouldBe 128
            it.height shouldBe 85
            Color(it.getRGB(64, 42)).let { pixel -> (pixel.red > 200 && pixel.blue < 80) shouldBe true }
        }
    }

    private fun service(maxSize: Long) = FileService(database, dir, clock, maxSize)

}
