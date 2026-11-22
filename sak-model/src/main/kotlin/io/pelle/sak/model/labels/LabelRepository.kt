package io.pelle.sak.model.labels

import io.pelle.sak.model.changesTo
import io.pelle.sak.model.changedAt
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.LabelsRecord
import io.pelle.sak.model.jooq.tables.references.LABELS
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException
import java.time.Clock
import java.time.Instant

// what links labels, e.g. the notes: a repository of an app. A class, so that its function with
// jOOQ stays in the model (MODEL-2)
abstract class LabelLinks {
    // drops the links of the owner to the label, in the transaction that deletes the label
    internal abstract fun detach(
        dsl: DSLContext,
        owner: String,
        labelId: Int,
        now: Instant,
    )
}

// the labels, for LabelService only; reads the live labels of the owner only
class LabelRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
) {
    fun page(
        owner: String,
        offset: Long,
        limit: Int,
    ): List<Label> =
        database.dsl
            .selectFrom(LABELS)
            .where(LABELS.OWNER.eq(owner), LABELS.DELETED_AT.isNull)
            .orderBy(LABELS.NAME)
            .limit(limit)
            .offset(offset)
            .fetch()
            .map { it.toLabel() }

    fun count(owner: String): Long =
        database.dsl
            .fetchCount(LABELS, LABELS.OWNER.eq(owner), LABELS.DELETED_AT.isNull)
            .toLong()

    fun byId(
        owner: String,
        id: Int,
    ): Label? =
        database.dsl
            .selectFrom(LABELS)
            .where(LABELS.OWNER.eq(owner), LABELS.ID.eq(id), LABELS.DELETED_AT.isNull)
            .fetchOne()
            ?.toLabel()

    // in no particular order, the notes sort the labels of each note themselves
    fun byIds(
        owner: String,
        ids: Collection<Int>,
    ): List<Label> =
        database.dsl
            .selectFrom(LABELS)
            .where(LABELS.OWNER.eq(owner), LABELS.ID.`in`(ids), LABELS.DELETED_AT.isNull)
            .fetch()
            .map { it.toLabel() }

    fun byNames(
        owner: String,
        names: Collection<String>,
    ): List<Label> =
        database.dsl
            .selectFrom(LABELS)
            .where(LABELS.OWNER.eq(owner), LABELS.NAME.`in`(names), LABELS.DELETED_AT.isNull)
            .orderBy(LABELS.NAME)
            .fetch()
            .map { it.toLabel() }

    // null when the owner has a label of the name
    fun insert(
        owner: String,
        name: String,
    ): Label? =
        try {
            val now = clock.instant()
            database.dsl
                .insertInto(LABELS)
                .set(LABELS.OWNER, owner)
                .set(LABELS.NAME, name)
                .set(LABELS.CREATED_AT, now)
                .returning()
                .fetchOne()
                ?.toLabel()
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    fun rename(
        owner: String,
        id: Int,
        name: String,
    ): LabelNameUpdateResult =
        try {
            database.dsl
                .update(LABELS)
                .set(LABELS.NAME, name)
                .set(LABELS.UPDATED_AT, changedAt(LABELS.UPDATED_AT, clock.instant(), LABELS.NAME.changesTo(name)))
                .where(LABELS.OWNER.eq(owner), LABELS.ID.eq(id), LABELS.DELETED_AT.isNull)
                .returning()
                .fetchOne()
                ?.let { LabelNameUpdateResult.Renamed(it.toLabel()) }
                ?: LabelNameUpdateResult.NotFound
        } catch (_: IntegrityConstraintViolationException) {
            LabelNameUpdateResult.NameTaken
        }

    // false when the owner had none. What links labels drops its links to it in the same transaction
    // (SHARED-2), so no link to a deleted label stays
    fun delete(
        owner: String,
        id: Int,
        links: List<LabelLinks> = emptyList(),
    ): Boolean {
        val now = clock.instant()
        return database.dsl.transactionResult { configuration ->
            val dsl = configuration.dsl()
            val deleted =
                dsl
                    .update(LABELS)
                    .set(LABELS.DELETED_AT, now)
                    .where(LABELS.OWNER.eq(owner), LABELS.ID.eq(id), LABELS.DELETED_AT.isNull)
                    .execute() > 0
            if (deleted) links.forEach { it.detach(dsl, owner, id, now) }
            deleted
        }
    }

    private fun LabelsRecord.toLabel() =
        Label(
            id = id!!,
            name = name!!,
            createdAt = createdAt!!,
            updatedAt = updatedAt,
        )
}
