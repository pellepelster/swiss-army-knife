package io.pelle.sak.settings

import io.pelle.sak.settings.model.internal.SettingLevel
import io.pelle.sak.settings.model.internal.SettingScope
import io.pelle.sak.settings.model.internal.SettingsGroup

// the groups of the settings the api serves, with their checks and the values of their read-only
// settings; nothing of values. Filled once while the backend starts, in the order of the settings page,
// only read afterwards (SET-7)
class SettingsRegistry {
    // by scope, in the order of registration
    private val registered = linkedMapOf<SettingScope, SettingsRegistration>()

    // a group the settings api serves; what it requires is registered before, so requirements never go
    // round in a circle
    fun register(registration: SettingsRegistration) {
        val (group, check, readOnly) = registration
        require(group.name !in registered) { "the group ${group.name} is registered twice" }
        // a check sees the values of the instance only, a group of users would never be checked (SET-8)
        require(check === SettingsCheck.NONE || group.of(SettingLevel.USER).isEmpty()) {
            "the check of the group ${group.name} sees the settings of the instance only, the group has settings of the users"
        }
        group.settings.filter { it.readOnly }.forEach {
            require(readOnly.has(it)) { "the read-only setting ${it.name} of the group ${group.name} has no value" }
        }
        group.settings.mapNotNull { it.requires }.forEach { required ->
            require(registered[required.scope]?.group?.settings?.any { it === required } == true) {
                "the setting ${required.scope}.${required.name} a setting of ${group.name} requires is not registered"
            }
        }
        registered[group.name] = registration
    }

    // every group, in the order of the settings page
    val groups: List<SettingsGroup> get() = registered.values.map { it.group }

    // the groups with settings of the level: of the instance for the admins, of the users for each user
    fun groups(level: SettingLevel): List<SettingsGroup> = groups.filter { it.of(level).isNotEmpty() }

    // the group of the name with settings of the level, null when there is none
    fun group(
        name: String,
        level: SettingLevel,
    ): SettingsGroup? = groups(level).firstOrNull { it.name.value == name }

    // the registration of a scope, null for a setting of no group
    internal fun registrationOf(scope: SettingScope): SettingsRegistration? = registered[scope]
}
