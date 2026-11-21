package io.pelle.sak.model.restic

import io.kotest.matchers.shouldBe
import io.pelle.sak.model.SakDatabase
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

    private fun repository(owner: String) = (repositories.insert(owner, "laptop", "token-$owner") as ResticInsert.Inserted).repository.id

    @Test
    fun `a backup plan keeps its repository, its host and its paths in their order`() {
        val laptop = repository(ANNA)
        val values = ResticBackupPlanValues("documents", laptop, "annas-laptop", listOf("/home/anna/Documents", "/home/anna/Notes"))
        val saved = (plans.insert(ANNA, values) as ResticBackupPlanSave.Saved).plan
        saved shouldBe ResticBackupPlan(saved.id, ANNA, "documents", laptop, "annas-laptop", values.paths, now, null)
        plans.of(ANNA) shouldBe listOf(saved)
        plans.byId(BERT, saved.id) shouldBe null
        plans.of(BERT) shouldBe emptyList()
    }

    @Test
    fun `a backup plan names a repository of its owner and a name of its own`() {
        val laptop = repository(ANNA)
        val bertsRepository = repository(BERT)
        plans.insert(ANNA, ResticBackupPlanValues("documents", bertsRepository, "h", listOf("/a"))) shouldBe ResticBackupPlanSave.UnknownRepository
        plans.insert(ANNA, ResticBackupPlanValues("documents", 4711, "h", listOf("/a"))) shouldBe ResticBackupPlanSave.UnknownRepository
        plans.insert(ANNA, ResticBackupPlanValues("documents", laptop, "h", listOf("/a")))
        plans.insert(ANNA, ResticBackupPlanValues("documents", laptop, "h", listOf("/b"))) shouldBe ResticBackupPlanSave.NameTaken
    }

    @Test
    fun `a change replaces the values and the paths, of a backup plan of the owner only`() {
        val laptop = repository(ANNA)
        val id = (plans.insert(ANNA, ResticBackupPlanValues("documents", laptop, "h", listOf("/a", "/b"))) as ResticBackupPlanSave.Saved).plan.id
        plans.insert(ANNA, ResticBackupPlanValues("other", laptop, "h", listOf("/c")))
        // what a run keeps is kept with the plan, none keeps every snapshot (BACKUP-8)
        val changed = ResticBackupPlanValues("papers", laptop, "h2", listOf("/b", "/z"), ResticKeep(daily = 7, monthly = 12))
        plans.update(BERT, id, changed) shouldBe ResticBackupPlanSave.NotFound
        plans.update(ANNA, id, changed.copy(name = "other")) shouldBe ResticBackupPlanSave.NameTaken
        plans.update(ANNA, id, changed.copy(repositoryId = 4711)) shouldBe ResticBackupPlanSave.UnknownRepository
        (plans.update(ANNA, id, changed) as ResticBackupPlanSave.Saved).plan shouldBe
            ResticBackupPlan(id, ANNA, "papers", laptop, "h2", listOf("/b", "/z"), now, now, ResticKeep(daily = 7, monthly = 12))
        (plans.update(ANNA, id, changed.copy(keep = ResticKeep())) as ResticBackupPlanSave.Saved).plan.keep shouldBe ResticKeep()
    }
}
