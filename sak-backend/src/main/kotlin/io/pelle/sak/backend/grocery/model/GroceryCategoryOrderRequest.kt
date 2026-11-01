package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

// the ids of all categories of a list, each once, in their new order (GROCERY-13)
@Serializable
data class GroceryCategoryOrderRequest(
    val ids: List<Int>,
)
