package io.pelle.sak.backend.settings.model

import kotlinx.serialization.Serializable

@Serializable
data class SettingScopeResponse(
    // the key of the scope in the values
    val name: String,
    val label: String,
    val description: String,
    val settings: List<SettingResponse>,
    // the name of the boolean setting that turns the scope on, null when it is always on
    val enabledBy: String?,
)
