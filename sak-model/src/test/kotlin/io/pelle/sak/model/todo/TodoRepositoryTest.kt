package io.pelle.sak.model.todo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.lists.ListDelete
import io.pelle.sak.model.lists.ListUpdate
import io.pelle.sak.model.lists.SakList
import io.pelle.sak.model.jooq.tables.references.TODO_ITEMS
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val PELLE = "pelle@pelle.io"

private const val OTHER = "other@example.org"

class TodoRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val now = Instant.parse("2026-11-12T08:00:00Z")

    private val repository by lazy { TodoRepository(database, Clock.fixed(now, ZoneOffset.UTC)) }

    @Test
    fun `a new list is private or shared, its owner deletes it, the last one too`() {
        repository.lists(PELLE) shouldBe emptyList()
        val home = repository.insertList(PELLE, "Home", shared = false)!!
        home shouldBe SakList(home.id, PELLE, "Home", false, now, null, items = 0)
        repository.insertList(PELLE, "Home", shared = true) shouldBe null
        repository.insertItem(PELLE, home.id, "Fix the door")!!.title shouldBe "Fix the door"
        repository.lists(PELLE) shouldBe listOf(home.copy(items = 1))

        repository.deleteList(OTHER, home.id) shouldBe ListDelete.NotFound
        repository.deleteList(PELLE, home.id) shouldBe ListDelete.Deleted
        repository.deleteList(PELLE, home.id) shouldBe ListDelete.NotFound
        repository.lists(PELLE) shouldBe emptyList()
        // its tasks go with it, but stay as deleted
        database.dsl.fetchCount(TODO_ITEMS, TODO_ITEMS.LIST_ID.eq(home.id), TODO_ITEMS.DELETED_AT.isNull) shouldBe 0
        database.dsl.fetchCount(TODO_ITEMS, TODO_ITEMS.LIST_ID.eq(home.id)) shouldBe 1
        // the name of a deleted list is free again
        repository.insertList(PELLE, "Home", shared = false) shouldBe repository.lists(PELLE).single()
    }

    @Test
    fun `a user sees their lists and the shared ones of the others, by name`() {
        repository.insertList(PELLE, "Work", shared = false)
        repository.insertList(PELLE, "Home", shared = false)
        repository.insertList(OTHER, "Garden", shared = true)
        repository.insertList(OTHER, "Secret", shared = false)

        repository.lists(PELLE).map { it.name } shouldBe listOf("Garden", "Home", "Work")
        repository.lists(OTHER).map { it.name } shouldBe listOf("Garden", "Secret")
    }

    @Test
    fun `another user sees and changes only the shared lists and their tasks, only the owner shares and deletes a list`() {
        val secret = repository.insertList(PELLE, "Secret", shared = false)!!
        val party = repository.insertList(PELLE, "Party", shared = true)!!
        val gift = repository.insertItem(PELLE, secret.id, "Buy a gift")!!
        val invite = repository.insertItem(PELLE, party.id, "Send the invitations")!!

        repository.list(OTHER, secret.id) shouldBe null
        repository.items(OTHER, secret.id) shouldBe null
        repository.item(OTHER, gift.id) shouldBe null
        repository.insertItem(OTHER, secret.id, "Peek") shouldBe null
        repository.setDone(OTHER, gift.id, done = true) shouldBe null
        repository.purge(OTHER, secret.id, emptyList()) shouldBe null
        repository.updateList(OTHER, secret.id, "Mine", shared = true) shouldBe ListUpdate.NotFound
        repository.deleteList(OTHER, secret.id) shouldBe ListDelete.NotFound
        repository.seesList(OTHER, secret.id) shouldBe false
        repository.items(PELLE, secret.id)!!.map { it.title } shouldBe listOf("Buy a gift")

        repository.list(OTHER, party.id) shouldBe party.copy(items = 1)
        repository.insertItem(OTHER, party.id, "Bring music")!!.owner shouldBe OTHER
        // every user who sees the list marks a task done, also one another user added (LIST-2)
        repository.setDone(OTHER, invite.id, done = true)!!.doneAt shouldBe now
        repository.items(PELLE, party.id)!!.map { it.title } shouldBe listOf("Send the invitations", "Bring music")
        // only the owner renames it, another user changes its icon (LIST-2, SEC-19)
        (repository.updateList(OTHER, party.id, "Party", shared = true, icon = "🎉") as ListUpdate.Updated).list.let {
            it.icon shouldBe "🎉"
            it.owner shouldBe PELLE
            it.items shouldBe 1
        }
        repository.updateList(OTHER, party.id, "Party", shared = false) shouldBe ListUpdate.NotOwner
        repository.updateList(OTHER, party.id, "Ours", shared = true) shouldBe ListUpdate.NotOwner
        repository.updateList(OTHER, party.id, "Secret", shared = true) shouldBe ListUpdate.NotOwner
        repository.updateList(PELLE, party.id, "Secret", shared = true) shouldBe ListUpdate.NameTaken
        repository.deleteList(OTHER, party.id) shouldBe ListDelete.NotFound
        // unshared by its owner, the list is none of the other user's to change
        // the owner unshares it, which the update says, the other user saw it until then (LIST-4)
        (repository.updateList(PELLE, party.id, "Ours", shared = false) as ListUpdate.Updated).unshared shouldBe true
        (repository.updateList(PELLE, party.id, "Ours", shared = false) as ListUpdate.Updated).unshared shouldBe false
        repository.updateList(OTHER, party.id, "Theirs", shared = false) shouldBe ListUpdate.NotFound
        // unshared, its changes no longer reach the other user (LIST-4)
        repository.seesList(OTHER, party.id) shouldBe false
        repository.deleteList(PELLE, party.id) shouldBe ListDelete.Deleted
        repository.items(OTHER, party.id) shouldBe null
        repository.seesList(PELLE, party.id) shouldBe true
    }

    @Test
    fun `the high priorities come first, then the normal and the low ones, each in the order they were added`() {
        val list = repository.insertList(PELLE, "Home", shared = false)!!
        repository.insertItem(PELLE, list.id, "Low", priority = TodoPriority.LOW)
        repository.insertItem(PELLE, list.id, "Normal")
        repository.insertItem(PELLE, list.id, "High", priority = TodoPriority.HIGH)
        repository.insertItem(PELLE, list.id, "Normal too", priority = TodoPriority.NORMAL)
        repository.items(PELLE, list.id)!!.map { it.title to it.priority } shouldBe
            listOf(
                "High" to TodoPriority.HIGH,
                "Normal" to TodoPriority.NORMAL,
                "Normal too" to TodoPriority.NORMAL,
                "Low" to TodoPriority.LOW,
            )

        // a priority changed moves the task to its place
        val low = repository.items(PELLE, list.id)!!.last()
        repository.setPriority(PELLE, low.id, TodoPriority.HIGH)!!.let {
            it.priority shouldBe TodoPriority.HIGH
            it.updatedAt shouldBe now
        }
        repository.items(PELLE, list.id)!!.map { it.title }.take(2) shouldBe listOf("Low", "High")
        repository.setPriority(OTHER, low.id, TodoPriority.LOW) shouldBe null
    }

    @Test
    fun `a done task keeps its place, open again too, a purge deletes the done ones`() {
        val list = repository.insertList(PELLE, "Home", shared = false)!!
        val first = repository.insertItem(PELLE, list.id, "First")!!
        repository.insertItem(PELLE, list.id, "Second", LocalDate.parse("2026-12-24"))!!.dueDate shouldBe LocalDate.parse("2026-12-24")
        val third = repository.insertItem(PELLE, list.id, "Third")!!
        repository.items(PELLE, list.id)!!.map { it.title } shouldBe listOf("First", "Second", "Third")

        repository.setDone(PELLE, first.id, done = true)!!.doneAt shouldBe now
        repository.setDone(PELLE, third.id, done = true)
        repository.items(PELLE, list.id)!!.map { it.title to (it.doneAt != null) } shouldBe
            listOf("First" to true, "Second" to false, "Third" to true)
        repository.list(PELLE, list.id)!!.items shouldBe 1
        repository.setDone(PELLE, third.id, done = false)!!.doneAt shouldBe null
        repository.list(PELLE, list.id)!!.items shouldBe 2

        // the due date goes again (TODO-4)
        val second = repository.items(PELLE, list.id)!!.first { it.title == "Second" }
        repository.setDueDate(PELLE, second.id, null)!!.dueDate shouldBe null
        repository.setDueDate(OTHER, second.id, null) shouldBe null

        repository.purge(OTHER, list.id, listOf(first.id)) shouldBe null
        // only the done ones of the ids go: an open one stays, one done after the user saw the list too
        repository.setDone(PELLE, third.id, done = true)
        repository.purge(PELLE, list.id, listOf(first.id, second.id)) shouldBe 1
        repository.purge(PELLE, list.id, listOf(first.id)) shouldBe 0
        repository.item(PELLE, first.id) shouldBe null
        repository.setDone(PELLE, first.id, done = false) shouldBe null
        repository.items(PELLE, list.id)!!.map { it.title } shouldBe listOf("Second", "Third")
        // soft deleted: the row stays
        database.dsl.fetchOne(TODO_ITEMS, TODO_ITEMS.ID.eq(first.id))!!.deletedAt shouldBe now
    }

    @Test
    fun `the database takes a due date only as a real day, as yyyy-mm-dd`() {
        val list = repository.insertList(PELLE, "Home", shared = false)!!
        val task = repository.insertItem(PELLE, list.id, "Fix the door", LocalDate.parse("2026-02-28"))!!
        database.dsl.fetchValue("SELECT typeof(due_date) || ' ' || due_date FROM todo_items WHERE id = ?", task.id) shouldBe "text 2026-02-28"
        for (day in listOf("2026-02-30", "28.02.2026", "2026-2-28", "soon", "")) {
            shouldThrow<DataAccessException> { database.dsl.execute("UPDATE todo_items SET due_date = ? WHERE id = ?", day, task.id) }
        }
        database.dsl.execute("UPDATE todo_items SET due_date = NULL WHERE id = ?", task.id) shouldBe 1
    }
}
