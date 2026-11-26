package io.pelle.sak.model.settings

import io.pelle.sak.model.SYSTEM_OWNER
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// who inserts the rows of the tests
private const val OWNER = "admin@example.org"

class SettingRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-10-27T08:00:00Z")

    private val repository by lazy { SettingRepository(SakDatabase.open(tempDir.resolve("sak.db")), Clock.fixed(now, ZoneOffset.UTC)) }

    @Test
    fun `keeps one setting per name and scope that is not deleted, a deleted one stays`() {
        val first = repository.insert("A_SETTING", "system", "the value", owner = OWNER)!!
        first.createdAt shouldBe now

        repository.insert("A_SETTING", "system", "other", owner = OWNER) shouldBe null
        repository.insert("A_SETTING", "admin@example.org", "other", owner = OWNER) shouldNotBe null

        repository.delete("A_SETTING", "system") shouldNotBe null
        repository.delete("A_SETTING", "system") shouldBe null
        repository.byNameAndScope("A_SETTING", "system") shouldBe null
        repository.byNameAndScope("A_SETTING", "admin@example.org")!!.value shouldBe "other"
        repository.insert("A_SETTING", "system", "again", owner = OWNER)!!.value shouldBe "again"
    }

    @Test
    fun `a setting of a user is apart from the one of the instance and from the ones of the other users`() {
        repository.insert("ai", "notes", "true", owner = OWNER)!!.level shouldBe null
        repository.replace("notes", mapOf("ai" to "false"), owner = "anna@example.org", user = "anna@example.org")
        repository.replace("notes", mapOf("ai" to "true"), owner = "bert@example.org", user = "bert@example.org")

        repository.byNameAndScope("ai", "notes")!!.let {
            it.value shouldBe "true"
            it.level shouldBe null
        }
        repository.byNameAndScope("ai", "notes", "anna@example.org")!!.let {
            it.value shouldBe "false"
            it.level shouldBe "anna@example.org"
            it.owner shouldBe "anna@example.org"
        }
        repository.byNameAndScope("ai", "notes", "carl@example.org") shouldBe null
        repository.ofUser("notes", "bert@example.org").mapValues { it.value.value } shouldBe mapOf("ai" to "true")
        repository.ofUser("grocery", "bert@example.org") shouldBe emptyMap()

        // a user replaces and deletes only their own
        repository.replace("notes", mapOf("ai" to "true"), owner = "anna@example.org", user = "anna@example.org")
        repository.byNameAndScope("ai", "notes", "anna@example.org")!!.value shouldBe "true"
        repository.insert("ai", "notes", "false", owner = "anna@example.org", user = "anna@example.org") shouldBe null
        repository.delete("ai", "notes", "anna@example.org") shouldNotBe null
        repository.byNameAndScope("ai", "notes", "anna@example.org") shouldBe null
        repository.byNameAndScope("ai", "notes", "bert@example.org")!!.value shouldBe "true"
        repository.byNameAndScope("ai", "notes")!!.value shouldBe "true"
    }

    @Test
    fun `replaces values in one go, the old row stays as deleted and an equal value is kept`() {
        repository.insert("A_SETTING", "system", "old", owner = OWNER)!!

        repository.replace("system", mapOf("A_SETTING" to "new", "B_SETTING" to "first"), owner = OWNER)
        repository.byNameAndScope("A_SETTING", "system")!!.value shouldBe "new"
        repository.byNameAndScope("B_SETTING", "system")!!.value shouldBe "first"

        val kept = repository.byNameAndScope("B_SETTING", "system")!!
        repository.replace("system", mapOf("B_SETTING" to "first"), owner = OWNER)
        repository.byNameAndScope("B_SETTING", "system")!!.id shouldBe kept.id
    }

    @Test
    fun `a setting records who set it, a value replaced is the one of who replaced it`() {
        repository.insert("A_SETTING", "system", "first", owner = SYSTEM_OWNER)!!.owner shouldBe SYSTEM_OWNER

        repository.replace("system", mapOf("A_SETTING" to "second"), owner = OWNER)
        repository.byNameAndScope("A_SETTING", "system")!!.owner shouldBe OWNER
        // an equal value stays the one it was
        repository.replace("system", mapOf("A_SETTING" to "second"), owner = "other@example.org")
        repository.byNameAndScope("A_SETTING", "system")!!.owner shouldBe OWNER
    }
}
