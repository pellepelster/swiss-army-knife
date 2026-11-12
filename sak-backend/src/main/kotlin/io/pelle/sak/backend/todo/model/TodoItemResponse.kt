package io.pelle.sak.backend.todo.model

import kotlinx.serialization.Serializable

// a task, open or done (TODO-3)
@Serializable
data class TodoItemResponse(
    val id: Int,
    val listId: Int,
    val title: String,
    val createdAt: String,
    // missing until it is changed
    val updatedAt: String?,
    // the day it is to be done by, yyyy-mm-dd; missing for no day (TODO-4)
    val dueDate: String? = null,
    // high, normal or low (TODO-5)
    val priority: TodoPriorityValue,
    // when it was done, missing while it is open (TODO-3)
    val doneAt: String? = null,
)
