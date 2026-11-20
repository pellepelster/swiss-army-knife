package io.pelle.sak.backend.restic

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private const val ANNA = "anna@example.org"

private const val BERT = "bert@example.org"

class ResticRepositoryServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val service by lazy { ResticRepositoryService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    private fun s3(
        name: String = "offsite",
        url: String = "s3:https://s3.example.org/backups",
        shared: Boolean = false,
        env: List<ResticEnvInput> =
            listOf(ResticEnvInput("AWS_ACCESS_KEY_ID", value = "AKIA1"), ResticEnvInput("AWS_SECRET_ACCESS_KEY", secret = "s3cr3t")),
        options: List<Pair<String, String>> = emptyList(),
        upload: String? = null,
    ) = ResticInput(name, shared, url, env, options, upload, null)

    private fun local(
        url: String = "/media/backup/sak",
        shared: Boolean = false,
    ) = ResticInput("disk", shared, url, emptyList(), emptyList(), null, null)

    // the codes of the errors of an invalid input
    private fun errors(result: ResticResult) = (result as ResticResult.Invalid).errors.map { it.key.code }

    @Test
    fun `an s3 repository is stored with https, its bucket and its path`() {
        val saved = (service.create(ANNA, s3(url = "s3:s3.example.org:9000/backups/sak/", upload = "20MiB/s")) as ResticResult.Saved).repository
        saved.url shouldBe "s3:https://s3.example.org:9000/backups/sak"
        saved.limitUploadKib shouldBe 20480
        service.secrets(ANNA, saved.id) shouldBe mapOf("AWS_SECRET_ACCESS_KEY" to "s3cr3t")
    }

    @Test
    fun `a local repository is an absolute path, with no environment and never shared`() {
        (service.create(ANNA, local()) as ResticResult.Saved).repository.url shouldBe "/media/backup/sak"
        (service.create(ANNA, local("local:/srv/backup").copy(name = "other")) as ResticResult.Saved).repository.url shouldBe "/srv/backup"
        errors(service.create(ANNA, local("backup"))) shouldBe listOf("restic.invalid_url")
        errors(service.create(ANNA, local(shared = true))) shouldBe listOf("restic.local_not_shared")
        errors(service.create(ANNA, local().copy(env = listOf(ResticEnvInput("AWS_ACCESS_KEY_ID", value = "x"))))) shouldBe
            listOf("restic.unknown_env")
        errors(service.create(ANNA, local().copy(options = listOf("s3.region" to "eu")))) shouldBe listOf("restic.unknown_option")
    }

    @Test
    fun `other kinds of repositories, http and a login in the url are refused`() {
        listOf(
            "sftp:user@host:/srv/restic",
            "rclone:remote:path",
            "rest:https://host/",
            "b2:bucket:path",
            "s3:http://s3.example.org/backups",
            "s3:https://s3.example.org/",
            "s3:https://s3.example.org/backups?x=1",
            "",
        ).forEach { url -> errors(service.create(ANNA, s3(url = url))) shouldBe listOf("restic.invalid_url") }
        errors(service.create(ANNA, s3(url = "s3:https://key:secret@s3.example.org/backups"))) shouldBe listOf("restic.login_in_url")
    }

    @Test
    fun `only what restic needs to reach the repository, nothing that starts a program`() {
        listOf("RESTIC_PASSWORD", "RESTIC_PASSWORD_FILE", "RESTIC_PASSWORD_COMMAND", "LD_PRELOAD", "PATH", "HOME").forEach { name ->
            errors(service.create(ANNA, s3(env = listOf(ResticEnvInput(name, value = "x"))))) shouldBe listOf("restic.unknown_env")
        }
        listOf("sftp.command" to "sh", "sftp.args" to "-x", "rclone.program" to "sh").forEach { option ->
            errors(service.create(ANNA, s3(options = listOf(option)))) shouldBe listOf("restic.unknown_option")
        }
        errors(service.create(ANNA, s3(options = listOf("s3.bucket-lookup" to "sideways")))) shouldBe listOf("restic.invalid_value")
        (service.create(ANNA, s3(options = listOf("s3.bucket-lookup" to "path", "s3.storage-class" to "STANDARD_IA")))).shouldBeInstanceOf<ResticResult.Saved>()
        errors(service.create(ANNA, s3(env = listOf(ResticEnvInput("AWS_DEFAULT_REGION", value = "eu\nX=1"))))) shouldBe listOf("restic.invalid_value")
        errors(service.create(ANNA, s3(env = listOf(ResticEnvInput("AWS_DEFAULT_REGION", value = "eu", secret = "eu"))))) shouldBe listOf("restic.invalid_env")
    }

    @Test
    fun `a name and the limits are checked`() {
        listOf("", "Offsite", "-offsite", "off site", "a".repeat(64)).forEach { name ->
            errors(service.create(ANNA, s3(name = name))) shouldBe listOf("restic.invalid_name")
        }
        listOf("20MB/s", "0MiB/s", "fast", "11GiB/s").forEach { rate ->
            errors(service.create(ANNA, s3(upload = rate))) shouldBe listOf("restic.invalid_limit")
        }
        ResticRepositoryService.rate(20480) shouldBe "20MiB/s"
        ResticRepositoryService.rate(1536) shouldBe "1536KiB/s"
        ResticRepositoryService.rate(1048576) shouldBe "1GiB/s"
        ResticRepositoryService.rate(null as Int?) shouldBe null
    }

    @Test
    fun `a change keeps a secret marked kept, only the owner changes a repository`() {
        val offsite = (service.create(ANNA, s3(shared = true)) as ResticResult.Saved).repository
        val kept =
            s3(
                shared = true,
                env = listOf(ResticEnvInput("AWS_ACCESS_KEY_ID", value = "AKIA2"), ResticEnvInput("AWS_SECRET_ACCESS_KEY", kept = true)),
            )
        service.update(BERT, offsite.id, kept) shouldBe ResticResult.NotOwner
        service.update(BERT, 4711, kept) shouldBe ResticResult.NotFound
        val changed = (service.update(ANNA, offsite.id, kept) as ResticResult.Saved).repository
        changed.env.first().value shouldBe "AKIA2"
        service.secrets(ANNA, offsite.id) shouldBe mapOf("AWS_SECRET_ACCESS_KEY" to "s3cr3t")
        // kept is for a secret the repository has
        errors(service.update(ANNA, offsite.id, s3(env = listOf(ResticEnvInput("AWS_SESSION_TOKEN", kept = true))))) shouldBe
            listOf("restic.nothing_kept")
        errors(service.create(ANNA, s3(name = "new", env = listOf(ResticEnvInput("AWS_SECRET_ACCESS_KEY", kept = true))))) shouldBe
            listOf("restic.nothing_kept")
        // a shared one is seen by the others, with its secrets (BACKUP-6)
        service.secrets(BERT, offsite.id) shouldBe mapOf("AWS_SECRET_ACCESS_KEY" to "s3cr3t")
        service.create(ANNA, s3()) shouldBe ResticResult.Exists
    }
}
