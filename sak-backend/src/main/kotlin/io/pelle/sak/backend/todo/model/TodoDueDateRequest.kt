package io.pelle.sak.backend.todo.model

import kotlinx.serialization.Serializable

// the day a task is to be done by, yyyy-mm-dd; missing, null or empty takes it away (TODO-4)
@Serializable
data class TodoDueDateRequest(
    val dueDate: String? = null,
)
