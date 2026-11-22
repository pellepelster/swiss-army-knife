package io.pelle.sak.backend.users

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
        service.add("admin@example.org", " Bob@Example.org ", "  ").shouldBeInstanceOf<UserAddResult.Added>().user.name shouldBe null
        service.add("admin@example.org", "anna@example.org", " Anna ").shouldBeInstanceOf<UserAddResult.Added>().user.name shouldBe "Anna"
        service.add("admin@example.org", "BOB@example.org", null) shouldBe UserAddResult.Exists
        // an admin of the configuration is never also a user
        service.add("admin@example.org", "ADMIN@example.org", "Admin") shouldBe UserAddResult.Admin
        service.add("admin@example.org", "no address", "x".repeat(65)) shouldBe
            UserAddResult.Invalid(listOf(ErrorMessage(UsersTexts.INVALID_EMAIL), ErrorMessage(UsersTexts.INVALID_NAME, "max" to UserService.MAX_NAME_LENGTH)))

        // what the login takes for no address is none here: its user would never get a link
        service.add("admin@example.org", "anna@exa mple.org", null) shouldBe UserAddResult.Invalid(listOf(ErrorMessage(UsersTexts.INVALID_EMAIL)))
        service.add("admin@example.org", "Anna <other@example.org>", null) shouldBe UserAddResult.Invalid(listOf(ErrorMessage(UsersTexts.INVALID_EMAIL)))

        service.users().map { it.email to it.configured } shouldBe
            listOf("admin@example.org" to true, "anna@example.org" to false, "bob@example.org" to false)
        service.isUser("Anna@example.org") shouldBe true
        service.isUser("admin@example.org") shouldBe false
        service.name("anna@example.org") shouldBe "Anna"
        service.name("bob@example.org") shouldBe null

        val anna = service.users().first { it.email == "anna@example.org" }
        service.delete(anna.id!!) shouldBe true
        service.isUser("anna@example.org") shouldBe false
        service.delete(anna.id!!) shouldBe false
    }
}
