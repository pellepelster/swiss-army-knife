package io.pelle.sak.model.restic

import io.pelle.sak.model.changesTo
import io.pelle.sak.model.changedAt
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.ResticBackupPlansRecord
import io.pelle.sak.model.jooq.tables.references.RESTIC_BACKUP_PLANS
import io.pelle.sak.model.jooq.tables.references.RESTIC_BACKUP_PLAN_PATHS
import io.pelle.sak.model.jooq.tables.references.RESTIC_REPOSITORIES
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant

// how many of the last, daily, weekly, monthly and yearly snapshots of a backup plan a run keeps;
// none keeps them all (BACKUP-8)
data class ResticKeep(
    val last: Int? = null,
    val daily: Int? = null,
    val weekly: Int? = null,
    val monthly: Int? = null,
    val yearly: Int? = null,
)

// what a backup plan is made of, to insert or to replace (BACKUP-5)
data class ResticBackupPlanValues(
    val name: String,
    val repositoryId: Int,
    // the host name of the computer the paths are on
    val host: String,
    // in their order (BACKUP-6)
    val paths: List<String>,
    val keep: ResticKeep = ResticKeep(),
)

// a backup plan of restic (BACKUP-5)
data class ResticBackupPlan(
    val id: Int,
    val owner: String,
    val name: String,
    val repositoryId: Int,
    val host: String,
    val paths: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant?,
    val keep: ResticKeep = ResticKeep(),
)

// what saving a backup plan came to
sealed interface ResticBackupPlanWriteResult {
    data class Saved(
        val plan: ResticBackupPlan,
    ) : ResticBackupPlanWriteResult

    // the user has no such backup plan
    data object NotFound : ResticBackupPlanWriteResult

    // the repository the backup plan names is none of the owner (API-6)
    data object UnknownRepository : ResticBackupPlanWriteResult

    // the owner has another backup plan of the name
    data object NameTaken : ResticBackupPlanWriteResult
}

// the backup plans of restic and their paths, for ResticBackupPlanService only. A backup plan belongs to its owner
// only, as does its repository (BACKUP-5, MODEL-6). Reads live rows only
class ResticBackupPlanRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
) {
    // the backup plans of the owner, by name
    fun of(owner: String): List<ResticBackupPlan> =
        database.dsl
            .selectFrom(RESTIC_BACKUP_PLANS)
            .where(RESTIC_BACKUP_PLANS.OWNER.eq(owner), RESTIC_BACKUP_PLANS.DELETED_AT.isNull)
            .orderBy(RESTIC_BACKUP_PLANS.NAME)
            .fetch()
            .map { it.toPlan(database.dsl) }

    fun byId(
        owner: String,
        id: Int,
    ): ResticBackupPlan? = record(database.dsl, owner, id)?.toPlan(database.dsl)

    // the unique index of the owner and the name decides, so a backup plan created at the same time is
    // told apart
    fun insert(
        owner: String,
        values: ResticBackupPlanValues,
    ): ResticBackupPlanWriteResult =
        try {
            database.dsl.transactionResult { configuration ->
                val dsl = DSL.using(configuration)
                if (!ownsRepository(dsl, owner, values.repositoryId)) return@transactionResult ResticBackupPlanWriteResult.UnknownRepository
                val now = clock.instant()
                val id =
                    dsl
                        .insertInto(RESTIC_BACKUP_PLANS)
                        .set(RESTIC_BACKUP_PLANS.OWNER, owner)
                        .set(RESTIC_BACKUP_PLANS.NAME, values.name)
                        .set(RESTIC_BACKUP_PLANS.REPOSITORY_ID, values.repositoryId)
                        .set(RESTIC_BACKUP_PLANS.HOST, values.host)
                        .set(keepColumns(values.keep))
                        .set(RESTIC_BACKUP_PLANS.CREATED_AT, now)
                        .returningResult(RESTIC_BACKUP_PLANS.ID)
                        .fetchOne()!!
                        .value1()!!
                insertPaths(dsl, id, owner, values.paths, now)
                ResticBackupPlanWriteResult.Saved(record(dsl, owner, id)!!.toPlan(dsl))
            }
        } catch (_: IntegrityConstraintViolationException) {
            ResticBackupPlanWriteResult.NameTaken
        }

    // replaces the values of a backup plan of the owner; its paths are replaced as a whole
    fun update(
        owner: String,
        id: Int,
        values: ResticBackupPlanValues,
    ): ResticBackupPlanWriteResult =
        try {
            database.dsl.transactionResult { configuration ->
                val dsl = DSL.using(configuration)
                record(dsl, owner, id) ?: return@transactionResult ResticBackupPlanWriteResult.NotFound
                if (!ownsRepository(dsl, owner, values.repositoryId)) return@transactionResult ResticBackupPlanWriteResult.UnknownRepository
                val now = clock.instant()
                // other paths, or the paths in another order, change the plan as its name does
                val pathsChange = paths(dsl, id) != values.paths
                val keepChanges = keepColumns(values.keep).map { (column, value) -> column.changesTo(value) }
                dsl
                    .update(RESTIC_BACKUP_PLANS)
                    .set(RESTIC_BACKUP_PLANS.NAME, values.name)
                    .set(RESTIC_BACKUP_PLANS.REPOSITORY_ID, values.repositoryId)
                    .set(RESTIC_BACKUP_PLANS.HOST, values.host)
                    .set(keepColumns(values.keep))
                    .set(
                        RESTIC_BACKUP_PLANS.UPDATED_AT,
                        changedAt(
                            RESTIC_BACKUP_PLANS.UPDATED_AT,
                            now,
                            RESTIC_BACKUP_PLANS.NAME.changesTo(values.name),
                            RESTIC_BACKUP_PLANS.REPOSITORY_ID.changesTo(values.repositoryId),
                            RESTIC_BACKUP_PLANS.HOST.changesTo(values.host),
                            DSL.condition(pathsChange),
                            *keepChanges.toTypedArray(),
                        ),
                    )
                    .where(RESTIC_BACKUP_PLANS.ID.eq(id), RESTIC_BACKUP_PLANS.OWNER.eq(owner), RESTIC_BACKUP_PLANS.DELETED_AT.isNull)
                    .execute()
                dsl
                    .update(RESTIC_BACKUP_PLAN_PATHS)
                    .set(RESTIC_BACKUP_PLAN_PATHS.DELETED_AT, now)
                    .where(RESTIC_BACKUP_PLAN_PATHS.PLAN_ID.eq(id), RESTIC_BACKUP_PLAN_PATHS.DELETED_AT.isNull)
                    .execute()
                insertPaths(dsl, id, owner, values.paths, now)
                ResticBackupPlanWriteResult.Saved(record(dsl, owner, id)!!.toPlan(dsl))
            }
        } catch (_: IntegrityConstraintViolationException) {
            ResticBackupPlanWriteResult.NameTaken
        }

    private fun ownsRepository(
        dsl: DSLContext,
        owner: String,
        repositoryId: Int,
    ) = dsl.fetchExists(
        RESTIC_REPOSITORIES,
        RESTIC_REPOSITORIES.ID.eq(repositoryId),
        RESTIC_REPOSITORIES.OWNER.eq(owner),
        RESTIC_REPOSITORIES.DELETED_AT.isNull,
    )

    private fun insertPaths(
        dsl: DSLContext,
        id: Int,
        owner: String,
        paths: List<String>,
        now: Instant,
    ) = paths.forEachIndexed { position, path ->
        dsl
            .insertInto(RESTIC_BACKUP_PLAN_PATHS)
            .set(RESTIC_BACKUP_PLAN_PATHS.PLAN_ID, id)
            .set(RESTIC_BACKUP_PLAN_PATHS.OWNER, owner)
            .set(RESTIC_BACKUP_PLAN_PATHS.PATH, path)
            .set(RESTIC_BACKUP_PLAN_PATHS.POSITION, position)
            .set(RESTIC_BACKUP_PLAN_PATHS.CREATED_AT, now)
            .execute()
    }

    private fun record(
        dsl: DSLContext,
        owner: String,
        id: Int,
    ): ResticBackupPlansRecord? =
        dsl
            .selectFrom(RESTIC_BACKUP_PLANS)
            .where(RESTIC_BACKUP_PLANS.ID.eq(id), RESTIC_BACKUP_PLANS.OWNER.eq(owner), RESTIC_BACKUP_PLANS.DELETED_AT.isNull)
            .fetchOne()

    // the live paths of a plan in their order
    private fun paths(
        dsl: DSLContext,
        planId: Int,
    ): List<String> =
        dsl
            .select(RESTIC_BACKUP_PLAN_PATHS.PATH)
            .from(RESTIC_BACKUP_PLAN_PATHS)
            .where(RESTIC_BACKUP_PLAN_PATHS.PLAN_ID.eq(planId), RESTIC_BACKUP_PLAN_PATHS.DELETED_AT.isNull)
            .orderBy(RESTIC_BACKUP_PLAN_PATHS.POSITION)
            .fetch(RESTIC_BACKUP_PLAN_PATHS.PATH)
            .map { it!! }

    private fun ResticBackupPlansRecord.toPlan(dsl: DSLContext): ResticBackupPlan {
        val paths = paths(dsl, id!!)
        val keep = ResticKeep(keepLast, keepDaily, keepWeekly, keepMonthly, keepYearly)
        return ResticBackupPlan(id!!, owner!!, name!!, repositoryId!!, host!!, paths, createdAt!!, updatedAt, keep)
    }

    // the columns of the keep setting (BACKUP-8)
    private fun keepColumns(keep: ResticKeep): Map<Field<Int?>, Int?> =
        mapOf(
            RESTIC_BACKUP_PLANS.KEEP_LAST to keep.last,
            RESTIC_BACKUP_PLANS.KEEP_DAILY to keep.daily,
            RESTIC_BACKUP_PLANS.KEEP_WEEKLY to keep.weekly,
            RESTIC_BACKUP_PLANS.KEEP_MONTHLY to keep.monthly,
            RESTIC_BACKUP_PLANS.KEEP_YEARLY to keep.yearly,
        )
}
