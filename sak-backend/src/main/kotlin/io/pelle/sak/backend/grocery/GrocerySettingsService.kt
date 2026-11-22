package io.pelle.sak.backend.grocery

import io.pelle.sak.backend.openrouter.AiSettingsService
import io.pelle.sak.backend.settings.SettingScope
import io.pelle.sak.backend.settings.SettingScopeName
import io.pelle.sak.backend.settings.SettingService

// the settings of the grocery lists, the only settings they read: whether the grocery lists may send a
// photo to the AI
class GrocerySettingsService(
    settings: SettingService,
) : AiSettingsService(settings, SCOPE, AI) {
    companion object {
        val AI = aiSetting(GroceryTexts.AI_LABEL, GroceryTexts.AI_DESCRIPTION)

        val SCOPE = SettingScope(SettingScopeName("grocery"), GroceryTexts.SCOPE_LABEL, GroceryTexts.SCOPE_DESCRIPTION, listOf(AI))
    }
}
