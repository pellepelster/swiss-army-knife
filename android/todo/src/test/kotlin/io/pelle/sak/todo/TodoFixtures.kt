package io.pelle.sak.todo

import io.pelle.sak.api.model.TodoItemResponse
import io.pelle.sak.api.model.TodoListResponse
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.json
import io.pelle.sak.todo.api.TodoApi

// what the fake backend answers for the todo app, of the generated classes of the api (testing,
// Fixtures.kt), for the tests of the model, the rotations and the screenshots

const val CREATED = "2026-11-10T08:00:00.000Z"

// when the tasks done were done
const val DONE = "2026-11-11T08:00:00Z"

// the todo api of the fake backend, what the model makes for a url and a session
fun FakeBackend.api(
    url: String,
    token: String?,
    languages: String? = null,
) = TodoApi(client, url, token, languages)

fun list(
    id: Int,
    name: String,
    shared: Boolean = false,
    mine: Boolean = true,
    icon: String? = null,
    items: Int = 0,
) = json(TodoListResponse(id, name, shared, mine, items, CREATED, icon))

// a task open, done with doneAt
fun item(
    id: Int,
    listId: Int,
    title: String,
    dueDate: String? = null,
    priority: TodoItemResponse.Priority = TodoItemResponse.Priority.NORMAL,
    doneAt: String? = null,
) = json(TodoItemResponse(id, listId, title, CREATED, priority, dueDate = dueDate, doneAt = doneAt))
