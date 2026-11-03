package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

// the items the AI read in a photo, nothing added yet: the user confirms them (GROCERY-16)
@Serializable
data class GroceryDiscoveredResponse(
    val items: List<GroceryDiscoveredItem>,
)

// an item as it would be added (GroceryNewItemRequest), within its limits
@Serializable
data class GroceryDiscoveredItem(
    val name: String,
    // positive, at most 3 decimal places
    val amount: Double? = null,
    // only with an amount
    val unit: String? = null,
)
