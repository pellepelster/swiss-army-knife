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
    // how many items are to buy on it
    val items: Int,
    // one emoji, missing for none: the apps show the first letter of the name
    val icon: String?,
    val createdAt: String,
    // missing until it is changed
    val updatedAt: String?,
)
