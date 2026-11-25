package io.pelle.sak.backend.files

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.files.FileRepository
import io.pelle.sak.openrouter.SpeechTranscriber
import io.pelle.sak.openrouter.TextRecognitionException
import io.pelle.sak.openrouter.TextRecognizer
import io.pelle.sak.api.storage.model.BlobContent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.io.IOException
import java.nio.file.Files
import io.pelle.sak.model.testing.Media
import io.pelle.sak.api.testing.testClock

private const val PELLE = "pelle@pelle.io"

class FileTextServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock = testClock()

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val files by lazy { FileService(database, tempDir.resolve("files"), clock) }

    // reads the bytes after the jpeg header as the text, "fail" fails
    private val recognizer =
        object : TextRecognizer {
            val images = mutableListOf<ByteArray>()

            override fun recognize(
                image: BlobContent,
                contentType: String,
            ): String {
                val bytes = image.open().use { it.readAllBytes() }
                images.add(bytes)
                val text = String(bytes.drop(Media.JPEG.size).toByteArray())
                if (text == "fail") throw TextRecognitionException("the model failed")
                return " $text\n"
            }
        }

    // writes down every recording as the same sentence
    private val transcriber =
        object : SpeechTranscriber {
            val recordings = mutableListOf<String>()

            override fun transcribe(
                audio: BlobContent,
                contentType: String,
            ): String {
                recordings.add(contentType)
                return "Buy milk on the way home."
            }
        }

    // the AI features in the settings
    private var aiEnabled = true

    private val service by lazy { FileTextService(files, { recognizer }, { transcriber }) { aiEnabled } }

    private fun photo(content: String) =
        (files.store((Media.JPEG + content.toByteArray()).inputStream(), PELLE) as FileStoreResult.Stored).file.id

    @Test
    fun `the limit is asked only when the file goes to the AI provider`() {
        val receipt = photo("Total 12.50")
        var asked = 0
        val counting = { asked++; true }

        // unknown, not an image or a recording, AI turned off: nothing goes out, nothing is counted
        service.discover(PELLE, 4711, counting) shouldBe FileTextDiscoverResult.NotFound
        aiEnabled = false
        service.discover(PELLE, receipt, counting) shouldBe FileTextDiscoverResult.Disabled
        aiEnabled = true
        asked shouldBe 0

        service.discover(PELLE, receipt, counting) shouldBe FileTextDiscoverResult.Discovered("Total 12.50")
        // the known text is answered without asking again
        service.discover(PELLE, receipt, counting) shouldBe FileTextDiscoverResult.Discovered("Total 12.50")
        asked shouldBe 1

        // over the limit: the file does not go out
        service.discover(PELLE, photo("Total 3.20")) { false } shouldBe FileTextDiscoverResult.Limited
        recognizer.images.size shouldBe 1
    }

    @Test
    fun `a text can be discovered only with the AI turned on by the owner and a provider set up`() {
        // turned on by one user, not by the other
        val ofOne: (String) -> Boolean = { it == "one@pelle.io" }
        FileTextService(files, { recognizer }, { null }, ofOne).available("one@pelle.io") shouldBe true
        FileTextService(files, { null }, { transcriber }, ofOne).available("one@pelle.io") shouldBe true
        FileTextService(files, { recognizer }, { transcriber }, ofOne).available("other@pelle.io") shouldBe false
        FileTextService(files, { recognizer }, { transcriber }, ofOne).configured() shouldBe true
        FileTextService(files, { null }, { null }, ofOne).available("one@pelle.io") shouldBe false
        FileTextService(files, { null }, { null }, ofOne).configured() shouldBe false
    }

    @Test
    fun `discovers the text in an image once and keeps it with the file`() {
        val receipt = photo("Total 12.50")

        service.discover(PELLE, receipt) shouldBe FileTextDiscoverResult.Discovered("Total 12.50")
        service.discover(PELLE, receipt) shouldBe FileTextDiscoverResult.Discovered("Total 12.50")
        recognizer.images.size shouldBe 1
        files.byId(PELLE, receipt)!!.discovered shouldBe "Total 12.50"
        service.discover(PELLE, 4711) shouldBe FileTextDiscoverResult.NotFound
        // the text once discovered is there without a recognizer too
        FileTextService(files, { null }, { null }) { true }.discover(PELLE, receipt) shouldBe FileTextDiscoverResult.Discovered("Total 12.50")
    }

    @Test
    fun `sends nothing while the AI features are turned off, a text once discovered is still there`() {
        val receipt = photo("Total 12.50")
        service.discover(PELLE, receipt)

        aiEnabled = false
        service.discover(PELLE, photo("another")) shouldBe FileTextDiscoverResult.Disabled
        service.discover(PELLE, receipt) shouldBe FileTextDiscoverResult.Discovered("Total 12.50")
        recognizer.images.size shouldBe 1
    }

    @Test
    fun `discovers the text in the files of their uploader only`() {
        val receipt = photo("Total 12.50")

        service.discover("other@example.org", receipt) shouldBe FileTextDiscoverResult.NotFound
        recognizer.images shouldBe emptyList()
    }

    @Test
    fun `tells when the text can not be discovered and tries again next time`() {
        val failing = photo("fail")

        service.discover(PELLE, failing).shouldBeInstanceOf<FileTextDiscoverResult.Failed>()
        service.discover(PELLE, failing).shouldBeInstanceOf<FileTextDiscoverResult.Failed>()
        recognizer.images.size shouldBe 2
        files.byId(PELLE, failing)!!.discovered shouldBe null

        FileTextService(files, { null }, { null }) { true }.discover(PELLE, failing) shouldBe FileTextDiscoverResult.NotConfigured
    }

    @Test
    fun `transcribes recordings once and keeps the text with the file`() {
        val recording = (files.store(Media.wav().inputStream(), PELLE) as FileStoreResult.Stored).file.id

        service.discover(PELLE, recording) shouldBe FileTextDiscoverResult.Discovered("Buy milk on the way home.")
        service.discover(PELLE, recording) shouldBe FileTextDiscoverResult.Discovered("Buy milk on the way home.")
        transcriber.recordings shouldBe listOf("audio/wav")
        recognizer.images shouldBe emptyList()

        FileTextService(files, { recognizer }, { null }) { true }.discover(PELLE, photo("another")).shouldBeInstanceOf<FileTextDiscoverResult.Discovered>()
        val unheard = (files.store(Media.wav(2).inputStream(), PELLE) as FileStoreResult.Stored).file.id
        FileTextService(files, { recognizer }, { null }) { true }.discover(PELLE, unheard) shouldBe FileTextDiscoverResult.NotConfigured
    }

    @Test
    fun `discovers text in images and recordings only`() {
        // the storage takes no such file, the row is there anyway
        val file = FileRepository(database).insert("application/pdf", 3, PELLE).id

        service.discover(PELLE, file) shouldBe FileTextDiscoverResult.Unsupported
        recognizer.images shouldBe emptyList()
    }

    @Test
    fun `tells when the content of a file can not be read`() {
        val lost = photo("lost")
        files.byId(PELLE, lost)!!.resourceId.let { Files.delete(tempDir.resolve("files").resolve(it.value).resolve("content")) }

        service.discover(PELLE, lost).shouldBeInstanceOf<FileTextDiscoverResult.Failed>().cause.shouldBeInstanceOf<IOException>()
        recognizer.images shouldBe emptyList()
    }
}
