package io.pelle.sak.model.settings

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.SettingsRecord
import io.pelle.sak.model.jooq.tables.references.SETTINGS
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant

// a setting as it is stored, only SettingService (sak-backend) reads it
data class SettingRow(
    val id: Int,
    val name: String,
    // the name of the scope of settings, e.g. 'system'
    val scope: String,
    val value: String,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

// the database access of the settings, only SettingService (sak-backend) uses it; it reads the
// settings that are not deleted only
class SettingRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
) {
    fun byNameAndScope(
        name: String,
        scope: String,
    ): SettingRow? =
        database.dsl
            .selectFrom(SETTINGS)
            .where(SETTINGS.NAME.eq(name), SETTINGS.SCOPE.eq(scope), SETTINGS.DELETED_AT.isNull)
            .fetchOne()
            ?.toRow()

    // null when the name and scope have a setting already
    fun insert(
        name: String,
        scope: String,
        value: String,
    ): SettingRow? =
        try {
            database.dsl
                .insertInto(SETTINGS)
                .set(SETTINGS.NAME, name)
                .set(SETTINGS.SCOPE, scope)
                .set(SETTINGS.VALUE, value)
                .set(SETTINGS.CREATED_AT, clock.instant())
                .returning()
                .fetchOne()
                ?.toRow()
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    // the values of the names, in one transaction: a value replaced stays as a deleted row, an
    // equal one is kept as it is
    fun replace(
        scope: String,
        values: Map<String, String>,
    ): Unit =
        database.dsl.transaction { configuration ->
            val dsl = DSL.using(configuration)
            val now = clock.instant()
            values.forEach { (name, value) ->
                val live = SETTINGS.NAME.eq(name).and(SETTINGS.SCOPE.eq(scope)).and(SETTINGS.DELETED_AT.isNull)
                if (dsl.fetchExists(SETTINGS, live.and(SETTINGS.VALUE.eq(value)))) return@forEach
                dsl.update(SETTINGS).set(SETTINGS.DELETED_AT, now).where(live).execute()
                dsl
                    .insertInto(SETTINGS)
                    .set(SETTINGS.NAME, name)
                    .set(SETTINGS.SCOPE, scope)
                    .set(SETTINGS.VALUE, value)
                    .set(SETTINGS.CREATED_AT, now)
                    .execute()
            }
        }

    // the setting stays as deleted; false when there was none
    fun delete(
        name: String,
        scope: String,
    ): Boolean =
        database.dsl
            .update(SETTINGS)
            .set(SETTINGS.DELETED_AT, clock.instant())
            .where(SETTINGS.NAME.eq(name), SETTINGS.SCOPE.eq(scope), SETTINGS.DELETED_AT.isNull)
            .execute() > 0

    private fun SettingsRecord.toRow() =
        SettingRow(
            id = id!!,
            name = name!!,
            scope = scope!!,
            value = value!!,
            createdAt = createdAt!!,
            updatedAt = updatedAt,
        )
}
