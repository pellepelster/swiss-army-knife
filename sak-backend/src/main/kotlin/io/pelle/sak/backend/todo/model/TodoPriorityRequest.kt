package io.pelle.sak.backend.todo.model

import kotlinx.serialization.Serializable

// the priority of a task: high, normal or low (TODO-5)
@Serializable
data class TodoPriorityRequest(
    val priority: String,
)
