package io.pelle.sak.model.notes

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.NOTES
import io.pelle.sak.model.files.FileService
import io.pelle.sak.model.files.FileServiceTest
import io.pelle.sak.model.files.StoreFileResult
import io.pelle.sak.model.labels.LabelService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class NoteServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock =
        object : Clock() {
            var now: Instant = Instant.parse("2026-10-27T08:00:00Z")

            override fun instant() = now

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId) = this
        }

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val labels by lazy { LabelService(database, clock) }

    private val files by lazy { FileService(database, tempDir.resolve("files"), clock) }

    private val service by lazy { NoteService(database, labels, files, clock) }

    private fun photo(content: String) =
        (files.store((FileServiceTest.JPEG + content.toByteArray()).inputStream(), PELLE) as StoreFileResult.Stored).file.sha256

    private fun create(
        text: String,
        color: String = "yellow",
        labelNames: List<String> = emptyList(),
        owner: String = PELLE,
    ) = service.create(owner, text, color, labelNames).shouldBeInstanceOf<SaveNoteResult.Saved>().note

    private fun later(minutes: Long = 1) {
        clock.now = clock.now.plusSeconds(minutes * 60)
    }

    @Test
    fun `creates notes with their labels, the unknown ones are created`() {
        labels.create("home")

        val note = create("Call the plumber", "PINK", listOf("Todo", "home", "todo"))
        note.color shouldBe NoteColor.PINK
        note.labels.map { it.name } shouldBe listOf("home", "todo")
        note.createdAt shouldBe clock.now
        note.updatedAt shouldBe clock.now
        service.byId(PELLE, note.id) shouldBe note
        labels.page(0, 10).items.map { it.name } shouldBe listOf("home", "todo")
    }

    @Test
    fun `rejects notes with every problem they have and creates no labels for them`() {
        service.create(PELLE, "x".repeat(10_001), "purple", listOf("ok", " ")) shouldBe
            SaveNoteResult.Invalid(setOf(NoteProblem.TEXT_TOO_LONG, NoteProblem.INVALID_COLOR, NoteProblem.INVALID_LABEL))
        service.create(PELLE, "fine", "purple", listOf("ok")) shouldBe SaveNoteResult.Invalid(setOf(NoteProblem.INVALID_COLOR))
        labels.byName("ok") shouldBe null
        create("x".repeat(10_000))
    }

    @Test
    fun `a note has at most ten labels, the same one written differently counts once`() {
        val ten = (1..10).map { "label $it" }
        create("ten", labelNames = ten + "LABEL 1").labels.size shouldBe 10
        service.create(PELLE, "eleven", "yellow", ten + "label 11") shouldBe SaveNoteResult.Invalid(setOf(NoteProblem.TOO_MANY_LABELS))
        labels.byName("label 11") shouldBe null
    }

    @Test
    fun `pages the newest notes first, of all or of one label`() {
        val milk = create("milk", labelNames = listOf("shopping"))
        later()
        val plumber = create("plumber", labelNames = listOf("home", "todo"))
        later()
        val app = create("app", labelNames = listOf("todo"))

        service.page(PELLE, 0, 2).let {
            it.items.map { note -> note.id } shouldBe listOf(app.id, plumber.id)
            it.total shouldBe 3
        }
        service.page(PELLE, 2, 2).items shouldBe listOf(milk)
        service.page(PELLE, 0, 10, "TODO").let {
            it.items.map { note -> note.text } shouldBe listOf("app", "plumber")
            it.total shouldBe 2
        }
        service.page(PELLE, 0, 10, "unknown") shouldBe io.pelle.sak.model.Slice(emptyList(), 0)
    }

    @Test
    fun `replaces text, color and labels and tells when`() {
        val note = create("milk", labelNames = listOf("shopping", "todo"))
        later()

        val updated = service.update(PELLE, note.id, "milk and bread", "green", listOf("shopping", "weekend"))
        updated.shouldBeInstanceOf<SaveNoteResult.Saved>().note.let {
            it.text shouldBe "milk and bread"
            it.color shouldBe NoteColor.GREEN
            it.labels.map { label -> label.name } shouldBe listOf("shopping", "weekend")
            it.createdAt shouldBe note.createdAt
            it.updatedAt shouldBe clock.now
        }
        service.byId(PELLE, note.id) shouldBe updated.note
        service.page(PELLE, 0, 10, "todo").total shouldBe 0

        service.update(PELLE, note.id, "", "blue", listOf("")) shouldBe SaveNoteResult.Invalid(setOf(NoteProblem.INVALID_LABEL))
        service.update(PELLE, 4711, "", "blue") shouldBe SaveNoteResult.NotFound
    }

    @Test
    fun `notes belong to the user that created them, nobody else sees or changes them`() {
        val mine = create("mine", labelNames = listOf("todo"))
        val theirs = create("theirs", labelNames = listOf("todo"), owner = OTHER)
        mine.owner shouldBe PELLE

        service.page(PELLE, 0, 10).items shouldBe listOf(mine)
        service.page(OTHER, 0, 10, "todo").items shouldBe listOf(theirs)
        service.byId(PELLE, theirs.id) shouldBe null
        service.update(PELLE, theirs.id, "taken", "pink") shouldBe SaveNoteResult.NotFound
        service.delete(PELLE, theirs.id) shouldBe DeleteNoteResult.NotFound
        service.byId(OTHER, theirs.id) shouldBe theirs
    }

    @Test
    fun `keeps the photos of a note in their order, files of the file storage`() {
        val beach = photo("beach")
        val sunset = photo("sunset")

        val note = service.create(PELLE, "holidays", "blue", photos = listOf(sunset, beach, sunset))
        note.shouldBeInstanceOf<SaveNoteResult.Saved>().note.photos shouldBe listOf(sunset, beach)
        service.byId(PELLE, note.note.id)!!.photos shouldBe listOf(sunset, beach)
        service.page(PELLE, 0, 10).items.single().photos shouldBe listOf(sunset, beach)

        service.update(PELLE, note.note.id, "holidays", "blue", photos = listOf(beach))
            .shouldBeInstanceOf<SaveNoteResult.Saved>().note.photos shouldBe listOf(beach)
        service.byId(PELLE, note.note.id)!!.photos shouldBe listOf(beach)
        service.update(PELLE, note.note.id, "holidays", "blue", photos = listOf("0".repeat(64))) shouldBe
            SaveNoteResult.Invalid(setOf(NoteProblem.UNKNOWN_PHOTO))
        service.create(PELLE, "many", "blue", photos = (0..NoteService.MAX_PHOTOS).map { photo("photo $it") }) shouldBe
            SaveNoteResult.Invalid(setOf(NoteProblem.TOO_MANY_PHOTOS))
    }

    @Test
    fun `deletes notes softly`() {
        val note = create("milk")

        service.delete(PELLE, note.id) shouldBe DeleteNoteResult.Deleted
        service.delete(PELLE, note.id) shouldBe DeleteNoteResult.NotFound
        service.byId(PELLE, note.id) shouldBe null
        service.update(PELLE, note.id, "again", "yellow") shouldBe SaveNoteResult.NotFound
        service.page(PELLE, 0, 10).total shouldBe 0
        database.dsl.fetchCount(NOTES) shouldBe 1
    }

    @Test
    fun `a deleted label is dropped from the notes, a new one of its name is not attached`() {
        val note = create("plumber", labelNames = listOf("home", "todo"))
        val home = labels.byName("home")!!

        labels.delete(home.id)
        service.byId(PELLE, note.id)!!.labels.map { it.name } shouldBe listOf("todo")
        labels.create("home")
        service.byId(PELLE, note.id)!!.labels.map { it.name } shouldBe listOf("todo")
        service.page(PELLE, 0, 10, "home").total shouldBe 0
    }

    companion object {
        private const val PELLE = "pelle@pelle.io"

        private const val OTHER = "other@example.org"
    }
}
