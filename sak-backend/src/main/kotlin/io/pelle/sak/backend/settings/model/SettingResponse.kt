package io.pelle.sak.backend.settings.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class SettingResponse(
    // the key of the setting in its scope
    val name: String,
    val label: String,
    val description: String,
    val type: SettingTypeResponse,
    // the value while none is set
    val default: JsonPrimitive,
    // the most characters of a text, null for the other types
    val maxLength: Int?,
    // how many characters of a value the api shows, the rest is masked; null shows the whole value
    val mask: Int?,
    // a value given by the backend, e.g. from its configuration, which can not be changed
    val readOnly: Boolean,
    // the boolean setting that must be on for this one to count, null when there is none
    val requires: SettingRequirementResponse?,
)
