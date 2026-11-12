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
import io.pelle.sak.api.model.TodoListResponse
import io.pelle.sak.core.R
import io.pelle.sak.core.UiText
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.core.lists.ListForm
import io.pelle.sak.core.lists.ListSummary
import io.pelle.sak.core.lists.ListTexts
import io.pelle.sak.core.lists.ListsApi
import io.pelle.sak.core.lists.ListsAppScreen
import io.pelle.sak.core.lists.ListsModel
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.session.openList
import io.pelle.sak.core.session.withOpenList
import io.pelle.sak.core.update.AppUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// core has no texts of lists: the test takes four of its others, each a failure of its own
private val TEXTS = ListTexts(R.string.error_link_not_requested, R.string.error_link_not_used, R.string.error_user_not_read, R.string.error_changes_not_read)

// the lists of an app below /things, with the bodies of the todo lists
private class ThingListsApi(
    client: HttpClient,
    url: String,
    token: String?,
) : SakApi(client, url, token, "en", LoginRequest.Client.TODO_ANDROID),
    ListsApi<TodoListResponse> {
    override suspend fun lists(): List<TodoListResponse> = all("$url/things/lists", TEXTS.notSaved)

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

    override fun changes(opened: suspend () -> Unit): Flow<Int> = emptyFlow()
}

private data class ThingSettings(
    override val id: Int? = null,
    override val name: String = "",
    override val shared: Boolean = false,
    override val icon: String? = null,
    override val mine: Boolean = true,
    override val busy: Boolean = false,
    override val error: UiText? = null,
) : ListForm

private data class ThingListsScreen(
    override val user: String,
    override val lists: List<TodoListResponse> = emptyList(),
    override val open: Int? = null,
    override val settings: ThingSettings? = null,
    val error: UiText? = null,
    override val update: AppUpdate? = null,
    override val updateNotice: UiText? = null,
) : ListsAppScreen<TodoListResponse, ThingSettings> {
    override fun withUser(user: String) = copy(user = user)

    override fun withUpdate(
        update: AppUpdate?,
        updateNotice: UiText?,
    ) = copy(update = update, updateNotice = updateNotice)

    override fun withLists(lists: List<TodoListResponse>) = copy(lists = lists)

    override fun withOpen(open: Int?) = copy(open = open)

    override fun withSettings(settings: ThingSettings?) = copy(settings = settings)

    override fun withError(error: UiText?) = copy(error = error)
}

// the logic of the lists of core with an app as small as it gets: it shows a list or the home and
// loads the lists; what the hooks were called with is kept
private class ThingListsModel(
    store: SessionStore,
    backend: FakeBackend,
    scope: TestScope,
    clock: Clock,
) : ListsModel<ThingListsApi, TodoListResponse, ThingSettings>(
        store,
        { url, token -> ThingListsApi(backend.client, url, token) },
        scope,
        allowUrl = false,
        clock = clock,
        appVersion = "latest",
        updater = null,
        texts = TEXTS,
        summaryOf = { ListSummary(it.id, it.name, it.icon, it.shared, it.mine, it.items) },
    ) {
    val opened = mutableListOf<Int>()
    var saving = 0

    // the settings as the hook before saving found them, busy not yet shown
    var savingBusy: Boolean? = null

    override fun showApp(stored: Stored) {
        state.value = ThingListsScreen(stored.email.orEmpty(), open = stored.openList)
        load()
    }

    override fun listsScreen() = state.value as? ThingListsScreen

    override fun settingsOf(list: ListSummary?) = list?.let { ThingSettings(it.id, it.name, it.shared, it.icon, mine = it.mine) } ?: ThingSettings()

    override fun edited(
        settings: ThingSettings,
        name: String,
        shared: Boolean,
        icon: String?,
    ) = settings.copy(name = name, shared = shared, icon = icon)

    override fun sending(
        settings: ThingSettings,
        busy: Boolean,
        error: UiText?,
    ) = settings.copy(busy = busy, error = error)

    override fun show(open: Int?) {
        listsScreen()?.let { state.value = it.copy(open = open) }
        scope.launch { store.update { it.withOpenList(open) } }
        load()
    }

    override fun load(after: Job?) {
        scope.launch {
            after?.join()
            val lists = request(TEXTS.notSaved, { error -> listsScreen()?.let { state.value = it.copy(error = error) } }) { it.lists() } ?: return@launch
            listsScreen()?.let { screen -> state.value = screen.copy(lists = lists, open = screen.open?.takeIf { id -> lists.any { it.id == id } }) }
        }
    }

    override fun settingsOpened(id: Int) {
        opened += id
    }

    override fun savingSettings() {
        saving++
        savingBusy = listsScreen()?.settings?.busy
    }

    override fun back() {
        listsScreen()?.let { state.value = it.copy(settings = null) }
    }

    override fun refresh() = load()
}

class ListsModelTest {
    private val now = Instant.parse("2026-11-12T08:00:00Z")

    private val backend =
        FakeBackend().apply {
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
            answers["GET /things/lists"] = HttpStatusCode.OK to page(list(1, "Home"), list(2, "Party", shared = true, mine = false))
        }

    init {
        // the texts of core only; the tests of testing have no app
        System.setProperty("sak.app.package", "io.pelle.sak.testing")
    }

    private fun list(
        id: Int,
        name: String,
        shared: Boolean = false,
        mine: Boolean = true,
    ) = """{"id":$id,"name":"$name","shared":$shared,"mine":$mine,"items":0,"createdAt":"2026-11-12T07:00:00.000Z"}"""

    private fun store(openList: Int? = null) =
        MemorySessionStore(
            Stored(apiUrl = URL, address = "https://sak.example.org", token = "t", email = "admin", expiresAt = now.plusSeconds(60)).withOpenList(openList),
        )

    private fun TestScope.signedIn(store: MemorySessionStore = store()): ThingListsModel {
        backend.dispatcher = StandardTestDispatcher(testScheduler)
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
            assertEquals(ThingSettings(), model.lists().settings)
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
            assertEquals(listOf(2), model.opened)
            assertEquals(ThingSettings(2, "Party", shared = true, mine = false), model.lists().settings)
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
    fun `a refused save stays on the settings with the reason, a list gone closes them`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()
            model.openSettings(1)
            model.editSettings("Party", shared = false, icon = null)
            backend.answers["PUT /things/lists/1"] = HttpStatusCode.Conflict to """{"errors":[{"code":"things.duplicate","message":"A list of this name exists."}]}"""
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
            assertEquals("The changes could not be read.", model.lists().error.english())
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
            assertEquals("The user could not be read.", model.lists().error.english())
            assertEquals(listOf(1, 2), model.lists().lists.map { it.id })

            backend.answers["DELETE /things/lists/1"] = HttpStatusCode.NoContent to ""
            backend.answers["GET /things/lists"] = HttpStatusCode.OK to page(list(2, "Party", shared = true, mine = false))
            model.deleteList(1)
            advanceUntilIdle()
            assertEquals(listOf(2), model.lists().lists.map { it.id })
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
}
