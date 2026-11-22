package io.pelle.sak.backend.openrouter

import io.pelle.sak.api.TextKey
import io.pelle.sak.backend.settings.BooleanSetting
import io.pelle.sak.backend.settings.Requirement
import io.pelle.sak.backend.settings.SettingName
import io.pelle.sak.backend.settings.SettingScope
import io.pelle.sak.backend.settings.SettingService
import io.pelle.sak.backend.settings.StoredSettingsService

// the settings of an app whose one setting lets it send data to the AI, e.g. the notes and the grocery
// lists: turned on for the app and for the deployment (its requirement); without an api key nothing is
// sent either way
open class AiSettingsService(
    settings: SettingService,
    scope: SettingScope,
    private val ai: BooleanSetting,
) : StoredSettingsService(scope, settings) {
    fun aiEnabled(): Boolean = get(ai) == true

    companion object {
        // off until an admin turns it on, also with the AI of the deployment turned on
        fun aiSetting(
            label: TextKey,
            description: TextKey,
        ) = BooleanSetting(
            SettingName("ai"),
            label,
            description,
            default = false,
            requires = Requirement(OpenRouterManager.SCOPE, OpenRouterManager.ENABLED),
        )
    }
}
