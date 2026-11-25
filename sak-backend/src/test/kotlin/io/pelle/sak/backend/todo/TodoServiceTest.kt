package io.pelle.sak.backend.todo

import io.pelle.sak.backend.DeleteResult
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.api.Page
import io.pelle.sak.backend.lists.ListSaveResult
import io.pelle.sak.backend.lists.MAX_LIST_NAME_LENGTH
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.todo.TodoPriority
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.pelle.sak.core.Language
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private const val PELLE = "pelle@pelle.io"

private const val OTHER = "other@example.org"

class TodoServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val service by lazy { TodoService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    private fun list(
        user: String,
        name: String,
        shared: Boolean = false,
    ) = (service.createList(user, name, shared) as ListSaveResult.Saved).list.id

    private fun add(
        user: String,
        listId: Int,
        title: String,
    ) = (service.addItem(user, listId, title) as TodoItemAddResult.Saved).item

    private fun done(
        user: String,
        id: Int,
    ) = (service.setDone(user, id, done = true) as TodoItemUpdateResult.Saved).item

    @Test
    fun `a user who never had a list gets a private first list in their language at the sign-in, once (LIST-1)`() {
        service.signedIn(PELLE, Language.DE)
        service.signedIn(PELLE, Language.EN)
        service.lists(PELLE).map { it.name to it.shared } shouldBe listOf("Meine Aufgaben" to false)

        // the shared lists of others do not count, a deleted list does not come back
        list(OTHER, "Team", shared = true)
        service.signedIn("third@example.org", Language.EN)
        service.lists("third@example.org").map { it.name } shouldBe listOf("My Todos", "Team")
        service.deleteList(PELLE, service.lists(PELLE).single { it.owner == PELLE }.id) shouldBe DeleteResult.Deleted
        service.signedIn(PELLE, Language.EN)
        service.lists(PELLE).filter { it.owner == PELLE } shouldBe emptyList()
    }

    @Test
    fun `a task is trimmed, in the order they were added, done it keeps its place until a purge`() {
        val home = list(PELLE, "Home")
        val door = add(PELLE, home, "  Fix the door ")
        add(PELLE, home, "Paint the fence")
        door.title shouldBe "Fix the door"
        service.items(PELLE, home)!!.items.map { it.title } shouldBe listOf("Fix the door", "Paint the fence")

        done(PELLE, door.id).doneAt shouldNotBe null
        service.items(PELLE, home)!!.items.map { it.title } shouldBe listOf("Fix the door", "Paint the fence")
        // only the done tasks the user saw go, one done meanwhile stays (TODO-6)
        val fence = service.items(PELLE, home)!!.items.last()
        done(PELLE, fence.id)
        service.purge(PELLE, home, listOf(door.id)) shouldBe DeleteResult.Deleted
        service.purge(OTHER, home, listOf(fence.id)) shouldBe DeleteResult.NotFound
        service.items(PELLE, home)!!.items.map { it.title } shouldBe listOf("Paint the fence")
        service.setDone(PELLE, 999, done = true) shouldBe TodoItemUpdateResult.NotFound
    }

    @Test
    fun `a task is renamed and deleted by itself, by every user who sees its list (LIST-2)`() {
        val party = list(PELLE, "Party", shared = true)
        val private = list(PELLE, "Home")
        val music = add(OTHER, party, "Bring music")
        val door = add(PELLE, private, "Fix the door")

        // trimmed, a done one too; a title that does not fit is refused and stays
        done(PELLE, music.id)
        (service.renameItem(PELLE, music.id, "  Bring the music ") as TodoItemUpdateResult.Saved).item.let {
            it.title shouldBe "Bring the music"
            it.doneAt shouldNotBe null
        }
        service.renameItem(PELLE, music.id, " ").shouldBeInstanceOf<TodoItemUpdateResult.Invalid>()
        service.renameItem(PELLE, music.id, "x".repeat(TodoService.MAX_TITLE_LENGTH + 1)).shouldBeInstanceOf<TodoItemUpdateResult.Invalid>()
        service.item(PELLE, music.id)!!.title shouldBe "Bring the music"

        // not a task of a list the user does not see
        service.renameItem(OTHER, door.id, "Theirs") shouldBe TodoItemUpdateResult.NotFound
        service.deleteItem(OTHER, door.id) shouldBe DeleteResult.NotFound
        service.item(PELLE, door.id)!!.title shouldBe "Fix the door"

        service.deleteItem(OTHER, music.id) shouldBe DeleteResult.Deleted
        service.deleteItem(OTHER, music.id) shouldBe DeleteResult.NotFound
        service.items(PELLE, party)!!.items shouldBe emptyList()
        service.renameItem(PELLE, music.id, "Gone") shouldBe TodoItemUpdateResult.NotFound
    }

    @Test
    fun `a title, a name and an icon that do not fit are refused, a task names a list the user sees`() {
        val home = list(PELLE, "Home")
        service.addItem(PELLE, home, " ") shouldBe
            TodoItemAddResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_TITLE, "max" to TodoService.MAX_TITLE_LENGTH)))
        service.addItem(PELLE, home, "x".repeat(TodoService.MAX_TITLE_LENGTH + 1)) shouldBe
            TodoItemAddResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_TITLE, "max" to TodoService.MAX_TITLE_LENGTH)))
        service.addItem(OTHER, home, "Peek") shouldBe TodoItemAddResult.UnknownList
        service.addItem(PELLE, home, "Later", "next week") shouldBe
            TodoItemAddResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_DUE_DATE)))
        service.addItem(PELLE, home, "Later", "2026-02-30") shouldBe
            TodoItemAddResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_DUE_DATE)))
        // a day that passed is a day too, an empty one none (TODO-4)
        (service.addItem(PELLE, home, "Late", "2020-01-01") as TodoItemAddResult.Saved).item.dueDate.toString() shouldBe "2020-01-01"
        (service.addItem(PELLE, home, "Whenever", "") as TodoItemAddResult.Saved).item.dueDate shouldBe null
        // the due date changes afterwards, none takes it away (TODO-4)
        val late = (service.addItem(PELLE, home, "Late again", "2020-01-01") as TodoItemAddResult.Saved).item
        (service.setDueDate(PELLE, late.id, null) as TodoItemUpdateResult.Saved).item.dueDate shouldBe null
        (service.setDueDate(PELLE, late.id, "2026-12-24") as TodoItemUpdateResult.Saved).item.dueDate.toString() shouldBe "2026-12-24"
        service.setDueDate(PELLE, late.id, "soon") shouldBe TodoItemUpdateResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_DUE_DATE)))
        service.setDueDate(OTHER, late.id, null) shouldBe TodoItemUpdateResult.NotFound
        // a priority is high, normal or low, none is normal (TODO-5)
        service.addItem(PELLE, home, "Soon", priority = "urgent") shouldBe
            TodoItemAddResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_PRIORITY)))
        (service.addItem(PELLE, home, "Soon", priority = "high") as TodoItemAddResult.Saved).item.priority shouldBe TodoPriority.HIGH
        val sometime = (service.addItem(PELLE, home, "Sometime") as TodoItemAddResult.Saved).item
        sometime.priority shouldBe TodoPriority.NORMAL
        // changed afterwards, by every user who sees the list
        (service.setPriority(PELLE, sometime.id, "low") as TodoItemUpdateResult.Saved).item.priority shouldBe TodoPriority.LOW
        service.setPriority(PELLE, sometime.id, "urgent") shouldBe TodoItemUpdateResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_PRIORITY)))
        service.setPriority(OTHER, sometime.id, "high") shouldBe TodoItemUpdateResult.NotFound

        service.createList(PELLE, "", shared = false, icon = "ab") shouldBe
            ListSaveResult.Invalid(
                listOf(
                    ErrorMessage(TodoTexts.INVALID_LIST_NAME, "max" to MAX_LIST_NAME_LENGTH),
                    ErrorMessage(TodoTexts.INVALID_ICON),
                ),
            )
        service.createList(PELLE, " Home ", shared = false) shouldBe ListSaveResult.Exists
        (service.createList(PELLE, "Party", shared = false, icon = " 🎉 ") as ListSaveResult.Saved).list.icon shouldBe "🎉"
    }

    @Test
    fun `a shared list is seen and changed by every user, only its owner shares and deletes it`() {
        val party = list(PELLE, "Party", shared = true)
        val music = add(OTHER, party, "Bring music")
        // every user who sees the list marks a task done and purges it, also one of another user (LIST-2)
        done(PELLE, music.id).doneAt shouldNotBe null
        service.purge(OTHER, party, listOf(music.id)) shouldBe DeleteResult.Deleted
        service.updateList(OTHER, party, "Party", shared = false) shouldBe ListSaveResult.NotOwner
        service.updateList(OTHER, party, "Ours", shared = true) shouldBe ListSaveResult.NotOwner
        (service.updateList(OTHER, party, "Party", shared = true, icon = "🎉") as ListSaveResult.Saved).list.icon shouldBe "🎉"
        service.deleteList(OTHER, party) shouldBe DeleteResult.NotFound
        service.deleteList(PELLE, party) shouldBe DeleteResult.Deleted
    }

    @Test
    fun `a change of a list reaches the users who see it, a private list of another user not (LIST-4)`(): Unit =
        runBlocking {
            val own = list(PELLE, "Home")
            val party = list(PELLE, "Party", shared = true)
            // listening before anything changes
            val heard = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { service.changes(OTHER).take(4).toList() } }

            add(PELLE, own, "Secret")
            val music = add(PELLE, party, "Bring music")
            done(OTHER, music.id)
            service.purge(OTHER, party, listOf(music.id)) shouldBe DeleteResult.Deleted
            // nothing to purge, no change
            service.purge(OTHER, party, listOf(music.id)) shouldBe DeleteResult.Deleted
            service.deleteList(PELLE, party) shouldBe DeleteResult.Deleted

            // the own list of pelle is private, the shared one heard also when it goes
            heard.await() shouldBe listOf(party, party, party, party)
        }

    @Test
    fun `a list unshared reaches the users who saw it, its changes after that not (LIST-4)`(): Unit =
        runBlocking {
            val party = list(PELLE, "Party", shared = true)
            val picnic = list(PELLE, "Picnic", shared = true)
            val heard = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { service.changes(OTHER).take(2).toList() } }

            // its unsharing reaches every user once, a task on the private list after that not
            service.updateList(PELLE, party, "Party", shared = false)
            add(PELLE, party, "Secret")
            add(PELLE, picnic, "Bring a blanket")

            heard.await() shouldBe listOf(party, picnic)
        }

    @Test
    fun `the tasks of a list are a page with the count of all`() {
        val home = (service.createList(PELLE, "Home", shared = false) as ListSaveResult.Saved).list.id
        (1..5).forEach { service.addItem(PELLE, home, "Task $it") }

        service.items(PELLE, home, offset = 3, limit = 10)!!.let {
            it.items.map { item -> item.title } shouldBe listOf("Task 4", "Task 5")
            it.total shouldBe 5
        }
        service.items(PELLE, home, offset = 9, limit = 10) shouldBe Page(emptyList(), 5)
        service.items(OTHER, home, offset = 0, limit = 10) shouldBe null
    }
}
