package io.pelle.sak.model.files

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.migrateIsolated
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class FileRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    @Test
    fun `gives every file a resource id of its own, the content is placed before the row is committed`() {
        val placed = mutableListOf<ResourceId>()
        val repository = FileRepository(database)
        val first = repository.insert("a".repeat(64), "image/jpeg", 1, PELLE) { placed += it }
        val second = repository.insert("a".repeat(64), "image/jpeg", 1, PELLE) { placed += it }

        placed shouldContainExactly listOf(first.resourceId, second.resourceId)
        second.resourceId shouldNotBe first.resourceId
        repository.byId(PELLE, first.id) shouldBe first
        repository.resourceIds() shouldBe setOf(first.resourceId, second.resourceId)
        repository.locations() shouldContainExactly
            listOf(FileLocation(first.resourceId, "a".repeat(64)), FileLocation(second.resourceId, "a".repeat(64)))
    }

    @Test
    fun `a file whose content can not be placed is not stored`() {
        val repository = FileRepository(database)
        shouldThrow<IllegalStateException> { repository.insert("a".repeat(64), "image/jpeg", 1, PELLE) { error("no space") } }
        repository.resourceIds() shouldBe emptySet()
    }

    @Test
    fun `gives no resource id that is taken beside the database`() {
        val left = ResourceId("leftover0001")
        val taken = FileRepository(database, free = { it != left }, random = draws("leftover0001", "fresh0000001"))
        taken.insert("a".repeat(64), "image/jpeg", 1, PELLE).resourceId shouldBe ResourceId("fresh0000001")
    }

    @Test
    fun `the files stored before the resource ids get one each`() {
        val file = tempDir.resolve("before.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            migrateIsolated(connection, "db/changelog/until-0003.yaml")
            connection.createStatement().use { statement ->
                listOf("a", "a", "b").forEachIndexed { index, sha ->
                    statement.executeUpdate(
                        "INSERT INTO files (id, sha256, content_type, size, owner, created_at, deleted_at) " +
                            "VALUES (${index + 1}, '${sha.repeat(64)}', 'image/jpeg', 1, '$PELLE', '2026-11-01 00:00:00.000', " +
                            "${if (index == 2) "'2026-11-02 00:00:00.000'" else "NULL"})",
                    )
                }
            }
            // liquibase leaves the connection without auto commit
            connection.commit()
        }

        val repository = FileRepository(SakDatabase.open(file))
        val locations = repository.locations()
        // the deleted file too
        locations.map { it.sha256 } shouldContainExactly listOf("a".repeat(64), "a".repeat(64), "b".repeat(64))
        locations.map { it.resourceId }.toSet().size shouldBe 3
        repository.byId(PELLE, 1)!!.resourceId shouldBe locations.first().resourceId
        repository.insert("c".repeat(64), "image/jpeg", 1, PELLE).resourceId shouldNotBe locations.first().resourceId
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
