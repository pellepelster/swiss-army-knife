package io.pelle.sak.settings.user

import io.pelle.sak.core.Text
import io.pelle.sak.core.TextKey
import io.pelle.sak.settings.SettingDefinition
import io.pelle.sak.settings.SettingService

// a setting each user sets for themselves, in the scope of an app or of the system; it is stored with
// the user (SettingService), the default while they set none
class UserSetting<T : Any>(
    val definition: SettingDefinition<T>,
    // the description in full, e.g. with the models of the AI it names
    val description: () -> Text = { Text(definition.description) },
    // why the setting does nothing now, e.g. no AI set up; null while it counts
    val unavailable: () -> TextKey? = { null },
) {
    init {
        require(!definition.readOnly) { "the user setting ${definition.name} is read-only" }
        require(definition.requires == null) { "the user setting ${definition.name} requires a setting of the instance" }
    }

    val name get() = definition.name
}
