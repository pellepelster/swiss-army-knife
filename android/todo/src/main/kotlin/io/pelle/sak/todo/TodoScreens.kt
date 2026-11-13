package io.pelle.sak.todo

import io.pelle.sak.api.model.TodoItemResponse
import io.pelle.sak.api.model.TodoListResponse
import io.pelle.sak.core.UiText
import io.pelle.sak.core.lists.ListForm
import io.pelle.sak.core.lists.ListsAppScreen
import io.pelle.sak.core.lists.ListSummary
import io.pelle.sak.core.Undo
import io.pelle.sak.core.update.AppUpdate
import java.time.LocalDate

// what the screens of the app show once the user is signed in, as TodoModel holds it

// the high ones first by ordinal (TODO-5)
typealias Priority = TodoItemResponse.Priority

// the order of the backend: by priority, then in the order added (TODO-5)
val TASK_ORDER: Comparator<TodoItemResponse> = compareBy<TodoItemResponse> { it.priority.ordinal }.thenBy { it.id }

// null for none or for a value that is no date (TODO-4)
val TodoItemResponse.due: LocalDate? get() = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

// a list on the home; items is the number of open tasks
fun TodoListResponse.summary() = ListSummary(id, name, icon, shared, mine, items)

// the settings of a list the user creates (no id) or changes
data class ListSettings(
    override val id: Int? = null,
    override val name: String = "",
    override val shared: Boolean = false,
    override val icon: String? = null,
    override val mine: Boolean = true,
    override val busy: Boolean = false,
    override val error: UiText? = null,
) : ListForm

// the screen once the user is signed in (the screens before are core's)
data class ListsScreen(
    // who is signed in: their name, else the e-mail address, or admin
    override val user: String,
    override val lists: List<TodoListResponse> = emptyList(),
    // the list open, null on the home
    override val open: Int? = null,
    // the tasks of the list open in TASK_ORDER, a done one in its place (TODO-3), without the ones of a
    // purge that can still be undone (TODO-6)
    val items: List<TodoItemResponse> = emptyList(),
    val loading: Boolean = true,
    val error: UiText? = null,
    // the next task (TODO-4, TODO-5), kept while it is sent
    val itemTitle: String = "",
    val itemDue: LocalDate? = null,
    val itemPriority: Priority = Priority.NORMAL,
    val adding: Boolean = false,
    val undo: Undo? = null,
    // the settings of a list, a new one or one the user sees, over the home or the list
    override val settings: ListSettings? = null,
    // the newer version of the app the deployment runs, offered on the home
    override val update: AppUpdate? = null,
    // the answer of "Check for updates" of the menu when there is no update
    override val updateNotice: UiText? = null,
) : ListsAppScreen<TodoListResponse, ListSettings> {
    override fun withUser(user: String) = copy(user = user)

    override fun withLists(lists: List<TodoListResponse>) = copy(lists = lists)

    override fun withOpen(open: Int?) = copy(open = open)

    override fun withSettings(settings: ListSettings?) = copy(settings = settings)

    override fun withError(error: UiText?) = copy(error = error)

    override fun withUpdate(
        update: AppUpdate?,
        updateNotice: UiText?,
    ) = copy(update = update, updateNotice = updateNotice)
}
