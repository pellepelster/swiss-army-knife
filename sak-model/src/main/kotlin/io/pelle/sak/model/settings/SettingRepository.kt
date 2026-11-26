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
    // its level, whose setting it is: null for the instance, else the address of the user
    val level: String?,
    val createdAt: Instant,
    // who set it (MODEL-5)
    val owner: String,
)

// the settings, for SettingService only; reads live settings only. A setting is of the instance (user
// null) or of one user; every read and write names whose, so a user reaches only their own (MODEL-6)
class SettingRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
) {
    fun byNameAndScope(
        name: String,
        scope: String,
        // null for the setting of the instance
        user: String? = null,
    ): SettingRow? =
        database.dsl
            .selectFrom(SETTINGS)
            .where(SETTINGS.NAME.eq(name), SETTINGS.SCOPE.eq(scope), whose(user), SETTINGS.DELETED_AT.isNull)
            .fetchOne()
            ?.toRow()

    // the settings of the user in the scope, by their names
    fun ofUser(
        scope: String,
        user: String,
    ): Map<String, SettingRow> =
        database.dsl
            .selectFrom(SETTINGS)
            .where(SETTINGS.SCOPE.eq(scope), whose(user), SETTINGS.DELETED_AT.isNull)
            .fetch()
            .map { it.toRow() }
            .associateBy { it.name }

    // null when the name and scope have a setting already
    fun insert(
        name: String,
        scope: String,
        value: String,
        // who creates it (MODEL-5), e.g. SYSTEM_OWNER for a setting the backend generates
        owner: String,
        // null for a setting of the instance
        user: String? = null,
    ): SettingRow? =
        try {
            database.dsl
                .insertInto(SETTINGS)
                .set(SETTINGS.NAME, name)
                .set(SETTINGS.SCOPE, scope)
                .set(SETTINGS.VALUE, value)
                .set(SETTINGS.LEVEL, user)
                .set(SETTINGS.CREATED_AT, clock.instant())
                .set(SETTINGS.OWNER, owner)
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
        // who sets them, the owner of the rows of the new values
        owner: String,
        // null for settings of the instance
        user: String? = null,
    ): Unit =
        database.dsl.transaction { configuration ->
            val dsl = DSL.using(configuration)
            val now = clock.instant()
            values.forEach { (name, value) ->
                val live = SETTINGS.NAME.eq(name).and(SETTINGS.SCOPE.eq(scope)).and(whose(user)).and(SETTINGS.DELETED_AT.isNull)
                if (dsl.fetchExists(SETTINGS, live.and(SETTINGS.VALUE.eq(value)))) return@forEach
                dsl.update(SETTINGS).set(SETTINGS.DELETED_AT, now).where(live).execute()
                dsl
                    .insertInto(SETTINGS)
                    .set(SETTINGS.NAME, name)
                    .set(SETTINGS.SCOPE, scope)
                    .set(SETTINGS.VALUE, value)
                    .set(SETTINGS.LEVEL, user)
                    .set(SETTINGS.CREATED_AT, now)
                    .set(SETTINGS.OWNER, owner)
                    .execute()
            }
        }

    // the setting stays as deleted; the row, null when there was none
    fun delete(
        name: String,
        scope: String,
        // null for the setting of the instance
        user: String? = null,
    ): SettingRow? =
        database.dsl
            .update(SETTINGS)
            .set(SETTINGS.DELETED_AT, clock.instant())
            .where(SETTINGS.NAME.eq(name), SETTINGS.SCOPE.eq(scope), whose(user), SETTINGS.DELETED_AT.isNull)
            .returning()
            .fetchOne()
            ?.toRow()

    // the rows of the instance, or of the user
    private fun whose(user: String?) = if (user == null) SETTINGS.LEVEL.isNull else SETTINGS.LEVEL.eq(user)

    private fun SettingsRecord.toRow() =
        SettingRow(
            id = id!!,
            name = name!!,
            scope = scope!!,
            value = value!!,
            level = level,
            createdAt = createdAt!!,
            owner = owner!!,
        )
}
