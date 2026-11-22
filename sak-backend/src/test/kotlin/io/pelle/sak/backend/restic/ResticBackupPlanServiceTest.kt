package io.pelle.sak.backend.restic

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.restic.ResticBackupPlanValues
import io.pelle.sak.model.restic.ResticKeep
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private const val ANNA = "anna@example.org"

private const val BERT = "bert@example.org"

class ResticBackupPlanServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val repositories by lazy { ResticRepositoryService(database, mayLogin = { true }) }

    private val plans by lazy { ResticBackupPlanService(database) }

    private fun repository() = (repositories.create(ANNA, "laptop") as ResticRepositoryCreateResult.Created).repository.id

    private fun errors(result: ResticBackupPlanSaveResult) = (result as ResticBackupPlanSaveResult.Invalid).errors.map { it.key.code }

    @Test
    fun `a backup plan has a name, the host name of its computer and absolute paths, each once`() {
        val laptop = repository()
        fun plan(
            name: String = "documents",
            host: String = "annas-laptop",
            paths: List<String> = listOf("/home/anna/Documents"),
            keep: ResticKeep = ResticKeep(),
        ) = plans.create(ANNA, ResticBackupPlanValues(name, laptop, host, paths, keep))

        plan(host = " annas-laptop.local ", paths = listOf(" /home/anna/Documents ")).shouldBeInstanceOf<ResticBackupPlanSaveResult.Saved>().plan.let {
            it.host shouldBe "annas-laptop.local"
            it.paths shouldBe listOf("/home/anna/Documents")
        }
        errors(plan(name = "Documents!")) shouldBe listOf("restic.invalid_name")
        listOf("", "-laptop", "lap top", "a".repeat(254)).forEach { host -> errors(plan(name = "x", host = host)) shouldBe listOf("restic.invalid_host") }
        errors(plan(name = "x", paths = emptyList())) shouldBe listOf("restic.no_paths")
        errors(plan(name = "x", paths = listOf("/a", "/a"))) shouldBe listOf("restic.double_path")
        // as restic keeps them in a snapshot (BK-11)
        plan(name = "clean", paths = listOf("/home/anna/Documents/", "/home//anna/./Music", "/home/anna/../bert", "/")).shouldBeInstanceOf<ResticBackupPlanSaveResult.Saved>().plan.paths shouldBe
            listOf("/home/anna/Documents", "/home/anna/Music", "/home/bert", "/")
        errors(plan(name = "x", paths = listOf("/a", "/a/"))) shouldBe listOf("restic.double_path")
        errors(plan(name = "x", paths = listOf("/a", "relative", "/b\n"))) shouldBe listOf("restic.invalid_path")
        errors(plan(name = "x", paths = (0..ResticBackupPlanService.MAX_PATHS).map { "/p$it" })) shouldBe listOf("restic.too_many_paths")
        // how many snapshots a run keeps: from 1 to 1000 of a kind, none keeps them all (BACKUP-8)
        listOf(ResticKeep(daily = 0), ResticKeep(yearly = ResticBackupPlanService.MAX_KEEP + 1), ResticKeep(last = -1)).forEach { keep ->
            errors(plan(name = "x", keep = keep)) shouldBe listOf("restic.invalid_keep")
        }
        plan(name = "kept", keep = ResticKeep(daily = 7, weekly = 4, monthly = 12)).shouldBeInstanceOf<ResticBackupPlanSaveResult.Saved>().plan.keep shouldBe
            ResticKeep(daily = 7, weekly = 4, monthly = 12)
        plan() shouldBe ResticBackupPlanSaveResult.Exists
    }

    @Test
    fun `a backup plan is changed only by its owner, to a free name and a repository of the owner`() {
        val laptop = repository()
        val berts = (repositories.create(BERT, "laptop") as ResticRepositoryCreateResult.Created).repository.id
        fun values(
            name: String,
            repository: Int = laptop,
        ) = ResticBackupPlanValues(name, repository, "annas-laptop", listOf("/home/anna"), ResticKeep())
        val documents = plans.create(ANNA, values("documents")).shouldBeInstanceOf<ResticBackupPlanSaveResult.Saved>().plan
        plans.create(ANNA, values("music")).shouldBeInstanceOf<ResticBackupPlanSaveResult.Saved>()

        plans.update(BERT, documents.id, values("documents", berts)) shouldBe ResticBackupPlanSaveResult.NotFound
        plans.update(ANNA, documents.id, values("music")) shouldBe ResticBackupPlanSaveResult.Exists
        plans.update(ANNA, documents.id, values("documents", berts)) shouldBe ResticBackupPlanSaveResult.UnknownRepository
        plans.create(ANNA, values("photos", berts)) shouldBe ResticBackupPlanSaveResult.UnknownRepository
        plans.update(ANNA, 4711, values("other")) shouldBe ResticBackupPlanSaveResult.NotFound
        plans.update(ANNA, documents.id, values("papers")).shouldBeInstanceOf<ResticBackupPlanSaveResult.Saved>().plan.name shouldBe "papers"
        plans.plans(ANNA).map { it.name } shouldBe listOf("music", "papers")
        plans.plans(BERT) shouldBe emptyList()
    }
}
