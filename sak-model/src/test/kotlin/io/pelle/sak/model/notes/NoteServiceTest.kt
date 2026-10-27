package io.pelle.sak.model.notes

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.NOTES
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

    private val service by lazy { NoteService(database, labels, clock) }

    private fun create(
        text: String,
        color: String = "yellow",
        labelNames: List<String> = emptyList(),
    ) = service.create(text, color, labelNames).shouldBeInstanceOf<SaveNoteResult.Saved>().note

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
        service.byId(note.id) shouldBe note
        labels.page(0, 10).items.map { it.name } shouldBe listOf("home", "todo")
    }

    @Test
    fun `rejects notes with every problem they have and creates no labels for them`() {
        service.create("x".repeat(10_001), "purple", listOf("ok", " ")) shouldBe
            SaveNoteResult.Invalid(setOf(NoteProblem.TEXT_TOO_LONG, NoteProblem.INVALID_COLOR, NoteProblem.INVALID_LABEL))
        service.create("fine", "purple", listOf("ok")) shouldBe SaveNoteResult.Invalid(setOf(NoteProblem.INVALID_COLOR))
        labels.byName("ok") shouldBe null
        create("x".repeat(10_000))
    }

    @Test
    fun `pages the newest notes first, of all or of one label`() {
        val milk = create("milk", labelNames = listOf("shopping"))
        later()
        val plumber = create("plumber", labelNames = listOf("home", "todo"))
        later()
        val app = create("app", labelNames = listOf("todo"))

        service.page(0, 2).let {
            it.items.map { note -> note.id } shouldBe listOf(app.id, plumber.id)
            it.total shouldBe 3
        }
        service.page(2, 2).items shouldBe listOf(milk)
        service.page(0, 10, "TODO").let {
            it.items.map { note -> note.text } shouldBe listOf("app", "plumber")
            it.total shouldBe 2
        }
        service.page(0, 10, "unknown") shouldBe io.pelle.sak.model.Slice(emptyList(), 0)
    }

    @Test
    fun `replaces text, color and labels and tells when`() {
        val note = create("milk", labelNames = listOf("shopping", "todo"))
        later()

        val updated = service.update(note.id, "milk and bread", "green", listOf("shopping", "weekend"))
        updated.shouldBeInstanceOf<SaveNoteResult.Saved>().note.let {
            it.text shouldBe "milk and bread"
            it.color shouldBe NoteColor.GREEN
            it.labels.map { label -> label.name } shouldBe listOf("shopping", "weekend")
            it.createdAt shouldBe note.createdAt
            it.updatedAt shouldBe clock.now
        }
        service.byId(note.id) shouldBe updated.note
        service.page(0, 10, "todo").total shouldBe 0

        service.update(note.id, "", "blue", listOf("")) shouldBe SaveNoteResult.Invalid(setOf(NoteProblem.INVALID_LABEL))
        service.update(4711, "", "blue") shouldBe SaveNoteResult.NotFound
    }

    @Test
    fun `deletes notes softly`() {
        val note = create("milk")

        service.delete(note.id) shouldBe DeleteNoteResult.Deleted
        service.delete(note.id) shouldBe DeleteNoteResult.NotFound
        service.byId(note.id) shouldBe null
        service.update(note.id, "again", "yellow") shouldBe SaveNoteResult.NotFound
        service.page(0, 10).total shouldBe 0
        database.dsl.fetchCount(NOTES) shouldBe 1
    }

    @Test
    fun `a deleted label is dropped from the notes, a new one of its name is not attached`() {
        val note = create("plumber", labelNames = listOf("home", "todo"))
        val home = labels.byName("home")!!

        labels.delete(home.id)
        service.byId(note.id)!!.labels.map { it.name } shouldBe listOf("todo")
        labels.create("home")
        service.byId(note.id)!!.labels.map { it.name } shouldBe listOf("todo")
        service.page(0, 10, "home").total shouldBe 0
    }
}
