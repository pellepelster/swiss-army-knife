package io.pelle.sak.backend.labels

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.labels.Label
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private const val OWNER = "pelle@pelle.io"

class LabelServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock =
        object : Clock() {
            var now: Instant = Instant.parse("2026-10-27T08:00:00Z")

            override fun instant() = now

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId) = this
        }

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val deleted = mutableListOf<Int>()

    private val service by lazy { LabelService(database, clock).apply { onDelete { deleted.add(it) } } }

    private fun create(name: String) = service.create(OWNER, name).shouldBeInstanceOf<CreateLabelResult.Created>().label

    @Test
    fun `creates labels with normalized names, unique among the ones not deleted`() {
        val label = create("  Home   Office ")
        label shouldBe Label(label.id, "home office", clock.now, null)

        service.create(OWNER, "HOME office") shouldBe CreateLabelResult.Exists
        for (name in listOf("", "   ", "a".repeat(65), "bell\u0007")) {
            service.create(OWNER, name) shouldBe CreateLabelResult.InvalidName
        }
        create("a".repeat(64))
        service.byName(OWNER, "Home Office") shouldBe label
    }

    @Test
    fun `pages the labels by name`() {
        listOf("work", "home", "todo").forEach { create(it) }

        service.page(OWNER, 0, 2).let {
            it.items.map { label -> label.name } shouldBe listOf("home", "todo")
            it.total shouldBe 3
        }
        service.page(OWNER, 2, 2).items.map { it.name } shouldBe listOf("work")
    }

    @Test
    fun `renames labels and tells when`() {
        val home = create("home")
        create("work")
        clock.now = Instant.parse("2026-10-27T09:00:00Z")

        service.rename(OWNER, home.id, "House").shouldBeInstanceOf<RenameLabelResult.Renamed>().label shouldBe
            home.copy(name = "house", updatedAt = clock.now)
        service.byId(OWNER, home.id)?.updatedAt shouldBe clock.now
        service.rename(OWNER, home.id, "work") shouldBe RenameLabelResult.Exists
        service.rename(OWNER, home.id, " ") shouldBe RenameLabelResult.InvalidName
        service.rename(OWNER, 4711, "other") shouldBe RenameLabelResult.NotFound
    }

    @Test
    fun `deletes labels softly, their names are free again`() {
        val home = create("home")

        service.delete(OWNER, home.id) shouldBe DeleteLabelResult.Deleted
        service.delete(OWNER, home.id) shouldBe DeleteLabelResult.NotFound
        deleted shouldBe listOf(home.id)
        service.byId(OWNER, home.id) shouldBe null
        service.page(OWNER, 0, 10).total shouldBe 0

        // a new label takes the name
        create("home").id shouldBe home.id + 1
        service.rename(OWNER, service.byName(OWNER, "home")!!.id, "home").shouldBeInstanceOf<RenameLabelResult.Renamed>()
    }

    @Test
    fun `resolves names to labels and creates the missing ones`() {
        val home = create("home")

        val resolved = service.resolve(OWNER, setOf("work", "home"))
        resolved.map { it.name } shouldBe listOf("home", "work")
        resolved.first() shouldBe home
        service.resolve(OWNER, emptySet()) shouldBe emptyList()
    }

    @Test
    fun `keeps the labels of a user to the user, another has labels of the same names`() {
        val home = create("home")

        service.page("other@example.org", 0, 10).total shouldBe 0
        service.byName("other@example.org", "home") shouldBe null
        service.delete("other@example.org", home.id) shouldBe DeleteLabelResult.NotFound
        service.rename("other@example.org", home.id, "house") shouldBe RenameLabelResult.NotFound
        val others = service.resolve("other@example.org", setOf("home")).single()
        others.id shouldBe home.id + 1
        service.byIds(OWNER, listOf(home.id, others.id)) shouldBe listOf(home)
    }
}
