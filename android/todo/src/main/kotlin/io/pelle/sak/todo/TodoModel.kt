package io.pelle.sak.todo

import io.pelle.sak.core.SakSetup
import io.pelle.sak.api.model.TodoItemResponse
import io.pelle.sak.api.model.TodoListResponse
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.lists.ListFields
import io.pelle.sak.core.lists.ListTexts
import io.pelle.sak.core.lists.ListsModel
import io.pelle.sak.core.lists.Rename
import io.pelle.sak.core.lists.Renaming
import io.pelle.sak.core.Undo
import io.pelle.sak.core.uiText
import io.pelle.sak.todo.api.TodoApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.time.LocalDate

// how long a purge can be undone, the same as in the web app (UNDO_DELETE_MS, UX-4.2)
const val UNDO_MS = 2_000L

// the texts of the lists of the app, of its model and of the screens of core
internal val TODO_TEXTS =
    ListTexts(
        appName = R.string.todo_app_name,
        create = R.string.todo_form_create,
        allLists = R.string.todo_all_lists,
        chooseIcon = R.string.todo_form_choose_icon,
        noIcon = R.string.todo_form_no_icon,
        deleteList = R.string.todo_form_delete_list,
        notCreated = R.string.todo_error_list_not_created,
        notSaved = R.string.todo_form_not_saved,
        notDeleted = R.string.todo_form_not_deleted,
        notLoaded = R.string.todo_lists_not_loaded,
        gone = R.string.todo_list_gone,
    )

// the logic of the app, without android, on top of ListsModel: the tasks of the list open, a task added
// with a due date or none (TODO-4) and a priority (TODO-5), a task done struck through in its place
// (TODO-3), the done tasks purged once the undo ran out (TODO-6). apiOf makes the api of a url with a
// session token or none
class TodoModel(
    setup: SakSetup,
    apiOf: (url: String, token: String?) -> TodoApi,
) : ListsModel<TodoApi, TodoListResponse, ListFields, ListsScreen>(
        setup,
        apiOf,
        TODO_TEXTS,
        TodoListResponse::summary,
        { it },
    ) {
    // the purge that can still be undone (TODO-6): ids are the done tasks hidden, the ones purged;
    // items are those tasks as the list had them last, for the undo
    private data class Purge(
        val listId: Int,
        val ids: List<Int>,
        val items: List<TodoItemResponse>,
    )

    // the done or open again sent last, by task: a tap waits for the one before, so a quick second tap
    // does not overtake the first; a load waits for them all (TODO-3)
    private val marking = mutableMapOf<Int, Job>()

    // UNDO_MS, longer when the accessibility settings ask for more time; set by the ui (UndoTime)
    var undoMs = UNDO_MS

    // sends the purge once its undo ran out, after the done of its tasks still sent, also when the app
    // closes meanwhile; a failure loads the list
    private val purges =
        undoWindow<Purge>({ undoMs }) { purge ->
            listsState { it.copy(undo = null) }
            val marked = purge.ids.mapNotNull { marking[it] }
            launchLasting {
                marked.joinAll()
                request(R.string.todo_list_not_purged, ::failed) { api ->
                    // the list went meanwhile: nothing to purge, no error
                    try {
                        api.purge(purge.listId, purge.ids)
                    } catch (e: ApiException) {
                        if (e.status != 404) throw e
                    }
                }
            }
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
        listsState { it.copy(error = null) }
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
        listsState { it.copy(error = null) }
        scope.launch {
            request(R.string.todo_list_due_date_not_saved, ::failed) { it.setDueDate(item.id, day) } ?: return@launch
            load()
        }
    }

    // the Delete of the menu of a task asks for a confirmation first (UX-12, UX-4.1)
    fun askDelete(item: TodoItemResponse) = listsState { it.copy(deleting = item) }

    fun dismissDelete() = listsState { it.copy(deleting = null) }

    // after the user confirmed it (UX-12): the task goes for good, no undo; the list loads after the
    // answer, a failure shows why and loads, also for a task that is gone already
    fun deleteItem(item: TodoItemResponse) {
        if (state.value !is ListsScreen) return
        listsState { it.copy(deleting = null, error = null) }
        scope.launch {
            request(R.string.todo_list_not_deleted, ::failed) { it.deleteItem(item.id) } ?: return@launch
            load()
        }
    }

    fun askRename(item: TodoItemResponse) = listsState { it.copy(renaming = Renaming(item, item.title)) }

    fun editRename(title: String) = listsState { lists -> lists.copy(renaming = lists.renaming?.copy(name = title)) }

    // the field of the title was left: its error shows from now on (UX-6)
    fun leaveRename() = listsState { lists -> lists.copy(renaming = lists.renaming?.copy(left = true)) }

    fun dismissRename() = listsState { it.copy(renaming = null) }

    // an invalid title stays in the dialog with its error (validTitle), an unchanged one is not sent;
    // the list loads after the answer, a failure shows why and loads
    fun renameItem() {
        val lists = state.value as? ListsScreen ?: return
        val renaming = lists.renaming ?: return
        val rename = renaming.submit(renaming.item.title, ::validTitle)
        if (rename is Rename.Invalid) {
            state.value = lists.copy(renaming = rename.renaming)
            return
        }
        state.value = lists.copy(renaming = null)
        if (rename !is Rename.Send) return
        val (item, title) = rename
        listsState { it.copy(error = null) }
        scope.launch {
            request(R.string.todo_list_not_renamed, ::failed) { it.renameItem(item.id, title) } ?: return@launch
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
        val ids = done.map { it.id }
        val serial = purges.start(Purge(listId, ids, done))
        listsState { it.copy(items = it.items.filter { item -> item.id !in ids }, undo = Undo(uiText(R.string.todo_list_purged), serial), error = null) }
    }

    // the done tasks come back in their places, also without a connection: nothing went to the backend
    fun undo() {
        val purge = purges.undo() ?: return
        listsState { lists ->
            if (lists.open != purge.listId) return@listsState lists.copy(undo = null)
            val back = purge.items.filter { item -> lists.items.none { it.id == item.id } }
            lists.copy(items = (lists.items + back).sortedWith(TASK_ORDER), undo = null)
        }
    }

    // closes the top dialog, from the settings to where they were opened, from a list to the home (also
    // on the next start)
    override fun back() {
        val lists = state.value as? ListsScreen ?: return
        when {
            lists.renaming != null -> state.value = lists.copy(renaming = null)
            lists.deleting != null -> state.value = lists.copy(deleting = null)
            lists.settings != null -> state.value = lists.copy(settings = null)
            lists.open != null -> show(null)
        }
    }

    // the new task is cleared, the dialogs go
    override fun opened(
        lists: ListsScreen,
        open: Int?,
    ) = lists.copy(
        items = emptyList(),
        itemTitle = "",
        itemDue = null,
        itemPriority = Priority.NORMAL,
        undo = null,
        renaming = null,
        deleting = null,
    )

    fun typed(title: String) = listsState { it.copy(itemTitle = title) }

    // the due date of the next task, null for none (TODO-4)
    fun chooseDue(day: LocalDate?) = listsState { it.copy(itemDue = day) }

    // the priority of the next task (TODO-5)
    fun choosePriority(priority: Priority) = listsState { it.copy(itemPriority = priority) }

    // adds the task typed with the due date and priority chosen, then clears them and loads; a failure
    // keeps them to send again (addEntry)
    fun addItem() {
        val lists = listsScreen() ?: return
        val title = lists.itemTitle.trim()
        val due = lists.itemDue
        val priority = lists.itemPriority
        addEntry(title, R.string.todo_list_not_added, { it }, { it.copy(itemTitle = "", itemDue = null, itemPriority = Priority.NORMAL) }) { api, list ->
            api.addItem(list, title, due, priority)
        }
    }

    override fun appScreen(
        user: String,
        open: Int?,
    ) = ListsScreen(user, open = open)

    // the tasks of the list open, once after the done of tasks still sent. The tasks of a pending purge
    // stay hidden and are kept as they are now, for its undo
    override fun loadWaits() = marking.values.toList()

    override suspend fun loaded(
        api: TodoApi,
        open: Int?,
    ): (ListsScreen) -> ListsScreen {
        val items = open?.let { api.items(it) }.orEmpty()
        val pending = purges.value?.takeIf { it.listId == open }
        // a task of the purge open again meanwhile shows, the backend does not purge it
        val hidden = pending?.let { purge -> items.filter { it.id in purge.ids && it.doneAt != null } }.orEmpty()
        if (pending != null) purges.update { it.copy(items = hidden) }
        return { current ->
            // the dialogs belong to the list open: when it went, they go; they stay with the task as
            // it is now, also when the list no longer shows it
            val stays = current.open == open
            val renaming =
                current.renaming?.takeIf { stays }?.let { renaming ->
                    items.firstOrNull { it.id == renaming.item.id }?.let { renaming.copy(item = it) } ?: renaming
                }
            val deleting = current.deleting?.takeIf { stays }?.let { deleting -> items.firstOrNull { it.id == deleting.id } ?: deleting }
            current.copy(items = items - hidden.toSet(), renaming = renaming, deleting = deleting)
        }
    }

    override fun listsScreen() = state.value as? ListsScreen

    companion object {
        // TodoService.MAX_TITLE_LENGTH
        const val MAX_TITLE = 256

        fun validTitle(title: String) = title.trim().let { it.isNotEmpty() && it.length <= MAX_TITLE }
    }
}
