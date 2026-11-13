package io.pelle.sak.backend.todo

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.apiTime
import io.pelle.sak.api.errors
import io.pelle.sak.api.idParameter
import io.pelle.sak.api.installApi
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
import io.pelle.sak.backend.todo.model.TodoDoneRequest
import io.pelle.sak.backend.todo.model.TodoDueDateRequest
import io.pelle.sak.backend.todo.model.TodoItemResponse
import io.pelle.sak.backend.todo.model.TodoListRequest
import io.pelle.sak.backend.todo.model.TodoListResponse
import io.pelle.sak.backend.todo.model.TodoNewItemRequest
import io.pelle.sak.backend.todo.model.TodoPriorityRequest
import io.pelle.sak.backend.todo.model.TodoPriorityValue
import io.pelle.sak.backend.todo.model.TodoPurgeRequest
import io.pelle.sak.auth.owner
import io.pelle.sak.backend.lists.listChanges
import io.pelle.sak.backend.lists.respondList
import io.pelle.sak.model.todo.TodoItem
import io.pelle.sak.model.lists.SakList
import io.pelle.sak.model.lists.ListDelete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TODO_TAG = "todo"

private fun SakList.response(user: String): TodoListResponse = TodoListResponse(id, name, shared, owner == user, items, icon, createdAt.apiTime(), updatedAt?.apiTime())

private fun TodoItem.response() = TodoItemResponse(id, listId, title, createdAt.apiTime(), updatedAt?.apiTime(), dueDate?.toString(), TodoPriorityValue.of(priority), doneAt?.apiTime())

private suspend fun ApplicationCall.respondItem(result: TodoItemResult) =
    when (result) {
        is TodoItemResult.Saved -> respond(result.item.response())
        is TodoItemResult.Invalid -> respondErrors(result.errors)
        TodoItemResult.NotFound -> respond(HttpStatusCode.NotFound)
    }

// the todo lists of the signed in user and the shared ones of the others with their tasks, meant to
// be mounted behind the authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.todo(todoService: TodoService) {
    installApi()

    route("/lists") {
        get {
            val page = call.pageRequest() ?: return@get
            val lists = withContext(Dispatchers.IO) { todoService.lists(call.owner) }
            call.respond(page.of(page.of(lists.map { it.response(call.owner) })))
        }.describe {
            tag(TODO_TAG)
            summary = "The todo lists"
            description = "The lists of the signed in user and the shared lists of the others, by name."
            parameters { pagination() }
            responses {
                HttpStatusCode.OK {
                    description = "A page of the lists"
                    schema = jsonSchema<PageResponse<TodoListResponse>>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid page")
            }
        }

        post {
            val request = call.receive<TodoListRequest>()
            call.respondList(withContext(Dispatchers.IO) { todoService.createList(call.owner, request.name, request.shared, request.icon) }, TodoTexts.LISTS, created = true, SakList::response)
        }.describe {
            tag(TODO_TAG)
            summary = "Create a todo list"
            requestBody {
                required = true
                schema = jsonSchema<TodoListRequest>()
            }
            responses {
                HttpStatusCode.Created {
                    description = "The new list"
                    schema = jsonSchema<TodoListResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid name or icon, or a malformed request")
                errors(HttpStatusCode.Conflict, "The user has a list of the name")
            }
        }

        get("/{id}") {
            val id = call.idParameter() ?: return@get
            when (val list = withContext(Dispatchers.IO) { todoService.list(call.owner, id) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(list.response(call.owner))
            }
        }.describe {
            tag(TODO_TAG)
            summary = "A todo list"
            description = "A list of the signed in user or a shared list of another user."
            parameters { listId() }
            responses {
                HttpStatusCode.OK {
                    description = "The list"
                    schema = jsonSchema<TodoListResponse>()
                }
                errors(HttpStatusCode.NotFound, "The user sees no such list")
            }
        }

        put("/{id}") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<TodoListRequest>()
            call.respondList(withContext(Dispatchers.IO) { todoService.updateList(call.owner, id, request.name, request.shared, request.icon) }, TodoTexts.LISTS, response = SakList::response)
        }.describe {
            tag(TODO_TAG)
            summary = "Rename or share a todo list"
            description =
                "Every user who sees a list changes its name and its icon, a shared list of another user too (LIST-2); only its " +
                "owner shares or unshares it."
            parameters { listId() }
            requestBody {
                required = true
                schema = jsonSchema<TodoListRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The changed list"
                    schema = jsonSchema<TodoListResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid name or icon, or a malformed request")
                errors(HttpStatusCode.Forbidden, "The user is not the owner and would rename, share or unshare the list")
                errors(HttpStatusCode.NotFound, "The user sees no such list")
                errors(HttpStatusCode.Conflict, "Another list of the owner has the name")
            }
        }

        delete("/{id}") {
            val id = call.idParameter() ?: return@delete
            when (withContext(Dispatchers.IO) { todoService.deleteList(call.owner, id) }) {
                ListDelete.Deleted -> call.respond(HttpStatusCode.NoContent)
                ListDelete.NotFound -> call.respond(HttpStatusCode.NotFound)
            }
        }.describe {
            tag(TODO_TAG)
            summary = "Delete a todo list"
            description = "Its tasks go with it; only its owner deletes a list, their last one too."
            parameters { listId() }
            responses {
                HttpStatusCode.NoContent { description = "The list is gone" }
                errors(HttpStatusCode.NotFound, "The user has no such list")
            }
        }

        get("/{id}/items") {
            val id = call.idParameter() ?: return@get
            val page = call.pageRequest() ?: return@get
            when (val items = withContext(Dispatchers.IO) { todoService.items(call.owner, id) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(page.of(page.of(items.map { it.response() })))
            }
        }.describe {
            tag(TODO_TAG)
            summary = "The tasks of a todo list"
            description =
                "Open and done alike, a done task keeps its place (TODO-3): by priority, the high ones first (TODO-5), then in " +
                "the order they were added."
            parameters {
                listId()
                pagination()
            }
            responses {
                HttpStatusCode.OK {
                    description = "A page of the tasks"
                    schema = jsonSchema<PageResponse<TodoItemResponse>>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid page")
                errors(HttpStatusCode.NotFound, "The user sees no such list")
            }
        }

        post("/{id}/purge") {
            val id = call.idParameter() ?: return@post
            val request = call.receive<TodoPurgeRequest>()
            val purged = withContext(Dispatchers.IO) { todoService.purge(call.owner, id, request.itemIds) }
            call.respond(if (purged != null) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }.describe {
            tag(TODO_TAG)
            summary = "Purge the done tasks of a todo list"
            description =
                "The done tasks of the list the request names, the ones the user saw done, are deleted; the open ones stay, and " +
                "so does one done meanwhile (TODO-6). Every user who sees the list purges it (LIST-2)."
            parameters { listId() }
            requestBody {
                required = true
                schema = jsonSchema<TodoPurgeRequest>()
            }
            responses {
                HttpStatusCode.NoContent { description = "The done tasks are gone" }
                errors(HttpStatusCode.BadRequest, "A malformed request")
                errors(HttpStatusCode.NotFound, "The user sees no such list")
            }
        }
    }

    route("/items") {
        post {
            val request = call.receive<TodoNewItemRequest>()
            when (val result = withContext(Dispatchers.IO) { todoService.addItem(call.owner, request.listId, request.title, request.dueDate, request.priority) }) {
                is TodoNewItemResult.Saved -> call.respond(HttpStatusCode.Created, result.item.response())
                is TodoNewItemResult.Invalid -> call.respondErrors(result.errors)
                TodoNewItemResult.UnknownList -> call.respondErrors(TodoTexts.UNKNOWN_LIST, status = HttpStatusCode.UnprocessableEntity)
            }
        }.describe {
            tag(TODO_TAG)
            summary = "Add a task to a todo list"
            description = "A task names its list, one the user sees, and may have a day it is to be done by (TODO-4) and a priority (TODO-5)."
            requestBody {
                required = true
                schema = jsonSchema<TodoNewItemRequest>()
            }
            responses {
                HttpStatusCode.Created {
                    description = "The new task"
                    schema = jsonSchema<TodoItemResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid title, due date or priority, or a malformed request, e.g. without a list")
                errors(HttpStatusCode.UnprocessableEntity, "The user sees no such list")
            }
        }

        get("/{id}") {
            val id = call.idParameter() ?: return@get
            when (val item = withContext(Dispatchers.IO) { todoService.item(call.owner, id) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(item.response())
            }
        }.describe {
            tag(TODO_TAG)
            summary = "An task of a todo list"
            description = "An task of a list the user sees."
            parameters { itemId() }
            responses {
                HttpStatusCode.OK {
                    description = "The task"
                    schema = jsonSchema<TodoItemResponse>()
                }
                errors(HttpStatusCode.NotFound, "The user sees no such task")
            }
        }

        put("/{id}/priority") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<TodoPriorityRequest>()
            call.respondItem(withContext(Dispatchers.IO) { todoService.setPriority(call.owner, id, request.priority) })
        }.describe {
            tag(TODO_TAG)
            summary = "Change the priority of a task"
            description =
                "High, normal or low (TODO-5), of an open or a done task; the list shows it in its new place. Every user who sees " +
                "its list changes it (LIST-2)."
            parameters { itemId() }
            requestBody {
                required = true
                schema = jsonSchema<TodoPriorityRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The task"
                    schema = jsonSchema<TodoItemResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid priority, or a malformed request")
                errors(HttpStatusCode.NotFound, "The user sees no such task")
            }
        }

        put("/{id}/due-date") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<TodoDueDateRequest>()
            call.respondItem(withContext(Dispatchers.IO) { todoService.setDueDate(call.owner, id, request.dueDate) })
        }.describe {
            tag(TODO_TAG)
            summary = "Set or remove the due date of a task"
            description =
                "The day a task is to be done by, of an open or a done task; none takes it away (TODO-4). Every user who sees " +
                "its list changes it (LIST-2)."
            parameters { itemId() }
            requestBody {
                required = true
                schema = jsonSchema<TodoDueDateRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The task"
                    schema = jsonSchema<TodoItemResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid due date, or a malformed request")
                errors(HttpStatusCode.NotFound, "The user sees no such task")
            }
        }

        put("/{id}/done") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<TodoDoneRequest>()
            call.respondItem(withContext(Dispatchers.IO) { todoService.setDone(call.owner, id, request.done) })
        }.describe {
            tag(TODO_TAG)
            summary = "Mark a task done or open again"
            description =
                "A done task stays on its list, struck through, until the list is purged (TODO-3, TODO-6). Every user who sees " +
                "its list marks it, also one another user added (LIST-2)."
            parameters { itemId() }
            requestBody {
                required = true
                schema = jsonSchema<TodoDoneRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The task"
                    schema = jsonSchema<TodoItemResponse>()
                }
                errors(HttpStatusCode.BadRequest, "A malformed request")
                errors(HttpStatusCode.NotFound, "The user sees no such task")
            }
        }
    }

    listChanges(TODO_TAG, "todo", "tasks", todoService::changes)
}

private fun Parameters.Builder.listId() =
    path("id") {
        description = "The id of the list"
        required = true
        schema = jsonSchema<Int>()
    }

private fun Parameters.Builder.itemId() =
    path("id") {
        description = "The id of the task"
        required = true
        schema = jsonSchema<Int>()
    }
