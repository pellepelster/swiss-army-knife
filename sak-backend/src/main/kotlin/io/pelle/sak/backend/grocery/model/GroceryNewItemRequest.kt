package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

// a new item, always on a list (LIST-1); the values as in GroceryItemRequest
@Serializable
data class GroceryNewItemRequest(
    val listId: Int,
    val name: String,
    // positive, at most 3 decimal places
    val amount: Double? = null,
    // free text, only with an amount
    val unit: String? = null,
    // one of the categories, none when missing
    val category: String? = null,
)
