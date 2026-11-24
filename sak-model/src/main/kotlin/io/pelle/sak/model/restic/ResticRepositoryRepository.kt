package io.pelle.sak.model.restic

import io.pelle.sak.model.firstOfOwner
import io.pelle.sak.model.ResourceIds
import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.ResticRepositoriesRecord
import io.pelle.sak.model.jooq.tables.references.RESTIC_BACKUP_PLANS
import io.pelle.sak.model.jooq.tables.references.RESTIC_BACKUP_PLAN_PATHS
import io.pelle.sak.model.jooq.tables.references.RESTIC_REPOSITORIES
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant

// a repository of restic the instance keeps (BACKUP-1), without its token; its files are below its
// resource id (MODEL-8)
data class ResticRepository(
    val id: Int,
    val resourceId: ResourceId,
    val owner: String,
    val name: String,
    val createdAt: Instant,
)

// what the REST backend of a repository checks a login against (BACKUP-2), whose it is, and where its
// files are
data class ResticLogin(
    val token: String,
    val owner: String,
    val resourceId: ResourceId,
) {
    // the token stays out of logs
    override fun toString() = "ResticLogin(owner=***, resourceId=$resourceId)"
}

// what inserting a repository came to
sealed interface ResticRepositoryInsertResult {
    data class Inserted(
        val repository: ResticRepository,
    ) : ResticRepositoryInsertResult

    // the owner has a repository of the name
    data object NameTaken : ResticRepositoryInsertResult
}

// the repositories of restic, for ResticRepositoryService only. A repository belongs to its owner
// only (BACKUP-1, MODEL-6); its token is never part of ResticRepository, so it reaches the api
// only when asked for alone (BACKUP-2). Reads live rows only
class ResticRepositoryRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
    // whether a resource id may be given out beside the database, e.g. no directory of it is left from
    // a restore of the instance (MODEL-8)
    free: (ResourceId) -> Boolean = { true },
    random: SecureRandom = SecureRandom(),
) {
    private val resourceIds = ResourceIds(RESTIC_REPOSITORIES, RESTIC_REPOSITORIES.RESOURCE_ID, free, random)

    // the repositories of the owner, by name
    fun of(owner: String): List<ResticRepository> =
        database.dsl
            .selectFrom(RESTIC_REPOSITORIES)
            .where(RESTIC_REPOSITORIES.OWNER.eq(owner), RESTIC_REPOSITORIES.DELETED_AT.isNull)
            .orderBy(RESTIC_REPOSITORIES.NAME)
            .fetch()
            .map { it.repository() }

    // the resource ids of all repositories, the deleted ones included: their directories are no leftovers
    fun resourceIds(): Set<ResourceId> = resourceIds.all(database.dsl)

    // the token of a repository of the owner
    fun token(
        owner: String,
        id: Int,
    ): String? =
        database.dsl
            .select(RESTIC_REPOSITORIES.TOKEN)
            .from(RESTIC_REPOSITORIES)
            .where(RESTIC_REPOSITORIES.ID.eq(id), RESTIC_REPOSITORIES.OWNER.eq(owner), RESTIC_REPOSITORIES.DELETED_AT.isNull)
            .fetchOne(RESTIC_REPOSITORIES.TOKEN)

    // the token and the resource id of a live repository, whoever owns it: what its REST backend checks a
    // login against
    fun loginOf(id: Int): ResticLogin? =
        database.dsl
            .select(RESTIC_REPOSITORIES.TOKEN, RESTIC_REPOSITORIES.OWNER, RESTIC_REPOSITORIES.RESOURCE_ID)
            .from(RESTIC_REPOSITORIES)
            .where(RESTIC_REPOSITORIES.ID.eq(id), RESTIC_REPOSITORIES.DELETED_AT.isNull)
            .fetchOne()
            ?.let { ResticLogin(it.value1()!!, it.value2()!!, ResourceId(it.value3()!!)) }

    // the first repository of an owner who never had one, deleted ones included (BACKUP-7); null when
    // they had one. One made at the same time, e.g. by another sign-in, wins, the name is unique per
    // owner
    fun insertFirst(
        owner: String,
        name: String,
        token: String,
    ): ResticRepository? = firstOfOwner(database, RESTIC_REPOSITORIES, RESTIC_REPOSITORIES.OWNER, owner) { dsl -> insert(dsl, owner, name, token) }

    // the unique index of the owner and the name decides, so a repository created at the same time
    // is told apart
    fun insert(
        owner: String,
        name: String,
        token: String,
    ): ResticRepositoryInsertResult =
        try {
            ResticRepositoryInsertResult.Inserted(database.dsl.transactionResult { configuration -> insert(DSL.using(configuration), owner, name, token) })
        } catch (_: IntegrityConstraintViolationException) {
            ResticRepositoryInsertResult.NameTaken
        }

    // deletes a repository of the owner, its backup plans of every computer and their paths, in one
    // transaction; they stay as deleted (BACKUP-9). Its token is refused from then on, as loginOf reads
    // live rows only; its files stay until it is purged. False when the owner has no such repository
    fun delete(
        owner: String,
        id: Int,
    ): Boolean =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val now = clock.instant()
            val deleted =
                dsl
                    .update(RESTIC_REPOSITORIES)
                    .set(RESTIC_REPOSITORIES.DELETED_AT, now)
                    .where(RESTIC_REPOSITORIES.ID.eq(id), RESTIC_REPOSITORIES.OWNER.eq(owner), RESTIC_REPOSITORIES.DELETED_AT.isNull)
                    .execute()
            if (deleted == 0) return@transactionResult false
            val plans = dsl.select(RESTIC_BACKUP_PLANS.ID).from(RESTIC_BACKUP_PLANS).where(RESTIC_BACKUP_PLANS.REPOSITORY_ID.eq(id))
            dsl
                .update(RESTIC_BACKUP_PLAN_PATHS)
                .set(RESTIC_BACKUP_PLAN_PATHS.DELETED_AT, now)
                .where(RESTIC_BACKUP_PLAN_PATHS.PLAN_ID.`in`(plans), RESTIC_BACKUP_PLAN_PATHS.DELETED_AT.isNull)
                .execute()
            dsl
                .update(RESTIC_BACKUP_PLANS)
                .set(RESTIC_BACKUP_PLANS.DELETED_AT, now)
                .where(RESTIC_BACKUP_PLANS.REPOSITORY_ID.eq(id), RESTIC_BACKUP_PLANS.DELETED_AT.isNull)
                .execute()
            true
        }

    private fun insert(
        dsl: DSLContext,
        owner: String,
        name: String,
        token: String,
    ): ResticRepository =
        dsl
            .insertInto(RESTIC_REPOSITORIES)
            .set(RESTIC_REPOSITORIES.OWNER, owner)
            .set(RESTIC_REPOSITORIES.NAME, name)
            .set(RESTIC_REPOSITORIES.TOKEN, token)
            .set(RESTIC_REPOSITORIES.RESOURCE_ID, resourceIds.new(dsl).value)
            .set(RESTIC_REPOSITORIES.CREATED_AT, clock.instant())
            .returning()
            .fetchOne()!!
            .repository()

    private fun ResticRepositoriesRecord.repository() = ResticRepository(id!!, ResourceId(resourceId!!), owner!!, name!!, createdAt!!)
}
