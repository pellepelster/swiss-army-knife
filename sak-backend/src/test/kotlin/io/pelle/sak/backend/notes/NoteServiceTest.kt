package io.pelle.sak.backend.notes

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.backend.Slice
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.backend.files.StoreFileResult
import io.pelle.sak.backend.labels.LabelService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import io.pelle.sak.model.testing.Media

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
        (files.store((Media.JPEG + content.toByteArray()).inputStream(), PELLE) as StoreFileResult.Stored).file.id

    private fun create(
        text: String,
        color: String = "yellow",
        labelNames: List<String> = emptyList(),
        owner: String = PELLE,
        photos: List<Int> = emptyList(),
        recordings: List<Int> = emptyList(),
    ) = service.create(owner, text, color, labelNames, photos, recordings).shouldBeInstanceOf<SaveNoteResult.Saved>().note

    private fun later(minutes: Long = 1) {
        clock.now = clock.now.plusSeconds(minutes * 60)
    }

    @Test
    fun `creates notes with their labels, the unknown ones are created`() {
        labels.create(PELLE, "home")

        val note = create("Call the plumber", "PINK", listOf("Todo", "home", "todo"))
        note.color shouldBe NoteColor.PINK
        note.labels.map { it.name } shouldBe listOf("home", "todo")
        note.createdAt shouldBe clock.now
        // a new note is not changed yet
        note.updatedAt shouldBe null
        service.byId(PELLE, note.id) shouldBe note
        labels.page(PELLE, 0, 10).items.map { it.name } shouldBe listOf("home", "todo")
    }

    @Test
    fun `rejects notes with every problem they have and creates no labels for them`() {
        service.create(PELLE, "x".repeat(10_001), "purple", listOf("ok", " ")) shouldBe
            SaveNoteResult.Invalid(setOf(NoteProblem.TEXT_TOO_LONG, NoteProblem.INVALID_COLOR, NoteProblem.INVALID_LABEL))
        service.create(PELLE, "fine", "purple", listOf("ok")) shouldBe SaveNoteResult.Invalid(setOf(NoteProblem.INVALID_COLOR))
        labels.byName(PELLE, "ok") shouldBe null
        create("x".repeat(10_000))
    }

    @Test
    fun `a note has at most ten labels, the same one written differently counts once`() {
        val ten = (1..10).map { "label $it" }
        create("ten", labelNames = ten + "LABEL 1").labels.size shouldBe 10
        service.create(PELLE, "eleven", "yellow", ten + "label 11") shouldBe SaveNoteResult.Invalid(setOf(NoteProblem.TOO_MANY_LABELS))
        labels.byName(PELLE, "label 11") shouldBe null
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
        service.page(PELLE, 0, 10, "unknown") shouldBe Slice(emptyList(), 0)
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
        // a note that is not there creates no label
        service.update(PELLE, 4711, "", "blue", listOf("ghost")) shouldBe SaveNoteResult.NotFound
        labels.byName(PELLE, "ghost") shouldBe null
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
        service.update(PELLE, note.note.id, "holidays", "blue", photos = listOf(4711)) shouldBe
            SaveNoteResult.Invalid(setOf(NoteProblem.UNKNOWN_PHOTO))
        service.create(PELLE, "many", "blue", photos = (0..NoteService.MAX_PHOTOS).map { photo("photo $it") }) shouldBe
            SaveNoteResult.Invalid(setOf(NoteProblem.TOO_MANY_PHOTOS))
    }

    @Test
    fun `keeps the recordings of a note apart from its photos, each of its kind`() {
        val beach = photo("beach")
        val memo = (files.store(Media.wav().inputStream(), PELLE) as StoreFileResult.Stored).file.id

        val note = create("holidays", photos = listOf(beach), recordings = listOf(memo, memo))
        note.photos shouldBe listOf(beach)
        note.recordings shouldBe listOf(memo)
        service.byId(PELLE, note.id)!!.let {
            it.photos shouldBe listOf(beach)
            it.recordings shouldBe listOf(memo)
        }
        service.page(PELLE, 0, 10).items.single().recordings shouldBe listOf(memo)

        service.update(PELLE, note.id, "holidays", "blue", photos = listOf(beach))
            .shouldBeInstanceOf<SaveNoteResult.Saved>().note.recordings shouldBe emptyList()
        // a recording is no photo and a photo no recording
        service.update(PELLE, note.id, "holidays", "blue", photos = listOf(memo), recordings = listOf(beach)) shouldBe
            SaveNoteResult.Invalid(setOf(NoteProblem.UNKNOWN_PHOTO, NoteProblem.UNKNOWN_RECORDING))
    }

    @Test
    fun `a note only takes the photos and recordings its owner uploaded`() {
        val beach = photo("beach")
        val memo = (files.store(Media.wav().inputStream(), PELLE) as StoreFileResult.Stored).file.id

        service.create(OTHER, "mine now", "blue", photos = listOf(beach), recordings = listOf(memo)) shouldBe
            SaveNoteResult.Invalid(setOf(NoteProblem.UNKNOWN_PHOTO, NoteProblem.UNKNOWN_RECORDING))
        val theirs = create("theirs", owner = OTHER)
        service.update(OTHER, theirs.id, "theirs", "blue", photos = listOf(beach)) shouldBe
            SaveNoteResult.Invalid(setOf(NoteProblem.UNKNOWN_PHOTO))
    }

    @Test
    fun `deletes notes softly`() {
        val note = create("milk")

        service.delete(PELLE, note.id) shouldBe DeleteNoteResult.Deleted
        service.delete(PELLE, note.id) shouldBe DeleteNoteResult.NotFound
        service.byId(PELLE, note.id) shouldBe null
        service.update(PELLE, note.id, "again", "yellow") shouldBe SaveNoteResult.NotFound
        service.page(PELLE, 0, 10).total shouldBe 0
    }

    @Test
    fun `a deleted label is dropped from the notes, a new one of its name is not attached`() {
        val note = create("plumber", labelNames = listOf("home", "todo"))
        val home = labels.byName(PELLE, "home")!!

        labels.delete(PELLE, home.id)
        service.byId(PELLE, note.id)!!.labels.map { it.name } shouldBe listOf("todo")
        labels.create(PELLE, "home")
        service.byId(PELLE, note.id)!!.labels.map { it.name } shouldBe listOf("todo")
        service.page(PELLE, 0, 10, "home").total shouldBe 0
    }

    @Test
    fun `the labels of a note are the ones of its owner`() {
        val mine = create("plumber", labelNames = listOf("home")).labels.single()
        val others = create("tickets", labelNames = listOf("home"), owner = OTHER).labels.single()

        others.id shouldBe mine.id + 1
        labels.page(OTHER, 0, 10).items shouldBe listOf(others)
        service.page(OTHER, 0, 10, "home").items.map { it.text } shouldBe listOf("tickets")
        service.page(PELLE, 0, 10, "home").items.map { it.text } shouldBe listOf("plumber")
    }

    companion object {
        private const val PELLE = "pelle@pelle.io"

        private const val OTHER = "other@example.org"
    }
}
