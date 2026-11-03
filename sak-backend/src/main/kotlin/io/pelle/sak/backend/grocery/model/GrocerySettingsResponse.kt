package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

// what the grocery lists may do for the signed in user, whatever their role (GROCERY-16)
@Serializable
data class GrocerySettingsResponse(
    // whether the items in a photo can be discovered now: the AI features turned on by an admin and an
    // AI provider set up
    val discoverItems: Boolean,
)
