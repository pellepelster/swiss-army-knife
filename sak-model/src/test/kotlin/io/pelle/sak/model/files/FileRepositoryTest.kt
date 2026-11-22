package io.pelle.sak.model.files

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FileRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    @Test
    fun `gives every file a resource id of its own, the content is placed before the row is committed`() {
        val placed = mutableListOf<ResourceId>()
        val repository = FileRepository(database)
        val first = repository.insert("image/jpeg", 1, PELLE) { placed += it }
        val second = repository.insert("image/jpeg", 1, PELLE) { placed += it }

        placed shouldContainExactly listOf(first.resourceId, second.resourceId)
        second.resourceId shouldNotBe first.resourceId
        repository.byId(PELLE, first.id) shouldBe first
        repository.resourceIds() shouldBe setOf(first.resourceId, second.resourceId)
    }

    @Test
    fun `a file whose content can not be placed is not stored`() {
        val repository = FileRepository(database)
        shouldThrow<IllegalStateException> { repository.insert("image/jpeg", 1, PELLE) { error("no space") } }
        repository.resourceIds() shouldBe emptySet()
    }

    @Test
    fun `gives no resource id that is taken beside the database`() {
        val left = ResourceId("leftover0001")
        val taken = FileRepository(database, free = { it != left }, random = draws("leftover0001", "fresh0000001"))
        taken.insert("image/jpeg", 1, PELLE).resourceId shouldBe ResourceId("fresh0000001")
    }

    // a random that draws the resource ids in the order given
    private fun draws(vararg ids: String): java.security.SecureRandom {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val indexes = ids.flatMap { id -> id.map { alphabet.indexOf(it) } }.iterator()
        return object : java.security.SecureRandom() {
            override fun nextInt(bound: Int) = indexes.next()
        }
    }

    private companion object {
        const val PELLE = "pelle@pelle.io"
    }
}
