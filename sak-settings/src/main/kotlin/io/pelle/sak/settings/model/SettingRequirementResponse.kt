package io.pelle.sak.settings.model

import kotlinx.serialization.Serializable

// a boolean setting that must be on, by the names of its scope and of itself
@Serializable
data class SettingRequirementResponse(
    val scope: String,
    val setting: String,
)
