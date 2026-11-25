package io.pelle.sak.settings.user

import io.pelle.sak.core.TextKey
import io.pelle.sak.settings.SettingScopeName
import io.pelle.sak.settings.settings

// the settings of the users in one scope, e.g. notes
class UserSettingScope(
    val name: SettingScopeName,
    val label: TextKey,
    val settings: List<UserSetting<*>>,
) {
    init {
        require(settings.map { it.name }.toSet().size == settings.size) { "the user settings of the scope $name have the same name twice" }
    }

    fun setting(name: String) = settings.firstOrNull { it.name.value == name }
}
