package io.pelle.sak.model.labels

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.LabelsRecord
import io.pelle.sak.model.jooq.tables.references.LABELS
import org.jooq.exception.IntegrityConstraintViolationException
import java.time.Clock

// the database access of the labels, only LabelService (sak-backend) uses it; it reads the labels that are not
// deleted only
class LabelRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
) {
    fun page(
        offset: Long,
        limit: Int,
    ): List<Label> =
        database.dsl
            .selectFrom(LABELS)
            .where(LABELS.DELETED_AT.isNull)
            .orderBy(LABELS.NAME)
            .limit(limit)
            .offset(offset)
            .fetch()
            .map { it.toLabel() }

    fun count(): Long =
        database.dsl
            .fetchCount(LABELS, LABELS.DELETED_AT.isNull)
            .toLong()

    fun byId(id: Int): Label? =
        database.dsl
            .selectFrom(LABELS)
            .where(LABELS.ID.eq(id), LABELS.DELETED_AT.isNull)
            .fetchOne()
            ?.toLabel()

    // in no particular order, the notes sort the labels of each note themselves
    fun byIds(ids: Collection<Int>): List<Label> =
        database.dsl
            .selectFrom(LABELS)
            .where(LABELS.ID.`in`(ids), LABELS.DELETED_AT.isNull)
            .fetch()
            .map { it.toLabel() }

    fun byNames(names: Collection<String>): List<Label> =
        database.dsl
            .selectFrom(LABELS)
            .where(LABELS.NAME.`in`(names), LABELS.DELETED_AT.isNull)
            .orderBy(LABELS.NAME)
            .fetch()
            .map { it.toLabel() }

    // null when a label of the name exists
    fun insert(name: String): Label? =
        try {
            val now = clock.instant()
            database.dsl
                .insertInto(LABELS)
                .set(LABELS.NAME, name)
                .set(LABELS.CREATED_AT, now)
                .returning()
                .fetchOne()
                ?.toLabel()
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    fun rename(
        id: Int,
        name: String,
    ): LabelRename =
        try {
            database.dsl
                .update(LABELS)
                .set(LABELS.NAME, name)
                .set(LABELS.UPDATED_AT, clock.instant())
                .where(LABELS.ID.eq(id), LABELS.DELETED_AT.isNull)
                .returning()
                .fetchOne()
                ?.let { LabelRename.Renamed(it.toLabel()) }
                ?: LabelRename.NotFound
        } catch (_: IntegrityConstraintViolationException) {
            LabelRename.NameTaken
        }

    // false when there was none
    fun delete(id: Int): Boolean {
        val now = clock.instant()
        return database.dsl
            .update(LABELS)
            .set(LABELS.DELETED_AT, now)
            .where(LABELS.ID.eq(id), LABELS.DELETED_AT.isNull)
            .execute() > 0
    }

    private fun LabelsRecord.toLabel() =
        Label(
            id = id!!,
            name = name!!,
            createdAt = createdAt!!,
            updatedAt = updatedAt,
        )
}
