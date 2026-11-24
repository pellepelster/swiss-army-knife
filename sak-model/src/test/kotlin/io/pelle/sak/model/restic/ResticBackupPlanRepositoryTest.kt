package io.pelle.sak.model.restic

import io.kotest.matchers.shouldBe
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.RESTIC_BACKUP_PLANS
import io.pelle.sak.model.jooq.tables.references.RESTIC_BACKUP_PLAN_PATHS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val ANNA = "anna@example.org"

private const val BERT = "bert@example.org"

class ResticBackupPlanRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-11-21T08:00:00Z")

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val repositories by lazy { ResticRepositoryRepository(database, Clock.fixed(now, ZoneOffset.UTC)) }

    private val plans by lazy { ResticBackupPlanRepository(database, Clock.fixed(now, ZoneOffset.UTC)) }

    private fun repository(owner: String) = (repositories.insert(owner, "laptop", "token-$owner") as ResticRepositoryInsertResult.Inserted).repository.id

    @Test
    fun `a backup plan keeps its repository, its host and its paths in their order`() {
        val laptop = repository(ANNA)
        val values = ResticBackupPlanValues("documents", laptop, "annas-laptop", listOf("/home/anna/Documents", "/home/anna/Notes"))
        val saved = (plans.insert(ANNA, values) as ResticBackupPlanWriteResult.Saved).plan
        saved shouldBe ResticBackupPlan(saved.id, ANNA, "documents", laptop, "annas-laptop", values.paths, now, null)
        plans.of(ANNA) shouldBe listOf(saved)
        plans.byId(BERT, saved.id) shouldBe null
        plans.of(BERT) shouldBe emptyList()
    }

    @Test
    fun `a backup plan names a repository of its owner and a name of its own`() {
        val laptop = repository(ANNA)
        val bertsRepository = repository(BERT)
        plans.insert(ANNA, ResticBackupPlanValues("documents", bertsRepository, "h", listOf("/a"))) shouldBe ResticBackupPlanWriteResult.UnknownRepository
        plans.insert(ANNA, ResticBackupPlanValues("documents", 4711, "h", listOf("/a"))) shouldBe ResticBackupPlanWriteResult.UnknownRepository
        plans.insert(ANNA, ResticBackupPlanValues("documents", laptop, "h", listOf("/a")))
        plans.insert(ANNA, ResticBackupPlanValues("documents", laptop, "h", listOf("/b"))) shouldBe ResticBackupPlanWriteResult.NameTaken
    }

    @Test
    fun `a change replaces the values and the paths, of a backup plan of the owner only`() {
        val laptop = repository(ANNA)
        val id = (plans.insert(ANNA, ResticBackupPlanValues("documents", laptop, "h", listOf("/a", "/b"))) as ResticBackupPlanWriteResult.Saved).plan.id
        plans.insert(ANNA, ResticBackupPlanValues("other", laptop, "h", listOf("/c")))
        // what a run keeps is kept with the plan, none keeps every snapshot (BACKUP-8)
        val changed = ResticBackupPlanValues("papers", laptop, "h2", listOf("/b", "/z"), ResticKeep(daily = 7, monthly = 12))
        plans.update(BERT, id, changed) shouldBe ResticBackupPlanWriteResult.NotFound
        plans.update(ANNA, id, changed.copy(name = "other")) shouldBe ResticBackupPlanWriteResult.NameTaken
        plans.update(ANNA, id, changed.copy(repositoryId = 4711)) shouldBe ResticBackupPlanWriteResult.UnknownRepository
        (plans.update(ANNA, id, changed) as ResticBackupPlanWriteResult.Saved).plan shouldBe
            ResticBackupPlan(id, ANNA, "papers", laptop, "h2", listOf("/b", "/z"), now, now, ResticKeep(daily = 7, monthly = 12))
        (plans.update(ANNA, id, changed.copy(keep = ResticKeep())) as ResticBackupPlanWriteResult.Saved).plan.keep shouldBe ResticKeep()
    }

    @Test
    fun `a delete keeps the backup plan and its paths as deleted, of the owner only`() {
        val laptop = repository(ANNA)
        val id = (plans.insert(ANNA, ResticBackupPlanValues("documents", laptop, "h", listOf("/a", "/b"))) as ResticBackupPlanWriteResult.Saved).plan.id

        plans.delete(BERT, id) shouldBe false
        plans.byId(ANNA, id)?.name shouldBe "documents"
        plans.delete(ANNA, id) shouldBe true
        plans.delete(ANNA, id) shouldBe false
        plans.byId(ANNA, id) shouldBe null
        plans.of(ANNA) shouldBe emptyList()
        // kept as deleted, the plan and each of its paths
        database.dsl.fetchCount(RESTIC_BACKUP_PLANS, RESTIC_BACKUP_PLANS.ID.eq(id), RESTIC_BACKUP_PLANS.DELETED_AT.isNotNull) shouldBe 1
        database.dsl.fetchCount(RESTIC_BACKUP_PLAN_PATHS, RESTIC_BACKUP_PLAN_PATHS.PLAN_ID.eq(id), RESTIC_BACKUP_PLAN_PATHS.DELETED_AT.isNull) shouldBe 0
        database.dsl.fetchCount(RESTIC_BACKUP_PLAN_PATHS, RESTIC_BACKUP_PLAN_PATHS.PLAN_ID.eq(id)) shouldBe 2
        // the name is free again
        (plans.insert(ANNA, ResticBackupPlanValues("documents", laptop, "h", listOf("/a"))) is ResticBackupPlanWriteResult.Saved) shouldBe true
    }

    @Test
    fun `a deleted repository takes its backup plans of every computer and their paths, of the owner only (BACKUP-9)`() {
        val laptop = repository(ANNA)
        val other = (repositories.insert(ANNA, "nas", "token-nas") as ResticRepositoryInsertResult.Inserted).repository.id
        val here = (plans.insert(ANNA, ResticBackupPlanValues("documents", laptop, "annas-laptop", listOf("/a", "/b"))) as ResticBackupPlanWriteResult.Saved).plan.id
        val there = (plans.insert(ANNA, ResticBackupPlanValues("photos", laptop, "annas-desktop", listOf("/c"))) as ResticBackupPlanWriteResult.Saved).plan.id
        val kept = (plans.insert(ANNA, ResticBackupPlanValues("music", other, "annas-laptop", listOf("/d"))) as ResticBackupPlanWriteResult.Saved).plan.id

        repositories.delete(BERT, laptop) shouldBe false
        repositories.of(ANNA).map { it.id } shouldBe listOf(laptop, other)
        repositories.delete(ANNA, laptop) shouldBe true
        repositories.delete(ANNA, laptop) shouldBe false

        repositories.of(ANNA).map { it.id } shouldBe listOf(other)
        // its token is refused from then on, and it is no reason for a first repository (BACKUP-7)
        repositories.loginOf(laptop) shouldBe null
        repositories.insertFirst(ANNA, "my-backups", "token-first") shouldBe null
        plans.of(ANNA).map { it.id } shouldBe listOf(kept)
        // kept as deleted, the repository, its plans and each of their paths (MODEL-3)
        database.dsl.fetchCount(RESTIC_BACKUP_PLANS, RESTIC_BACKUP_PLANS.ID.`in`(here, there), RESTIC_BACKUP_PLANS.DELETED_AT.isNotNull) shouldBe 2
        database.dsl.fetchCount(RESTIC_BACKUP_PLAN_PATHS, RESTIC_BACKUP_PLAN_PATHS.PLAN_ID.`in`(here, there), RESTIC_BACKUP_PLAN_PATHS.DELETED_AT.isNotNull) shouldBe 3
        database.dsl.fetchCount(RESTIC_BACKUP_PLAN_PATHS, RESTIC_BACKUP_PLAN_PATHS.PLAN_ID.eq(kept), RESTIC_BACKUP_PLAN_PATHS.DELETED_AT.isNull) shouldBe 1
        // the name is free again
        (repositories.insert(ANNA, "laptop", "token-again") is ResticRepositoryInsertResult.Inserted) shouldBe true
    }

    @Test
    fun `the excludes are kept with the plan, a change of them is a change of the plan`() {
        val laptop = repository(ANNA)
        val values = ResticBackupPlanValues("documents", laptop, "h", listOf("/a"))
        val saved = (plans.insert(ANNA, values) as ResticBackupPlanWriteResult.Saved).plan
        // a plan made without leaves out nothing
        saved.excludes shouldBe ResticExcludes("", caches = false)

        val excludes = ResticExcludes("# caches\n\$HOME/.cache\n\nnode_modules", caches = true)
        val changed = (plans.update(ANNA, saved.id, values.copy(excludes = excludes)) as ResticBackupPlanWriteResult.Saved).plan
        changed.excludes shouldBe excludes
        changed.updatedAt shouldBe now
        plans.byId(ANNA, saved.id)!!.excludes shouldBe excludes
    }
}
