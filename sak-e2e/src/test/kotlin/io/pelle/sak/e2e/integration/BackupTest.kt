package io.pelle.sak.e2e.integration

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.pelle.sak.e2e.util.NotesPage
import io.pelle.sak.e2e.util.TestDeployments
import io.pelle.sak.e2e.util.TestEnvironment
import io.pelle.sak.e2e.util.step
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.UUID

// a backup and a restore into the same deployment, on both shared deployments: what was there at
// the backup comes back, what came after it goes. After the tests that only add and remove notes of
// their own, the restore replaces all data
@Tag("integration")
@Order(3)
@ExtendWith(TestDeployments::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackupTest {
    private lateinit var environment: TestEnvironment

    @BeforeAll
    fun deployments(environment: TestEnvironment) {
        this.environment = environment
    }

    @ParameterizedTest
    @ValueSource(strings = ["single", "multi"])
    fun `a restore brings back the data of the backup`(mode: String) {
        val deployment = environment.deployment(mode)
        val backedUp = UUID.randomUUID().toString()
        val later = UUID.randomUUID().toString()
        val backups = File(System.getProperty("user.home"), ".sak/backups/${deployment.name}")
        backups.deleteRecursively()

        environment.context(deployment).use { context ->
            val page = context.newPage()
            deployment.signIn(page)
            NotesPage.open(page)
            NotesPage.create(page, backedUp)
        }

        step("backing up the deployment with sakctl", deployment)
        deployment.sakctl.run("backup", deployment.name) shouldBe 0
        val backup = backups.listFiles().orEmpty().single()
        backup.name shouldMatch Regex("${Regex.escape(deployment.name)}-\\d{8}T\\d{6}Z\\.age\\.sak")
        // encrypted on the server: the text of the note is nowhere in the file
        String(backup.readBytes(), Charsets.ISO_8859_1).contains(backedUp) shouldBe false

        environment.context(deployment).use { context ->
            val page = context.newPage()
            deployment.signIn(page)
            NotesPage.open(page)
            NotesPage.delete(page, backedUp)
            NotesPage.create(page, later)
        }

        step("restoring ${backup.name} with sakctl, it waits until the backend answers again", deployment)
        deployment.sakctl.run("restore", deployment.name, backup.path, "--yes") shouldBe 0
        step("checking that the note of the backup is back and the later one is gone", deployment)

        // the restore started the backend anew: a new sign in
        environment.context(deployment).use { context ->
            val page = context.newPage()
            deployment.signIn(page)
            NotesPage.open(page)
            val texts = NotesPage.texts(page)
            texts shouldContain backedUp
            texts shouldNotContain later
            NotesPage.delete(page, backedUp)
        }
    }
}
