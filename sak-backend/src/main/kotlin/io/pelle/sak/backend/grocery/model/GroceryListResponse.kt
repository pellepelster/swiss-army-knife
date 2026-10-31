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
    // the default list of its owner, which keeps its name and is never deleted (LIST-1)
    val default: Boolean,
    val createdAt: String,
    // missing until it is changed
    val updatedAt: String?,
)
