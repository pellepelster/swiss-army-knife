package io.pelle.sak.model.notes

import io.kotest.matchers.shouldBe
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.NOTES
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class NoteRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val repository by lazy { NoteRepository(database) }

    private val now = Instant.parse("2026-10-27T08:00:00Z")

    @Test
    fun `deletes notes softly, of their owner only, the row stays`() {
        val note = repository.insert("pelle@pelle.io", "milk", "yellow", emptySet(), emptyList(), now)

        repository.delete("other@example.org", note.id, now) shouldBe false
        repository.delete("pelle@pelle.io", note.id, now) shouldBe true
        repository.delete("pelle@pelle.io", note.id, now) shouldBe false
        repository.byId("pelle@pelle.io", note.id) shouldBe null
        database.dsl.fetchCount(NOTES) shouldBe 1
    }
}
