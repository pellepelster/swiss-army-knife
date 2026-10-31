package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

@Serializable
data class GroceryItemRequest(
    // the list of a new item, the default list of the user when it is missing; a changed item
    // stays on its list
    val listId: Int? = null,
    val name: String,
    // positive, at most 3 decimal places
    val amount: Double? = null,
    // free text, only with an amount
    val unit: String? = null,
    // one of the categories, none when missing
    val category: String? = null,
    // bought
    val checked: Boolean = false,
)
