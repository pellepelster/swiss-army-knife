package io.pelle.sak.settings.model.api

import kotlinx.serialization.Serializable

// every setting of the user signed in, by scope; a user has few, so there is no page
@Serializable
data class UserSettingsResponse(
    val scopes: List<UserSettingScopeResponse>,
)
