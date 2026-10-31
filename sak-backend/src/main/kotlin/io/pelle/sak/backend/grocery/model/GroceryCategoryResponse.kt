package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

// a category of the items, the categories are in the order a list shows them
@Serializable
data class GroceryCategoryResponse(
    val name: String,
    val label: String,
)
