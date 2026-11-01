package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

// the values of an item that is changed, it stays on its list; a new one is a GroceryNewItemRequest
@Serializable
data class GroceryItemRequest(
    val name: String,
    // positive, at most 3 decimal places
    val amount: Double? = null,
    // free text, only with an amount
    val unit: String? = null,
    // one of the categories of the list, none when missing: the item loses its category (GROCERY-13);
    // a changed one goes onto the list's suggestion of the name
    val categoryId: Int? = null,
)
