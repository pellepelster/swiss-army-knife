package io.pelle.sak.todo

import io.pelle.sak.api.model.TodoItemResponse
import io.pelle.sak.api.model.TodoListResponse
import io.pelle.sak.core.UiText
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.lists.ChangeStream
import io.pelle.sak.core.lists.ListSummary
import io.pelle.sak.core.lists.ListTexts
import io.pelle.sak.core.lists.ListsModel
import io.pelle.sak.core.Undo
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.session.openList
import io.pelle.sak.core.session.withOpenList
import io.pelle.sak.core.uiText
import io.pelle.sak.core.update.Updater
import io.pelle.sak.todo.api.TodoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

// how long a purge can be undone, the same as in the web app (UNDO_DELETE_MS, UX-4.2)
const val UNDO_MS = 2_000L

// the logic of the app, without android, on top of ListsModel: the tasks of the list open, a task added
// with a due date or none (TODO-4) and a priority (TODO-5), a task done struck through in its place
// (TODO-3), the done tasks purged once the undo ran out (TODO-6). apiOf makes the api of a url with a
// session token or none
class TodoModel(
    store: SessionStore,
    apiOf: (url: String, token: String?) -> TodoApi,
    scope: CoroutineScope,
    // the debug build takes a whole url of the api, e.g. of the backend of local:run
    allowUrl: Boolean,
    clock: Clock = Clock.systemUTC(),
    // the version of the app, 1.4.2 of a release, latest of every other build
    appVersion: String = "latest",
    updater: Updater? = Updater { error("no updater") },
) : ListsModel<TodoApi, TodoListResponse, ListSettings>(
        store,
        apiOf,
        scope,
        allowUrl,
        clock,
        appVersion,
        updater,
        ListTexts(R.string.todo_error_list_not_created, R.string.todo_form_not_saved, R.string.todo_form_not_deleted, R.string.todo_list_gone),
        TodoListResponse::summary,
    ) {
    private var loading: Job? = null

    // the purge that can still be undone (TODO-6): ids are the done tasks hidden, the ones purged;
    // items are those tasks as the list had them last, for the undo; timer sends it to the backend
    private data class Purge(
        val listId: Int,
        val ids: List<Int>,
        val items: List<TodoItemResponse>,
        val timer: Job,
    )

    private var pending: Purge? = null
    private var serial = 0

    // the request of the purge sent last, which a load waits for, so it does not show the done tasks again
    private var purging: Job? = null

    // the done or open again sent last, by task: a tap waits for the one before, so a quick second tap
    // does not overtake the first; a load waits for them all (TODO-3)
    private val marking = mutableMapOf<Int, Job>()

    // counts up with every show: an answer for a list that is no longer shown changes nothing
    private var shown = 0

    // UNDO_MS, longer when the accessibility settings ask for more time; set by the ui (UndoTime)
    var undoMs = UNDO_MS

    // the changes of the lists (LIST-4): a change of the list open, or of any list on the home, loads
    // again, and so does a reconnect, the stream may have missed changes
    private val changes =
        ChangeStream(
            scope,
            clock,
            session = {
                state.first { it is ListsScreen }
                store.load().takeIf { it.apiUrl != null }
            },
            changes = { stored, opened -> apiOf(stored.apiUrl.orEmpty(), stored.token).changes(opened) },
            ended = { e, token -> sessionEnded(e, token) },
            onReconnect = { load() },
            onChange = { listId ->
                val lists = state.value as? ListsScreen
                if (lists != null && (lists.open == null || lists.open == listId)) load()
            },
        )

    // the app comes to the foreground (LIST-4)
    fun startListening() = changes.start()

    // the app goes to the background: a purge that waits is sent now, it would be lost with the app.
    // Not on a rotation (SakActivity), the undo stays
    fun stopped() {
        commitPending()
        changes.stop()
    }

    // toggles the task as the list shows it now, struck through at once; the list loads after the
    // answer, a failure shows why and loads the list (TODO-3)
    fun done(item: TodoItemResponse) {
        val lists = state.value as? ListsScreen ?: return
        val shown = lists.items.firstOrNull { it.id == item.id } ?: return
        val done = shown.doneAt == null
        state.value =
            lists.copy(items = lists.items.map { if (it.id == item.id) it.copy(doneAt = if (done) clock.instant().toString() else null) else it }, error = null)
        val before = marking[item.id]
        val sending =
            scope.launch(start = CoroutineStart.LAZY) {
                before?.join()
                val answer = request(R.string.todo_list_not_done, ::failed) { it.setDone(item.id, done) }
                // the last tap of the task loads the list as the backend has it
                if (marking[item.id] == coroutineContext.job) {
                    marking.remove(item.id)
                    if (answer != null) load()
                }
            }
        marking[item.id] = sending
        sending.start()
    }

    // the list loads then, with the task in its new place; a failure shows why and loads (TODO-5)
    fun setPriority(
        item: TodoItemResponse,
        priority: Priority,
    ) {
        if (item.priority == priority) return
        lists { it.copy(error = null) }
        scope.launch {
            request(R.string.todo_list_priority_not_set, ::failed) { it.setPriority(item.id, priority) } ?: return@launch
            load()
        }
    }

    // null takes the due date away; the list loads then, a failure shows why and loads (TODO-4)
    fun setDueDate(
        item: TodoItemResponse,
        day: LocalDate?,
    ) {
        if (item.dueDate == day?.toString()) return
        lists { it.copy(error = null) }
        scope.launch {
            request(R.string.todo_list_due_date_not_saved, ::failed) { it.setDueDate(item.id, day) } ?: return@launch
            load()
        }
    }

    // the done tasks shown go at once, the backend deletes them after undoMs unless undone; a task done
    // meanwhile stays, struck through. A purge before goes to the backend at once (TODO-6, UX-4.2)
    fun purge() {
        val lists = state.value as? ListsScreen ?: return
        val listId = lists.open ?: return
        val done = lists.items.filter { it.doneAt != null }
        if (done.isEmpty()) return
        commitPending()
        val timer =
            scope.launch {
                delay(undoMs)
                commitPending()
            }
        val ids = done.map { it.id }
        pending = Purge(listId, ids, done, timer)
        lists { it.copy(items = it.items.filter { item -> item.id !in ids }, undo = Undo(uiText(R.string.todo_list_purged), ++serial), error = null) }
    }

    // the done tasks come back in their places, also without a connection: nothing went to the backend
    fun undo() {
        val purge = pending ?: return
        purge.timer.cancel()
        pending = null
        lists { lists ->
            if (lists.open != purge.listId) return@lists lists.copy(undo = null)
            val back = purge.items.filter { item -> lists.items.none { it.id == item.id } }
            lists.copy(items = (lists.items + back).sortedWith(TASK_ORDER), undo = null)
        }
    }

    // sends the pending purge now, after the done of its tasks still sent, also when the app closes
    // meanwhile; a failure loads the list. Answers the job of the request, null without a purge
    private fun commitPending(): Job? {
        val purge = pending ?: return null
        pending = null
        purge.timer.cancel()
        lists { it.copy(undo = null) }
        val marked = purge.ids.mapNotNull { marking[it] }
        return launchLasting {
            marked.joinAll()
            request(R.string.todo_list_not_purged, ::failed) { api ->
                // the list went meanwhile: nothing to purge, no error
                try {
                    api.purge(purge.listId, purge.ids)
                } catch (e: ApiException) {
                    if (e.status != 404) throw e
                }
            }
        }.also { purging = it }
    }

    // forgets the pending purge without sending it, e.g. the session ended
    private fun dropPending() {
        pending?.timer?.cancel()
        pending = null
    }

    // from the settings to where they were opened, from a list to the home (also on the next start)
    override fun back() {
        val lists = state.value as? ListsScreen ?: return
        when {
            lists.settings != null -> state.value = lists.copy(settings = null)
            lists.open != null -> show(null)
        }
    }

    // sends the pending purge and clears the new task; answers still due for the list before change
    // nothing here
    override fun show(open: Int?) {
        val purged = commitPending()
        val lists = state.value as? ListsScreen ?: return
        shown++
        state.value =
            lists.copy(
                open = open,
                items = emptyList(),
                loading = true,
                error = null,
                itemTitle = "",
                itemDue = null,
                itemPriority = Priority.NORMAL,
                adding = false,
                undo = null,
            )
        scope.launch { store.update { it.withOpenList(open) } }
        load(after = purged)
    }

    override fun refresh() {
        val lists = state.value as? ListsScreen ?: return
        state.value = lists.copy(loading = true, error = null)
        load()
    }

    fun typed(title: String) = lists { it.copy(itemTitle = title) }

    // the due date of the next task, null for none (TODO-4)
    fun chooseDue(day: LocalDate?) = lists { it.copy(itemDue = day) }

    // the priority of the next task (TODO-5)
    fun choosePriority(priority: Priority) = lists { it.copy(itemPriority = priority) }

    // adds the task typed with the due date and priority chosen, then clears them and loads; a failure
    // keeps them to send again. The answer once another list shows changes nothing
    fun addItem() {
        val lists = state.value as? ListsScreen ?: return
        val title = lists.itemTitle.trim()
        val list = lists.open
        if (title.isEmpty() || list == null || lists.adding) return
        val due = lists.itemDue
        val priority = lists.itemPriority
        state.value = lists.copy(adding = true, error = null)
        val since = shown
        scope.launch {
            request(R.string.todo_list_not_added, { error -> if (since == shown) lists { it.copy(adding = false, error = error) } }) {
                it.addItem(list, title, due, priority)
            } ?: return@launch
            if (since != shown) return@launch
            lists { it.copy(itemTitle = "", itemDue = null, itemPriority = Priority.NORMAL, adding = false) }
            load()
        }
    }

    // the user signs out: a purge that waits is sent first
    override suspend fun signingOut() {
        commitPending()?.join()
        loading?.cancel()
    }

    override fun sessionEnding() = dropPending()

    override fun sessionChanged() = changes.restart()

    override fun showApp(stored: Stored) {
        state.value = ListsScreen(stored.email.orEmpty(), open = stored.openList)
        load()
    }

    // the lists, then the tasks of the one open, once after, the purge sent last and the done of tasks
    // still sent. The tasks of a pending purge stay hidden and are kept as they are now, for its undo.
    // A list that is gone leads to the home, a session that ended to the sign-in
    override fun load(after: Job?) {
        loading?.cancel()
        val waits = listOfNotNull(after, purging) + marking.values
        loading =
            scope.launch {
                waits.joinAll()
                request(R.string.todo_lists_not_loaded, { error -> lists { it.copy(loading = false, error = error) } }) { api ->
                    val lists = api.lists()
                    val current = state.value as? ListsScreen ?: return@request
                    val wanted = current.open
                    val open = wanted?.takeIf { id -> lists.any { it.id == id } }
                    if (wanted != null && open == null) store.update { it.withOpenList(null) }
                    val items = open?.let { api.items(it) }.orEmpty()
                    // a task of the purge open again meanwhile shows, the backend does not purge it
                    val hidden = pending?.takeIf { it.listId == open }?.let { purge -> items.filter { it.id in purge.ids && it.doneAt != null } }.orEmpty()
                    pending?.takeIf { it.listId == open }?.let { pending = it.copy(items = hidden) }
                    lists { it.copy(lists = lists, open = open, items = items - hidden.toSet(), loading = false) }
                }
            }
    }

    override fun listsScreen() = state.value as? ListsScreen

    override fun settingsOf(list: ListSummary?) = list?.let { ListSettings(it.id, it.name, it.shared, it.icon, mine = it.mine) } ?: ListSettings()

    override fun edited(
        settings: ListSettings,
        name: String,
        shared: Boolean,
        icon: String?,
    ) = settings.copy(name = name, shared = shared, icon = icon)

    override fun sending(
        settings: ListSettings,
        busy: Boolean,
        error: UiText?,
    ) = settings.copy(busy = busy, error = error)

    // nothing when another screen is shown
    private inline fun lists(change: (ListsScreen) -> ListsScreen) {
        (state.value as? ListsScreen)?.let { state.value = change(it) }
    }
}
