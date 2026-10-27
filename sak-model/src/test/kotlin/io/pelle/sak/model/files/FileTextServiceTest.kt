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

    // writes down every recording as the same sentence
    private val transcriber =
        object : SpeechTranscriber {
            val recordings = mutableListOf<String>()

            override fun transcribe(
                audio: ByteArray,
                contentType: String,
            ): String {
                recordings.add(contentType)
                return "Buy milk on the way home."
            }
        }

    private val service by lazy { FileTextService(files, recognizer, transcriber) }

    private fun photo(content: String) =
        (files.store((FileServiceTest.JPEG + content.toByteArray()).inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file.id

    @Test
    fun `discovers the text in an image once and keeps it with the file`() {
        val receipt = photo("Total 12.50")

        service.discover(receipt) shouldBe DiscoverTextResult.Discovered("Total 12.50")
        service.discover(receipt) shouldBe DiscoverTextResult.Discovered("Total 12.50")
        recognizer.images.size shouldBe 1
        files.byId(receipt)!!.discovered shouldBe "Total 12.50"
        service.discover(4711) shouldBe DiscoverTextResult.NotFound
    }

    @Test
    fun `tells when the text can not be discovered and tries again next time`() {
        val failing = photo("fail")

        service.discover(failing).shouldBeInstanceOf<DiscoverTextResult.Failed>()
        service.discover(failing).shouldBeInstanceOf<DiscoverTextResult.Failed>()
        recognizer.images.size shouldBe 2
        files.byId(failing)!!.discovered shouldBe null

        FileTextService(files, null).discover(failing) shouldBe DiscoverTextResult.NotConfigured
    }

    @Test
    fun `transcribes recordings once and keeps the text with the file`() {
        val recording = (files.store(FileServiceTest.wav().inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file.id

        service.discover(recording) shouldBe DiscoverTextResult.Discovered("Buy milk on the way home.")
        service.discover(recording) shouldBe DiscoverTextResult.Discovered("Buy milk on the way home.")
        transcriber.recordings shouldBe listOf("audio/wav")
        recognizer.images shouldBe emptyList()

        FileTextService(files, recognizer, null).discover(photo("another")).shouldBeInstanceOf<DiscoverTextResult.Discovered>()
        val unheard = (files.store(FileServiceTest.wav(2).inputStream(), "pelle@pelle.io") as StoreFileResult.Stored).file.id
        FileTextService(files, recognizer, null).discover(unheard) shouldBe DiscoverTextResult.NotConfigured
    }

    @Test
    fun `discovers text in images and recordings only`() {
        val file = photo("not an image after all")
        database.dsl.update(FILES).set(FILES.CONTENT_TYPE, "application/pdf").where(FILES.ID.eq(file)).execute()

        service.discover(file) shouldBe DiscoverTextResult.Unsupported
        recognizer.images shouldBe emptyList()
    }
}
