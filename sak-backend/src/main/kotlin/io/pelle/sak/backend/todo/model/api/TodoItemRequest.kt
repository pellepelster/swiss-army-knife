package io.pelle.sak.backend.todo.model.api

import kotlinx.serialization.Serializable

// what a change of a task sets: its title; its due date, its priority and whether it is done have
// requests of their own
@Serializable
data class TodoItemRequest(
    val title: String,
)
