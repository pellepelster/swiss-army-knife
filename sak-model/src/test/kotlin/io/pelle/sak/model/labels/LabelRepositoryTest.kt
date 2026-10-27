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
import java.time.LocalDateTime

class LabelRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val repository by lazy { LabelRepository(database) }

    private val now = Instant.parse("2026-10-27T08:00:00Z")

    @Test
    fun `deletes labels softly, the row stays and its name is free again`() {
        val home = repository.insert("home", now)!!

        repository.delete(home.id, now) shouldBe true
        repository.delete(home.id, now) shouldBe false
        repository.byId(home.id) shouldBe null
        database.dsl.fetchCount(LABELS) shouldBe 1
        repository.insert("home", now)!!.id shouldBe home.id + 1
    }

    @Test
    fun `the schema keeps active and deleted_at in step`() {
        fun insert(
            active: Int?,
            deletedAt: LocalDateTime?,
        ) = database.dsl
            .insertInto(LABELS)
            .set(LABELS.NAME, "home")
            .set(LABELS.ACTIVE, active)
            .set(LABELS.CREATED_AT, LocalDateTime.of(2026, 10, 27, 8, 0))
            .set(LABELS.UPDATED_AT, LocalDateTime.of(2026, 10, 27, 8, 0))
            .set(LABELS.DELETED_AT, deletedAt)
            .execute()

        shouldThrow<DataAccessException> { insert(null, null) }
        shouldThrow<DataAccessException> { insert(1, LocalDateTime.of(2026, 10, 27, 9, 0)) }
        shouldThrow<DataAccessException> { insert(2, null) }
        insert(1, null) shouldBe 1
        shouldThrow<DataAccessException> { insert(1, null) }
        insert(null, LocalDateTime.of(2026, 10, 27, 9, 0)) shouldBe 1
        insert(null, LocalDateTime.of(2026, 10, 27, 9, 0)) shouldBe 1
    }
}
