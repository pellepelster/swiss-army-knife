package io.pelle.sak.model.settings

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SettingRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-10-27T08:00:00Z")

    private val repository by lazy { SettingRepository(SakDatabase.open(tempDir.resolve("sak.db")), Clock.fixed(now, ZoneOffset.UTC)) }

    @Test
    fun `keeps one setting per name and scope that is not deleted, a deleted one stays`() {
        val first = repository.insert("A_SETTING", "system", "the value")!!
        first.createdAt shouldBe now
        first.updatedAt shouldBe null

        repository.insert("A_SETTING", "system", "other") shouldBe null
        repository.insert("A_SETTING", "admin@example.org", "other") shouldNotBe null

        repository.delete("A_SETTING", "system") shouldBe true
        repository.delete("A_SETTING", "system") shouldBe false
        repository.byNameAndScope("A_SETTING", "system") shouldBe null
        repository.byNameAndScope("A_SETTING", "admin@example.org")!!.value shouldBe "other"
        repository.insert("A_SETTING", "system", "again")!!.value shouldBe "again"
    }
}
