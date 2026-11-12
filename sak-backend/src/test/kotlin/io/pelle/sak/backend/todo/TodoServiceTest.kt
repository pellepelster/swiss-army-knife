package io.pelle.sak.backend.todo

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.lists.ListResult
import io.pelle.sak.backend.lists.MAX_LIST_NAME_LENGTH
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.lists.ListDelete
import io.pelle.sak.model.todo.TodoPriority
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    ) = (service.createList(user, name, shared) as ListResult.Saved).list.id

    private fun add(
        user: String,
        listId: Int,
        title: String,
    ) = (service.addItem(user, listId, title) as TodoNewItemResult.Saved).item

    private fun done(
        user: String,
        id: Int,
    ) = (service.setDone(user, id, done = true) as TodoItemResult.Saved).item

    @Test
    fun `a task is trimmed, in the order they were added, done it keeps its place until a purge`() {
        val home = list(PELLE, "Home")
        val door = add(PELLE, home, "  Fix the door ")
        add(PELLE, home, "Paint the fence")
        door.title shouldBe "Fix the door"
        service.items(PELLE, home)!!.map { it.title } shouldBe listOf("Fix the door", "Paint the fence")

        done(PELLE, door.id).doneAt shouldNotBe null
        service.items(PELLE, home)!!.map { it.title } shouldBe listOf("Fix the door", "Paint the fence")
        // only the done tasks the user saw go, one done meanwhile stays (TODO-6)
        val fence = service.items(PELLE, home)!!.last()
        done(PELLE, fence.id)
        service.purge(PELLE, home, listOf(door.id)) shouldBe 1
        service.purge(OTHER, home, listOf(fence.id)) shouldBe null
        service.items(PELLE, home)!!.map { it.title } shouldBe listOf("Paint the fence")
        service.setDone(PELLE, 999, done = true) shouldBe TodoItemResult.NotFound
    }

    @Test
    fun `a title, a name and an icon that do not fit are refused, a task names a list the user sees`() {
        val home = list(PELLE, "Home")
        service.addItem(PELLE, home, " ") shouldBe
            TodoNewItemResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_TITLE, "max" to TodoService.MAX_TITLE_LENGTH)))
        service.addItem(PELLE, home, "x".repeat(TodoService.MAX_TITLE_LENGTH + 1)) shouldBe
            TodoNewItemResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_TITLE, "max" to TodoService.MAX_TITLE_LENGTH)))
        service.addItem(OTHER, home, "Peek") shouldBe TodoNewItemResult.UnknownList
        service.addItem(PELLE, home, "Later", "next week") shouldBe
            TodoNewItemResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_DUE_DATE)))
        service.addItem(PELLE, home, "Later", "2026-02-30") shouldBe
            TodoNewItemResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_DUE_DATE)))
        // a day that passed is a day too, an empty one none (TODO-4)
        (service.addItem(PELLE, home, "Late", "2020-01-01") as TodoNewItemResult.Saved).item.dueDate.toString() shouldBe "2020-01-01"
        (service.addItem(PELLE, home, "Whenever", "") as TodoNewItemResult.Saved).item.dueDate shouldBe null
        // the due date changes afterwards, none takes it away (TODO-4)
        val late = (service.addItem(PELLE, home, "Late again", "2020-01-01") as TodoNewItemResult.Saved).item
        (service.setDueDate(PELLE, late.id, null) as TodoItemResult.Saved).item.dueDate shouldBe null
        (service.setDueDate(PELLE, late.id, "2026-12-24") as TodoItemResult.Saved).item.dueDate.toString() shouldBe "2026-12-24"
        service.setDueDate(PELLE, late.id, "soon") shouldBe TodoItemResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_DUE_DATE)))
        service.setDueDate(OTHER, late.id, null) shouldBe TodoItemResult.NotFound
        // a priority is high, normal or low, none is normal (TODO-5)
        service.addItem(PELLE, home, "Soon", priority = "urgent") shouldBe
            TodoNewItemResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_PRIORITY)))
        (service.addItem(PELLE, home, "Soon", priority = "high") as TodoNewItemResult.Saved).item.priority shouldBe TodoPriority.HIGH
        val sometime = (service.addItem(PELLE, home, "Sometime") as TodoNewItemResult.Saved).item
        sometime.priority shouldBe TodoPriority.NORMAL
        // changed afterwards, by every user who sees the list
        (service.setPriority(PELLE, sometime.id, "low") as TodoItemResult.Saved).item.priority shouldBe TodoPriority.LOW
        service.setPriority(PELLE, sometime.id, "urgent") shouldBe TodoItemResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_PRIORITY)))
        service.setPriority(OTHER, sometime.id, "high") shouldBe TodoItemResult.NotFound

        service.createList(PELLE, "", shared = false, icon = "ab") shouldBe
            ListResult.Invalid(
                listOf(
                    ErrorMessage(TodoTexts.INVALID_LIST_NAME, "max" to MAX_LIST_NAME_LENGTH),
                    ErrorMessage(TodoTexts.INVALID_ICON),
                ),
            )
        service.createList(PELLE, " Home ", shared = false) shouldBe ListResult.Exists
        (service.createList(PELLE, "Party", shared = false, icon = " 🎉 ") as ListResult.Saved).list.icon shouldBe "🎉"
    }

    @Test
    fun `a shared list is seen and changed by every user, only its owner shares and deletes it`() {
        val party = list(PELLE, "Party", shared = true)
        val music = add(OTHER, party, "Bring music")
        // every user who sees the list marks a task done and purges it, also one of another user (LIST-2)
        done(PELLE, music.id).doneAt shouldNotBe null
        service.purge(OTHER, party, listOf(music.id)) shouldBe 1
        service.updateList(OTHER, party, "Ours", shared = false) shouldBe ListResult.NotOwner
        (service.updateList(OTHER, party, "Ours", shared = true) as ListResult.Saved).list.name shouldBe "Ours"
        service.deleteList(OTHER, party) shouldBe ListDelete.NotFound
        service.deleteList(PELLE, party) shouldBe ListDelete.Deleted
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
            service.purge(OTHER, party, listOf(music.id)) shouldBe 1
            // nothing to purge, no change
            service.purge(OTHER, party, listOf(music.id)) shouldBe 0
            service.deleteList(PELLE, party) shouldBe ListDelete.Deleted

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
}
