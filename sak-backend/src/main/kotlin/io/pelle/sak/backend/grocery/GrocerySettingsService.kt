package io.pelle.sak.backend.grocery

import io.pelle.sak.backend.openrouter.OpenRouterManager
import io.pelle.sak.backend.settings.BooleanSetting
import io.pelle.sak.backend.settings.Requirement
import io.pelle.sak.backend.settings.SettingName
import io.pelle.sak.backend.settings.SettingScope
import io.pelle.sak.backend.settings.SettingScopeName
import io.pelle.sak.backend.settings.SettingService
import io.pelle.sak.backend.settings.StoredSettingsService

// the settings of the grocery lists, the only settings they read
class GrocerySettingsService(
    settings: SettingService,
) : StoredSettingsService(SCOPE, settings) {
    // whether the grocery lists may send a photo to the AI: turned on here and for the deployment (its
    // requirement); without an api key nothing is sent either way
    fun aiEnabled(): Boolean = get(AI) == true

    companion object {
        // off until an admin turns it on, also with the AI of the deployment turned on
        val AI =
            BooleanSetting(
                SettingName("ai"),
                GroceryTexts.AI_LABEL,
                GroceryTexts.AI_DESCRIPTION,
                default = false,
                requires = Requirement(OpenRouterManager.SCOPE, OpenRouterManager.ENABLED),
            )

        val SCOPE = SettingScope(SettingScopeName("grocery"), GroceryTexts.SCOPE_LABEL, GroceryTexts.SCOPE_DESCRIPTION, listOf(AI))
    }
}
