package io.pelle.sak.todo

import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.Screen
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.session.openList
import io.pelle.sak.core.session.withOpenList
import io.pelle.sak.core.update.Updater
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.URL
import io.pelle.sak.testing.english
import io.pelle.sak.testing.page
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
            answers["GET /todo/lists"] = HttpStatusCode.OK to page(list(1, "Home"), list(2, "Party", shared = true))
            answers["GET /todo/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Fix the door"), item(11, 1, "Paint the fence", "2026-12-24"))
            answers["GET /todo/lists/2/items"] = HttpStatusCode.OK to page()
        }

    private fun list(
        id: Int,
        name: String,
        shared: Boolean = false,
    ) = """{"id":$id,"name":"$name","shared":$shared,"mine":true,"items":0,"createdAt":"2026-11-12T07:00:00.000Z"}"""

    private fun item(
        id: Int,
        listId: Int,
        title: String,
        dueDate: String? = null,
        priority: String = "normal",
    ) = """{"id":$id,"listId":$listId,"title":"$title","priority":"$priority","createdAt":"2026-11-12T07:00:00.000Z"${dueDate?.let { ",\"dueDate\":\"$it\"" } ?: ""}}"""

    private fun signedInStore(openList: Int? = 1) =
        MemorySessionStore(
            Stored(apiUrl = URL, address = "https://sak.example.org", token = "t", email = "admin", expiresAt = now.plusSeconds(60)).withOpenList(openList),
        )

    private fun TestScope.model(store: MemorySessionStore): TodoModel {
        backend.dispatcher = StandardTestDispatcher(testScheduler)
        return TodoModel(store, backend::api, this, allowUrl = false, clock = Clock.fixed(now, ZoneOffset.UTC), updater = Updater { error("no update expected") })
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
            backend.answers["POST /todo/items"] = HttpStatusCode.BadRequest to """{"errors":[{"code":"todo.invalid_due_date","message":"A due date must be a day as yyyy-mm-dd."}]}"""
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

    private fun done(item: String) = item.dropLast(1) + ""","doneAt":"2026-11-12T08:00:00Z"}"""

    @Test
    fun `a task done is struck through at once and the backend learns it, done again it is open again`() =
        runTest {
            backend.answers["PUT /todo/items/10/done"] = HttpStatusCode.OK to done(item(10, 1, "Fix the door"))
            val model = signedIn()
            advanceUntilIdle()
            val door = model.lists().items.first()

            backend.answers["GET /todo/lists/1/items"] =
                HttpStatusCode.OK to page(done(item(10, 1, "Fix the door")), item(11, 1, "Paint the fence", "2026-12-24"))
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
            backend.answers["PUT /todo/items/11/priority"] = HttpStatusCode.OK to item(11, 1, "Paint the fence", "2026-12-24", "high")
            val model = signedIn()
            advanceUntilIdle()
            val fence = model.lists().items.first { it.id == 11 }

            model.setPriority(fence, Priority.NORMAL)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.contains("/priority") })

            backend.answers["GET /todo/lists/1/items"] =
                HttpStatusCode.OK to page(item(11, 1, "Paint the fence", "2026-12-24", "high"), item(10, 1, "Fix the door"))
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
            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(done(item(10, 1, "Fix the door")), item(11, 1, "Paint the fence"))
            backend.answers["POST /todo/lists/1/purge"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()

            model.purge()
            assertEquals(listOf("Paint the fence"), model.titles())
            assertEquals("Done tasks removed", model.lists().undo?.text.english())
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

            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(done(item(10, 1, "Fix the door")))
            model.refresh()
            advanceUntilIdle()
            model.purge()
            model.stopped()
            advanceUntilIdle()
            assertEquals(listOf("""POST /todo/lists/1/purge {"itemIds":[10]}"""), backend.requests.filter { it.contains("/purge") })
        }

    @Test
    fun `a purge the backend refuses shows why and the done tasks again`() =
        runTest {
            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(done(item(10, 1, "Fix the door")))
            backend.answers["POST /todo/lists/1/purge"] = HttpStatusCode.InternalServerError to ""
            val model = signedIn()
            advanceUntilIdle()
            model.purge()
            advanceUntilIdle()
            assertEquals("The done tasks could not be removed.", model.lists().error.english())
            assertEquals(listOf("Fix the door"), model.titles())
        }

    @Test
    fun `a purge takes only the done tasks shown, a task done meanwhile stays struck through and is not purged, also over a load`() =
        runTest {
            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(11, 1, "Paint the fence"), done(item(10, 1, "Fix the door")))
            backend.answers["PUT /todo/items/11/done"] = HttpStatusCode.OK to done(item(11, 1, "Paint the fence"))
            backend.answers["POST /todo/lists/1/purge"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()

            model.purge()
            assertEquals(listOf("Paint the fence"), model.titles())
            model.done(model.lists().items.single())
            backend.answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(done(item(11, 1, "Paint the fence")), done(item(10, 1, "Fix the door")))
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
                HttpStatusCode.OK to page(item(12, 1, "Call", priority = "high"), done(item(10, 1, "Fix the door")), item(11, 1, "Paint the fence"))
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
    fun `a task added while another list opens changes nothing there`() =
        runTest {
            backend.answers["POST /todo/items"] = HttpStatusCode.BadRequest to """{"errors":[{"code":"todo.invalid_title","message":"Refused."}]}"""
            val model = signedIn()
            advanceUntilIdle()
            model.typed("Taxes")
            model.addItem()
            model.back()
            model.open(2)
            model.typed("Cake")
            advanceUntilIdle()
            assertEquals(2, model.lists().open)
            assertEquals("Cake", model.lists().itemTitle)
            assertNull(model.lists().error)
            assertTrue(!model.lists().adding)
        }

    @Test
    fun `the list deleted from its settings leads to the home, also on the next start`() =
        runTest {
            backend.answers["DELETE /todo/lists/1"] = HttpStatusCode.NoContent to ""
            val store = signedInStore(openList = 1)
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.openSettings(1)
            backend.answers["GET /todo/lists"] = HttpStatusCode.OK to page(list(2, "Party", shared = true))
            model.deleteList()
            advanceUntilIdle()
            assertNull(model.lists().open)
            assertNull(model.lists().settings)
            assertNull(store.load().openList)
        }

    @Test
    fun `a new list is created and opens, a shared list of another user is not the user's to share`() =
        runTest {
            backend.answers["POST /todo/lists"] = HttpStatusCode.Created to list(3, "Work")
            backend.answers["GET /todo/lists/3/items"] = HttpStatusCode.OK to page()
            val model = signedIn(openList = null)
            advanceUntilIdle()
            // the backend has it from now on
            backend.answers["GET /todo/lists"] = HttpStatusCode.OK to page(list(1, "Home"), list(3, "Work"))
            model.newList()
            model.editSettings("Work", shared = false, icon = "💼")
            model.saveSettings()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /todo/lists {"name":"Work","shared":false,"icon":"💼"}"""))
            assertEquals(3, model.lists().open)
            assertNull(model.lists().settings)

            backend.answers["GET /todo/lists"] =
                HttpStatusCode.OK to page("""{"id":4,"name":"Theirs","shared":true,"mine":false,"items":0,"createdAt":"2026-11-12T07:00:00.000Z"}""")
            model.refresh()
            advanceUntilIdle()
            model.openSettings(4)
            model.editSettings("Ours", shared = false, icon = null)
            assertEquals(true, model.lists().settings?.shared)
            assertEquals("Ours", model.lists().settings?.name)
        }

    @Test
    fun `a session that ended leads to the sign-in`() =
        runTest {
            backend.answers["GET /todo/lists"] = HttpStatusCode.Unauthorized to ""
            val model = signedIn()
            advanceUntilIdle()
            assertTrue(model.screen.value is Screen.SignIn)
        }
}
