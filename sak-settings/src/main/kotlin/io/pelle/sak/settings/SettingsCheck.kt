package io.pelle.sak.settings

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.settings.model.SettingDefinition

// what is wrong with values of the instance of a group that are each valid, e.g. together or for a
// service outside, such as an api key the provider refuses; given when the group is registered
fun interface SettingsCheck {
    fun check(values: ChangedValues): List<ErrorMessage>

    companion object {
        val NONE = SettingsCheck { emptyList() }
    }
}

// the values of a group of the instance as a change would leave them
interface ChangedValues {
    // the new value, null when the change leaves the setting as it is
    fun <T : Any> new(setting: SettingDefinition<T>): T?

    // the stored value, as before the change
    fun <T : Any> stored(setting: SettingDefinition<T>): T

    // the value after the change: the new one, else the stored one
    operator fun <T : Any> get(setting: SettingDefinition<T>): T = new(setting) ?: stored(setting)
}
