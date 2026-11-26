package io.pelle.sak.backend.grocery.model.api

import kotlinx.serialization.Serializable

// an item the AI read in a photo, as it would be added (GroceryNewItemRequest), within its limits
@Serializable
data class GroceryDiscoveredItemResponse(
    val name: String,
    // positive, at most 3 decimal places
    val amount: Double? = null,
    // only with an amount
    val unit: String? = null,
)
