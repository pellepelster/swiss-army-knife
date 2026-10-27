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

    private fun create(name: String) = service.create(name).shouldBeInstanceOf<CreateLabelResult.Created>().label

    @Test
    fun `creates labels with normalized names, unique among the ones not deleted`() {
        val label = create("  Home   Office ")
        label shouldBe Label(label.id, "home office", clock.now, clock.now)

        service.create("HOME office") shouldBe CreateLabelResult.Exists
        for (name in listOf("", "   ", "a".repeat(65), "bell\u0007")) {
            service.create(name) shouldBe CreateLabelResult.InvalidName
        }
        create("a".repeat(64))
        service.byName("Home Office") shouldBe label
    }

    @Test
    fun `pages the labels by name`() {
        listOf("work", "home", "todo").forEach { create(it) }

        service.page(0, 2).let {
            it.items.map { label -> label.name } shouldBe listOf("home", "todo")
            it.total shouldBe 3
        }
        service.page(2, 2).items.map { it.name } shouldBe listOf("work")
    }

    @Test
    fun `renames labels and tells when`() {
        val home = create("home")
        create("work")
        clock.now = Instant.parse("2026-10-27T09:00:00Z")

        service.rename(home.id, "House").shouldBeInstanceOf<RenameLabelResult.Renamed>().label shouldBe
            home.copy(name = "house", updatedAt = clock.now)
        service.byId(home.id)?.updatedAt shouldBe clock.now
        service.rename(home.id, "work") shouldBe RenameLabelResult.Exists
        service.rename(home.id, " ") shouldBe RenameLabelResult.InvalidName
        service.rename(4711, "other") shouldBe RenameLabelResult.NotFound
    }

    @Test
    fun `deletes labels softly, their names are free again`() {
        val home = create("home")

        service.delete(home.id) shouldBe DeleteLabelResult.Deleted
        service.delete(home.id) shouldBe DeleteLabelResult.NotFound
        deleted shouldBe listOf(home.id)
        service.byId(home.id) shouldBe null
        service.page(0, 10).total shouldBe 0

        // a new label takes the name
        create("home").id shouldBe home.id + 1
        service.rename(service.byName("home")!!.id, "home").shouldBeInstanceOf<RenameLabelResult.Renamed>()
    }

    @Test
    fun `resolves names to labels and creates the missing ones`() {
        val home = create("home")

        val resolved = service.resolve(listOf("Work", "home", "work ")).shouldBeInstanceOf<ResolveLabelsResult.Resolved>()
        resolved.labels.map { it.name } shouldBe listOf("home", "work")
        resolved.labels.first() shouldBe home
        service.resolve(emptyList()) shouldBe ResolveLabelsResult.Resolved(emptyList())
        service.resolve(listOf("ok", "")) shouldBe ResolveLabelsResult.InvalidNames(listOf(""))
        service.byName("ok") shouldBe null
    }

}
