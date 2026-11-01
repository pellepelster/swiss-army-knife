package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

// a category of a list (GROCERY-13); a list answers them in the order its groups show
@Serializable
data class GroceryCategoryResponse(
    val id: Int,
    val name: String,
    // one emoji, missing for none
    val icon: String?,
)
