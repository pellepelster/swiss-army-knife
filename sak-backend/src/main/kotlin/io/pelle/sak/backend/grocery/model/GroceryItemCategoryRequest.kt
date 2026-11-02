package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

// the category of an item: one of its list, null or missing for none (GROCERY-13)
@Serializable
data class GroceryItemCategoryRequest(
    val categoryId: Int? = null,
)
