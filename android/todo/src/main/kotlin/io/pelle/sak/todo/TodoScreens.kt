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

// the priority of a task as the backend sends it, the high ones first (TODO-5)
typealias Priority = TodoItemResponse.Priority

// the order of the tasks of a list as the backend gives them: by priority, then in the order they were
// added (TODO-5)
val TASK_ORDER: Comparator<TodoItemResponse> = compareBy<TodoItemResponse> { it.priority.ordinal }.thenBy { it.id }

// the day a task is to be done by, null for none (TODO-4); one that is no day is none
val TodoItemResponse.due: LocalDate? get() = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

// a list on the home, with how many tasks are open
fun TodoListResponse.summary() = ListSummary(id, name, icon, shared, mine, items)

// the settings of a list the user creates (no id) or changes (ListForm)
data class ListSettings(
    override val id: Int? = null,
    override val name: String = "",
    override val shared: Boolean = false,
    override val icon: String? = null,
    override val mine: Boolean = true,
    override val busy: Boolean = false,
    override val error: UiText? = null,
) : ListForm

// the screen of the todo lists once the user is signed in (the screens before are core's)
data class ListsScreen(
    // who is signed in: their name, else the e-mail address, or admin
    override val user: String,
    override val lists: List<TodoListResponse> = emptyList(),
    // the id of the list open, null on the home, which shows all lists
    override val open: Int? = null,
    // the tasks of the list open by priority, then in the order they were added (TASK_ORDER), a done
    // one struck through in its place (TODO-3); the done ones of a purge left out while it can be
    // undone (TODO-6)
    val items: List<TodoItemResponse> = emptyList(),
    val loading: Boolean = true,
    val error: UiText? = null,
    // the title typed into the field at the top, the day and the priority chosen (TODO-4, TODO-5),
    // kept while they are sent
    val itemTitle: String = "",
    val itemDue: LocalDate? = null,
    val itemPriority: Priority = Priority.NORMAL,
    val adding: Boolean = false,
    val undo: Undo? = null,
    // the screen of the settings of a list, a new one or one the user sees, over the home or the list
    override val settings: ListSettings? = null,
    // the newer version of the app the deployment runs, offered on the home
    override val update: AppUpdate? = null,
    // the answer of the check for updates of the menu while there is no update to offer
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
