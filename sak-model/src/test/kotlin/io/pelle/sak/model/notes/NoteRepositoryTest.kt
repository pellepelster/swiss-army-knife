package io.pelle.sak.model.notes

import io.kotest.matchers.shouldBe
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.files.FileRepository
import io.pelle.sak.model.jooq.tables.references.NOTES
import io.pelle.sak.model.jooq.tables.references.NOTES_FILES
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

    private fun file(name: String) = FileRepository(database).insert(name.padEnd(64, '0'), "image/jpeg", 1, "pelle@pelle.io", now).id

    // the links of the note, the ones not deleted first, each in its order
    private fun links(noteId: Int) =
        database.dsl
            .selectFrom(NOTES_FILES)
            .where(NOTES_FILES.NOTE_ID.eq(noteId))
            .orderBy(NOTES_FILES.DELETED_AT.isNotNull, NOTES_FILES.POSITION)
            .fetch()
            .map { Link(it.id!!, it.fileId!!, it.position!!, it.deletedAt != null) }

    private data class Link(
        val id: Int,
        val fileId: Int,
        val position: Int,
        val deleted: Boolean,
    )

    @Test
    fun `deletes notes softly, of their owner only, the row stays`() {
        val note = repository.insert("pelle@pelle.io", "milk", "yellow", emptySet(), emptyList(), now)

        repository.delete("other@example.org", note.id, now) shouldBe false
        repository.delete("pelle@pelle.io", note.id, now) shouldBe true
        repository.delete("pelle@pelle.io", note.id, now) shouldBe false
        repository.byId("pelle@pelle.io", note.id) shouldBe null
        database.dsl.fetchCount(NOTES) shouldBe 1
    }

    @Test
    fun `deletes the files removed from a note softly, the files that stay keep their link`() {
        val beach = file("beach")
        val sunset = file("sunset")
        val dunes = file("dunes")
        val note = repository.insert("pelle@pelle.io", "holidays", "blue", emptySet(), listOf(beach, sunset), now)
        val (beachLink, sunsetLink) = links(note.id)

        repository.update("pelle@pelle.io", note.id, "holidays", "blue", emptySet(), listOf(dunes, sunset), later) shouldBe true

        repository.byId("pelle@pelle.io", note.id)!!.files shouldBe listOf(dunes, sunset)
        val (dunesLink, movedSunsetLink, deletedBeachLink) = links(note.id)
        dunesLink.fileId shouldBe dunes
        movedSunsetLink shouldBe sunsetLink.copy(position = 1)
        deletedBeachLink shouldBe beachLink.copy(deleted = true)
    }

    @Test
    fun `attaches a file removed from a note again with a link of its own`() {
        val beach = file("beach")
        val note = repository.insert("pelle@pelle.io", "holidays", "blue", emptySet(), listOf(beach), now)

        repository.update("pelle@pelle.io", note.id, "holidays", "blue", emptySet(), emptyList(), later)
        repository.byId("pelle@pelle.io", note.id)!!.files shouldBe emptyList()
        repository.update("pelle@pelle.io", note.id, "holidays", "blue", emptySet(), listOf(beach), later)

        repository.byId("pelle@pelle.io", note.id)!!.files shouldBe listOf(beach)
        links(note.id).map { it.fileId to it.deleted } shouldBe listOf(beach to false, beach to true)
    }

    @Test
    fun `deletes the files of a deleted note softly with it`() {
        val beach = file("beach")
        val note = repository.insert("pelle@pelle.io", "holidays", "blue", emptySet(), listOf(beach), now)

        repository.delete("pelle@pelle.io", note.id, later) shouldBe true

        links(note.id).single().deleted shouldBe true
    }
}
