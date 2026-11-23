package io.pelle.sak.backend.users

import io.pelle.sak.backend.DeleteResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class UserServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val service by lazy { UserService(SakDatabase.open(tempDir.resolve("sak.db")), listOf(" Admin@Example.org ")) }

    @Test
    fun `a user is added by a normalized address with an optional name, the admins of the configuration come first`() {
        service.create("admin@example.org", " Bob@Example.org ", "  ").shouldBeInstanceOf<UserCreateResult.Added>().user.name shouldBe null
        service.create("admin@example.org", "anna@example.org", " Anna ").shouldBeInstanceOf<UserCreateResult.Added>().user.name shouldBe "Anna"
        service.create("admin@example.org", "BOB@example.org", null) shouldBe UserCreateResult.Exists
        // an admin of the configuration is never also a user
        service.create("admin@example.org", "ADMIN@example.org", "Admin") shouldBe UserCreateResult.Admin
        service.create("admin@example.org", "no address", "x".repeat(65)) shouldBe
            UserCreateResult.Invalid(listOf(ErrorMessage(UsersTexts.INVALID_EMAIL), ErrorMessage(UsersTexts.INVALID_NAME, "max" to UserService.MAX_NAME_LENGTH)))

        // what the login takes for no address is none here: its user would never get a link
        service.create("admin@example.org", "anna@exa mple.org", null) shouldBe UserCreateResult.Invalid(listOf(ErrorMessage(UsersTexts.INVALID_EMAIL)))
        service.create("admin@example.org", "Anna <other@example.org>", null) shouldBe UserCreateResult.Invalid(listOf(ErrorMessage(UsersTexts.INVALID_EMAIL)))

        service.all().map { it.email to it.configured } shouldBe
            listOf("admin@example.org" to true, "anna@example.org" to false, "bob@example.org" to false)
        service.isUser("Anna@example.org") shouldBe true
        service.isUser("admin@example.org") shouldBe false
        service.name("anna@example.org") shouldBe "Anna"
        service.name("bob@example.org") shouldBe null

        val anna = service.all().first { it.email == "anna@example.org" }
        service.delete(anna.id!!) shouldBe DeleteResult.Deleted
        service.isUser("anna@example.org") shouldBe false
        service.delete(anna.id!!) shouldBe DeleteResult.NotFound
    }
}
