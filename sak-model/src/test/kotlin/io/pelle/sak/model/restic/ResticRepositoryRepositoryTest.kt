package io.pelle.sak.model.restic

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val ANNA = "anna@example.org"

private const val BERT = "bert@example.org"

class ResticRepositoryRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val now = Instant.parse("2026-11-20T08:00:00Z")

    private val repository by lazy { ResticRepositoryRepository(database, Clock.fixed(now, ZoneOffset.UTC)) }

    private fun s3(
        name: String = "offsite",
        shared: Boolean = false,
    ) = ResticRepositoryValues(
        name,
        shared,
        "s3:https://s3.example.org/backups",
        listOf(ResticEnvValue("AWS_ACCESS_KEY_ID", "AKIA1", secret = false), ResticEnvValue("AWS_SECRET_ACCESS_KEY", "s3cr3t", secret = true)),
        listOf("s3.storage-class" to "STANDARD_IA"),
        limitUploadKib = 20480,
        limitDownloadKib = null,
    )

    private fun inserted(
        owner: String,
        values: ResticRepositoryValues,
    ) = (repository.insert(owner, values) as ResticInsert.Inserted).repository

    @Test
    fun `a repository keeps its values, its secrets only apart`() {
        val offsite = inserted(ANNA, s3())
        offsite shouldBe
            ResticRepository(
                offsite.id,
                ANNA,
                "offsite",
                false,
                "s3:https://s3.example.org/backups",
                listOf(ResticEnv("AWS_ACCESS_KEY_ID", "AKIA1", false), ResticEnv("AWS_SECRET_ACCESS_KEY", null, true)),
                listOf("s3.storage-class" to "STANDARD_IA"),
                20480,
                null,
                now,
                null,
            )
        repository.byId(ANNA, offsite.id) shouldBe offsite
        repository.secrets(ANNA, offsite.id) shouldBe mapOf("AWS_SECRET_ACCESS_KEY" to "s3cr3t")
        offsite.toString() shouldNotContain "s3cr3t"
        s3().toString() shouldNotContain "s3cr3t"
    }

    @Test
    fun `a user sees their own repositories and the shared ones of the others`() {
        val mine = inserted(ANNA, s3("mine"))
        val shared = inserted(BERT, s3("shared", shared = true))
        val private = inserted(BERT, s3("private"))
        repository.visible(ANNA).map { it.name } shouldBe listOf("mine", "shared")
        repository.byId(ANNA, private.id) shouldBe null
        repository.secrets(ANNA, private.id) shouldBe null
        repository.secrets(ANNA, shared.id) shouldBe mapOf("AWS_SECRET_ACCESS_KEY" to "s3cr3t")
        repository.visible(BERT).map { it.name } shouldBe listOf("private", "shared")
        repository.byId(BERT, mine.id) shouldBe null
    }

    @Test
    fun `a name is unique among the repositories of its owner`() {
        inserted(ANNA, s3("offsite"))
        repository.insert(ANNA, s3("offsite")) shouldBe ResticInsert.NameTaken
        repository.insert(BERT, s3("offsite")).shouldBeInstanceOf<ResticInsert.Inserted>()
    }

    @Test
    fun `only the owner changes a repository, its environment and options are replaced`() {
        val offsite = inserted(ANNA, s3("offsite", shared = true))
        inserted(ANNA, s3("other"))
        val local = ResticRepositoryValues("disk", false, "/media/backup", emptyList(), emptyList(), null, null)
        repository.update(BERT, offsite.id, local) shouldBe ResticUpdate.NotOwner
        repository.update(BERT, 4711, local) shouldBe ResticUpdate.NotFound
        repository.update(ANNA, offsite.id, local.copy(name = "other")) shouldBe ResticUpdate.NameTaken
        val changed = (repository.update(ANNA, offsite.id, local) as ResticUpdate.Updated).repository
        changed shouldBe ResticRepository(offsite.id, ANNA, "disk", false, "/media/backup", emptyList(), emptyList(), null, null, now, now)
        repository.secrets(ANNA, offsite.id) shouldBe emptyMap()
        // no longer shared
        repository.byId(BERT, offsite.id) shouldBe null
    }
}
