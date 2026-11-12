package io.pelle.sak.backend.todo.model

import kotlinx.serialization.Serializable

// a new task, always on a list (LIST-1)
@Serializable
data class TodoNewItemRequest(
    val listId: Int,
    val title: String,
    // the day it is to be done by, yyyy-mm-dd; missing or empty for no day (TODO-4)
    val dueDate: String? = null,
    // high, normal or low; missing for normal (TODO-5)
    val priority: String? = null,
)
