package io.pelle.sak.backend.todo.model

import kotlinx.serialization.Serializable

// a task done, or open again (TODO-3)
@Serializable
data class TodoDoneRequest(
    val done: Boolean,
)
