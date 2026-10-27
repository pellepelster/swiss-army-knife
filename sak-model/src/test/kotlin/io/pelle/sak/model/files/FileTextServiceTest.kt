package io.pelle.sak.model.files

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.FILES
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FileTextServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC)

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val files by lazy { FileService(database, tempDir.resolve("files"), clock) }

    // reads the bytes after the jpeg header as the text, "fail" fails
    private val recognizer =
        object : TextRecognizer {
            val images = mutableListOf<ByteArray>()

            override fun recognize(
                image: ByteArray,
                contentType: String,
            ): String {
                images.add(image)
                val text = String(image.drop(FileServiceTest.JPEG.size).toByteArray())
                if (text == "fail") throw TextRecognitionException("the model failed")
                return " $text\n"
            }
        }

    private val service by lazy { FileTextService(files, recognizer) }

    private fun photo(content: String) =
        (files.store((FileServiceTest.JPEG + content.toByteArray()).inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file.sha256

    @Test
    fun `discovers the text in an image once and keeps it with the file`() {
        val receipt = photo("Total 12.50")

        service.discover(receipt) shouldBe DiscoverTextResult.Discovered("Total 12.50")
        service.discover(receipt) shouldBe DiscoverTextResult.Discovered("Total 12.50")
        recognizer.images.size shouldBe 1
        files.bySha256(receipt)!!.discovered shouldBe "Total 12.50"
        service.discover("0".repeat(64)) shouldBe DiscoverTextResult.NotFound
    }

    @Test
    fun `tells when the text can not be discovered and tries again next time`() {
        val failing = photo("fail")

        service.discover(failing).shouldBeInstanceOf<DiscoverTextResult.Failed>()
        service.discover(failing).shouldBeInstanceOf<DiscoverTextResult.Failed>()
        recognizer.images.size shouldBe 2
        files.bySha256(failing)!!.discovered shouldBe null

        FileTextService(files, null).discover(failing) shouldBe DiscoverTextResult.NotConfigured
    }

    @Test
    fun `discovers text in images only`() {
        val file = photo("not an image after all")
        database.dsl.update(FILES).set(FILES.CONTENT_TYPE, "application/pdf").where(FILES.SHA256.eq(file)).execute()

        service.discover(file) shouldBe DiscoverTextResult.NotAnImage
        recognizer.images shouldBe emptyList()
    }
}
