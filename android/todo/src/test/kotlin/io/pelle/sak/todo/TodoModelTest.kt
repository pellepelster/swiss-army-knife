package io.pelle.sak.todo

import io.ktor.http.HttpStatusCode
import io.pelle.sak.api.model.TodoItemResponse
import io.pelle.sak.api.model.TodoItemResponse.Priority.HIGH
import io.pelle.sak.core.Screen
import io.pelle.sak.core.lists.ChangeStream
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.openList
import io.pelle.sak.core.session.withOpenList
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.sakSetup
import io.pelle.sak.testing.answerOn
import io.pelle.sak.testing.changed
import io.pelle.sak.testing.refused
import io.pelle.sak.testing.signedIn
import io.pelle.sak.testing.english
import io.pelle.sak.testing.page
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class TodoModelTest {
    private val now = Instant.parse("2026-11-12T08:00:00Z")

    private val backend =
        FakeBackend().apply {
            answers["GET /todo/lists"] = HttpStatusCode.OK to page(list(1, "Home"), list(2, "Party", shared = true))
            answers["GET /todo/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Fix the door"), item(11, 1, "Paint the fence", "2026-12-24"))
            answers["GET /todo/lists/2/items"] = HttpStatusCode.OK to page()
        }

    private fun signedInStore(openList: Int? = 1) =
        MemorySessionStore(signedIn(now.plusSeconds(60)).withOpenList(openList))

    private fun TestScope.model(store: MemorySessionStore): TodoModel {
        backend.answerOn(this)
        return TodoModel(sakSetup(store, Clock.fixed(now, ZoneOffset.UTC)), backend::api)
    }

    private fun TestScope.signedIn(openList: Int? = 1): TodoModel = model(signedInStore(openList)).also { it.start() }

    private fun TodoModel.lists() = screen.value as ListsScreen

    private fun TodoModel.titles() = lists().items.map { it.title }

    @Test
    fun `the home shows all lists, a list opens with its open tasks and their due dates, back leads home`() =
        runTest {
            val store = signedInStore(openList = null)
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            assertEquals(listOf("Home", "Party"), model.lists().lists.map { it.name })
            assertTrue(backend.requests.none { it.contains("/items") })

            model.open(1)
            advanceUntilIdle()
            assertEquals(1, model.lists().open)
            assertEquals(listOf("Fix the door", "Paint the fence"), model.titles())
            assertEquals(listOf(null, "2026-12-24"), model.lists().items.map { it.dueDate })
            assertEquals(1, store.load().openList)

            model.back()
            advanceUntilIdle()
            assertNull(model.lists().open)
            assertNull(store.load().openList)
        }

    @Test
    fun `a task typed at the top goes to the list shown with the day chosen, the field is empty again`() =
        runTest {
            backend.answers["POST /todo/items"] = HttpStatusCode.Created to item(20, 2, "Cake", "2026-12-24")
            val model = signedIn(openList = 2)
            advanceUntilIdle()

            model.typed("   ")
            model.addItem()
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("POST /todo/items") })

            backend.answers["GET /todo/lists/2/items"] = HttpStatusCode.OK to page(item(20, 2, "Cake", "2026-12-24"))
            model.typed(" Cake ")
            model.chooseDue(LocalDate.parse("2026-12-24"))
            model.addItem()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /todo/items {"listId":2,"title":"Cake","dueDate":"2026-12-24"}"""))
            assertEquals("", model.lists().itemTitle)
            assertNull(model.lists().itemDue)
            assertEquals(listOf("Cake"), model.titles())

            // without a day, none is sent; nor the normal priority (TODO-5)
            model.typed("Tea")
            model.addItem()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /todo/items {"listId":2,"title":"Tea"}"""))

            // a priority chosen goes with the task, the next one is normal again
            model.typed("Taxes")
            assertEquals(Priority.NORMAL, model.lists().itemPriority)
            model.choosePriority(Priority.HIGH)
            model.addItem()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /todo/items {"listId":2,"title":"Taxes","priority":"high"}"""))
            assertEquals(Priority.NORMAL, model.lists().itemPriority)
        }

    @Test
    fun `a task the backend refuses keeps its title and day and shows why`() =
        runTest {
            backend.answers["POST /todo/items"] = HttpStatusCode.BadRequest to refused("A due date must be a day as yyyy-mm-dd.", "todo.invalid_due_date")
            val model = signedIn()
            advanceUntilIdle()
            model.typed("Taxes")
            model.chooseDue(LocalDate.parse("2026-05-31"))
            model.addItem()
            advanceUntilIdle()
            assertEquals("A due date must be a day as yyyy-mm-dd.", model.lists().error.english())
            assertEquals("Taxes", model.lists().itemTitle)
            assertEquals(LocalDate.parse("2026-05-31"), model.lists().itemDue)
        }

    @Test
    fun `a task done is struck through at once and the backend learns it, done again it is open again`() =
        runTest {
            backend.answers["PUT /todo/items/10/done"] = HttpStatusCode.OK to item(10, 1, "Fix the door", doneAt = DONE)
            val model = signedIn()
            advanceUntilIdle()
            val door = model.lists().items.first()

            backend.answers["GET /todo/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Fix the door", doneAt = DONE), item(11, 1, "Paint the fence", "2026-12-24"))
            model.done(door)
            assertTrue(model.lists().items.first { it.id == 10 }.doneAt != null)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /todo/items/10/done {"done":true}"""))
            // in its place, as the backend has it; no toast
            assertEquals(listOf("Fix the door", "Paint the fence"), model.titles())
            assertNull(model.lists().undo)

            backend.answers["PUT /todo/items/10/done"] = HttpStatusCode.OK to item(10, 1, "Fix the door")
            model.done(model.lists().items.first { it.id == 10 })
            assertNull(model.lists().items.first { it.id == 10 }.doneAt)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /todo/items/10/done {"done":false}"""))
        }

    @Test
    fun `the priority of a task changes from its menu, the list loads with it in its new place, a refused one shows why`() =
        runTest {
            backend.answers["PUT /todo/items/11/priority"] = HttpStatusCode.OK to item(11, 1, "Paint the fence", "2026-12-24", HIGH)
            val model = signedIn()
            advanceUntilIdle()
            val fence = model.lists().items.first { it.id == 11 }

            model.setPriority(fence, Priority.NORMAL)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.contains("/priority") })

            backend.answers["GET /todo/lists/1/items"] =
                HttpStatusCode.OK to page(item(11, 1, "Paint the fence", "2026-12-24", HIGH), item(10, 1, "Fix the door"))
            model.setPriority(fence, Priority.HIGH)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /todo/items/11/priority {"priority":"high"}"""))
            assertEquals(listOf("Paint the fence", "Fix the door"), model.titles())

            backend.answers["PUT /todo/items/10/priority"] = HttpStatusCode.InternalServerError to ""
            model.setPriority(model.lists().items.first { it.id == 10 }, Priority.LOW)
            advanceUntilIdle()
            assertEquals("The priority could not be changed.", model.lists().error.english())
        }

    @Test
    fun `a due date is added to a task without one and taken away again from its menu, a refused one shows why`() =
        runTest {
            backend.answers["PUT /todo/items/11/due-date"] = HttpStatusCode.OK to item(11, 1, "Paint the fence")
            val model = signedIn()
            advanceUntilIdle()

            // a task without one sends nothing to take it away
            model.setDueDate(model.lists().items.first { it.id == 10 }, null)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.contains("/due-date") })

            backend.answers["PUT /todo/items/10/due-date"] = HttpStatusCode.OK to item(10, 1, "Fix the door", "2026-12-31")
            model.setDueDate(model.lists().items.first { it.id == 10 }, LocalDate.parse("2026-12-31"))
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /todo/items/10/due-date {"dueDate":"2026-12-31"}"""))

            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Fix the door"), item(11, 1, "Paint the fence"))
            model.setDueDate(model.lists().items.first { it.id == 11 }, null)
            advanceUntilIdle()
            // a nullable field without a value is left out, the backend takes it as none
            assertTrue(backend.requests.contains("""PUT /todo/items/11/due-date {}"""))
            assertNull(model.lists().items.first { it.id == 11 }.dueDate)

            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Fix the door", "2026-12-24"))
            model.refresh()
            advanceUntilIdle()
            backend.answers["PUT /todo/items/10/due-date"] = HttpStatusCode.InternalServerError to ""
            model.setDueDate(model.lists().items.first { it.id == 10 }, null)
            advanceUntilIdle()
            assertEquals("The due date could not be saved.", model.lists().error.english())
        }

    // the Rename of the menu of the task, the title typed, then saved
    private fun TodoModel.rename(
        item: TodoItemResponse,
        title: String,
    ) {
        askRename(item)
        editRename(title)
        renameItem()
    }

    private fun TodoModel.item(title: String) = lists().items.first { it.title == title }

    @Test
    fun `a task is renamed from its menu, an invalid or unchanged title is not sent, a refused one shows why`() =
        runTest {
            backend.answers["PUT /todo/items/10"] = HttpStatusCode.OK to item(10, 1, "Fix the gate")
            backend.answers["PUT /todo/items/11"] = HttpStatusCode.BadRequest to refused("no")
            val model = signedIn()
            advanceUntilIdle()

            backend.answers["GET /todo/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Fix the gate"), item(11, 1, "Paint the fence", "2026-12-24"))
            model.rename(model.item("Fix the door"), "  Fix the gate ")
            assertNull(model.lists().renaming)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /todo/items/10 {"title":"Fix the gate"}"""))
            assertNull(model.lists().error)
            // the list loads again as the backend has it
            assertEquals(listOf("Fix the gate", "Paint the fence"), model.titles())

            model.rename(model.item("Paint the fence"), "Paint")
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /todo/items/11 {"title":"Paint"}"""))
            assertEquals("no", model.lists().error.english())
            assertEquals(listOf("Fix the gate", "Paint the fence"), model.titles())

            val sent = backend.requests.count { it.startsWith("PUT /todo/items/") }
            // an invalid title stays in the dialog, its error shows (UX-6)
            model.askRename(model.item("Fix the gate"))
            model.editRename(" ")
            assertFalse(model.lists().renaming!!.left)
            model.renameItem()
            assertTrue(model.lists().renaming!!.left)
            model.editRename("x".repeat(257))
            model.renameItem()
            assertNotNull(model.lists().renaming)
            model.dismissRename()
            assertNull(model.lists().renaming)
            // the field left shows the error too
            model.askRename(model.item("Fix the gate"))
            model.leaveRename()
            assertTrue(model.lists().renaming!!.left)
            // unchanged, the dialog closes and nothing is sent
            model.editRename(" Fix the gate ")
            model.renameItem()
            assertNull(model.lists().renaming)
            advanceUntilIdle()
            assertEquals(sent, backend.requests.count { it.startsWith("PUT /todo/items/") })
            assertTrue(TodoModel.validTitle("x".repeat(256)))
            assertFalse(TodoModel.validTitle("x".repeat(257)))
        }

    @Test
    fun `a rename the backend can not do, or of a task that is gone, shows why and the list as the backend has it`() =
        runTest {
            backend.answers["PUT /todo/items/10"] = HttpStatusCode.InternalServerError to ""
            val model = signedIn()
            advanceUntilIdle()

            model.rename(model.item("Fix the door"), "Fix the gate")
            advanceUntilIdle()
            assertEquals("The task could not be renamed.", model.lists().error.english())
            assertEquals(listOf("Fix the door", "Paint the fence"), model.titles())

            // deleted meanwhile, e.g. by another user: no answer of the fake backend is a 404
            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Fix the door"))
            model.rename(model.item("Paint the fence"), "Paint")
            advanceUntilIdle()
            assertEquals("The task could not be renamed.", model.lists().error.english())
            assertEquals(listOf("Fix the door"), model.titles())
        }

    @Test
    fun `a task deleted from the menu of its row goes for good, a refused delete shows why`() =
        runTest {
            backend.answers["DELETE /todo/items/10"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()

            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(11, 1, "Paint the fence", "2026-12-24"))
            model.askDelete(model.item("Fix the door"))
            assertEquals(10, model.lists().deleting?.id)
            // nothing goes before the user confirmed it (UX-4.1)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("DELETE") })
            model.deleteItem(model.item("Fix the door"))
            assertNull(model.lists().deleting)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /todo/items/10"))
            assertNull(model.lists().error)
            // no undo: the list loads again as the backend has it
            assertNull(model.lists().undo)
            assertEquals(listOf("Paint the fence"), model.titles())

            // refused, here a task that is gone already (404): the list says why and loads
            model.deleteItem(model.item("Paint the fence"))
            advanceUntilIdle()
            assertEquals("The task could not be deleted.", model.lists().error.english())
            assertEquals(listOf("Paint the fence"), model.titles())
        }

    @Test
    fun `the dialogs of the menu of a task stay when its row goes, back closes them`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()

            model.askRename(model.item("Fix the door"))
            model.editRename("Fix the ga")
            // another user deleted it: the list no longer shows it, the dialog keeps the title typed
            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(11, 1, "Paint the fence", "2026-12-24"))
            model.refresh()
            advanceUntilIdle()
            assertEquals(listOf("Paint the fence"), model.titles())
            assertEquals("Fix the ga", model.lists().renaming?.name)
            model.back()
            assertNull(model.lists().renaming)
            assertEquals(1, model.lists().open)

            model.askDelete(model.item("Paint the fence"))
            model.back()
            assertNull(model.lists().deleting)
            model.askDelete(model.item("Paint the fence"))
            model.dismissDelete()
            assertNull(model.lists().deleting)
            assertEquals(1, model.lists().open)
        }

    @Test
    fun `a task the backend can not mark done shows why and the list as the backend has it`() =
        runTest {
            backend.answers["PUT /todo/items/10/done"] = HttpStatusCode.InternalServerError to ""
            val model = signedIn()
            advanceUntilIdle()
            model.done(model.lists().items.first())
            advanceUntilIdle()
            assertEquals("The task could not be marked done.", model.lists().error.english())
            assertNull(model.lists().items.first { it.id == 10 }.doneAt)
        }

    @Test
    fun `a purge takes the done tasks away at once, undo brings them back, the backend purges once the undo ran out`() =
        runTest {
            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Fix the door", doneAt = DONE), item(11, 1, "Paint the fence"))
            backend.answers["POST /todo/lists/1/purge"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()

            model.purge()
            assertEquals(listOf("Paint the fence"), model.titles())
            assertEquals("Done tasks deleted", model.lists().undo?.text.english())
            model.undo()
            advanceUntilIdle()
            assertEquals(listOf("Fix the door", "Paint the fence"), model.titles())
            assertTrue(backend.requests.none { it.contains("/purge") })

            model.purge()
            advanceTimeBy(UNDO_MS - 1)
            runCurrent()
            // a load meanwhile keeps the done tasks away
            model.refresh()
            runCurrent()
            assertEquals(listOf("Paint the fence"), model.titles())
            assertTrue(backend.requests.none { it.contains("/purge") })

            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(11, 1, "Paint the fence"))
            advanceUntilIdle()
            assertEquals(listOf("""POST /todo/lists/1/purge {"itemIds":[10]}"""), backend.requests.filter { it.contains("/purge") })
            assertNull(model.lists().undo)
            assertEquals(listOf("Paint the fence"), model.titles())
        }

    @Test
    fun `a purge is sent when the app goes, nothing to purge without done tasks`() =
        runTest {
            backend.answers["POST /todo/lists/1/purge"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()
            model.purge()
            assertNull(model.lists().undo)

            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Fix the door", doneAt = DONE))
            model.refresh()
            advanceUntilIdle()
            model.purge()
            model.background()
            advanceUntilIdle()
            assertEquals(listOf("""POST /todo/lists/1/purge {"itemIds":[10]}"""), backend.requests.filter { it.contains("/purge") })
        }

    @Test
    fun `a purge the backend refuses shows why and the done tasks again`() =
        runTest {
            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Fix the door", doneAt = DONE))
            backend.answers["POST /todo/lists/1/purge"] = HttpStatusCode.InternalServerError to ""
            val model = signedIn()
            advanceUntilIdle()
            model.purge()
            advanceUntilIdle()
            assertEquals("The done tasks could not be deleted.", model.lists().error.english())
            assertEquals(listOf("Fix the door"), model.titles())
        }

    @Test
    fun `a purge takes only the done tasks shown, a task done meanwhile stays struck through and is not purged, also over a load`() =
        runTest {
            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(11, 1, "Paint the fence"), item(10, 1, "Fix the door", doneAt = DONE))
            backend.answers["PUT /todo/items/11/done"] = HttpStatusCode.OK to item(11, 1, "Paint the fence", doneAt = DONE)
            backend.answers["POST /todo/lists/1/purge"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()

            model.purge()
            assertEquals(listOf("Paint the fence"), model.titles())
            model.done(model.lists().items.single())
            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(11, 1, "Paint the fence", doneAt = DONE), item(10, 1, "Fix the door", doneAt = DONE))
            advanceTimeBy(UNDO_MS / 2)
            runCurrent()
            // struck through in its place, the load after the done keeps the purged one away
            assertEquals(listOf("Paint the fence"), model.titles())
            assertTrue(model.lists().items.single().doneAt != null)

            advanceUntilIdle()
            assertEquals(listOf("""POST /todo/lists/1/purge {"itemIds":[10]}"""), backend.requests.filter { it.contains("/purge") })
        }

    @Test
    fun `undo brings the done tasks back in their places without asking the backend`() =
        runTest {
            backend.answers["GET /todo/lists/1/items"] =
                HttpStatusCode.OK to page(item(12, 1, "Call", priority = HIGH), item(10, 1, "Fix the door", doneAt = DONE), item(11, 1, "Paint the fence"))
            val model = signedIn()
            advanceUntilIdle()
            model.purge()
            assertEquals(listOf("Call", "Paint the fence"), model.titles())

            // no connection
            backend.answers["GET /todo/lists"] = HttpStatusCode.InternalServerError to ""
            val asked = backend.requests.size
            model.undo()
            advanceUntilIdle()
            assertEquals(listOf("Call", "Fix the door", "Paint the fence"), model.titles())
            assertNull(model.lists().undo)
            assertNull(model.lists().error)
            assertEquals(asked, backend.requests.size)
        }

    @Test
    fun `a quick second tap on a task waits for the first, the backend learns both in their order`() =
        runTest {
            backend.answers["PUT /todo/items/10/done"] = HttpStatusCode.OK to item(10, 1, "Fix the door")
            val model = signedIn()
            advanceUntilIdle()
            val door = model.lists().items.first { it.id == 10 }

            model.done(door)
            // the row as composed before the first tap: the tap opens it again
            model.done(door)
            assertNull(model.lists().items.first { it.id == 10 }.doneAt)
            advanceUntilIdle()
            assertEquals(
                listOf("""PUT /todo/items/10/done {"done":true}""", """PUT /todo/items/10/done {"done":false}"""),
                backend.requests.filter { it.contains("/done") },
            )
            assertNull(model.lists().items.first { it.id == 10 }.doneAt)
        }

    @Test
    fun `a session that ended leads to the sign-in`() =
        runTest {
            backend.answers["GET /todo/lists"] = HttpStatusCode.Unauthorized to ""
            val model = signedIn()
            advanceUntilIdle()
            assertTrue(model.screen.value is Screen.SignIn)
        }

    @Test
    fun `while the app is seen the stream of the todo lists loads the list open again on its change`() =
        runTest {
            backend.answers["GET /todo/changes"] = HttpStatusCode.OK to ": listening\n\n${changed(1)}"
            val model = signedIn()
            advanceUntilIdle()
            val loads = { backend.requests.count { it == "GET /todo/lists/1/items" } }
            assertEquals(1, loads())
            model.foreground()
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS / 2)
            runCurrent()
            model.background()
            assertEquals("t", backend.tokens["/todo/changes"])
            assertEquals(2, loads())
        }
}
