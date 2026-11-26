package io.pelle.sak.settings.model.internal

import io.pelle.sak.core.TextKey

// the settings that belong together, e.g. those of the whole system
data class SettingsGroup(
    val name: SettingScope,
    val label: TextKey,
    // what the settings page of the admins says of it; a scope of user settings only needs none
    val description: TextKey?,
    // of the instance and of the users, apart by their level
    val settings: List<SettingDefinition<*>>,
    // the setting of the scope that turns it on, one of its settings; while it is off, the other
    // settings of the scope are null to the code and greyed out on the settings page
    val enabledBy: BooleanSetting? = null,
) {
    init {
        require(settings.map { it.name }.toSet().size == settings.size) { "the settings of the scope $name have the same name twice" }
        settings.forEach { require(it.scope == name) { "the setting ${it.name} of the scope ${it.scope} is not one of the scope $name" } }
        require(enabledBy == null || settings.any { it === enabledBy }) { "the setting turning the scope $name on is not one of its settings" }
        require(enabledBy?.readOnly != true) { "the setting turning the scope $name on is read-only" }
        require(enabledBy == null || enabledBy.level == SettingLevel.INSTANCE) { "the setting turning the scope $name on is one of a user" }
        require(description != null || settings.none { it.level == SettingLevel.INSTANCE }) { "the scope $name of the admins has no description" }
    }

    fun setting(name: String) = settings.firstOrNull { it.name.value == name }

    // the settings of the level
    fun of(level: SettingLevel) = settings.filter { it.level == level }
}
