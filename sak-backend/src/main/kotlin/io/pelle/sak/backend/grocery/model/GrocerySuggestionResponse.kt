package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

// an item the user added before, with the quantity and the category used last
@Serializable
data class GrocerySuggestionResponse(
    val id: Int,
    val name: String,
    val amount: Double?,
    val unit: String?,
    val category: String?,
)
