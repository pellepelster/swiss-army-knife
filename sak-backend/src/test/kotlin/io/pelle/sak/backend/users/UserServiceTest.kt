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
    fun `a sign out everywhere counts from the next second, for an added user and an admin of the configuration (SEC-4)`() {
        val anna = service.create("admin@example.org", "anna@example.org", null).shouldBeInstanceOf<UserCreateResult.Added>().user
        service.validFrom("admin@example.org") shouldBe null

        service.signOutEverywhere("admin@example.org", "Admin@Example.org")
        val admin = service.validFrom("admin@example.org")!!
        // the next whole second: a token of this second, the one that signed out, is refused too
        admin.nano shouldBe 0
        (admin > java.time.Instant.now().minusSeconds(1)) shouldBe true
        service.validFrom("anna@example.org") shouldBe anna.createdAt

        // an admin signs a user out, the later time counts
        service.signOut("admin@example.org", anna.id) shouldBe true
        (service.validFrom("anna@example.org")!! > anna.createdAt) shouldBe true
        service.signOut("admin@example.org", 4711) shouldBe false
    }

    @Test
    fun `the tokens of a user count from when they were added, a delete is told, added again counts from then (SEC-39)`() {
        val deleted = mutableListOf<String>()
        service.whenDeleted { deleted += it }
        val first = service.create("admin@example.org", "anna@example.org", null).shouldBeInstanceOf<UserCreateResult.Added>().user
        service.validFrom("Anna@Example.org") shouldBe first.createdAt
        // an admin of the configuration and an address that is no user have no time, the roles decide
        service.validFrom("admin@example.org") shouldBe null
        service.validFrom("nobody@example.org") shouldBe null

        service.delete("admin@example.org", first.id) shouldBe DeleteResult.Deleted
        deleted shouldBe listOf("anna@example.org")
        // a delete signs out everywhere too, from the next whole second on
        val signedOut = service.validFrom("anna@example.org")!!
        signedOut.nano shouldBe 0
        (signedOut > first.createdAt) shouldBe true
        // a delete of nobody tells nothing
        service.delete("admin@example.org", first.id) shouldBe DeleteResult.NotFound
        deleted shouldBe listOf("anna@example.org")

        val again = service.create("admin@example.org", "anna@example.org", null).shouldBeInstanceOf<UserCreateResult.Added>().user
        // the later of the add and the sign out of the delete
        service.validFrom("anna@example.org") shouldBe maxOf(again.createdAt, signedOut)
    }

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
        service.delete("admin@example.org", anna.id!!) shouldBe DeleteResult.Deleted
        service.isUser("anna@example.org") shouldBe false
        service.delete("admin@example.org", anna.id!!) shouldBe DeleteResult.NotFound
    }
}
