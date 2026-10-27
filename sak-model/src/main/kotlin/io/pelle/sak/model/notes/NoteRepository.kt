package io.pelle.sak.model.notes

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.NotesRecord
import io.pelle.sak.model.jooq.tables.references.NOTES
import io.pelle.sak.model.jooq.tables.references.NOTES_LABELS
import io.pelle.sak.model.jooq.tables.references.NOTES_FILES
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

// a note as it is stored, with the ids of its labels and the ids of its files (its photos and its
// recordings) in their order
data class NoteRow(
    val id: Int,
    val owner: String,
    val text: String,
    val color: String,
    val labelIds: Set<Int>,
    val files: List<Int>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

// the database access of the notes and the links to their labels and files, only NoteService
// (sak-backend) uses it; it reads and changes the notes of one owner that are not deleted only. A
// label or a file removed from a note, and the ones of a deleted note, stay as links with
// deleted_at set
class NoteRepository(
    private val database: SakDatabase,
) {
    // the newest first, of one label or of all when labelId is null
    fun page(
        owner: String,
        offset: Long,
        limit: Int,
        labelId: Int?,
    ): List<NoteRow> {
        val notes =
            database.dsl
                .selectFrom(NOTES)
                .where(visible(owner, labelId))
                .orderBy(NOTES.CREATED_AT.desc(), NOTES.ID.desc())
                .limit(limit)
                .offset(offset)
                .fetch()
        val ids = notes.map { it.id!! }
        val labels = labelIds(database.dsl, ids)
        val files = files(database.dsl, ids)
        return notes.map { it.toRow(labels[it.id].orEmpty(), files[it.id].orEmpty()) }
    }

    fun count(
        owner: String,
        labelId: Int?,
    ): Long = database.dsl.fetchCount(NOTES, visible(owner, labelId)).toLong()

    fun byId(
        owner: String,
        id: Int,
    ): NoteRow? =
        database.dsl
            .selectFrom(NOTES)
            .where(NOTES.ID.eq(id), NOTES.OWNER.eq(owner), NOTES.DELETED_AT.isNull)
            .fetchOne()
            ?.let { it.toRow(labelIds(database.dsl, listOf(id))[id].orEmpty(), files(database.dsl, listOf(id))[id].orEmpty()) }

    fun insert(
        owner: String,
        text: String,
        color: String,
        labelIds: Set<Int>,
        files: List<Int>,
        now: Instant,
    ): NoteRow =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val note =
                dsl
                    .insertInto(NOTES)
                    .set(NOTES.OWNER, owner)
                    .set(NOTES.TEXT, text)
                    .set(NOTES.COLOR, color)
                    .set(NOTES.CREATED_AT, now.utc())
                    .set(NOTES.UPDATED_AT, now.utc())
                    .returning()
                    .fetchOne()!!
            labelIds.forEach { insertLabel(dsl, note.id!!, it, now) }
            files.forEachIndexed { position, fileId -> insertFile(dsl, note.id!!, fileId, position, now) }
            note.toRow(labelIds, files)
        }

    // false when there is none
    fun update(
        owner: String,
        id: Int,
        text: String,
        color: String,
        labelIds: Set<Int>,
        files: List<Int>,
        now: Instant,
    ): Boolean =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val updated =
                dsl
                    .update(NOTES)
                    .set(NOTES.TEXT, text)
                    .set(NOTES.COLOR, color)
                    .set(NOTES.UPDATED_AT, now.utc())
                    .where(NOTES.ID.eq(id), NOTES.OWNER.eq(owner), NOTES.DELETED_AT.isNull)
                    .execute() > 0
            if (updated) {
                updateLabels(dsl, id, labelIds, now)
                updateFiles(dsl, id, files, now)
            }
            updated
        }

    // false when there was none; its links to its labels and files are deleted with it
    fun delete(
        owner: String,
        id: Int,
        now: Instant,
    ): Boolean =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val deleted =
                dsl
                    .update(NOTES)
                    .set(NOTES.UPDATED_AT, now.utc())
                    .set(NOTES.DELETED_AT, now.utc())
                    .where(NOTES.ID.eq(id), NOTES.OWNER.eq(owner), NOTES.DELETED_AT.isNull)
                    .execute() > 0
            if (deleted) {
                deleteLabels(dsl, NOTES_LABELS.NOTE_ID.eq(id), now)
                deleteFiles(dsl, NOTES_FILES.NOTE_ID.eq(id), now)
            }
            deleted
        }

    // drops the label from every note
    fun detachLabel(
        labelId: Int,
        now: Instant,
    ) = deleteLabels(database.dsl, NOTES_LABELS.LABEL_ID.eq(labelId), now)

    private fun visible(
        owner: String,
        labelId: Int?,
    ): Condition =
        NOTES.OWNER.eq(owner).and(NOTES.DELETED_AT.isNull).and(
            if (labelId == null) {
                DSL.noCondition()
            } else {
                NOTES.ID.`in`(
                    DSL.select(NOTES_LABELS.NOTE_ID).from(NOTES_LABELS).where(NOTES_LABELS.LABEL_ID.eq(labelId), NOTES_LABELS.DELETED_AT.isNull),
                )
            },
        )

    // the links of the labels that stay keep their row, the ones of the labels removed are deleted,
    // the labels added get a link of their own
    private fun updateLabels(
        dsl: DSLContext,
        noteId: Int,
        labelIds: Set<Int>,
        now: Instant,
    ) {
        val links =
            dsl
                .selectFrom(NOTES_LABELS)
                .where(NOTES_LABELS.NOTE_ID.eq(noteId), NOTES_LABELS.DELETED_AT.isNull)
                .fetch()
                .associateBy { it.labelId!! }
        deleteLabels(dsl, NOTES_LABELS.ID.`in`(links.filterKeys { it !in labelIds }.values.map { it.id }), now)
        (labelIds - links.keys).forEach { insertLabel(dsl, noteId, it, now) }
    }

    private fun insertLabel(
        dsl: DSLContext,
        noteId: Int,
        labelId: Int,
        now: Instant,
    ) {
        dsl
            .insertInto(NOTES_LABELS)
            .set(NOTES_LABELS.NOTE_ID, noteId)
            .set(NOTES_LABELS.LABEL_ID, labelId)
            .set(NOTES_LABELS.CREATED_AT, now.utc())
            .set(NOTES_LABELS.UPDATED_AT, now.utc())
            .execute()
    }

    // the links that are not deleted yet among the ones of the condition
    private fun deleteLabels(
        dsl: DSLContext,
        condition: Condition,
        now: Instant,
    ) {
        dsl
            .update(NOTES_LABELS)
            .set(NOTES_LABELS.UPDATED_AT, now.utc())
            .set(NOTES_LABELS.DELETED_AT, now.utc())
            .where(condition, NOTES_LABELS.DELETED_AT.isNull)
            .execute()
    }

    // the links of the files that stay keep their row and take their new position, the ones of the
    // files removed are deleted, the files added get a link of their own
    private fun updateFiles(
        dsl: DSLContext,
        noteId: Int,
        files: List<Int>,
        now: Instant,
    ) {
        val links =
            dsl
                .selectFrom(NOTES_FILES)
                .where(NOTES_FILES.NOTE_ID.eq(noteId), NOTES_FILES.DELETED_AT.isNull)
                .fetch()
                .associateBy { it.fileId!! }
        deleteFiles(dsl, NOTES_FILES.ID.`in`(links.filterKeys { it !in files }.values.map { it.id }), now)
        files.forEachIndexed { position, fileId ->
            val link = links[fileId]
            if (link == null) {
                insertFile(dsl, noteId, fileId, position, now)
            } else if (link.position != position) {
                dsl
                    .update(NOTES_FILES)
                    .set(NOTES_FILES.POSITION, position)
                    .set(NOTES_FILES.UPDATED_AT, now.utc())
                    .where(NOTES_FILES.ID.eq(link.id))
                    .execute()
            }
        }
    }

    private fun insertFile(
        dsl: DSLContext,
        noteId: Int,
        fileId: Int,
        position: Int,
        now: Instant,
    ) {
        dsl
            .insertInto(NOTES_FILES)
            .set(NOTES_FILES.NOTE_ID, noteId)
            .set(NOTES_FILES.FILE_ID, fileId)
            .set(NOTES_FILES.POSITION, position)
            .set(NOTES_FILES.CREATED_AT, now.utc())
            .set(NOTES_FILES.UPDATED_AT, now.utc())
            .execute()
    }

    // the links that are not deleted yet among the ones of the condition
    private fun deleteFiles(
        dsl: DSLContext,
        condition: Condition,
        now: Instant,
    ) {
        dsl
            .update(NOTES_FILES)
            .set(NOTES_FILES.UPDATED_AT, now.utc())
            .set(NOTES_FILES.DELETED_AT, now.utc())
            .where(condition, NOTES_FILES.DELETED_AT.isNull)
            .execute()
    }

    private fun files(
        dsl: DSLContext,
        noteIds: Collection<Int>,
    ): Map<Int, List<Int>> =
        dsl
            .selectFrom(NOTES_FILES)
            .where(NOTES_FILES.NOTE_ID.`in`(noteIds), NOTES_FILES.DELETED_AT.isNull)
            .orderBy(NOTES_FILES.NOTE_ID, NOTES_FILES.POSITION)
            .fetch()
            .groupBy({ it.noteId!! }, { it.fileId!! })

    private fun labelIds(
        dsl: DSLContext,
        noteIds: Collection<Int>,
    ): Map<Int, Set<Int>> =
        dsl
            .selectFrom(NOTES_LABELS)
            .where(NOTES_LABELS.NOTE_ID.`in`(noteIds), NOTES_LABELS.DELETED_AT.isNull)
            .fetch()
            .groupBy({ it.noteId!! }, { it.labelId!! })
            .mapValues { it.value.toSet() }

    private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun NotesRecord.toRow(
        labelIds: Set<Int>,
        files: List<Int>,
    ) =
        NoteRow(
            id = id!!,
            owner = owner!!,
            text = text!!,
            color = color!!,
            labelIds = labelIds,
            files = files,
            createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
            updatedAt = updatedAt!!.toInstant(ZoneOffset.UTC),
        )
}
