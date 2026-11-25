package io.pelle.sak.settings.user.model

import kotlinx.serialization.Serializable

// the settings of the user signed in in one scope, e.g. notes
@Serializable
data class UserSettingScopeResponse(
    // the key of the scope, e.g. notes
    val name: String,
    val label: String,
    val settings: List<UserSettingResponse>,
)
