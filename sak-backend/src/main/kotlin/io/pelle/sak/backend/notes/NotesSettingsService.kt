package io.pelle.sak.backend.notes

import io.pelle.sak.backend.openrouter.OpenRouterManager
import io.pelle.sak.backend.settings.BooleanSetting
import io.pelle.sak.backend.settings.Requirement
import io.pelle.sak.backend.settings.SettingName
import io.pelle.sak.backend.settings.SettingScope
import io.pelle.sak.backend.settings.SettingScopeName
import io.pelle.sak.backend.settings.SettingService
import io.pelle.sak.backend.settings.StoredSettingsService

// the settings of the notes app, the only settings it reads
class NotesSettingsService(
    settings: SettingService,
) : StoredSettingsService(SCOPE, settings) {
    // whether the notes may send a photo or a recording to the AI: turned on here and for the
    // deployment (its requirement); without an api key nothing is sent either way
    fun aiEnabled(): Boolean = get(AI) == true

    companion object {
        // off until an admin turns it on, also with the AI of the deployment turned on
        val AI =
            BooleanSetting(
                SettingName("ai"),
                "AI features",
                "Lets the notes discover the text in photos and recordings with the AI of the deployment.",
                default = false,
                requires = Requirement(OpenRouterManager.SCOPE, OpenRouterManager.ENABLED),
            )

        val SCOPE = SettingScope(SettingScopeName("notes"), "Notes", "The settings of the notes.", listOf(AI))
    }
}
