package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

// a name of the items of the list, with the quantity and the category of its latest item; the id is that item (GROCERY-11)
@Serializable
data class GrocerySuggestionResponse(
    val id: Int,
    val name: String,
    val amount: Double?,
    val unit: String?,
    // a category of the list, null for none
    val categoryId: Int?,
)
