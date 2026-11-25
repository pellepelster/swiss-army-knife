package io.pelle.sak.settings.user

import io.pelle.sak.core.TextKey
import kotlinx.serialization.json.JsonPrimitive

// a setting of a user with its value, as the api shows it
data class UserSettingValue(
    val setting: UserSetting<*>,
    val value: JsonPrimitive,
    // null while the setting counts
    val unavailable: TextKey?,
)
