package io.pelle.sak.testing

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.pelle.sak.api.model.LoginRequest
import io.pelle.sak.api.model.TodoListRequest
import io.pelle.sak.api.model.TodoItemResponse
import io.pelle.sak.api.model.TodoListResponse
import io.pelle.sak.api.model.TodoNewItemRequest
import io.pelle.sak.core.R
import io.pelle.sak.core.Screen
import io.pelle.sak.core.UiText
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.core.lists.ChangeStream
import io.pelle.sak.core.lists.ListFields
import io.pelle.sak.core.lists.ListSummary
import io.pelle.sak.core.lists.ListTexts
import io.pelle.sak.core.lists.ListsApi
import io.pelle.sak.core.lists.ListsAppScreen
import io.pelle.sak.core.lists.ListsModel
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.openList
import io.pelle.sak.core.session.withOpenList
import io.pelle.sak.core.update.AppUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// core has no texts of lists: the test takes others of it, each failure a text of its own
private val TEXTS =
    ListTexts(
        appName = R.string.shared_settings,
        create = R.string.shared_settings,
        allLists = R.string.shared_settings,
        chooseIcon = R.string.shared_settings,
        noIcon = R.string.shared_settings,
        deleteList = R.string.shared_delete,
        notCreated = R.string.error_link_not_requested,
        notSaved = R.string.error_link_not_used,
        notDeleted = R.string.error_user_not_read,
        notLoaded = R.string.error_password_not_checked,
        gone = R.string.error_changes_not_read,
    )

// the lists of an app below /things, with the bodies of the todo lists
private class ThingListsApi(
    client: HttpClient,
    url: String,
    token: String?,
) : SakApi(client, url, token, "en", LoginRequest.Client.TODO_ANDROID),
    ListsApi<TodoListResponse> {
    override suspend fun lists(): List<TodoListResponse> = all("$url/things/lists", TEXTS.notLoaded)

    suspend fun items(listId: Int): List<TodoItemResponse> = all("$url/things/lists/$listId/items", TEXTS.notLoaded)

    suspend fun addItem(
        listId: Int,
        title: String,
    ): TodoItemResponse =
        client
            .post("$url/things/items") {
                session()
                contentType(ContentType.Application.Json)
                setBody(TodoNewItemRequest(listId = listId, title = title))
            }.answer(TEXTS.notCreated)

    override suspend fun createList(
        name: String,
        shared: Boolean,
        icon: String?,
    ): TodoListResponse =
        client
            .post("$url/things/lists") {
                session()
                contentType(ContentType.Application.Json)
                setBody(TodoListRequest(name, shared, icon))
            }.answer(TEXTS.notCreated)

    override suspend fun updateList(
        id: Int,
        name: String,
        shared: Boolean,
        icon: String?,
    ): TodoListResponse =
        client
            .put("$url/things/lists/$id") {
                session()
                contentType(ContentType.Application.Json)
                setBody(TodoListRequest(name, shared, icon))
            }.answer(TEXTS.notSaved)

    override suspend fun deleteList(id: Int) {
        client.delete("$url/things/lists/$id") { session() }.answer<Unit>(TEXTS.notDeleted)
    }

    override fun changes(opened: suspend () -> Unit): Flow<Int> = listChanges("$url/things/changes", opened)
}

private data class ThingListsScreen(
    override val user: String,
    override val lists: List<TodoListResponse> = emptyList(),
    override val open: Int? = null,
    override val settings: ListFields? = null,
    // the titles of the entries of the list open
    val entries: List<String> = emptyList(),
    val typed: String = "",
    override val adding: Boolean = false,
    val loading: Boolean = true,
    val error: UiText? = null,
    override val update: AppUpdate? = null,
    override val updateNotice: UiText? = null,
) : ListsAppScreen<TodoListResponse, ListFields> {
    override fun withUser(user: String) = copy(user = user)

    override fun withUpdate(
        update: AppUpdate?,
        updateNotice: UiText?,
    ) = copy(update = update, updateNotice = updateNotice)

    override fun withLists(lists: List<TodoListResponse>) = copy(lists = lists)

    override fun withOpen(open: Int?) = copy(open = open)

    override fun withSettings(settings: ListFields?) = copy(settings = settings)

    override fun withError(error: UiText?) = copy(error = error)

    override fun withLoading(loading: Boolean) = copy(loading = loading)

    override fun withAdding(adding: Boolean) = copy(adding = adding)
}

// the smallest app on ListsModel; it keeps what the hooks were called with
private class ThingListsModel(
    store: SessionStore,
    backend: FakeBackend,
    scope: TestScope,
    clock: Clock,
) : ListsModel<ThingListsApi, TodoListResponse, ListFields, ThingListsScreen>(
        scope.sakSetup(store, clock, updater = null),
        { url, token -> ThingListsApi(backend.client, url, token) },
        texts = TEXTS,
        summaryOf = { ListSummary(it.id, it.name, it.icon, it.shared, it.mine, it.items) },
        settingsOf = { it },
    ) {
    val settingsOpenedFor = mutableListOf<Int>()
    var saving = 0

    // the settings as the hook before saving found them, busy not yet shown
    var savingBusy: Boolean? = null

    override fun appScreen(
        user: String,
        open: Int?,
    ) = ThingListsScreen(user, open = open)

    override fun listsScreen() = state.value as? ThingListsScreen

    override fun opened(
        lists: ThingListsScreen,
        open: Int?,
    ) = lists.copy(entries = emptyList(), typed = "")

    override suspend fun loaded(
        api: ThingListsApi,
        open: Int?,
    ): (ThingListsScreen) -> ThingListsScreen {
        val entries = open?.let { api.items(it) }.orEmpty().map { it.title }
        return { it.copy(entries = entries) }
    }

    override fun settingsOpened(id: Int) {
        settingsOpenedFor += id
    }

    override fun savingSettings() {
        saving++
        savingBusy = listsScreen()?.settings?.busy
    }

    override fun back() {
        val lists = listsScreen() ?: return
        if (lists.settings != null) state.value = lists.copy(settings = null) else if (lists.open != null) show(null)
    }

    fun type(text: String) = listsState { it.copy(typed = text) }

    fun add() {
        val title = listsScreen()?.typed?.trim() ?: return
        addEntry(title, TEXTS.notCreated, { it }, { it.copy(typed = "") }) { api, list -> api.addItem(list, title) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ListsModelTest {
    private val now = Instant.parse("2026-11-12T08:00:00Z")

    private val backend =
        FakeBackend().apply {
            answers["GET /things/lists"] = HttpStatusCode.OK to page(list(1, "Home"), list(2, "Party", shared = true, mine = false))
            answers["GET /things/lists/1/items"] = HttpStatusCode.OK to page()
            answers["GET /things/lists/2/items"] = HttpStatusCode.OK to page()
        }

    private fun list(
        id: Int,
        name: String,
        shared: Boolean = false,
        mine: Boolean = true,
    ) = json(TodoListResponse(id, name, shared, mine, 0, "2026-11-12T07:00:00.000Z"))

    private fun store(openList: Int? = null) =
        MemorySessionStore(signedIn(now.plusSeconds(60)).withOpenList(openList))

    private fun TestScope.signedIn(store: MemorySessionStore = store()): ThingListsModel {
        backend.answerOn(this)
        return ThingListsModel(store, backend, this, Clock.fixed(now, ZoneOffset.UTC)).also { it.start() }
    }

    private fun ThingListsModel.lists() = screen.value as ThingListsScreen

    @Test
    fun `a new list is created from the home and opens, the hook before saving runs before its busy`() =
        runTest {
            val store = store()
            val model = signedIn(store)
            advanceUntilIdle()
            model.newList()
            assertEquals(ListFields(), model.lists().settings)
            model.editSettings("Garden", shared = true, icon = "🌻")
            backend.answers["POST /things/lists"] = HttpStatusCode.Created to list(3, "Garden", shared = true)
            backend.answers["GET /things/lists"] = HttpStatusCode.OK to page(list(3, "Garden", shared = true), list(1, "Home"))
            model.saveSettings()
            assertTrue(model.lists().settings!!.busy)
            assertEquals(1, model.saving)
            assertEquals(false, model.savingBusy)
            // a second save while the first is sent changes nothing
            model.saveSettings()
            assertEquals(1, model.saving)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /things/lists {"name":"Garden","shared":true,"icon":"🌻"}"""))
            assertNull(model.lists().settings)
            assertEquals(3, model.lists().open)
            assertEquals(3, store.load().openList)
        }

    @Test
    fun `an empty name is not saved, no new list from a list open`() =
        runTest {
            val model = signedIn(store(openList = 1))
            advanceUntilIdle()
            model.newList()
            assertNull(model.lists().settings)
            model.openSettings(1)
            model.editSettings("  ", shared = false, icon = null)
            model.saveSettings()
            advanceUntilIdle()
            assertEquals(0, model.saving)
            assertFalse(model.lists().settings!!.busy)
            assertTrue(backend.requests.none { it.startsWith("PUT") })
        }

    @Test
    fun `the settings of a shared list of another user change its icon, not its name or whether it is shared`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()
            model.openSettings(2)
            assertEquals(listOf(2), model.settingsOpenedFor)
            assertEquals(ListFields(2, "Party", shared = true, mine = false), model.lists().settings)
            model.editSettings("Feast", shared = false, icon = "🎉")
            assertTrue(model.lists().settings!!.shared)
            assertEquals("Party", model.lists().settings!!.name)
            backend.answers["PUT /things/lists/2"] = HttpStatusCode.OK to list(2, "Party", shared = true, mine = false)
            model.saveSettings()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /things/lists/2 {"name":"Party","shared":true,"icon":"🎉"}"""))
            assertNull(model.lists().settings)
            // only its owner deletes it
            model.openSettings(2)
            model.deleteList()
            model.deleteList(2)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("DELETE") })
        }

    @Test
    fun `a refused create or save stays on the settings with the reason, a list gone closes them`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()
            backend.answers["POST /things/lists"] = HttpStatusCode.Conflict to refused("A list of this name exists.")
            model.newList()
            model.editSettings(" Party ", shared = true, icon = null)
            model.saveSettings()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /things/lists {"name":"Party","shared":true}"""))
            assertEquals(ListFields(name = " Party ", shared = true, error = UiText.Plain("A list of this name exists.")), model.lists().settings)
            model.back()
            assertNull(model.lists().settings)

            model.openSettings(1)
            model.editSettings("Party", shared = false, icon = null)
            backend.answers["PUT /things/lists/1"] = HttpStatusCode.Conflict to refused("A list of this name exists.")
            model.saveSettings()
            advanceUntilIdle()
            val settings = model.lists().settings!!
            assertFalse(settings.busy)
            assertEquals("A list of this name exists.", settings.error.english())
            assertEquals("Party", settings.name)

            backend.answers["PUT /things/lists/1"] = HttpStatusCode.NotFound to ""
            model.saveSettings()
            advanceUntilIdle()
            assertNull(model.lists().settings)
            assertEquals("The changes could not be loaded.", model.lists().error.english())
        }

    @Test
    fun `the list open deleted from its settings leads to the home, also on the next start`() =
        runTest {
            val store = store(openList = 1)
            val model = signedIn(store)
            advanceUntilIdle()
            assertEquals(1, model.lists().open)
            model.openSettings(1)
            backend.answers["DELETE /things/lists/1"] = HttpStatusCode.NoContent to ""
            backend.answers["GET /things/lists"] = HttpStatusCode.OK to page(list(2, "Party", shared = true, mine = false))
            model.deleteList()
            assertTrue(model.lists().settings!!.busy)
            advanceUntilIdle()
            assertNull(model.lists().settings)
            assertNull(model.lists().open)
            assertNull(store.load().openList)
            assertEquals(listOf(2), model.lists().lists.map { it.id })
        }

    @Test
    fun `a list deleted from the home goes, a failure shows on the home`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()
            backend.answers["DELETE /things/lists/1"] = HttpStatusCode.InternalServerError to ""
            model.deleteList(1)
            advanceUntilIdle()
            assertEquals("The user could not be loaded.", model.lists().error.english())
            assertEquals(listOf(1, 2), model.lists().lists.map { it.id })

            backend.answers["DELETE /things/lists/1"] = HttpStatusCode.NoContent to ""
            backend.answers["GET /things/lists"] = HttpStatusCode.OK to page(list(2, "Party", shared = true, mine = false))
            model.deleteList(1)
            advanceUntilIdle()
            assertEquals(listOf(2), model.lists().lists.map { it.id })

            // the last own list goes too (LIST-1), the home is empty then
            backend.answers["GET /things/lists"] = HttpStatusCode.OK to page(list(3, "Last"))
            backend.answers["DELETE /things/lists/3"] = HttpStatusCode.NoContent to ""
            model.refresh()
            advanceUntilIdle()
            backend.answers["GET /things/lists"] = HttpStatusCode.OK to page()
            model.deleteList(3)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /things/lists/3"))
            assertTrue(model.lists().lists.isEmpty())
        }

    @Test
    fun `a list of the home opens, an unknown one does not`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()
            model.open(9)
            assertNull(model.lists().open)
            model.open(1)
            advanceUntilIdle()
            assertEquals(1, model.lists().open)
        }

    private fun item(
        id: Int,
        listId: Int,
        title: String,
    ) = json(TodoItemResponse(id, listId, title, "2026-11-12T07:00:00.000Z", TodoItemResponse.Priority.NORMAL))

    @Test
    fun `the list open last opens on the start, one that went leads to the home, also on the next start`() =
        runTest {
            backend.answers["GET /things/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Door"))
            val gone = store(openList = 3)
            signedIn(gone).also { advanceUntilIdle() }.let { model ->
                assertNull(model.lists().open)
                assertFalse(model.lists().loading)
                assertNull(gone.load().openList)
            }

            val store = store(openList = 1)
            val model = signedIn(store)
            advanceUntilIdle()
            assertEquals(1, model.lists().open)
            assertEquals(listOf("Door"), model.lists().entries)
            backend.answers["GET /things/lists"] = HttpStatusCode.OK to page(list(2, "Party", shared = true, mine = false))
            model.refresh()
            assertTrue(model.lists().loading)
            advanceUntilIdle()
            assertNull(model.lists().open)
            assertEquals(emptyList<String>(), model.lists().entries)
            assertNull(store.load().openList)

            // a failed load shows why
            backend.answers["GET /things/lists"] = HttpStatusCode.InternalServerError to ""
            model.refresh()
            advanceUntilIdle()
            assertFalse(model.lists().loading)
            assertEquals("The password could not be checked.", model.lists().error.english())
        }

    @Test
    fun `back leads home, also on the next start, and the list the user opens is the one of the next start`() =
        runTest {
            val store = store(openList = 1)
            val model = signedIn(store)
            advanceUntilIdle()
            model.back()
            advanceUntilIdle()
            assertNull(model.lists().open)
            assertNull(store.load().openList)
            model.open(2)
            advanceUntilIdle()
            assertEquals(2, store.load().openList)
        }

    @Test
    fun `an entry added clears its field and loads, a refused one keeps it, one added while another list opens changes nothing there`() =
        runTest {
            backend.answers["POST /things/items"] = HttpStatusCode.Created to item(11, 1, "Fence")
            val model = signedIn(store(openList = 1))
            advanceUntilIdle()
            model.type("  ")
            model.add()
            assertFalse(model.lists().adding)
            model.type("Fence")
            backend.answers["GET /things/lists/1/items"] = HttpStatusCode.OK to page(item(11, 1, "Fence"))
            model.add()
            assertTrue(model.lists().adding)
            // one at a time
            model.add()
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("POST /things/items") })
            assertEquals("", model.lists().typed)
            assertFalse(model.lists().adding)
            assertEquals(listOf("Fence"), model.lists().entries)

            backend.answers["POST /things/items"] = HttpStatusCode.BadRequest to refused("Refused.")
            model.type("Gate")
            model.add()
            advanceUntilIdle()
            assertEquals("Gate", model.lists().typed)
            assertEquals("Refused.", model.lists().error.english())

            model.add()
            model.back()
            model.open(2)
            model.type("Cake")
            advanceUntilIdle()
            assertEquals(2, model.lists().open)
            assertEquals("Cake", model.lists().typed)
            assertNull(model.lists().error)
            assertFalse(model.lists().adding)
        }

    @Test
    fun `while the app is seen a change of the list open loads it again, of another list not, the home on any`() =
        runTest {
            backend.answers["GET /things/changes"] = HttpStatusCode.OK to ": listening\n\n${changed(2)}${changed(1)}"
            val model = signedIn(store(openList = 1))
            advanceUntilIdle()
            val loads = { backend.requests.count { it == "GET /things/lists/1/items" } }
            assertEquals(1, loads())
            assertTrue(backend.requests.none { it == "GET /things/changes" })

            model.foreground()
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS / 2)
            runCurrent()
            assertEquals(2, loads())
            assertEquals("t", backend.tokens["/things/changes"])

            // the stream ended; the next one loads for what it may have missed and for its change, the
            // second load replaces the first
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS)
            runCurrent()
            assertEquals(3, loads())

            model.background()
            advanceUntilIdle()
            assertEquals(2, backend.requests.count { it == "GET /things/changes" })

            model.back()
            advanceUntilIdle()
            val homes = backend.requests.count { it == "GET /things/lists" }
            backend.answers["GET /things/changes"] = HttpStatusCode.OK to changed(7)
            model.foreground()
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS / 2)
            runCurrent()
            model.background()
            // once for what the stream missed while the app was not seen, once for the change of list 7
            assertEquals(homes + 2, backend.requests.count { it == "GET /things/lists" })
        }

    @Test
    fun `a stream the backend refuses with 401 ends the session`() =
        runTest {
            backend.answers["GET /things/changes"] = HttpStatusCode.Unauthorized to ""
            val model = signedIn()
            advanceUntilIdle()
            model.foreground()
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS / 2)
            runCurrent()
            assertEquals("Your session ended, please sign in again.", (model.screen.value as Screen.SignIn).error.english())
            model.background()
        }
}
