package io.pelle.sak.model.users

import io.kotest.matchers.shouldBe
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class UserRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-11-01T08:00:00Z")

    private val repository by lazy { UserRepository(SakDatabase.open(tempDir.resolve("sak.db")), Clock.fixed(now, ZoneOffset.UTC)) }

    @Test
    fun `a user is added once, by address, and stays as a deleted row when removed`() {
        val anna = repository.insert("admin@example.org", "anna@example.org", "Anna")!!
        anna shouldBe User(anna.id, "admin@example.org", "anna@example.org", "Anna", now, null)
        repository.insert("admin@example.org", "anna@example.org", null) shouldBe null
        val bob = repository.insert("admin@example.org", "bob@example.org", null)!!
        repository.all().map { it.email } shouldBe listOf("anna@example.org", "bob@example.org")
        repository.byEmail("bob@example.org") shouldBe bob

        repository.delete(anna.id) shouldBe true
        repository.delete(anna.id) shouldBe false
        repository.byEmail("anna@example.org") shouldBe null
        // the address may be added again
        repository.insert("admin@example.org", "anna@example.org", null)!!.name shouldBe null
    }
}
