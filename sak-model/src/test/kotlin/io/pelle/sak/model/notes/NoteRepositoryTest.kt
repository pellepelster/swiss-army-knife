package io.pelle.sak.model.notes

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.files.FileRepository
import io.pelle.sak.model.jooq.tables.references.NOTES
import io.pelle.sak.model.jooq.tables.references.NOTES_FILES
import io.pelle.sak.model.jooq.tables.references.NOTES_LABELS
import io.pelle.sak.model.labels.LabelRepository
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class NoteRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val repository by lazy { NoteRepository(database) }

    private val now = Instant.parse("2026-10-27T08:00:00Z")

    private val later = now.plusSeconds(60)

    private fun file(name: String) = FileRepository(database).insert(name.padEnd(64, '0'), "image/jpeg", 1, "pelle@pelle.io").id

    // the links of the note, the ones not deleted first, each in its order
    private fun links(noteId: Int) =
        database.dsl
            .selectFrom(NOTES_FILES)
            .where(NOTES_FILES.NOTE_ID.eq(noteId))
            .orderBy(NOTES_FILES.DELETED_AT.isNotNull, NOTES_FILES.POSITION)
            .fetch()
            .map { Link(it.id!!, it.fileId!!, it.position!!, it.deletedAt != null) }

    private fun label(name: String) = LabelRepository(database).insert(name)!!.id

    // the labels of the note with whether their link is deleted, by the order they were attached in
    private fun labelLinks(noteId: Int) =
        database.dsl
            .selectFrom(NOTES_LABELS)
            .where(NOTES_LABELS.NOTE_ID.eq(noteId))
            .orderBy(NOTES_LABELS.ID)
            .fetch()
            .map { it.labelId!! to (it.deletedAt != null) }

    private data class Link(
        val id: Int,
        val fileId: Int,
        val position: Int,
        val deleted: Boolean,
    )

    @Test
    fun `deletes notes softly, of their owner only, the row stays`() {
        val note = repository.insert("pelle@pelle.io", "milk", "yellow", emptySet(), emptyList())

        repository.delete("other@example.org", note.id) shouldBe false
        repository.delete("pelle@pelle.io", note.id) shouldBe true
        repository.delete("pelle@pelle.io", note.id) shouldBe false
        repository.byId("pelle@pelle.io", note.id) shouldBe null
        database.dsl.fetchCount(NOTES) shouldBe 1
    }

    @Test
    fun `deletes the files removed from a note softly, the files that stay keep their link`() {
        val beach = file("beach")
        val sunset = file("sunset")
        val dunes = file("dunes")
        val note = repository.insert("pelle@pelle.io", "holidays", "blue", emptySet(), listOf(beach, sunset))
        val (beachLink, sunsetLink) = links(note.id)

        repository.update("pelle@pelle.io", note.id, "holidays", "blue", emptySet(), listOf(dunes, sunset))!!.files shouldBe listOf(dunes, sunset)

        repository.byId("pelle@pelle.io", note.id)!!.files shouldBe listOf(dunes, sunset)
        val (dunesLink, movedSunsetLink, deletedBeachLink) = links(note.id)
        dunesLink.fileId shouldBe dunes
        movedSunsetLink shouldBe sunsetLink.copy(position = 1)
        deletedBeachLink shouldBe beachLink.copy(deleted = true)
    }

    @Test
    fun `attaches a file removed from a note again with a link of its own`() {
        val beach = file("beach")
        val note = repository.insert("pelle@pelle.io", "holidays", "blue", emptySet(), listOf(beach))

        repository.update("pelle@pelle.io", note.id, "holidays", "blue", emptySet(), emptyList())
        repository.byId("pelle@pelle.io", note.id)!!.files shouldBe emptyList()
        repository.update("pelle@pelle.io", note.id, "holidays", "blue", emptySet(), listOf(beach))

        repository.byId("pelle@pelle.io", note.id)!!.files shouldBe listOf(beach)
        links(note.id).map { it.fileId to it.deleted } shouldBe listOf(beach to false, beach to true)
    }

    @Test
    fun `deletes the files of a deleted note softly with it`() {
        val beach = file("beach")
        val note = repository.insert("pelle@pelle.io", "holidays", "blue", emptySet(), listOf(beach))

        repository.delete("pelle@pelle.io", note.id) shouldBe true

        links(note.id).single().deleted shouldBe true
    }

    @Test
    fun `deletes the labels removed from a note softly, the labels that stay keep their link`() {
        val milk = label("milk")
        val eggs = label("eggs")
        val note = repository.insert("pelle@pelle.io", "shopping", "yellow", setOf(milk, eggs), emptyList())

        repository.update("pelle@pelle.io", note.id, "shopping", "yellow", setOf(eggs), emptyList())
        repository.byId("pelle@pelle.io", note.id)!!.labelIds shouldBe setOf(eggs)
        repository.count("pelle@pelle.io", milk) shouldBe 0
        repository.update("pelle@pelle.io", note.id, "shopping", "yellow", setOf(eggs, milk), emptyList())

        repository.byId("pelle@pelle.io", note.id)!!.labelIds shouldBe setOf(milk, eggs)
        labelLinks(note.id) shouldBe listOf(milk to true, eggs to false, milk to false)
    }

    @Test
    fun `deletes the labels of a deleted note and the links to a deleted label softly`() {
        val milk = label("milk")
        val eggs = label("eggs")
        val deleted = repository.insert("pelle@pelle.io", "shopping", "yellow", setOf(milk), emptyList())
        val kept = repository.insert("pelle@pelle.io", "breakfast", "yellow", setOf(milk, eggs), emptyList())

        repository.delete("pelle@pelle.io", deleted.id)
        repository.detachLabel(eggs)

        labelLinks(deleted.id) shouldBe listOf(milk to true)
        labelLinks(kept.id) shouldBe listOf(milk to false, eggs to true)
        repository.byId("pelle@pelle.io", kept.id)!!.labelIds shouldBe setOf(milk)
        repository.count("pelle@pelle.io", eggs) shouldBe 0
    }

    @Test
    fun `the schema attaches a file to a note once among the links that are not deleted only`() {
        val beach = file("beach")
        val note = repository.insert("pelle@pelle.io", "holidays", "blue", emptySet(), emptyList())
        fun insert(deletedAt: Instant?) =
            database.dsl
                .insertInto(NOTES_FILES)
                .set(NOTES_FILES.NOTE_ID, note.id)
                .set(NOTES_FILES.FILE_ID, beach)
                .set(NOTES_FILES.POSITION, 0)
                .set(NOTES_FILES.CREATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
                .set(NOTES_FILES.UPDATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
                .set(NOTES_FILES.DELETED_AT, deletedAt)
                .execute()

        insert(null) shouldBe 1
        shouldThrow<DataAccessException> { insert(null) }
        insert(Instant.parse("2026-10-27T09:00:00Z")) shouldBe 1
        insert(Instant.parse("2026-10-27T09:00:00Z")) shouldBe 1
    }
}
