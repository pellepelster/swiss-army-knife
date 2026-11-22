package io.pelle.sak.model.notes

import io.pelle.sak.model.jooq.tables.references.NOTE_LISTS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    // the note of a save that is expected to store it
    private fun NoteWriteResult.note() = (this as NoteWriteResult.Saved).note

    // the list of the owner, the one they get when they sign in
    private fun list(owner: String): Int {
        repository.ensureList(owner, FIRST_LIST)
        return repository.ownList(owner)!!
    }

    private fun file(name: String) = FileRepository(database).insert("image/jpeg", 1, "pelle@pelle.io").id

    // the links of the note, the ones not deleted first, each in its order
    private fun links(noteId: Int) =
        database.dsl
            .selectFrom(NOTES_FILES)
            .where(NOTES_FILES.NOTE_ID.eq(noteId))
            .orderBy(NOTES_FILES.DELETED_AT.isNotNull, NOTES_FILES.POSITION)
            .fetch()
            .map { Link(it.id!!, it.fileId!!, it.position!!, it.deletedAt != null) }

    private fun label(name: String) = LabelRepository(database).insert("pelle@pelle.io", name)!!.id

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
        val note = repository.insert("pelle@pelle.io", list("pelle@pelle.io"), "milk", "yellow", emptySet(), emptyList()).note()

        repository.delete("other@example.org", note.id) shouldBe null
        repository.delete("pelle@pelle.io", note.id) shouldNotBe null
        repository.delete("pelle@pelle.io", note.id) shouldBe null
        repository.byId("pelle@pelle.io", note.id) shouldBe null
        database.dsl.fetchCount(NOTES) shouldBe 1
    }

    @Test
    fun `deletes the files removed from a note softly, the files that stay keep their link`() {
        val beach = file("beach")
        val sunset = file("sunset")
        val dunes = file("dunes")
        val note = repository.insert("pelle@pelle.io", list("pelle@pelle.io"), "holidays", "blue", emptySet(), listOf(beach, sunset)).note()
        val (beachLink, sunsetLink) = links(note.id)

        repository.update("pelle@pelle.io", note.id, "holidays", "blue", emptySet(), listOf(dunes, sunset)).note().fileIds shouldBe listOf(dunes, sunset)

        repository.byId("pelle@pelle.io", note.id)!!.fileIds shouldBe listOf(dunes, sunset)
        val (dunesLink, movedSunsetLink, deletedBeachLink) = links(note.id)
        dunesLink.fileId shouldBe dunes
        movedSunsetLink shouldBe sunsetLink.copy(position = 1)
        deletedBeachLink shouldBe beachLink.copy(deleted = true)
    }

    @Test
    fun `attaches a file removed from a note again with a link of its own`() {
        val beach = file("beach")
        val note = repository.insert("pelle@pelle.io", list("pelle@pelle.io"), "holidays", "blue", emptySet(), listOf(beach)).note()

        repository.update("pelle@pelle.io", note.id, "holidays", "blue", emptySet(), emptyList()).note()
        repository.byId("pelle@pelle.io", note.id)!!.fileIds shouldBe emptyList()
        repository.update("pelle@pelle.io", note.id, "holidays", "blue", emptySet(), listOf(beach)).note()

        repository.byId("pelle@pelle.io", note.id)!!.fileIds shouldBe listOf(beach)
        links(note.id).map { it.fileId to it.deleted } shouldBe listOf(beach to false, beach to true)
    }

    @Test
    fun `deletes the files of a deleted note softly with it`() {
        val beach = file("beach")
        val note = repository.insert("pelle@pelle.io", list("pelle@pelle.io"), "holidays", "blue", emptySet(), listOf(beach)).note()

        repository.delete("pelle@pelle.io", note.id) shouldNotBe null

        links(note.id).single().deleted shouldBe true
    }

    @Test
    fun `the labels of the notes are the ones a live note of the owner has, by name`() {
        val owner = "pelle@pelle.io"
        val milk = label("milk")
        val eggs = label("eggs")
        // a label of no note, and one of a note deleted, filter nothing
        label("unused")
        val gone = label("gone")
        val note = repository.insert(owner, list(owner), "shopping", "yellow", setOf(milk, eggs), emptyList()).note()
        repository.insert(owner, list(owner), "old", "yellow", setOf(gone), emptyList()).note().let { repository.delete(owner, it.id) }
        // the labels of another user's notes are theirs
        repository.insert("other@example.org", list("other@example.org"), "theirs", "yellow", setOf(label("theirs")), emptyList()).note()

        repository.usedLabels(owner).map { it.name } shouldBe listOf("eggs", "milk")
        // removed from its last note, a label filters nothing any more
        repository.update(owner, note.id, "shopping", "yellow", setOf(eggs), emptyList()).note()
        repository.usedLabels(owner).map { it.name } shouldBe listOf("eggs")
    }

    @Test
    fun `deletes the labels removed from a note softly, the labels that stay keep their link`() {
        val milk = label("milk")
        val eggs = label("eggs")
        val note = repository.insert("pelle@pelle.io", list("pelle@pelle.io"), "shopping", "yellow", setOf(milk, eggs), emptyList()).note()

        repository.update("pelle@pelle.io", note.id, "shopping", "yellow", setOf(eggs), emptyList()).note()
        repository.byId("pelle@pelle.io", note.id)!!.labelIds shouldBe setOf(eggs)
        repository.count("pelle@pelle.io", milk) shouldBe 0
        repository.update("pelle@pelle.io", note.id, "shopping", "yellow", setOf(eggs, milk), emptyList()).note()

        repository.byId("pelle@pelle.io", note.id)!!.labelIds shouldBe setOf(milk, eggs)
        labelLinks(note.id) shouldBe listOf(milk to true, eggs to false, milk to false)
    }

    @Test
    fun `deletes the labels of a deleted note and the links to a deleted label softly`() {
        val milk = label("milk")
        val eggs = label("eggs")
        val deleted = repository.insert("pelle@pelle.io", list("pelle@pelle.io"), "shopping", "yellow", setOf(milk), emptyList()).note()
        val kept = repository.insert("pelle@pelle.io", list("pelle@pelle.io"), "breakfast", "yellow", setOf(milk, eggs), emptyList()).note()

        repository.delete("pelle@pelle.io", deleted.id)
        LabelRepository(database).delete("pelle@pelle.io", eggs, listOf(repository)) shouldNotBe null

        labelLinks(deleted.id) shouldBe listOf(milk to true)
        labelLinks(kept.id) shouldBe listOf(milk to false, eggs to true)
        repository.byId("pelle@pelle.io", kept.id)!!.labelIds shouldBe setOf(milk)
        repository.count("pelle@pelle.io", eggs) shouldBe 0
    }

    @Test
    fun `the schema attaches a file to a note once among the links that are not deleted only`() {
        val beach = file("beach")
        val note = repository.insert("pelle@pelle.io", list("pelle@pelle.io"), "holidays", "blue", emptySet(), emptyList()).note()
        fun insert(deletedAt: Instant?) =
            database.dsl
                .insertInto(NOTES_FILES)
                .set(NOTES_FILES.NOTE_ID, note.id)
                .set(NOTES_FILES.FILE_ID, beach)
                .set(NOTES_FILES.POSITION, 0)
                .set(NOTES_FILES.CREATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
                .set(NOTES_FILES.OWNER, "admin@example.org")
                .set(NOTES_FILES.UPDATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
                .set(NOTES_FILES.DELETED_AT, deletedAt)
                .execute()

        insert(null) shouldBe 1
        shouldThrow<DataAccessException> { insert(null) }
        insert(Instant.parse("2026-10-27T09:00:00Z")) shouldBe 1
        insert(Instant.parse("2026-10-27T09:00:00Z")) shouldBe 1
    }

    @Test
    fun `the links of a note are of the owner of the note`() {
        val summer = label("summer")
        val beach = file("beach")
        val note = repository.insert("pelle@pelle.io", list("pelle@pelle.io"), "holidays", "blue", setOf(summer), listOf(beach)).note()
        repository.update("pelle@pelle.io", note.id, "holidays", "blue", setOf(summer, label("sea")), listOf(beach, file("dunes"))).note()

        database.dsl.select(NOTES_LABELS.OWNER).from(NOTES_LABELS).fetch(NOTES_LABELS.OWNER).toSet() shouldBe setOf("pelle@pelle.io")
        database.dsl.select(NOTES_FILES.OWNER).from(NOTES_FILES).fetch(NOTES_FILES.OWNER).toSet() shouldBe setOf("pelle@pelle.io")
    }

    @Test
    fun `a user without a list gets one, private and only once, a user with a list gets none`() {
        repository.ownList("pelle@pelle.io") shouldBe null
        repository.ensureList("pelle@pelle.io", FIRST_LIST)
        repository.ensureList("pelle@pelle.io", FIRST_LIST)
        val first = repository.ownList("pelle@pelle.io")!!
        database.dsl
            .insertInto(NOTE_LISTS)
            .set(NOTE_LISTS.OWNER, "other@example.org")
            .set(NOTE_LISTS.NAME, "Ideas")
            .set(NOTE_LISTS.SHARED, false)
            .set(NOTE_LISTS.CREATED_AT, now)
            .execute()
        repository.ensureList("other@example.org", FIRST_LIST)

        repository.insert("pelle@pelle.io", first, "milk", "yellow", emptySet(), emptyList()).note().listId shouldBe first
        database.dsl
            .select(NOTE_LISTS.OWNER, NOTE_LISTS.NAME, NOTE_LISTS.SHARED)
            .from(NOTE_LISTS)
            .orderBy(NOTE_LISTS.ID)
            .fetch { listOf(it.value1(), it.value2(), it.value3()) } shouldBe
            listOf(listOf("pelle@pelle.io", FIRST_LIST, false), listOf("other@example.org", "Ideas", false))
    }

    @Test
    fun `a user whose list was deleted gets no new one (LIST-1)`() {
        repository.ensureList("pelle@pelle.io", FIRST_LIST)
        database.dsl.update(NOTE_LISTS).set(NOTE_LISTS.DELETED_AT, now).execute()
        repository.ensureList("pelle@pelle.io", FIRST_LIST)

        repository.ownList("pelle@pelle.io") shouldBe null
    }

    @Test
    fun `the schema keeps the name of a list unique among the lists of an owner that are not deleted only`() {
        repository.insert("pelle@pelle.io", list("pelle@pelle.io"), "milk", "yellow", emptySet(), emptyList()).note()
        fun insertDefault() =
            database.dsl
                .insertInto(NOTE_LISTS)
                .set(NOTE_LISTS.OWNER, "pelle@pelle.io")
                .set(NOTE_LISTS.NAME, FIRST_LIST)
                .set(NOTE_LISTS.SHARED, false)
                .set(NOTE_LISTS.CREATED_AT, now)
                .execute()
        shouldThrow<DataAccessException> { insertDefault() }
        database.dsl.update(NOTE_LISTS).set(NOTE_LISTS.DELETED_AT, now).execute()
        insertDefault() shouldBe 1
    }

    @Test
    fun `a note takes only a list and files of its owner, a label that is not the owner's is left out`() {
        val owner = "pelle@pelle.io"
        val theirs = list("other@example.org")
        val photo = file("beach")
        val milk = label("milk")

        repository.insert(owner, theirs, "x", "yellow", emptySet(), emptyList()) shouldBe NoteWriteResult.NoList
        repository.insert("other@example.org", theirs, "x", "yellow", emptySet(), listOf(photo)) shouldBe NoteWriteResult.UnknownFiles(setOf(photo))
        // the label of another user, e.g. one deleted and made again in the same moment, is not linked
        repository.insert("other@example.org", theirs, "x", "yellow", setOf(milk), emptyList()).note().labelIds shouldBe emptySet()

        val note = repository.insert(owner, list(owner), "x", "yellow", setOf(milk), listOf(photo)).note()
        repository.update(owner, note.id, "x", "yellow", emptySet(), listOf(photo, 4711)) shouldBe NoteWriteResult.UnknownFiles(setOf(4711))
        repository.update(owner, 4711, "x", "yellow", emptySet(), emptyList()) shouldBe NoteWriteResult.NotFound
        repository.byId(owner, note.id)!!.fileIds shouldBe listOf(photo)
    }

}

private const val FIRST_LIST = "My Notes"
