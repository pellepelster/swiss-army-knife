package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

// the name and the icon of a category of a list (GROCERY-13)
@Serializable
data class GroceryCategoryRequest(
    val name: String,
    // one emoji, missing or empty for none
    val icon: String? = null,
)
