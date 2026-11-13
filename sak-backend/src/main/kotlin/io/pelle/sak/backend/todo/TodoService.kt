package io.pelle.sak.backend.todo

import io.pelle.sak.api.Catalog
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.lists.ListChanges
import io.pelle.sak.backend.lists.ListResult
import io.pelle.sak.backend.lists.ListTexts
import io.pelle.sak.backend.lists.listValues
import io.pelle.sak.backend.lists.result
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.todo.TodoItem
import io.pelle.sak.model.lists.SakList
import io.pelle.sak.model.lists.ListDelete
import io.pelle.sak.model.todo.TodoPriority
import io.pelle.sak.model.todo.TodoRepository
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate

// what adding a task came to
sealed interface TodoNewItemResult {
    data class Saved(
        val item: TodoItem,
    ) : TodoNewItemResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : TodoNewItemResult

    // the list the new task names is none the user sees (API-6)
    data object UnknownList : TodoNewItemResult
}

// what changing a task came to
sealed interface TodoItemResult {
    data class Saved(
        val item: TodoItem,
    ) : TodoItemResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : TodoItemResult

    // the user sees no such task
    data object NotFound : TodoItemResult
}

// the todo lists and their tasks, the only way to them; it checks the values, TodoRepository checks
// who sees and changes what (MODEL-6)
class TodoService(
    private val repository: TodoRepository,
    private val changes: ListChanges = ListChanges(),
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(TodoRepository(database, clock))

    // the ids of the lists the user sees that change from now on, a list deleted included (LIST-4)
    fun changes(user: String): Flow<Int> = changes.of(user, repository::seesList)

    fun lists(user: String): List<SakList> = repository.lists(user)

    // a list the user sees, null when there is none
    fun list(
        user: String,
        id: Int,
    ): SakList? = repository.list(user, id)

    // an task of a list the user sees, null when there is none
    fun item(
        user: String,
        id: Int,
    ) = repository.item(user, id)

    // the icon is one emoji, empty or none for no icon
    fun createList(
        user: String,
        name: String,
        shared: Boolean,
        icon: String? = null,
    ): ListResult {
        val (values, errors) = listValues(name, icon, TodoTexts.LISTS)
        if (errors.isNotEmpty()) return ListResult.Invalid(errors)
        return repository.insertList(user, values.name, shared, values.icon)?.let { ListResult.Saved(changed(it)) } ?: ListResult.Exists
    }

    fun updateList(
        user: String,
        id: Int,
        name: String,
        shared: Boolean,
        icon: String? = null,
    ): ListResult {
        val (values, errors) = listValues(name, icon, TodoTexts.LISTS)
        if (errors.isNotEmpty()) return ListResult.Invalid(errors)
        return repository.updateList(user, id, values.name, shared, values.icon).also(changes::changed).result()
    }

    // with its tasks, the last list of the user's own too (LIST-1)
    fun deleteList(
        user: String,
        id: Int,
    ): ListDelete = repository.deleteList(user, id).also { if (it == ListDelete.Deleted) changes.changed(id) }

    // the tasks of a list the user sees, done ones in their place, by priority (TODO-3, TODO-5); null for another list
    fun items(
        user: String,
        listId: Int,
    ): List<TodoItem>? = repository.items(user, listId)

    // on a list the user sees, a task names its list (LIST-1); the due date is a day as yyyy-mm-dd,
    // none or empty for no day (TODO-4), also one that passed; the priority high, normal or low, none
    // for normal (TODO-5)
    fun addItem(
        user: String,
        listId: Int,
        title: String,
        dueDate: String? = null,
        priority: String? = null,
    ): TodoNewItemResult {
        val normalized = title.trim()
        val day = day(dueDate)
        val errors =
            listOfNotNull(
                if (normalized.isEmpty() || normalized.length > MAX_TITLE_LENGTH || normalized.any { it.isISOControl() }) {
                    ErrorMessage(TodoTexts.INVALID_TITLE, "max" to MAX_TITLE_LENGTH)
                } else {
                    null
                },
                day.exceptionOrNull()?.let { ErrorMessage(TodoTexts.INVALID_DUE_DATE) },
                if (priority != null && TodoPriority.of(priority) == null) ErrorMessage(TodoTexts.INVALID_PRIORITY) else null,
            )
        if (errors.isNotEmpty()) return TodoNewItemResult.Invalid(errors)
        val item = repository.insertItem(user, listId, normalized, day.getOrNull(), priority?.let(TodoPriority::of) ?: TodoPriority.NORMAL)
        return item?.let { TodoNewItemResult.Saved(changed(it)) } ?: TodoNewItemResult.UnknownList
    }

    // done or open again (TODO-3), by any user who sees its list (LIST-2)
    fun setDone(
        user: String,
        id: Int,
        done: Boolean,
    ): TodoItemResult = saved(repository.setDone(user, id, done))

    // the priority of a task, high, normal or low (TODO-5), by any user who sees its list (LIST-2)
    fun setPriority(
        user: String,
        id: Int,
        priority: String,
    ): TodoItemResult {
        val value = TodoPriority.of(priority) ?: return TodoItemResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_PRIORITY)))
        return saved(repository.setPriority(user, id, value))
    }

    // the day a task is to be done by, yyyy-mm-dd, none or empty to take it away (TODO-4), by any user
    // who sees its list (LIST-2)
    fun setDueDate(
        user: String,
        id: Int,
        dueDate: String?,
    ): TodoItemResult {
        val day = day(dueDate).getOrElse { return TodoItemResult.Invalid(listOf(ErrorMessage(TodoTexts.INVALID_DUE_DATE))) }
        return saved(repository.setDueDate(user, id, day))
    }

    // the done tasks of a list the user sees are deleted, of the ids given only, the ones the user saw
    // done (TODO-6); null for another list, else how many went
    fun purge(
        user: String,
        listId: Int,
        ids: Collection<Int>,
    ): Int? = repository.purge(user, listId, ids)?.also { if (it > 0) changes.changed(listId) }

    // yyyy-mm-dd, none or empty for no day; a failure for another text
    private fun day(text: String?): Result<LocalDate?> = text?.trim()?.ifEmpty { null }?.let { runCatching { LocalDate.parse(it) } } ?: Result.success(null)

    private fun saved(item: TodoItem?) = item?.let { TodoItemResult.Saved(changed(it)) } ?: TodoItemResult.NotFound

    private fun changed(list: SakList) = list.also { changes.changed(it.id) }

    private fun changed(item: TodoItem) = item.also { changes.changed(it.listId) }

    companion object {
        const val MAX_TITLE_LENGTH = 256
    }
}

// the texts of the todo lists (todo.backend and todo.common in i18n/texts.yml)
object TodoTexts : Catalog("todo") {
    val INVALID_ICON = text("invalid_icon")
    val INVALID_LIST_NAME = text("invalid_list_name")
    val INVALID_TITLE = text("invalid_title")
    val INVALID_DUE_DATE = text("invalid_due_date")
    val INVALID_PRIORITY = text("invalid_priority")
    val LIST_EXISTS = text("list_exists")
    val UNKNOWN_LIST = text("unknown_list")
    val ONLY_OWNER_SHARES = text("only_owner_shares")
    val LISTS = ListTexts(INVALID_ICON, INVALID_LIST_NAME, LIST_EXISTS, ONLY_OWNER_SHARES)
}
