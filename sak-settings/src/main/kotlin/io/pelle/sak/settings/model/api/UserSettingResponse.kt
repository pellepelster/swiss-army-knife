package io.pelle.sak.settings.model.api

import io.pelle.sak.settings.model.api.SettingTypeResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

// a setting of the user signed in, with its value; what the dialog of the user settings is built from
@Serializable
data class UserSettingResponse(
    // the key of the setting in its scope
    val name: String,
    val label: String,
    // what it does, e.g. where the data goes
    val description: String,
    val type: SettingTypeResponse,
    // the value while the user set none
    val default: JsonPrimitive,
    // the value of the user, the default while they set none
    val value: JsonPrimitive,
    // whether it counts now; false e.g. while no AI is set up, the value stays
    val available: Boolean,
    // why it does not count, null while it does
    val unavailableReason: String?,
)
