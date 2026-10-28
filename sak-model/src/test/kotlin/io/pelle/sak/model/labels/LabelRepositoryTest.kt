package io.pelle.sak.model.labels

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.LABELS
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import io.kotest.matchers.types.shouldBeInstanceOf

class LabelRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val repository by lazy { LabelRepository(database) }

    private val now = Instant.parse("2026-10-27T08:00:00Z")

    private companion object {
        const val OWNER = "pelle@pelle.io"
        const val OTHER = "other@example.org"
    }

    @Test
    fun `deletes labels softly, the row stays and its name is free again`() {
        val home = repository.insert(OWNER, "home")!!

        repository.delete(OWNER, home.id) shouldBe true
        repository.delete(OWNER, home.id) shouldBe false
        repository.byId(OWNER, home.id) shouldBe null
        database.dsl.fetchCount(LABELS) shouldBe 1
        // deleting is no change, only deleted_at is set
        database.dsl.selectFrom(LABELS).fetchOne()!!.updatedAt shouldBe null
        repository.insert(OWNER, "home")!!.id shouldBe home.id + 1
    }

    @Test
    fun `the schema keeps the name unique among the labels of an owner that are not deleted only`() {
        fun insert(
            deletedAt: Instant?,
            owner: String = OWNER,
        ) = database.dsl
            .insertInto(LABELS)
            .set(LABELS.OWNER, owner)
            .set(LABELS.NAME, "home")
            .set(LABELS.CREATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
            .set(LABELS.UPDATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
            .set(LABELS.DELETED_AT, deletedAt)
            .execute()

        insert(null) shouldBe 1
        shouldThrow<DataAccessException> { insert(null) }
        insert(Instant.parse("2026-10-27T09:00:00Z")) shouldBe 1
        insert(Instant.parse("2026-10-27T09:00:00Z")) shouldBe 1
        insert(null, OTHER) shouldBe 1
    }

    @Test
    fun `keeps the labels of an owner to the owner`() {
        val home = repository.insert(OWNER, "home")!!
        val others = repository.insert(OTHER, "home")!!

        repository.page(OTHER, 0, 10) shouldBe listOf(others)
        repository.count(OTHER) shouldBe 1
        repository.byId(OTHER, home.id) shouldBe null
        repository.byIds(OTHER, listOf(home.id, others.id)) shouldBe listOf(others)
        repository.byNames(OTHER, listOf("home")) shouldBe listOf(others)
        repository.rename(OTHER, home.id, "house") shouldBe LabelRename.NotFound
        repository.delete(OTHER, home.id) shouldBe false
        repository.byId(OWNER, home.id) shouldBe home
    }

    @Test
    fun `tells why a label could not be renamed`() {
        val home = repository.insert(OWNER, "home")!!
        val work = repository.insert(OWNER, "work")!!

        repository.rename(OWNER, home.id, "house").shouldBeInstanceOf<LabelRename.Renamed>().label.name shouldBe "house"
        repository.rename(OWNER, home.id, "work") shouldBe LabelRename.NameTaken
        repository.delete(OWNER, work.id)
        repository.rename(OWNER, work.id, "office") shouldBe LabelRename.NotFound
        repository.rename(OWNER, 4711, "office") shouldBe LabelRename.NotFound
    }
}
