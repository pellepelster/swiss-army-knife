package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

@Serializable
data class GroceryListRequest(
    val name: String,
    // of all users (LIST-2)
    val shared: Boolean = false,
)
