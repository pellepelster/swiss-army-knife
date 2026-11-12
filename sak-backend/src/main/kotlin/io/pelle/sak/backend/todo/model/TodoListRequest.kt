package io.pelle.sak.backend.todo.model

import kotlinx.serialization.Serializable

@Serializable
data class TodoListRequest(
    val name: String,
    // of all users (LIST-2)
    val shared: Boolean = false,
    // one emoji, missing or empty for none
    val icon: String? = null,
)
