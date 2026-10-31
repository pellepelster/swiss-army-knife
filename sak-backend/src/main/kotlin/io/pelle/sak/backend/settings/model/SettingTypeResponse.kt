package io.pelle.sak.backend.settings.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// the type of the values of a setting, the settings page chooses its input by it
@Serializable
enum class SettingTypeResponse {
    @SerialName("boolean")
    BOOLEAN,

    @SerialName("text")
    TEXT,
}
