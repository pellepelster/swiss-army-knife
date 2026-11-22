package io.pelle.sak.model.notes

import io.pelle.sak.model.changesTo
import io.pelle.sak.model.changedAt
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.labels.Label
import io.pelle.sak.model.labels.LabelLinks
import io.pelle.sak.model.jooq.tables.records.NotesRecord
import io.pelle.sak.model.jooq.tables.references.FILES
import io.pelle.sak.model.jooq.tables.references.LABELS
import io.pelle.sak.model.jooq.tables.references.NOTES
import io.pelle.sak.model.jooq.tables.references.NOTE_LISTS
import io.pelle.sak.model.jooq.tables.references.NOTES_LABELS
import io.pelle.sak.model.jooq.tables.references.NOTES_FILES
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant


// a note as it is stored, with the ids of its labels and the ids of its files (its photos and its
// recordings) in their order
data class NoteRow(
    val id: Int,
    val owner: String,
    // the list it is in
    val listId: Int,
    val text: String,
    val color: String,
    val labelIds: Set<Int>,
    val fileIds: List<Int>,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

// what saving a note gives: the note as it is stored, or what it refers to that is not the owner's
sealed interface NoteWriteResult {
    data class Saved(
        val note: NoteRow,
    ) : NoteWriteResult

    // a change of a note that is not there
    data object NotFound : NoteWriteResult

    // a new note in a list that is no live list of the owner
    data object NoList : NoteWriteResult

    // files that are no live files of the owner
    data class UnknownFiles(
        val fileIds: Set<Int>,
    ) : NoteWriteResult
}

// the notes and their links to labels and files, for NoteService only; reads and changes the live
// notes of one owner. A link removed, also of a deleted note, stays with deleted_at set
class NoteRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
) : LabelLinks() {
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
        val fileIds = fileIds(database.dsl, ids)
        return notes.map { it.toRow(labels[it.id].orEmpty(), fileIds[it.id].orEmpty()) }
    }

    fun count(
        owner: String,
        labelId: Int?,
    ): Long = database.dsl.fetchCount(NOTES, visible(owner, labelId)).toLong()

    // the live labels a live note of the owner has, by name: the ones the notes filter by
    fun usedLabels(owner: String): List<Label> =
        database.dsl
            .selectFrom(LABELS)
            .where(
                LABELS.DELETED_AT.isNull,
                LABELS.ID.`in`(
                    DSL
                        .select(NOTES_LABELS.LABEL_ID)
                        .from(NOTES_LABELS)
                        .join(NOTES)
                        .on(NOTES.ID.eq(NOTES_LABELS.NOTE_ID))
                        .where(NOTES_LABELS.DELETED_AT.isNull, visible(owner, null)),
                ),
            ).orderBy(LABELS.NAME)
            .fetch { Label(it.id!!, it.name!!, it.createdAt!!, it.updatedAt) }

    fun byId(
        owner: String,
        id: Int,
    ): NoteRow? =
        database.dsl
            .selectFrom(NOTES)
            .where(NOTES.ID.eq(id), NOTES.OWNER.eq(owner), NOTES.DELETED_AT.isNull)
            .fetchOne()
            ?.let { it.toRow(labelIds(database.dsl, listOf(id))[id].orEmpty(), fileIds(database.dsl, listOf(id))[id].orEmpty()) }

    // the oldest list of the owner, null when they have none
    fun ownList(owner: String): Int? =
        database.dsl
            .select(NOTE_LISTS.ID)
            .from(NOTE_LISTS)
            .where(NOTE_LISTS.OWNER.eq(owner), NOTE_LISTS.DELETED_AT.isNull)
            .orderBy(NOTE_LISTS.ID)
            .limit(1)
            .fetchOne(NOTE_LISTS.ID)

    // the first list of an owner who never had a list of their own, deleted ones included (LIST-1,
    // NOTES-1); a list made at the same time, e.g. by another sign-in, wins, the name is unique per
    // owner
    fun ensureList(
        owner: String,
        name: String,
    ) {
        database.dsl.transaction { configuration ->
            val dsl = DSL.using(configuration)
            if (dsl.fetchExists(NOTE_LISTS, NOTE_LISTS.OWNER.eq(owner))) return@transaction
            dsl
                .insertInto(NOTE_LISTS)
                .set(NOTE_LISTS.OWNER, owner)
                .set(NOTE_LISTS.NAME, name)
                .set(NOTE_LISTS.SHARED, false)
                .set(NOTE_LISTS.CREATED_AT, clock.instant())
                .onConflictDoNothing()
                .execute()
        }
    }

    // a new note in the list. The list and the files are checked to be the owner's in the transaction of
    // the insert (MODEL-6); a label that is not the owner's live one, e.g. deleted in the same moment, is
    // left out
    fun insert(
        owner: String,
        listId: Int,
        text: String,
        color: String,
        labelIds: Set<Int>,
        fileIds: List<Int>,
    ): NoteWriteResult =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val now = clock.instant()
            if (!dsl.fetchExists(NOTE_LISTS, NOTE_LISTS.ID.eq(listId), NOTE_LISTS.OWNER.eq(owner), NOTE_LISTS.DELETED_AT.isNull)) {
                return@transactionResult NoteWriteResult.NoList
            }
            unknownFiles(dsl, owner, fileIds).takeIf { it.isNotEmpty() }?.let { return@transactionResult NoteWriteResult.UnknownFiles(it) }
            val labels = ownLabels(dsl, owner, labelIds)
            val note =
                dsl
                    .insertInto(NOTES)
                    .set(NOTES.OWNER, owner)
                    .set(NOTES.LIST_ID, listId)
                    .set(NOTES.TEXT, text)
                    .set(NOTES.COLOR, color)
                    .set(NOTES.CREATED_AT, now)
                    .returning()
                    .fetchOne()!!
            labels.forEach { insertLabel(dsl, note.id!!, it, now, owner) }
            fileIds.forEachIndexed { position, fileId -> insertFile(dsl, note.id!!, fileId, position, now, owner) }
            NoteWriteResult.Saved(note.toRow(labels, fileIds))
        }

    // the note as it is stored now; the files and the labels are checked as for a new note
    fun update(
        owner: String,
        id: Int,
        text: String,
        color: String,
        labelIds: Set<Int>,
        fileIds: List<Int>,
    ): NoteWriteResult =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val now = clock.instant()
            unknownFiles(dsl, owner, fileIds).takeIf { it.isNotEmpty() }?.let { return@transactionResult NoteWriteResult.UnknownFiles(it) }
            val labels = ownLabels(dsl, owner, labelIds)
            // other labels or files, or the files in another order, change the note as its text does
            val linksChange = labelIds(dsl, listOf(id))[id].orEmpty() != labels || fileIds(dsl, listOf(id))[id].orEmpty() != fileIds
            val note =
                dsl
                    .update(NOTES)
                    .set(NOTES.TEXT, text)
                    .set(NOTES.COLOR, color)
                    .set(
                        NOTES.UPDATED_AT,
                        changedAt(NOTES.UPDATED_AT, now, NOTES.TEXT.changesTo(text), NOTES.COLOR.changesTo(color), DSL.condition(linksChange)),
                    )
                    .where(NOTES.ID.eq(id), NOTES.OWNER.eq(owner), NOTES.DELETED_AT.isNull)
                    .returning()
                    .fetchOne()
                    ?: return@transactionResult NoteWriteResult.NotFound
            updateLabels(dsl, id, labels, now, owner)
            updateFiles(dsl, id, fileIds, now, owner)
            NoteWriteResult.Saved(note.toRow(labels, fileIds))
        }

    // the ones among the ids that are live labels of the owner
    private fun ownLabels(
        dsl: DSLContext,
        owner: String,
        labelIds: Set<Int>,
    ): Set<Int> =
        if (labelIds.isEmpty()) {
            emptySet()
        } else {
            dsl
                .select(LABELS.ID)
                .from(LABELS)
                .where(LABELS.ID.`in`(labelIds), LABELS.OWNER.eq(owner), LABELS.DELETED_AT.isNull)
                .fetchSet(LABELS.ID)
                .filterNotNull()
                .toSet()
        }

    // the ones among the ids that are no live files of the owner
    private fun unknownFiles(
        dsl: DSLContext,
        owner: String,
        fileIds: List<Int>,
    ): Set<Int> =
        if (fileIds.isEmpty()) {
            emptySet()
        } else {
            fileIds.toSet() -
                dsl
                    .select(FILES.ID)
                    .from(FILES)
                    .where(FILES.ID.`in`(fileIds), FILES.OWNER.eq(owner), FILES.DELETED_AT.isNull)
                    .fetchSet(FILES.ID)
                    .filterNotNull()
                    .toSet()
        }

    // the note as it was, null when there was none; its links to its labels and files are deleted with it
    fun delete(
        owner: String,
        id: Int,
    ): NoteRow? {
        // the note as it was, with its labels and files: the delete takes their links
        val note = byId(owner, id) ?: return null
        return database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val now = clock.instant()
            val deleted =
                dsl
                    .update(NOTES)
                    .set(NOTES.DELETED_AT, now)
                    .where(NOTES.ID.eq(id), NOTES.OWNER.eq(owner), NOTES.DELETED_AT.isNull)
                    .execute() > 0
            if (deleted) {
                deleteLabels(dsl, NOTES_LABELS.NOTE_ID.eq(id), now)
                deleteFiles(dsl, NOTES_FILES.NOTE_ID.eq(id), now)
            }
            note.takeIf { deleted }
        }
    }

    // drops the label of the owner from every note of the owner
    internal override fun detach(
        dsl: DSLContext,
        owner: String,
        labelId: Int,
        now: Instant,
    ) = deleteLabels(dsl, NOTES_LABELS.LABEL_ID.eq(labelId).and(NOTES_LABELS.OWNER.eq(owner)), now)

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
        // the owner of the note, who links the label or the file
        owner: String,
    ) {
        val links =
            dsl
                .selectFrom(NOTES_LABELS)
                .where(NOTES_LABELS.NOTE_ID.eq(noteId), NOTES_LABELS.DELETED_AT.isNull)
                .fetch()
                .associateBy { it.labelId!! }
        deleteLabels(dsl, NOTES_LABELS.ID.`in`(links.filterKeys { it !in labelIds }.values.map { it.id }), now)
        (labelIds - links.keys).forEach { insertLabel(dsl, noteId, it, now, owner) }
    }

    private fun insertLabel(
        dsl: DSLContext,
        noteId: Int,
        labelId: Int,
        now: Instant,
        // the owner of the note, who links the label or the file
        owner: String,
    ) {
        dsl
            .insertInto(NOTES_LABELS)
            .set(NOTES_LABELS.NOTE_ID, noteId)
            .set(NOTES_LABELS.LABEL_ID, labelId)
            .set(NOTES_LABELS.CREATED_AT, now)
            .set(NOTES_LABELS.OWNER, owner)
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
            .set(NOTES_LABELS.DELETED_AT, now)
            .where(condition, NOTES_LABELS.DELETED_AT.isNull)
            .execute()
    }

    // the links of the files that stay keep their row and take their new position, the ones of the
    // files removed are deleted, the files added get a link of their own
    private fun updateFiles(
        dsl: DSLContext,
        noteId: Int,
        fileIds: List<Int>,
        now: Instant,
        // the owner of the note, who links the label or the file
        owner: String,
    ) {
        val links =
            dsl
                .selectFrom(NOTES_FILES)
                .where(NOTES_FILES.NOTE_ID.eq(noteId), NOTES_FILES.DELETED_AT.isNull)
                .fetch()
                .associateBy { it.fileId!! }
        deleteFiles(dsl, NOTES_FILES.ID.`in`(links.filterKeys { it !in fileIds }.values.map { it.id }), now)
        fileIds.forEachIndexed { position, fileId ->
            val link = links[fileId]
            if (link == null) {
                insertFile(dsl, noteId, fileId, position, now, owner)
            } else if (link.position != position) {
                dsl
                    .update(NOTES_FILES)
                    .set(NOTES_FILES.POSITION, position)
                    .set(NOTES_FILES.UPDATED_AT, now)
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
        // the owner of the note, who links the label or the file
        owner: String,
    ) {
        dsl
            .insertInto(NOTES_FILES)
            .set(NOTES_FILES.NOTE_ID, noteId)
            .set(NOTES_FILES.FILE_ID, fileId)
            .set(NOTES_FILES.POSITION, position)
            .set(NOTES_FILES.CREATED_AT, now)
            .set(NOTES_FILES.OWNER, owner)
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
            .set(NOTES_FILES.DELETED_AT, now)
            .where(condition, NOTES_FILES.DELETED_AT.isNull)
            .execute()
    }

    private fun fileIds(
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

    private fun NotesRecord.toRow(
        labelIds: Set<Int>,
        fileIds: List<Int>,
    ) =
        NoteRow(
            id = id!!,
            owner = owner!!,
            listId = listId!!,
            text = text!!,
            color = color!!,
            labelIds = labelIds,
            fileIds = fileIds,
            createdAt = createdAt!!,
            updatedAt = updatedAt,
        )
}
