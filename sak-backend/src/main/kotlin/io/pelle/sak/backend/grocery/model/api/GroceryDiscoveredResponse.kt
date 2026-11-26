package io.pelle.sak.backend.grocery.model.api

import kotlinx.serialization.Serializable

// the items the AI read in a photo, nothing added yet: the user confirms them (GROCERY-16)
@Serializable
data class GroceryDiscoveredResponse(
    val items: List<GroceryDiscoveredItemResponse>,
)
