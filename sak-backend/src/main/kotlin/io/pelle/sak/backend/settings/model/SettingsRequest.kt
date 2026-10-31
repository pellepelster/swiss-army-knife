package io.pelle.sak.backend.settings.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// the values to set in one scope, by the names of its settings, e.g. {"enabled": true}; the
// settings not named keep their values
@Serializable
@JvmInline
value class SettingsRequest(
    val settings: Map<String, JsonElement>,
)
