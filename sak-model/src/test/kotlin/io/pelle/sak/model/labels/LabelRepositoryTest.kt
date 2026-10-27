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

    @Test
    fun `deletes labels softly, the row stays and its name is free again`() {
        val home = repository.insert("home")!!

        repository.delete(home.id) shouldBe true
        repository.delete(home.id) shouldBe false
        repository.byId(home.id) shouldBe null
        database.dsl.fetchCount(LABELS) shouldBe 1
        // deleting is no change, only deleted_at is set
        database.dsl.selectFrom(LABELS).fetchOne()!!.updatedAt shouldBe null
        repository.insert("home")!!.id shouldBe home.id + 1
    }

    @Test
    fun `the schema keeps the name unique among the labels that are not deleted only`() {
        fun insert(deletedAt: Instant?) =
            database.dsl
                .insertInto(LABELS)
                .set(LABELS.NAME, "home")
                .set(LABELS.CREATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
                .set(LABELS.UPDATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
                .set(LABELS.DELETED_AT, deletedAt)
                .execute()

        insert(null) shouldBe 1
        shouldThrow<DataAccessException> { insert(null) }
        insert(Instant.parse("2026-10-27T09:00:00Z")) shouldBe 1
        insert(Instant.parse("2026-10-27T09:00:00Z")) shouldBe 1
    }

    @Test
    fun `tells why a label could not be renamed`() {
        val home = repository.insert("home")!!
        val work = repository.insert("work")!!

        repository.rename(home.id, "house").shouldBeInstanceOf<LabelRename.Renamed>().label.name shouldBe "house"
        repository.rename(home.id, "work") shouldBe LabelRename.NameTaken
        repository.delete(work.id)
        repository.rename(work.id, "office") shouldBe LabelRename.NotFound
        repository.rename(4711, "office") shouldBe LabelRename.NotFound
    }
}
