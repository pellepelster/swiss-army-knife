package io.pelle.sak.settings

import io.pelle.sak.settings.model.internal.SettingsGroup

// a group as it is registered at SettingService: the group, its check across values and the values of
// its read-only settings. A part describes its group with it; the backend registers every group in one
// place, in the order of the settings page (SET-6)
data class SettingsRegistration(
    val group: SettingsGroup,
    val check: SettingsCheck = SettingsCheck.NONE,
    val readOnly: ReadOnlyValues = ReadOnlyValues(),
)
