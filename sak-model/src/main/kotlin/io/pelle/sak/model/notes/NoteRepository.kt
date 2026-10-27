package io.pelle.sak.model.notes

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.NotesRecord
import io.pelle.sak.model.jooq.tables.references.NOTES
import io.pelle.sak.model.jooq.tables.references.NOTES_LABELS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

// a note as it is stored, with the ids of its labels
internal data class NoteRow(
    val id: Int,
    val owner: String,
    val text: String,
    val color: String,
    val labelIds: Set<Int>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

// the database access of the notes and the links to their labels, only NoteService uses it; it
// reads and changes the notes of one owner that are not deleted only
internal class NoteRepository(
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
        val labels = labelIds(database.dsl, notes.map { it.id!! })
        return notes.map { it.toRow(labels[it.id].orEmpty()) }
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
            ?.let { it.toRow(labelIds(database.dsl, listOf(id))[id].orEmpty()) }

    fun insert(
        owner: String,
        text: String,
        color: String,
        labelIds: Set<Int>,
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
            insertLabels(dsl, note.id!!, labelIds)
            note.toRow(labelIds)
        }

    // false when there is none
    fun update(
        owner: String,
        id: Int,
        text: String,
        color: String,
        labelIds: Set<Int>,
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
                dsl.deleteFrom(NOTES_LABELS).where(NOTES_LABELS.NOTE_ID.eq(id)).execute()
                insertLabels(dsl, id, labelIds)
            }
            updated
        }

    // false when there was none
    fun delete(
        owner: String,
        id: Int,
        now: Instant,
    ): Boolean =
        database.dsl
            .update(NOTES)
            .set(NOTES.UPDATED_AT, now.utc())
            .set(NOTES.DELETED_AT, now.utc())
            .where(NOTES.ID.eq(id), NOTES.OWNER.eq(owner), NOTES.DELETED_AT.isNull)
            .execute() > 0

    // drops the label from every note
    fun detachLabel(labelId: Int) {
        database.dsl.deleteFrom(NOTES_LABELS).where(NOTES_LABELS.LABEL_ID.eq(labelId)).execute()
    }

    private fun visible(
        owner: String,
        labelId: Int?,
    ): Condition =
        NOTES.OWNER.eq(owner).and(NOTES.DELETED_AT.isNull).and(
            if (labelId == null) {
                DSL.noCondition()
            } else {
                NOTES.ID.`in`(DSL.select(NOTES_LABELS.NOTE_ID).from(NOTES_LABELS).where(NOTES_LABELS.LABEL_ID.eq(labelId)))
            },
        )

    private fun insertLabels(
        dsl: DSLContext,
        noteId: Int,
        labelIds: Set<Int>,
    ) = labelIds.forEach { labelId ->
        dsl
            .insertInto(NOTES_LABELS)
            .set(NOTES_LABELS.NOTE_ID, noteId)
            .set(NOTES_LABELS.LABEL_ID, labelId)
            .execute()
    }

    private fun labelIds(
        dsl: DSLContext,
        noteIds: Collection<Int>,
    ): Map<Int, Set<Int>> =
        dsl
            .selectFrom(NOTES_LABELS)
            .where(NOTES_LABELS.NOTE_ID.`in`(noteIds))
            .fetch()
            .groupBy({ it.noteId!! }, { it.labelId!! })
            .mapValues { it.value.toSet() }

    private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun NotesRecord.toRow(labelIds: Set<Int>) =
        NoteRow(
            id = id!!,
            owner = owner!!,
            text = text!!,
            color = color!!,
            labelIds = labelIds,
            createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
            updatedAt = updatedAt!!.toInstant(ZoneOffset.UTC),
        )
}
