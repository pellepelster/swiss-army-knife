package io.pelle.sak.model.labels

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.LabelsRecord
import io.pelle.sak.model.jooq.tables.references.LABELS
import org.jooq.exception.IntegrityConstraintViolationException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

// the database access of the labels, only LabelService (sak-backend) uses it; it reads the labels that are not
// deleted only
class LabelRepository(
    private val database: SakDatabase,
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

    fun byIds(ids: Collection<Int>): List<Label> =
        database.dsl
            .selectFrom(LABELS)
            .where(LABELS.ID.`in`(ids), LABELS.DELETED_AT.isNull)
            .orderBy(LABELS.NAME)
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
    fun insert(
        name: String,
        now: Instant,
    ): Label? =
        try {
            database.dsl
                .insertInto(LABELS)
                .set(LABELS.NAME, name)
                .set(LABELS.ACTIVE, ACTIVE)
                .set(LABELS.CREATED_AT, now.utc())
                .set(LABELS.UPDATED_AT, now.utc())
                .returning()
                .fetchOne()
                ?.toLabel()
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    // false when another label has the name
    fun rename(
        id: Int,
        name: String,
        now: Instant,
    ): Boolean =
        try {
            database.dsl
                .update(LABELS)
                .set(LABELS.NAME, name)
                .set(LABELS.UPDATED_AT, now.utc())
                .where(LABELS.ID.eq(id), LABELS.DELETED_AT.isNull)
                .execute()
            true
        } catch (_: IntegrityConstraintViolationException) {
            false
        }

    // false when there was none
    fun delete(
        id: Int,
        now: Instant,
    ): Boolean =
        database.dsl
            .update(LABELS)
            .set(LABELS.ACTIVE, null as Int?)
            .set(LABELS.UPDATED_AT, now.utc())
            .set(LABELS.DELETED_AT, now.utc())
            .where(LABELS.ID.eq(id), LABELS.DELETED_AT.isNull)
            .execute() > 0

    private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun LabelsRecord.toLabel() =
        Label(
            id = id!!,
            name = name!!,
            createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
            updatedAt = updatedAt!!.toInstant(ZoneOffset.UTC),
        )

    companion object {
        private const val ACTIVE = 1
    }
}
