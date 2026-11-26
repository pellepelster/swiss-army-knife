package io.pelle.sak.settings

import io.pelle.sak.settings.model.SettingDefinition

// the values of the read-only settings of a group, each asked for on every read, e.g. from the
// configuration; given when the group is registered (SettingService.register)
class ReadOnlyValues {
    private val values = mutableMapOf<SettingDefinition<*>, () -> Any>()

    fun <T : Any> with(
        setting: SettingDefinition<T>,
        value: () -> T,
    ) = apply {
        require(setting.readOnly) { "the setting ${setting.name} is not read-only" }
        values[setting] = value
    }

    fun has(setting: SettingDefinition<*>) = setting in values

    // with(setting, value) took a T for the setting
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> value(setting: SettingDefinition<T>): T = values.getValue(setting)() as T
}
