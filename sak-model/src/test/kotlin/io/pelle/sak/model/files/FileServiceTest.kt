package io.pelle.sak.model.files

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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

    companion object {
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
