package io.pelle.sak.model.restic

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.ResourceId
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

    private val now = Instant.parse("2026-11-20T08:00:00Z")

    private val repository by lazy { ResticRepositoryRepository(SakDatabase.open(tempDir.resolve("sak.db")), Clock.fixed(now, ZoneOffset.UTC)) }

    @Test
    fun `an owner who never had a repository gets a first one, only once (BACKUP-7)`() {
        repository.insertFirst("anna@example.org", "my-backups", "t1")!!.name shouldBe "my-backups"
        repository.insertFirst("anna@example.org", "my-backups", "t2") shouldBe null
        repository.of("anna@example.org").map { it.name } shouldBe listOf("my-backups")
        repository.token("anna@example.org", repository.of("anna@example.org").single().id) shouldBe "t1"

        // one with a repository of their own gets none
        repository.insert("bert@example.org", "laptop", "t3")
        repository.insertFirst("bert@example.org", "my-backups", "t4") shouldBe null
        repository.of("bert@example.org").map { it.name } shouldBe listOf("laptop")
    }

    @Test
    fun `a repository belongs to its owner, its token is apart`() {
        val laptop = (repository.insert(ANNA, "laptop", "token-1") as ResticInsert.Inserted).repository
        laptop shouldBe ResticRepository(laptop.id, laptop.resourceId, ANNA, "laptop", now)
        repository.of(ANNA) shouldBe listOf(laptop)
        repository.of(BERT) shouldBe emptyList()
        repository.token(ANNA, laptop.id) shouldBe "token-1"
        repository.token(BERT, laptop.id) shouldBe null
        repository.loginOf(laptop.id) shouldBe ResticLogin("token-1", ANNA, laptop.resourceId)
        repository.loginOf(4711) shouldBe null
    }

    @Test
    fun `a name is unique among the repositories of its owner`() {
        repository.insert(ANNA, "laptop", "a").shouldBeInstanceOf<ResticInsert.Inserted>()
        repository.insert(ANNA, "laptop", "b") shouldBe ResticInsert.NameTaken
        repository.insert(BERT, "laptop", "c").shouldBeInstanceOf<ResticInsert.Inserted>()
        repository.of(ANNA).map { it.name } shouldBe listOf("laptop")
    }

    @Test
    fun `every repository gets its own resource id, never one that is taken (MODEL-8)`() {
        val laptop = (repository.insert(ANNA, "laptop", "a") as ResticInsert.Inserted).repository
        val first = repository.insertFirst(BERT, "my-backups", "b")!!
        laptop.resourceId shouldNotBe first.resourceId
        repository.of(BERT).single().resourceId shouldBe first.resourceId
        repository.resourceIds() shouldContainExactlyInAnyOrder listOf(laptop.resourceId, first.resourceId)

        // a taken one is drawn again
        val left = ResourceId("leftover0001")
        val taken = ResticRepositoryRepository(SakDatabase.open(tempDir.resolve("other.db")), free = { it != left }, random = draws(left.value, "fresh0000001"))
        (taken.insert(ANNA, "laptop", "c") as ResticInsert.Inserted).repository.resourceId shouldBe ResourceId("fresh0000001")
    }

    // a SecureRandom that draws the characters of `ids` one after the other
    private fun draws(vararg ids: String) =
        object : java.security.SecureRandom() {
            private val next = ids.flatMap { id -> id.map { "abcdefghijklmnopqrstuvwxyz0123456789".indexOf(it) } }.iterator()

            override fun nextInt(bound: Int) = next.next()
        }
}
