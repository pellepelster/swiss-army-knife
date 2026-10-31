package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

@Serializable
data class GroceryListResponse(
    val id: Int,
    val name: String,
    // of all users (LIST-2)
    val shared: Boolean,
    // of the signed in user, who renames, shares and deletes it
    val mine: Boolean,
    val createdAt: String,
    // missing until it is changed
    val updatedAt: String?,
)
