package io.pelle.sak.backend.restic

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.core.Language
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private const val ANNA = "anna@example.org"

class ResticRepositoryServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val service by lazy { ResticRepositoryService(SakDatabase.open(tempDir.resolve("sak.db")), mayLogin = { true }) }

    @Test
    fun `the name is the description in lower case, umlauts written out, everything else a hyphen`() {
        ResticRepositoryService.nameOf("My Backups") shouldBe "my-backups"
        ResticRepositoryService.nameOf("Meine Backups") shouldBe "meine-backups"
        ResticRepositoryService.nameOf("Fotos & Videos 2024") shouldBe "fotos-videos-2024"
        ResticRepositoryService.nameOf("Größe über Äpfel") shouldBe "groesse-ueber-aepfel"
        ResticRepositoryService.nameOf("Café crème") shouldBe "cafe-creme"
        ResticRepositoryService.nameOf("  --nas_backup--  ") shouldBe "nas_backup"
        ResticRepositoryService.nameOf("!!!") shouldBe ""
        ResticRepositoryService.nameOf("a".repeat(70) + " b") shouldBe "a".repeat(63)
        ResticRepositoryService.nameOf("a".repeat(62) + " b") shouldBe "a".repeat(62)
    }

    @Test
    fun `the first repository is described in the language of the sign-in, its name derived from it (BACKUP-7)`() {
        service.signedIn(ANNA, Language.DE)
        service.repositories(ANNA).map { it.name to it.description } shouldBe listOf("meine-backups" to "Meine Backups")
        service.signedIn("bert@example.org", Language.EN)
        service.repositories("bert@example.org").map { it.name to it.description } shouldBe listOf("my-backups" to "My Backups")
    }

    @Test
    fun `a repository is created with its description, a name the user has or no name at all is refused`() {
        val created = service.create(ANNA, "  Fotos & Videos 2024 ").shouldBeInstanceOf<ResticRepositoryCreateResult.Created>().repository
        created.name shouldBe "fotos-videos-2024"
        created.description shouldBe "Fotos & Videos 2024"
        // another description that gives the same name
        service.create(ANNA, "fotos videos 2024") shouldBe ResticRepositoryCreateResult.Exists("fotos-videos-2024")
        service.create(ANNA, "!!!") shouldBe ResticRepositoryCreateResult.Invalid(listOf(ErrorMessage(ResticTexts.NO_NAME)))
        val invalid = ResticRepositoryCreateResult.Invalid(listOf(ErrorMessage(ResticTexts.INVALID_DESCRIPTION, "max" to 255)))
        service.create(ANNA, " ") shouldBe invalid
        service.create(ANNA, "a".repeat(256)) shouldBe invalid
        service.create(ANNA, "line\nbreak") shouldBe invalid
    }
}
