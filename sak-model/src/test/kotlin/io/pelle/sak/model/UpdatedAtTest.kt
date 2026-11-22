package io.pelle.sak.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.pelle.sak.model.files.FileRepository
import io.pelle.sak.model.grocery.GroceryItemValues
import io.pelle.sak.model.grocery.GroceryRepository
import io.pelle.sak.model.jooq.tables.references.GROCERY_ITEMS
import io.pelle.sak.model.labels.LabelNameUpdateResult
import io.pelle.sak.model.labels.LabelRepository
import io.pelle.sak.model.lists.ListUpdateResult
import io.pelle.sak.model.notes.NoteRepository
import io.pelle.sak.model.notes.NoteWriteResult
import io.pelle.sak.model.todo.TodoPriority
import io.pelle.sak.model.todo.TodoRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// updated_at is null until a row really changes (MODEL-4): a save of the values it has is no change
class UpdatedAtTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val later = Instant.parse("2026-11-22T09:00:00Z")

    // rows are made at the first time and saved again at the later one
    private val first = Clock.fixed(Instant.parse("2026-11-22T08:00:00Z"), ZoneOffset.UTC)

    private val second = Clock.fixed(later, ZoneOffset.UTC)

    @Test
    fun `a list and a task keep their time when saved with the values they have`() {
        val list = TodoRepository(database, first).insertList(PELLE, "Home", shared = false, icon = "🏠")!!
        val task = TodoRepository(database, first).insertItem(PELLE, list.id, "Fix the door", LocalDate.parse("2026-12-01"))!!
        val repository = TodoRepository(database, second)

        (repository.updateList(PELLE, list.id, "Home", shared = false, icon = "🏠") as ListUpdateResult.Updated).list.updatedAt shouldBe null
        repository.setPriority(PELLE, task.id, TodoPriority.NORMAL)!!.updatedAt shouldBe null
        repository.setDueDate(PELLE, task.id, LocalDate.parse("2026-12-01"))!!.updatedAt shouldBe null
        repository.setDone(PELLE, task.id, done = false)!!.updatedAt shouldBe null

        (repository.updateList(PELLE, list.id, "Home", shared = false, icon = null) as ListUpdateResult.Updated).list.updatedAt shouldBe later
        repository.setDone(PELLE, task.id, done = true)!!.updatedAt shouldBe later
    }

    @Test
    fun `a grocery item keeps its time when saved as it is, a check changes it`() {
        val list = GroceryRepository(database, first).insertList(PELLE, "Food", shared = false)!!
        val values = GroceryItemValues("Milk", BigDecimal("1.5"), "l", null)
        val item = GroceryRepository(database, first).insertItem(PELLE, list.id, values).item!!
        val repository = GroceryRepository(database, second)

        repository.updateItem(PELLE, item.id, values).item!!.updatedAt shouldBe null
        repository.setCategory(PELLE, item.id, null).item!!.updatedAt shouldBe null
        repository.setPhoto(PELLE, item.id, null)!!.updatedAt shouldBe null

        repository.checkItem(PELLE, item.id) shouldNotBe null
        database.dsl.select(GROCERY_ITEMS.UPDATED_AT).from(GROCERY_ITEMS).where(GROCERY_ITEMS.ID.eq(item.id)).fetchOne()!!.value1() shouldBe later
    }

    @Test
    fun `a label and a note keep their time when saved as they are, another label of the note changes it`() {
        val milk = LabelRepository(database, first).insert(PELLE, "milk")!!
        val eggs = LabelRepository(database, first).insert(PELLE, "eggs")!!
        val photo = FileRepository(database, first).insert("image/jpeg", 1, PELLE)
        val list = NoteRepository(database, first).run {
            ensureList(PELLE, "Notes")
            ownList(PELLE)!!
        }
        val note = (NoteRepository(database, first).insert(PELLE, list, "shopping", "yellow", setOf(milk.id), listOf(photo.id)) as NoteWriteResult.Saved).note

        (LabelRepository(database, second).rename(PELLE, milk.id, "milk") as LabelNameUpdateResult.Renamed).label.updatedAt shouldBe null
        FileRepository(database, second).updateDiscovered(photo.id, "a text")!!.updatedAt shouldBe later
        val repository = NoteRepository(database, second)
        (repository.update(PELLE, note.id, "shopping", "yellow", setOf(milk.id), listOf(photo.id)) as NoteWriteResult.Saved).note.updatedAt shouldBe null
        (repository.update(PELLE, note.id, "shopping", "yellow", setOf(milk.id, eggs.id), listOf(photo.id)) as NoteWriteResult.Saved).note.updatedAt shouldBe later
    }

    private companion object {
        const val PELLE = "pelle@pelle.io"
    }
}
