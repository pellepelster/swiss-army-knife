package io.pelle.sak.model.restic

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.ResticRepositoriesRecord
import io.pelle.sak.model.jooq.tables.references.RESTIC_REPOSITORIES
import io.pelle.sak.model.jooq.tables.references.RESTIC_REPOSITORY_ENV
import io.pelle.sak.model.jooq.tables.references.RESTIC_REPOSITORY_OPTIONS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant

// a variable of the environment of restic as a repository answers it: a secret of the connection
// without its value (BACKUP-5)
data class ResticEnv(
    val name: String,
    // null for a secret
    val value: String?,
    val secret: Boolean,
)

// a variable of the environment to store, a secret with its value
data class ResticEnvValue(
    val name: String,
    val value: String,
    val secret: Boolean,
) {
    // the value of a secret never reaches a log
    override fun toString() = "ResticEnvValue($name, ${if (secret) "…" else value})"
}

// what a repository is made of, to insert or to replace (BACKUP-1); never a password (BACKUP-4)
data class ResticRepositoryValues(
    val name: String,
    val shared: Boolean,
    val url: String,
    val env: List<ResticEnvValue>,
    val options: List<Pair<String, String>>,
    val limitUploadKib: Int?,
    val limitDownloadKib: Int?,
)

// a repository of restic (BACKUP-1)
data class ResticRepository(
    val id: Int,
    val owner: String,
    val name: String,
    // of all users (BACKUP-6)
    val shared: Boolean,
    val url: String,
    val env: List<ResticEnv>,
    val options: List<Pair<String, String>>,
    val limitUploadKib: Int?,
    val limitDownloadKib: Int?,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

// what inserting a repository came to
sealed interface ResticInsert {
    data class Inserted(
        val repository: ResticRepository,
    ) : ResticInsert

    // the owner has a repository of the name
    data object NameTaken : ResticInsert
}

// what changing a repository came to
sealed interface ResticUpdate {
    data class Updated(
        val repository: ResticRepository,
    ) : ResticUpdate

    // the user sees no such repository
    data object NotFound : ResticUpdate

    // the user sees it, a shared one of another user, but only its owner changes it
    data object NotOwner : ResticUpdate

    // the owner has another repository of the name
    data object NameTaken : ResticUpdate
}

// the repositories of restic and their environment and options, for ResticRepositoryService only.
// A user sees their own repositories and the shared ones of the others (BACKUP-6, MODEL-6), and
// changes their own only. Reads live rows only
class ResticRepositoryRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
) {
    // the repositories the user sees, by name
    fun visible(user: String): List<ResticRepository> {
        val records =
            database.dsl
                .selectFrom(RESTIC_REPOSITORIES)
                .where(RESTIC_REPOSITORIES.DELETED_AT.isNull, sees(user))
                .orderBy(RESTIC_REPOSITORIES.NAME, RESTIC_REPOSITORIES.ID)
                .fetch()
        return records.map { it.toRepository(database.dsl) }
    }

    fun byId(
        user: String,
        id: Int,
    ): ResticRepository? = record(database.dsl, user, id)?.toRepository(database.dsl)

    // the values of the secrets of the connection of a repository the user sees, by their names;
    // never part of ResticRepository so they can't reach a list or a repository of the api
    fun secrets(
        user: String,
        id: Int,
    ): Map<String, String>? {
        record(database.dsl, user, id) ?: return null
        return database.dsl
            .select(RESTIC_REPOSITORY_ENV.NAME, RESTIC_REPOSITORY_ENV.CONTENT)
            .from(RESTIC_REPOSITORY_ENV)
            .where(
                RESTIC_REPOSITORY_ENV.REPOSITORY_ID.eq(id),
                RESTIC_REPOSITORY_ENV.SECRET.isTrue,
                RESTIC_REPOSITORY_ENV.DELETED_AT.isNull,
            ).orderBy(RESTIC_REPOSITORY_ENV.POSITION)
            .fetch()
            .associate { it.value1()!! to it.value2()!! }
    }

    // the unique index of the owner and the name decides, so a repository created at the same time
    // is told apart
    fun insert(
        // who creates it, also of its environment and options (MODEL-5)
        owner: String,
        values: ResticRepositoryValues,
    ): ResticInsert =
        try {
            val id =
                database.dsl.transactionResult { configuration ->
                    val dsl = DSL.using(configuration)
                    val now = clock.instant()
                    val id =
                        dsl
                            .insertInto(RESTIC_REPOSITORIES)
                            .set(RESTIC_REPOSITORIES.OWNER, owner)
                            .set(RESTIC_REPOSITORIES.NAME, values.name)
                            .set(RESTIC_REPOSITORIES.SHARED, values.shared)
                            .set(RESTIC_REPOSITORIES.URL, values.url)
                            .set(RESTIC_REPOSITORIES.LIMIT_UPLOAD_KIB, values.limitUploadKib)
                            .set(RESTIC_REPOSITORIES.LIMIT_DOWNLOAD_KIB, values.limitDownloadKib)
                            .set(RESTIC_REPOSITORIES.CREATED_AT, now)
                            .returningResult(RESTIC_REPOSITORIES.ID)
                            .fetchOne()!!
                            .value1()!!
                    insertParts(dsl, id, owner, values, now)
                    id
                }
            ResticInsert.Inserted(byId(owner, id)!!)
        } catch (_: IntegrityConstraintViolationException) {
            ResticInsert.NameTaken
        }

    // replaces the values of a repository of the user: its environment and options are replaced as
    // a whole, the rows before are deleted
    fun update(
        user: String,
        id: Int,
        values: ResticRepositoryValues,
    ): ResticUpdate {
        val record = record(database.dsl, user, id) ?: return ResticUpdate.NotFound
        if (record.owner != user) return ResticUpdate.NotOwner
        return try {
            database.dsl.transaction { configuration ->
                val dsl = DSL.using(configuration)
                val now = clock.instant()
                dsl
                    .update(RESTIC_REPOSITORIES)
                    .set(RESTIC_REPOSITORIES.NAME, values.name)
                    .set(RESTIC_REPOSITORIES.SHARED, values.shared)
                    .set(RESTIC_REPOSITORIES.URL, values.url)
                    .set(RESTIC_REPOSITORIES.LIMIT_UPLOAD_KIB, values.limitUploadKib)
                    .set(RESTIC_REPOSITORIES.LIMIT_DOWNLOAD_KIB, values.limitDownloadKib)
                    .set(RESTIC_REPOSITORIES.UPDATED_AT, now)
                    .where(RESTIC_REPOSITORIES.ID.eq(id), RESTIC_REPOSITORIES.OWNER.eq(user), RESTIC_REPOSITORIES.DELETED_AT.isNull)
                    .execute()
                dsl
                    .update(RESTIC_REPOSITORY_ENV)
                    .set(RESTIC_REPOSITORY_ENV.DELETED_AT, now)
                    .where(RESTIC_REPOSITORY_ENV.REPOSITORY_ID.eq(id), RESTIC_REPOSITORY_ENV.DELETED_AT.isNull)
                    .execute()
                dsl
                    .update(RESTIC_REPOSITORY_OPTIONS)
                    .set(RESTIC_REPOSITORY_OPTIONS.DELETED_AT, now)
                    .where(RESTIC_REPOSITORY_OPTIONS.REPOSITORY_ID.eq(id), RESTIC_REPOSITORY_OPTIONS.DELETED_AT.isNull)
                    .execute()
                insertParts(dsl, id, user, values, now)
            }
            ResticUpdate.Updated(byId(user, id)!!)
        } catch (_: IntegrityConstraintViolationException) {
            ResticUpdate.NameTaken
        }
    }

    private fun insertParts(
        dsl: DSLContext,
        id: Int,
        owner: String,
        values: ResticRepositoryValues,
        now: Instant,
    ) {
        values.env.forEachIndexed { position, env ->
            dsl
                .insertInto(RESTIC_REPOSITORY_ENV)
                .set(RESTIC_REPOSITORY_ENV.REPOSITORY_ID, id)
                .set(RESTIC_REPOSITORY_ENV.OWNER, owner)
                .set(RESTIC_REPOSITORY_ENV.NAME, env.name)
                .set(RESTIC_REPOSITORY_ENV.CONTENT, env.value)
                .set(RESTIC_REPOSITORY_ENV.SECRET, env.secret)
                .set(RESTIC_REPOSITORY_ENV.POSITION, position)
                .set(RESTIC_REPOSITORY_ENV.CREATED_AT, now)
                .execute()
        }
        values.options.forEachIndexed { position, (name, value) ->
            dsl
                .insertInto(RESTIC_REPOSITORY_OPTIONS)
                .set(RESTIC_REPOSITORY_OPTIONS.REPOSITORY_ID, id)
                .set(RESTIC_REPOSITORY_OPTIONS.OWNER, owner)
                .set(RESTIC_REPOSITORY_OPTIONS.NAME, name)
                .set(RESTIC_REPOSITORY_OPTIONS.CONTENT, value)
                .set(RESTIC_REPOSITORY_OPTIONS.POSITION, position)
                .set(RESTIC_REPOSITORY_OPTIONS.CREATED_AT, now)
                .execute()
        }
    }

    private fun record(
        dsl: DSLContext,
        user: String,
        id: Int,
    ): ResticRepositoriesRecord? =
        dsl
            .selectFrom(RESTIC_REPOSITORIES)
            .where(RESTIC_REPOSITORIES.ID.eq(id), RESTIC_REPOSITORIES.DELETED_AT.isNull, sees(user))
            .fetchOne()

    // the own repositories of the user and the shared ones of the others
    private fun sees(user: String): Condition = RESTIC_REPOSITORIES.OWNER.eq(user).or(RESTIC_REPOSITORIES.SHARED.isTrue)

    private fun ResticRepositoriesRecord.toRepository(dsl: DSLContext): ResticRepository {
        val env =
            dsl
                .selectFrom(RESTIC_REPOSITORY_ENV)
                .where(RESTIC_REPOSITORY_ENV.REPOSITORY_ID.eq(id), RESTIC_REPOSITORY_ENV.DELETED_AT.isNull)
                .orderBy(RESTIC_REPOSITORY_ENV.POSITION)
                .fetch()
                .map { ResticEnv(it.name!!, if (it.secret!!) null else it.content!!, it.secret!!) }
        val options =
            dsl
                .selectFrom(RESTIC_REPOSITORY_OPTIONS)
                .where(RESTIC_REPOSITORY_OPTIONS.REPOSITORY_ID.eq(id), RESTIC_REPOSITORY_OPTIONS.DELETED_AT.isNull)
                .orderBy(RESTIC_REPOSITORY_OPTIONS.POSITION)
                .fetch()
                .map { it.name!! to it.content!! }
        return ResticRepository(
            id!!,
            owner!!,
            name!!,
            shared!!,
            url!!,
            env,
            options,
            limitUploadKib,
            limitDownloadKib,
            createdAt!!,
            updatedAt,
        )
    }
}
