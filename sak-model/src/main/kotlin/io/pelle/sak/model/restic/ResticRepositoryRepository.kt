package io.pelle.sak.model.restic

import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.ResticRepositoriesRecord
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
sealed interface ResticInsert {
    data class Inserted(
        val repository: ResticRepository,
    ) : ResticInsert

    // the owner has a repository of the name
    data object NameTaken : ResticInsert
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
    private val free: (ResourceId) -> Boolean = { true },
    private val random: SecureRandom = SecureRandom(),
) {
    // the repositories of the owner, by name
    fun of(owner: String): List<ResticRepository> =
        database.dsl
            .selectFrom(RESTIC_REPOSITORIES)
            .where(RESTIC_REPOSITORIES.OWNER.eq(owner), RESTIC_REPOSITORIES.DELETED_AT.isNull)
            .orderBy(RESTIC_REPOSITORIES.NAME)
            .fetch()
            .map { it.repository() }

    // the resource ids of all repositories, the deleted ones included: their directories are no leftovers
    fun resourceIds(): Set<ResourceId> =
        database.dsl
            .select(RESTIC_REPOSITORIES.RESOURCE_ID)
            .from(RESTIC_REPOSITORIES)
            .fetch(RESTIC_REPOSITORIES.RESOURCE_ID)
            .map { ResourceId(it!!) }
            .toSet()

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
    ): ResticRepository? =
        try {
            database.dsl.transactionResult { configuration ->
                val dsl = DSL.using(configuration)
                if (dsl.fetchExists(RESTIC_REPOSITORIES, RESTIC_REPOSITORIES.OWNER.eq(owner))) return@transactionResult null
                insert(dsl, owner, name, token)
            }
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    // the unique index of the owner and the name decides, so a repository created at the same time
    // is told apart
    fun insert(
        owner: String,
        name: String,
        token: String,
    ): ResticInsert =
        try {
            ResticInsert.Inserted(database.dsl.transactionResult { configuration -> insert(DSL.using(configuration), owner, name, token) })
        } catch (_: IntegrityConstraintViolationException) {
            ResticInsert.NameTaken
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
            .set(RESTIC_REPOSITORIES.RESOURCE_ID, newResourceId(dsl).value)
            .set(RESTIC_REPOSITORIES.CREATED_AT, clock.instant())
            .returning()
            .fetchOne()!!
            .repository()

    // a resource id no repository had, deleted ones included, and free beside the database; drawn
    // again when taken, which 36^12 ids make next to impossible
    private fun newResourceId(dsl: DSLContext): ResourceId =
        generateSequence { ResourceId.random(random) }
            .take(MAX_DRAWS)
            .firstOrNull { !dsl.fetchExists(RESTIC_REPOSITORIES, RESTIC_REPOSITORIES.RESOURCE_ID.eq(it.value)) && free(it) }
            ?: error("no free resource id after $MAX_DRAWS draws")

    private fun ResticRepositoriesRecord.repository() = ResticRepository(id!!, ResourceId(resourceId!!), owner!!, name!!, createdAt!!)

    private companion object {
        const val MAX_DRAWS = 3
    }
}
