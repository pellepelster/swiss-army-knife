package io.pelle.sak.todo.api

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.pelle.sak.api.model.LoginRequest
import io.pelle.sak.api.model.TodoDoneRequest
import io.pelle.sak.api.model.TodoDueDateRequest
import io.pelle.sak.api.model.TodoItemResponse
import io.pelle.sak.api.model.TodoListRequest
import io.pelle.sak.api.model.TodoListResponse
import io.pelle.sak.api.model.TodoNewItemRequest
import io.pelle.sak.api.model.TodoPriorityRequest
import io.pelle.sak.api.model.TodoPurgeRequest
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.core.lists.ListsApi
import io.pelle.sak.todo.Priority
import io.pelle.sak.todo.R
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

// the todo api of one deployment on top of SakApi: the lists, their tasks and the stream of their
// changes
class TodoApi(
    client: HttpClient,
    url: String,
    token: String? = null,
    languages: String? = null,
) : SakApi(client, url, token, languages, LOGIN_CLIENT),
    ListsApi<TodoListResponse> {
    // in the order the backend gives them: by name
    override suspend fun lists(): List<TodoListResponse> = all("$url/todo/lists", R.string.todo_lists_not_loaded)

    override suspend fun createList(
        name: String,
        shared: Boolean,
        icon: String?,
    ): TodoListResponse =
        client
            .post("$url/todo/lists") {
                session()
                contentType(ContentType.Application.Json)
                setBody(TodoListRequest(name, shared, icon))
            }.answer(R.string.todo_error_list_not_created)

    override suspend fun updateList(
        id: Int,
        name: String,
        shared: Boolean,
        icon: String?,
    ): TodoListResponse =
        client
            .put("$url/todo/lists/$id") {
                session()
                contentType(ContentType.Application.Json)
                setBody(TodoListRequest(name, shared, icon))
            }.answer(R.string.todo_form_not_saved)

    // only a list of the user's own, with its tasks, the last one too (LIST-1)
    override suspend fun deleteList(id: Int) {
        client.delete("$url/todo/lists/$id") { session() }.answer<Unit>(R.string.todo_form_not_deleted)
    }

    // a done one in its place (TODO-3), in TASK_ORDER (TODO-5)
    suspend fun items(listId: Int): List<TodoItemResponse> = all("$url/todo/lists/$listId/items", R.string.todo_list_items_not_loaded)

    // normal, the default of the backend, is not sent (TODO-4, TODO-5)
    suspend fun addItem(
        listId: Int,
        title: String,
        dueDate: LocalDate? = null,
        priority: Priority = Priority.NORMAL,
    ): TodoItemResponse =
        client
            .post("$url/todo/items") {
                session()
                contentType(ContentType.Application.Json)
                setBody(TodoNewItemRequest(listId = listId, title = title, dueDate = dueDate?.toString(), priority = priority.takeIf { it != Priority.NORMAL }?.value))
            }.answer(R.string.todo_list_not_added)

    // done or open again (TODO-3)
    suspend fun setDone(
        id: Int,
        done: Boolean,
    ): TodoItemResponse =
        client
            .put("$url/todo/items/$id/done") {
                session()
                contentType(ContentType.Application.Json)
                setBody(TodoDoneRequest(done))
            }.answer(R.string.todo_list_not_done)

    // open or done (TODO-5)
    suspend fun setPriority(
        id: Int,
        priority: Priority,
    ): TodoItemResponse =
        client
            .put("$url/todo/items/$id/priority") {
                session()
                contentType(ContentType.Application.Json)
                setBody(TodoPriorityRequest(priority.value))
            }.answer(R.string.todo_list_priority_not_set)

    // null takes the due date away (TODO-4)
    suspend fun setDueDate(
        id: Int,
        dueDate: LocalDate?,
    ): TodoItemResponse =
        client
            .put("$url/todo/items/$id/due-date") {
                session()
                contentType(ContentType.Application.Json)
                setBody(TodoDueDateRequest(dueDate?.toString()))
            }.answer(R.string.todo_list_due_date_not_saved)

    // deletes the done tasks of itemIds, the ones the user saw; one open again or gone stays (TODO-6);
    // 204, no body
    suspend fun purge(
        listId: Int,
        itemIds: List<Int>,
    ) {
        client
            .post("$url/todo/lists/$listId/purge") {
                session()
                contentType(ContentType.Application.Json)
                setBody(TodoPurgeRequest(itemIds))
            }.answer<Unit>(R.string.todo_list_not_purged)
    }

    // the ids of the lists the user sees as they change (LIST-4, SakApi.listChanges)
    override fun changes(opened: suspend () -> Unit): Flow<Int> = listChanges("$url/todo/changes", opened)

    companion object {
        // the signin page of the web app hands a login link on to this app (LoginClient of sak-auth)
        val LOGIN_CLIENT = LoginRequest.Client.TODO_ANDROID
    }
}
